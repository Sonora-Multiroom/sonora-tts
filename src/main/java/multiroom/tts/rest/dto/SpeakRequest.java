package multiroom.tts.rest.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
        String playbackMode,
        @Schema(description = "Volume (0-100) of this announcement only. It replaces the target outputs' volume "
                + "for the announcement and changes no output's volume, so the room's volume control does not "
                + "reach it. Omitted: tts.playback.route-volume, else the announcement follows the outputs' volume")
        @Min(value = 0, message = "routeVolume must be at least 0")
        @Max(value = 100, message = "routeVolume must be at most 100") Integer routeVolume,
        @Schema(description = "How far this announcement lowers what else plays, in dB from -60.0 to 0.0. "
                + "Accepted only with playbackMode duck-others (400 with mix). Omitted: "
                + "tts.playback.ducking-level-db, else the hub's input default and system level")
        @DecimalMin(value = "-60.0", message = "duckingLevelDb must be at least -60.0")
        @DecimalMax(value = "0.0", message = "duckingLevelDb must be at most 0.0") Double duckingLevelDb) {
}
