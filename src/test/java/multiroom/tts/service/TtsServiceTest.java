package multiroom.tts.service;

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
import multiroom.tts.provider.ProviderRegistry;
import multiroom.tts.provider.SynthesisRequest;
import multiroom.tts.provider.SynthesisResult;
import multiroom.tts.provider.TtsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    @BeforeEach
    void setUp() {
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
        properties.setQueue(new QueueProperties());

        Map<String, TtsProvider> providers = Map.of("openai", openaiProvider, "piper-local", piperProvider);
        ProviderRegistry registry = new ProviderRegistry(providers, "openai");

        return new TtsService(properties, registry, audioConverter, audioCache, inputResolver,
                deviceRegistryService, deviceQueryService, routeService, completionListener);
    }

    @Test
    void singleOutputFlowSynthesizesConvertsRegistersAndRoutes() throws Exception {
        when(audioCache.get(any())).thenReturn(Optional.empty());
        when(openaiProvider.synthesize(any())).thenReturn(
                new SynthesisResult(fakeProviderWav(), 24000, 1, 16));
        when(audioCache.put(any(), any())).thenReturn(java.nio.file.Path.of("cache/hash.wav"));

        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));
        AnnounceResult result = service.speak(new AnnounceCommand("Dinner is ready", "living-room",
                TargetType.SINGLE_OUTPUT, null, null, null));

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
        service.speak(new AnnounceCommand("Motion detected", "all-rooms", TargetType.OUTPUT_GROUP, null, null, null));

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
                TargetType.OUTPUT_GROUP, null, null, null)))
                .isInstanceOf(TtsException.class)
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.TARGET_NOT_FOUND);
    }

    @Test
    void unknownTargetRaisesTargetNotFound() {
        when(deviceQueryService.getOutput(OutputId.of("bedroom"))).thenReturn(Optional.empty());

        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        assertThatThrownBy(() -> service.speak(new AnnounceCommand("Hi", "bedroom",
                TargetType.SINGLE_OUTPUT, null, null, null)))
                .isInstanceOf(TtsException.class)
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.TARGET_NOT_FOUND);
    }

    @Test
    void cacheHitSkipsProviderCall() {
        when(audioCache.get(any())).thenReturn(Optional.of(java.nio.file.Path.of("cache/hash.wav")));

        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));
        AnnounceResult result = service.speak(new AnnounceCommand("Dinner is ready", "living-room",
                TargetType.SINGLE_OUTPUT, null, null, null));

        assertThat(result.cacheHit()).isTrue();
        verifyNoInteractions(openaiProvider);
        verify(audioCache).pin(any());
    }

    @Test
    void cacheMissCallsProviderAndWritesCache() throws Exception {
        when(audioCache.get(any())).thenReturn(Optional.empty());
        when(openaiProvider.synthesize(any())).thenReturn(new SynthesisResult(fakeProviderWav(), 24000, 1, 16));
        when(audioCache.put(any(), any())).thenReturn(java.nio.file.Path.of("cache/hash.wav"));

        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));
        AnnounceResult result = service.speak(new AnnounceCommand("Dinner is ready", "living-room",
                TargetType.SINGLE_OUTPUT, null, null, null));

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
                TargetType.SINGLE_OUTPUT, null, null, null));

        assertThat(result).isNotNull();
        verify(deviceRegistryService, timeout(ASYNC_TIMEOUT_MS)).registerInput(any());
        verify(routeService, timeout(ASYNC_TIMEOUT_MS)).createRoute(any(InputId.class), eq(OutputId.of("living-room")));
    }

    @Test
    void unknownProviderNameRaisesProviderNotFoundWithNoSynthesisCall() {
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        assertThatThrownBy(() -> service.speak(new AnnounceCommand("Hi", "living-room",
                TargetType.SINGLE_OUTPUT, "nonexistent", null, null)))
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
                "piper-local", null, null));

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
                null, "nova", "fr-FR"));

        verify(openaiProvider).synthesize(new SynthesisRequest("Hi", "nova", "fr-FR", 48000, 2));

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
                TargetType.SINGLE_OUTPUT, null, null, null)))
                .isInstanceOf(TtsException.class)
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.PROVIDER_TIMEOUT);
    }

    @Test
    void emptyOrOverLongTextIsRejectedBeforeAnyProviderCall() {
        TtsService service = serviceWith(providerConfig("openai", "alloy", "en-US", "tts-1"));

        assertThatThrownBy(() -> service.speak(new AnnounceCommand("  ", "living-room",
                TargetType.SINGLE_OUTPUT, null, null, null)))
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
        service.speak(new AnnounceCommand("Hi", "living-room", TargetType.SINGLE_OUTPUT, null, null, null));

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
                TargetType.SINGLE_OUTPUT, null, null, null)))
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
                TargetType.SINGLE_OUTPUT, null, null, null)))
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
        service.speak(new AnnounceCommand("Hi", "living-room", TargetType.SINGLE_OUTPUT, null, null, null));

        verify(routeService).stopRoutesByOutput(OutputId.of("living-room"));
    }
}
