package com.imsvet.ims.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imsvet.ims.settings.domain.SettingFormat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit test (U) of the setting formats (ADR-044, BR-LOT-010).
 */
class SettingFormatTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "60,30,15|60,30,15", " 90 , 30 ,7 |90,30,7", "3,2,1|3,2,1"})
    void expiryDaysAreThreeStrictlyDecreasingPositiveNumbers(String raw, String stored) {
        assertThat(SettingFormat.THREE_DESCENDING_DAYS.normalize(raw)).isEqualTo(stored);
    }

    @ParameterizedTest
    @ValueSource(strings = {"60,30", "60,30,15,7", "30,60,15", "60,30,30", "60,30,0", "60,-30,15", "a,b,c", ""})
    void otherExpiryDaysAreRejected(String raw) {
        assertThatThrownBy(() -> SettingFormat.THREE_DESCENDING_DAYS.normalize(raw))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"9|9.00", "19.5|19.50", "0|0.00", "100|100.00", "9.00|9.00"})
    void percentIsStoredWithTwoDecimals(String raw, String stored) {
        assertThat(SettingFormat.PERCENT.normalize(raw)).isEqualTo(stored);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "100.01", "9.001", "nine", ""})
    void otherPercentagesAreRejected(String raw) {
        assertThatThrownBy(() -> SettingFormat.PERCENT.normalize(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5", "1.5", "x"})
    void positiveIntegerRejectsOtherValues(String raw) {
        assertThatThrownBy(() -> SettingFormat.POSITIVE_INTEGER.normalize(raw))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"fr-DZ|fr-DZ", "ar-DZ|ar-DZ", "en|en"})
    void localeMustBeSupported(String raw, String stored) {
        assertThat(SettingFormat.LOCALE_TAG.normalize(raw)).isEqualTo(stored);
        assertThatThrownBy(() -> SettingFormat.LOCALE_TAG.normalize("xx-QQ"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DZDX", "Africa/Nowhere"})
    void currencyAndZoneMustExist(String raw) {
        assertThatThrownBy(() -> SettingFormat.CURRENCY_CODE.normalize(raw))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettingFormat.ZONE_ID.normalize(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    /** ADR-082: {@code opening_stock_open}. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"true|true", "TRUE|true", " false |false", "False|false"})
    void booleansAreStoredInLowerCase(String raw, String stored) {
        assertThat(SettingFormat.BOOLEAN.normalize(raw)).isEqualTo(stored);
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "1", "on", ""})
    void otherBooleansAreRejected(String raw) {
        assertThatThrownBy(() -> SettingFormat.BOOLEAN.normalize(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    /** ADR-108: {@code credit_overdue_block_days}. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"off|off", " OFF |off", "0|0", " 30 |30"})
    void overdueDaysAreOffOrADayCount(String raw, String stored) {
        assertThat(SettingFormat.OFF_OR_DAYS.normalize(raw)).isEqualTo(stored);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "on", "1.5", ""})
    void otherOverdueDaysAreRejected(String raw) {
        assertThatThrownBy(() -> SettingFormat.OFF_OR_DAYS.normalize(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    /** ADR-078, ADR-086: {@code reorder_job_time}, {@code integrity_check_time}. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"06:00|06:00", "6:05|06:05", "23:59|23:59", "00:00|00:00"})
    void timesOfDayAreStoredAsHoursAndMinutes(String raw, String stored) {
        assertThat(SettingFormat.TIME_OF_DAY.normalize(raw)).isEqualTo(stored);
    }

    @ParameterizedTest
    @ValueSource(strings = {"24:00", "12:60", "6h", "06:00:00", ""})
    void otherTimesAreRejected(String raw) {
        assertThatThrownBy(() -> SettingFormat.TIME_OF_DAY.normalize(raw)).isInstanceOf(IllegalArgumentException.class);
    }
}
