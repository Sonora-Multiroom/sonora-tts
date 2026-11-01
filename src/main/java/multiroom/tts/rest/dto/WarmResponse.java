package multiroom.tts.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** {@code POST /api/tts/cache/warm} answer. */
public record WarmResponse(
        @Schema(description = "true if the audio was already stored and no provider was called")
        boolean cacheHit) {
}
