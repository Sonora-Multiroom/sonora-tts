package multiroom.tts.provider.cloud.google;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Optional;

/**
 * How a {@code google-cloud} entry proves its identity to Google — the spec's <em>Credential</em>:
 * an API key or a service account, exactly one per entry. The provider holds one and applies it
 * to every request it sends, so an API-key entry's requests are exactly what they were before 003.
 */
public sealed interface GoogleCredential {

    /**
     * Obtains what {@link #authorize} needs, within {@code budget}.
     *
     * @return the access token to send; empty when the credential needs none
     */
    Optional<String> prepare(Duration budget);

    /**
     * @param token   what {@link #prepare} returned, or {@code null} when it returned nothing
     * @param timeout the request's timeout
     * @return a request builder for {@code endpoint} carrying this credential
     */
    HttpRequest.Builder authorize(URI endpoint, String token, Duration timeout);

    /** Forgets {@code token} after Google refused it; nothing for a credential that holds none. */
    void discard(String token);

    /** Whether an HTTP 401 is answered by renewing the credential and resending once. */
    boolean retriesUnauthorized();

    /** Appends {@code ?key=<key>}; nothing to renew, so a 401 is final. */
    record ApiKey(String key) implements GoogleCredential {

        @Override
        public Optional<String> prepare(Duration budget) {
            return Optional.empty();
        }

        @Override
        public HttpRequest.Builder authorize(URI endpoint, String token, Duration timeout) {
            return HttpRequest.newBuilder(URI.create(endpoint + "?key=" + key)).timeout(timeout);
        }

        @Override
        public void discard(String token) {
        }

        @Override
        public boolean retriesUnauthorized() {
            return false;
        }

        /** Never the key. */
        @Override
        public String toString() {
            return "ApiKey[]";
        }
    }

    /** Sends {@code Authorization: Bearer <token>} from the entry's own token cache. */
    record ServiceAccount(GoogleAccessTokenCache tokens) implements GoogleCredential {

        @Override
        public Optional<String> prepare(Duration budget) {
            return Optional.of(tokens.token(budget));
        }

        @Override
        public HttpRequest.Builder authorize(URI endpoint, String token, Duration timeout) {
            return HttpRequest.newBuilder(endpoint).timeout(timeout).header("Authorization", "Bearer " + token);
        }

        @Override
        public void discard(String token) {
            tokens.discard(token);
        }

        /** Google answers an expired or revoked token with 401, so one renewal may fix it. */
        @Override
        public boolean retriesUnauthorized() {
            return true;
        }
    }
}
