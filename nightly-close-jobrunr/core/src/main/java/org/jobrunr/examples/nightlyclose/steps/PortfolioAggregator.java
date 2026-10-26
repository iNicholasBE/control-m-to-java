package org.jobrunr.examples.nightlyclose.steps;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Step 4, aggregate the position scores per portfolio. Replace-in-one-transaction keeps it safe to rerun. */
@Component
public class PortfolioAggregator {

    private final JdbcClient jdbc;

    public PortfolioAggregator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public int aggregate(UUID runId) {
        jdbc.sql("DELETE FROM portfolio_aggregates WHERE run_id = ?").param(runId).update();
        return jdbc.sql("""
                        INSERT INTO portfolio_aggregates
                        SELECT run_id, portfolio, count(*), sum(abs(notional_eur)), sum(market_value_eur),
                               sum(var_1d), sum(expected_loss), sum(stressed_loss)
                          FROM position_risk WHERE run_id = ?
                         GROUP BY run_id, portfolio""")
                .param(runId).update();
    }
}
