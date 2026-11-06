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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The acceptance state machine (specs/acceptance.md, with the team's decisions at the bottom). Runs the real app on a
 * random port with a throwaway database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AcceptanceTest {

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");
    private static final Pattern STATUS = Pattern.compile("\"status\":\"([A-Z]+)\"");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void acceptingNeedsTwoDistinctReviewers() throws Exception {
        Talk t = newTalk();
        assertEquals(409, decide(t, "ACCEPTED").statusCode());
        rate(t, 1, 4);
        rate(t, 1, 5);  // the same reviewer again is still one reviewer
        assertEquals(409, decide(t, "ACCEPTED").statusCode());
        assertEquals("SUBMITTED", status(t));
        rate(t, 2, 3);
        HttpResponse<String> accepted = decide(t, "ACCEPTED");
        assertEquals(200, accepted.statusCode(), accepted.body());
        assertEquals("ACCEPTED", statusOf(accepted.body()));
        assertEquals("ACCEPTED", status(t));
    }

    @Test
    void rejectingAndWaitlistingNeedNoRatings() throws Exception {
        assertEquals(200, decide(newTalk(), "REJECTED").statusCode());
        assertEquals(200, decide(newTalk(), "WAITLISTED").statusCode());
    }

    @Test
    void aWaitlistedTalkCanStillBeDecided() throws Exception {
        Talk accepted = rated(newTalk());
        assertEquals(200, decide(accepted, "WAITLISTED").statusCode());
        assertEquals(200, decide(accepted, "ACCEPTED").statusCode());

        Talk rejected = newTalk();
        assertEquals(200, decide(rejected, "WAITLISTED").statusCode());
        assertEquals(409, decide(rejected, "WAITLISTED").statusCode());
        assertEquals(200, decide(rejected, "REJECTED").statusCode());
    }

    @Test
    void acceptedAndRejectedAreFinal() throws Exception {
        Talk accepted = rated(newTalk());
        assertEquals(200, decide(accepted, "ACCEPTED").statusCode());
        for (String s : new String[] {"REJECTED", "WAITLISTED", "ACCEPTED"}) {
            assertEquals(409, decide(accepted, s).statusCode(), s);
        }
        assertEquals("ACCEPTED", status(accepted));

        Talk rejected = rated(newTalk());
        assertEquals(200, decide(rejected, "REJECTED").statusCode());
        for (String s : new String[] {"ACCEPTED", "WAITLISTED", "REJECTED"}) {
            assertEquals(409, decide(rejected, s).statusCode(), s);
        }
        assertEquals("REJECTED", status(rejected));
    }

    @Test
    void onlyTheThreeDecisionsAreDecisions() throws Exception {
        Talk t = newTalk();
        for (String body : new String[] {"{\"status\":\"SUBMITTED\"}", "{\"status\":\"WITHDRAWN\"}",
                "{\"status\":\"accepted\"}", "{\"status\":null}", "{}", "{\"status\":1}"}) {
            assertEquals(400, send("PUT", "/talks/" + t.id + "/status", body).statusCode(), body);
        }
        assertEquals(400, send("PUT", "/talks/" + t.id + "/status", null).statusCode());
        assertEquals("SUBMITTED", status(t));
        assertEquals(404, send("PUT", "/talks/999999/status", "{\"status\":\"REJECTED\"}").statusCode());
    }

    @Test
    void theSpeakerCanWithdrawUnlessRejectedOrWithdrawn() throws Exception {
        Talk submitted = newTalk();
        HttpResponse<String> res = withdraw(submitted, submitted.speaker);
        assertEquals(200, res.statusCode(), res.body());
        assertEquals("WITHDRAWN", statusOf(res.body()));
        assertEquals(409, withdraw(submitted, submitted.speaker).statusCode());
        assertEquals(409, decide(submitted, "WAITLISTED").statusCode());

        Talk waitlisted = newTalk();
        decide(waitlisted, "WAITLISTED");
        assertEquals(200, withdraw(waitlisted, waitlisted.speaker).statusCode());

        Talk accepted = rated(newTalk());
        decide(accepted, "ACCEPTED");
        assertEquals(200, withdraw(accepted, accepted.speaker).statusCode());

        Talk rejected = newTalk();
        decide(rejected, "REJECTED");
        assertEquals(409, withdraw(rejected, rejected.speaker).statusCode());
        assertEquals("REJECTED", status(rejected));
    }

    @Test
    void nobodyElseCanWithdraw() throws Exception {
        Talk t = newTalk();
        Talk other = newTalk();
        assertEquals(403, withdraw(t, other.speaker).statusCode());
        assertEquals(400, send("POST", "/talks/" + t.id + "/withdraw", "{}").statusCode());
        assertEquals(400, send("POST", "/talks/" + t.id + "/withdraw", "{\"speakerId\":\"" + t.speaker + "\"}").statusCode());
        assertEquals(404, send("POST", "/talks/999999/withdraw", "{\"speakerId\":" + t.speaker + "}").statusCode());
        assertEquals("SUBMITTED", status(t));
    }

    @Test
    void concurrentConflictingDecisionsHaveOneWinner() throws Exception {
        for (int round = 0; round < 5; round++) {
            Talk t = rated(newTalk());
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String s = i % 3 == 0 ? "ACCEPTED" : i % 3 == 1 ? "REJECTED" : "WAITLISTED";
                sent.add(http.sendAsync(request("PUT", "/talks/" + t.id + "/status", "{\"status\":\"" + s + "\"}"),
                        HttpResponse.BodyHandlers.ofString()));
            }
            sent.add(http.sendAsync(request("POST", "/talks/" + t.id + "/withdraw", "{\"speakerId\":" + t.speaker + "}"),
                    HttpResponse.BodyHandlers.ofString()));
            List<String> wins = new ArrayList<>();
            for (CompletableFuture<HttpResponse<String>> f : sent) {
                HttpResponse<String> res = f.join();
                assertTrue(res.statusCode() == 200 || res.statusCode() == 409, res.statusCode() + " " + res.body());
                if (res.statusCode() == 200) {
                    wins.add(statusOf(res.body()));
                }
            }
            // Every success must be a legal step from the one before it, ending in the talk's current status.
            String now = status(t);
            assertTrue(!wins.isEmpty() && wins.size() <= 3, wins.toString());
            assertTrue(wins.contains(now), wins + " but now " + now);
            if (wins.size() > 1) {
                assertTrue(wins.contains("WAITLISTED") || now.equals("WITHDRAWN"), wins + " -> " + now);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static final class Talk {
        final String id;
        final String speaker;

        Talk(String id, String speaker) {
            this.id = id;
            this.speaker = speaker;
        }
    }

    private Talk newTalk() throws Exception {
        String speaker = id(send("POST", "/speakers",
                "{\"name\":\"Grace Hopper\",\"email\":\"grace-" + UUID.randomUUID() + "@example.com\"}"));
        String talk = id(send("POST", "/talks",
                "{\"title\":\"Nanoseconds\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}"));
        return new Talk(talk, speaker);
    }

    private Talk rated(Talk t) throws Exception {
        rate(t, 1, 4);
        rate(t, 2, 5);
        return t;
    }

    private void rate(Talk t, int reviewer, int score) throws Exception {
        HttpResponse<String> res = send("PUT", "/talks/" + t.id + "/ratings/" + reviewer, "{\"score\":" + score + "}");
        assertEquals(200, res.statusCode(), res.body());
    }

    private HttpResponse<String> decide(Talk t, String status) throws Exception {
        return send("PUT", "/talks/" + t.id + "/status", "{\"status\":\"" + status + "\"}");
    }

    private HttpResponse<String> withdraw(Talk t, String speaker) throws Exception {
        return send("POST", "/talks/" + t.id + "/withdraw", "{\"speakerId\":" + speaker + "}");
    }

    private String status(Talk t) throws Exception {
        HttpResponse<String> res = send("GET", "/talks/" + t.id, null);
        assertEquals(200, res.statusCode(), res.body());
        return statusOf(res.body());
    }

    private static String statusOf(String body) {
        Matcher m = STATUS.matcher(body);
        assertTrue(m.find(), body);
        return m.group(1);
    }

    private static String id(HttpResponse<String> res) {
        assertEquals(201, res.statusCode(), res.body());
        Matcher m = ID.matcher(res.body());
        assertTrue(m.find(), res.body());
        return m.group(1);
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        return http.send(request(method, path, json), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String method, String path, String json) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
    }
}
