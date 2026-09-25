package multiroom.tts.provider.cloud.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.config.VoiceCatalogueProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One {@code google-cloud} entry's in-memory copy of {@code GET v1/voices} — the spec's
 * <em>Voice Catalogue</em>. Never fetched at construction:
 * the first need fetches it, and it is then trusted for {@code ttl}.
 *
 * <p>A failed fetch is remembered for {@code failure-backoff}, during which nothing is fetched and
 * the answer is {@link CheckResult.Unavailable}, so an outage costs one slow request per period.
 * A failed refresh does not discard an older copy that is still within its {@code ttl}.
 *
 * <p>Warm lookups read an immutable snapshot through a {@code volatile} field and never lock.
 * Fetches are single-flight: the lock holder re-checks the state, so N concurrent cold requests
 * make one call.
 */
public final class GoogleVoiceCatalogue {

    private static final Logger log = LoggerFactory.getLogger(GoogleVoiceCatalogue.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Sorts by language, then engine, then short name, as the listing contract publishes. */
    private static final Comparator<CatalogueVoice> ORDER = Comparator
            .comparing(CatalogueVoice::language, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(CatalogueVoice::engine, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(CatalogueVoice::shortName, String.CASE_INSENSITIVE_ORDER);

    /** Fetches the raw {@code v1/voices} JSON. */
    @FunctionalInterface
    public interface CatalogueFetcher {

        /**
         * @param timeout the longest the fetch may take
         * @throws IOException on a timeout, an I/O failure or a non-2xx response; its message is
         *                     the reason reported to callers, so it must never contain the key
         */
        String fetch(Duration timeout) throws IOException;
    }

    /** The answer to {@link #check}. */
    public sealed interface CheckResult {

        /** The voice exists; {@code voice().fullName()} is the catalogue's spelling. */
        record Found(CatalogueVoice voice) implements CheckResult {
        }

        /** The voice does not exist; these are the voices of the same language and engine. */
        record Missing(List<CatalogueVoice> alternatives) implements CheckResult {
        }

        /** The catalogue cannot be consulted now; the check is skipped. */
        record Unavailable(String reason) implements CheckResult {
        }
    }

    /** One successful fetch. */
    private record Snapshot(Instant fetchedAt, Map<String, CatalogueVoice> byFoldedName) {
    }

    private final String providerName;
    private final Duration ttl;
    private final Duration failureBackoff;
    private final Clock clock;
    private final CatalogueFetcher fetcher;
    private final ReentrantLock fetchLock = new ReentrantLock();

    private volatile Snapshot snapshot;
    private volatile Instant failedAt;
    private volatile String failureReason;

    public GoogleVoiceCatalogue(String providerName, VoiceCatalogueProperties properties, Clock clock,
                                CatalogueFetcher fetcher) {
        this.providerName = providerName;
        this.ttl = properties.getTtl();
        this.failureBackoff = properties.getFailureBackoff();
        this.clock = clock;
        this.fetcher = fetcher;
    }

    /**
     * Checks that a full voice name exists, ignoring case. A voice missing from the copy triggers
     * one refetch, but only if the copy is older than the failure back-off, so repeated typos
     * cannot force a download each time.
     *
     * @param fullName the resolved full voice name
     * @param budget   the longest this call may spend fetching
     */
    public CheckResult check(String fullName, Duration budget) {
        Snapshot current = usableSnapshot(budget);
        if (current == null) {
            return new CheckResult.Unavailable(unavailableReason());
        }
        String folded = fold(fullName);
        CatalogueVoice voice = current.byFoldedName().get(folded);
        if (voice == null && age(current).compareTo(failureBackoff) >= 0) {
            Snapshot refreshed = refetch(current, budget);
            if (refreshed != null) {
                current = refreshed;
                voice = current.byFoldedName().get(folded);
            }
        }
        return voice != null ? new CheckResult.Found(voice) : new CheckResult.Missing(alternatives(current, fullName));
    }

    /**
     * Lists the voices matching the optional filters, sorted by language, engine and short name.
     * The engine matches a recognized engine by any alias, or an unrecognized segment ignoring
     * case.
     *
     * @throws TtsException {@link TtsErrorCode#VOICE_CATALOGUE_UNAVAILABLE} when the catalogue
     *                      cannot be fetched now, or a recent failure is in back-off
     */
    public List<CatalogueVoice> list(String language, String engine, Duration budget) {
        Snapshot current = usableSnapshot(budget);
        if (current == null) {
            throw new TtsException(TtsErrorCode.VOICE_CATALOGUE_UNAVAILABLE,
                    "The voice catalogue of provider '" + providerName + "' is unavailable: " + unavailableReason());
        }
        GoogleLanguage languageFilter = language == null ? null : GoogleLanguage.parse(language);
        String engineFilter = engine == null ? null
                : GoogleEngine.fromName(engine).map(GoogleEngine::canonical).orElse(engine);
        return current.byFoldedName().values().stream()
                .filter(voice -> languageFilter == null || languageFilter.tag().equals(voice.language()))
                .filter(voice -> engineFilter == null || engineFilter.equalsIgnoreCase(voice.engine()))
                .sorted(ORDER)
                .toList();
    }

    /** The copy to answer from, fetching first if there is none within its {@code ttl}. */
    private Snapshot usableSnapshot(Duration budget) {
        Snapshot current = snapshot;
        if (isFresh(current)) {
            return current;
        }
        if (inBackoff()) {
            return null;
        }
        if (!lock(budget)) {
            return null;
        }
        try {
            current = snapshot;
            if (isFresh(current)) {
                return current;
            }
            if (inBackoff()) {
                return null;
            }
            return fetch(budget) ? snapshot : null;
        } finally {
            fetchLock.unlock();
        }
    }

    /**
     * Refetches a fresh copy that lacks a voice. A failure keeps the old copy, which is still
     * within its {@code ttl}.
     *
     * @return the copy to answer from; {@code null} to keep {@code stale}
     */
    private Snapshot refetch(Snapshot stale, Duration budget) {
        if (inBackoff() || !lock(budget)) {
            return null;
        }
        try {
            if (snapshot != stale) {
                // Another request refetched while this one waited for the lock.
                return snapshot;
            }
            if (inBackoff()) {
                return null;
            }
            return fetch(budget) ? snapshot : null;
        } finally {
            fetchLock.unlock();
        }
    }

    /** Must be called holding {@link #fetchLock}. */
    private boolean fetch(Duration budget) {
        log.debug("multiroom-tts: fetching the voice catalogue of provider '{}'", providerName);
        try {
            snapshot = parse(fetcher.fetch(budget), clock.instant());
            failedAt = null;
            failureReason = null;
            return true;
        } catch (IOException | RuntimeException e) {
            failureReason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            failedAt = clock.instant();
            log.warn("multiroom-tts: the voice catalogue of provider '{}' could not be fetched ({}); voices are "
                    + "not checked for the next {}", providerName, failureReason, failureBackoff);
            return false;
        }
    }

    private static Snapshot parse(String json, Instant fetchedAt) throws IOException {
        JsonNode voices = MAPPER.readTree(json).path("voices");
        if (!voices.isArray()) {
            throw new IOException("the response has no voices array");
        }
        Map<String, CatalogueVoice> byFoldedName = new LinkedHashMap<>();
        for (JsonNode voice : voices) {
            CatalogueVoice.fromName(voice.path("name").asText(null))
                    .ifPresent(parsed -> byFoldedName.put(fold(parsed.fullName()), parsed));
        }
        return new Snapshot(fetchedAt, Map.copyOf(byFoldedName));
    }

    /** The voices sharing {@code fullName}'s language and engine segment. */
    private static List<CatalogueVoice> alternatives(Snapshot current, String fullName) {
        if (!GoogleVoiceName.isWellFormed(fullName)
                || !(GoogleVoiceName.parse(fullName) instanceof GoogleVoiceName.Full full)) {
            return List.of();
        }
        String language = full.language().tag();
        String engine = full.engineSpelling();
        return current.byFoldedName().values().stream()
                .filter(voice -> voice.language().equals(language) && voice.engine().equalsIgnoreCase(engine))
                .sorted(ORDER)
                .toList();
    }

    private boolean lock(Duration budget) {
        try {
            return fetchLock.tryLock(Math.max(budget.toMillis(), 0), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean isFresh(Snapshot current) {
        return current != null && age(current).compareTo(ttl) < 0;
    }

    private boolean inBackoff() {
        Instant failed = failedAt;
        return failed != null && Duration.between(failed, clock.instant()).compareTo(failureBackoff) < 0;
    }

    private Duration age(Snapshot current) {
        return Duration.between(current.fetchedAt(), clock.instant());
    }

    private String unavailableReason() {
        String reason = failureReason;
        return reason != null ? reason : "no catalogue could be fetched in time";
    }

    private static String fold(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
