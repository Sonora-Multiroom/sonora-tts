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

class GoogleVoiceResolverTest {

    private static TtsProviderConfig entry(String engine, String language, String voice) {
        TtsProviderConfig config = new TtsProviderConfig();
        config.setName("google");
        config.setType(ProviderType.GOOGLE_CLOUD);
        config.setApiKey("key");
        config.setEngine(engine);
        config.setLanguage(language);
        config.setVoice(voice);
        return config;
    }

    private static RequestedSettings voice(String voice) {
        return new RequestedSettings(voice, null, null, null, null);
    }

    private static RequestedSettings voiceAndLanguage(String voice, String language) {
        return new RequestedSettings(voice, language, null, null, null);
    }

    private static SynthesisSettings resolve(TtsProviderConfig entry, RequestedSettings requested) {
        return new GoogleVoiceResolver(entry).resolve(requested);
    }

    private static AbstractThrowableAssert<?, ? extends Throwable> assertInvalidRequest(
            TtsProviderConfig entry, RequestedSettings requested) {
        return assertThatThrownBy(() -> resolve(entry, requested))
                .isInstanceOf(TtsException.class)
                .satisfies(e -> assertThat(((TtsException) e).getErrorCode()).isEqualTo(TtsErrorCode.INVALID_REQUEST));
    }

    // --- a full name carries its own language ------------------------------------------------

    @Test
    void us1_1_fullVoiceWithNoLanguageAnywhereTakesTheVoicesLanguage() {
        SynthesisSettings settings = resolve(entry(null, null, "en-US-Neural2-C"), voice("uk-UA-Chirp3-HD-Charon"));

        assertThat(settings.language()).isEqualTo("uk-UA");
        assertThat(settings.voice()).isEqualTo("uk-UA-Chirp3-HD-Charon");
        assertThat(settings.requestedVoice()).isEqualTo("uk-UA-Chirp3-HD-Charon");
        assertThat(settings.voiceKey()).isEqualTo("uk-ua-chirp3-hd-charon");
    }

    @Test
    void requestedVoiceIsTheCallersSpellingAsSent() {
        SynthesisSettings settings = resolve(entry(null, null, "en-US-Neural2-C"), voice("uk-ua-chirp3-hd-charon"));

        assertThat(settings.voice()).isEqualTo("uk-UA-Chirp3-HD-Charon");
        assertThat(settings.requestedVoice()).isEqualTo("uk-ua-chirp3-hd-charon");
        assertThat(settings.voiceKey()).isEqualTo("uk-ua-chirp3-hd-charon");
    }

    @Test
    void us1_2_anEntryLanguageDoesNotOverrideAFullVoicesLanguage() {
        SynthesisSettings settings = resolve(entry(null, "en-US", "en-US-Neural2-C"), voice("uk-UA-Chirp3-HD-Charon"));

        assertThat(settings.language()).isEqualTo("uk-UA");
    }

    @Test
    void us1_3_aRequestLanguageContradictingTheVoiceIsACallerError() {
        assertInvalidRequest(entry(null, "en-US", "en-US-Neural2-C"), voiceAndLanguage("uk-UA-Chirp3-HD-Charon", "en-US"))
                .hasMessage("Language 'en-US' contradicts voice 'uk-UA-Chirp3-HD-Charon', whose language is 'uk-UA'");
    }

    @Test
    void aRequestLanguageInAnotherCaseIsNoContradiction() {
        SynthesisSettings settings = resolve(entry(null, null, "en-US-Neural2-C"),
                voiceAndLanguage("uk-UA-Chirp3-HD-Charon", "uk-ua"));

        assertThat(settings.language()).isEqualTo("uk-UA");
    }

    @Test
    void withNoVoiceAnywhereTheLanguageIsRequestThenConfigThenEnUs() {
        assertThat(resolve(entry("wavenet", "de-DE", null), voiceAndLanguage(null, "fr-fr")).language()).isEqualTo("fr-FR");
        assertThat(resolve(entry("wavenet", "de-de", null), voice(null)).language()).isEqualTo("de-DE");
        assertThat(resolve(entry("wavenet", null, null), voice(null)).language()).isEqualTo("en-US");

        SynthesisSettings settings = resolve(entry("wavenet", null, null), voice(null));
        assertThat(settings.voice()).isNull();
        assertThat(settings.voiceKey()).isNull();
        assertThat(settings.requestedVoice()).isNull();
    }

    @Test
    void theConfiguredFullVoiceIsUsedWhenTheRequestNamesNone() {
        SynthesisSettings settings = resolve(entry(null, "en-US", "en-US-Neural2-C"), voice(null));

        assertThat(settings.voice()).isEqualTo("en-US-Neural2-C");
        assertThat(settings.language()).isEqualTo("en-US");
        assertThat(settings.requestedVoice()).isEqualTo("en-US-Neural2-C");
    }

    @Test
    void aRequestLanguageContradictingTheConfiguredFullVoiceIsACallerError() {
        assertInvalidRequest(entry(null, null, "en-US-Neural2-C"), voiceAndLanguage(null, "uk-UA"))
                .hasMessageContaining("uk-UA")
                .hasMessageContaining("en-US-Neural2-C");
    }

    @Test
    void aMalformedRequestVoiceIsACallerError() {
        assertInvalidRequest(entry(null, null, "en-US-Neural2-C"), voice("uk-UA-Charon"))
                .hasMessageContaining("uk-UA-Charon");
    }

    @Test
    void aMalformedRequestLanguageIsACallerError() {
        assertInvalidRequest(entry(null, null, "en-US-Neural2-C"), voiceAndLanguage(null, "ukrainian"))
                .hasMessage("Language 'ukrainian' is not a language-region tag such as uk-UA, cmn-CN or es-419");
    }

    @Test
    void theEngineIsAlwaysNullBecauseTheFullNameEncodesIt() {
        assertThat(resolve(entry(null, null, "en-US-Neural2-C"), voice("uk-UA-Chirp3-HD-Charon")).engine()).isNull();
        assertThat(resolve(entry("wavenet", "en-US", null), voice(null)).engine()).isNull();
    }

    @Test
    void anUnrecognizedEngineInAFullNameIsAccepted() {
        SynthesisSettings settings = resolve(entry(null, null, "en-US-Neural2-C"), voice("en-us-news-k"));

        assertThat(settings.voice()).isEqualTo("en-US-news-K");
        assertThat(settings.language()).isEqualTo("en-US");
    }

    // --- short names, engines and default engine/language ------------------------------------

    private static RequestedSettings voiceAndEngine(String voice, String engine) {
        return new RequestedSettings(voice, null, engine, null, null);
    }

    @Test
    void us2_1_aStructuredEntryComposesItsConfiguredShortVoice() {
        SynthesisSettings settings = resolve(entry("chirp3-hd", "uk-UA", "charon"), voice(null));

        assertThat(settings.voice()).isEqualTo("uk-UA-Chirp3-HD-Charon");
        assertThat(settings.language()).isEqualTo("uk-UA");
        assertThat(settings.requestedVoice()).isEqualTo("charon");
    }

    @Test
    void us2_2_aShortRequestVoiceUsesTheEntrysEngineAndLanguage() {
        SynthesisSettings settings = resolve(entry("chirp3-hd", "uk-UA", "charon"), voice("Puck"));

        assertThat(settings.voice()).isEqualTo("uk-UA-Chirp3-HD-Puck");
        assertThat(settings.requestedVoice()).isEqualTo("Puck");
    }

    @Test
    void us2_3_aSingleLetterVoiceComposesWithTheEntrysEngine() {
        assertThat(resolve(entry("wavenet", "en-US", null), voice("d")).voice()).isEqualTo("en-US-Wavenet-D");
    }

    @Test
    void us2_7_aRequestEngineOverridesTheDefaultEngine() {
        SynthesisSettings settings = resolve(entry("chirp3-hd", "en-US", null), voiceAndEngine("c", "neural2"));

        assertThat(settings.voice()).isEqualTo("en-US-Neural2-C");
    }

    @Test
    void us3_5_aRequestedEngineAndShortVoiceUseTheEntrysLanguage() {
        assertThat(resolve(entry("chirp3-hd", "de-DE", null), voiceAndEngine("D", "wavenet")).voice())
                .isEqualTo("de-DE-Wavenet-D");
    }

    @Test
    void shortNamesMatchRegardlessOfCase() {
        SynthesisSettings upper = resolve(entry("chirp3-hd", "uk-UA", null), voice("CHARON"));
        SynthesisSettings lower = resolve(entry("chirp3-hd", "uk-UA", null), voice("charon"));

        assertThat(lower.voice()).isEqualTo("uk-UA-Chirp3-HD-Charon");
        assertThat(upper.voice()).isEqualToIgnoringCase("uk-UA-Chirp3-HD-Charon");
        assertThat(upper.voiceKey()).isEqualTo(lower.voiceKey());
    }

    @Test
    void aRequestEngineContradictingARequestFullVoiceIsACallerError() {
        assertInvalidRequest(entry(null, null, "en-US-Neural2-C"), voiceAndEngine("uk-UA-Chirp3-HD-Charon", "wavenet"))
                .hasMessage("Engine 'Wavenet' contradicts voice 'uk-UA-Chirp3-HD-Charon', whose engine is 'Chirp3-HD'");
    }

    @Test
    void aRequestEngineMatchingAFullVoiceIsAccepted() {
        assertThat(resolve(entry(null, null, "en-US-Neural2-C"), voiceAndEngine("uk-UA-Chirp3-HD-Charon", "CHIRP3_HD"))
                .voice()).isEqualTo("uk-UA-Chirp3-HD-Charon");
    }

    @Test
    void aRequestEngineContradictingTheConfiguredFullVoiceIsACallerError() {
        assertInvalidRequest(entry(null, null, "en-US-Neural2-C"), voiceAndEngine(null, "wavenet"))
                .hasMessageContaining("Wavenet")
                .hasMessageContaining("Neural2");
    }

    @Test
    void aRequestEngineContradictingAnUnrecognizedFullVoiceEngineIsACallerError() {
        assertInvalidRequest(entry(null, null, "en-US-Neural2-C"), voiceAndEngine("en-US-Polyglot-1", "neural2"))
                .hasMessageContaining("Polyglot");
    }

    @Test
    void anUnknownRequestEngineListsTheSupportedOnes() {
        assertInvalidRequest(entry("chirp3-hd", "uk-UA", "charon"), voiceAndEngine(null, "chirp4"))
                .hasMessage("Unknown engine 'chirp4'. Supported: Standard, Wavenet, Neural2, Studio, Chirp-HD, Chirp3-HD");
    }

    @Test
    void aRequestEngineWithNoVoiceAnywhereIsACallerError() {
        assertInvalidRequest(entry("wavenet", "en-US", null), voiceAndEngine(null, "neural2"))
                .hasMessageContaining("needs a voice");
    }

    @Test
    void aShortRequestVoiceWithNoLanguageAnywhereIsACallerError() {
        assertInvalidRequest(entry("wavenet", null, null), voice("d"))
                .hasMessageContaining("language")
                .hasMessageContaining("'d'");
    }

    @Test
    void aShortRequestVoiceWithARequestLanguageComposesInThatLanguage() {
        assertThat(resolve(entry("wavenet", null, null), voiceAndLanguage("d", "fr-fr")).voice())
                .isEqualTo("fr-FR-Wavenet-D");
    }

    @Test
    void anUnrecognizedDefaultEngineCannotComposeShortNames() {
        TtsProviderConfig polyglot = entry(null, null, "en-US-Polyglot-1");

        assertInvalidRequest(polyglot, voice("c"))
                .hasMessageContaining("Polyglot")
                .hasMessageContaining("google");
        SynthesisSettings settings = resolve(polyglot, voiceAndEngine("c", "neural2"));
        assertThat(settings.voice()).isEqualTo("en-US-Neural2-C");
        assertThat(settings.language()).isEqualTo("en-US");
    }

    @Test
    void us2_8_theDefaultEngineAndLanguageComeFromAConfiguredFullVoice() {
        SynthesisSettings settings = resolve(entry(null, null, "uk-UA-Chirp3-HD-Charon"), voice("puck"));

        assertThat(settings.voice()).isEqualTo("uk-UA-Chirp3-HD-Puck");
        assertThat(settings.language()).isEqualTo("uk-UA");
    }

    @Test
    void theConfiguredLanguageIsTheDefaultForShortNamesEvenBesideAFullVoiceInAnotherLanguage() {
        TtsProviderConfig mixed = entry(null, "en-US", "uk-UA-Chirp3-HD-Charon");

        SynthesisSettings configured = resolve(mixed, voice(null));
        assertThat(configured.voice()).isEqualTo("uk-UA-Chirp3-HD-Charon");
        assertThat(configured.language()).isEqualTo("uk-UA");

        assertThat(resolve(mixed, voice("puck")).voice()).isEqualTo("en-US-Chirp3-HD-Puck");
    }

    @Test
    void aConfiguredFullVoiceWinsOverAConfiguredEngineThatDisagrees() {
        TtsProviderConfig disagreeing = entry("wavenet", null, "uk-UA-Chirp3-HD-Charon");

        assertThat(resolve(disagreeing, voice(null)).voice()).isEqualTo("uk-UA-Chirp3-HD-Charon");
        assertThat(resolve(disagreeing, voice("d")).voice()).isEqualTo("uk-UA-Wavenet-D");
    }

    @Test
    void us2_4_aFullNameAndTheEquivalentShortNameShareAVoiceKey() {
        SynthesisSettings full = resolve(entry(null, "en-US", "en-US-Neural2-C"), voice("uk-ua-chirp3-hd-charon"));
        SynthesisSettings composed = resolve(entry("chirp3-hd", "uk-UA", null), voice("Charon"));

        assertThat(composed.voiceKey()).isEqualTo(full.voiceKey());
        assertThat(composed.language()).isEqualTo(full.language());
    }

    // --- pitch and speaking rate -------------------------------------------------------------

    private static TtsProviderConfig tuned(Double pitch, Double speakingRate) {
        TtsProviderConfig config = entry(null, "en-US", "en-US-Neural2-C");
        config.setPitch(pitch);
        config.setSpeakingRate(speakingRate);
        return config;
    }

    private static RequestedSettings audio(Double pitch, Double speakingRate) {
        return new RequestedSettings(null, null, null, pitch, speakingRate);
    }

    @Test
    void us4_1_theEntrysPitchAndRateAreResolved() {
        SynthesisSettings settings = resolve(tuned(-2.0, 1.2), audio(null, null));

        assertThat(settings.pitch()).isEqualTo(-2.0);
        assertThat(settings.speakingRate()).isEqualTo(1.2);
        assertThat(settings.pitchKey()).isEqualTo(-2.0);
        assertThat(settings.speakingRateKey()).isEqualTo(1.2);
    }

    @Test
    void us4_2_noPitchOrRateAnywhereResolvesToNull() {
        SynthesisSettings settings = resolve(tuned(null, null), audio(null, null));

        assertThat(settings.pitch()).isNull();
        assertThat(settings.speakingRate()).isNull();
        assertThat(settings.pitchKey()).isNull();
        assertThat(settings.speakingRateKey()).isNull();
    }

    @Test
    void us4_3_aRequestRateOverridesTheEntrys() {
        SynthesisSettings settings = resolve(tuned(-2.0, 1.2), audio(null, 0.9));

        assertThat(settings.speakingRate()).isEqualTo(0.9);
        assertThat(settings.pitch()).isEqualTo(-2.0);
    }

    @Test
    void us4_5_aRateOutOfRangeIsACallerErrorStatingTheRange() {
        assertInvalidRequest(tuned(null, null), audio(null, 3.0))
                .hasMessage("speakingRate 3.0 is outside the allowed range [0.25, 2.0]");
    }

    @Test
    void us4_5_aPitchOutOfRangeIsACallerErrorStatingTheRange() {
        assertInvalidRequest(tuned(null, null), audio(-21.0, null))
                .hasMessage("pitch -21.0 is outside the allowed range [-20.0, 20.0]");
    }

    @Test
    void aNaNPitchIsACallerErrorStatingTheRange() {
        assertInvalidRequest(tuned(null, null), audio(Double.NaN, null))
                .hasMessage("pitch NaN is outside the allowed range [-20.0, 20.0]");
    }

    @Test
    void aNaNRateIsACallerErrorStatingTheRange() {
        assertInvalidRequest(tuned(null, null), audio(null, Double.NaN))
                .hasMessage("speakingRate NaN is outside the allowed range [0.25, 2.0]");
    }

    @Test
    void theRangeBoundsAreInclusive() {
        assertThat(resolve(tuned(null, null), audio(-20.0, 0.25)).speakingRate()).isEqualTo(0.25);
        assertThat(resolve(tuned(null, null), audio(20.0, 2.0)).pitch()).isEqualTo(20.0);
    }

    @Test
    void neutralValuesAreSentButLeftOutOfTheCacheKey() {
        SynthesisSettings settings = resolve(tuned(null, null), audio(0.0, 1.0));

        assertThat(settings.pitch()).isEqualTo(0.0);
        assertThat(settings.speakingRate()).isEqualTo(1.0);
        assertThat(settings.pitchKey()).isNull();
        assertThat(settings.speakingRateKey()).isNull();
    }
}
