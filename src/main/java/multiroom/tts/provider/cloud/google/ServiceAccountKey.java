package multiroom.tts.provider.cloud.google;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A service account's JSON key, as Google issues it — the spec's <em>Service Account Key</em>.
 * Read and checked once, while the provider is built at start-up, and never read again: a replaced
 * file takes effect on restart, so a broken replacement is caught by start-up, never by an
 * announcement.
 *
 * <p>Loading reads a local file and does no network work. Every fault is an
 * {@link IllegalStateException} naming the extension, the entry and the resolved absolute path, and
 * never the key's content.
 *
 * @param path         the resolved absolute path, used in every message about the file
 * @param clientEmail  the account's identity; the assertion's issuer
 * @param privateKey   the RSA signing key; proven usable for signing at load
 * @param privateKeyId the assertion header's {@code kid}, or {@code null} when the file has none
 * @param tokenUri     where tokens are requested; {@code https}, or {@code http} on loopback only
 */
public record ServiceAccountKey(Path path, String clientEmail, PrivateKey privateKey, String privateKeyId,
                                URI tokenUri) {

    /** Used when the file names no {@code token_uri}. */
    public static final URI DEFAULT_TOKEN_URI = URI.create("https://oauth2.googleapis.com/token");

    private static final String SERVICE_ACCOUNT_TYPE = "service_account";
    private static final String PKCS8_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PKCS8_END = "-----END PRIVATE KEY-----";
    private static final String PKCS1_BEGIN = "-----BEGIN RSA PRIVATE KEY-----";
    private static final Pattern IPV4_LOOPBACK = Pattern.compile("127(\\.\\d{1,3}){3}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Reads and checks the key file.
     *
     * @param providerName the entry's name, for messages
     * @param file         the configured path; a relative one resolves against the working
     *                     directory, and a leading {@code ~} is not expanded
     * @throws IllegalStateException naming the entry and the absolute path, for any fault
     */
    public static ServiceAccountKey load(String providerName, Path file) {
        Path path = file.toAbsolutePath();
        Faults faults = new Faults(providerName, path);
        JsonNode json = parse(read(path, faults), faults);

        String type = text(json, "type");
        if (!SERVICE_ACCOUNT_TYPE.equals(type)) {
            throw faults.of(typeFault(json, type));
        }
        String clientEmail = required(json, "client_email", faults);
        PrivateKey privateKey = privateKey(required(json, "private_key", faults), faults);
        String privateKeyId = text(json, "private_key_id");
        URI tokenUri = tokenUri(text(json, "token_uri"), faults);
        return new ServiceAccountKey(path, clientEmail, privateKey,
                privateKeyId == null || privateKeyId.isBlank() ? null : privateKeyId, tokenUri);
    }

    private static String read(Path path, Faults faults) {
        if (Files.isDirectory(path)) {
            throw faults.of("which is a directory, not a file");
        }
        try {
            return Files.readString(path);
        } catch (NoSuchFileException e) {
            throw faults.of("which does not exist");
        } catch (IOException e) {
            throw faults.of("which cannot be read (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static JsonNode parse(String content, Faults faults) {
        JsonNode json;
        try {
            json = MAPPER.readTree(content);
        } catch (JsonProcessingException e) {
            // Jackson's message quotes the content, which may be the key: never pass it on.
            throw faults.of("which is not valid JSON");
        }
        if (json == null || !json.isObject()) {
            throw faults.of("which is not a service account key: it is not a JSON object");
        }
        return json;
    }

    /** Names what the file looks like instead, since the other files Google hands out are the likely mistake. */
    private static String typeFault(JsonNode json, String type) {
        if (type != null) {
            return "which has type '" + type + "', not a service account key (\"type\": \"service_account\")";
        }
        Iterator<String> fields = json.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (field.equals("installed") || field.equals("web")) {
                return "which has no type and an '" + field + "' object: it is an OAuth client file, "
                        + "not a service account key";
            }
        }
        return "which has no type: it is not a service account key (\"type\": \"service_account\")";
    }

    private static String required(JsonNode json, String field, Faults faults) {
        String value = text(json, field);
        if (value == null || value.isBlank()) {
            throw faults.of("which has no " + field);
        }
        return value;
    }

    private static String text(JsonNode json, String field) {
        JsonNode node = json.get(field);
        return node != null && node.isTextual() ? node.asText() : null;
    }

    /** PKCS#8 PEM, RSA, and accepted by {@link Signature#initSign}: what "usable for signing" means. */
    private static PrivateKey privateKey(String pem, Faults faults) {
        if (pem.contains(PKCS1_BEGIN)) {
            throw faults.of("whose private_key is a PKCS#1 'RSA PRIVATE KEY' block; it must be the PKCS#8 key "
                    + "Google issues ('BEGIN PRIVATE KEY')");
        }
        if (!pem.contains(PKCS8_BEGIN) || !pem.contains(PKCS8_END)) {
            throw faults.of("whose private_key cannot be used for signing: it is not a PEM 'PRIVATE KEY' block");
        }
        String body = pem.replace(PKCS8_BEGIN, "").replace(PKCS8_END, "").replaceAll("\\s", "");
        try {
            PrivateKey key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
            Signature.getInstance("SHA256withRSA").initSign(key);
            return key;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // The cause is dropped on purpose: nothing derived from the key leaves this method.
            throw faults.of("whose private_key cannot be used for signing: it is not an RSA key in PKCS#8 form");
        }
    }

    private static URI tokenUri(String value, Faults faults) {
        if (value == null) {
            return DEFAULT_TOKEN_URI;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw faults.of("whose token_uri '" + value + "' is not an absolute URL");
        }
        if (!uri.isAbsolute() || uri.getHost() == null) {
            throw faults.of("whose token_uri '" + value + "' is not an absolute URL");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(scheme.equals("http") && isLoopback(uri.getHost()))) {
            throw faults.of("whose token_uri '" + value + "' is neither https nor a loopback address; "
                    + "the signed assertion must not travel in clear text");
        }
        return uri;
    }

    /** Decided from the host text alone: resolving a name would be network work at start-up. */
    private static boolean isLoopback(String host) {
        String folded = host.toLowerCase(Locale.ROOT);
        return folded.equals("localhost") || folded.equals("[::1]") || IPV4_LOOPBACK.matcher(folded).matches();
    }

    /** Never the key. */
    @Override
    public String toString() {
        return "ServiceAccountKey[clientEmail=" + clientEmail + ", path=" + path + "]";
    }

    /** Builds start-up faults sharing the entry and the path. */
    private record Faults(String providerName, Path path) {

        IllegalStateException of(String reason) {
            return new IllegalStateException("multiroom-tts: provider '" + providerName
                    + "' has service-account-key-file '" + path + "' " + reason);
        }
    }
}
