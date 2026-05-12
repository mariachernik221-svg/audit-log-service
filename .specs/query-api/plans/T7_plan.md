# T7 — Integration coverage for filtering, ordering, and pagination invariants

_Scope, ACs, DoD: see [tasks.md](../tasks.md) § T7._

## Files

- `src/test/java/com/auditlog/integration/QueryApiIntegrationTest.java` (new) — `@SpringBootTest` + `MockMvc` + Testcontainers Postgres. Mirrors the `@Testcontainers` setup used in `AuditEventRepositoryTest`.

## Signatures / snippets

```java
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class QueryApiIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) { /* same as AuditEventRepositoryTest */ }

  @Autowired MockMvc mockMvc;
  @Autowired AuditEventRepository repository;   // for seed data only

  // tests below
}
```

## Steps

1. Wire `@SpringBootTest` + Testcontainers Postgres + `MockMvc`.
2. Seed test data through `repository.save(...)`.
3. Hit `GET /audit-events` through `MockMvc` for each scenario below.

## Tests

Happy paths:

- actor + range.
- resource + range.
- range only.
- `order = ASC` and `order = DESC` against the same data set.

Case-insensitivity:

- Inserting `actor = "Alice"` is retrievable with `actor=alice` and vice versa.

400 cases (end-to-end through `ApiExceptionHandler`):

- Missing `from`.
- Missing `to`.
- `from >= to`.
- `to − from > 90d`.
- `limit` out of `1..500`.
- Malformed cursor.
- Cursor whose embedded query differs from the request.

Pagination invariants:

- Walk every page of a query; union equals the unpaged result; no duplicates; no gaps.
- Same-`timestamp` ties: rows that share a `timestamp` and are split across a page boundary remain ordered by `id` consistently.

Snapshot boundary:

- After fetching page 1, insert new rows whose `timestamp > T_start`; subsequent pages of the same cursor walk must not include them.
- A fresh query started after those inserts does include them (new `T_start`).

## Dependencies

- T4 must be applied (indexes present) before this test runs against the migrated schema.
- T5 must be complete so the full service flow exists.
