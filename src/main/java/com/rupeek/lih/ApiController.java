package com.rupeek.lih;

import com.rupeek.lender.dto.CommonPrecheckRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.Map;

/** Same paths and contracts as lender-core's PrecheckController and CallbackController. */
@RestController
public class ApiController {
    private final LihPrecheckService service;

    public ApiController(LihPrecheckService service) { this.service = service; }

    @PostMapping(path = "/api/v1/precheck", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> precheck(@RequestBody @Valid CommonPrecheckRequest r) {
        return service.invoke(r.getLoanreqID(), r.getLenderSlug(), r.getLoanProcess());
    }

    @GetMapping(path = "/api/v1/precheck", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> fetchPrecheck(@RequestParam String loanreqID, @RequestParam String lenderSlug,
                                           @RequestParam(required = false) String loanProcess) {
        return service.fetch(loanreqID, lenderSlug, loanProcess);
    }

    @PostMapping("/v1/callback")
    public ResponseEntity<?> lenderCallback(@RequestBody Map<String, Object> body,
                                            @RequestParam String source, @RequestParam String purpose) {
        return service.callback(body, source, purpose);
    }
}
