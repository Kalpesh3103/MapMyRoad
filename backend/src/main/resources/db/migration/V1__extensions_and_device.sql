-- DATABASE_SCHEMA.md A1 and A4. gen_random_uuid() is built into PostgreSQL 13 and later.
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE device (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  app_version   TEXT,
  os_api_level  SMALLINT,
  trust_score   REAL NOT NULL DEFAULT 0.5 CHECK (trust_score BETWEEN 0 AND 1)
);

CREATE TABLE device_refresh_token (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  device_id   UUID NOT NULL REFERENCES device(id) ON DELETE CASCADE,
  token_hash  BYTEA NOT NULL UNIQUE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at  TIMESTAMPTZ NOT NULL,
  revoked_at  TIMESTAMPTZ
);
CREATE INDEX device_refresh_token_device_idx ON device_refresh_token (device_id);
