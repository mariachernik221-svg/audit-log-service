CREATE INDEX idx_audit_events_actor_ts_id    ON audit_events (lower(actor),    timestamp, id);
CREATE INDEX idx_audit_events_resource_ts_id ON audit_events (lower(resource), timestamp, id);
CREATE INDEX idx_audit_events_ts_id          ON audit_events (timestamp, id);
