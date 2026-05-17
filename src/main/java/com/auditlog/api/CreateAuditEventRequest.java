package com.auditlog.api;

import com.auditlog.domain.Outcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateAuditEventRequest(
    @NotNull @NotBlank String actor,
    @NotNull @NotBlank String action,
    @NotNull @NotBlank String resource,
    @NotNull Outcome outcome,
    String context) {}
