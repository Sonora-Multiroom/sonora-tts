package multiroom.tts.provider.cloud.google;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoogleCredentialTest {

    private static final URI ENDPOINT = URI.create("https://texttospeech.googleapis.com/v1/text:synthesize");

    @Test
    void anApiKeyIsAQueryParameterAndNeedsNoPreparation() {
        GoogleCredential credential = new GoogleCredential.ApiKey("secret-key");

        HttpRequest request = credential.authorize(ENDPOINT, null, Duration.ofSeconds(3)).GET().build();

        assertThat(credential.prepare(Duration.ofSeconds(1))).isEmpty();
        assertThat(request.uri()).isEqualTo(URI.create(ENDPOINT + "?key=secret-key"));
        assertThat(request.headers().firstValue("Authorization")).isEmpty();
        assertThat(request.timeout()).contains(Duration.ofSeconds(3));
        assertThat(credential.retriesUnauthorized()).isFalse();
        assertThat(credential.toString()).doesNotContain("secret-key");
        credential.discard("anything");
    }

    @Test
    void aServiceAccountSendsABearerTokenFromItsCache() {
        GoogleAccessTokenCache tokens = mock(GoogleAccessTokenCache.class);
        when(tokens.token(Duration.ofSeconds(1))).thenReturn("ya29.test");
        when(tokens.toString()).thenReturn("GoogleAccessTokenCache[provider=g]");
        GoogleCredential credential = new GoogleCredential.ServiceAccount(tokens);

        String token = credential.prepare(Duration.ofSeconds(1)).orElseThrow();
        HttpRequest request = credential.authorize(ENDPOINT, token, Duration.ofSeconds(3)).GET().build();

        assertThat(token).isEqualTo("ya29.test");
        assertThat(request.uri()).isEqualTo(ENDPOINT);
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer ya29.test");
        assertThat(credential.retriesUnauthorized()).isTrue();
        assertThat(credential.toString()).doesNotContain("ya29.test");

        credential.discard("ya29.test");
        verify(tokens).discard("ya29.test");
    }
}
