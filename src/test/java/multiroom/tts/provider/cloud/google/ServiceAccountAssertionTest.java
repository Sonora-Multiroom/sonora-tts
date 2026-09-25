package multiroom.tts.provider.cloud.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceAccountAssertionTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    private String sign(Map<String, Object> overrides) {
        ServiceAccountKey key = ServiceAccountKey.load("g", TestServiceAccountKeys.write(dir, overrides));
        return new ServiceAccountAssertion(key, ServiceAccountAssertion.CLOUD_PLATFORM_SCOPE,
                Clock.fixed(NOW, ZoneOffset.UTC)).sign();
    }

    private static JsonNode decode(String part) throws Exception {
        return MAPPER.readTree(Base64.getUrlDecoder().decode(part));
    }

    @Test
    void theHeaderIsRs256WithTheKeyId() throws Exception {
        JsonNode header = decode(sign(Map.of("token_uri", "http://localhost:9/token")).split("\\.")[0]);

        assertThat(header.path("alg").asText()).isEqualTo("RS256");
        assertThat(header.path("typ").asText()).isEqualTo("JWT");
        assertThat(header.path("kid").asText()).isEqualTo(TestServiceAccountKeys.PRIVATE_KEY_ID);
    }

    @Test
    void theHeaderHasNoKeyIdWhenTheFileHasNone() throws Exception {
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("private_key_id", null);

        JsonNode header = decode(sign(overrides).split("\\.")[0]);

        assertThat(header.has("kid")).isFalse();
    }

    @Test
    void theClaimsNameTheAccountScopeAudienceAndAnHour() throws Exception {
        JsonNode claims = decode(sign(Map.of("token_uri", "http://localhost:9/token")).split("\\.")[1]);

        assertThat(claims.path("iss").asText()).isEqualTo(TestServiceAccountKeys.CLIENT_EMAIL);
        assertThat(claims.path("scope").asText()).isEqualTo("https://www.googleapis.com/auth/cloud-platform");
        assertThat(claims.path("aud").asText()).isEqualTo("http://localhost:9/token");
        assertThat(claims.path("iat").asLong()).isEqualTo(NOW.getEpochSecond());
        assertThat(claims.path("exp").asLong()).isEqualTo(NOW.getEpochSecond() + 3600);
    }

    @Test
    void thePartsAreUnpaddedAndTheSignatureVerifies() throws Exception {
        String jwt = sign(Map.of("token_uri", "http://localhost:9/token"));
        String[] parts = jwt.split("\\.");

        assertThat(parts).hasSize(3);
        assertThat(jwt).doesNotContain("=");
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(TestServiceAccountKeys.publicKey());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }
}
