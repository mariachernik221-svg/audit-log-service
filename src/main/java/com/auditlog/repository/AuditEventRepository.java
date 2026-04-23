package com.auditlog.repository;

import com.auditlog.domain.AuditEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findByActor(String actor);

    List<AuditEvent> findByResource(String resource);

    List<AuditEvent> findByTimestampBetween(Instant from, Instant to);
}
