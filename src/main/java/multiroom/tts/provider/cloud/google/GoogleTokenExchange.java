package multiroom.tts.provider.cloud.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Sends one signed assertion to the token service and returns the access token, or a classified
 * {@link TokenFailure}. Messages are built only from the status, Google's explanation and the
 * HTTP client's own exception messages, never from the request or a success body, so neither the
 * assertion nor a token can reach one.
 */
public final class GoogleTokenExchange {

    private static final String JWT_BEARER_GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Exchanges an assertion for a token; a seam so the token cache can be tested without HTTP. */
    @FunctionalInterface
    public interface Exchanger {

        /**
         * @param timeout the longest the exchange may take
         * @throws TokenFailure for any failure, classified by whether it says anything about the key
         */
        Token exchange(String assertion, Duration timeout);
    }

    /** An issued access token and how long Google says it lives. */
    public record Token(String value, Duration expiresIn) {

        /** Never the token. */
        @Override
        public String toString() {
            return "Token[expiresIn=" + expiresIn + "]";
        }
    }

    private final String providerName;
    private final HttpClient httpClient;
    private final URI tokenUri;

    public GoogleTokenExchange(String providerName, HttpClient httpClient, URI tokenUri) {
        this.providerName = providerName;
        this.httpClient = httpClient;
        this.tokenUri = tokenUri;
    }

    /** Sends {@code assertion}; the {@link Exchanger} the token cache uses. */
    public Token exchange(String assertion, Duration timeout) {
        HttpRequest request = HttpRequest.newBuilder(tokenUri)
                .timeout(timeout.compareTo(Duration.ofMillis(1)) >= 0 ? timeout : Duration.ofMillis(1))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=" + urlEncode(JWT_BEARER_GRANT)
                        + "&assertion=" + urlEncode(assertion)))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new TokenFailure.Unavailable(TtsErrorCode.PROVIDER_TIMEOUT, providerName,
                    "the token service did not respond within " + describe(timeout), e);
        } catch (IOException e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new TokenFailure.Unavailable(TtsErrorCode.PROVIDER_ERROR, providerName, reason, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Says nothing about the token service, so it is not held off.
            throw new TtsException(TtsErrorCode.PROVIDER_ERROR,
                    TokenFailure.message(providerName, "interrupted while waiting for the token service"), e);
        }
        return classify(response);
    }

    private Token classify(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 429) {
            throw new TokenFailure.Unavailable(TtsErrorCode.PROVIDER_RATE_LIMITED, providerName,
                    "the token service returned HTTP 429" + explanation(response), null);
        }
        if (status >= 500) {
            throw new TokenFailure.Unavailable(TtsErrorCode.PROVIDER_ERROR, providerName,
                    "the token service returned HTTP " + status + explanation(response), null);
        }
        if (status >= 400) {
            throw new TokenFailure.Rejected(TtsErrorCode.PROVIDER_ERROR, providerName,
                    "Google rejected the service account key (HTTP " + status + ")" + explanation(response));
        }
        if (status >= 300) {
            throw new TokenFailure.Rejected(TtsErrorCode.PROVIDER_ERROR, providerName,
                    "the token service returned HTTP " + status);
        }
        return parse(response.body(), status);
    }

    private Token parse(String body, int status) {
        try {
            JsonNode json = MAPPER.readTree(body);
            JsonNode value = json.path("access_token");
            JsonNode expiresIn = json.path("expires_in");
            if (value.isTextual() && !value.asText().isBlank() && expiresIn.canConvertToLong() && expiresIn.asLong() > 0) {
                return new Token(value.asText(), Duration.ofSeconds(expiresIn.asLong()));
            }
        } catch (IOException e) {
            // Falls through: the body is not quoted, since it may hold a token.
        }
        throw new TokenFailure.Rejected(TtsErrorCode.PROVIDER_ERROR, providerName,
                "the token service returned an unusable response (HTTP " + status + ")");
    }

    /** {@code ": <Google's explanation>"}, or nothing when the body carries none. */
    private static String explanation(HttpResponse<String> response) {
        return GoogleErrorBody.message(response.body()).map(message -> ": " + message).orElse("");
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String describe(Duration duration) {
        long millis = duration.toMillis();
        return millis % 1000 == 0 ? (millis / 1000) + " seconds" : millis + " ms";
    }
}
