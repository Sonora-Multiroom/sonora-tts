package multiroom.tts.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import multiroom.api.model.TargetType;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.cache.AudioCache;
import multiroom.tts.cache.CacheStats;
import multiroom.tts.config.ProviderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MicrometerTtsMetricsTest {

    private SimpleMeterRegistry registry;
    private AudioCache audioCache;
    private MicrometerTtsMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        audioCache = mock(AudioCache.class);
        when(audioCache.stats()).thenReturn(new CacheStats(3, 1_000, 5_000, Map.of()));
        metrics = new MicrometerTtsMetrics(registry, audioCache, 10, List.of("openai", "google"));
    }

    private static SynthesisUsage usage(String text, String stylePrompt) {
        return SynthesisUsage.of("google", ProviderType.GOOGLE_CLOUD, "Neural2", text, stylePrompt);
    }

    // --- robustness -------------------------------------------------------------------------

    @Test
    void aRegistryThatThrowsNeverReachesTheCaller() {
        MeterRegistry broken = mock(MeterRegistry.class);
        when(broken.config()).thenThrow(new IllegalStateException("broken"));
        when(broken.counter(any(), any(Iterable.class))).thenThrow(new IllegalStateException("broken"));

        assertThatCode(() -> {
            MicrometerTtsMetrics throwing = new MicrometerTtsMetrics(broken, audioCache, 10, List.of("openai"));
            throwing.announcementAccepted("openai");
            throwing.announcementRejected("openai", "TARGET_NOT_FOUND", "none");
            throwing.cacheLookup(true);
            TtsMetrics.SynthesisTimer timer = throwing.synthesisStarted(usage("Hi", null));
            timer.succeeded();
            timer.failed(TtsErrorCode.PROVIDER_TIMEOUT);
            throwing.audioProduced(usage("Hi", null), 1.0);
            throwing.trackQueue(TargetType.SINGLE_OUTPUT, "kitchen", () -> 1);
            throwing.playbackStarted();
            throwing.playbackFailed();
        }).doesNotThrowAnyException();
    }

    // --- announcements ----------------------------------------------------------------------

    @Test
    void everyConfiguredProviderHasAnAcceptedSeriesAtZeroFromTheStart() {
        for (String provider : List.of("openai", "google")) {
            Counter accepted = registry.get("tts.announcements").tags("outcome", "accepted", "error", "none",
                    "reason", "none", "provider", provider).counter();
            assertThat(accepted.count()).isZero();
        }
    }

    @Test
    void anAcceptedAnnouncementIncrementsItsProvidersSeries() {
        metrics.announcementAccepted("openai");

        assertThat(registry.get("tts.announcements").tags("outcome", "accepted", "error", "none", "reason", "none",
                "provider", "openai").counter().count()).isEqualTo(1);
    }

    @Test
    void aRejectedAnnouncementCarriesItsErrorAndReason() {
        metrics.announcementRejected("openai", "PROVIDER_ERROR", "queue_full");

        assertThat(registry.get("tts.announcements").tags("outcome", "rejected", "error", "PROVIDER_ERROR",
                "reason", "queue_full", "provider", "openai").counter().count()).isEqualTo(1);
    }

    @Test
    void missingTagValuesBecomeFixedWords() {
        metrics.announcementRejected(null, null, null);

        assertThat(registry.get("tts.announcements").tags("outcome", "rejected", "error", "none", "reason", "none",
                "provider", "unknown").counter().count()).isEqualTo(1);
    }

    // --- synthesis --------------------------------------------------------------------------

    @Test
    void aSuccessfulSynthesisRecordsOneTimerSampleAndTheTextCharacters() {
        metrics.synthesisStarted(usage("Hello", null)).succeeded();

        Timer timer = registry.get("tts.synthesis").tags("provider", "google", "type", "google-cloud",
                "outcome", "success", "error", "none").timer();
        assertThat(timer.count()).isEqualTo(1);
        DistributionSummary text = registry.get("tts.synthesis.characters").tags("provider", "google",
                "type", "google-cloud", "tier", "Neural2", "outcome", "success", "error", "none", "part", "text")
                .summary();
        assertThat(text.count()).isEqualTo(1);
        assertThat(text.totalAmount()).isEqualTo(5);
        assertThat(registry.find("tts.synthesis.characters").tag("part", "style_prompt").summary()).isNull();
    }

    @Test
    void aFailedSynthesisCarriesItsErrorCode() {
        metrics.synthesisStarted(usage("Hello", null)).failed(TtsErrorCode.PROVIDER_TIMEOUT);

        assertThat(registry.get("tts.synthesis").tags("outcome", "failure", "error", "PROVIDER_TIMEOUT").timer()
                .count()).isEqualTo(1);
        assertThat(registry.get("tts.synthesis.characters").tags("outcome", "failure", "error", "PROVIDER_TIMEOUT",
                "part", "text").summary().totalAmount()).isEqualTo(5);
    }

    @Test
    void aStylePromptIsRecordedAsItsOwnPart() {
        metrics.synthesisStarted(SynthesisUsage.of("gemini", ProviderType.GOOGLE_GEMINI, "gemini-2.5-flash-tts",
                "Hello", "Warmly")).succeeded();

        assertThat(registry.get("tts.synthesis.characters").tags("provider", "gemini", "tier",
                "gemini-2.5-flash-tts", "part", "style_prompt").summary().totalAmount()).isEqualTo(6);
    }

    @Test
    void aProviderWithNoTypeIsTaggedUnknown() {
        metrics.synthesisStarted(SynthesisUsage.of("mock", null, "none", "Hi", null)).succeeded();

        assertThat(registry.get("tts.synthesis").tags("provider", "mock", "type", "unknown").timer().count())
                .isEqualTo(1);
    }

    @Test
    void audioProducedRecordsItsSecondsUnderTheProviderAndTier() {
        metrics.audioProduced(usage("Hello", null), 2.5);

        DistributionSummary audio = registry.get("tts.synthesis.audio").tags("provider", "google",
                "type", "google-cloud", "tier", "Neural2").summary();
        assertThat(audio.totalAmount()).isEqualTo(2.5);
    }

    @Test
    void theTimerHasTheDefaultTimeoutsBuckets() {
        metrics.synthesisStarted(usage("Hello", null)).succeeded();

        CountAtBucket[] buckets = registry.get("tts.synthesis").timer().takeSnapshot().histogramCounts();
        assertThat(Arrays.stream(buckets).map(b -> b.bucket(java.util.concurrent.TimeUnit.SECONDS)))
                .containsExactly(0.1, 0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 7.5, 10.0);
    }

    @Test
    void bucketsStopAtTheFirstBoundAtOrAboveTheLongestTimeoutAndIncludeIt() {
        assertThat(seconds(MicrometerTtsMetrics.synthesisBuckets(30)))
                .containsExactly(0.1, 0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 7.5, 10.0, 15.0, 20.0, 30.0);
        assertThat(seconds(MicrometerTtsMetrics.synthesisBuckets(25)))
                .containsExactly(0.1, 0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 7.5, 10.0, 15.0, 20.0, 25.0, 30.0);
        assertThat(seconds(MicrometerTtsMetrics.synthesisBuckets(200)))
                .containsExactly(0.1, 0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 7.5, 10.0, 15.0, 20.0, 30.0, 45.0, 60.0,
                        90.0, 120.0, 200.0);
    }

    private static List<Double> seconds(Duration[] buckets) {
        return Arrays.stream(buckets).map(d -> d.toMillis() / 1000.0).toList();
    }

    // --- cache ------------------------------------------------------------------------------

    @Test
    void cacheLookupsAreCountedByOutcome() {
        metrics.cacheLookup(true);
        metrics.cacheLookup(true);
        metrics.cacheLookup(false);

        assertThat(registry.get("tts.cache.requests").tag("outcome", "hit").counter().count()).isEqualTo(2);
        assertThat(registry.get("tts.cache.requests").tag("outcome", "miss").counter().count()).isEqualTo(1);
    }

    @Test
    void cacheGaugesReadTheCacheAtReadTime() {
        Gauge size = registry.get("tts.cache.size").gauge();
        assertThat(size.getId().getBaseUnit()).isEqualTo("bytes");
        assertThat(size.value()).isEqualTo(1_000);
        assertThat(registry.get("tts.cache.max").gauge().value()).isEqualTo(5_000);
        assertThat(registry.get("tts.cache.max").gauge().getId().getBaseUnit()).isEqualTo("bytes");
        assertThat(registry.get("tts.cache.entries").gauge().value()).isEqualTo(3);

        when(audioCache.stats()).thenReturn(new CacheStats(0, 0, 5_000, Map.of()));

        assertThat(size.value()).isZero();
        assertThat(registry.get("tts.cache.entries").gauge().value()).isZero();
    }

    // --- queue and playback -----------------------------------------------------------------

    @Test
    void aQueueGaugeReadsItsSupplierLive() {
        AtomicInteger depth = new AtomicInteger(2);
        metrics.trackQueue(TargetType.SINGLE_OUTPUT, "kitchen", depth::get);

        Gauge gauge = registry.get("tts.queue.depth").tags("target", "kitchen", "target_type", "output").gauge();
        assertThat(gauge.value()).isEqualTo(2);
        depth.set(0);
        assertThat(gauge.value()).isZero();
    }

    @Test
    void aGroupIsTaggedGroup() {
        metrics.trackQueue(TargetType.OUTPUT_GROUP, "all-rooms", () -> 1);

        assertThat(registry.get("tts.queue.depth").tags("target", "all-rooms", "target_type", "group").gauge()
                .value()).isEqualTo(1);
    }

    @Test
    void aSecondTrackForTheSameTargetKeepsTheFirstSupplier() {
        metrics.trackQueue(TargetType.SINGLE_OUTPUT, "kitchen", () -> 1);
        metrics.trackQueue(TargetType.SINGLE_OUTPUT, "kitchen", () -> 7);

        assertThat(registry.find("tts.queue.depth").gauges()).hasSize(1);
        assertThat(registry.get("tts.queue.depth").gauge().value()).isEqualTo(1);
    }

    @Test
    void playbacksAreCountedByOutcome() {
        metrics.playbackStarted();
        metrics.playbackStarted();
        metrics.playbackFailed();

        assertThat(registry.get("tts.playbacks").tag("outcome", "started").counter().count()).isEqualTo(2);
        assertThat(registry.get("tts.playbacks").tag("outcome", "failed").counter().count()).isEqualTo(1);
    }
}
