package multiroom.tts.provider.cloud.google;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleErrorBodyTest {

    @Test
    void extractsTheMessageFromGooglesErrorEnvelope() {
        String body = """
                {"error":{"code":400,"message":"Requested language code 'en-US' doesn't match the voice's language code 'uk-UA'.","status":"INVALID_ARGUMENT"}}
                """;

        assertThat(GoogleErrorBody.message(body))
                .contains("Requested language code 'en-US' doesn't match the voice's language code 'uk-UA'.");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"<html><body>Bad Gateway</body></html>", "{}", "{\"error\":{}}",
            "{\"error\":\"flat\"}", "{\"error\":{\"message\":\"\"}}", "[1,2]", "{\"error\":{\"message\":42}}"})
    void anythingElseGivesNoMessage(String body) {
        assertThat(GoogleErrorBody.message(body)).isEmpty();
    }

    @Test
    void aVeryLongMessageIsTruncatedTo500CharactersPlusAnEllipsis() {
        String longMessage = "x".repeat(2000);

        assertThat(GoogleErrorBody.message("{\"error\":{\"message\":\"" + longMessage + "\"}}"))
                .contains("x".repeat(500) + "…");
    }
}
