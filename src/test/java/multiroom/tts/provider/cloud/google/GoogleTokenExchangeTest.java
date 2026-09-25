package multiroom.tts.provider.cloud.google;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import multiroom.tts.TtsErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleTokenExchangeTest {

    private static final String ASSERTION = "eyJhbGciOiJSUzI1NiJ9.eyJpc3MiOiJ4In0.c2ln";

    private WireMockServer server;
    private GoogleTokenExchange exchange;

    @BeforeEach
    void start() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        exchange = new GoogleTokenExchange("g", HttpClient.newHttpClient(), URI.create(server.baseUrl() + "/token"));
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private void answer(int status, String body) {
        server.stubFor(post(urlPathEqualTo("/token")).willReturn(aResponse().withStatus(status).withBody(body)));
    }

    @Test
    void aSuccessIsTheTokenAndItsLifetime() {
        answer(200, "{\"access_token\":\"ya29.test\",\"expires_in\":3599,\"token_type\":\"Bearer\"}");

        GoogleTokenExchange.Token token = exchange.exchange(ASSERTION, Duration.ofSeconds(2));

        assertThat(token.value()).isEqualTo("ya29.test");
        assertThat(token.expiresIn()).isEqualTo(Duration.ofSeconds(3599));
        assertThat(token.toString()).doesNotContain("ya29.test");
        server.verify(postRequestedFor(urlPathEqualTo("/token"))
                .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded"))
                .withRequestBody(containing("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"))
                .withRequestBody(containing("assertion=" + ASSERTION)));
    }

    /**
     * A generous budget, so a cold WireMock under a full build is never mistaken for a timeout;
     * only the timeout case needs a short one.
     */
    private void assertFailure(Class<? extends TokenFailure> kind, TtsErrorCode code, String... fragments) {
        assertFailure(Duration.ofSeconds(5), kind, code, fragments);
    }

    private void assertFailure(Duration budget, Class<? extends TokenFailure> kind, TtsErrorCode code,
                               String... fragments) {
        assertThatThrownBy(() -> exchange.exchange(ASSERTION, budget))
                .isInstanceOf(kind)
                .hasMessageStartingWith("Provider 'g' could not obtain an access token: ")
                .satisfies(e -> {
                    assertThat(((TokenFailure) e).getErrorCode()).isEqualTo(code);
                    for (String fragment : fragments) {
                        assertThat(e.getMessage()).contains(fragment);
                    }
                    assertThat(e.getMessage()).doesNotContain(ASSERTION);
                });
    }

    @Test
    void aTimeoutWithinTheBudgetIsUnavailable() {
        server.stubFor(post(urlPathEqualTo("/token")).willReturn(aResponse().withStatus(200)
                .withBody("{}").withFixedDelay(5000)));

        long started = System.nanoTime();
        assertFailure(Duration.ofMillis(500), TokenFailure.Unavailable.class, TtsErrorCode.PROVIDER_TIMEOUT,
                "did not respond within 500 ms");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(4000));
    }

    @Test
    void aConnectionResetIsUnavailable() {
        server.stubFor(post(urlPathEqualTo("/token"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertFailure(TokenFailure.Unavailable.class, TtsErrorCode.PROVIDER_ERROR);
    }

    @Test
    void aServerErrorIsUnavailable() {
        answer(503, "{\"error\":{\"code\":503,\"message\":\"The service is currently unavailable.\"}}");

        assertFailure(TokenFailure.Unavailable.class, TtsErrorCode.PROVIDER_ERROR, "HTTP 503",
                "The service is currently unavailable.");
    }

    @Test
    void aRateLimitIsUnavailableAndRateLimited() {
        answer(429, "");

        assertFailure(TokenFailure.Unavailable.class, TtsErrorCode.PROVIDER_RATE_LIMITED, "HTTP 429");
    }

    @Test
    void anInvalidGrantIsARejectionWithGooglesExplanation() {
        answer(400, "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid JWT Signature.\"}");

        assertFailure(TokenFailure.Rejected.class, TtsErrorCode.PROVIDER_ERROR, "could not obtain an access token",
                "rejected the service account key", "HTTP 400", "Invalid JWT Signature. (invalid_grant)");
    }

    @Test
    void aRejectionWithANonJsonBodyStillStatesTheStatus() {
        answer(401, "<html>nope</html>");

        assertFailure(TokenFailure.Rejected.class, TtsErrorCode.PROVIDER_ERROR, "HTTP 401");
    }

    @Test
    void aSuccessWithoutATokenIsUnusable() {
        answer(200, "{\"token_type\":\"Bearer\",\"expires_in\":3599}");

        assertFailure(TokenFailure.Rejected.class, TtsErrorCode.PROVIDER_ERROR, "unusable response");
    }

    @Test
    void aSuccessWithoutALifetimeIsUnusable() {
        answer(200, "{\"access_token\":\"ya29.test\"}");

        assertFailure(TokenFailure.Rejected.class, TtsErrorCode.PROVIDER_ERROR, "unusable response");
    }

    @Test
    void aSuccessThatIsNotJsonIsUnusable() {
        answer(200, "ya29.test");

        assertThatThrownBy(() -> exchange.exchange(ASSERTION, Duration.ofSeconds(2)))
                .isInstanceOf(TokenFailure.Rejected.class)
                .hasMessageContaining("unusable response")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("ya29.test"));
    }
}
