package multiroom.tts.rest;

import java.util.UUID;

/** {@code 202} body for {@code POST /api/tts/speak}. */
public record SpeakAcceptedResponse(UUID announcementId, boolean cacheHit, int queueDepth) {
}
