package multiroom.tts.rest.controller;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.cache.AudioCache;
import multiroom.tts.cache.CacheStats;
import multiroom.tts.config.TtsProperties;
import multiroom.tts.config.TtsProviderConfig;
import multiroom.tts.rest.api.TtsCacheApi;
import multiroom.tts.rest.dto.WarmRequest;
import multiroom.tts.rest.dto.WarmResponse;
import multiroom.tts.service.TtsService;
import multiroom.tts.service.WarmCommand;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.stream.Collectors;

/** Implements {@link TtsCacheApi} over the {@link AudioCache}, and warm-ups through {@link TtsService}. */
@RestController
public class TtsCacheController implements TtsCacheApi {

    private final AudioCache audioCache;
    private final TtsService ttsService;
    private final Set<String> configuredProviderNames;

    public TtsCacheController(AudioCache audioCache, TtsProperties properties, TtsService ttsService) {
        this.audioCache = audioCache;
        this.ttsService = ttsService;
        this.configuredProviderNames = properties.getProviders().stream()
                .map(TtsProviderConfig::getName)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public ResponseEntity<Void> clearCache(String providerName) {
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

    @Override
    public CacheStats stats() {
        return audioCache.stats();
    }

    @Override
    public WarmResponse warm(WarmRequest request) {
        boolean cacheHit = ttsService.warm(new WarmCommand(request.text(), request.providerName(), request.voice(),
                request.language(), request.engine(), request.pitch(), request.speakingRate(),
                request.stylePrompt()));
        return new WarmResponse(cacheHit);
    }
}
