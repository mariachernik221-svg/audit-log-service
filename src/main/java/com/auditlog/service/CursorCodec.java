package com.auditlog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CursorCodec {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().addModule(new JavaTimeModule()).build();

  public String encode(Instant ts, UUID id, AuditEventQuery query) {
    DecodedCursor payload =
        new DecodedCursor(
            ts,
            id,
            query.actor(),
            query.resource(),
            query.from(),
            query.to(),
            query.order(),
            query.tStart());
    try {
      byte[] json = MAPPER.writeValueAsBytes(payload);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cursor encode failed", e);
    }
  }

  public DecodedCursor decode(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("cursor must not be blank");
    }
    byte[] json;
    try {
      json = Base64.getUrlDecoder().decode(raw);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("cursor is not valid base64url", e);
    }
    DecodedCursor decoded;
    try {
      decoded = MAPPER.readValue(json, DecodedCursor.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("cursor is not valid JSON", e);
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException("cursor could not be read", e);
    }
    if (decoded.ts() == null
        || decoded.id() == null
        || decoded.from() == null
        || decoded.to() == null
        || decoded.order() == null
        || decoded.tStart() == null) {
      throw new IllegalArgumentException("cursor payload is missing required fields");
    }
    return decoded;
  }

  public record DecodedCursor(
      Instant ts,
      UUID id,
      String actor,
      String resource,
      Instant from,
      Instant to,
      Order order,
      Instant tStart) {}
}
