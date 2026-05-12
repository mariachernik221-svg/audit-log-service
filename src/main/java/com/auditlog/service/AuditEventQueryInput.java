package com.auditlog.service;

import java.time.Instant;

public record AuditEventQueryInput(
    String actor,
    String resource,
    Instant from,
    Instant to,
    Order order,
    Integer limit,
    String cursor) {}
