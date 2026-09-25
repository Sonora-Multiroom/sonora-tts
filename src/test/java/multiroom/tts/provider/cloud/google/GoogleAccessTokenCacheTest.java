package multiroom.tts.provider.cloud.google;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.provider.cloud.google.GoogleVoiceCatalogueTest.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleAccessTokenCacheTest {

    private static final Duration HOUR = Duration.ofSeconds(3599);
    private static final Duration BUDGET = Duration.ofSeconds(2);

    @TempDir
    static Path dir;

    private static ServiceAccountKey key;

    private MutableClock clock;
    private CountingExchange exchange;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        if (key == null) {
            key = ServiceAccountKey.load("g", TestServiceAccountKeys.write(dir, Map.of("token_uri", "http://localhost:9/token")));
        }
        clock = new MutableClock(Instant.parse("2026-09-25T10:00:00Z"));
        exchange = new CountingExchange(clock);
        logs = new ListAppender<>();
        logs.start();
        logger().addAppender(logs);
        logger().setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        logger().detachAppender(logs);
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(GoogleAccessTokenCache.class);
    }

    private GoogleAccessTokenCache cache(Duration failureBackoff) {
        return new GoogleAccessTokenCache("g", new ServiceAccountAssertion(key,
                ServiceAccountAssertion.CLOUD_PLATFORM_SCOPE, clock), exchange, key.clientEmail(), failureBackoff, clock);
    }

    private GoogleAccessTokenCache cache() {
        return cache(Duration.ofSeconds(60));
    }

    /** Counts calls and answers with whatever the test queued, a fresh token by default. */
    static final class CountingExchange implements GoogleTokenExchange.Exchanger {

        private final MutableClock clock;
        final AtomicInteger calls = new AtomicInteger();
        volatile Duration lifetime = HOUR;
        volatile Supplier<RuntimeException> failure;
        volatile Duration advanceDuring = Duration.ZERO;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;

        CountingExchange(MutableClock clock) {
            this.clock = clock;
        }

        @Override
        public GoogleTokenExchange.Token exchange(String assertion, Duration timeout) {
            int n = calls.incrementAndGet();
            if (entered != null) {
                entered.countDown();
            }
            if (release != null) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            clock.advance(advanceDuring);
            if (failure != null) {
                throw failure.get();
            }
            return new GoogleTokenExchange.Token("token-" + n, lifetime);
        }
    }

    private static TokenFailure.Unavailable unavailable() {
        return new TokenFailure.Unavailable(TtsErrorCode.PROVIDER_TIMEOUT, "g",
                "the token service did not respond within 2 seconds", null);
    }

    private static TokenFailure.Rejected rejected() {
        return new TokenFailure.Rejected(TtsErrorCode.PROVIDER_ERROR, "g",
                "Google rejected the service account key (HTTP 400): Invalid JWT Signature. (invalid_grant)");
    }

    // --- reuse and renewal ---------------------------------------------------------------------

    @Test
    void a_theFirstCallFetchesAndTheSecondReuses() {
        GoogleAccessTokenCache cache = cache();

        assertThat(cache.token(BUDGET)).isEqualTo("token-1");
        assertThat(cache.token(BUDGET)).isEqualTo("token-1");
        assertThat(exchange.calls).hasValue(1);
    }

    @Test
    void b_renewalStartsFiveMinutesBeforeExpiry() {
        GoogleAccessTokenCache cache = cache();
        cache.token(BUDGET);

        clock.advance(HOUR.minusMinutes(5).minusSeconds(1));
        assertThat(cache.token(BUDGET)).isEqualTo("token-1");
        clock.advance(Duration.ofSeconds(1));
        assertThat(cache.token(BUDGET)).isEqualTo("token-2");
        assertThat(exchange.calls).hasValue(2);
    }

    @Test
    void c_aShortLivedTokenIsRenewedAtHalfItsLifetime() {
        exchange.lifetime = Duration.ofSeconds(300);
        GoogleAccessTokenCache cache = cache();
        cache.token(BUDGET);

        clock.advance(Duration.ofSeconds(149));
        assertThat(cache.token(BUDGET)).isEqualTo("token-1");
        clock.advance(Duration.ofSeconds(1));
        assertThat(cache.token(BUDGET)).isEqualTo("token-2");
    }

    @Test
    void d_aSteadyHourCostsExactlyTwoFetches() {
        GoogleAccessTokenCache cache = cache();

        for (int second = 0; second < 3600; second += 10) {
            cache.token(BUDGET);
            clock.advance(Duration.ofSeconds(10));
        }

        assertThat(exchange.calls).hasValue(2);
    }

    @Test
    void e_concurrentCallersOnAnEmptyCacheShareOneFetch() throws Exception {
        GoogleAccessTokenCache cache = cache();
        exchange.release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return cache.token(Duration.ofSeconds(5));
                }));
            }
            start.countDown();
            Thread.sleep(200);
            exchange.release.countDown();

            Set<String> tokens = new HashSet<>();
            for (Future<String> result : results) {
                tokens.add(result.get(5, TimeUnit.SECONDS));
            }
            assertThat(tokens).containsExactly("token-1");
            assertThat(exchange.calls).hasValue(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void f_discardClearsOnlyTheTokenStillHeld() {
        GoogleAccessTokenCache cache = cache();
        cache.token(BUDGET);

        cache.discard("stale");
        assertThat(cache.token(BUDGET)).isEqualTo("token-1");
        assertThat(exchange.calls).hasValue(1);

        cache.discard("token-1");
        assertThat(cache.token(BUDGET)).isEqualTo("token-2");
    }

    @Test
    void g_theExpiryIsMeasuredFromBeforeTheExchange() {
        exchange.advanceDuring = Duration.ofSeconds(30);
        GoogleAccessTokenCache cache = cache();
        cache.token(BUDGET);

        // Renewal is due 3299 s after the request was sent, 3269 s after it returned.
        clock.advance(Duration.ofSeconds(3268));
        exchange.advanceDuring = Duration.ZERO;
        assertThat(cache.token(BUDGET)).isEqualTo("token-1");
        clock.advance(Duration.ofSeconds(1));
        assertThat(cache.token(BUDGET)).isEqualTo("token-2");
    }

    @Test
    void h_aCallerWhoseBudgetRunsOutWaitingTimesOutWithoutABackOff() throws Exception {
        GoogleAccessTokenCache cache = cache();
        exchange.entered = new CountDownLatch(1);
        exchange.release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> holder = pool.submit(() -> cache.token(Duration.ofSeconds(5)));
            assertThat(exchange.entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> cache.token(Duration.ofMillis(50)))
                    .isInstanceOf(TtsException.class)
                    .isNotInstanceOf(TokenFailure.class)
                    .hasMessageContaining("another request was still fetching one")
                    .extracting(e -> ((TtsException) e).getErrorCode())
                    .isEqualTo(TtsErrorCode.PROVIDER_TIMEOUT);

            exchange.release.countDown();
            assertThat(holder.get(5, TimeUnit.SECONDS)).isEqualTo("token-1");
            assertThat(cache.token(BUDGET)).isEqualTo("token-1");
            assertThat(exchange.calls).hasValue(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // --- failures and the back-off ------------------------------------------------------------

    @Test
    void backoff_a_anUnavailableServiceIsHeldOffWithTheSameCode() {
        GoogleAccessTokenCache cache = cache();
        exchange.failure = GoogleAccessTokenCacheTest::unavailable;

        assertThatThrownBy(() -> cache.token(BUDGET)).isInstanceOf(TokenFailure.Unavailable.class);
        clock.advance(Duration.ofSeconds(59));
        assertThatThrownBy(() -> cache.token(BUDGET))
                .isInstanceOf(TtsException.class)
                .hasMessageContaining("in back-off after a recent failure")
                .hasMessageContaining("did not respond within 2 seconds")
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.PROVIDER_TIMEOUT);
        assertThat(exchange.calls).hasValue(1);
    }

    @Test
    void backoff_b_afterTheBackOffTheNextCallFetches() {
        GoogleAccessTokenCache cache = cache();
        exchange.failure = GoogleAccessTokenCacheTest::unavailable;
        assertThatThrownBy(() -> cache.token(BUDGET)).isInstanceOf(TokenFailure.class);

        clock.advance(Duration.ofSeconds(60));
        exchange.failure = null;

        assertThat(cache.token(BUDGET)).isEqualTo("token-2");
    }

    @Test
    void backoff_c_aRejectionIsNeverHeldOff() {
        GoogleAccessTokenCache cache = cache();
        exchange.failure = GoogleAccessTokenCacheTest::rejected;

        assertThatThrownBy(() -> cache.token(BUDGET)).isInstanceOf(TokenFailure.Rejected.class);
        assertThatThrownBy(() -> cache.token(BUDGET)).isInstanceOf(TokenFailure.Rejected.class);
        assertThat(exchange.calls).hasValue(2);

        exchange.failure = null;
        assertThat(cache.token(BUDGET)).isEqualTo("token-3");
    }

    @Test
    void backoff_d_aValidTokenIsUsedThroughAFailedRenewalAndTheBackOff() {
        GoogleAccessTokenCache cache = cache(Duration.ofMinutes(10));
        assertThat(cache.token(BUDGET)).isEqualTo("token-1");

        clock.advance(HOUR.minusMinutes(5));
        exchange.failure = GoogleAccessTokenCacheTest::unavailable;
        assertThat(cache.token(BUDGET)).isEqualTo("token-1");
        assertThat(exchange.calls).hasValue(2);

        assertThat(cache.token(BUDGET)).isEqualTo("token-1");
        assertThat(exchange.calls).hasValue(2);

        clock.advance(Duration.ofMinutes(5));
        assertThatThrownBy(() -> cache.token(BUDGET))
                .isInstanceOf(TtsException.class)
                .hasMessageContaining("in back-off after a recent failure");
        assertThat(exchange.calls).hasValue(2);
    }

    @Test
    void backoff_d_aDiscardedTokenIsNotUsedDuringTheBackOff() {
        GoogleAccessTokenCache cache = cache(Duration.ofMinutes(10));
        cache.token(BUDGET);
        clock.advance(HOUR.minusMinutes(5));
        exchange.failure = GoogleAccessTokenCacheTest::unavailable;
        assertThat(cache.token(BUDGET)).isEqualTo("token-1");

        cache.discard("token-1");

        assertThatThrownBy(() -> cache.token(BUDGET))
                .isInstanceOf(TtsException.class)
                .hasMessageContaining("in back-off after a recent failure");
        assertThat(exchange.calls).hasValue(2);
    }

    @Test
    void backoff_e_aSuccessClearsTheBackOff() {
        GoogleAccessTokenCache cache = cache();
        exchange.failure = GoogleAccessTokenCacheTest::unavailable;
        assertThatThrownBy(() -> cache.token(BUDGET)).isInstanceOf(TokenFailure.class);
        clock.advance(Duration.ofSeconds(60));
        exchange.failure = null;
        assertThat(cache.token(BUDGET)).isEqualTo("token-2");

        // A discard now fetches at once: no back-off is left over.
        cache.discard("token-2");
        assertThat(cache.token(BUDGET)).isEqualTo("token-3");
    }

    @Test
    void backoff_f_failuresAreLoggedWithTheAccountAndNeverTheToken() {
        GoogleAccessTokenCache cache = cache();
        cache.token(BUDGET);
        cache.discard("token-1");
        exchange.failure = GoogleAccessTokenCacheTest::unavailable;
        assertThatThrownBy(() -> cache.token(BUDGET)).isInstanceOf(TokenFailure.class);
        clock.advance(Duration.ofMinutes(2));
        exchange.failure = GoogleAccessTokenCacheTest::rejected;
        assertThatThrownBy(() -> cache.token(BUDGET)).isInstanceOf(TokenFailure.class);

        List<String> warnings = logs.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(0)).contains("'g'", key.clientEmail(), "did not respond within 2 seconds", "PT1M");
        assertThat(warnings.get(1)).contains("'g'", key.clientEmail(), "invalid_grant").doesNotContain("PT1M");
        for (ILoggingEvent event : logs.list) {
            assertThat(event.getFormattedMessage()).doesNotContain("token-1").doesNotContain("eyJ");
        }
        assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("obtained an access token for " + key.clientEmail()));
        assertThat(cache.toString()).doesNotContain("token-");
    }
}
