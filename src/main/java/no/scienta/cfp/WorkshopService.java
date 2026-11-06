package no.scienta.cfp;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Registrations for accepted workshops, 20 seats each. Status codes: workflow/decisions/workshop-registration.md.
 */
@Service
public class WorkshopService {

    private static final Logger log = LoggerFactory.getLogger(WorkshopService.class);

    static final int CAPACITY = 20;

    private final RegistrationDao registrations;

    public WorkshopService(RegistrationDao registrations) {
        this.registrations = registrations;
    }

    @Transactional
    public Map<String, Object> register(long workshopId, Map<String, Object> body) {
        requireWorkshop(workshopId);
        Object name = body == null ? null : body.get("name");
        Object email = body == null ? null : body.get("email");
        if (!(name instanceof String) || ((String) name).trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (!(email instanceof String) || !Utils.isValidEmail((String) email)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a valid email is required");
        }

        String status = registrations.lockStatus(workshopId);
        if (!"ACCEPTED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a " + status + " workshop takes no registrations");
        }
        if (registrations.emailRegistered(workshopId, (String) email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "this email is already registered for the workshop");
        }
        if (registrations.count(workshopId) >= CAPACITY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "the workshop is full");
        }
        try {
            Map<String, Object> registration = registrations.insert(workshopId, (String) name, (String) email);
            log.info("registration " + registration.get("id") + " for workshop " + workshopId);
            return registration;
        } catch (DuplicateKeyException e) {
            // can't happen under the row lock, but the unique index is the last word
            throw new ResponseStatusException(HttpStatus.CONFLICT, "this email is already registered for the workshop");
        }
    }

    public List<Map<String, Object>> list(long workshopId) {
        requireWorkshop(workshopId);
        return registrations.findByWorkshop(workshopId);
    }

    private void requireWorkshop(long workshopId) {
        Map<String, Object> talk = registrations.talk(workshopId);
        if (talk == null || !"WORKSHOP".equals(talk.get("format"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "workshop not found");
        }
    }
}
