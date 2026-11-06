package no.scienta.cfp;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Co-speakers of a talk, in the order they were added (talk_co_speaker.seq).
 */
@Repository
public class CoSpeakerDao {

    private final JdbcTemplate jdbc;

    public CoSpeakerDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Long> findByTalk(long talkId) {
        return jdbc.queryForList("SELECT speaker_id FROM talk_co_speaker WHERE talk_id = ? ORDER BY seq",
                Long.class, talkId);
    }

    /**
     * Co-speakers of several talks in one query. Talks without co-speakers are missing from the map.
     */
    public Map<Long, List<Long>> findByTalks(Collection<Long> talkIds) {
        Map<Long, List<Long>> result = new HashMap<>();
        if (talkIds.isEmpty()) {
            return result;
        }
        jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "SELECT talk_id, speaker_id FROM talk_co_speaker WHERE talk_id = ANY (?) ORDER BY seq");
            Array ids = con.createArrayOf("bigint", talkIds.toArray());
            ps.setArray(1, ids);
            return ps;
        }, rs -> {
            result.computeIfAbsent(rs.getLong("talk_id"), k -> new ArrayList<>()).add(rs.getLong("speaker_id"));
        });
        return result;
    }

    /**
     * Adds the co-speaker at the end of the talk's list. A no-op if they already are one.
     */
    public void add(long talkId, long speakerId) {
        jdbc.update("INSERT INTO talk_co_speaker (talk_id, speaker_id) VALUES (?, ?) ON CONFLICT (talk_id, speaker_id) DO NOTHING",
                talkId, speakerId);
    }

    public void remove(long talkId, long speakerId) {
        jdbc.update("DELETE FROM talk_co_speaker WHERE talk_id = ? AND speaker_id = ?", talkId, speakerId);
    }
}
