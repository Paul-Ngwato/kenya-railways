package com.ueab.sac.controller;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.ueab.sac.model.Course;
import com.ueab.sac.model.DailyNote;
import com.ueab.sac.model.Enrollment;
import com.ueab.sac.model.EventChangeLog;
import com.ueab.sac.model.PlannerProject;
import com.ueab.sac.model.Role;
import com.ueab.sac.model.ScheduleEvent;
import com.ueab.sac.model.User;
import com.ueab.sac.repo.CourseRepository;
import com.ueab.sac.repo.DailyNoteRepository;
import com.ueab.sac.repo.EnrollmentRepository;
import com.ueab.sac.repo.EventChangeLogRepository;
import com.ueab.sac.repo.InboxItemRepository;
import com.ueab.sac.repo.PlannerProjectRepository;
import com.ueab.sac.repo.ScheduleEventRepository;
import com.ueab.sac.repo.UserRepository;
import com.ueab.sac.security.CurrentUser;
import com.ueab.sac.service.inbox.InboxService;

/**
 * Dashboard: role-aware — admins see platform stats, students get a
 * "daily activity command center": today's cards, quick actions,
 * timeline, journal and a change feed.
 */
@Controller
public class DashboardController {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("h:mm a");
    private static final DateTimeFormatter TIME_SHORT_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final UserRepository users;
    private final EnrollmentRepository enrollments;
    private final ScheduleEventRepository events;
    private final CourseRepository courses;
    private final EventChangeLogRepository changeLogs;
    private final DailyNoteRepository dailyNotes;
    private final InboxItemRepository inboxItems;
    private final PlannerProjectRepository plannerProjects;
    private final InboxService inboxService;

    public DashboardController(UserRepository users,
                               EnrollmentRepository enrollments,
                               ScheduleEventRepository events,
                               CourseRepository courses,
                               EventChangeLogRepository changeLogs,
                               DailyNoteRepository dailyNotes,
                               InboxItemRepository inboxItems,
                               PlannerProjectRepository plannerProjects,
                               InboxService inboxService) {
        this.users = users;
        this.enrollments = enrollments;
        this.events = events;
        this.courses = courses;
        this.changeLogs = changeLogs;
        this.dailyNotes = dailyNotes;
        this.inboxItems = inboxItems;
        this.plannerProjects = plannerProjects;
        this.inboxService = inboxService;
    }

    @GetMapping("/")
    public String index() {
        return "redirect:/dashboard";
    }

    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(required = false) LocalDate date, Model model) {
        User me = CurrentUser.get(users);

        if (me.getRole() == Role.ADMIN) {
            return adminDashboard(model, me);
        }
        return studentDashboard(model, me, date != null ? date : LocalDate.now());
    }

    /* ---------------- admin dashboard (unchanged) ---------------- */

    private String adminDashboard(Model model, User me) {
        Map<String, Long> stats = new HashMap<>();
        stats.put("users", users.count());
        stats.put("students", users.findAll().stream().filter(u -> u.getRole() == Role.STUDENT).count());
        stats.put("courses", courses.count());
        stats.put("enrollments", enrollments.count());

        List<ScheduleEvent> upcoming = events.findAll().stream()
                .filter(e -> e.getDate() != null && !e.getDate().isBefore(LocalDate.now()))
                .sorted(java.util.Comparator.comparing(ScheduleEvent::getDate,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                .limit(8)
                .toList();

        model.addAttribute("me", me);
        model.addAttribute("stats", stats);
        model.addAttribute("upcoming", upcoming);
        return "dashboard-admin";
    }

    /* ---------------- student: daily command center ---------------- */

    private String studentDashboard(Model model, User me, LocalDate day) {
        List<ScheduleEvent> dayEvents =
                events.findByOwnerIdAndDateOrderByStartTimeAsc(me.getId(), day);

        model.addAttribute("me", me);
        model.addAttribute("day", day);
        model.addAttribute("greeting", greeting());
        model.addAttribute("weekStrip", weekStrip(day));
        model.addAttribute("cards", activityCards(dayEvents));
        model.addAttribute("summary", summary(dayEvents));
        model.addAttribute("timeline", timeline(dayEvents));
        model.addAttribute("important", important(dayEvents));
        model.addAttribute("recentUpdates", recentUpdates(me, day));
        model.addAttribute("tomorrow", events
                .findByOwnerIdAndDateOrderByStartTimeAsc(me.getId(), day.plusDays(1))
                .stream().limit(5).map(this::nextDayLine).toList());
        model.addAttribute("journal", dailyNotes.findByOwnerIdAndDay(me.getId(), day)
                .map(DailyNote::getBody).orElse(""));
        model.addAttribute("enrollments", enrollments.findByStudentId(me.getId()));

        // --- pipeline cards: AI Inbox + Planner ---
        model.addAttribute("inboxPending", inboxService.pendingCount(me));
        List<Map<String, Object>> plannerCards = new ArrayList<>();
        for (PlannerProject p : plannerProjects.findWithTasksByOwnerId(me.getId())) {
            if (p.getStatus() != PlannerProject.Status.ACTIVE) continue;
            Map<String, Object> pc = new HashMap<>();
            pc.put("id", p.getId());
            pc.put("name", p.getName());
            pc.put("progress", p.getProgressPercent());
            pc.put("deadline", p.getDeadline());
            plannerCards.add(pc);
            if (plannerCards.size() == 3) break;
        }
        model.addAttribute("plannerProjects", plannerCards);
        return "dashboard";
    }

    private String greeting() {
        int h = LocalTime.now().getHour();
        if (h < 12) return "Good morning";
        if (h < 17) return "Good afternoon";
        return "Good evening";
    }

    /** Monday..Sunday strip around the selected day. */
    private List<Map<String, Object>> weekStrip(LocalDate selected) {
        LocalDate monday = selected.with(DayOfWeek.MONDAY);
        List<Map<String, Object>> strip = new ArrayList<>();
        String[] labels = {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"};
        for (int i = 0; i < 7; i++) {
            LocalDate d = monday.plusDays(i);
            Map<String, Object> m = new HashMap<>();
            m.put("label", labels[i]);
            m.put("dayNum", d.getDayOfMonth());
            m.put("iso", d.toString());
            m.put("isToday", d.equals(LocalDate.now()));
            m.put("isSelected", d.equals(selected));
            strip.add(m);
        }
        return strip;
    }

    private static final Map<ScheduleEvent.Type, String> TYPE_EMOJI = Map.ofEntries(
            Map.entry(ScheduleEvent.Type.CLASS, "📚"),
            Map.entry(ScheduleEvent.Type.ASSIGNMENT, "📝"),
            Map.entry(ScheduleEvent.Type.STUDY, "📖"),
            Map.entry(ScheduleEvent.Type.PRACTICAL, "🧪"),
            Map.entry(ScheduleEvent.Type.MEETING, "📅"),
            Map.entry(ScheduleEvent.Type.EVENT, "🎯"),
            Map.entry(ScheduleEvent.Type.WORK, "💼"),
            Map.entry(ScheduleEvent.Type.REMINDER, "🔔"),
            Map.entry(ScheduleEvent.Type.CAT, "📝"),
            Map.entry(ScheduleEvent.Type.EXAM, "🧠"),
            Map.entry(ScheduleEvent.Type.DEADLINE, "⏰"),
            Map.entry(ScheduleEvent.Type.OTHER, "📌"));

    private static final Map<ScheduleEvent.Status, String> STATUS_LABEL = Map.of(
            ScheduleEvent.Status.UPCOMING, "Upcoming",
            ScheduleEvent.Status.IN_PROGRESS, "In progress",
            ScheduleEvent.Status.COMPLETED, "Completed",
            ScheduleEvent.Status.CHANGED, "Changed",
            ScheduleEvent.Status.RESCHEDULED, "Rescheduled",
            ScheduleEvent.Status.POSTPONED, "Postponed",
            ScheduleEvent.Status.CANCELLED, "Cancelled",
            ScheduleEvent.Status.DID_NOT_HAPPEN, "Didn't happen");

    /** Floating activity cards for the selected day. */
    private List<Map<String, Object>> activityCards(List<ScheduleEvent> dayEvents) {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (ScheduleEvent e : dayEvents) {
            Map<String, Object> c = new HashMap<>();
            c.put("id", e.getId());
            c.put("emoji", TYPE_EMOJI.getOrDefault(e.getType(), "📌"));
            c.put("title", e.getTitle());
            c.put("timeRange", timeRange(e.getStartTime(), e.getEndTime()));
            c.put("venue", e.getVenue());
            c.put("prevVenue", e.getPrevVenue());
            c.put("moved", e.getPrevVenue() != null && !e.getPrevVenue().isBlank());
            c.put("notes", e.getNotes());
            ScheduleEvent.Status st = e.getEffectiveStatus();
            c.put("status", st.name());
            c.put("statusLabel", STATUS_LABEL.get(st));
            c.put("isDone", st == ScheduleEvent.Status.COMPLETED);
            c.put("isOff", st == ScheduleEvent.Status.CANCELLED
                    || st == ScheduleEvent.Status.DID_NOT_HAPPEN
                    || st == ScheduleEvent.Status.POSTPONED);
            cards.add(c);
        }
        return cards;
    }

    private String timeRange(LocalDateTime start, LocalDateTime end) {
        if (start == null && end == null) return "All day";
        String s = start != null ? start.toLocalTime().format(TIME_SHORT_FMT) : "?";
        String e = end != null ? end.toLocalTime().format(TIME_SHORT_FMT) : null;
        return e != null ? s + " – " + e : s;
    }

    private Map<String, Object> summary(List<ScheduleEvent> dayEvents) {
        Map<String, Object> s = new HashMap<>();
        s.put("total", dayEvents.size());
        s.put("completed", dayEvents.stream().filter(e -> e.getEffectiveStatus() == ScheduleEvent.Status.COMPLETED).count());
        s.put("remaining", dayEvents.stream().filter(e ->
                e.getEffectiveStatus() == ScheduleEvent.Status.UPCOMING
                        || e.getEffectiveStatus() == ScheduleEvent.Status.IN_PROGRESS).count());
        s.put("changed", dayEvents.stream().filter(e ->
                e.getEffectiveStatus() == ScheduleEvent.Status.CHANGED
                        || e.getEffectiveStatus() == ScheduleEvent.Status.RESCHEDULED).count());
        return s;
    }

    /** Timeline rows: events plus "break" markers for gaps of an hour or more. */
    private List<Map<String, Object>> timeline(List<ScheduleEvent> dayEvents) {
        List<Map<String, Object>> rows = new ArrayList<>();
        LocalTime lastEnd = null;
        for (ScheduleEvent e : dayEvents) {
            if (e.getStartTime() == null) continue;
            LocalTime start = e.getStartTime().toLocalTime();
            if (lastEnd != null && start.isAfter(lastEnd.plusMinutes(59))) {
                Map<String, Object> brk = new HashMap<>();
                brk.put("isBreak", true);
                brk.put("time", lastEnd.format(TIME_SHORT_FMT));
                rows.add(brk);
            }
            Map<String, Object> r = new HashMap<>();
            r.put("isBreak", false);
            r.put("time", start.format(TIME_SHORT_FMT));
            r.put("emoji", TYPE_EMOJI.getOrDefault(e.getType(), "📌"));
            r.put("title", e.getTitle());
            r.put("venue", e.getVenue());
            ScheduleEvent.Status st = e.getEffectiveStatus();
            r.put("status", st.name());
            r.put("statusLabel", STATUS_LABEL.get(st));
            r.put("eventId", e.getId());
            rows.add(r);
            if (e.getEndTime() != null) lastEnd = e.getEndTime().toLocalTime();
            else if (start.isAfter(lastEnd == null ? LocalTime.MIN : lastEnd)) lastEnd = start;
        }
        return rows;
    }

    /** Announcements for the selected day: location changes, cancellations, reschedules. */
    private List<Map<String, Object>> important(List<ScheduleEvent> dayEvents) {
        return dayEvents.stream()
                .filter(e -> e.getEffectiveStatus() == ScheduleEvent.Status.CHANGED
                        || e.getEffectiveStatus() == ScheduleEvent.Status.RESCHEDULED
                        || e.getEffectiveStatus() == ScheduleEvent.Status.CANCELLED
                        || e.getEffectiveStatus() == ScheduleEvent.Status.POSTPONED)
                .limit(3)
                .map(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", e.getId());
                    m.put("title", e.getTitle());
                    ScheduleEvent.Status st = e.getEffectiveStatus();
                    m.put("statusLabel", STATUS_LABEL.get(st));
                    m.put("moved", e.getPrevVenue() != null && !e.getPrevVenue().isBlank());
                    m.put("prevVenue", e.getPrevVenue());
                    m.put("venue", e.getVenue());
                    m.put("time", e.getStartTime() != null
                            ? e.getStartTime().toLocalTime().format(TIME_FMT) : "");
                    return m;
                }).toList();
    }

    private List<Map<String, Object>> recentUpdates(User me, LocalDate day) {
        return changeLogs.findByEventOwnerIdAndAtAfterOrderByAtDesc(me.getId(), day.atStartOfDay())
                .stream().limit(8)
                .map(l -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("time", l.getAt().toLocalTime().format(TIME_FMT));
                    m.put("icon", iconFor(l.getKind()));
                    m.put("label", l.getLabel());
                    return m;
                }).toList();
    }

    private String iconFor(EventChangeLog.Kind kind) {
        return switch (kind) {
            case COMPLETED -> "✓";
            case LOCATION_CHANGED, LECTURER_CHANGED -> "🔄";
            case RESCHEDULED -> "↻";
            case POSTPONED -> "⏸";
            case CANCELLED, DID_NOT_HAPPEN -> "✕";
            case REOPENED -> "↩";
            case NOTE -> "📝";
            case ADDED -> "📌";
        };
    }

    private String nextDayLine(ScheduleEvent e) {
        String time = e.getStartTime() != null
                ? e.getStartTime().toLocalTime().format(TIME_FMT)
                : "";
        return (TYPE_EMOJI.getOrDefault(e.getType(), "📌") + " "
                + e.getTitle() + (time.isBlank() ? "" : " — " + time));
    }

    /* ---------------- actions ---------------- */

    private String backTo(LocalDate day) {
        return "redirect:/dashboard?date=" + day;
    }

    @PostMapping("/dashboard/activities")
    public String addActivity(@RequestParam String title,
                              @RequestParam String type,
                              @RequestParam LocalDate date,
                              @RequestParam(required = false) LocalTime start,
                              @RequestParam(required = false) LocalTime end,
                              @RequestParam(required = false) String venue,
                              @RequestParam(required = false) String lecturer,
                              @RequestParam(required = false) String notes,
                              @RequestParam(required = false) Long courseId) {
        User me = CurrentUser.get(users);
        ScheduleEvent e = new ScheduleEvent();
        e.setOwner(me);
        e.setTitle(title);
        try {
            e.setType(ScheduleEvent.Type.valueOf(type.toUpperCase()));
        } catch (Exception ex) {
            e.setType(ScheduleEvent.Type.OTHER);
        }
        e.setDate(date);
        if (start != null) e.setStartTime(LocalDateTime.of(date, start));
        if (end != null) e.setEndTime(LocalDateTime.of(date, end));
        e.setVenue(venue);
        String body = notes == null ? "" : notes;
        if (lecturer != null && !lecturer.isBlank()) {
            body = "Lecturer: " + lecturer + (body.isBlank() ? "" : "\n" + body);
        }
        e.setNotes(body.isBlank() ? null : body);
        if (courseId != null) {
            Course c = courses.findById(courseId).orElse(null);
            if (c != null && enrollments.existsByStudentIdAndCourseId(me.getId(), courseId)) {
                e.setCourse(c);
            }
        }
        events.save(e);

        EventChangeLog log = new EventChangeLog();
        log.setEvent(e);
        log.setActor(me);
        log.setKind(EventChangeLog.Kind.ADDED);
        log.setLabel("Added " + e.getTitle());
        changeLogs.save(log);

        return backTo(date);
    }

    /** Status transitions from the activity action panel. */
    @PostMapping("/dashboard/activities/{id}/status")
    public String setStatus(@PathVariable Long id,
                            @RequestParam String status,
                            @RequestParam(required = false) LocalDate day) {
        User me = CurrentUser.get(users);
        events.findById(id)
                .filter(e -> e.getOwner().getId().equals(me.getId()))
                .ifPresent(e -> applyStatus(e, status, me));
        return backTo(day != null ? day : LocalDate.now());
    }

    private void applyStatus(ScheduleEvent e, String status, User me) {
        ScheduleEvent.Status st;
        EventChangeLog.Kind kind;
        String label;
        try {
            st = ScheduleEvent.Status.valueOf(status.toUpperCase());
        } catch (Exception ex) {
            return;
        }
        switch (st) {
            case UPCOMING -> { kind = EventChangeLog.Kind.REOPENED; label = e.getTitle() + " reopened"; }
            case COMPLETED -> { kind = EventChangeLog.Kind.COMPLETED; label = e.getTitle() + " marked completed"; }
            case RESCHEDULED -> { kind = EventChangeLog.Kind.RESCHEDULED; label = e.getTitle() + " rescheduled"; }
            case POSTPONED -> { kind = EventChangeLog.Kind.POSTPONED; label = e.getTitle() + " postponed"; }
            case CANCELLED -> { kind = EventChangeLog.Kind.CANCELLED; label = e.getTitle() + " cancelled"; }
            case DID_NOT_HAPPEN -> { kind = EventChangeLog.Kind.DID_NOT_HAPPEN; label = e.getTitle() + " didn't happen"; }
            default -> { return; }
        }
        e.setStatus(st);
        events.save(e);
        log(me, e, kind, label, null);
    }

    /** Location change: keeps the old venue so the card shows "Lab 2 → Lab 4". */
    @PostMapping("/dashboard/activities/{id}/venue")
    public String changeVenue(@PathVariable Long id,
                              @RequestParam String venue,
                              @RequestParam(required = false) String reason,
                              @RequestParam(required = false) LocalDate day) {
        User me = CurrentUser.get(users);
        events.findById(id)
                .filter(e -> e.getOwner().getId().equals(me.getId()))
                .ifPresent(e -> {
                    String old = e.getVenue();
                    if (venue == null || venue.isBlank() || venue.equals(old)) return;
                    e.setPrevVenue(old);
                    e.setVenue(venue);
                    e.setStatus(ScheduleEvent.Status.CHANGED);
                    e.setLastChangeLabel("Location changed");
                    events.save(e);
                    log(me, e, EventChangeLog.Kind.LOCATION_CHANGED,
                            e.getTitle() + " moved from " + old + " → " + venue, reason);
                });
        return backTo(day != null ? day : LocalDate.now());
    }

    /** Reschedule to a new date/time from the action panel. */
    @PostMapping("/dashboard/activities/{id}/reschedule")
    public String reschedule(@PathVariable Long id,
                             @RequestParam(required = false) LocalDate newDate,
                             @RequestParam(required = false) LocalTime newTime,
                             @RequestParam(required = false) LocalDate day) {
        User me = CurrentUser.get(users);
        events.findById(id)
                .filter(e -> e.getOwner().getId().equals(me.getId()))
                .ifPresent(e -> {
                    LocalDate targetDate = newDate != null ? newDate : e.getDate();
                    if (targetDate == null) return;
                    e.setDate(targetDate);
                    if (newTime != null) {
                        e.setStartTime(LocalDateTime.of(targetDate, newTime));
                        if (e.getEndTime() != null) {
                            e.setEndTime(LocalDateTime.of(targetDate, e.getEndTime().toLocalTime()));
                        }
                    } else if (e.getStartTime() != null) {
                        e.setStartTime(e.getStartTime().with(targetDate));
                        if (e.getEndTime() != null) e.setEndTime(e.getEndTime().with(targetDate));
                    }
                    e.setStatus(ScheduleEvent.Status.RESCHEDULED);
                    events.save(e);
                    log(me, e, EventChangeLog.Kind.RESCHEDULED,
                            e.getTitle() + " rescheduled to " + targetDate, null);
                });
        return backTo(day != null ? day : LocalDate.now());
    }

    /** Attach a note to an activity without changing its status. */
    @PostMapping("/dashboard/activities/{id}/note")
    public String addNote(@PathVariable Long id,
                          @RequestParam String note,
                          @RequestParam(required = false) LocalDate day) {
        User me = CurrentUser.get(users);
        events.findById(id)
                .filter(e -> e.getOwner().getId().equals(me.getId()))
                .ifPresent(e -> log(me, e, EventChangeLog.Kind.NOTE,
                        "Note on " + e.getTitle(), note));
        return backTo(day != null ? day : LocalDate.now());
    }

    /** Upsert the "My Day" journal for the given date. */
    @PostMapping("/dashboard/journal")
    public String saveJournal(@RequestParam LocalDate day, @RequestParam String body) {
        User me = CurrentUser.get(users);
        DailyNote dn = dailyNotes.findByOwnerIdAndDay(me.getId(), day)
                .orElseGet(() -> {
                    DailyNote n = new DailyNote();
                    n.setOwner(me);
                    n.setDay(day);
                    return n;
                });
        dn.setBody(body == null || body.isBlank() ? null : body);
        dn.setUpdatedAt(LocalDateTime.now());
        dailyNotes.save(dn);
        return backTo(day);
    }

    private void log(User actor, ScheduleEvent e, EventChangeLog.Kind kind, String label, String detail) {
        EventChangeLog l = new EventChangeLog();
        l.setEvent(e);
        l.setActor(actor);
        l.setKind(kind);
        l.setLabel(label);
        l.setDetail(detail);
        changeLogs.save(l);
    }
}
