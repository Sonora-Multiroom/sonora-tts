package multiroom.tts.provider.cloud;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.config.TtsProviderConfig;
import multiroom.tts.provider.SynthesisRequest;
import multiroom.tts.provider.SynthesisResult;
import multiroom.tts.provider.TtsProvider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Google Cloud Text-to-Speech REST, {@code AUDIO_ENCODING_LINEAR16}: the response's
 * base64-decoded {@code audioContent} is itself a WAV file, so the rest of this module's WAV-in
 * pipeline needs no special case for this provider.
 */
public class GoogleCloudTtsProvider implements TtsProvider {

    private static final URI DEFAULT_ENDPOINT =
            URI.create("https://texttospeech.googleapis.com/v1/text:synthesize");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;

    public GoogleCloudTtsProvider(TtsProviderConfig config) {
        this(config, DEFAULT_ENDPOINT);
    }

    /** Visible so tests can point this provider at a WireMock server instead of the real API. */
    public GoogleCloudTtsProvider(TtsProviderConfig config, URI endpoint) {
        this.timeout = Duration.ofSeconds(config.getTimeoutSeconds());
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.endpoint = endpoint;
        this.apiKey = config.getApiKey();
    }

    @Override
    public SynthesisResult synthesize(SynthesisRequest request) {
        HttpRequest httpRequest = buildRequest(request);

        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new TtsException(TtsErrorCode.PROVIDER_TIMEOUT,
                    "Provider 'google-cloud' did not respond within " + timeout.getSeconds() + " seconds", e);
        } catch (IOException e) {
            throw new TtsException(TtsErrorCode.PROVIDER_ERROR,
                    "Failed to call Google Cloud TTS: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TtsException(TtsErrorCode.PROVIDER_ERROR, "Interrupted while calling Google Cloud TTS", e);
        }

        if (response.statusCode() == 429) {
            throw new TtsException(TtsErrorCode.PROVIDER_RATE_LIMITED,
                    "Provider 'google-cloud' reported rate limit exceeded (HTTP 429)");
        }
        if (response.statusCode() >= 400) {
            throw new TtsException(TtsErrorCode.PROVIDER_ERROR,
                    "Provider 'google-cloud' returned HTTP " + response.statusCode());
        }

        return new SynthesisResult(decodeAudioContent(response.body()), request.targetSampleRate(), 1, 16);
    }

    private HttpRequest buildRequest(SynthesisRequest request) {
        Map<String, Object> input = Map.of("text", request.text());
        Map<String, Object> voice = new LinkedHashMap<>();
        voice.put("languageCode", request.language());
        if (request.voice() != null) {
            voice.put("name", request.voice());
        }
        Map<String, Object> audioConfig = Map.of(
                "audioEncoding", "LINEAR16",
                "sampleRateHertz", request.targetSampleRate());
        Map<String, Object> body = Map.of("input", input, "voice", voice, "audioConfig", audioConfig);

        try {
            return HttpRequest.newBuilder(URI.create(endpoint + "?key=" + apiKey))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Google Cloud TTS request", e);
        }
    }

    private byte[] decodeAudioContent(String responseBody) {
        try {
            JsonNode node = MAPPER.readTree(responseBody);
            return Base64.getDecoder().decode(node.get("audioContent").asText());
        } catch (IOException | NullPointerException e) {
            throw new TtsException(TtsErrorCode.PROVIDER_ERROR,
                    "Provider 'google-cloud' returned an unparseable response", e);
        }
    }
}
