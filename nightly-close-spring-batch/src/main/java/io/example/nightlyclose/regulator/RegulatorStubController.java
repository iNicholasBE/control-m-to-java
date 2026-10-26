package io.example.nightlyclose.regulator;

import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.example.nightlyclose.recovery.NodeIdentity;

/**
 * Simulated regulator endpoint. It plays the other side of the wire: the UNIQUE index on the idempotency key is
 * what turns a second delivery into a rejected insert, and every rejection is logged so tests can count them.
 */
@RestController
public class RegulatorStubController {

    private static final Logger log = LoggerFactory.getLogger(RegulatorStubController.class);

    private final JdbcTemplate jdbc;
    private final NodeIdentity node;

    public RegulatorStubController(JdbcTemplate jdbc, NodeIdentity node) {
        this.jdbc = jdbc;
        this.node = node;
    }

    @PostMapping("/regulator/submissions")
    public ResponseEntity<Map<String, Object>> submit(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestParam LocalDate businessDate, @RequestParam int runNumber, @RequestBody byte[] report)
            throws Exception {
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(report));
        try {
            jdbc.update("""
                    INSERT INTO deliveries (idempotency_key, business_date, run_number, delivered_at, report_sha256, node)
                    VALUES (?, ?, ?, clock_timestamp(), ?, ?)""",
                    idempotencyKey, businessDate, runNumber, sha256, node.nodeName());
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("idempotencyKey", idempotencyKey,
                    "status", "ACCEPTED"));
        } catch (DuplicateKeyException duplicate) {
            jdbc.update("""
                    INSERT INTO delivery_rejections (idempotency_key, attempted_at, report_sha256, node)
                    VALUES (?, clock_timestamp(), ?, ?)""", idempotencyKey, sha256, node.nodeName());
            log.warn("Regulator stub: duplicate delivery for {} rejected", idempotencyKey);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("idempotencyKey", idempotencyKey,
                    "status", "DUPLICATE_REJECTED"));
        }
    }
}
