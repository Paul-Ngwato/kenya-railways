package com.ueab.sac.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.*;

/** A single schedule item: class, exam, CAT, deadline, etc. */
@Entity
@Table(name = "schedule_events")
public class ScheduleEvent {

    public enum Type { CLASS, ASSIGNMENT, STUDY, PRACTICAL, MEETING, EVENT, WORK, REMINDER, CAT, EXAM, DEADLINE, OTHER,
                       CLUB, SPORT, WORSHIP, VOLUNTEER }

    /** Academic vs co-curricular bucket for filtering and stats. */
    public boolean isCoCurricular() {
        return type == Type.CLUB || type == Type.SPORT || type == Type.WORSHIP || type == Type.VOLUNTEER;
    }

    /** Lifecycle status for a single day's activity (the "daily log" concept). */
    public enum Status { UPCOMING, IN_PROGRESS, COMPLETED, CHANGED, RESCHEDULED, POSTPONED, CANCELLED, DID_NOT_HAPPEN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "owner_id")
    private User owner;

    @ManyToOne
    @JoinColumn(name = "course_id")
    private Course course;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type = Type.CLASS;

    private LocalDate date;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private String venue;

    /** Venue before the last change, so the card can show "Lab 2 → Lab 4". */
    private String prevVenue;

    /** User-friendly summary of the latest change, e.g. "Location changed". */
    private String lastChangeLabel;

    /** Lifecycle status (daily-log). UPCOMING is the effective default at render time. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Status status;

    private String notes;

    /** true when created automatically (e.g. AI Inbox confirm) */
    private boolean autoSynced = false;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }

    public Course getCourse() { return course; }
    public void setCourse(Course course) { this.course = course; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }

    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }

    public String getVenue() { return venue; }
    public void setVenue(String venue) { this.venue = venue; }

    public String getPrevVenue() { return prevVenue; }
    public void setPrevVenue(String prevVenue) { this.prevVenue = prevVenue; }

    public String getLastChangeLabel() { return lastChangeLabel; }
    public void setLastChangeLabel(String lastChangeLabel) { this.lastChangeLabel = lastChangeLabel; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    /** Status shown in UI: stored status, or UPCOMING when never touched. */
    public Status getEffectiveStatus() {
        return status == null ? Status.UPCOMING : status;
    }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public boolean isAutoSynced() { return autoSynced; }
    public void setAutoSynced(boolean autoSynced) { this.autoSynced = autoSynced; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
