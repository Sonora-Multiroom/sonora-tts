package multiroom.tts.provider.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.config.TtsProviderConfig;
import multiroom.tts.provider.DefaultSettingsResolution;
import multiroom.tts.provider.RequestedSettings;
import multiroom.tts.provider.SynthesisRequest;
import multiroom.tts.provider.SynthesisResult;
import multiroom.tts.provider.SynthesisSettings;
import multiroom.tts.provider.TtsProvider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code POST /v1/audio/speech}. Built at construction time; contacts nothing until
 * {@link #synthesize} is called.
 */
public class OpenAiTtsProvider implements TtsProvider {

    private static final String DEFAULT_MODEL = "tts-1";
    private static final URI DEFAULT_ENDPOINT = URI.create("https://api.openai.com/v1/audio/speech");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TtsProviderConfig config;
    private final HttpClient httpClient;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public OpenAiTtsProvider(TtsProviderConfig config) {
        this(config, DEFAULT_ENDPOINT);
    }

    /** Visible so tests can point this provider at a WireMock server instead of the real API. */
    public OpenAiTtsProvider(TtsProviderConfig config, URI endpoint) {
        this.config = config;
        this.timeout = Duration.ofSeconds(config.getTimeoutSeconds());
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.endpoint = endpoint;
        this.apiKey = config.getApiKey();
        this.model = config.getEngine() != null ? config.getEngine() : DEFAULT_MODEL;
    }

    @Override
    public SynthesisSettings resolveSettings(RequestedSettings requested) {
        return DefaultSettingsResolution.resolve(config, requested);
    }

    @Override
    public SynthesisResult synthesize(SynthesisRequest request) {
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(request)))
                .build();

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new TtsException(TtsErrorCode.PROVIDER_TIMEOUT,
                    "Provider 'openai' did not respond within " + timeout.getSeconds() + " seconds", e);
        } catch (IOException e) {
            throw new TtsException(TtsErrorCode.PROVIDER_ERROR, "Failed to call OpenAI TTS: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TtsException(TtsErrorCode.PROVIDER_ERROR, "Interrupted while calling OpenAI TTS", e);
        }

        if (response.statusCode() == 429) {
            throw new TtsException(TtsErrorCode.PROVIDER_RATE_LIMITED,
                    "Provider 'openai' reported rate limit exceeded (HTTP 429)");
        }
        if (response.statusCode() >= 400) {
            throw new TtsException(TtsErrorCode.PROVIDER_ERROR,
                    "Provider 'openai' returned HTTP " + response.statusCode());
        }

        return new SynthesisResult(response.body(), request.targetSampleRate(), request.targetChannels(), 16);
    }

    private String buildRequestBody(SynthesisRequest request) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", request.text());
        body.put("voice", request.voice());
        body.put("response_format", "wav");
        try {
            return MAPPER.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize OpenAI TTS request", e);
        }
    }
}
