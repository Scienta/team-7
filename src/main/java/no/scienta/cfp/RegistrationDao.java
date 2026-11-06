package no.scienta.cfp;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Workshop registrations, in the order they were made (registration.id).
 */
@Repository
public class RegistrationDao {

    private static final String COLUMNS = "id, talk_id AS \"workshopId\", name, email";

    private final JdbcTemplate jdbc;

    public RegistrationDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The talk's format and status, or null if there's no such talk. */
    public Map<String, Object> talk(long talkId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT format, status FROM talk WHERE id = ?", talkId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * The talk's status, read with a row lock that's held until the transaction ends. Registrations for one workshop
     * (and a withdrawal or decision on it) queue up behind each other, so the checks and the insert see a stable count.
     */
    public String lockStatus(long talkId) {
        return jdbc.queryForObject("SELECT status FROM talk WHERE id = ? FOR UPDATE", String.class, talkId);
    }

    public boolean emailRegistered(long talkId, String email) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM registration WHERE talk_id = ? AND lower(email) = lower(?)", Integer.class, talkId, email);
        return n != null && n > 0;
    }

    public int count(long talkId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM registration WHERE talk_id = ?", Integer.class, talkId);
    }

    public Map<String, Object> insert(long talkId, String name, String email) {
        return jdbc.queryForMap("INSERT INTO registration (talk_id, name, email) VALUES (?, ?, ?) RETURNING " + COLUMNS,
                talkId, name, email);
    }

    public List<Map<String, Object>> findByWorkshop(long talkId) {
        return jdbc.queryForList("SELECT " + COLUMNS + " FROM registration WHERE talk_id = ? ORDER BY id", talkId);
    }
}
