package com.auditlog.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.auditlog.domain.AuditEvent;
import com.auditlog.domain.Outcome;
import com.auditlog.repository.AuditEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class QueryApiIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:17-alpine").withReuse(true);

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired MockMvc mockMvc;
  @Autowired AuditEventRepository repository;
  @Autowired ObjectMapper objectMapper;

  @PersistenceContext EntityManager entityManager;

  private static final Instant FROM = Instant.parse("2026-04-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-04-30T00:00:00Z");

  @Test
  void happyPathFiltersByActorAndRangeAscending() throws Exception {
    String actor = "actor-happy-1";
    AuditEvent e1 = seed(actor, "r", Instant.parse("2026-04-02T00:00:00Z"));
    AuditEvent e2 = seed(actor, "r", Instant.parse("2026-04-05T00:00:00Z"));
    seed("other", "r", Instant.parse("2026-04-03T00:00:00Z"));
    flush();

    mockMvc
        .perform(
            get("/audit-events")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("actor", actor)
                .param("order", "ASC"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].id").value(e1.getId().toString()))
        .andExpect(jsonPath("$.items[1].id").value(e2.getId().toString()));
  }

  @Test
  void happyPathFiltersByResourceAndRangeDescending() throws Exception {
    String resource = "resource-happy-2";
    AuditEvent older = seed("a", resource, Instant.parse("2026-04-02T00:00:00Z"));
    AuditEvent newer = seed("a", resource, Instant.parse("2026-04-05T00:00:00Z"));
    flush();

    mockMvc
        .perform(
            get("/audit-events")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("resource", resource)
                .param("order", "DESC"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(newer.getId().toString()))
        .andExpect(jsonPath("$.items[1].id").value(older.getId().toString()));
  }

  @Test
  void happyPathRangeOnlyReturnsAllEventsInWindow() throws Exception {
    String actor = "actor-range-only";
    seed(actor, "r", Instant.parse("2026-04-02T00:00:00Z"));
    seed(actor, "r", Instant.parse("2026-04-03T00:00:00Z"));
    seed(actor, "r", Instant.parse("2026-03-30T00:00:00Z")); // before FROM
    flush();

    mockMvc
        .perform(
            get("/audit-events")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("actor", actor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2));
  }

  @Test
  void caseInsensitiveActorMatchEndToEnd() throws Exception {
    String actor = "Mixed-Case-Actor";
    seed(actor, "r", Instant.parse("2026-04-02T00:00:00Z"));
    flush();

    mockMvc
        .perform(
            get("/audit-events")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("actor", actor.toLowerCase()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1));

    mockMvc
        .perform(
            get("/audit-events")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("actor", actor.toUpperCase()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1));
  }

  @Test
  void caseInsensitiveResourceMatchEndToEnd() throws Exception {
    String resource = "MIXED-Resource";
    seed("a", resource, Instant.parse("2026-04-02T00:00:00Z"));
    flush();

    mockMvc
        .perform(
            get("/audit-events")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("resource", "mixed-resource"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1));
  }

  @Test
  void missingFromReturns400() throws Exception {
    mockMvc
        .perform(get("/audit-events").param("to", TO.toString()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void missingToReturns400() throws Exception {
    mockMvc
        .perform(get("/audit-events").param("from", FROM.toString()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void fromEqualToToReturns400() throws Exception {
    mockMvc
        .perform(get("/audit-events").param("from", FROM.toString()).param("to", FROM.toString()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void windowGreaterThan90DaysReturns400() throws Exception {
    Instant tooFar = FROM.plus(java.time.Duration.ofDays(91));
    mockMvc
        .perform(get("/audit-events").param("from", FROM.toString()).param("to", tooFar.toString()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void limitBelowMinReturns400() throws Exception {
    mockMvc
        .perform(
            get("/audit-events")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("limit", "0"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void limitAboveMaxReturns400() throws Exception {
    mockMvc
        .perform(
            get("/audit-events")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("limit", "501"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void malformedCursorReturns400() throws Exception {
    mockMvc
        .perform(
            get("/audit-events")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("cursor", "%%%not-base64%%%"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void cursorWithMismatchedFiltersReturns400() throws Exception {
    String actor = "actor-mismatch-1";
    for (int i = 0; i < 3; i++) {
      seed(actor, "r", Instant.parse("2026-04-0" + (i + 1) + "T00:00:00Z"));
    }
    flush();

    Page page = fetchPage(actor, null, FROM, TO, "ASC", 2, null);
    assertThat(page.nextCursor()).isNotNull();

    mockMvc
        .perform(
            get("/audit-events")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("actor", "different-actor")
                .param("order", "ASC")
                .param("limit", "2")
                .param("cursor", page.nextCursor()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void paginationWalkReturnsAllEventsExactlyOnce() throws Exception {
    String actor = "actor-walk";
    int total = 7;
    List<UUID> seededIds = new ArrayList<>();
    for (int i = 0; i < total; i++) {
      seededIds.add(seed(actor, "r", FROM.plus(java.time.Duration.ofMinutes(i))).getId());
    }
    flush();

    Set<UUID> seen = new HashSet<>();
    String cursor = null;
    int pages = 0;
    do {
      Page page = fetchPage(actor, null, FROM, TO, "ASC", 2, cursor);
      for (Map<String, Object> item : page.items()) {
        UUID id = UUID.fromString((String) item.get("id"));
        assertThat(seen.add(id)).as("duplicate id %s", id).isTrue();
      }
      cursor = page.nextCursor();
      pages++;
    } while (cursor != null && pages < 100);

    assertThat(seen).containsExactlyInAnyOrderElementsOf(seededIds);
  }

  @Test
  void sameTimestampTiesOrderedConsistentlyAcrossPageBoundary() throws Exception {
    String actor = "actor-ties";
    Instant tie = Instant.parse("2026-04-15T00:00:00Z");
    seed(actor, "r", tie);
    seed(actor, "r", tie);
    seed(actor, "r", tie);
    flush();

    Page p1 = fetchPage(actor, null, FROM, TO, "ASC", 2, null);
    assertThat(p1.items()).hasSize(2);
    assertThat(p1.nextCursor()).isNotNull();
    Page p2 = fetchPage(actor, null, FROM, TO, "ASC", 2, p1.nextCursor());

    UUID lastOnPage1 = UUID.fromString((String) p1.items().get(1).get("id"));
    UUID firstOnPage2 = UUID.fromString((String) p2.items().get(0).get("id"));
    assertThat(lastOnPage1.toString()).isLessThan(firstOnPage2.toString());
  }

  @Test
  void snapshotBoundaryExcludesEventsAppendedAfterFirstPage() throws Exception {
    String actor = "actor-snapshot";
    Instant now = Instant.now();
    Instant from = now.minusSeconds(3600);
    Instant to = now.plus(java.time.Duration.ofDays(80));

    AuditEvent a = seed(actor, "r", now.minusSeconds(1800));
    AuditEvent b = seed(actor, "r", now.minusSeconds(900));
    flush();

    Page p1 = fetchPage(actor, null, from, to, "ASC", 1, null);
    assertThat(p1.items()).hasSize(1);
    assertThat(p1.items().get(0).get("id")).isEqualTo(a.getId().toString());
    assertThat(p1.nextCursor()).isNotNull();

    // Append an event with a timestamp in the future window, guaranteed > T_start
    // (which the server captured ~now during the first request) but still <= `to`.
    Instant afterTStart = now.plus(java.time.Duration.ofDays(10));
    AuditEvent appendedAfter = seed(actor, "r", afterTStart);
    flush();

    Page p2 = fetchPage(actor, null, from, to, "ASC", 10, p1.nextCursor());

    List<String> ids = p2.items().stream().map(m -> (String) m.get("id")).toList();
    assertThat(ids).contains(b.getId().toString());
    assertThat(ids).doesNotContain(appendedAfter.getId().toString());
  }

  @Test
  void freshnessNewlyWrittenEventVisibleToNextQuery() throws Exception {
    String actor = "actor-freshness";
    Instant now = Instant.now();
    Instant from = now.minusSeconds(3600);
    Instant to = now.plus(java.time.Duration.ofDays(1));

    AuditEvent justWritten = seed(actor, "r", now.minusSeconds(1));
    flush();

    Page page = fetchPage(actor, null, from, to, "ASC", 50, null);

    List<String> ids = page.items().stream().map(m -> (String) m.get("id")).toList();
    assertThat(ids).contains(justWritten.getId().toString());
  }

  // -- helpers --

  private AuditEvent seed(String actor, String resource, Instant ts) {
    AuditEvent event = new AuditEvent(actor, "action", resource, Outcome.SUCCESS, null);
    setField(event, "timestamp", ts);
    return repository.save(event);
  }

  private void flush() {
    entityManager.flush();
  }

  private Page fetchPage(
      String actor,
      String resource,
      Instant from,
      Instant to,
      String order,
      int limit,
      String cursor)
      throws Exception {
    var request =
        get("/audit-events")
            .param("from", from.toString())
            .param("to", to.toString())
            .param("order", order)
            .param("limit", String.valueOf(limit));
    if (actor != null) {
      request = request.param("actor", actor);
    }
    if (resource != null) {
      request = request.param("resource", resource);
    }
    if (cursor != null) {
      request = request.param("cursor", cursor);
    }
    MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), Page.class);
  }

  private record Page(List<Map<String, Object>> items, String nextCursor) {}

  private static void setField(Object target, String name, Object value) {
    try {
      Field f = AuditEvent.class.getDeclaredField(name);
      f.setAccessible(true);
      f.set(target, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
