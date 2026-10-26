package io.example.nightlyclose;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("chain")
public record ChainProperties(
        @DefaultValue("./inbox") Path inbox,
        @DefaultValue("./outbox") Path outbox,
        @DefaultValue("./markers") Path markers,
        @DefaultValue("./holidays.txt") Path holidays,
        @DefaultValue("5000") int chunkSize,
        @DefaultValue Watcher watcher,
        @DefaultValue Resources resources,
        @DefaultValue Sla sla,
        @DefaultValue Bridge bridge,
        @DefaultValue Schedule schedule,
        @DefaultValue Recovery recovery,
        @DefaultValue Regulator regulator,
        @DefaultValue TestHooks testHooks) {

    public record Watcher(@DefaultValue("1s") Duration pollInterval, @DefaultValue("12h") Duration timeout) {
    }

    /** Control-M resource pool "DB connections": the partition TaskExecutor never runs more workers than this. */
    public record Resources(@DefaultValue("20") int dbConnections) {
    }

    public record Sla(@DefaultValue("06:00") LocalTime deadline, @DefaultValue("UTC") ZoneId zone) {
    }

    public record Bridge(@DefaultValue("./bridge/inbox") Path inbox, @DefaultValue("./bridge/outbox") Path outbox,
            @DefaultValue("true") boolean enabled) {
    }

    public record Schedule(@DefaultValue("0 30 22 * * ?") String cron, @DefaultValue("true") boolean enabled) {
    }

    public record Recovery(@DefaultValue("true") boolean enabled, @DefaultValue("30s") Duration staleAfter,
            @DefaultValue("5s") Duration heartbeat, @DefaultValue("60s") Duration shutdownWait) {
    }

    public record Regulator(@DefaultValue("") String url) {
    }

    /** Kill-test hooks: give the harness a window to land a kill -9 around the regulator call. */
    public record TestHooks(@DefaultValue("0ms") Duration deliverSleepBeforeCall,
            @DefaultValue("0ms") Duration deliverSleepAfterCall) {
    }
}
