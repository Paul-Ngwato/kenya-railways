package com.ueab.sac.repo;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ueab.sac.model.EventChangeLog;

public interface EventChangeLogRepository extends JpaRepository<EventChangeLog, Long> {

    List<EventChangeLog> findByEventIdOrderByAtDesc(Long eventId);

    /** Feed across all of a user's events, newest first. */
    List<EventChangeLog> findByEventOwnerIdOrderByAtDesc(Long ownerId);

    /** Feed limited to a time window (e.g. since midnight today). */
    List<EventChangeLog> findByEventOwnerIdAndAtAfterOrderByAtDesc(Long ownerId, LocalDateTime since);

    /** Used when an event is deleted: its history goes with it. */
    @Modifying
    @Query("delete from EventChangeLog l where l.event.id = :eventId")
    void deleteByEventId(@Param("eventId") Long eventId);
}
