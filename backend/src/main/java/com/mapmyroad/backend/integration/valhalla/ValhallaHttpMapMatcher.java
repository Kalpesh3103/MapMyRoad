package com.mapmyroad.backend.integration.valhalla;

import com.mapmyroad.backend.config.MatchingProperties;
import com.mapmyroad.backend.config.ValhallaProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ValhallaMapMatcher} over HTTP: {@code POST {baseUrl}/trace_attributes} (ARCHITECTURE.md section 9,
 * DATABASE_SCHEMA.md A6).
 *
 * <p>Request: {@code shape} with {@code lat}, {@code lon} and {@code time} (epoch seconds) per point,
 * {@code shape_match: map_snap} (the trace is GPS, so Valhalla's HMM matcher is needed rather than
 * {@code edge_walk}), and an include filter so the response only carries the way ID of each edge and the per-point
 * match result. Field names follow the Valhalla Map Matching API documentation; see the class comment of
 * {@link TraceAttributesResponseParser} for the expected response.
 *
 * <p>Error handling: Valhalla answers HTTP 400 when it cannot match the trace at all (for example error 442 "no
 * suitable edges near location" or 444 "map match algorithm failed to find path"). That is a property of the
 * data, so the chunk is returned as all unmatched. Connection failures, timeouts, other HTTP errors and malformed
 * bodies mean the service is not usable right now: {@link ValhallaUnavailableException}, and the caller retries
 * later. Logs carry status codes and exception class names only, never shapes or coordinates.
 *
 * <p>Long traces are split into chunks of at most {@code maxPointsPerRequest} points that overlap by
 * {@link MatchingProperties#CHUNK_OVERLAP_POINTS}. Each point takes its result from the chunk in which it is not
 * within half the overlap of a cut, so every point is matched with some context on both sides.
 */
@Component
public class ValhallaHttpMapMatcher implements ValhallaMapMatcher {

    private static final Logger log = LoggerFactory.getLogger(ValhallaHttpMapMatcher.class);

    /** Valhalla error codes meaning "the trace could not be matched" (442 no path, 443/444 map-matching failures). */
    private static final Set<Integer> NO_MATCH_ERROR_CODES = Set.of(442, 443, 444);

    static final List<String> ATTRIBUTES = List.of(
            "edge.way_id",
            "matched.point",
            "matched.type",
            "matched.edge_index",
            "matched.distance_from_trace_point");

    private final RestClient restClient;
    private final URI traceAttributesUri;
    private final int maxPointsPerRequest;

    @Autowired
    public ValhallaHttpMapMatcher(
            RestClient.Builder restClientBuilder,
            ValhallaProperties valhallaProperties,
            MatchingProperties matchingProperties) {
        this(restClientBuilder.clone().requestFactory(requestFactory(valhallaProperties)).build(),
                valhallaProperties.baseUrl(), matchingProperties.maxPointsPerRequest());
    }

    /** For tests: a prepared client (for example bound to {@code MockRestServiceServer}). */
    ValhallaHttpMapMatcher(RestClient restClient, String baseUrl, int maxPointsPerRequest) {
        this.restClient = restClient;
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        // Built once from configuration (SECURITY.md T12: never a user-supplied URL).
        this.traceAttributesUri = URI.create(base + "/trace_attributes");
        this.maxPointsPerRequest = maxPointsPerRequest;
    }

    /** Bounded timeouts, so a slow Valhalla never stalls the job (CODING_STANDARDS.md 4.5). */
    private static JdkClientHttpRequestFactory requestFactory(ValhallaProperties properties) {
        // HTTP/1.1 like ValhallaHttpRouter: prime_server speaks plain HTTP/1.1, so skip the JDK's h2c upgrade.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return requestFactory;
    }

    @Override
    public List<MatchedPoint> match(List<TracePoint> points, ValhallaCosting costing) {
        if (points.size() < 2) {
            return TraceAttributesResponseParser.allUnmatched(points);
        }
        return matchInChunks(points, maxPointsPerRequest, MatchingProperties.CHUNK_OVERLAP_POINTS,
                chunk -> matchChunk(chunk, costing));
    }

    /**
     * Splits {@code points} into chunks of at most {@code maxPoints} that overlap by {@code overlap} points, calls
     * {@code matcher} for each and stitches the results back together, one per input point.
     */
    static List<MatchedPoint> matchInChunks(
            List<TracePoint> points,
            int maxPoints,
            int overlap,
            Function<List<TracePoint>, List<MatchedPoint>> matcher) {
        int n = points.size();
        if (n <= maxPoints) {
            return checkedResult(matcher.apply(points), n);
        }
        if (overlap < 2 || maxPoints < 2 * overlap) {
            throw new IllegalArgumentException("chunk size must be at least twice the overlap");
        }
        int step = maxPoints - overlap;
        int half = overlap / 2;
        List<MatchedPoint> result = new ArrayList<>(n);
        int start = 0;
        while (true) {
            int end = Math.min(n, start + maxPoints);
            List<MatchedPoint> chunkResult = checkedResult(matcher.apply(points.subList(start, end)), end - start);
            // Owned range: skip the first half of the overlap (owned by the previous chunk) and leave the last
            // overlap - half points to the next chunk. Consecutive owned ranges are contiguous: the next chunk
            // starts at start + step and owns from start + step + half = end - (overlap - half).
            int ownedFrom = start == 0 ? start : start + half;
            int ownedTo = end == n ? n : end - (overlap - half);
            for (int i = ownedFrom; i < ownedTo; i++) {
                result.add(chunkResult.get(i - start));
            }
            if (end == n) {
                return result;
            }
            start += step;
        }
    }

    private static List<MatchedPoint> checkedResult(List<MatchedPoint> result, int expected) {
        if (result == null || result.size() != expected) {
            throw new IllegalStateException("map matcher returned an unexpected number of points");
        }
        return result;
    }

    private List<MatchedPoint> matchChunk(List<TracePoint> chunk, ValhallaCosting costing) {
        String requestBody = JsonMapper.shared().writeValueAsString(requestBody(chunk, costing));
        String responseBody;
        try {
            responseBody = restClient.post()
                    .uri(traceAttributesUri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 400) {
                // "Nothing matched" (or a shape Valhalla refuses): not an outage. The rows are still clustered
                // by distance only (DATABASE_SCHEMA.md A6). Codes other than the known no-match ones are logged,
                // so a malformed request does not silently look like an unmatched trip.
                Integer errorCode = RouteResponseParser.errorCode(e.getResponseBodyAsString());
                if (errorCode != null && NO_MATCH_ERROR_CODES.contains(errorCode)) {
                    log.debug("Valhalla trace_attributes found no match (error_code {})", errorCode);
                } else {
                    log.warn("Valhalla trace_attributes rejected the request (HTTP 400, error_code {})", errorCode);
                }
                return TraceAttributesResponseParser.allUnmatched(chunk);
            }
            log.warn("Valhalla trace_attributes failed with HTTP {}", e.getStatusCode().value());
            throw new ValhallaUnavailableException("Valhalla trace_attributes failed", e);
        } catch (RestClientException e) {
            log.warn("Valhalla trace_attributes unreachable: {}", e.getClass().getSimpleName());
            throw new ValhallaUnavailableException("Valhalla trace_attributes unreachable", e);
        }
        try {
            return TraceAttributesResponseParser.parse(responseBody, chunk);
        } catch (TraceAttributesResponseParser.MalformedResponseException e) {
            log.warn("Valhalla trace_attributes returned an unexpected body");
            throw new ValhallaUnavailableException("Valhalla trace_attributes returned an unexpected body", e);
        }
    }

    static Map<String, Object> requestBody(List<TracePoint> chunk, ValhallaCosting costing) {
        List<Map<String, Object>> shape = new ArrayList<>(chunk.size());
        for (TracePoint point : chunk) {
            Map<String, Object> shapePoint = new LinkedHashMap<>();
            shapePoint.put("lat", point.lat());
            shapePoint.put("lon", point.lon());
            if (point.time() != null) {
                shapePoint.put("time", point.time().getEpochSecond());
            }
            shape.add(shapePoint);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("shape", shape);
        body.put("costing", costing.value());
        body.put("shape_match", "map_snap");
        body.put("filters", Map.of("attributes", ATTRIBUTES, "action", "include"));
        return body;
    }
}
