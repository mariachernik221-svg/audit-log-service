package com.auditlog.api;

import com.auditlog.domain.Outcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateAuditEventRequest(
        @NotBlank String actor,
        @NotBlank String action,
        @NotBlank String resource,
        @NotNull Outcome outcome,
        String context
) {}
