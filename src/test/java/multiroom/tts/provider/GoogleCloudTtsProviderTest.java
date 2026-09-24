package multiroom.tts.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.config.ProviderType;
import multiroom.tts.config.TtsProviderConfig;
import multiroom.tts.provider.cloud.GoogleCloudTtsProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleCloudTtsProviderTest {

    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private GoogleCloudTtsProvider providerUnderTest(int timeoutSeconds) {
        TtsProviderConfig config = new TtsProviderConfig();
        config.setName("google-cloud");
        config.setType(ProviderType.GOOGLE_CLOUD);
        config.setApiKey("test-key");
        config.setTimeoutSeconds(timeoutSeconds);
        return new GoogleCloudTtsProvider(config, URI.create(server.baseUrl() + "/v1/text:synthesize"));
    }

    @Test
    void constructionMakesNoHttpCall() {
        providerUnderTest(2);

        server.verify(0, anyRequestedFor(urlPathEqualTo("/v1/text:synthesize")));
    }

    @Test
    void linear16ResponseIsBase64DecodedIntoAudioData() {
        byte[] wavBytes = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'};
        String responseJson = "{\"audioContent\":\"" + Base64.getEncoder().encodeToString(wavBytes) + "\"}";
        server.stubFor(post(urlPathEqualTo("/v1/text:synthesize"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseJson)));

        SynthesisResult result = providerUnderTest(2)
                .synthesize(new SynthesisRequest("hello", "en-US-Neural2-C", "en-US", 24000, 1));

        assertThat(result.audioData()).isEqualTo(wavBytes);
    }

    @Test
    void rateLimitRaisesProviderRateLimited() {
        server.stubFor(post(urlPathEqualTo("/v1/text:synthesize")).willReturn(aResponse().withStatus(429)));

        assertThatThrownBy(() -> providerUnderTest(2)
                .synthesize(new SynthesisRequest("hello", "en-US-Neural2-C", "en-US", 24000, 1)))
                .isInstanceOf(TtsException.class)
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.PROVIDER_RATE_LIMITED);
    }

    @Test
    void timeoutCancelsTheCall() {
        server.stubFor(post(urlPathEqualTo("/v1/text:synthesize"))
                .willReturn(aResponse().withStatus(200).withBody("{}").withFixedDelay(3000)));

        assertThatThrownBy(() -> providerUnderTest(1)
                .synthesize(new SynthesisRequest("hello", "en-US-Neural2-C", "en-US", 24000, 1)))
                .isInstanceOf(TtsException.class)
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.PROVIDER_TIMEOUT);
    }
}
