package no.scienta.cfp;

import java.util.ArrayList;
import java.util.List;

/**
 * A talk as the API returns it. Also used internally all over the place.
 */
public class TalkDto {

    public Long id;
    public String title;
    public String format;
    public String level;
    public Long speakerId;
    public Integer durationMinutes;
    public String status;
    // Strings, formatted by us rather than by Jackson, because the legacy reporting tools read them as exact text:
    // Jackson 3 writes dates as ...Z and a java.sql.Date as a timestamp.
    public String submittedAt;  // yyyy-MM-dd'T'HH:mm:ss.SSS+00:00, UTC
    public String submittedOn;  // yyyy-MM-dd, the UTC date of submittedAt
    long submittedAtMillis;     // package-private, so not in the JSON; for the CSV export
    public Double averageRating;
    public int ratingCount;
    public List<Long> coSpeakerIds = new ArrayList<>();  // in the order they were added
}
