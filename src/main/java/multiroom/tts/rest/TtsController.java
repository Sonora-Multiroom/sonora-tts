package multiroom.tts.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import multiroom.tts.service.AnnounceCommand;
import multiroom.tts.service.AnnounceResult;
import multiroom.tts.service.TtsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The external trigger for announcements. {@code /api/tts/**} is this module's own namespace —
 * {@code /api/v2/**} is {@code multiroom-rest}'s published contract and must not be touched here.
 * Adds no authentication of its own; the shared HTTP surface governs access.
 */
@RestController
@RequestMapping("/api/tts")
public class TtsController {

    private final TtsService ttsService;

    public TtsController(TtsService ttsService) {
        this.ttsService = ttsService;
    }

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
    public ResponseEntity<SpeakAcceptedResponse> speak(@Valid @RequestBody SpeakRequest request) {
        AnnounceResult result = ttsService.speak(new AnnounceCommand(
                request.text(), request.targetName(), request.targetType(),
                request.providerName(), request.voice(), request.language(),
                request.engine(), request.pitch(), request.speakingRate(), request.stylePrompt()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new SpeakAcceptedResponse(result.announcementId(), result.cacheHit(), result.queueDepth()));
    }
}
