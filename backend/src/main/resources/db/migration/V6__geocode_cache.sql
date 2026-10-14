-- DATABASE_SCHEMA.md A7: cached geocoding responses (M4-13).
CREATE TABLE geocode_cache (
  cache_key   TEXT PRIMARY KEY,
  response    JSONB NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX geocode_cache_expires_idx ON geocode_cache (expires_at);
