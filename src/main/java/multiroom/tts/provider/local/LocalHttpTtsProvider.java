package multiroom.tts.provider.local;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.LinkedHashMap;
import java.util.Map;

/** Proxies to a user-configured local TTS service (Ollama, FastAPI, a custom script, ...). */
public class LocalHttpTtsProvider implements TtsProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final URI endpoint;
    private final String requestTemplate;
    private final Duration timeout;

    public LocalHttpTtsProvider(TtsProviderConfig config) {
        this.timeout = Duration.ofSeconds(config.getTimeoutSeconds());
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.endpoint = URI.create(config.getEndpoint());
        this.requestTemplate = config.getRequestTemplate();
    }

    @Override
    public SynthesisResult synthesize(SynthesisRequest request) {
        String body = requestTemplate != null ? interpolate(requestTemplate, request) : defaultBody(request);
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new TtsException(TtsErrorCode.PROVIDER_TIMEOUT,
                    "Local HTTP provider did not respond within " + timeout.getSeconds() + " seconds", e);
        } catch (IOException e) {
            throw new TtsException(TtsErrorCode.PROVIDER_ERROR,
                    "Failed to call local HTTP provider: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TtsException(TtsErrorCode.PROVIDER_ERROR, "Interrupted while calling local HTTP provider", e);
        }

        if (response.statusCode() == 429) {
            throw new TtsException(TtsErrorCode.PROVIDER_RATE_LIMITED,
                    "Local HTTP provider reported rate limit exceeded (HTTP 429)");
        }
        if (response.statusCode() >= 400) {
            throw new TtsException(TtsErrorCode.PROVIDER_ERROR,
                    "Local HTTP provider returned HTTP " + response.statusCode());
        }

        return new SynthesisResult(response.body(), request.targetSampleRate(), request.targetChannels(), 16);
    }

    private String interpolate(String template, SynthesisRequest request) {
        return template
                .replace("{{text}}", escape(request.text()))
                .replace("{{voice}}", escape(request.voice()))
                .replace("{{language}}", escape(request.language()));
    }

    private String defaultBody(SynthesisRequest request) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("text", request.text());
        body.put("voice", request.voice());
        body.put("language", request.language());
        try {
            return MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize local HTTP TTS request", e);
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
