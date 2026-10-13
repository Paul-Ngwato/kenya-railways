package com.ueab.sac.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import jakarta.persistence.*;

/**
 * One item captured in the AI Inbox (a file, a pasted message, or a WhatsApp
 * message). AI extracts structured fields, but nothing reaches the calendar
 * until the user verifies it.
 */
@Entity
@Table(name = "inbox_items")
public class InboxItem {

    public enum Status { PENDING, CONFIRMED, EDITED, REJECTED, DUPLICATE }

    public enum SourceType { TEXT, FILE, WHATSAPP }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "owner_id")
    private User owner;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SourceType sourceType = SourceType.TEXT;

    /** Short label of the origin: filename, "Pasted text", WhatsApp group name... */
    private String sourceLabel;

    /** The raw text the extraction ran on. */
    @Column(columnDefinition = "text")
    private String rawText;

    // ---- AI-extracted fields (editable during verification) ----

    private String title;

    /** ScheduleEvent.Type name, or null when nothing was found. */
    private String eventType;

    private LocalDate eventDate;

    private LocalTime eventTime;

    private LocalTime endTime;

    private String venue;

    /** For weekly timetable rows: comma-separated weekday codes (mon,tue...), or null. */
    @Column(length = 60)
    private String weeklyDays;

    /** Free text warning, e.g. "No date found — please add one". */
    private String warning;

    /** true when a very similar event was detected in the extraction batch. */
    private boolean duplicateSuspect = false;

    /** Points at an existing calendar event when a duplicate was confirmed. */
    @ManyToOne
    @JoinColumn(name = "duplicate_of_event_id")
    private ScheduleEvent duplicateOf;

    /** Fingerprint used for duplicate detection within a batch. */
    private String fingerprint;

    /** Set when the item was turned into a calendar event. */
    @ManyToOne
    @JoinColumn(name = "created_event_id")
    private ScheduleEvent createdEvent;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public SourceType getSourceType() { return sourceType; }
    public void setSourceType(SourceType sourceType) { this.sourceType = sourceType; }

    public String getSourceLabel() { return sourceLabel; }
    public void setSourceLabel(String sourceLabel) { this.sourceLabel = sourceLabel; }

    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public LocalDate getEventDate() { return eventDate; }
    public void setEventDate(LocalDate eventDate) { this.eventDate = eventDate; }

    public LocalTime getEventTime() { return eventTime; }
    public void setEventTime(LocalTime eventTime) { this.eventTime = eventTime; }

    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }

    public String getVenue() { return venue; }
    public void setVenue(String venue) { this.venue = venue; }

    public String getWeeklyDays() { return weeklyDays; }
    public void setWeeklyDays(String weeklyDays) { this.weeklyDays = weeklyDays; }

    public String getWarning() { return warning; }
    public void setWarning(String warning) { this.warning = warning; }

    public boolean isDuplicateSuspect() { return duplicateSuspect; }
    public void setDuplicateSuspect(boolean duplicateSuspect) { this.duplicateSuspect = duplicateSuspect; }

    public ScheduleEvent getDuplicateOf() { return duplicateOf; }
    public void setDuplicateOf(ScheduleEvent duplicateOf) { this.duplicateOf = duplicateOf; }

    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }

    public ScheduleEvent getCreatedEvent() { return createdEvent; }
    public void setCreatedEvent(ScheduleEvent createdEvent) { this.createdEvent = createdEvent; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
