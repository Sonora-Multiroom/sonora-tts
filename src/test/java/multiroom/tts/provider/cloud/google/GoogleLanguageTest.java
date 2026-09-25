package multiroom.tts.provider.cloud.google;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleLanguageTest {

    @ParameterizedTest
    @CsvSource({
            "uk-ua, uk-UA",
            "UK-ua, uk-UA",
            "uk-UA, uk-UA",
            "CMN-cn, cmn-CN",
            "es-419, es-419",
            "EN-us, en-US"
    })
    void parseCanonicalizesCase(String input, String canonical) {
        assertThat(GoogleLanguage.parse(input).tag()).isEqualTo(canonical);
        assertThat(GoogleLanguage.parse(input)).hasToString(canonical);
        assertThat(GoogleLanguage.isWellFormed(input)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ukrainian", "uk", "uk_UA", "uk-UAA", "u-UA", "ukra-UA", "uk-41", "uk-UA-x"})
    @NullAndEmptySource
    void rejectsAnythingButALanguageRegionTag(String input) {
        assertThat(GoogleLanguage.isWellFormed(input)).isFalse();
        assertThatThrownBy(() -> GoogleLanguage.parse(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalityIgnoresCase() {
        assertThat(GoogleLanguage.parse("uk-ua")).isEqualTo(GoogleLanguage.parse("UK-UA"));
        assertThat(GoogleLanguage.parse("uk-ua")).hasSameHashCodeAs(GoogleLanguage.parse("UK-UA"));
        assertThat(GoogleLanguage.parse("uk-UA")).isNotEqualTo(GoogleLanguage.parse("en-US"));
    }
}
