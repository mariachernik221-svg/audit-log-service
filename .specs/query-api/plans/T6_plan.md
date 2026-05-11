# T6 — Controller and service tests for validation and contract behavior

_Scope, ACs, DoD: see [tasks.md](../tasks.md) § T6._

## Files

- `src/test/java/com/auditlog/api/AuditEventControllerTest.java` (edit) — fill gaps left by T1.
- `src/test/java/com/auditlog/service/AuditEventServiceImplTest.java` (edit) — fill gaps left by T2 and T5.

## Signatures / snippets

No production code changes in this task. Tests only.

## Steps

1. Identify cases not already covered by T1, T2, T5 tests.
2. Add the cases listed below.
3. Run `./gradlew test` until green.

## Tests

Controller (`AuditEventControllerTest`):

- Empty service result → 200 with body `{ "items": [], "nextCursor": null }`.
- `actor=Alice` and `actor=alice` both reach the service with the case preserved (lowering happens in JPQL, not in the controller).

Service (`AuditEventServiceImplTest`):

- `from == to` rejected.
- Boundary: `to − from = 90d` accepted; `to − from > 90d` rejected.
- Cursor whose decoded `actor` / `resource` / `from` / `to` / `order` differs from the request → `IllegalArgumentException` (→ 400 chain).
- Malformed cursor (non-base64, truncated JSON) → `IllegalArgumentException` (→ 400 chain).

Unit-level only; no Testcontainers in this task.
