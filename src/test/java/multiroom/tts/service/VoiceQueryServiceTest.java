package multiroom.tts.service;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.config.ProviderType;
import multiroom.tts.config.TtsProperties;
import multiroom.tts.config.TtsProviderConfig;
import multiroom.tts.provider.ProviderRegistry;
import multiroom.tts.provider.TtsProvider;
import multiroom.tts.provider.VoiceCatalogueProvider;
import multiroom.tts.provider.cloud.google.CatalogueVoice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class VoiceQueryServiceTest {

    private TtsProvider googleProvider;
    private TtsProvider piperProvider;
    private VoiceQueryService service;

    private static TtsProviderConfig entry(String name, ProviderType type) {
        TtsProviderConfig config = new TtsProviderConfig();
        config.setName(name);
        config.setType(type);
        return config;
    }

    @BeforeEach
    void setUp() {
        googleProvider = mock(TtsProvider.class, withSettings().extraInterfaces(VoiceCatalogueProvider.class));
        piperProvider = mock(TtsProvider.class);
        TtsProperties properties = new TtsProperties();
        properties.setProviders(List.of(entry("google", ProviderType.GOOGLE_CLOUD),
                entry("piper-local", ProviderType.LOCAL_HTTP)));
        service = new VoiceQueryService(properties,
                new ProviderRegistry(Map.of("google", googleProvider, "piper-local", piperProvider), "google"));
    }

    @Test
    void aGoogleProvidersVoicesAreDelegatedToItsCatalogue() {
        List<CatalogueVoice> voices = List.of(new CatalogueVoice("uk-UA-Chirp3-HD-Charon", "Charon", "Chirp3-HD", "uk-UA"));
        when(((VoiceCatalogueProvider) googleProvider).listVoices("uk-UA", "chirp3-hd")).thenReturn(voices);

        assertThat(service.listVoices("google", "uk-UA", "chirp3-hd")).isEqualTo(voices);
        verify((VoiceCatalogueProvider) googleProvider).listVoices("uk-UA", "chirp3-hd");
    }

    @Test
    void us5_2_aProviderTypeWithoutListingIsACallerError() {
        assertThatThrownBy(() -> service.listVoices("piper-local", null, null))
                .isInstanceOf(TtsException.class)
                .hasMessage("Provider 'piper-local' of type LOCAL_HTTP does not support voice listing")
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.INVALID_REQUEST);
    }

    @Test
    void us5_4_anUnknownProviderIsNotFound() {
        assertThatThrownBy(() -> service.listVoices("gogle", null, null))
                .isInstanceOf(TtsException.class)
                .hasMessage("No provider configured with name 'gogle'")
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.PROVIDER_NOT_FOUND);
    }

    @Test
    void aMalformedLanguageFilterIsACallerErrorWithNoCatalogueCall() {
        assertThatThrownBy(() -> service.listVoices("google", "ukrainian", null))
                .isInstanceOf(TtsException.class)
                .hasMessage("Language 'ukrainian' is not a language-region tag such as uk-UA, cmn-CN or es-419")
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.INVALID_REQUEST);
        verifyNoInteractions(googleProvider);
    }
}
