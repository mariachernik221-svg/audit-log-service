package com.auditlog.service;

import java.time.Instant;
import java.util.UUID;

public record CursorPosition(Instant ts, UUID id) {}
