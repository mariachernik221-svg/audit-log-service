# Query API - Tasks

Estimated size scale: `S` = up to 0.5 day, `M` = 1-2 days, `L` = 2-3 days.

## T1. Add the paginated query endpoint and request/response types

Implements:
- [requirements.md](./requirements.md) - `US-1` AC1, AC2, AC4, AC6
- [requirements.md](./requirements.md) - `US-2` AC1-AC3
- [requirements.md](./requirements.md) - `US-3` AC1-AC2
- [design.md](./design.md) - Section `1. Overview`
- [design.md](./design.md) - Section `2. API contract`
- [design.md](./design.md) - Section `5. Component design` / `api`

Work:
- Add `AuditEventQueryRequest` with query-param binding and API-layer validation for `from`, `to`, `limit`, and `order`.
- Add `AuditEventPage` response type with `items` and `nextCursor`.
- Add `AuditEventController.search(...)` accepting the new request model and returning the paginated response.
- Map service results to `AuditEventResponse`; keep the handler free of any write-path coupling.

Definition of done:
- `GET /audit-events` accepts `from`, `to`, optional `actor`, optional `resource`, optional `order`, optional `limit`, and optional `cursor`.
- Missing or malformed API parameters are rejected through the existing `ApiExceptionHandler` contract.
- Successful responses use `{ items, nextCursor }`.
- Controller tests cover valid request binding and main `400` request-validation failures.

Dependencies:
- None.

Estimated size:
- `M`

## T2. Build the service-layer query model, semantic validation, and cursor codec

Implements:
- [requirements.md](./requirements.md) - `US-1` AC2, AC4, AC5, AC7
- [requirements.md](./requirements.md) - `US-2` AC4-AC5
- [requirements.md](./requirements.md) - `US-3` AC2-AC4
- [design.md](./design.md) - Section `2. API contract` (`400` rules)
- [design.md](./design.md) - Section `3. Query & pagination`
- [design.md](./design.md) - Section `5. Component design` / `service`
- [design.md](./design.md) - Section `5. Component design` / `Validation split`

Work:
- Add `AuditEventQuery` as the service-layer value object carrying normalized filters, time range, order, limit, decoded cursor position, and snapshot boundary `T_start`.
- Add `CursorCodec` using JSON + base64url for encode/decode of cursor payload `{ ts, id, actor, resource, from, to, order, tStart }`.
- Add a `search` method to `AuditEventService` (interface) and `AuditEventServiceImpl` that returns a page result carrying items plus `nextCursor`.
- Enforce semantic rules in service: blank actor/resource normalize to `null`, `from < to`, time window `<= 90d`, malformed cursor rejected, cursor-query mismatch rejected.
- Resolve `T_start`: first request (no cursor) → `Instant.now()`; subsequent requests → value decoded from cursor.

Definition of done:
- Service rejects invalid semantic combinations with `IllegalArgumentException` so the API layer returns `400`.
- Cursor round-trip preserves `ts`, `id`, `actor`, `resource`, `from`, `to`, `order`, and `tStart`.
- Cursor tampering or malformed payloads fail closed with `400`.
- `T_start` is set on the first request and reused unchanged on every subsequent page of the same cursor walk.
- Service unit tests cover semantic validation, cursor round-trip, malformed cursor handling, query-mismatch rejection, and `T_start` resolution.

Dependencies:
- `T1`

Estimated size:
- `M`

## T3. Implement deterministic keyset search in the repository

Implements:
- [requirements.md](./requirements.md) - `US-2` AC2-AC5
- [requirements.md](./requirements.md) - `US-3` AC3-AC5
- [design.md](./design.md) - Section `3. Query & pagination`
- [design.md](./design.md) - Section `5. Component design` / `repository`

Work:
- Add repository methods supporting keyset pagination.
- Implement ascending and descending search variants with strict ordering on `(timestamp, id)`.
- Apply case-insensitive exact-match filtering for `actor` and `resource`.
- Apply the snapshot upper bound `timestamp <= :T_start` alongside the user-supplied range.
- Support `limit + 1` fetches so the service can detect whether a next page exists.

Definition of done:
- Repository can execute range-only, actor+range, resource+range, and actor+resource+range queries.
- Ordering is deterministic for identical queries because results are sorted by `timestamp` then `id`.
- Ascending and descending pagination use the correct keyset predicates and do not rely on offset pagination.
- The snapshot upper bound is honored: events with `timestamp > T_start` are never returned for that cursor walk.
- Repository tests cover filtering, ordering, same-timestamp tie handling, and the snapshot upper bound.

Dependencies:
- `T2`

Estimated size:
- `L`

## T4. Add database indexes for query shapes and cursor traversal

Implements:
- [design.md](./design.md) - Section `4. Data model & persistence`

Work:
- Add Flyway migration `V3__query_api_indexes.sql` with the actor, resource, and timestamp composite indexes from the design.
- Verify the migration applies cleanly on an empty database and does not break existing persistence tests.

Definition of done:
- Migration creates all three designed indexes with the expected expressions and column order.
- Application startup and repository integration tests succeed against a clean migrated database.
- Existing write-path behavior remains unchanged after migration.

Dependencies:
- None.

Estimated size:
- `S`

## T5. Wire service pagination flow end-to-end

Implements:
- [requirements.md](./requirements.md) - `US-3` AC1-AC5
- [design.md](./design.md) - Section `3. Query & pagination`
- [design.md](./design.md) - Section `5. Component design` / `service`

Work:
- Implement service orchestration that selects the repository direction based on `order`.
- Pass `T_start` to the repository as the snapshot upper bound on every page.
- Fetch `limit + 1`, trim to requested page size, and build `nextCursor` from the last kept row plus the originating query plus `T_start`.
- Return `nextCursor = null` when there is no additional page.

Definition of done:
- First page and follow-up pages return correct item counts and continuation-token behavior.
- Ascending and descending requests dispatch to the matching repository method.
- Pagination state is opaque to callers and generated only by the server.
- Every page of the same cursor walk uses the same `T_start` value.
- Service tests cover trimming behavior, `nextCursor` generation, single-page queries, and `T_start` propagation across pages.

Dependencies:
- `T2`
- `T3`

Estimated size:
- `M`

## T6. Add controller and service automated tests for validation and contract behavior

Implements:
- [requirements.md](./requirements.md) - `US-1` AC2, AC4, AC5, AC6
- [requirements.md](./requirements.md) - `US-2` AC2-AC5
- [requirements.md](./requirements.md) - `US-3` AC1-AC4
- [design.md](./design.md) - Section `6. Testing strategy` / `Unit (service)`
- [design.md](./design.md) - Section `6. Testing strategy` / `400` cases

Work:
- Add controller tests for request binding, validation failures, and page response shape.
- Add service unit tests for semantic validation, repository dispatch, cursor behavior, page trimming, and `T_start` resolution.

Definition of done:
- Automated tests cover all specified `400` cases owned by API and service layers.
- Tests verify empty result sets are returned as successful empty pages, not errors.
- Tests verify case normalization inputs and order dispatch behavior.

Dependencies:
- `T1`
- `T2`
- `T5`

Estimated size:
- `M`

## T7. Add integration coverage for filtering, ordering, and pagination invariants

Implements:
- [requirements.md](./requirements.md) - `US-1` AC1-AC7
- [requirements.md](./requirements.md) - `US-2` AC1-AC5
- [requirements.md](./requirements.md) - `US-3` AC1-AC5
- [design.md](./design.md) - Section `6. Testing strategy` / `Integration`

Work:
- Add Spring + Postgres integration tests for actor/time-range, resource/time-range, combined filters, range-only queries, and both sort orders.
- Add integration tests for case-insensitive matches and all specified bad-request scenarios.
- Add pagination invariant tests proving no duplicates and no gaps across pages, including same-timestamp rows.
- Add a snapshot-boundary test: events appended after the first page of a cursor walk must not appear in any later page of that walk.
- Add a freshness test: an event becomes visible to a brand-new query immediately after its write transaction commits.

Definition of done:
- Integration tests prove the endpoint is read-only and returns deterministic ordering across repeated identical queries.
- Paged traversal over a stable query returns the same logical result set as the full traversal of that query.
- Snapshot-boundary test confirms events with `timestamp > T_start` are excluded from the cursor walk and only become visible to a new query.
- Test suite passes with the new Flyway migration applied.

Dependencies:
- `T4`
- `T5`

Estimated size:
- `L`

## T8. Add multi-actor list support to the actor filter

Implements:
- [requirements.md](./requirements.md) - `US-1` AC2, AC3
- [requirements.md](./requirements.md) - `US-3` AC6
- [design.md](./design.md) - Section `2. API contract` (`actor` row, `400` trigger for > 10 distinct actors)
- [design.md](./design.md) - Section `3. Query & pagination` (`IN`-list filter, cursor `actors` payload)
- [design.md](./design.md) - Section `4. Data model & persistence` (existing index justification for the IN-list)
- [design.md](./design.md) - Section `5. Component design` / `service` (normalization step)
- [design.md](./design.md) - Section `5. Component design` / `Validation split`
- [design.md](./design.md) - Section `6. Testing strategy`

Work:
- Accept `actor` as a raw comma-separated string at the API layer; pass it through unchanged to the service.
- In the service, normalize the actor input: split on `,`, trim each element, drop blanks, lower-case, dedupe, sort. Empty result → no actor filter (`null` / empty list).
- Enforce a normalized-size cap of 10; throw `IllegalArgumentException` (→ HTTP 400) when exceeded.
- Replace the single-value `lower(actor) = lower(:actor)` repository predicate with `lower(actor) IN (:actors)`, applied only when the normalized actor list is non-empty.
- Extend `CursorCodec` payload: rename `actor` to `actors` carrying the sorted lower-cased list; on decode, reject with `400` when the request's normalized actor list does not exactly equal the cursor's `actors`.
- Update integration and unit tests to exercise multi-actor matching, normalization, cap rejection, and cursor-vs-request actor-list mismatch.

Definition of done:
- `?actor=a1,a2,a3` returns events whose actor matches any of the listed values, case-insensitive.
- Lists containing whitespace, duplicates, or empty entries are normalized; the 10-cap is enforced on the normalized list.
- Requests with more than 10 distinct actors after normalization fail with HTTP 400 via the existing `ApiExceptionHandler`.
- The cursor binds the normalized actor list; reusing a cursor against a request with a different actor list fails with HTTP 400.
- Single-actor (`?actor=a1`) behavior remains unchanged.
- Unit tests cover normalization rules, cap rejection, IN-list dispatch, and cursor mismatch on actor list.
- Integration tests cover the multi-actor happy path, the > 10-actor 400, and cursor mismatch on actor list.

Dependencies:
- `T1`
- `T2`
- `T3`
- `T5`

Estimated size:
- `M`

## Suggested execution order

1. `T1`
2. `T2`
3. `T3`
4. `T4`
5. `T5`
6. `T6`
7. `T7`
8. `T8`

## Notes

- `T4` can be delivered in parallel with `T1`-`T3`, but `T7` should run only after the migration is in place.
- `T8` extends the single-actor baseline; it touches `T1`, `T2`, `T3`, and `T5` artifacts and is best scheduled after the baseline query API is in place.
- The previous simple read endpoint has already been removed; this work builds the query API from scratch rather than modifying an existing one.
