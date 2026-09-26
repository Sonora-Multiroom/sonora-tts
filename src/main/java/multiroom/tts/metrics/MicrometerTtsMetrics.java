package multiroom.tts.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import multiroom.api.model.TargetType;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.cache.AudioCache;
import multiroom.tts.cache.CacheStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.function.ToDoubleFunction;

/**
 * {@link TtsMetrics} over the host's {@link MeterRegistry}, so the meters appear in the host's own
 * {@code /actuator/prometheus}. This is the <strong>only</strong> class that imports Micrometer:
 * everything else talks to {@link TtsMetrics}, which is what lets the module load on a host
 * without Micrometer.
 *
 * <p>Two rules hold for every meter here. Every series of one metric carries the same tag keys,
 * with a fixed word for a value that does not apply, because Prometheus silently drops a meter
 * whose key set differs from the first one registered under its name. And no public method
 * throws: a failure to record is logged and swallowed, never passed on to an announcement.
 */
public class MicrometerTtsMetrics implements TtsMetrics {

    private static final Logger log = LoggerFactory.getLogger(MicrometerTtsMetrics.class);

    /**
     * Fixed histogram bounds rather than Micrometer's generated percentile histogram, which has
     * about 70 buckets per series; these give at most 17 and still let {@code histogram_quantile}
     * interpolate p95/p99 well enough.
     */
    static final List<Double> SYNTHESIS_BUCKET_SECONDS =
            List.of(0.1, 0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 7.5, 10.0, 15.0, 20.0, 30.0, 45.0, 60.0, 90.0, 120.0);

    private static final String ANNOUNCEMENTS = "tts.announcements";
    private static final String SYNTHESIS = "tts.synthesis";
    private static final String SYNTHESIS_CHARACTERS = "tts.synthesis.characters";
    private static final String SYNTHESIS_AUDIO = "tts.synthesis.audio";
    private static final String CACHE_REQUESTS = "tts.cache.requests";
    private static final String QUEUE_DEPTH = "tts.queue.depth";
    private static final String PLAYBACKS = "tts.playbacks";

    private final MeterRegistry registry;
    private final Duration[] synthesisBuckets;
    private final Set<String> trackedQueues = ConcurrentHashMap.newKeySet();
    private final Set<String> metricsWarnedAbout = ConcurrentHashMap.newKeySet();

    /**
     * Registers the cache gauges, and the accepted-announcement series of every configured
     * provider at 0, so a dashboard shows "nothing yet" rather than "no data" before the first
     * announcement.
     *
     * @param longestTimeoutSeconds the longest {@code timeout-seconds} among the enabled provider
     *                              entries; the synthesis histogram's buckets reach it
     * @param providerNames         the configured provider names
     */
    public MicrometerTtsMetrics(MeterRegistry registry, AudioCache audioCache, int longestTimeoutSeconds,
                                Collection<String> providerNames) {
        this.registry = registry;
        this.synthesisBuckets = synthesisBuckets(longestTimeoutSeconds);
        record("tts.cache", () -> registerCacheGauges(audioCache));
        record(ANNOUNCEMENTS, () -> providerNames.forEach(name -> announcementCounter("accepted", NONE, NONE, name)));
    }

    /**
     * The bucket list cut after the first bound at or above the longest timeout, with that timeout
     * added when it is not already a bound, so a p99 can be read against the timeout itself.
     */
    static Duration[] synthesisBuckets(int longestTimeoutSeconds) {
        List<Double> bounds = new ArrayList<>();
        for (double bound : SYNTHESIS_BUCKET_SECONDS) {
            bounds.add(bound);
            if (bound >= longestTimeoutSeconds) {
                break;
            }
        }
        if (!bounds.contains((double) longestTimeoutSeconds)) {
            bounds.add((double) longestTimeoutSeconds);
        }
        return bounds.stream()
                .sorted()
                .map(seconds -> Duration.ofMillis(Math.round(seconds * 1000)))
                .toArray(Duration[]::new);
    }

    private void registerCacheGauges(AudioCache audioCache) {
        cacheGauge("tts.cache.size", "Current size of the TTS audio cache",
                audioCache, stats -> stats.totalSizeBytes(), "bytes");
        cacheGauge("tts.cache.max", "Configured maximum size of the TTS audio cache",
                audioCache, stats -> stats.maxSizeBytes(), "bytes");
        cacheGauge("tts.cache.entries", "Entries in the TTS audio cache",
                audioCache, stats -> stats.totalEntries(), null);
    }

    /** Reads {@code AudioCache.stats()} at scrape time; the index is in memory, so this is no I/O. */
    private void cacheGauge(String name, String description, AudioCache audioCache,
                            ToDoubleFunction<CacheStats> value, String unit) {
        Gauge.builder(name, audioCache, cache -> value.applyAsDouble(cache.stats()))
                .description(description)
                .baseUnit(unit)
                .strongReference(true)
                .register(registry);
    }

    @Override
    public void announcementAccepted(String providerTag) {
        record(ANNOUNCEMENTS, () -> announcementCounter("accepted", NONE, NONE, providerTag).increment());
    }

    @Override
    public void announcementRejected(String providerTag, String error, String reason) {
        record(ANNOUNCEMENTS, () -> announcementCounter("rejected", error, reason, providerTag).increment());
    }

    private Counter announcementCounter(String outcome, String error, String reason, String providerTag) {
        return Counter.builder(ANNOUNCEMENTS)
                .description("Speak requests answered, by outcome, error code, rejection reason and provider")
                .tag("outcome", outcome)
                .tag("error", tag(error, NONE))
                .tag("reason", tag(reason, NONE))
                .tag("provider", tag(providerTag, UNKNOWN))
                .register(registry);
    }

    @Override
    public void cacheLookup(boolean hit) {
        record(CACHE_REQUESTS, () -> Counter.builder(CACHE_REQUESTS)
                .description("TTS audio cache lookups made for announcements, by hit or miss")
                .tag("outcome", hit ? "hit" : "miss")
                .register(registry)
                .increment());
    }

    @Override
    public SynthesisTimer synthesisStarted(SynthesisUsage usage) {
        try {
            return new MicrometerSynthesisTimer(usage, Timer.start(registry));
        } catch (RuntimeException e) {
            warn(SYNTHESIS, e);
            return new NoopTtsMetrics().synthesisStarted(usage);
        }
    }

    @Override
    public void audioProduced(SynthesisUsage usage, double seconds) {
        record(SYNTHESIS_AUDIO, () -> DistributionSummary.builder(SYNTHESIS_AUDIO)
                .description("Duration of the audio produced by each successful synthesis")
                .baseUnit("seconds")
                .tag("provider", tag(usage.providerName(), UNKNOWN))
                .tag("type", typeTag(usage))
                .tag("tier", tag(usage.tier(), NONE))
                .register(registry)
                .record(seconds));
    }

    @Override
    public void trackQueue(TargetType type, String target, IntSupplier depth) {
        record(QUEUE_DEPTH, () -> {
            if (trackedQueues.add(type + ":" + target)) {
                Gauge.builder(QUEUE_DEPTH, depth, IntSupplier::getAsInt)
                        .description("Announcements waiting for a target, excluding the one playing")
                        .tag("target", target)
                        .tag("target_type", type == TargetType.OUTPUT_GROUP ? "group" : "output")
                        .strongReference(true)
                        .register(registry);
            }
        });
    }

    @Override
    public void playbackStarted() {
        playback("started");
    }

    @Override
    public void playbackFailed() {
        playback("failed");
    }

    private void playback(String outcome) {
        record(PLAYBACKS, () -> Counter.builder(PLAYBACKS)
                .description("Queued announcements taken off their queue, by whether playback started")
                .tag("outcome", outcome)
                .register(registry)
                .increment());
    }

    /** Runs one recording, logging instead of throwing: at {@code WARN} once per metric, then {@code DEBUG}. */
    private void record(String metric, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            warn(metric, e);
        }
    }

    private void warn(String metric, RuntimeException e) {
        if (metricsWarnedAbout.add(metric)) {
            log.warn("multiroom-tts: failed to record metric {}; further failures are logged at DEBUG", metric, e);
        } else {
            log.debug("multiroom-tts: failed to record metric {}", metric, e);
        }
    }

    private static String tag(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String typeTag(SynthesisUsage usage) {
        return usage.providerType() == null ? UNKNOWN : usage.providerType().configName();
    }

    /** One provider call: the timer sample, then the characters sent, under the same outcome. */
    private final class MicrometerSynthesisTimer implements SynthesisTimer {

        private final SynthesisUsage usage;
        private final Timer.Sample sample;

        private MicrometerSynthesisTimer(SynthesisUsage usage, Timer.Sample sample) {
            this.usage = usage;
            this.sample = sample;
        }

        @Override
        public void succeeded() {
            finish("success", NONE);
        }

        @Override
        public void failed(TtsErrorCode error) {
            finish("failure", error == null ? TtsErrorCode.PROVIDER_ERROR.name() : error.name());
        }

        private void finish(String outcome, String error) {
            String provider = tag(usage.providerName(), UNKNOWN);
            String type = typeTag(usage);
            record(SYNTHESIS, () -> sample.stop(Timer.builder(SYNTHESIS)
                    .description("Time a provider took to synthesize speech, excluding format conversion")
                    .serviceLevelObjectives(synthesisBuckets)
                    .tag("provider", provider)
                    .tag("type", type)
                    .tag("outcome", outcome)
                    .tag("error", error)
                    .register(registry)));
            record(SYNTHESIS_CHARACTERS, () -> {
                characters("text", usage.textCharacters(), provider, type, outcome, error);
                characters("style_prompt", usage.stylePromptCharacters(), provider, type, outcome, error);
            });
        }

        /** An empty part is not recorded, so a provider with no style prompt adds no zero samples. */
        private void characters(String part, int count, String provider, String type, String outcome,
                                String error) {
            if (count <= 0) {
                return;
            }
            DistributionSummary.builder(SYNTHESIS_CHARACTERS)
                    .description("Characters sent to a provider per synthesis, by request part")
                    .baseUnit("characters")
                    .tag("provider", provider)
                    .tag("type", type)
                    .tag("tier", tag(usage.tier(), NONE))
                    .tag("outcome", outcome)
                    .tag("error", error)
                    .tag("part", part)
                    .register(registry)
                    .record(count);
        }
    }
}
