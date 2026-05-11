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
import java.time.Instant;
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
}
