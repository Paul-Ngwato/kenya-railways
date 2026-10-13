package com.ueab.sac.service.inbox;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ueab.sac.model.InboxItem;
import com.ueab.sac.service.LlmClient;

/**
 * Turns raw text (pasted, extracted from files, or WhatsApp messages) into
 * structured event fields. Uses the LLM when configured, otherwise a local
 * heuristic extractor so the platform works fully offline.
 */
@Service
public class InboxExtractionService {

    private final LlmClient llm;

    public InboxExtractionService(LlmClient llm) {
        this.llm = llm;
    }

    /** Whether a vision-capable LLM is configured (drives the image path). */
    public boolean llmEnabled() {
        return llm.isEnabled();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Result of extraction for one raw text. */
    public record Extracted(String title, String type, LocalDate date,
                            LocalTime start, LocalTime end, String venue,
                            String warning) {}

    /** A recurring weekly class parsed from a timetable image (e.g. tue,thu 06:30-08:00). */
    public record Weekly(String title, String type, List<String> days,
                         LocalTime start, LocalTime end, String venue, String lecturer,
                         LocalDate rangeStart, LocalDate rangeEnd) {}

    /** Parses "8:00 PM", "8pm", "20:00", "8.30 pm" into a LocalTime; null if not a time. */
    private static LocalTime parseLoose12h(String raw) {
        if (raw == null) return null;
        String t = raw.trim().toLowerCase(Locale.ROOT).replace('.', ':').replace('–', '-');
        Matcher m = Pattern.compile("\\b(\\d{1,2})(?::([0-5]\\d))?\\s*(am|pm)?\\b").matcher(t);
        if (!m.find()) return null;
        try {
            int h = Integer.parseInt(m.group(1));
            int min = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
            String ap = m.group(3);
            if (ap != null) {
                if (ap.equals("pm") && h < 12) h += 12;
                if (ap.equals("am") && h == 12) h = 0;
            } else if (h > 23) {
                return null;
            }
            if (h < 0 || h > 23 || min > 59) return null;
            return LocalTime.of(h, min);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Offline flyer/poster parser: days, times and venue are scattered across
     * lines (e.g. GDG poster: "Monday · Wednesday · Friday" / "8:00 PM - 9:00 PM
     * EAT" / "Online via Discord"). Looks for a date range for the repeat count.
     */
    public Weekly weeklyFromFlyer(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.replaceAll("\\r?\\n", " ").replaceAll("\\s+", " ").trim();
        if (t.length() < 12) return null;

        // days written as words (Monday, Wednesday...) — needs >= 2 or an explicit list
        List<String> days = new ArrayList<>();
        Matcher dm = Pattern.compile(
                "\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b",
                Pattern.CASE_INSENSITIVE).matcher(t);
        while (dm.find()) {
            String d = normalizeDay(dm.group(1));
            if (d != null && !days.contains(d)) days.add(d);
        }
        if (days.size() < 2) return null; // single weekday = probably a one-off, not a series

        // time range "8:00 PM - 9:00 PM" or single time
        LocalTime start = null;
        LocalTime end = null;
        Matcher rm = Pattern.compile(
                "\\b(\\d{1,2}(?::[0-5]\\d)?\\s*(?:am|pm)?)\\s*(?:-|–|—|to|until)\\s*"
                        + "(\\d{1,2}(?::[0-5]\\d)?\\s*(?:am|pm)?)",
                Pattern.CASE_INSENSITIVE).matcher(t);
        if (rm.find()) {
            start = parseLoose12h(rm.group(1));
            end = parseLoose12h(rm.group(2));
        }
        if (start == null) {
            Matcher sm = Pattern.compile("\\b(\\d{1,2}(?::[0-5]\\d)?\\s*(?:am|pm))\\b",
                    Pattern.CASE_INSENSITIVE).matcher(t);
            if (sm.find()) start = parseLoose12h(sm.group(1));
        }
        if (start == null) return null;

        // venue: "Online via Discord", "on Discord", "in Room 5", keyword rooms
        String venue = null;
        Matcher om = Pattern.compile(
                "(?i)\\b(?:online\\s+(?:via|on|at)|on|in)\\s+((?:[A-Z][\\w-]*\\s?){1,3})").matcher(t);
        if (om.find()) venue = om.group(1).trim();
        if (venue == null) venue = extractVenue(t);

        // optional date range "5 Oct - 7 Nov 2026" / "Oct 5 to Nov 7"
        LocalDate rangeStart = null;
        LocalDate rangeEnd = null;
        Matcher dr = Pattern.compile(
                "\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*"
                        + "\\s*(?:-|–|—|to)\\s*(\\d{1,2})(?:st|nd|rd|th)?\\s+"
                        + "(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*(?:\\s*(\\d{4}))?",
                Pattern.CASE_INSENSITIVE).matcher(t);
        if (dr.find()) {
            try {
                // groups: 1=day1 2=month1 3=day2 4=month2 5=year?
                int y = dr.group(5) != null ? Integer.parseInt(dr.group(5)) : LocalDate.now().getYear();
                rangeStart = LocalDate.of(y, monthNumber(dr.group(2)), Integer.parseInt(dr.group(1)));
                rangeEnd = LocalDate.of(y, monthNumber(dr.group(4)), Integer.parseInt(dr.group(3)));
                if (rangeEnd.isBefore(rangeStart)) rangeEnd = rangeEnd.plusYears(1);
            } catch (Exception ignored) { rangeStart = null; rangeEnd = null; }
        }

        // title: the longest ALL-CAPS-ish run of words near the top, else first 5 words
        String title = flyerTitle(text);
        return new Weekly(title, detectWeeklyType(t), days, start, end, venue, null, rangeStart, rangeEnd);
    }

    /** Co-curricular vs academic classification from flyer keywords. */
    static String detectWeeklyType(String t) {
        String s = t.toLowerCase(Locale.ROOT);
        if (s.contains("worship") || s.contains("church") || s.contains("chapel") || s.contains("sabbath")
                || s.contains("prayer") || s.contains("fellowship")) return "WORSHIP";
        if (s.contains("match") || s.contains("tournament") || s.contains("league") || s.contains("training")
                || s.contains("gym") || s.contains("fitness") || s.contains("sport") || s.contains("football")
                || s.contains("basketball") || s.contains("volleyball") || s.contains("rugby")) return "SPORT";
        if (s.contains("volunteer") || s.contains("outreach") || s.contains("community service")
                || s.contains("charity") || s.contains("cleanup")) return "VOLUNTEER";
        if (s.contains("club") || s.contains("society") || s.contains("gdg") || s.contains("developer")
                || s.contains("coding") || s.contains("robotics") || s.contains("debate") || s.contains("moot")
                || s.contains("toastmasters") || s.contains("entrepreneur")) return "CLUB";
        return "EVENT";
    }

    /** Title for flyers: first strong line (≥4 chars, mostly letters), max 5 words. */
    private static String flyerTitle(String text) {
        for (String line : text.split("\\r?\\n")) {
            String l = line.replaceAll("[^A-Za-z0-9 +&-]", " ").replaceAll("\\s+", " ").trim();
            if (l.length() < 4) continue;
            String[] words = l.split(" ");
            int letters = 0;
            for (String w : words) if (w.matches("[A-Za-z]{2,}")) letters++;
            if (letters >= Math.max(1, words.length / 2) && l.length() >= 6) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(5, words.length); i++) sb.append(words[i]).append(' ');
                String out = sb.toString().trim();
                if (out.length() > 60) out = out.substring(0, 57) + "...";
                return out;
            }
        }
        return "Weekly event";
    }

    /** Extracts one event from a raw text chunk. */
    public Extracted extract(String rawText) {
        if (llm.isEnabled()) {
            Extracted viaLlm = viaLlm(rawText);
            if (viaLlm != null) return viaLlm;
        }
        return localExtract(rawText);
    }

    /**
     * Splits a document into per-item chunks when it looks like it contains
     * several entries (one per line starting with a date, etc.).
     * Returns a single chunk containing the whole text when in doubt.
     */
    public List<String> splitIntoItems(String text) {
        if (text == null) return List.of();
        String[] lines = text.split("\\r?\\n");
        List<String> datedLines = new ArrayList<>();
        for (String line : lines) {
            String t = line.trim();
            if (t.length() < 4) continue;
            if (DATE_ANY_HINT.matcher(t).find()) datedLines.add(t);
        }
        // several self-contained dated lines -> treat each as one item
        if (datedLines.size() >= 3 && datedLines.size() >= lines.length * 0.5) {
            return datedLines;
        }
        String whole = text.trim();
        return whole.isEmpty() ? List.of() : List.of(whole);
    }

    // ------------------------------------------------------------------
    // LLM path
    // ------------------------------------------------------------------

    private Extracted viaLlm(String rawText) {
        String json = llm.chat(
                "You extract academic schedule entries. Reply ONLY with JSON: "
                        + "{\"title\":\"...\",\"type\":\"CLASS|ASSIGNMENT|STUDY|PRACTICAL|MEETING|EVENT|WORK|REMINDER|CAT|EXAM|DEADLINE|OTHER\","
                        + "\"date\":\"YYYY-MM-DD\",\"start\":\"HH:MM\",\"end\":\"HH:MM\",\"venue\":\"...\",\"warning\":\"...\"}. "
                        + "Use null/empty for unknown fields. If the text is not about a schedulable academic event, "
                        + "reply {\"title\":null}. Today is " + LocalDate.now() + ".",
                rawText.length() > 6000 ? rawText.substring(0, 6000) : rawText);
        if (json == null || json.isBlank()) return null;
        try {
            return fromNode(MAPPER.readTree(cleanJson(json)));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Vision path: reads a photo/screenshot (timetable, exam schedule, notice,
     * WhatsApp screenshot) and returns every schedulable event found in it.
     * Returns an empty list when the LLM is disabled, fails, or sees nothing.
     */
    public List<Extracted> extractFromImage(byte[] imageBytes, String mimeType) {
        if (!llm.isEnabled() || imageBytes == null || imageBytes.length == 0) return List.of();
        String json = llm.chatWithImage(
                "You extract academic schedule entries from images: timetables, exam schedules, "
                        + "notices, memos or chat screenshots (photos are fine). "
                        + "Reply ONLY with JSON: {\"events\":[{\"title\":\"...\","
                        + "\"type\":\"CLASS|ASSIGNMENT|STUDY|PRACTICAL|MEETING|EVENT|WORK|REMINDER|CAT|EXAM|DEADLINE|CLUB|SPORT|WORSHIP|VOLUNTEER|OTHER\","
                        + "\"date\":\"YYYY-MM-DD\",\"start\":\"HH:MM\",\"end\":\"HH:MM\",\"venue\":\"...\",\"warning\":\"...\"}]}. "
                        + "Transcribe every schedulable event you can read; use null for unknown fields. "
                        + "If the image contains no schedulable event, reply {\"events\":[]}. "
                        + "Today is " + LocalDate.now() + ".",                "List every schedulable academic event visible in this image.",
                imageBytes, mimeType);
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode root = MAPPER.readTree(cleanJson(json));
            JsonNode arr = root.path("events");
            if (arr.isMissingNode() && root.isArray()) arr = root;
            List<Extracted> out = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    Extracted e = fromNode(n);
                    if (e != null) out.add(e);
                }
            } else {
                Extracted e = fromNode(root);
                if (e != null) out.add(e);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Vision path specialized for weekly class timetables (like university
     * portal screenshots): each row is a course meeting on fixed weekdays at a
     * fixed time (e.g. "tue,thu 06:30-08:00"). The user later picks how many
     * weeks to expand into concrete dated events.
     */
    public List<Weekly> extractWeeklyFromImage(byte[] imageBytes, String mimeType) {
        if (!llm.isEnabled() || imageBytes == null || imageBytes.length == 0) return List.of();
        String json = llm.chatWithImage(
                "You read university timetable and event-flyer images (portal screenshots, photos of "
                        + "printed tables, club/sport/worship posters). Each row is one weekly series: title, "
                        + "days and start/end times. Reply ONLY with JSON: {\"classes\":[{\"title\":\"COSC391 Data Mining\","
                        + "\"days\":[\"mon\",\"tue\",\"wed\",\"thu\",\"fri\",\"sat\",\"sun\"],"
                        + "\"start\":\"HH:MM\",\"end\":\"HH:MM\",\"venue\":\"...\",\"lecturer\":\"...\"}]}. "
                        + "Rules: title = course code + course title; days = lowercase 3-letter weekday codes "
                        + "(24-hour times; if the image shows AM/PM convert to 24h). Transcribe every row you can "
                        + "read; use null for unknown fields. If the image is not a weekly timetable, "
                        + "reply {\"classes\":[]}. Today is " + LocalDate.now() + ".",
                "Extract every weekly class row from this timetable image.",
                imageBytes, mimeType);
        if (json == null || json.isBlank()) return List.of();
        try {
            JsonNode root = MAPPER.readTree(cleanJson(json));
            JsonNode arr = root.path("classes");
            if (arr.isMissingNode() && root.isArray()) arr = root;
            List<Weekly> out = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    String title = n.path("title").asText(null);
                    List<String> days = new ArrayList<>();
                    JsonNode d = n.path("days");
                    if (d.isArray()) {
                        for (JsonNode day : d) {
                            String s = normalizeDay(day.asText(null));
                            if (s != null) days.add(s);
                        }
                    }
                    LocalTime start = parseTimeOrNull(n.path("start").asText(null));
                    LocalTime end = parseTimeOrNull(n.path("end").asText(null));
                    if (title == null || title.isBlank() || days.isEmpty()) continue;
                    out.add(new Weekly(title.trim(), "CLASS", days, start, end,
                            blankToNull(n.path("venue").asText(null)),
                            blankToNull(n.path("lecturer").asText(null)), null, null));
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Offline timetable-row parser: recognizes OCR'd lines like
     * "2 COSC391 DATA MINING 3 DR. MONY L105A - Library tue,thu 06:30 08:00"
     * and returns the weekly class, or null when the line is not a row.
     * This makes image uploads work without any LLM configured.
     */
    public Weekly weeklyFromText(String text) {
        if (text == null) return null;
        String t = text.replaceAll("\\s+", " ").trim();
        if (t.length() < 12) return null;

        // course code: 3-4 letters + 3-4 digits (INSY443, COSC391, ECON210)
        Matcher code = Pattern.compile("\\b([A-Z]{3,4}\\s?\\d{3,4})\\b").matcher(t);
        if (!code.find()) return null;
        String courseCode = code.group(1).replaceAll("\\s+", "");

        // weekday tokens (distinct, in order). Full-word alternation longest-first,
        // so names like "MONY" don't get read as "mon".
        List<String> days = new ArrayList<>();
        Matcher dm = Pattern.compile(
                "\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday"
                        + "|mon|tues|tue|weds|wed|thurs|thu|fri|sat|sun)\\b",
                Pattern.CASE_INSENSITIVE).matcher(t);
        while (dm.find()) {
            String d = normalizeDay(dm.group(1));
            if (d != null && !days.contains(d)) days.add(d);
        }
        if (days.isEmpty()) return null;

        // clock times HH:MM (OCR usually yields 24h)
        List<LocalTime> times = new ArrayList<>();
        Matcher tm = Pattern.compile("\\b(\\d{1,2}:[0-5]\\d)\\b").matcher(t);
        while (tm.find() && times.size() < 2) {
            LocalTime lt = parseTimeOrNull(tm.group(1));
            if (lt != null && (times.isEmpty() || !lt.equals(times.get(times.size() - 1)))) {
                times.add(lt);
            }
        }
        if (times.isEmpty()) return null;

        // title: course code + following ALL-CAPS words (stop at Credit/honorific/digits)
        String title = buildRowTitle(t, code.end(), courseCode);

        // venue via the existing keyword matcher
        String venue = extractVenue(t);
        return new Weekly(title, "CLASS", days, times.get(0),
                times.size() > 1 ? times.get(1) : null, venue, null, null, null);
    }

    /** Title = course code + up to 6 ALL-CAPS words right after it. */
    private static String buildRowTitle(String line, int from, String fallback) {
        String[] words = line.substring(from).trim().split(" ");
        StringBuilder sb = new StringBuilder(fallback);
        int taken = 0;
        for (String w : words) {
            String clean = w.replaceAll("[^A-Za-z0-9&-]", "");
            if (clean.isEmpty()) continue;
            if (clean.matches("(?i)(credit|lecturer|room|days|start|end)")) break;
            if (clean.matches("(?i)(dr|prof|mr|ms|mrs)\\.?")) break;
            if (!clean.matches("[A-Z&-]{2,}")) break; // only ALL-CAPS words
            sb.append(' ').append(clean);
            if (++taken >= 6) break;
        }
        return sb.toString();
    }

    /** Normalizes weekday names/codes to lowercase 3-letter codes; null if unknown. */
    private static String normalizeDay(String s) {
        if (s == null) return null;
        String d = s.trim().toLowerCase(Locale.ROOT);
        return switch (d) {
            case "mon", "monday", "m" -> "mon";
            case "tue", "tues", "tuesday", "t" -> "tue";
            case "wed", "weds", "wednesday", "w" -> "wed";
            case "thu", "thur", "thurs", "thursday" -> "thu";
            case "fri", "friday", "f" -> "fri";
            case "sat", "saturday" -> "sat";
            case "sun", "sunday" -> "sun";
            default -> null;
        };
    }

    /** Maps one JSON object to Extracted; null when there is no usable title. */
    private Extracted fromNode(JsonNode node) {
        String title = node.path("title").asText(null);
        if (title == null || title.isBlank()) return null;

        LocalDate date = parseDateOrNull(node.path("date").asText(null));
        LocalTime start = parseTimeOrNull(node.path("start").asText(null));
        LocalTime end = parseTimeOrNull(node.path("end").asText(null));
        String warning = node.path("warning").asText(null);
        if (date == null && (warning == null || warning.isBlank())) {
            warning = "No date was found — please set one before confirming.";
        }
        return new Extracted(title.trim(),
                node.path("type").asText("OTHER"),
                date, start, end,
                blankToNull(node.path("venue").asText(null)),
                blankToNull(warning));
    }

    /** Strips markdown fences / chatter so the JSON can be parsed. Keeps the
     *  outermost object or array (first opening bracket .. last closing one),
     *  so nested event arrays survive intact. */
    private static String cleanJson(String json) {
        int as = json.indexOf('[');
        int ae = json.lastIndexOf(']');
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        boolean arrayFirst = as >= 0 && (start < 0 || as < start);
        if (arrayFirst && ae > as) return json.substring(as, ae + 1);
        if (start >= 0 && end > start) return json.substring(start, end + 1);
        return json;
    }

    // ------------------------------------------------------------------
    // Local heuristic path (offline)
    // ------------------------------------------------------------------

    private static final Pattern P_DMY = Pattern.compile(
            "\\b(\\d{1,2})[/.-](\\d{1,2})(?:[/.-](\\d{2,4}))?\\b");
    private static final Pattern P_MONTH_NAME = Pattern.compile(
            "\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern P_WEEKDAY = Pattern.compile(
            "\\b(next\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern P_TODAY_TOMORROW = Pattern.compile(
            "\\b(today|tomorrow)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_TIME = Pattern.compile(
            "\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*([ap]m)\\b|\\b(\\d{1,2}):(\\d{2})\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern P_VENUE = Pattern.compile(
            "\\b((?:[A-Z][A-Za-z0-9]*\\s){0,2}(?:Room|Lab|Hall|Block|Theatre|Complex|Annex)(?:\\s?[A-Z0-9]\\b)?)");
    private static final Pattern P_TIME_RANGE = Pattern.compile(
            "(\\b\\d{1,2}(?:[:.]\\d{2})?\\s*[ap]m?\\b|\\b\\d{1,2}:\\d{2}\\b)\\s*(?:-|–|to|until)\\s*"
                    + "(\\b\\d{1,2}(?:[:.]\\d{2})?\\s*[ap]m?\\b|\\b\\d{1,2}:\\d{2}\\b)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DATE_ANY_HINT = Pattern.compile(
            "\\b\\d{1,2}[/.-]\\d{1,2}\\b|\\b\\d{1,2}(st|nd|rd|th)?\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)"
                    + "|\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b|\\b(today|tomorrow)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern P_EXAM = Pattern.compile("\\bexam\\b|examination", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_CAT = Pattern.compile("\\bcat\\b|continuous assessment", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_DEADLINE = Pattern.compile("deadline|due|submit|submission|hand ?in", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_ASSIGNMENT = Pattern.compile("assignment|homework|task", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_PRACTICAL = Pattern.compile("practical|lab session|laboratory", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_MEETING = Pattern.compile("meeting|consultation", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_CLASS = Pattern.compile("\\bclass\\b|lecture|lesson", Pattern.CASE_INSENSITIVE);

    private Extracted localExtract(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim();

        LocalDate date = extractDate(t);
        LocalTime[] range = extractTimeRange(t);
        String venue = extractVenue(t);

        boolean exam = P_EXAM.matcher(t).find();
        boolean cat = P_CAT.matcher(t).find();
        boolean deadline = P_DEADLINE.matcher(t).find();
        boolean assignment = P_ASSIGNMENT.matcher(t).find();
        boolean practical = P_PRACTICAL.matcher(t).find();
        boolean meeting = P_MEETING.matcher(t).find();

        String type = exam ? "EXAM" : cat ? "CAT" : deadline ? "DEADLINE"
                : assignment ? "ASSIGNMENT" : practical ? "PRACTICAL"
                : meeting ? "MEETING" : "CLASS";

        // no date and nothing that looks schedulable -> reject quietly
        if (date == null && range[0] == null && venue == null && !exam && !cat && !deadline) {
            return null;
        }

        String title = buildTitle(t, type);
        String warning = null;
        if (date == null) warning = "No date was found — please set one before confirming.";

        return new Extracted(title, type, date, range[0], range[1], venue, warning);
    }

    private String buildTitle(String text, String type) {
        String t = text.replaceAll("\\s+", " ").trim();
        // drop a leading "Re:" / "Fwd:" noise
        t = t.replaceFirst("(?i)^(re|fwd)\\s*:\\s*", "");
        if (t.length() > 60) t = t.substring(0, 57) + "...";
        return t;
    }

    private LocalDate extractDate(String text) {
        Matcher m = P_DMY.matcher(text);
        if (m.find()) {
            try {
                int day = Integer.parseInt(m.group(1));
                int month = Integer.parseInt(m.group(2));
                int year = (m.group(3) != null) ? Integer.parseInt(m.group(3)) : LocalDate.now().getYear();
                if (year < 100) year += 2000;
                return LocalDate.of(year, month, day);
            } catch (Exception ignored) {}
        }
        m = P_MONTH_NAME.matcher(text);
        if (m.find()) {
            try {
                int day = Integer.parseInt(m.group(1));
                int month = monthNumber(m.group(2));
                if (month > 0) {
                    int year = LocalDate.now().getYear();
                    LocalDate d = LocalDate.of(year, month, day);
                    if (d.isBefore(LocalDate.now().minusDays(180))) d = d.plusYears(1);
                    return d;
                }
            } catch (Exception ignored) {}
        }
        m = P_WEEKDAY.matcher(text);
        if (m.find()) {
            String wd = m.group(2).toUpperCase(Locale.ROOT);
            LocalDate d = LocalDate.now();
            int target = java.time.DayOfWeek.valueOf(wd).getValue();
            int diff = (target - d.getDayOfWeek().getValue() + 7) % 7;
            if (diff == 0) diff = 7;
            if (m.group(1) != null && diff < 7) diff += 7;
            return d.plusDays(diff);
        }
        m = P_TODAY_TOMORROW.matcher(text);
        if (m.find()) {
            return m.group(1).equalsIgnoreCase("tomorrow")
                    ? LocalDate.now().plusDays(1) : LocalDate.now();
        }
        return null;
    }

    /** Finds "2pm - 4pm" style ranges first, then a single time. */
    private LocalTime[] extractTimeRange(String text) {
        Matcher m = P_TIME_RANGE.matcher(text);
        if (m.find()) {
            LocalTime s = parseLooseTime(m.group(1));
            LocalTime e = parseLooseTime(m.group(2));
            if (s != null && e != null) return new LocalTime[]{s, e};
        }
        LocalTime single = extractTime(text);
        return new LocalTime[]{single, null};
    }

    private LocalTime extractTime(String text) {
        Matcher m = P_TIME.matcher(text);
        if (m.find()) return parseLooseTime(m.group());
        return null;
    }

    private LocalTime parseLooseTime(String raw) {
        if (raw == null) return null;
        try {
            String t = raw.trim().toLowerCase(Locale.ROOT).replace('.', ':');
            int h;
            int min;
            if (t.endsWith("am") || t.endsWith("pm")) {
                boolean pm = t.endsWith("pm");
                t = t.substring(0, t.length() - 2).trim();
                String[] parts = t.split(":");
                h = Integer.parseInt(parts[0]);
                min = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                if (pm && h < 12) h += 12;
                if (!pm && h == 12) h = 0;
            } else {
                String[] parts = t.split(":");
                h = Integer.parseInt(parts[0]);
                min = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            }
            if (h >= 0 && h <= 23 && min >= 0 && min <= 59) return LocalTime.of(h, min);
        } catch (Exception ignored) {}
        return null;
    }

    private String extractVenue(String text) {
        Matcher m = P_VENUE.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static int monthNumber(String abbr) {
        return switch (abbr.toUpperCase(Locale.ROOT)) {
            case "JAN" -> 1; case "FEB" -> 2; case "MAR" -> 3; case "APR" -> 4;
            case "MAY" -> 5; case "JUN" -> 6; case "JUL" -> 7; case "AUG" -> 8;
            case "SEP" -> 9; case "OCT" -> 10; case "NOV" -> 11; case "DEC" -> 12;
            default -> 0;
        };
    }

    private static LocalDate parseDateOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalTime parseTimeOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalTime.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    // ------------------------------------------------------------------
    // Duplicate detection
    // ------------------------------------------------------------------

    /**
     * Fingerprint of an extracted item: normalized title + date + time.
     * Items with the same fingerprint are flagged as possible duplicates.
     */
    public String fingerprint(String title, LocalDate date, LocalTime time) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        String[] words = t.split(" ");
        Arrays.sort(words);
        return String.join(" ", Arrays.asList(words))
                + "|" + (date == null ? "?" : date.toString())
                + "|" + (time == null ? "?" : time.toString());
    }

    /** True when an existing calendar event looks like the same occurrence. */
    public boolean looksLikeDuplicate(com.ueab.sac.model.ScheduleEvent e,
                                      String title, LocalDate date, LocalTime time) {
        if (e.getDate() == null || date == null) return false;
        if (!e.getDate().equals(date)) return false;
        if (time != null && e.getStartTime() != null
                && !e.getStartTime().toLocalTime().equals(time)) return false;
        String a = normalizeTitle(title);
        String b = normalizeTitle(e.getTitle());
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    private static String normalizeTitle(String title) {
        if (title == null) return "";
        // strip a leading type prefix like "CAT:", "CAT 1:", "EXAM -" so that
        // "CAT 1: Database Systems" matches "Database Systems CAT"
        return title.toLowerCase(Locale.ROOT)
                .replaceFirst("^\\s*(cat|exam|deadline|assignment)\\s*\\d{0,3}\\s*[:.\\-–]?\\s*", "")
                .replaceAll("[^a-z0-9]+", " ").trim();
    }
}
