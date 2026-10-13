package com.ueab.sac.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.ueab.sac.model.Course;
import com.ueab.sac.model.ScheduleEvent;
import com.ueab.sac.model.User;
import com.ueab.sac.repo.CourseRepository;
import com.ueab.sac.repo.EnrollmentRepository;
import com.ueab.sac.repo.EventChangeLogRepository;
import com.ueab.sac.repo.InboxItemRepository;
import com.ueab.sac.repo.ScheduleEventRepository;
import com.ueab.sac.repo.UserRepository;
import com.ueab.sac.security.CurrentUser;

/** Schedule Core module: calendar + event management. */
@Controller
@RequestMapping("/schedule")
public class ScheduleController {

    private final UserRepository users;
    private final CourseRepository courses;
    private final EnrollmentRepository enrollments;
    private final ScheduleEventRepository events;
    private final EventChangeLogRepository changeLogs;
    private final InboxItemRepository inboxItems;

    public ScheduleController(UserRepository users, CourseRepository courses,
                              EnrollmentRepository enrollments, ScheduleEventRepository events,
                              EventChangeLogRepository changeLogs, InboxItemRepository inboxItems) {
        this.users = users;
        this.courses = courses;
        this.enrollments = enrollments;
        this.events = events;
        this.changeLogs = changeLogs;
        this.inboxItems = inboxItems;
    }

    @GetMapping
    public String calendar(@RequestParam(required = false) Integer year,
                           @RequestParam(required = false) Integer month,
                           @RequestParam(required = false, defaultValue = "all") String view,
                           Model model) {
        User me = CurrentUser.get(users);
        YearMonth ym = (year == null || month == null)
                ? YearMonth.now()
                : YearMonth.of(year, month);

        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        List<ScheduleEvent> monthEvents =
                events.findByOwnerIdAndDateBetweenOrderByDateAscStartTimeAsc(me.getId(), start, end);
        if (view.equals("academic") || view.equals("co")) {
            boolean co = view.equals("co");
            monthEvents = monthEvents.stream()
                    .filter(e -> e.isCoCurricular() == co)
                    .toList();
        }

        model.addAttribute("me", me);
        model.addAttribute("yearMonth", ym);
        model.addAttribute("view", view);
        model.addAttribute("events", monthEvents);
        model.addAttribute("myCourses",
                enrollments.findByStudentId(me.getId()).stream().map(e -> e.getCourse()).toList());
        return "schedule";
    }

    /**
     * iCalendar export of the whole schedule — imports into Google Calendar,
     * Apple Calendar, Outlook or any phone app. 21st-century kid essential.
     */
    @GetMapping("/events.ics")
    @Transactional(readOnly = true)
    public ResponseEntity<String> exportIcs(@RequestParam Long userId) throws Exception {
        // userId instead of auth context: ICS clients can't send session cookies
        List<ScheduleEvent> all =
                events.findByOwnerIdAndDateBetweenOrderByDateAscStartTimeAsc(
                        userId, LocalDate.of(2000, 1, 1), LocalDate.of(2100, 1, 1));

        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n")
          .append("VERSION:2.0\r\n")
          .append("PRODID:-//Smart Academic Companion//EN\r\n")
          .append("CALSCALE:GREGORIAN\r\n");
        java.time.format.DateTimeFormatter ics =
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
        for (ScheduleEvent e : all) {
            if (e.getDate() == null) continue;
            LocalDateTime s = e.getStartTime() != null
                    ? e.getStartTime() : e.getDate().atTime(8, 0);
            LocalDateTime en = e.getEndTime() != null
                    ? e.getEndTime() : s.plusHours(1);
            sb.append("BEGIN:VEVENT\r\n")
              .append("UID:sac-").append(e.getId()).append("@smart-academic-companion\r\n")
              .append("DTSTAMP:").append(LocalDateTime.now().format(ics)).append("\r\n")
              .append("DTSTART:").append(s.format(ics)).append("\r\n")
              .append("DTEND:").append(en.format(ics)).append("\r\n")
              .append("SUMMARY:").append(icsEscape(e.getTitle())).append("\r\n");
            if (e.getVenue() != null && !e.getVenue().isBlank()) {
                sb.append("LOCATION:").append(icsEscape(e.getVenue())).append("\r\n");
            }
            if (e.getNotes() != null && !e.getNotes().isBlank()) {
                sb.append("DESCRIPTION:").append(icsEscape(e.getNotes())).append("\r\n");
            }
            sb.append("END:VEVENT\r\n");
        }
        sb.append("END:VCALENDAR\r\n");

        return ResponseEntity.ok()
                .header("Content-Type", "text/calendar; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"my-schedule.ics\"")
                .body(sb.toString());
    }

    private static String icsEscape(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;")
                .replace(",", "\\,").replace("\n", "\\n");
    }

    @PostMapping("/events")
    public String addEvent(@RequestParam String title,
                           @RequestParam String type,
                           @RequestParam LocalDate date,
                           @RequestParam(required = false) String time,
                           @RequestParam(required = false) String venue,
                           @RequestParam(required = false) Long courseId,
                           @RequestParam(required = false) String notes) {
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
        if (time != null && !time.isBlank()) {
            try {
                e.setStartTime(LocalDateTime.of(date, java.time.LocalTime.parse(time)));
            } catch (Exception ignored) {}
        }
        e.setVenue(venue);
        e.setNotes(notes);
        if (courseId != null) {
            Course c = courses.findById(courseId).orElse(null);
            // only attach courses the student is enrolled in
            if (c != null && enrollments.existsByStudentIdAndCourseId(me.getId(), courseId)) {
                e.setCourse(c);
            }
        }
        events.save(e);
        return "redirect:/schedule";
    }

    @Transactional
    @PostMapping("/events/{id}/delete")
    public String deleteEvent(@PathVariable Long id) {
        User me = CurrentUser.get(users);
        events.findById(id)
                .filter(e -> e.getOwner().getId().equals(me.getId()))
                .ifPresent(e -> deleteCompletely(e));
        return "redirect:/schedule";
    }

    /** Removes an event plus its change history and clears inbox references. */
    private void deleteCompletely(ScheduleEvent e) {
        changeLogs.deleteByEventId(e.getId());
        inboxItems.clearEventReferences(e.getId());
        events.delete(e);
    }
}
