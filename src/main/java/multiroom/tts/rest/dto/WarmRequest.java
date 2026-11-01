package multiroom.tts.rest.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/tts/cache/warm} request body: the voice fields of a speak request. The maximum
 * length of {@code text} is operator-configured, so it is enforced by {@code TtsService}, as for
 * {@link SpeakRequest}. Fields only a speak request has (target, playback mode, volume, ducking) are
 * ignored, so a speak body can be reused as it is.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WarmRequest(
        @NotBlank(message = "Text must not be blank") String text,
        String providerName,
        String voice,
        String language,
        String engine,
        Double pitch,
        Double speakingRate,
        String stylePrompt) {
}
