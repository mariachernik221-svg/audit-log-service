# T1 — Add paginated query endpoint and request/response types

_Scope, ACs, DoD: see [tasks.md](../tasks.md) § T1._

## Files

- `src/main/java/com/auditlog/service/Order.java` (new) — `enum Order { ASC, DESC }`. Lives in `service` because both `api` and `service` need it and `api → service` is the allowed dependency direction per `AGENTS.md`.
- `src/main/java/com/auditlog/api/AuditEventQueryRequest.java` (new) — record bound from query string.
- `src/main/java/com/auditlog/api/AuditEventPage.java` (new) — response record.
- `src/main/java/com/auditlog/api/AuditEventController.java` (edit) — add `search` handler.

## Signatures / snippets

```java
public enum Order { ASC, DESC }

public record AuditEventQueryRequest(
    @NotNull Instant from,
    @NotNull Instant to,
    String actor,
    String resource,
    Order order,                          // null → service defaults to ASC
    @Min(1) @Max(500) Integer limit,      // null → service defaults to 50
    String cursor) {}

public record AuditEventPage(List<AuditEventResponse> items, String nextCursor) {}

@GetMapping
public AuditEventPage search(@Valid AuditEventQueryRequest request) { ... }
```

## Steps

1. Add `Order` enum in `com.auditlog.service`.
2. Add `AuditEventQueryRequest` with Jakarta validation per design §5 `api`.
3. Add `AuditEventPage` record.
4. Add `@GetMapping` on `AuditEventController` that delegates to `service.search(...)` and maps result items via `AuditEventResponse.from` into an `AuditEventPage`.
5. Defer `from < to`, window ≤ 90d, and cursor decode to the service layer (per design §5 Validation split).

## Tests

`src/test/java/com/auditlog/api/AuditEventControllerTest.java`:

- Valid request returns 200 + `{ items, nextCursor }` shape.
- Missing `from` → 400.
- Missing `to` → 400.
- `limit` < 1 or > 500 → 400.
- Unknown `order` value → 400.
- Service `IllegalArgumentException` mapped to 400 by the existing `ApiExceptionHandler`.
