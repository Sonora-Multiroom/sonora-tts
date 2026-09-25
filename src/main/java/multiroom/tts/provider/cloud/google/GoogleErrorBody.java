package multiroom.tts.provider.cloud.google;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * Reads Google's explanation out of a failure body, the standard envelope
 * {@code {"error":{"code":…,"message":"…","status":"…"}}}.
 */
public final class GoogleErrorBody {

    /** Keeps a pathological body out of logs and responses. */
    private static final int MAX_LENGTH = 500;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GoogleErrorBody() {
    }

    /**
     * @return {@code error.message}, truncated to {@value #MAX_LENGTH} characters plus an
     *         ellipsis; empty for an empty, non-JSON or differently shaped body. Never throws.
     */
    public static Optional<String> message(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode message = MAPPER.readTree(body).path("error").path("message");
            if (!message.isTextual() || message.asText().isBlank()) {
                return Optional.empty();
            }
            String text = message.asText();
            return Optional.of(text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) + "…" : text);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }
}
