package org.jobrunr.examples.nightlyclose.steps;

import org.jobrunr.examples.nightlyclose.NightlyCloseProperties;
import org.jobrunr.examples.nightlyclose.contract.ChainRun;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Step 5, write {@code REPORT-<businessDate>-<runNumber>.csv}. The content depends only on the data, never on
 * timestamps or run ids, so a run that was killed and resumed must produce a byte-identical file.
 */
@Component
public class RegulatoryReportWriter {

    private static final String HEADER = "business_date,portfolio,positions,gross_notional_eur,net_market_value_eur,var_1d_eur,expected_loss_eur,stressed_loss_eur";

    private final JdbcClient jdbc;
    private final NightlyCloseProperties properties;

    public RegulatoryReportWriter(JdbcClient jdbc, NightlyCloseProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public Path write(ChainRun run) throws IOException {
        List<Line> lines = jdbc.sql("""
                        SELECT portfolio, positions, gross_notional_eur, net_market_value_eur, var_1d_eur, expected_loss_eur, stressed_loss_eur
                          FROM portfolio_aggregates WHERE run_id = ? ORDER BY portfolio""")
                .param(run.runId())
                .query((rs, n) -> new Line(rs.getString(1), rs.getLong(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7)))
                .list();
        if (lines.isEmpty()) throw new IllegalStateException("No aggregates for run " + run.runId());

        var csv = new StringBuilder(HEADER).append('\n');
        Line total = new Line("TOTAL", 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        for (Line line : lines) {
            csv.append(line.toCsv(run)).append('\n');
            total = total.plus(line);
        }
        csv.append(total.toCsv(run)).append('\n');

        Files.createDirectories(properties.outboxDir());
        Path report = properties.outboxDir().resolve("REPORT-" + run.businessDate() + "-" + run.runNumber() + ".csv");
        // Write next to the target and move, so nobody downstream ever reads half a report.
        Path partial = report.resolveSibling(report.getFileName() + ".part");
        Files.writeString(partial, csv, StandardCharsets.UTF_8);
        Files.move(partial, report, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return report;
    }

    private record Line(String portfolio, long positions, BigDecimal gross, BigDecimal net, BigDecimal var,
                        BigDecimal expectedLoss, BigDecimal stressedLoss) {

        Line plus(Line other) {
            return new Line(portfolio, positions + other.positions, gross.add(other.gross), net.add(other.net),
                    var.add(other.var), expectedLoss.add(other.expectedLoss), stressedLoss.add(other.stressedLoss));
        }

        String toCsv(ChainRun run) {
            return String.join(",", run.businessDate().toString(), portfolio, Long.toString(positions),
                    gross.toPlainString(), net.toPlainString(), var.toPlainString(),
                    expectedLoss.toPlainString(), stressedLoss.toPlainString());
        }
    }
}
