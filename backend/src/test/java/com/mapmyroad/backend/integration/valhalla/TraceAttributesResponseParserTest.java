package com.mapmyroad.backend.integration.valhalla;

import static com.mapmyroad.backend.integration.valhalla.TraceAttributesFixtures.fixture;
import static com.mapmyroad.backend.integration.valhalla.TraceAttributesFixtures.trace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class TraceAttributesResponseParserTest {

    @Test
    void parsesMatchedAndInterpolatedPoints() {
        List<TracePoint> input = trace(3);

        List<MatchedPoint> result =
                TraceAttributesResponseParser.parse(fixture("trace-attributes-matched.json"), input);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).wayId()).isEqualTo(111001L);
        assertThat(result.get(0).lat()).isEqualTo(18.520101);
        assertThat(result.get(0).lon()).isEqualTo(73.856501);
        assertThat(result.get(0).distanceFromTraceM()).isEqualTo(1.2);
        assertThat(result.get(1).wayId()).isEqualTo(111001L); // interpolated counts as matched
        assertThat(result.get(2).wayId()).isEqualTo(111002L);
    }

    @Test
    void unmatchedAndUnresolvableEdgesBecomeUnmatched() {
        List<TracePoint> input = trace(5);

        List<MatchedPoint> result =
                TraceAttributesResponseParser.parse(fixture("trace-attributes-partial.json"), input);

        assertThat(result.get(0).wayId()).isEqualTo(222001L);
        // type "unmatched": keeps the input coordinates, no distance
        assertThat(result.get(1).matched()).isFalse();
        assertThat(result.get(1).lat()).isEqualTo(input.get(1).lat());
        assertThat(result.get(1).distanceFromTraceM()).isNull();
        // edge without way_id, and a sentinel edge index
        assertThat(result.get(2).matched()).isFalse();
        assertThat(result.get(3).matched()).isFalse();
        // far snaps are still reported as matched; the snap-distance rule is applied by the matching service
        assertThat(result.get(4).wayId()).isEqualTo(222001L);
        assertThat(result.get(4).distanceFromTraceM()).isEqualTo(40.0);
    }

    @Test
    void rejectsLengthMismatchAndMalformedJson() {
        assertThatThrownBy(() -> TraceAttributesResponseParser.parse(fixture("trace-attributes-matched.json"),
                trace(4))).isInstanceOf(TraceAttributesResponseParser.MalformedResponseException.class);
        assertThatThrownBy(() -> TraceAttributesResponseParser.parse("{not json", trace(2)))
                .isInstanceOf(TraceAttributesResponseParser.MalformedResponseException.class);
        assertThatThrownBy(() -> TraceAttributesResponseParser.parse("[]", trace(2)))
                .isInstanceOf(TraceAttributesResponseParser.MalformedResponseException.class);
    }

    @Test
    void allUnmatchedKeepsInput() {
        List<TracePoint> input = trace(2);

        List<MatchedPoint> result = TraceAttributesResponseParser.allUnmatched(input);

        assertThat(result).extracting(MatchedPoint::matched).containsExactly(false, false);
        assertThat(result.get(1).lon()).isEqualTo(input.get(1).lon());
    }
}
