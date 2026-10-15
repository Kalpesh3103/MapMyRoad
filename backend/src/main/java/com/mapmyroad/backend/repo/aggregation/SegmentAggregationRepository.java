package com.mapmyroad.backend.repo.aggregation;

import com.mapmyroad.backend.service.matching.SegmentBuckets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * Road segment statistics (M4-10, DATABASE_SCHEMA.md A7): incremental upsert from newly matched windows and the
 * nightly refresh of {@code distinct_devices}.
 *
 * <p>Segment geometry is cut from {@code osm_way.geom} (geometry 4326) with {@code ST_LineSubstring} at the bucket
 * boundaries, using the way's geodesic {@code length_m} to turn meters into fractions; over one way the planar
 * fraction is a close approximation. It is stored as {@code geography} like every distance-queried column.
 */
@Repository
public class SegmentAggregationRepository {

    // aggregated_at IS NULL is the "not yet aggregated" marker (V8); set in the same transaction as the upsert.
    private static final String FIND_UNAGGREGATED_WINDOWS = """
            SELECT id, way_id, bucket, roughness, observed_at
            FROM segment_observation
            WHERE matched_at IS NOT NULL AND aggregated_at IS NULL
            ORDER BY matched_at, id
            LIMIT :limit
            """;

    // Row locks so the EWMA read-modify-write cannot interleave with another writer of the same segments.
    private static final String LOCK_STATS = """
            SELECT way_id, bucket, roughness_avg, sample_count
            FROM road_segment_stat
            WHERE way_id IN (:wayIds)
            FOR UPDATE
            """;

    // roughness_avg and score come from SegmentMath (EWMA over the old value and the new samples in time order).
    // distinct_devices is exact for the key at this moment (segment_observation_key_idx); the nightly refresh
    // lowers it again as retention clears device IDs. The bucket guard skips keys that do not fit the way (for
    // example after an OSM refresh shortened it); geometry is refreshed on every update for the same reason.
    private static final String UPSERT_STAT = """
            INSERT INTO road_segment_stat (way_id, bucket, geom, roughness_avg, score, sample_count, distinct_devices,
                                           last_observed_at, updated_at)
            SELECT w.way_id, :bucket,
                   ST_LineSubstring(w.geom,
                                    LEAST(1.0, :bucket * :bucketLengthM / w.length_m),
                                    LEAST(1.0, (:bucket + 1) * :bucketLengthM / w.length_m))::geography,
                   CAST(:roughnessAvg AS real), :score, :newSamples,
                   (SELECT COUNT(DISTINCT o.device_id) FROM segment_observation o
                    WHERE o.way_id = w.way_id AND o.bucket = :bucket),
                   :lastObservedAt, :now
            FROM osm_way w
            WHERE w.way_id = :wayId AND w.length_m > 0 AND :bucket * :bucketLengthM < w.length_m
            ON CONFLICT (way_id, bucket) DO UPDATE SET
              geom             = EXCLUDED.geom,
              roughness_avg    = EXCLUDED.roughness_avg,
              score            = EXCLUDED.score,
              sample_count     = road_segment_stat.sample_count + EXCLUDED.sample_count,
              distinct_devices = EXCLUDED.distinct_devices,
              last_observed_at = GREATEST(road_segment_stat.last_observed_at, EXCLUDED.last_observed_at),
              updated_at       = EXCLUDED.updated_at
            """;

    private static final String MARK_AGGREGATED = """
            UPDATE segment_observation SET aggregated_at = :now WHERE id IN (:ids) AND aggregated_at IS NULL
            """;

    // Nightly (DATABASE_SCHEMA.md A7): distinct devices from raw rows, in keyset batches over the primary key.
    private static final String REFRESH_DISTINCT_DEVICES = """
            WITH k AS (
              SELECT way_id, bucket FROM road_segment_stat
              WHERE (way_id, bucket) > (:afterWayId, :afterBucket)
              ORDER BY way_id, bucket
              LIMIT :limit
            ), d AS (
              SELECT k.way_id, k.bucket,
                     (SELECT COUNT(DISTINCT o.device_id) FROM segment_observation o
                      WHERE o.way_id = k.way_id AND o.bucket = k.bucket) AS n
              FROM k
            ), u AS (
              UPDATE road_segment_stat s SET distinct_devices = d.n, updated_at = :now
              FROM d
              WHERE s.way_id = d.way_id AND s.bucket = d.bucket AND s.distinct_devices <> d.n
            )
            -- The data-modifying CTE runs even though the result does not reference it.
            SELECT way_id, bucket FROM k ORDER BY way_id DESC, bucket DESC LIMIT 1
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public SegmentAggregationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<WindowSample> findUnaggregatedWindows(int limit) {
        return jdbc.query(FIND_UNAGGREGATED_WINDOWS, new MapSqlParameterSource("limit", limit),
                (rs, i) -> new WindowSample(
                        rs.getObject("id", UUID.class),
                        nullableLong(rs, "way_id"),
                        nullableInt(rs, "bucket"),
                        rs.getDouble("roughness"),
                        rs.getObject("observed_at", OffsetDateTime.class).toInstant()));
    }

    /** Locks and returns existing rows of the given ways; must run in the upsert's transaction. */
    public List<SegmentStatRow> lockStats(Collection<Long> wayIds) {
        if (wayIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(LOCK_STATS, new MapSqlParameterSource("wayIds", wayIds),
                (rs, i) -> new SegmentStatRow(
                        new SegmentKey(rs.getLong("way_id"), rs.getInt("bucket")),
                        rs.getDouble("roughness_avg"),
                        rs.getInt("sample_count")));
    }

    public int upsertStats(List<SegmentStatUpdate> updates, Instant now) {
        if (updates.isEmpty()) {
            return 0;
        }
        SqlParameterSource[] batch = new SqlParameterSource[updates.size()];
        for (int i = 0; i < updates.size(); i++) {
            SegmentStatUpdate update = updates.get(i);
            batch[i] = new MapSqlParameterSource()
                    .addValue("wayId", update.key().wayId())
                    .addValue("bucket", update.key().bucket(), Types.INTEGER)
                    .addValue("bucketLengthM", SegmentBuckets.BUCKET_LENGTH_M, Types.DOUBLE)
                    .addValue("roughnessAvg", update.roughnessAvg())
                    .addValue("score", update.score(), Types.SMALLINT)
                    .addValue("newSamples", update.newSamples(), Types.INTEGER)
                    .addValue("lastObservedAt", timestamp(update.lastObservedAt()))
                    .addValue("now", timestamp(now));
        }
        int total = 0;
        for (int count : jdbc.batchUpdate(UPSERT_STAT, batch)) {
            total += count < 0 ? 1 : count;
        }
        return total;
    }

    public int markAggregated(Collection<UUID> windowIds, Instant now) {
        if (windowIds.isEmpty()) {
            return 0;
        }
        return jdbc.update(MARK_AGGREGATED, new MapSqlParameterSource()
                .addValue("ids", windowIds)
                .addValue("now", timestamp(now)));
    }

    /**
     * Refreshes {@code distinct_devices} for the next batch of segments after {@code after} (exclusive). Returns the
     * last key of the batch, or null when there are no more segments.
     */
    public SegmentKey refreshDistinctDevices(SegmentKey after, int limit, Instant now) {
        var params = new MapSqlParameterSource()
                .addValue("afterWayId", after.wayId())
                .addValue("afterBucket", after.bucket())
                .addValue("limit", limit)
                .addValue("now", timestamp(now));
        List<SegmentKey> last = jdbc.query(REFRESH_DISTINCT_DEVICES, params,
                (rs, i) -> new SegmentKey(rs.getLong("way_id"), rs.getInt("bucket")));
        return last.isEmpty() ? null : last.get(0);
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
