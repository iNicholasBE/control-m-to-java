package org.jobrunr.examples.nightlyclose.regulator;

import org.jobrunr.examples.nightlyclose.NightlyCloseProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;

/**
 * Sends the report to the regulator with an idempotency key. A 409 means "already received with this key":
 * the regulator kept the first copy, so for us the delivery is done.
 */
@Component
public class RegulatorClient {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final NightlyCloseProperties properties;

    public RegulatorClient(NightlyCloseProperties properties) {
        this.properties = properties;
    }

    public Receipt submit(String idempotencyKey, LocalDate businessDate, int runNumber, Path report) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(properties.regulatorUrl())
                .timeout(Duration.ofSeconds(30))
                .header(IDEMPOTENCY_KEY, idempotencyKey)
                .header("X-Business-Date", businessDate.toString())
                .header("X-Run-Number", Integer.toString(runNumber))
                .header("Content-Type", "text/csv")
                .POST(HttpRequest.BodyPublishers.ofFile(report))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return switch (response.statusCode()) {
            case 201 -> new Receipt(response.body(), false);
            case 409 -> new Receipt(response.body(), true);
            default -> throw new IOException("Regulator answered " + response.statusCode() + ": " + response.body());
        };
    }

    public record Receipt(String receiptId, boolean duplicate) {
    }
}
