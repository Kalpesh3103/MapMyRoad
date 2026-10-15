package com.mapmyroad.backend.service.hazards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mapmyroad.backend.api.error.ApiException;
import com.mapmyroad.backend.api.error.ErrorCode;
import com.mapmyroad.backend.support.AbstractPostgisIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Feedback votes against PostGIS (API_SPEC.md 5.6, SECURITY.md T6). Synthetic coordinates only. */
class HazardFeedbackIntegrationTest extends AbstractPostgisIntegrationTest {

    private static final double LON = 73.8567;
    private static final double LAT = 18.5204;
    // About 11 m and about 220 m north of the hazard.
    private static final double NEAR_LAT = LAT + 0.0001;
    private static final double FAR_LAT = LAT + 0.002;

    @Autowired
    private HazardService hazardService;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private UUID hazardId;
    private UUID deviceA;
    private UUID deviceB;

    @BeforeEach
    void setUp() {
        for (String table : List.of("hazard_feedback", "hazard_recompute_queue", "hazard_event", "hazard")) {
            jdbc.getJdbcOperations().update("DELETE FROM " + table);
        }
        deviceA = device();
        deviceB = device();
        hazardId = UUID.randomUUID();
        // way_id NULL: the stricter device threshold (3) applies, so the hazard has 3 devices.
        jdbc.update(
                """
                INSERT INTO hazard (id, geom, severity_max, severity_avg, event_count, distinct_devices, confidence,
                                    status, first_seen_at, last_seen_at)
                VALUES (:id, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, 2, 2, 5, 3, 0.7, 'active',
                        now() - interval '5 days', now() - interval '1 day')
                """,
                new MapSqlParameterSource().addValue("id", hazardId).addValue("lon", LON).addValue("lat", LAT));
    }

    @Test
    void voteIsStoredCountedAndQueued() {
        FeedbackResult result = hazardService.feedback(vote(deviceA, FeedbackAction.CONFIRM, NEAR_LAT));

        assertThat(result.id()).isEqualTo(hazardId);
        assertThat(result.confidence()).isEqualTo(0.7);
        assertThat(result.status()).isEqualTo(HazardStatus.ACTIVE);
        assertThat(counts()).containsEntry("confirm_count", 1).containsEntry("dismiss_count", 0);
        assertThat(queued()).isEqualTo(1);
    }

    @Test
    void newVoteReplacesThePreviousOneAndCountsStayExact() {
        hazardService.feedback(vote(deviceA, FeedbackAction.CONFIRM, NEAR_LAT));
        hazardService.feedback(vote(deviceA, FeedbackAction.DISMISS, NEAR_LAT));

        assertThat(votes()).isEqualTo(1);
        assertThat(counts()).containsEntry("confirm_count", 0).containsEntry("dismiss_count", 1);

        hazardService.feedback(vote(deviceB, FeedbackAction.CONFIRM, NEAR_LAT));
        assertThat(votes()).isEqualTo(2);
        assertThat(counts()).containsEntry("confirm_count", 1).containsEntry("dismiss_count", 1);
        assertThat(queued()).isEqualTo(1);
    }

    @Test
    void farVoteIsTooFarAndNothingIsStored() {
        assertThatThrownBy(() -> hazardService.feedback(vote(deviceA, FeedbackAction.DISMISS, FAR_LAT)))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.TOO_FAR));
        assertThat(votes()).isZero();
        assertThat(queued()).isZero();
    }

    @Test
    void unknownOrSuppressedHazardIs404() {
        FeedbackCommand unknown = new FeedbackCommand(
                UUID.randomUUID(), deviceA, FeedbackAction.CONFIRM, NEAR_LAT, LON, Instant.now());
        assertThatThrownBy(() -> hazardService.feedback(unknown))
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.NOT_FOUND));

        jdbc.update(
                "UPDATE hazard SET suppressed_reason = 'level_crossing' WHERE id = :id",
                new MapSqlParameterSource("id", hazardId));
        assertThatThrownBy(() -> hazardService.feedback(vote(deviceA, FeedbackAction.CONFIRM, NEAR_LAT)))
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void feedbackLocationIsNeverStored() {
        hazardService.feedback(vote(deviceA, FeedbackAction.CONFIRM, NEAR_LAT));

        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'hazard_feedback'",
                Map.of(),
                String.class);
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "hazard_id", "device_id", "action", "observed_at", "created_at");
    }

    private FeedbackCommand vote(UUID device, FeedbackAction action, double lat) {
        return new FeedbackCommand(
                hazardId, device, action, lat, LON, Instant.now().minus(2, ChronoUnit.HOURS));
    }

    private UUID device() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO device (id) VALUES (:id)", new MapSqlParameterSource("id", id));
        return id;
    }

    private Map<String, Object> counts() {
        return jdbc.queryForMap(
                "SELECT confirm_count, dismiss_count FROM hazard WHERE id = :id", Map.of("id", hazardId));
    }

    private int votes() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM hazard_feedback WHERE hazard_id = :id", Map.of("id", hazardId), Integer.class);
        return n == null ? 0 : n;
    }

    private int queued() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM hazard_recompute_queue WHERE hazard_id = :id",
                Map.of("id", hazardId),
                Integer.class);
        return n == null ? 0 : n;
    }
}
