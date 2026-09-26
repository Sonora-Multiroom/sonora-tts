package multiroom.tts.metrics;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import multiroom.api.model.TargetType;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.cache.AudioCache;
import multiroom.tts.cache.CacheStats;
import multiroom.tts.config.ProviderType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The names and series the operator guide's queries depend on, as a real Prometheus scrape shows
 * them. A {@code SimpleMeterRegistry} would not catch the failure that matters most here:
 * Prometheus silently drops a meter whose tag keys differ from the first one of its name.
 */
class PrometheusNamesTest {

    private static String scrape;

    @BeforeAll
    static void recordOneOfEveryCombinationAndScrape() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        AudioCache audioCache = mock(AudioCache.class);
        when(audioCache.stats()).thenReturn(new CacheStats(2, 2_048, 1_048_576, Map.of()));
        MicrometerTtsMetrics metrics = new MicrometerTtsMetrics(registry, audioCache, 10, List.of("openai", "gemini"));

        metrics.announcementAccepted("openai");
        metrics.announcementRejected("openai", "TARGET_NOT_FOUND", "none");
        metrics.announcementRejected("openai", "PROVIDER_ERROR", "queue_full");
        metrics.announcementRejected("openai", "PROVIDER_ERROR", "shutting_down");
        metrics.announcementRejected("unknown", "INVALID_REQUEST", "none");
        metrics.announcementRejected("openai", "INTERNAL", "none");

        SynthesisUsage openai = SynthesisUsage.of("openai", ProviderType.OPENAI, "none", "Hello", null);
        SynthesisUsage gemini = SynthesisUsage.of("gemini", ProviderType.GOOGLE_GEMINI, "gemini-2.5-flash-tts",
                "Hello", "Warmly");
        metrics.synthesisStarted(openai).succeeded();
        metrics.synthesisStarted(openai).failed(TtsErrorCode.PROVIDER_TIMEOUT);
        metrics.synthesisStarted(gemini).succeeded();
        metrics.audioProduced(openai, 1.5);
        metrics.audioProduced(gemini, 2.0);

        metrics.cacheLookup(true);
        metrics.cacheLookup(false);
        metrics.trackQueue(TargetType.SINGLE_OUTPUT, "kitchen", () -> 1);
        metrics.trackQueue(TargetType.OUTPUT_GROUP, "all-rooms", () -> 0);
        metrics.playbackStarted();
        metrics.playbackFailed();

        scrape = registry.scrape();
    }

    @Test
    void everySeriesTheGuideQueriesIsScraped() {
        List<String> names = List.of(
                "tts_announcements_total",
                "tts_synthesis_seconds_count", "tts_synthesis_seconds_sum", "tts_synthesis_seconds_max",
                "tts_synthesis_seconds_bucket",
                "tts_synthesis_characters_count", "tts_synthesis_characters_sum", "tts_synthesis_characters_max",
                "tts_synthesis_audio_seconds_count", "tts_synthesis_audio_seconds_sum",
                "tts_synthesis_audio_seconds_max",
                "tts_cache_requests_total", "tts_cache_size_bytes", "tts_cache_max_bytes", "tts_cache_entries",
                "tts_queue_depth", "tts_playbacks_total");

        for (String name : names) {
            assertThat(scrape).as(name).containsPattern("(?m)^" + name + "\\{?[ {]?");
        }
    }

    @Test
    void noCombinationIsDroppedForADifferentSetOfTagKeys() {
        assertThat(scrape).contains(
                "tts_announcements_total{error=\"none\",outcome=\"accepted\",provider=\"openai\",reason=\"none\"} 1",
                "tts_announcements_total{error=\"none\",outcome=\"accepted\",provider=\"gemini\",reason=\"none\"} 0",
                "tts_announcements_total{error=\"TARGET_NOT_FOUND\",outcome=\"rejected\",provider=\"openai\",reason=\"none\"} 1",
                "tts_announcements_total{error=\"PROVIDER_ERROR\",outcome=\"rejected\",provider=\"openai\",reason=\"queue_full\"} 1",
                "tts_announcements_total{error=\"PROVIDER_ERROR\",outcome=\"rejected\",provider=\"openai\",reason=\"shutting_down\"} 1",
                "tts_announcements_total{error=\"INVALID_REQUEST\",outcome=\"rejected\",provider=\"unknown\",reason=\"none\"} 1",
                "tts_announcements_total{error=\"INTERNAL\",outcome=\"rejected\",provider=\"openai\",reason=\"none\"} 1",
                "tts_synthesis_seconds_count{error=\"none\",outcome=\"success\",provider=\"openai\",type=\"openai\"} 1",
                "tts_synthesis_seconds_count{error=\"PROVIDER_TIMEOUT\",outcome=\"failure\",provider=\"openai\",type=\"openai\"} 1",
                "tts_synthesis_seconds_count{error=\"none\",outcome=\"success\",provider=\"gemini\",type=\"google-gemini\"} 1",
                "tts_synthesis_characters_sum{error=\"none\",outcome=\"success\",part=\"text\",provider=\"openai\",tier=\"none\",type=\"openai\"} 5",
                "tts_synthesis_characters_sum{error=\"PROVIDER_TIMEOUT\",outcome=\"failure\",part=\"text\",provider=\"openai\",tier=\"none\",type=\"openai\"} 5",
                "tts_synthesis_characters_sum{error=\"none\",outcome=\"success\",part=\"style_prompt\",provider=\"gemini\",tier=\"gemini-2.5-flash-tts\",type=\"google-gemini\"} 6",
                "tts_synthesis_audio_seconds_sum{provider=\"openai\",tier=\"none\",type=\"openai\"} 1.5",
                "tts_synthesis_audio_seconds_sum{provider=\"gemini\",tier=\"gemini-2.5-flash-tts\",type=\"google-gemini\"} 2",
                "tts_cache_requests_total{outcome=\"hit\"} 1",
                "tts_cache_requests_total{outcome=\"miss\"} 1",
                "tts_cache_size_bytes 2048",
                "tts_cache_max_bytes 1048576",
                "tts_cache_entries 2",
                "tts_queue_depth{target=\"kitchen\",target_type=\"output\"} 1",
                "tts_queue_depth{target=\"all-rooms\",target_type=\"group\"} 0",
                "tts_playbacks_total{outcome=\"started\"} 1",
                "tts_playbacks_total{outcome=\"failed\"} 1");
    }

    @Test
    void theBucketBoundsAreThePlannedListUpToTheLongestTimeout() {
        Matcher bounds = Pattern.compile(
                "tts_synthesis_seconds_bucket\\{error=\"none\",outcome=\"success\",provider=\"openai\",type=\"openai\","
                        + "le=\"([^\"]+)\"}")
                .matcher(scrape);
        List<String> found = new java.util.ArrayList<>();
        while (bounds.find()) {
            found.add(bounds.group(1));
        }

        assertThat(found.stream().map(le -> le.equals("+Inf") ? Double.POSITIVE_INFINITY : Double.parseDouble(le)))
                .containsExactlyElementsOf(Arrays.asList(0.1, 0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 7.5, 10.0,
                        Double.POSITIVE_INFINITY));
    }

    @Test
    void everyMetricHasADescription() {
        Matcher help = Pattern.compile("(?m)^# HELP (tts_\\S+) ?(.*)$").matcher(scrape);
        int metrics = 0;
        while (help.find()) {
            metrics++;
            assertThat(help.group(2)).as(help.group(1)).isNotBlank();
        }

        assertThat(metrics).isPositive();
    }
}
