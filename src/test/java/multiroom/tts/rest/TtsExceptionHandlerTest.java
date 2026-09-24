package multiroom.tts.rest;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.service.TtsService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

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

    private static int expectedStatus(TtsErrorCode code) {
        return switch (code) {
            case INVALID_REQUEST, TARGET_NOT_FOUND, PROVIDER_NOT_FOUND -> 400;
            case PROVIDER_TIMEOUT, PROVIDER_RATE_LIMITED, PROVIDER_ERROR, FORMAT_NORMALIZATION_FAILED -> 503;
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
}
