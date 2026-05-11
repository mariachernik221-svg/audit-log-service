# T2 — Service-layer query model, semantic validation, cursor codec

_Scope, ACs, DoD: see [tasks.md](../tasks.md) § T2._

## Files

- `src/main/java/com/auditlog/service/AuditEventQueryInput.java` (new) — entry-point input from controller.
- `src/main/java/com/auditlog/service/AuditEventQuery.java` (new) — internal normalized value object, including decoded cursor position and `T_start`.
- `src/main/java/com/auditlog/service/CursorPosition.java` (new) — `(ts, id)` pair.
- `src/main/java/com/auditlog/service/AuditEventQueryResult.java` (new) — service-level page: `List<AuditEvent>` + `nextCursor`.
- `src/main/java/com/auditlog/service/CursorCodec.java` (new) — JSON + base64url encode/decode of the cursor payload.
- `src/main/java/com/auditlog/service/AuditEventService.java` (edit) — add `search`.
- `src/main/java/com/auditlog/service/AuditEventServiceImpl.java` (edit) — implement `search` with semantic checks and `T_start` resolution; real repo call is wired in T5.
- `src/main/java/com/auditlog/api/AuditEventController.java` (edit) — build `AuditEventQueryInput`, call `service.search`, wrap result in `AuditEventPage`.

## Signatures / snippets

```java
public record AuditEventQueryInput(
    String actor, String resource,
    Instant from, Instant to,
    Order order, Integer limit,
    String cursor) {}

public record AuditEventQuery(
    String actor, String resource,        // normalized: blank → null
    Instant from, Instant to,
    Order order, int limit,
    Optional<CursorPosition> position,    // empty on first page
    Instant tStart) {}

public record CursorPosition(Instant ts, UUID id) {}

public final class CursorCodec {
  public String encode(Instant ts, UUID id, AuditEventQuery query);
  public DecodedCursor decode(String raw);   // throws IllegalArgumentException on tamper/malformed

  public record DecodedCursor(
      Instant ts, UUID id,
      String actor, String resource,
      Instant from, Instant to,
      Order order, Instant tStart) {}
}

public record AuditEventQueryResult(List<AuditEvent> items, String nextCursor) {}

public interface AuditEventService {
  AuditEvent create(...);                                       // existing
  AuditEventQueryResult search(AuditEventQueryInput input);     // new
}
```

## Steps

1. Add the new records / classes listed above.
2. Add `search` to `AuditEventService`.
3. In `AuditEventServiceImpl.search`:
   - Normalize blank `actor` / `resource` to `null`; apply `limit` default 50; apply `order` default `ASC`.
   - Semantic checks (throw `IllegalArgumentException`): `from < to`; `to − from ≤ 90d`; if `cursor != null` → `CursorCodec.decode`; reject if any of `actor` / `resource` / `from` / `to` / `order` differ from the request.
   - Resolve `T_start`: cursor present → `decoded.tStart`; else → `Instant.now()`.
   - Build the internal `AuditEventQuery` value object.
   - Return a stub `AuditEventQueryResult(List.of(), null)`. The real repo call lands in T5.
4. Update `AuditEventController.search` to construct `AuditEventQueryInput`, call `service.search`, and map result items via `AuditEventResponse.from` into `AuditEventPage`.

## Tests

`src/test/java/com/auditlog/service/AuditEventServiceImplTest.java` and a new `CursorCodecTest`:

- `CursorCodec` round-trip preserves all eight fields.
- `CursorCodec.decode` rejects malformed base64, truncated payloads, and non-JSON.
- `search` rejects `from >= to`.
- `search` rejects `to − from > 90d`.
- `search` rejects a cursor whose embedded query differs from the current request.
- `T_start = now` when no cursor; `T_start` from cursor when cursor is present.
- Blank `actor` / `resource` normalized to `null`; default `limit = 50`; default `order = ASC`.
