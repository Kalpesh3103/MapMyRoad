-- DATABASE_SCHEMA.md A7: feedback votes and the recompute queue used after deletions.
CREATE TABLE hazard_feedback (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  hazard_id   UUID NOT NULL REFERENCES hazard(id) ON DELETE CASCADE,
  device_id   UUID NOT NULL REFERENCES device(id) ON DELETE CASCADE,
  action      TEXT NOT NULL CHECK (action IN ('confirm','dismiss')),
  observed_at TIMESTAMPTZ NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (hazard_id, device_id)
);

CREATE TABLE hazard_recompute_queue (
  hazard_id  UUID PRIMARY KEY,
  queued_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
