package multiroom.tts;

import multiroom.api.conversion.FormatConverter;
import multiroom.api.services.DeviceQueryService;
import multiroom.api.services.DeviceRegistryService;
import multiroom.api.services.RouteService;
import multiroom.tts.audio.AudioConverter;
import multiroom.tts.audio.TtsInputResolver;
import multiroom.tts.cache.AudioCache;
import multiroom.tts.cache.FilesystemAudioCache;
import multiroom.tts.config.TtsProperties;
import multiroom.tts.config.TtsProviderConfig;
import multiroom.tts.provider.ProviderRegistry;
import multiroom.tts.provider.TtsProvider;
import multiroom.tts.provider.cloud.GoogleCloudTtsProvider;
import multiroom.tts.provider.cloud.GoogleGeminiTtsProvider;
import multiroom.tts.provider.cloud.OpenAiTtsProvider;
import multiroom.tts.provider.cloud.google.ServiceAccountKey;
import multiroom.tts.provider.local.LocalHttpTtsProvider;
import multiroom.tts.provider.local.PiperTtsProvider;
import multiroom.tts.service.PlaybackCompletionListener;
import multiroom.tts.service.TtsService;
import multiroom.tts.service.VoiceQueryService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The extension's entry point — named by
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * There is no {@code Extension} interface to implement and no {@code Extension-Class} manifest
 * attribute; 019 deleted both.
 *
 * <p>{@code matchIfMissing = true} so an upgrade never silently disables a working extension.
 * Core contains no knowledge of this module.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "multiroom.tts.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TtsProperties.class)
@ComponentScan("multiroom.tts")
public class TtsAutoConfiguration {

    @Bean
    public ProviderRegistry providerRegistry(TtsProperties properties) {
        Map<String, TtsProvider> providers = new LinkedHashMap<>();
        for (TtsProviderConfig config : properties.getProviders()) {
            if (config.isEnabled()) {
                providers.put(config.getName(), buildProvider(config, properties));
            }
        }
        return new ProviderRegistry(providers, properties.getDefaultProvider());
    }

    private TtsProvider buildProvider(TtsProviderConfig config, TtsProperties properties) {
        return switch (config.getType()) {
            case OPENAI -> new OpenAiTtsProvider(config);
            case GOOGLE_CLOUD -> new GoogleCloudTtsProvider(config, properties.getVoiceCatalogue(),
                    serviceAccountKey(config));
            case GOOGLE_GEMINI -> new GoogleGeminiTtsProvider(config, properties.getVoiceCatalogue(),
                    serviceAccountKey(config), properties.getMaxTextLength());
            case PIPER -> new PiperTtsProvider(config);
            case LOCAL_HTTP -> new LocalHttpTtsProvider(config);
        };
    }

    /**
     * Reads the entry's key file here, once, rather than in validation: validating it there would
     * read it twice or keep a private key in a configuration-properties bean. A local file read
     * during construction is allowed; a fault still aborts start-up naming the entry.
     */
    private static ServiceAccountKey serviceAccountKey(TtsProviderConfig config) {
        String file = config.getServiceAccountKeyFile();
        return file == null || file.isBlank() ? null : ServiceAccountKey.load(config.getName(), Path.of(file));
    }

    @Bean
    public VoiceQueryService voiceQueryService(TtsProperties properties, ProviderRegistry providerRegistry) {
        return new VoiceQueryService(properties, providerRegistry);
    }

    @Bean
    public AudioConverter audioConverter(FormatConverter formatConverter) {
        return new AudioConverter(formatConverter);
    }

    @Bean
    public AudioCache audioCache(TtsProperties properties) {
        return new FilesystemAudioCache(properties.getCache().getDir(), properties.getCache().getMaxSizeMb());
    }

    @Bean
    public TtsInputResolver ttsInputResolver() {
        return new TtsInputResolver();
    }

    @Bean
    public PlaybackCompletionListener playbackCompletionListener(RouteService routeService, AudioCache audioCache,
                                                                   TtsInputResolver ttsInputResolver) {
        return new PlaybackCompletionListener(routeService, audioCache, ttsInputResolver);
    }

    @Bean
    public TtsService ttsService(TtsProperties properties, ProviderRegistry providerRegistry,
                                  AudioConverter audioConverter, AudioCache audioCache,
                                  TtsInputResolver ttsInputResolver, DeviceRegistryService deviceRegistryService,
                                  DeviceQueryService deviceQueryService, RouteService routeService,
                                  PlaybackCompletionListener playbackCompletionListener) {
        return new TtsService(properties, providerRegistry, audioConverter, audioCache, ttsInputResolver,
                deviceRegistryService, deviceQueryService, routeService, playbackCompletionListener);
    }
}
