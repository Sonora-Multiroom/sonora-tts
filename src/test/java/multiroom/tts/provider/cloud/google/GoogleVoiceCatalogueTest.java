package multiroom.tts.provider.cloud.google;

import multiroom.tts.config.VoiceCatalogueProperties;
import multiroom.tts.provider.cloud.google.GoogleVoiceCatalogue.CheckResult;
import multiroom.tts.provider.cloud.google.GoogleVoiceCatalogue.CheckResult.Found;
import multiroom.tts.provider.cloud.google.GoogleVoiceCatalogue.CheckResult.Missing;
import multiroom.tts.provider.cloud.google.GoogleVoiceCatalogue.CheckResult.Unavailable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleVoiceCatalogueTest {

    private static final Duration BUDGET = Duration.ofSeconds(3);
    private static final String FIXTURE = loadFixture();

    private MutableClock clock;
    private StubFetcher fetcher;
    private GoogleVoiceCatalogue catalogue;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-24T12:00:00Z"));
        fetcher = new StubFetcher(FIXTURE);
        catalogue = new GoogleVoiceCatalogue("google", new VoiceCatalogueProperties(), clock, fetcher);
    }

    private static String loadFixture() {
        try (InputStream in = GoogleVoiceCatalogueTest.class.getResourceAsStream("/google-voices.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private CheckResult check(String fullName) {
        return catalogue.check(fullName, BUDGET);
    }

    @Test
    void a_constructionFetchesNothing() {
        assertThat(fetcher.calls.get()).isZero();
    }

    @Test
    void b_theFirstCheckFetchesOnceAndAFreshCatalogueIsReused() {
        assertThat(check("uk-UA-Chirp3-HD-Charon")).isInstanceOf(Found.class);
        clock.advance(Duration.ofHours(23));
        assertThat(check("uk-UA-Chirp3-HD-Puck")).isInstanceOf(Found.class);

        assertThat(fetcher.calls.get()).isEqualTo(1);
    }

    @Test
    void c_anExpiredCatalogueIsFetchedAgain() {
        check("uk-UA-Chirp3-HD-Charon");
        clock.advance(Duration.ofHours(24));
        check("uk-UA-Chirp3-HD-Charon");

        assertThat(fetcher.calls.get()).isEqualTo(2);
    }

    @Test
    void d_lookupIgnoresCaseAndAnswersWithTheCataloguesSpelling() {
        CheckResult result = check("UK-UA-CHIRP3-HD-CHARON");

        assertThat(result).isInstanceOf(Found.class);
        assertThat(((Found) result).voice().fullName()).isEqualTo("uk-UA-Chirp3-HD-Charon");
    }

    @Test
    void e_aMissOnAYoungCatalogueListsTheAlternativesWithoutRefetching() {
        check("uk-UA-Chirp3-HD-Charon");
        clock.advance(Duration.ofSeconds(30));

        CheckResult result = check("uk-UA-Chirp3-HD-Charn");

        assertThat(result).isInstanceOf(Missing.class);
        List<CatalogueVoice> alternatives = ((Missing) result).alternatives();
        assertThat(alternatives).hasSize(30);
        assertThat(alternatives).allSatisfy(voice -> {
            assertThat(voice.language()).isEqualTo("uk-UA");
            assertThat(voice.engine()).isEqualTo("Chirp3-HD");
        });
        assertThat(alternatives.get(0).shortName()).isEqualTo("Achernar");
        assertThat(alternatives.get(29).shortName()).isEqualTo("Zubenelgenubi");
        assertThat(fetcher.calls.get()).isEqualTo(1);
    }

    @Test
    void f_aMissOnACatalogueOlderThanTheBackOffRefetchesExactlyOnce() {
        check("uk-UA-Chirp3-HD-Charon");
        clock.advance(Duration.ofSeconds(61));

        assertThat(check("uk-UA-Chirp3-HD-Charn")).isInstanceOf(Missing.class);
        assertThat(fetcher.calls.get()).isEqualTo(2);

        // The refetch made the copy young again: the next typo does not download it once more.
        assertThat(check("uk-UA-Chirp3-HD-Charn")).isInstanceOf(Missing.class);
        assertThat(fetcher.calls.get()).isEqualTo(2);
    }

    @Test
    void g_aVoicePublishedSinceTheLastFetchIsFoundByTheRefetch() {
        check("uk-UA-Chirp3-HD-Charon");
        clock.advance(Duration.ofMinutes(5));
        fetcher.json = FIXTURE.replace("\"voices\": [", "\"voices\": [{\"name\":\"uk-UA-Chirp3-HD-Newstar\"},");

        CheckResult result = check("uk-UA-Chirp3-HD-Newstar");

        assertThat(result).isInstanceOf(Found.class);
        assertThat(fetcher.calls.get()).isEqualTo(2);
    }

    @Test
    void h_aFailedFetchIsRememberedForTheBackOff() {
        fetcher.failure = new IOException("HTTP 503");

        assertThat(check("uk-UA-Chirp3-HD-Charon")).isInstanceOf(Unavailable.class);
        clock.advance(Duration.ofSeconds(59));
        assertThat(check("uk-UA-Chirp3-HD-Charon")).isInstanceOf(Unavailable.class);
        assertThat(check("uk-UA-Chirp3-HD-Puck")).isInstanceOf(Unavailable.class);
        assertThat(fetcher.calls.get()).isEqualTo(1);

        fetcher.failure = null;
        clock.advance(Duration.ofSeconds(1));
        assertThat(check("uk-UA-Chirp3-HD-Charon")).isInstanceOf(Found.class);
        assertThat(fetcher.calls.get()).isEqualTo(2);
    }

    @Test
    void h_theUnavailableReasonCarriesTheFetchFailure() {
        fetcher.failure = new IOException("HTTP 403: API key not valid");

        CheckResult result = check("uk-UA-Chirp3-HD-Charon");

        assertThat(((Unavailable) result).reason()).contains("HTTP 403: API key not valid");
    }

    @Test
    void i_aFailedRefreshKeepsAnsweringFromTheOldCopyUntilItExpires() {
        check("uk-UA-Chirp3-HD-Charon");
        clock.advance(Duration.ofHours(1));
        fetcher.failure = new IOException("HTTP 503");

        // The miss triggers a refresh, which fails: the loaded copy still answers.
        assertThat(check("uk-UA-Chirp3-HD-Charn")).isInstanceOf(Missing.class);
        assertThat(check("uk-UA-Chirp3-HD-Charon")).isInstanceOf(Found.class);
        assertThat(fetcher.calls.get()).isEqualTo(2);

        clock.advance(Duration.ofHours(23));
        assertThat(check("uk-UA-Chirp3-HD-Charon")).isInstanceOf(Unavailable.class);
    }

    @Test
    void j_concurrentColdChecksShareOneFetch() throws Exception {
        fetcher.delay = Duration.ofMillis(200);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<CheckResult>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return check("uk-UA-Chirp3-HD-Charon");
                }));
            }
            start.countDown();
            for (Future<CheckResult> result : results) {
                assertThat(result.get()).isInstanceOf(Found.class);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(fetcher.calls.get()).isEqualTo(1);
    }

    @Test
    void k_bareNamesAreDroppedButTheirFullNamesAreKept() {
        assertThat(check("Achird")).isNotInstanceOf(Found.class);
        assertThat(catalogue.list(null, null, BUDGET)).noneMatch(voice -> voice.fullName().equals("Achird"));

        CheckResult achird = check("uk-UA-Chirp3-HD-Achird");
        assertThat(achird).isInstanceOf(Found.class);
        assertThat(((Found) achird).voice().shortName()).isEqualTo("Achird");
    }

    @Test
    void k_unrecognizedEnginesAreFoundAndGroupedByTheirSegment() {
        assertThat(check("en-US-Polyglot-1")).isInstanceOf(Found.class);

        CheckResult missing = check("en-US-Polyglot-2");
        assertThat(missing).isInstanceOf(Missing.class);
        assertThat(((Missing) missing).alternatives()).extracting(CatalogueVoice::fullName)
                .containsExactly("en-US-Polyglot-1");
    }

    @Test
    void anUnparseableCatalogueIsAFailedFetch() {
        fetcher.json = "<html>oops</html>";

        assertThat(check("uk-UA-Chirp3-HD-Charon")).isInstanceOf(Unavailable.class);
    }

    /** A clock tests move by hand, so expiry and back-off are tested without sleeping. */
    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Returns {@link #json} or throws {@link #failure}, counting calls. */
    static final class StubFetcher implements GoogleVoiceCatalogue.CatalogueFetcher {

        final AtomicInteger calls = new AtomicInteger();
        volatile String json;
        volatile IOException failure;
        volatile Duration delay = Duration.ZERO;

        StubFetcher(String json) {
            this.json = json;
        }

        @Override
        public String fetch(Duration timeout) throws IOException {
            calls.incrementAndGet();
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (failure != null) {
                throw failure;
            }
            return json;
        }
    }

    // --- listing -----------------------------------------------------------------------------

    @Test
    void listFiltersByLanguageAndEngineAliasesIgnoringCaseSortedByShortName() {
        List<CatalogueVoice> voices = catalogue.list("uk-ua", "chirp3-hd", BUDGET);

        assertThat(voices).hasSize(30);
        assertThat(voices).allSatisfy(voice -> {
            assertThat(voice.language()).isEqualTo("uk-UA");
            assertThat(voice.engine()).isEqualTo("Chirp3-HD");
        });
        assertThat(voices).extracting(CatalogueVoice::shortName)
                .isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER)
                .startsWith("Achernar", "Achird")
                .endsWith("Zubenelgenubi");
    }

    @Test
    void listMatchesAnUnrecognizedEngineBySegment() {
        List<CatalogueVoice> voices = catalogue.list(null, "polyglot", BUDGET);

        assertThat(voices).containsExactly(new CatalogueVoice("en-US-Polyglot-1", "1", "Polyglot", "en-US"));
    }

    @Test
    void listWithNoFiltersReturnsEverythingSortedByLanguageEngineAndShortName() {
        List<CatalogueVoice> voices = catalogue.list(null, null, BUDGET);

        // 41 fixture entries minus the bare "Achird".
        assertThat(voices).hasSize(40);
        assertThat(voices.get(0).fullName()).isEqualTo("en-US-Chirp3-HD-Charon");
        assertThat(voices.get(voices.size() - 1).fullName()).isEqualTo("uk-UA-Chirp3-HD-Zubenelgenubi");
        assertThat(voices).extracting(CatalogueVoice::engine).containsSubsequence("Chirp3-HD", "Neural2", "News",
                "Polyglot", "Wavenet");
    }

    @Test
    void listDuringTheBackOffIsUnavailableWithoutAFetch() {
        fetcher.failure = new IOException("HTTP 503");
        assertThat(check("uk-UA-Chirp3-HD-Charon")).isInstanceOf(Unavailable.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> catalogue.list(null, null, BUDGET))
                .isInstanceOf(multiroom.tts.TtsException.class)
                .hasMessage("The voice catalogue of provider 'google' is unavailable: HTTP 503")
                .extracting(e -> ((multiroom.tts.TtsException) e).getErrorCode())
                .isEqualTo(multiroom.tts.TtsErrorCode.VOICE_CATALOGUE_UNAVAILABLE);
        assertThat(fetcher.calls.get()).isEqualTo(1);
    }
}
