package multiroom.tts.rest;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.service.AnnounceCommand;
import multiroom.tts.service.AnnounceResult;
import multiroom.tts.service.TtsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TtsController.class)
class TtsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TtsService ttsService;

    @Test
    void validRequestReturns202() throws Exception {
        UUID id = UUID.randomUUID();
        when(ttsService.speak(any())).thenReturn(new AnnounceResult(id, false, 1));

        mockMvc.perform(post("/api/tts/speak")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Dinner is ready","targetName":"living-room","targetType":"SINGLE_OUTPUT"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.announcementId").value(id.toString()))
                .andExpect(jsonPath("$.cacheHit").value(false))
                .andExpect(jsonPath("$.queueDepth").value(1));
    }

    @Test
    void emptyTextReturns400WithInvalidRequestCode() throws Exception {
        mockMvc.perform(post("/api/tts/speak")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"","targetName":"living-room","targetType":"SINGLE_OUTPUT"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void unknownTargetReturns400WithTargetNotFoundCode() throws Exception {
        when(ttsService.speak(any())).thenThrow(
                new TtsException(TtsErrorCode.TARGET_NOT_FOUND, "No output or output group named 'bedroom' found"));

        mockMvc.perform(post("/api/tts/speak")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Hi","targetName":"bedroom","targetType":"SINGLE_OUTPUT"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("TARGET_NOT_FOUND"));
    }

    @Test
    void textOverConfiguredMaximumReturns400() throws Exception {
        when(ttsService.speak(any())).thenThrow(
                new TtsException(TtsErrorCode.INVALID_REQUEST, "Text length 600 exceeds maximum allowed length of 500"));

        mockMvc.perform(post("/api/tts/speak")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"%s","targetName":"living-room","targetType":"SINGLE_OUTPUT"}
                                """.formatted("a".repeat(600))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void unknownProviderReturns400() throws Exception {
        when(ttsService.speak(any())).thenThrow(
                new TtsException(TtsErrorCode.PROVIDER_NOT_FOUND, "No provider configured with name 'my-provider'"));

        mockMvc.perform(post("/api/tts/speak")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Hi","targetName":"living-room","targetType":"SINGLE_OUTPUT","providerName":"my-provider"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PROVIDER_NOT_FOUND"));
    }

    @Test
    void providerTimeoutReturns503() throws Exception {
        when(ttsService.speak(any())).thenThrow(
                new TtsException(TtsErrorCode.PROVIDER_TIMEOUT, "Provider 'openai' did not respond within 10 seconds"));

        mockMvc.perform(post("/api/tts/speak")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Hi","targetName":"living-room","targetType":"SINGLE_OUTPUT"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("PROVIDER_TIMEOUT"));
    }

    @Test
    void outputGroupTargetTypeReturns202() throws Exception {
        when(ttsService.speak(any())).thenReturn(new AnnounceResult(UUID.randomUUID(), false, 1));

        mockMvc.perform(post("/api/tts/speak")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Motion detected","targetName":"all-rooms","targetType":"OUTPUT_GROUP"}
                                """))
                .andExpect(status().isAccepted());
    }

    @Test
    void unknownGroupReturns400() throws Exception {
        when(ttsService.speak(any())).thenThrow(
                new TtsException(TtsErrorCode.TARGET_NOT_FOUND, "No output or output group named 'nowhere' found"));

        mockMvc.perform(post("/api/tts/speak")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Hi","targetName":"nowhere","targetType":"OUTPUT_GROUP"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidTargetTypeValueReturns400() throws Exception {
        mockMvc.perform(post("/api/tts/speak")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Hi","targetName":"living-room","targetType":"NOT_A_REAL_TYPE"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void optionalVoiceAndLanguageFieldsReachTheServiceUnchanged() throws Exception {
        when(ttsService.speak(any())).thenReturn(new AnnounceResult(UUID.randomUUID(), false, 1));

        mockMvc.perform(post("/api/tts/speak")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Hi","targetName":"kitchen","targetType":"SINGLE_OUTPUT",
                                 "providerName":"piper-local","voice":"en_US-ryan-medium","language":"en-US"}
                                """))
                .andExpect(status().isAccepted());

        verify(ttsService).speak(eq(new AnnounceCommand("Hi", "kitchen",
                multiroom.api.model.TargetType.SINGLE_OUTPUT, "piper-local", "en_US-ryan-medium", "en-US")));
    }
}
