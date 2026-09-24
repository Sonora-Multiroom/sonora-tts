package multiroom.tts.service;

import java.util.UUID;

/** What {@link TtsService#speak} returns once the audio exists and is queued to play. */
public record AnnounceResult(UUID announcementId, boolean cacheHit, int queueDepth) {
}
