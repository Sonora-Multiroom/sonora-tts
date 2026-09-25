package multiroom.tts.service;

import multiroom.api.model.TargetType;

/**
 * What {@link TtsService#speak} is asked to do — the REST layer's own {@code SpeakRequest} maps
 * into this so the service package does not depend on {@code rest}.
 */
public record AnnounceCommand(String text, String targetName, TargetType targetType, String providerName,
                               String voice, String language, String engine, Double pitch,
                               Double speakingRate) {
}
