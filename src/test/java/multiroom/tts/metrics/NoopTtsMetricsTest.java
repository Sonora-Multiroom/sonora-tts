package multiroom.tts.metrics;

import multiroom.api.model.TargetType;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.config.ProviderType;
import org.junit.jupiter.api.Test;

import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class NoopTtsMetricsTest {

    @Test
    void everyEventIsAcceptedAndIgnored() {
        NoopTtsMetrics metrics = new NoopTtsMetrics();
        SynthesisUsage usage = SynthesisUsage.of("openai", ProviderType.OPENAI, "none", "Hi", null);
        IntSupplier depth = mock(IntSupplier.class);

        assertThatCode(() -> {
            metrics.announcementAccepted("openai");
            metrics.announcementRejected("openai", "TARGET_NOT_FOUND", "none");
            metrics.cacheLookup(true);
            metrics.synthesisStarted(usage).succeeded();
            metrics.synthesisStarted(usage).failed(TtsErrorCode.PROVIDER_TIMEOUT);
            metrics.audioProduced(usage, 1.0);
            metrics.trackQueue(TargetType.SINGLE_OUTPUT, "kitchen", depth);
            metrics.playbackStarted();
            metrics.playbackFailed();
        }).doesNotThrowAnyException();
        verifyNoInteractions(depth);
    }

    @Test
    void everySynthesisSharesOneTimer() {
        NoopTtsMetrics metrics = new NoopTtsMetrics();
        SynthesisUsage usage = SynthesisUsage.of("openai", ProviderType.OPENAI, "none", "Hi", null);

        assertThat(metrics.synthesisStarted(usage)).isSameAs(metrics.synthesisStarted(usage));
    }
}
