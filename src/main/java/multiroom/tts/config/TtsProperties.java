package multiroom.tts.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import multiroom.tts.provider.cloud.google.GoogleEngine;
import multiroom.tts.provider.cloud.google.GoogleLanguage;
import multiroom.tts.provider.cloud.google.GoogleVoiceName;
import multiroom.tts.provider.cloud.google.GoogleVoiceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Root configuration for the extension, bound from {@code multiroom.tts}.
 *
 * <p>Every default here is a field initialiser: 019 forbids a bundled {@code application.yml},
 * so this class is the only place a default may live. Validation in {@link #validate()} reads
 * configuration and the filesystem only — never the network, nor a subprocess: an
 * operator-fixable fault (a missing or doubly-set credential, a binary path that does not exist) aborts start-up
 * naming this extension; an unreachable-but-correctly-configured provider does not.
 */
@Data
@Validated
@ConfigurationProperties("multiroom.tts")
public class TtsProperties {

    private static final Logger log = LoggerFactory.getLogger(TtsProperties.class);
    private static final int TIMEOUT_WARN_THRESHOLD_SECONDS = 10;

    /** Switches the whole extension off without removing its distributable. */
    private boolean enabled = true;

    private List<TtsProviderConfig> providers = new ArrayList<>();

    /** Explicit default provider name; the first configured provider is used if absent. */
    private String defaultProvider;

    private CacheProperties cache = new CacheProperties();

    private int maxTextLength = 500;

    private QueueProperties queue = new QueueProperties();

    private VoiceCatalogueProperties voiceCatalogue = new VoiceCatalogueProperties();

    @PostConstruct
    public void validate() {
        if (!enabled) {
            return;
        }
        if (providers == null || providers.isEmpty()) {
            throw fault("at least one provider must be configured under multiroom.tts.providers");
        }
        requirePositive("voice-catalogue.ttl", voiceCatalogue.getTtl());
        requirePositive("voice-catalogue.failure-backoff", voiceCatalogue.getFailureBackoff());
        requirePositive("voice-catalogue.fetch-timeout", voiceCatalogue.getFetchTimeout());

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
            // operator-fixable config fault, so it aborts start-up here instead.
            if (!enabledNames.contains(defaultProvider)) {
                throw fault("default-provider '" + defaultProvider + "' names a provider that is "
                        + "disabled; enable it or point default-provider at an enabled provider");
            }
        }
    }

    private void validateProvider(TtsProviderConfig provider) {
        switch (provider.getType()) {
            case OPENAI -> requireApiKey(provider);
            case GOOGLE_CLOUD -> {
                requireExactlyOneGoogleCredential(provider);
                validateGoogleVoiceSelection(provider);
                validateGoogleAudioSettings(provider);
            }
            case PIPER -> {
                // pythonExecutable is not checked for existence: it defaults to "python3",
                // a bare command resolved via PATH, not a literal filesystem path — and
                // validation must never spawn a process to test it.
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
        if (provider.getType() != ProviderType.GOOGLE_CLOUD) {
            // Never accepted and silently ignored: only google-cloud sends them.
            rejectGoogleOnlySetting(provider, "pitch", provider.getPitch() != null);
            rejectGoogleOnlySetting(provider, "speaking-rate", provider.getSpeakingRate() != null);
            rejectGoogleOnlySetting(provider, "service-account-key-file", isSet(provider.getServiceAccountKeyFile()));
        }

        if (provider.getTimeoutSeconds() > TIMEOUT_WARN_THRESHOLD_SECONDS) {
            log.warn("multiroom-tts: provider '{}' is configured with timeout-seconds={}, above the "
                            + "{}s default that the error-within-10s budget assumes; that "
                            + "guarantee no longer holds for this provider",
                    provider.getName(), provider.getTimeoutSeconds(), TIMEOUT_WARN_THRESHOLD_SECONDS);
        }
    }

    private void validateGoogleAudioSettings(TtsProviderConfig provider) {
        requireInRange(provider, "pitch", provider.getPitch(),
                GoogleVoiceResolver.MIN_PITCH, GoogleVoiceResolver.MAX_PITCH);
        requireInRange(provider, "speaking-rate", provider.getSpeakingRate(),
                GoogleVoiceResolver.MIN_SPEAKING_RATE, GoogleVoiceResolver.MAX_SPEAKING_RATE);
        // extra-params would otherwise be accepted and ignored. Mapping arbitrary keys is
        // not offered: whether a setting took effect would then depend on how its key was spelled.
        if (provider.getExtraParams() != null && !provider.getExtraParams().isEmpty()) {
            throw fault("provider '" + provider.getName() + "' sets extra-params " + provider.getExtraParams().keySet()
                    + ", which google-cloud does not use; set pitch and speaking-rate instead");
        }
    }

    private static void requireInRange(TtsProviderConfig provider, String key, Double value, double min, double max) {
        // Both comparisons are false for NaN, so it has to be rejected explicitly.
        if (value != null && (!Double.isFinite(value) || value < min || value > max)) {
            throw fault("provider '" + provider.getName() + "' has " + key + " " + value
                    + ", outside the allowed range [" + min + ", " + max + "]");
        }
    }

    private static void rejectGoogleOnlySetting(TtsProviderConfig provider, String key, boolean set) {
        if (set) {
            throw fault("provider '" + provider.getName() + "' sets " + key + ", which only google-cloud "
                    + "supports; a provider of type " + provider.getType() + " would ignore it");
        }
    }

    private static void requirePositive(String key, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw fault("multiroom.tts." + key + " must be positive, but is " + value);
        }
    }

    /**
     * Shape only: the key file itself is read once, when the provider is built, so a private key
     * never sits in this bean.
     */
    private static void requireExactlyOneGoogleCredential(TtsProviderConfig provider) {
        boolean apiKey = isSet(provider.getApiKey());
        boolean keyFile = isSet(provider.getServiceAccountKeyFile());
        if (apiKey == keyFile) {
            throw fault("provider '" + provider.getName() + "' requires exactly one of api-key and "
                    + "service-account-key-file" + (apiKey ? ", not both" : ""));
        }
    }

    /** A blank value counts as unset. */
    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private void requireApiKey(TtsProviderConfig provider) {
        if (provider.getApiKey() == null || provider.getApiKey().isBlank()) {
            throw fault("provider '" + provider.getName() + "' requires api-key");
        }
    }

    /**
     * Format checks only: whether a voice exists is never checked at start-up, because that needs
     * the network.
     */
    private void validateGoogleVoiceSelection(TtsProviderConfig provider) {
        String name = provider.getName();
        String language = provider.getLanguage();
        String voice = provider.getVoice();
        if (language != null && !GoogleLanguage.isWellFormed(language)) {
            throw fault("provider '" + name + "' has language '" + language
                    + "', which is not a language-region tag such as uk-UA, cmn-CN or es-419");
        }
        if (voice != null && !GoogleVoiceName.isWellFormed(voice)) {
            throw fault("provider '" + name + "' has voice '" + voice + "', which is neither a full voice "
                    + "name such as uk-UA-Chirp3-HD-Charon nor a short voice name such as Charon");
        }
        String engine = provider.getEngine();
        if (engine != null && GoogleEngine.fromName(engine).isEmpty()) {
            throw fault("provider '" + name + "' has engine '" + engine + "', which is not one of "
                    + GoogleEngine.supportedList());
        }
        GoogleVoiceName parsedVoice = voice == null ? null : GoogleVoiceName.parse(voice);
        boolean fullVoice = parsedVoice instanceof GoogleVoiceName.Full;
        // Every entry needs a default engine for short names, from `engine` or else from a full voice.
        if (engine == null && !fullVoice) {
            throw fault("provider '" + name + "': an engine is required unless voice is a full voice name "
                    + "such as uk-UA-Chirp3-HD-Charon; set engine to one of " + GoogleEngine.supportedList());
        }
        if (parsedVoice instanceof GoogleVoiceName.Short && language == null) {
            throw fault("provider '" + name + "': a language is required for short voice '" + voice + "'");
        }
        if (language != null && parsedVoice instanceof GoogleVoiceName.Full full
                && !full.language().equals(GoogleLanguage.parse(language))) {
            // Not a fault: production's entry has this shape and must start without edits. The voice
            // wins for itself, and the language stays the default for short names.
            log.warn("multiroom-tts: provider '{}' sets language '{}' but voice '{}' is in '{}'; the "
                            + "configured voice is synthesized in its own language, and '{}' remains the "
                            + "default language for short voice names in requests",
                    name, language, voice, full.language(), language);
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
