package no.scienta.cfp;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The programme grid: one row per room and slot (table programme), talk_id null when the cell is empty.
 */
@Repository
public class ProgrammeDao {

    private final JdbcTemplate jdbc;

    public ProgrammeDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The talk's format and status, or null if there's no such talk. */
    public Map<String, Object> talk(long talkId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT format, status FROM talk WHERE id = ?", talkId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** The talk in this cell, or null when it's empty. */
    public Long talkIn(String room, String slot) {
        return jdbc.queryForObject("SELECT talk_id FROM programme WHERE room = ? AND slot = ?", Long.class, room, slot);
    }

    /**
     * Locks the three cells of a slot until the transaction ends and returns room -> talk id (null when empty).
     * Everyone scheduling into the same slot queues up here, so the occupancy and people checks can't race.
     */
    public Map<String, Long> lockSlot(String slot) {
        Map<String, Long> cells = new java.util.HashMap<>();
        jdbc.query("SELECT room, talk_id FROM programme WHERE slot = ? ORDER BY room FOR UPDATE",
                rs -> {
                    // getObject, not getLong + wasNull: wasNull only reports on the last column read
                    Number t = (Number) rs.getObject("talk_id");
                    cells.put(rs.getString("room"), t == null ? null : t.longValue());
                }, slot);
        return cells;
    }

    public boolean scheduled(long talkId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM programme WHERE talk_id = ?", Integer.class, talkId);
        return n != null && n > 0;
    }

    /**
     * Whether any person of the talk (main speaker or co-speaker) is also a person of a talk already in this slot.
     */
    public boolean personBusyInSlot(long talkId, String slot) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM programme p"
                        + " JOIN (SELECT id AS talk_id, speaker_id AS person FROM talk"
                        + "       UNION ALL SELECT talk_id, speaker_id FROM talk_co_speaker) o ON o.talk_id = p.talk_id"
                        + " WHERE p.slot = ? AND o.person IN"
                        + "   (SELECT speaker_id FROM talk WHERE id = ? UNION SELECT speaker_id FROM talk_co_speaker WHERE talk_id = ?)",
                Integer.class, slot, talkId, talkId);
        return n != null && n > 0;
    }

    public void put(String room, String slot, long talkId) {
        jdbc.update("UPDATE programme SET talk_id = ? WHERE room = ? AND slot = ?", talkId, room, slot);
    }

    /** Takes the talk out of the cell; false if there was none. */
    public boolean clear(String room, String slot) {
        return jdbc.update("UPDATE programme SET talk_id = NULL WHERE room = ? AND slot = ? AND talk_id IS NOT NULL",
                room, slot) > 0;
    }
}
