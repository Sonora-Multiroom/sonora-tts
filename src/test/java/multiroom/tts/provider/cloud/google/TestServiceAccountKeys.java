package multiroom.tts.provider.cloud.google;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes service account key files for tests. The key pair is generated at run time, once per JVM
 * (RSA generation is slow), so no key material is ever committed.
 */
public final class TestServiceAccountKeys {

    public static final String CLIENT_EMAIL = "sonora-tts@test-project.iam.gserviceaccount.com";
    public static final String PRIVATE_KEY_ID = "0123456789abcdef";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static KeyPair keyPair;

    private TestServiceAccountKeys() {
    }

    public static synchronized KeyPair keyPair() {
        if (keyPair == null) {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                keyPair = generator.generateKeyPair();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        return keyPair;
    }

    public static PublicKey publicKey() {
        return keyPair().getPublic();
    }

    /** The PKCS#8 body, base64 without line breaks: what must never appear in any output. */
    public static String privateKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair().getPrivate().getEncoded());
    }

    /** The key as Google writes it: PKCS#8 PEM with {@code \n} line breaks every 64 characters. */
    public static String privateKeyPem() {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair().getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }

    /** A valid key file whose {@code token_uri} is {@code tokenUri}. */
    public static Path write(Path dir, String tokenUri) {
        return write(dir, Map.of("token_uri", tokenUri));
    }

    /**
     * A key file with {@code overrides} applied over a valid one; a {@code null} value removes the
     * field.
     */
    public static Path write(Path dir, Map<String, Object> overrides) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", "service_account");
        fields.put("project_id", "test-project");
        fields.put("private_key_id", PRIVATE_KEY_ID);
        fields.put("private_key", privateKeyPem());
        fields.put("client_email", CLIENT_EMAIL);
        fields.put("token_uri", "https://oauth2.googleapis.com/token");
        overrides.forEach((key, value) -> {
            if (value == null) {
                fields.remove(key);
            } else {
                fields.put(key, value);
            }
        });
        return writeRaw(dir, toJson(fields));
    }

    /** A file with exactly this content. */
    public static Path writeRaw(Path dir, String content) {
        try {
            Path file = Files.createTempFile(dir, "sa-", ".json");
            Files.writeString(file, content);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String toJson(Map<String, Object> fields) {
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
