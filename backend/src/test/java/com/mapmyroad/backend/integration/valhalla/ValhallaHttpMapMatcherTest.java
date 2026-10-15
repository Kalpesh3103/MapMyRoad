package com.mapmyroad.backend.integration.valhalla;

import static com.mapmyroad.backend.integration.valhalla.TraceAttributesFixtures.fixture;
import static com.mapmyroad.backend.integration.valhalla.TraceAttributesFixtures.trace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP behavior against {@code MockRestServiceServer} (no sockets). Synthetic data only. */
class ValhallaHttpMapMatcherTest {

    private static final String BASE = "http://valhalla.test:8002";
    private static final String URL = BASE + "/trace_attributes";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ValhallaHttpMapMatcher matcher = new ValhallaHttpMapMatcher(builder.build(), BASE + "/", 2000);

    @Test
    void sendsTraceAttributesRequestAndParsesResponse() {
        AtomicReference<String> body = new AtomicReference<>();
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> body.set(((MockClientHttpRequest) request).getBodyAsString()))
                .andRespond(withSuccess(fixture("trace-attributes-matched.json"), MediaType.APPLICATION_JSON));

        List<MatchedPoint> result = matcher.match(trace(3), ValhallaCosting.MOTORCYCLE);

        server.verify();
        assertThat(result).extracting(MatchedPoint::wayId).containsExactly(111001L, 111001L, 111002L);
        JsonNode request = JsonMapper.shared().readTree(body.get());
        assertThat(request.path("costing").stringValue()).isEqualTo("motorcycle");
        assertThat(request.path("shape_match").stringValue()).isEqualTo("map_snap");
        assertThat(request.path("shape")).hasSize(3);
        assertThat(request.path("shape").get(0).path("lat").doubleValue()).isEqualTo(trace(1).get(0).lat());
        assertThat(request.path("shape").get(0).path("time").longValue())
                .isEqualTo(trace(1).get(0).time().getEpochSecond());
        assertThat(request.path("filters").path("action").stringValue()).isEqualTo("include");
        assertThat(request.path("filters").path("attributes")).hasSize(ValhallaHttpMapMatcher.ATTRIBUTES.size());
    }

    @Test
    void badRequestMeansNothingMatched() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(fixture("trace-attributes-no-match-400.json")));

        List<MatchedPoint> result = matcher.match(trace(4), ValhallaCosting.AUTO);

        assertThat(result).hasSize(4);
        assertThat(result).extracting(MatchedPoint::matched).containsOnly(false);
    }

    @Test
    void serverErrorIsUnavailable() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> matcher.match(trace(2), ValhallaCosting.AUTO))
                .isInstanceOf(ValhallaUnavailableException.class);
    }

    @Test
    void malformedBodyIsUnavailable() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"matched_points\": []}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> matcher.match(trace(2), ValhallaCosting.AUTO))
                .isInstanceOf(ValhallaUnavailableException.class);
    }

    @Test
    void connectionFailureIsUnavailable() {
        server.expect(requestTo(URL)).andRespond(withException(new ConnectException("refused")));

        assertThatThrownBy(() -> matcher.match(trace(2), ValhallaCosting.AUTO))
                .isInstanceOf(ValhallaUnavailableException.class);
    }

    @Test
    void longTraceIsSentInChunks() {
        ValhallaHttpMapMatcher small = new ValhallaHttpMapMatcher(builder.build(), BASE, 40);
        server.expect(ExpectedCount.times(3), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        List<MatchedPoint> result = small.match(trace(95), ValhallaCosting.AUTO);

        server.verify();
        assertThat(result).hasSize(95);
    }

    @Test
    void singlePointIsUnmatchedWithoutCall() {
        List<MatchedPoint> result = matcher.match(trace(1), ValhallaCosting.AUTO);

        server.verify();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).matched()).isFalse();
    }

    @Test
    void chunksLongTracesWithOverlapAndStitchesOnePointPerInput() {
        List<TracePoint> points = trace(95);
        List<Integer> chunkSizes = new ArrayList<>();
        // Each "matched" point carries its global input index as way ID, plus the chunk number in the distance.
        AtomicReference<Integer> chunkNo = new AtomicReference<>(0);
        Map<TracePoint, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < points.size(); i++) {
            indexOf.put(points.get(i), i);
        }

        List<MatchedPoint> result = ValhallaHttpMapMatcher.matchInChunks(points, 40, 10, chunk -> {
            chunkSizes.add(chunk.size());
            int no = chunkNo.getAndSet(chunkNo.get() + 1);
            return chunk.stream()
                    .map(p -> new MatchedPoint((long) indexOf.get(p), p.lat(), p.lon(), (double) no))
                    .toList();
        });

        assertThat(chunkSizes).containsExactly(40, 40, 35);
        assertThat(result).hasSize(95);
        for (int i = 0; i < result.size(); i++) {
            assertThat(result.get(i).wayId()).isEqualTo((long) i);
        }
        // Cuts are in the middle of each overlap: chunk 0 owns [0, 35), chunk 1 [35, 65), chunk 2 [65, 95).
        assertThat(result.get(34).distanceFromTraceM()).isEqualTo(0.0);
        assertThat(result.get(35).distanceFromTraceM()).isEqualTo(1.0);
        assertThat(result.get(64).distanceFromTraceM()).isEqualTo(1.0);
        assertThat(result.get(65).distanceFromTraceM()).isEqualTo(2.0);
    }

    @Test
    void shortTraceIsOneRequest() {
        List<Integer> chunkSizes = new ArrayList<>();

        ValhallaHttpMapMatcher.matchInChunks(trace(40), 40, 10, chunk -> {
            chunkSizes.add(chunk.size());
            return TraceAttributesResponseParser.allUnmatched(chunk);
        });

        assertThat(chunkSizes).containsExactly(40);
    }
}
