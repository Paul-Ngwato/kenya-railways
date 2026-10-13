package com.ueab.sac.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ueab.sac.model.PlannerProject;

public interface PlannerProjectRepository extends JpaRepository<PlannerProject, Long> {

    /** Fetches projects with their tasks — safe to render progressPercent outside a transaction. */
    @Query("select distinct p from PlannerProject p left join fetch p.tasks "
            + "where p.owner.id = :ownerId order by p.createdAt desc")
    List<PlannerProject> findWithTasksByOwnerId(@Param("ownerId") Long ownerId);

    @Query("select distinct p from PlannerProject p left join fetch p.tasks where p.id = :id")
    Optional<PlannerProject> findWithTasksById(@Param("id") Long id);
}
