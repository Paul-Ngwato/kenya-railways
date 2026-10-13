package com.ueab.sac;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.ueab.sac.service.LlmClient;
import com.ueab.sac.service.inbox.InboxExtractionService;
import com.ueab.sac.service.inbox.ImageOcrService;

class ImageExtractionTest {

    @Test
    void weeklyTimetableResponseIsParsed() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.isEnabled()).thenReturn(true);
        when(llm.chatWithImage(anyString(), anyString(), any(), anyString()))
                .thenReturn("```json\n{\"classes\":["
                        + "{\"title\":\"INSY443 Research Methods in IT\",\"days\":[\"fri\"],"
                        + "\"start\":\"06:30\",\"end\":\"08:00\",\"venue\":\"L105C (Computer Lab C) - Library\",\"lecturer\":\"DR. MONY VICTOR OTIENO\"},"
                        + "{\"title\":\"MGMT378 Entrepreneurship\",\"days\":[\"tue\",\"thursday\"],"
                        + "\"start\":\"08:00\",\"end\":\"09:30\",\"venue\":\"H101 - Humanities\",\"lecturer\":null}"
                        + "]}\n```}");

        InboxExtractionService svc = new InboxExtractionService(llm);
        List<InboxExtractionService.Weekly> out =
                svc.extractWeeklyFromImage(new byte[]{1}, "image/png");

        assertEquals(2, out.size());
        assertEquals("INSY443 Research Methods in IT", out.get(0).title());
        assertEquals(List.of("fri"), out.get(0).days());
        assertEquals(LocalTime.of(6, 30), out.get(0).start());
        assertEquals("H101 - Humanities", out.get(1).venue());
        assertEquals(List.of("tue", "thu"), out.get(1).days()); // "thursday" normalized
    }

    @Test
    void ocrTimetableRowIsParsedOffline() {
        LlmClient llm = new LlmClient(); // disabled -> pure offline path
        InboxExtractionService svc = new InboxExtractionService(llm);

        String row = "2 COSC391 DATA MINING 3 DR. MONY VICTOR OTIENO "
                + "L105A (Computer Lab A) - Library tue,thu 06:30 08:00";
        var w = svc.weeklyFromText(row);

        assertNotNull(w);
        assertEquals("COSC391 DATA MINING", w.title());
        assertEquals(List.of("tue", "thu"), w.days());
        assertEquals(LocalTime.of(6, 30), w.start());
        assertEquals(LocalTime.of(8, 0), w.end());
        assertNotNull(w.venue());

        var fri = svc.weeklyFromText(
                "1 INSY443 RESEARCH METHODS IN IT 2 DR. MONY VICTOR OTIENO "
                        + "L105C (Computer Lab C) - Library fri 06:30 08:00");
        assertNotNull(fri);
        assertEquals(List.of("fri"), fri.days());
        assertEquals("INSY443 RESEARCH METHODS IN IT", fri.title());

        // non-row text stays null
        assertNull(svc.weeklyFromText("Guys, our database class moved to Friday 2pm"));
    }

    @Test
    void gdgFlyerIsParsedOffline() {
        LlmClient llm = new LlmClient(); // disabled -> pure offline path
        InboxExtractionService svc = new InboxExtractionService(llm);

        String flyer = String.join("\n",
                "GDG on Campus UEAB",
                "presents",
                "PYTHON + GO",
                "Learn. Build. Ship.",
                "WHAT YOU'LL DO",
                "Build practical Python applications and syntax",
                "WHEN",
                "Monday - Wednesday - Friday",
                "8:00 PM - 9:00 PM EAT",
                "Online via Discord",
                "For students who want to seriously improve their development skills",
                "5 Oct - 7 Nov 2026");

        var w = svc.weeklyFromFlyer(flyer);
        assertNotNull(w);
        assertEquals(List.of("mon", "wed", "fri"), w.days());
        assertEquals(LocalTime.of(20, 0), w.start());       // 8:00 PM -> 20:00
        assertEquals(LocalTime.of(21, 0), w.end());         // 9:00 PM -> 21:00
        assertNotNull(w.venue());
        assertEquals(LocalDate.of(2026, 10, 5), w.rangeStart());
        assertEquals(LocalDate.of(2026, 11, 7), w.rangeEnd());

        // single weekday text is NOT a series
        assertNull(svc.weeklyFromFlyer("Workshop on Friday at 5pm in Room 2"));
    }

    @Test
    void visionResponseWithMultipleEventsIsParsed() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.isEnabled()).thenReturn(true);
        when(llm.chatWithImage(anyString(), anyString(), any(), anyString()))
                .thenReturn("```json\n{\"events\":["
                        + "{\"title\":\"Database Systems CAT\",\"type\":\"CAT\","
                        + "\"date\":\"2026-09-25\",\"start\":\"10:00\",\"end\":\"11:30\","
                        + "\"venue\":\"Main Hall\",\"warning\":null},"
                        + "{\"title\":\"Algebra exam\",\"type\":\"EXAM\","
                        + "\"date\":\"2026-12-03\",\"start\":\"09:00\",\"end\":null,"
                        + "\"venue\":\"Exam Hall\",\"warning\":\"Time may be approximate\"}"
                        + "]}\n```");

        InboxExtractionService svc = new InboxExtractionService(llm);
        List<InboxExtractionService.Extracted> out =
                svc.extractFromImage(new byte[]{1, 2, 3}, "image/png");

        assertEquals(2, out.size());

        var first = out.get(0);
        assertEquals("Database Systems CAT", first.title());
        assertEquals("CAT", first.type());
        assertEquals(LocalDate.of(2026, 9, 25), first.date());
        assertEquals(LocalTime.of(10, 0), first.start());
        assertEquals(LocalTime.of(11, 30), first.end());
        assertEquals("Main Hall", first.venue());
        assertNull(first.warning());

        var second = out.get(1);
        assertEquals("EXAM", second.type());
        assertEquals(LocalTime.of(9, 0), second.start());
        assertNull(second.end());
        assertNotNull(second.warning());
    }

    @Test
    void visionResponseWithoutEventsYieldsEmptyList() {
        LlmClient llm = mock(LlmClient.class);
        when(llm.isEnabled()).thenReturn(true);
        when(llm.chatWithImage(anyString(), anyString(), any(), anyString()))
                .thenReturn("{\"events\":[]}");

        InboxExtractionService svc = new InboxExtractionService(llm);
        assertTrue(svc.extractFromImage(new byte[]{1}, "image/jpeg").isEmpty());
    }

    @Test
    void visionPathReturnsEmptyWhenLlmDisabled() {
        LlmClient llm = new LlmClient(); // not enabled by default
        InboxExtractionService svc = new InboxExtractionService(llm);
        assertTrue(svc.extractFromImage(new byte[]{1}, "image/png").isEmpty());
        assertTrue(svc.extractFromImage(null, "image/png").isEmpty());
    }

    @Test
    void imageFilenamesAreDetected() {
        assertTrue(ImageOcrService.isImageFilename("timetable.png"));
        assertTrue(ImageOcrService.isImageFilename("photo.JPG"));
        assertTrue(ImageOcrService.isImageFilename("screenshot.jpeg"));
        assertTrue(ImageOcrService.isImageFilename("scan.webp"));
        assertFalse(ImageOcrService.isImageFilename("notes.docx"));
        assertFalse(ImageOcrService.isImageFilename("exam.pdf"));
        assertFalse(ImageOcrService.isImageFilename(null));
    }

    @Test
    void mimeTypesAreMapped() {
        assertEquals("image/jpeg", ImageOcrService.mimeFor("a.jpg"));
        assertEquals("image/png", ImageOcrService.mimeFor("a.PNG"));
        assertEquals("image/webp", ImageOcrService.mimeFor("a.webp"));
        assertEquals("image/png", ImageOcrService.mimeFor("unknownfile"));
    }
}
