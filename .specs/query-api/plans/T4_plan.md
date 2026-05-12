# T4 — Add Flyway migration V3 for query-shape indexes

_Scope, ACs, DoD: see [tasks.md](../tasks.md) § T4._

## Files

- `src/main/resources/db/migration/V3__query_api_indexes.sql` (new) — three composite indexes, verbatim from design §4.

## Signatures / snippets

```sql
CREATE INDEX idx_audit_events_actor_ts_id    ON audit_events (lower(actor),    timestamp, id);
CREATE INDEX idx_audit_events_resource_ts_id ON audit_events (lower(resource), timestamp, id);
CREATE INDEX idx_audit_events_ts_id          ON audit_events (timestamp, id);
```

## Steps

1. Add `V3__query_api_indexes.sql` with the three `CREATE INDEX` statements above.
2. Run `./gradlew test` to confirm V1 → V2 → V3 applies cleanly against the Testcontainers Postgres used by existing integration tests.

## Tests

No new test class.

- Existing `AuditEventRepositoryTest` and `AuditEventImmutabilityIntegrationTest` continue to pass against a fresh database with V1+V2+V3 applied — this is the migration smoke check.
