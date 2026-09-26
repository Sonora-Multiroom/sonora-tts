package multiroom.tts.provider;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.config.ProviderType;
import multiroom.tts.config.TtsProviderConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The 001 resolution rule that OpenAI, Piper and local HTTP keep exactly. */
class DefaultSettingsResolutionTest {

    private static TtsProviderConfig config(String voice, String language, String engine) {
        TtsProviderConfig config = new TtsProviderConfig();
        config.setName("piper-local");
        config.setType(ProviderType.LOCAL_HTTP);
        config.setVoice(voice);
        config.setLanguage(language);
        config.setEngine(engine);
        return config;
    }

    private static RequestedSettings requested(String voice, String language) {
        return new RequestedSettings(voice, language, null, null, null);
    }

    @Test
    void requestVoiceAndLanguageWinOverConfiguredOnes() {
        SynthesisSettings settings = DefaultSettingsResolution.resolve(
                config("ryan", "en-GB", "default"), requested("amy", "fr-FR"));

        assertThat(settings.voice()).isEqualTo("amy");
        assertThat(settings.language()).isEqualTo("fr-FR");
    }

    @Test
    void configuredVoiceAndLanguageApplyWhenTheRequestGivesNone() {
        SynthesisSettings settings = DefaultSettingsResolution.resolve(
                config("ryan", "en-GB", "default"), requested(null, null));

        assertThat(settings.voice()).isEqualTo("ryan");
        assertThat(settings.language()).isEqualTo("en-GB");
    }

    @Test
    void languageFallsBackToEnUsWhenNeitherRequestNorConfigSetsOne() {
        SynthesisSettings settings = DefaultSettingsResolution.resolve(
                config(null, null, null), requested(null, null));

        assertThat(settings.language()).isEqualTo("en-US");
        assertThat(settings.voice()).isNull();
    }

    @Test
    void engineComesFromConfigAndKeysEqualTheValues() {
        SynthesisSettings settings = DefaultSettingsResolution.resolve(
                config("ryan", "en-US", "tts-1"), requested(null, null));

        assertThat(settings.engine()).isEqualTo("tts-1");
        assertThat(settings.voiceKey()).isEqualTo(settings.voice());
        assertThat(settings.requestedVoice()).isEqualTo(settings.voice());
        assertThat(settings.pitch()).isNull();
        assertThat(settings.speakingRate()).isNull();
        assertThat(settings.pitchKey()).isNull();
        assertThat(settings.speakingRateKey()).isNull();
    }

    @Test
    void engineOverrideIsRejectedNamingTheFieldAndProviderType() {
        assertRejected(new RequestedSettings(null, null, "neural2", null, null), "engine");
    }

    @Test
    void pitchOverrideIsRejectedNamingTheFieldAndProviderType() {
        assertRejected(new RequestedSettings(null, null, null, -2.0, null), "pitch");
    }

    @Test
    void speakingRateOverrideIsRejectedNamingTheFieldAndProviderType() {
        assertRejected(new RequestedSettings(null, null, null, null, 0.9), "speakingRate");
    }

    @Test
    void aStylePromptOverrideIsRejectedNamingTheFieldAndProviderType() {
        assertRejected(new RequestedSettings(null, null, null, null, null, "Calm."), "stylePrompt");
    }

    @Test
    void anEmptyStylePromptOverrideIsRejectedToo() {
        assertRejected(new RequestedSettings(null, null, null, null, null, ""), "stylePrompt");
    }

    private static void assertRejected(RequestedSettings requested, String field) {
        assertThatThrownBy(() -> DefaultSettingsResolution.resolve(config("ryan", null, null), requested))
                .isInstanceOf(TtsException.class)
                .hasMessage("Field '" + field + "' is not supported by provider 'piper-local' of type LOCAL_HTTP")
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.INVALID_REQUEST);
    }
}
