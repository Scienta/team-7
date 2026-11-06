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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Acceptance tests for the submission cap change request: the limit of 3 talks counts main-speaker and co-speaker
 * places, ignores rejected and withdrawn talks, and holds under concurrency. Black box over HTTP.
 *
 * R1: a speaker's 3 talks include the talks they co-speak on.
 * R2: rejected and withdrawn talks don't count; removing a co-speaker frees the place at once.
 * R3: a speaker at the limit can't submit or be added to a talk in the running; adding to a rejected or withdrawn
 *     talk is fine.
 * R4: the limit holds when submissions and invitations arrive together.
 * R5: nothing that exists is removed.
 * R6: a speaker's talk list shows only the talks they are main speaker on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SubmissionCapSpecTest {

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");
    private static final Pattern CO_SPEAKER_IDS = Pattern.compile("\"coSpeakerIds\":\\[([^\\]]*)\\]");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    // ---------------------------------------------------------------- R1

    @Test
    @DisplayName("R1: a speaker co-speaking on 3 talks can't submit a talk (409)")
    void r1_threeCoSpeakingPlacesBlockSubmission() throws Exception {
        String x = newSpeaker();
        coSpeakOnNewTalks(x, 3);
        assertEquals(409, submit(x).statusCode());
        assertEquals(0, ownTalks(x).size());
    }

    @Test
    @DisplayName("R1: a speaker with 2 own talks and 1 co-speaking place can't submit (409)")
    void r1_ownAndCoSpeakingAddUp() throws Exception {
        String x = newSpeaker();
        newTalk(x);
        newTalk(x);
        coSpeakOnNewTalks(x, 1);
        assertEquals(409, submit(x).statusCode());
        assertEquals(2, ownTalks(x).size());
    }

    @Test
    @DisplayName("R1: a speaker with 2 own talks can take 1 co-speaking place (200) but not a second (409)")
    void r1_twoOwnTalksStillAllowOneCoSpeakingPlace() throws Exception {
        String x = newSpeaker();
        newTalk(x);
        newTalk(x);
        String other = newTalk(newSpeaker());
        assertEquals(200, addCoSpeaker(other, x).statusCode());
        String another = newTalk(newSpeaker());
        assertEquals(409, addCoSpeaker(another, x).statusCode());
    }

    @Test
    @DisplayName("R1: a speaker with 3 own talks can't be added as co-speaker to a talk in the running (409)")
    void r1_threeOwnTalksBlockCoSpeaking() throws Exception {
        String x = newSpeaker();
        newTalk(x);
        newTalk(x);
        newTalk(x);
        String other = newTalk(newSpeaker());
        assertEquals(409, addCoSpeaker(other, x).statusCode());
        assertTrue(coSpeakerIds(other).isEmpty());
    }

    @Test
    @DisplayName("R1: a speaker co-speaking on 2 talks plus 1 own talk can't be added to a third talk (409)")
    void r1_coSpeakingAndOwnBlockCoSpeaking() throws Exception {
        String x = newSpeaker();
        coSpeakOnNewTalks(x, 2);
        newTalk(x);
        String other = newTalk(newSpeaker());
        assertEquals(409, addCoSpeaker(other, x).statusCode());
        assertTrue(coSpeakerIds(other).isEmpty());
    }

    // ---------------------------------------------------------------- R2

    @Test
    @DisplayName("R2: after one of 3 own talks is rejected the speaker can submit again")
    void r2_rejectedOwnTalkFreesPlace() throws Exception {
        String x = newSpeaker();
        String first = newTalk(x);
        newTalk(x);
        newTalk(x);
        assertEquals(409, submit(x).statusCode());
        assertEquals(200, reject(first).statusCode());
        assertEquals(201, submit(x).statusCode());
        assertEquals(409, submit(x).statusCode());
    }

    @Test
    @DisplayName("R2: after one of 3 own talks is withdrawn the speaker can submit again")
    void r2_withdrawnOwnTalkFreesPlace() throws Exception {
        String x = newSpeaker();
        String first = newTalk(x);
        newTalk(x);
        newTalk(x);
        assertEquals(409, submit(x).statusCode());
        HttpResponse<String> res = withdraw(first, x);
        assertTrue(res.statusCode() / 100 == 2, res.statusCode() + " " + res.body());
        assertEquals(201, submit(x).statusCode());
        assertEquals(409, submit(x).statusCode());
    }

    @Test
    @DisplayName("R2: after a talk the speaker co-speaks on is rejected the speaker can submit")
    void r2_rejectedCoSpeakingTalkFreesPlace() throws Exception {
        String x = newSpeaker();
        List<String> talks = coSpeakOnNewTalks(x, 3);
        assertEquals(409, submit(x).statusCode());
        assertEquals(200, reject(talks.get(0)).statusCode());
        assertEquals(201, submit(x).statusCode());
        assertEquals(409, submit(x).statusCode());
    }

    @Test
    @DisplayName("R2: after a talk the speaker co-speaks on is withdrawn the speaker can submit")
    void r2_withdrawnCoSpeakingTalkFreesPlace() throws Exception {
        String x = newSpeaker();
        String mainSpeaker = newSpeaker();
        String t1 = newTalk(mainSpeaker);
        assertEquals(200, addCoSpeaker(t1, x).statusCode());
        coSpeakOnNewTalks(x, 2);
        assertEquals(409, submit(x).statusCode());
        HttpResponse<String> res = withdraw(t1, mainSpeaker);
        assertTrue(res.statusCode() / 100 == 2, res.statusCode() + " " + res.body());
        assertEquals(201, submit(x).statusCode());
    }

    @Test
    @DisplayName("R2: removing the speaker as co-speaker frees the place immediately")
    void r2_removingCoSpeakerFreesPlace() throws Exception {
        String x = newSpeaker();
        List<String> talks = coSpeakOnNewTalks(x, 3);
        assertEquals(409, submit(x).statusCode());
        // the co-speakers spec: DELETE answers 200 with the Talk
        assertEquals(200, removeCoSpeaker(talks.get(1), x).statusCode());
        assertEquals(201, submit(x).statusCode());
    }

    @Test
    @DisplayName("R2: a freed place can be used for co-speaking on another talk straight away")
    void r2_freedPlaceUsableForCoSpeaking() throws Exception {
        String x = newSpeaker();
        String first = newTalk(x);
        newTalk(x);
        newTalk(x);
        String other = newTalk(newSpeaker());
        assertEquals(409, addCoSpeaker(other, x).statusCode());
        assertEquals(200, reject(first).statusCode());
        assertEquals(200, addCoSpeaker(other, x).statusCode());
        assertTrue(coSpeakerIds(other).contains(x));
    }

    @Test
    @DisplayName("R2: WAITLISTED and ACCEPTED talks still count")
    void r2_waitlistedAndAcceptedStillCount() throws Exception {
        String x = newSpeaker();
        String accepted = newTalk(x);
        String waitlisted = newTalk(x);
        newTalk(x);
        rate(accepted, 4, 4);
        assertEquals(200, setStatus(accepted, "ACCEPTED").statusCode());
        rate(waitlisted, 4, 4);
        assertEquals(200, setStatus(waitlisted, "WAITLISTED").statusCode());
        assertEquals(409, submit(x).statusCode());
        String other = newTalk(newSpeaker());
        assertEquals(409, addCoSpeaker(other, x).statusCode());
    }

    @Test
    @DisplayName("R2: ACCEPTED and WAITLISTED talks the speaker co-speaks on still count")
    void r2_acceptedCoSpeakingStillCounts() throws Exception {
        String x = newSpeaker();
        List<String> talks = coSpeakOnNewTalks(x, 3);
        rate(talks.get(0), 4, 4);
        assertEquals(200, setStatus(talks.get(0), "ACCEPTED").statusCode());
        rate(talks.get(1), 4, 4);
        assertEquals(200, setStatus(talks.get(1), "WAITLISTED").statusCode());
        assertEquals(409, submit(x).statusCode());
    }

    // ---------------------------------------------------------------- R3

    @Test
    @DisplayName("R3: at the limit, a new submission is 409")
    void r3_atLimitSubmissionIs409() throws Exception {
        String x = newSpeaker();
        newTalk(x);
        coSpeakOnNewTalks(x, 2);
        assertEquals(409, submit(x).statusCode());
    }

    @Test
    @DisplayName("R3: at the limit, adding the speaker to a REJECTED talk is 200")
    void r3_atLimitAddToRejectedTalkIs200() throws Exception {
        String x = newSpeaker();
        newTalk(x);
        newTalk(x);
        newTalk(x);
        String other = newTalk(newSpeaker());
        assertEquals(200, reject(other).statusCode());
        assertEquals(200, addCoSpeaker(other, x).statusCode());
        assertTrue(coSpeakerIds(other).contains(x));
        // the rejected talk doesn't count: still at the limit with the 3 own talks, still blocked
        assertEquals(409, submit(x).statusCode());
    }

    @Test
    @DisplayName("R3: at the limit, adding the speaker to a WITHDRAWN talk is 200")
    void r3_atLimitAddToWithdrawnTalkIs200() throws Exception {
        String x = newSpeaker();
        newTalk(x);
        newTalk(x);
        newTalk(x);
        String mainSpeaker = newSpeaker();
        String other = newTalk(mainSpeaker);
        HttpResponse<String> res = withdraw(other, mainSpeaker);
        assertTrue(res.statusCode() / 100 == 2, res.statusCode() + " " + res.body());
        assertEquals(200, addCoSpeaker(other, x).statusCode());
        assertTrue(coSpeakerIds(other).contains(x));
        assertEquals(409, submit(x).statusCode());
    }

    @Test
    @DisplayName("R3: at the limit, re-adding a speaker who already co-speaks on the talk is 200 and changes nothing")
    void r3_atLimitReAddingExistingCoSpeakerIs200() throws Exception {
        String x = newSpeaker();
        newTalk(x);
        List<String> talks = coSpeakOnNewTalks(x, 2);
        assertEquals(409, submit(x).statusCode());
        assertEquals(200, addCoSpeaker(talks.get(0), x).statusCode());
        assertEquals(List.of(x), coSpeakerIds(talks.get(0)));
        assertEquals(1, ownTalks(x).size());
        // still at the limit, nothing was freed or consumed
        assertEquals(409, submit(x).statusCode());
    }

    // ---------------------------------------------------------------- R4

    @Test
    @DisplayName("R4: with 1 own talk, 3 parallel submissions and 3 parallel co-speaker additions let exactly 2 through")
    void r4_mixedSubmissionsAndInvitationsHoldTheLimit() throws Exception {
        String x = newSpeaker();
        newTalk(x);
        List<String> others = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            others.add(newTalk(newSpeaker()));
        }
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            sent.add(http.sendAsync(submitRequest(x, "Parallel " + i), HttpResponse.BodyHandlers.ofString()));
            sent.add(http.sendAsync(request("PUT", "/talks/" + others.get(i) + "/co-speakers/" + x, null),
                    HttpResponse.BodyHandlers.ofString()));
        }
        int succeeded = 0;
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            HttpResponse<String> res = f.join();
            String method = res.request().method();
            int ok = method.equals("POST") ? 201 : 200;
            assertTrue(res.statusCode() == ok || res.statusCode() == 409, method + " " + res.statusCode() + " " + res.body());
            if (res.statusCode() == ok) {
                succeeded++;
            }
        }
        assertEquals(2, succeeded);
        int count = ownTalks(x).size();
        for (String other : others) {
            if (coSpeakerIds(other).contains(x)) {
                count++;
            }
        }
        assertEquals(3, count);
    }

    @Test
    @DisplayName("R4: 10 parallel submissions by a speaker who co-speaks on 1 talk create exactly 2")
    void r4_parallelSubmissionsWithCoSpeakingPlace() throws Exception {
        String x = newSpeaker();
        coSpeakOnNewTalks(x, 1);
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sent.add(http.sendAsync(submitRequest(x, "Parallel " + i), HttpResponse.BodyHandlers.ofString()));
        }
        int created = 0;
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            HttpResponse<String> res = f.join();
            assertTrue(res.statusCode() == 201 || res.statusCode() == 409, res.statusCode() + " " + res.body());
            if (res.statusCode() == 201) {
                created++;
            }
        }
        assertEquals(2, created);
        assertEquals(2, ownTalks(x).size());
    }

    // ---------------------------------------------------------------- R5

    @Test
    @DisplayName("R5: nothing is removed when the limit is reached or exceeded by refused attempts")
    void r5_nothingIsRemovedAtTheLimit() throws Exception {
        String x = newSpeaker();
        List<String> coTalks = coSpeakOnNewTalks(x, 2);
        String own = newTalk(x);
        assertEquals(409, submit(x).statusCode());
        String other = newTalk(newSpeaker());
        assertEquals(409, addCoSpeaker(other, x).statusCode());
        assertEquals(List.of(own), ownTalks(x));
        for (String t : coTalks) {
            assertEquals(List.of(x), coSpeakerIds(t));
        }
    }

    @Test
    @DisplayName("R5: a rejected talk stays on the list and is kept while the speaker fills the freed place")
    void r5_rejectedTalkIsKeptWhenPlaceIsReused() throws Exception {
        String x = newSpeaker();
        String a = newTalk(x);
        String b = newTalk(x);
        String c = newTalk(x);
        assertEquals(200, reject(a).statusCode());
        String d = newTalk(newSpeaker());
        assertEquals(200, addCoSpeaker(d, x).statusCode());
        // x now holds 4 talks, 3 of which count; nothing was removed
        List<String> own = ownTalks(x);
        assertEquals(3, own.size());
        assertTrue(own.containsAll(List.of(a, b, c)));
        assertEquals(List.of(x), coSpeakerIds(d));
        assertEquals(409, submit(x).statusCode());
    }

    // ---------------------------------------------------------------- R6

    @Test
    @DisplayName("R6: the speaker's own talk list shows only talks where they are the main speaker")
    void r6_listShowsOnlyMainSpeakerTalks() throws Exception {
        String x = newSpeaker();
        String own = newTalk(x);
        List<String> coTalks = coSpeakOnNewTalks(x, 2);
        List<String> listed = ownTalks(x);
        assertEquals(List.of(own), listed);
        for (String t : coTalks) {
            assertTrue(!listed.contains(t));
        }
    }

    @Test
    @DisplayName("R6: GET /talks?speakerId= shows only talks where the speaker is the main speaker")
    void r6_filteredTalkListShowsOnlyMainSpeakerTalks() throws Exception {
        String x = newSpeaker();
        String own = newTalk(x);
        coSpeakOnNewTalks(x, 2);
        HttpResponse<String> res = http.send(request("GET", "/talks?speakerId=" + x, null),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        assertEquals(List.of(own), ids(res.body()));
    }

    // ---------------------------------------------------------------- helpers

    private String newSpeaker() throws Exception {
        HttpResponse<String> res = http.send(request("POST", "/speakers",
                "{\"name\":\"Grace Hopper\",\"email\":\"grace-" + UUID.randomUUID() + "@example.com\"}"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, res.statusCode(), res.body());
        return firstId(res.body());
    }

    private HttpResponse<String> submit(String speaker) throws Exception {
        return http.send(submitRequest(speaker, "Talk " + UUID.randomUUID()), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest submitRequest(String speaker, String title) {
        return request("POST", "/talks",
                "{\"title\":\"" + title + "\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}");
    }

    /** Submits a talk that must be accepted by the service, and returns its id. */
    private String newTalk(String speaker) throws Exception {
        HttpResponse<String> res = submit(speaker);
        assertEquals(201, res.statusCode(), res.body());
        return firstId(res.body());
    }

    /** Puts the speaker as co-speaker on n new talks, each with a different new main speaker. */
    private List<String> coSpeakOnNewTalks(String speaker, int n) throws Exception {
        List<String> talks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String talk = newTalk(newSpeaker());
            HttpResponse<String> res = addCoSpeaker(talk, speaker);
            assertEquals(200, res.statusCode(), res.body());
            talks.add(talk);
        }
        return talks;
    }

    private HttpResponse<String> addCoSpeaker(String talk, String speaker) throws Exception {
        return http.send(request("PUT", "/talks/" + talk + "/co-speakers/" + speaker, null),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> removeCoSpeaker(String talk, String speaker) throws Exception {
        return http.send(request("DELETE", "/talks/" + talk + "/co-speakers/" + speaker, null),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> reject(String talk) throws Exception {
        return setStatus(talk, "REJECTED");
    }

    private HttpResponse<String> setStatus(String talk, String status) throws Exception {
        return http.send(request("PUT", "/talks/" + talk + "/status", "{\"status\":\"" + status + "\"}"),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> withdraw(String talk, String mainSpeaker) throws Exception {
        return http.send(request("POST", "/talks/" + talk + "/withdraw", "{\"speakerId\":" + mainSpeaker + "}"),
                HttpResponse.BodyHandlers.ofString());
    }

    private void rate(String talk, int first, int second) throws Exception {
        assertEquals(200, http.send(request("PUT", "/talks/" + talk + "/ratings/1", "{\"score\":" + first + "}"),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(200, http.send(request("PUT", "/talks/" + talk + "/ratings/2", "{\"score\":" + second + "}"),
                HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    private List<String> ownTalks(String speaker) throws Exception {
        HttpResponse<String> res = http.send(request("GET", "/speakers/" + speaker + "/talks", null),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        return ids(res.body());
    }

    private List<String> coSpeakerIds(String talk) throws Exception {
        HttpResponse<String> res = http.send(request("GET", "/talks/" + talk, null), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        Matcher m = CO_SPEAKER_IDS.matcher(res.body());
        assertTrue(m.find(), res.body());
        List<String> result = new ArrayList<>();
        for (String part : m.group(1).split(",")) {
            String id = part.trim();
            if (!id.isEmpty()) {
                result.add(id);
            }
        }
        return result;
    }

    private static String firstId(String body) {
        Matcher m = ID.matcher(body);
        assertTrue(m.find(), body);
        return m.group(1);
    }

    private static List<String> ids(String body) {
        Matcher m = ID.matcher(body);
        List<String> result = new ArrayList<>();
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }

    private HttpRequest request(String method, String path, String json) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
    }
}
