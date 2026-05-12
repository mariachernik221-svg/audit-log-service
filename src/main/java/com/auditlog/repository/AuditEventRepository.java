package com.auditlog.repository;

import com.auditlog.domain.AuditEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends Repository<AuditEvent, UUID> {

  AuditEvent save(AuditEvent event);

  @Query(
      """
          select e from AuditEvent e
          where (cast(:actor    as string)            is null or lower(e.actor)    = lower(:actor))
            and (cast(:resource as string)            is null or lower(e.resource) = lower(:resource))
            and e.timestamp >= :from
            and e.timestamp <  :to
            and e.timestamp <= :tStart
            and (cast(:lastTs as java.time.Instant) is null
                 or e.timestamp > :lastTs
                 or (e.timestamp = :lastTs and e.id > :lastId))
          order by e.timestamp asc, e.id asc
          """)
  List<AuditEvent> searchAsc(
      @Param("actor") String actor,
      @Param("resource") String resource,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("tStart") Instant tStart,
      @Param("lastTs") Instant lastTs,
      @Param("lastId") UUID lastId,
      Limit limit);

  @Query(
      """
          select e from AuditEvent e
          where (cast(:actor    as string)            is null or lower(e.actor)    = lower(:actor))
            and (cast(:resource as string)            is null or lower(e.resource) = lower(:resource))
            and e.timestamp >= :from
            and e.timestamp <  :to
            and e.timestamp <= :tStart
            and (cast(:lastTs as java.time.Instant) is null
                 or e.timestamp < :lastTs
                 or (e.timestamp = :lastTs and e.id < :lastId))
          order by e.timestamp desc, e.id desc
          """)
  List<AuditEvent> searchDesc(
      @Param("actor") String actor,
      @Param("resource") String resource,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("tStart") Instant tStart,
      @Param("lastTs") Instant lastTs,
      @Param("lastId") UUID lastId,
      Limit limit);
}
