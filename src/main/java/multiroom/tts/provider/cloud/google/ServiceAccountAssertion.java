package multiroom.tts.provider.cloud.google;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The signed JWT a service account exchanges for an access token (Google's server-to-server
 * OAuth flow): RS256, issued by the account, addressed to the token service, valid for an hour.
 * Built with the JDK alone, so no authentication library is bundled. Pure: no I/O.
 *
 * <p>Safe for concurrent use: each {@link #sign()} uses its own {@link Signature}.
 */
public final class ServiceAccountAssertion {

    /**
     * Broad enough for Text-to-Speech and for the Agent Platform check Google applies to Gemini
     * voices, so a later Gemini feature needs no change to how tokens are obtained.
     */
    public static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    /** The longest Google accepts. */
    private static final Duration LIFETIME = Duration.ofHours(1);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private final ServiceAccountKey key;
    private final String scope;
    private final Clock clock;

    public ServiceAccountAssertion(ServiceAccountKey key, String scope, Clock clock) {
        this.key = key;
        this.scope = scope;
        this.clock = clock;
    }

    /** @return {@code header.claims.signature}, each part base64url without padding */
    public String sign() {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        if (key.privateKeyId() != null) {
            header.put("kid", key.privateKeyId());
        }
        long issuedAt = clock.instant().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", key.clientEmail());
        claims.put("scope", scope);
        // Google requires the audience to be the address the assertion is sent to.
        claims.put("aud", key.tokenUri().toString());
        claims.put("iat", issuedAt);
        claims.put("exp", issuedAt + LIFETIME.getSeconds());

        String signingInput = encode(header) + "." + encode(claims);
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key.privateKey());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + BASE64URL.encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            // Unreachable: loading the key already proved it can sign.
            throw new IllegalStateException("the service account key of " + key.clientEmail() + " cannot sign", e);
        }
    }

    private static String encode(Map<String, Object> json) {
        try {
            return BASE64URL.encodeToString(MAPPER.writeValueAsBytes(json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize the service account assertion", e);
        }
    }
}
