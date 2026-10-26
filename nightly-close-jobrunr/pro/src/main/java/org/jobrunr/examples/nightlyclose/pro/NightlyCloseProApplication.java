package org.jobrunr.examples.nightlyclose.pro;

import org.jobrunr.examples.nightlyclose.NightlyCloseCoreConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(NightlyCloseCoreConfiguration.class)
public class NightlyCloseProApplication {

    public static void main(String[] args) {
        SpringApplication.run(NightlyCloseProApplication.class, args);
    }
}
