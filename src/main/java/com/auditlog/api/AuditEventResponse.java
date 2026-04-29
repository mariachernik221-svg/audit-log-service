package com.auditlog.api;

import com.auditlog.domain.AuditEvent;
import com.auditlog.domain.Outcome;
import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(
    UUID id,
    Instant timestamp,
    String actor,
    String action,
    String resource,
    Outcome outcome,
    String context) {
  public static AuditEventResponse from(AuditEvent event) {
    return new AuditEventResponse(
        event.getId(),
        event.getTimestamp(),
        event.getActor(),
        event.getAction(),
        event.getResource(),
        event.getOutcome(),
        event.getContext());
  }
}
