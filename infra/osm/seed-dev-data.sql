-- SYNTHETIC seed data for local development and demos (TESTING_STRATEGY.md section 13).
-- EVERYTHING in this file is made up: roads, devices, hazards and segment statistics are synthetic and
-- describe no real person, trip or road condition. NEVER run this against production. It is run only by
-- infra/scripts/seed-dev-data.sh, which refuses non-local targets and passes -v i_know_this_is_dev=yes.
--
-- Markers: synthetic UUIDs start with 5eed0000-; synthetic OSM ids are NEGATIVE (real OSM ids are
-- positive), names start with "Synthetic". Area: a few straight lines around central Pune
-- (inside DEV_BBOX in infra/osm/pilot-area.env).
--
-- Idempotent: fixed ids plus ON CONFLICT DO NOTHING; running it twice changes nothing. Timestamps are
-- relative to the first run.
-- Synthetic ways and the synthetic calming point are inserted only while osm_way / osm_calming_point hold
-- no real (positive id) rows, so they never mix into an imported OSM network. A later osm-import.sh
-- replaces them, and reconcile.sql then drops the synthetic segment statistics (their ways are gone);
-- simply run the seed again afterwards if you want them back (stats on negative way ids, still drawn).
-- Columns follow Flyway V1 (device), V2 (osm_way, osm_calming_point), V3 (hazard_event) and V4 (hazard,
-- road_segment_stat).

\if :{?i_know_this_is_dev}
\else
  \set i_know_this_is_dev no
\endif
SELECT :'i_know_this_is_dev' = 'yes' AS seed_allowed \gset
\if :seed_allowed
\else
  DO $$ BEGIN RAISE EXCEPTION 'seed-dev-data.sql refused: run it through infra/scripts/seed-dev-data.sh'; END $$;
\endif

BEGIN;

-- Second guard inside the database: a production database has far more than a handful of devices.
DO $$
BEGIN
  IF (SELECT count(*) FROM device WHERE id::text NOT LIKE '5eed0000-%') > 100 THEN
    RAISE EXCEPTION 'more than 100 real devices found: this does not look like a development database';
  END IF;
END
$$;

-- Synthetic straight roads (lon/lat, SRID 4326). Lengths are computed, roughly 0.6 to 2.2 km.
CREATE TEMP TABLE seed_line (
  way_id   bigint PRIMARY KEY,
  highway  text NOT NULL,
  name     text NOT NULL,
  base     int  NOT NULL,              -- typical comfort score used to vary segment statistics
  geom     geometry(LineString, 4326) NOT NULL
) ON COMMIT DROP;

INSERT INTO seed_line (way_id, highway, name, base, geom) VALUES
  (-1001, 'primary',     'Synthetic Primary Road A',  75,
     ST_MakeLine(ST_SetSRID(ST_MakePoint(73.8400, 18.5200), 4326), ST_SetSRID(ST_MakePoint(73.8600, 18.5200), 4326))),
  (-1002, 'primary',     'Synthetic Primary Road B',  60,
     ST_MakeLine(ST_SetSRID(ST_MakePoint(73.8500, 18.5100), 4326), ST_SetSRID(ST_MakePoint(73.8500, 18.5300), 4326))),
  (-1003, 'residential', 'Synthetic Residential Lane C', 35,
     ST_MakeLine(ST_SetSRID(ST_MakePoint(73.8420, 18.5250), 4326), ST_SetSRID(ST_MakePoint(73.8480, 18.5250), 4326))),
  (-1004, 'residential', 'Synthetic Residential Lane D', 50,
     ST_MakeLine(ST_SetSRID(ST_MakePoint(73.8550, 18.5150), 4326), ST_SetSRID(ST_MakePoint(73.8550, 18.5250), 4326))),
  (-1005, 'secondary',   'Synthetic Secondary Road E', 82,
     ST_MakeLine(ST_SetSRID(ST_MakePoint(73.8420, 18.5130), 4326), ST_SetSRID(ST_MakePoint(73.8580, 18.5280), 4326)));

-- Reference ways, only while no real OSM data is loaded.
INSERT INTO osm_way (way_id, highway, name, surface, geom, length_m)
SELECT l.way_id, l.highway, l.name, 'asphalt', l.geom, ST_Length(l.geom::geography)::real
FROM seed_line l
WHERE NOT EXISTS (SELECT 1 FROM osm_way w WHERE w.way_id > 0)
ON CONFLICT (way_id) DO NOTHING;

-- One synthetic speed breaker (the suppressed hazard 12 below sits on it).
INSERT INTO osm_calming_point (node_id, kind, geom)
SELECT -2001, 'bump', ST_LineInterpolatePoint(l.geom, 0.20)
FROM seed_line l
WHERE l.way_id = -1004
  AND NOT EXISTS (SELECT 1 FROM osm_calming_point c WHERE c.node_id > 0)
ON CONFLICT (node_id) DO NOTHING;

-- Three synthetic devices, default trust 0.5.
INSERT INTO device (id, created_at, last_seen_at, app_version, os_api_level, trust_score) VALUES
  ('5eed0000-0000-4000-8001-000000000001', now() - interval '60 days', now() - interval '1 day',  '0.0.0-synthetic', 34, 0.5),
  ('5eed0000-0000-4000-8001-000000000002', now() - interval '45 days', now() - interval '2 days', '0.0.0-synthetic', 33, 0.5),
  ('5eed0000-0000-4000-8001-000000000003', now() - interval '30 days', now() - interval '5 days', '0.0.0-synthetic', 31, 0.5)
ON CONFLICT (id) DO NOTHING;

-- Twenty synthetic hazards with mixed severity, confidence and status:
--   8 and 13: low confidence (< 0.3, hidden by the tile visibility rule)
--   12: suppressed (osm_traffic_calming, on the synthetic bump), 20: suppressed (repeat_pattern)
--   14: stale, 17: fixed. distinct_devices never exceeds the 3 synthetic devices.
-- Counters must match what the aggregation jobs recompute from raw rows (AggregationService): event_count and
-- distinct_devices match the synthetic hazard_event rows below; confirm_count / dismiss_count are only a
-- starting point (the recompute queue recounts them from hazard_feedback after the first vote); clean_pass_count
-- is 0 because no hazard_clean_pass rows are seeded (the nightly refresh would reset it). confidence is the
-- hand-picked initial value; the nightly refresh recomputes it from the events with ConfidenceFormula.
INSERT INTO hazard (id, geom, way_id, severity_max, severity_avg, event_count, distinct_devices,
                    confirm_count, dismiss_count, clean_pass_count, confidence, status, suppressed_reason,
                    first_seen_at, last_seen_at, last_clean_pass_at, fixed_at, updated_at)
SELECT ('5eed0000-0000-4000-8002-' || lpad(h.n::text, 12, '0'))::uuid,
       ST_LineInterpolatePoint(l.geom, h.frac)::geography,
       h.way_id, h.sev_max, h.sev_avg, h.events, h.devices, h.confirms, h.dismisses, h.clean_passes,
       h.confidence, h.status, h.suppressed,
       now() - make_interval(days => h.first_days),
       now() - make_interval(days => h.last_days),
       CASE WHEN h.clean_days IS NOT NULL THEN now() - make_interval(days => h.clean_days) END,
       CASE WHEN h.fixed_days IS NOT NULL THEN now() - make_interval(days => h.fixed_days) END,
       now()
FROM (VALUES
  -- n, way_id, frac, sev_max, sev_avg, events, devices, confirms, dismisses, clean, confidence, status, suppressed, first_days, last_days, clean_days, fixed_days
  ( 1, -1001, 0.10, 3, 2.6::real, 14, 3, 2, 0, 0, 0.92::real, 'active', NULL::text,             40,  1, NULL::int, NULL::int),
  ( 2, -1001, 0.25, 2, 1.8,        6, 3, 1, 0, 0, 0.71,       'active', NULL,                   30,  2, NULL, NULL),
  ( 3, -1001, 0.40, 1, 1.2,        3, 2, 0, 0, 0, 0.38,       'active', NULL,                   20,  5, NULL, NULL),
  ( 4, -1001, 0.55, 3, 2.9,       22, 3, 3, 0, 0, 0.97,       'active', NULL,                   50,  0, NULL, NULL),
  ( 5, -1001, 0.80, 2, 2.0,        4, 2, 0, 1, 0, 0.33,       'active', NULL,                   25,  9, NULL, NULL),
  ( 6, -1002, 0.15, 2, 1.5,        5, 2, 0, 0, 0, 0.55,       'active', NULL,                   18,  3, NULL, NULL),
  ( 7, -1002, 0.35, 3, 2.4,        9, 3, 1, 0, 0, 0.84,       'active', NULL,                   35,  1, NULL, NULL),
  ( 8, -1002, 0.60, 1, 1.0,        1, 1, 0, 0, 0, 0.12,       'active', NULL,                    4,  4, NULL, NULL),
  ( 9, -1002, 0.85, 2, 1.7,        7, 3, 0, 0, 0, 0.66,       'active', NULL,                   22,  2, NULL, NULL),
  (10, -1003, 0.30, 2, 2.0,        3, 2, 0, 0, 0, 0.45,       'active', NULL,                   12,  6, NULL, NULL),
  (11, -1003, 0.70, 3, 2.7,        8, 3, 2, 0, 0, 0.88,       'active', NULL,                   28,  1, NULL, NULL),
  (12, -1004, 0.20, 2, 1.9,       10, 3, 0, 0, 0, 0.80,       'active', 'osm_traffic_calming',  60,  1, NULL, NULL),
  (13, -1004, 0.50, 1, 1.3,        2, 1, 0, 0, 0, 0.22,       'active', NULL,                    6,  6, NULL, NULL),
  (14, -1004, 0.80, 2, 1.6,        5, 2, 0, 2, 0, 0.18,       'stale',  NULL,                   90, 45, NULL, NULL),
  (15, -1005, 0.10, 3, 2.5,       11, 3, 1, 0, 0, 0.90,       'active', NULL,                   33,  0, NULL, NULL),
  (16, -1005, 0.30, 2, 1.8,        6, 2, 0, 0, 0, 0.58,       'active', NULL,                   15,  3, NULL, NULL),
  (17, -1005, 0.50, 2, 2.2,        5, 3, 0, 1, 0, 0.40,       'fixed',  NULL,                  120, 60, NULL,   20),
  (18, -1005, 0.70, 1, 1.1,        3, 2, 0, 0, 0, 0.31,       'active', NULL,                   10,  8, NULL, NULL),
  (19, -1005, 0.90, 3, 3.0,       16, 3, 3, 0, 0, 0.95,       'active', NULL,                   45,  0, NULL, NULL),
  (20, -1003, 0.50, 1, 1.4,        2, 2, 0, 0, 0, 0.26,       'active', 'repeat_pattern',       14,  7, NULL, NULL)
) AS h(n, way_id, frac, sev_max, sev_avg, events, devices, confirms, dismisses, clean_passes, confidence,
       status, suppressed, first_days, last_days, clean_days, fixed_days)
JOIN seed_line l ON l.way_id = h.way_id
ON CONFLICT (id) DO NOTHING;

-- Synthetic raw events behind each hazard, already matched and clustered (matched_at and hazard_id set), so the
-- matching and clustering jobs skip them and the nightly refresh recomputes the same event_count,
-- distinct_devices, severity_max and position instead of dropping the hazards to zero confidence. Devices are
-- assigned round-robin among the hazard's distinct_devices synthetic devices; times are spread evenly from
-- first_seen_at to last_seen_at. Ids: 5eed0000-0000-4000-8003-<hazard n * 100 + event index>.
INSERT INTO hazard_event (id, device_id, trip_token, hazard_id, geom, way_id, severity, vehicle_type, speed_kmh,
                          observed_at, received_at, matched_at)
SELECT ('5eed0000-0000-4000-8003-' || lpad((right(h.id::text, 12)::bigint * 100 + e.i)::text, 12, '0'))::uuid,
       ('5eed0000-0000-4000-8001-' || lpad(((e.i - 1) % h.distinct_devices + 1)::text, 12, '0'))::uuid,
       NULL,
       h.id, h.geom, h.way_id,
       CASE WHEN e.i = 1 THEN h.severity_max
            ELSE least(h.severity_max, greatest(1, round(h.severity_avg)::int)) END,
       2, 25,
       t.observed_at, t.observed_at, t.observed_at
FROM hazard h
CROSS JOIN LATERAL generate_series(1, h.event_count) AS e(i)
CROSS JOIN LATERAL (
  SELECT h.first_seen_at
         + ((e.i - 1)::float8 / greatest(h.event_count - 1, 1)) * (h.last_seen_at - h.first_seen_at) AS observed_at
) t
WHERE h.id::text LIKE '5eed0000-0000-4000-8002-%'
  AND h.distinct_devices > 0
ON CONFLICT (id) DO NOTHING;

-- Segment statistics: one row per 50 m bucket along each synthetic road, with a gap every 9th bucket
-- (no coverage). Scores vary deterministically around the road's base value; sample_count (1 to 12) and
-- distinct_devices (1 to 3) vary so that some rows are below the visibility rule of API_SPEC.md 5.4
-- (at least 3 samples from at least 2 devices) and do not appear in tiles. roughness_avg is illustrative.
INSERT INTO road_segment_stat (way_id, bucket, geom, roughness_avg, score, sample_count, distinct_devices,
                               last_observed_at, updated_at)
SELECT s.way_id, s.bucket,
       ST_LineSubstring(s.geom, s.bucket * 50.0 / s.len_m, least((s.bucket + 1) * 50.0 / s.len_m, 1.0))::geography,
       round(((100 - s.score) / 20.0)::numeric, 2)::real,
       s.score,
       s.samples,
       least(s.samples, s.devices),
       now() - make_interval(days => (s.bucket * 3) % 30),
       now()
FROM (
  SELECT l.way_id, b.bucket, l.geom, ST_Length(l.geom::geography) AS len_m,
         greatest(0, least(100, l.base + ((b.bucket * 37 + abs(l.way_id)::int) % 41) - 20))::smallint AS score,
         1 + (b.bucket * 7 + abs(l.way_id)::int) % 12 AS samples,
         1 + (b.bucket * 5 + abs(l.way_id)::int) % 3  AS devices
  FROM seed_line l
  CROSS JOIN LATERAL generate_series(0, ceil(ST_Length(l.geom::geography) / 50.0)::int - 1) AS b(bucket)
  WHERE b.bucket % 9 <> 4
) s
ON CONFLICT (way_id, bucket) DO NOTHING;

COMMIT;

\echo 'synthetic seed data (counts):'
SELECT
  (SELECT count(*) FROM osm_way WHERE way_id < 0)                       AS synthetic_ways,
  (SELECT count(*) FROM device WHERE id::text LIKE '5eed0000-%')        AS synthetic_devices,
  (SELECT count(*) FROM hazard WHERE id::text LIKE '5eed0000-%')        AS synthetic_hazards,
  (SELECT count(*) FROM hazard_event WHERE id::text LIKE '5eed0000-%')  AS synthetic_events,
  (SELECT count(*) FROM hazard WHERE id::text LIKE '5eed0000-%'
     AND status = 'active' AND suppressed_reason IS NULL AND confidence >= 0.3) AS hazards_visible,
  (SELECT count(*) FROM road_segment_stat WHERE way_id < 0)             AS synthetic_segments,
  (SELECT count(*) FROM road_segment_stat WHERE way_id < 0
     AND sample_count >= 3 AND distinct_devices >= 2)                   AS segments_visible;
