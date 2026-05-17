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
  void fromEqualToToReturns422() throws Exception {
    mockMvc
        .perform(get("/audit-events").param("from", FROM.toString()).param("to", FROM.toString()))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void windowGreaterThan90DaysReturns422() throws Exception {
    Instant tooFar = FROM.plus(java.time.Duration.ofDays(91));
    mockMvc
        .perform(get("/audit-events").param("from", FROM.toString()).param("to", tooFar.toString()))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void multiActorHappyPathReturnsUnionCaseInsensitive() throws Exception {
    AuditEvent eAlice = seed("Alice", "r", Instant.parse("2026-04-02T00:00:00Z"));
    AuditEvent eBob = seed("BOB", "r", Instant.parse("2026-04-03T00:00:00Z"));
    seed("carol", "r", Instant.parse("2026-04-04T00:00:00Z"));
    flush();

    MvcResult result =
        mockMvc
            .perform(
                get("/audit-events")
                    .param("from", FROM.toString())
                    .param("to", TO.toString())
                    .param("actor", "alice,BOB")
                    .param("order", "ASC"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andReturn();
    Page page = objectMapper.readValue(result.getResponse().getContentAsString(), Page.class);
    List<String> ids = page.items().stream().map(m -> (String) m.get("id")).toList();
    assertThat(ids).containsExactly(eAlice.getId().toString(), eBob.getId().toString());
  }

  @Test
  void multiActorListTrimsAndDedupesBeforeMatching() throws Exception {
    AuditEvent eAlice = seed("alice", "r", Instant.parse("2026-04-02T00:00:00Z"));
    flush();

    mockMvc
        .perform(
            get("/audit-events")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("actor", "  alice ,Alice ,ALICE  "))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].id").value(eAlice.getId().toString()));
  }

  @Test
  void actorListAbove10DistinctReturns422() throws Exception {
    mockMvc
        .perform(
            get("/audit-events")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("actor", "a1,a2,a3,a4,a5,a6,a7,a8,a9,a10,a11"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void multiActorHappyPathWithThreeActors() throws Exception {
    AuditEvent eAlice = seed("alice", "r", Instant.parse("2026-04-02T00:00:00Z"));
    AuditEvent eBob = seed("bob", "r", Instant.parse("2026-04-03T00:00:00Z"));
    AuditEvent eCarol = seed("carol", "r", Instant.parse("2026-04-04T00:00:00Z"));
    seed("dave", "r", Instant.parse("2026-04-05T00:00:00Z"));
    flush();

    MvcResult result =
        mockMvc
            .perform(
                get("/audit-events")
                    .param("from", FROM.toString())
                    .param("to", TO.toString())
                    .param("actor", "alice,bob,carol")
                    .param("order", "ASC"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(3))
            .andReturn();
    Page page = objectMapper.readValue(result.getResponse().getContentAsString(), Page.class);
    List<String> ids = page.items().stream().map(m -> (String) m.get("id")).toList();
    assertThat(ids)
        .containsExactly(eAlice.getId().toString(), eBob.getId().toString(), eCarol.getId().toString());
  }

  @Test
  void multiActorHappyPathWithTenActorsHitsAllAndExcludesEleventh() throws Exception {
    List<AuditEvent> targets = new ArrayList<>();
    for (int i = 1; i <= 10; i++) {
      targets.add(
          seed("actor10-" + i, "r", Instant.parse("2026-04-02T00:00:00Z").plusSeconds(i)));
    }
    seed("actor10-11", "r", Instant.parse("2026-04-02T00:00:10Z"));
    flush();

    String actors =
        "actor10-1,actor10-2,actor10-3,actor10-4,actor10-5,actor10-6,actor10-7,actor10-8,actor10-9,actor10-10";

    MvcResult result =
        mockMvc
            .perform(
                get("/audit-events")
                    .param("from", FROM.toString())
                    .param("to", TO.toString())
                    .param("actor", actors)
                    .param("order", "ASC")
                    .param("limit", "50"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(10))
            .andReturn();
    Page page = objectMapper.readValue(result.getResponse().getContentAsString(), Page.class);
    Set<String> ids =
        page.items().stream().map(m -> (String) m.get("id")).collect(java.util.stream.Collectors.toSet());
    Set<String> expected =
        targets.stream().map(e -> e.getId().toString()).collect(java.util.stream.Collectors.toSet());
    assertThat(ids).isEqualTo(expected);
  }

  @Test
  void multiActorKeysetPaginationUnionEqualsUnpagedNoDupsNoGaps() throws Exception {
    String actorAlice = "kp-alice";
    String actorBob = "kp-bob";
    String actorCarol = "kp-carol";
    List<UUID> expected = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      expected.add(
          seed(actorAlice, "r", Instant.parse("2026-04-02T00:00:00Z").plusSeconds(i * 3L))
              .getId());
      expected.add(
          seed(actorBob, "r", Instant.parse("2026-04-02T00:00:01Z").plusSeconds(i * 3L)).getId());
      expected.add(
          seed(actorCarol, "r", Instant.parse("2026-04-02T00:00:02Z").plusSeconds(i * 3L))
              .getId());
    }
    seed("other", "r", Instant.parse("2026-04-02T00:01:00Z"));
    flush();

    String actorList = actorAlice + "," + actorBob + "," + actorCarol;

    Page unpaged = fetchPage(actorList, null, FROM, TO, "ASC", 50, null);
    assertThat(unpaged.items()).hasSize(15);
    assertThat(unpaged.nextCursor()).isNull();
    List<String> unpagedIds = unpaged.items().stream().map(m -> (String) m.get("id")).toList();

    List<String> paged = new ArrayList<>();
    String cursor = null;
    int pageSize = 4;
    do {
      Page p = fetchPage(actorList, null, FROM, TO, "ASC", pageSize, cursor);
      p.items().stream().map(m -> (String) m.get("id")).forEach(paged::add);
      cursor = p.nextCursor();
    } while (cursor != null);

    assertThat(paged).hasSize(15);
    assertThat(paged).doesNotHaveDuplicates();
    assertThat(paged).isEqualTo(unpagedIds);
    Set<String> pagedSet = new HashSet<>(paged);
    Set<String> expectedSet =
        expected.stream().map(UUID::toString).collect(java.util.stream.Collectors.toSet());
    assertThat(pagedSet).isEqualTo(expectedSet);
  }

  @Test
  void emptyActorParameterReturns400() throws Exception {
    mockMvc
        .perform(
            get("/audit-events")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("actor", ""))
        .andExpect(status().isBadRequest());
  }

  @Test
  void whitespaceOnlyActorParameterReturns400() throws Exception {
    mockMvc
        .perform(
            get("/audit-events")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("actor", "   "))
        .andExpect(status().isBadRequest());
  }

  @Test
  void actorListWithBlankEntryReturns422() throws Exception {
    mockMvc
        .perform(
            get("/audit-events")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("actor", "alice,,bob"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void actorListWithTrailingCommaReturns422() throws Exception {
    mockMvc
        .perform(
            get("/audit-events")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("actor", "alice,bob,"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void omittedActorParameterStillAccepted() throws Exception {
    seed("anybody", "r", Instant.parse("2026-04-02T00:00:00Z"));
    flush();

    mockMvc
        .perform(get("/audit-events").param("from", FROM.toString()).param("to", TO.toString()))
        .andExpect(status().isOk());
  }

  @Test
  void cursorWithDifferentActorListReturns422() throws Exception {
    String actorList = "alice,bob";
    seed("alice", "r", Instant.parse("2026-04-02T00:00:00Z"));
    seed("bob", "r", Instant.parse("2026-04-03T00:00:00Z"));
    flush();

    Page first = fetchPage("alice,bob", null, FROM, TO, "ASC", 1, null);
    assertThat(first.nextCursor()).isNotNull();

    mockMvc
        .perform(
            get("/audit-events")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("actor", "alice,carol")
                .param("order", "ASC")
                .param("limit", "1")
                .param("cursor", first.nextCursor()))
        .andExpect(status().isUnprocessableEntity());

    // sanity: original actor list keeps walking
    Page second = fetchPage(actorList, null, FROM, TO, "ASC", 10, first.nextCursor());
    assertThat(second.items()).isNotEmpty();
  }

  @Test
  void limitBelowMinReturns422() throws Exception {
    mockMvc
        .perform(
            get("/audit-events")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("limit", "0"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void limitAboveMaxReturns422() throws Exception {
    mockMvc
        .perform(
            get("/audit-events")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("limit", "501"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void malformedCursorReturns422() throws Exception {
    mockMvc
        .perform(
            get("/audit-events")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("cursor", "%%%not-base64%%%"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void cursorWithMismatchedFiltersReturns422() throws Exception {
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
        .andExpect(status().isUnprocessableEntity());
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
