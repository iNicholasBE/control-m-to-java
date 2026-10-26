package org.jobrunr.examples.nightlyclose;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Directories are relative to the working directory, so the harness runs each app from its own work folder.
 */
@ConfigurationProperties("nightly-close")
public record NightlyCloseProperties(
        @DefaultValue("./inbox") Path inboxDir,
        @DefaultValue("./outbox") Path outboxDir,
        @DefaultValue("./markers") Path markersDir,
        @DefaultValue("./bridge/inbox") Path bridgeInboxDir,
        @DefaultValue("./bridge/outbox") Path bridgeOutboxDir,
        @DefaultValue("./holidays.txt") Path holidaysFile,
        @DefaultValue("PT6H") Duration watcherTimeout,
        @DefaultValue("06:00") LocalTime slaTime,
        @DefaultValue("Europe/Brussels") ZoneId slaZone,
        URI regulatorUrl,
        @DefaultValue Test test) {

    /**
     * Test hooks for the kill-test harness. Off unless {@code pause-at} names a marker.
     *
     * @param pauseAt       the marker (without ".entered") at which the step sleeps, so a kill -9 lands exactly there
     * @param pauseDuration how long to sleep at that marker
     */
    public record Test(@DefaultValue("") String pauseAt, @DefaultValue("PT3S") Duration pauseDuration) {
    }
}
