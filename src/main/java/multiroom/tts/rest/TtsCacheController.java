package multiroom.tts.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.cache.AudioCache;
import multiroom.tts.cache.CacheStats;
import multiroom.tts.config.TtsProperties;
import multiroom.tts.config.TtsProviderConfig;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.stream.Collectors;

/** {@code DELETE /api/tts/cache[?providerName=]} and {@code GET /api/tts/cache/stats}. */
@RestController
@RequestMapping("/api/tts/cache")
public class TtsCacheController {

    private final AudioCache audioCache;
    private final Set<String> configuredProviderNames;

    public TtsCacheController(AudioCache audioCache, TtsProperties properties) {
        this.audioCache = audioCache;
        this.configuredProviderNames = properties.getProviders().stream()
                .map(TtsProviderConfig::getName)
                .collect(Collectors.toUnmodifiableSet());
    }

    @DeleteMapping
    @Operation(summary = "Clear the audio cache", operationId = "clearCache")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Cache cleared successfully"),
            @ApiResponse(responseCode = "400", description = "Unknown provider name specified",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<Void> clearCache(@RequestParam(required = false) String providerName) {
        if (providerName != null) {
            if (!configuredProviderNames.contains(providerName)) {
                throw new TtsException(TtsErrorCode.PROVIDER_NOT_FOUND,
                        "No provider configured with name '" + providerName + "'");
            }
            audioCache.clearByProvider(providerName);
        } else {
            audioCache.clear();
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/stats")
    @Operation(summary = "Get audio cache statistics", operationId = "getCacheStats")
    public CacheStats stats() {
        return audioCache.stats();
    }
}
