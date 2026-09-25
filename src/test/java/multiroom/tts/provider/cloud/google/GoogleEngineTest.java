package multiroom.tts.provider.cloud.google;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleEngineTest {

    @ParameterizedTest
    @CsvSource({
            "standard, STANDARD",
            "Standard, STANDARD",
            "wavenet, WAVENET",
            "WaveNet, WAVENET",
            "neural2, NEURAL2",
            "NEURAL2, NEURAL2",
            "studio, STUDIO",
            "chirp-hd, CHIRP_HD",
            "Chirp-HD, CHIRP_HD",
            "chirphd, CHIRP_HD",
            "chirp3-hd, CHIRP3_HD",
            "Chirp3-HD, CHIRP3_HD",
            "Chirp3_HD, CHIRP3_HD",
            "chirp3hd, CHIRP3_HD"
    })
    void recognizesEveryAlias(String alias, GoogleEngine expected) {
        assertThat(GoogleEngine.fromName(alias)).contains(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "STANDARD, Standard",
            "WAVENET, Wavenet",
            "NEURAL2, Neural2",
            "STUDIO, Studio",
            "CHIRP_HD, Chirp-HD",
            "CHIRP3_HD, Chirp3-HD"
    })
    void canonicalSpellingIsGooglesOwn(GoogleEngine engine, String canonical) {
        assertThat(engine.canonical()).isEqualTo(canonical);
    }

    @ParameterizedTest
    @ValueSource(strings = {"chirp4", "polyglot", "News", " "})
    @NullAndEmptySource
    void unknownEnginesAreEmpty(String name) {
        assertThat(GoogleEngine.fromName(name)).isEmpty();
    }

    @Test
    void supportedListNamesEveryEngineInCanonicalSpelling() {
        assertThat(GoogleEngine.supportedList()).isEqualTo("Standard, Wavenet, Neural2, Studio, Chirp-HD, Chirp3-HD");
    }
}
