package com.auditlog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.auditlog.domain.AuditEvent;
import com.auditlog.domain.Outcome;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Limit;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Testcontainers
class AuditEventRepositoryTest {

  private static final Instant T0 = Instant.parse("2026-04-01T00:00:00Z");
  private static final Instant T1 = Instant.parse("2026-04-01T01:00:00Z");
  private static final Instant T2 = Instant.parse("2026-04-01T02:00:00Z");
  private static final Instant T3 = Instant.parse("2026-04-01T03:00:00Z");
  private static final Instant T_FUTURE = Instant.parse("2030-01-01T00:00:00Z");
  private static final Limit BIG = Limit.of(100);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired AuditEventRepository repository;

  @Autowired TestEntityManager entityManager;

  @Test
  void persistsAllFieldsThroughJpaMapping() {
    AuditEvent saved =
        repository.save(
            new AuditEvent(
                "alice", "user.login", "session:1", Outcome.SUCCESS, "{\"ip\":\"1.2.3.4\"}"));
    entityManager.flush();
    entityManager.clear();

    AuditEvent reloaded = entityManager.find(AuditEvent.class, saved.getId());

    assertThat(reloaded.getActor()).isEqualTo("alice");
    assertThat(reloaded.getAction()).isEqualTo("user.login");
    assertThat(reloaded.getResource()).isEqualTo("session:1");
    assertThat(reloaded.getOutcome()).isEqualTo(Outcome.SUCCESS);
    assertThat(reloaded.getContext()).contains("1.2.3.4");
    assertThat(reloaded.getTimestamp()).isNotNull();
  }

  @Test
  void searchAscReturnsEventsInRangeOrderedByTimestampThenId() {
    AuditEvent a = save("alice", "r", T1);
    AuditEvent b = save("alice", "r", T2);
    AuditEvent c = save("alice", "r", T3);

    List<AuditEvent> result =
        repository.searchAsc(null, null, T0, T_FUTURE, T_FUTURE, null, null, BIG);

    assertThat(result).extracting(AuditEvent::getId).containsExactly(a.getId(), b.getId(), c.getId());
  }

  @Test
  void searchDescReturnsEventsInReverseOrder() {
    AuditEvent a = save("alice", "r", T1);
    AuditEvent b = save("alice", "r", T2);
    AuditEvent c = save("alice", "r", T3);

    List<AuditEvent> result =
        repository.searchDesc(null, null, T0, T_FUTURE, T_FUTURE, null, null, BIG);

    assertThat(result).extracting(AuditEvent::getId).containsExactly(c.getId(), b.getId(), a.getId());
  }

  @Test
  void searchAscFiltersByActorCaseInsensitively() {
    AuditEvent match = save("Alice", "r", T1);
    save("bob", "r", T2);

    List<AuditEvent> result =
        repository.searchAsc("alice", null, T0, T_FUTURE, T_FUTURE, null, null, BIG);

    assertThat(result).extracting(AuditEvent::getId).containsExactly(match.getId());
  }

  @Test
  void searchAscFiltersByResourceCaseInsensitively() {
    AuditEvent match = save("alice", "Project:42", T1);
    save("alice", "project:99", T2);

    List<AuditEvent> result =
        repository.searchAsc(null, "project:42", T0, T_FUTURE, T_FUTURE, null, null, BIG);

    assertThat(result).extracting(AuditEvent::getId).containsExactly(match.getId());
  }

  @Test
  void searchAscCombinesActorAndResource() {
    AuditEvent match = save("alice", "project:42", T1);
    save("alice", "project:99", T1);
    save("bob", "project:42", T1);

    List<AuditEvent> result =
        repository.searchAsc("alice", "project:42", T0, T_FUTURE, T_FUTURE, null, null, BIG);

    assertThat(result).extracting(AuditEvent::getId).containsExactly(match.getId());
  }

  @Test
  void searchAscIncludesFromBoundaryAndExcludesToBoundary() {
    AuditEvent atFrom = save("alice", "r", T1);
    AuditEvent inside = save("alice", "r", T2);
    save("alice", "r", T3);

    List<AuditEvent> result =
        repository.searchAsc(null, null, T1, T3, T_FUTURE, null, null, BIG);

    assertThat(result)
        .extracting(AuditEvent::getId)
        .containsExactly(atFrom.getId(), inside.getId());
  }

  @Test
  void searchAscExcludesEventsAfterTStart() {
    AuditEvent inSnapshot = save("alice", "r", T1);
    save("alice", "r", T3);

    List<AuditEvent> result =
        repository.searchAsc(null, null, T0, T_FUTURE, T2, null, null, BIG);

    assertThat(result).extracting(AuditEvent::getId).containsExactly(inSnapshot.getId());
  }

  @Test
  void searchAscIncludesEventsAtExactlyTStart() {
    AuditEvent atBoundary = save("alice", "r", T2);
    save("alice", "r", T3);

    List<AuditEvent> result =
        repository.searchAsc(null, null, T0, T_FUTURE, T2, null, null, BIG);

    assertThat(result).extracting(AuditEvent::getId).containsExactly(atBoundary.getId());
  }

  @Test
  void searchAscAdvancesStrictlyPastLastTimestampAndId() {
    AuditEvent first = save("alice", "r", T1);
    AuditEvent second = save("alice", "r", T2);
    AuditEvent third = save("alice", "r", T3);

    List<AuditEvent> result =
        repository.searchAsc(
            null, null, T0, T_FUTURE, T_FUTURE, first.getTimestamp(), first.getId(), BIG);

    assertThat(result)
        .extracting(AuditEvent::getId)
        .containsExactly(second.getId(), third.getId());
  }

  @Test
  void searchAscBreaksTimestampTiesByIdAscending() {
    AuditEvent a = save("alice", "r", T2);
    AuditEvent b = save("alice", "r", T2);

    List<AuditEvent> result =
        repository.searchAsc(null, null, T0, T_FUTURE, T_FUTURE, null, null, BIG);

    UUID firstId = result.get(0).getId();
    UUID secondId = result.get(1).getId();
    assertThat(firstId).isLessThan(secondId);
    assertThat(List.of(firstId, secondId)).containsExactlyInAnyOrder(a.getId(), b.getId());
  }

  @Test
  void searchAscWithKeysetAdvancesAcrossSameTimestampTie() {
    AuditEvent a = save("alice", "r", T2);
    AuditEvent b = save("alice", "r", T2);

    UUID smaller = a.getId().compareTo(b.getId()) < 0 ? a.getId() : b.getId();
    UUID larger = a.getId().compareTo(b.getId()) < 0 ? b.getId() : a.getId();

    List<AuditEvent> result =
        repository.searchAsc(null, null, T0, T_FUTURE, T_FUTURE, T2, smaller, BIG);

    assertThat(result).extracting(AuditEvent::getId).containsExactly(larger);
  }

  @Test
  void searchDescBreaksTimestampTiesByIdDescending() {
    AuditEvent a = save("alice", "r", T2);
    AuditEvent b = save("alice", "r", T2);

    List<AuditEvent> result =
        repository.searchDesc(null, null, T0, T_FUTURE, T_FUTURE, null, null, BIG);

    UUID firstId = result.get(0).getId();
    UUID secondId = result.get(1).getId();
    assertThat(firstId).isGreaterThan(secondId);
    assertThat(List.of(firstId, secondId)).containsExactlyInAnyOrder(a.getId(), b.getId());
  }

  @Test
  void searchDescAdvancesStrictlyPastLastTimestampAndId() {
    AuditEvent first = save("alice", "r", T3);
    AuditEvent second = save("alice", "r", T2);
    AuditEvent third = save("alice", "r", T1);

    List<AuditEvent> result =
        repository.searchDesc(
            null, null, T0, T_FUTURE, T_FUTURE, first.getTimestamp(), first.getId(), BIG);

    assertThat(result)
        .extracting(AuditEvent::getId)
        .containsExactly(second.getId(), third.getId());
  }

  @Test
  void searchAscRespectsLimit() {
    save("alice", "r", T1);
    save("alice", "r", T2);
    save("alice", "r", T3);

    List<AuditEvent> result =
        repository.searchAsc(null, null, T0, T_FUTURE, T_FUTURE, null, null, Limit.of(2));

    assertThat(result).hasSize(2);
  }

  @Test
  void searchAscReturnsEmptyWhenNothingMatches() {
    save("alice", "r", T1);

    assertThat(
            repository.searchAsc(
                "nobody", null, T0, T_FUTURE, T_FUTURE, null, null, BIG))
        .isEmpty();
    assertThat(
            repository.searchAsc(
                null, "nothing", T0, T_FUTURE, T_FUTURE, null, null, BIG))
        .isEmpty();
    assertThat(
            repository.searchAsc(
                null, null, T_FUTURE, T_FUTURE.plusSeconds(60), T_FUTURE, null, null, BIG))
        .isEmpty();
  }

  private AuditEvent save(String actor, String resource, Instant ts) {
    AuditEvent event = new AuditEvent(actor, "x", resource, Outcome.SUCCESS, null);
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
