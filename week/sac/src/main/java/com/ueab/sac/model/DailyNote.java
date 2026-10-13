package com.ueab.sac.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.*;

/** One user's personal note about a specific day — the "My Day" journal. */
@Entity
@Table(name = "daily_notes",
       uniqueConstraints = @UniqueConstraint(columnNames = {"owner_id", "day"}))
public class DailyNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "owner_id")
    private User owner;

    @Column(nullable = false)
    private LocalDate day;

    @Column(length = 2000)
    private String body;

    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }

    public User getOwner() { return owner; }
    public void setOwner(User owner) { this.owner = owner; }

    public LocalDate getDay() { return day; }
    public void setDay(LocalDate day) { this.day = day; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
