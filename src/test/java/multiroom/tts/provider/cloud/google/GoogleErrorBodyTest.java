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
            "{\"error\":123}", "{\"error\":\"\"}", "{\"error\":{\"message\":\"\"}}", "[1,2]", "{\"error\":{\"message\":42}}"})
    void anythingElseGivesNoMessage(String body) {
        assertThat(GoogleErrorBody.message(body)).isEmpty();
    }

    @Test
    void aVeryLongMessageIsTruncatedTo500CharactersPlusAnEllipsis() {
        String longMessage = "x".repeat(2000);

        assertThat(GoogleErrorBody.message("{\"error\":{\"message\":\"" + longMessage + "\"}}"))
                .contains("x".repeat(500) + "…");
    }

    // --- 003: the token service's OAuth error shape -------------------------------------------

    @Test
    void theOAuthShapeGivesTheDescriptionAndTheErrorCode() {
        assertThat(GoogleErrorBody.message("{\"error\":\"invalid_grant\",\"error_description\":\"Invalid JWT Signature.\"}"))
                .contains("Invalid JWT Signature. (invalid_grant)");
    }

    @Test
    void theOAuthShapeWithNoDescriptionGivesTheBareError() {
        assertThat(GoogleErrorBody.message("{\"error\":\"invalid_scope\"}")).contains("invalid_scope");
    }

    @Test
    void aVeryLongOAuthDescriptionIsTruncatedTo500CharactersPlusAnEllipsis() {
        String body = "{\"error\":\"invalid_grant\",\"error_description\":\"" + "y".repeat(600) + "\"}";

        assertThat(GoogleErrorBody.message(body)).contains("y".repeat(500) + "…");
    }
}
