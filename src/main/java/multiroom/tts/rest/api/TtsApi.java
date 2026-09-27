package multiroom.tts.rest.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import multiroom.tts.rest.dto.ErrorResponse;
import multiroom.tts.rest.dto.SpeakAcceptedResponse;
import multiroom.tts.rest.dto.SpeakRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The external trigger for announcements. {@code /api/tts/**} is this module's own namespace —
 * {@code /api/v2/**} is {@code multiroom-rest}'s published contract and must not be touched here.
 * Adds no authentication of its own; the shared HTTP surface governs access.
 */
@Tag(name = "TTS", description = "Text-to-speech announcements to an output or a group")
@RequestMapping("/api/tts")
public interface TtsApi {

    @PostMapping("/speak")
    @Operation(summary = "Trigger a TTS announcement", operationId = "speak")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Request accepted; audio exists and is queued to play",
                    content = @Content(schema = @Schema(implementation = SpeakAcceptedResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request, unknown target, or unknown provider",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Provider unavailable, errored, or returned an unconvertible format",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<SpeakAcceptedResponse> speak(@Valid @RequestBody SpeakRequest request);
}
