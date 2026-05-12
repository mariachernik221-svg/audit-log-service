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
| `actor`    | no       | —       | case-insensitive exact match           |
| `resource` | no       | —       | case-insensitive exact match           |
| `order`    | no       | `asc`   | `asc` or `desc`                        |
| `limit`    | no       | `50`    | 1–500                                  |
| `cursor`   | no       | —       | opaque token from previous page        |

**Response 200** — `{ "items": [...AuditEventResponse], "nextCursor": "<string>" | null }`

**Response 400** — existing `ApiExceptionHandler` body. Triggers: missing/invalid `from`/`to`, `from >= to`, `to - from > 90d`, `limit` out of range, bad `order`, malformed cursor, cursor whose embedded query differs from the current request.

## 3. Query & pagination

**Why keyset over offset.** Offset pagination (`LIMIT n OFFSET m`) makes the DB scan and discard `m` rows for every page, so cost grows linearly with depth — bad fit for audit walks that may traverse millions of events. Offset also breaks the exactly-once guarantee from `requirements.md` US-3 AC3: rows shift under the offset window if anything inserts or reorders mid-walk, producing duplicates or skips. Keyset on `(timestamp, id)` is index-backed, O(log n) per page regardless of depth, and gives stable cursor semantics — combined with the snapshot boundary (`timestamp <= T_start`, see below), each event in the result set is returned exactly once. Cursor stays opaque per US-3 AC4.

- Filter: `lower(actor) = lower(:actor)` / `lower(resource) = lower(:resource)` when present; `timestamp >= :from AND timestamp < :to` always; **plus snapshot boundary `timestamp <= :T_start`**.
- Snapshot boundary `T_start`:
  - First request of a query (no cursor): server sets `T_start = Instant.now()`.
  - Subsequent requests: server decodes `T_start` from the cursor and reuses it for every page of the same query.
  - Effect: events written after the first request are excluded from this query and only become visible to a *new* query. Implements `requirements.md` US-3 AC3.
- Sort: `timestamp <order>, id <order>` — strict total order, deterministic.
- Cursor payload (JSON, then base64url):
  ```
  { ts, id, actor, resource, from, to, order, tStart }
  ```
  Binds position **and** the originating query **and** the snapshot. On next request, server decodes and rejects with `400` if any of `actor`/`resource`/`from`/`to`/`order` differ from the current request. `tStart` is propagated, not validated against the request (client never supplies it).
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

## 5. Component design

Layering per `AGENTS.md`.

**`api`**

- `AuditEventQueryRequest` — record bound from query string with jakarta validation: `@NotNull from/to`, `@Min(1) @Max(500) limit`, `Order` enum, optional `actor`/`resource`/`cursor`.
- `AuditEventPage` — record `{ List<AuditEventResponse> items, String nextCursor }`.
- `AuditEventController.search(@Valid AuditEventQueryRequest)` → calls `service.search(...)`.

**`service`**

- `AuditEventQuery` — value object carrying normalized inputs + decoded cursor.
- `AuditEventServiceImpl.search`:
  1. Normalize blank `actor`/`resource` to `null`.
  2. Semantic checks (throw `IllegalArgumentException`): `from < to`, window ≤ 90d, cursor decodes and matches query.
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

| Layer    | Checks                                                              |
|----------|---------------------------------------------------------------------|
| `api`    | format, required `from`/`to`, `limit` range, `order` enum            |
| `service`| `from < to`, window ≤ 90d, cursor decode + cursor-vs-query match     |

## 6. Testing strategy

**Unit (`service`)**

- Reject `from >= to`, window > 90d, mismatched cursor.
- `limit + 1` probing trims correctly; `nextCursor` null when single page.
- Asc/desc dispatched to correct repository method.
- `CursorCodec` round-trip; rejects malformed input.

**Integration (Spring + Postgres via Testcontainers)**

- Happy paths: actor + range, resource + range, range only, both orders.
- Case-insensitive match.
- `400` cases: missing `from`/`to`, `from >= to`, window > 90d, `limit` out of range, bad cursor, cursor with mismatched filters/order.
- Pagination invariants: union of pages = unpaged query, no duplicates, no gaps — including with concurrent appends inside the range. Events appended after the first page must not appear in any later page of the same cursor walk (snapshot boundary `T_start`).
- Ties on `timestamp` resolved by `id` consistently across page boundaries.
