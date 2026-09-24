package multiroom.tts.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.config.ProviderType;
import multiroom.tts.config.TtsProviderConfig;
import multiroom.tts.provider.cloud.OpenAiTtsProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiTtsProviderTest {

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

    private static byte[] fakeWav() {
        return new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'};
    }

    private OpenAiTtsProvider providerUnderTest(int timeoutSeconds) {
        TtsProviderConfig config = new TtsProviderConfig();
        config.setName("openai");
        config.setType(ProviderType.OPENAI);
        config.setApiKey("sk-test");
        config.setTimeoutSeconds(timeoutSeconds);
        return new OpenAiTtsProvider(config, URI.create(server.baseUrl() + "/v1/audio/speech"));
    }

    @Test
    void constructionMakesNoHttpCall() {
        providerUnderTest(2);

        server.verify(0, anyRequestedFor(urlPathEqualTo("/v1/audio/speech")));
    }

    @Test
    void successfulResponseReturnsTheWavBytes() {
        server.stubFor(post(urlEqualTo("/v1/audio/speech"))
                .willReturn(aResponse().withStatus(200).withBody(fakeWav())));

        SynthesisResult result = providerUnderTest(2)
                .synthesize(new SynthesisRequest("hello", "alloy", "en-US", 48000, 2));

        assertThat(result.audioData()).isEqualTo(fakeWav());
    }

    @Test
    void rateLimitRaisesProviderRateLimited() {
        server.stubFor(post(urlEqualTo("/v1/audio/speech")).willReturn(aResponse().withStatus(429)));

        assertThatThrownBy(() -> providerUnderTest(2)
                .synthesize(new SynthesisRequest("hello", "alloy", "en-US", 48000, 2)))
                .isInstanceOf(TtsException.class)
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.PROVIDER_RATE_LIMITED);
    }

    @Test
    void timeoutCancelsTheCall() {
        server.stubFor(post(urlEqualTo("/v1/audio/speech"))
                .willReturn(aResponse().withStatus(200).withBody(fakeWav()).withFixedDelay(3000)));

        assertThatThrownBy(() -> providerUnderTest(1)
                .synthesize(new SynthesisRequest("hello", "alloy", "en-US", 48000, 2)))
                .isInstanceOf(TtsException.class)
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.PROVIDER_TIMEOUT);
    }
}
