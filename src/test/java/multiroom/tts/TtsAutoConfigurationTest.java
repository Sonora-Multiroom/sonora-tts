package multiroom.tts;

import com.github.tomakehurst.wiremock.WireMockServer;
import multiroom.api.conversion.FormatConverter;
import multiroom.api.services.DeviceQueryService;
import multiroom.api.services.DeviceRegistryService;
import multiroom.api.services.RouteService;
import multiroom.tts.audio.TtsInputResolver;
import multiroom.tts.provider.ProviderRegistry;
import multiroom.tts.provider.cloud.google.TestServiceAccountKeys;
import multiroom.tts.rest.TtsCacheController;
import multiroom.tts.config.TtsProperties;
import multiroom.tts.rest.TtsController;
import multiroom.tts.rest.TtsVoiceController;
import multiroom.tts.service.TtsService;
import multiroom.tts.service.VoiceQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TtsAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TtsAutoConfiguration.class))
            .withBean(RouteService.class, () -> mock(RouteService.class))
            .withBean(DeviceRegistryService.class, () -> mock(DeviceRegistryService.class))
            .withBean(DeviceQueryService.class, () -> mock(DeviceQueryService.class))
            .withBean(FormatConverter.class, () -> mock(FormatConverter.class))
            .withPropertyValues(
                    "multiroom.tts.providers[0].name=openai",
                    "multiroom.tts.providers[0].type=OPENAI",
                    "multiroom.tts.providers[0].api-key=test-key"
            );

    @Test
    void contributesItsBeansByDefault() {
        contextRunner.run((AssertableApplicationContext context) -> {
            assertThat(context).hasSingleBean(TtsService.class);
            assertThat(context).hasSingleBean(TtsInputResolver.class);
            assertThat(context).hasSingleBean(TtsController.class);
            assertThat(context).hasSingleBean(TtsCacheController.class);
        });
    }

    @Test
    void contributesNothingWhenDisabled() {
        contextRunner.withPropertyValues("multiroom.tts.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(TtsService.class);
            assertThat(context).doesNotHaveBean(TtsInputResolver.class);
            assertThat(context).doesNotHaveBean(TtsController.class);
            assertThat(context.getStartupFailure()).isNull();
        });
    }

    @Test
    void aGoogleEntryWithAudioSettingsAndCatalogueTimingsStartsWithTheVoiceBeans() {
        contextRunner.withPropertyValues(
                "multiroom.tts.providers[1].name=google",
                "multiroom.tts.providers[1].type=GOOGLE_CLOUD",
                "multiroom.tts.providers[1].api-key=test-key",
                "multiroom.tts.providers[1].voice=en-US-Neural2-C",
                "multiroom.tts.providers[1].pitch=-2",
                "multiroom.tts.providers[1].speaking-rate=1.1",
                "multiroom.tts.voice-catalogue.ttl=12h",
                "multiroom.tts.voice-catalogue.failure-backoff=30s",
                "multiroom.tts.voice-catalogue.fetch-timeout=2s"
        ).run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context).hasSingleBean(VoiceQueryService.class);
            assertThat(context).hasSingleBean(TtsVoiceController.class);
            TtsProperties properties = context.getBean(TtsProperties.class);
            assertThat(properties.getVoiceCatalogue().getTtl()).isEqualTo(Duration.ofHours(12));
            assertThat(properties.getVoiceCatalogue().getFailureBackoff()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.getVoiceCatalogue().getFetchTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.getProviders().get(1).getSpeakingRate()).isEqualTo(1.1);
        });
    }

    @Test
    void aMalformedGoogleEntryAbortsStartUpNamingTheExtension() {
        contextRunner.withPropertyValues(
                "multiroom.tts.providers[1].name=google",
                "multiroom.tts.providers[1].type=GOOGLE_CLOUD",
                "multiroom.tts.providers[1].api-key=test-key",
                "multiroom.tts.providers[1].engine=chirp4",
                "multiroom.tts.providers[1].language=en-US"
        ).run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause().hasMessageContaining("multiroom-tts").hasMessageContaining("chirp4"));
    }

    @Test
    void contributesNoVoiceBeansWhenDisabled() {
        contextRunner.withPropertyValues("multiroom.tts.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(VoiceQueryService.class);
            assertThat(context).doesNotHaveBean(TtsVoiceController.class);
            assertThat(context.getStartupFailure()).isNull();
        });
    }

    // --- 003: a service-account entry ---------------------------------------------------------

    private ApplicationContextRunner withServiceAccount(String keyFile) {
        return contextRunner.withPropertyValues(
                "multiroom.tts.providers[1].name=google",
                "multiroom.tts.providers[1].type=google-cloud",
                "multiroom.tts.providers[1].service-account-key-file=" + keyFile,
                "multiroom.tts.providers[1].voice=en-US-Neural2-C");
    }

    @Test
    void aServiceAccountEntryStartsWithNoRequestToGoogle(@TempDir Path dir) {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            Path keyFile = TestServiceAccountKeys.write(dir, server.baseUrl() + "/token");

            withServiceAccount(keyFile.toString()).run(context -> {
                assertThat(context.getStartupFailure()).isNull();
                assertThat(context).hasSingleBean(ProviderRegistry.class);
            });

            server.verify(0, anyRequestedFor(anyUrl()));
        } finally {
            server.stop();
        }
    }

    @Test
    void aMissingKeyFileAbortsStartUpNamingTheAbsolutePath(@TempDir Path dir) {
        Path missing = dir.resolve("absent.json");

        withServiceAccount(missing.toString()).run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageStartingWith("multiroom-tts: provider 'google'")
                .hasMessageContaining(missing.toAbsolutePath().toString())
                .hasMessageContaining("does not exist"));
    }

    @Test
    void aKeyFileOfTheWrongTypeAbortsStartUp(@TempDir Path dir) {
        Path wrongType = TestServiceAccountKeys.write(dir, Map.of("type", "authorized_user"));

        withServiceAccount(wrongType.toString()).run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageStartingWith("multiroom-tts: provider 'google'")
                .hasMessageContaining(wrongType.toAbsolutePath().toString())
                .hasMessageContaining("'authorized_user'"));
    }

    @Test
    void aDisabledExtensionNeverReadsTheKeyFile(@TempDir Path dir) {
        withServiceAccount(dir.resolve("absent.json").toString())
                .withPropertyValues("multiroom.tts.enabled=false")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context).doesNotHaveBean(ProviderRegistry.class);
                });
    }
}
