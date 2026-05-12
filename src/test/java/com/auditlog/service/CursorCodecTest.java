package com.auditlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CursorCodecTest {

  private final CursorCodec codec = new CursorCodec();

  @Test
  void encodeAndDecodeRoundTripPreservesAllFields() {
    Instant ts = Instant.parse("2026-04-15T12:34:56Z");
    UUID id = UUID.fromString("a1b2c3d4-1111-2222-3333-444455556666");
    Instant from = Instant.parse("2026-04-01T00:00:00Z");
    Instant to = Instant.parse("2026-04-30T00:00:00Z");
    Instant tStart = Instant.parse("2026-05-01T08:00:00Z");
    AuditEventQuery query =
        new AuditEventQuery(
            "alice", "project:42", from, to, Order.DESC, 50, Optional.empty(), tStart);

    String encoded = codec.encode(ts, id, query);
    CursorCodec.DecodedCursor decoded = codec.decode(encoded);

    assertThat(decoded.ts()).isEqualTo(ts);
    assertThat(decoded.id()).isEqualTo(id);
    assertThat(decoded.actor()).isEqualTo("alice");
    assertThat(decoded.resource()).isEqualTo("project:42");
    assertThat(decoded.from()).isEqualTo(from);
    assertThat(decoded.to()).isEqualTo(to);
    assertThat(decoded.order()).isEqualTo(Order.DESC);
    assertThat(decoded.tStart()).isEqualTo(tStart);
  }

  @Test
  void roundTripPreservesNullActorAndResource() {
    Instant ts = Instant.parse("2026-04-15T12:34:56Z");
    UUID id = UUID.randomUUID();
    AuditEventQuery query =
        new AuditEventQuery(
            null,
            null,
            Instant.parse("2026-04-01T00:00:00Z"),
            Instant.parse("2026-04-30T00:00:00Z"),
            Order.ASC,
            50,
            Optional.empty(),
            Instant.parse("2026-05-01T00:00:00Z"));

    CursorCodec.DecodedCursor decoded = codec.decode(codec.encode(ts, id, query));

    assertThat(decoded.actor()).isNull();
    assertThat(decoded.resource()).isNull();
  }

  @Test
  void decodeRejectsNullInput() {
    assertThatThrownBy(() -> codec.decode(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank");
  }

  @Test
  void decodeRejectsBlankInput() {
    assertThatThrownBy(() -> codec.decode("   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank");
  }

  @Test
  void decodeRejectsNonBase64Input() {
    assertThatThrownBy(() -> codec.decode("%%%not-base64%%%"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("base64");
  }

  @Test
  void decodeRejectsBase64ThatIsNotJson() {
    String notJson =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("not json".getBytes());
    assertThatThrownBy(() -> codec.decode(notJson))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("JSON");
  }

  @Test
  void decodeRejectsTruncatedJson() {
    String truncated =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"ts\":\"2026-04-15T12".getBytes());
    assertThatThrownBy(() -> codec.decode(truncated))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("JSON");
  }

  @Test
  void decodeRejectsJsonMissingRequiredFields() {
    String partial =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("{\"actor\":\"alice\"}".getBytes());
    assertThatThrownBy(() -> codec.decode(partial))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing required fields");
  }
}
