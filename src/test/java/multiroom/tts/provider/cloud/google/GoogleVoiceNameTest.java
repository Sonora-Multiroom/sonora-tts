package multiroom.tts.provider.cloud.google;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleVoiceNameTest {

    @Test
    void fullNameIsSplitIntoLanguageEngineAndVoice() {
        GoogleVoiceName name = GoogleVoiceName.parse("uk-ua-chirp3-hd-charon");

        assertThat(name).isEqualTo(new GoogleVoiceName.Full(GoogleLanguage.parse("uk-UA"), "chirp3-hd", "charon"));
        assertThat(name).hasToString("uk-UA-Chirp3-HD-Charon");
        assertThat(((GoogleVoiceName.Full) name).engine()).contains(GoogleEngine.CHIRP3_HD);
    }

    @Test
    void anEngineWithAHyphenSplitsAtTheLastHyphen() {
        GoogleVoiceName.Full name = (GoogleVoiceName.Full) GoogleVoiceName.parse("en-US-Chirp-HD-F");

        assertThat(name.engineSegment()).isEqualTo("Chirp-HD");
        assertThat(name.voice()).isEqualTo("F");
        assertThat(name.engine()).contains(GoogleEngine.CHIRP_HD);
    }

    @Test
    void anUnrecognizedEngineSegmentIsKeptAsWritten() {
        GoogleVoiceName.Full name = (GoogleVoiceName.Full) GoogleVoiceName.parse("en-US-Polyglot-1");

        assertThat(name.engineSegment()).isEqualTo("Polyglot");
        assertThat(name.voice()).isEqualTo("1");
        assertThat(name.engine()).isEmpty();
        assertThat(name).hasToString("en-US-Polyglot-1");
    }

    @Test
    void anUnrecognizedEngineSegmentIsNotRecapitalized() {
        assertThat(GoogleVoiceName.parse("en-us-news-k")).hasToString("en-US-news-K");
    }

    @Test
    void aNumericRegionIsAccepted() {
        assertThat(GoogleVoiceName.parse("es-419-neural2-a")).hasToString("es-419-Neural2-A");
    }

    @Test
    void shortNameIsCapitalized() {
        GoogleVoiceName name = GoogleVoiceName.parse("charon");

        assertThat(name).isEqualTo(new GoogleVoiceName.Short("charon"));
        assertThat(name).hasToString("Charon");
    }

    @Test
    void shortNameComposesWithEngineAndLanguage() {
        GoogleVoiceName.Full full = new GoogleVoiceName.Short("d")
                .compose(GoogleEngine.WAVENET, GoogleLanguage.parse("en-US"));

        assertThat(full).hasToString("en-US-Wavenet-D");
        assertThat(full.engine()).contains(GoogleEngine.WAVENET);
    }

    @ParameterizedTest
    @ValueSource(strings = {"uk-UA-Charon", "uk-UA-", "-Charon", "Char on", "Charon!", "uk-UA-Chirp3-HD-",
            "english-US-Neural2-C", "uk--Chirp3-HD-Charon"})
    @NullAndEmptySource
    void malformedNamesAreRejected(String input) {
        assertThat(GoogleVoiceName.isWellFormed(input)).isFalse();
        assertThatThrownBy(() -> GoogleVoiceName.parse(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"uk-UA-Chirp3-HD-Charon", "en-US-Neural2-C", "charon", "D", "en-US-Polyglot-1"})
    void wellFormedNamesAreAccepted(String input) {
        assertThat(GoogleVoiceName.isWellFormed(input)).isTrue();
    }
}
