package com.auditlog.repository;

import com.auditlog.domain.AuditEvent;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface AuditEventRepository extends Repository<AuditEvent, UUID> {

  AuditEvent save(AuditEvent event);
}
