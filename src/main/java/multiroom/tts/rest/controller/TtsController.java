package multiroom.tts.rest.controller;

import multiroom.tts.rest.api.TtsApi;
import multiroom.tts.rest.dto.SpeakAcceptedResponse;
import multiroom.tts.rest.dto.SpeakRequest;
import multiroom.tts.service.AnnounceCommand;
import multiroom.tts.service.AnnounceResult;
import multiroom.tts.service.TtsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Implements {@link TtsApi}: maps the request onto {@link TtsService}. */
@RestController
public class TtsController implements TtsApi {

    private final TtsService ttsService;

    public TtsController(TtsService ttsService) {
        this.ttsService = ttsService;
    }

    @Override
    public ResponseEntity<SpeakAcceptedResponse> speak(SpeakRequest request) {
        AnnounceResult result = ttsService.speak(new AnnounceCommand(
                request.text(), request.targetName(), request.targetType(),
                request.providerName(), request.voice(), request.language(),
                request.engine(), request.pitch(), request.speakingRate(), request.stylePrompt()));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new SpeakAcceptedResponse(result.announcementId(), result.cacheHit(), result.queueDepth()));
    }
}
