package multiroom.tts.config;

import lombok.Data;

/** Bound from {@code multiroom.tts.playback}. */
@Data
public class PlaybackProperties {

    private String defaultMode = "duck-others";

    /** Only call after {@link TtsProperties#validate()} has accepted {@link #defaultMode}. */
    public PlaybackMode resolvedDefaultMode() {
        return PlaybackMode.fromName(defaultMode).orElseThrow();
    }
}
