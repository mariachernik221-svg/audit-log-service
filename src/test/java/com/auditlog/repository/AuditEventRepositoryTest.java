package com.auditlog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.auditlog.domain.AuditEvent;
import com.auditlog.domain.Outcome;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Testcontainers
class AuditEventRepositoryTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired AuditEventRepository repository;

  @Test
  void persistsAndReadsBackById() {
    AuditEvent saved =
        repository.save(
            new AuditEvent(
                "alice", "user.login", "session:1", Outcome.SUCCESS, "{\"ip\":\"1.2.3.4\"}"));

    AuditEvent found = repository.findById(saved.getId()).orElseThrow();

    assertThat(found.getActor()).isEqualTo("alice");
    assertThat(found.getAction()).isEqualTo("user.login");
    assertThat(found.getResource()).isEqualTo("session:1");
    assertThat(found.getOutcome()).isEqualTo(Outcome.SUCCESS);
    assertThat(found.getContext()).contains("1.2.3.4");
    assertThat(found.getTimestamp()).isNotNull();
  }

  @Test
  void searchByActorOnlyReturnsOrderedNewestFirst() {
    AuditEvent older =
        save("alice", "x.y", "r:1", Outcome.SUCCESS, Instant.now().minus(2, ChronoUnit.HOURS));
    AuditEvent newer =
        save("alice", "x.y", "r:2", Outcome.SUCCESS, Instant.now().minus(1, ChronoUnit.HOURS));
    save("bob", "x.y", "r:3", Outcome.SUCCESS, Instant.now());

    List<AuditEvent> result = repository.search("alice", null, null, null);

    assertThat(result).extracting(AuditEvent::getId).containsExactly(newer.getId(), older.getId());
  }

  @Test
  void searchByResourceOnly() {
    save("alice", "x.y", "project:42", Outcome.SUCCESS, Instant.now().minus(3, ChronoUnit.HOURS));
    AuditEvent target =
        save("bob", "x.y", "project:42", Outcome.DENIED, Instant.now().minus(1, ChronoUnit.HOURS));
    save("carol", "x.y", "project:99", Outcome.SUCCESS, Instant.now());

    List<AuditEvent> result = repository.search(null, "project:42", null, null);

    assertThat(result).hasSize(2);
    assertThat(result.getFirst().getId()).isEqualTo(target.getId());
    assertThat(result).allMatch(e -> e.getResource().equals("project:42"));
  }

  @Test
  void searchByTimeRangeOnlyHalfOpen() {
    Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    save("alice", "a", "r:1", Outcome.SUCCESS, t0.minusSeconds(1));
    AuditEvent inA = save("alice", "a", "r:2", Outcome.SUCCESS, t0);
    AuditEvent inB = save("alice", "a", "r:3", Outcome.SUCCESS, t0.plusSeconds(30));
    save("alice", "a", "r:4", Outcome.SUCCESS, t0.plusSeconds(60));

    List<AuditEvent> result = repository.search(null, null, t0, t0.plusSeconds(60));

    assertThat(result).extracting(AuditEvent::getId).containsExactly(inB.getId(), inA.getId());
  }

  @Test
  void searchByActorAndResource() {
    AuditEvent target = save("alice", "x", "project:42", Outcome.SUCCESS, Instant.now());
    save("alice", "x", "project:99", Outcome.SUCCESS, Instant.now());
    save("bob", "x", "project:42", Outcome.SUCCESS, Instant.now());

    List<AuditEvent> result = repository.search("alice", "project:42", null, null);

    assertThat(result).extracting(AuditEvent::getId).containsExactly(target.getId());
  }

  @Test
  void searchByAllFiltersCombined() {
    Instant t0 = Instant.parse("2026-03-02T00:00:00Z");
    Instant t1 = Instant.parse("2026-03-06T00:00:00Z");

    AuditEvent target =
        save(
            "mark.smith",
            "payment.charge",
            "payment-service",
            Outcome.SUCCESS,
            Instant.parse("2026-03-04T12:00:00Z"));
    save("mark.smith", "payment.charge", "payment-service", Outcome.SUCCESS, t0.minusSeconds(1));
    save("mark.smith", "payment.charge", "payment-service", Outcome.SUCCESS, t1.plusSeconds(1));
    save(
        "mark.smith",
        "payment.charge",
        "billing-service",
        Outcome.SUCCESS,
        Instant.parse("2026-03-04T12:00:00Z"));
    save(
        "alice",
        "payment.charge",
        "payment-service",
        Outcome.SUCCESS,
        Instant.parse("2026-03-04T12:00:00Z"));

    List<AuditEvent> result = repository.search("mark.smith", "payment-service", t0, t1);

    assertThat(result).extracting(AuditEvent::getId).containsExactly(target.getId());
  }

  @Test
  void searchAllNullsReturnsEverything() {
    save("alice", "a", "r:1", Outcome.SUCCESS, Instant.now());
    save("bob", "a", "r:2", Outcome.SUCCESS, Instant.now());

    List<AuditEvent> result = repository.search(null, null, null, null);

    assertThat(result).hasSizeGreaterThanOrEqualTo(2);
  }

  @Test
  void searchEmptyResultWhenNoMatch() {
    save("alice", "a", "r:1", Outcome.SUCCESS, Instant.now());

    assertThat(repository.search("nobody", null, null, null)).isEmpty();
    assertThat(repository.search(null, "nothing", null, null)).isEmpty();
    Instant future = Instant.now().plus(1, ChronoUnit.DAYS);
    assertThat(repository.search(null, null, future, future.plusSeconds(60))).isEmpty();
  }

  private AuditEvent save(
      String actor, String action, String resource, Outcome outcome, Instant ts) {
    AuditEvent event = new AuditEvent(actor, action, resource, outcome, null);
    setTimestamp(event, ts);
    return repository.save(event);
  }

  private static void setTimestamp(AuditEvent event, Instant ts) {
    try {
      var field = AuditEvent.class.getDeclaredField("timestamp");
      field.setAccessible(true);
      field.set(event, ts);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
