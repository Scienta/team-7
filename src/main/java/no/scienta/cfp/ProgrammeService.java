package no.scienta.cfp;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The conference programme: accepted talks in a fixed grid of rooms and slots. Rules: specs/scheduling.md;
 * status codes and edge cases: workflow/decisions/scheduling.md.
 */
@Service
public class ProgrammeService {

    private static final Logger log = LoggerFactory.getLogger(ProgrammeService.class);

    private static final java.util.Set<String> ROOMS = java.util.Set.of("R1", "R2", "R3");

    /** Slot -> {start, end}, as on the signs. */
    private static final Map<String, String[]> SLOTS = new LinkedHashMap<>();

    static {
        SLOTS.put("S1", new String[] {"09:00", "10:00"});
        SLOTS.put("S2", new String[] {"10:15", "11:15"});
        SLOTS.put("S3", new String[] {"11:30", "12:30"});
        SLOTS.put("S4", new String[] {"13:30", "15:30"});
        SLOTS.put("S5", new String[] {"15:45", "16:45"});
    }

    /** The only slot long enough for a workshop (120 minutes). */
    private static final String LONG_SLOT = "S4";

    private final ProgrammeDao programme;

    public ProgrammeService(ProgrammeDao programme) {
        this.programme = programme;
    }

    @Transactional
    public Map<String, Object> put(String room, String slot, Map<String, Object> body) {
        requireCell(room, slot);
        Object id = body == null ? null : body.get("talkId");
        if (!(id instanceof Integer || id instanceof Long)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "talkId must be a whole number");
        }
        long talkId = ((Number) id).longValue();
        Map<String, Object> talk = programme.talk(talkId);
        if (talk == null) {
            // the room and slot exist; the body names a talk that doesn't (harness scheduling/check-1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "talk " + talkId + " doesn't exist");
        }
        if (!"ACCEPTED".equals(talk.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only accepted talks go on the programme, this one is " + talk.get("status"));
        }
        if ("WORKSHOP".equals(talk.get("format")) && !LONG_SLOT.equals(slot)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a workshop needs the long slot " + LONG_SLOT);
        }

        Map<String, Long> cells = programme.lockSlot(slot);
        if (Long.valueOf(talkId).equals(cells.get(room))) {
            // Putting a talk where it already is changes nothing (like adding someone who already is a co-speaker).
            return entry(room, slot, talkId);
        }
        if (programme.scheduled(talkId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "the talk is already on the programme; take it out first");
        }
        if (cells.get(room) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, room + " " + slot + " already has a talk");
        }
        if (programme.personBusyInSlot(talkId, slot)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a speaker of this talk already speaks in " + slot);
        }
        try {
            programme.put(room, slot, talkId);
        } catch (DuplicateKeyException e) {
            // the same talk racing into a cell of another slot (a different lock): the unique index decides
            throw new ResponseStatusException(HttpStatus.CONFLICT, "the talk is already on the programme; take it out first");
        }
        log.info("programme " + room + " " + slot + " <- talk " + talkId);
        return entry(room, slot, talkId);
    }

    public Map<String, Object> get(String room, String slot) {
        requireCell(room, slot);
        Long talkId = programme.talkIn(room, slot);
        if (talkId == null) {
            // nothing scheduled there (harness scheduling/check-2..5)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "nothing scheduled in " + room + " " + slot);
        }
        return entry(room, slot, talkId);
    }

    @Transactional
    public void clear(String room, String slot) {
        requireCell(room, slot);
        if (!programme.clear(room, slot)) {
            // Nothing to take out, like an empty cell's GET (and removing someone who isn't a co-speaker).
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "nothing scheduled in " + room + " " + slot);
        }
        log.info("programme " + room + " " + slot + " cleared");
    }

    private static void requireCell(String room, String slot) {
        if (!ROOMS.contains(room) || !SLOTS.containsKey(slot)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no room " + room + " / slot " + slot + " at this venue");
        }
    }

    private static Map<String, Object> entry(String room, String slot, Long talkId) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("room", room);
        e.put("slot", slot);
        e.put("start", SLOTS.get(slot)[0]);
        e.put("end", SLOTS.get(slot)[1]);
        e.put("talkId", talkId);
        return e;
    }
}
