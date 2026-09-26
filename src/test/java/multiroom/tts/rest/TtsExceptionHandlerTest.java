package multiroom.tts.rest;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.metrics.TtsMetrics;
import multiroom.tts.service.TtsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every {@link TtsErrorCode} the contract publishes maps to exactly the status
 * {@code contracts/tts-rest-api.yaml} declares — caller-fixable codes to 400, provider-side codes
 * to 503 — and the body always carries both {@code error} and {@code message}.
 */
@WebMvcTest(TtsController.class)
class TtsExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TtsService ttsService;

    /** The advice takes it, and every {@code @WebMvcTest} picks the advice up. */
    @MockBean
    private TtsMetrics metrics;

    private static int expectedStatus(TtsErrorCode code) {
        return switch (code) {
            case INVALID_REQUEST, TARGET_NOT_FOUND, PROVIDER_NOT_FOUND, INVALID_VOICE -> 400;
            case PROVIDER_TIMEOUT, PROVIDER_RATE_LIMITED, PROVIDER_ERROR, FORMAT_NORMALIZATION_FAILED,
                    VOICE_CATALOGUE_UNAVAILABLE -> 503;
        };
    }

    @ParameterizedTest
    @EnumSource(TtsErrorCode.class)
    void everyErrorCodeMapsToItsPublishedStatusAndCarriesBothFields(TtsErrorCode code) throws Exception {
        when(ttsService.speak(any())).thenThrow(new TtsException(code, "boom: " + code));

        mockMvc.perform(post("/api/tts/speak")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Hi","targetName":"living-room","targetType":"SINGLE_OUTPUT"}
                                """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .is(expectedStatus(code)))
                .andExpect(jsonPath("$.error").value(code.name()))
                .andExpect(jsonPath("$.message").value("boom: " + code));
    }

    // --- 005: rejections before the service runs are counted ---------------------------------

    private void expectUnreadableBody(String body) throws Exception {
        mockMvc.perform(post("/api/tts/speak").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request body could not be read"));
        verify(metrics).announcementRejected("unknown", "INVALID_REQUEST", "none");
        verifyNoInteractions(ttsService);
    }

    @Test
    void aBodyThatIsNotJsonGetsTheContractsErrorResponseAndIsCounted() throws Exception {
        expectUnreadableBody("{");
    }

    @Test
    void anUnknownTargetTypeGetsTheContractsErrorResponseAndIsCounted() throws Exception {
        expectUnreadableBody("""
                {"text":"Hi","targetName":"living-room","targetType":"BOGUS"}
                """);
    }

    @Test
    void aBeanValidationFailureIsCounted() throws Exception {
        mockMvc.perform(post("/api/tts/speak").contentType(MediaType.APPLICATION_JSON).content("""
                        {"text":"","targetName":"living-room","targetType":"SINGLE_OUTPUT"}
                        """))
                .andExpect(status().isBadRequest());

        verify(metrics).announcementRejected("unknown", "INVALID_REQUEST", "none");
    }

    @Test
    void anErrorTheServiceAlreadyCountedIsNotCountedAgain() throws Exception {
        when(ttsService.speak(any())).thenThrow(new TtsException(TtsErrorCode.TARGET_NOT_FOUND, "missing"));

        mockMvc.perform(post("/api/tts/speak").contentType(MediaType.APPLICATION_JSON).content("""
                        {"text":"Hi","targetName":"bedroom","targetType":"SINGLE_OUTPUT"}
                        """))
                .andExpect(status().isBadRequest());

        verify(metrics, never()).announcementRejected(any(), any(), any());
    }

    @Test
    void aFailureOnAnotherControllerIsNotAnAnnouncement() throws Exception {
        TtsMetrics recorder = mock(TtsMetrics.class);
        TtsExceptionHandler handler = new TtsExceptionHandler(recorder);
        java.lang.reflect.Method clearCache = TtsCacheController.class.getMethod("clearCache", String.class);
        HandlerMethod cacheHandler = new HandlerMethod(mock(TtsCacheController.class), clearCache);

        ResponseEntity<ErrorResponse> invalid = handler.handleValidationFailure(new MethodArgumentNotValidException(
                new MethodParameter(clearCache, 0), new BeanPropertyBindingResult(new Object(), "request")),
                cacheHandler);
        ResponseEntity<ErrorResponse> unreadable = handler.handleUnreadableBody(new HttpMessageNotReadableException(
                "bad", new ServletServerHttpRequest(new MockHttpServletRequest())), cacheHandler);

        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unreadable.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(recorder);
    }
}
