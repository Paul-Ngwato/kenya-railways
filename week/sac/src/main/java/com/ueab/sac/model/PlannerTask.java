package com.ueab.sac.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import jakarta.persistence.*;

/**
 * One planned work session belonging to a PlannerProject. Tasks may depend
 * on earlier tasks so the scheduler never schedules "Testing" before
 * "Build". PLANNED = proposed by AI, not yet accepted. SCHEDULED = has a
 * date/time and a calendar block.
 */
@Entity
@Table(name = "planner_tasks")
public class PlannerTask {

    public enum Status { PLANNED, SCHEDULED, DONE, SKIPPED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "project_id")
    private PlannerProject project;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PLANNED;

    /** Estimated work in minutes. */
    private int durationMinutes = 90;

    /** Task that must finish before this one starts (nullable). */
    @ManyToOne
    @JoinColumn(name = "depends_on_id")
    private PlannerTask dependsOn;

    private int orderIndex;

    private LocalDate plannedDate;

    private LocalTime plannedStart;

    private LocalTime plannedEnd;

    private LocalDateTime completedAt;

    /** Calendar block created when the plan was accepted. */
    @ManyToOne
    @JoinColumn(name = "event_id")
    private ScheduleEvent event;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public PlannerProject getProject() { return project; }
    public void setProject(PlannerProject project) { this.project = project; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }

    public PlannerTask getDependsOn() { return dependsOn; }
    public void setDependsOn(PlannerTask dependsOn) { this.dependsOn = dependsOn; }

    public int getOrderIndex() { return orderIndex; }
    public void setOrderIndex(int orderIndex) { this.orderIndex = orderIndex; }

    public LocalDate getPlannedDate() { return plannedDate; }
    public void setPlannedDate(LocalDate plannedDate) { this.plannedDate = plannedDate; }

    public LocalTime getPlannedStart() { return plannedStart; }
    public void setPlannedStart(LocalTime plannedStart) { this.plannedStart = plannedStart; }

    public LocalTime getPlannedEnd() { return plannedEnd; }
    public void setPlannedEnd(LocalTime plannedEnd) { this.plannedEnd = plannedEnd; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public ScheduleEvent getEvent() { return event; }
    public void setEvent(ScheduleEvent event) { this.event = event; }
}
