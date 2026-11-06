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
 * Co-speakers (specs/co-speakers.md): up to two registered speakers besides the main one, in the order they
 * were added, also under concurrent requests. Runs the real app on a random port with a throwaway database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CoSpeakersTest {

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");
    private static final Pattern CO_SPEAKERS = Pattern.compile("\"coSpeakerIds\":\\[([^\\]]*)\\]");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void aNewTalkHasNoCoSpeakersEverywhereItIsShown() throws Exception {
        String main = newSpeaker();
        HttpResponse<String> created = send("POST", "/talks", talk(main));
        assertEquals(201, created.statusCode(), created.body());
        assertEquals("", coSpeakers(created.body()));
        String talk = id(created);

        assertEquals("", coSpeakers(send("GET", "/talks/" + talk, null).body()));
        assertTrue(send("GET", "/talks", null).body().contains("\"coSpeakerIds\":[]"));
        assertTrue(send("GET", "/speakers/" + main + "/talks", null).body().contains("\"coSpeakerIds\":[]"));
    }

    @Test
    void coSpeakersAreShownInTheOrderTheyWereAdded() throws Exception {
        String talk = newTalk(newSpeaker());
        String b = newSpeaker();
        String a = newSpeaker();

        HttpResponse<String> first = send("PUT", coSpeaker(talk, b), null);
        assertEquals(200, first.statusCode(), first.body());
        assertEquals(b, coSpeakers(first.body()));
        assertTrue(first.body().contains("\"id\":" + talk), first.body());

        HttpResponse<String> second = send("PUT", coSpeaker(talk, a), null);
        assertEquals(200, second.statusCode(), second.body());
        assertEquals(b + "," + a, coSpeakers(second.body()));

        assertEquals(b + "," + a, coSpeakers(send("GET", "/talks/" + talk, null).body()));
        assertEquals(b + "," + a, coSpeakers(send("GET", "/talks?sort=rating", null).body().split(
                "\"id\":" + talk + ",", 2)[1]));
    }

    @Test
    void addingSomeoneTwiceChangesNothingAlsoWhenTheTalkIsFull() throws Exception {
        String talk = newTalk(newSpeaker());
        String a = newSpeaker();
        String b = newSpeaker();
        send("PUT", coSpeaker(talk, a), null);

        HttpResponse<String> again = send("PUT", coSpeaker(talk, a), null);
        assertEquals(200, again.statusCode(), again.body());
        assertEquals(a, coSpeakers(again.body()));

        send("PUT", coSpeaker(talk, b), null);
        HttpResponse<String> whenFull = send("PUT", coSpeaker(talk, a), null);
        assertEquals(200, whenFull.statusCode(), whenFull.body());
        assertEquals(a + "," + b, coSpeakers(whenFull.body()));
    }

    @Test
    void aThirdCoSpeakerIsRejected() throws Exception {
        String talk = newTalk(newSpeaker());
        String a = newSpeaker();
        String b = newSpeaker();
        assertEquals(200, send("PUT", coSpeaker(talk, a), null).statusCode());
        assertEquals(200, send("PUT", coSpeaker(talk, b), null).statusCode());

        HttpResponse<String> third = send("PUT", coSpeaker(talk, newSpeaker()), null);
        assertEquals(409, third.statusCode(), third.body());
        assertEquals(a + "," + b, coSpeakers(send("GET", "/talks/" + talk, null).body()));
    }

    @Test
    void theMainSpeakerCannotCoSpeakTheirOwnTalk() throws Exception {
        String main = newSpeaker();
        String talk = newTalk(main);
        HttpResponse<String> res = send("PUT", coSpeaker(talk, main), null);
        assertEquals(400, res.statusCode(), res.body());
        assertEquals("", coSpeakers(send("GET", "/talks/" + talk, null).body()));
    }

    @Test
    void unknownTalksAndSpeakersAreNotFound() throws Exception {
        String talk = newTalk(newSpeaker());
        String speaker = newSpeaker();
        assertEquals(404, send("PUT", coSpeaker("999999", speaker), null).statusCode());
        assertEquals(404, send("PUT", coSpeaker(talk, "999999"), null).statusCode());
        assertEquals(404, send("DELETE", coSpeaker("999999", speaker), null).statusCode());
        assertEquals(404, send("DELETE", coSpeaker(talk, "999999"), null).statusCode());
        assertEquals(400, send("PUT", coSpeaker("abc", speaker), null).statusCode());
    }

    @Test
    void aCoSpeakerCanBeRemovedAndAddedAgainAtTheEnd() throws Exception {
        String talk = newTalk(newSpeaker());
        String a = newSpeaker();
        String b = newSpeaker();
        send("PUT", coSpeaker(talk, a), null);
        send("PUT", coSpeaker(talk, b), null);

        HttpResponse<String> removed = send("DELETE", coSpeaker(talk, a), null);
        assertEquals(200, removed.statusCode(), removed.body());
        assertEquals(b, coSpeakers(removed.body()));

        HttpResponse<String> again = send("PUT", coSpeaker(talk, a), null);
        assertEquals(200, again.statusCode(), again.body());
        assertEquals(b + "," + a, coSpeakers(again.body()));
    }

    @Test
    void removingSomeoneWhoIsNotACoSpeakerIsNotFoundAndChangesNothing() throws Exception {
        String main = newSpeaker();
        String talk = newTalk(main);
        String a = newSpeaker();
        String b = newSpeaker();
        send("PUT", coSpeaker(talk, a), null);
        send("PUT", coSpeaker(talk, b), null);

        // the harness's co-speakers/check-1: remove b, then remove b again
        assertEquals(200, send("DELETE", coSpeaker(talk, b), null).statusCode());
        HttpResponse<String> again = send("DELETE", coSpeaker(talk, b), null);
        assertEquals(404, again.statusCode(), again.body());
        assertEquals(404, send("DELETE", coSpeaker(talk, newSpeaker()), null).statusCode());
        assertEquals(404, send("DELETE", coSpeaker(talk, main), null).statusCode());
        assertEquals(a, coSpeakers(send("GET", "/talks/" + talk, null).body()));
    }


    @Test
    void coSpeakingNeitherShowsInTheOwnTalkListNorUsesUpTheLimit() throws Exception {
        String busy = newSpeaker();
        for (int i = 0; i < 2; i++) {
            assertEquals(200, send("PUT", coSpeaker(newTalk(newSpeaker()), busy), null).statusCode());
        }
        HttpResponse<String> own = send("GET", "/speakers/" + busy + "/talks", null);
        assertEquals("[]", own.body());
        assertEquals("[]", send("GET", "/talks?speakerId=" + busy, null).body());

        for (int i = 0; i < 3; i++) {
            newTalk(busy);
        }
        assertEquals(409, send("POST", "/talks", talk(busy)).statusCode());

        // at the limit of their own talks, they can still co-speak on someone else's
        assertEquals(200, send("PUT", coSpeaker(newTalk(newSpeaker()), busy), null).statusCode());
        assertEquals(3, count(send("GET", "/speakers/" + busy + "/talks", null).body()));
    }

    @Test
    void concurrentAdditionsNeverExceedTwoCoSpeakers() throws Exception {
        String talk = newTalk(newSpeaker());
        List<String> speakers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            speakers.add(newSpeaker());
        }
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (String s : speakers) {
            sent.add(sendAsync("PUT", coSpeaker(talk, s)));
            sent.add(sendAsync("PUT", coSpeaker(talk, s)));  // a double-click
        }
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            HttpResponse<String> res = f.join();
            assertTrue(res.statusCode() == 200 || res.statusCode() == 409, res.statusCode() + " " + res.body());
        }
        String[] ids = coSpeakers(send("GET", "/talks/" + talk, null).body()).split(",");
        assertEquals(2, ids.length);
        assertTrue(!ids[0].equals(ids[1]));
    }

    // ---------------------------------------------------------------- helpers

    private static String coSpeaker(String talk, String speaker) {
        return "/talks/" + talk + "/co-speakers/" + speaker;
    }

    private static String coSpeakers(String body) {
        Matcher m = CO_SPEAKERS.matcher(body);
        assertTrue(m.find(), body);
        return m.group(1);
    }

    private static String talk(String speaker) {
        return "{\"title\":\"Pairing on stage\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}";
    }

    private String newSpeaker() throws Exception {
        return id(send("POST", "/speakers",
                "{\"name\":\"Grace Hopper\",\"email\":\"grace-" + UUID.randomUUID() + "@example.com\"}"));
    }

    private String newTalk(String speaker) throws Exception {
        return id(send("POST", "/talks", talk(speaker)));
    }

    private static String id(HttpResponse<String> res) {
        assertEquals(201, res.statusCode(), res.body());
        Matcher m = ID.matcher(res.body());
        assertTrue(m.find(), res.body());
        return m.group(1);
    }

    private static int count(String body) {
        Matcher m = ID.matcher(body);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        return http.send(request(method, path, json), HttpResponse.BodyHandlers.ofString());
    }

    private CompletableFuture<HttpResponse<String>> sendAsync(String method, String path) {
        return http.sendAsync(request(method, path, null), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String method, String path, String json) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
    }
}
