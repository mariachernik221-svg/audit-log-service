# Query API — Requirements

## Problem

Audit events are stored immutably but cannot currently be queried in a structured, paginated way. Compliance officers, SREs, and security analysts need a read-only way to retrieve events by actor, resource, and time range, and to walk through large result sets reliably. Without this, audits, incident timelines, and security analyses cannot be performed against the audit log.

## User Stories

### US-1: Compliance officer confirms or refutes an action

**As a** compliance officer, **I want to** find events for a specific actor within a time range, **so that** I can confirm whether an action took place during an audit.

**AC:**
- A user can retrieve events filtered by actor and a time range.
- The `actor` query parameter accepts a comma-separated list (e.g. `?actor=a1,a2,a3`); an event matches if its actor equals any value in the list, regardless of letter casing.
- The actor list is capped at 10 distinct values after trimming whitespace and removing duplicates; requests carrying more than 10 distinct values are rejected with HTTP 422. Duplicates are dropped silently before the cap is enforced. A list that contains any blank entry between commas (e.g. `?actor=a,,b`, `?actor=alice,bob,`) is rejected with HTTP 422. An entirely blank `actor` value (`?actor=`, only whitespace) is treated as "value not provided" and rejected with HTTP 400. Omitting the `actor` parameter entirely is treated as "no actor filter" and is accepted.
- When no events match, the result is an HTTP 200 response with an empty result set (not a 4xx/5xx error).
- Freshness: an event is visible to a new query as soon as its write transaction has committed; no additional numeric latency guarantee is provided.
- A time range must always be provided; queries missing either the start or end of the range are rejected with HTTP 400.
- Time-range span is capped at 90 days (`to - from <= 90d`); requests above the cap are rejected with HTTP 422 (see `design.md` §2).
- Each returned event exposes the following fields: `id`, `timestamp`, `actor`, `action`, `resource`, `outcome`, and `context`.
- Query endpoints are read-only: a successful or failed query performs no `INSERT`, `UPDATE`, or `DELETE` on the `audit_events` table.

**Error code policy.** All required parameters that are absent or empty are rejected with HTTP 400 ("value not provided"). All parameters that are present but malformed or semantically invalid (bad ISO-8601, `from >= to`, window > 90d, `limit` out of `[1, 500]`, unknown `order`, blank entry inside the actor list, more than 10 actors, malformed cursor, cursor whose embedded query differs from the current request) are rejected with HTTP 422 ("value supplied but invalid"). 422 also applies to the POST `/audit-events` endpoint when a present field carries a blank string (e.g. `"actor": ""`); a JSON body missing the field entirely (e.g. no `outcome` key) is rejected with HTTP 400.

### US-2: SRE reconstructs the timeline of actions on a resource

**As an** SRE, **I want to** retrieve events for a specific resource within a time range, ordered by time, **so that** I can reconstruct what happened during an incident.

**AC:**
- A user can retrieve events filtered by resource and a time range.
- Results are returned in a deterministic time order — two identical queries always produce the same sequence.
- The user can choose chronological (oldest first) or reverse-chronological (most recent first) order.
- Filters by actor and by resource can be combined; results then satisfy both.
- Filtering on actor or resource matches regardless of letter casing, so users do not have to guess how a value was originally written.

### US-3: Security analyst paginates a large result set without loss or duplication

**As a** security analyst, **I want to** walk through a large result set page by page, **so that** I can process every matching event exactly once.

**AC:**
- The user can request results in pages. Default page size is 50 events. The page size is capped at 500; requests above the cap are rejected with HTTP 422.
- When more results are available, the response includes a non-null `nextCursor` field. When the final page has been returned, `nextCursor` is null (or omitted).
- A query establishes a snapshot boundary `T_start` equal to the server time of the first request. All pages of that query return only events with `timestamp <= T_start`. Events appended after `T_start` are not visible until a new query is started. Iterating all pages of a query returns each event with `timestamp <= T_start` exactly once — no duplicates, no missing events.
- The cursor is an opaque token: the server returns it as a string, and the client passes it back unchanged. A cursor that has been modified, truncated, or otherwise tampered with is rejected with HTTP 422. Clients are not expected to parse or construct cursor contents.
- The guarantees above (snapshot boundary, exactly-once iteration, cursor opacity, page-size cap) hold for every supported combination of filters (actor, resource) and order (chronological, reverse-chronological).
- A multi-actor query (`?actor=a1,a2,…`) paginates under the same guarantees as a single-actor query: snapshot boundary, exactly-once iteration, deterministic order, and cursor opacity all hold; the cursor binds the full actor list so reusing a cursor with a different list is rejected with HTTP 422.

## Out of scope

- Creating, updating, or deleting events (events remain append-only).
- Filtering by anything other than actor, resource, and time range (no filtering by action, outcome, or contextual fields).
- Full-text search, aggregations, statistics, or analytics.
- Authentication and authorization rules for the endpoint.
- Rate limiting and per-user quotas.
- Bulk export, file downloads (CSV, etc.), or streaming/subscription delivery.
- User interface or dashboard for browsing events.
- Data retention, archival, or deletion policies.
- Compliance/jurisdictional rules — inherited from the retention policy owned outside this API.
- Query-volume / response-time SLOs — left to operational tuning, not acceptance criteria.
