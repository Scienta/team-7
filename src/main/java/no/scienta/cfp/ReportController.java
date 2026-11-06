package no.scienta.cfp;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Exports for the programme committee's spreadsheet. Not part of the public API.
 */
@RestController
public class ReportController {

    private final ConferenceService service;

    public ReportController(ConferenceService service) {
        this.service = service;
    }

    @GetMapping("/admin/stats")
    public Map<String, Object> stats() {
        return service.statistics();
    }

    @GetMapping("/admin/talks.csv")
    public ResponseEntity<String> talksCsv() {
        List<TalkDto> talks = service.listTalks(null, null);
        Map<Long, String> names = service.speakerNames();
        StringBuilder sb = new StringBuilder();
        sb.append("id;title;format;minutes;level;speaker;status;submitted;rating;ratings\n");
        for (TalkDto t : talks) {
            String name = names.get(t.speakerId);
            if (name == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "speaker not found");
            }
            sb.append(t.id).append(';')
                    .append(Utils.csv(t.title)).append(';')
                    .append(Utils.capitalize(t.format.replace("_", " "))).append(';')
                    .append(t.durationMinutes).append(';')
                    .append(Utils.capitalize(t.level)).append(';')
                    .append(Utils.csv(name)).append(';')
                    .append(t.status).append(';')
                    .append(Utils.norwegianDate(new java.util.Date(t.submittedAtMillis))).append(';')
                    .append(t.averageRating == null ? "" : String.valueOf(t.averageRating).replace('.', ',')).append(';')
                    .append(t.ratingCount)
                    .append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=talks.csv")
                .body(sb.toString());
    }
}
