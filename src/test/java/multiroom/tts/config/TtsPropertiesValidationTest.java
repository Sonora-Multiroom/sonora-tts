package multiroom.tts.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TtsPropertiesValidationTest {

    @Test
    void abortsWhenNoProvidersConfigured() {
        TtsProperties properties = new TtsProperties();

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one provider");
    }

    @Test
    void abortsWhenApiKeyMissingForCloudProvider() {
        TtsProviderConfig openai = new TtsProviderConfig();
        openai.setName("openai");
        openai.setType(ProviderType.OPENAI);

        TtsProperties properties = new TtsProperties();
        properties.setProviders(List.of(openai));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key");
    }

    @Test
    void abortsWhenPiperModelDoesNotExist() {
        TtsProviderConfig piper = new TtsProviderConfig();
        piper.setName("piper-local");
        piper.setType(ProviderType.PIPER);
        piper.setModelPath("/no/such/model.onnx");

        TtsProperties properties = new TtsProperties();
        properties.setProviders(List.of(piper));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model-path")
                .hasMessageContaining("does not exist");
    }

    @Test
    void abortsWhenPiperModelConfigSidecarIsMissing(@TempDir Path tempDir) throws Exception {
        Path model = tempDir.resolve("model.onnx");
        Files.createFile(model);
        // Deliberately no model.onnx.json alongside it.

        TtsProviderConfig piper = new TtsProviderConfig();
        piper.setName("piper-local");
        piper.setType(ProviderType.PIPER);
        piper.setModelPath(model.toString());

        TtsProperties properties = new TtsProperties();
        properties.setProviders(List.of(piper));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".onnx.json");
    }

    @Test
    void passesWithValidCloudProviderConfiguration() {
        TtsProviderConfig openai = new TtsProviderConfig();
        openai.setName("openai");
        openai.setType(ProviderType.OPENAI);
        openai.setApiKey("sk-test");

        TtsProperties properties = new TtsProperties();
        properties.setProviders(List.of(openai));

        properties.validate();
    }

    @Test
    void passesWhenPiperModelAndConfigSidecarExist(@TempDir Path tempDir) throws Exception {
        Path model = tempDir.resolve("model.onnx");
        Path modelConfig = tempDir.resolve("model.onnx.json");
        Files.createFile(model);
        Files.createFile(modelConfig);

        TtsProviderConfig piper = new TtsProviderConfig();
        piper.setName("piper-local");
        piper.setType(ProviderType.PIPER);
        piper.setModelPath(model.toString());

        TtsProperties properties = new TtsProperties();
        properties.setProviders(List.of(piper));

        properties.validate();
    }

    @Test
    void doesNotValidatePythonExecutableAsAFilesystemPath(@TempDir Path tempDir) throws Exception {
        // pythonExecutable defaults to "python3", a bare PATH-resolved command name, not a
        // literal path — validation must not reject it for "not existing" at cwd, and must
        // never spawn a process to check it either.
        Path model = tempDir.resolve("model.onnx");
        Path modelConfig = tempDir.resolve("model.onnx.json");
        Files.createFile(model);
        Files.createFile(modelConfig);

        TtsProviderConfig piper = new TtsProviderConfig();
        piper.setName("piper-local");
        piper.setType(ProviderType.PIPER);
        piper.setModelPath(model.toString());
        assertThat(piper.getPythonExecutable()).isEqualTo("python3");

        TtsProperties properties = new TtsProperties();
        properties.setProviders(List.of(piper));

        properties.validate();
    }

    @Test
    void doesNotContactAnyNetworkOrProcessDuringValidation() {
        // An API key that is present but wrong, or an endpoint that is present but
        // unreachable, must never be detected here — validation touches configuration
        // and the filesystem only.
        TtsProviderConfig localHttp = new TtsProviderConfig();
        localHttp.setName("local");
        localHttp.setType(ProviderType.LOCAL_HTTP);
        localHttp.setEndpoint("http://127.0.0.1:1/definitely-not-listening");

        TtsProperties properties = new TtsProperties();
        properties.setProviders(List.of(localHttp));

        properties.validate();
    }

    @Test
    void abortsWhenDefaultProviderNameIsUnknown() {
        TtsProviderConfig openai = new TtsProviderConfig();
        openai.setName("openai");
        openai.setType(ProviderType.OPENAI);
        openai.setApiKey("sk-test");

        TtsProperties properties = new TtsProperties();
        properties.setProviders(List.of(openai));
        properties.setDefaultProvider("does-not-exist");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist");
    }

    @Test
    void abortsWhenDefaultProviderIsConfiguredButDisabled() {
        // Only enabled providers are registered, so a disabled default would leave
        // resolveDefault() returning null and fail at request time as a PROVIDER_ERROR
        // naming a provider that is plainly present in the configuration.
        TtsProviderConfig openai = new TtsProviderConfig();
        openai.setName("openai");
        openai.setType(ProviderType.OPENAI);
        openai.setApiKey("sk-test");
        openai.setEnabled(false);

        TtsProviderConfig local = new TtsProviderConfig();
        local.setName("local");
        local.setType(ProviderType.LOCAL_HTTP);
        local.setEndpoint("http://127.0.0.1:5002/api/tts");

        TtsProperties properties = new TtsProperties();
        properties.setProviders(List.of(openai, local));
        properties.setDefaultProvider("openai");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("multiroom-tts")
                .hasMessageContaining("openai")
                .hasMessageContaining("disabled");
    }

    @Test
    void skipsValidationWhenExtensionDisabled() {
        TtsProperties properties = new TtsProperties();
        properties.setEnabled(false);

        properties.validate();

        assertThat(properties.getProviders()).isEmpty();
    }

    // --- 002: google-cloud entries -----------------------------------------------------------

    private static TtsProviderConfig google(String engine, String language, String voice) {
        TtsProviderConfig google = new TtsProviderConfig();
        google.setName("google");
        google.setType(ProviderType.GOOGLE_CLOUD);
        google.setApiKey("key");
        google.setEngine(engine);
        google.setLanguage(language);
        google.setVoice(voice);
        return google;
    }

    private static TtsProperties propertiesWith(TtsProviderConfig... providers) {
        TtsProperties properties = new TtsProperties();
        properties.setProviders(List.of(providers));
        return properties;
    }

    @Test
    void theExactProductionGoogleEntryStartsWithNoNetworkCall() {
        // Production's entry, verbatim: it must start with no edits. There is no
        // network to reach here: validation is a pure configuration check.
        TtsProviderConfig google = google(null, "en-US", "en-US-Neural2-C");
        google.setTimeoutSeconds(10);

        propertiesWith(google).validate();
    }

    @Test
    void aMalformedGoogleLanguageAbortsStartUpNamingEntryAndValue() {
        assertThatThrownBy(() -> propertiesWith(google(null, "english", "en-US-Neural2-C")).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'google'")
                .hasMessageContaining("'english'");
    }

    @Test
    void aMalformedGoogleVoiceAbortsStartUpNamingEntryAndValue() {
        assertThatThrownBy(() -> propertiesWith(google(null, "uk-UA", "uk-UA-Charon")).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'google'")
                .hasMessageContaining("'uk-UA-Charon'");
    }

    @Test
    void aLanguageContradictingAFullVoiceOnlyWarns() {
        propertiesWith(google(null, "en-US", "uk-UA-Chirp3-HD-Charon")).validate();
    }

    @Test
    void aLocalHttpEntryWithNoLanguageStillStarts() {
        TtsProviderConfig localHttp = new TtsProviderConfig();
        localHttp.setName("piper-local");
        localHttp.setType(ProviderType.LOCAL_HTTP);
        localHttp.setEndpoint("http://127.0.0.1:5002/api/tts");

        propertiesWith(localHttp).validate();

        assertThat(localHttp.getLanguage()).isNull();
    }

    @Test
    void us2_6_aShortVoiceWithNoEngineAbortsStartUp() {
        assertThatThrownBy(() -> propertiesWith(google(null, "uk-UA", "charon")).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'google'")
                .hasMessageContaining("an engine is required");
    }

    @Test
    void anEntryWithNeitherVoiceNorEngineAbortsStartUp() {
        assertThatThrownBy(() -> propertiesWith(google(null, "en-US", null)).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'google'")
                .hasMessageContaining("an engine is required");
    }

    @Test
    void anUnrecognizedEngineAbortsStartUpListingTheSupportedOnes() {
        assertThatThrownBy(() -> propertiesWith(google("chirp4", "en-US", null)).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'google'")
                .hasMessageContaining("'chirp4'")
                .hasMessageContaining("Standard, Wavenet, Neural2, Studio, Chirp-HD, Chirp3-HD");
    }

    @Test
    void aShortVoiceWithNoLanguageAbortsStartUp() {
        assertThatThrownBy(() -> propertiesWith(google("chirp3-hd", null, "charon")).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'google'")
                .hasMessageContaining("a language is required for short voice 'charon'");
    }

    @Test
    void us2_5_theProductionShapeWithNoEngineStarts() {
        propertiesWith(google(null, "en-US", "en-US-Neural2-C")).validate();
    }

    @Test
    void aFullVoiceAloneStarts() {
        propertiesWith(google(null, null, "uk-UA-Chirp3-HD-Charon")).validate();
    }

    @Test
    void anEngineAndLanguageWithNoVoiceStart() {
        propertiesWith(google("WaveNet", "en-US", null)).validate();
    }

    @Test
    void theStructuredShapeStarts() {
        propertiesWith(google("chirp3-hd", "uk-UA", "charon")).validate();
    }

    @Test
    void nonPositiveCatalogueTimingsAbortStartUp() {
        for (String field : List.of("ttl", "failure-backoff", "fetch-timeout")) {
            for (java.time.Duration value : List.of(java.time.Duration.ZERO, java.time.Duration.ofSeconds(-1))) {
                TtsProperties properties = propertiesWith(google(null, "en-US", "en-US-Neural2-C"));
                VoiceCatalogueProperties catalogue = properties.getVoiceCatalogue();
                switch (field) {
                    case "ttl" -> catalogue.setTtl(value);
                    case "failure-backoff" -> catalogue.setFailureBackoff(value);
                    default -> catalogue.setFetchTimeout(value);
                }

                assertThatThrownBy(properties::validate)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageStartingWith("multiroom-tts: ")
                        .hasMessageContaining("voice-catalogue." + field);
            }
        }
    }

    @Test
    void theCatalogueDefaultsArePositive() {
        VoiceCatalogueProperties catalogue = new VoiceCatalogueProperties();

        assertThat(catalogue.getTtl()).isEqualTo(java.time.Duration.ofHours(24));
        assertThat(catalogue.getFailureBackoff()).isEqualTo(java.time.Duration.ofSeconds(60));
        assertThat(catalogue.getFetchTimeout()).isEqualTo(java.time.Duration.ofSeconds(3));
    }

    @Test
    void aGoogleSpeakingRateOutOfRangeAbortsStartUpStatingTheRange() {
        TtsProviderConfig google = google(null, "en-US", "en-US-Neural2-C");
        google.setSpeakingRate(2.5);

        assertThatThrownBy(() -> propertiesWith(google).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'google'")
                .hasMessageContaining("2.5")
                .hasMessageContaining("[0.25, 2.0]");
    }

    @Test
    void aGooglePitchOutOfRangeAbortsStartUpStatingTheRange() {
        TtsProviderConfig google = google(null, "en-US", "en-US-Neural2-C");
        google.setPitch(25.0);

        assertThatThrownBy(() -> propertiesWith(google).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'google'")
                .hasMessageContaining("25.0")
                .hasMessageContaining("[-20.0, 20.0]");
    }

    @Test
    void aNaNGooglePitchAbortsStartUpStatingTheRange() {
        TtsProviderConfig google = google(null, "en-US", "en-US-Neural2-C");
        google.setPitch(Double.NaN);

        assertThatThrownBy(() -> propertiesWith(google).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'google'")
                .hasMessageContaining("pitch NaN")
                .hasMessageContaining("[-20.0, 20.0]");
    }

    @Test
    void aNaNGoogleSpeakingRateAbortsStartUpStatingTheRange() {
        TtsProviderConfig google = google(null, "en-US", "en-US-Neural2-C");
        google.setSpeakingRate(Double.NaN);

        assertThatThrownBy(() -> propertiesWith(google).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'google'")
                .hasMessageContaining("speaking-rate NaN")
                .hasMessageContaining("[0.25, 2.0]");
    }

    @Test
    void googleExtraParamsAbortStartUpPointingAtTheTypedSettings() {
        TtsProviderConfig google = google(null, "en-US", "en-US-Neural2-C");
        google.getExtraParams().put("speaking_rate", "1.1");

        assertThatThrownBy(() -> propertiesWith(google).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'google'")
                .hasMessageContaining("speaking_rate")
                .hasMessageContaining("pitch")
                .hasMessageContaining("speaking-rate");
    }

    @Test
    void sc008_pitchOnAnotherProviderTypeAbortsStartUp() {
        TtsProviderConfig openai = new TtsProviderConfig();
        openai.setName("openai");
        openai.setType(ProviderType.OPENAI);
        openai.setApiKey("sk-test");
        openai.setPitch(1.0);

        assertThatThrownBy(() -> propertiesWith(openai).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'openai'")
                .hasMessageContaining("pitch")
                .hasMessageContaining("OPENAI");
    }

    @Test
    void sc008_speakingRateOnAnotherProviderTypeAbortsStartUp() {
        TtsProviderConfig localHttp = new TtsProviderConfig();
        localHttp.setName("piper-local");
        localHttp.setType(ProviderType.LOCAL_HTTP);
        localHttp.setEndpoint("http://127.0.0.1:5002/api/tts");
        localHttp.setSpeakingRate(1.1);

        assertThatThrownBy(() -> propertiesWith(localHttp).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("speaking-rate")
                .hasMessageContaining("LOCAL_HTTP");
    }

    @Test
    void inRangeGooglePitchAndRateStart() {
        TtsProviderConfig google = google(null, "en-US", "en-US-Neural2-C");
        google.setSpeakingRate(1.1);
        google.setPitch(-2.0);

        propertiesWith(google).validate();
    }

    // --- 003: exactly one google-cloud credential ---------------------------------------------

    private static TtsProviderConfig googleWith(String apiKey, String keyFile) {
        TtsProviderConfig google = google(null, "en-US", "en-US-Neural2-C");
        google.setApiKey(apiKey);
        google.setServiceAccountKeyFile(keyFile);
        return google;
    }

    @Test
    void aGoogleEntryWithNeitherCredentialAbortsStartUp() {
        assertThatThrownBy(() -> propertiesWith(googleWith(null, null)).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("multiroom-tts: provider 'google' requires exactly one of api-key and "
                        + "service-account-key-file");
    }

    @Test
    void aGoogleEntryWithBothCredentialsAbortsStartUp() {
        assertThatThrownBy(() -> propertiesWith(googleWith("key", "/etc/sa.json")).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("multiroom-tts: provider 'google' requires exactly one of api-key and "
                        + "service-account-key-file, not both");
    }

    @Test
    void aBlankApiKeyBesideAKeyFileCountsAsUnset() {
        // Validation checks the shape only: the file is read when the provider is built.
        propertiesWith(googleWith("  ", "/does/not/matter.json")).validate();
    }

    @Test
    void aBlankKeyFileBesideNoApiKeyCountsAsUnset() {
        assertThatThrownBy(() -> propertiesWith(googleWith(null, " ")).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires exactly one of api-key and service-account-key-file");
    }

    @Test
    void aPre003GoogleEntryWithOnlyAnApiKeyStillStarts() {
        propertiesWith(googleWith("key", null)).validate();
    }

    @Test
    void openAiStillRequiresAnApiKey() {
        TtsProviderConfig openai = new TtsProviderConfig();
        openai.setName("openai");
        openai.setType(ProviderType.OPENAI);

        assertThatThrownBy(() -> propertiesWith(openai).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("multiroom-tts: provider 'openai' requires api-key");
    }

    @Test
    void aKeyFileOnAnyOtherProviderTypeAbortsStartUp(@TempDir Path tempDir) throws Exception {
        TtsProviderConfig openai = new TtsProviderConfig();
        openai.setName("openai");
        openai.setType(ProviderType.OPENAI);
        openai.setApiKey("sk-test");

        Path model = Files.createFile(tempDir.resolve("model.onnx"));
        Files.createFile(tempDir.resolve("model.onnx.json"));
        TtsProviderConfig piper = new TtsProviderConfig();
        piper.setName("piper");
        piper.setType(ProviderType.PIPER);
        piper.setModelPath(model.toString());

        TtsProviderConfig localHttp = new TtsProviderConfig();
        localHttp.setName("local");
        localHttp.setType(ProviderType.LOCAL_HTTP);
        localHttp.setEndpoint("http://127.0.0.1:5002/api/tts");

        for (TtsProviderConfig provider : List.of(openai, piper, localHttp)) {
            provider.setServiceAccountKeyFile("/etc/sa.json");

            assertThatThrownBy(() -> propertiesWith(provider).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("multiroom-tts: provider '" + provider.getName() + "'")
                    .hasMessageContaining("service-account-key-file")
                    .hasMessageContaining(provider.getType().name());
        }
    }

    // --- 004: google-gemini entries ------------------------------------------------------------

    private static TtsProviderConfig gemini(String model, String voice, String language) {
        TtsProviderConfig config = new TtsProviderConfig();
        config.setName("gemini");
        config.setType(ProviderType.GOOGLE_GEMINI);
        config.setServiceAccountKeyFile("sa.json");
        config.setModel(model);
        config.setVoice(voice);
        config.setLanguage(language);
        return config;
    }

    @Test
    void aMinimalValidGeminiEntryValidates() {
        propertiesWith(gemini("gemini-2.5-flash-tts", "Kore", "en-US")).validate();
    }

    @Test
    void aGeminiEntryWithSpeakingRateValidatesWithoutTrippingTheGoogleCloudOnlyRule() {
        TtsProviderConfig gemini = gemini("gemini-2.5-flash-tts", "Kore", "en-US");
        gemini.setSpeakingRate(1.2);

        propertiesWith(gemini).validate();
    }

    @Test
    void aGeminiEntryWithServiceAccountKeyFileValidatesWithoutTrippingTheGoogleCloudOnlyRule() {
        // service-account-key-file is set by gemini() itself; this asserts the entry starts,
        // proving the non-google-cloud guard no longer rejects it for google-gemini.
        propertiesWith(gemini("gemini-2.5-flash-tts", "Kore", "en-US")).validate();
    }

    @Test
    void aStylePromptWhoseStrippedLengthIsAtMostMaxTextLengthValidates() {
        TtsProperties properties = propertiesWith(gemini("gemini-2.5-flash-tts", "Kore", "en-US"));
        // Surrounding whitespace pushes the raw value over the limit, but the stripped length fits.
        properties.getProviders().get(0).setStylePrompt(" " + "x".repeat(properties.getMaxTextLength()) + " ");

        properties.validate();
    }

    @Test
    void aStylePromptLongerThanMaxTextLengthAfterStrippingAbortsStartUp() {
        TtsProperties properties = propertiesWith(gemini("gemini-2.5-flash-tts", "Kore", "en-US"));
        properties.getProviders().get(0).setStylePrompt("x".repeat(properties.getMaxTextLength() + 1));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'gemini'")
                .hasMessageContaining(String.valueOf(properties.getMaxTextLength() + 1))
                .hasMessageContaining(String.valueOf(properties.getMaxTextLength()));
    }

    // --- 004: google-gemini start-up faults ----------------------------------------------------

    @Test
    void anApiKeyWithAKeyFileAbortsStartUpBeforeTheMissingKeyFileCheck() {
        TtsProviderConfig config = gemini("gemini-2.5-flash-tts", "Kore", "en-US");
        config.setApiKey("test-key");

        assertThatThrownBy(() -> propertiesWith(config).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'gemini'")
                .hasMessageContaining("service account key file")
                .hasMessageContaining("cannot use an API key");
    }

    @Test
    void anApiKeyAloneAbortsStartUp() {
        TtsProviderConfig config = gemini("gemini-2.5-flash-tts", "Kore", "en-US");
        config.setApiKey("test-key");
        config.setServiceAccountKeyFile(null);

        assertThatThrownBy(() -> propertiesWith(config).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'gemini'")
                .hasMessageContaining("service account key file")
                .hasMessageContaining("cannot use an API key");
    }

    @Test
    void noKeyFileAbortsStartUp() {
        TtsProviderConfig config = gemini("gemini-2.5-flash-tts", "Kore", "en-US");
        config.setServiceAccountKeyFile(null);

        assertThatThrownBy(() -> propertiesWith(config).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'gemini'")
                .hasMessageContaining("service-account-key-file");
    }

    @Test
    void aMissingModelAbortsStartUpNamingTheSetting() {
        assertThatThrownBy(() -> propertiesWith(gemini(null, "Kore", "en-US")).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'gemini'")
                .hasMessageContaining("model");
    }

    @Test
    void aMissingVoiceAbortsStartUpNamingTheSetting() {
        assertThatThrownBy(() -> propertiesWith(gemini("gemini-2.5-flash-tts", null, "en-US")).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'gemini'")
                .hasMessageContaining("voice");
    }

    @Test
    void aMissingLanguageAbortsStartUpNamingTheSetting() {
        assertThatThrownBy(() -> propertiesWith(gemini("gemini-2.5-flash-tts", "Kore", null)).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'gemini'")
                .hasMessageContaining("language");
    }

    @Test
    void aMalformedModelAbortsStartUpNamingTheKeyAndValue() {
        assertThatThrownBy(() -> propertiesWith(gemini("Gemini-2.5", "Kore", "en-US")).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'gemini'")
                .hasMessageContaining("model")
                .hasMessageContaining("Gemini-2.5");
    }

    @Test
    void aMalformedVoiceAbortsStartUpNamingTheKeyAndValue() {
        assertThatThrownBy(() -> propertiesWith(gemini("gemini-2.5-flash-tts", "en-US-Kore", "en-US")).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'gemini'")
                .hasMessageContaining("voice")
                .hasMessageContaining("en-US-Kore");
    }

    @Test
    void aMalformedLanguageAbortsStartUpNamingTheKeyAndValue() {
        assertThatThrownBy(() -> propertiesWith(gemini("gemini-2.5-flash-tts", "Kore", "english")).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'gemini'")
                .hasMessageContaining("language")
                .hasMessageContaining("english");
    }

    @Test
    void anEngineOnAGeminiEntryAbortsStartUpNamingTheKey() {
        TtsProviderConfig config = gemini("gemini-2.5-flash-tts", "Kore", "en-US");
        config.setEngine("chirp3-hd");

        assertThatThrownBy(() -> propertiesWith(config).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'gemini'")
                .hasMessageContaining("engine");
    }

    @Test
    void aPitchOnAGeminiEntryAbortsStartUpNamingTheKey() {
        TtsProviderConfig config = gemini("gemini-2.5-flash-tts", "Kore", "en-US");
        config.setPitch(2.0);

        assertThatThrownBy(() -> propertiesWith(config).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'gemini'")
                .hasMessageContaining("pitch");
    }

    @Test
    void nonEmptyExtraParamsOnAGeminiEntryAbortsStartUpNamingTheKey() {
        TtsProviderConfig config = gemini("gemini-2.5-flash-tts", "Kore", "en-US");
        config.getExtraParams().put("foo", "bar");

        assertThatThrownBy(() -> propertiesWith(config).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'gemini'")
                .hasMessageContaining("extra-params");
    }

    @Test
    void aGeminiSpeakingRateOutOfRangeAbortsStartUp() {
        TtsProviderConfig config = gemini("gemini-2.5-flash-tts", "Kore", "en-US");
        config.setSpeakingRate(2.5);

        assertThatThrownBy(() -> propertiesWith(config).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'gemini'")
                .hasMessageContaining("2.5")
                .hasMessageContaining("[0.25, 2.0]");
    }

    @Test
    void aNaNGeminiSpeakingRateAbortsStartUp() {
        TtsProviderConfig config = gemini("gemini-2.5-flash-tts", "Kore", "en-US");
        config.setSpeakingRate(Double.NaN);

        assertThatThrownBy(() -> propertiesWith(config).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("multiroom-tts: provider 'gemini'")
                .hasMessageContaining("speaking-rate NaN")
                .hasMessageContaining("[0.25, 2.0]");
    }

    @Test
    void aStylePromptOnGoogleCloudOpenAiOrLocalHttpAbortsStartUp() {
        TtsProviderConfig googleCloud = google(null, "en-US", "en-US-Neural2-C");
        googleCloud.setStylePrompt("hi");

        TtsProviderConfig openai = new TtsProviderConfig();
        openai.setName("openai");
        openai.setType(ProviderType.OPENAI);
        openai.setApiKey("sk-test");
        openai.setStylePrompt("hi");

        TtsProviderConfig localHttp = new TtsProviderConfig();
        localHttp.setName("local");
        localHttp.setType(ProviderType.LOCAL_HTTP);
        localHttp.setEndpoint("http://127.0.0.1:5002/api/tts");
        localHttp.setStylePrompt("hi");

        for (TtsProviderConfig provider : List.of(googleCloud, openai, localHttp)) {
            assertThatThrownBy(() -> propertiesWith(provider).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("multiroom-tts: provider '" + provider.getName() + "'")
                    .hasMessageContaining("style-prompt")
                    .hasMessageContaining("google-gemini");
        }
    }

    @Test
    void aModelOnGoogleCloudOrOpenAiAbortsStartUp() {
        TtsProviderConfig googleCloud = google(null, "en-US", "en-US-Neural2-C");
        googleCloud.setModel("gemini-2.5-flash-tts");

        TtsProviderConfig openai = new TtsProviderConfig();
        openai.setName("openai");
        openai.setType(ProviderType.OPENAI);
        openai.setApiKey("sk-test");
        openai.setModel("gemini-2.5-flash-tts");

        for (TtsProviderConfig provider : List.of(googleCloud, openai)) {
            assertThatThrownBy(() -> propertiesWith(provider).validate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("multiroom-tts: provider '" + provider.getName() + "'")
                    .hasMessageContaining("model")
                    .hasMessageContaining("google-gemini");
        }
    }

    @Test
    void productionsExactGoogleCloudEntryStillValidatesBesideTheGeminiRules() {
        TtsProviderConfig google = google(null, "en-US", "en-US-Neural2-C");
        google.setTimeoutSeconds(10);

        propertiesWith(google).validate();
    }

    // --- 004: the default-provider cost warning ------------------------------------------------

    private java.util.List<String> captureWarnings(Runnable action) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("multiroom.tts");
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            action.run();
        } finally {
            logger.detachAppender(logs);
        }
        return logs.list.stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("billed per token"))
                .toList();
    }

    @Test
    void anExplicitGeminiDefaultProviderWarnsExactlyOnce() {
        TtsProperties properties = propertiesWith(gemini("gemini-2.5-flash-tts", "Kore", "en-US"));
        properties.setDefaultProvider("gemini");

        java.util.List<String> warnings = captureWarnings(properties::validate);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("gemini").contains("billed per token");
    }

    @Test
    void theFirstEnabledEntryBeingGeminiWithNoNamedDefaultWarns() {
        TtsProviderConfig disabledClassic = google(null, "en-US", "en-US-Neural2-C");
        disabledClassic.setName("google");
        disabledClassic.setEnabled(false);
        TtsProviderConfig geminiEntry = gemini("gemini-2.5-flash-tts", "Kore", "en-US");
        TtsProperties properties = propertiesWith(disabledClassic, geminiEntry);

        java.util.List<String> warnings = captureWarnings(properties::validate);

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("gemini");
    }

    @Test
    void noWarningWhenTheEffectiveDefaultIsNotGemini() {
        TtsProperties properties = propertiesWith(google(null, "en-US", "en-US-Neural2-C"),
                gemini("gemini-2.5-flash-tts", "Kore", "en-US"));

        java.util.List<String> warnings = captureWarnings(properties::validate);

        assertThat(warnings).isEmpty();
    }
}
