package com.auditlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.auditlog.domain.AuditEvent;
import com.auditlog.domain.Outcome;
import com.auditlog.repository.AuditEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditEventServiceImplTest {

  @Mock AuditEventRepository repository;

  AuditEventServiceImpl service;
  CursorCodec cursorCodec;

  @BeforeEach
  void setUp() {
    cursorCodec = new CursorCodec();
    service = new AuditEventServiceImpl(repository, cursorCodec);
  }

  @Test
  void createPersistsEventAndStampsTimestampServerSide() {
    Instant before = Instant.now();
    when(repository.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

    AuditEvent result =
        service.create("alice", "user.login", "session:1", Outcome.SUCCESS, "{\"ip\":\"1.2.3.4\"}");

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(repository).save(captor.capture());
    AuditEvent saved = captor.getValue();

    assertThat(saved.getActor()).isEqualTo("alice");
    assertThat(saved.getAction()).isEqualTo("user.login");
    assertThat(saved.getResource()).isEqualTo("session:1");
    assertThat(saved.getOutcome()).isEqualTo(Outcome.SUCCESS);
    assertThat(saved.getContext()).contains("1.2.3.4");
    assertThat(saved.getTimestamp()).isBetween(before, Instant.now());
    assertThat(result).isSameAs(saved);
  }

  @Test
  void createAcceptsNullContext() {
    when(repository.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

    AuditEvent result = service.create("alice", "user.login", "session:1", Outcome.SUCCESS, null);

    assertThat(result.getContext()).isNull();
  }

  @Test
  void createRejectsBlankActor() {
    assertThatThrownBy(() -> service.create("  ", "x", "r", Outcome.SUCCESS, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("actor");
    verifyNoInteractions(repository);
  }

  @Test
  void createRejectsNullActor() {
    assertThatThrownBy(() -> service.create(null, "x", "r", Outcome.SUCCESS, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("actor");
    verifyNoInteractions(repository);
  }

  @Test
  void createRejectsBlankAction() {
    assertThatThrownBy(() -> service.create("alice", "", "r", Outcome.SUCCESS, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("action");
    verifyNoInteractions(repository);
  }

  @Test
  void createRejectsBlankResource() {
    assertThatThrownBy(() -> service.create("alice", "x", " ", Outcome.SUCCESS, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("resource");
    verifyNoInteractions(repository);
  }

  @Test
  void createRejectsNullOutcome() {
    assertThatThrownBy(() -> service.create("alice", "x", "r", null, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("outcome");
    verifyNoInteractions(repository);
  }

  @Test
  void searchReturnsEmptyResultForValidRangeWithoutCursor() {
    Instant from = Instant.parse("2026-04-01T00:00:00Z");
    Instant to = Instant.parse("2026-04-30T00:00:00Z");
    AuditEventQueryInput input =
        new AuditEventQueryInput("alice", "project:42", from, to, Order.ASC, 100, null);

    AuditEventQueryResult result = service.search(input);

    assertThat(result.items()).isEmpty();
    assertThat(result.nextCursor()).isNull();
    verifyNoInteractions(repository);
  }

  @Test
  void searchRejectsNullInput() {
    assertThatThrownBy(() -> service.search(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("input");
    verifyNoInteractions(repository);
  }

  @Test
  void searchRejectsNullFrom() {
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            null, null, null, Instant.parse("2026-04-30T00:00:00Z"), null, null, null);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("from");
  }

  @Test
  void searchRejectsNullTo() {
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            null, null, Instant.parse("2026-04-01T00:00:00Z"), null, null, null, null);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("to");
  }

  @Test
  void searchRejectsFromEqualToTo() {
    Instant t = Instant.parse("2026-04-01T00:00:00Z");
    AuditEventQueryInput input = new AuditEventQueryInput(null, null, t, t, null, null, null);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("from must be before to");
  }

  @Test
  void searchRejectsFromAfterTo() {
    Instant from = Instant.parse("2026-04-30T00:00:00Z");
    Instant to = Instant.parse("2026-04-01T00:00:00Z");
    AuditEventQueryInput input = new AuditEventQueryInput(null, null, from, to, null, null, null);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("from must be before to");
  }

  @Test
  void searchRejectsWindowLongerThan90Days() {
    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    Instant to = from.plus(Duration.ofDays(91));
    AuditEventQueryInput input = new AuditEventQueryInput(null, null, from, to, null, null, null);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("90 days");
  }

  @Test
  void searchAcceptsMatchingCursor() {
    Instant from = Instant.parse("2026-04-01T00:00:00Z");
    Instant to = Instant.parse("2026-04-30T00:00:00Z");
    Instant tStart = Instant.parse("2026-05-01T12:00:00Z");
    AuditEventQuery prior =
        new AuditEventQuery(
            "alice", "project:42", from, to, Order.ASC, 50, java.util.Optional.empty(), tStart);
    String cursor =
        cursorCodec.encode(Instant.parse("2026-04-15T00:00:00Z"), UUID.randomUUID(), prior);

    AuditEventQueryInput input =
        new AuditEventQueryInput("alice", "project:42", from, to, Order.ASC, 50, cursor);

    AuditEventQueryResult result = service.search(input);
    assertThat(result.items()).isEmpty();
    assertThat(result.nextCursor()).isNull();
  }

  @Test
  void searchRejectsCursorWithMismatchedActor() {
    String cursor = encodedCursor("alice", "r", asc("2026-04-01"), asc("2026-04-30"), Order.ASC);
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            "bob", "r", asc("2026-04-01"), asc("2026-04-30"), Order.ASC, 50, cursor);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cursor does not match");
  }

  @Test
  void searchRejectsCursorWithMismatchedResource() {
    String cursor = encodedCursor("a", "r1", asc("2026-04-01"), asc("2026-04-30"), Order.ASC);
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            "a", "r2", asc("2026-04-01"), asc("2026-04-30"), Order.ASC, 50, cursor);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cursor does not match");
  }

  @Test
  void searchRejectsCursorWithMismatchedFrom() {
    String cursor = encodedCursor("a", "r", asc("2026-04-01"), asc("2026-04-30"), Order.ASC);
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            "a", "r", asc("2026-04-02"), asc("2026-04-30"), Order.ASC, 50, cursor);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cursor does not match");
  }

  @Test
  void searchRejectsCursorWithMismatchedTo() {
    String cursor = encodedCursor("a", "r", asc("2026-04-01"), asc("2026-04-30"), Order.ASC);
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            "a", "r", asc("2026-04-01"), asc("2026-04-29"), Order.ASC, 50, cursor);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cursor does not match");
  }

  @Test
  void searchRejectsCursorWithMismatchedOrder() {
    String cursor = encodedCursor("a", "r", asc("2026-04-01"), asc("2026-04-30"), Order.ASC);
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            "a", "r", asc("2026-04-01"), asc("2026-04-30"), Order.DESC, 50, cursor);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cursor does not match");
  }

  @Test
  void searchNormalizesBlankActorAndResourceBeforeCursorComparison() {
    String cursor = encodedCursor(null, null, asc("2026-04-01"), asc("2026-04-30"), Order.ASC);
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            "  ", "", asc("2026-04-01"), asc("2026-04-30"), Order.ASC, 50, cursor);

    AuditEventQueryResult result = service.search(input);
    assertThat(result.items()).isEmpty();
  }

  @Test
  void searchRejectsMalformedCursor() {
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            "a", "r", asc("2026-04-01"), asc("2026-04-30"), Order.ASC, 50, "%%not-base64%%");
    assertThatThrownBy(() -> service.search(input)).isInstanceOf(IllegalArgumentException.class);
  }

  private String encodedCursor(String actor, String resource, Instant from, Instant to, Order o) {
    AuditEventQuery prior =
        new AuditEventQuery(
            actor,
            resource,
            from,
            to,
            o,
            50,
            java.util.Optional.empty(),
            Instant.parse("2026-05-01T00:00:00Z"));
    return cursorCodec.encode(from.plusSeconds(1), UUID.randomUUID(), prior);
  }

  private static Instant asc(String date) {
    return Instant.parse(date + "T00:00:00Z");
  }
}
