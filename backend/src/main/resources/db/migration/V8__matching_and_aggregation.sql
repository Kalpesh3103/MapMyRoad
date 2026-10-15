-- M4-9 and M4-10: map-matching and aggregation bookkeeping (DATABASE_SCHEMA.md A6, A7, A9).

-- Windows are aggregated exactly once (segment statistics and clean passes). A per-row marker, written in the
-- same transaction as the aggregate change, is idempotent regardless of the order in which rows are matched;
-- a received_at watermark would skip rows that are matched later than newer rows (CODING_STANDARDS.md 4.5).
ALTER TABLE segment_observation ADD COLUMN aggregated_at TIMESTAMPTZ;

-- Matched but not yet aggregated windows (small: only the rows waiting for the next aggregation run).
CREATE INDEX segment_observation_unaggregated_idx ON segment_observation (matched_at)
  WHERE matched_at IS NOT NULL AND aggregated_at IS NULL;

-- Rows already processed before this migration must not be aggregated again by the first run.
UPDATE segment_observation SET aggregated_at = matched_at WHERE matched_at IS NOT NULL;

-- Matched events waiting for clustering (hazard_id is the processed marker for events).
CREATE INDEX hazard_event_unclustered_idx ON hazard_event (matched_at)
  WHERE matched_at IS NOT NULL AND hazard_id IS NULL;

-- The matching job loads all pending rows of one trip; trip tokens exist only until matching (at most 24 h).
CREATE INDEX hazard_event_pending_trip_idx ON hazard_event (trip_token)
  WHERE matched_at IS NULL AND trip_token IS NOT NULL;
CREATE INDEX segment_observation_pending_trip_idx ON segment_observation (trip_token)
  WHERE matched_at IS NULL AND trip_token IS NOT NULL;

-- Clean passes over a hazard (ARCHITECTURE.md 8.3). One row per (hazard, window), so a window counts at most
-- once per hazard. No device ID is stored here: the device comes from the window row, so the retention job
-- (device_id set to NULL after 90 days) and "delete my data" (ON DELETE CASCADE from segment_observation) apply
-- without extra work.
CREATE TABLE hazard_clean_pass (
  hazard_id       UUID NOT NULL REFERENCES hazard(id) ON DELETE CASCADE,
  observation_id  UUID NOT NULL REFERENCES segment_observation(id) ON DELETE CASCADE,
  passed_at       TIMESTAMPTZ NOT NULL,   -- observed_at of the window
  PRIMARY KEY (hazard_id, observation_id)
);
CREATE INDEX hazard_clean_pass_observation_idx ON hazard_clean_pass (observation_id);

