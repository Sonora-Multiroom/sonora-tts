package multiroom.tts.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderTypeTest {

    @ParameterizedTest
    @CsvSource({
            "OPENAI, openai",
            "GOOGLE_CLOUD, google-cloud",
            "GOOGLE_GEMINI, google-gemini",
            "PIPER, piper",
            "LOCAL_HTTP, local-http"
    })
    void configNameIsTheSpellingOperatorsWriteInConfiguration(ProviderType type, String expected) {
        assertThat(type.configName()).isEqualTo(expected);
    }
}
