package multiroom.tts.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.config.ProviderType;
import multiroom.tts.config.TtsProviderConfig;
import multiroom.tts.config.VoiceCatalogueProperties;
import multiroom.tts.provider.cloud.GoogleCloudTtsProvider;
import multiroom.tts.provider.cloud.GoogleGeminiTtsProvider;
import multiroom.tts.provider.cloud.google.CatalogueVoice;
import multiroom.tts.provider.cloud.google.ServiceAccountKey;
import multiroom.tts.provider.cloud.google.TestServiceAccountKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleGeminiTtsProviderTest {

    private static final String SYNTHESIZE = "/v1/text:synthesize";
    private static final String TOKEN = "/token";
    private static final String TOKEN_BODY = "{\"access_token\":\"ya29.test\",\"expires_in\":3599}";
    private static final int MAX_TEXT_LENGTH = 500;

    private WireMockServer server;

    @TempDir
    Path keyDir;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

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

    private Path keyFile() {
        return TestServiceAccountKeys.write(keyDir, server.baseUrl() + TOKEN);
    }

    private GoogleGeminiTtsProvider provider(TtsProviderConfig config) {
        return provider(config, keyFile());
    }

    private GoogleGeminiTtsProvider provider(TtsProviderConfig config, Path keyFile) {
        ServiceAccountKey key = ServiceAccountKey.load(config.getName(), keyFile);
        return new GoogleGeminiTtsProvider(config, new VoiceCatalogueProperties(), key, MAX_TEXT_LENGTH,
                URI.create(server.baseUrl() + "/v1/"), Clock.systemUTC());
    }

    private void stubToken() {
        server.stubFor(post(urlPathEqualTo(TOKEN)).willReturn(okJson(TOKEN_BODY)));
    }

    private static String audioResponse(byte[] wavBytes) {
        return "{\"audioContent\":\"" + Base64.getEncoder().encodeToString(wavBytes) + "\"}";
    }

    private void stubSynthesis(byte[] wavBytes) {
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE))
                .willReturn(aResponse().withStatus(200).withBody(audioResponse(wavBytes))));
    }

    /** A minimal but well-formed WAV, at the given sample rate, that {@code AudioSystem} accepts. */
    private static byte[] wav(int sampleRateHz, int channels, int bits, byte[] samples) throws Exception {
        AudioFormat format = new AudioFormat(sampleRateHz, bits, channels, true, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(samples), format,
                samples.length / format.getFrameSize())) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, out);
        }
        return out.toByteArray();
    }

    private static SynthesisRequest request(GoogleGeminiTtsProvider provider, String text, String voice,
                                             String language, Double speakingRate) {
        SynthesisSettings settings = provider.resolveSettings(
                new RequestedSettings(voice, language, null, null, speakingRate));
        return new SynthesisRequest(text, settings, 48000, 1);
    }

    private static SynthesisRequest requestWithPrompt(GoogleGeminiTtsProvider provider, String text,
                                                       String stylePrompt) {
        SynthesisSettings settings = provider.resolveSettings(
                new RequestedSettings(null, null, null, null, null, stylePrompt));
        return new SynthesisRequest(text, settings, 48000, 1);
    }

    @Test
    void constructingMakesZeroHttpRequests() {
        provider(entry());

        server.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void synthesizeMakesOneTokenRequestThenOnePostWithBearerAndNoKeyParam() throws Exception {
        stubToken();
        stubSynthesis(wav(24000, 1, 16, new byte[] {1, 2}));
        GoogleGeminiTtsProvider provider = provider(entry());

        provider.synthesize(request(provider, "hello", null, null, null));

        server.verify(1, postRequestedFor(urlPathEqualTo(TOKEN)));
        server.verify(postRequestedFor(urlPathEqualTo(SYNTHESIZE))
                .withHeader("Authorization", equalTo("Bearer ya29.test"))
                .withQueryParam("key", absent()));
    }

    @Test
    void theRequestBodyCarriesTheModelVoiceAndLanguage() throws Exception {
        stubToken();
        stubSynthesis(wav(24000, 1, 16, new byte[] {1, 2}));
        GoogleGeminiTtsProvider provider = provider(entry());

        provider.synthesize(request(provider, "hello", null, null, null));

        server.verify(postRequestedFor(urlPathEqualTo(SYNTHESIZE))
                .withRequestBody(matchingJsonPath("$.input.text", equalTo("hello")))
                .withRequestBody(matchingJsonPath("$.voice.languageCode", equalTo("en-US")))
                .withRequestBody(matchingJsonPath("$.voice.name", equalTo("Kore")))
                .withRequestBody(matchingJsonPath("$.voice.modelName", equalTo("gemini-2.5-flash-tts")))
                .withRequestBody(matchingJsonPath("$.audioConfig.audioEncoding", equalTo("LINEAR16")))
                .withRequestBody(matchingJsonPath("$.audioConfig.sampleRateHertz", equalTo("48000")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.notMatching(".*\"pitch\".*"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.notMatching(".*\"prompt\".*")));
    }

    @Test
    void speakingRateIsSentOnlyWhenSet() throws Exception {
        stubToken();
        stubSynthesis(wav(24000, 1, 16, new byte[] {1, 2}));
        GoogleGeminiTtsProvider provider = provider(entry());

        provider.synthesize(request(provider, "hello", null, null, 1.4));

        server.verify(postRequestedFor(urlPathEqualTo(SYNTHESIZE))
                .withRequestBody(matchingJsonPath("$.audioConfig.speakingRate", equalTo("1.4"))));

        provider.synthesize(request(provider, "world", null, null, null));

        server.verify(postRequestedFor(urlPathEqualTo(SYNTHESIZE))
                .withRequestBody(matchingJsonPath("$.input.text", equalTo("world")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.notMatching(".*\"speakingRate\".*")));
    }

    @Test
    void aSecondSynthesisWithinTheTokensLifetimeMakesNoSecondTokenRequest() throws Exception {
        stubToken();
        stubSynthesis(wav(24000, 1, 16, new byte[] {1, 2}));
        GoogleGeminiTtsProvider provider = provider(entry());

        provider.synthesize(request(provider, "hello", null, null, null));
        provider.synthesize(request(provider, "world", null, null, null));

        server.verify(1, postRequestedFor(urlPathEqualTo(TOKEN)));
        server.verify(2, postRequestedFor(urlPathEqualTo(SYNTHESIZE)));
    }

    @Test
    void aReturnedWavComesBackByteForByteAsAudioData() throws Exception {
        stubToken();
        byte[] wavBytes = wav(24000, 1, 16, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        stubSynthesis(wavBytes);
        GoogleGeminiTtsProvider provider = provider(entry());

        SynthesisResult result = provider.synthesize(request(provider, "hello", null, null, null));

        assertThat(result.audioData()).isEqualTo(wavBytes);
    }

    @Test
    void theProviderImplementsVoiceCatalogueProvider() {
        assertThat(provider(entry())).isInstanceOf(VoiceCatalogueProvider.class);
    }

    // --- voice listing ---------------------------------------------------------------------------

    private static final String VOICES = "/v1/voices";

    /** Full and bare names side by side, as the 2026-09-26 live call returned them. */
    private static final String VOICES_BODY = """
            {"voices": [
              {"languageCodes": ["uk-UA"], "name": "uk-UA-Chirp3-HD-Kore", "ssmlGender": "FEMALE"},
              {"languageCodes": ["en-US"], "name": "Zephyr", "ssmlGender": "FEMALE"},
              {"languageCodes": ["en-US"], "name": "Achird", "ssmlGender": "MALE"},
              {"languageCodes": ["en-US"], "name": "en-US-Neural2-C", "ssmlGender": "FEMALE"}
            ]}
            """;

    private void stubVoices() {
        server.stubFor(get(urlPathEqualTo(VOICES)).willReturn(okJson(VOICES_BODY)));
    }

    private static final List<CatalogueVoice> GEMINI_VOICES = List.of(
            new CatalogueVoice("Achird", "Achird", "gemini-2.5-flash-tts", null, "MALE"),
            new CatalogueVoice("Zephyr", "Zephyr", "gemini-2.5-flash-tts", null, "FEMALE"));

    @Test
    void listingFetchesGooglesVoicesWithABearerTokenAndKeepsOnlyGeminiVoices() {
        stubToken();
        stubVoices();

        List<CatalogueVoice> voices = provider(entry()).listVoices(null, null);

        assertThat(voices).isEqualTo(GEMINI_VOICES);
        server.verify(1, postRequestedFor(urlPathEqualTo(TOKEN)));
        server.verify(1, getRequestedFor(urlPathEqualTo(VOICES))
                .withHeader("Authorization", equalTo("Bearer ya29.test"))
                .withQueryParam("key", absent()));
    }

    @Test
    void aLanguageDoesNotNarrowTheListAndAWarmListIsNotFetchedAgain() {
        stubToken();
        stubVoices();
        GoogleGeminiTtsProvider provider = provider(entry());

        assertThat(provider.listVoices(null, null)).isEqualTo(GEMINI_VOICES);
        assertThat(provider.listVoices("uk-UA", null)).isEqualTo(GEMINI_VOICES);

        server.verify(1, getRequestedFor(urlPathEqualTo(VOICES)));
    }

    @Test
    void anEngineFilterIsACallerErrorAndContactsNothing() {
        GoogleGeminiTtsProvider provider = provider(entry());

        assertThatThrownBy(() -> provider.listVoices(null, "chirp3-hd"))
                .hasMessage("Filter 'engine' is not supported by provider 'gemini' of type GOOGLE_GEMINI")
                .satisfies(e -> assertCode(e, TtsErrorCode.INVALID_REQUEST));
        server.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void aRefusedVoiceListIsUnavailableWithGooglesExplanationAndIsHeldOff() {
        stubToken();
        server.stubFor(get(urlPathEqualTo(VOICES)).willReturn(aResponse().withStatus(403)
                .withBody("{\"error\":{\"code\":403,\"message\":\"Cloud Text-to-Speech API has not been used\"}}")));
        GoogleGeminiTtsProvider provider = provider(entry());

        assertThatThrownBy(() -> provider.listVoices(null, null))
                .hasMessageContaining("gemini")
                .hasMessageContaining("HTTP 403: Cloud Text-to-Speech API has not been used")
                .satisfies(e -> assertCode(e, TtsErrorCode.VOICE_CATALOGUE_UNAVAILABLE));
        assertThatThrownBy(() -> provider.listVoices(null, null))
                .satisfies(e -> assertCode(e, TtsErrorCode.VOICE_CATALOGUE_UNAVAILABLE));

        server.verify(1, getRequestedFor(urlPathEqualTo(VOICES)));
    }

    @Test
    void announcingNeverFetchesOrChecksAgainstTheVoiceList() throws Exception {
        stubToken();
        stubVoices();
        stubSynthesis(wav(24000, 1, 16, new byte[] {1, 2}));
        GoogleGeminiTtsProvider provider = provider(entry());

        provider.synthesize(request(provider, "hello", null, null, null));
        server.verify(0, getRequestedFor(urlPathEqualTo(VOICES)));

        provider.listVoices(null, null);
        provider.synthesize(request(provider, "hello", "Puck", null, null));

        server.verify(1, getRequestedFor(urlPathEqualTo(VOICES)));
        server.verify(postRequestedFor(urlPathEqualTo(SYNTHESIZE))
                .withRequestBody(matchingJsonPath("$.voice.name", equalTo("Puck"))));
    }

    @Test
    void noPrivateKeyTokenOrJwtAppearsInAnyLogLineOrMessageOnTheListingPath() {
        Logger logger = (Logger) LoggerFactory.getLogger("multiroom.tts");
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.TRACE);
        java.util.List<Throwable> failures = new java.util.ArrayList<>();
        java.util.List<String> texts = new java.util.ArrayList<>();
        try {
            server.stubFor(post(urlPathEqualTo(TOKEN)).willReturn(aResponse().withStatus(400).withBody(INVALID_GRANT)));
            GoogleGeminiTtsProvider denied = provider(entry());
            capture(failures, () -> denied.listVoices(null, null));

            server.stubFor(post(urlPathEqualTo(TOKEN)).willReturn(okJson(TOKEN_BODY).withFixedDelay(3000)));
            VoiceCatalogueProperties shortFetch = new VoiceCatalogueProperties();
            shortFetch.setFetchTimeout(java.time.Duration.ofSeconds(1));
            GoogleGeminiTtsProvider slow = new GoogleGeminiTtsProvider(entry(), shortFetch,
                    ServiceAccountKey.load("gemini", keyFile()), MAX_TEXT_LENGTH,
                    URI.create(server.baseUrl() + "/v1/"), Clock.systemUTC());
            capture(failures, () -> slow.listVoices(null, null));

            stubToken();
            server.stubFor(get(urlPathEqualTo(VOICES)).willReturn(aResponse().withStatus(403)
                    .withBody("{\"error\":{\"code\":403,\"message\":\"denied\"}}")));
            GoogleGeminiTtsProvider refused = provider(entry());
            capture(failures, () -> refused.listVoices(null, null));
        } finally {
            logger.detachAppender(logs);
            logger.setLevel(previous);
        }

        assertThat(failures).hasSize(3);
        for (Throwable failure : failures) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                texts.add(String.valueOf(cause.getMessage()));
                texts.add(cause.toString());
            }
        }
        logs.list.forEach(event -> texts.add(event.getFormattedMessage()));
        assertThat(logs.list).isNotEmpty();

        String privateKey = TestServiceAccountKeys.privateKeyBase64().substring(0, 40);
        for (String text : texts) {
            assertThat(text).doesNotContain(privateKey).doesNotContain("ya29.test").doesNotContain("eyJ");
        }
    }

    // --- style prompt ------------------------------------------------------------------------

    @Test
    void anEffectivePromptIsSentAsInputPromptAndTheTextIsUnchanged() throws Exception {
        stubToken();
        stubSynthesis(wav(24000, 1, 16, new byte[] {1, 2}));
        GoogleGeminiTtsProvider provider = provider(entry());

        provider.synthesize(requestWithPrompt(provider, "hello", "Announce this urgently."));

        server.verify(postRequestedFor(urlPathEqualTo(SYNTHESIZE))
                .withRequestBody(matchingJsonPath("$.input.text", equalTo("hello")))
                .withRequestBody(matchingJsonPath("$.input.prompt", equalTo("Announce this urgently."))));
    }

    @Test
    void noEffectivePromptMeansNoPromptKeyInInputAtAll() throws Exception {
        stubToken();
        stubSynthesis(wav(24000, 1, 16, new byte[] {1, 2}));
        GoogleGeminiTtsProvider provider = provider(entry());

        provider.synthesize(request(provider, "hello", null, null, null));

        server.verify(postRequestedFor(urlPathEqualTo(SYNTHESIZE))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.notMatching(".*\"prompt\".*")));
    }

    // --- Google's refusals explain themselves ---------------------------------------------------

    private static void assertCode(Throwable e, TtsErrorCode code) {
        assertThat(e).isInstanceOf(TtsException.class);
        assertThat(((TtsException) e).getErrorCode()).isEqualTo(code);
    }

    @Test
    void aPermissionDenied403IsAProviderErrorWithGooglesExplanation() throws Exception {
        stubToken();
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).willReturn(aResponse().withStatus(403).withBody(
                "{\"error\":{\"code\":403,\"message\":\"Permission 'aiplatform.endpoints.predict' denied on "
                        + "resource\",\"status\":\"PERMISSION_DENIED\"}}")));
        GoogleGeminiTtsProvider provider = provider(entry());

        assertThatThrownBy(() -> provider.synthesize(request(provider, "hello", null, null, null)))
                .hasMessageContaining("gemini")
                .hasMessageContaining("aiplatform.endpoints.predict")
                .satisfies(e -> assertCode(e, TtsErrorCode.PROVIDER_ERROR));
    }

    @Test
    void aDisabledAgentPlatformApiIsAProviderErrorWithGooglesExplanation() throws Exception {
        stubToken();
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).willReturn(aResponse().withStatus(403).withBody(
                "{\"error\":{\"code\":403,\"message\":\"Agent Platform API has not been used in project 123 "
                        + "or it is disabled\",\"status\":\"PERMISSION_DENIED\"}}")));
        GoogleGeminiTtsProvider provider = provider(entry());

        assertThatThrownBy(() -> provider.synthesize(request(provider, "hello", null, null, null)))
                .hasMessageContaining("gemini")
                .hasMessageContaining("Agent Platform API has not been used")
                .satisfies(e -> assertCode(e, TtsErrorCode.PROVIDER_ERROR));
    }

    @Test
    void anUnknownModelIs400InvalidArgumentAsAProviderError() throws Exception {
        stubToken();
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).willReturn(aResponse().withStatus(400).withBody(
                "{\"error\":{\"code\":400,\"message\":\"Unknown model 'gemini-9000'\",\"status\":\"INVALID_ARGUMENT\"}}")));
        GoogleGeminiTtsProvider provider = provider(entry());

        assertThatThrownBy(() -> provider.synthesize(request(provider, "hello", null, null, null)))
                .hasMessageContaining("gemini")
                .hasMessageContaining("Unknown model")
                .satisfies(e -> assertCode(e, TtsErrorCode.PROVIDER_ERROR));
    }

    @Test
    void anUnknownVoiceIs400InvalidArgumentAsAProviderError() throws Exception {
        stubToken();
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).willReturn(aResponse().withStatus(400).withBody(
                "{\"error\":{\"code\":400,\"message\":\"Unknown voice 'Nosuchvoice'\",\"status\":\"INVALID_ARGUMENT\"}}")));
        GoogleGeminiTtsProvider provider = provider(entry());

        assertThatThrownBy(() -> provider.synthesize(request(provider, "hello", null, null, null)))
                .hasMessageContaining("gemini")
                .hasMessageContaining("Unknown voice")
                .satisfies(e -> assertCode(e, TtsErrorCode.PROVIDER_ERROR));
    }

    @Test
    void a429IsRateLimited() throws Exception {
        stubToken();
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).willReturn(aResponse().withStatus(429).withBody(
                "{\"error\":{\"code\":429,\"message\":\"Quota exceeded\"}}")));
        GoogleGeminiTtsProvider provider = provider(entry());

        assertThatThrownBy(() -> provider.synthesize(request(provider, "hello", null, null, null)))
                .hasMessageContaining("gemini")
                .hasMessageContaining("Quota exceeded")
                .satisfies(e -> assertCode(e, TtsErrorCode.PROVIDER_RATE_LIMITED));
    }

    @Test
    void aDelayBeyondTimeoutSecondsIsAProviderTimeout() throws Exception {
        stubToken();
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE))
                .willReturn(aResponse().withStatus(200).withBody("{}").withFixedDelay(3000)));
        TtsProviderConfig config = entry();
        config.setTimeoutSeconds(1);
        GoogleGeminiTtsProvider provider = provider(config);

        assertThatThrownBy(() -> provider.synthesize(request(provider, "hello", null, null, null)))
                .hasMessageContaining("gemini")
                .satisfies(e -> assertCode(e, TtsErrorCode.PROVIDER_TIMEOUT));
    }

    @Test
    void a401FollowedBy200SucceedsAfterExactlyOneRenewal() throws Exception {
        stubToken();
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("recovered"));
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).inScenario("retry")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200).withBody(audioResponse(new byte[] {9}))));
        GoogleGeminiTtsProvider provider = provider(entry());

        SynthesisResult result = provider.synthesize(request(provider, "hello", null, null, null));

        assertThat(result.audioData()).containsExactly(9);
        server.verify(2, postRequestedFor(urlPathEqualTo(TOKEN)));
        server.verify(2, postRequestedFor(urlPathEqualTo(SYNTHESIZE)));
    }

    @Test
    void a401TwiceIsAProviderError() throws Exception {
        stubToken();
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).willReturn(aResponse().withStatus(401).withBody(
                "{\"error\":{\"code\":401,\"message\":\"Request had invalid authentication credentials.\"}}")));
        GoogleGeminiTtsProvider provider = provider(entry());

        assertThatThrownBy(() -> provider.synthesize(request(provider, "hello", null, null, null)))
                .hasMessageContaining("gemini")
                .hasMessageContaining("Request had invalid authentication credentials.")
                .satisfies(e -> assertCode(e, TtsErrorCode.PROVIDER_ERROR));
        server.verify(2, postRequestedFor(urlPathEqualTo(TOKEN)));
        server.verify(2, postRequestedFor(urlPathEqualTo(SYNTHESIZE)));
    }

    // --- token failures --------------------------------------------------------------------------

    private static final String INVALID_GRANT = "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid JWT Signature.\"}";

    @Test
    void aSlowTokenServiceTimesOutAndIsHeldOff() throws Exception {
        server.stubFor(post(urlPathEqualTo(TOKEN)).willReturn(okJson(TOKEN_BODY).withFixedDelay(3000)));
        stubSynthesis(wav(24000, 1, 16, new byte[] {1, 2}));
        TtsProviderConfig config = entry();
        config.setTimeoutSeconds(1);
        GoogleGeminiTtsProvider provider = provider(config);

        assertThatThrownBy(() -> provider.synthesize(request(provider, "hello", null, null, null)))
                .hasMessageContaining("could not obtain an access token")
                .satisfies(e -> assertCode(e, TtsErrorCode.PROVIDER_TIMEOUT));

        assertThatThrownBy(() -> provider.synthesize(request(provider, "hello", null, null, null)))
                .hasMessageContaining("in back-off after a recent failure")
                .satisfies(e -> assertCode(e, TtsErrorCode.PROVIDER_TIMEOUT));
        server.verify(1, postRequestedFor(urlPathEqualTo(TOKEN)));
        server.verify(0, postRequestedFor(urlPathEqualTo(SYNTHESIZE)));
    }

    @Test
    void aDeniedTokenCarriesGooglesExplanationAndIsNotRemembered() throws Exception {
        server.stubFor(post(urlPathEqualTo(TOKEN)).inScenario("token")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(400).withBody(INVALID_GRANT))
                .willSetStateTo("recovered"));
        server.stubFor(post(urlPathEqualTo(TOKEN)).inScenario("token")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson(TOKEN_BODY)));
        stubSynthesis(wav(24000, 1, 16, new byte[] {1, 2}));
        GoogleGeminiTtsProvider provider = provider(entry());

        assertThatThrownBy(() -> provider.synthesize(request(provider, "hello", null, null, null)))
                .hasMessageContaining("could not obtain an access token")
                .satisfies(e -> assertCode(e, TtsErrorCode.PROVIDER_ERROR));

        SynthesisResult result = provider.synthesize(request(provider, "hello", null, null, null));
        assertThat(result.audioData()).isNotEmpty();
        server.verify(2, postRequestedFor(urlPathEqualTo(TOKEN)));
    }

    @Test
    void twoGeminiEntriesFromTheSameKeyFileDoNotShareAToken() throws Exception {
        stubToken();
        stubSynthesis(wav(24000, 1, 16, new byte[] {1, 2}));
        Path file = keyFile();
        GoogleGeminiTtsProvider a = provider(entry(), file);
        GoogleGeminiTtsProvider b = provider(entry(), file);

        a.synthesize(request(a, "hello", null, null, null));
        b.synthesize(request(b, "hello", null, null, null));

        server.verify(2, postRequestedFor(urlPathEqualTo(TOKEN)));
    }

    @Test
    void aGeminiEntryAndAServiceAccountGoogleCloudEntryFromTheSameKeyFileDoNotShareAToken() throws Exception {
        stubToken();
        stubSynthesis(wav(24000, 1, 16, new byte[] {1, 2}));
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).willReturn(
                aResponse().withStatus(200).withBody(audioResponse(new byte[] {3}))));
        Path file = keyFile();
        GoogleGeminiTtsProvider gemini = provider(entry(), file);

        TtsProviderConfig googleCloudConfig = new TtsProviderConfig();
        googleCloudConfig.setName("google");
        googleCloudConfig.setType(ProviderType.GOOGLE_CLOUD);
        googleCloudConfig.setServiceAccountKeyFile(file.toString());
        googleCloudConfig.setVoice("en-US-Neural2-C");
        ServiceAccountKey googleCloudKey = ServiceAccountKey.load("google", file);
        GoogleCloudTtsProvider googleCloud = new GoogleCloudTtsProvider(googleCloudConfig, new VoiceCatalogueProperties(),
                googleCloudKey, URI.create(server.baseUrl() + "/v1/"), Clock.systemUTC());

        gemini.synthesize(request(gemini, "hello", null, null, null));
        googleCloud.synthesize(new SynthesisRequest("hello",
                googleCloud.resolveSettings(new RequestedSettings(null, "en-US", null, null, null)), 24000, 1));

        server.verify(2, postRequestedFor(urlPathEqualTo(TOKEN)));
        server.verify(postRequestedFor(urlPathEqualTo(SYNTHESIZE))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.notMatching(".*\"modelName\".*"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.notMatching(".*\"prompt\".*")));
    }

    // --- no secret in any output ------------------------------------------------------------------

    private static void capture(java.util.List<Throwable> failures, Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }

    @Test
    void noPrivateKeyTokenOrJwtAppearsInAnyLogLineOrMessage() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("multiroom.tts");
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.TRACE);
        java.util.List<Throwable> failures = new java.util.ArrayList<>();
        java.util.List<String> texts = new java.util.ArrayList<>();
        try {
            Path file = keyFile();
            texts.add(ServiceAccountKey.load("gemini", file).toString());

            server.stubFor(post(urlPathEqualTo(TOKEN)).inScenario("token")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(400).withBody(INVALID_GRANT))
                    .willSetStateTo("recovered"));
            server.stubFor(post(urlPathEqualTo(TOKEN)).inScenario("token")
                    .whenScenarioStateIs("recovered")
                    .willReturn(okJson(TOKEN_BODY)));
            stubSynthesis(wav(24000, 1, 16, new byte[] {1, 2}));
            GoogleGeminiTtsProvider provider = provider(entry(), file);
            texts.add(provider.toString());
            capture(failures, () -> provider.synthesize(request(provider, "hello", null, null, null)));
            provider.synthesize(request(provider, "hello", null, null, null));

            server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).willReturn(aResponse().withStatus(403)
                    .withBody("{\"error\":{\"code\":403,\"message\":\"denied\"}}")));
            capture(failures, () -> provider.synthesize(request(provider, "hello", null, null, null)));

            server.stubFor(post(urlPathEqualTo(TOKEN)).willReturn(okJson(TOKEN_BODY).withFixedDelay(3000)));
            TtsProviderConfig slowConfig = entry();
            slowConfig.setTimeoutSeconds(1);
            GoogleGeminiTtsProvider slow = provider(slowConfig, keyFile());
            capture(failures, () -> slow.synthesize(request(slow, "hello", null, null, null)));
            capture(failures, () -> slow.synthesize(request(slow, "hello", null, null, null)));
        } finally {
            logger.detachAppender(logs);
            logger.setLevel(previous);
        }

        assertThat(failures).hasSize(4);
        for (Throwable failure : failures) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                texts.add(String.valueOf(cause.getMessage()));
                texts.add(cause.toString());
            }
        }
        logs.list.forEach(event -> texts.add(event.getFormattedMessage()));
        assertThat(logs.list).isNotEmpty();

        String privateKey = TestServiceAccountKeys.privateKeyBase64().substring(0, 40);
        for (String text : texts) {
            assertThat(text).doesNotContain(privateKey).doesNotContain("ya29.test").doesNotContain("eyJ");
        }
    }
}
