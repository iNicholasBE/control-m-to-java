package io.example.nightlyclose;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class NightlyCloseApplication {

    public static void main(String[] args) {
        // Spring Batch stores LocalDateTime (no zone) in its metadata tables. Pinning the JVM to UTC
        // keeps BATCH_* timestamps, our timestamptz columns and the UTC cron trigger on one clock.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(NightlyCloseApplication.class, args);
    }
}
