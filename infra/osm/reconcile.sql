-- Segment-key reconciliation after an OSM refresh (DEPLOYMENT.md 5.2 step 4, DATABASE_SCHEMA.md A7 notes).
-- Run by infra/scripts/osm-import.sh right after swap.sql:
--   psql -v ON_ERROR_STOP=1 [-v force=1] -f reconcile.sql
--
-- Segment keys are (way_id, bucket) with 50 m buckets along the way (bucket b covers 50*b .. 50*b+50 m).
-- Pilot-level rules (A7 allows dropping statistics that can no longer be mapped):
--   1. road_segment_stat rows whose way_id no longer exists in osm_way are deleted (way deleted, split or
--      merged in OSM; the new pieces get fresh statistics from new observations).
--   2. For ways whose length changed by more than 10 m, rows for buckets that start beyond the new
--      length are deleted. Buckets inside the new length are kept as they are.
-- FUTURE WORK (not implemented): full re-keying, i.e. geometric matching of old segment geometries
-- (road_segment_stat.geom) to the new ways with ST_LineLocatePoint and moving the statistics instead of
-- dropping them; also re-bucketing when a way's geometry moved but its length did not change.
-- hazard.way_id, hazard_event.way_id and segment_observation.way_id are NOT changed here; the counts of
-- rows pointing at vanished ways are reported for information (the matching and aggregation jobs own them).
--
-- Safety guard (override with -v force=1): refuse if osm_way is empty, or if more than 25 % of all
-- segment statistics would be deleted (that points to a bad extract rather than normal OSM edits).
-- Output: counts only.

\if :{?force}
\else
  \set force 0
\endif
SELECT set_config('mmr.force', :'force', false) AS force_setting \gset

SELECT to_regclass('osm_import.prev_way_length') IS NOT NULL AS has_prev \gset

BEGIN;
SET LOCAL lock_timeout = '60s';

CREATE TEMP TABLE reconcile_drop ON COMMIT DROP AS
SELECT s.way_id, s.bucket, 'way_missing'::text AS reason
FROM road_segment_stat s
WHERE NOT EXISTS (SELECT 1 FROM osm_way w WHERE w.way_id = s.way_id);

\if :has_prev
INSERT INTO reconcile_drop (way_id, bucket, reason)
SELECT s.way_id, s.bucket, 'beyond_new_length'
FROM road_segment_stat s
JOIN osm_way w ON w.way_id = s.way_id
JOIN osm_import.prev_way_length p ON p.way_id = s.way_id
WHERE abs(w.length_m - p.length_m) > 10
  AND s.bucket * 50 >= w.length_m;
\endif

DO $$
DECLARE
  ways_now   bigint;
  stats_all  bigint;
  stats_drop bigint;
  forced     boolean := current_setting('mmr.force') = '1';
BEGIN
  SELECT count(*) INTO ways_now FROM osm_way;
  SELECT count(*) INTO stats_all FROM road_segment_stat;
  SELECT count(*) INTO stats_drop FROM reconcile_drop;
  IF ways_now = 0 THEN
    RAISE EXCEPTION 'osm_way is empty, refusing to reconcile (it would delete every segment statistic)';
  END IF;
  IF NOT forced AND stats_all > 0 AND stats_drop > stats_all * 0.25 THEN
    RAISE EXCEPTION 'reconcile would delete % of % segment statistics (> 25 %%); re-run with force=1 if intended',
      stats_drop, stats_all;
  END IF;
END
$$;

\echo 'segment statistics to drop, by reason:'
SELECT reason, count(*) AS rows FROM reconcile_drop GROUP BY reason ORDER BY reason;

DELETE FROM road_segment_stat s
USING reconcile_drop d
WHERE s.way_id = d.way_id AND s.bucket = d.bucket;

COMMIT;

\echo 'reconcile done. Remaining rows and references to ways that no longer exist (informational):'
SELECT
  (SELECT count(*) FROM road_segment_stat) AS road_segment_stat_rows,
  (SELECT count(*) FROM hazard h
     WHERE h.way_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM osm_way w WHERE w.way_id = h.way_id)) AS hazards_on_missing_ways,
  (SELECT count(*) FROM segment_observation o
     WHERE o.way_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM osm_way w WHERE w.way_id = o.way_id)) AS observations_on_missing_ways;
