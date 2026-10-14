-- DATABASE_SCHEMA.md A7: aggregated hazards and road segment statistics.
CREATE TABLE hazard (
  id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  geom               geography(Point, 4326) NOT NULL,
  way_id             BIGINT,
  severity_max       SMALLINT NOT NULL CHECK (severity_max BETWEEN 1 AND 3),
  severity_avg       REAL NOT NULL,
  event_count        INT NOT NULL DEFAULT 0,
  distinct_devices   INT NOT NULL DEFAULT 0,
  confirm_count      INT NOT NULL DEFAULT 0,
  dismiss_count      INT NOT NULL DEFAULT 0,
  clean_pass_count   INT NOT NULL DEFAULT 0,
  confidence         REAL NOT NULL DEFAULT 0 CHECK (confidence BETWEEN 0 AND 1),
  status             TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','stale','fixed')),
  suppressed_reason  TEXT CHECK (suppressed_reason IN ('osm_traffic_calming','level_crossing','repeat_pattern')),
  first_seen_at      TIMESTAMPTZ NOT NULL,
  last_seen_at       TIMESTAMPTZ NOT NULL,
  last_clean_pass_at TIMESTAMPTZ,
  fixed_at           TIMESTAMPTZ,
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX hazard_geom_gix   ON hazard USING GIST (geom);
CREATE INDEX hazard_status_idx ON hazard (status, last_seen_at);
CREATE INDEX hazard_way_idx    ON hazard (way_id);

ALTER TABLE hazard_event
  ADD CONSTRAINT hazard_event_hazard_fk
  FOREIGN KEY (hazard_id) REFERENCES hazard(id) ON DELETE SET NULL;

CREATE TABLE road_segment_stat (
  way_id             BIGINT NOT NULL,
  bucket             INT NOT NULL,
  geom               geography(LineString, 4326) NOT NULL,
  roughness_avg      REAL NOT NULL,
  score              SMALLINT NOT NULL CHECK (score BETWEEN 0 AND 100),
  sample_count       INT NOT NULL DEFAULT 0,
  distinct_devices   INT NOT NULL DEFAULT 0,
  last_observed_at   TIMESTAMPTZ NOT NULL,
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (way_id, bucket)
);
CREATE INDEX road_segment_stat_geom_gix ON road_segment_stat USING GIST (geom);
