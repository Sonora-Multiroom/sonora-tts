package multiroom.tts.provider.cloud.google;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleTtsClientTest {

    private static final String SYNTHESIZE = "/v1/text:synthesize";
    private static final String VOICES = "/v1/voices";
    private static final String TOKEN = "/token";
    private static final String TOKEN_BODY = "{\"access_token\":\"ya29.test\",\"expires_in\":3599}";

    private WireMockServer server;

    @TempDir
    Path keyDir;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private URI apiBase() {
        return URI.create(server.baseUrl() + "/v1/");
    }

    private GoogleTtsClient apiKeyClient(int timeoutSeconds) {
        return new GoogleTtsClient("gemini", Duration.ofSeconds(timeoutSeconds), apiBase(), null, "test-key",
                Duration.ofSeconds(60), Clock.systemUTC());
    }

    private Path keyFile() {
        return TestServiceAccountKeys.write(keyDir, server.baseUrl() + TOKEN);
    }

    private GoogleTtsClient serviceAccountClient(int timeoutSeconds) {
        ServiceAccountKey key = ServiceAccountKey.load("gemini", keyFile());
        return new GoogleTtsClient("gemini", Duration.ofSeconds(timeoutSeconds), apiBase(), key, null,
                Duration.ofSeconds(60), Clock.systemUTC());
    }

    private void stubToken() {
        server.stubFor(post(urlPathEqualTo(TOKEN)).willReturn(okJson(TOKEN_BODY)));
    }

    private static String audioResponse(byte[] wavBytes) {
        return "{\"audioContent\":\"" + Base64.getEncoder().encodeToString(wavBytes) + "\"}";
    }

    private void stubSynthesis() {
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE))
                .willReturn(aResponse().withStatus(200).withBody(audioResponse(new byte[] {1}))));
    }

    private static Instant deadline(Duration timeout) {
        return Instant.now().plus(timeout);
    }

    @Test
    void constructionMakesNoHttpCall() {
        serviceAccountClient(2);
        apiKeyClient(2);

        server.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void anApiKeyCredentialSendsKeyQueryParamAndNoAuthorizationHeader() {
        stubSynthesis();

        apiKeyClient(2).synthesize("{}", deadline(Duration.ofSeconds(2)));

        server.verify(postRequestedFor(urlPathEqualTo(SYNTHESIZE))
                .withQueryParam("key", equalTo("test-key"))
                .withHeader("Authorization", absent()));
    }

    @Test
    void aServiceAccountCredentialSendsABearerTokenFromTheTokenEndpoint() {
        stubToken();
        stubSynthesis();

        serviceAccountClient(2).synthesize("{}", deadline(Duration.ofSeconds(2)));

        server.verify(postRequestedFor(urlPathEqualTo(SYNTHESIZE))
                .withHeader("Authorization", equalTo("Bearer ya29.test"))
                .withQueryParam("key", absent()));
        server.verify(1, postRequestedFor(urlPathEqualTo(TOKEN)));
    }

    @Test
    void a401FromAServiceAccountDiscardsRenewsAndResendsOnce() {
        stubToken();
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("recovered"));
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).inScenario("retry")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200).withBody(audioResponse(new byte[] {7}))));

        byte[] result = serviceAccountClient(2).synthesize("{}", deadline(Duration.ofSeconds(2)));

        assertThat(result).containsExactly(7);
        server.verify(2, postRequestedFor(urlPathEqualTo(TOKEN)));
        server.verify(2, postRequestedFor(urlPathEqualTo(SYNTHESIZE)));
    }

    @Test
    void a403IsNeverRetried() {
        stubToken();
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).willReturn(aResponse().withStatus(403)
                .withBody("{\"error\":{\"code\":403,\"message\":\"Permission denied\"}}")));

        assertThatThrownBy(() -> serviceAccountClient(2).synthesize("{}", deadline(Duration.ofSeconds(2))))
                .isInstanceOf(TtsException.class)
                .satisfies(e -> assertThat(((TtsException) e).getErrorCode()).isEqualTo(TtsErrorCode.PROVIDER_ERROR));
        server.verify(1, postRequestedFor(urlPathEqualTo(TOKEN)));
        server.verify(1, postRequestedFor(urlPathEqualTo(SYNTHESIZE)));
    }

    @Test
    void a429IsRateLimitedWithGooglesExplanation() {
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).willReturn(aResponse().withStatus(429)
                .withBody("{\"error\":{\"code\":429,\"message\":\"Quota exceeded\"}}")));

        assertThatThrownBy(() -> apiKeyClient(2).synthesize("{}", deadline(Duration.ofSeconds(2))))
                .isInstanceOf(TtsException.class)
                .hasMessage("Provider 'gemini' reported rate limit exceeded (HTTP 429): Quota exceeded")
                .satisfies(e -> assertThat(((TtsException) e).getErrorCode()).isEqualTo(TtsErrorCode.PROVIDER_RATE_LIMITED));
    }

    @Test
    void a400IsAProviderErrorWithGooglesExplanation() {
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).willReturn(aResponse().withStatus(400)
                .withBody("{\"error\":{\"code\":400,\"message\":\"Invalid voice\"}}")));

        assertThatThrownBy(() -> apiKeyClient(2).synthesize("{}", deadline(Duration.ofSeconds(2))))
                .isInstanceOf(TtsException.class)
                .hasMessage("Provider 'gemini' returned HTTP 400: Invalid voice")
                .satisfies(e -> assertThat(((TtsException) e).getErrorCode()).isEqualTo(TtsErrorCode.PROVIDER_ERROR));
    }

    @Test
    void aDelayedResponseBeyondTheDeadlineIsAProviderTimeout() {
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE))
                .willReturn(aResponse().withStatus(200).withBody("{}").withFixedDelay(3000)));

        assertThatThrownBy(() -> apiKeyClient(1).synthesize("{}", deadline(Duration.ofSeconds(1))))
                .isInstanceOf(TtsException.class)
                .hasMessage("Provider 'gemini' did not respond within 1 seconds")
                .satisfies(e -> assertThat(((TtsException) e).getErrorCode()).isEqualTo(TtsErrorCode.PROVIDER_TIMEOUT));
    }

    @Test
    void a200WithNoAudioContentIsAProviderError() {
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).willReturn(aResponse().withStatus(200).withBody("{}")));

        assertThatThrownBy(() -> apiKeyClient(2).synthesize("{}", deadline(Duration.ofSeconds(2))))
                .isInstanceOf(TtsException.class)
                .satisfies(e -> assertThat(((TtsException) e).getErrorCode()).isEqualTo(TtsErrorCode.PROVIDER_ERROR));
    }

    @Test
    void synthesizeDecodesBase64AudioContent() {
        byte[] wavBytes = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'};
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE))
                .willReturn(aResponse().withStatus(200).withBody(audioResponse(wavBytes))));

        byte[] result = apiKeyClient(2).synthesize("{}", deadline(Duration.ofSeconds(2)));

        assertThat(result).isEqualTo(wavBytes);
    }

    @Test
    void getReturnsTheRawResponseForTheCatalogueFetcher() throws Exception {
        stubToken();
        server.stubFor(get(urlPathEqualTo(VOICES)).willReturn(aResponse().withStatus(200).withBody("[\"a\"]")));

        HttpResponse<String> response = serviceAccountClient(2).get(apiBase().resolve("voices"),
                deadline(Duration.ofSeconds(2)));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("[\"a\"]");
        server.verify(getRequestedFor(urlPathEqualTo(VOICES)).withHeader("Authorization", equalTo("Bearer ya29.test")));
    }

    @Test
    void fetchVoicesReturnsTheBodyOfA200() throws Exception {
        stubToken();
        server.stubFor(get(urlPathEqualTo(VOICES)).willReturn(okJson("{\"voices\":[]}")));

        assertThat(serviceAccountClient(2).fetchVoices(Duration.ofSeconds(2))).isEqualTo("{\"voices\":[]}");
        server.verify(getRequestedFor(urlPathEqualTo(VOICES)).withHeader("Authorization", equalTo("Bearer ya29.test")));
    }

    @Test
    void fetchVoicesTurnsARefusalIntoAnIOExceptionWithGooglesExplanation() {
        stubToken();
        server.stubFor(get(urlPathEqualTo(VOICES)).willReturn(aResponse().withStatus(403)
                .withBody("{\"error\":{\"code\":403,\"message\":\"Permission denied\"}}")));

        assertThatThrownBy(() -> serviceAccountClient(2).fetchVoices(Duration.ofSeconds(2)))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("HTTP 403: Permission denied");
    }

    @Test
    void fetchVoicesTurnsATokenFailureIntoAnIOExceptionCarryingItsMessage() {
        server.stubFor(post(urlPathEqualTo(TOKEN)).willReturn(aResponse().withStatus(400)
                .withBody("{\"error\":\"invalid_grant\",\"error_description\":\"Invalid JWT Signature.\"}")));

        assertThatThrownBy(() -> serviceAccountClient(2).fetchVoices(Duration.ofSeconds(2)))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("could not obtain an access token")
                .hasCauseInstanceOf(TtsException.class);
        server.verify(0, getRequestedFor(urlPathEqualTo(VOICES)));
    }

    @Test
    void postSendsJsonContentType() {
        stubSynthesis();

        apiKeyClient(2).synthesize("{}", deadline(Duration.ofSeconds(2)));

        server.verify(postRequestedFor(urlPathEqualTo(SYNTHESIZE)).withHeader("Content-Type", equalTo("application/json")));
    }
}
