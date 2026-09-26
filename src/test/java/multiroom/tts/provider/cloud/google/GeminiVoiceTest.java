package multiroom.tts.provider.cloud.google;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiVoiceTest {

    @Test
    void isWellFormedAcceptsAnyCasingOfLetters() {
        assertThat(GeminiVoice.isWellFormed("Kore")).isTrue();
        assertThat(GeminiVoice.isWellFormed("kore")).isTrue();
        assertThat(GeminiVoice.isWellFormed("KORE")).isTrue();
        assertThat(GeminiVoice.isWellFormed("Zubenelgenubi")).isTrue();
    }

    @Test
    void isWellFormedRejectsNonLetterForms() {
        assertThat(GeminiVoice.isWellFormed(null)).isFalse();
        assertThat(GeminiVoice.isWellFormed("")).isFalse();
        assertThat(GeminiVoice.isWellFormed("Ko re")).isFalse();
        assertThat(GeminiVoice.isWellFormed("Kore1")).isFalse();
        assertThat(GeminiVoice.isWellFormed("en-US-Kore")).isFalse();
        assertThat(GeminiVoice.isWellFormed("Kóre")).isFalse();
    }

    @Test
    void parseCanonicalizesCase() {
        assertThat(GeminiVoice.parse("kORE").name()).isEqualTo("Kore");
        assertThat(GeminiVoice.parse("KORE").name()).isEqualTo("Kore");
        assertThat(GeminiVoice.parse("kore").name()).isEqualTo("Kore");
        assertThat(GeminiVoice.parse("Kore").name()).isEqualTo("Kore");
    }

    @Test
    void parseOfAMalformedValueThrows() {
        assertThatThrownBy(() -> GeminiVoice.parse("en-US-Kore")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GeminiVoice.parse("")).isInstanceOf(IllegalArgumentException.class);
    }
}
