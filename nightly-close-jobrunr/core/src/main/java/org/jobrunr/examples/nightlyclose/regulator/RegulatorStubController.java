package org.jobrunr.examples.nightlyclose.regulator;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;

/**
 * The simulated regulator endpoint. In production this is somebody else's system; it lives in the app here so
 * the harness can count deliveries. The UNIQUE index on the idempotency key is the regulator's dedup.
 */
@RestController
public class RegulatorStubController {

    private final JdbcClient jdbc;

    public RegulatorStubController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/regulator/submissions")
    public ResponseEntity<String> receive(
            @RequestHeader(RegulatorClient.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestHeader("X-Business-Date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
            @RequestHeader("X-Run-Number") int runNumber,
            @RequestBody byte[] report) {
        try {
            long id = jdbc.sql("""
                            INSERT INTO deliveries (idempotency_key, business_date, run_number, report_sha256)
                            VALUES (?, ?, ?, ?) RETURNING id""")
                    .params(idempotencyKey, businessDate, runNumber, sha256(report))
                    .query(Long.class).single();
            return ResponseEntity.status(HttpStatus.CREATED).body("RCPT-" + id);
        } catch (DuplicateKeyException duplicate) {
            jdbc.sql("INSERT INTO delivery_rejections (idempotency_key, business_date, run_number) VALUES (?, ?, ?)")
                    .params(idempotencyKey, businessDate, runNumber).update();
            long existing = jdbc.sql("SELECT id FROM deliveries WHERE idempotency_key = ?")
                    .param(idempotencyKey).query(Long.class).single();
            return ResponseEntity.status(HttpStatus.CONFLICT).body("RCPT-" + existing);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
