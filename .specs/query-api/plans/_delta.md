**T2_plan.md and T5_plan.md**

- CursorCodec.encode(ts, id, query) — design.md says encode(ts, id, query, tStart). Plan elides tStart parameter on the
  assumption it lives inside AuditEventQuery.

**T3_plan.md and T5_plan.md**

- design.md §5 repository: setMaxResults(limit + 1) for next-page detection. Plan uses Spring Data Limit parameter
  instead.
