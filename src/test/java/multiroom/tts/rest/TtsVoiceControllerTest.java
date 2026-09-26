package multiroom.tts.rest;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.provider.cloud.google.CatalogueVoice;
import multiroom.tts.service.VoiceQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TtsVoiceController.class)
class TtsVoiceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VoiceQueryService voiceQueryService;

    @Test
    void us5_1_listsTheVoicesInThePublishedShape() throws Exception {
        when(voiceQueryService.listVoices("google", "uk-UA", "chirp3-hd")).thenReturn(List.of(
                new CatalogueVoice("uk-UA-Chirp3-HD-Charon", "Charon", "Chirp3-HD", "uk-UA"),
                new CatalogueVoice("uk-UA-Chirp3-HD-Puck", "Puck", "Chirp3-HD", "uk-UA")));

        mockMvc.perform(get("/api/tts/providers/google/voices").param("language", "uk-UA").param("engine", "chirp3-hd"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerName").value("google"))
                .andExpect(jsonPath("$.voices.length()").value(2))
                .andExpect(jsonPath("$.voices[0].shortName").value("Charon"))
                .andExpect(jsonPath("$.voices[0].fullName").value("uk-UA-Chirp3-HD-Charon"))
                .andExpect(jsonPath("$.voices[0].engine").value("Chirp3-HD"))
                .andExpect(jsonPath("$.voices[0].language").value("uk-UA"))
                .andExpect(jsonPath("$.voices[1].shortName").value("Puck"));
    }

    @Test
    void aGoogleCloudVoiceCarriesItsGender() throws Exception {
        when(voiceQueryService.listVoices("google", null, null)).thenReturn(List.of(
                new CatalogueVoice("uk-UA-Chirp3-HD-Charon", "Charon", "Chirp3-HD", "uk-UA", "MALE")));

        mockMvc.perform(get("/api/tts/providers/google/voices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voices[0].language").value("uk-UA"))
                .andExpect(jsonPath("$.voices[0].gender").value("MALE"));
    }

    @Test
    void aGeminiVoiceHasTheModelAsEngineAndNoLanguageKey() throws Exception {
        when(voiceQueryService.listVoices("gemini", null, null)).thenReturn(List.of(
                new CatalogueVoice("Kore", "Kore", "gemini-2.5-flash-tts", null, "FEMALE")));

        mockMvc.perform(get("/api/tts/providers/gemini/voices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voices[0].shortName").value("Kore"))
                .andExpect(jsonPath("$.voices[0].fullName").value("Kore"))
                .andExpect(jsonPath("$.voices[0].engine").value("gemini-2.5-flash-tts"))
                .andExpect(jsonPath("$.voices[0].gender").value("FEMALE"))
                .andExpect(jsonPath("$.voices[0].language").doesNotExist());
    }

    @Test
    void filtersAreOptional() throws Exception {
        when(voiceQueryService.listVoices(any(), isNull(), isNull())).thenReturn(List.of());

        mockMvc.perform(get("/api/tts/providers/google/voices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voices.length()").value(0));
    }

    @Test
    void anUnsupportedProviderTypeIs400() throws Exception {
        when(voiceQueryService.listVoices(any(), any(), any())).thenThrow(new TtsException(TtsErrorCode.INVALID_REQUEST,
                "Provider 'piper-local' of type LOCAL_HTTP does not support voice listing"));

        mockMvc.perform(get("/api/tts/providers/piper-local/voices"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Provider 'piper-local' of type LOCAL_HTTP does not support voice listing"));
    }

    @Test
    void anUnknownProviderIs400() throws Exception {
        when(voiceQueryService.listVoices(any(), any(), any())).thenThrow(new TtsException(TtsErrorCode.PROVIDER_NOT_FOUND,
                "No provider configured with name 'gogle'"));

        mockMvc.perform(get("/api/tts/providers/gogle/voices"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PROVIDER_NOT_FOUND"));
    }

    @Test
    void us5_3_anUnavailableCatalogueIs503() throws Exception {
        when(voiceQueryService.listVoices(any(), any(), any())).thenThrow(new TtsException(
                TtsErrorCode.VOICE_CATALOGUE_UNAVAILABLE, "The voice catalogue of provider 'google' is unavailable: HTTP 503"));

        mockMvc.perform(get("/api/tts/providers/google/voices"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("VOICE_CATALOGUE_UNAVAILABLE"));
    }
}
