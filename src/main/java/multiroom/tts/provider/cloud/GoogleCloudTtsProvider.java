package multiroom.tts.provider.cloud;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import multiroom.tts.provider.cloud.google.GoogleTtsClient;
import multiroom.tts.provider.cloud.google.GoogleVoiceCatalogue;
import multiroom.tts.provider.cloud.google.GoogleVoiceCatalogue.CheckResult;
import multiroom.tts.provider.cloud.google.GoogleVoiceName;
import multiroom.tts.provider.cloud.google.GoogleVoiceResolver;
import multiroom.tts.provider.cloud.google.ServiceAccountKey;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * existence check is {@link GoogleVoiceCatalogue}'s, sending and error mapping are
 * {@link GoogleTtsClient}'s. Messages name the configured entry, not the provider type, so a
 * caller can tell two {@code google-cloud} entries apart.
 */
public class GoogleCloudTtsProvider implements TtsProvider, VoiceCatalogueProvider {

    private static final URI DEFAULT_API_BASE = URI.create("https://texttospeech.googleapis.com/v1/");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private final GoogleVoiceResolver resolver;
    private final GoogleVoiceCatalogue catalogue;
    private final GoogleTtsClient client;
    private final Duration timeout;
    private final Duration fetchTimeout;
    private final Clock clock;

    /**
     * @param serviceAccountKey the entry's loaded key file, or {@code null} to authenticate with
     *                          its {@code api-key}
     */
    public GoogleCloudTtsProvider(TtsProviderConfig config, VoiceCatalogueProperties catalogueProperties,
                                  ServiceAccountKey serviceAccountKey) {
        this(config, catalogueProperties, serviceAccountKey, DEFAULT_API_BASE, Clock.systemUTC());
    }

    /**
     * Visible so tests can point this provider at a WireMock server instead of the real API, and
     * control time.
     *
     * @param apiBase the {@code v1/} base URI; {@code text:synthesize} and {@code voices} resolve
     *                against it
     */
    public GoogleCloudTtsProvider(TtsProviderConfig config, VoiceCatalogueProperties catalogueProperties,
                                  ServiceAccountKey serviceAccountKey, URI apiBase, Clock clock) {
        this.name = config.getName();
        this.resolver = new GoogleVoiceResolver(config);
        this.timeout = Duration.ofSeconds(config.getTimeoutSeconds());
        this.client = new GoogleTtsClient(name, timeout, apiBase, serviceAccountKey, config.getApiKey(),
                catalogueProperties.getFailureBackoff(), clock);
        this.fetchTimeout = catalogueProperties.getFetchTimeout();
        this.clock = clock;
        // One catalogue per entry, never shared; constructing it fetches nothing.
        this.catalogue = new GoogleVoiceCatalogue(name, catalogueProperties, clock, client::fetchVoices);
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
     * Obtains the credential, checks the voice against the catalogue, then synthesizes. All three
     * share the entry's {@code timeout-seconds}: the catalogue gets at most {@code fetch-timeout}
     * of it, so a hung catalogue still leaves time to synthesize.
     *
     * <p>A service account's token comes first, so a token failure is reported as itself and asks
     * Google for a token once, instead of surfacing through the catalogue's shorter budget and
     * back-off.
     *
     * @throws TtsException {@link TtsErrorCode#INVALID_VOICE} for a voice the loaded catalogue
     *                      lacks, before any synthesis call; the token failure, unchanged, when no
     *                      token can be had
     */
    @Override
    public SynthesisResult synthesize(SynthesisRequest request) {
        Instant deadline = clock.instant().plus(timeout);
        client.prepare(remaining(deadline));
        String voice = request.voice() == null ? null : checkVoice(request.settings(), remaining(deadline));
        String body = requestBody(request, voice);

        byte[] audio = client.synthesize(body, deadline);
        return new SynthesisResult(audio, request.targetSampleRate(), 1, 16);
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

    private static String requestBody(SynthesisRequest request, String voiceName) {
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
            return MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Google Cloud TTS request", e);
        }
    }

    private Duration remaining(Instant deadline) {
        Duration remaining = Duration.between(clock.instant(), deadline);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
