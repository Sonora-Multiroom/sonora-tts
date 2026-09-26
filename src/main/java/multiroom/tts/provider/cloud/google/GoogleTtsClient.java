package multiroom.tts.provider.cloud.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Function;

/**
 * The Google plumbing {@code google-cloud} and {@code google-gemini} both need (004): one
 * authorised send with 003's single retry after a 401, Google's error mapping with its own
 * explanation, decoding {@code audioContent}, and fetching the published voice list. Extracted out of {@code GoogleCloudTtsProvider}
 * so a fix reaches both types; {@code GoogleCloudTtsProvider} keeps what only it needs (the voice
 * resolver, the catalogue and the catalogue check).
 *
 * <p>Building this contacts nothing: the {@link GoogleCredential} it holds fetches a token only
 * on first need.
 */
public final class GoogleTtsClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Synthesis always gets at least this long, however much a catalogue fetch used. */
    private static final Duration MIN_SYNTHESIS_TIMEOUT = Duration.ofSeconds(1);

    private final String name;
    private final HttpClient httpClient;
    private final URI synthesizeEndpoint;
    private final URI voicesEndpoint;
    private final GoogleCredential credential;
    private final Duration timeout;
    private final Clock clock;

    /**
     * @param serviceAccountKey the entry's loaded key file, or {@code null} to authenticate with
     *                          {@code apiKey}
     * @param apiKey            the entry's API key, or {@code null} when a service account is used
     * @param failureBackoff    how long a service account's token service is held off after a
     *                          failure that says nothing about the key
     */
    public GoogleTtsClient(String name, Duration timeout, URI apiBase, ServiceAccountKey serviceAccountKey,
                           String apiKey, Duration failureBackoff, Clock clock) {
        this.name = name;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        // "./": a bare "text:synthesize" would parse as a URI with the scheme "text".
        this.synthesizeEndpoint = apiBase.resolve("./text:synthesize");
        this.voicesEndpoint = apiBase.resolve("voices");
        // One token cache per entry, never shared; constructing it contacts nothing.
        this.credential = serviceAccountKey == null
                ? new GoogleCredential.ApiKey(apiKey)
                : new GoogleCredential.ServiceAccount(new GoogleAccessTokenCache(name,
                        new ServiceAccountAssertion(serviceAccountKey, ServiceAccountAssertion.CLOUD_PLATFORM_SCOPE, clock),
                        new GoogleTokenExchange(name, httpClient, serviceAccountKey.tokenUri())::exchange,
                        serviceAccountKey.clientEmail(), failureBackoff, clock));
        this.clock = clock;
    }

    /**
     * Obtains what {@link #synthesize} or {@link #get} will send, within {@code budget}.
     *
     * @throws TtsException when no token can be had (a service-account credential only)
     */
    public Optional<String> prepare(Duration budget) {
        return credential.prepare(budget);
    }

    /**
     * {@code POST text:synthesize}. On an HTTP 401 a service-account credential discards its
     * token, renews it within what remains of {@code deadline} and resends once; never on 403,
     * and never twice.
     *
     * @return the base64-decoded {@code audioContent}
     * @throws TtsException {@link TtsErrorCode#PROVIDER_TIMEOUT}, {@link TtsErrorCode#PROVIDER_RATE_LIMITED}
     *                      or {@link TtsErrorCode#PROVIDER_ERROR}, each naming this entry and
     *                      carrying Google's explanation when there is one
     */
    public byte[] synthesize(String jsonBody, Instant deadline) {
        Optional<String> token = prepare(remaining(deadline));
        HttpResponse<String> response;
        try {
            response = send(t -> credential.authorize(synthesizeEndpoint, t, max(remaining(deadline), MIN_SYNTHESIS_TIMEOUT))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build(), token, deadline);
        } catch (HttpTimeoutException e) {
            throw new TtsException(TtsErrorCode.PROVIDER_TIMEOUT,
                    "Provider '" + name + "' did not respond within " + timeout.getSeconds() + " seconds", e);
        } catch (IOException e) {
            throw new TtsException(TtsErrorCode.PROVIDER_ERROR,
                    "Failed to call Google Cloud TTS: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TtsException(TtsErrorCode.PROVIDER_ERROR, "Interrupted while calling Google Cloud TTS", e);
        }

        if (response.statusCode() == 429) {
            throw new TtsException(TtsErrorCode.PROVIDER_RATE_LIMITED,
                    "Provider '" + name + "' reported rate limit exceeded (HTTP 429)" + explanation(response));
        }
        if (response.statusCode() >= 400) {
            throw new TtsException(TtsErrorCode.PROVIDER_ERROR,
                    "Provider '" + name + "' returned HTTP " + response.statusCode() + explanation(response));
        }
        return decodeAudioContent(response.body());
    }

    /**
     * A GET against {@code endpoint} (the voice catalogue), with the same authorisation and 401
     * retry as {@link #synthesize}. The caller interprets the status: the catalogue fetcher's
     * back-off applies to its own failures, not to this client's.
     */
    public HttpResponse<String> get(URI endpoint, Instant deadline) throws IOException, InterruptedException {
        Optional<String> token = prepare(remaining(deadline));
        return send(t -> credential.authorize(endpoint, t, max(remaining(deadline), Duration.ofMillis(1)))
                .GET()
                .build(), token, deadline);
    }

    /**
     * A {@link GoogleVoiceCatalogue.CatalogueFetcher} for either Google type: {@code GET v1/voices}
     * with no language, so one call lists every voice. It obtains its own token: on the listing
     * path this is the entry's first token request, and a failure to get one fails the fetch,
     * which starts the catalogue's back-off.
     *
     * @throws IOException on any failure, its message being the reason reported to callers:
     *                     {@code "HTTP <status>: <Google's explanation>"} for a refusal
     */
    public String fetchVoices(Duration budget) throws IOException {
        Instant deadline = clock.instant().plus(budget);
        HttpResponse<String> response;
        try {
            response = get(voicesEndpoint, deadline);
        } catch (TtsException e) {
            throw new IOException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + explanation(response));
        }
        return response.body();
    }

    /**
     * Sends the request {@code build} makes for a token, and on an HTTP 401 that the credential
     * can answer, renews the token within what is left of {@code deadline} and resends once.
     */
    private HttpResponse<String> send(Function<String, HttpRequest> build, Optional<String> token, Instant deadline)
            throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(build.apply(token.orElse(null)),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 && credential.retriesUnauthorized()) {
            credential.discard(token.orElse(null));
            Optional<String> renewed = credential.prepare(remaining(deadline));
            response = httpClient.send(build.apply(renewed.orElse(null)), HttpResponse.BodyHandlers.ofString());
        }
        return response;
    }

    /** {@code ": <Google's message>"}, or nothing when the body carries none. */
    private static String explanation(HttpResponse<String> response) {
        return GoogleErrorBody.message(response.body()).map(message -> ": " + message).orElse("");
    }

    private byte[] decodeAudioContent(String responseBody) {
        try {
            JsonNode audioContent = MAPPER.readTree(responseBody).path("audioContent");
            if (!audioContent.isTextual()) {
                throw new TtsException(TtsErrorCode.PROVIDER_ERROR,
                        "Provider '" + name + "' returned a response with no audioContent");
            }
            return Base64.getDecoder().decode(audioContent.asText());
        } catch (IOException | IllegalArgumentException e) {
            throw new TtsException(TtsErrorCode.PROVIDER_ERROR,
                    "Provider '" + name + "' returned an unparseable response", e);
        }
    }

    private Duration remaining(Instant deadline) {
        Duration remaining = Duration.between(clock.instant(), deadline);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
