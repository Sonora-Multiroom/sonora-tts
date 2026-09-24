package multiroom.tts.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Root configuration for the extension, bound from {@code multiroom.tts}.
 *
 * <p>Every default here is a field initialiser: 019 forbids a bundled {@code application.yml},
 * so this class is the only place a default may live. Validation in {@link #validate()} reads
 * configuration and the filesystem only — never the network — per FR-028/FR-029: an
 * operator-fixable fault (missing API key, a binary path that does not exist) aborts start-up
 * naming this extension; an unreachable-but-correctly-configured provider does not.
 */
@Data
@Validated
@ConfigurationProperties("multiroom.tts")
public class TtsProperties {

    private static final Logger log = LoggerFactory.getLogger(TtsProperties.class);
    private static final int TIMEOUT_WARN_THRESHOLD_SECONDS = 10;

    /** Switches the whole extension off without removing its distributable (019 FR-008). */
    private boolean enabled = true;

    private List<TtsProviderConfig> providers = new ArrayList<>();

    /** Explicit default provider name; the first configured provider is used if absent. */
    private String defaultProvider;

    private CacheProperties cache = new CacheProperties();

    private int maxTextLength = 500;

    private QueueProperties queue = new QueueProperties();

    @PostConstruct
    public void validate() {
        if (!enabled) {
            return;
        }
        if (providers == null || providers.isEmpty()) {
            throw fault("at least one provider must be configured under multiroom.tts.providers");
        }

        Set<String> seenNames = new HashSet<>();
        Set<String> enabledNames = new HashSet<>();
        for (TtsProviderConfig provider : providers) {
            if (provider.getName() == null || provider.getName().isBlank()) {
                throw fault("every entry under multiroom.tts.providers must have a name");
            }
            if (!seenNames.add(provider.getName())) {
                throw fault("duplicate provider name '" + provider.getName() + "'");
            }
            if (provider.getType() == null) {
                throw fault("provider '" + provider.getName() + "' must set a type");
            }
            if (provider.isEnabled()) {
                enabledNames.add(provider.getName());
            }
            validateProvider(provider);
        }

        if (defaultProvider != null && !defaultProvider.isBlank()) {
            if (!seenNames.contains(defaultProvider)) {
                throw fault("default-provider '" + defaultProvider + "' does not match any configured provider");
            }
            // Only enabled providers reach ProviderRegistry, so a disabled default would leave
            // resolveDefault() returning null and surface at runtime as a misleading
            // PROVIDER_ERROR on the first request that omits a provider name — an
            // operator-fixable config fault, so it aborts start-up here instead (FR-028).
            if (!enabledNames.contains(defaultProvider)) {
                throw fault("default-provider '" + defaultProvider + "' names a provider that is "
                        + "disabled; enable it or point default-provider at an enabled provider");
            }
        }
    }

    private void validateProvider(TtsProviderConfig provider) {
        switch (provider.getType()) {
            case OPENAI, GOOGLE_CLOUD -> {
                if (provider.getApiKey() == null || provider.getApiKey().isBlank()) {
                    throw fault("provider '" + provider.getName() + "' requires api-key");
                }
            }
            case PIPER -> {
                // pythonExecutable is not checked for existence: it defaults to "python3",
                // a bare command resolved via PATH, not a literal filesystem path — and
                // validation must never spawn a process to test it (FR-029).
                requirePathExists(provider, "model-path", provider.getModelPath());
                String configPath = provider.getModelPath() + ".json";
                if (!Files.exists(Path.of(configPath))) {
                    throw fault("provider '" + provider.getName() + "' is missing the Piper model "
                            + "config '" + configPath + "' — the .onnx.json sidecar must sit next "
                            + "to the .onnx model");
                }
            }
            case LOCAL_HTTP -> {
                if (provider.getEndpoint() == null || provider.getEndpoint().isBlank()) {
                    throw fault("provider '" + provider.getName() + "' requires endpoint");
                }
            }
        }

        if (provider.getTimeoutSeconds() > TIMEOUT_WARN_THRESHOLD_SECONDS) {
            log.warn("multiroom-tts: provider '{}' is configured with timeout-seconds={}, above the "
                            + "{}s default that SC-003's error budget assumes; the error-within-10s "
                            + "guarantee no longer holds for this provider",
                    provider.getName(), provider.getTimeoutSeconds(), TIMEOUT_WARN_THRESHOLD_SECONDS);
        }
    }

    private void requirePathExists(TtsProviderConfig provider, String fieldName, String value) {
        if (value == null || value.isBlank()) {
            throw fault("provider '" + provider.getName() + "' requires " + fieldName);
        }
        Path path = Path.of(value);
        if (!Files.exists(path)) {
            throw fault("provider '" + provider.getName() + "' has " + fieldName + " '" + value
                    + "' which does not exist");
        }
    }

    private static IllegalStateException fault(String message) {
        return new IllegalStateException("multiroom-tts: " + message);
    }
}
