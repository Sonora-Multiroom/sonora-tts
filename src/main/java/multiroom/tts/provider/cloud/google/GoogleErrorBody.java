package multiroom.tts.provider.cloud.google;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * Reads Google's explanation out of a failure body, in either of the two shapes Google uses: the
 * API envelope {@code {"error":{"code":…,"message":"…","status":"…"}}}, and the token service's
 * OAuth shape {@code {"error":"invalid_grant","error_description":"…"}}.
 */
public final class GoogleErrorBody {

    /** Keeps a pathological body out of logs and responses. */
    private static final int MAX_LENGTH = 500;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GoogleErrorBody() {
    }

    /**
     * @return {@code error.message}, or for the OAuth shape {@code "<error_description> (<error>)"}
     *         (the bare {@code error} when there is no description), truncated to
     *         {@value #MAX_LENGTH} characters plus an ellipsis; empty for an empty, non-JSON or
     *         differently shaped body. Never throws.
     */
    public static Optional<String> message(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode error = root.path("error");
            String text;
            if (error.isTextual()) {
                text = oauthMessage(error.asText(), root);
            } else {
                JsonNode message = error.path("message");
                text = message.isTextual() ? message.asText() : null;
            }
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) + "…" : text);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private static String oauthMessage(String error, JsonNode root) {
        if (error.isBlank()) {
            return null;
        }
        JsonNode description = root.path("error_description");
        return description.isTextual() && !description.asText().isBlank()
                ? description.asText() + " (" + error + ")" : error;
    }
}
