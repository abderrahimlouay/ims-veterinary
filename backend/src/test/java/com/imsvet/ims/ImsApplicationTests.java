package com.imsvet.ims;

import static org.assertj.core.api.Assertions.assertThat;

import com.imsvet.ims.common.time.BusinessSettings;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ImsApplicationTests {

    @Autowired
    private BusinessSettings business;

    @Test
    void contextLoadsWithTheSeededBusinessSettingsOfAdr033() {
        assertThat(business.currency()).isEqualTo(Currency.getInstance("DZD"));
        assertThat(business.locale()).isEqualTo(Locale.forLanguageTag("fr-DZ"));
        assertThat(business.timeZone()).isEqualTo(ZoneId.of("Africa/Algiers"));
    }
}
