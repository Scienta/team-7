package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Black-box acceptance tests for programme scheduling, written from specs/scheduling.md and the team's decisions in
 * workflow/decisions/scheduling.md. The programme is one global grid of 15 cells, so every test starts by emptying it.
 *
 * Requirements, numbered from the spec's "What we want":
 * R1 fixed venue, R2 put/look up/take out, R3 only accepted talks, R4 fit, R5 one talk per cell and once,
 * R6 nobody in two rooms, R7 rules are checked only on PUT, R8 concurrency.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SchedulingSpecTest {

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final String[] ROOMS = {"R1", "R2", "R3"};
    private static final String[] SLOTS = {"S1", "S2", "S3", "S4", "S5"};
    private static final String[][] TIMES = {
            {"09:00", "10:00"}, {"10:15", "11:15"}, {"11:30", "12:30"}, {"13:30", "15:30"}, {"15:45", "16:45"}};

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
                assertEquals(204, send("DELETE", cell(room, slot), null).statusCode());
            }
        }
    }

    // ---------------------------------------------------------------- R1: the venue

    @Test
    @DisplayName("R1: all 15 cells exist and are empty with the right start and end")
    void allCellsExistAndAreEmpty() throws Exception {
        for (String room : ROOMS) {
            for (int i = 0; i < SLOTS.length; i++) {
                HttpResponse<String> res = send("GET", cell(room, SLOTS[i]), null);
                assertEquals(200, res.statusCode(), room + SLOTS[i] + " " + res.body());
                assertEquals(room, field(res.body(), "room"));
                assertEquals(SLOTS[i], field(res.body(), "slot"));
                assertEquals(TIMES[i][0], field(res.body(), "start"));
                assertEquals(TIMES[i][1], field(res.body(), "end"));
                assertEquals("null", field(res.body(), "talkId"), res.body());
            }
        }
    }

    @Test
    @DisplayName("R1: an unknown or wrongly written room or slot is 404 for PUT, GET and DELETE")
    void unknownRoomOrSlotIs404() throws Exception {
        long talk = acceptedTalk("PRESENTATION");
        String[] bad = {"/programme/R4/S1", "/programme/R1/S6", "/programme/r1/S1", "/programme/R1/s1",
                "/programme/R0/S1", "/programme/R1/S0"};
        for (String path : bad) {
            assertEquals(404, send("PUT", path, "{\"talkId\":" + talk + "}").statusCode(), "PUT " + path);
            assertEquals(404, send("GET", path, null).statusCode(), "GET " + path);
            assertEquals(404, send("DELETE", path, null).statusCode(), "DELETE " + path);
        }
        // the talk was never placed anywhere
        assertEquals(200, send("PUT", cell("R1", "S1"), "{\"talkId\":" + talk + "}").statusCode());
    }

    // ---------------------------------------------------------------- R2: put, look up, take out

    @Test
    @DisplayName("R2: PUT returns the Entry and GET returns the same")
    void putReturnsEntryAndGetMatches() throws Exception {
        long talk = acceptedTalk("PRESENTATION");
        HttpResponse<String> put = send("PUT", cell("R2", "S4"), "{\"talkId\":" + talk + "}");
        assertEquals(200, put.statusCode(), put.body());
        assertEntry(put.body(), "R2", "S4", "13:30", "15:30", String.valueOf(talk));
        HttpResponse<String> get = send("GET", cell("R2", "S4"), null);
        assertEquals(200, get.statusCode(), get.body());
        assertEntry(get.body(), "R2", "S4", "13:30", "15:30", String.valueOf(talk));
    }

    @Test
    @DisplayName("R2: DELETE takes the talk out (204), the cell is empty again and the talk can go elsewhere")
    void deleteTakesTalkOutAndTalkCanMove() throws Exception {
        long talk = acceptedTalk("PRESENTATION");
        assertEquals(200, send("PUT", cell("R1", "S1"), "{\"talkId\":" + talk + "}").statusCode());
        assertEquals(204, send("DELETE", cell("R1", "S1"), null).statusCode());
        HttpResponse<String> get = send("GET", cell("R1", "S1"), null);
        assertEquals(200, get.statusCode());
        assertEquals("null", field(get.body(), "talkId"), get.body());
        HttpResponse<String> moved = send("PUT", cell("R3", "S2"), "{\"talkId\":" + talk + "}");
        assertEquals(200, moved.statusCode(), moved.body());
        assertEntry(moved.body(), "R3", "S2", "10:15", "11:15", String.valueOf(talk));
    }

    @Test
    @DisplayName("R2: DELETE on an empty cell is 204")
    void deleteOnEmptyCellIs204() throws Exception {
        assertEquals(204, send("DELETE", cell("R3", "S5"), null).statusCode());
        assertEquals(204, send("DELETE", cell("R3", "S5"), null).statusCode());
    }

    @Test
    @DisplayName("R2: a talk taken out frees its cell for another talk")
    void freedCellCanHoldAnotherTalk() throws Exception {
        long a = acceptedTalk("PRESENTATION");
        long b = acceptedTalk("LIGHTNING_20");
        assertEquals(200, send("PUT", cell("R1", "S3"), "{\"talkId\":" + a + "}").statusCode());
        assertEquals(204, send("DELETE", cell("R1", "S3"), null).statusCode());
        assertEquals(200, send("PUT", cell("R1", "S3"), "{\"talkId\":" + b + "}").statusCode());
        assertEquals(String.valueOf(b), field(send("GET", cell("R1", "S3"), null).body(), "talkId"));
    }

    // ---------------------------------------------------------------- R3: only accepted talks

    @Test
    @DisplayName("R3: a SUBMITTED talk is 409")
    void submittedTalkIs409() throws Exception {
        long talk = newTalk("PRESENTATION", newSpeaker());
        assertRejected(409, "R1", "S1", talk);
    }

    @Test
    @DisplayName("R3: a WAITLISTED talk is 409")
    void waitlistedTalkIs409() throws Exception {
        long talk = newTalk("PRESENTATION", newSpeaker());
        assertEquals(200, send("PUT", "/talks/" + talk + "/status", "{\"status\":\"WAITLISTED\"}").statusCode());
        assertRejected(409, "R1", "S1", talk);
    }

    @Test
    @DisplayName("R3: a REJECTED talk is 409")
    void rejectedTalkIs409() throws Exception {
        long talk = newTalk("PRESENTATION", newSpeaker());
        assertEquals(200, send("PUT", "/talks/" + talk + "/status", "{\"status\":\"REJECTED\"}").statusCode());
        assertRejected(409, "R1", "S1", talk);
    }

    @Test
    @DisplayName("R3: a talk withdrawn after acceptance is 409")
    void withdrawnTalkIs409() throws Exception {
        String speaker = newSpeaker();
        long talk = newTalk("PRESENTATION", speaker);
        accept(talk);
        HttpResponse<String> w = send("POST", "/talks/" + talk + "/withdraw", "{\"speakerId\":" + speaker + "}");
        assertTrue(w.statusCode() / 100 == 2, w.statusCode() + " " + w.body());
        assertRejected(409, "R1", "S1", talk);
    }

    @Test
    @DisplayName("R3: an unknown talkId is 404")
    void unknownTalkIs404() throws Exception {
        assertRejected(404, "R1", "S1", 987654321L);
    }

    @Test
    @DisplayName("R3: a missing body, a missing talkId or a non-integer talkId is 400")
    void malformedBodyIs400() throws Exception {
        String path = cell("R1", "S1");
        assertEquals(400, send("PUT", path, null).statusCode(), "no body");
        assertEquals(400, send("PUT", path, "{}").statusCode(), "no talkId");
        assertEquals(400, send("PUT", path, "{\"talkId\":\"abc\"}").statusCode(), "string talkId");
        assertEquals(400, send("PUT", path, "{\"talkId\":1.5}").statusCode(), "decimal talkId");
        assertEquals(400, send("PUT", path, "[1]").statusCode(), "not an object");
        HttpResponse<String> get = send("GET", path, null);
        assertEquals("null", field(get.body(), "talkId"), get.body());
    }

    // ---------------------------------------------------------------- R4: fit

    @Test
    @DisplayName("R4: a WORKSHOP in S1, S2, S3 or S5 is 409 and in S4 is 200")
    void workshopNeedsTheLongSlot() throws Exception {
        long talk = acceptedTalk("WORKSHOP");
        for (String slot : new String[] {"S1", "S2", "S3", "S5"}) {
            assertRejected(409, "R1", slot, talk);
            assertEquals("null", field(send("GET", cell("R1", slot), null).body(), "talkId"));
        }
        HttpResponse<String> ok = send("PUT", cell("R1", "S4"), "{\"talkId\":" + talk + "}");
        assertEquals(200, ok.statusCode(), ok.body());
        assertEntry(ok.body(), "R1", "S4", "13:30", "15:30", String.valueOf(talk));
    }

    @Test
    @DisplayName("R4: a LIGHTNING_10 or PRESENTATION fits S4 and S1")
    void otherFormatsFitAnywhere() throws Exception {
        for (String format : new String[] {"LIGHTNING_10", "PRESENTATION", "LIGHTNING_20"}) {
            for (String slot : new String[] {"S4", "S1"}) {
                long talk = acceptedTalk(format);
                HttpResponse<String> res = send("PUT", cell("R2", slot), "{\"talkId\":" + talk + "}");
                assertEquals(200, res.statusCode(), format + " " + slot + " " + res.body());
                assertEquals(204, send("DELETE", cell("R2", slot), null).statusCode());
            }
        }
    }

    // ---------------------------------------------------------------- R5: one talk per cell, a talk once

    @Test
    @DisplayName("R5: another talk into an occupied cell is 409 and the first talk stays")
    void occupiedCellIs409() throws Exception {
        long a = acceptedTalk("PRESENTATION");
        long b = acceptedTalk("PRESENTATION");
        assertEquals(200, send("PUT", cell("R1", "S1"), "{\"talkId\":" + a + "}").statusCode());
        assertRejected(409, "R1", "S1", b);
        assertEquals(String.valueOf(a), field(send("GET", cell("R1", "S1"), null).body(), "talkId"));
    }

    @Test
    @DisplayName("R5: a talk already on the programme is 409 in another cell")
    void talkAlreadyScheduledIs409InOtherCell() throws Exception {
        long talk = acceptedTalk("PRESENTATION");
        assertEquals(200, send("PUT", cell("R1", "S1"), "{\"talkId\":" + talk + "}").statusCode());
        assertRejected(409, "R2", "S2", talk);
        assertEquals("null", field(send("GET", cell("R2", "S2"), null).body(), "talkId"));
        assertEquals(String.valueOf(talk), field(send("GET", cell("R1", "S1"), null).body(), "talkId"));
    }

    @Test
    @DisplayName("R5: a talk already on the programme is 409 also when put again into the same cell")
    void talkAlreadyScheduledIs409InSameCell() throws Exception {
        long talk = acceptedTalk("PRESENTATION");
        assertEquals(200, send("PUT", cell("R1", "S1"), "{\"talkId\":" + talk + "}").statusCode());
        assertRejected(409, "R1", "S1", talk);
        assertEquals(String.valueOf(talk), field(send("GET", cell("R1", "S1"), null).body(), "talkId"));
    }

    // ---------------------------------------------------------------- R6: nobody in two rooms at once

    @Test
    @DisplayName("R6: two talks with the same main speaker can't share a slot")
    void sameMainSpeakerSameSlotIs409() throws Exception {
        String speaker = newSpeaker();
        long a = acceptedTalk("PRESENTATION", speaker);
        long b = acceptedTalk("PRESENTATION", speaker);
        assertEquals(200, send("PUT", cell("R1", "S2"), "{\"talkId\":" + a + "}").statusCode());
        assertRejected(409, "R2", "S2", b);
        assertEquals("null", field(send("GET", cell("R2", "S2"), null).body(), "talkId"));
    }

    @Test
    @DisplayName("R6: one speaker with two talks in different slots is fine")
    void sameSpeakerDifferentSlotsIsFine() throws Exception {
        String speaker = newSpeaker();
        long a = acceptedTalk("PRESENTATION", speaker);
        long b = acceptedTalk("PRESENTATION", speaker);
        assertEquals(200, send("PUT", cell("R1", "S2"), "{\"talkId\":" + a + "}").statusCode());
        assertEquals(200, send("PUT", cell("R1", "S3"), "{\"talkId\":" + b + "}").statusCode());
    }

    @Test
    @DisplayName("R6: a co-speaker of one talk who is the main speaker of another can't share a slot")
    void coSpeakerVsMainSpeakerIs409() throws Exception {
        String s1 = newSpeaker();
        String s2 = newSpeaker();
        long a = acceptedTalk("PRESENTATION", s1);
        long b = acceptedTalk("PRESENTATION", s2);
        addCoSpeaker(a, s2);
        assertEquals(200, send("PUT", cell("R1", "S1"), "{\"talkId\":" + a + "}").statusCode());
        assertRejected(409, "R2", "S1", b);
        // and the other way round, as long as the slot is the same
        assertEquals(204, send("DELETE", cell("R1", "S1"), null).statusCode());
        assertEquals(200, send("PUT", cell("R2", "S1"), "{\"talkId\":" + b + "}").statusCode());
        assertRejected(409, "R1", "S1", a);
    }

    @Test
    @DisplayName("R6: the same co-speaker on two talks can't share a slot")
    void coSpeakerVsCoSpeakerIs409() throws Exception {
        String shared = newSpeaker();
        long a = acceptedTalk("PRESENTATION");
        long b = acceptedTalk("PRESENTATION");
        addCoSpeaker(a, shared);
        addCoSpeaker(b, shared);
        assertEquals(200, send("PUT", cell("R1", "S5"), "{\"talkId\":" + a + "}").statusCode());
        assertRejected(409, "R3", "S5", b);
        // a different slot is fine
        assertEquals(200, send("PUT", cell("R3", "S1"), "{\"talkId\":" + b + "}").statusCode());
    }

    // ---------------------------------------------------------------- R7: rules only checked on PUT

    @Test
    @DisplayName("R7: withdrawing a scheduled talk leaves the programme as it is")
    void withdrawAfterSchedulingKeepsEntry() throws Exception {
        String speaker = newSpeaker();
        long talk = newTalk("PRESENTATION", speaker);
        accept(talk);
        assertEquals(200, send("PUT", cell("R1", "S1"), "{\"talkId\":" + talk + "}").statusCode());
        HttpResponse<String> w = send("POST", "/talks/" + talk + "/withdraw", "{\"speakerId\":" + speaker + "}");
        assertTrue(w.statusCode() / 100 == 2, w.statusCode() + " " + w.body());
        HttpResponse<String> get = send("GET", cell("R1", "S1"), null);
        assertEquals(200, get.statusCode());
        assertEntry(get.body(), "R1", "S1", "09:00", "10:00", String.valueOf(talk));
    }

    @Test
    @DisplayName("R7: adding a clashing co-speaker to a scheduled talk leaves the programme as it is")
    void clashingCoSpeakerAfterSchedulingKeepsEntry() throws Exception {
        String s1 = newSpeaker();
        String s2 = newSpeaker();
        long a = acceptedTalk("PRESENTATION", s1);
        long b = acceptedTalk("PRESENTATION", s2);
        assertEquals(200, send("PUT", cell("R1", "S1"), "{\"talkId\":" + a + "}").statusCode());
        assertEquals(200, send("PUT", cell("R2", "S1"), "{\"talkId\":" + b + "}").statusCode());
        addCoSpeaker(a, s2);
        assertEquals(String.valueOf(a), field(send("GET", cell("R1", "S1"), null).body(), "talkId"));
        assertEquals(String.valueOf(b), field(send("GET", cell("R2", "S1"), null).body(), "talkId"));
    }

    // ---------------------------------------------------------------- R8: concurrency

    @Test
    @DisplayName("R8: 10 different talks into one empty cell at once give exactly one 200")
    void tenTalksIntoOneCell() throws Exception {
        List<Long> talks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            talks.add(acceptedTalk("PRESENTATION"));
        }
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (long talk : talks) {
            sent.add(sendAsync("PUT", cell("R2", "S3"), "{\"talkId\":" + talk + "}"));
        }
        long winner = -1;
        int ok = 0;
        for (int i = 0; i < sent.size(); i++) {
            HttpResponse<String> res = sent.get(i).join();
            assertTrue(res.statusCode() == 200 || res.statusCode() == 409, res.statusCode() + " " + res.body());
            if (res.statusCode() == 200) {
                ok++;
                winner = talks.get(i);
            }
        }
        assertEquals(1, ok);
        assertEquals(String.valueOf(winner), field(send("GET", cell("R2", "S3"), null).body(), "talkId"));
    }

    @Test
    @DisplayName("R8: one talk into 5 different free cells at once gives exactly one 200")
    void oneTalkIntoFiveCells() throws Exception {
        long talk = acceptedTalk("PRESENTATION");
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (String slot : SLOTS) {
            sent.add(sendAsync("PUT", cell("R1", slot), "{\"talkId\":" + talk + "}"));
        }
        int ok = 0;
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            HttpResponse<String> res = f.join();
            assertTrue(res.statusCode() == 200 || res.statusCode() == 409, res.statusCode() + " " + res.body());
            if (res.statusCode() == 200) {
                ok++;
            }
        }
        assertEquals(1, ok);
        int filled = 0;
        for (String slot : SLOTS) {
            if (String.valueOf(talk).equals(field(send("GET", cell("R1", slot), null).body(), "talkId"))) {
                filled++;
            }
        }
        assertEquals(1, filled);
    }

    @Test
    @DisplayName("R8: 3 talks with one main speaker into R1, R2 and R3 of one slot at once give exactly one 200")
    void sameSpeakerThreeRoomsAtOnce() throws Exception {
        String speaker = newSpeaker();
        List<Long> talks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            talks.add(acceptedTalk("PRESENTATION", speaker));
        }
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            sent.add(sendAsync("PUT", cell(ROOMS[i], "S2"), "{\"talkId\":" + talks.get(i) + "}"));
        }
        int ok = 0;
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            HttpResponse<String> res = f.join();
            assertTrue(res.statusCode() == 200 || res.statusCode() == 409, res.statusCode() + " " + res.body());
            if (res.statusCode() == 200) {
                ok++;
            }
        }
        assertEquals(1, ok);
        int filled = 0;
        for (String room : ROOMS) {
            if (!"null".equals(field(send("GET", cell(room, "S2"), null).body(), "talkId"))) {
                filled++;
            }
        }
        assertEquals(1, filled);
    }

    // ---------------------------------------------------------------- helpers

    private void assertRejected(int status, String room, String slot, long talk) throws Exception {
        HttpResponse<String> res = send("PUT", cell(room, slot), "{\"talkId\":" + talk + "}");
        assertEquals(status, res.statusCode(), res.body());
    }

    private static void assertEntry(String body, String room, String slot, String start, String end, String talkId) {
        assertEquals(room, field(body, "room"), body);
        assertEquals(slot, field(body, "slot"), body);
        assertEquals(start, field(body, "start"), body);
        assertEquals(end, field(body, "end"), body);
        assertEquals(talkId, field(body, "talkId"), body);
    }

    /** The raw value of a top-level scalar field: strings without quotes, numbers as digits, or "null". */
    private static String field(String body, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(\"[^\"]*\"|null|-?\\d+)").matcher(body);
        assertTrue(m.find(), "no field " + name + " in " + body);
        String v = m.group(1);
        return v.startsWith("\"") ? v.substring(1, v.length() - 1) : v;
    }

    private static String cell(String room, String slot) {
        return "/programme/" + room + "/" + slot;
    }

    private String newSpeaker() throws Exception {
        HttpResponse<String> res = send("POST", "/speakers",
                "{\"name\":\"Grace Hopper\",\"email\":\"grace-" + UUID.randomUUID() + "@example.com\"}");
        assertEquals(201, res.statusCode(), res.body());
        return id(res.body());
    }

    private long newTalk(String format, String speaker) throws Exception {
        HttpResponse<String> res = send("POST", "/talks",
                "{\"title\":\"Talk " + UUID.randomUUID() + "\",\"format\":\"" + format
                        + "\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}");
        assertEquals(201, res.statusCode(), res.body());
        return Long.parseLong(id(res.body()));
    }

    private void accept(long talk) throws Exception {
        assertEquals(200, send("PUT", "/talks/" + talk + "/ratings/1", "{\"score\":4}").statusCode());
        assertEquals(200, send("PUT", "/talks/" + talk + "/ratings/2", "{\"score\":4}").statusCode());
        HttpResponse<String> res = send("PUT", "/talks/" + talk + "/status", "{\"status\":\"ACCEPTED\"}");
        assertEquals(200, res.statusCode(), res.body());
    }

    private long acceptedTalk(String format) throws Exception {
        return acceptedTalk(format, newSpeaker());
    }

    private long acceptedTalk(String format, String speaker) throws Exception {
        long talk = newTalk(format, speaker);
        accept(talk);
        return talk;
    }

    private void addCoSpeaker(long talk, String speaker) throws Exception {
        HttpResponse<String> res = send("PUT", "/talks/" + talk + "/co-speakers/" + speaker, null);
        assertTrue(res.statusCode() / 100 == 2, res.statusCode() + " " + res.body());
    }

    private static String id(String body) {
        Matcher m = ID.matcher(body);
        assertTrue(m.find(), body);
        return m.group(1);
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        return http.send(request(method, path, json), HttpResponse.BodyHandlers.ofString());
    }

    private CompletableFuture<HttpResponse<String>> sendAsync(String method, String path, String json) {
        return http.sendAsync(request(method, path, json), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String method, String path, String json) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
    }
}
