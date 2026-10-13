package multiroom.tts.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import multiroom.api.model.TargetType;

/**
 * {@code POST /api/tts/speak} request body, matching {@code contracts/tts-rest-api.yaml} field for
 * field. {@code text}'s maximum length is operator-configured, so it is enforced by
 * {@code TtsService}, not by a static annotation here.
 *
 * <p>{@code engine} and {@code pitch} are {@code google-cloud} only; {@code speakingRate} is
 * accepted by {@code google-cloud} and {@code google-gemini}; {@code stylePrompt} is
 * {@code google-gemini} only. Each provider rejects a field it does not support, and range-checks
 * the ones it does, itself.
 *
 * <p>{@code playbackMode} is optional: {@code duck-others} or {@code mix}. {@code TtsService}
 * checks it, so an unknown value answers {@code INVALID_REQUEST} before any synthesis.
 */
public record SpeakRequest(
        @NotBlank(message = "Text must not be blank") String text,
        @Schema(description = "ID of the output or output group (not its display name): 1-255 letters,"
                + " digits, hyphens or underscores. Any other name answers 400 TARGET_NOT_FOUND")
        @NotBlank(message = "targetName must not be blank") String targetName,
        @NotNull(message = "targetType is required") TargetType targetType,
        String providerName,
        String voice,
        String language,
        String engine,
        Double pitch,
        Double speakingRate,
        String stylePrompt,
        String playbackMode) {
}
