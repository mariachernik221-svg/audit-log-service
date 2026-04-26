package com.auditlog.service;

import com.auditlog.domain.AuditEvent;
import com.auditlog.domain.Outcome;
import java.time.Instant;
import java.util.List;

public interface AuditEventService {

    AuditEvent create(String actor, String action, String resource, Outcome outcome, String context);

    List<AuditEvent> search(String actor, String resource, Instant from, Instant to);
}
