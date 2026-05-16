# Query API — Requirements

## Problem

Audit events are stored immutably but cannot currently be queried in a structured, paginated way. Compliance officers, SREs, and security analysts need a read-only way to retrieve events by actor, resource, and time range, and to walk through large result sets reliably. Without this, audits, incident timelines, and security analyses cannot be performed against the audit log.

## User Stories

### US-1: Compliance officer confirms or refutes an action

**As a** compliance officer, **I want to** find events for a specific actor within a time range, **so that** I can confirm whether an action took place during an audit.

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
- The user can request results in pages. Default page size is 50 events. The page size is capped at 500; requests above the cap are rejected with HTTP 400.
- When more results are available, the response includes a non-null `nextCursor` field. When the final page has been returned, `nextCursor` is null (or omitted).
- A query establishes a snapshot boundary `T_start` equal to the server time of the first request. All pages of that query return only events with `timestamp <= T_start`. Events appended after `T_start` are not visible until a new query is started. Iterating all pages of a query returns each event with `timestamp <= T_start` exactly once — no duplicates, no missing events.
- The cursor is an opaque token: the server returns it as a string, and the client passes it back unchanged. A cursor that has been modified, truncated, or otherwise tampered with is rejected with HTTP 400. Clients are not expected to parse or construct cursor contents.
- The guarantees above (snapshot boundary, exactly-once iteration, cursor opacity, page-size cap) hold for every supported combination of filters (actor, resource) and order (chronological, reverse-chronological).

## Out of scope

- Creating, updating, or deleting events (events remain append-only).
- Filtering by anything other than actor, resource, and time range (no filtering by action, outcome, or contextual fields).
- Full-text search, aggregations, statistics, or analytics.
- Authentication and authorization rules for the endpoint.
- Rate limiting and per-user quotas.
- Bulk export, file downloads (CSV, etc.), or streaming/subscription delivery.
- User interface or dashboard for browsing events.
- Data retention, archival, or deletion policies.

## Open questions

1. Are there compliance or regulatory constraints (retention windows, jurisdictional rules) that this read API must observe but that are not yet captured?
2. Should the API expose any "freshness" guarantee — i.e., how soon after an event is written must it be visible to a query?
3. Are there expected query-volume or response-time SLOs that should be set as acceptance criteria rather than left to operational tuning?
4. Should there be an upper bound on how far back in time a query may reach (e.g., to align with a retention policy or to protect performance)?
