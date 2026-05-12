package com.auditlog.service;

import java.time.Instant;
import java.util.Optional;

public record AuditEventQuery(
    String actor,
    String resource,
    Instant from,
    Instant to,
    Order order,
    int limit,
    Optional<CursorPosition> position,
    Instant tStart) {}
