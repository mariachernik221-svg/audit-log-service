# T3 — Deterministic keyset search in the repository

_Scope, ACs, DoD: see [tasks.md](../tasks.md) § T3._

## Files

- `src/main/java/com/auditlog/repository/AuditEventRepository.java` (edit) — add `searchAsc` and `searchDesc`.

## Signatures / snippets

Spring Boot 3.4.4 → Spring Data 3.4 → `org.springframework.data.domain.Limit` parameter is available.

```java
@Query("""
    select e from AuditEvent e
    where (cast(:actor    as string) is null or lower(e.actor)    = lower(:actor))
      and (cast(:resource as string) is null or lower(e.resource) = lower(:resource))
      and e.timestamp >= :from
      and e.timestamp <  :to
      and e.timestamp <= :tStart
      and (cast(:lastTs as java.time.Instant) is null
           or e.timestamp > :lastTs
           or (e.timestamp = :lastTs and e.id > :lastId))
    order by e.timestamp asc, e.id asc
    """)
List<AuditEvent> searchAsc(
    @Param("actor") String actor,
    @Param("resource") String resource,
    @Param("from") Instant from,
    @Param("to") Instant to,
    @Param("tStart") Instant tStart,
    @Param("lastTs") Instant lastTs,
    @Param("lastId") UUID lastId,
    Limit limit);

// Same shape, reversed comparators, `order by e.timestamp desc, e.id desc`:
List<AuditEvent> searchDesc(...);
```

## Steps

1. Add both JPQL queries with the keyset predicates from design §3.
2. `lastTs` / `lastId` are `null` on the first page; the service passes them from the decoded cursor on subsequent pages.
3. The service (in T5) calls these methods with `Limit.of(limit + 1)` so it can detect whether a next page exists.

## Tests

`src/test/java/com/auditlog/repository/AuditEventRepositoryTest.java` (Testcontainers Postgres):

- Range-only search, ascending.
- Range-only search, descending.
- actor + range, resource + range, actor + resource + range.
- Case-insensitive match for `actor` and `resource`.
- Rows with the same `timestamp` are ordered by `id`.
- Keyset predicate skips strictly past `(lastTs, lastId)` — no row at `(lastTs, lastId)` reappears.
- Snapshot upper bound: rows with `timestamp > T_start` are excluded.
