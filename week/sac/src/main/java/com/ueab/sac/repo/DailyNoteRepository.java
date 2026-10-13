package com.ueab.sac.repo;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ueab.sac.model.DailyNote;

public interface DailyNoteRepository extends JpaRepository<DailyNote, Long> {

    Optional<DailyNote> findByOwnerIdAndDay(Long ownerId, LocalDate day);
}
