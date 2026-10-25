package multiroom.tts.service;

import multiroom.api.model.TargetType;

/**
 * What {@link TtsService#speak} is asked to do — the REST layer's own {@code SpeakRequest} maps
 * into this so the service package does not depend on {@code rest}.
 *
 * @param stylePrompt {@code google-gemini} only. {@code null} = not given; empty or whitespace =
 *                    no prompt for this announcement
 * @param playbackMode {@code null} = not given, so the configured default applies
 * @param routeVolume  0-100; {@code null} = not given, so {@code tts.playback.route-volume} applies
 * @param duckingLevelDb dB, -60.0 to 0.0, only with {@code duck-others}; {@code null} = not given, so
 *                       {@code tts.playback.ducking-level-db} applies
 */
public record AnnounceCommand(String text, String targetName, TargetType targetType, String providerName,
                               String voice, String language, String engine, Double pitch,
                               Double speakingRate, String stylePrompt, String playbackMode,
                               Integer routeVolume, Double duckingLevelDb) {

    /** The pre-034 shape, for every caller that names no route volume or ducking level. */
    public AnnounceCommand(String text, String targetName, TargetType targetType, String providerName,
                           String voice, String language, String engine, Double pitch, Double speakingRate,
                           String stylePrompt, String playbackMode) {
        this(text, targetName, targetType, providerName, voice, language, engine, pitch, speakingRate,
                stylePrompt, playbackMode, null, null);
    }

    /** The pre-004 shape, for every caller that never sends a style prompt. */
    public AnnounceCommand(String text, String targetName, TargetType targetType, String providerName,
                           String voice, String language, String engine, Double pitch, Double speakingRate) {
        this(text, targetName, targetType, providerName, voice, language, engine, pitch, speakingRate, null, null, null, null);
    }

    /** The pre-006 shape, for every caller that never names a playback mode. */
    public AnnounceCommand(String text, String targetName, TargetType targetType, String providerName,
                           String voice, String language, String engine, Double pitch, Double speakingRate,
                           String stylePrompt) {
        this(text, targetName, targetType, providerName, voice, language, engine, pitch, speakingRate,
                stylePrompt, null, null, null);
    }
}
