package com.ueab.sac.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ueab.sac.model.PlannerTask;

public interface PlannerTaskRepository extends JpaRepository<PlannerTask, Long> {

    List<PlannerTask> findByProjectIdOrderByOrderIndexAsc(Long projectId);

    List<PlannerTask> findByProjectOwnerIdAndStatus(Long ownerId, PlannerTask.Status status);
}
