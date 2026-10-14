-- DATABASE_SCHEMA.md A6. IDs are client-generated so retried uploads are idempotent.
CREATE TABLE hazard_event (
  id            UUID PRIMARY KEY,
  device_id     UUID REFERENCES device(id) ON DELETE CASCADE,   -- set to NULL by the retention job
  trip_token    UUID,                                           -- cleared after map-matching
  hazard_id     UUID,                                           -- FK added in V4
  geom          geography(Point, 4326) NOT NULL,
  heading_deg   SMALLINT CHECK (heading_deg BETWEEN 0 AND 359),
  way_id        BIGINT,
  severity      SMALLINT NOT NULL CHECK (severity BETWEEN 1 AND 3),
  vehicle_type  SMALLINT NOT NULL CHECK (vehicle_type BETWEEN 1 AND 4),
  speed_kmh     REAL NOT NULL CHECK (speed_kmh BETWEEN 0 AND 200),
  peak_score    REAL,
  observed_at   TIMESTAMPTZ NOT NULL,
  received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  matched_at    TIMESTAMPTZ
);
CREATE INDEX hazard_event_geom_gix     ON hazard_event USING GIST (geom);
CREATE INDEX hazard_event_device_idx   ON hazard_event (device_id);
CREATE INDEX hazard_event_hazard_idx   ON hazard_event (hazard_id);
CREATE INDEX hazard_event_observed_idx ON hazard_event (observed_at);
CREATE INDEX hazard_event_pending_idx  ON hazard_event (received_at) WHERE matched_at IS NULL;

CREATE TABLE segment_observation (
  id            UUID PRIMARY KEY,
  device_id     UUID REFERENCES device(id) ON DELETE CASCADE,
  trip_token    UUID,
  geom          geography(LineString, 4326) NOT NULL,
  way_id        BIGINT,
  bucket        INT,
  roughness     REAL NOT NULL CHECK (roughness >= 0),
  speed_kmh     REAL NOT NULL,
  vehicle_type  SMALLINT NOT NULL,
  observed_at   TIMESTAMPTZ NOT NULL,
  received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  matched_at    TIMESTAMPTZ
);
CREATE INDEX segment_observation_geom_gix     ON segment_observation USING GIST (geom);
CREATE INDEX segment_observation_device_idx   ON segment_observation (device_id);
CREATE INDEX segment_observation_key_idx      ON segment_observation (way_id, bucket);
CREATE INDEX segment_observation_pending_idx  ON segment_observation (received_at) WHERE matched_at IS NULL;
