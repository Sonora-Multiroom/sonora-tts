package multiroom.tts.rest.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import multiroom.tts.cache.CacheStats;
import multiroom.tts.rest.dto.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** {@code DELETE /api/tts/cache[?providerName=]} and {@code GET /api/tts/cache/stats}. */
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
}
