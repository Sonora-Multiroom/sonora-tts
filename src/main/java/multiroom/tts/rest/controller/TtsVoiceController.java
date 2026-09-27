package multiroom.tts.rest.controller;

import multiroom.tts.rest.api.TtsVoiceApi;
import multiroom.tts.rest.dto.VoiceListResponse;
import multiroom.tts.service.VoiceQueryService;
import org.springframework.web.bind.annotation.RestController;

/** Implements {@link TtsVoiceApi} over the {@link VoiceQueryService}. */
@RestController
public class TtsVoiceController implements TtsVoiceApi {

    private final VoiceQueryService voiceQueryService;

    public TtsVoiceController(VoiceQueryService voiceQueryService) {
        this.voiceQueryService = voiceQueryService;
    }

    @Override
    public VoiceListResponse listVoices(String providerName, String language, String engine) {
        return VoiceListResponse.of(providerName, voiceQueryService.listVoices(providerName, language, engine));
    }
}
