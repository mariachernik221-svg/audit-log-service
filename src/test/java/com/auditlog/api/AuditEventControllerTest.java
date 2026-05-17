package com.auditlog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.auditlog.domain.AuditEvent;
import com.auditlog.domain.Outcome;
import com.auditlog.service.AuditEventQueryInput;
import com.auditlog.service.AuditEventQueryResult;
import com.auditlog.service.AuditEventService;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuditEventController.class)
class AuditEventControllerTest {

  @Autowired MockMvc mockMvc;

  @MockitoBean AuditEventService service;

  @Test
  void postCreatesEventReturns201WithLocation() throws Exception {
    UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
    Instant ts = Instant.parse("2026-04-25T10:00:00Z");
    AuditEvent saved =
        entity(id, ts, "alice", "user.login", "session:1", Outcome.SUCCESS, "{\"ip\":\"1.2.3.4\"}");
    when(service.create(
            "alice", "user.login", "session:1", Outcome.SUCCESS, "{\"ip\":\"1.2.3.4\"}"))
        .thenReturn(saved);

    mockMvc
        .perform(
            post("/audit-events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {
                                  "actor": "alice",
                                  "action": "user.login",
                                  "resource": "session:1",
                                  "outcome": "SUCCESS",
                                  "context": "{\\"ip\\":\\"1.2.3.4\\"}"
                                }
                                """))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/audit-events/" + id))
        .andExpect(jsonPath("$.id").value(id.toString()))
        .andExpect(jsonPath("$.actor").value("alice"))
        .andExpect(jsonPath("$.action").value("user.login"))
        .andExpect(jsonPath("$.resource").value("session:1"))
        .andExpect(jsonPath("$.outcome").value("SUCCESS"))
        .andExpect(jsonPath("$.timestamp").value("2026-04-25T10:00:00Z"));
  }

  @Test
  void postRejectsBlankActorWith422() throws Exception {
    mockMvc
        .perform(
            post("/audit-events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {
                                  "actor": "",
                                  "action": "x",
                                  "resource": "r",
                                  "outcome": "SUCCESS"
                                }
                                """))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.message").value("validation failed"))
        .andExpect(content().string(containsString("actor")));
    verifyNoInteractions(service);
  }

  @Test
  void postRejectsMissingOutcomeWith400() throws Exception {
    mockMvc
        .perform(
            post("/audit-events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {
                                  "actor": "alice",
                                  "action": "x",
                                  "resource": "r"
                                }
                                """))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("outcome")));
    verifyNoInteractions(service);
  }

  @Test
  void postMapsServiceIllegalArgumentTo422() throws Exception {
    when(service.create(any(), any(), any(), any(), any()))
        .thenThrow(new IllegalArgumentException("actor must not be blank"));

    mockMvc
        .perform(
            post("/audit-events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                                {
                                  "actor": "alice",
                                  "action": "x",
                                  "resource": "r",
                                  "outcome": "SUCCESS"
                                }
                                """))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.message").value("actor must not be blank"));
  }

  @Test
  void getReturnsPageWithItemsAndNextCursor() throws Exception {
    when(service.search(any(AuditEventQueryInput.class)))
        .thenReturn(new AuditEventQueryResult(List.of(), null));

    mockMvc
        .perform(
            get("/audit-events")
                .param("from", "2026-04-01T00:00:00Z")
                .param("to", "2026-04-30T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.nextCursor").value(nullValue()));
  }

  @Test
  void getPreservesActorCaseWhenPassingToService() throws Exception {
    when(service.search(any(AuditEventQueryInput.class)))
        .thenReturn(new AuditEventQueryResult(List.of(), null));

    mockMvc
        .perform(
            get("/audit-events")
                .param("from", "2026-04-01T00:00:00Z")
                .param("to", "2026-04-30T00:00:00Z")
                .param("actor", "Alice"))
        .andExpect(status().isOk());

    ArgumentCaptor<AuditEventQueryInput> captor =
        ArgumentCaptor.forClass(AuditEventQueryInput.class);
    verify(service).search(captor.capture());
    assertThat(captor.getValue().actor()).isEqualTo("Alice");
  }

  @Test
  void getPassesActorListRawToService() throws Exception {
    when(service.search(any(AuditEventQueryInput.class)))
        .thenReturn(new AuditEventQueryResult(List.of(), null));

    mockMvc
        .perform(
            get("/audit-events")
                .param("from", "2026-04-01T00:00:00Z")
                .param("to", "2026-04-30T00:00:00Z")
                .param("actor", "Alice,bob,CAROL"))
        .andExpect(status().isOk());

    ArgumentCaptor<AuditEventQueryInput> captor =
        ArgumentCaptor.forClass(AuditEventQueryInput.class);
    verify(service).search(captor.capture());
    assertThat(captor.getValue().actor()).isEqualTo("Alice,bob,CAROL");
  }

  @Test
  void getMapsActorListAbove10To422() throws Exception {
    when(service.search(any(AuditEventQueryInput.class)))
        .thenThrow(new IllegalArgumentException("actor list must not exceed 10 distinct values"));

    mockMvc
        .perform(
            get("/audit-events")
                .param("from", "2026-04-01T00:00:00Z")
                .param("to", "2026-04-30T00:00:00Z")
                .param("actor", "a1,a2,a3,a4,a5,a6,a7,a8,a9,a10,a11"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.message").value("actor list must not exceed 10 distinct values"));
  }

  @Test
  void getMapsEmptyActorParamTo400() throws Exception {
    when(service.search(any(AuditEventQueryInput.class)))
        .thenThrow(
            new com.auditlog.service.MissingRequestValueException("actor must not be blank"));

    mockMvc
        .perform(
            get("/audit-events")
                .param("from", "2026-04-01T00:00:00Z")
                .param("to", "2026-04-30T00:00:00Z")
                .param("actor", ""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("actor must not be blank"));
  }

  @Test
  void getMapsBlankEntryActorListTo422() throws Exception {
    when(service.search(any(AuditEventQueryInput.class)))
        .thenThrow(new IllegalArgumentException("actor list must not contain blank entries"));

    mockMvc
        .perform(
            get("/audit-events")
                .param("from", "2026-04-01T00:00:00Z")
                .param("to", "2026-04-30T00:00:00Z")
                .param("actor", "alice,,bob"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.message").value("actor list must not contain blank entries"));
  }

  @Test
  void getPreservesResourceCaseWhenPassingToService() throws Exception {
    when(service.search(any(AuditEventQueryInput.class)))
        .thenReturn(new AuditEventQueryResult(List.of(), null));

    mockMvc
        .perform(
            get("/audit-events")
                .param("from", "2026-04-01T00:00:00Z")
                .param("to", "2026-04-30T00:00:00Z")
                .param("resource", "Project:42"))
        .andExpect(status().isOk());

    ArgumentCaptor<AuditEventQueryInput> captor =
        ArgumentCaptor.forClass(AuditEventQueryInput.class);
    verify(service).search(captor.capture());
    assertThat(captor.getValue().resource()).isEqualTo("Project:42");
  }

  @Test
  void getReturnsEmptyPageWith200WhenServiceReturnsEmpty() throws Exception {
    when(service.search(any(AuditEventQueryInput.class)))
        .thenReturn(new AuditEventQueryResult(List.of(), null));

    mockMvc
        .perform(
            get("/audit-events")
                .param("from", "2026-04-01T00:00:00Z")
                .param("to", "2026-04-30T00:00:00Z")
                .param("actor", "nobody"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.nextCursor").value(nullValue()));
  }

  @Test
  void getMapsServiceIllegalArgumentTo422() throws Exception {
    when(service.search(any(AuditEventQueryInput.class)))
        .thenThrow(new IllegalArgumentException("from must be before to"));

    mockMvc
        .perform(
            get("/audit-events")
                .param("from", "2026-04-30T00:00:00Z")
                .param("to", "2026-04-01T00:00:00Z"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.message").value("from must be before to"));
  }

  @Test
  void getRejectsMissingFromWith400() throws Exception {
    mockMvc
        .perform(get("/audit-events").param("to", "2026-04-30T00:00:00Z"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("from")));
    verifyNoInteractions(service);
  }

  @Test
  void getRejectsMissingToWith400() throws Exception {
    mockMvc
        .perform(get("/audit-events").param("from", "2026-04-01T00:00:00Z"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("to")));
    verifyNoInteractions(service);
  }

  @Test
  void getRejectsLimitBelowMinWith422() throws Exception {
    mockMvc
        .perform(
            get("/audit-events")
                .param("from", "2026-04-01T00:00:00Z")
                .param("to", "2026-04-30T00:00:00Z")
                .param("limit", "0"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().string(containsString("limit")));
    verifyNoInteractions(service);
  }

  @Test
  void getRejectsLimitAboveMaxWith422() throws Exception {
    mockMvc
        .perform(
            get("/audit-events")
                .param("from", "2026-04-01T00:00:00Z")
                .param("to", "2026-04-30T00:00:00Z")
                .param("limit", "501"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().string(containsString("limit")));
    verifyNoInteractions(service);
  }

  @Test
  void getRejectsUnknownOrderWith422() throws Exception {
    mockMvc
        .perform(
            get("/audit-events")
                .param("from", "2026-04-01T00:00:00Z")
                .param("to", "2026-04-30T00:00:00Z")
                .param("order", "sideways"))
        .andExpect(status().isUnprocessableEntity());
    verifyNoInteractions(service);
  }

  @Test
  void getRejectsMalformedFromWith422() throws Exception {
    mockMvc
        .perform(
            get("/audit-events")
                .param("from", "not-a-date")
                .param("to", "2026-04-30T00:00:00Z"))
        .andExpect(status().isUnprocessableEntity());
    verifyNoInteractions(service);
  }

  private static AuditEvent entity(
      UUID id,
      Instant ts,
      String actor,
      String action,
      String resource,
      Outcome outcome,
      String context) {
    AuditEvent event = new AuditEvent(actor, action, resource, outcome, context);
    setField(event, "id", id);
    setField(event, "timestamp", ts);
    return event;
  }

  private static void setField(Object target, String name, Object value) {
    try {
      Field field = AuditEvent.class.getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
