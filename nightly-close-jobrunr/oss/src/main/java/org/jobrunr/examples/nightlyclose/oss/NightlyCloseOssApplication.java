package org.jobrunr.examples.nightlyclose.oss;

import org.jobrunr.examples.nightlyclose.NightlyCloseCoreConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(NightlyCloseCoreConfiguration.class)
public class NightlyCloseOssApplication {

    public static void main(String[] args) {
        SpringApplication.run(NightlyCloseOssApplication.class, args);
    }
}
