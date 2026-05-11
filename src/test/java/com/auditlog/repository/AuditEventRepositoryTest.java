package com.auditlog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.auditlog.domain.AuditEvent;
import com.auditlog.domain.Outcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
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
}
