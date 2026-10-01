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
import multiroom.tts.metrics.MicrometerTtsMetrics;
import multiroom.tts.metrics.NoopTtsMetrics;
import multiroom.tts.metrics.TtsMetrics;
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
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

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
    public PlaybackCompletionListener playbackCompletionListener(AudioCache audioCache,
                                                                   TtsInputResolver ttsInputResolver) {
        return new PlaybackCompletionListener(audioCache, ttsInputResolver);
    }

    @Bean
    public TtsService ttsService(TtsProperties properties, ProviderRegistry providerRegistry,
                                  AudioConverter audioConverter, AudioCache audioCache,
                                  TtsInputResolver ttsInputResolver, DeviceRegistryService deviceRegistryService,
                                  DeviceQueryService deviceQueryService, RouteService routeService,
                                  PlaybackCompletionListener playbackCompletionListener, TtsMetrics ttsMetrics) {
        return new TtsService(properties, providerRegistry, audioConverter, audioCache, ttsInputResolver,
                deviceRegistryService, deviceQueryService, routeService, playbackCompletionListener, ttsMetrics);
    }

    /**
     * Records into the host's registry when it has one. The registry is resolved through {@code
     * ObjectProvider} when this bean is created, after every bean definition (the actuator's
     * included) is registered, so no auto-configuration ordering is needed. A host with
     * Micrometer but no registry gets the no-op: missing metrics are not a fault.
     *
     * <p>The class is named by string so that {@link TtsAutoConfiguration} itself still loads on
     * a host without Micrometer.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class MicrometerMetricsConfiguration {

        @Bean
        public TtsMetrics ttsMetrics(ObjectProvider<MeterRegistry> meterRegistry, AudioCache audioCache,
                                     TtsProperties properties, ProviderRegistry providerRegistry) {
            MeterRegistry registry = meterRegistry.getIfAvailable();
            if (registry == null) {
                return new NoopTtsMetrics();
            }
            return new MicrometerTtsMetrics(registry, audioCache, longestTimeoutSeconds(properties),
                    providerRegistry.names());
        }

        /** Disabled entries are not in the registry and never synthesize, so they do not count. */
        private static int longestTimeoutSeconds(TtsProperties properties) {
            return properties.getProviders().stream()
                    .filter(TtsProviderConfig::isEnabled)
                    .mapToInt(TtsProviderConfig::getTimeoutSeconds)
                    .max()
                    .orElse(new TtsProviderConfig().getTimeoutSeconds());
        }
    }

    /** A host without Micrometer: the extension runs as before and records nothing. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("io.micrometer.core.instrument.MeterRegistry")
    static class NoopMetricsConfiguration {

        @Bean
        public TtsMetrics ttsMetrics() {
            return new NoopTtsMetrics();
        }
    }
}
