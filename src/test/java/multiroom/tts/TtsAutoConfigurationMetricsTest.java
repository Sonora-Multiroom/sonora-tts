package multiroom.tts;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import multiroom.api.conversion.FormatConverter;
import multiroom.api.services.DeviceQueryService;
import multiroom.api.services.DeviceRegistryService;
import multiroom.api.services.RouteService;
import multiroom.tts.metrics.MicrometerTtsMetrics;
import multiroom.tts.metrics.NoopTtsMetrics;
import multiroom.tts.metrics.TtsMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Which {@link TtsMetrics} the extension contributes in each kind of host. */
class TtsAutoConfigurationMetricsTest {

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
    void aHostWithARegistryGetsMicrometerMetricsAndTheMetersAppearInIt() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        contextRunner.withBean(MeterRegistry.class, () -> registry).run(context -> {
            assertThat(context).hasSingleBean(TtsMetrics.class);
            assertThat(context.getBean(TtsMetrics.class)).isInstanceOf(MicrometerTtsMetrics.class);
            assertThat(registry.find("tts.cache.size").gauge()).isNotNull();
            assertThat(registry.find("tts.announcements").tag("provider", "openai").counter()).isNotNull();
        });
    }

    @Test
    void aHostWithMicrometerButNoRegistryGetsTheNoop() {
        contextRunner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context.getBean(TtsMetrics.class)).isInstanceOf(NoopTtsMetrics.class);
        });
    }

    @Test
    void aHostWithoutMicrometerStartsWithTheNoop() {
        contextRunner.withClassLoader(new FilteredClassLoader(MeterRegistry.class)).run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(context.getBean(TtsMetrics.class)).isInstanceOf(NoopTtsMetrics.class);
        });
    }

    @Test
    void aDisabledExtensionContributesNoMetricsAndNoMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        contextRunner.withBean(MeterRegistry.class, () -> registry)
                .withPropertyValues("multiroom.tts.enabled=false")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context).doesNotHaveBean(TtsMetrics.class);
                    assertThat(registry.getMeters()).noneMatch(m -> m.getId().getName().startsWith("tts."));
                });
    }
}
