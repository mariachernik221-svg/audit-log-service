# T5 — Wire service pagination flow end-to-end

_Scope, ACs, DoD: see [tasks.md](../tasks.md) § T5._

## Files

- `src/main/java/com/auditlog/service/AuditEventServiceImpl.java` (edit) — replace the T2 stub with the real repo dispatch + trim + next-cursor logic.

## Signatures / snippets

```java
AuditEventQueryResult search(AuditEventQueryInput input) {
  AuditEventQuery query = /* normalize + validate + resolve T_start from T2 */;

  Instant lastTs = query.position().map(CursorPosition::ts).orElse(null);
  UUID    lastId = query.position().map(CursorPosition::id).orElse(null);

  List<AuditEvent> raw = (query.order() == Order.ASC)
      ? repository.searchAsc (query.actor(), query.resource(), query.from(), query.to(),
                              query.tStart(), lastTs, lastId, Limit.of(query.limit() + 1))
      : repository.searchDesc(query.actor(), query.resource(), query.from(), query.to(),
                              query.tStart(), lastTs, lastId, Limit.of(query.limit() + 1));

  String nextCursor = null;
  List<AuditEvent> items = raw;
  if (raw.size() > query.limit()) {
    items = raw.subList(0, query.limit());
    AuditEvent last = items.get(items.size() - 1);
    nextCursor = cursorCodec.encode(last.getTimestamp(), last.getId(), query);
  }
  return new AuditEventQueryResult(items, nextCursor);
}
```

## Steps

1. Inject `AuditEventRepository` and `CursorCodec` in `AuditEventServiceImpl` (the codec was added in T2; repo is already there).
2. After the T2 logic produces an `AuditEventQuery`, pick `searchAsc` or `searchDesc` based on `query.order()`.
3. Always fetch `Limit.of(limit + 1)`.
4. If the returned list size > `limit`: trim to `limit` and encode `nextCursor` from the last kept `(timestamp, id)` + the originating query.
5. Else: `nextCursor = null`.
6. Return `AuditEventQueryResult(items, nextCursor)`.

## Tests

`src/test/java/com/auditlog/service/AuditEventServiceImplTest.java` (mocked repo):

- `limit + 1` returned → result trimmed to `limit`, `nextCursor` non-null.
- `≤ limit` returned → `nextCursor` is `null`.
- `order = ASC` dispatches to `searchAsc`; `order = DESC` dispatches to `searchDesc`.
- `T_start` decoded from the input cursor is passed to the repo unchanged and re-encoded into the new `nextCursor` unchanged across multi-page walks.
