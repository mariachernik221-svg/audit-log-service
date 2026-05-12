package com.auditlog.service;

import com.auditlog.domain.AuditEvent;
import java.util.List;

public record AuditEventQueryResult(List<AuditEvent> items, String nextCursor) {}
