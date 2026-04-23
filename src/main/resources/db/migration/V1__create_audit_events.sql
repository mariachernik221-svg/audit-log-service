CREATE TABLE audit_events (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    timestamp  TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor      VARCHAR(255) NOT NULL,
    action     VARCHAR(255) NOT NULL,
    resource   VARCHAR(255) NOT NULL,
    outcome    VARCHAR(32)  NOT NULL,
    context    JSONB
);

CREATE INDEX idx_audit_events_actor     ON audit_events (actor);
CREATE INDEX idx_audit_events_resource  ON audit_events (resource);
CREATE INDEX idx_audit_events_timestamp ON audit_events (timestamp);
