package multiroom.tts.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import multiroom.tts.provider.cloud.google.GeminiModel;
import multiroom.tts.provider.cloud.google.GeminiVoice;
import multiroom.tts.provider.cloud.google.GoogleEngine;
import multiroom.tts.provider.cloud.google.GoogleLanguage;
import multiroom.tts.provider.cloud.google.GoogleSpeakingRate;
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

        warnIfGeminiIsTheEffectiveDefault();
    }

    /**
     * The effective default is {@code default-provider} when set, or else the first
     * <strong>enabled</strong> entry — the same rule {@code ProviderRegistry} applies. A
     * {@code google-gemini} effective default is allowed, but every announcement without a
     * {@code providerName} then costs Gemini tokens, so start-up warns and continues.
     */
    private void warnIfGeminiIsTheEffectiveDefault() {
        TtsProviderConfig effectiveDefault;
        if (defaultProvider != null && !defaultProvider.isBlank()) {
            effectiveDefault = providers.stream().filter(p -> p.getName().equals(defaultProvider)).findFirst()
                    .orElse(null);
        } else {
            effectiveDefault = providers.stream().filter(TtsProviderConfig::isEnabled).findFirst().orElse(null);
        }
        if (effectiveDefault != null && effectiveDefault.getType() == ProviderType.GOOGLE_GEMINI) {
            log.warn("multiroom-tts: provider '{}' (google-gemini) is the default provider; every announcement "
                    + "without a providerName is synthesized by Gemini and billed per token", effectiveDefault.getName());
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
            case GOOGLE_GEMINI -> validateGoogleGeminiEntry(provider);
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
            // Never accepted and silently ignored: only google-cloud sends pitch.
            rejectSetting(provider, "pitch", provider.getPitch() != null, "google-cloud supports");
        }
        if (provider.getType() != ProviderType.GOOGLE_CLOUD && provider.getType() != ProviderType.GOOGLE_GEMINI) {
            // Speaking rate and the key file are shared by both Google types.
            rejectSetting(provider, "speaking-rate", provider.getSpeakingRate() != null,
                    "google-cloud and google-gemini support");
            rejectSetting(provider, "service-account-key-file", isSet(provider.getServiceAccountKeyFile()),
                    "google-cloud and google-gemini support");
        }
        if (provider.getType() != ProviderType.GOOGLE_GEMINI) {
            // Never accepted and silently ignored: no other type reads them.
            rejectSetting(provider, "style-prompt", isSet(provider.getStylePrompt()), "google-gemini supports");
            rejectSetting(provider, "model", isSet(provider.getModel()), "google-gemini supports");
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
                GoogleSpeakingRate.MIN, GoogleSpeakingRate.MAX);
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

    private static void rejectSetting(TtsProviderConfig provider, String key, boolean set, String supportersClause) {
        if (set) {
            throw fault("provider '" + provider.getName() + "' sets " + key + ", which only " + supportersClause
                    + "; a provider of type " + provider.getType() + " would ignore it");
        }
    }

    /**
     * {@code google-gemini}: a service account key file, model, voice and language are required;
     * an {@code api-key} can never work for Gemini, so it is rejected first, ahead of the missing
     * key-file check, and reports the more useful fault when an entry sets both.
     */
    private void validateGoogleGeminiEntry(TtsProviderConfig provider) {
        if (isSet(provider.getApiKey())) {
            throw fault("provider '" + provider.getName() + "' has an api-key: Gemini voices require a "
                    + "service account key file and cannot use an API key");
        }
        if (!isSet(provider.getServiceAccountKeyFile())) {
            throw fault("provider '" + provider.getName() + "' requires service-account-key-file");
        }
        requireNonBlank(provider, "model", provider.getModel());
        requireNonBlank(provider, "voice", provider.getVoice());
        requireNonBlank(provider, "language", provider.getLanguage());
        if (!GeminiModel.isWellFormed(provider.getModel())) {
            throw fault("provider '" + provider.getName() + "' has model '" + provider.getModel()
                    + "', which is not a well-formed Gemini model name");
        }
        if (!GeminiVoice.isWellFormed(provider.getVoice())) {
            throw fault("provider '" + provider.getName() + "' has voice '" + provider.getVoice()
                    + "', which is not a Gemini voice name: one word of letters, such as Kore");
        }
        if (!GoogleLanguage.isWellFormed(provider.getLanguage())) {
            throw fault("provider '" + provider.getName() + "' has language '" + provider.getLanguage()
                    + "', which is not a language-region tag such as uk-UA, cmn-CN or es-419");
        }
        // engine, pitch and extra-params are never accepted and silently ignored: Gemini has no
        // use for any of them. extra-params gets its own message rather than reusing
        // validateGoogleAudioSettings's, which suggests pitch and speaking-rate.
        if (provider.getEngine() != null) {
            throw fault("provider '" + provider.getName() + "' sets engine, which google-gemini does not support");
        }
        if (provider.getPitch() != null) {
            throw fault("provider '" + provider.getName() + "' sets pitch, which google-gemini does not support");
        }
        if (provider.getExtraParams() != null && !provider.getExtraParams().isEmpty()) {
            throw fault("provider '" + provider.getName() + "' sets extra-params " + provider.getExtraParams().keySet()
                    + ", which google-gemini does not support");
        }
        requireInRange(provider, "speaking-rate", provider.getSpeakingRate(), GoogleSpeakingRate.MIN, GoogleSpeakingRate.MAX);
        if (provider.getStylePrompt() != null) {
            int length = provider.getStylePrompt().strip().length();
            if (length > maxTextLength) {
                throw fault("provider '" + provider.getName() + "' has a style-prompt of length " + length
                        + " after trimming, exceeding the maximum allowed length of " + maxTextLength);
            }
        }
    }

    private static void requireNonBlank(TtsProviderConfig provider, String key, String value) {
        if (value == null || value.isBlank()) {
            throw fault("provider '" + provider.getName() + "' requires " + key);
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
