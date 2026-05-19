package com.auditlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.auditlog.domain.AuditEvent;
import com.auditlog.domain.Outcome;
import com.auditlog.repository.AuditEventRepository;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

@ExtendWith(MockitoExtension.class)
class AuditEventServiceImplTest {

  @Mock AuditEventRepository repository;

  AuditEventServiceImpl service;
  CursorCodec cursorCodec;

  @BeforeEach
  void setUp() {
    cursorCodec = new CursorCodec();
    service = new AuditEventServiceImpl(repository, cursorCodec);
  }

  @Test
  void createPersistsEventAndStampsTimestampServerSide() {
    Instant before = Instant.now();
    when(repository.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

    AuditEvent result =
        service.create("alice", "user.login", "session:1", Outcome.SUCCESS, "{\"ip\":\"1.2.3.4\"}");

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(repository).save(captor.capture());
    AuditEvent saved = captor.getValue();

    assertThat(saved.getActor()).isEqualTo("alice");
    assertThat(saved.getAction()).isEqualTo("user.login");
    assertThat(saved.getResource()).isEqualTo("session:1");
    assertThat(saved.getOutcome()).isEqualTo(Outcome.SUCCESS);
    assertThat(saved.getContext()).contains("1.2.3.4");
    assertThat(saved.getTimestamp()).isBetween(before, Instant.now());
    assertThat(result).isSameAs(saved);
  }

  @Test
  void createAcceptsNullContext() {
    when(repository.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

    AuditEvent result = service.create("alice", "user.login", "session:1", Outcome.SUCCESS, null);

    assertThat(result.getContext()).isNull();
  }

  @Test
  void createRejectsBlankActor() {
    assertThatThrownBy(() -> service.create("  ", "x", "r", Outcome.SUCCESS, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("actor");
    verifyNoInteractions(repository);
  }

  @Test
  void createRejectsNullActor() {
    assertThatThrownBy(() -> service.create(null, "x", "r", Outcome.SUCCESS, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("actor");
    verifyNoInteractions(repository);
  }

  @Test
  void createRejectsBlankAction() {
    assertThatThrownBy(() -> service.create("alice", "", "r", Outcome.SUCCESS, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("action");
    verifyNoInteractions(repository);
  }

  @Test
  void createRejectsBlankResource() {
    assertThatThrownBy(() -> service.create("alice", "x", " ", Outcome.SUCCESS, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("resource");
    verifyNoInteractions(repository);
  }

  @Test
  void createRejectsNullOutcome() {
    assertThatThrownBy(() -> service.create("alice", "x", "r", null, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("outcome");
    verifyNoInteractions(repository);
  }

  @Test
  void searchReturnsEmptyResultForValidRangeWithoutCursor() {
    Instant from = Instant.parse("2026-04-01T00:00:00Z");
    Instant to = Instant.parse("2026-04-30T00:00:00Z");
    AuditEventQueryInput input =
        new AuditEventQueryInput("alice", "project:42", from, to, Order.ASC, 100, null);

    AuditEventQueryResult result = service.search(input);

    assertThat(result.items()).isEmpty();
    assertThat(result.nextCursor()).isNull();
  }

  @Test
  void searchRejectsNullInput() {
    assertThatThrownBy(() -> service.search(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("input");
    verifyNoInteractions(repository);
  }

  @Test
  void searchRejectsNullFrom() {
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            null, null, null, Instant.parse("2026-04-30T00:00:00Z"), null, null, null);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("from");
  }

  @Test
  void searchRejectsNullTo() {
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            null, null, Instant.parse("2026-04-01T00:00:00Z"), null, null, null, null);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("to");
  }

  @Test
  void searchRejectsFromEqualToTo() {
    Instant t = Instant.parse("2026-04-01T00:00:00Z");
    AuditEventQueryInput input = new AuditEventQueryInput(null, null, t, t, null, null, null);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("from must be before to");
  }

  @Test
  void searchRejectsFromAfterTo() {
    Instant from = Instant.parse("2026-04-30T00:00:00Z");
    Instant to = Instant.parse("2026-04-01T00:00:00Z");
    AuditEventQueryInput input = new AuditEventQueryInput(null, null, from, to, null, null, null);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("from must be before to");
  }

  @Test
  void searchAcceptsExactly90DayWindow() {
    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    Instant to = from.plus(Duration.ofDays(90));
    AuditEventQueryInput input =
        new AuditEventQueryInput(null, null, from, to, Order.ASC, 50, null);

    AuditEventQueryResult result = service.search(input);

    assertThat(result.items()).isEmpty();
    assertThat(result.nextCursor()).isNull();
  }

  @Test
  void searchRejectsWindowLongerThan90Days() {
    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    Instant to = from.plus(Duration.ofDays(91));
    AuditEventQueryInput input = new AuditEventQueryInput(null, null, from, to, null, null, null);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("90 days");
  }

  @Test
  void searchAcceptsMatchingCursor() {
    Instant from = Instant.parse("2026-04-01T00:00:00Z");
    Instant to = Instant.parse("2026-04-30T00:00:00Z");
    Instant tStart = Instant.parse("2026-05-01T12:00:00Z");
    AuditEventQuery prior =
        new AuditEventQuery(
            List.of("alice"),
            "project:42",
            from,
            to,
            Order.ASC,
            50,
            java.util.Optional.empty(),
            tStart);
    String cursor =
        cursorCodec.encode(Instant.parse("2026-04-15T00:00:00Z"), UUID.randomUUID(), prior);

    AuditEventQueryInput input =
        new AuditEventQueryInput("alice", "project:42", from, to, Order.ASC, 50, cursor);

    AuditEventQueryResult result = service.search(input);
    assertThat(result.items()).isEmpty();
    assertThat(result.nextCursor()).isNull();
  }

  @Test
  void searchRejectsCursorWithMismatchedActor() {
    String cursor =
        encodedCursor(List.of("alice"), "r", asc("2026-04-01"), asc("2026-04-30"), Order.ASC);
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            "bob", "r", asc("2026-04-01"), asc("2026-04-30"), Order.ASC, 50, cursor);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cursor does not match");
  }

  @Test
  void searchRejectsCursorWithMismatchedResource() {
    String cursor =
        encodedCursor(List.of("a"), "r1", asc("2026-04-01"), asc("2026-04-30"), Order.ASC);
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            "a", "r2", asc("2026-04-01"), asc("2026-04-30"), Order.ASC, 50, cursor);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cursor does not match");
  }

  @Test
  void searchRejectsCursorWithMismatchedFrom() {
    String cursor =
        encodedCursor(List.of("a"), "r", asc("2026-04-01"), asc("2026-04-30"), Order.ASC);
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            "a", "r", asc("2026-04-02"), asc("2026-04-30"), Order.ASC, 50, cursor);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cursor does not match");
  }

  @Test
  void searchRejectsCursorWithMismatchedTo() {
    String cursor =
        encodedCursor(List.of("a"), "r", asc("2026-04-01"), asc("2026-04-30"), Order.ASC);
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            "a", "r", asc("2026-04-01"), asc("2026-04-29"), Order.ASC, 50, cursor);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cursor does not match");
  }

  @Test
  void searchRejectsCursorWithMismatchedOrder() {
    String cursor =
        encodedCursor(List.of("a"), "r", asc("2026-04-01"), asc("2026-04-30"), Order.ASC);
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            "a", "r", asc("2026-04-01"), asc("2026-04-30"), Order.DESC, 50, cursor);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cursor does not match");
  }

  @Test
  void searchRejectsCursorWithMismatchedActorList() {
    String cursor =
        encodedCursor(
            List.of("alice", "bob"), "r", asc("2026-04-01"), asc("2026-04-30"), Order.ASC);
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            "alice,carol", "r", asc("2026-04-01"), asc("2026-04-30"), Order.ASC, 50, cursor);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cursor does not match");
  }

  @Test
  void searchTreatsNullActorParamAsNoFilter() {
    String cursor = encodedCursor(List.of(), null, asc("2026-04-01"), asc("2026-04-30"), Order.ASC);
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            null, "", asc("2026-04-01"), asc("2026-04-30"), Order.ASC, 50, cursor);

    AuditEventQueryResult result = service.search(input);
    assertThat(result.items()).isEmpty();
  }

  @Test
  void searchRejectsBlankActorParameter() {
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            "", null, asc("2026-04-01"), asc("2026-04-30"), Order.ASC, 50, null);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("actor");
    verifyNoInteractions(repository);
  }

  @Test
  void searchRejectsWhitespaceOnlyActorParameter() {
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            "   ", null, asc("2026-04-01"), asc("2026-04-30"), Order.ASC, 50, null);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("actor");
    verifyNoInteractions(repository);
  }

  @Test
  void searchRejectsActorListWithBlankEntry() {
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            "alice,,bob", null, asc("2026-04-01"), asc("2026-04-30"), Order.ASC, 50, null);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank entries");
    verifyNoInteractions(repository);
  }

  @Test
  void searchRejectsTrailingCommaActorList() {
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            "alice,bob,", null, asc("2026-04-01"), asc("2026-04-30"), Order.ASC, 50, null);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank entries");
    verifyNoInteractions(repository);
  }

  @Test
  void searchRejectsCommaOnlyActorList() {
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            ",,", null, asc("2026-04-01"), asc("2026-04-30"), Order.ASC, 50, null);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank entries");
    verifyNoInteractions(repository);
  }

  @Test
  void searchAcceptsCursorWhenRequestActorListMatchesAfterNormalization() {
    String cursor =
        encodedCursor(
            List.of("alice", "bob"), "r", asc("2026-04-01"), asc("2026-04-30"), Order.ASC);
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            " BOB , alice , ALICE ",
            "r",
            asc("2026-04-01"),
            asc("2026-04-30"),
            Order.ASC,
            50,
            cursor);

    AuditEventQueryResult result = service.search(input);
    assertThat(result.items()).isEmpty();
  }

  @Test
  void searchRejectsMalformedCursor() {
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            "a", "r", asc("2026-04-01"), asc("2026-04-30"), Order.ASC, 50, "%%not-base64%%");
    assertThatThrownBy(() -> service.search(input)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void searchRejectsActorListAbove10DistinctValues() {
    String input11 = "a1,a2,a3,a4,a5,a6,a7,a8,a9,a10,a11";
    AuditEventQueryInput input =
        new AuditEventQueryInput(
            input11, null, asc("2026-04-01"), asc("2026-04-30"), Order.ASC, 50, null);
    assertThatThrownBy(() -> service.search(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("actor list");
    verifyNoInteractions(repository);
  }

  @Test
  void searchAcceptsExactly10DistinctActors() {
    String input10 = "a1,a2,a3,a4,a5,a6,a7,a8,a9,a10";
    when(repository.searchAsc(any(), any(), any(), any(), any(), any(), any(), any(Limit.class)))
        .thenReturn(List.of());

    AuditEventQueryResult result =
        service.search(
            new AuditEventQueryInput(
                input10, null, asc("2026-04-01"), asc("2026-04-30"), Order.ASC, 50, null));

    assertThat(result.items()).isEmpty();
  }

  @Test
  void searchNormalizesActorListDedupesTrimsAndLowercasesBeforeCapCheck() {
    String input = " Alice , BOB , alice , bob , CAROL ";
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    when(repository.searchAsc(any(), any(), any(), any(), any(), any(), any(), any(Limit.class)))
        .thenReturn(List.of());

    service.search(
        new AuditEventQueryInput(
            input, null, asc("2026-04-01"), asc("2026-04-30"), Order.ASC, 50, null));

    verify(repository)
        .searchAsc(captor.capture(), any(), any(), any(), any(), any(), any(), any(Limit.class));
    assertThat(captor.getValue()).containsExactly("alice", "bob", "carol");
  }

  @Test
  void searchPassesNullActorsParamWhenFilterAbsent() {
    when(repository.searchAsc(any(), any(), any(), any(), any(), any(), any(), any(Limit.class)))
        .thenReturn(List.of());

    service.search(
        new AuditEventQueryInput(
            null, null, asc("2026-04-01"), asc("2026-04-30"), Order.ASC, 50, null));

    verify(repository)
        .searchAsc(isNull(), any(), any(), any(), any(), any(), any(), any(Limit.class));
  }

  private String encodedCursor(
      List<String> actors, String resource, Instant from, Instant to, Order o) {
    AuditEventQuery prior =
        new AuditEventQuery(
            actors,
            resource,
            from,
            to,
            o,
            50,
            java.util.Optional.empty(),
            Instant.parse("2026-05-01T00:00:00Z"));
    return cursorCodec.encode(from.plusSeconds(1), UUID.randomUUID(), prior);
  }

  private static Instant asc(String date) {
    return Instant.parse(date + "T00:00:00Z");
  }

  @Test
  void searchTrimsToLimitAndBuildsNextCursorWhenMoreThanLimit() {
    Instant from = asc("2026-04-01");
    Instant to = asc("2026-04-30");
    AuditEvent e1 = event(asc("2026-04-10"));
    AuditEvent e2 = event(asc("2026-04-11"));
    AuditEvent e3 = event(asc("2026-04-12"));
    when(repository.searchAsc(
            isNull(),
            isNull(),
            eq(from),
            eq(to),
            any(Instant.class),
            isNull(),
            isNull(),
            any(Limit.class)))
        .thenReturn(List.of(e1, e2, e3));

    AuditEventQueryResult result =
        service.search(new AuditEventQueryInput(null, null, from, to, Order.ASC, 2, null));

    assertThat(result.items())
        .extracting(AuditEvent::getId)
        .containsExactly(e1.getId(), e2.getId());
    assertThat(result.nextCursor()).isNotNull();
  }

  @Test
  void searchReturnsNullNextCursorWhenResultsFitWithinLimit() {
    Instant from = asc("2026-04-01");
    Instant to = asc("2026-04-30");
    AuditEvent e1 = event(asc("2026-04-10"));
    AuditEvent e2 = event(asc("2026-04-11"));
    when(repository.searchAsc(any(), any(), any(), any(), any(), any(), any(), any(Limit.class)))
        .thenReturn(List.of(e1, e2));

    AuditEventQueryResult result =
        service.search(new AuditEventQueryInput(null, null, from, to, Order.ASC, 2, null));

    assertThat(result.items()).hasSize(2);
    assertThat(result.nextCursor()).isNull();
  }

  @Test
  void searchAscDispatchesToSearchAscAndRequestsLimitPlusOne() {
    Instant from = asc("2026-04-01");
    Instant to = asc("2026-04-30");
    when(repository.searchAsc(any(), any(), any(), any(), any(), any(), any(), any(Limit.class)))
        .thenReturn(List.of());

    service.search(new AuditEventQueryInput(null, null, from, to, Order.ASC, 10, null));

    ArgumentCaptor<Limit> limitCaptor = ArgumentCaptor.forClass(Limit.class);
    verify(repository)
        .searchAsc(any(), any(), any(), any(), any(), any(), any(), limitCaptor.capture());
    assertThat(limitCaptor.getValue().max()).isEqualTo(11);
  }

  @Test
  void searchDescDispatchesToSearchDesc() {
    Instant from = asc("2026-04-01");
    Instant to = asc("2026-04-30");
    when(repository.searchDesc(any(), any(), any(), any(), any(), any(), any(), any(Limit.class)))
        .thenReturn(List.of());

    service.search(new AuditEventQueryInput(null, null, from, to, Order.DESC, 10, null));

    verify(repository)
        .searchDesc(any(), any(), any(), any(), any(), any(), any(), any(Limit.class));
  }

  @Test
  void searchPassesCursorPositionAndPropagatesTStartToRepository() {
    Instant from = asc("2026-04-01");
    Instant to = asc("2026-04-30");
    Instant tStart = Instant.parse("2026-05-01T00:00:00Z");
    Instant lastTs = asc("2026-04-15");
    UUID lastId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    AuditEventQuery prior =
        new AuditEventQuery(
            List.of(), null, from, to, Order.ASC, 50, java.util.Optional.empty(), tStart);
    String cursor = cursorCodec.encode(lastTs, lastId, prior);
    when(repository.searchAsc(any(), any(), any(), any(), any(), any(), any(), any(Limit.class)))
        .thenReturn(List.of());

    service.search(new AuditEventQueryInput(null, null, from, to, Order.ASC, 50, cursor));

    verify(repository)
        .searchAsc(
            isNull(),
            isNull(),
            eq(from),
            eq(to),
            eq(tStart),
            eq(lastTs),
            eq(lastId),
            any(Limit.class));
  }

  @Test
  void searchEncodesNextCursorUsingLastKeptRowAndPropagatedTStart() {
    Instant from = asc("2026-04-01");
    Instant to = asc("2026-04-30");
    Instant tStart = Instant.parse("2026-05-01T00:00:00Z");
    AuditEventQuery prior =
        new AuditEventQuery(
            List.of(), null, from, to, Order.ASC, 50, java.util.Optional.empty(), tStart);
    String inboundCursor = cursorCodec.encode(asc("2026-04-05"), UUID.randomUUID(), prior);
    AuditEvent e1 = event(asc("2026-04-10"));
    AuditEvent e2 = event(asc("2026-04-11"));
    AuditEvent e3 = event(asc("2026-04-12"));
    when(repository.searchAsc(any(), any(), any(), any(), any(), any(), any(), any(Limit.class)))
        .thenReturn(List.of(e1, e2, e3));

    AuditEventQueryResult result =
        service.search(new AuditEventQueryInput(null, null, from, to, Order.ASC, 2, inboundCursor));

    assertThat(result.nextCursor()).isNotNull();
    CursorCodec.DecodedCursor decoded = cursorCodec.decode(result.nextCursor());
    assertThat(decoded.ts()).isEqualTo(e2.getTimestamp());
    assertThat(decoded.id()).isEqualTo(e2.getId());
    assertThat(decoded.tStart()).isEqualTo(tStart);
  }

  private static AuditEvent event(Instant ts) {
    AuditEvent event = new AuditEvent("alice", "x", "r", Outcome.SUCCESS, null);
    setField(event, "id", UUID.randomUUID());
    setField(event, "timestamp", ts);
    return event;
  }

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
