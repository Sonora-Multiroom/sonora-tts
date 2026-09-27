package multiroom.tts.rest.controller;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.cache.AudioCache;
import multiroom.tts.cache.CacheStats;
import multiroom.tts.config.TtsProperties;
import multiroom.tts.config.TtsProviderConfig;
import multiroom.tts.rest.api.TtsCacheApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.stream.Collectors;

/** Implements {@link TtsCacheApi} over the {@link AudioCache}. */
@RestController
public class TtsCacheController implements TtsCacheApi {

    private final AudioCache audioCache;
    private final Set<String> configuredProviderNames;

    public TtsCacheController(AudioCache audioCache, TtsProperties properties) {
        this.audioCache = audioCache;
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
}
