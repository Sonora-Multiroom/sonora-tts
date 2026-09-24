package multiroom.tts;

import multiroom.api.conversion.FormatConverter;
import multiroom.api.services.DeviceQueryService;
import multiroom.api.services.DeviceRegistryService;
import multiroom.api.services.RouteService;
import multiroom.tts.audio.TtsInputResolver;
import multiroom.tts.rest.TtsCacheController;
import multiroom.tts.rest.TtsController;
import multiroom.tts.service.TtsService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
}
