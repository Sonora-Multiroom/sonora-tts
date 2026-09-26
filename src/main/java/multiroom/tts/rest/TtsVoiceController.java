package multiroom.tts.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import multiroom.tts.service.VoiceQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/tts/providers/{providerName}/voices[?language=&engine=]}. For a
 * {@code google-cloud} entry it lists from the same in-memory catalogue that validates
 * {@code voice} on {@code POST /api/tts/speak}, so a listed voice is an accepted voice. For a
 * {@code google-gemini} entry it lists the Gemini voices Google publishes, which announcing does
 * not check against.
 */
@RestController
@RequestMapping("/api/tts/providers")
public class TtsVoiceController {

    private final VoiceQueryService voiceQueryService;

    public TtsVoiceController(VoiceQueryService voiceQueryService) {
        this.voiceQueryService = voiceQueryService;
    }

    @GetMapping("/{providerName}/voices")
    @Operation(summary = "List the voices a provider offers", operationId = "listVoices")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Matching voices; google-cloud: sorted by language, "
                    + "engine, then short name; google-gemini: sorted by name",
                    content = @Content(schema = @Schema(implementation = VoiceListResponse.class))),
            @ApiResponse(responseCode = "400", description = "Unknown provider, a type that does not list voices, or a malformed filter",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "The voice catalogue cannot be fetched now",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public VoiceListResponse listVoices(@PathVariable String providerName,
                                        @RequestParam(required = false) String language,
                                        @RequestParam(required = false) String engine) {
        return VoiceListResponse.of(providerName, voiceQueryService.listVoices(providerName, language, engine));
    }
}
