package multiroom.tts.provider.cloud.google;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The Google voice families this extension can compose short voice names for. An
 * engine segment of a full name that matches none of these (such as {@code Polyglot} or
 * {@code News}) is an <em>unrecognized engine</em>: still usable through full names, but never a
 * constant here.
 */
public enum GoogleEngine {
    STANDARD("Standard"),
    WAVENET("Wavenet"),
    NEURAL2("Neural2"),
    STUDIO("Studio"),
    CHIRP_HD("Chirp-HD"),
    CHIRP3_HD("Chirp3-HD");

    private final String canonical;

    GoogleEngine(String canonical) {
        this.canonical = canonical;
    }

    /** Google's own spelling, as it appears in voice names ({@code Chirp3-HD}). */
    public String canonical() {
        return canonical;
    }

    /**
     * Looks an engine up by any common spelling. Case, {@code -} and {@code _} are ignored, which
     * covers {@code WaveNet}, {@code chirp3-hd}, {@code Chirp3_HD} and {@code chirp3hd} without
     * keeping a per-engine alias list.
     */
    public static Optional<GoogleEngine> fromName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String folded = fold(name);
        return Arrays.stream(values())
                .filter(engine -> fold(engine.canonical).equals(folded))
                .findFirst();
    }

    /** {@code "Standard, Wavenet, Neural2, Studio, Chirp-HD, Chirp3-HD"}, for error messages. */
    public static String supportedList() {
        return Arrays.stream(values()).map(GoogleEngine::canonical).collect(Collectors.joining(", "));
    }

    private static String fold(String name) {
        return name.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }
}
