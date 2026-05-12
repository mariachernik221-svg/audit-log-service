package com.auditlog.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
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
import com.auditlog.service.AuditEventService;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
  void postRejectsBlankActorWith400() throws Exception {
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
        .andExpect(status().isBadRequest())
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
  void postMapsServiceIllegalArgumentTo400() throws Exception {
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
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("actor must not be blank"));
  }

  @Test
  void getReturnsPageWithItemsAndNextCursor() throws Exception {
    mockMvc
        .perform(
            get("/audit-events")
                .param("from", "2026-04-01T00:00:00Z")
                .param("to", "2026-04-30T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.items.length()").value(0))
        .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    verifyNoInteractions(service);
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
  void getRejectsLimitBelowMinWith400() throws Exception {
    mockMvc
        .perform(
            get("/audit-events")
                .param("from", "2026-04-01T00:00:00Z")
                .param("to", "2026-04-30T00:00:00Z")
                .param("limit", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("limit")));
    verifyNoInteractions(service);
  }

  @Test
  void getRejectsLimitAboveMaxWith400() throws Exception {
    mockMvc
        .perform(
            get("/audit-events")
                .param("from", "2026-04-01T00:00:00Z")
                .param("to", "2026-04-30T00:00:00Z")
                .param("limit", "501"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString("limit")));
    verifyNoInteractions(service);
  }

  @Test
  void getRejectsUnknownOrderWith400() throws Exception {
    mockMvc
        .perform(
            get("/audit-events")
                .param("from", "2026-04-01T00:00:00Z")
                .param("to", "2026-04-30T00:00:00Z")
                .param("order", "sideways"))
        .andExpect(status().isBadRequest());
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
