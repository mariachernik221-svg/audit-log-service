package com.auditlog.service;

import com.auditlog.domain.AuditEvent;
import com.auditlog.domain.Outcome;

public interface AuditEventService {

  AuditEvent create(String actor, String action, String resource, Outcome outcome, String context);
}
