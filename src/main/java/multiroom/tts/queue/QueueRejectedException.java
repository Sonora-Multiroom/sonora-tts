package multiroom.tts.queue;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;

import java.util.Locale;

/**
 * A per-target queue refused an announcement. The caller still sees {@link
 * TtsErrorCode#PROVIDER_ERROR} with the same message as before, so the REST contract is
 * unchanged; the {@link Reason} only lets the metrics tell a queue rejection apart from a real
 * provider failure.
 */
public class QueueRejectedException extends TtsException {

    /** Why the queue refused. */
    public enum Reason {
        /** The target already has {@code queue.max-depth-per-target} announcements waiting. */
        QUEUE_FULL,
        /** The extension is stopping and accepts no new work. */
        SHUTTING_DOWN
    }

    private final Reason reason;

    public QueueRejectedException(Reason reason, String message) {
        super(TtsErrorCode.PROVIDER_ERROR, message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /** The reason as the metrics publish it: {@code queue_full} or {@code shutting_down}. */
    public String tagValue() {
        return reason.name().toLowerCase(Locale.ROOT);
    }
}
