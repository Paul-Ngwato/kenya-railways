package com.ueab.sac;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.ueab.sac.model.InboxItem;
import com.ueab.sac.model.ScheduleEvent;
import com.ueab.sac.model.User;
import com.ueab.sac.repo.CourseRepository;
import com.ueab.sac.repo.EnrollmentRepository;
import com.ueab.sac.repo.InboxItemRepository;
import com.ueab.sac.repo.ScheduleEventRepository;
import com.ueab.sac.service.LlmClient;
import com.ueab.sac.service.inbox.InboxExtractionService;
import com.ueab.sac.service.inbox.InboxService;
import com.ueab.sac.service.inbox.ImageOcrService;
import com.ueab.sac.service.parse.ExtractionService;

class InboxServiceImageTest {

    private InboxItemRepository items;
    private ScheduleEventRepository events;
    private CourseRepository courses;
    private EnrollmentRepository enrollments;
    private InboxService inbox;
    private User user;

    @BeforeEach
    void setup() {
        items = mock(InboxItemRepository.class);
        events = mock(ScheduleEventRepository.class);
        when(items.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(events.save(any())).thenAnswer(inv -> {
            ScheduleEvent e = inv.getArgument(0);
            if (e.getId() == null) e.setId((long) (e.hashCode() & 0xffff));
            return e;
        });
        when(items.findByOwnerIdAndStatusOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of());
        when(events.findByOwnerIdAndDateBetweenOrderByDateAscStartTimeAsc(any(), any(), any()))
                .thenReturn(List.of());
        courses = mock(CourseRepository.class);
        enrollments = mock(EnrollmentRepository.class);
        when(courses.findByCodeIgnoreCase(anyString())).thenReturn(java.util.Optional.empty());
        when(courses.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(enrollments.existsByStudentIdAndCourseId(any(), any())).thenReturn(false);
        user = new User();
        user.setId(1L);

        // Weekly timetable response (like an icampus portal screenshot)
        LlmClient llm = mock(LlmClient.class);
        when(llm.isEnabled()).thenReturn(true);
        when(llm.chatWithImage(anyString(), anyString(), any(), anyString()))
                .thenReturn("{\"classes\":[{\"title\":\"COSC391 Data Mining\","
                        + "\"days\":[\"tue\",\"thu\"],\"start\":\"06:30\",\"end\":\"08:00\","
                        + "\"venue\":\"L105A (Computer Lab A) - Library\","
                        + "\"lecturer\":\"DR. MONY VICTOR OTIENO\"}]}");

        inbox = new InboxService(items, events, mock(ExtractionService.class),
                new InboxExtractionService(llm), new ImageOcrService(), courses, enrollments);
    }

    @Test
    void timetableImageCreatesWeeklyInboxItem() {
        MockMultipartFile png = new MockMultipartFile(
                "files", "timetable.png", "image/png", new byte[]{1, 2, 3});

        List<InboxItem> created = inbox.ingestFiles(user, List.of(png));

        assertEquals(1, created.size());
        InboxItem item = created.get(0);
        assertEquals(InboxItem.Status.PENDING, item.getStatus());
        assertEquals("COSC391 Data Mining", item.getTitle());
        assertEquals("CLASS", item.getEventType());
        assertEquals("tue,thu", item.getWeeklyDays());
        assertEquals(LocalTime.of(6, 30), item.getEventTime());
        assertEquals(LocalTime.of(8, 0), item.getEndTime());
        assertEquals("L105A (Computer Lab A) - Library", item.getVenue());
        assertNotNull(item.getWarning());
    }

    @Test
    void confirmingWeeklyItemExpandsToAllDaysForChosenWeeks() {
        MockMultipartFile png = new MockMultipartFile(
                "files", "timetable.png", "image/png", new byte[]{1, 2, 3});
        InboxItem item = inbox.ingestFiles(user, List.of(png)).get(0);

        // start from a known Wednesday; tue,thu rows should skip to Thursday
        ScheduleEvent first = inbox.confirm(user, item, "COSC391 Data Mining", "CLASS",
                LocalDate.of(2026, 9, 16), LocalTime.of(6, 30), LocalTime.of(8, 0),
                "L105A (Computer Lab A) - Library", null, 3);

        assertNotNull(first);
        assertEquals(LocalDate.of(2026, 9, 17), first.getDate()); // first thu

        // window = start date + 2 more weeks: Sep 16 .. Sep 30.
        // tue/thu occurrences: Thu 17, Tue 22, Thu 24, Tue 29 => 4 events
        org.mockito.Mockito.verify(events,
                org.mockito.Mockito.times(4)).save(any(ScheduleEvent.class));
        assertEquals(LocalDate.of(2026, 9, 17), first.getDate());
    }

    @Test
    void parseDaysHandlesCsv() {
        List<DayOfWeek> days = InboxService.parseDays("mon,wed,fri");
        assertEquals(List.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), days);
        assertTrue(InboxService.parseDays(null).isEmpty());
        assertTrue(InboxService.parseDays("bogus").isEmpty());
    }

    @Test
    void nextOccurrenceFindsFirstMatchingDay() {
        // Wed 2026-09-16: tue,thu => thu 17th
        assertEquals(LocalDate.of(2026, 9, 17),
                InboxService.nextOccurrence("tue,thu", LocalDate.of(2026, 9, 16)));
        // same-day match returns the same date (tue 15th for tue,thu)
        assertEquals(LocalDate.of(2026, 9, 15),
                InboxService.nextOccurrence("tue,thu", LocalDate.of(2026, 9, 15)));
        assertNull(InboxService.nextOccurrence(null, LocalDate.of(2026, 9, 16)));
        assertNull(InboxService.nextOccurrence("bogus", LocalDate.of(2026, 9, 16)));
    }

    @Test
    void imageWithoutVisionResultFallsBackToManualEntry() {
        // LLM enabled but returns nothing -> OCR disabled by default -> manual item
        LlmClient llm = mock(LlmClient.class);
        when(llm.isEnabled()).thenReturn(true);
        when(llm.chatWithImage(anyString(), anyString(), any(), anyString()))
                .thenReturn("{\"events\":[]}");
        ImageOcrService ocrOff = mock(ImageOcrService.class);
        when(ocrOff.isEnabled()).thenReturn(false);
        when(ocrOff.recognize(any())).thenReturn(null);

        InboxService svc = new InboxService(items, events, mock(ExtractionService.class),
                new InboxExtractionService(llm), ocrOff, courses, enrollments);

        MockMultipartFile png = new MockMultipartFile(
                "files", "photo.jpg", "image/jpeg", new byte[]{9});

        List<InboxItem> created = svc.ingestFiles(user, List.of(png));

        assertEquals(1, created.size());
        InboxItem item = created.get(0);
        assertEquals(InboxItem.Status.PENDING, item.getStatus());
        assertNotNull(item.getWarning());
        assertTrue(item.getWarning().contains("vision"));
    }
}
