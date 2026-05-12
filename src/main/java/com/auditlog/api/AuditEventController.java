package com.auditlog.api;

import com.auditlog.domain.AuditEvent;
import com.auditlog.service.AuditEventService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/audit-events")
public class AuditEventController {

  private final AuditEventService service;

  public AuditEventController(AuditEventService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<AuditEventResponse> create(
      @Valid @RequestBody CreateAuditEventRequest request) {
    AuditEvent created =
        service.create(
            request.actor(),
            request.action(),
            request.resource(),
            request.outcome(),
            request.context());
    AuditEventResponse body = AuditEventResponse.from(created);
    return ResponseEntity.created(URI.create("/audit-events/" + created.getId())).body(body);
  }

  @GetMapping
  public AuditEventPage search(@Valid @ModelAttribute AuditEventQueryRequest request) {
    return new AuditEventPage(List.of(), null);
  }
}
