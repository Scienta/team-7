package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.SpringVersion;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The exact JSON the base specs require, so a framework upgrade (Jackson, Spring) can't change it unnoticed.
 * Runs the real app on a random port with a throwaway database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WireFormatTest {

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void speakerIsReturnedExactlyAsRegistered() throws Exception {
        String email = "Ada-" + UUID.randomUUID() + "@Example.com";
        HttpResponse<String> created = send("POST", "/speakers", "{\"name\":\"Ada Lovelace\",\"email\":\"" + email + "\"}");
        assertEquals(201, created.statusCode(), created.body());
        String id = id(created);
        String expected = "{\"id\":" + id + ",\"name\":\"Ada Lovelace\",\"email\":\"" + email + "\",\"bio\":null}";
        assertEquals(expected, created.body());
        assertEquals(expected, send("GET", "/speakers/" + id, null).body());
    }

    @Test
    void talkFieldsHaveTheLegacyFormats() throws Exception {
        String speaker = newSpeaker();
        // durationMinutes from the client is ignored: the format decides
        HttpResponse<String> created = send("POST", "/talks",
                "{\"title\":\"Legacy\",\"format\":\"LIGHTNING_20\",\"level\":\"ADVANCED\",\"speakerId\":" + speaker
                        + ",\"durationMinutes\":999}");
        assertEquals(201, created.statusCode(), created.body());
        String body = created.body();
        assertTrue(body.matches("\\{\"id\":\\d+,\"title\":\"Legacy\",\"format\":\"LIGHTNING_20\",\"level\":\"ADVANCED\","
                + "\"speakerId\":" + speaker + ",\"durationMinutes\":20,\"status\":\"SUBMITTED\","
                + "\"submittedAt\":\"\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}\\+00:00\","
                + "\"submittedOn\":\"\\d{4}-\\d\\d-\\d\\d\",\"averageRating\":null,\"ratingCount\":0,\"coSpeakerIds\":\\[\\]}"), body);
        String at = body.replaceAll(".*\"submittedAt\":\"([^\"]+)\".*", "$1");
        String on = body.replaceAll(".*\"submittedOn\":\"([^\"]+)\".*", "$1");
        assertEquals(at.substring(0, 10), on);
    }

    @Test
    void ratingsShowRoundedAveragesAndTheUsShortTime() throws Exception {
        String speaker = newSpeaker();
        String talk = id(send("POST", "/talks",
                "{\"title\":\"Rated\",\"format\":\"WORKSHOP\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}"));

        HttpResponse<String> rated = send("PUT", "/talks/" + talk + "/ratings/1", "{\"score\":4}");
        assertEquals(200, rated.statusCode(), rated.body());
        assertTrue(rated.body().matches("\\{\"talkId\":" + talk + ",\"reviewerId\":1,\"score\":4,"
                + "\"ratedAtDisplay\":\"\\d{1,2}/\\d{1,2}/\\d\\d, \\d{1,2}:\\d\\d (AM|PM)\"}"), rated.body());

        send("PUT", "/talks/" + talk + "/ratings/2", "{\"score\":4}");
        assertTrue(send("GET", "/talks/" + talk, null).body().contains("\"averageRating\":4.0,\"ratingCount\":2"));
        send("PUT", "/talks/" + talk + "/ratings/3", "{\"score\":4}");
        send("PUT", "/talks/" + talk + "/ratings/4", "{\"score\":5}");
        String shown = send("GET", "/talks/" + talk, null).body();
        assertTrue(shown.contains("\"averageRating\":4.3,\"ratingCount\":4"), shown);
        String listed = send("GET", "/talks?sort=rating", null).body();
        assertTrue(listed.startsWith("[{") && listed.contains("\"averageRating\":4.3"), listed);
    }

    @Test
    void scoresAreWholeNumbersOnly() throws Exception {
        String speaker = newSpeaker();
        String talk = id(send("POST", "/talks",
                "{\"title\":\"Strict\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}"));
        for (String body : new String[] {"{\"score\":4.0}", "{\"score\":\"4\"}", "{\"score\":4.5}", "{}", "{\"score\":6}"}) {
            assertEquals(400, send("PUT", "/talks/" + talk + "/ratings/1", body).statusCode(), body);
        }
        assertEquals(400, send("PUT", "/talks/" + talk + "/ratings/0", "{\"score\":3}").statusCode());
        assertEquals(400, send("PUT", "/talks/" + talk + "/ratings/abc", "{\"score\":3}").statusCode());
    }

    @Test
    void formatsAndLevelsAreCaseSensitive() throws Exception {
        String speaker = newSpeaker();
        assertEquals(400, send("POST", "/talks",
                "{\"title\":\"x\",\"format\":\"workshop\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}").statusCode());
        assertEquals(400, send("POST", "/talks",
                "{\"title\":\"x\",\"format\":\"WORKSHOP\",\"level\":\"Beginner\",\"speakerId\":" + speaker + "}").statusCode());
    }

    @Test
    void theLegacyMobileClientMayPutCommentsInItsJson() throws Exception {
        HttpResponse<String> res = send("POST", "/speakers",
                "{/* mobile 2019 */ \"name\":\"Mo\", // trailing\n \"email\":\"mo-" + UUID.randomUUID() + "@example.com\"}");
        assertEquals(201, res.statusCode(), res.body());
    }

    @Test
    void errorsSayWhatWasWrong() throws Exception {
        HttpResponse<String> res = send("POST", "/speakers", "{\"name\":\"X\",\"email\":\"nope\"}");
        assertEquals(400, res.statusCode());
        assertTrue(res.body().contains("\"message\":\"invalid email\""), res.body());
    }

    @Test
    void statsCountEachSpeakerWithTalksOnce() throws Exception {
        int before = speakersWithTalks();
        String busy = newSpeaker();
        newSpeaker();  // no talks: doesn't count
        for (int i = 0; i < 2; i++) {
            assertEquals(201, send("POST", "/talks", "{\"title\":\"Stat " + i
                    + "\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + busy + "}").statusCode());
        }
        assertEquals(before + 1, speakersWithTalks());
    }

    private int speakersWithTalks() throws Exception {
        HttpResponse<String> res = send("GET", "/admin/stats", null);
        assertEquals(200, res.statusCode(), res.body());
        return Integer.parseInt(res.body().replaceAll(".*\"speakersWithTalks\":(\\d+).*", "$1"));
    }

    @Test
    void infoReportsTheRunningVersions() throws Exception {
        HttpResponse<String> info = send("GET", "/actuator/info", null);
        assertEquals(200, info.statusCode(), info.body());
        assertTrue(info.body().contains("\"springBoot\":\"" + SpringBootVersion.getVersion() + "\""), info.body());
        assertTrue(info.body().contains("\"springFramework\":\"" + SpringVersion.getVersion() + "\""), info.body());
        assertTrue(info.body().contains("\"java\":\"" + System.getProperty("java.version") + "\""), info.body());
    }

    private String newSpeaker() throws Exception {
        return id(send("POST", "/speakers", "{\"name\":\"Grace\",\"email\":\"grace-" + UUID.randomUUID() + "@example.com\"}"));
    }

    private static String id(HttpResponse<String> res) {
        assertEquals(201, res.statusCode(), res.body());
        Matcher m = ID.matcher(res.body());
        assertTrue(m.find(), res.body());
        return m.group(1);
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
