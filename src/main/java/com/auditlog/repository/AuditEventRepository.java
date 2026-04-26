package com.auditlog.repository;

import com.auditlog.domain.AuditEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends Repository<AuditEvent, UUID> {

    AuditEvent save(AuditEvent event);

    Optional<AuditEvent> findById(UUID id);

    @Query("""
            select e from AuditEvent e
            where (cast(:actor    as string)            is null or e.actor    = :actor)
              and (cast(:resource as string)            is null or e.resource = :resource)
              and (cast(:from     as java.time.Instant) is null or e.timestamp >= :from)
              and (cast(:to       as java.time.Instant) is null or e.timestamp <  :to)
            order by e.timestamp desc
            """)
    List<AuditEvent> search(
            @Param("actor") String actor,
            @Param("resource") String resource,
            @Param("from") Instant from,
            @Param("to") Instant to);
}
