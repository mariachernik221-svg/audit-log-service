package com.auditlog.api;

import java.util.List;

public record AuditEventPage(List<AuditEventResponse> items, String nextCursor) {}
