package multiroom.tts.service;

import multiroom.api.events.RouteDestroyedEvent;
import multiroom.api.model.InputId;
import multiroom.tts.audio.TtsInputResolver;
import multiroom.tts.cache.AudioCache;
import multiroom.tts.queue.AnnouncementTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Releases what this module holds for an announcement once its route is destroyed. Restoring the
 * level of other routes is the host's job. Nothing in this module can observe playback directly — {@code AudioInput.onComplete} lives in {@code multiroom.core.io}, out of reach. The route
 * lifecycle is the public signal: on EOF the pipeline drains and core publishes
 * {@link RouteDestroyedEvent}. Matching {@code event.route().getInputId()} against a tracked
 * announcement identifies it exactly, with no polling and no duration arithmetic.
 *
 * <p>This module never calls {@code unregisterInput}: the ephemeral input was registered with
 * {@code autoRemove = true}, so core's {@code AutoRemoveInputListener} handles the same event and
 * removes it first; a second call would throw.
 *
 * <p>Cleanup on completion also releases the announcement's entry from {@link TtsInputResolver} —
 * otherwise that resolver's uuid-to-file map would grow for the life of the process, since nothing
 * else ever removes an entry from it.
 */
public class PlaybackCompletionListener {

    private static final Logger log = LoggerFactory.getLogger(PlaybackCompletionListener.class);

    private final AudioCache audioCache;
    private final TtsInputResolver inputResolver;
    private final Map<InputId, Tracked> inFlight = new ConcurrentHashMap<>();

    public PlaybackCompletionListener(AudioCache audioCache, TtsInputResolver inputResolver) {
        this.audioCache = audioCache;
        this.inputResolver = inputResolver;
    }

    /**
     * Registers an in-flight announcement so its cache entry is unpinned, its resolver entry
     * released and any temp file deleted once its route is destroyed.
     *
     * @param onComplete invoked exactly once, after release, whether or not a queue is waiting
     *                    on it; may be {@code null}
     */
    public void track(AnnouncementTask task, Runnable onComplete) {
        inFlight.put(task.inputId(), new Tracked(task, onComplete));
    }

    @EventListener
    public void onRouteDestroyed(RouteDestroyedEvent event) {
        InputId inputId = event.route().getInputId();
        Tracked tracked = inFlight.remove(inputId);
        if (tracked == null) {
            // Not one of ours, or already handled — idempotent under a duplicate event.
            return;
        }
        release(tracked.task());
        log.info("TTS_PLAYBACK_COMPLETED announcementId={} target={}",
                tracked.task().announcementId(), tracked.task().targetName());
        if (tracked.onComplete() != null) {
            tracked.onComplete().run();
        }
    }

    /**
     * Cleans up a tracked announcement that will never reach {@link RouteDestroyedEvent} because
     * its route was never created — a {@code RouteService.createRoute} failure right after {@link
     * #track}. Runs the identical cleanup {@link #onRouteDestroyed} would (unpinning the cache
     * entry, releasing the resolver mapping, deleting any temp file), since waiting for an event
     * that cannot arrive would otherwise leak all three.
     * Does not invoke the task's completion callback — the caller already knows activation failed
     * and drives its own next step.
     */
    public void cancel(InputId inputId) {
        Tracked tracked = inFlight.remove(inputId);
        if (tracked == null) {
            return;
        }
        release(tracked.task());
    }

    private void release(AnnouncementTask task) {
        if (task.cacheKey() != null) {
            audioCache.unpin(task.cacheKey());
        }
        inputResolver.release(task.announcementId());
        if (task.temporaryFile()) {
            try {
                Files.deleteIfExists(task.audioFile());
            } catch (IOException e) {
                log.warn("Failed to delete temporary audio file {}", task.audioFile(), e);
            }
        }
    }

    private record Tracked(AnnouncementTask task, Runnable onComplete) {
    }
}
