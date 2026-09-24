package multiroom.tts.provider;

import multiroom.tts.TtsException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ProviderRegistryTest {

    @Test
    void resolveDefaultReturnsFirstConfiguredWhenNoExplicitDefault() {
        TtsProvider first = mock(TtsProvider.class);
        TtsProvider second = mock(TtsProvider.class);
        Map<String, TtsProvider> providers = new LinkedHashMap<>();
        providers.put("openai", first);
        providers.put("piper-local", second);

        ProviderRegistry registry = new ProviderRegistry(providers, null);

        assertThat(registry.resolveDefault()).isSameAs(first);
        assertThat(registry.defaultProviderName()).isEqualTo("openai");
    }

    @Test
    void resolveDefaultHonoursExplicitDefault() {
        TtsProvider first = mock(TtsProvider.class);
        TtsProvider second = mock(TtsProvider.class);
        Map<String, TtsProvider> providers = new LinkedHashMap<>();
        providers.put("openai", first);
        providers.put("piper-local", second);

        ProviderRegistry registry = new ProviderRegistry(providers, "piper-local");

        assertThat(registry.resolveDefault()).isSameAs(second);
        assertThat(registry.defaultProviderName()).isEqualTo("piper-local");
    }

    @Test
    void resolveReturnsNamedProvider() {
        TtsProvider provider = mock(TtsProvider.class);
        ProviderRegistry registry = new ProviderRegistry(Map.of("openai", provider), "openai");

        assertThat(registry.resolve("openai")).isSameAs(provider);
    }

    @Test
    void explicitDefaultThatWasNeverRegisteredIsRejectedAtConstruction() {
        // The configured default may be a provider that exists but is disabled, in which case
        // it never reaches this map — fail loudly rather than let resolveDefault() return null.
        Map<String, TtsProvider> providers = Map.of("piper-local", mock(TtsProvider.class));

        assertThatThrownBy(() -> new ProviderRegistry(providers, "openai"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openai");
    }

    @Test
    void resolveUnknownNameThrowsClearError() {
        ProviderRegistry registry = new ProviderRegistry(Map.of("openai", mock(TtsProvider.class)), "openai");

        assertThatThrownBy(() -> registry.resolve("nonexistent"))
                .isInstanceOf(TtsException.class)
                .hasMessageContaining("nonexistent");
    }
}
