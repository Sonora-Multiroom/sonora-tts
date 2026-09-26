package multiroom.tts.metrics;

import multiroom.api.model.TargetType;
import multiroom.tts.TtsErrorCode;

import java.util.function.IntSupplier;

/**
 * Records nothing. Chosen when the host has no Micrometer on its classpath, or has Micrometer but
 * no {@code MeterRegistry} bean: missing metrics are not a fault, so the extension runs as before.
 */
public class NoopTtsMetrics implements TtsMetrics {

    private static final SynthesisTimer NOOP_TIMER = new SynthesisTimer() {
        @Override
        public void succeeded() {
        }

        @Override
        public void failed(TtsErrorCode error) {
        }
    };

    @Override
    public void announcementAccepted(String providerTag) {
    }

    @Override
    public void announcementRejected(String providerTag, String error, String reason) {
    }

    @Override
    public void cacheLookup(boolean hit) {
    }

    @Override
    public SynthesisTimer synthesisStarted(SynthesisUsage usage) {
        return NOOP_TIMER;
    }

    @Override
    public void audioProduced(SynthesisUsage usage, double seconds) {
    }

    @Override
    public void trackQueue(TargetType type, String target, IntSupplier depth) {
    }

    @Override
    public void playbackStarted() {
    }

    @Override
    public void playbackFailed() {
    }
}
