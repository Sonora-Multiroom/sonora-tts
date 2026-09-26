package multiroom.tts.provider.cloud.google;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleSpeakingRateTest {

    @Test
    void checkAcceptsNullAndTheBoundsAndNeutral() {
        GoogleSpeakingRate.check("speakingRate", null);
        GoogleSpeakingRate.check("speakingRate", 0.25);
        GoogleSpeakingRate.check("speakingRate", 1.0);
        GoogleSpeakingRate.check("speakingRate", 2.0);
    }

    @Test
    void checkRejectsBelowRange() {
        assertThatThrownBy(() -> GoogleSpeakingRate.check("speakingRate", 0.2))
                .isInstanceOf(TtsException.class)
                .hasMessage("speakingRate 0.2 is outside the allowed range [0.25, 2.0]")
                .satisfies(e -> assertThat(((TtsException) e).getErrorCode()).isEqualTo(TtsErrorCode.INVALID_REQUEST));
    }

    @Test
    void checkRejectsAboveRange() {
        assertThatThrownBy(() -> GoogleSpeakingRate.check("speakingRate", 2.5))
                .isInstanceOf(TtsException.class)
                .hasMessage("speakingRate 2.5 is outside the allowed range [0.25, 2.0]")
                .satisfies(e -> assertThat(((TtsException) e).getErrorCode()).isEqualTo(TtsErrorCode.INVALID_REQUEST));
    }

    @Test
    void checkRejectsNaN() {
        assertThatThrownBy(() -> GoogleSpeakingRate.check("speakingRate", Double.NaN))
                .isInstanceOf(TtsException.class)
                .hasMessage("speakingRate NaN is outside the allowed range [0.25, 2.0]");
    }

    @Test
    void checkRejectsInfinity() {
        assertThatThrownBy(() -> GoogleSpeakingRate.check("speakingRate", Double.POSITIVE_INFINITY))
                .isInstanceOf(TtsException.class);
        assertThatThrownBy(() -> GoogleSpeakingRate.check("speakingRate", Double.NEGATIVE_INFINITY))
                .isInstanceOf(TtsException.class);
    }

    @Test
    void keyOfIsNullForNullAndNeutral() {
        assertThat(GoogleSpeakingRate.keyOf(null)).isNull();
        assertThat(GoogleSpeakingRate.keyOf(1.0)).isNull();
    }

    @Test
    void keyOfIsTheValueOtherwise() {
        assertThat(GoogleSpeakingRate.keyOf(1.1)).isEqualTo(1.1);
    }

    @Test
    void isInRangeGivesTheSameVerdictsAsABoolean() {
        assertThat(GoogleSpeakingRate.isInRange(null)).isTrue();
        assertThat(GoogleSpeakingRate.isInRange(0.25)).isTrue();
        assertThat(GoogleSpeakingRate.isInRange(2.0)).isTrue();
        assertThat(GoogleSpeakingRate.isInRange(0.2)).isFalse();
        assertThat(GoogleSpeakingRate.isInRange(2.5)).isFalse();
        assertThat(GoogleSpeakingRate.isInRange(Double.NaN)).isFalse();
    }
}
