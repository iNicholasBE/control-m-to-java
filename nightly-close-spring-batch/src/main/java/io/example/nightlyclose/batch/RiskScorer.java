package io.example.nightlyclose.batch;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.batch.infrastructure.item.ItemProcessor;

/**
 * Stand-in for a real risk model: deterministic, reads every numeric column of the 40, costs a little CPU.
 * BigDecimal keeps the result bit-for-bit reproducible, which the kill tests rely on (byte-identical report).
 */
public class RiskScorer implements ItemProcessor<StagedPosition, RiskScorer.RiskScore> {

    public record RiskScore(long lineNo, String portfolio, BigDecimal score) {
    }

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    @Override
    public RiskScore process(StagedPosition position) {
        List<String> fields = Csv.split(position.raw());
        BigDecimal score = BigDecimal.ZERO;
        for (int column = 0; column < fields.size(); column++) {
            if (NUMBER.matcher(fields.get(column)).matches()) {
                BigDecimal weight = BigDecimal.ONE.divide(BigDecimal.valueOf(column + 1L), 8, RoundingMode.HALF_EVEN);
                score = score.add(new BigDecimal(fields.get(column)).abs().multiply(weight));
            }
        }
        return new RiskScore(position.lineNo(), position.portfolio(), score.setScale(4, RoundingMode.HALF_EVEN));
    }
}
