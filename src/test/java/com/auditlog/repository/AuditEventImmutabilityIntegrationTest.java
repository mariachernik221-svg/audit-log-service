package com.auditlog.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.auditlog.domain.AuditEvent;
import com.auditlog.domain.Outcome;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Testcontainers
class AuditEventImmutabilityIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired AuditEventRepository repository;

  @Autowired JdbcTemplate jdbcTemplate;

  @Autowired TestEntityManager entityManager;

  @Test
  void rawUpdateRejectedByTrigger() {
    AuditEvent saved =
        repository.save(new AuditEvent("alice", "user.login", "session:1", Outcome.SUCCESS, null));
    entityManager.flush();

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE audit_events SET actor = 'hacker' WHERE id = ?", saved.getId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void rawDeleteRejectedByTrigger() {
    AuditEvent saved =
        repository.save(new AuditEvent("alice", "user.login", "session:1", Outcome.SUCCESS, null));
    entityManager.flush();

    assertThatThrownBy(
            () -> jdbcTemplate.update("DELETE FROM audit_events WHERE id = ?", saved.getId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void truncateRejectedByTrigger() {
    repository.save(new AuditEvent("alice", "x", "r", Outcome.SUCCESS, null));
    entityManager.flush();

    assertThatThrownBy(() -> jdbcTemplate.execute("TRUNCATE audit_events"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void repositoryInterfaceExposesNoMutatingApi() {
    Method[] methods = AuditEventRepository.class.getMethods();
    assertThat(Arrays.stream(methods).map(Method::getName))
        .doesNotContain(
            "delete",
            "deleteById",
            "deleteAll",
            "deleteAllById",
            "deleteInBatch",
            "deleteAllInBatch",
            "deleteAllByIdInBatch");
  }
}
