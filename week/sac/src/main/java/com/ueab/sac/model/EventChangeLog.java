package com.ueab.sac.model;

import java.time.LocalDateTime;

import jakarta.persistence.*;

/**
 * Append-only log of everything that happened to a schedule event during its day:
 * completions, location changes, cancellations, notes attached to an activity, etc.
 * Powers the dashboard's "Recent Updates" feed.
 */
@Entity
@Table(name = "event_change_logs")
public class EventChangeLog {

    public enum Kind { COMPLETED, LOCATION_CHANGED, LECTURER_CHANGED, RESCHEDULED, POSTPONED, CANCELLED, DID_NOT_HAPPEN, REOPENED, NOTE, ADDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "event_id")
    private ScheduleEvent event;

    @ManyToOne(optional = false)
    @JoinColumn(name = "actor_id")
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    /** Human-readable summary, e.g. "Chemistry moved from Lab 2 → Lab 4". */
    @Column(nullable = false)
    private String label;

    /** Optional detail, e.g. the reason for a room change. */
    private String detail;

    private LocalDateTime at = LocalDateTime.now();

    public Long getId() { return id; }

    public ScheduleEvent getEvent() { return event; }
    public void setEvent(ScheduleEvent event) { this.event = event; }

    public User getActor() { return actor; }
    public void setActor(User actor) { this.actor = actor; }

    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public LocalDateTime getAt() { return at; }
    public void setAt(LocalDateTime at) { this.at = at; }
}
