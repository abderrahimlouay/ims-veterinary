package com.imsvet.ims.demo;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The demo data never run next to the production profile (README, trial only). */
class DemoDataConfigurationTest {

    @Test
    void aProductionStartWithTheDemoProfileIsRefused() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod", "demo");

        assertThatThrownBy(() -> DemoDataConfiguration.refuseProduction(production))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("prod");
    }

    @Test
    void theDemoProfileAloneStarts() {
        MockEnvironment demo = new MockEnvironment();
        demo.setActiveProfiles("demo");

        assertThatCode(() -> DemoDataConfiguration.refuseProduction(demo)).doesNotThrowAnyException();
    }
}
