package multiroom.tts.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import multiroom.api.conversion.FormatConverter;
import multiroom.api.model.AudioOutputDefinition;
import multiroom.api.model.GroupId;
import multiroom.api.model.InputId;
import multiroom.api.model.OutputGroup;
import multiroom.api.model.OutputId;
import multiroom.api.model.Route;
import multiroom.api.model.RouteId;
import multiroom.api.model.RouteStatus;
import multiroom.api.model.SampleFormat;
import multiroom.api.model.TargetType;
import multiroom.api.services.DeviceQueryService;
import multiroom.api.services.DeviceRegistryService;
import multiroom.api.services.RouteService;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.audio.AudioConverter;
import multiroom.tts.audio.TtsInputResolver;
import multiroom.tts.cache.AudioCache;
import multiroom.tts.cache.CacheKey;
import multiroom.tts.cache.CacheWriteException;
import multiroom.tts.config.ProviderType;
import multiroom.tts.config.QueueProperties;
import multiroom.tts.config.TtsProperties;
import multiroom.tts.config.TtsProviderConfig;
import multiroom.tts.config.VoiceCatalogueProperties;
import multiroom.tts.metrics.MicrometerTtsMetrics;
import multiroom.tts.metrics.SynthesisUsage;
import multiroom.tts.metrics.TtsMetrics;
import multiroom.tts.provider.DefaultSettingsResolution;
import multiroom.tts.provider.ProviderRegistry;
import multiroom.tts.provider.SynthesisRequest;
import multiroom.tts.provider.SynthesisResult;
import multiroom.tts.provider.SynthesisSettings;
import multiroom.tts.provider.TtsProvider;
import multiroom.tts.provider.cloud.GoogleCloudTtsProvider;
import multiroom.tts.provider.cloud.GoogleGeminiTtsProvider;
import multiroom.tts.provider.cloud.google.ServiceAccountKey;
import multiroom.tts.provider.cloud.google.TestServiceAccountKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TtsServiceTest {

    private static final long ASYNC_TIMEOUT_MS = 2000;

    private DeviceRegistryService deviceRegistryService;
    private DeviceQueryService deviceQueryService;
    private RouteService routeService;
    private AudioCache audioCache;
    private TtsInputResolver inputResolver;
    private PlaybackCompletionListener completionListener;
    private TtsProvider openaiProvider;
    private TtsProvider piperProvider;
    private AudioConverter audioConverter;
    private TtsMetrics metrics;
    private TtsMetrics.SynthesisTimer synthesisTimer;
    private int maxDepthPerTarget = 10;

    @BeforeEach
    void setUp() {
        metrics = mock(TtsMetrics.class);
        synthesisTimer = mock(TtsMetrics.SynthesisTimer.class);
        when(metrics.synthesisStarted(any())).thenReturn(synthesisTimer);
        deviceRegistryService = mock(DeviceRegistryService.class);
        deviceQueryService = mock(DeviceQueryService.class);
        routeService = mock(RouteService.class);
        audioCache = mock(AudioCache.class);
        inputResolver = mock(TtsInputResolver.class);
        completionListener = mock(PlaybackCompletionListener.class);
        openaiProvider = mock(TtsProvider.class);
        piperProvider = mock(TtsProvider.class);

        FormatConverter formatConverter = mock(FormatConverter.class);
        when(formatConverter.canConvert(any(), any())).thenReturn(true);
        when(formatConverter.convert(any(), any(), any())).thenAnswer(invocation -> {
            ByteBuffer input = invocation.getArgument(0);
            byte[] copy = new byte[input.remaining()];
            input.get(copy);
            return ByteBuffer.wrap(copy);
        });
        audioConverter = new AudioConverter(formatConverter);

        when(deviceQueryService.getOutput(OutputId.of("living-room")))
                .thenReturn(Optional.of(AudioOutputDefinition.builder()
                        .outputId(OutputId.of("living-room")).displayName("Living Room")
                        .uri("alsa://hw:0,0").enabled(true).available(true).build()));
        when(routeService.stopRoutesByOutput(any())).thenReturn(List.of());
        when(routeService.stopRoutesByGroup(any())).thenReturn(List.of());
    }

    private static byte[] fakeProviderWav() throws Exception {
        byte[] pcm = new byte[] {1, 2, 3, 4};
        AudioFormat format = new AudioFormat(24000, 16, 1, true, false);
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(pcm), format, 1)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, out);
            return out.toByteArray();
        }
    }

    private TtsProviderConfig providerConfig(String name, String voice, String language, String engine) {
        TtsProviderConfig config = new TtsProviderConfig();
        config.setName(name);
        config.setType(ProviderType.OPENAI);
        config.setApiKey("key");
        config.setVoice(voice);
        config.setLanguage(language);
        config.setEngine(engine);
        return config;
    }

    private TtsService serviceWith(TtsProviderConfig... configs) {
        TtsProperties properties = new TtsProperties();
        properties.setProviders(List.of(configs));
        properties.setMaxTextLength(500);
        QueueProperties queue = new QueueProperties();
        queue.setMaxDepthPerTarget(maxDepthPerTarget);
        properties.setQueue(queue);

        Map<String, TtsProvider> providers = Map.of("openai", openaiProvider, "piper-local", piperProvider);
        ProviderRegistry registry = new ProviderRegistry(providers, "openai");
        for (Map.Entry<String, TtsProvider> entry : providers.entrySet()) {
            TtsProviderConfig config = java.util.Arrays.stream(configs)
                    .filter(c -> c.getName().equals(entry.getKey()))
                    .findFirst()
                    .orElseGet(() -> providerConfig(entry.getKey(), null, null, null));
            when(entry.getValue().resolveSettings(any()))
                    .thenAnswer(inv -> DefaultSettingsResolution.resolve(config, inv.getArgument(0)));
        }

        return newService(properties, registry);
    }

    /** The one place a {@code TtsService} is built, so a new collaborator is added once. */
    private TtsService newService(TtsProperties properties, ProviderRegistry registry) {
        return new TtsService(properties, registry, audioConverter, audioCache, inputResolver,
                deviceRegistryService, deviceQueryService, routeService, completionListener, metrics);
    }

    @Test
    void singleOutputFlowSynthesizesConvertsRegistersAndRoutes() throws Exception {
        when(audioCache.get(any())).thenReturn(Optional.empty());
        when(openaiProvider.synthesize(any())).thenReturn(
                new SynthesisResult(fakeProviderWav(), 24000, 1, 16));
        when(audioCache.put(any(), any())).thenReturn(java.nio.file.Path.of("cache/hash.wav"));

        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));
        AnnounceResult result = service.speak(new AnnounceCommand("Dinner is ready", "living-room",
                TargetType.SINGLE_OUTPUT, null, null, null, null, null, null));

        assertThat(result.cacheHit()).isFalse();
        verify(openaiProvider).synthesize(any());
        verify(deviceRegistryService, timeout(ASYNC_TIMEOUT_MS)).registerInput(any());
        verify(routeService, timeout(ASYNC_TIMEOUT_MS)).createRoute(any(InputId.class), eq(OutputId.of("living-room")));
        verify(deviceRegistryService, never()).unregisterInput(any());
    }

    @Test
    void outputGroupTargetProducesExactlyOneCreateRouteCall() {
        when(deviceQueryService.getGroup(GroupId.of("all-rooms"))).thenReturn(Optional.of(
                OutputGroup.builder().groupId(GroupId.of("all-rooms")).displayName("All Rooms")
                        .outputIds(List.of(OutputId.of("living-room"), OutputId.of("kitchen"))).enabled(true).build()));
        when(audioCache.get(any())).thenReturn(Optional.of(java.nio.file.Path.of("cache/hit.wav")));

        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));
        service.speak(new AnnounceCommand("Motion detected", "all-rooms", TargetType.OUTPUT_GROUP, null, null, null, null, null, null));

        verify(routeService, timeout(ASYNC_TIMEOUT_MS)).createRoute(any(InputId.class), eq(GroupId.of("all-rooms")));
        verify(routeService, never()).createRoute(any(InputId.class), eq(OutputId.of("living-room")));
        verify(routeService, never()).createRoute(any(InputId.class), eq(OutputId.of("kitchen")));
    }

    @Test
    void groupWithNoOutputsRaisesTargetNotFound() {
        when(deviceQueryService.getGroup(GroupId.of("empty-group")))
                .thenReturn(Optional.of(OutputGroup.builder().groupId(GroupId.of("empty-group"))
                        .displayName("Empty").outputIds(List.of()).enabled(true).build()));

        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        assertThatThrownBy(() -> service.speak(new AnnounceCommand("Hi", "empty-group",
                TargetType.OUTPUT_GROUP, null, null, null, null, null, null)))
                .isInstanceOf(TtsException.class)
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.TARGET_NOT_FOUND);
    }

    @Test
    void unknownTargetRaisesTargetNotFound() {
        when(deviceQueryService.getOutput(OutputId.of("bedroom"))).thenReturn(Optional.empty());

        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        assertThatThrownBy(() -> service.speak(new AnnounceCommand("Hi", "bedroom",
                TargetType.SINGLE_OUTPUT, null, null, null, null, null, null)))
                .isInstanceOf(TtsException.class)
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.TARGET_NOT_FOUND);
    }

    @Test
    void cacheHitSkipsProviderCall() {
        when(audioCache.get(any())).thenReturn(Optional.of(java.nio.file.Path.of("cache/hash.wav")));

        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));
        AnnounceResult result = service.speak(new AnnounceCommand("Dinner is ready", "living-room",
                TargetType.SINGLE_OUTPUT, null, null, null, null, null, null));

        assertThat(result.cacheHit()).isTrue();
        verify(openaiProvider, never()).synthesize(any());
        verify(audioCache).pin(any());
    }

    @Test
    void cacheMissCallsProviderAndWritesCache() throws Exception {
        when(audioCache.get(any())).thenReturn(Optional.empty());
        when(openaiProvider.synthesize(any())).thenReturn(new SynthesisResult(fakeProviderWav(), 24000, 1, 16));
        when(audioCache.put(any(), any())).thenReturn(java.nio.file.Path.of("cache/hash.wav"));

        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));
        AnnounceResult result = service.speak(new AnnounceCommand("Dinner is ready", "living-room",
                TargetType.SINGLE_OUTPUT, null, null, null, null, null, null));

        assertThat(result.cacheHit()).isFalse();
        verify(openaiProvider).synthesize(any());
        verify(audioCache).put(any(), any());
    }

    @Test
    void diskFullCacheWriteFailureStillPlaysAudio() throws Exception {
        when(audioCache.get(any())).thenReturn(Optional.empty());
        when(openaiProvider.synthesize(any())).thenReturn(new SynthesisResult(fakeProviderWav(), 24000, 1, 16));
        when(audioCache.put(any(), any())).thenThrow(new CacheWriteException("disk full", new java.io.IOException()));

        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));
        AnnounceResult result = service.speak(new AnnounceCommand("Dinner is ready", "living-room",
                TargetType.SINGLE_OUTPUT, null, null, null, null, null, null));

        assertThat(result).isNotNull();
        verify(deviceRegistryService, timeout(ASYNC_TIMEOUT_MS)).registerInput(any());
        verify(routeService, timeout(ASYNC_TIMEOUT_MS)).createRoute(any(InputId.class), eq(OutputId.of("living-room")));
    }

    @Test
    void unknownProviderNameRaisesProviderNotFoundWithNoSynthesisCall() {
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        assertThatThrownBy(() -> service.speak(new AnnounceCommand("Hi", "living-room",
                TargetType.SINGLE_OUTPUT, "nonexistent", null, null, null, null, null)))
                .isInstanceOf(TtsException.class)
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.PROVIDER_NOT_FOUND);

        verifyNoInteractions(openaiProvider);
        verifyNoInteractions(piperProvider);
    }

    @Test
    void explicitProviderOverrideIsUsedInsteadOfDefault() throws Exception {
        when(audioCache.get(any())).thenReturn(Optional.empty());
        when(piperProvider.synthesize(any())).thenReturn(new SynthesisResult(fakeProviderWav(), 22050, 1, 16));
        when(audioCache.put(any(), any())).thenReturn(java.nio.file.Path.of("cache/hash.wav"));

        TtsService service = serviceWith(
                providerConfig("openai", "alloy", "en-US", "tts-1"),
                providerConfig("piper-local", "ryan", "en-US", "default"));

        service.speak(new AnnounceCommand("Hi", "living-room", TargetType.SINGLE_OUTPUT,
                "piper-local", null, null, null, null, null));

        verify(piperProvider).synthesize(any());
        verifyNoInteractions(openaiProvider);
    }

    @Test
    void voiceAndLanguageOverridesReachTheProviderAndChangeTheCacheKey() throws Exception {
        when(audioCache.get(any())).thenReturn(Optional.empty());
        when(openaiProvider.synthesize(any())).thenReturn(new SynthesisResult(fakeProviderWav(), 24000, 1, 16));
        when(audioCache.put(any(), any())).thenReturn(java.nio.file.Path.of("cache/hash.wav"));

        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));
        service.speak(new AnnounceCommand("Hi", "living-room", TargetType.SINGLE_OUTPUT,
                null, "nova", "fr-FR", null, null, null));

        verify(openaiProvider).synthesize(new SynthesisRequest("Hi", SynthesisSettings.of("nova", "fr-FR", "tts-1"), 48000, 2));

        var keyCaptor = org.mockito.ArgumentCaptor.forClass(CacheKey.class);
        verify(audioCache).get(keyCaptor.capture());
        assertThat(keyCaptor.getValue().voice()).isEqualTo("nova");
        assertThat(keyCaptor.getValue().language()).isEqualTo("fr-FR");

        CacheKey defaultsKey = new CacheKey("Hi", "openai", "tts-1", "alloy", "en-US", SampleFormat.standard());
        assertThat(keyCaptor.getValue()).isNotEqualTo(defaultsKey);
    }

    @Test
    void providerTimeoutPropagatesAsAnErrorRegardlessOfQueueState() {
        when(audioCache.get(any())).thenReturn(Optional.empty());
        when(openaiProvider.synthesize(any()))
                .thenThrow(new TtsException(TtsErrorCode.PROVIDER_TIMEOUT, "timed out"));

        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        assertThatThrownBy(() -> service.speak(new AnnounceCommand("Hi", "living-room",
                TargetType.SINGLE_OUTPUT, null, null, null, null, null, null)))
                .isInstanceOf(TtsException.class)
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.PROVIDER_TIMEOUT);
    }

    @Test
    void emptyOrOverLongTextIsRejectedBeforeAnyProviderCall() {
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        assertThatThrownBy(() -> service.speak(new AnnounceCommand("  ", "living-room",
                TargetType.SINGLE_OUTPUT, null, null, null, null, null, null)))
                .isInstanceOf(TtsException.class)
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.INVALID_REQUEST);

        verifyNoInteractions(openaiProvider);
    }

    @Test
    void routeCreationFailureCancelsTrackingAndUnregistersTheOrphanedInput() {
        when(audioCache.get(any())).thenReturn(Optional.of(java.nio.file.Path.of("cache/hit.wav")));
        when(routeService.createRoute(any(InputId.class), eq(OutputId.of("living-room"))))
                .thenThrow(new RuntimeException("output disappeared"));

        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));
        service.speak(new AnnounceCommand("Hi", "living-room", TargetType.SINGLE_OUTPUT, null, null, null, null, null, null));

        // No RouteDestroyedEvent will ever arrive for a route that was never created, so the
        // service must clean up directly instead of leaking the tracked entry, the cache pin
        // and the orphaned ephemeral input registration.
        verify(completionListener, timeout(ASYNC_TIMEOUT_MS)).cancel(any(InputId.class));
        verify(deviceRegistryService, timeout(ASYNC_TIMEOUT_MS)).unregisterInput(any(InputId.class));
    }

    @Test
    void enqueueFailureUnpinsTheCacheEntryRatherThanLeakingItForever() {
        when(audioCache.get(any())).thenReturn(Optional.of(java.nio.file.Path.of("cache/hit.wav")));

        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));
        service.stop();

        assertThatThrownBy(() -> service.speak(new AnnounceCommand("Hi", "living-room",
                TargetType.SINGLE_OUTPUT, null, null, null, null, null, null)))
                .isInstanceOf(TtsException.class);

        CacheKey cacheKey = new CacheKey("Hi", "openai", "tts-1", "alloy", "en-US", SampleFormat.standard());
        verify(audioCache).unpin(cacheKey);
    }

    @Test
    void enqueueFailureRestoresTheRoutesTheTargetWasAlreadyStrippedOf() {
        Route existing = Route.builder().routeId(RouteId.of("route-1")).inputId(InputId.of("music"))
                .targetType(TargetType.SINGLE_OUTPUT).targetId("living-room").status(RouteStatus.ACTIVE)
                .createdAt(Instant.now()).build();
        when(routeService.stopRoutesByOutput(OutputId.of("living-room"))).thenReturn(List.of(existing));
        when(audioCache.get(any())).thenReturn(Optional.of(java.nio.file.Path.of("cache/hit.wav")));

        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));
        service.stop();

        assertThatThrownBy(() -> service.speak(new AnnounceCommand("Hi", "living-room",
                TargetType.SINGLE_OUTPUT, null, null, null, null, null, null)))
                .isInstanceOf(TtsException.class);

        // The announcement will never play, so whatever was playing before must come back —
        // otherwise the target stays silent until an unrelated route command arrives.
        verify(completionListener).restoreRoutes(eq(List.of(existing)), any(java.util.UUID.class));
    }

    @Test
    void snapshotsAndStopsRoutesAlreadyOnTheTargetBeforeQueuing() {
        Route existing = Route.builder().routeId(RouteId.of("route-1")).inputId(InputId.of("music"))
                .targetType(TargetType.SINGLE_OUTPUT).targetId("living-room").status(RouteStatus.ACTIVE)
                .createdAt(Instant.now()).build();
        when(routeService.stopRoutesByOutput(OutputId.of("living-room"))).thenReturn(List.of(existing));
        when(audioCache.get(any())).thenReturn(Optional.of(java.nio.file.Path.of("cache/hit.wav")));

        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));
        service.speak(new AnnounceCommand("Hi", "living-room", TargetType.SINGLE_OUTPUT, null, null, null, null, null, null));

        verify(routeService).stopRoutesByOutput(OutputId.of("living-room"));
    }

    @Test
    void googleOnlyOverrideOnAnOpenAiProviderIsRejectedBeforeAnySynthesis() {
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        assertThatThrownBy(() -> service.speak(new AnnounceCommand("Hi", "living-room",
                TargetType.SINGLE_OUTPUT, null, null, null, null, -2.0, null)))
                .isInstanceOf(TtsException.class)
                .hasMessageContaining("pitch")
                .hasMessageContaining("OPENAI")
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.INVALID_REQUEST);

        verify(openaiProvider, never()).synthesize(any());
        verify(audioCache, never()).get(any());
    }

    @Test
    void theCacheKeyCarriesTheProvidersVoiceKeyNotTheCallersSpelling() {
        TtsProvider googleProvider = mock(TtsProvider.class);
        when(googleProvider.resolveSettings(any())).thenReturn(new SynthesisSettings("uk-UA-Chirp3-HD-Charon",
                "uk-ua-chirp3-hd-charon", "uk-UA-Chirp3-HD-Charon", "uk-UA", null, null, null, null, null));
        when(audioCache.get(any())).thenReturn(Optional.of(java.nio.file.Path.of("cache/hit.wav")));

        TtsProperties properties = new TtsProperties();
        properties.setProviders(List.of(providerConfig("google", null, null, null)));
        TtsService service = newService(properties, new ProviderRegistry(Map.of("google", googleProvider), "google"));

        service.speak(new AnnounceCommand("Hi", "living-room", TargetType.SINGLE_OUTPUT, "google",
                "uk-UA-Chirp3-HD-Charon", null, null, null, null));

        var keyCaptor = org.mockito.ArgumentCaptor.forClass(CacheKey.class);
        verify(audioCache).get(keyCaptor.capture());
        assertThat(keyCaptor.getValue().voice()).isEqualTo("uk-ua-chirp3-hd-charon");
        assertThat(keyCaptor.getValue().engineName()).isNull();
        assertThat(keyCaptor.getValue().language()).isEqualTo("uk-UA");
        verify(googleProvider, never()).synthesize(any());
    }

    // --- 002: a real GoogleCloudTtsProvider decides the key -----------------------------------

    /** Production's shape: a full voice, a language, no engine. */
    private static TtsProviderConfig productionGoogleEntry() {
        TtsProviderConfig google = new TtsProviderConfig();
        google.setName("google");
        google.setType(ProviderType.GOOGLE_CLOUD);
        google.setApiKey("key");
        google.setLanguage("en-US");
        google.setVoice("en-US-Neural2-C");
        return google;
    }

    private TtsService realGoogleService(WireMockServer server) {
        return realGoogleService(server, productionGoogleEntry(), null);
    }

    private TtsService realGoogleService(WireMockServer server, TtsProviderConfig google, ServiceAccountKey key) {
        TtsProperties properties = new TtsProperties();
        properties.setProviders(List.of(google));
        GoogleCloudTtsProvider provider = new GoogleCloudTtsProvider(google, new VoiceCatalogueProperties(), key,
                URI.create(server.baseUrl() + "/v1/"), Clock.systemUTC());
        return newService(properties, new ProviderRegistry(Map.of("google", provider), "google"));
    }

    private static AnnounceCommand googleCommand(String voice, String engine, String language, Double pitch,
                                                 Double speakingRate) {
        return new AnnounceCommand("Dinner is ready", "living-room", TargetType.SINGLE_OUTPUT, "google",
                voice, language, engine, pitch, speakingRate);
    }

    private List<CacheKey> lookedUpKeys(int expected) {
        var keyCaptor = org.mockito.ArgumentCaptor.forClass(CacheKey.class);
        verify(audioCache, org.mockito.Mockito.times(expected)).get(keyCaptor.capture());
        return keyCaptor.getAllValues();
    }

    @Test
    void us2_4_aFullNameAndTheEquivalentShortNameHitOneEntryWithNoHttpCall() {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            when(audioCache.get(any())).thenReturn(Optional.of(java.nio.file.Path.of("cache/hit.wav")));
            TtsService service = realGoogleService(server);

            service.speak(googleCommand("charon", "chirp3-hd", "uk-UA", null, null));
            service.speak(googleCommand("uk-UA-Chirp3-HD-Charon", null, null, null, null));

            List<CacheKey> keys = lookedUpKeys(2);
            assertThat(keys.get(0)).isEqualTo(keys.get(1));
            assertThat(keys.get(0).toHash()).isEqualTo(keys.get(1).toHash());
            // A hit costs no catalogue and no synthesis request.
            server.verify(0, anyRequestedFor(anyUrl()));
        } finally {
            server.stop();
        }
    }

    @Test
    void sc006_aMissConsultsTheCatalogueOnceAndSynthesizesOnce() throws Exception {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            String catalogue;
            try (java.io.InputStream in = getClass().getResourceAsStream("/google-voices.json")) {
                catalogue = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            server.stubFor(get(urlPathEqualTo("/v1/voices")).willReturn(aResponse().withStatus(200).withBody(catalogue)));
            server.stubFor(post(urlPathEqualTo("/v1/text:synthesize")).willReturn(aResponse().withStatus(200)
                    .withBody("{\"audioContent\":\"" + java.util.Base64.getEncoder().encodeToString(fakeProviderWav()) + "\"}")));
            when(audioCache.get(any())).thenReturn(Optional.empty());
            when(audioCache.put(any(), any())).thenReturn(java.nio.file.Path.of("cache/hash.wav"));

            AnnounceResult result = realGoogleService(server).speak(googleCommand("charon", "chirp3-hd", "uk-UA", null, null));

            assertThat(result.cacheHit()).isFalse();
            server.verify(1, getRequestedFor(urlPathEqualTo("/v1/voices")));
            server.verify(1, postRequestedFor(urlPathEqualTo("/v1/text:synthesize")));
        } finally {
            server.stop();
        }
    }

    @Test
    void us4_4_aRateChangeIsADifferentEntryButAnExplicitDefaultIsNot() {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            when(audioCache.get(any())).thenReturn(Optional.of(java.nio.file.Path.of("cache/hit.wav")));
            TtsService service = realGoogleService(server);

            service.speak(googleCommand(null, null, null, null, null));
            service.speak(googleCommand(null, null, null, null, 0.9));
            service.speak(googleCommand(null, null, null, 0.0, 1.0));

            List<CacheKey> keys = lookedUpKeys(3);
            assertThat(keys.get(1).toHash()).isNotEqualTo(keys.get(0).toHash());
            assertThat(keys.get(2).toHash()).isEqualTo(keys.get(0).toHash());
            server.verify(0, anyRequestedFor(anyUrl()));
        } finally {
            server.stop();
        }
    }

    @Test
    void aNonGoogleCacheKeyHashesExactlyAsIn001() {
        when(audioCache.get(any())).thenReturn(Optional.of(java.nio.file.Path.of("cache/hit.wav")));

        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));
        service.speak(new AnnounceCommand("hello", "living-room", TargetType.SINGLE_OUTPUT, null, null, null,
                null, null, null));

        // SHA-256 of 001's key for these fields; see CacheKeyTest.
        assertThat(lookedUpKeys(1).get(0).toHash())
                .isEqualTo("dab14075da8e7d0f2e73edcf312004775587448c85e231c2d70bd186e1201a58");
    }

    // --- 003: how an entry authenticates never enters the cache key ---------------------------

    @Test
    void theCredentialNeverEntersTheCacheKeyAndAHitNeverAsksForAToken(@TempDir java.nio.file.Path keyDir) {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            when(audioCache.get(any())).thenReturn(Optional.of(java.nio.file.Path.of("cache/hit.wav")));
            TtsProviderConfig serviceAccountEntry = productionGoogleEntry();
            serviceAccountEntry.setApiKey(null);
            serviceAccountEntry.setServiceAccountKeyFile("sa.json");
            ServiceAccountKey key = ServiceAccountKey.load("google",
                    TestServiceAccountKeys.write(keyDir, server.baseUrl() + "/token"));

            realGoogleService(server).speak(googleCommand("charon", "chirp3-hd", "uk-UA", null, null));
            realGoogleService(server, serviceAccountEntry, key).speak(googleCommand("charon", "chirp3-hd", "uk-UA", null, null));

            List<CacheKey> keys = lookedUpKeys(2);
            assertThat(keys.get(0)).isEqualTo(keys.get(1));
            assertThat(keys.get(0).toHash()).isEqualTo(keys.get(1).toHash());
            // A hit returns before the provider is called: no token, no catalogue, no synthesis.
            server.verify(0, anyRequestedFor(anyUrl()));
        } finally {
            server.stop();
        }
    }

    // --- 004: a real GoogleGeminiTtsProvider decides the key -----------------------------------

    private static TtsProviderConfig geminiEntry() {
        TtsProviderConfig config = new TtsProviderConfig();
        config.setName("gemini");
        config.setType(ProviderType.GOOGLE_GEMINI);
        config.setServiceAccountKeyFile("sa.json");
        config.setModel("gemini-2.5-flash-tts");
        config.setVoice("Kore");
        config.setLanguage("en-US");
        return config;
    }

    private TtsService realGeminiService(WireMockServer server, ServiceAccountKey key) {
        TtsProperties properties = new TtsProperties();
        TtsProviderConfig gemini = geminiEntry();
        properties.setProviders(List.of(gemini));
        GoogleGeminiTtsProvider provider = new GoogleGeminiTtsProvider(gemini, new VoiceCatalogueProperties(), key,
                properties.getMaxTextLength(), URI.create(server.baseUrl() + "/v1/"), Clock.systemUTC());
        return newService(properties, new ProviderRegistry(Map.of("gemini", provider), "gemini"));
    }

    private static AnnounceCommand geminiCommand(String voice, String language, Double speakingRate) {
        return new AnnounceCommand("Dinner is ready", "living-room", TargetType.SINGLE_OUTPUT, "gemini",
                voice, language, null, null, speakingRate, null);
    }

    private static AnnounceCommand geminiCommandWithPrompt(String stylePrompt) {
        return new AnnounceCommand("Dinner is ready", "living-room", TargetType.SINGLE_OUTPUT, "gemini",
                null, null, null, null, null, stylePrompt);
    }

    private TtsService realGeminiServiceWithDefaultPrompt(WireMockServer server, ServiceAccountKey key,
                                                           String defaultPrompt) {
        TtsProperties properties = new TtsProperties();
        TtsProviderConfig gemini = geminiEntry();
        gemini.setStylePrompt(defaultPrompt);
        properties.setProviders(List.of(gemini));
        GoogleGeminiTtsProvider provider = new GoogleGeminiTtsProvider(gemini, new VoiceCatalogueProperties(), key,
                properties.getMaxTextLength(), URI.create(server.baseUrl() + "/v1/"), Clock.systemUTC());
        return newService(properties, new ProviderRegistry(Map.of("gemini", provider), "gemini"));
    }

    /** A statefully backed cache mock, so a repeated announcement is a genuine hit. */
    private void useStatefulCache() {
        Map<CacheKey, java.nio.file.Path> stored = new java.util.HashMap<>();
        when(audioCache.get(any())).thenAnswer(inv -> Optional.ofNullable(stored.get(inv.getArgument(0))));
        when(audioCache.put(any(), any())).thenAnswer(inv -> {
            CacheKey key = inv.getArgument(0);
            java.nio.file.Path path = java.nio.file.Path.of("cache/" + key.toHash() + ".wav");
            stored.put(key, path);
            return path;
        });
    }

    private void stubGeminiToken(WireMockServer server) {
        server.stubFor(post(urlPathEqualTo("/token")).willReturn(
                aResponse().withStatus(200).withBody("{\"access_token\":\"ya29.test\",\"expires_in\":3599}")));
    }

    private void stubGeminiSynthesis(WireMockServer server) throws Exception {
        server.stubFor(post(urlPathEqualTo("/v1/text:synthesize")).willReturn(aResponse().withStatus(200)
                .withBody("{\"audioContent\":\"" + java.util.Base64.getEncoder().encodeToString(fakeProviderWav()) + "\"}")));
    }

    @Test
    void gemini_repeatedAnnouncementIsAHitWithNoHttpCall(@TempDir java.nio.file.Path keyDir) throws Exception {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            stubGeminiToken(server);
            stubGeminiSynthesis(server);
            useStatefulCache();
            ServiceAccountKey key = ServiceAccountKey.load("gemini", TestServiceAccountKeys.write(keyDir, server.baseUrl() + "/token"));
            TtsService service = realGeminiService(server, key);

            AnnounceResult first = service.speak(geminiCommand(null, null, null));
            server.resetRequests();
            AnnounceResult second = service.speak(geminiCommand(null, null, null));

            assertThat(first.cacheHit()).isFalse();
            assertThat(second.cacheHit()).isTrue();
            server.verify(0, anyRequestedFor(anyUrl()));
        } finally {
            server.stop();
        }
    }

    @Test
    void gemini_voiceKORECaseInsensitivelyHitsTheEntryCreatedWithKore(@TempDir java.nio.file.Path keyDir) throws Exception {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            stubGeminiToken(server);
            stubGeminiSynthesis(server);
            useStatefulCache();
            ServiceAccountKey key = ServiceAccountKey.load("gemini", TestServiceAccountKeys.write(keyDir, server.baseUrl() + "/token"));
            TtsService service = realGeminiService(server, key);

            service.speak(geminiCommand("Kore", null, null));
            server.resetRequests();
            AnnounceResult second = service.speak(geminiCommand("KORE", null, null));

            assertThat(second.cacheHit()).isTrue();
            server.verify(0, anyRequestedFor(anyUrl()));
        } finally {
            server.stop();
        }
    }

    @Test
    void gemini_aDifferentLanguageOrVoiceIsASeparateCacheEntry(@TempDir java.nio.file.Path keyDir) throws Exception {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            stubGeminiToken(server);
            stubGeminiSynthesis(server);
            useStatefulCache();
            ServiceAccountKey key = ServiceAccountKey.load("gemini", TestServiceAccountKeys.write(keyDir, server.baseUrl() + "/token"));
            TtsService service = realGeminiService(server, key);

            service.speak(geminiCommand(null, null, null));
            AnnounceResult differentLanguage = service.speak(geminiCommand(null, "uk-UA", null));
            AnnounceResult differentVoice = service.speak(geminiCommand("Charon", null, null));

            assertThat(differentLanguage.cacheHit()).isFalse();
            assertThat(differentVoice.cacheHit()).isFalse();
            server.verify(3, postRequestedFor(urlPathEqualTo("/v1/text:synthesize")));
        } finally {
            server.stop();
        }
    }

    @Test
    void gemini_threeDistinctPromptsAreThreeCacheEntriesAndEachRepeatIsAHit(@TempDir java.nio.file.Path keyDir)
            throws Exception {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            stubGeminiToken(server);
            stubGeminiSynthesis(server);
            useStatefulCache();
            ServiceAccountKey key = ServiceAccountKey.load("gemini", TestServiceAccountKeys.write(keyDir, server.baseUrl() + "/token"));
            TtsService service = realGeminiServiceWithDefaultPrompt(server, key, "Say this calmly.");

            AnnounceResult noPrompt = service.speak(geminiCommandWithPrompt(null));
            AnnounceResult otherPrompt = service.speak(geminiCommandWithPrompt("Announce this urgently."));
            AnnounceResult emptyPrompt = service.speak(geminiCommandWithPrompt(""));

            assertThat(noPrompt.cacheHit()).isFalse();
            assertThat(otherPrompt.cacheHit()).isFalse();
            assertThat(emptyPrompt.cacheHit()).isFalse();
            server.verify(3, postRequestedFor(urlPathEqualTo("/v1/text:synthesize")));

            server.resetRequests();
            assertThat(service.speak(geminiCommandWithPrompt(null)).cacheHit()).isTrue();
            assertThat(service.speak(geminiCommandWithPrompt("Announce this urgently.")).cacheHit()).isTrue();
            assertThat(service.speak(geminiCommandWithPrompt("")).cacheHit()).isTrue();
            server.verify(0, anyRequestedFor(anyUrl()));
        } finally {
            server.stop();
        }
    }

    @Test
    void gemini_anEmptyAndABlankRequestPromptShareOneEntry(@TempDir java.nio.file.Path keyDir) throws Exception {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            stubGeminiToken(server);
            stubGeminiSynthesis(server);
            useStatefulCache();
            ServiceAccountKey key = ServiceAccountKey.load("gemini", TestServiceAccountKeys.write(keyDir, server.baseUrl() + "/token"));
            TtsService service = realGeminiServiceWithDefaultPrompt(server, key, "Say this calmly.");

            service.speak(geminiCommandWithPrompt(""));
            server.resetRequests();
            AnnounceResult blank = service.speak(geminiCommandWithPrompt("  "));

            assertThat(blank.cacheHit()).isTrue();
            server.verify(0, anyRequestedFor(anyUrl()));
        } finally {
            server.stop();
        }
    }

    @Test
    void gemini_withNoDefaultConfiguredNoPromptAndAnEmptyPromptShareOneEntry(@TempDir java.nio.file.Path keyDir)
            throws Exception {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            stubGeminiToken(server);
            stubGeminiSynthesis(server);
            useStatefulCache();
            ServiceAccountKey key = ServiceAccountKey.load("gemini", TestServiceAccountKeys.write(keyDir, server.baseUrl() + "/token"));
            TtsService service = realGeminiService(server, key);

            service.speak(geminiCommandWithPrompt(null));
            server.resetRequests();
            AnnounceResult empty = service.speak(geminiCommandWithPrompt(""));

            assertThat(empty.cacheHit()).isTrue();
            server.verify(0, anyRequestedFor(anyUrl()));
        } finally {
            server.stop();
        }
    }

    @Test
    void gemini_theCacheKeysEngineNameIsTheModel(@TempDir java.nio.file.Path keyDir) throws Exception {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            stubGeminiToken(server);
            stubGeminiSynthesis(server);
            when(audioCache.get(any())).thenReturn(Optional.of(java.nio.file.Path.of("cache/hit.wav")));
            ServiceAccountKey key = ServiceAccountKey.load("gemini", TestServiceAccountKeys.write(keyDir, server.baseUrl() + "/token"));
            TtsService service = realGeminiService(server, key);

            service.speak(geminiCommand(null, null, null));

            assertThat(lookedUpKeys(1).get(0).engineName()).isEqualTo("gemini-2.5-flash-tts");
        } finally {
            server.stop();
        }
    }

    // --- 005: announcements are counted ------------------------------------------------------

    private static AnnounceCommand speakTo(String target, String provider) {
        return new AnnounceCommand("Dinner is ready", target, TargetType.SINGLE_OUTPUT, provider,
                null, null, null, null, null);
    }

    private void verifyOnlyRejection(String provider, String error, String reason) {
        verify(metrics).announcementRejected(provider, error, reason);
        verify(metrics, never()).announcementAccepted(any());
        verify(metrics, times(1)).announcementRejected(any(), any(), any());
    }

    @Test
    void anAcceptedAnnouncementIsCountedOnceUnderItsProvider() {
        when(audioCache.get(any())).thenReturn(Optional.of(java.nio.file.Path.of("cache/hit.wav")));
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        service.speak(speakTo("living-room", "piper-local"));

        verify(metrics, times(1)).announcementAccepted("piper-local");
        verify(metrics, never()).announcementRejected(any(), any(), any());
    }

    @Test
    void anAnnouncementNamingNoProviderIsCountedUnderTheDefault() {
        when(audioCache.get(any())).thenReturn(Optional.of(java.nio.file.Path.of("cache/hit.wav")));
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        service.speak(speakTo("living-room", null));

        verify(metrics, times(1)).announcementAccepted("openai");
    }

    @Test
    void aMissingTargetIsCountedAsRejectedUnderTheProvider() {
        when(deviceQueryService.getOutput(OutputId.of("bedroom"))).thenReturn(Optional.empty());
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        assertThatThrownBy(() -> service.speak(speakTo("bedroom", null))).isInstanceOf(TtsException.class);

        verifyOnlyRejection("openai", "TARGET_NOT_FOUND", "none");
    }

    @Test
    void anUnconfiguredProviderNameIsNeverUsedAsATag() {
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        assertThatThrownBy(() -> service.speak(speakTo("living-room", "nope"))).isInstanceOf(TtsException.class);

        verifyOnlyRejection("unknown", "PROVIDER_NOT_FOUND", "none");
    }

    @Test
    void blankTextIsCountedAsAnInvalidRequest() {
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        assertThatThrownBy(() -> service.speak(new AnnounceCommand(" ", "living-room", TargetType.SINGLE_OUTPUT,
                null, null, null, null, null, null))).isInstanceOf(TtsException.class);

        verifyOnlyRejection("openai", "INVALID_REQUEST", "none");
    }

    @Test
    void aFullQueueIsCountedWithItsReason() {
        maxDepthPerTarget = 1;
        when(audioCache.get(any())).thenReturn(Optional.of(java.nio.file.Path.of("cache/hit.wav")));
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));
        // The mocked completion listener never signals completion, so the first announcement
        // occupies the worker for good and the second fills the one queue slot.
        service.speak(speakTo("living-room", null));
        verify(routeService, timeout(ASYNC_TIMEOUT_MS)).createRoute(any(InputId.class), any(OutputId.class));
        service.speak(speakTo("living-room", null));

        assertThatThrownBy(() -> service.speak(speakTo("living-room", null))).isInstanceOf(TtsException.class);

        verify(metrics).announcementRejected("openai", "PROVIDER_ERROR", "queue_full");
        verify(metrics, times(2)).announcementAccepted("openai");
    }

    @Test
    void aShutdownIsCountedWithItsReason() {
        when(audioCache.get(any())).thenReturn(Optional.of(java.nio.file.Path.of("cache/hit.wav")));
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));
        service.stop();

        assertThatThrownBy(() -> service.speak(speakTo("living-room", null))).isInstanceOf(TtsException.class);

        verifyOnlyRejection("openai", "PROVIDER_ERROR", "shutting_down");
    }

    @Test
    void anUnexpectedFailureIsCountedAsInternalAndStillPropagates() {
        IllegalStateException failure = new IllegalStateException("route service down");
        when(routeService.stopRoutesByOutput(any())).thenThrow(failure);
        when(audioCache.get(any())).thenReturn(Optional.of(java.nio.file.Path.of("cache/hit.wav")));
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        assertThatThrownBy(() -> service.speak(speakTo("living-room", null))).isSameAs(failure);

        verifyOnlyRejection("openai", "INTERNAL", "none");
    }

    // --- 005: synthesis is measured ---------------------------------------------------------

    private static final java.nio.file.Path HIT = java.nio.file.Path.of("cache/hit.wav");

    /** A 24 kHz mono WAV carrying {@code pcmBytes} of silence. */
    private static byte[] providerWav(int pcmBytes) throws Exception {
        AudioFormat format = new AudioFormat(24000, 16, 1, true, false);
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(new byte[pcmBytes]), format,
                pcmBytes / 2)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, out);
            return out.toByteArray();
        }
    }

    private SynthesisUsage capturedUsage() {
        ArgumentCaptor<SynthesisUsage> usage = ArgumentCaptor.forClass(SynthesisUsage.class);
        verify(metrics).synthesisStarted(usage.capture());
        return usage.getValue();
    }

    /** Fills the one queue slot, so the next announcement to living-room is refused. */
    private void occupyTheQueue(TtsService service) {
        service.speak(speakTo("living-room", null));
        verify(routeService, timeout(ASYNC_TIMEOUT_MS)).createRoute(any(InputId.class), any(OutputId.class));
        service.speak(speakTo("living-room", null));
    }

    @Test
    void aMissMeasuresTheWorkBeforeCallingTheProviderThenRecordsSuccess() throws Exception {
        when(audioCache.get(any())).thenReturn(Optional.empty());
        when(audioCache.put(any(), any())).thenReturn(HIT);
        when(openaiProvider.type()).thenReturn(ProviderType.OPENAI);
        when(openaiProvider.billingTier(any())).thenReturn("none");
        when(openaiProvider.synthesize(any())).thenReturn(new SynthesisResult(fakeProviderWav(), 24000, 1, 16));
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        service.speak(speakTo("living-room", null));

        InOrder order = inOrder(metrics, openaiProvider, synthesisTimer);
        order.verify(metrics).synthesisStarted(any());
        order.verify(openaiProvider).synthesize(any());
        order.verify(synthesisTimer).succeeded();
        assertThat(capturedUsage()).isEqualTo(new SynthesisUsage("openai", ProviderType.OPENAI, "none", 15, 0));
        verify(synthesisTimer, never()).failed(any());
    }

    @Test
    void theStylePromptIsCountedFromTheResolvedSettings() throws Exception {
        when(audioCache.get(any())).thenReturn(Optional.empty());
        when(audioCache.put(any(), any())).thenReturn(HIT);
        when(openaiProvider.synthesize(any())).thenReturn(new SynthesisResult(fakeProviderWav(), 24000, 1, 16));
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));
        doReturn(new SynthesisSettings("Kore", "Kore", "Kore", "en-US", "gemini-2.5-flash-tts", null, null, null,
                null, "Warmly")).when(openaiProvider).resolveSettings(any());

        service.speak(speakTo("living-room", null));

        assertThat(capturedUsage().stylePromptCharacters()).isEqualTo(6);
    }

    @Test
    void aProviderTimeoutIsRecordedWithItsCodeAndNoAudio() {
        when(audioCache.get(any())).thenReturn(Optional.empty());
        when(openaiProvider.synthesize(any())).thenThrow(new TtsException(TtsErrorCode.PROVIDER_TIMEOUT, "slow"));
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        assertThatThrownBy(() -> service.speak(speakTo("living-room", null))).isInstanceOf(TtsException.class);

        verify(synthesisTimer).failed(TtsErrorCode.PROVIDER_TIMEOUT);
        verify(synthesisTimer, never()).succeeded();
        verify(metrics, never()).audioProduced(any(), anyDouble());
    }

    @Test
    void anUnexpectedProviderFailureIsRecordedAsTheProviderErrorTheCallerGets() {
        when(audioCache.get(any())).thenReturn(Optional.empty());
        when(openaiProvider.synthesize(any())).thenThrow(new IllegalStateException("bug"));
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        assertThatThrownBy(() -> service.speak(speakTo("living-room", null))).isInstanceOf(TtsException.class);

        verify(synthesisTimer).failed(TtsErrorCode.PROVIDER_ERROR);
        verify(metrics).announcementRejected("openai", "PROVIDER_ERROR", "none");
    }

    @Test
    void theAudioDurationIsTheConvertedPcmLength() throws Exception {
        when(audioCache.get(any())).thenReturn(Optional.empty());
        when(audioCache.put(any(), any())).thenReturn(HIT);
        // The mocked converter passes bytes through: 192,000 bytes of 48 kHz stereo 16-bit is 1 s.
        when(openaiProvider.synthesize(any())).thenReturn(new SynthesisResult(providerWav(192_000), 24000, 1, 16));
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        service.speak(speakTo("living-room", null));

        verify(metrics).audioProduced(any(SynthesisUsage.class), eq(1.0));
    }

    @Test
    void aHitMeasuresNoSynthesis() {
        when(audioCache.get(any())).thenReturn(Optional.of(HIT));
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        service.speak(speakTo("living-room", null));

        verify(metrics, never()).synthesisStarted(any());
        verify(metrics, never()).audioProduced(any(), anyDouble());
    }

    @Test
    void aMissRefusedByAFullQueueStillCountsTheCharactersSent() throws Exception {
        maxDepthPerTarget = 1;
        when(audioCache.get(any())).thenReturn(Optional.of(HIT), Optional.of(HIT), Optional.empty());
        when(audioCache.put(any(), any())).thenReturn(HIT);
        when(openaiProvider.synthesize(any())).thenReturn(new SynthesisResult(fakeProviderWav(), 24000, 1, 16));
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));
        occupyTheQueue(service);

        assertThatThrownBy(() -> service.speak(speakTo("living-room", null))).isInstanceOf(TtsException.class);

        verify(metrics).synthesisStarted(any());
        verify(synthesisTimer).succeeded();
    }

    @Test
    void aHitRefusedByAFullQueueMeasuresNoSynthesis() {
        maxDepthPerTarget = 1;
        when(audioCache.get(any())).thenReturn(Optional.of(HIT));
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));
        occupyTheQueue(service);

        assertThatThrownBy(() -> service.speak(speakTo("living-room", null))).isInstanceOf(TtsException.class);

        verify(metrics, never()).synthesisStarted(any());
    }

    @Test
    void unconvertibleAudioIsASuccessfulSynthesisButARejectedAnnouncement() {
        when(audioCache.get(any())).thenReturn(Optional.empty());
        when(openaiProvider.synthesize(any())).thenReturn(new SynthesisResult(new byte[] {1, 2, 3}, 24000, 1, 16));
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        assertThatThrownBy(() -> service.speak(speakTo("living-room", null))).isInstanceOf(TtsException.class);

        verify(synthesisTimer).succeeded();
        verify(metrics, never()).audioProduced(any(), anyDouble());
        verify(metrics).announcementRejected("openai", "FORMAT_NORMALIZATION_FAILED", "none");
    }

    // --- 005: cache lookups are counted ------------------------------------------------------

    @Test
    void aHitIsCountedOnce() {
        when(audioCache.get(any())).thenReturn(Optional.of(HIT));
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        service.speak(speakTo("living-room", null));

        verify(metrics, times(1)).cacheLookup(true);
        verify(metrics, never()).cacheLookup(false);
    }

    @Test
    void aMissIsCountedOnceEvenWhenTheCacheWriteFails() throws Exception {
        when(audioCache.get(any())).thenReturn(Optional.empty());
        when(openaiProvider.synthesize(any())).thenReturn(new SynthesisResult(fakeProviderWav(), 24000, 1, 16));
        when(audioCache.put(any(), any())).thenThrow(new CacheWriteException("disk full", new java.io.IOException()));
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        service.speak(speakTo("living-room", null));

        verify(metrics, times(1)).cacheLookup(false);
        verify(metrics, never()).cacheLookup(true);
        verify(metrics).announcementAccepted("openai");
    }

    @Test
    void aRequestRejectedBeforeTheLookupCountsNoLookup() {
        when(deviceQueryService.getOutput(OutputId.of("bedroom"))).thenReturn(Optional.empty());
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        assertThatThrownBy(() -> service.speak(speakTo("bedroom", null))).isInstanceOf(TtsException.class);

        verify(metrics, never()).cacheLookup(org.mockito.ArgumentMatchers.anyBoolean());
    }

    // --- 005: queues and playbacks are measured ---------------------------------------------

    @Test
    void anAcceptedAnnouncementPublishesItsTargetsLiveQueueDepth() {
        when(audioCache.get(any())).thenReturn(Optional.of(HIT));
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        service.speak(speakTo("living-room", null));
        verify(routeService, timeout(ASYNC_TIMEOUT_MS)).createRoute(any(InputId.class), any(OutputId.class));
        service.speak(speakTo("living-room", null));

        ArgumentCaptor<IntSupplier> depth = ArgumentCaptor.forClass(IntSupplier.class);
        verify(metrics, times(2)).trackQueue(eq(TargetType.SINGLE_OUTPUT), eq("living-room"), depth.capture());
        // The first announcement is playing (and never finishes here); only the second waits.
        assertThat(depth.getValue().getAsInt()).isEqualTo(1);
    }

    @Test
    void aRejectedAnnouncementPublishesNoQueue() {
        when(deviceQueryService.getOutput(OutputId.of("bedroom"))).thenReturn(Optional.empty());
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        assertThatThrownBy(() -> service.speak(speakTo("bedroom", null))).isInstanceOf(TtsException.class);

        verify(metrics, never()).trackQueue(any(), any(), any());
    }

    @Test
    void aCreatedRouteIsCountedAsAStartedPlayback() {
        when(audioCache.get(any())).thenReturn(Optional.of(HIT));
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        service.speak(speakTo("living-room", null));

        verify(metrics, timeout(ASYNC_TIMEOUT_MS)).playbackStarted();
        verify(metrics, never()).playbackFailed();
    }

    @Test
    void aRouteThatCannotBeCreatedIsCountedAsAFailedPlayback() {
        when(audioCache.get(any())).thenReturn(Optional.of(HIT));
        when(routeService.createRoute(any(InputId.class), any(OutputId.class)))
                .thenThrow(new IllegalStateException("no route"));
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        service.speak(speakTo("living-room", null));

        verify(metrics, timeout(ASYNC_TIMEOUT_MS)).playbackFailed();
        verify(metrics, after(200).times(1)).playbackFailed();
        verify(metrics, never()).playbackStarted();
    }

    @Test
    void aFailureBeforeTheRouteStepIsCountedAsOneFailedPlayback() {
        when(audioCache.get(any())).thenReturn(Optional.of(HIT));
        doThrow(new IllegalStateException("registry down")).when(deviceRegistryService).registerInput(any());
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        service.speak(speakTo("living-room", null));

        verify(metrics, timeout(ASYNC_TIMEOUT_MS)).playbackFailed();
        verify(metrics, after(200).times(1)).playbackFailed();
        verify(metrics, never()).playbackStarted();
    }

    // --- 005: no caller text reaches a tag ---------------------------------------------------

    @Test
    void tenThousandRandomRequestsAddNoSeriesAndLeakNoCallerTextOrSecret() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics = new MicrometerTtsMetrics(registry, audioCache, 10, List.of("openai", "piper-local"));
        maxDepthPerTarget = 100_000;
        when(audioCache.get(any())).thenReturn(Optional.empty());
        when(audioCache.put(any(), any())).thenReturn(HIT);
        when(openaiProvider.type()).thenReturn(ProviderType.OPENAI);
        when(openaiProvider.synthesize(any())).thenReturn(new SynthesisResult(fakeProviderWav(), 24000, 1, 16));
        doAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return null;
        }).when(completionListener).track(any(), any());
        TtsProviderConfig openai = providerConfig("openai", "alloy", "en-US", "tts-1");
        openai.setApiKey("sk-CANARY-api-key");
        openai.setServiceAccountKeyFile("/secret/CANARY-key-file.json");
        TtsService service = serviceWith(openai);

        java.util.function.Consumer<Integer> send = i -> {
            String random = UUID.randomUUID().toString();
            AnnounceCommand command = i % 2 == 0
                    ? new AnnounceCommand("text " + random, "living-room", TargetType.SINGLE_OUTPUT, "openai",
                            "voice-" + random, null, null, null, null)
                    : speakTo("living-room", "provider-" + random);
            try {
                service.speak(command);
            } catch (TtsException expected) {
                // The unknown provider names are rejected; that is part of what is measured.
            }
        };
        for (int i = 0; i < 10; i++) {
            send.accept(i);
        }
        // Let the five accepted ones play, so the playback series exist before counting.
        verify(completionListener, timeout(ASYNC_TIMEOUT_MS).times(5)).track(any(), any());
        int metersAfterTen = registry.getMeters().size();

        for (int i = 0; i < 10_000; i++) {
            send.accept(i);
        }

        assertThat(registry.getMeters()).hasSize(metersAfterTen);
        List<String> tagValues = registry.getMeters().stream()
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream())
                .map(Tag::getValue)
                .toList();
        assertThat(tagValues).noneMatch(value -> value.startsWith("text ") || value.startsWith("voice-")
                || value.startsWith("provider-") || value.contains("CANARY"));
        assertThat(tagValues).contains("unknown", "openai");
    }
}
