package com.ueab.sac.service.inbox;

import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.ueab.sac.model.Course;
import com.ueab.sac.model.Enrollment;
import com.ueab.sac.model.InboxItem;
import com.ueab.sac.model.ScheduleEvent;
import com.ueab.sac.model.User;
import com.ueab.sac.repo.CourseRepository;
import com.ueab.sac.repo.EnrollmentRepository;
import com.ueab.sac.repo.InboxItemRepository;
import com.ueab.sac.repo.ScheduleEventRepository;
import com.ueab.sac.service.parse.ExtractionService;

/**
 * AI Inbox orchestration: capture -> extract -> flag duplicates.
 * Nothing is added to the calendar here; confirmation happens in the
 * controller after the user verifies each item.
 */
@Service
public class InboxService {

    private final InboxItemRepository items;
    private final ScheduleEventRepository events;
    private final ExtractionService extraction;
    private final InboxExtractionService ai;
    private final ImageOcrService ocr;
    private final CourseRepository courses;
    private final EnrollmentRepository enrollments;

    public InboxService(InboxItemRepository items,
                        ScheduleEventRepository events,
                        ExtractionService extraction,
                        InboxExtractionService ai,
                        ImageOcrService ocr,
                        CourseRepository courses,
                        EnrollmentRepository enrollments) {
        this.items = items;
        this.events = events;
        this.extraction = extraction;
        this.ai = ai;
        this.ocr = ocr;
        this.courses = courses;
        this.enrollments = enrollments;
    }

    /** Number of items awaiting verification (for dashboard/nav badges). */
    public long pendingCount(User owner) {
        return items.countByOwnerIdAndStatus(owner.getId(), InboxItem.Status.PENDING);
    }

    /** Captures a pasted text, splitting it into per-item chunks when sensible. */
    public List<InboxItem> ingestText(User owner, String text) {
        List<InboxItem> created = new ArrayList<>();
        for (String chunk : ai.splitIntoItems(text)) {
            InboxItem item = buildItem(owner, InboxItem.SourceType.TEXT, "Pasted text", chunk);
            created.add(saveWithDuplicateCheck(item, owner));
        }
        return created;
    }

    /** Captures uploaded files (PDF/Word/Excel/PowerPoint/text/images), one item per file. */
    public List<InboxItem> ingestFiles(User owner, List<MultipartFile> files) {
        List<InboxItem> created = new ArrayList<>();
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) continue;
            String name = f.getOriginalFilename() == null ? "upload" : f.getOriginalFilename();
            if (ImageOcrService.isImageFilename(name)) {
                created.addAll(ingestImage(owner, f, name));
                continue;
            }
            try (InputStream in = f.getInputStream()) {
                String text = extraction.extract(name, in);
                for (String chunk : ai.splitIntoItems(text)) {
                    InboxItem item = buildItem(owner, InboxItem.SourceType.FILE, name, chunk);
                    created.add(saveWithDuplicateCheck(item, owner));
                }
            } catch (Exception e) {
                InboxItem failed = buildItem(owner, InboxItem.SourceType.FILE, name,
                        "(Could not read file: " + e.getMessage() + ")");
                failed.setTitle("Unreadable upload: " + name);
                failed.setWarning("The file could not be parsed. Supported: .docx, .pptx, .xlsx, .pdf, .txt, images");
                items.save(failed);
                created.add(failed);
            }
        }
        return created;
    }

    /**
     * Captures an image (photo/screenshot of a timetable, exam schedule, notice
     * or chat). Tries the vision model first; falls back to local OCR + the
     * regular text extractor when no vision LLM is configured or it fails.
     */
    private List<InboxItem> ingestImage(User owner, MultipartFile f, String name) {
        List<InboxItem> created = new ArrayList<>();
        byte[] bytes;
        try (InputStream in = f.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (Exception e) {
            InboxItem failed = buildItem(owner, InboxItem.SourceType.FILE, name,
                    "(Could not read image: " + e.getMessage() + ")");
            failed.setTitle("Unreadable image: " + name);
            failed.setWarning("The image could not be read. Supported: .png, .jpg, .jpeg, .gif, .bmp, .webp");
            items.save(failed);
            return List.of(failed);
        }

        String mime = ImageOcrService.mimeFor(name);

        // 1) vision model — weekly class timetable first (university portal screenshots)
        if (ai.llmEnabled()) {
            List<InboxExtractionService.Weekly> weekly = ai.extractWeeklyFromImage(bytes, mime);
            if (!weekly.isEmpty()) {
                for (InboxExtractionService.Weekly w : weekly) {
                    InboxItem item = new InboxItem();
                    item.setOwner(owner);
                    item.setSourceType(InboxItem.SourceType.FILE);
                    item.setSourceLabel(name);
                    item.setRawText("[Timetable row] " + w.title()
                            + " · " + String.join(",", w.days())
                            + (w.start() != null ? " · " + w.start() + (w.end() != null ? "-" + w.end() : "") : ""));
                    item.setTitle(w.title());
                    item.setEventType(w.type());
                    item.setVenue(w.venue());
                    item.setWeeklyDays(String.join(",", w.days()));
                    item.setEventTime(w.start());
                    item.setEndTime(w.end());
                    item.setWarning("Weekly class — will be added to every "
                            + String.join(" & ", w.days()) + " when confirmed.");
                    created.add(saveWithDuplicateCheck(item, owner));
                }
                return created;
            }
            // 1b) one-off dated events (exams, deadlines, notices)
            List<InboxExtractionService.Extracted> found = ai.extractFromImage(bytes, mime);
            if (!found.isEmpty()) {
                for (InboxExtractionService.Extracted ex : found) {
                    InboxItem item = itemFromExtracted(owner, InboxItem.SourceType.FILE, name,
                            "[Image] " + name, ex);
                    created.add(saveWithDuplicateCheck(item, owner));
                }
                return created;
            }
        }

        // 2) offline OCR fallback — try timetable rows first, then plain text
        String text = ocr.recognize(new java.io.ByteArrayInputStream(bytes));
        if (text != null && !text.isBlank()) {
            // 2a) timetable rows ("COSC391 DATA MINING ... tue,thu 06:30 08:00")
            List<InboxExtractionService.Weekly> rows = new ArrayList<>();
            for (String line : text.split("\\r?\\n")) {
                InboxExtractionService.Weekly w = ai.weeklyFromText(line);
                if (w != null) rows.add(w);
            }
            if (!rows.isEmpty()) {
                for (InboxExtractionService.Weekly w : rows) {
                    created.add(saveWithDuplicateCheck(
                            weeklyItem(owner, name, w, "Timetable row"), owner));
                }
                return created;
            }
            // 2a-bis) flyer/poster series (days & times scattered across lines)
            InboxExtractionService.Weekly flyer = ai.weeklyFromFlyer(text);
            if (flyer != null) {
                created.add(saveWithDuplicateCheck(
                        weeklyItem(owner, name, flyer, "Flyer / poster"), owner));
                return created;
            }
            // 2b) any other schedulable text
            List<String> chunks = ai.splitIntoItems(text);
            if (!chunks.isEmpty()) {
                for (String chunk : chunks) {
                    InboxItem item = buildItem(owner, InboxItem.SourceType.FILE, name, chunk);
                    created.add(saveWithDuplicateCheck(item, owner));
                }
                return created;
            }
        }

        // 3) nothing readable -> ask the user to fill in the fields
        InboxItem failed = new InboxItem();
        failed.setOwner(owner);
        failed.setSourceType(InboxItem.SourceType.FILE);
        failed.setSourceLabel(name);
        failed.setRawText("[Image: " + name + "]");
        failed.setTitle("Image upload: " + name);
        failed.setEventType("OTHER");
        if (!ocr.isEnabled()) {
            failed.setWarning("AI vision is not configured and OCR is disabled, so the image could not be read. "
                    + "Fill in the fields manually or paste the text instead.");
        } else {
            failed.setWarning("No readable schedule text was found in this image. "
                    + "Fill in the fields manually or try a clearer photo.");
        }
        items.save(failed);
        created.add(failed);
        return created;
    }

    /**
     * For CLASS events titled like "COSC391 Data Mining", finds the Course by
     * its code (or creates + enrolls it) and returns it; null otherwise.
     * This is how an uploaded timetable fills the Courses module automatically.
     */
    private Course resolveCourse(User owner, String title) {
        var m = java.util.regex.Pattern.compile("\\b([A-Z]{3,4}\\s?\\d{3,4})\\b").matcher(title);
        if (!m.find()) return null;
        String code = m.group(1).replaceAll("\\s+", "");
        Course c = courses.findByCodeIgnoreCase(code).orElse(null);
        if (c == null) {
            String rest = title.replaceFirst("^.*?" + java.util.regex.Pattern.quote(m.group(1)), "").trim();
            c = new Course(code, rest.isBlank() ? code : rest, "");
            c = courses.save(c);
        }
        if (!enrollments.existsByStudentIdAndCourseId(owner.getId(), c.getId())) {
            Enrollment en = new Enrollment();
            en.setStudent(owner);
            en.setCourse(c);
            enrollments.save(en);
        }
        return c;
    }

    /** Builds an inbox item from a parsed weekly row/flyer. */
    private InboxItem weeklyItem(User owner, String name, InboxExtractionService.Weekly w,
                                 String kind) {
        InboxItem item = new InboxItem();
        item.setOwner(owner);
        item.setSourceType(InboxItem.SourceType.FILE);
        item.setSourceLabel(name);
        item.setRawText("[" + kind + "] " + w.title()
                + " · " + String.join(",", w.days())
                + (w.start() != null ? " · " + w.start() + (w.end() != null ? "-" + w.end() : "") : ""));
        item.setTitle(w.title());
        item.setEventType(w.type());
        item.setVenue(w.venue());
        item.setWeeklyDays(String.join(",", w.days()));
        item.setEventTime(w.start());
        item.setEndTime(w.end());
        // first occurrence as the start date
        item.setEventDate(InboxService.nextOccurrence(item.getWeeklyDays(), LocalDate.now()));
        // if the flyer states a date range, pre-compute the number of weeks
        if (w.rangeStart() != null && w.rangeEnd() != null) {
            long weeks = java.time.temporal.ChronoUnit.WEEKS.between(w.rangeStart(), w.rangeEnd()) + 1;
            item.setWarning(kind + " (read offline from the image) — detected "
                    + weeks + " week(s) from " + w.rangeStart() + " to " + w.rangeEnd()
                    + ". Added to every " + String.join(" & ", w.days()) + " when confirmed.");
        } else {
            item.setWarning(kind + " (read offline from the image) — will be added to every "
                    + String.join(" & ", w.days()) + " when confirmed.");
        }
        return item;
    }

    /** Builds an inbox item from an already-extracted result (vision path). */
    private InboxItem itemFromExtracted(User owner, InboxItem.SourceType type, String label,
                                        String raw, InboxExtractionService.Extracted ex) {
        InboxItem item = new InboxItem();
        item.setOwner(owner);
        item.setSourceType(type);
        item.setSourceLabel(label);
        item.setRawText(raw);
        item.setTitle(ex.title());
        item.setEventType(ex.type());
        item.setEventDate(ex.date());
        item.setEventTime(ex.start());
        item.setEndTime(ex.end());
        item.setVenue(ex.venue());
        item.setWarning(ex.warning());
        return item;
    }

    /**
     * Applies (possibly edited) fields to an item and creates the calendar event.
     * Returns the saved calendar event, or null when required fields are missing.
     */
    public ScheduleEvent confirm(User owner, InboxItem item, String title, String type,
                                 java.time.LocalDate date, java.time.LocalTime start,
                                 java.time.LocalTime end, String venue, String notes) {
        return confirm(owner, item, title, type, date, start, end, venue, notes, 1);
    }

    /**
     * Applies (possibly edited) fields to an item and creates calendar event(s).
     * For weekly timetable rows (weeklyDays set), creates one event per weekday
     * per week for {@code weeks} consecutive weeks starting from {@code date}.
     */
    public ScheduleEvent confirm(User owner, InboxItem item, String title, String type,
                                 java.time.LocalDate date, java.time.LocalTime start,
                                 java.time.LocalTime end, String venue, String notes,
                                 int weeks) {
        if (date == null || title == null || title.isBlank()) return null;

        // ---- weekly timetable row: expand to one event per weekday per week ----
        if (item.getWeeklyDays() != null && !item.getWeeklyDays().isBlank() && weeks > 1) {
            return confirmWeekly(owner, item, title, type, date, start, end, venue, notes, weeks);
        }

        // decide whether the user edited the AI's interpretation BEFORE overwriting
        boolean edited = !java.util.Objects.equals(item.getEventTime(), start)
                || !java.util.Objects.equals(item.getEndTime(), end)
                || !java.util.Objects.equals(item.getVenue(), venue)
                || !java.util.Objects.equals(item.getEventType(), type)
                || !java.util.Objects.equals(item.getTitle(), title.trim());

        item.setTitle(title.trim());
        item.setEventType(type);
        item.setEventDate(date);
        item.setEventTime(start);
        item.setEndTime(end);
        item.setVenue(venue);

        ScheduleEvent e = new ScheduleEvent();
        e.setOwner(owner);
        e.setTitle(title.trim());
        try {
            e.setType(ScheduleEvent.Type.valueOf(type));
        } catch (Exception ex) {
            e.setType(ScheduleEvent.Type.OTHER);
        }
        e.setDate(date);
        if (start != null) e.setStartTime(java.time.LocalDateTime.of(date, start));
        if (end != null) e.setEndTime(java.time.LocalDateTime.of(date, end));
        e.setVenue(venue);
        e.setCourse(resolveCourse(owner, title)); // auto-fill Courses from CLASS rows
        String body = notes == null ? "" : notes.trim();
        String src = "Imported from AI Inbox (" + item.getSourceLabel() + ")";
        e.setNotes(body.isBlank() ? src : src + "\n" + body);
        e.setAutoSynced(true);
        e = events.save(e);

        item.setStatus(edited ? InboxItem.Status.EDITED : InboxItem.Status.CONFIRMED);
        item.setCreatedEvent(e);
        item.setWarning(null);
        items.save(item);
        return e;
    }

    /** Creates one dated event per weekday per week for a weekly timetable row. */
    private ScheduleEvent confirmWeekly(User owner, InboxItem item, String title, String type,
                                        LocalDate firstDate, LocalTime start, LocalTime end,
                                        String venue, String notes, int weeks) {
        List<DayOfWeek> days = parseDays(item.getWeeklyDays());
        if (days.isEmpty()) {
            item.setWeeklyDays(null);
            return confirm(owner, item, title, type, firstDate, start, end, venue, notes, 1);
        }

        // find the first occurrence date on/after firstDate
        LocalDate cursor = firstDate;
        while (!days.contains(cursor.getDayOfWeek())) {
            cursor = cursor.plusDays(1);
        }

        ScheduleEvent first = null;
        int made = 0;
        LocalDate d = cursor;
        LocalDate last = firstDate.plusWeeks(weeks - 1);
        while (!d.isAfter(last)) {
            if (days.contains(d.getDayOfWeek())) {
                ScheduleEvent e = new ScheduleEvent();
                e.setOwner(owner);
                e.setTitle(title.trim());
                try {
                    e.setType(ScheduleEvent.Type.valueOf(type));
                } catch (Exception ex) {
                    e.setType(ScheduleEvent.Type.OTHER);
                }
                e.setDate(d);
                if (start != null) e.setStartTime(java.time.LocalDateTime.of(d, start));
                if (end != null) e.setEndTime(java.time.LocalDateTime.of(d, end));
                e.setVenue(venue);
                e.setCourse(resolveCourse(owner, title)); // auto-fill Courses from CLASS rows
                String body = notes == null ? "" : notes.trim();
                String src = "Imported from AI Inbox (" + item.getSourceLabel() + ") — weekly class";
                e.setNotes(body.isBlank() ? src : src + "\n" + body);
                e.setAutoSynced(true);
                e = events.save(e);
                if (first == null) first = e;
                made++;
            }
            d = d.plusDays(1);
        }

        item.setTitle(title.trim());
        item.setEventType(type);
        item.setEventDate(cursor);
        item.setEventTime(start);
        item.setEndTime(end);
        item.setVenue(venue);
        item.setStatus(made > 1 ? InboxItem.Status.EDITED : InboxItem.Status.CONFIRMED);
        item.setCreatedEvent(first);
        item.setWarning(null);
        items.save(item);
        return first;
    }

    /** Parses "mon,tue" into DayOfWeek values (ignores unknown codes). */
    public static List<DayOfWeek> parseDays(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<DayOfWeek> out = new ArrayList<>();
        for (String part : csv.toLowerCase(Locale.ROOT).split(",")) {
            switch (part.trim()) {
                case "mon" -> out.add(DayOfWeek.MONDAY);
                case "tue" -> out.add(DayOfWeek.TUESDAY);
                case "wed" -> out.add(DayOfWeek.WEDNESDAY);
                case "thu" -> out.add(DayOfWeek.THURSDAY);
                case "fri" -> out.add(DayOfWeek.FRIDAY);
                case "sat" -> out.add(DayOfWeek.SATURDAY);
                case "sun" -> out.add(DayOfWeek.SUNDAY);
                default -> { }
            }
        }
        return out;
    }

    /** First date on/after {@code from} matching any of the weekly days (null-safe). */
    public static LocalDate nextOccurrence(String weeklyDays, LocalDate from) {
        List<DayOfWeek> days = parseDays(weeklyDays);
        if (days.isEmpty() || from == null) return null;
        LocalDate d = from;
        for (int i = 0; i < 7; i++) {
            if (days.contains(d.getDayOfWeek())) return d;
            d = d.plusDays(1);
        }
        return from;
    }

    /** Weeks implied by a stored warning like "detected 5 week(s) ...", or 0. */
    public static int suggestedWeeks(String warning) {
        if (warning == null) return 0;
        var m = java.util.regex.Pattern.compile("detected (\\d+) week").matcher(warning);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** Removes the item entirely — nothing is kept in history. */
    public void reject(InboxItem item) {
        items.delete(item);
    }

    /** Permanently deletes a processed (non-pending) inbox item from history. */
    public void deleteProcessed(User owner, Long itemId) {
        items.findById(itemId)
                .filter(i -> i.getOwner().getId().equals(owner.getId()))
                .filter(i -> i.getStatus() != InboxItem.Status.PENDING)
                .ifPresent(items::delete);
    }

    /** Marks the item as kept-as-existing (duplicate confirmed, nothing added). */
    public void keepExisting(InboxItem item) {
        item.setStatus(InboxItem.Status.DUPLICATE);
        item.setWarning("Duplicate — existing calendar event kept.");
        items.save(item);
    }

    // ------------------------------------------------------------------

    private InboxItem buildItem(User owner, InboxItem.SourceType type, String label, String raw) {
        InboxItem item = new InboxItem();
        item.setOwner(owner);
        item.setSourceType(type);
        item.setSourceLabel(label);
        item.setRawText(raw);

        InboxExtractionService.Extracted ex = ai.extract(raw);
        if (ex == null) {
            item.setTitle(raw.length() > 60 ? raw.substring(0, 57) + "..." : raw);
            item.setEventType("OTHER");
            item.setWarning("AI could not find a schedulable event in this text. Please fill in the fields.");
        } else {
            item.setTitle(ex.title());
            item.setEventType(ex.type());
            item.setEventDate(ex.date());
            item.setEventTime(ex.start());
            item.setEndTime(ex.end());
            item.setVenue(ex.venue());
            item.setWarning(ex.warning());
        }
        return item;
    }

    private InboxItem saveWithDuplicateCheck(InboxItem item, User owner) {
        // 1) compare against pending items in the current queue
        for (InboxItem other : items.findByOwnerIdAndStatusOrderByCreatedAtAsc(
                owner.getId(), InboxItem.Status.PENDING)) {
            if (other.getId() != null && other.getId().equals(item.getId())) continue;
            if (other.getFingerprint() != null
                    && other.getFingerprint().equals(ai.fingerprint(
                            item.getTitle(), item.getEventDate(), item.getEventTime()))) {
                item.setDuplicateSuspect(true);
                item.setDuplicateOf(other.getCreatedEvent());
                item.setWarning("Possible duplicate of an item already in your inbox.");
                break;
            }
        }
        // 2) compare against existing calendar events
        if (!item.isDuplicateSuspect() && item.getEventDate() != null) {
            for (ScheduleEvent e : events.findByOwnerIdAndDateBetweenOrderByDateAscStartTimeAsc(
                    owner.getId(), item.getEventDate(), item.getEventDate())) {
                if (ai.looksLikeDuplicate(e, item.getTitle(), item.getEventDate(), item.getEventTime())) {
                    item.setDuplicateSuspect(true);
                    item.setDuplicateOf(e);
                    item.setWarning("Possible duplicate: \"" + e.getTitle() + "\" already exists on "
                            + item.getEventDate() + ".");
                    break;
                }
            }
        }
        item.setFingerprint(ai.fingerprint(item.getTitle(), item.getEventDate(), item.getEventTime()));
        return items.save(item);
    }

}
