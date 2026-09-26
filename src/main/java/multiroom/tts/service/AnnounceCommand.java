package multiroom.tts.service;

import multiroom.api.model.TargetType;

/**
 * What {@link TtsService#speak} is asked to do — the REST layer's own {@code SpeakRequest} maps
 * into this so the service package does not depend on {@code rest}.
 *
 * @param stylePrompt {@code google-gemini} only. {@code null} = not given; empty or whitespace =
 *                    no prompt for this announcement
 */
public record AnnounceCommand(String text, String targetName, TargetType targetType, String providerName,
                               String voice, String language, String engine, Double pitch,
                               Double speakingRate, String stylePrompt) {

    /** The pre-004 shape, for every caller that never sends a style prompt. */
    public AnnounceCommand(String text, String targetName, TargetType targetType, String providerName,
                           String voice, String language, String engine, Double pitch, Double speakingRate) {
        this(text, targetName, targetType, providerName, voice, language, engine, pitch, speakingRate, null);
    }
}
