package no.scienta.cfp;

import java.util.Date;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TalkDao {

    private static final String COLUMNS = "id, title, format, duration, level, status, speaker_id, submitted_at";

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
        t.submittedAt = new Date(millis);
        t.submittedOn = Utils.toSqlDate(millis);
        return t;
    };

    public TalkDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TalkDto findById(long id) {
        List<TalkDto> list = jdbc.query("SELECT " + COLUMNS + " FROM talk WHERE id = ?", mapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<TalkDto> findBySpeaker(long speakerId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM talk WHERE speaker_id = ? ORDER BY id", mapper, speakerId);
    }

    public List<TalkDto> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM talk ORDER BY id", mapper);
    }

    public boolean exists(long id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM talk WHERE id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    /**
     * Inserts the talk only if the speaker has fewer than {@code max} talks. Returns the new id, or null.
     * The speaker row is locked first so two submissions for the same speaker can't both see room under the limit.
     */
    @Transactional
    public Long insertIfBelow(int max, String title, String format, int duration, String level, long speakerId, long submittedAt) {
        jdbc.queryForList("SELECT id FROM speaker WHERE id = ? FOR UPDATE", speakerId);
        List<Long> ids = jdbc.queryForList(
                "INSERT INTO talk (title, format, duration, level, status, speaker_id, submitted_at) "
                        + "SELECT ?, ?, ?, ?, 'SUBMITTED', ?, ? "
                        + "WHERE (SELECT COUNT(*) FROM talk WHERE speaker_id = ?) < ? RETURNING id",
                Long.class, title, format, duration, level, speakerId, submittedAt, speakerId, max);
        return ids.isEmpty() ? null : ids.get(0);
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
