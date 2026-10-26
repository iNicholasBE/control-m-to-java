package io.example.nightlyclose.batch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;

import io.example.nightlyclose.RunKey;
import io.example.nightlyclose.chain.ChainRuns;

/** One partition per portfolio found in staging, numbered 1..n in portfolio order: scoring-partition-1, -2, ... */
public class PortfolioPartitioner implements Partitioner {

    private final JdbcTemplate jdbc;
    private final ChainRuns chainRuns;
    private final RunKey key;

    public PortfolioPartitioner(JdbcTemplate jdbc, ChainRuns chainRuns, RunKey key) {
        this.jdbc = jdbc;
        this.chainRuns = chainRuns;
        this.key = key;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        List<String> portfolios = jdbc.queryForList(
                "SELECT DISTINCT portfolio FROM positions_staging WHERE run_id = ? ORDER BY portfolio",
                String.class, key.runId());
        Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
        for (int i = 0; i < portfolios.size(); i++) {
            ExecutionContext context = new ExecutionContext();
            context.putString("portfolio", portfolios.get(i));
            context.putInt("partitionNumber", i + 1);
            partitions.put("scoring-partition-" + (i + 1), context);
        }
        chainRuns.recordPartitions(key, portfolios.size());
        return partitions;
    }
}
