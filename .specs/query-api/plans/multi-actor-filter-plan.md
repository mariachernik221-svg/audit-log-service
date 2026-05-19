# T8 — Multi-actor filter implementation plan

## Context

`tasks.md` T8 extends the existing single-actor filter to a comma-separated list (cap 10) per `requirements.md` US-1 AC-1.2 / AC-1.3 / AC-1.4 / AC-1.5 / AC-1.6 / AC-1.7 and US-3 AC-3.12, justified by `design.md` §2/§3/§4/§5/§6. Today:

- `AuditEventQueryRequest.actor` (api) — single raw `String`.
- `AuditEventQueryInput.actor` / `AuditEventQuery.actor` (service) — single `String`, blank → `null` (`AuditEventServiceImpl.search` lines 47–48).
- `AuditEventRepository.searchAsc/searchDesc` — single `:actor` param, predicate `lower(e.actor) = lower(cast(:actor as string))`.
- `CursorCodec.DecodedCursor.actor` — single `String`. Cursor-vs-request mismatch check compares single strings (`AuditEventServiceImpl.search` lines 65–69).

Goal: replace the single-value path end-to-end with a normalized `List<String>` (lower-cased, deduped, sorted, ≤10) without breaking the single-actor call shape (`?actor=a1` keeps working — list of one).

User-requested implementation order: **validator → repository → controller**. "Validator" = service-layer normalization + cap-10 enforcement (the layer that owns semantic validation per `design.md` §5 / Validation split). Controller stays a passthrough.

## Critical files

- `src/main/java/com/auditlog/api/AuditEventQueryRequest.java` — bind `actor` as raw comma-string (already a `String`, no change to type; just confirm `@ModelAttribute` keeps the raw value).
- `src/main/java/com/auditlog/api/AuditEventController.java` — pass `request.actor()` (raw string) into `AuditEventQueryInput`.
- `src/main/java/com/auditlog/service/AuditEventQueryInput.java` — keep `String actor` (raw input). No change.
- `src/main/java/com/auditlog/service/AuditEventQuery.java` — replace `String actor` with `List<String> actors` (normalized, sorted, deduped, lower-cased; empty list = no filter).
- `src/main/java/com/auditlog/service/AuditEventServiceImpl.java` — add normalization helper, cap-10 check, change cursor-vs-query compare to list equality, pass list to repository.
- `src/main/java/com/auditlog/service/CursorCodec.java` — rename `DecodedCursor.actor` → `actors` (`List<String>`), encode from `AuditEventQuery.actors()`.
- `src/main/java/com/auditlog/repository/AuditEventRepository.java` — swap `:actor` for `:actors` JPQL `IN`-list (skipped when list empty).
- Tests: `AuditEventServiceImplTest`, `CursorCodecTest`, `AuditEventControllerTest`, `AuditEventRepositoryTest`, `QueryApiIntegrationTest`.

## Implementation — three phases (per user-requested order)

### Phase A — Validator (service-layer normalization + cap-10)

1. Add private helper in `AuditEventServiceImpl`:
   ```java
   private static List<String> normalizeActors(String raw) {
     if (raw == null || raw.isBlank()) return List.of();
     return Arrays.stream(raw.split(","))
         .map(String::trim)
         .filter(s -> !s.isEmpty())
         .map(s -> s.toLowerCase(Locale.ROOT))
         .distinct()
         .sorted()
         .toList();
   }
   ```
2. In `search()`:
   - Replace `String actor = blankToNull(input.actor());` with `List<String> actors = normalizeActors(input.actor());`.
   - After `from`/`to` checks, add: `if (actors.size() > 10) throw new IllegalArgumentException("actor list must not exceed 10 distinct values");`
3. Update `AuditEventQuery` record: `String actor` → `List<String> actors` (defensive `List.copyOf` in canonical ctor or trust upstream — call sites already pass an immutable list).
4. Update construction of `AuditEventQuery` to pass `actors`.

Acceptance after Phase A (unit-test level): blank input → `[]`; `"a,A, b ,,"` → `["a","b"]`; 11-distinct list throws `IllegalArgumentException`; 10-distinct list passes.

### Phase B — Repository (IN-list JPQL)

1. Change both `searchAsc` and `searchDesc` signatures:
   - Replace `@Param("actor") String actor` with `@Param("actors") List<String> actors`.
2. Change the predicate from
   ```
   (cast(:actor as string) is null or lower(e.actor) = lower(cast(:actor as string)))
   ```
   to
   ```
   (:actors is null or lower(e.actor) in :actors)
   ```
   Notes: callers pass `null` when no filter; otherwise pass the already-lower-cased, deduped list — so the JPQL stays `lower(e.actor) in :actors` without an extra `lower()` on the list elements.
3. Repository integration test must cover: IN-list match across 2–3 actors, IN-list with one entry (back-compat), null list (range-only).

Index sanity (no migration changes): existing `idx_audit_events_actor_ts_id` already on `(lower(actor), timestamp, id)` — Postgres planner handles `lower(e.actor) IN (...)` via BitmapOr/loop. `design.md` §4 already documents this — no Flyway change needed.

### Phase C — Controller & cursor codec & tests

1. `AuditEventController.search` — still passes raw `request.actor()` into `AuditEventQueryInput`. No change beyond confirming `@ModelAttribute` preserves comma string as-is (Spring's default binding for `String` does this; no custom converter needed).
2. `CursorCodec.DecodedCursor` — change field `String actor` → `List<String> actors`. Encode path reads from `query.actors()`. Decode path validates non-null list — fail-closed when the field is absent, matching the other required-field checks (`CursorCodec.decode` lines 55–62).
3. In `AuditEventServiceImpl.search`, change cursor-vs-query mismatch check:
   ```java
   if (!Objects.equals(decoded.actors(), actors)        // ordered list equality (both sorted)
       || !Objects.equals(decoded.resource(), resource)
       || …)
   ```
4. Tests:
   - `CursorCodecTest` — round-trip `actors` list (empty, single, multi); reject payload missing `actors`.
   - `AuditEventServiceImplTest` — normalization cases (whitespace, casing, dedupe, blanks), cap-10 throws, single-actor still works, cursor mismatch when list differs.
   - `AuditEventControllerTest` — `?actor=a,b,c` 200; `?actor=` (empty after split) treated as no filter; 11-distinct → 400 via `ApiExceptionHandler`.
   - `AuditEventRepositoryTest` — IN-list filtering with multiple actors, single actor (regression), null list.
   - `QueryApiIntegrationTest` — happy-path multi-actor (`?actor=a1,a2,a3` returns union, case-insensitive); 11-distinct → 400; cursor-mismatch on actor list → 400.

## Out of scope (defer to other tasks)

- Resource multi-value (Q1 answer: actor only).
- New DB index (Q4 answer: justify existing).
- Schema migration.

## Verification (end-to-end)

1. `./gradlew test` — full suite. Specifically expect new tests:
   - `AuditEventServiceImplTest#normalizesActorList…`, `#rejectsActorListAbove10`, `#cursorMismatchOnActorList`.
   - `CursorCodecTest#roundTripsActorList`.
   - `AuditEventControllerTest#…ActorList…`.
   - `AuditEventRepositoryTest#…ActorIn…`.
   - `QueryApiIntegrationTest#multiActorHappyPath`, `#actorListAbove10Returns400`, `#cursorMismatchOnActorListReturns400`.
2. Existing baseline tests (`happyPathFiltersByActorAndRangeAscending`, etc.) must still pass — single-actor regression check.
3. Manual smoke (optional, if dev DB available): `GET /audit-events?from=…&to=…&actor=Alice,bob,ALICE` → 200; same with 11 distinct → 400.

## Rollout notes

- Cursor payload field rename `actor` → `actors` is a wire-breaking change for any in-flight cursor. Acceptable: cursors are server-issued, ephemeral; no migration. Any in-flight cursor minted by the pre-change build is rejected as malformed (`400`), and the client restarts the walk — matches existing cursor-tamper handling.
- Architecture: changes stay inside their respective layers (`api`, `service`, `repository`); `LayerBoundariesTest` should continue to pass without changes.
