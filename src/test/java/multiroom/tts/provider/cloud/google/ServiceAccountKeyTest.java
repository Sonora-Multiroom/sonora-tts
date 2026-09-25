package multiroom.tts.provider.cloud.google;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceAccountKeyTest {

    @TempDir
    Path dir;

    private static Map<String, Object> override(String field, Object value) {
        Map<String, Object> overrides = new HashMap<>();
        overrides.put(field, value);
        return overrides;
    }

    // --- the happy path ------------------------------------------------------------------------

    @Test
    void aValidFileLoadsItsFields() {
        Path file = TestServiceAccountKeys.write(dir, "http://localhost:8089/token");

        ServiceAccountKey key = ServiceAccountKey.load("g", file);

        assertThat(key.clientEmail()).isEqualTo(TestServiceAccountKeys.CLIENT_EMAIL);
        assertThat(key.privateKeyId()).isEqualTo(TestServiceAccountKeys.PRIVATE_KEY_ID);
        assertThat(key.tokenUri()).isEqualTo(URI.create("http://localhost:8089/token"));
        assertThat(key.privateKey().getAlgorithm()).isEqualTo("RSA");
        assertThat(key.path()).isEqualTo(file.toAbsolutePath());
    }

    @Test
    void aRelativePathIsResolvedAgainstTheWorkingDirectory() throws Exception {
        Path file = TestServiceAccountKeys.write(dir, "https://oauth2.googleapis.com/token");
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path relative;
        try {
            relative = workingDirectory.relativize(file.toAbsolutePath());
        } catch (IllegalArgumentException e) {
            // A temporary directory on another drive (Windows) cannot be relative; copy it here.
            Path local = Files.createTempFile(workingDirectory.resolve("target"), "sa-", ".json");
            Files.copy(file, local, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            local.toFile().deleteOnExit();
            relative = workingDirectory.relativize(local);
        }
        assertThat(relative.isAbsolute()).isFalse();

        ServiceAccountKey key = ServiceAccountKey.load("g", relative);

        assertThat(key.path().isAbsolute()).isTrue();
        assertThat(key.path()).isEqualTo(workingDirectory.resolve(relative));
    }

    @Test
    void aMissingTokenUriDefaultsToGooglesTokenAddress() {
        ServiceAccountKey key = ServiceAccountKey.load("g", TestServiceAccountKeys.write(dir, override("token_uri", null)));

        assertThat(key.tokenUri()).isEqualTo(URI.create("https://oauth2.googleapis.com/token"));
    }

    @Test
    void aMissingPrivateKeyIdIsNull() {
        ServiceAccountKey key = ServiceAccountKey.load("g",
                TestServiceAccountKeys.write(dir, override("private_key_id", null)));

        assertThat(key.privateKeyId()).isNull();
    }

    @Test
    void toStringNamesTheAccountAndPathButNeverTheKey() {
        Path file = TestServiceAccountKeys.write(dir, "https://oauth2.googleapis.com/token");

        String text = ServiceAccountKey.load("g", file).toString();

        assertThat(text).contains(TestServiceAccountKeys.CLIENT_EMAIL).contains(file.toAbsolutePath().toString());
        assertThat(text).doesNotContain(TestServiceAccountKeys.privateKeyBase64().substring(0, 40));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:9/token", "http://127.0.0.1:9/token", "http://127.1.2.3/token",
            "http://[::1]:9/token", "https://oauth2.googleapis.com/token"})
    void httpsOrALoopbackTokenUriIsAccepted(String tokenUri) {
        assertThat(ServiceAccountKey.load("g", TestServiceAccountKeys.write(dir, tokenUri)).tokenUri())
                .isEqualTo(URI.create(tokenUri));
    }

    // --- every fault -------------------------------------------------------------------------

    private void assertFault(Path file, String... fragments) {
        assertThatThrownBy(() -> ServiceAccountKey.load("g", file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'g'")
                .hasMessageContaining(file.toAbsolutePath().toString())
                .hasMessageContaining("service-account-key-file")
                .satisfies(e -> {
                    for (String fragment : fragments) {
                        assertThat(e.getMessage()).contains(fragment);
                    }
                    assertThat(e.getMessage()).doesNotContain(TestServiceAccountKeys.privateKeyBase64().substring(0, 40));
                });
    }

    @Test
    void aMissingFile() {
        assertFault(dir.resolve("absent.json"), "does not exist");
    }

    @Test
    void aRelativeFileThatDoesNotExistReportsItsAbsolutePath() {
        Path relative = Path.of("no-such-dir", "sa.json");

        assertFault(relative, Path.of("").toAbsolutePath().resolve(relative).toString(), "does not exist");
    }

    @Test
    void aLeadingTildeIsNotExpanded() {
        Path tilde = Path.of("~/sa.json");

        assertFault(tilde, Path.of("").toAbsolutePath().resolve("~").resolve("sa.json").toString(), "does not exist");
    }

    @Test
    void aDirectory() {
        assertFault(dir, "is a directory");
    }

    @Test
    void notJson() {
        assertFault(TestServiceAccountKeys.writeRaw(dir, "this is { not json"), "not valid JSON");
    }

    @Test
    void jsonThatIsNotAnObject() {
        assertFault(TestServiceAccountKeys.writeRaw(dir, "[1, 2, 3]"), "not a JSON object");
    }

    @Test
    void anAuthorizedUserFile() {
        assertFault(TestServiceAccountKeys.write(dir, override("type", "authorized_user")),
                "'authorized_user'", "not a service account key");
    }

    @Test
    void anOAuthClientFile() {
        Path file = TestServiceAccountKeys.writeRaw(dir,
                "{\"installed\":{\"client_id\":\"x.apps.googleusercontent.com\",\"client_secret\":\"s\"}}");

        assertFault(file, "no type", "'installed'", "not a service account key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"client_email", "private_key"})
    void aMissingRequiredField(String field) {
        assertFault(TestServiceAccountKeys.write(dir, override(field, null)), "has no " + field);
    }

    @Test
    void aBlankClientEmail() {
        assertFault(TestServiceAccountKeys.write(dir, override("client_email", "  ")), "has no client_email");
    }

    @Test
    void aCorruptedPrivateKey() {
        String pem = TestServiceAccountKeys.privateKeyPem();
        String corrupted = pem.substring(0, 60) + "AAAAAAAAAAAAAAAAAAAA" + pem.substring(80);

        assertFault(TestServiceAccountKeys.write(dir, override("private_key", corrupted)),
                "cannot be used for signing");
    }

    @Test
    void aPkcs1PrivateKey() {
        String pkcs1 = "-----BEGIN RSA PRIVATE KEY-----\nMIIBOgIBAAJBAK\n-----END RSA PRIVATE KEY-----\n";

        assertFault(TestServiceAccountKeys.write(dir, override("private_key", pkcs1)), "PKCS#8");
    }

    @Test
    void anEcPrivateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(generator.generateKeyPair()
                .getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";

        assertFault(TestServiceAccountKeys.write(dir, override("private_key", pem)), "cannot be used for signing");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a url", "http://oauth2.googleapis.com/token", "ftp://oauth2.googleapis.com/token",
            "/token", "http://localhost.example.com/token"})
    void anUnacceptableTokenUri(String tokenUri) {
        assertFault(TestServiceAccountKeys.write(dir, tokenUri), "token_uri", "'" + tokenUri + "'");
    }
}
