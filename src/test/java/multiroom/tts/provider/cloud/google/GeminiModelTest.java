package multiroom.tts.provider.cloud.google;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiModelTest {

    @Test
    void isWellFormedAcceptsLowerCaseDigitsDotsAndHyphens() {
        assertThat(GeminiModel.isWellFormed("gemini-2.5-flash-tts")).isTrue();
        assertThat(GeminiModel.isWellFormed("gemini-3.1-flash-tts-preview")).isTrue();
    }

    @Test
    void isWellFormedRejectsOtherForms() {
        assertThat(GeminiModel.isWellFormed(null)).isFalse();
        assertThat(GeminiModel.isWellFormed("")).isFalse();
        assertThat(GeminiModel.isWellFormed("Gemini-2.5-flash-tts")).isFalse();
        assertThat(GeminiModel.isWellFormed("gemini 2.5")).isFalse();
        assertThat(GeminiModel.isWellFormed("-gemini")).isFalse();
        assertThat(GeminiModel.isWellFormed(".gemini")).isFalse();
        assertThat(GeminiModel.isWellFormed("gemini_2")).isFalse();
    }

    @Test
    void parseNeverChangesTheValue() {
        assertThat(GeminiModel.parse("gemini-2.5-flash-tts").name()).isEqualTo("gemini-2.5-flash-tts");
    }

    @Test
    void parseOfAMalformedValueThrows() {
        assertThatThrownBy(() -> GeminiModel.parse("Gemini-2.5")).isInstanceOf(IllegalArgumentException.class);
    }
}
