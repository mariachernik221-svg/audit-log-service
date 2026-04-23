package com.auditlog.service;

import com.auditlog.domain.AuditEvent;
import java.time.Instant;
import java.util.List;

public interface AuditEventService {

    AuditEvent create(String actor, String action, String resource,
                      com.auditlog.domain.Outcome outcome, String context);

    List<AuditEvent> findByActor(String actor);

    List<AuditEvent> findByResource(String resource);

    List<AuditEvent> findByTimeRange(Instant from, Instant to);
}
