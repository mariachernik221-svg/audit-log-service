# Query API — Requirements

## Problem

Audit events are stored immutably but cannot currently be queried in a structured, paginated way. Compliance officers, SREs, and security analysts need a read-only way to retrieve events by actor, resource, and time range, and to walk through large result sets reliably. Without this, audits, incident timelines, and security analyses cannot be performed against the audit log.

## User Stories

### US-1: Compliance officer confirms or refutes an action

**As a** compliance officer, **I want to** find events for a specific actor within a time range, **so that** I can confirm whether an action took place during an audit.

**AC:** (EARS form: `WHEN <trigger> THEN <observable outcome>`)

- **AC-1.1** WHEN client sends GET with `actor=<a>` AND `from=T1&to=T2` THEN response 200 contains only events whose `actor` equals `<a>` (case-insensitive) AND `T1 <= timestamp < T2`.
- **AC-1.2** WHEN client sends `actor=a1,a2,a3` (comma-separated) THEN response 200 contains only events whose `actor` equals any value in the list (case-insensitive).
- **AC-1.3** WHEN client sends `actor` list with more than 10 distinct values (after trim + dedup) THEN response is HTTP 422.
- **AC-1.4** WHEN client sends `actor` list containing duplicate values THEN duplicates are dropped silently before the 10-value cap is enforced.
- **AC-1.5** WHEN client sends `actor` list with a blank entry between commas (e.g. `?actor=a,,b`, `?actor=alice,bob,`) THEN response is HTTP 422.
- **AC-1.6** WHEN client sends `actor=` or `actor=<whitespace>` THEN response is HTTP 400 ("value not provided").
- **AC-1.7** WHEN client omits the `actor` parameter entirely THEN no actor filter is applied AND response is 200.
- **AC-1.8** WHEN query matches no events THEN response is HTTP 200 with an empty result set.
- **AC-1.9** WHEN an event's write transaction has committed THEN that event is visible to any new query started after commit (no additional numeric latency guarantee).
- **AC-1.10** WHEN client sends request missing `from` OR `to` THEN response is HTTP 400.
- **AC-1.11** WHEN `to - from > 90 days` THEN response is HTTP 422 (see `design.md` §2).
- **AC-1.12** WHEN response 200 returns events THEN each event exposes fields `id`, `timestamp`, `actor`, `action`, `resource`, `outcome`, `context`.
- **AC-1.13** WHEN client sends any GET query (success or failure) THEN no `INSERT`, `UPDATE`, or `DELETE` is executed on `audit_events`.

**Error code policy.** All required parameters that are absent or empty are rejected with HTTP 400 ("value not provided"). All parameters that are present but malformed or semantically invalid (bad ISO-8601, `from >= to`, window > 90d, `limit` out of `[1, 500]`, unknown `order`, blank entry inside the actor list, more than 10 actors, malformed cursor, cursor whose embedded query differs from the current request) are rejected with HTTP 422 ("value supplied but invalid"). 422 also applies to the POST `/audit-events` endpoint when a present field carries a blank string (e.g. `"actor": ""`); a JSON body missing the field entirely (e.g. no `outcome` key) is rejected with HTTP 400.

### US-2: SRE reconstructs the timeline of actions on a resource

**As an** SRE, **I want to** retrieve events for a specific resource within a time range, ordered by time, **so that** I can reconstruct what happened during an incident.

**AC:** (EARS form: `WHEN <trigger> THEN <observable outcome>`)

- **AC-2.1** WHEN client sends GET with `resource=<r>` AND `from=T1&to=T2` THEN response 200 contains only events whose `resource` equals `<r>` (case-insensitive) AND `T1 <= timestamp < T2`.
- **AC-2.2** WHEN two identical queries are issued against an unchanged dataset THEN both return events in the same sequence.
- **AC-2.3** WHEN client sends `order=asc` THEN events are ordered chronologically (oldest first).
- **AC-2.4** WHEN client sends `order=desc` THEN events are ordered reverse-chronologically (most recent first).
- **AC-2.5** WHEN client sends both `actor` AND `resource` filters THEN response 200 contains only events that satisfy both filters.
- **AC-2.6** WHEN client sends `actor` or `resource` value differing only in letter case from the stored value THEN that event matches.

### US-3: Security analyst paginates a large result set without loss or duplication

**As a** security analyst, **I want to** walk through a large result set page by page, **so that** I can process every matching event exactly once.

**AC:** (EARS form: `WHEN <trigger> THEN <observable outcome>`)

- **AC-3.1** WHEN client omits the `limit` parameter THEN default page size is 50.
- **AC-3.2** WHEN client sends `limit > 500` THEN response is HTTP 422.
- **AC-3.3** WHEN more results exist beyond the current page THEN response includes a non-null `nextCursor` field.
- **AC-3.4** WHEN the final page has been returned THEN `nextCursor` is null (or omitted).
- **AC-3.5** WHEN the first request of a query is received THEN server records `T_start` equal to current server time.
- **AC-3.6** WHEN client paginates through a query whose snapshot boundary is `T_start` THEN every page returns only events with `timestamp <= T_start`.
- **AC-3.7** WHEN events are appended after `T_start` THEN those events are not visible to any subsequent page of the original query.
- **AC-3.8** WHEN client iterates through all pages of a query THEN each event with `timestamp <= T_start` is returned exactly once (no duplicates, no missing events).
- **AC-3.9** WHEN server returns `nextCursor` THEN client passes it back unchanged in the next request (no parsing or construction by client).
- **AC-3.10** WHEN client sends a `cursor` that has been modified, truncated, or otherwise tampered with THEN response is HTTP 422.
- **AC-3.11** WHEN snapshot/exactly-once/opacity/page-cap guarantees apply THEN they hold for every supported combination of `actor`, `resource`, and `order` (asc/desc).
- **AC-3.12** WHEN client reuses a cursor from a multi-actor query with a different actor list THEN response is HTTP 422 (cursor binds the full actor list).

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
