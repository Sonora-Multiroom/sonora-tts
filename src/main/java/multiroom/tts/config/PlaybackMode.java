package multiroom.tts.config;

import multiroom.api.model.RouteJoinMode;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The subset of the host's {@link RouteJoinMode}s an announcement may use. Replace is excluded on
 * purpose: it would silence the room after the announcement, or need the stop-and-restore
 * behaviour that joining a route replaced.
 */
public enum PlaybackMode {
    /** Lowers what already plays on the target for the length of the announcement. */
    DUCK_OTHERS,
    /** Plays alongside what already plays, at its own level. */
    MIX;

    /** The spelling used in configuration and in the request ({@code duck-others}). */
    public String configName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Matches {@link #configName()} ignoring case; no other spelling is accepted. */
    public static Optional<PlaybackMode> fromName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(m -> m.configName().equalsIgnoreCase(name)).findFirst();
    }

    /** The accepted names, for error messages. */
    public static String supportedList() {
        return Arrays.stream(values()).map(PlaybackMode::configName).collect(Collectors.joining(", "));
    }

    /** The host join mode this announcement mode maps to. */
    public RouteJoinMode joinMode() {
        return switch (this) {
            case DUCK_OTHERS -> RouteJoinMode.DUCK_OTHERS;
            case MIX -> RouteJoinMode.MIX;
        };
    }
}
