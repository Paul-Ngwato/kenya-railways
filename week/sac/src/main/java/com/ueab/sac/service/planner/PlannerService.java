package com.ueab.sac.service.planner;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ueab.sac.model.PlannerProject;
import com.ueab.sac.model.PlannerTask;
import com.ueab.sac.model.ScheduleEvent;
import com.ueab.sac.model.User;
import com.ueab.sac.repo.PlannerProjectRepository;
import com.ueab.sac.repo.PlannerTaskRepository;
import com.ueab.sac.repo.ScheduleEventRepository;
import com.ueab.sac.service.LlmClient;

/**
 * The Planner brain. Turns a goal like "website due Oct 10" into ordered,
 * dependency-aware work sessions that fit around the user's real calendar.
 * Works offline (template plans + greedy slot search) and better with an LLM.
 */
@Service
public class PlannerService {

    /** Working-day window used when looking for free study slots. */
    private static final LocalTime DAY_START = LocalTime.of(7, 0);
    private static final LocalTime DAY_END = LocalTime.of(22, 0);
    private static final int MIN_SLOT_MINUTES = 45;

    private final PlannerProjectRepository projects;
    private final PlannerTaskRepository tasks;
    private final ScheduleEventRepository events;
    private final LlmClient llm;
    private final ObjectMapper mapper = new ObjectMapper();

    public PlannerService(PlannerProjectRepository projects,
                          PlannerTaskRepository tasks,
                          ScheduleEventRepository events,
                          LlmClient llm) {
        this.projects = projects;
        this.tasks = tasks;
        this.events = events;
        this.llm = llm;
    }

    // ------------------------------------------------------------------
    // Plan generation
    // ------------------------------------------------------------------

    /** One generated plan line. */
    public record PlanLine(String title, int minutes) {}

    /**
     * Generates an ordered task list for a goal. LLM first, offline template
     * as fallback. Dependency chain is enforced by list order.
     */
    public List<PlanLine> generatePlan(String goal, String details, LocalDate deadline) {
        if (llm.isEnabled()) {
            List<PlanLine> viaLlm = viaLlm(goal, details, deadline);
            if (viaLlm != null && !viaLlm.isEmpty()) return viaLlm;
        }
        return offlinePlan(goal, deadline);
    }

    private List<PlanLine> viaLlm(String goal, String details, LocalDate deadline) {
        String prompt = "Break this student project into an ordered list of work tasks "
                + "(each max 3 hours). Respect realistic dependencies: research before design, "
                + "build before testing, documentation and review last. "
                + "Reply ONLY with JSON array: "
                + "[{\"title\":\"...\",\"minutes\":90}]. Today is " + LocalDate.now()
                + (deadline != null ? ", deadline is " + deadline : "") + ".";
        String body = "Goal: " + goal + (details == null || details.isBlank() ? "" : "\nDetails: " + details);
        String json = llm.chat(prompt, body);
        if (json == null || json.isBlank()) return null;
        try {
            String cleaned = json.replaceAll("(?s)^.*?\\[", "[").replaceAll("(?s)\\].*$", "]")
                    + (json.contains("]") ? "" : "]");
            var arr = mapper.readTree(cleaned);
            List<PlanLine> out = new ArrayList<>();
            for (var n : arr) {
                String t = n.path("title").asText(null);
                int m = n.path("minutes").asInt(90);
                if (t != null && !t.isBlank()) out.add(new PlanLine(t.trim(), Math.max(30, Math.min(240, m))));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Offline fallback plan by goal shape. */
    private List<PlanLine> offlinePlan(String goal, LocalDate deadline) {
        String g = goal.toLowerCase();
        List<PlanLine> plan = new ArrayList<>();
        if (g.contains("paper") || g.contains("essay") || g.contains("report") || g.contains("research")) {
            for (Object[] row : new Object[][]{
                    {"Understand requirements", 60}, {"Research & sources", 120},
                    {"Create outline", 60}, {"Write first draft", 180},
                    {"Revise & edit", 120}, {"Format references", 60},
                    {"Final review", 60}, {"Submit", 30}}) {
                plan.add(new PlanLine((String) row[0], (int) row[1]));
            }
        } else if (g.contains("present") || g.contains("slides")) {
            for (Object[] row : new Object[][]{
                    {"Understand the topic", 60}, {"Research content", 120},
                    {"Draft slides", 120}, {"Create visuals", 90},
                    {"Rehearse", 90}, {"Final polish", 60}}) {
                plan.add(new PlanLine((String) row[0], (int) row[1]));
            }
        } else if (g.contains("study") || g.contains("exam") || g.contains("revision")) {
            for (Object[] row : new Object[][]{
                    {"Collect materials", 60}, {"Topic 1 review", 90},
                    {"Topic 2 review", 90}, {"Practice questions", 120},
                    {"Weak areas review", 90}, {"Final review", 60}}) {
                plan.add(new PlanLine((String) row[0], (int) row[1]));
            }
        } else { // generic build/software project
            for (Object[] row : new Object[][]{
                    {"Understand requirements", 60}, {"Research", 90},
                    {"Define features / design", 90}, {"Setup & scaffolding", 60},
                    {"Build core part 1", 180}, {"Build core part 2", 180},
                    {"Integration", 90}, {"Testing & fixes", 120},
                    {"Documentation", 60}, {"Final review & submit", 60}}) {
                plan.add(new PlanLine((String) row[0], (int) row[1]));
            }
        }
        return plan;
    }

    // ------------------------------------------------------------------
    // Scheduling
    // ------------------------------------------------------------------

    /**
     * Creates the project with generated tasks, distributing sessions across
     * free calendar slots between now and the deadline.
     */
    @Transactional
    public PlannerProject createProject(User owner, String name, String details, LocalDate deadline,
                                        boolean autoSchedule) {
        PlannerProject p = new PlannerProject();
        p.setOwner(owner);
        p.setName(name);
        p.setDetails(details);
        p.setDeadline(deadline != null ? deadline : LocalDate.now().plusWeeks(2));
        p = projects.save(p);

        List<PlanLine> plan = generatePlan(name, details, p.getDeadline());
        PlannerTask prev = null;
        int idx = 0;
        for (PlanLine line : plan) {
            PlannerTask t = new PlannerTask();
            t.setProject(p);
            t.setTitle(line.title());
            t.setDurationMinutes(line.minutes());
            t.setOrderIndex(idx++);
            t.setDependsOn(prev);
            t = tasks.save(t);
            prev = t;
        }

        if (autoSchedule) {
            scheduleProject(p);
        }
        return p;
    }

    /**
     * Greedy calendar-aware scheduler: walks days from tomorrow (or today if
     * deadline pressure) to the deadline, skips busy windows from the user's
     * ScheduleEvents, and drops each task into the first free slot of its
     * required size. Enforces dependency order: a task is only placed on or
     * after the day its predecessor was placed.
     */
    @Transactional
    public ScheduleStats scheduleProject(PlannerProject p) {
        LocalDate start = LocalDate.now();
        LocalDate end = p.getDeadline();
        if (end.isBefore(start)) end = start;

        List<PlannerTask> ordered = tasks.findByProjectIdOrderByOrderIndexAsc(p.getId());
        Map<LocalDate, List<ScheduleEvent>> busy = new java.util.HashMap<>();
        List<ScheduleEvent> windowEvents =
                events.findByOwnerIdAndDateBetweenOrderByDateAscStartTimeAsc(p.getOwner().getId(), start, end);
        for (ScheduleEvent e : windowEvents) {
            if (e.getDate() != null
                    && e.getEffectiveStatus() != ScheduleEvent.Status.CANCELLED
                    && e.getEffectiveStatus() != ScheduleEvent.Status.DID_NOT_HAPPEN) {
                busy.computeIfAbsent(e.getDate(), k -> new ArrayList<>()).add(e);
            }
        }

        ScheduleStats stats = new ScheduleStats();
        LocalDate cursor = start;

        for (PlannerTask t : ordered) {
            if (t.getStatus() == PlannerTask.Status.DONE
                    || t.getStatus() == PlannerTask.Status.SKIPPED) {
                continue;
            }
            cursor = enforceDependencyDay(cursor, t);
            LocalDate day = cursor;
            boolean placed = false;
            while (!day.isAfter(end)) {
                List<ScheduleEvent> dayBusy = busy.getOrDefault(day, List.of());
                LocalTime[] slot = findFreeSlot(dayBusy, t.getDurationMinutes());
                if (slot != null) {
                    applySlot(t, day, slot, stats);
                    busy.computeIfAbsent(day, k -> new ArrayList<>())
                            .add(blockEvent(t, day, slot));
                    placed = true;
                    break;
                }
                day = day.plusDays(1);
                if (day.isAfter(end)) break;
            }
            if (!placed) {
                stats.unscheduled++;
                t.setPlannedDate(null);
                t.setPlannedStart(null);
                t.setPlannedEnd(null);
                t.setStatus(PlannerTask.Status.PLANNED);
                tasks.save(t);
            }
            cursor = day;
        }
        return stats;
    }

    /** Ensures a task is never scheduled before the day its dependency runs. */
    private LocalDate enforceDependencyDay(LocalDate cursor, PlannerTask t) {
        if (t.getDependsOn() != null && t.getDependsOn().getPlannedDate() != null) {
            LocalDate depDay = t.getDependsOn().getPlannedDate();
            if (cursor.isBefore(depDay)) return depDay;
        }
        return cursor;
    }

    private void applySlot(PlannerTask t, LocalDate day, LocalTime[] slot, ScheduleStats stats) {
        t.setPlannedDate(day);
        t.setPlannedStart(slot[0]);
        t.setPlannedEnd(slot[1]);
        t.setStatus(PlannerTask.Status.SCHEDULED);
        tasks.save(t);
        stats.scheduled++;
        stats.totalMinutes += t.getDurationMinutes();
    }

    private ScheduleEvent blockEvent(PlannerTask t, LocalDate day, LocalTime[] slot) {
        ScheduleEvent b = new ScheduleEvent();
        b.setOwner(t.getProject().getOwner());
        b.setCourse(null);
        b.setTitle("🧠 " + t.getProject().getName() + " — " + t.getTitle());
        b.setType(ScheduleEvent.Type.WORK);
        b.setDate(day);
        b.setStartTime(LocalDateTime.of(day, slot[0]));
        b.setEndTime(LocalDateTime.of(day, slot[1]));
        b.setNotes("Planner session for project \"" + t.getProject().getName() + "\" (task #"
                + t.getId() + "). Auto-scheduled around your existing commitments.");
        b.setAutoSynced(true);
        // in-memory only: lets later iterations of scheduleProject see this slot as busy
        return b;
    }

    /** Finds the first free slot of the required length in a day's schedule. */
    private LocalTime[] findFreeSlot(List<ScheduleEvent> busy, int durationMinutes) {
        List<LocalTime[]> busyRanges = new ArrayList<>();
        for (ScheduleEvent e : busy) {
            if (e.getStartTime() != null && e.getEndTime() != null) {
                busyRanges.add(new LocalTime[]{e.getStartTime().toLocalTime(), e.getEndTime().toLocalTime()});
            } else if (e.getStartTime() != null) {
                LocalTime s = e.getStartTime().toLocalTime();
                busyRanges.add(new LocalTime[]{s, s.plusMinutes(90)});
            }
        }
        busyRanges.sort(Comparator.comparing(r -> r[0]));

        LocalTime cursor = DAY_START;
        for (LocalTime[] r : busyRanges) {
            if (r[0].isAfter(cursor) && java.time.Duration.between(cursor, r[0]).toMinutes() >= durationMinutes) {
                return new LocalTime[]{cursor, cursor.plusMinutes(durationMinutes)};
            }
            if (r[1].isAfter(cursor)) cursor = r[1];
            if (!cursor.isBefore(DAY_END)) return null;
        }
        if (java.time.Duration.between(cursor, DAY_END).toMinutes() >= Math.max(durationMinutes, MIN_SLOT_MINUTES)) {
            return new LocalTime[]{cursor, cursor.plusMinutes(durationMinutes)};
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Rescheduling
    // ------------------------------------------------------------------

    /** Stats returned by a (re)scheduling run. */
    public static class ScheduleStats {
        public int scheduled;
        public int unscheduled;
        public int totalMinutes;

        public String summary() {
            String h = (totalMinutes / 60) + "h" + (totalMinutes % 60 > 0 ? (totalMinutes % 60) + "m" : "");
            return scheduled + " sessions scheduled (" + h + " of work)"
                    + (unscheduled > 0 ? ", " + unscheduled + " could not fit before the deadline" : "");
        }
    }

    /**
     * Auto-reschedule: called after calendar changes. Un-schedules future
     * sessions of active projects and re-fits them into the new calendar.
     * Returns null when nothing needed to move.
     */
    @Transactional
    public ScheduleStats autoReschedule(User owner) {
        List<PlannerProject> active = projects.findWithTasksByOwnerId(owner.getId()).stream()
                .filter(p -> p.getStatus() == PlannerProject.Status.ACTIVE)
                .toList();
        boolean anyScheduled = active.stream().anyMatch(p ->
                tasks.findByProjectIdOrderByOrderIndexAsc(p.getId()).stream()
                        .anyMatch(t -> t.getStatus() == PlannerTask.Status.SCHEDULED
                                && t.getPlannedDate() != null
                                && !t.getPlannedDate().isBefore(LocalDate.now())));
        if (!anyScheduled) return null;

        int before = 0;
        for (PlannerProject p : active) {
            for (PlannerTask t : tasks.findByProjectIdOrderByOrderIndexAsc(p.getId())) {
                if (t.getStatus() == PlannerTask.Status.SCHEDULED
                        && t.getPlannedDate() != null
                        && !t.getPlannedDate().isBefore(LocalDate.now())) {
                    before++;
                }
            }
        }
        if (before == 0) return null;

        ScheduleStats stats = new ScheduleStats();
        for (PlannerProject p : active) {
            stats.scheduled += rescheduleProject(p).scheduled;
        }
        return stats;
    }

    /** Clears future scheduled sessions and re-runs the scheduler. */
    @Transactional
    public ScheduleStats rescheduleProject(PlannerProject p) {
        List<PlannerTask> all = tasks.findByProjectIdOrderByOrderIndexAsc(p.getId());
        for (PlannerTask t : all) {
            if (t.getStatus() == PlannerTask.Status.SCHEDULED
                    && t.getPlannedDate() != null
                    && !t.getPlannedDate().isBefore(LocalDate.now())) {
                t.setStatus(PlannerTask.Status.PLANNED);
                t.setPlannedDate(null);
                t.setPlannedStart(null);
                t.setPlannedEnd(null);
                tasks.save(t);
            }
        }
        return scheduleProject(p);
    }

    // ------------------------------------------------------------------
    // Task actions
    // ------------------------------------------------------------------

    @Transactional
    public void acceptPlan(PlannerProject p) {
        for (PlannerTask t : tasks.findByProjectIdOrderByOrderIndexAsc(p.getId())) {
            if (t.getStatus() == PlannerTask.Status.PLANNED && t.getPlannedDate() != null) {
                createBlockEvent(p, t);
                t.setStatus(PlannerTask.Status.SCHEDULED);
                tasks.save(t);
            }
        }
    }

    private void createBlockEvent(PlannerProject p, PlannerTask t) {
        if (t.getPlannedDate() == null || t.getPlannedStart() == null) return;
        ScheduleEvent b = new ScheduleEvent();
        b.setOwner(p.getOwner());
        b.setTitle("🧠 " + p.getName() + " — " + t.getTitle());
        b.setType(ScheduleEvent.Type.WORK);
        b.setDate(t.getPlannedDate());
        b.setStartTime(LocalDateTime.of(t.getPlannedDate(), t.getPlannedStart()));
        b.setEndTime(t.getPlannedEnd() != null
                ? LocalDateTime.of(t.getPlannedDate(), t.getPlannedEnd())
                : LocalDateTime.of(t.getPlannedDate(), t.getPlannedStart().plusMinutes(t.getDurationMinutes())));
        b.setNotes("Planner session for project \"" + p.getName() + "\".");
        b.setAutoSynced(true);
        events.save(b);
        t.setEvent(b);
    }

    @Transactional
    public void completeTask(PlannerTask t) {
        t.setStatus(PlannerTask.Status.DONE);
        t.setCompletedAt(LocalDateTime.now());
        tasks.save(t);
        if (t.getEvent() != null) {
            t.getEvent().setStatus(ScheduleEvent.Status.COMPLETED);
            events.save(t.getEvent());
        }
    }

    @Transactional
    public void skipTask(PlannerTask t) {
        t.setStatus(PlannerTask.Status.SKIPPED);
        tasks.save(t);
        if (t.getEvent() != null) {
            t.getEvent().setStatus(ScheduleEvent.Status.CANCELLED);
            events.save(t.getEvent());
        }
    }

    @Transactional
    public void reopenTask(PlannerTask t) {
        t.setStatus(PlannerTask.Status.PLANNED);
        t.setCompletedAt(null);
        t.setPlannedDate(null);
        t.setPlannedStart(null);
        t.setPlannedEnd(null);
        tasks.save(t);
        if (t.getEvent() != null) {
            events.delete(t.getEvent());
            t.setEvent(null);
        }
    }

    @Transactional
    public void deleteProject(PlannerProject p) {
        for (PlannerTask t : tasks.findByProjectIdOrderByOrderIndexAsc(p.getId())) {
            if (t.getEvent() != null) events.delete(t.getEvent());
        }
        projects.delete(p);
    }
}
