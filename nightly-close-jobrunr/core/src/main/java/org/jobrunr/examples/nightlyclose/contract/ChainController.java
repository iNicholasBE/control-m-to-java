package org.jobrunr.examples.nightlyclose.contract;

import org.jobrunr.examples.nightlyclose.ChainIds;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST transport of the bridge. The file transport ({@code BridgeFileWatcher}) calls the same contract.
 */
@RestController
@RequestMapping("/chains/nightly-close")
public class ChainController {

    private final EnqueueContract contract;
    private final ChainStatusService status;

    public ChainController(EnqueueContract contract, ChainStatusService status) {
        this.contract = contract;
        this.status = status;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> submit(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @RequestParam(defaultValue = "1") int runNumber) {
        var submission = contract.submit(businessDate, runNumber);
        var body = new LinkedHashMap<String, Object>();
        body.put("outcome", submission.outcome());
        if (submission.run() != null) {
            body.put("runId", submission.run().runId());
            body.put("businessDate", submission.run().businessDate());
            body.put("runNumber", submission.run().runNumber());
            body.put("state", submission.run().state());
        }
        if (submission.reason() != null) body.put("reason", submission.reason());
        var httpStatus = switch (submission.outcome()) {
            case CREATED -> HttpStatus.ACCEPTED;
            case EXISTING -> HttpStatus.OK;
            case CONFLICT -> HttpStatus.CONFLICT;
            case REJECTED -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(httpStatus).body(body);
    }

    @GetMapping("/{businessDate}")
    public ResponseEntity<ChainStatus> get(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @RequestParam(defaultValue = "1") int runNumber) {
        return status.statusOf(ChainIds.runId(businessDate, runNumber))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
