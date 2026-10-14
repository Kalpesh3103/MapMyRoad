-- The retention job (DATABASE_SCHEMA.md A10) filters raw rows by received_at. V3 only has partial indexes for
-- unmatched rows, so without these the nightly batches would scan the largest tables (A12).
CREATE INDEX hazard_event_received_idx        ON hazard_event (received_at);
CREATE INDEX segment_observation_received_idx ON segment_observation (received_at);
CREATE INDEX device_last_seen_idx             ON device (last_seen_at);
CREATE INDEX device_refresh_token_expires_idx ON device_refresh_token (expires_at);
