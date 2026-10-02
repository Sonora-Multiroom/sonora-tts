package multiroom.tts.service;

import multiroom.api.exceptions.RouteAdmissionException;
import multiroom.api.model.GroupId;
import multiroom.api.model.InputId;
import multiroom.api.model.OutputGroup;
import multiroom.api.model.OutputId;
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
import multiroom.tts.config.PlaybackMode;
import multiroom.tts.cache.CacheWriteException;
import multiroom.tts.config.TtsProperties;
import multiroom.tts.metrics.SynthesisUsage;
import multiroom.tts.metrics.TtsMetrics;
import multiroom.tts.provider.ProviderRegistry;
import multiroom.tts.provider.RequestedSettings;
import multiroom.tts.provider.SynthesisRequest;
import multiroom.tts.provider.SynthesisResult;
import multiroom.tts.provider.SynthesisSettings;
import multiroom.tts.provider.TtsProvider;
import multiroom.tts.queue.AnnouncementQueueManager;
import multiroom.tts.queue.AnnouncementTask;
import multiroom.tts.queue.QueueRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates one announcement end to end: validate, resolve the target and provider, serve from
 * cache or synthesize and convert, then hand the routing step to the per-target queue. The
 * announcement joins its target alongside whatever already plays there, in duck-others or mix
 * mode ({@link PlaybackMode}); the host lowers and restores the other routes.
 *
 * <p>The target's native audio format is fixed at {@link SampleFormat#standard()} — this module
 * has no per-output format lookup, so every announcement converts to the one system-wide native
 * format (48 kHz stereo 16-bit PCM) regardless of which output ultimately plays it. The pipeline's
 * own per-route conversion covers whatever gap remains for a differently configured output.
 *
 * <p>This class's own work ends at {@code queueManager.enqueue}: it does not block for playback,
 * and does not unregister the ephemeral input — {@link
 * PlaybackCompletionListener} owns all three, because completion is only observable as {@code
 * RouteDestroyedEvent}.
 */
public class TtsService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);
    private static final SampleFormat NATIVE_FORMAT = SampleFormat.standard();

    private final TtsProperties properties;
    private final ProviderRegistry providerRegistry;
    private final AudioConverter audioConverter;
    private final AudioCache audioCache;
    private final TtsInputResolver inputResolver;
    private final DeviceRegistryService deviceRegistryService;
    private final DeviceQueryService deviceQueryService;
    private final RouteService routeService;
    private final PlaybackCompletionListener completionListener;
    private final AnnouncementQueueManager queueManager;
    private final TtsMetrics metrics;

    public TtsService(TtsProperties properties, ProviderRegistry providerRegistry, AudioConverter audioConverter,
                       AudioCache audioCache, TtsInputResolver inputResolver,
                       DeviceRegistryService deviceRegistryService, DeviceQueryService deviceQueryService,
                       RouteService routeService, PlaybackCompletionListener completionListener,
                       TtsMetrics metrics) {
        this.properties = properties;
        this.providerRegistry = providerRegistry;
        this.audioConverter = audioConverter;
        this.audioCache = audioCache;
        this.inputResolver = inputResolver;
        this.deviceRegistryService = deviceRegistryService;
        this.deviceQueryService = deviceQueryService;
        this.routeService = routeService;
        this.completionListener = completionListener;
        this.metrics = metrics;
        this.queueManager = new AnnouncementQueueManager(properties.getQueue().getMaxDepthPerTarget(),
                this::activateCountingEarlyFailures);
    }

    /**
     * Counts every answer exactly once, here rather than at each {@code throw}, so a new failure
     * path is counted without anyone remembering to.
     */
    public AnnounceResult speak(AnnounceCommand command) {
        String providerTag = providerTag(command.providerName());
        try {
            AnnounceResult result = announce(command);
            metrics.announcementAccepted(providerTag);
            return result;
        } catch (TtsException e) {
            String reason = e instanceof QueueRejectedException rejected ? rejected.tagValue() : TtsMetrics.NONE;
            metrics.announcementRejected(providerTag, e.getErrorCode().name(), reason);
            throw e;
        } catch (RuntimeException e) {
            metrics.announcementRejected(providerTag, TtsMetrics.INTERNAL, TtsMetrics.NONE);
            throw e;
        }
    }

    /**
     * A name the caller sent becomes a tag only if it is configured: anything else would let a
     * caller mint a new series per request.
     */
    private String providerTag(String requestedProvider) {
        if (requestedProvider == null) {
            return providerRegistry.defaultProviderName();
        }
        return providerRegistry.isConfigured(requestedProvider) ? requestedProvider : TtsMetrics.UNKNOWN;
    }

    private AnnounceResult announce(AnnounceCommand command) {
        validateText(command.text());
        PlaybackMode playbackMode = resolvePlaybackMode(command.playbackMode());
        validateTargetExists(command.targetType(), command.targetName());

        String providerName = command.providerName() != null
                ? command.providerName()
                : providerRegistry.defaultProviderName();
        TtsProvider provider = command.providerName() != null
                ? providerRegistry.resolve(command.providerName())
                : providerRegistry.resolveDefault();

        // Resolution is pure and runs before the cache lookup, so the key is built from the
        // resolved settings however the caller spelled them, and a hit costs no network work.
        // The *Key forms are the provider's decision; they are copied, never
        // interpreted, so this path knows no provider's defaults (Principle IV).
        SynthesisSettings settings = provider.resolveSettings(new RequestedSettings(
                command.voice(), command.language(), command.engine(), command.pitch(), command.speakingRate(),
                command.stylePrompt()));
        CacheKey cacheKey = new CacheKey(command.text(), providerName, settings.engine(), settings.voiceKey(),
                settings.language(), settings.pitchKey(), settings.speakingRateKey(), NATIVE_FORMAT,
                settings.stylePrompt());

        boolean cacheHit;
        boolean temporaryFile = false;
        Path audioFile;
        Optional<Path> cached = audioCache.get(cacheKey);
        metrics.cacheLookup(cached.isPresent());
        if (cached.isPresent()) {
            cacheHit = true;
            audioFile = cached.get();
            audioCache.pin(cacheKey);
            log.debug("TTS_CACHE_HIT text.length={} provider={}", command.text().length(), providerName);
        } else {
            cacheHit = false;
            log.debug("TTS_CACHE_MISS text.length={} provider={}", command.text().length(), providerName);
            Synthesized synthesized = synthesize(provider, providerName,
                    new SynthesisRequest(command.text(), settings,
                            NATIVE_FORMAT.sampleRate(), NATIVE_FORMAT.channels()));
            byte[] pcm = audioConverter.convert(synthesized.result().audioData(), NATIVE_FORMAT);
            metrics.audioProduced(synthesized.usage(), audioSeconds(pcm));
            try {
                audioFile = audioCache.put(cacheKey, pcm);
                audioCache.pin(cacheKey);
            } catch (CacheWriteException e) {
                log.warn("Cache write failed ({}); spilling announcement to a temporary file", e.getMessage());
                audioFile = spillToTempFile(pcm);
                temporaryFile = true;
            }
        }

        UUID announcementId = UUID.randomUUID();
        InputId inputId = InputId.of("tts-" + announcementId);
        AnnouncementTask task = new AnnouncementTask(announcementId, inputId, command.targetType(),
                command.targetName(), audioFile, temporaryFile,
                playbackMode, temporaryFile ? null : cacheKey);

        String queueKey = queueKey(command.targetType(), command.targetName());
        int queueDepth;
        try {
            queueDepth = queueManager.enqueue(queueKey, task);
        } catch (RuntimeException e) {
            // The task was never handed to the queue, so nothing will ever release this
            // reservation via RouteDestroyedEvent — release it here instead of leaking it
            // for the life of the process (a permanently pinned cache entry, or an orphaned
            // spill file outside the cache directory). No route was touched, so none needs restoring.
            if (temporaryFile) {
                deleteQuietly(audioFile);
            } else {
                audioCache.unpin(cacheKey);
            }
            throw e;
        }
        metrics.trackQueue(command.targetType(), command.targetName(), () -> queueManager.depth(queueKey));
        log.info("TTS_REQUEST_RECEIVED announcementId={} target={} cacheHit={} queueDepth={}",
                announcementId, command.targetName(), cacheHit, queueDepth);

        return new AnnounceResult(announcementId, cacheHit, queueDepth);
    }

    /** What one provider call produced, and the work it was asked for. */
    private record Synthesized(SynthesisResult result, SynthesisUsage usage) {
    }

    /**
     * The single call site of {@link TtsProvider#synthesize} for every provider type, so the
     * characters are counted here, once, before the provider is called: this is the point a
     * future monthly character budget will check. Format conversion stays outside the timer.
     */
    private Synthesized synthesize(TtsProvider provider, String providerName, SynthesisRequest request) {
        SynthesisUsage usage = SynthesisUsage.of(providerName, provider.type(),
                provider.billingTier(request.settings()), request.text(), request.settings().stylePrompt());
        log.debug("TTS_SYNTHESIS_STARTED provider={}", providerName);
        TtsMetrics.SynthesisTimer timer = metrics.synthesisStarted(usage);
        try {
            SynthesisResult result = provider.synthesize(request);
            timer.succeeded();
            log.info("TTS_SYNTHESIS_COMPLETED provider={}", providerName);
            return new Synthesized(result, usage);
        } catch (TtsException e) {
            timer.failed(e.getErrorCode());
            log.error("TTS_SYNTHESIS_ERROR provider={} code={}", providerName, e.getErrorCode());
            throw e;
        } catch (RuntimeException e) {
            timer.failed(TtsErrorCode.PROVIDER_ERROR);
            log.error("TTS_SYNTHESIS_ERROR provider={} code={}", providerName, TtsErrorCode.PROVIDER_ERROR, e);
            throw new TtsException(TtsErrorCode.PROVIDER_ERROR,
                    "Provider '" + providerName + "' failed: " + e.getMessage(), e);
        }
    }

    /** Exact, because the audio is already converted: bytes over bytes per second of the native format. */
    private static double audioSeconds(byte[] pcm) {
        return (double) pcm.length / (NATIVE_FORMAT.sampleRate() * NATIVE_FORMAT.bytesPerFrame());
    }

    /**
     * What the queue runs. {@link #activate} counts a failed route itself and does not rethrow,
     * so only a failure before the route step (registering the input, tracking it) reaches the
     * catch here, and no failure is counted twice.
     */
    private void activateCountingEarlyFailures(AnnouncementTask task, Runnable onPlaybackComplete) {
        try {
            activate(task, onPlaybackComplete);
        } catch (RuntimeException e) {
            metrics.playbackFailed();
            throw e;
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
            var joinMode = task.playbackMode().joinMode();
            if (task.targetType() == TargetType.SINGLE_OUTPUT) {
                routeService.createRoute(task.inputId(), OutputId.of(task.targetName()), joinMode);
            } else {
                routeService.createRoute(task.inputId(), GroupId.of(task.targetName()), joinMode);
            }
            metrics.playbackStarted();
            log.info("TTS_PLAYBACK_STARTED announcementId={} target={}", task.announcementId(), task.targetName());
        } catch (RouteAdmissionException e) {
            // The host's own limits (route cap, input already on the output) — an expected
            // refusal, not a fault, so no stack trace.
            log.warn("TTS_PLAYBACK_REFUSED announcementId={} target={} output={} reason={}",
                    task.announcementId(), task.targetName(), e.getOutputId(), e.getReason());
            abandon(task, onPlaybackComplete);
        } catch (RuntimeException e) {
            log.error("Failed to create route for announcement {} on target {}",
                    task.announcementId(), task.targetName(), e);
            abandon(task, onPlaybackComplete);
        }
    }

    /**
     * Drops an announcement whose route was never created. It is not retried: a late announcement
     * is worse than none. Unregistering the input is right here, and only here: no route exists,
     * so core will never auto-remove it.
     */
    private void abandon(AnnouncementTask task, Runnable onPlaybackComplete) {
        metrics.playbackFailed();
        completionListener.cancel(task.inputId());
        try {
            deviceRegistryService.unregisterInput(task.inputId());
        } catch (RuntimeException unregisterFailure) {
            log.warn("Failed to unregister ephemeral input {} after failed route creation",
                    task.inputId(), unregisterFailure);
        }
        onPlaybackComplete.run();
    }

    private PlaybackMode resolvePlaybackMode(String requested) {
        if (requested == null) {
            return properties.getPlayback().resolvedDefaultMode();
        }
        return PlaybackMode.fromName(requested).orElseThrow(() -> new TtsException(TtsErrorCode.INVALID_REQUEST,
                "Unknown playbackMode '" + requested + "'. Supported: " + PlaybackMode.supportedList()));
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
