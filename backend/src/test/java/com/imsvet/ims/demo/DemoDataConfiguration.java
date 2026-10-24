package com.imsvet.ims.demo;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Trial data, profile {@code demo} only (README). Lives in the test sources, so it is never in the production
 * artifact; and it refuses to start next to the {@code prod} profile. Seeds through the application services after
 * the administrator bootstrap, once per database.
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoDataConfiguration {

    DemoDataConfiguration(Environment environment) {
        refuseProduction(environment);
    }

    static void refuseProduction(Environment environment) {
        if (environment.matchesProfiles("prod")) {
            throw new IllegalStateException("The demo profile must never run with the prod profile.");
        }
    }

    @Bean
    @Primary
    DemoClock demoClock() {
        return new DemoClock();
    }

    /** After every application runner, so the administrator bootstrap has run first. */
    @Bean
    ApplicationListener<ApplicationReadyEvent> demoDataSeeding(DemoDataSeeder seeder) {
        return event -> seeder.seedOnce();
    }
}
