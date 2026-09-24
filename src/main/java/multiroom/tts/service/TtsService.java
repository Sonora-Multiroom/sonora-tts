package multiroom.tts.service;

import multiroom.api.model.GroupId;
import multiroom.api.model.InputId;
import multiroom.api.model.OutputGroup;
import multiroom.api.model.OutputId;
import multiroom.api.model.Route;
import multiroom.api.model.SampleFormat;
import multiroom.api.model.TargetType;
import multiroom.api.services.DeviceQueryService;
import multiroom.api.services.DeviceRegistryService;
import multiroom.api.services.RouteService;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.audio.AudioConverter;
import multiroom.tts.audio.TtsInputResolver;
import multiroom.tts.audio.WavFileWriter;
import multiroom.tts.cache.AudioCache;
import multiroom.tts.cache.CacheKey;
import multiroom.tts.cache.CacheWriteException;
import multiroom.tts.config.TtsProperties;
import multiroom.tts.config.TtsProviderConfig;
import multiroom.tts.provider.ProviderRegistry;
import multiroom.tts.provider.SynthesisRequest;
import multiroom.tts.provider.SynthesisResult;
import multiroom.tts.provider.TtsProvider;
import multiroom.tts.queue.AnnouncementQueueManager;
import multiroom.tts.queue.AnnouncementTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates one announcement end to end: validate, resolve the target and provider, serve from
 * cache or synthesize and convert, snapshot and stop whatever is already playing on the target,
 * then hand the routing step to the per-target queue.
 *
 * <p>The target's native audio format is fixed at {@link SampleFormat#standard()} — this module
 * has no per-output format lookup, so every announcement converts to the one system-wide native
 * format (48 kHz stereo 16-bit PCM) regardless of which output ultimately plays it. The pipeline's
 * own per-route conversion covers whatever gap remains for a differently configured output.
 *
 * <p>This class's own work ends at {@code queueManager.enqueue}: it does not block for playback,
 * does not restore routes and does not unregister the ephemeral input — {@link
 * PlaybackCompletionListener} owns all three, because completion is only observable as {@code
 * RouteDestroyedEvent} (FR-008).
 */
public class TtsService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);
    private static final SampleFormat NATIVE_FORMAT = SampleFormat.standard();

    private final TtsProperties properties;
    private final ProviderRegistry providerRegistry;
    private final Map<String, TtsProviderConfig> providerConfigsByName;
    private final AudioConverter audioConverter;
    private final AudioCache audioCache;
    private final TtsInputResolver inputResolver;
    private final DeviceRegistryService deviceRegistryService;
    private final DeviceQueryService deviceQueryService;
    private final RouteService routeService;
    private final PlaybackCompletionListener completionListener;
    private final AnnouncementQueueManager queueManager;

    public TtsService(TtsProperties properties, ProviderRegistry providerRegistry, AudioConverter audioConverter,
                       AudioCache audioCache, TtsInputResolver inputResolver,
                       DeviceRegistryService deviceRegistryService, DeviceQueryService deviceQueryService,
                       RouteService routeService, PlaybackCompletionListener completionListener) {
        this.properties = properties;
        this.providerRegistry = providerRegistry;
        this.providerConfigsByName = properties.getProviders().stream()
                .collect(Collectors.toMap(TtsProviderConfig::getName, config -> config));
        this.audioConverter = audioConverter;
        this.audioCache = audioCache;
        this.inputResolver = inputResolver;
        this.deviceRegistryService = deviceRegistryService;
        this.deviceQueryService = deviceQueryService;
        this.routeService = routeService;
        this.completionListener = completionListener;
        this.queueManager = new AnnouncementQueueManager(properties.getQueue().getMaxDepthPerTarget(), this::activate);
    }

    public AnnounceResult speak(AnnounceCommand command) {
        validateText(command.text());
        validateTargetExists(command.targetType(), command.targetName());

        String providerName = command.providerName() != null
                ? command.providerName()
                : providerRegistry.defaultProviderName();
        TtsProvider provider = command.providerName() != null
                ? providerRegistry.resolve(command.providerName())
                : providerRegistry.resolveDefault();
        TtsProviderConfig providerConfig = providerConfigsByName.get(providerName);

        String voice = command.voice() != null ? command.voice() : providerConfig.getVoice();
        String language = command.language() != null ? command.language() : providerConfig.getLanguage();
        String engine = providerConfig.getEngine();

        CacheKey cacheKey = new CacheKey(command.text(), providerName, engine, voice, language, NATIVE_FORMAT);

        boolean cacheHit;
        boolean temporaryFile = false;
        Path audioFile;
        Optional<Path> cached = audioCache.get(cacheKey);
        if (cached.isPresent()) {
            cacheHit = true;
            audioFile = cached.get();
            audioCache.pin(cacheKey);
            log.debug("TTS_CACHE_HIT text.length={} provider={}", command.text().length(), providerName);
        } else {
            cacheHit = false;
            log.debug("TTS_CACHE_MISS text.length={} provider={}", command.text().length(), providerName);
            SynthesisResult synthesisResult = synthesize(provider, providerName,
                    new SynthesisRequest(command.text(), voice, language,
                            NATIVE_FORMAT.sampleRate(), NATIVE_FORMAT.channels()));
            byte[] pcm = audioConverter.convert(synthesisResult.audioData(), NATIVE_FORMAT);
            try {
                audioFile = audioCache.put(cacheKey, pcm);
                audioCache.pin(cacheKey);
            } catch (CacheWriteException e) {
                log.warn("Cache write failed ({}); spilling announcement to a temporary file", e.getMessage());
                audioFile = spillToTempFile(pcm);
                temporaryFile = true;
            }
        }

        List<Route> routeSnapshot = snapshotAndStopExistingRoutes(command.targetType(), command.targetName());

        UUID announcementId = UUID.randomUUID();
        InputId inputId = InputId.of("tts-" + announcementId);
        AnnouncementTask task = new AnnouncementTask(announcementId, inputId, command.targetType(),
                command.targetName(), audioFile, temporaryFile, routeSnapshot,
                temporaryFile ? null : cacheKey);

        int queueDepth;
        try {
            queueDepth = queueManager.enqueue(queueKey(command.targetType(), command.targetName()), task);
        } catch (RuntimeException e) {
            // The task was never handed to the queue, so nothing will ever restore this
            // reservation via RouteDestroyedEvent — release it here instead of leaking it
            // for the life of the process (a permanently pinned cache entry, or an orphaned
            // spill file outside the cache directory). The target's own routes were already
            // stopped a few lines above for an announcement that will now never play, so they
            // must come back too — otherwise the target stays silent until an unrelated route
            // command arrives.
            completionListener.restoreRoutes(routeSnapshot, announcementId);
            if (temporaryFile) {
                deleteQuietly(audioFile);
            } else {
                audioCache.unpin(cacheKey);
            }
            throw e;
        }
        log.info("TTS_REQUEST_RECEIVED announcementId={} target={} cacheHit={} queueDepth={}",
                announcementId, command.targetName(), cacheHit, queueDepth);

        return new AnnounceResult(announcementId, cacheHit, queueDepth);
    }

    private SynthesisResult synthesize(TtsProvider provider, String providerName, SynthesisRequest request) {
        log.debug("TTS_SYNTHESIS_STARTED provider={}", providerName);
        try {
            SynthesisResult result = provider.synthesize(request);
            log.info("TTS_SYNTHESIS_COMPLETED provider={}", providerName);
            return result;
        } catch (TtsException e) {
            log.error("TTS_SYNTHESIS_ERROR provider={} code={}", providerName, e.getErrorCode());
            throw e;
        } catch (RuntimeException e) {
            log.error("TTS_SYNTHESIS_ERROR provider={} code={}", providerName, TtsErrorCode.PROVIDER_ERROR, e);
            throw new TtsException(TtsErrorCode.PROVIDER_ERROR,
                    "Provider '" + providerName + "' failed: " + e.getMessage(), e);
        }
    }

    /** Invoked by the queue worker when this task's target frees up. */
    private void activate(AnnouncementTask task, Runnable onPlaybackComplete) {
        inputResolver.register(task.announcementId(), task.audioFile());
        var input = multiroom.api.model.AudioInputDefinition.builder()
                .inputId(task.inputId())
                .displayName("TTS announcement " + task.announcementId())
                .uri("tts://" + task.announcementId())
                .enabled(true)
                .autoRemove(true)
                .build();
        deviceRegistryService.registerInput(input);
        completionListener.track(task, onPlaybackComplete);
        try {
            if (task.targetType() == TargetType.SINGLE_OUTPUT) {
                routeService.createRoute(task.inputId(), OutputId.of(task.targetName()));
            } else {
                routeService.createRoute(task.inputId(), GroupId.of(task.targetName()));
            }
            log.info("TTS_PLAYBACK_STARTED announcementId={} target={}", task.announcementId(), task.targetName());
        } catch (RuntimeException e) {
            log.error("Failed to create route for announcement {} on target {}",
                    task.announcementId(), task.targetName(), e);
            // No route was created, so RouteDestroyedEvent will never arrive for this input —
            // cancel() runs the same cleanup onRouteDestroyed would (restoring the snapshotted
            // routes, unpinning the cache entry, releasing the resolver mapping, deleting any
            // temp file) instead of leaking all four for the life of the process.
            completionListener.cancel(task.inputId());
            try {
                deviceRegistryService.unregisterInput(task.inputId());
            } catch (RuntimeException unregisterFailure) {
                log.warn("Failed to unregister ephemeral input {} after failed route creation",
                        task.inputId(), unregisterFailure);
            }
            onPlaybackComplete.run();
        }
    }

    private void validateText(String text) {
        if (text == null || text.isBlank()) {
            throw new TtsException(TtsErrorCode.INVALID_REQUEST, "Text must not be blank");
        }
        if (text.length() > properties.getMaxTextLength()) {
            throw new TtsException(TtsErrorCode.INVALID_REQUEST, "Text length " + text.length()
                    + " exceeds maximum allowed length of " + properties.getMaxTextLength());
        }
    }

    private void validateTargetExists(TargetType targetType, String targetName) {
        if (targetType == TargetType.SINGLE_OUTPUT) {
            if (deviceQueryService.getOutput(OutputId.of(targetName)).isEmpty()) {
                throw new TtsException(TtsErrorCode.TARGET_NOT_FOUND,
                        "No output or output group named '" + targetName + "' found");
            }
        } else {
            OutputGroup group = deviceQueryService.getGroup(GroupId.of(targetName)).orElseThrow(
                    () -> new TtsException(TtsErrorCode.TARGET_NOT_FOUND,
                            "No output or output group named '" + targetName + "' found"));
            if (group.getOutputIds().isEmpty()) {
                throw new TtsException(TtsErrorCode.TARGET_NOT_FOUND,
                        "Output group '" + targetName + "' has no outputs");
            }
        }
    }

    private List<Route> snapshotAndStopExistingRoutes(TargetType targetType, String targetName) {
        if (targetType == TargetType.SINGLE_OUTPUT) {
            return routeService.stopRoutesByOutput(OutputId.of(targetName));
        }
        return routeService.stopRoutesByGroup(GroupId.of(targetName));
    }

    private Path spillToTempFile(byte[] pcm) {
        try {
            Path tempFile = Files.createTempFile("multiroom-tts-", ".wav");
            WavFileWriter.write(tempFile, pcm, NATIVE_FORMAT);
            return tempFile;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete abandoned temporary audio file {}", path, e);
        }
    }

    private static String queueKey(TargetType targetType, String targetName) {
        return targetType + ":" + targetName;
    }

    @Override
    public void start() {
        queueManager.start();
    }

    @Override
    public void stop() {
        queueManager.stop();
    }

    @Override
    public boolean isRunning() {
        return queueManager.isRunning();
    }
}
