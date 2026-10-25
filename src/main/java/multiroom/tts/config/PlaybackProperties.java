package multiroom.tts.config;

import lombok.Data;

/** Bound from {@code multiroom.tts.playback}. */
@Data
public class PlaybackProperties {

    private String defaultMode = "duck-others";

    /**
     * Route volume (0-100) of every announcement, replacing the target outputs' volume for it only.
     * {@code null}: the announcement follows the outputs' volume. A speak request's {@code routeVolume}
     * wins.
     */
    private Integer routeVolume;

    /**
     * Ducking level in dB (-60.0 to 0.0) for {@code duck-others} announcements. {@code null}: the hub's
     * input default and system level apply. Never sent for a {@code mix} announcement. A speak request's
     * {@code duckingLevelDb} wins.
     */
    private Double duckingLevelDb;

    /** Only call after {@link TtsProperties#validate()} has accepted {@link #defaultMode}. */
    public PlaybackMode resolvedDefaultMode() {
        return PlaybackMode.fromName(defaultMode).orElseThrow();
    }
}
