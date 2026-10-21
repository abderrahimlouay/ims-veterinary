package com.imsvet.ims;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * The configuration every integration test imports: real MySQL plus the settable test clock. Importing
 * exactly this keeps all tests in one context and one container (ADR-035). Not used by
 * {@link TestImsApplication}, whose local run keeps the real clock.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(MySqlContainerConfiguration.class)
public class TestcontainersConfiguration {

    @Bean
    @Primary
    TestClock testClock() {
        return new TestClock();
    }
}
