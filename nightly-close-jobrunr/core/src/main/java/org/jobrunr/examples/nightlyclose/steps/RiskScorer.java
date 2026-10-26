package org.jobrunr.examples.nightlyclose.steps;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Step 3, risk scoring for one portfolio. Scores and the partition's progress row commit in one transaction,
 * so a partition is either fully scored and counted, or not at all.
 */
@Component
public class RiskScorer {

    /** Simulated scenarios per position. Seeded per position, so every rerun produces the same numbers. */
    private static final int SCENARIOS = 250;
    private static final double Z_99 = 2.326;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transaction;

    public RiskScorer(JdbcClient jdbc, JdbcTemplate jdbcTemplate, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.transaction = transaction;
    }

    public List<Partition> partitionsOf(UUID runId) {
        List<String> portfolios = jdbc.sql("SELECT DISTINCT portfolio FROM positions_staging WHERE run_id = ? ORDER BY portfolio")
                .param(runId).query(String.class).list();
        return IntStream.range(0, portfolios.size())
                .mapToObj(i -> new Partition(i + 1, portfolios.get(i)))
                .toList();
    }

    /** @return the number of positions scored */
    public int score(UUID runId, Partition partition) {
        return transaction.execute(status -> {
            jdbc.sql("DELETE FROM position_risk WHERE run_id = ? AND portfolio = ?").params(runId, partition.portfolio()).update();
            List<Position> positions = jdbc.sql("""
                            SELECT position_id, notional_eur, market_value, fx_rate, volatility, pd, lgd, delta, haircut, collateral_eur
                              FROM positions_staging WHERE run_id = ? AND portfolio = ? ORDER BY position_id""")
                    .params(runId, partition.portfolio())
                    .query((rs, n) -> new Position(rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                            rs.getDouble(5), rs.getDouble(6), rs.getDouble(7), rs.getDouble(8), rs.getDouble(9), rs.getDouble(10)))
                    .list();
            List<Object[]> rows = positions.stream().map(p -> score(runId, partition.portfolio(), p)).toList();
            jdbcTemplate.batchUpdate("""
                    INSERT INTO position_risk (run_id, portfolio, position_id, notional_eur, market_value_eur, var_1d, expected_loss, stressed_loss)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", rows);
            jdbc.sql("""
                            INSERT INTO partition_progress (run_id, partition, portfolio, positions) VALUES (?, ?, ?, ?)
                            ON CONFLICT (run_id, partition) DO UPDATE SET positions = excluded.positions, finished_at = clock_timestamp()""")
                    .params(runId, partition.number(), partition.portfolio(), positions.size()).update();
            return positions.size();
        });
    }

    private static Object[] score(UUID runId, String portfolio, Position p) {
        double marketValueEur = p.marketValue() * p.fxRate();
        double dailyVol = p.volatility() / Math.sqrt(252);
        double var1d = Math.abs(p.notionalEur() * p.delta()) * dailyVol * Z_99;
        double exposure = Math.max(0, p.notionalEur() - p.collateralEur() * (1 - p.haircut()));
        double expectedLoss = exposure * p.pd() * p.lgd();

        // Worst simulated scenario: a fat-tailed shock to the price plus a default draw.
        var random = new SplittableRandom(p.positionId().hashCode());
        double worst = 0;
        for (int i = 0; i < SCENARIOS; i++) {
            double shock = random.nextGaussian() * dailyVol * (random.nextDouble() < 0.05 ? 3 : 1);
            double loss = -shock * p.notionalEur() * p.delta() + (random.nextDouble() < p.pd() ? exposure * p.lgd() : 0);
            worst = Math.max(worst, loss);
        }
        return new Object[]{runId, portfolio, p.positionId(), money(p.notionalEur()), money(marketValueEur),
                money(var1d), money(expectedLoss), money(worst)};
    }

    private static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_EVEN);
    }

    private record Position(String positionId, double notionalEur, double marketValue, double fxRate, double volatility,
                            double pd, double lgd, double delta, double haircut, double collateralEur) {
    }
}
