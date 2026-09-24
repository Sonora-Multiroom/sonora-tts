package multiroom.tts.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.config.ProviderType;
import multiroom.tts.config.TtsProviderConfig;
import multiroom.tts.provider.local.LocalHttpTtsProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalHttpTtsProviderTest {

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

    private TtsProviderConfig config(int timeoutSeconds, String requestTemplate) {
        TtsProviderConfig config = new TtsProviderConfig();
        config.setName("local");
        config.setType(ProviderType.LOCAL_HTTP);
        config.setEndpoint(server.baseUrl() + "/synthesize");
        config.setTimeoutSeconds(timeoutSeconds);
        config.setRequestTemplate(requestTemplate);
        return config;
    }

    @Test
    void postsToTheConfiguredEndpointWithDefaultBody() {
        byte[] wav = {'R', 'I', 'F', 'F'};
        server.stubFor(post(urlPathEqualTo("/synthesize")).willReturn(aResponse().withStatus(200).withBody(wav)));

        SynthesisResult result = new LocalHttpTtsProvider(config(2, null))
                .synthesize(new SynthesisRequest("hello", "voice-a", "en-US", 48000, 2));

        assertThat(result.audioData()).isEqualTo(wav);
        server.verify(postRequestedFor(urlPathEqualTo("/synthesize"))
                .withRequestBody(equalToJson("{\"text\":\"hello\",\"voice\":\"voice-a\",\"language\":\"en-US\"}")));
    }

    @Test
    void requestTemplateInterpolationSubstitutesFields() {
        server.stubFor(post(urlPathEqualTo("/synthesize")).willReturn(aResponse().withStatus(200).withBody(new byte[0])));

        String template = "{\"say\":\"{{text}}\",\"v\":\"{{voice}}\",\"lang\":\"{{language}}\"}";
        new LocalHttpTtsProvider(config(2, template))
                .synthesize(new SynthesisRequest("hi there", "voice-a", "en-US", 48000, 2));

        server.verify(postRequestedFor(urlPathEqualTo("/synthesize"))
                .withRequestBody(equalToJson("{\"say\":\"hi there\",\"v\":\"voice-a\",\"lang\":\"en-US\"}")));
    }

    @Test
    void timeoutCancelsTheCall() {
        server.stubFor(post(urlPathEqualTo("/synthesize"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(3000)));

        assertThatThrownBy(() -> new LocalHttpTtsProvider(config(1, null))
                .synthesize(new SynthesisRequest("hi", "voice-a", "en-US", 48000, 2)))
                .isInstanceOf(TtsException.class)
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.PROVIDER_TIMEOUT);
    }
}
