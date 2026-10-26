package org.jobrunr.examples.nightlyclose.steps;

import org.jobrunr.examples.nightlyclose.contract.ChainRun;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

/**
 * Step 2, extract: load the positions file into {@code positions_staging} with Postgres COPY.
 * Delete and load happen in one transaction, so a rerun after a crash starts from an empty staging set.
 */
@Component
public class PositionsExtractor {

    public static final String HEADER = String.join(",", List.of(
            "position_id", "business_date", "portfolio", "book", "desk", "trader_id", "instrument_id", "instrument_type",
            "currency", "counterparty_id", "counterparty_rating", "country", "sector", "quantity", "price", "notional",
            "market_value", "accrued_interest", "fx_rate", "notional_eur", "duration", "convexity", "delta", "gamma",
            "vega", "theta", "volatility", "pd", "lgd", "maturity_date", "trade_date", "settlement_date", "coupon_rate",
            "spread_bps", "haircut", "collateral_eur", "limit_eur", "status", "source_system", "comments"));

    private final DataSource dataSource;
    private final PositionsFiles files;

    public PositionsExtractor(DataSource dataSource, PositionsFiles files) {
        this.dataSource = dataSource;
        this.files = files;
    }

    public Path positionsFile(ChainRun run) {
        return files.pathFor(run.businessDate());
    }

    /** @return the number of rows loaded */
    public long extract(ChainRun run) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM positions_staging WHERE run_id = ?")) {
                    delete.setObject(1, run.runId());
                    delete.executeUpdate();
                }
                long rows = copy(connection, run);
                connection.commit();
                return rows;
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private long copy(Connection connection, ChainRun run) throws Exception {
        var copyApi = connection.unwrap(PGConnection.class).getCopyAPI();
        CopyIn copyIn = copyApi.copyIn("COPY positions_staging (run_id," + HEADER + ") FROM STDIN WITH (FORMAT csv)");
        // The file has no run id; prefix it to every line on the way in. Lines are buffered and handed to COPY in
        // 256 KB chunks: one writeToCopy call per line (600,000 calls for 200,000 rows) costs more than the load itself.
        String prefix = run.runId() + ",";
        StringBuilder buffer = new StringBuilder(300_000);
        try (BufferedReader reader = Files.newBufferedReader(files.pathFor(run.businessDate()), StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (!HEADER.equals(header)) {
                copyIn.cancelCopy();
                throw new IllegalArgumentException("Unexpected positions header: " + header);
            }
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.isEmpty()) continue;
                buffer.append(prefix).append(line).append('\n');
                if (buffer.length() >= 256 * 1024) {
                    byte[] chunk = buffer.toString().getBytes(StandardCharsets.UTF_8);
                    copyIn.writeToCopy(chunk, 0, chunk.length);
                    buffer.setLength(0);
                }
            }
            if (buffer.length() > 0) {
                byte[] chunk = buffer.toString().getBytes(StandardCharsets.UTF_8);
                copyIn.writeToCopy(chunk, 0, chunk.length);
            }
            return copyIn.endCopy();
        } catch (Exception e) {
            if (copyIn.isActive()) copyIn.cancelCopy();
            throw e;
        }
    }
}
