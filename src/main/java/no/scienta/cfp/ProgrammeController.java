package no.scienta.cfp;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProgrammeController {

    private final ProgrammeService service;

    public ProgrammeController(ProgrammeService service) {
        this.service = service;
    }

    /** The whole programme as JSON, for the website. */
    @GetMapping("/programme")
    public List<Map<String, Object>> export() {
        return service.export();
    }

    /**
     * The whole programme as CSV, for the print shop. The content type is set here, not negotiated, so it's text/csv
     * whatever the request's Accept header says (no 406 for Accept: application/json).
     */
    @GetMapping("/programme.csv")
    public ResponseEntity<byte[]> exportCsv() {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(service.exportCsv().getBytes(StandardCharsets.UTF_8));
    }

    @PutMapping("/programme/{room}/{slot}")
    public Map<String, Object> put(@PathVariable String room, @PathVariable String slot,
                                   @RequestBody(required = false) Map<String, Object> body) {
        return service.put(room, slot, body);
    }

    @GetMapping("/programme/{room}/{slot}")
    public Map<String, Object> get(@PathVariable String room, @PathVariable String slot) {
        return service.get(room, slot);
    }

    @DeleteMapping("/programme/{room}/{slot}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable String room, @PathVariable String slot) {
        service.clear(room, slot);
    }
}
