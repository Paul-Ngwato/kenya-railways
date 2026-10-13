package com.ueab.sac.repo;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ueab.sac.model.ScheduleEvent;

public interface ScheduleEventRepository extends JpaRepository<ScheduleEvent, Long> {

    List<ScheduleEvent> findByOwnerIdOrderByDateAscStartTimeAsc(Long ownerId);

    List<ScheduleEvent> findByOwnerIdAndDateOrderByStartTimeAsc(Long ownerId, LocalDate date);

    List<ScheduleEvent> findByOwnerIdAndDateBetweenOrderByDateAscStartTimeAsc(
            Long ownerId, LocalDate start, LocalDate end);
}
