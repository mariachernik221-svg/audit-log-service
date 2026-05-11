package com.auditlog.service;

import com.auditlog.domain.AuditEvent;
import com.auditlog.domain.Outcome;
import com.auditlog.repository.AuditEventRepository;
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

  private static void requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
