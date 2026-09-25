package multiroom.tts.provider.cloud;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.config.TtsProviderConfig;
import multiroom.tts.config.VoiceCatalogueProperties;
import multiroom.tts.provider.RequestedSettings;
import multiroom.tts.provider.SynthesisRequest;
import multiroom.tts.provider.SynthesisResult;
import multiroom.tts.provider.SynthesisSettings;
import multiroom.tts.provider.TtsProvider;
import multiroom.tts.provider.VoiceCatalogueProvider;
import multiroom.tts.provider.cloud.google.CatalogueVoice;
import multiroom.tts.provider.cloud.google.GoogleErrorBody;
import multiroom.tts.provider.cloud.google.GoogleVoiceCatalogue;
import multiroom.tts.provider.cloud.google.GoogleVoiceCatalogue.CheckResult;
import multiroom.tts.provider.cloud.google.GoogleVoiceName;
import multiroom.tts.provider.cloud.google.GoogleVoiceResolver;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Google Cloud Text-to-Speech REST {@code v1}, {@code AUDIO_ENCODING_LINEAR16}: the
 * response's base64-decoded {@code audioContent} is itself a WAV file, so the rest of this
 * module's WAV-in pipeline needs no special case for this provider.
 *
 * <p>This class only orchestrates: voice resolution is {@link GoogleVoiceResolver}'s, the voice
 * existence check is {@link GoogleVoiceCatalogue}'s, and reading Google's error explanation is
 * {@link GoogleErrorBody}'s. Messages name the configured entry, not the provider type, so a
 * caller can tell two {@code google-cloud} entries apart.
 */
public class GoogleCloudTtsProvider implements TtsProvider, VoiceCatalogueProvider {

    private static final URI DEFAULT_API_BASE = URI.create("https://texttospeech.googleapis.com/v1/");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Synthesis always gets at least this long, however much the catalogue fetch used. */
    private static final Duration MIN_SYNTHESIS_TIMEOUT = Duration.ofSeconds(1);

    private final String name;
    private final GoogleVoiceResolver resolver;
    private final GoogleVoiceCatalogue catalogue;
    private final HttpClient httpClient;
    private final URI synthesizeEndpoint;
    private final URI voicesEndpoint;
    private final String apiKey;
    private final Duration timeout;
    private final Duration fetchTimeout;
    private final Clock clock;

    public GoogleCloudTtsProvider(TtsProviderConfig config, VoiceCatalogueProperties catalogueProperties) {
        this(config, catalogueProperties, DEFAULT_API_BASE, Clock.systemUTC());
    }

    /**
     * Visible so tests can point this provider at a WireMock server instead of the real API, and
     * control time.
     *
     * @param apiBase the {@code v1/} base URI; {@code text:synthesize} and {@code voices} resolve
     *                against it
     */
    public GoogleCloudTtsProvider(TtsProviderConfig config, VoiceCatalogueProperties catalogueProperties,
                                  URI apiBase, Clock clock) {
        this.name = config.getName();
        this.resolver = new GoogleVoiceResolver(config);
        this.timeout = Duration.ofSeconds(config.getTimeoutSeconds());
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        // "./": a bare "text:synthesize" would parse as a URI with the scheme "text".
        this.synthesizeEndpoint = apiBase.resolve("./text:synthesize");
        this.voicesEndpoint = apiBase.resolve("voices");
        this.apiKey = config.getApiKey();
        this.fetchTimeout = catalogueProperties.getFetchTimeout();
        this.clock = clock;
        // One catalogue per entry, never shared; constructing it fetches nothing.
        this.catalogue = new GoogleVoiceCatalogue(name, catalogueProperties, clock, this::fetchCatalogue);
    }

    @Override
    public SynthesisSettings resolveSettings(RequestedSettings requested) {
        return resolver.resolve(requested);
    }

    /**
     * Listing has no synthesis to leave time for, but it still gets only {@code fetch-timeout}:
     * a caller browsing voices should get a fast 503, not wait out the entry's whole timeout.
     */
    @Override
    public List<CatalogueVoice> listVoices(String language, String engine) {
        return catalogue.list(language, engine, fetchTimeout);
    }

    /**
     * Checks the voice against the catalogue, then synthesizes. Both share the entry's
     * {@code timeout-seconds}: the catalogue gets at most {@code fetch-timeout} of it, so a hung
     * catalogue still leaves time to synthesize.
     *
     * @throws TtsException {@link TtsErrorCode#INVALID_VOICE} for a voice the loaded catalogue
     *                      lacks, before any synthesis call
     */
    @Override
    public SynthesisResult synthesize(SynthesisRequest request) {
        Instant deadline = clock.instant().plus(timeout);
        String voice = request.voice() == null ? null : checkVoice(request.settings(), remaining(deadline));
        HttpRequest httpRequest = buildRequest(request, voice, max(remaining(deadline), MIN_SYNTHESIS_TIMEOUT));

        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
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

        return new SynthesisResult(decodeAudioContent(response.body()), request.targetSampleRate(), 1, 16);
    }

    /**
     * @return the voice to send: the catalogue's spelling when the catalogue answers, otherwise
     *         the rule spelling — an unreachable catalogue never fails an announcement
     */
    private String checkVoice(SynthesisSettings settings, Duration remaining) {
        CheckResult result = catalogue.check(settings.voice(), min(fetchTimeout, remaining));
        if (result instanceof CheckResult.Found found) {
            return found.voice().fullName();
        }
        if (result instanceof CheckResult.Missing missing) {
            throw new TtsException(TtsErrorCode.INVALID_VOICE, invalidVoiceMessage(settings, missing));
        }
        return settings.voice();
    }

    /** Quotes the caller's own spelling, not the resolved name, so a typo is recognizable. */
    private static String invalidVoiceMessage(SynthesisSettings settings, CheckResult.Missing missing) {
        GoogleVoiceName.Full resolved = (GoogleVoiceName.Full) GoogleVoiceName.parse(settings.voice());
        String engine = resolved.engineSpelling();
        String language = resolved.language().tag();
        if (missing.alternatives().isEmpty()) {
            return "No " + engine + " voices are available for " + language;
        }
        return "Voice '" + settings.requestedVoice() + "' is not available for " + engine + " / " + language
                + ". Available: " + missing.alternatives().stream()
                        .map(CatalogueVoice::shortName)
                        .collect(Collectors.joining(", "));
    }

    /** The catalogue's fetcher: {@code GET v1/voices} with no language, so one call lists every voice. */
    private String fetchCatalogue(Duration fetchBudget) throws IOException {
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(voicesEndpoint + "?key=" + apiKey))
                .timeout(max(fetchBudget, Duration.ofMillis(1)))
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + explanation(response));
        }
        return response.body();
    }

    /** {@code ": <Google's message>"}, or nothing when the body carries none. */
    private static String explanation(HttpResponse<String> response) {
        return GoogleErrorBody.message(response.body()).map(message -> ": " + message).orElse("");
    }

    private HttpRequest buildRequest(SynthesisRequest request, String voiceName, Duration requestTimeout) {
        Map<String, Object> input = Map.of("text", request.text());
        Map<String, Object> voice = new LinkedHashMap<>();
        voice.put("languageCode", request.language());
        if (voiceName != null) {
            voice.put("name", voiceName);
        }
        Map<String, Object> audioConfig = new LinkedHashMap<>();
        audioConfig.put("audioEncoding", "LINEAR16");
        audioConfig.put("sampleRateHertz", request.targetSampleRate());
        // Sent whenever set, and omitted otherwise, so Google's own default applies.
        if (request.settings().pitch() != null) {
            audioConfig.put("pitch", request.settings().pitch());
        }
        if (request.settings().speakingRate() != null) {
            audioConfig.put("speakingRate", request.settings().speakingRate());
        }
        Map<String, Object> body = Map.of("input", input, "voice", voice, "audioConfig", audioConfig);

        try {
            return HttpRequest.newBuilder(URI.create(synthesizeEndpoint + "?key=" + apiKey))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Google Cloud TTS request", e);
        }
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

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
