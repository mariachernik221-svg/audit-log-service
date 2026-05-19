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
          where (:actors is null or lower(e.actor) in :actors)
            and (cast(:resource as string) is null
                 or lower(e.resource) = lower(cast(:resource as string)))
            and e.timestamp >= :from
            and e.timestamp <  :to
            and e.timestamp <= :tStart
            and (cast(:lastTs as java.time.Instant) is null
                 or e.timestamp > :lastTs
                 or (e.timestamp = :lastTs and e.id > :lastId))
          order by e.timestamp asc, e.id asc
          """)
  List<AuditEvent> searchAsc(
      @Param("actors") List<String> actors,
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
          where (:actors is null or lower(e.actor) in :actors)
            and (cast(:resource as string) is null
                 or lower(e.resource) = lower(cast(:resource as string)))
            and e.timestamp >= :from
            and e.timestamp <  :to
            and e.timestamp <= :tStart
            and (cast(:lastTs as java.time.Instant) is null
                 or e.timestamp < :lastTs
                 or (e.timestamp = :lastTs and e.id < :lastId))
          order by e.timestamp desc, e.id desc
          """)
  List<AuditEvent> searchDesc(
      @Param("actors") List<String> actors,
      @Param("resource") String resource,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("tStart") Instant tStart,
      @Param("lastTs") Instant lastTs,
      @Param("lastId") UUID lastId,
      Limit limit);
}
