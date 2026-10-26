package io.example.nightlyclose.chain;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.example.nightlyclose.RunKey;

@RestController
public class ChainController {

    private final ChainService chains;

    public ChainController(ChainService chains) {
        this.chains = chains;
    }

    @PostMapping("/chains/nightly-close")
    public ResponseEntity<Map<String, Object>> submit(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @RequestParam(defaultValue = "1") int runNumber) {
        ChainService.Submission submission = chains.submit(new RunKey(businessDate, runNumber), "rest");
        return ResponseEntity.status(submission.httpStatus()).body(submission.body());
    }

    @GetMapping("/chains/nightly-close/{businessDate}")
    public ResponseEntity<Map<String, Object>> status(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @RequestParam(defaultValue = "1") int runNumber) {
        return chains.status(new RunKey(businessDate, runNumber))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
