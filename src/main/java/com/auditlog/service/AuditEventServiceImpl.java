package com.auditlog.service;

import com.auditlog.domain.AuditEvent;
import com.auditlog.domain.Outcome;
import com.auditlog.repository.AuditEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditEventServiceImpl implements AuditEventService {

  private static final int DEFAULT_LIMIT = 50;
  private static final Order DEFAULT_ORDER = Order.ASC;
  private static final Duration MAX_WINDOW = Duration.ofDays(90);

  private final AuditEventRepository repository;
  private final CursorCodec cursorCodec;

  public AuditEventServiceImpl(AuditEventRepository repository, CursorCodec cursorCodec) {
    this.repository = repository;
    this.cursorCodec = cursorCodec;
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
  public AuditEventQueryResult search(AuditEventQueryInput input) {
    Objects.requireNonNull(input, "input must not be null");

    String actor = blankToNull(input.actor());
    String resource = blankToNull(input.resource());
    Order order = input.order() != null ? input.order() : DEFAULT_ORDER;
    int limit = input.limit() != null ? input.limit() : DEFAULT_LIMIT;

    Objects.requireNonNull(input.from(), "from must not be null");
    Objects.requireNonNull(input.to(), "to must not be null");
    if (!input.from().isBefore(input.to())) {
      throw new IllegalArgumentException("from must be before to");
    }
    if (Duration.between(input.from(), input.to()).compareTo(MAX_WINDOW) > 0) {
      throw new IllegalArgumentException("time window must not exceed 90 days");
    }

    Optional<CursorPosition> position;
    Instant tStart;
    if (input.cursor() != null && !input.cursor().isBlank()) {
      CursorCodec.DecodedCursor decoded = cursorCodec.decode(input.cursor());
      if (!Objects.equals(decoded.actor(), actor)
          || !Objects.equals(decoded.resource(), resource)
          || !Objects.equals(decoded.from(), input.from())
          || !Objects.equals(decoded.to(), input.to())
          || decoded.order() != order) {
        throw new IllegalArgumentException("cursor does not match the current query");
      }
      position = Optional.of(new CursorPosition(decoded.ts(), decoded.id()));
      tStart = decoded.tStart();
    } else {
      position = Optional.empty();
      tStart = Instant.now();
    }

    AuditEventQuery query =
        new AuditEventQuery(
            actor, resource, input.from(), input.to(), order, limit, position, tStart);

    // Repository wiring lands in T5. Stub returns an empty page.
    return stubResult(query);
  }

  private AuditEventQueryResult stubResult(AuditEventQuery query) {
    return new AuditEventQueryResult(List.of(), null);
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
