package multiroom.tts.rest.controller;

import multiroom.tts.cache.AudioCache;
import multiroom.tts.cache.CacheStats;
import multiroom.tts.config.ProviderType;
import multiroom.tts.config.TtsProperties;
import multiroom.tts.config.TtsProviderConfig;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.metrics.TtsMetrics;
import multiroom.tts.service.TtsService;
import multiroom.tts.service.WarmCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TtsCacheController.class)
@Import(TtsCacheControllerTest.TestConfig.class)
class TtsCacheControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AudioCache audioCache;

    @MockBean
    private TtsService ttsService;

    /** The advice takes it, and every {@code @WebMvcTest} picks the advice up. */
    @MockBean
    private TtsMetrics metrics;

    @Test
    void deleteWithNoProviderClearsEverythingAndReturns204() throws Exception {
        mockMvc.perform(delete("/api/tts/cache")).andExpect(status().isNoContent());

        verify(audioCache).clear();
    }

    @Test
    void deleteWithKnownProviderClearsOnlyThatProvider() throws Exception {
        mockMvc.perform(delete("/api/tts/cache").param("providerName", "openai"))
                .andExpect(status().isNoContent());

        verify(audioCache).clearByProvider("openai");
    }

    @Test
    void deleteWithUnknownProviderReturns400AndClearsNothing() throws Exception {
        mockMvc.perform(delete("/api/tts/cache").param("providerName", "does-not-exist"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PROVIDER_NOT_FOUND"));

        verify(audioCache, org.mockito.Mockito.never()).clearByProvider(org.mockito.ArgumentMatchers.any());
        verify(audioCache, org.mockito.Mockito.never()).clear();
    }

    @Test
    void statsReturnsThePublishedShape() throws Exception {
        when(audioCache.stats()).thenReturn(new CacheStats(42, 15_728_640L, 524_288_000L,
                Map.of("openai", 35, "piper-local", 7)));

        mockMvc.perform(get("/api/tts/cache/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEntries").value(42))
                .andExpect(jsonPath("$.totalSizeBytes").value(15_728_640))
                .andExpect(jsonPath("$.maxSizeBytes").value(524_288_000))
                .andExpect(jsonPath("$.entriesByProvider.openai").value(35));
    }

    static class TestConfig {
        @Bean
        TtsProperties ttsProperties() {
            TtsProperties properties = new TtsProperties();
            TtsProviderConfig openai = new TtsProviderConfig();
            openai.setName("openai");
            openai.setType(ProviderType.OPENAI);
            openai.setApiKey("key");
            properties.setProviders(List.of(openai));
            return properties;
        }
    }

    // --- warm

    private static final String WARM_URL = "/api/tts/cache/warm";

    @Test
    void warmAnswers200WithOnlyCacheHit() throws Exception {
        when(ttsService.warm(any())).thenReturn(false);

        mockMvc.perform(post(WARM_URL).contentType("application/json").content("{\"text\":\"Hello\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .json("{\"cacheHit\":false}", true));
    }

    @Test
    void warmPassesEveryVoiceFieldToTheService() throws Exception {
        when(ttsService.warm(any())).thenReturn(true);

        mockMvc.perform(post(WARM_URL).contentType("application/json").content("""
                {"text":"Hello","providerName":"openai","voice":"alloy","language":"en-US","engine":"e",
                 "pitch":1.5,"speakingRate":0.9,"stylePrompt":"warm"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cacheHit").value(true));

        verify(ttsService).warm(new WarmCommand("Hello", "openai", "alloy", "en-US", "e", 1.5, 0.9, "warm"));
    }

    @Test
    void warmIgnoresSpeakOnlyFieldsSoASpeakBodyCanBeReused() throws Exception {
        when(ttsService.warm(any())).thenReturn(false);

        mockMvc.perform(post(WARM_URL).contentType("application/json").content("""
                {"text":"Hello","targetName":"kitchen","targetType":"nonsense","playbackMode":"mix",
                 "routeVolume":50,"duckingLevelDb":-20}"""))
                .andExpect(status().isOk());
    }

    @Test
    void aFailedStoreAnswers503WithBothFields() throws Exception {
        when(ttsService.warm(any())).thenThrow(new TtsException(TtsErrorCode.CACHE_WRITE_FAILED, "not stored"));

        mockMvc.perform(post(WARM_URL).contentType("application/json").content("{\"text\":\"Hello\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("CACHE_WRITE_FAILED"))
                .andExpect(jsonPath("$.message").value("not stored"));
    }

    @Test
    void warmWithBlankTextOrAnUnreadableBodyAnswers400() throws Exception {
        mockMvc.perform(post(WARM_URL).contentType("application/json").content("{\"text\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        mockMvc.perform(post(WARM_URL).contentType("application/json").content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }
}
