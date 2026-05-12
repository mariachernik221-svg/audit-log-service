package com.auditlog.api;

import com.auditlog.service.Order;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;

public record AuditEventQueryRequest(
    @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
    @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
    String actor,
    String resource,
    Order order,
    @Min(1) @Max(500) Integer limit,
    String cursor) {}
