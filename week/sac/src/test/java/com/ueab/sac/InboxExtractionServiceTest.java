package com.ueab.sac;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import com.ueab.sac.model.ScheduleEvent;
import com.ueab.sac.service.LlmClient;
import com.ueab.sac.service.inbox.InboxExtractionService;

class InboxExtractionServiceTest {

    private final InboxExtractionService svc = new InboxExtractionService(new LlmClient());

    @Test
    void extractsClassChangeFromCasualMessage() {
        var ex = svc.extract("Guys, our database class has been moved to Friday at 2pm in Lab 3.");
        assertNotNull(ex);
        assertEquals(LocalDate.now().with(java.time.DayOfWeek.FRIDAY)
                .isAfter(LocalDate.now()) ? LocalDate.now().with(java.time.DayOfWeek.FRIDAY)
                : LocalDate.now().with(java.time.DayOfWeek.FRIDAY).plusDays(7), ex.date());
        assertEquals(LocalTime.of(14, 0), ex.start());
        assertEquals("Lab 3", ex.venue());
    }

    @Test
    void extractsExamWithDateAndVenue() {
        var ex = svc.extract("End semester EXAM for Database Systems on 3rd Dec at 9:30 am in Main Hall.");
        assertNotNull(ex);
        assertEquals("EXAM", ex.type());
        assertEquals(LocalDate.of(LocalDate.now().getYear(), 12, 3), ex.date());
        assertEquals(LocalTime.of(9, 30), ex.start());
        assertNotNull(ex.venue());
    }

    @Test
    void extractsTimeRange() {
        var ex = svc.extract("Group meeting 25/09/2026 from 2pm - 4pm in Room B5.");
        assertNotNull(ex);
        assertEquals(LocalDate.of(LocalDate.now().getYear(), 9, 25), ex.date());
        assertEquals(LocalTime.of(14, 0), ex.start());
        assertEquals(LocalTime.of(16, 0), ex.end());
    }

    @Test
    void warnsWhenNoDateFound() {
        var ex = svc.extract("Assignment deadline is tomorrow at 11:59pm.");
        assertNotNull(ex);
        assertNotNull(ex.date()); // tomorrow resolved
        var undated = svc.extract("The library will be repainting the reading room.");
        if (undated != null) {
            // weak content may still yield something but should carry a warning
            assertNotNull(undated.warning());
        }
    }

    @Test
    void fingerprintsMatchForSameEvent() {
        String a = svc.fingerprint("Database Systems CAT", LocalDate.of(2026, 9, 25), LocalTime.of(10, 0));
        String b = svc.fingerprint("CAT Database Systems", LocalDate.of(2026, 9, 25), LocalTime.of(10, 0));
        assertEquals(a, b);
    }

    @Test
    void detectsDuplicateExistingEvent() {
        ScheduleEvent existing = new ScheduleEvent();
        existing.setTitle("CAT 1: Database Systems");
        existing.setDate(LocalDate.of(2026, 9, 25));
        existing.setStartTime(java.time.LocalDateTime.of(2026, 9, 25, 10, 0));

        assertTrue(svc.looksLikeDuplicate(existing, "Database Systems CAT",
                LocalDate.of(2026, 9, 25), LocalTime.of(10, 0)));
        assertFalse(svc.looksLikeDuplicate(existing, "Chemistry practical",
                LocalDate.of(2026, 9, 25), LocalTime.of(10, 0)));
        assertFalse(svc.looksLikeDuplicate(existing, "Database Systems CAT",
                LocalDate.of(2026, 9, 26), LocalTime.of(10, 0)));
    }

    @Test
    void splitsTimetableIntoPerLineItems() {
        String text = "Mon 14/09: Algebra, 8am\n"
                + "Tue 15/09: DB, 10am\n"
                + "Wed 16/09: Chemistry, 2pm\n"
                + "Thu 17/09: English, 9am";
        var chunks = svc.splitIntoItems(text);
        assertEquals(4, chunks.size());
    }

    @Test
    void keepsSingleMessageWhole() {
        var chunks = svc.splitIntoItems(
                "Hey everyone, the deadline for the term paper is 30/10. "
                        + "Late submissions will not be marked. Good luck!");
        assertEquals(1, chunks.size());
    }
}
