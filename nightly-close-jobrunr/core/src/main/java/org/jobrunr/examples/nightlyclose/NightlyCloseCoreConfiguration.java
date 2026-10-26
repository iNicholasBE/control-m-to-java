package org.jobrunr.examples.nightlyclose;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Imported by the OSS and the Pro application. Everything in this package is scheduler-agnostic.
 */
@Configuration
@ComponentScan
@EnableConfigurationProperties(NightlyCloseProperties.class)
public class NightlyCloseCoreConfiguration {
}
