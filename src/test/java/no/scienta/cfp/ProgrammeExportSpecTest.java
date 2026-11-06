package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Black-box tests for the Programme export spec (harness /specs/programme-export/spec.md, version 1).
 *
 * <p>Requirements, in the order of "What we want":
 * <ul>
 * <li>R1: two read-only views, one row per scheduled talk, nothing else, same order in both</li>
 * <li>R2: earliest start first, within a slot by room</li>
 * <li>R3: row content: slot, start, end, room, talkId, title, format, speakers as they are right now</li>
 * <li>R4: speakers by name, main speaker first, then co-speakers in the order added; CSV joins with "; "</li>
 * <li>R5: the CSV: UTF-8, text/csv even for Accept: application/json, exact header, RFC 4180, LF, quoting</li>
 * <li>R6: an empty programme is [] / a header-only CSV</li>
 * </ul>
 *
 * <p>The tests share one database and the programme has only 15 cells, so every test starts by emptying the whole
 * programme. JUnit runs the tests one at a time, so whole-programme assertions (order, empty, exact CSV) are safe.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProgrammeExportSpecTest {

    private static final String HEADER = "slot,start,end,room,talkId,title,format,speakers";
    private static final List<String> ROOMS = List.of("R1", "R2", "R3");
    private static final List<String> SLOTS = List.of("S1", "S2", "S3", "S4", "S5");
    private static final Map<String, String[]> TIMES = Map.of(
            "S1", new String[] {"09:00", "10:00"},
            "S2", new String[] {"10:15", "11:15"},
            "S3", new String[] {"11:30", "12:30"},
            "S4", new String[] {"13:30", "15:30"},
            "S5", new String[] {"15:45", "16:45"});

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void emptyProgramme() throws Exception {
        for (String room : ROOMS) {
            for (String slot : SLOTS) {
                // status ignored on purpose: this is test set-up, not a check
                send("DELETE", "/programme/" + room + "/" + slot, null);
            }
        }
    }

    // ---------------------------------------------------------------- R1

    @Test
    @DisplayName("R1: one JSON row per scheduled talk, and nothing for talks that are not on the programme")
    void r1OneRowPerScheduledTalk() throws Exception {
        long a = acceptedTalk("Scheduled A", "PRESENTATION", "Ada Lovelace");
        long b = acceptedTalk("Scheduled B", "LIGHTNING_10", "Grace Hopper");
        long notScheduled = acceptedTalk("Accepted but not scheduled", "PRESENTATION", "Alan Turing");
        long submitted = submittedTalk("Only submitted", "PRESENTATION", "Edsger Dijkstra");
        schedule("R1", "S1", a);
        schedule("R2", "S3", b);

        List<Map<String, Object>> rows = jsonRows();
        assertEquals(List.of(a, b), rows.stream().map(r -> ((Number) r.get("talkId")).longValue()).collect(Collectors.toList()));
        assertFalse(rows.stream().anyMatch(r -> ((Number) r.get("talkId")).longValue() == notScheduled));
        assertFalse(rows.stream().anyMatch(r -> ((Number) r.get("talkId")).longValue() == submitted));
    }

    @Test
    @DisplayName("R1: JSON and CSV have the same rows in the same order")
    void r1JsonAndCsvSameRowsSameOrder() throws Exception {
        long t1 = acceptedTalk("Closing keynote", "PRESENTATION", "Ada Lovelace");
        long t2 = acceptedTalk("Workshop day", "WORKSHOP", "Grace Hopper");
        long t3 = acceptedTalk("Opening", "LIGHTNING_20", "Alan Turing");
        long t4 = acceptedTalk("Opening, with comma", "LIGHTNING_10", "Barbara Liskov");
        schedule("R3", "S5", t1);
        schedule("R2", "S4", t2);
        schedule("R2", "S1", t3);
        schedule("R1", "S1", t4);

        List<Map<String, Object>> json = jsonRows();
        List<List<String>> csv = csvRecords(csv().body());
        assertEquals(HEADER, String.join(",", csv.get(0)));
        assertEquals(json.size(), csv.size() - 1);
        for (int i = 0; i < json.size(); i++) {
            Map<String, Object> j = json.get(i);
            List<String> c = csv.get(i + 1);
            assertEquals(8, c.size(), "row " + i);
            assertEquals(j.get("slot"), c.get(0));
            assertEquals(j.get("start"), c.get(1));
            assertEquals(j.get("end"), c.get(2));
            assertEquals(j.get("room"), c.get(3));
            assertEquals(String.valueOf(((Number) j.get("talkId")).longValue()), c.get(4));
            assertEquals(j.get("title"), c.get(5));
            assertEquals(j.get("format"), c.get(6));
            @SuppressWarnings("unchecked")
            List<String> speakers = (List<String>) j.get("speakers");
            assertEquals(String.join("; ", speakers), c.get(7));
        }
    }

    @Test
    @DisplayName("R1: the views are read-only: POST to /programme and /programme.csv is not a success")
    void r1ReadOnly() throws Exception {
        for (String path : List.of("/programme", "/programme.csv")) {
            int status = send("POST", path, "{}").statusCode();
            assertTrue(status >= 400 && status < 500, path + " answered " + status);
        }
    }

    @Test
    @DisplayName("R1: taking a talk off the programme removes its row")
    void r1RemovedTalkDisappears() throws Exception {
        long a = acceptedTalk("Stays", "PRESENTATION", "Ada Lovelace");
        long b = acceptedTalk("Goes", "PRESENTATION", "Grace Hopper");
        schedule("R1", "S1", a);
        schedule("R2", "S1", b);
        assertEquals(2, jsonRows().size());
        send("DELETE", "/programme/R2/S1", null);
        List<Map<String, Object>> rows = jsonRows();
        assertEquals(1, rows.size());
        assertEquals(a, ((Number) rows.get(0).get("talkId")).longValue());
        assertEquals(2, csvRecords(csv().body()).size());
    }

    @Test
    @DisplayName("R1 (decision): a talk withdrawn after it was scheduled stays on the programme")
    void r1WithdrawnTalkStaysListed() throws Exception {
        long speaker = speaker("Ada Lovelace");
        long t = talk(speaker, "Withdrawn later", "PRESENTATION");
        accept(t);
        schedule("R1", "S2", t);
        HttpResponse<String> w = send("POST", "/talks/" + t + "/withdraw", "{\"speakerId\":" + speaker + "}");
        assertEquals(200, w.statusCode(), w.body());
        List<Map<String, Object>> rows = jsonRows();
        assertEquals(1, rows.size());
        assertEquals(t, ((Number) rows.get(0).get("talkId")).longValue());
    }

    // ---------------------------------------------------------------- R2

    @Test
    @DisplayName("R2: earliest start first, within a slot by room, in JSON and in CSV")
    void r2OrderedByStartThenRoom() throws Exception {
        String[][] cells = {{"R3", "S5"}, {"R2", "S4"}, {"R1", "S2"}, {"R3", "S1"}, {"R1", "S1"}, {"R2", "S1"}, {"R1", "S3"}};
        List<String> expected = List.of("S1/R1", "S1/R2", "S1/R3", "S2/R1", "S3/R1", "S4/R2", "S5/R3");
        for (String[] cell : cells) {
            long t = acceptedTalk("Talk in " + cell[0] + cell[1], "PRESENTATION", "Speaker " + cell[0] + cell[1]);
            schedule(cell[0], cell[1], t);
        }
        List<String> json = jsonRows().stream().map(r -> r.get("slot") + "/" + r.get("room")).collect(Collectors.toList());
        assertEquals(expected, json);
        List<String> csv = csvRecords(csv().body()).stream().skip(1).map(r -> r.get(0) + "/" + r.get(3)).collect(Collectors.toList());
        assertEquals(expected, csv);
    }

    @Test
    @DisplayName("R2: the order does not depend on the order the talks were scheduled or submitted in")
    void r2OrderIndependentOfInsertionOrder() throws Exception {
        long late = acceptedTalk("Submitted first, late slot", "PRESENTATION", "Ada Lovelace");
        long early = acceptedTalk("Submitted last, early slot", "PRESENTATION", "Grace Hopper");
        schedule("R1", "S5", late);
        schedule("R3", "S1", early);
        List<Long> ids = jsonRows().stream().map(r -> ((Number) r.get("talkId")).longValue()).collect(Collectors.toList());
        assertEquals(List.of(early, late), ids);
    }

    // ---------------------------------------------------------------- R3

    @Test
    @DisplayName("R3: a row has slot, start, end, room, talkId, title, format and speakers")
    void r3RowFields() throws Exception {
        long t = acceptedTalk("Fields galore", "LIGHTNING_20", "Ada Lovelace");
        schedule("R2", "S2", t);
        List<Map<String, Object>> rows = jsonRows();
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("S2", row.get("slot"));
        assertEquals("10:15", row.get("start"));
        assertEquals("11:15", row.get("end"));
        assertEquals("R2", row.get("room"));
        assertEquals(t, ((Number) row.get("talkId")).longValue());
        assertEquals("Fields galore", row.get("title"));
        assertEquals("LIGHTNING_20", row.get("format"));
        assertEquals(List.of("Ada Lovelace"), row.get("speakers"));
        assertEquals(List.of("slot", "start", "end", "room", "talkId", "title", "format", "speakers"),
                new ArrayList<>(row.keySet()));
    }

    @Test
    @DisplayName("R3: start and end are the slot's times as HH:mm, for every slot")
    void r3TimesForEverySlot() throws Exception {
        for (String slot : SLOTS) {
            long t = acceptedTalk("In " + slot, "PRESENTATION", "Speaker in " + slot);
            schedule("R1", slot, t);
        }
        List<Map<String, Object>> rows = jsonRows();
        assertEquals(5, rows.size());
        for (Map<String, Object> row : rows) {
            String[] times = TIMES.get((String) row.get("slot"));
            assertEquals(times[0], row.get("start"), "start of " + row.get("slot"));
            assertEquals(times[1], row.get("end"), "end of " + row.get("slot"));
        }
        // the long slot, as in the spec's example
        assertEquals("13:30", rows.get(3).get("start"));
        assertEquals("15:30", rows.get(3).get("end"));
    }

    @Test
    @DisplayName("R3: a workshop in the long slot shows format WORKSHOP and 13:30-15:30")
    void r3WorkshopInLongSlot() throws Exception {
        long t = acceptedTalk("Hands on", "WORKSHOP", "Grace Hopper");
        schedule("R3", "S4", t);
        Map<String, Object> row = jsonRows().get(0);
        assertEquals("WORKSHOP", row.get("format"));
        assertEquals("S4", row.get("slot"));
        assertEquals("13:30", row.get("start"));
        assertEquals("15:30", row.get("end"));
    }

    @Test
    @DisplayName("R3: talkId is a JSON number, speakers is a JSON array")
    void r3JsonTypes() throws Exception {
        long t = acceptedTalk("Types", "PRESENTATION", "Ada Lovelace");
        schedule("R1", "S1", t);
        Map<String, Object> row = jsonRows().get(0);
        assertTrue(row.get("talkId") instanceof Number, "talkId: " + row.get("talkId"));
        assertTrue(row.get("speakers") instanceof List, "speakers: " + row.get("speakers"));
        assertTrue(row.get("title") instanceof String);
    }

    @Test
    @DisplayName("R3: speakers are as they are right now: a co-speaker added after scheduling shows up")
    void r3CoSpeakerAddedAfterSchedulingShowsUp() throws Exception {
        long t = acceptedTalk("Grows later", "PRESENTATION", "Grace Hopper");
        schedule("R1", "S1", t);
        assertEquals(List.of("Grace Hopper"), jsonRows().get(0).get("speakers"));
        long co = speaker("Ada Lovelace");
        coSpeaker(t, co);
        assertEquals(List.of("Grace Hopper", "Ada Lovelace"), jsonRows().get(0).get("speakers"));
        assertEquals("Grace Hopper; Ada Lovelace", csvRecords(csv().body()).get(1).get(7));
    }

    @Test
    @DisplayName("R3: a co-speaker removed after scheduling disappears from the row")
    void r3CoSpeakerRemovedAfterSchedulingDisappears() throws Exception {
        long t = acceptedTalk("Shrinks later", "PRESENTATION", "Grace Hopper");
        long co = speaker("Ada Lovelace");
        coSpeaker(t, co);
        schedule("R1", "S1", t);
        assertEquals(List.of("Grace Hopper", "Ada Lovelace"), jsonRows().get(0).get("speakers"));
        HttpResponse<String> del = send("DELETE", "/talks/" + t + "/co-speakers/" + co, null);
        assertEquals(200, del.statusCode(), del.body());
        assertEquals(List.of("Grace Hopper"), jsonRows().get(0).get("speakers"));
        assertEquals("Grace Hopper", csvRecords(csv().body()).get(1).get(7));
    }

    // ---------------------------------------------------------------- R4

    @Test
    @DisplayName("R4: main speaker first, then the co-speakers in the order they were added")
    void r4SpeakerOrder() throws Exception {
        long main = speaker("Main Speaker");
        long t = talk(main, "Three people", "PRESENTATION");
        accept(t);
        // added in an order that is neither alphabetical nor by id
        long c1 = speaker("Zed Zebra");
        long c2 = speaker("Aaron Aardvark");
        coSpeaker(t, c1);
        coSpeaker(t, c2);
        schedule("R1", "S1", t);
        assertEquals(List.of("Main Speaker", "Zed Zebra", "Aaron Aardvark"), jsonRows().get(0).get("speakers"));
        assertEquals("Main Speaker; Zed Zebra; Aaron Aardvark", csvRecords(csv().body()).get(1).get(7));
    }

    @Test
    @DisplayName("R4: a co-speaker removed and added again goes last")
    void r4ReAddedCoSpeakerGoesLast() throws Exception {
        long main = speaker("Main Speaker");
        long t = talk(main, "Reordered", "PRESENTATION");
        accept(t);
        long c1 = speaker("First Co");
        long c2 = speaker("Second Co");
        coSpeaker(t, c1);
        coSpeaker(t, c2);
        assertEquals(200, send("DELETE", "/talks/" + t + "/co-speakers/" + c1, null).statusCode());
        coSpeaker(t, c1);
        schedule("R1", "S1", t);
        assertEquals(List.of("Main Speaker", "Second Co", "First Co"), jsonRows().get(0).get("speakers"));
    }

    @Test
    @DisplayName("R4: in the CSV the speakers are one field separated by a semicolon and a space")
    void r4CsvSpeakersField() throws Exception {
        long main = speaker("Grace Hopper");
        long t = talk(main, "Two names", "LIGHTNING_10");
        accept(t);
        coSpeaker(t, speaker("Ada Lovelace"));
        schedule("R1", "S1", t);
        String body = csv().body();
        assertEquals(HEADER + "\nS1,09:00,10:00,R1," + t + ",Two names,LIGHTNING_10,Grace Hopper; Ada Lovelace\n", body);
    }

    @Test
    @DisplayName("R4: a talk without co-speakers lists only the main speaker, as an array of one")
    void r4OnlyMainSpeaker() throws Exception {
        long t = acceptedTalk("Solo", "PRESENTATION", "Alan Turing");
        schedule("R1", "S1", t);
        assertEquals(List.of("Alan Turing"), jsonRows().get(0).get("speakers"));
        assertEquals("Alan Turing", csvRecords(csv().body()).get(1).get(7));
    }

    @Test
    @DisplayName("R4: a speaker name with a comma makes the CSV speakers field quoted")
    void r4SpeakerNameWithCommaIsQuoted() throws Exception {
        long t = acceptedTalk("Comma name", "PRESENTATION", "Hopper, Grace");
        schedule("R1", "S1", t);
        String body = csv().body();
        assertEquals(HEADER + "\nS1,09:00,10:00,R1," + t + ",Comma name,PRESENTATION,\"Hopper, Grace\"\n", body);
        assertEquals(List.of("Hopper, Grace"), jsonRows().get(0).get("speakers"));
    }

    // ---------------------------------------------------------------- R5

    @Test
    @DisplayName("R5: the CSV is served as text/csv in UTF-8 with status 200")
    void r5ContentType() throws Exception {
        long t = acceptedTalk("Typed", "PRESENTATION", "Ada Lovelace");
        schedule("R1", "S1", t);
        HttpResponse<String> res = csv();
        assertEquals(200, res.statusCode());
        String ct = res.headers().firstValue("Content-Type").orElse("").toLowerCase();
        assertTrue(ct.startsWith("text/csv"), ct);
        assertTrue(ct.replace(" ", "").contains("charset=utf-8"), ct);
    }

    @Test
    @DisplayName("R5: the CSV is text/csv even when the client asks for application/json")
    void r5TextCsvEvenForJsonClients() throws Exception {
        long t = acceptedTalk("Typed", "PRESENTATION", "Ada Lovelace");
        schedule("R1", "S1", t);
        for (String accept : List.of("application/json", "application/json, text/plain, */*", "*/*", "text/html")) {
            HttpResponse<String> res = get("/programme.csv", accept);
            assertEquals(200, res.statusCode(), "Accept: " + accept);
            assertTrue(res.headers().firstValue("Content-Type").orElse("").toLowerCase().startsWith("text/csv"),
                    "Accept: " + accept + " gave " + res.headers().firstValue("Content-Type"));
            assertTrue(res.body().startsWith(HEADER + "\n"), "Accept: " + accept);
        }
    }

    @Test
    @DisplayName("R5: the first line is exactly the header")
    void r5HeaderLine() throws Exception {
        long t = acceptedTalk("Header check", "PRESENTATION", "Ada Lovelace");
        schedule("R1", "S1", t);
        String first = csv().body().split("\n", -1)[0];
        assertEquals(HEADER, first);
    }

    @Test
    @DisplayName("R5: Unix line endings, every line ends in a newline, no blank line at the end")
    void r5LineEndings() throws Exception {
        schedule("R1", "S1", acceptedTalk("One", "PRESENTATION", "Ada Lovelace"));
        schedule("R2", "S1", acceptedTalk("Two", "PRESENTATION", "Grace Hopper"));
        schedule("R1", "S2", acceptedTalk("Three", "PRESENTATION", "Alan Turing"));
        String body = csv().body();
        assertFalse(body.contains("\r"), "no carriage returns");
        assertTrue(body.endsWith("\n"), "ends with a newline");
        assertFalse(body.endsWith("\n\n"), "no blank line at the end");
        assertFalse(body.contains("\n\n"), "no blank line anywhere");
        assertEquals(4, body.chars().filter(c -> c == '\n').count(), "header plus three rows, one newline each");
    }

    @Test
    @DisplayName("R5: a field with a comma and quotes is quoted and its quotes are doubled")
    void r5QuotingCommaAndQuotes() throws Exception {
        long t = acceptedTalk("Kotlin, \"really\" fast", "PRESENTATION", "Ada Lovelace");
        schedule("R1", "S1", t);
        String body = csv().body();
        assertEquals(HEADER + "\nS1,09:00,10:00,R1," + t + ",\"Kotlin, \"\"really\"\" fast\",PRESENTATION,Ada Lovelace\n", body);
        assertEquals("Kotlin, \"really\" fast", jsonRows().get(0).get("title"));
    }

    @Test
    @DisplayName("R5: a field with only a comma is quoted")
    void r5QuotingComma() throws Exception {
        long t = acceptedTalk("Hello, world", "PRESENTATION", "Ada Lovelace");
        schedule("R1", "S1", t);
        assertTrue(csv().body().contains(",\"Hello, world\","), csv().body());
    }

    @Test
    @DisplayName("R5: a field with only a double quote is quoted and the quote doubled")
    void r5QuotingQuoteOnly() throws Exception {
        long t = acceptedTalk("Say \"hi\"", "PRESENTATION", "Ada Lovelace");
        schedule("R1", "S1", t);
        assertTrue(csv().body().contains(",\"Say \"\"hi\"\"\","), csv().body());
    }

    @Test
    @DisplayName("R5: a field with a line break is quoted and the line break kept inside the quotes")
    void r5QuotingLineBreak() throws Exception {
        HttpResponse<String> created = send("POST", "/talks", "{\"title\":\"Line one\\nLine two\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":"
                + speaker("Ada Lovelace") + "}");
        assumeTrue(created.statusCode() == 201, "service refuses a title with a line break: " + created.statusCode());
        long t = ((Number) json(created.body()).asMap().get("id")).longValue();
        accept(t);
        schedule("R1", "S1", t);
        String body = csv().body();
        assertTrue(body.contains(",\"Line one\nLine two\","), body);
        List<List<String>> records = csvRecords(body);
        assertEquals(2, records.size());
        assertEquals("Line one\nLine two", records.get(1).get(5));
    }

    @Test
    @DisplayName("R5: fields are quoted only when they have to be: spaces, semicolons and apostrophes are written as they are")
    void r5NoNeedlessQuoting() throws Exception {
        long t = acceptedTalk("  Spaces; semicolons & it's fine  ", "PRESENTATION", "Ada Lovelace");
        schedule("R1", "S1", t);
        coSpeaker(t, speaker("Grace Hopper"));
        String body = csv().body();
        assertEquals(HEADER + "\nS1,09:00,10:00,R1," + t + ",  Spaces; semicolons & it's fine  ,PRESENTATION,Ada Lovelace; Grace Hopper\n", body);
    }

    @Test
    @DisplayName("R5: non-ASCII text is UTF-8, without a byte order mark")
    void r5Utf8() throws Exception {
        long t = acceptedTalk("Smørbrød på scenen", "PRESENTATION", "Åse Næss");
        schedule("R1", "S1", t);
        HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(uri("/programme.csv")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, res.statusCode());
        byte[] bytes = res.body();
        assertFalse(bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF, "BOM");
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertEquals(HEADER + "\nS1,09:00,10:00,R1," + t + ",Smørbrød på scenen,PRESENTATION,Åse Næss\n", text);
        assertEquals("Smørbrød på scenen", jsonRows().get(0).get("title"));
        assertEquals(List.of("Åse Næss"), jsonRows().get(0).get("speakers"));
    }

    @Test
    @DisplayName("R5: the whole CSV for a small programme is exactly as the print shop expects")
    void r5ExactCsv() throws Exception {
        long a = acceptedTalk("Opening", "LIGHTNING_10", "Grace Hopper");
        long b = acceptedTalk("Kotlin, \"really\" fast", "PRESENTATION", "Ada Lovelace");
        long c = acceptedTalk("Hands on", "WORKSHOP", "Alan Turing");
        coSpeaker(b, speakerIdOf(a));
        schedule("R2", "S4", c);
        schedule("R1", "S2", b);
        schedule("R3", "S1", a);
        String expected = HEADER + "\n"
                + "S1,09:00,10:00,R3," + a + ",Opening,LIGHTNING_10,Grace Hopper\n"
                + "S2,10:15,11:15,R1," + b + ",\"Kotlin, \"\"really\"\" fast\",PRESENTATION,Ada Lovelace; Grace Hopper\n"
                + "S4,13:30,15:30,R2," + c + ",Hands on,WORKSHOP,Alan Turing\n";
        assertEquals(expected, csv().body());
    }

    // ---------------------------------------------------------------- R6

    @Test
    @DisplayName("R6: an empty programme is an empty JSON list")
    void r6EmptyJson() throws Exception {
        HttpResponse<String> res = get("/programme", "application/json");
        assertEquals(200, res.statusCode());
        assertTrue(res.headers().firstValue("Content-Type").orElse("").toLowerCase().startsWith("application/json"));
        assertEquals(List.of(), json(res.body()).asList());
    }

    @Test
    @DisplayName("R6: an empty programme is a CSV with just the header and its newline")
    void r6EmptyCsv() throws Exception {
        HttpResponse<String> res = csv();
        assertEquals(200, res.statusCode());
        assertTrue(res.headers().firstValue("Content-Type").orElse("").toLowerCase().startsWith("text/csv"));
        assertEquals(HEADER + "\n", res.body());
    }

    @Test
    @DisplayName("R6: a programme that has been emptied again is empty in both views")
    void r6EmptiedAgain() throws Exception {
        schedule("R1", "S1", acceptedTalk("Here and gone", "PRESENTATION", "Ada Lovelace"));
        assertEquals(1, jsonRows().size());
        send("DELETE", "/programme/R1/S1", null);
        assertEquals(List.of(), jsonRows());
        assertEquals(HEADER + "\n", csv().body());
    }

    // ---------------------------------------------------------------- team decisions on the URL

    @Test
    @DisplayName("R1 (decision): GET /programme/ with a trailing slash is the same JSON list")
    void r1TrailingSlash() throws Exception {
        schedule("R1", "S1", acceptedTalk("Slash", "PRESENTATION", "Ada Lovelace"));
        HttpResponse<String> res = get("/programme/", "application/json");
        assertEquals(200, res.statusCode());
        assertEquals(json(get("/programme", "application/json").body()).asList(), json(res.body()).asList());
    }

    // ---------------------------------------------------------------- helpers: domain

    private final Map<Long, Long> speakerOfTalk = new java.util.concurrent.ConcurrentHashMap<>();

    private long speakerIdOf(long talkId) {
        return speakerOfTalk.get(talkId);
    }

    private long speaker(String name) throws Exception {
        HttpResponse<String> res = send("POST", "/speakers",
                "{\"name\":" + quote(name) + ",\"email\":\"s-" + UUID.randomUUID() + "@example.com\"}");
        assertEquals(201, res.statusCode(), res.body());
        return ((Number) json(res.body()).asMap().get("id")).longValue();
    }

    private long talk(long speakerId, String title, String format) throws Exception {
        HttpResponse<String> res = send("POST", "/talks",
                "{\"title\":" + quote(title) + ",\"format\":\"" + format + "\",\"level\":\"BEGINNER\",\"speakerId\":" + speakerId + "}");
        assertEquals(201, res.statusCode(), res.body());
        long id = ((Number) json(res.body()).asMap().get("id")).longValue();
        speakerOfTalk.put(id, speakerId);
        return id;
    }

    private long submittedTalk(String title, String format, String speakerName) throws Exception {
        return talk(speaker(speakerName), title, format);
    }

    private long acceptedTalk(String title, String format, String speakerName) throws Exception {
        long t = submittedTalk(title, format, speakerName);
        accept(t);
        return t;
    }

    private void accept(long talkId) throws Exception {
        for (int reviewer = 1; reviewer <= 2; reviewer++) {
            HttpResponse<String> rate = send("PUT", "/talks/" + talkId + "/ratings/" + reviewer, "{\"score\":5}");
            assertEquals(200, rate.statusCode(), rate.body());
        }
        HttpResponse<String> res = send("PUT", "/talks/" + talkId + "/status", "{\"status\":\"ACCEPTED\"}");
        assertEquals(200, res.statusCode(), res.body());
    }

    private void coSpeaker(long talkId, long speakerId) throws Exception {
        HttpResponse<String> res = send("PUT", "/talks/" + talkId + "/co-speakers/" + speakerId, null);
        assertEquals(200, res.statusCode(), res.body());
    }

    private void schedule(String room, String slot, long talkId) throws Exception {
        HttpResponse<String> res = send("PUT", "/programme/" + room + "/" + slot, "{\"talkId\":" + talkId + "}");
        assertEquals(200, res.statusCode(), "scheduling " + talkId + " in " + room + "/" + slot + ": " + res.body());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> jsonRows() throws Exception {
        HttpResponse<String> res = get("/programme", "application/json");
        assertEquals(200, res.statusCode(), res.body());
        return (List<Map<String, Object>>) (List<?>) json(res.body()).asList();
    }

    private HttpResponse<String> csv() throws Exception {
        return get("/programme.csv", "text/csv");
    }

    // ---------------------------------------------------------------- helpers: HTTP

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> send(String method, String path, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .method(method, jsonBody == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path, String accept) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).header("Accept", accept).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    // ---------------------------------------------------------------- helpers: CSV (RFC 4180) and JSON readers

    /** Parses CSV text into records. Fails if the text does not end with a newline. */
    private static List<List<String>> csvRecords(String text) {
        assertTrue(text.endsWith("\n"), "CSV must end with a newline");
        List<List<String>> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                fields.add(field.toString());
                field.setLength(0);
                records.add(fields);
                fields = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        assertFalse(inQuotes, "unterminated quote in CSV");
        return records;
    }

    private static Json json(String text) {
        Json parser = new Json(text);
        Object value = parser.value();
        parser.end();
        return new Json(value);
    }

    /** A minimal JSON reader: objects keep their field order, numbers are Long or Double. */
    private static final class Json {
        private final String s;
        private int i;
        private final Object parsed;

        Json(String s) {
            this.s = s;
            this.parsed = null;
        }

        Json(Object parsed) {
            this.s = "";
            this.parsed = parsed;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> asMap() {
            return (Map<String, Object>) parsed;
        }

        @SuppressWarnings("unchecked")
        List<Object> asList() {
            return (List<Object>) parsed;
        }

        void end() {
            ws();
            assertEquals(s.length(), i, "trailing characters in JSON: " + s);
        }

        Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    ws();
                    String k = string();
                    ws();
                    expect(':');
                    m.put(k, value());
                    ws();
                    if (s.charAt(i) == ',') {
                        i++;
                    } else {
                        expect('}');
                        return m;
                    }
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    l.add(value());
                    ws();
                    if (s.charAt(i) == ',') {
                        i++;
                    } else {
                        expect(']');
                        return l;
                    }
                }
            }
            if (c == '"') {
                return string();
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String n = s.substring(start, i);
            assertNotEquals("", n, "bad JSON at " + start + ": " + s);
            return n.matches("-?\\d+") ? (Object) Long.valueOf(n) : (Object) Double.valueOf(n);
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            i++;
            return sb.toString();
        }

        private void expect(char c) {
            assertEquals(c, s.charAt(i), "JSON: expected '" + c + "' at " + i + " in " + s);
            i++;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}
