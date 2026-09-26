package multiroom.tts.metrics;

import multiroom.api.model.TargetType;
import multiroom.tts.TtsErrorCode;

import java.util.function.IntSupplier;

/**
 * The one seam through which the rest of the module reports what happened, named after events
 * rather than meters. It never exposes a Micrometer type, so callers and their tests do not
 * depend on Micrometer, and the module loads on a host without it.
 *
 * <p>No method throws: recording a metric must never fail an announcement. Callers pass only
 * bounded values — a configured provider name or {@link #UNKNOWN}, an error code name, a
 * validated target — never text a caller sent, because every distinct value becomes a separate
 * series in the host's registry.
 */
public interface TtsMetrics {

    /** A tag value for "does not apply", so every series of a metric carries the same tag keys. */
    String NONE = "none";

    /** The provider tag of a request whose provider is not configured, or not yet known. */
    String UNKNOWN = "unknown";

    /** The error tag of a request that failed with something other than a {@code TtsException}. */
    String INTERNAL = "INTERNAL";

    /** A speak request was answered with 202. */
    void announcementAccepted(String providerTag);

    /**
     * A speak request was answered with an error.
     *
     * @param error  the published error code's name, or {@link #INTERNAL}
     * @param reason why a queue refused it ({@code queue_full}, {@code shutting_down}), otherwise
     *               {@link #NONE}
     */
    void announcementRejected(String providerTag, String error, String reason);

    /** The cache was consulted for an announcement. */
    void cacheLookup(boolean hit);

    /**
     * A provider is about to be called. The returned timer must be finished exactly once.
     */
    SynthesisTimer synthesisStarted(SynthesisUsage usage);

    /** A successful synthesis produced this many seconds of audio, after conversion. */
    void audioProduced(SynthesisUsage usage, double seconds);

    /**
     * Publishes the depth of a validated target's queue. Only the first call for a target
     * registers anything; later calls are ignored, so this may be called on every enqueue.
     */
    void trackQueue(TargetType type, String target, IntSupplier depth);

    /** A queued announcement's route was created. */
    void playbackStarted();

    /** A queued announcement was taken off its queue but could not start. */
    void playbackFailed();

    /** One provider call in progress. */
    interface SynthesisTimer {

        /** The provider returned audio. */
        void succeeded();

        /**
         * The provider failed. A failure that is not a {@code TtsException} is passed as
         * {@link TtsErrorCode#PROVIDER_ERROR}, the code the caller receives for it.
         */
        void failed(TtsErrorCode error);
    }
}
