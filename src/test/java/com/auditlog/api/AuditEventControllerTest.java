package com.auditlog.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.auditlog.service.AuditEventService;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
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
  void getByActorOnly() throws Exception {
    AuditEvent event =
        entity(
            UUID.randomUUID(),
            Instant.parse("2026-04-25T10:00:00Z"),
            "alice",
            "x",
            "r",
            Outcome.SUCCESS,
            null);
    when(service.search(eq("alice"), isNull(), isNull(), isNull())).thenReturn(List.of(event));

    mockMvc
        .perform(get("/audit-events").param("actor", "alice"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].actor").value("alice"));
    verify(service).search("alice", null, null, null);
  }

  @Test
  void getByResourceOnly() throws Exception {
    AuditEvent event =
        entity(UUID.randomUUID(), Instant.now(), "alice", "x", "project:42", Outcome.DENIED, null);
    when(service.search(isNull(), eq("project:42"), isNull(), isNull())).thenReturn(List.of(event));

    mockMvc
        .perform(get("/audit-events").param("resource", "project:42"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].resource").value("project:42"))
        .andExpect(jsonPath("$[0].outcome").value("DENIED"));
  }

  @Test
  void getByAllParamsCombined() throws Exception {
    Instant from = Instant.parse("2026-03-02T00:00:00Z");
    Instant to = Instant.parse("2026-03-06T00:00:00Z");
    AuditEvent event =
        entity(
            UUID.randomUUID(),
            Instant.parse("2026-03-04T12:00:00Z"),
            "mark.smith",
            "payment.charge",
            "payment-service",
            Outcome.SUCCESS,
            null);
    when(service.search("mark.smith", "payment-service", from, to)).thenReturn(List.of(event));

    mockMvc
        .perform(
            get("/audit-events")
                .param("actor", "mark.smith")
                .param("resource", "payment-service")
                .param("from", from.toString())
                .param("to", to.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].actor").value("mark.smith"))
        .andExpect(jsonPath("$[0].resource").value("payment-service"));
    verify(service).search("mark.smith", "payment-service", from, to);
  }

  @Test
  void getMapsServiceIllegalArgumentTo400() throws Exception {
    when(service.search(isNull(), isNull(), isNull(), isNull()))
        .thenThrow(
            new IllegalArgumentException(
                "search requires at least one filter: actor, resource, from, or to"));

    mockMvc
        .perform(get("/audit-events"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message", containsString("at least one filter")));
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
