package multiroom.tts.provider.cloud.google;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.config.ProviderType;
import multiroom.tts.config.TtsProviderConfig;
import multiroom.tts.provider.RequestedSettings;
import multiroom.tts.provider.SynthesisSettings;
import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiSettingsResolverTest {

    private static final int MAX_TEXT_LENGTH = 500;

    private static TtsProviderConfig entry() {
        TtsProviderConfig config = new TtsProviderConfig();
        config.setName("gemini");
        config.setType(ProviderType.GOOGLE_GEMINI);
        config.setServiceAccountKeyFile("sa.json");
        config.setModel("gemini-2.5-flash-tts");
        config.setVoice("Kore");
        config.setLanguage("en-US");
        return config;
    }

    private static RequestedSettings requested(String voice, String language, Double speakingRate) {
        return new RequestedSettings(voice, language, null, null, speakingRate);
    }

    private static RequestedSettings requestedWithPrompt(String stylePrompt) {
        return new RequestedSettings(null, null, null, null, null, stylePrompt);
    }

    private static SynthesisSettings resolve(TtsProviderConfig config, RequestedSettings requested) {
        return new GeminiSettingsResolver(config, MAX_TEXT_LENGTH).resolve(requested);
    }

    private static AbstractThrowableAssert<?, ? extends Throwable> assertInvalidRequest(
            TtsProviderConfig config, RequestedSettings requested) {
        return assertThatThrownBy(() -> resolve(config, requested))
                .isInstanceOf(TtsException.class)
                .satisfies(e -> assertThat(((TtsException) e).getErrorCode()).isEqualTo(TtsErrorCode.INVALID_REQUEST));
    }

    @Test
    void withNoOverridesTheConfiguredDefaultsApply() {
        SynthesisSettings settings = resolve(entry(), requested(null, null, null));

        assertThat(settings.voice()).isEqualTo("Kore");
        assertThat(settings.voiceKey()).isEqualTo("Kore");
        assertThat(settings.requestedVoice()).isEqualTo("Kore");
        assertThat(settings.language()).isEqualTo("en-US");
        assertThat(settings.engine()).isEqualTo("gemini-2.5-flash-tts");
        assertThat(settings.pitch()).isNull();
        assertThat(settings.pitchKey()).isNull();
        assertThat(settings.speakingRate()).isNull();
        assertThat(settings.speakingRateKey()).isNull();
    }

    @Test
    void aRequestVoiceIsNormalizedAndKeepsItsOwnRequestedSpelling() {
        SynthesisSettings settings = resolve(entry(), requested("charon", null, null));

        assertThat(settings.voice()).isEqualTo("Charon");
        assertThat(settings.requestedVoice()).isEqualTo("charon");
    }

    @Test
    void aRequestLanguageIsCanonicalized() {
        SynthesisSettings settings = resolve(entry(), requested(null, "uk-ua", null));

        assertThat(settings.language()).isEqualTo("uk-UA");
    }

    @Test
    void aMalformedRequestVoiceIsACallerErrorQuotingItAsWritten() {
        assertInvalidRequest(entry(), requested("en-US-Kore", null, null))
                .hasMessageContaining("en-US-Kore");
    }

    @Test
    void aMalformedRequestLanguageIsACallerError() {
        assertInvalidRequest(entry(), requested(null, "english", null))
                .hasMessage("Language 'english' is not a language-region tag such as uk-UA, cmn-CN or es-419");
    }

    @Test
    void aRequestRateIsUsedWithItsOwnKey() {
        SynthesisSettings settings = resolve(entry(), requested(null, null, 1.5));

        assertThat(settings.speakingRate()).isEqualTo(1.5);
        assertThat(settings.speakingRateKey()).isEqualTo(1.5);
    }

    @Test
    void theEntrysRateAppliesWhenTheRequestHasNone() {
        TtsProviderConfig config = entry();
        config.setSpeakingRate(1.2);

        SynthesisSettings settings = resolve(config, requested(null, null, null));

        assertThat(settings.speakingRate()).isEqualTo(1.2);
        assertThat(settings.speakingRateKey()).isEqualTo(1.2);
    }

    @Test
    void aNeutralRateIsSentButItsKeyIsNull() {
        SynthesisSettings settings = resolve(entry(), requested(null, null, 1.0));

        assertThat(settings.speakingRate()).isEqualTo(1.0);
        assertThat(settings.speakingRateKey()).isNull();
    }

    @Test
    void aRateOutOfRangeIsACallerError() {
        assertInvalidRequest(entry(), requested(null, null, 3.0))
                .hasMessage("speakingRate 3.0 is outside the allowed range [0.25, 2.0]");
    }

    // --- style prompt ------------------------------------------------------------------------

    private static TtsProviderConfig entryWithDefaultPrompt(String defaultPrompt) {
        TtsProviderConfig config = entry();
        config.setStylePrompt(defaultPrompt);
        return config;
    }

    @Test
    void withNoRequestPromptTheEntrysDefaultAppliesStripped() {
        SynthesisSettings settings = resolve(entryWithDefaultPrompt("  Say this calmly.\n"), requested(null, null, null));

        assertThat(settings.stylePrompt()).isEqualTo("Say this calmly.");
    }

    @Test
    void aRequestPromptIsStrippedAndOverridesTheDefault() {
        SynthesisSettings settings = resolve(entryWithDefaultPrompt("  Say this calmly.\n"),
                requestedWithPrompt(" Announce urgently. "));

        assertThat(settings.stylePrompt()).isEqualTo("Announce urgently.");
    }

    @Test
    void anEmptyOrBlankRequestPromptTurnsTheDefaultOff() {
        assertThat(resolve(entryWithDefaultPrompt("Say this calmly."), requestedWithPrompt("")).stylePrompt()).isNull();
        assertThat(resolve(entryWithDefaultPrompt("Say this calmly."), requestedWithPrompt("   ")).stylePrompt()).isNull();
    }

    @Test
    void withNoDefaultAndNoRequestPromptTheEffectivePromptIsNull() {
        SynthesisSettings settings = resolve(entry(), requested(null, null, null));

        assertThat(settings.stylePrompt()).isNull();
    }

    @Test
    void innerSpacingAndCaseAreKept() {
        SynthesisSettings settings = resolve(entry(), requestedWithPrompt("Say  THIS"));

        assertThat(settings.stylePrompt()).isEqualTo("Say  THIS");
    }

    @Test
    void aRequestPromptAtExactlyTheMaxLengthAfterStrippingIsAccepted() {
        String prompt = "x".repeat(MAX_TEXT_LENGTH);

        SynthesisSettings settings = resolve(entry(), requestedWithPrompt(prompt));

        assertThat(settings.stylePrompt()).isEqualTo(prompt);
    }

    @Test
    void aRequestPromptOneCharacterOverTheMaxLengthAfterStrippingIsACallerError() {
        String prompt = "x".repeat(MAX_TEXT_LENGTH + 1);

        assertInvalidRequest(entry(), requestedWithPrompt(prompt))
                .hasMessage("stylePrompt length " + (MAX_TEXT_LENGTH + 1) + " exceeds maximum allowed length of "
                        + MAX_TEXT_LENGTH);
    }

    @Test
    void aLongPromptThatFitsOnceStrippedIsAccepted() {
        String prompt = " " + "x".repeat(MAX_TEXT_LENGTH) + " ";

        SynthesisSettings settings = resolve(entry(), requestedWithPrompt(prompt));

        assertThat(settings.stylePrompt()).hasSize(MAX_TEXT_LENGTH);
    }

    // --- fields the wrong entry cannot honour ---------------------------------------------------

    @Test
    void aRequestEngineIsRejectedNamingTheFieldAndType() {
        assertInvalidRequest(entry(), new RequestedSettings(null, null, "chirp3-hd", null, null))
                .hasMessage("Field 'engine' is not supported by provider 'gemini' of type GOOGLE_GEMINI");
    }

    @Test
    void aRequestPitchIsRejectedNamingTheFieldAndType() {
        assertInvalidRequest(entry(), new RequestedSettings(null, null, null, 2.0, null))
                .hasMessage("Field 'pitch' is not supported by provider 'gemini' of type GOOGLE_GEMINI");
    }

    @Test
    void aRequestSpeakingRateIsStillAccepted() {
        SynthesisSettings settings = resolve(entry(), requested(null, null, 1.3));

        assertThat(settings.speakingRate()).isEqualTo(1.3);
    }
}
