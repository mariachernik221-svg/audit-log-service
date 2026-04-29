package com.auditlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.auditlog.domain.AuditEvent;
import com.auditlog.domain.Outcome;
import com.auditlog.repository.AuditEventRepository;
import java.time.Instant;
import java.util.List;
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

  @BeforeEach
  void setUp() {
    service = new AuditEventServiceImpl(repository);
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
  void searchByActorOnlyDelegatesWithNullsForOthers() {
    AuditEvent event = new AuditEvent("alice", "x", "r", Outcome.SUCCESS, null);
    when(repository.search(eq("alice"), isNull(), isNull(), isNull())).thenReturn(List.of(event));

    assertThat(service.search("alice", null, null, null)).containsExactly(event);
    verify(repository).search("alice", null, null, null);
  }

  @Test
  void searchByResourceOnlyDelegates() {
    AuditEvent event = new AuditEvent("alice", "x", "project:42", Outcome.SUCCESS, null);
    when(repository.search(isNull(), eq("project:42"), isNull(), isNull()))
        .thenReturn(List.of(event));

    assertThat(service.search(null, "project:42", null, null)).containsExactly(event);
  }

  @Test
  void searchByTimeRangeOnlyDelegates() {
    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    Instant to = from.plusSeconds(60);
    when(repository.search(isNull(), isNull(), eq(from), eq(to))).thenReturn(List.of());

    service.search(null, null, from, to);
    verify(repository).search(null, null, from, to);
  }

  @Test
  void searchByAllFiltersCombined() {
    Instant from = Instant.parse("2026-03-02T00:00:00Z");
    Instant to = Instant.parse("2026-03-06T00:00:00Z");
    AuditEvent event =
        new AuditEvent("mark.smith", "payment.charge", "payment-service", Outcome.SUCCESS, null);
    when(repository.search("mark.smith", "payment-service", from, to)).thenReturn(List.of(event));

    assertThat(service.search("mark.smith", "payment-service", from, to)).containsExactly(event);
  }

  @Test
  void searchTreatsBlankActorAndResourceAsNull() {
    when(repository.search(isNull(), isNull(), any(Instant.class), any(Instant.class)))
        .thenReturn(List.of());

    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    service.search("  ", "", from, from.plusSeconds(60));

    verify(repository).search(null, null, from, from.plusSeconds(60));
  }

  @Test
  void searchRejectsAllNullsOrBlanks() {
    assertThatThrownBy(() -> service.search(null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one filter");
    assertThatThrownBy(() -> service.search("", "  ", null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one filter");
    verifyNoInteractions(repository);
  }

  @Test
  void searchAcceptsOpenEndedRangeFromOnly() {
    Instant from = Instant.parse("2025-02-24T00:00:00Z");
    when(repository.search(isNull(), isNull(), eq(from), isNull())).thenReturn(List.of());

    service.search(null, null, from, null);

    verify(repository).search(null, null, from, null);
  }

  @Test
  void searchAcceptsOpenEndedRangeToOnly() {
    Instant to = Instant.parse("2026-01-01T00:00:00Z");
    when(repository.search(isNull(), isNull(), isNull(), eq(to))).thenReturn(List.of());

    service.search(null, null, null, to);

    verify(repository).search(null, null, null, to);
  }

  @Test
  void searchRejectsInvertedAndEqualRange() {
    Instant t = Instant.parse("2026-01-01T00:00:00Z");
    assertThatThrownBy(() -> service.search("alice", null, t, t.minusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("from must be before to");
    assertThatThrownBy(() -> service.search("alice", null, t, t))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("from must be before to");
    verifyNoInteractions(repository);
  }
}
