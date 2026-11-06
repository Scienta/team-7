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
 * Black-box tests for workshop registration with capacity, from specs/workshop-registration.md and
 * workflow/decisions/workshop-registration.md.
 *
 * <pre>
 * R1: name and email are required
 * R2: only accepted workshops take registrations; registrations survive a later withdrawal
 * R3: at most 20 attendees per workshop, also under a rush
 * R4: one registration per email (case-insensitive) per workshop; the same person can join several workshops
 * R5: the list shows registrations in the order they were made
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkshopRegistrationSpecTest {

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");
    private static final Pattern EMAIL = Pattern.compile("\"email\":\"([^\"]*)\"");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    // ---- R1: name and email are required ----

    @Test
    @DisplayName("R1: a valid registration gets 201 with id, workshopId, name and email")
    void r1ValidRegistration() throws Exception {
        String w = acceptedWorkshop();
        HttpResponse<String> res = register(w, "Ada Lovelace", "ada-" + UUID.randomUUID() + "@example.com");
        assertEquals(201, res.statusCode(), res.body());
        assertTrue(ID.matcher(res.body()).find(), res.body());
        assertTrue(res.body().contains("\"workshopId\":" + w), res.body());
        assertTrue(res.body().contains("\"name\":\"Ada Lovelace\""), res.body());
    }

    @Test
    @DisplayName("R1: a missing name gets 400")
    void r1MissingName() throws Exception {
        String w = acceptedWorkshop();
        assertEquals(400, post(w, "{\"email\":\"a@example.com\"}").statusCode());
        assertEquals(0, listed(w).size());
    }

    @Test
    @DisplayName("R1: a blank name gets 400")
    void r1BlankName() throws Exception {
        String w = acceptedWorkshop();
        assertEquals(400, post(w, "{\"name\":\"\",\"email\":\"a@example.com\"}").statusCode());
        assertEquals(400, post(w, "{\"name\":\"   \",\"email\":\"a@example.com\"}").statusCode());
        assertEquals(0, listed(w).size());
    }

    @Test
    @DisplayName("R1: a name that is not a string gets 400")
    void r1NameNotString() throws Exception {
        String w = acceptedWorkshop();
        assertEquals(400, post(w, "{\"name\":42,\"email\":\"a@example.com\"}").statusCode());
    }

    @Test
    @DisplayName("R1: a missing email gets 400")
    void r1MissingEmail() throws Exception {
        String w = acceptedWorkshop();
        assertEquals(400, post(w, "{\"name\":\"Ada\"}").statusCode());
        assertEquals(0, listed(w).size());
    }

    @Test
    @DisplayName("R1: an invalid email gets 400")
    void r1InvalidEmail() throws Exception {
        String w = acceptedWorkshop();
        for (String bad : new String[] {"", "not-an-email", "ada@", "@example.com", "ada@example", "ada example@x.com"}) {
            assertEquals(400, post(w, "{\"name\":\"Ada\",\"email\":\"" + bad + "\"}").statusCode(), "email: " + bad);
        }
        assertEquals(400, post(w, "{\"name\":\"Ada\",\"email\":42}").statusCode());
        assertEquals(0, listed(w).size());
    }

    @Test
    @DisplayName("R1: a missing or non-object body gets 400")
    void r1MissingBody() throws Exception {
        String w = acceptedWorkshop();
        assertEquals(400, http.send(request("POST", "/workshops/" + w + "/registrations", null),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(400, post(w, "[]").statusCode());
        assertEquals(400, post(w, "\"text\"").statusCode());
        assertEquals(400, post(w, "not json").statusCode());
        assertEquals(0, listed(w).size());
    }

    @Test
    @DisplayName("R1: name and email are returned exactly as typed")
    void r1EchoedAsTyped() throws Exception {
        String w = acceptedWorkshop();
        String email = "ADA." + UUID.randomUUID().toString().substring(0, 8) + "@Example.COM";
        HttpResponse<String> res = register(w, " Ada  Lovelace ", email);
        assertEquals(201, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"email\":\"" + email + "\""), res.body());
        assertTrue(res.body().contains("\"name\":\" Ada  Lovelace \""), res.body());
        HttpResponse<String> list = get(w);
        assertTrue(list.body().contains("\"email\":\"" + email + "\""), list.body());
        assertTrue(list.body().contains("\"name\":\" Ada  Lovelace \""), list.body());
    }

    // ---- R2: only accepted workshops ----

    @Test
    @DisplayName("R2: an accepted workshop takes registrations")
    void r2AcceptedWorkshop() throws Exception {
        String w = acceptedWorkshop();
        assertEquals(201, register(w, "Ada", uniqueEmail()).statusCode());
    }

    @Test
    @DisplayName("R2: a SUBMITTED workshop gets 409")
    void r2Submitted() throws Exception {
        String w = workshop(newSpeaker()).talkId;
        assertEquals(409, register(w, "Ada", uniqueEmail()).statusCode());
        assertEquals(0, listed(w).size());
    }

    @Test
    @DisplayName("R2: a WAITLISTED workshop gets 409")
    void r2Waitlisted() throws Exception {
        Workshop ws = workshop(newSpeaker());
        rate(ws.talkId);
        assertEquals(200, setStatus(ws.talkId, "WAITLISTED").statusCode());
        assertEquals(409, register(ws.talkId, "Ada", uniqueEmail()).statusCode());
        assertEquals(0, listed(ws.talkId).size());
    }

    @Test
    @DisplayName("R2: a REJECTED workshop gets 409")
    void r2Rejected() throws Exception {
        Workshop ws = workshop(newSpeaker());
        rate(ws.talkId);
        assertEquals(200, setStatus(ws.talkId, "REJECTED").statusCode());
        assertEquals(409, register(ws.talkId, "Ada", uniqueEmail()).statusCode());
        assertEquals(0, listed(ws.talkId).size());
    }

    @Test
    @DisplayName("R2: a workshop withdrawn after acceptance gets 409")
    void r2WithdrawnAfterAccepted() throws Exception {
        Workshop ws = acceptedWorkshopFull();
        assertEquals(201, register(ws.talkId, "Ada", uniqueEmail()).statusCode());
        withdraw(ws);
        assertEquals(409, register(ws.talkId, "Grace", uniqueEmail()).statusCode());
    }

    @Test
    @DisplayName("R2: a workshop withdrawn after people registered still lists them")
    void r2WithdrawnKeepsList() throws Exception {
        Workshop ws = acceptedWorkshopFull();
        String e1 = uniqueEmail();
        String e2 = uniqueEmail();
        assertEquals(201, register(ws.talkId, "Ada", e1).statusCode());
        assertEquals(201, register(ws.talkId, "Grace", e2).statusCode());
        withdraw(ws);
        HttpResponse<String> list = get(ws.talkId);
        assertEquals(200, list.statusCode(), list.body());
        assertEquals(List.of(e1, e2), emails(list.body()));
    }

    @Test
    @DisplayName("R2: a talk that is not a workshop gets 404 on POST and GET")
    void r2NonWorkshopTalk() throws Exception {
        String speaker = newSpeaker();
        HttpResponse<String> talk = http.send(request("POST", "/talks",
                "{\"title\":\"Not a workshop\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, talk.statusCode(), talk.body());
        String id = idOf(talk.body());
        assertEquals(404, register(id, "Ada", uniqueEmail()).statusCode());
        assertEquals(404, get(id).statusCode());
    }

    @Test
    @DisplayName("R2: an unknown talk id gets 404 on POST and GET")
    void r2UnknownId() throws Exception {
        assertEquals(404, register("999999", "Ada", uniqueEmail()).statusCode());
        assertEquals(404, get("999999").statusCode());
    }

    @Test
    @DisplayName("R2: an unknown or non-workshop id is 404 even when the body is invalid")
    void r2NotFoundBeforeValidation() throws Exception {
        assertEquals(404, post("999999", "{}").statusCode());
    }

    @Test
    @DisplayName("R2: validation (400) is checked before workshop state (409)")
    void r2ValidationBeforeState() throws Exception {
        String w = workshop(newSpeaker()).talkId;
        assertEquals(400, post(w, "{}").statusCode());
    }

    // ---- R3: capacity ----

    @Test
    @DisplayName("R3: the 21st registration gets 409 and the list stays at 20")
    void r3TwentyOneth() throws Exception {
        String w = acceptedWorkshop();
        for (int i = 0; i < 20; i++) {
            assertEquals(201, register(w, "Attendee " + i, uniqueEmail()).statusCode(), "registration " + i);
        }
        assertEquals(409, register(w, "Attendee 21", uniqueEmail()).statusCode());
        assertEquals(20, listed(w).size());
    }

    @Test
    @DisplayName("R3: a rush of 40 parallel registrations gives exactly 20 x 201, the rest 409, and 20 listed")
    void r3ConcurrentRush() throws Exception {
        String w = acceptedWorkshop();
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            sent.add(http.sendAsync(request("POST", "/workshops/" + w + "/registrations",
                    "{\"name\":\"Attendee " + i + "\",\"email\":\"rush-" + i + "-" + UUID.randomUUID() + "@example.com\"}"),
                    HttpResponse.BodyHandlers.ofString()));
        }
        int created = 0;
        int full = 0;
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            HttpResponse<String> res = f.join();
            assertTrue(res.statusCode() == 201 || res.statusCode() == 409, res.statusCode() + " " + res.body());
            if (res.statusCode() == 201) {
                created++;
            } else {
                full++;
            }
        }
        assertEquals(20, created);
        assertEquals(20, full);
        assertEquals(20, listed(w).size());
    }

    @Test
    @DisplayName("R3: the capacity is per workshop, so a full workshop does not block another")
    void r3CapacityPerWorkshop() throws Exception {
        String a = acceptedWorkshop();
        String b = acceptedWorkshop();
        for (int i = 0; i < 20; i++) {
            assertEquals(201, register(a, "Attendee " + i, uniqueEmail()).statusCode());
        }
        assertEquals(409, register(a, "Late", uniqueEmail()).statusCode());
        assertEquals(201, register(b, "Other", uniqueEmail()).statusCode());
    }

    // ---- R4: one registration per email per workshop ----

    @Test
    @DisplayName("R4: the same email twice for one workshop gets 409")
    void r4DuplicateEmail() throws Exception {
        String w = acceptedWorkshop();
        String email = uniqueEmail();
        assertEquals(201, register(w, "Ada", email).statusCode());
        assertEquals(409, register(w, "Ada again", email).statusCode());
        assertEquals(1, listed(w).size());
    }

    @Test
    @DisplayName("R4: emails differing only in case are the same person (409)")
    void r4CaseInsensitiveDuplicate() throws Exception {
        String w = acceptedWorkshop();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        assertEquals(201, register(w, "Ada", "ada-" + suffix + "@example.com").statusCode());
        assertEquals(409, register(w, "Ada", "ADA-" + suffix + "@Example.com").statusCode());
        assertEquals(1, listed(w).size());
    }

    @Test
    @DisplayName("R4: concurrent mixed-case duplicates give exactly one 201")
    void r4ConcurrentMixedCaseDuplicates() throws Exception {
        String w = acceptedWorkshop();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String[] variants = {"ada-" + suffix + "@example.com", "ADA-" + suffix + "@EXAMPLE.COM",
                "Ada-" + suffix + "@Example.com", "aDa-" + suffix + "@example.COM"};
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            sent.add(http.sendAsync(request("POST", "/workshops/" + w + "/registrations",
                    "{\"name\":\"Ada " + i + "\",\"email\":\"" + variants[i % variants.length] + "\"}"),
                    HttpResponse.BodyHandlers.ofString()));
        }
        int created = 0;
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            HttpResponse<String> res = f.join();
            assertTrue(res.statusCode() == 201 || res.statusCode() == 409, res.statusCode() + " " + res.body());
            if (res.statusCode() == 201) {
                created++;
            }
        }
        assertEquals(1, created);
        assertEquals(1, listed(w).size());
    }

    @Test
    @DisplayName("R4: the same email can register for two different workshops")
    void r4SameEmailTwoWorkshops() throws Exception {
        String a = acceptedWorkshop();
        String b = acceptedWorkshop();
        String email = uniqueEmail();
        assertEquals(201, register(a, "Ada", email).statusCode());
        assertEquals(201, register(b, "Ada", email.toUpperCase()).statusCode());
        assertEquals(1, listed(a).size());
        assertEquals(1, listed(b).size());
    }

    @Test
    @DisplayName("R4: a rejected duplicate changes nothing on the list")
    void r4DuplicateLeavesListUnchanged() throws Exception {
        String w = acceptedWorkshop();
        String email = uniqueEmail();
        HttpResponse<String> first = register(w, "Ada", email);
        assertEquals(201, first.statusCode());
        assertEquals(409, register(w, "Impostor", email).statusCode());
        HttpResponse<String> list = get(w);
        assertTrue(list.body().contains("\"name\":\"Ada\""), list.body());
        assertTrue(!list.body().contains("Impostor"), list.body());
    }

    // ---- R5: the list, in registration order ----

    @Test
    @DisplayName("R5: GET on a workshop with no registrations returns an empty list")
    void r5EmptyList() throws Exception {
        String w = acceptedWorkshop();
        HttpResponse<String> list = get(w);
        assertEquals(200, list.statusCode(), list.body());
        assertEquals("[]", list.body().replaceAll("\\s", ""));
    }

    @Test
    @DisplayName("R5: GET returns registrations in the order they were made")
    void r5OrderOfRegistration() throws Exception {
        String w = acceptedWorkshop();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            // Emails chosen so that alphabetical order differs from registration order.
            String email = "p" + (8 - i) + "-" + UUID.randomUUID() + "@example.com";
            expected.add(email);
            assertEquals(201, register(w, "Attendee " + i, email).statusCode());
        }
        HttpResponse<String> list = get(w);
        assertEquals(200, list.statusCode(), list.body());
        assertEquals(expected, emails(list.body()));
    }

    @Test
    @DisplayName("R5: GET lists only the registrations of that workshop")
    void r5OnlyThisWorkshop() throws Exception {
        String a = acceptedWorkshop();
        String b = acceptedWorkshop();
        String ea = uniqueEmail();
        String eb = uniqueEmail();
        assertEquals(201, register(a, "A", ea).statusCode());
        assertEquals(201, register(b, "B", eb).statusCode());
        assertEquals(List.of(ea), emails(get(a).body()));
        assertEquals(List.of(eb), emails(get(b).body()));
    }

    // ---- helpers ----

    private record Workshop(String talkId, String speakerId) {
    }

    private String acceptedWorkshop() throws Exception {
        return acceptedWorkshopFull().talkId;
    }

    private Workshop acceptedWorkshopFull() throws Exception {
        Workshop ws = workshop(newSpeaker());
        rate(ws.talkId);
        HttpResponse<String> res = setStatus(ws.talkId, "ACCEPTED");
        assertEquals(200, res.statusCode(), res.body());
        return ws;
    }

    private Workshop workshop(String speakerId) throws Exception {
        HttpResponse<String> res = http.send(request("POST", "/talks",
                "{\"title\":\"Workshop " + UUID.randomUUID() + "\",\"format\":\"WORKSHOP\",\"level\":\"BEGINNER\",\"speakerId\":"
                        + speakerId + "}"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, res.statusCode(), res.body());
        return new Workshop(idOf(res.body()), speakerId);
    }

    private void rate(String talkId) throws Exception {
        for (int reviewer = 1; reviewer <= 2; reviewer++) {
            HttpResponse<String> res = http.send(request("PUT", "/talks/" + talkId + "/ratings/" + reviewer, "{\"score\":4}"),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(res.statusCode() / 100 == 2, res.statusCode() + " " + res.body());
        }
    }

    private HttpResponse<String> setStatus(String talkId, String status) throws Exception {
        return http.send(request("PUT", "/talks/" + talkId + "/status", "{\"status\":\"" + status + "\"}"),
                HttpResponse.BodyHandlers.ofString());
    }

    private void withdraw(Workshop ws) throws Exception {
        HttpResponse<String> res = http.send(request("POST", "/talks/" + ws.talkId + "/withdraw",
                "{\"speakerId\":" + ws.speakerId + "}"), HttpResponse.BodyHandlers.ofString());
        assertTrue(res.statusCode() / 100 == 2, res.statusCode() + " " + res.body());
    }

    private String newSpeaker() throws Exception {
        HttpResponse<String> res = http.send(request("POST", "/speakers",
                "{\"name\":\"Grace Hopper\",\"email\":\"grace-" + UUID.randomUUID() + "@example.com\"}"),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, res.statusCode(), res.body());
        return idOf(res.body());
    }

    private static String uniqueEmail() {
        return "attendee-" + UUID.randomUUID() + "@example.com";
    }

    private HttpResponse<String> register(String workshopId, String name, String email) throws Exception {
        return post(workshopId, "{\"name\":\"" + name + "\",\"email\":\"" + email + "\"}");
    }

    private HttpResponse<String> post(String workshopId, String json) throws Exception {
        return http.send(request("POST", "/workshops/" + workshopId + "/registrations", json),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String workshopId) throws Exception {
        return http.send(request("GET", "/workshops/" + workshopId + "/registrations", null),
                HttpResponse.BodyHandlers.ofString());
    }

    private List<String> listed(String workshopId) throws Exception {
        HttpResponse<String> res = get(workshopId);
        assertEquals(200, res.statusCode(), res.body());
        return emails(res.body());
    }

    private static List<String> emails(String body) {
        List<String> out = new ArrayList<>();
        Matcher m = EMAIL.matcher(body);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static String idOf(String body) {
        Matcher m = ID.matcher(body);
        assertTrue(m.find(), body);
        return m.group(1);
    }

    private HttpRequest request(String method, String path, String json) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
    }
}
