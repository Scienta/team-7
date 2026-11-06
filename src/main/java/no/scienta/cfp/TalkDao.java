package no.scienta.cfp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TalkDao {

    // Each talk with its rating count and total, so a list is one query instead of one per talk.
    // and its co-speakers in the order they were added, so reading talks takes one connection, not two.
    private static final String SELECT = "SELECT t.id, t.title, t.format, t.duration, t.level, t.status, t.speaker_id, "
            + "t.submitted_at, COALESCE(r.cnt, 0) AS rating_count, COALESCE(r.total, 0) AS rating_total, r.lo AS rating_min, r.hi AS rating_max, "
            + "c.ids AS co_speaker_ids FROM talk t "
            + "LEFT JOIN (SELECT talk_id, COUNT(*) AS cnt, SUM(score) AS total, MIN(score) AS lo, MAX(score) AS hi "
            + "FROM rating GROUP BY talk_id) r "
            + "ON r.talk_id = t.id "
            + "LEFT JOIN (SELECT talk_id, array_agg(speaker_id ORDER BY seq) AS ids FROM talk_co_speaker GROUP BY talk_id) c "
            + "ON c.talk_id = t.id";

    private final JdbcTemplate jdbc;

    private final RowMapper<TalkDto> mapper = (rs, rowNum) -> {
        TalkDto t = new TalkDto();
        t.id = rs.getLong("id");
        t.title = rs.getString("title");
        t.format = rs.getString("format");
        t.durationMinutes = rs.getInt("duration");
        t.level = rs.getString("level");
        t.status = rs.getString("status");
        t.speakerId = rs.getLong("speaker_id");
        long millis = rs.getLong("submitted_at");
        t.submittedAtMillis = millis;
        t.submittedAt = Utils.legacyTimestamp(millis);
        t.submittedOn = Utils.legacyDate(millis);
        t.ratingCount = rs.getInt("rating_count");
        t.averageRating = average(rs.getLong("rating_total"), t.ratingCount, rs.getLong("rating_min"), rs.getLong("rating_max"));
        java.sql.Array coSpeakers = rs.getArray("co_speaker_ids");
        if (coSpeakers != null) {
            for (Object id : (Object[]) coSpeakers.getArray()) {
                t.coSpeakerIds.add(((Number) id).longValue());
            }
        }
        return t;
    };

    /** From 5 ratings on, the average leaves out one highest and one lowest score (specs/ratings-cr.md). */
    static final int TRIM_FROM = 5;

    /**
     * The shown average: null when nobody rated; from {@link #TRIM_FROM} ratings on without exactly one highest and one
     * lowest score; rounded to 2 decimals with ties to the even neighbour (3.125 -> 3.12, 3.375 -> 3.38). BigDecimal
     * divides the exact sum, so a tie is a real tie, not a double's approximation of one.
     */
    static Double average(long total, int count, long min, long max) {
        if (count == 0) {
            return null;
        }
        long sum = total;
        int n = count;
        if (count >= TRIM_FROM) {
            sum -= min + max;
            n -= 2;
        }
        return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_EVEN).doubleValue();
    }

    // The talk lists as JSON text built by Postgres, byte for byte what Jackson writes for a List<TalkDto>, so a list
    // of thousands of talks doesn't create thousands of objects in Java (GET /talks took 0.6-2.5 s server-side at
    // ~9500 talks, and the harness gives up after ~1 s). Keep in step with TalkDto's fields and average() above.
    //   q: the shown average x 100, rounded half even in integers (trimmed from TRIM_FROM ratings), null if unrated
    private static final String LIST_ROWS = "WITH b AS (SELECT t.id, t.title, t.format, t.duration, t.level, t.status, "
            + "t.speaker_id, t.submitted_at, COALESCE(r.cnt, 0) AS cnt, c.ids, "
            + "CASE WHEN r.cnt >= " + TRIM_FROM + " THEN r.total - r.lo - r.hi ELSE r.total END AS s, "
            + "CASE WHEN r.cnt >= " + TRIM_FROM + " THEN r.cnt - 2 ELSE r.cnt END AS n "
            + "FROM talk t "
            + "LEFT JOIN (SELECT talk_id, COUNT(*) AS cnt, SUM(score) AS total, MIN(score) AS lo, MAX(score) AS hi "
            + "FROM rating GROUP BY talk_id) r ON r.talk_id = t.id "
            + "LEFT JOIN (SELECT talk_id, array_agg(speaker_id ORDER BY seq) AS ids FROM talk_co_speaker GROUP BY talk_id) c "
            + "ON c.talk_id = t.id {WHERE}), "
            + "q AS (SELECT b.*, CASE WHEN n IS NULL THEN NULL ELSE s * 100 / n + CASE "
            + "WHEN 2 * (s * 100 % n) > n THEN 1 WHEN 2 * (s * 100 % n) < n THEN 0 ELSE (s * 100 / n) % 2 END END AS q FROM b) "
            + "SELECT COALESCE('[' || string_agg('{\"id\":' || id"
            + " || ',\"title\":' || to_json(title)::text"
            + " || ',\"format\":' || to_json(format)::text"
            + " || ',\"level\":' || COALESCE(to_json(level)::text, 'null')"
            + " || ',\"speakerId\":' || speaker_id"
            + " || ',\"durationMinutes\":' || COALESCE(duration, 0)"
            + " || ',\"status\":' || to_json(status)::text"
            + " || ',\"submittedAt\":\"' || to_char(to_timestamp(submitted_at / 1000) AT TIME ZONE 'UTC', "
            + "'YYYY-MM-DD\"T\"HH24:MI:SS') || '.' || lpad((submitted_at % 1000)::text, 3, '0') || '+00:00\"'"
            + " || ',\"submittedOn\":\"' || to_char(to_timestamp(submitted_at / 1000) AT TIME ZONE 'UTC', 'YYYY-MM-DD') || '\"'"
            // a double as Java prints it: 4.0, 4.5, 4.25
            + " || ',\"averageRating\":' || CASE WHEN q IS NULL THEN 'null' WHEN q % 100 = 0 THEN (q / 100) || '.0' "
            + "WHEN q % 10 = 0 THEN (q / 100) || '.' || (q % 100 / 10) ELSE (q / 100) || '.' || lpad((q % 100)::text, 2, '0') END"
            + " || ',\"ratingCount\":' || cnt"
            + " || ',\"coSpeakerIds\":[' || COALESCE(array_to_string(ids, ','), '') || ']}'"
            + ", ',' ORDER BY {ORDER}) || ']', '[]') FROM q";
    private static final String BY_ID = "id";
    // the ranking: rated first by the shown average, then more reviewers, then oldest; unrated last
    private static final String BY_RATING = "q IS NULL, q DESC, cnt DESC, id";

    /** GET /talks, optionally one speaker's and/or ranked, as JSON text (see LIST_ROWS). */
    public String listJson(Long speakerId, boolean byRating) {
        // placeholders, not String.format: the SQL has its own % (modulo)
        String sql = LIST_ROWS.replace("{WHERE}", speakerId == null ? "" : "WHERE t.speaker_id = ?")
                .replace("{ORDER}", byRating ? BY_RATING : BY_ID);
        return speakerId == null ? jdbc.queryForObject(sql, String.class)
                : jdbc.queryForObject(sql, String.class, speakerId);
    }

    public TalkDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TalkDto findById(long id) {
        List<TalkDto> list = jdbc.query(SELECT + " WHERE t.id = ?", mapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<TalkDto> findBySpeaker(long speakerId) {
        return jdbc.query(SELECT + " WHERE t.speaker_id = ? ORDER BY t.id", mapper, speakerId);
    }

    public List<TalkDto> findAll() {
        return jdbc.query(SELECT + " ORDER BY t.id", mapper);
    }

    public boolean exists(long id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM talk WHERE id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    /**
     * Locks the talk's row until the transaction ends and returns its main speaker, or null if there's no such talk.
     * Serialises changes to one talk's co-speakers, so concurrent additions can't push it past the limit.
     */
    public Long lockAndGetSpeaker(long id) {
        List<Long> rows = jdbc.queryForList("SELECT speaker_id FROM talk WHERE id = ? FOR UPDATE", Long.class, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Inserts the talk only if the speaker has fewer than {@code max} talks. Returns the new id, or null.
     * The speaker row is locked first so two submissions for the same speaker can't both see room under the limit.
     */
    @Transactional
    public Long insertIfBelow(int max, String title, String format, int duration, String level, long speakerId, long submittedAt) {
        lockSpeaker(speakerId);
        List<Long> ids = jdbc.queryForList(
                "INSERT INTO talk (title, format, duration, level, status, speaker_id, submitted_at) "
                        + "SELECT ?, ?, ?, ?, 'SUBMITTED', ?, ? "
                        + "WHERE (" + PRESENTING + ") < ? RETURNING id",
                Long.class, title, format, duration, level, speakerId, submittedAt, speakerId, speakerId, max);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * How many talks a speaker presents that still count towards the limit (specs/submission-cap-cr.md): as main
     * speaker or co-speaker, not rejected or withdrawn. Takes the speaker id twice.
     */
    private static final String PRESENTING = "SELECT COUNT(*) FROM talk p WHERE p.status NOT IN ('REJECTED', 'WITHDRAWN') "
            + "AND (p.speaker_id = ? OR EXISTS (SELECT 1 FROM talk_co_speaker c WHERE c.talk_id = p.id AND c.speaker_id = ?))";

    public int countPresenting(long speakerId) {
        return jdbc.queryForObject(PRESENTING, Integer.class, speakerId, speakerId);
    }

    /**
     * Locks the speaker's row until the transaction ends. Every path that can add to a speaker's count takes it
     * (a submission, being added as co-speaker), so they queue up per speaker and the limit can't be overtaken.
     * Order where both are needed: the talk first, then the speaker.
     */
    public void lockSpeaker(long speakerId) {
        jdbc.queryForList("SELECT id FROM speaker WHERE id = ? FOR UPDATE", speakerId);
    }

    public String status(long id) {
        return jdbc.queryForObject("SELECT status FROM talk WHERE id = ?", String.class, id);
    }

    /**
     * Moves the talk to {@code to} only if its status is one of {@code from}, it has at least {@code minReviewers}
     * distinct reviewers, and (when {@code speakerId} isn't null) it belongs to that speaker. One statement, so two
     * concurrent changes can't both pass the check: the second re-evaluates the WHERE against the first's result.
     * Returns true if the talk was changed.
     */
    public boolean updateStatusIf(long id, String to, java.util.Collection<String> from, int minReviewers, Long speakerId) {
        List<Object> args = new java.util.ArrayList<>();
        args.add(to);
        args.add(id);
        StringBuilder sql = new StringBuilder("UPDATE talk SET status = ? WHERE id = ? AND status IN (");
        for (String s : from) {
            sql.append(args.size() > 2 ? ", ?" : "?");
            args.add(s);
        }
        sql.append(")");
        if (minReviewers > 0) {
            // (talk_id, reviewer_id) is the rating's primary key, so COUNT(*) counts distinct reviewers
            sql.append(" AND (SELECT COUNT(*) FROM rating WHERE talk_id = ?) >= ?");
            args.add(id);
            args.add(minReviewers);
        }
        if (speakerId != null) {
            sql.append(" AND speaker_id = ?");
            args.add(speakerId);
        }
        return jdbc.update(sql.toString(), args.toArray()) == 1;
    }

    // old version, raced when the mobile app double-posted
    // public long insert(String title, String format, int duration, String level, long speakerId) {
    //     jdbc.update("INSERT INTO talk (title, format, duration, level, status, speaker_id) VALUES (?, ?, ?, ?, 'SUBMITTED', ?)",
    //             title, format, duration, level, speakerId);
    //     return jdbc.queryForObject("SELECT currval('talk_id_seq')", Long.class);
    // }
}
