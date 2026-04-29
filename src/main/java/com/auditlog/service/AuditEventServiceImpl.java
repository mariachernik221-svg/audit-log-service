package com.auditlog.service;

import com.auditlog.domain.AuditEvent;
import com.auditlog.domain.Outcome;
import com.auditlog.repository.AuditEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditEventServiceImpl implements AuditEventService {

  private final AuditEventRepository repository;

  public AuditEventServiceImpl(AuditEventRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional
  public AuditEvent create(
      String actor, String action, String resource, Outcome outcome, String context) {
    requireNonBlank(actor, "actor");
    requireNonBlank(action, "action");
    requireNonBlank(resource, "resource");
    Objects.requireNonNull(outcome, "outcome must not be null");
    return repository.save(new AuditEvent(actor, action, resource, outcome, context));
  }

  @Override
  @Transactional(readOnly = true)
  public List<AuditEvent> search(String actor, String resource, Instant from, Instant to) {
    String normalizedActor = blankToNull(actor);
    String normalizedResource = blankToNull(resource);

    boolean hasAnyFilter =
        normalizedActor != null || normalizedResource != null || from != null || to != null;
    if (!hasAnyFilter) {
      throw new IllegalArgumentException(
          "search requires at least one filter: actor, resource, from, or to");
    }
    if (from != null && to != null && !from.isBefore(to)) {
      throw new IllegalArgumentException("from must be before to");
    }

    return repository.search(normalizedActor, normalizedResource, from, to);
  }

  private static void requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }

  private static String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value;
  }
}
