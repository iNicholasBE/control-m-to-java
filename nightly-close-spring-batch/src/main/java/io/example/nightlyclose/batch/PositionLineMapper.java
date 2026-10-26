package io.example.nightlyclose.batch;

import java.util.List;

import org.springframework.batch.infrastructure.item.file.LineCallbackHandler;
import org.springframework.batch.infrastructure.item.file.LineMapper;

/** Maps a line to a staged position, using the header to find the portfolio column. */
public class PositionLineMapper implements LineMapper<StagedPosition>, LineCallbackHandler {

    private int portfolioColumn = -1;

    @Override
    public void handleLine(String header) {
        List<String> columns = Csv.split(header);
        portfolioColumn = columns.indexOf("portfolio");
        if (portfolioColumn < 0) {
            throw new IllegalStateException("No portfolio column in header: " + header);
        }
    }

    @Override
    public StagedPosition mapLine(String line, int lineNumber) {
        return new StagedPosition(lineNumber, Csv.split(line).get(portfolioColumn), line);
    }
}
