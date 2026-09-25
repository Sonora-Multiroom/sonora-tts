package multiroom.tts.provider.cloud.google;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One service-account entry's access token — the spec's <em>Access Token</em>. Nothing is fetched
 * at construction: the first need fetches a token, which is then reused until shortly before it
 * expires. Never shared between entries, even entries naming the same key file.
 *
 * <p>Built like {@link GoogleVoiceCatalogue}: a held token is read through a lock-free reference,
 * and fetches are single-flight, so N concurrent callers needing a new token make one request.
 * A token service that cannot serve now ({@link TokenFailure.Unavailable}) is held off for
 * {@code failure-backoff}; a rejection of the key never is, so the next call tries again.
 *
 * <p>A token still before its expiry keeps being used when an early renewal fails, and during a
 * back-off: the renewal margin exists to renew early, so a failed early renewal must not cost an
 * announcement the old token could still serve.
 */
public final class GoogleAccessTokenCache {

    private static final Logger log = LoggerFactory.getLogger(GoogleAccessTokenCache.class);

    /** Renew this long before expiry: a steady hour then costs one fetch plus one renewal. */
    private static final Duration RENEWAL_MARGIN = Duration.ofMinutes(5);

    /** Below this lifetime, the margin is half the lifetime instead. */
    private static final Duration SHORT_LIFETIME = Duration.ofMinutes(10);

    /** One issued token; {@code renewAt} before {@code expiresAt}. */
    private record AccessToken(String value, Instant renewAt, Instant expiresAt) {

        /** Never the token. */
        @Override
        public String toString() {
            return "AccessToken[renewAt=" + renewAt + ", expiresAt=" + expiresAt + "]";
        }
    }

    private final String providerName;
    private final ServiceAccountAssertion assertion;
    private final GoogleTokenExchange.Exchanger exchanger;
    private final String clientEmail;
    private final Duration failureBackoff;
    private final Clock clock;
    private final ReentrantLock fetchLock = new ReentrantLock();

    /** A reference rather than a plain volatile field, so {@link #discard} can compare and clear atomically. */
    private final AtomicReference<AccessToken> current = new AtomicReference<>();
    private volatile Instant failedAt;
    private volatile String failureReason;
    private volatile TtsErrorCode failureCode;

    public GoogleAccessTokenCache(String providerName, ServiceAccountAssertion assertion,
                                  GoogleTokenExchange.Exchanger exchanger, String clientEmail,
                                  Duration failureBackoff, Clock clock) {
        this.providerName = providerName;
        this.assertion = assertion;
        this.exchanger = exchanger;
        this.clientEmail = clientEmail;
        this.failureBackoff = failureBackoff;
        this.clock = clock;
    }

    /**
     * @param budget the longest this call may spend waiting for and fetching a token
     * @return a token to send as {@code Authorization: Bearer}
     * @throws TtsException when no token that is still valid can be had: the exchange's
     *                      {@link TokenFailure}, the back-off failure repeating its code, or
     *                      {@link TtsErrorCode#PROVIDER_TIMEOUT} when another request was still
     *                      fetching when the budget ran out
     */
    public String token(Duration budget) {
        AccessToken held = current.get();
        if (isFresh(held)) {
            return held.value();
        }
        if (inBackoff()) {
            return validOrBackoffFailure(held);
        }
        if (!lock(budget)) {
            // Not remembered: the lock holder's fetch is still running and records its own outcome.
            held = current.get();
            if (isValid(held)) {
                return held.value();
            }
            throw new TtsException(TtsErrorCode.PROVIDER_TIMEOUT, TokenFailure.message(providerName,
                    "another request was still fetching one after " + GoogleTokenExchange.describe(budget)));
        }
        try {
            held = current.get();
            if (isFresh(held)) {
                return held.value();
            }
            if (inBackoff()) {
                return validOrBackoffFailure(held);
            }
            return fetch(budget, held);
        } finally {
            fetchLock.unlock();
        }
    }

    /**
     * Forgets {@code rejected} after Google refused it, but only if it is still the held token, so
     * concurrent rejections of one token cause one renewal.
     */
    public void discard(String rejected) {
        current.getAndUpdate(held -> held != null && held.value().equals(rejected) ? null : held);
    }

    /** Must be called holding {@link #fetchLock}. */
    private String fetch(Duration budget, AccessToken held) {
        // Read before sending, so a slow response shortens the reuse window instead of extending it.
        Instant sentAt = clock.instant();
        try {
            GoogleTokenExchange.Token token = exchanger.exchange(assertion.sign(), budget);
            Duration lifetime = token.expiresIn();
            Duration margin = lifetime.compareTo(SHORT_LIFETIME) < 0 ? lifetime.dividedBy(2) : RENEWAL_MARGIN;
            current.set(new AccessToken(token.value(), sentAt.plus(lifetime).minus(margin), sentAt.plus(lifetime)));
            failedAt = null;
            failureReason = null;
            failureCode = null;
            log.debug("multiroom-tts: provider '{}' obtained an access token for {} (valid for {})",
                    providerName, clientEmail, lifetime);
            return token.value();
        } catch (TokenFailure.Unavailable e) {
            failureReason = e.reason();
            failureCode = e.getErrorCode();
            failedAt = clock.instant();
            log.warn("multiroom-tts: provider '{}' could not obtain an access token for {} ({}); the token service "
                    + "is not asked again for {}", providerName, clientEmail, e.reason(), failureBackoff);
            return validOr(held, e);
        } catch (TtsException e) {
            log.warn("multiroom-tts: provider '{}' could not obtain an access token for {} ({})",
                    providerName, clientEmail, e instanceof TokenFailure failure ? failure.reason() : e.getMessage());
            return validOr(held, e);
        }
    }

    private String validOr(AccessToken held, TtsException failure) {
        if (isValid(held) && current.get() == held) {
            return held.value();
        }
        throw failure;
    }

    private String validOrBackoffFailure(AccessToken held) {
        if (isValid(held)) {
            return held.value();
        }
        TtsErrorCode code = failureCode;
        throw new TtsException(code != null ? code : TtsErrorCode.PROVIDER_ERROR, TokenFailure.message(providerName,
                "the token service is in back-off after a recent failure: " + failureReason));
    }

    private boolean isFresh(AccessToken held) {
        return held != null && clock.instant().isBefore(held.renewAt());
    }

    private boolean isValid(AccessToken held) {
        return held != null && clock.instant().isBefore(held.expiresAt());
    }

    private boolean inBackoff() {
        Instant failed = failedAt;
        return failed != null && Duration.between(failed, clock.instant()).compareTo(failureBackoff) < 0;
    }

    private boolean lock(Duration budget) {
        try {
            return fetchLock.tryLock(Math.max(budget.toMillis(), 0), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Never the token. */
    @Override
    public String toString() {
        return "GoogleAccessTokenCache[provider=" + providerName + ", clientEmail=" + clientEmail + "]";
    }
}
