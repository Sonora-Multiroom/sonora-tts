package multiroom.tts.rest.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import multiroom.tts.cache.CacheStats;
import jakarta.validation.Valid;
import multiroom.tts.rest.dto.ErrorResponse;
import multiroom.tts.rest.dto.WarmRequest;
import multiroom.tts.rest.dto.WarmResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * {@code DELETE /api/tts/cache[?providerName=]}, {@code GET /api/tts/cache/stats} and
 * {@code POST /api/tts/cache/warm}.
 */
@Tag(name = "TTS Cache", description = "The on-disk cache of synthesized audio")
@RequestMapping("/api/tts/cache")
public interface TtsCacheApi {

    @DeleteMapping
    @Operation(summary = "Clear the audio cache", operationId = "clearCache")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Cache cleared successfully"),
            @ApiResponse(responseCode = "400", description = "Unknown provider name specified",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    ResponseEntity<Void> clearCache(@RequestParam(required = false) String providerName);

    @GetMapping("/stats")
    @Operation(summary = "Get audio cache statistics", operationId = "getCacheStats")
    CacheStats stats();

    @PostMapping("/warm")
    @Operation(summary = "Synthesize and store a phrase without playing it", operationId = "warmCache",
            description = "Stores the audio so a later announcement of the same text and voice is served from the "
                    + "cache with no provider call. Nothing is played, queued or pinned. A phrase already stored "
                    + "costs nothing and counts as just used. The extension does not rate limit warm-ups")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The audio is stored (cacheHit false) or already was (true)"),
            @ApiResponse(responseCode = "400", description = "Invalid request or unknown provider",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "The provider failed, or the audio could not be stored "
                    + "(CACHE_WRITE_FAILED); nothing was stored and repeating the request is safe",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    WarmResponse warm(@Valid @RequestBody WarmRequest request);
}
