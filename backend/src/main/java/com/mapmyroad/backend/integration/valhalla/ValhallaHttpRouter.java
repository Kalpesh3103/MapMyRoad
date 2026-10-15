package com.mapmyroad.backend.integration.valhalla;

import com.mapmyroad.backend.config.RoutingProperties;
import com.mapmyroad.backend.config.ValhallaProperties;
import com.mapmyroad.backend.integration.valhalla.RouteResponseParser.ParsedTrip;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link ValhallaRouter} over Valhalla's HTTP API (ARCHITECTURE.md section 9.1, ADR-0003, ADR-0004).
 *
 * <p>{@code POST /route} with alternates returns the candidates. {@code /route} does not report OSM way IDs, so when
 * {@code mmr.routing.resolve-way-ids} is on each candidate shape is sent to {@code POST /trace_attributes} with
 * {@code shape_match: edge_walk} (DATABASE_SCHEMA.md A9). That second call is best effort: on any failure the
 * candidate keeps an empty way list and scoring falls back to distance-only queries.
 *
 * <p>Logs carry HTTP statuses, Valhalla error codes and exception class names only: request bodies, shapes and
 * Valhalla's error text can contain coordinates (AGENTS.md section 6).
 */
@Component
public class ValhallaHttpRouter implements ValhallaRouter {

    private static final Logger log = LoggerFactory.getLogger(ValhallaHttpRouter.class);

    private final RestClient restClient;
    private final boolean resolveWayIds;

    @Autowired
    public ValhallaHttpRouter(
            RestClient.Builder builder, ValhallaProperties valhallaProperties, RoutingProperties routingProperties) {
        this(buildClient(builder, valhallaProperties), routingProperties.resolveWayIds());
    }

    /** For tests: a client bound to a mock server. */
    ValhallaHttpRouter(RestClient restClient, boolean resolveWayIds) {
        this.restClient = restClient;
        this.resolveWayIds = resolveWayIds;
    }

    @Override
    public List<RouteOption> route(
            double originLat,
            double originLon,
            double destinationLat,
            double destinationLon,
            ValhallaCosting costing,
            int maxAlternates) {
        String body = RouteRequestBodies.route(
                originLat, originLon, destinationLat, destinationLon, costing, Math.max(0, maxAlternates));
        String response;
        try {
            response = post("/route", body);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String errorBody = e.getResponseBodyAsString();
            if (status >= 400 && status < 500 && RouteResponseParser.isNoRouteError(errorBody)) {
                log.info("Valhalla found no route (error_code {})", RouteResponseParser.errorCode(errorBody));
                return List.of();
            }
            log.warn(
                    "Valhalla route failed with HTTP {} (error_code {})",
                    status,
                    RouteResponseParser.errorCode(errorBody));
            throw new ValhallaUnavailableException("Valhalla route failed with HTTP " + status, e);
        } catch (RestClientException e) {
            log.warn("Valhalla route unreachable: {}", e.getClass().getSimpleName());
            throw new ValhallaUnavailableException("Valhalla route unreachable", e);
        }
        List<ParsedTrip> trips = RouteResponseParser.parse(response);
        List<RouteOption> options = new ArrayList<>(trips.size());
        for (ParsedTrip trip : trips) {
            List<Long> wayIds = resolveWayIds ? wayIds(trip.encodedPolyline6(), costing) : List.of();
            options.add(new RouteOption(trip.encodedPolyline6(), trip.distanceM(), trip.durationSec(), wayIds));
        }
        return options;
    }

    /** Best effort: empty on any failure, so a broken trace_attributes never fails the route request. */
    private List<Long> wayIds(String encodedPolyline6, ValhallaCosting costing) {
        try {
            return RouteResponseParser.parseWayIds(
                    post("/trace_attributes", RouteRequestBodies.traceAttributes(encodedPolyline6, costing)));
        } catch (RestClientResponseException e) {
            log.warn(
                    "Valhalla trace_attributes failed with HTTP {} (error_code {}); scoring without way IDs",
                    e.getStatusCode().value(),
                    RouteResponseParser.errorCode(e.getResponseBodyAsString()));
        } catch (RestClientException | ValhallaUnavailableException e) {
            log.warn("Valhalla trace_attributes unavailable: {}; scoring without way IDs", e.getClass().getSimpleName());
        }
        return List.of();
    }

    private String post(String path, String body) {
        return restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    /** Dedicated client with bounded timeouts so a slow Valhalla never ties up request threads. */
    private static RestClient buildClient(RestClient.Builder builder, ValhallaProperties properties) {
        // HTTP/1.1: Valhalla's prime_server speaks plain HTTP/1.1; this avoids the JDK's h2c upgrade attempt.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder.clone().baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
    }
}
