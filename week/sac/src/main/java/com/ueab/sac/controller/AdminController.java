package com.ueab.sac.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.ueab.sac.model.Course;
import com.ueab.sac.model.Enrollment;
import com.ueab.sac.model.Role;
import com.ueab.sac.model.ScheduleEvent;
import com.ueab.sac.model.User;
import com.ueab.sac.repo.CourseRepository;
import com.ueab.sac.repo.EnrollmentRepository;
import com.ueab.sac.repo.EventChangeLogRepository;
import com.ueab.sac.repo.InboxItemRepository;
import com.ueab.sac.repo.ScheduleEventRepository;
import com.ueab.sac.repo.UserRepository;
import com.ueab.sac.security.CurrentUser;

/**
 * Admin Panel — only reachable by ROLE_ADMIN.
 * Student-wide features: user management, course catalog management,
 * global statistics and a system-wide event monitor.
 */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private final UserRepository users;
    private final CourseRepository courses;
    private final EnrollmentRepository enrollments;
    private final ScheduleEventRepository events;
    private final EventChangeLogRepository changeLogs;
    private final InboxItemRepository inboxItems;
    private final org.springframework.security.crypto.password.PasswordEncoder encoder;

    public AdminController(UserRepository users, CourseRepository courses,
                           EnrollmentRepository enrollments, ScheduleEventRepository events,
                           EventChangeLogRepository changeLogs, InboxItemRepository inboxItems,
                           org.springframework.security.crypto.password.PasswordEncoder encoder) {
        this.users = users;
        this.courses = courses;
        this.enrollments = enrollments;
        this.events = events;
        this.changeLogs = changeLogs;
        this.inboxItems = inboxItems;
        this.encoder = encoder;
    }

    /* ---------------- overview ---------------- */

    @GetMapping
    public String overview(Model model) {
        User me = CurrentUser.get(users);

        Map<String, Long> stats = new HashMap<>();
        stats.put("users", users.count());
        stats.put("students", users.findAll().stream().filter(u -> u.getRole() == Role.STUDENT).count());
        stats.put("admins", users.findAll().stream().filter(u -> u.getRole() == Role.ADMIN).count());
        stats.put("courses", courses.count());
        stats.put("enrollments", enrollments.count());
        stats.put("events", events.count());
        stats.put("upcoming", events.findAll().stream()
                .filter(e -> e.getDate() != null && !e.getDate().isBefore(LocalDate.now())).count());

        // 8 most recent events across the whole system (admin monitor)
        List<ScheduleEvent> recentEvents = events.findAll().stream()
                .sorted(java.util.Comparator.comparing(ScheduleEvent::getCreatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .limit(8)
                .toList();

        model.addAttribute("me", me);
        model.addAttribute("stats", stats);
        model.addAttribute("recentEvents", recentEvents);
        model.addAttribute("recentUsers", users.findAll().stream()
                .sorted(java.util.Comparator.comparing(User::getId).reversed())
                .limit(5)
                .toList());
        model.addAttribute("courses", courses.findAll());
        Map<Long, Integer> courseCounts = new HashMap<>();
        for (Course c : courses.findAll()) {
            courseCounts.put(c.getId(), enrollments.findByCourseId(c.getId()).size());
        }
        model.addAttribute("courseCounts", courseCounts);
        return "admin";
    }

    /* ---------------- user management ---------------- */

    @GetMapping("/users")
    public String users(Model model, @RequestParam(required = false) String q) {
        User me = CurrentUser.get(users);
        var all = users.findAll().stream()
                .sorted(java.util.Comparator.comparing(User::getId))
                .toList();
        if (q != null && !q.isBlank()) {
            String needle = q.toLowerCase();
            all = all.stream().filter(u ->
                    u.getUsername().toLowerCase().contains(needle)
                    || u.getFullName().toLowerCase().contains(needle)
                    || u.getEmail().toLowerCase().contains(needle)).toList();
        }

        // map userId -> number of enrolled courses (avoid lazy issues in view)
        Map<Long, Integer> courseCounts = new HashMap<>();
        for (User u : all) {
            courseCounts.put(u.getId(), enrollments.findByStudentId(u.getId()).size());
        }

        model.addAttribute("me", me);
        model.addAttribute("users", all);
        model.addAttribute("courseCounts", courseCounts);
        model.addAttribute("q", q == null ? "" : q);
        return "admin-users";
    }

    @PostMapping("/users/{id}/role")
    public String changeRole(@PathVariable Long id, @RequestParam Role role) {
        users.findById(id).ifPresent(u -> {
            u.setRole(role);
            users.save(u);
        });
        return "redirect:/admin/users";
    }

    @PostMapping("/users/{id}/toggle-active")
    public String toggleActive(@PathVariable Long id) {
        User me = CurrentUser.get(users);
        users.findById(id).ifPresent(u -> {
            if (!u.getId().equals(me.getId())) {   // admin cannot disable self
                u.setActive(!u.isActive());
                users.save(u);
            }
        });
        return "redirect:/admin/users";
    }

    @PostMapping("/users/{id}/reset-password")
    public String resetPassword(@PathVariable Long id, @RequestParam String password) {
        if (password != null && password.length() >= 4) {
            users.findById(id).ifPresent(u -> {
                u.setPasswordHash(encoder.encode(password));
                users.save(u);
            });
        }
        return "redirect:/admin/users";
    }

    /* ---------------- course catalog management ---------------- */

    @PostMapping("/courses")
    public String addCourse(@RequestParam String code,
                            @RequestParam String title,
                            @RequestParam(required = false) String lecturer) {
        if (code != null && !code.isBlank() && title != null && !title.isBlank()
                && !courses.existsByCodeIgnoreCase(code.trim())) {
            courses.save(new Course(code.trim(), title.trim(), lecturer));
        }
        return "redirect:/admin#catalog";
    }

    @PostMapping("/courses/{id}/delete")
    public String deleteCourse(@PathVariable Long id) {
        courses.findById(id).ifPresent(c -> {
            if (enrollments.findByCourseId(id).isEmpty()) {
                courses.delete(c);
            }
        });
        return "redirect:/admin#catalog";
    }

    /* ---------------- global schedule board ---------------- */

    @PostMapping("/events")
    public String createEvent(@RequestParam String title,
                              @RequestParam String type,
                              @RequestParam LocalDate date,
                              @RequestParam(required = false) String time,
                              @RequestParam(required = false) String venue,
                              @RequestParam(required = false) Long courseId,
                              @RequestParam(required = false) String notes,
                              @RequestParam(name = "broadcast", required = false) String broadcast) {
        User me = CurrentUser.get(users);
        LocalDateTime start = null;
        if (time != null && !time.isBlank()) {
            try { start = LocalDateTime.of(date, java.time.LocalTime.parse(time)); } catch (Exception ignored) {}
        }

        if (broadcast != null) {           // push to every enrolled student of the course
            Course course = courseId == null ? null : courses.findById(courseId).orElse(null);
            if (course != null) {
                for (Enrollment en : enrollments.findByCourseId(course.getId())) {
                    events.save(buildEvent(me, en.getStudent(), course, title, type, date, start, venue, notes, false));
                }
            }
        } else {                            // admin's own calendar
            events.save(buildEvent(me, me, null, title, type, date, start, venue, notes, false));
        }
        return "redirect:/admin#events";
    }

    @Transactional
    @PostMapping("/events/{id}/delete")
    public String deleteEvent(@PathVariable Long id) {
        events.findById(id).ifPresent(e -> {             // admin may remove any event
            changeLogs.deleteByEventId(e.getId());
            inboxItems.clearEventReferences(e.getId());
            events.delete(e);
        });
        return "redirect:/admin#events";
    }

    private ScheduleEvent buildEvent(User creator, User owner, Course course, String title,
                                     String type, LocalDate date, LocalDateTime start,
                                     String venue, String notes, boolean auto) {
        ScheduleEvent e = new ScheduleEvent();
        e.setOwner(owner);
        e.setCourse(course);
        e.setTitle(title);
        try { e.setType(ScheduleEvent.Type.valueOf(type.toUpperCase())); } catch (Exception ex) { e.setType(ScheduleEvent.Type.OTHER); }
        e.setDate(date);
        e.setStartTime(start);
        e.setVenue(venue);
        e.setNotes(notes != null && !notes.isBlank()
                ? notes + " (added by " + creator.getFullName() + ")" : null);
        e.setAutoSynced(auto);
        return e;
    }
}
