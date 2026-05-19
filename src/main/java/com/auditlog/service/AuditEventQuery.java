package com.auditlog.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public record AuditEventQuery(
    List<String> actors,
    String resource,
    Instant from,
    Instant to,
    Order order,
    int limit,
    Optional<CursorPosition> position,
    Instant tStart) {

  public AuditEventQuery {
    actors = actors == null ? List.of() : List.copyOf(actors);
  }
}
