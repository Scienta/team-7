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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A reviewer is any positive whole number, also above the 32-bit range. Runs the real app on a random port with a
 * throwaway database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RatingsTest {

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void aReviewerAboveTheIntegerRangeCanRate() throws Exception {
        String talk = newTalk();
        long reviewer = 3_000_000_000L;

        HttpResponse<String> rated = send("PUT", "/talks/" + talk + "/ratings/" + reviewer, "{\"score\":4}");
        assertEquals(200, rated.statusCode(), rated.body());
        assertTrue(rated.body().contains("\"reviewerId\":" + reviewer), rated.body());

        HttpResponse<String> shown = send("GET", "/talks/" + talk, null);
        assertTrue(shown.body().contains("\"ratingCount\":1"), shown.body());
        assertTrue(shown.body().contains("\"averageRating\":4.0"), shown.body());
    }

    private String newTalk() throws Exception {
        String speaker = id(send("POST", "/speakers",
                "{\"name\":\"Grace Hopper\",\"email\":\"grace-" + UUID.randomUUID() + "@example.com\"}"));
        return id(send("POST", "/talks",
                "{\"title\":\"Nanoseconds\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}"));
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
