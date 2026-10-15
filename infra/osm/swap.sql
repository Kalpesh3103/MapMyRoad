-- Copy the osm2pgsql staging tables into the Flyway-owned reference tables (M4-3, DEPLOYMENT.md 5.2).
-- Run by infra/scripts/osm-import.sh with psql inside the db container:
--   psql -v ON_ERROR_STOP=1 [-v force=1] -f swap.sql
--
-- Design (pilot): TRUNCATE + INSERT ... SELECT in ONE transaction.
--   + Flyway keeps owning the table definitions, index and constraint names. Renaming shadow tables over
--     Flyway tables (DEPLOYMENT.md 5.2 step 5) would leave index and constraint names that no longer
--     match the migrations and would silently drop any column a later migration adds (for example a V9
--     `access` column) unless the shadow table copied it.
--   - TRUNCATE takes an ACCESS EXCLUSIVE lock until COMMIT. Readers of osm_way / osm_calming_point
--     (map-matching job, hazard suppression) wait for the duration of the copy: estimated seconds to a
--     few tens of seconds for the pilot region. Background jobs retry, the public API does not read
--     these tables on the request path. Readers never see half-filled tables (all or nothing).
--   Revisit with a rename-based or partition-swap approach if the region or the lock time grows.
--
-- Safety guards (override with -v force=1):
--   * staging ways must not be empty;
--   * the new way count must be at least 50 % of the current count (a truncated or wrong extract would
--     otherwise wipe the reference data and, through reconcile.sql, the segment statistics).
-- Output: row counts only, never coordinates.

\if :{?force}
\else
  \set force 0
\endif
SELECT set_config('mmr.force', :'force', false) AS force_setting \gset

-- Fill osm_way.access only when the column exists (added by a later Flyway migration, for example V9).
SELECT EXISTS (
  SELECT 1 FROM information_schema.columns
  WHERE table_schema = 'public' AND table_name = 'osm_way' AND column_name = 'access'
) AS has_access \gset

BEGIN;
SET LOCAL lock_timeout = '60s';

DO $$
DECLARE
  staged_ways   bigint;
  staged_points bigint;
  current_ways  bigint;
  forced        boolean := current_setting('mmr.force') = '1';
BEGIN
  IF to_regclass('osm_import.ways') IS NULL OR to_regclass('osm_import.calming') IS NULL THEN
    RAISE EXCEPTION 'staging tables osm_import.ways / osm_import.calming are missing: run the osm2pgsql step first';
  END IF;
  SELECT count(*) INTO staged_ways FROM osm_import.ways;
  SELECT count(*) INTO staged_points FROM osm_import.calming;
  SELECT count(*) INTO current_ways FROM public.osm_way;
  RAISE NOTICE 'staged ways: %, staged calming points: %, current ways: %', staged_ways, staged_points, current_ways;
  IF staged_ways = 0 THEN
    RAISE EXCEPTION 'staging has no ways, refusing to swap';
  END IF;
  IF NOT forced AND current_ways > 0 AND staged_ways < current_ways * 0.5 THEN
    RAISE EXCEPTION 'staging has % ways, less than half of the current %; re-run with force=1 if intended',
      staged_ways, current_ways;
  END IF;
END
$$;

-- Previous way lengths for reconcile.sql (ways whose length changed). Lives in the staging schema, which
-- osm2pgsql does not drop (it only drops its own tables).
DROP TABLE IF EXISTS osm_import.prev_way_length;
CREATE TABLE osm_import.prev_way_length AS
  SELECT way_id, length_m FROM public.osm_way;
ALTER TABLE osm_import.prev_way_length ADD PRIMARY KEY (way_id);

LOCK TABLE public.osm_way, public.osm_calming_point IN ACCESS EXCLUSIVE MODE;
TRUNCATE public.osm_way, public.osm_calming_point;

\if :has_access
INSERT INTO public.osm_way (way_id, highway, name, surface, access, geom, length_m)
SELECT w.way_id, w.highway, w.name, w.surface, w.access, w.geom,
       ST_Length(w.geom::geography)::real
FROM osm_import.ways w
WHERE ST_NPoints(w.geom) >= 2 AND ST_Length(w.geom::geography) > 0;
\else
INSERT INTO public.osm_way (way_id, highway, name, surface, geom, length_m)
SELECT w.way_id, w.highway, w.name, w.surface, w.geom,
       ST_Length(w.geom::geography)::real
FROM osm_import.ways w
WHERE ST_NPoints(w.geom) >= 2 AND ST_Length(w.geom::geography) > 0;
\endif

INSERT INTO public.osm_calming_point (node_id, kind, geom)
SELECT c.node_id, c.kind, c.geom
FROM osm_import.calming c
WHERE c.kind IN ('bump', 'hump', 'table', 'cushion', 'rumble_strip', 'level_crossing');

COMMIT;

ANALYZE public.osm_way;
ANALYZE public.osm_calming_point;

\echo 'swap done. Row counts after the swap:'
\if :has_access
SELECT (SELECT count(*) FROM public.osm_way)                           AS osm_way_rows,
       (SELECT count(*) FROM public.osm_calming_point)                 AS osm_calming_point_rows,
       (SELECT count(*) FROM public.osm_way WHERE access = 'private')  AS private_access_ways;
\else
SELECT (SELECT count(*) FROM public.osm_way)           AS osm_way_rows,
       (SELECT count(*) FROM public.osm_calming_point) AS osm_calming_point_rows;
\endif
