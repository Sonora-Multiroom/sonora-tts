package multiroom.tts.provider;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.config.ProviderType;
import multiroom.tts.config.TtsProviderConfig;
import multiroom.tts.config.VoiceCatalogueProperties;
import multiroom.tts.provider.cloud.GoogleCloudTtsProvider;
import multiroom.tts.provider.cloud.google.CatalogueVoice;
import multiroom.tts.provider.cloud.google.ServiceAccountKey;
import multiroom.tts.provider.cloud.google.TestServiceAccountKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleCloudTtsProviderTest {

    private static final String SYNTHESIZE = "/v1/text:synthesize";
    private static final String VOICES = "/v1/voices";

    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        // The voice catalogue is unavailable in these tests, so the existence check is skipped.
        server.stubFor(get(urlPathEqualTo(VOICES)).willReturn(aResponse().withStatus(503)));
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private static TtsProviderConfig googleEntry(int timeoutSeconds) {
        TtsProviderConfig config = new TtsProviderConfig();
        config.setName("google-cloud");
        config.setType(ProviderType.GOOGLE_CLOUD);
        config.setApiKey("test-key");
        config.setVoice("en-US-Neural2-C");
        config.setTimeoutSeconds(timeoutSeconds);
        return config;
    }

    private GoogleCloudTtsProvider providerUnderTest(int timeoutSeconds) {
        return new GoogleCloudTtsProvider(googleEntry(timeoutSeconds), new VoiceCatalogueProperties(), null,
                URI.create(server.baseUrl() + "/v1/"), Clock.systemUTC());
    }

    private static SynthesisRequest request(String voice, String language) {
        return new SynthesisRequest("hello", SynthesisSettings.of(voice, language, null), 24000, 1);
    }

    private static String audioResponse(byte[] wavBytes) {
        return "{\"audioContent\":\"" + Base64.getEncoder().encodeToString(wavBytes) + "\"}";
    }

    @Test
    void constructionMakesNoHttpCall() {
        providerUnderTest(2);

        server.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void resolvingSettingsMakesNoHttpCall() {
        providerUnderTest(2).resolveSettings(new RequestedSettings("uk-UA-Chirp3-HD-Charon", null, null, null, null));

        server.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void linear16ResponseIsBase64DecodedIntoAudioData() {
        byte[] wavBytes = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'};
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(audioResponse(wavBytes))));

        SynthesisResult result = providerUnderTest(2).synthesize(request("en-US-Neural2-C", "en-US"));

        assertThat(result.audioData()).isEqualTo(wavBytes);
    }

    @Test
    void theResolvedLanguageIsSentAsTheVoicesLanguageCode() {
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE))
                .willReturn(aResponse().withStatus(200).withBody(audioResponse(new byte[] {1}))));

        providerUnderTest(2).synthesize(request("uk-UA-Chirp3-HD-Charon", "uk-UA"));

        server.verify(postRequestedFor(urlPathEqualTo(SYNTHESIZE))
                .withRequestBody(matchingJsonPath("$.voice.languageCode", equalTo("uk-UA")))
                .withRequestBody(matchingJsonPath("$.voice.name", equalTo("uk-UA-Chirp3-HD-Charon"))));
    }

    @Test
    void noVoiceNameIsSentWhenTheVoiceIsUnset() {
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE))
                .willReturn(aResponse().withStatus(200).withBody(audioResponse(new byte[] {1}))));

        providerUnderTest(2).synthesize(request(null, "en-US"));

        server.verify(postRequestedFor(urlPathEqualTo(SYNTHESIZE))
                .withRequestBody(notMatching(".*\"name\".*")));
    }

    @Test
    void aGoogleRejectionCarriesGooglesOwnExplanation() {
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).willReturn(aResponse().withStatus(400).withBody("""
                {"error":{"code":400,"message":"Requested language code 'en-US' doesn't match the voice's language code 'uk-UA'.","status":"INVALID_ARGUMENT"}}
                """)));

        assertThatThrownBy(() -> providerUnderTest(2).synthesize(request("uk-UA-Chirp3-HD-Charon", "en-US")))
                .isInstanceOf(TtsException.class)
                .hasMessage("Provider 'google-cloud' returned HTTP 400: Requested language code 'en-US' "
                        + "doesn't match the voice's language code 'uk-UA'.")
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.PROVIDER_ERROR);
    }

    @Test
    void anUnparseableErrorBodyStillStatesTheStatus() {
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE))
                .willReturn(aResponse().withStatus(500).withBody("<html><body>Internal error</body></html>")));

        assertThatThrownBy(() -> providerUnderTest(2).synthesize(request("en-US-Neural2-C", "en-US")))
                .isInstanceOf(TtsException.class)
                .hasMessage("Provider 'google-cloud' returned HTTP 500")
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.PROVIDER_ERROR);
    }

    @Test
    void rateLimitRaisesProviderRateLimitedWithGooglesText() {
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).willReturn(aResponse().withStatus(429)
                .withBody("{\"error\":{\"code\":429,\"message\":\"Quota exceeded for quota metric 'Requests'.\"}}")));

        assertThatThrownBy(() -> providerUnderTest(2).synthesize(request("en-US-Neural2-C", "en-US")))
                .isInstanceOf(TtsException.class)
                .hasMessageContaining("HTTP 429")
                .hasMessageContaining("Quota exceeded for quota metric 'Requests'.")
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.PROVIDER_RATE_LIMITED);
    }

    @Test
    void timeoutCancelsTheCall() {
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE))
                .willReturn(aResponse().withStatus(200).withBody("{}").withFixedDelay(3000)));

        assertThatThrownBy(() -> providerUnderTest(1).synthesize(request("en-US-Neural2-C", "en-US")))
                .isInstanceOf(TtsException.class)
                .hasMessageContaining("google-cloud")
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.PROVIDER_TIMEOUT);
    }

    @Test
    void anUnparseableSuccessBodyIsAProviderError() {
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).willReturn(aResponse().withStatus(200).withBody("not json")));

        assertThatThrownBy(() -> providerUnderTest(2).synthesize(request("en-US-Neural2-C", "en-US")))
                .isInstanceOf(TtsException.class)
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.PROVIDER_ERROR);
    }

    // --- the voice catalogue -------------------------------------------------------------------

    private static String fixture() {
        try (java.io.InputStream in = GoogleCloudTtsProviderTest.class.getResourceAsStream("/google-voices.json")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void stubCatalogue() {
        server.stubFor(get(urlPathEqualTo(VOICES)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(fixture())));
    }

    private void stubSynthesis() {
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE))
                .willReturn(aResponse().withStatus(200).withBody(audioResponse(new byte[] {1}))));
    }

    /**
     * Only for tests of a slow catalogue: a cold WireMock can take longer than 200 ms to answer
     * even a stub with no delay, so tests that need a successful fetch keep the 3 s default.
     */
    private static VoiceCatalogueProperties fastCatalogue() {
        VoiceCatalogueProperties properties = new VoiceCatalogueProperties();
        properties.setFetchTimeout(Duration.ofMillis(200));
        return properties;
    }

    private GoogleCloudTtsProvider provider(TtsProviderConfig config, VoiceCatalogueProperties catalogue) {
        return new GoogleCloudTtsProvider(config, catalogue, null, URI.create(server.baseUrl() + "/v1/"), Clock.systemUTC());
    }

    private static TtsProviderConfig structuredEntry(String engine, String language) {
        TtsProviderConfig config = googleEntry(5);
        config.setVoice(null);
        config.setEngine(engine);
        config.setLanguage(language);
        return config;
    }

    private static SynthesisRequest resolvedRequest(GoogleCloudTtsProvider provider, String voice, String engine,
                                                    String language) {
        SynthesisSettings settings = provider.resolveSettings(new RequestedSettings(voice, language, engine, null, null));
        return new SynthesisRequest("hello", settings, 24000, 1);
    }

    @Test
    void us3_1_aMistypedShortVoiceIsAnInvalidVoiceListingEveryAlternative() {
        stubCatalogue();
        stubSynthesis();
        GoogleCloudTtsProvider provider = provider(structuredEntry("chirp3-hd", "uk-UA"), new VoiceCatalogueProperties());

        assertThatThrownBy(() -> provider.synthesize(resolvedRequest(provider, "charn", null, null)))
                .isInstanceOf(TtsException.class)
                .hasMessage("Voice 'charn' is not available for Chirp3-HD / uk-UA. Available: Achernar, Achird, "
                        + "Algenib, Algieba, Alnilam, Aoede, Autonoe, Callirrhoe, Charon, Despina, Enceladus, Erinome, "
                        + "Fenrir, Gacrux, Iapetus, Kore, Laomedeia, Leda, Orus, Puck, Pulcherrima, Rasalgethi, "
                        + "Sadachbia, Sadaltager, Schedar, Sulafat, Umbriel, Vindemiatrix, Zephyr, Zubenelgenubi")
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.INVALID_VOICE);
        server.verify(0, postRequestedFor(urlPathEqualTo(SYNTHESIZE)));
    }

    @Test
    void aValidVoiceIsSentInTheCataloguesSpelling() {
        stubCatalogue();
        stubSynthesis();
        GoogleCloudTtsProvider provider = provider(googleEntry(5), new VoiceCatalogueProperties());

        provider.synthesize(request("en-US-NEWS-K", "en-US"));

        server.verify(postRequestedFor(urlPathEqualTo(SYNTHESIZE))
                .withRequestBody(matchingJsonPath("$.voice.name", equalTo("en-US-News-K"))));
    }

    @Test
    void us3_2_anUnreachableCatalogueStillSynthesizesAndIsNotRetriedWithinTheBackOff() {
        stubSynthesis();
        GoogleCloudTtsProvider provider = provider(googleEntry(5), new VoiceCatalogueProperties());

        provider.synthesize(request("uk-UA-Chirp3-HD-Charon", "uk-UA"));
        provider.synthesize(request("uk-UA-Chirp3-HD-Charon", "uk-UA"));

        server.verify(2, postRequestedFor(urlPathEqualTo(SYNTHESIZE)));
        server.verify(1, getRequestedFor(urlPathEqualTo(VOICES)));
    }

    @Test
    void aSlowCatalogueCostsAtMostTheFetchTimeoutAndSynthesisStillSucceeds() {
        server.stubFor(get(urlPathEqualTo(VOICES)).willReturn(aResponse().withStatus(200)
                .withBody(fixture()).withFixedDelay(2000)));
        stubSynthesis();
        GoogleCloudTtsProvider provider = provider(googleEntry(5), fastCatalogue());

        long started = System.nanoTime();
        SynthesisResult result = provider.synthesize(request("uk-UA-Chirp3-HD-Charon", "uk-UA"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(result.audioData()).containsExactly(1);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
        assertThat(elapsed).isLessThan(Duration.ofMillis(1500));
    }

    @Test
    void constructingTheProviderFetchesNoCatalogue() {
        stubCatalogue();
        provider(googleEntry(5), new VoiceCatalogueProperties());

        server.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void aVoicelessRequestNeverConsultsTheCatalogue() {
        stubCatalogue();
        stubSynthesis();
        GoogleCloudTtsProvider provider = provider(structuredEntry("wavenet", "en-US"), new VoiceCatalogueProperties());

        provider.synthesize(resolvedRequest(provider, null, null, null));

        server.verify(0, getRequestedFor(urlPathEqualTo(VOICES)));
        server.verify(1, postRequestedFor(urlPathEqualTo(SYNTHESIZE)));
    }

    @Test
    void us3_4_aShortVoiceIsLookedUpOnlyInTheDefaultEngine() {
        stubCatalogue();
        stubSynthesis();
        GoogleCloudTtsProvider provider = provider(structuredEntry("chirp3-hd", "en-US"), new VoiceCatalogueProperties());

        // Wavenet D exists in the fixture: that other engines are not searched is the point.
        assertThatThrownBy(() -> provider.synthesize(resolvedRequest(provider, "D", null, null)))
                .isInstanceOf(TtsException.class)
                .hasMessage("Voice 'D' is not available for Chirp3-HD / en-US. Available: Charon, Kore, Puck")
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.INVALID_VOICE);
        server.verify(0, postRequestedFor(urlPathEqualTo(SYNTHESIZE)));
    }

    @Test
    void aVoiceWithNoAlternativesSaysSo() {
        stubCatalogue();
        stubSynthesis();
        GoogleCloudTtsProvider provider = provider(structuredEntry("studio", "fr-FR"), new VoiceCatalogueProperties());

        assertThatThrownBy(() -> provider.synthesize(resolvedRequest(provider, "a", null, null)))
                .isInstanceOf(TtsException.class)
                .hasMessage("No Studio voices are available for fr-FR")
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.INVALID_VOICE);
    }

    @Test
    void fr011_eachEntryKeepsItsOwnCatalogueEvenWithTheSameKey() {
        server.stubFor(get(urlPathEqualTo(VOICES)).inScenario("catalogue")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        server.stubFor(get(urlPathEqualTo(VOICES)).inScenario("catalogue")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200).withBody(fixture())));
        stubSynthesis();
        GoogleCloudTtsProvider a = provider(googleEntry(5), new VoiceCatalogueProperties());
        GoogleCloudTtsProvider b = provider(googleEntry(5), new VoiceCatalogueProperties());

        a.synthesize(request("uk-UA-Chirp3-HD-Charon", "uk-UA"));
        server.verify(1, getRequestedFor(urlPathEqualTo(VOICES)));

        // B is not in A's back-off: it fetches its own copy and finds the voice missing.
        assertThatThrownBy(() -> b.synthesize(request("uk-UA-Chirp3-HD-Charn", "uk-UA")))
                .isInstanceOf(TtsException.class)
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.INVALID_VOICE);
        server.verify(2, getRequestedFor(urlPathEqualTo(VOICES)));

        // A is still in its own back-off: no request, and the typo is not caught.
        a.synthesize(request("uk-UA-Chirp3-HD-Charn", "uk-UA"));
        server.verify(2, getRequestedFor(urlPathEqualTo(VOICES)));
    }

    // --- audio adjustments reach Google --------------------------------------------------------

    private static SynthesisRequest adjusted(Double pitch, Double speakingRate) {
        SynthesisSettings settings = new SynthesisSettings("en-US-Neural2-C", "en-us-neural2-c", "en-US-Neural2-C",
                "en-US", null, pitch, speakingRate, pitch, speakingRate);
        return new SynthesisRequest("hello", settings, 24000, 1);
    }

    @Test
    void us4_pitchAndRateAreSentInAudioConfig() {
        stubSynthesis();

        providerUnderTest(2).synthesize(adjusted(-2.0, 1.2));

        server.verify(postRequestedFor(urlPathEqualTo(SYNTHESIZE))
                .withRequestBody(matchingJsonPath("$.audioConfig.pitch", equalTo("-2.0")))
                .withRequestBody(matchingJsonPath("$.audioConfig.speakingRate", equalTo("1.2")))
                .withRequestBody(matchingJsonPath("$.audioConfig.audioEncoding", equalTo("LINEAR16"))));
    }

    @Test
    void us4_unsetPitchAndRateAreNotSentAtAll() {
        stubSynthesis();

        providerUnderTest(2).synthesize(adjusted(null, null));

        server.verify(postRequestedFor(urlPathEqualTo(SYNTHESIZE))
                .withRequestBody(notMatching(".*\"pitch\".*"))
                .withRequestBody(notMatching(".*\"speakingRate\".*")));
    }

    // --- listing through the provider ---------------------------------------------------------

    @Test
    void us5_listVoicesUsesTheCatalogue() {
        stubCatalogue();
        GoogleCloudTtsProvider provider = provider(googleEntry(5), new VoiceCatalogueProperties());

        List<CatalogueVoice> voices = provider.listVoices("uk-UA", "chirp3-hd");

        assertThat(voices).hasSize(30).extracting(CatalogueVoice::fullName).contains("uk-UA-Chirp3-HD-Charon");
        server.verify(1, getRequestedFor(urlPathEqualTo(VOICES)));
    }

    @Test
    void us5_aSlowCatalogueFailsListingWithinTheFetchTimeoutAndIsNotRetriedWithinTheBackOff() {
        server.stubFor(get(urlPathEqualTo(VOICES)).willReturn(aResponse().withStatus(200)
                .withBody(fixture()).withFixedDelay(2000)));
        GoogleCloudTtsProvider provider = provider(googleEntry(10), fastCatalogue());

        long started = System.nanoTime();
        assertThatThrownBy(() -> provider.listVoices(null, null))
                .isInstanceOf(TtsException.class)
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.VOICE_CATALOGUE_UNAVAILABLE);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1500));

        assertThatThrownBy(() -> provider.listVoices(null, null))
                .isInstanceOf(TtsException.class)
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.VOICE_CATALOGUE_UNAVAILABLE);
        server.verify(1, getRequestedFor(urlPathEqualTo(VOICES)));
    }

    // --- 003: a service-account entry ---------------------------------------------------------

    private static final String TOKEN = "/token";
    private static final String TOKEN_BODY = "{\"access_token\":\"ya29.test\",\"expires_in\":3599,\"token_type\":\"Bearer\"}";

    @TempDir
    Path keyDir;

    private void stubToken() {
        server.stubFor(post(urlPathEqualTo(TOKEN)).willReturn(okJson(TOKEN_BODY)));
    }

    private static TtsProviderConfig serviceAccountEntry(int timeoutSeconds) {
        TtsProviderConfig config = googleEntry(timeoutSeconds);
        config.setApiKey(null);
        config.setServiceAccountKeyFile("sa.json");
        return config;
    }

    private Path keyFile() {
        return TestServiceAccountKeys.write(keyDir, server.baseUrl() + TOKEN);
    }

    private GoogleCloudTtsProvider serviceAccountProvider(TtsProviderConfig config, VoiceCatalogueProperties catalogue,
                                                          Path keyFile) {
        return new GoogleCloudTtsProvider(config, catalogue, ServiceAccountKey.load(config.getName(), keyFile),
                URI.create(server.baseUrl() + "/v1/"), Clock.systemUTC());
    }

    private GoogleCloudTtsProvider serviceAccountProvider(int timeoutSeconds) {
        return serviceAccountProvider(serviceAccountEntry(timeoutSeconds), new VoiceCatalogueProperties(), keyFile());
    }

    /** Two answers in turn from one path, then the second forever. */
    private void stubInTurn(String scenario, com.github.tomakehurst.wiremock.client.MappingBuilder first,
                            com.github.tomakehurst.wiremock.client.MappingBuilder second) {
        server.stubFor(first.inScenario(scenario).whenScenarioStateIs(Scenario.STARTED).willSetStateTo("second"));
        server.stubFor(second.inScenario(scenario).whenScenarioStateIs("second"));
    }

    /** The paths WireMock served, oldest first. */
    private List<String> servedPaths() {
        List<String> paths = new ArrayList<>(server.getAllServeEvents().stream()
                .map(event -> event.getRequest().getUrl().replaceAll("\\?.*", "")).toList());
        java.util.Collections.reverse(paths);
        return paths;
    }

    private static void assertCode(Throwable e, TtsErrorCode code) {
        assertThat(e).isInstanceOf(TtsException.class);
        assertThat(((TtsException) e).getErrorCode()).isEqualTo(code);
    }

    @Test
    void sa_a_synthesisCarriesABearerTokenAndNoKey() {
        stubToken();
        stubSynthesis();

        serviceAccountProvider(2).synthesize(request("en-US-Neural2-C", "en-US"));

        server.verify(postRequestedFor(urlPathEqualTo(SYNTHESIZE))
                .withHeader("Authorization", equalTo("Bearer ya29.test"))
                .withQueryParam("key", absent()));
    }

    @Test
    void sa_b_theCatalogueFetchCarriesTheSameToken() {
        stubToken();
        stubCatalogue();
        stubSynthesis();

        serviceAccountProvider(5).synthesize(request("en-US-Neural2-C", "en-US"));

        server.verify(getRequestedFor(urlPathEqualTo(VOICES))
                .withHeader("Authorization", equalTo("Bearer ya29.test"))
                .withQueryParam("key", absent()));
    }

    @Test
    void sa_c_twoAnnouncementsShareOneToken() {
        stubToken();
        stubSynthesis();
        GoogleCloudTtsProvider provider = serviceAccountProvider(2);

        provider.synthesize(request("en-US-Neural2-C", "en-US"));
        provider.synthesize(request("en-US-Neural2-C", "en-US"));

        server.verify(1, postRequestedFor(urlPathEqualTo(TOKEN)));
        server.verify(2, postRequestedFor(urlPathEqualTo(SYNTHESIZE)));
    }

    @Test
    void sa_d_theTokenIsObtainedBeforeTheCatalogue() {
        stubToken();
        stubCatalogue();
        stubSynthesis();

        serviceAccountProvider(5).synthesize(request("en-US-Neural2-C", "en-US"));

        assertThat(servedPaths()).containsExactly(TOKEN, VOICES, SYNTHESIZE);
    }

    @Test
    void sa_e_a401FromSynthesisRenewsTheTokenAndResendsOnce() {
        stubToken();
        stubInTurn("synthesis", post(urlPathEqualTo(SYNTHESIZE)).willReturn(aResponse().withStatus(401)),
                post(urlPathEqualTo(SYNTHESIZE)).willReturn(aResponse().withStatus(200).withBody(audioResponse(new byte[] {7}))));

        SynthesisResult result = serviceAccountProvider(2).synthesize(request("en-US-Neural2-C", "en-US"));

        assertThat(result.audioData()).containsExactly(7);
        server.verify(2, postRequestedFor(urlPathEqualTo(TOKEN)));
        server.verify(2, postRequestedFor(urlPathEqualTo(SYNTHESIZE)));
    }

    @Test
    void sa_f_a401TwiceIsReportedWithGooglesExplanation() {
        stubToken();
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).willReturn(aResponse().withStatus(401).withBody(
                "{\"error\":{\"code\":401,\"message\":\"Request had invalid authentication credentials.\"}}")));

        assertThatThrownBy(() -> serviceAccountProvider(2).synthesize(request("en-US-Neural2-C", "en-US")))
                .hasMessage("Provider 'google-cloud' returned HTTP 401: Request had invalid authentication credentials.")
                .satisfies(e -> assertCode(e, TtsErrorCode.PROVIDER_ERROR));
        server.verify(2, postRequestedFor(urlPathEqualTo(TOKEN)));
        server.verify(2, postRequestedFor(urlPathEqualTo(SYNTHESIZE)));
    }

    @Test
    void sa_g_a403IsNeverRetried() {
        stubToken();
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).willReturn(aResponse().withStatus(403).withBody(
                "{\"error\":{\"code\":403,\"message\":\"Permission 'serviceusage.services.use' denied.\"}}")));

        assertThatThrownBy(() -> serviceAccountProvider(2).synthesize(request("en-US-Neural2-C", "en-US")))
                .hasMessage("Provider 'google-cloud' returned HTTP 403: Permission 'serviceusage.services.use' denied.")
                .satisfies(e -> assertCode(e, TtsErrorCode.PROVIDER_ERROR));
        server.verify(1, postRequestedFor(urlPathEqualTo(TOKEN)));
        server.verify(1, postRequestedFor(urlPathEqualTo(SYNTHESIZE)));
    }

    @Test
    void sa_h_a401FromTheCatalogueRenewsOnceAndResendsIt() {
        stubToken();
        stubInTurn("voices", get(urlPathEqualTo(VOICES)).willReturn(aResponse().withStatus(401)),
                get(urlPathEqualTo(VOICES)).willReturn(aResponse().withStatus(200).withBody(fixture())));

        List<CatalogueVoice> voices = serviceAccountProvider(5).listVoices("uk-UA", "chirp3-hd");

        assertThat(voices).hasSize(30);
        server.verify(2, getRequestedFor(urlPathEqualTo(VOICES)));
        server.verify(2, postRequestedFor(urlPathEqualTo(TOKEN)));
    }

    @Test
    void sa_i_listingOnAColdEntryFetchesATokenThenTheCatalogue() {
        stubToken();
        stubCatalogue();

        serviceAccountProvider(5).listVoices(null, null);

        assertThat(servedPaths()).containsExactly(TOKEN, VOICES);
    }

    @Test
    void sa_j_entriesNamingTheSameKeyFileNeverShareAToken() {
        stubToken();
        stubSynthesis();
        Path file = keyFile();
        GoogleCloudTtsProvider a = serviceAccountProvider(serviceAccountEntry(2), new VoiceCatalogueProperties(), file);
        GoogleCloudTtsProvider b = serviceAccountProvider(serviceAccountEntry(2), new VoiceCatalogueProperties(), file);

        a.synthesize(request("en-US-Neural2-C", "en-US"));
        b.synthesize(request("en-US-Neural2-C", "en-US"));
        a.synthesize(request("en-US-Neural2-C", "en-US"));
        b.synthesize(request("en-US-Neural2-C", "en-US"));

        server.verify(2, postRequestedFor(urlPathEqualTo(TOKEN)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sa_k_pitchAndRateReachGoogleWithEitherCredential(boolean serviceAccount) {
        stubToken();
        stubSynthesis();
        GoogleCloudTtsProvider provider = serviceAccount ? serviceAccountProvider(2) : providerUnderTest(2);

        provider.synthesize(adjusted(-2.0, 1.2));

        server.verify(postRequestedFor(urlPathEqualTo(SYNTHESIZE))
                .withRequestBody(matchingJsonPath("$.audioConfig.pitch", equalTo("-2.0")))
                .withRequestBody(matchingJsonPath("$.audioConfig.speakingRate", equalTo("1.2"))));
    }

    @Test
    void sa_l_synthesisUsesATokenRenewedByTheCatalogue() {
        stubInTurn("token", post(urlPathEqualTo(TOKEN)).willReturn(okJson(
                        "{\"access_token\":\"ya29.first\",\"expires_in\":3599}")),
                post(urlPathEqualTo(TOKEN)).willReturn(okJson("{\"access_token\":\"ya29.second\",\"expires_in\":3599}")));
        stubInTurn("voices", get(urlPathEqualTo(VOICES)).willReturn(aResponse().withStatus(401)),
                get(urlPathEqualTo(VOICES)).willReturn(aResponse().withStatus(200).withBody(fixture())));
        stubSynthesis();

        serviceAccountProvider(5).synthesize(request("en-US-Neural2-C", "en-US"));

        server.verify(2, postRequestedFor(urlPathEqualTo(TOKEN)));
        server.verify(1, postRequestedFor(urlPathEqualTo(SYNTHESIZE)));
        server.verify(postRequestedFor(urlPathEqualTo(SYNTHESIZE))
                .withHeader("Authorization", equalTo("Bearer ya29.second")));
    }

    @Test
    void sa_anApiKeyEntryNeverAsksForAToken() {
        stubToken();
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> providerUnderTest(2).synthesize(request("en-US-Neural2-C", "en-US")))
                .satisfies(e -> assertCode(e, TtsErrorCode.PROVIDER_ERROR));
        server.verify(0, postRequestedFor(urlPathEqualTo(TOKEN)));
        server.verify(1, postRequestedFor(urlPathEqualTo(SYNTHESIZE)));
        server.verify(postRequestedFor(urlPathEqualTo(SYNTHESIZE))
                .withQueryParam("key", equalTo("test-key"))
                .withHeader("Authorization", absent()));
    }

    // --- 003: token failures at run time -------------------------------------------------------

    private static final String INVALID_GRANT = "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid JWT Signature.\"}";

    @Test
    void failure_a_aRejectedKeyFailsWithGooglesExplanationAndSendsNothingElse() {
        server.stubFor(post(urlPathEqualTo(TOKEN)).willReturn(aResponse().withStatus(400).withBody(INVALID_GRANT)));
        stubCatalogue();
        stubSynthesis();

        assertThatThrownBy(() -> serviceAccountProvider(2).synthesize(request("en-US-Neural2-C", "en-US")))
                .hasMessage("Provider 'google-cloud' could not obtain an access token: Google rejected the service "
                        + "account key (HTTP 400): Invalid JWT Signature. (invalid_grant)")
                .satisfies(e -> assertCode(e, TtsErrorCode.PROVIDER_ERROR));
        // Token first: one announcement asks Google for a token once, not once per step.
        server.verify(1, postRequestedFor(urlPathEqualTo(TOKEN)));
        server.verify(0, getRequestedFor(urlPathEqualTo(VOICES)));
        server.verify(0, postRequestedFor(urlPathEqualTo(SYNTHESIZE)));
    }

    @Test
    void failure_b_aSlowTokenServiceTimesOutWithinTheBudgetAndIsHeldOff() {
        server.stubFor(post(urlPathEqualTo(TOKEN)).willReturn(okJson(TOKEN_BODY).withFixedDelay(3000)));
        stubSynthesis();
        GoogleCloudTtsProvider provider = serviceAccountProvider(1);

        long started = System.nanoTime();
        assertThatThrownBy(() -> provider.synthesize(request("en-US-Neural2-C", "en-US")))
                .hasMessageContaining("could not obtain an access token")
                .satisfies(e -> assertCode(e, TtsErrorCode.PROVIDER_TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2500));

        started = System.nanoTime();
        assertThatThrownBy(() -> provider.synthesize(request("en-US-Neural2-C", "en-US")))
                .hasMessageContaining("in back-off after a recent failure")
                .satisfies(e -> assertCode(e, TtsErrorCode.PROVIDER_TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
        server.verify(1, postRequestedFor(urlPathEqualTo(TOKEN)));
        server.verify(0, postRequestedFor(urlPathEqualTo(SYNTHESIZE)));
    }

    @Test
    void failure_c_aDeniedTokenCarriesGooglesExplanationAsForAnApiKey() {
        stubToken();
        server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).willReturn(aResponse().withStatus(403).withBody(
                "{\"error\":{\"code\":403,\"message\":\"Cloud Text-to-Speech API has not been used in project 1.\"}}")));

        assertThatThrownBy(() -> serviceAccountProvider(2).synthesize(request("en-US-Neural2-C", "en-US")))
                .hasMessage("Provider 'google-cloud' returned HTTP 403: Cloud Text-to-Speech API has not been used in project 1.")
                .satisfies(e -> assertCode(e, TtsErrorCode.PROVIDER_ERROR));
    }

    @Test
    void failure_d_listingWithAnUnavailableTokenServiceIsACatalogueFailureHeldOff() {
        server.stubFor(post(urlPathEqualTo(TOKEN)).willReturn(aResponse().withStatus(503)));
        stubCatalogue();
        GoogleCloudTtsProvider provider = serviceAccountProvider(5);

        assertThatThrownBy(() -> provider.listVoices(null, null))
                .hasMessageContaining("could not obtain an access token")
                .hasMessageContaining("HTTP 503")
                .satisfies(e -> assertCode(e, TtsErrorCode.VOICE_CATALOGUE_UNAVAILABLE));
        server.resetRequests();

        assertThatThrownBy(() -> provider.listVoices(null, null))
                .satisfies(e -> assertCode(e, TtsErrorCode.VOICE_CATALOGUE_UNAVAILABLE));
        server.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void failure_e_f_aRejectedKeyRecoversWithNoRestartAndTheFileIsNeverReadAgain() throws Exception {
        stubInTurn("token", post(urlPathEqualTo(TOKEN)).willReturn(aResponse().withStatus(400).withBody(INVALID_GRANT)),
                post(urlPathEqualTo(TOKEN)).willReturn(okJson(TOKEN_BODY)));
        stubSynthesis();
        Path file = keyFile();
        GoogleCloudTtsProvider provider = serviceAccountProvider(serviceAccountEntry(2), new VoiceCatalogueProperties(), file);
        Files.delete(file);

        assertThatThrownBy(() -> provider.synthesize(request("en-US-Neural2-C", "en-US")))
                .hasMessageContaining("rejected the service account key")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(file.toString()));

        assertThat(provider.synthesize(request("en-US-Neural2-C", "en-US")).audioData()).containsExactly(1);
        server.verify(2, postRequestedFor(urlPathEqualTo(TOKEN)));
    }

    // --- 003: no secret in any output ----------------------------------------------------------

    @Test
    void noPrivateKeyAssertionOrTokenAppearsInAnyLogLineOrMessage() {
        Logger logger = (Logger) LoggerFactory.getLogger("multiroom.tts");
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.TRACE);
        List<Throwable> failures = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        try {
            Path file = keyFile();
            texts.add(ServiceAccountKey.load("google-cloud", file).toString());

            // (a) and (e): rejected, then recovered.
            stubInTurn("token", post(urlPathEqualTo(TOKEN)).willReturn(aResponse().withStatus(400).withBody(INVALID_GRANT)),
                    post(urlPathEqualTo(TOKEN)).willReturn(okJson(TOKEN_BODY)));
            stubCatalogue();
            stubSynthesis();
            GoogleCloudTtsProvider provider = serviceAccountProvider(serviceAccountEntry(2), new VoiceCatalogueProperties(), file);
            texts.add(provider.toString());
            capture(failures, () -> provider.synthesize(request("en-US-Neural2-C", "en-US")));
            provider.synthesize(request("en-US-Neural2-C", "en-US"));
            provider.listVoices(null, null);

            // (c): a denied token.
            server.stubFor(post(urlPathEqualTo(SYNTHESIZE)).willReturn(aResponse().withStatus(403)
                    .withBody("{\"error\":{\"code\":403,\"message\":\"denied\"}}")));
            capture(failures, () -> provider.synthesize(request("en-US-Neural2-C", "en-US")));

            // (b) and (d): an unavailable token service, on synthesis and on listing.
            server.stubFor(post(urlPathEqualTo(TOKEN)).willReturn(okJson(TOKEN_BODY).withFixedDelay(3000)));
            GoogleCloudTtsProvider slow = serviceAccountProvider(serviceAccountEntry(1), new VoiceCatalogueProperties(), file);
            capture(failures, () -> slow.synthesize(request("en-US-Neural2-C", "en-US")));
            capture(failures, () -> slow.synthesize(request("en-US-Neural2-C", "en-US")));
            server.stubFor(post(urlPathEqualTo(TOKEN)).willReturn(aResponse().withStatus(503)));
            GoogleCloudTtsProvider listing = serviceAccountProvider(serviceAccountEntry(1), new VoiceCatalogueProperties(), file);
            capture(failures, () -> listing.listVoices(null, null));
        } finally {
            logger.detachAppender(logs);
            logger.setLevel(previous);
        }

        List<String> assertions = server.getAllServeEvents().stream()
                .map(ServeEvent::getRequest)
                .filter(request -> request.getUrl().startsWith(TOKEN))
                .map(request -> URLDecoder.decode(request.getBodyAsString(), StandardCharsets.UTF_8)
                        .replaceAll(".*assertion=", ""))
                .toList();
        assertThat(assertions).isNotEmpty().allSatisfy(assertion -> assertThat(assertion).startsWith("eyJ"));
        assertThat(failures).hasSize(5);
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
            assertThat(text).doesNotContain(privateKey).doesNotContain("ya29.test");
            for (String assertion : assertions) {
                assertThat(text).doesNotContain(assertion);
            }
        }
    }

    private static void capture(List<Throwable> failures, Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }
}
