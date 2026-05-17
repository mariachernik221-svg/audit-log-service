# Query API — Design

Implements [`requirements.md`](./requirements.md).

## 1. Overview

`GET /audit-events` — read-only endpoint returning audit events filtered by `actor`, `resource`, and a required time range, sorted deterministically, paginated by opaque cursor.

## 2. API contract

**Request** — `GET /audit-events`

| Param      | Required | Default | Notes                                  |
|------------|----------|---------|----------------------------------------|
| `from`     | yes      | —       | ISO-8601, inclusive                    |
| `to`       | yes      | —       | ISO-8601, exclusive                    |
| `actor`    | no       | —       | case-insensitive exact match; comma-separated list, max 10 distinct values after trim + dedupe |
| `resource` | no       | —       | case-insensitive exact match           |
| `order`    | no       | `asc`   | `asc` or `desc`                        |
| `limit`    | no       | `50`    | 1–500                                  |
| `cursor`   | no       | —       | opaque token from previous page        |

**Response 200** — `{ "items": [...AuditEventResponse], "nextCursor": "<string>" | null }`

**Error responses.** All errors share the existing `ApiExceptionHandler` body. Status code is selected by whether the offending value was missing or invalid:

- **Response 400 — value not provided.** Required parameter absent (`from`, `to`), or `actor` parameter present but empty/whitespace-only (treated as "no value supplied"). On POST `/audit-events`, a missing JSON field that the schema declares `@NotNull` (e.g. `outcome` key absent) also returns 400.
- **Response 422 — value supplied but invalid.** Malformed ISO-8601 in `from`/`to`, `from >= to`, `to - from > 90d`, `limit` out of `[1, 500]`, unknown `order`, malformed cursor (bad base64/JSON/missing fields), cursor whose embedded query differs from the current request, more than 10 distinct actors after trim + dedupe, `actor` list with a blank entry between commas. On POST `/audit-events`, a present-but-blank string field (e.g. `"actor": ""`) also returns 422.

## 3. Query & pagination

**Why keyset over offset.** Offset pagination (`LIMIT n OFFSET m`) makes the DB scan and discard `m` rows for every page, so cost grows linearly with depth — bad fit for audit walks that may traverse millions of events. Offset also breaks the exactly-once guarantee from `requirements.md` US-3 AC3: rows shift under the offset window if anything inserts or reorders mid-walk, producing duplicates or skips. Keyset on `(timestamp, id)` is index-backed, O(log n) per page regardless of depth, and gives stable cursor semantics — combined with the snapshot boundary (`timestamp <= T_start`, see below), each event in the result set is returned exactly once. Cursor stays opaque per US-3 AC4.

- Filter: `lower(actor) IN (lower(:a1), …, lower(:aN))` when an actor list is present (N ≤ 10) / `lower(resource) = lower(:resource)` when present; `timestamp >= :from AND timestamp < :to` always; **plus snapshot boundary `timestamp <= :T_start`**.
- Snapshot boundary `T_start`:
  - First request of a query (no cursor): server sets `T_start = Instant.now()`.
  - Subsequent requests: server decodes `T_start` from the cursor and reuses it for every page of the same query.
  - Effect: events written after the first request are excluded from this query and only become visible to a *new* query. Implements `requirements.md` US-3 AC3.
- Freshness (`requirements.md` US-1 AC3): a committed event with `timestamp <= T_start` is included in the result set on the very next query — read-after-commit visibility falls out of running the snapshot query at `READ COMMITTED` against the append-only table. No write-side buffering or async indexing is introduced.
- Sort: `timestamp <order>, id <order>` — strict total order, deterministic.
- Cursor payload (JSON, then base64url):
  ```
  { ts, id, actors, resource, from, to, order, tStart }
  ```
  `actors` is a sorted list of the lower-cased, deduped actor names from the originating request (empty when no actor filter was supplied). Binds position **and** the originating query **and** the snapshot. On next request, server decodes and rejects with `400` if any of `actors`/`resource`/`from`/`to`/`order` differ from the current request (the request's actor list is normalized — trim, lower-case, dedupe, sort — before comparison). `tStart` is propagated, not validated against the request (client never supplies it).
- Keyset predicate (asc): `timestamp > :ts OR (timestamp = :ts AND id > :id)`. Reverse comparators for `desc`.
- Exactly-once iteration follows from: append-only invariant + strict keyset comparison + total order on `(timestamp, id)` + snapshot boundary `timestamp <= T_start` (prevents events appended mid-walk from entering the result set).

## 4. Data model & persistence

No schema changes. New Flyway migration `V3__query_api_indexes.sql`:

```sql
CREATE INDEX idx_audit_events_actor_ts_id    ON audit_events (lower(actor),    timestamp, id);
CREATE INDEX idx_audit_events_resource_ts_id ON audit_events (lower(resource), timestamp, id);
CREATE INDEX idx_audit_events_ts_id          ON audit_events (timestamp, id);
```

Covers the three filter shapes (actor + range, resource + range, range only) and supports the cursor.

**Multi-actor IN-list — why no new index.** For an `IN (lower(:a1), …, lower(:aN))` predicate with `N ≤ 10`, the Postgres planner uses `idx_audit_events_actor_ts_id` either as a `BitmapOr` over per-value bitmap index scans or as a parameterized index range scan looped over each value; in both shapes the leading `lower(actor)` column resolves the membership test and the trailing `(timestamp, id)` columns serve the range predicate and keyset ordering. A new index (e.g. on `actor` alone, a hash index, or a GIN array index) would either drop the `(timestamp, id)` suffix — forcing a re-sort and a separate filter step that defeats keyset pagination — or duplicate the existing index for no measurable gain at this list size, while adding write-amplification on the hot append path. The actor-list cap of 10 is sized to stay within the regime where this planner shape remains cheap.

## 5. Component design

Layering per `AGENTS.md`.

**`api`**

- `AuditEventQueryRequest` — record bound from query string with jakarta validation: `@NotNull from/to`, `@Min(1) @Max(500) limit`, `Order` enum, optional `actor` (raw comma-separated string from the query string, normalized in the service layer)/`resource`/`cursor`.
- `AuditEventPage` — record `{ List<AuditEventResponse> items, String nextCursor }`.
- `AuditEventController.search(@Valid AuditEventQueryRequest)` → calls `service.search(...)`.

**`service`**

- `AuditEventQuery` — value object carrying normalized inputs + decoded cursor.
- `AuditEventServiceImpl.search`:
  1. Normalize `actor`: when the parameter is omitted (`null`) → no actor filter. When present but entirely blank → `IllegalArgumentException` (→ 400). Otherwise split on `,`, trim each piece, reject any blank entry with `IllegalArgumentException` (→ 400), lower-case, dedupe, sort. Normalize blank `resource` to `null`.
  2. Semantic checks (throw `IllegalArgumentException`): `from < to`, window ≤ 90d, normalized actor list size ≤ 10, cursor decodes and matches query.
  3. Resolve `T_start`: if cursor present → use `tStart` from cursor; else → `Instant.now()`.
  4. Call repository with `limit + 1`, passing `T_start` as upper bound alongside `from`/`to`.
  5. If result size > `limit`, trim and build `nextCursor` from the last kept row + the query + `T_start`; else `nextCursor = null`.
- `CursorCodec` — `encode(ts, id, query)` / `decode(raw)`. The query already carries `tStart`, so it is encoded from there rather than passed as a separate argument. JSON + base64url. No signing — all tampering paths end in `400`.

**`repository`**

Two JPQL queries, one per direction (asc/desc), each with the keyset predicate. Service picks one based on `order`.

```java
List<AuditEvent> searchAsc(...);
List<AuditEvent> searchDesc(...);
```

Each method takes a `org.springframework.data.domain.Limit` parameter; the service passes `Limit.of(limit + 1)` for next-page detection.

**Validation split**

| Layer    | Checks                                                              | Failure → status |
|----------|---------------------------------------------------------------------|-------------------|
| `api`    | required `from`/`to` present                                        | 400 |
| `api`    | ISO-8601 format, `limit` in `[1, 500]`, `order` enum value           | 422 |
| `service`| `actor` parameter present but blank                                  | 400 (`MissingRequestValueException`) |
| `service`| `from < to`, window ≤ 90d, actor list ≤ 10 distinct values after trim + dedupe, no blank entry inside list, cursor decode + cursor-vs-query match | 422 (`IllegalArgumentException`) |

## 6. Testing strategy

**Unit (`service`)**

- Reject `from >= to`, window > 90d, mismatched cursor, actor list with > 10 distinct values after normalization, `actor` parameter present but entirely blank, actor list with a blank entry between commas.
- Actor list normalization: trim, reject blanks (whole value or any entry), lower-case, dedupe, sort; duplicates are collapsed before the 10-cap is enforced.
- `limit + 1` probing trims correctly; `nextCursor` null when single page.
- Asc/desc dispatched to correct repository method.
- `CursorCodec` round-trip; rejects malformed input.

**Integration (Spring + Postgres via Testcontainers)**

- Happy paths: actor + range, resource + range, range only, both orders.
- Case-insensitive match.
- `400` cases (value not provided): missing `from`/`to`, `actor` parameter present but empty/whitespace-only.
- `422` cases (value supplied but invalid): `from >= to`, window > 90d, `limit` out of range, bad cursor, cursor with mismatched filters/order, actor list with > 10 distinct values, blank entry inside the actor list, malformed ISO-8601, unknown `order`.
- Multi-actor happy path: `?actor=a1,a2,a3` returns the union of events whose actor matches any list entry (case-insensitive); pagination, ordering, and snapshot guarantees match the single-actor case.
- Pagination invariants: union of pages = unpaged query, no duplicates, no gaps — including with concurrent appends inside the range. Events appended after the first page must not appear in any later page of the same cursor walk (snapshot boundary `T_start`).
- Ties on `timestamp` resolved by `id` consistently across page boundaries.
