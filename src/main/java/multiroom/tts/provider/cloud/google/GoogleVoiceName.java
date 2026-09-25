package multiroom.tts.provider.cloud.google;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A voice as a caller or operator writes it — the spec's <em>Voice Reference</em>: either a
 * {@link Full} name, which carries its own language and engine, or a {@link Short} name, which is
 * completed from an engine and a language.
 *
 * <p>The form decides the kind unambiguously: a short name never contains a hyphen. A full name
 * is split at its <strong>last</strong> hyphen, so engines that contain one ({@code Chirp-HD},
 * {@code Chirp3-HD}) and engines this extension does not recognize both parse. A language with no
 * engine ({@code uk-UA-Charon}) is malformed rather than guessed at.
 *
 * <p>{@link #toString()} is the rule-canonical spelling. The catalogue's own spelling, when it is
 * available, takes precedence over it.
 */
public sealed interface GoogleVoiceName permits GoogleVoiceName.Full, GoogleVoiceName.Short {

    /** {@code {language}-{engine}-{voice}}, engine non-empty and possibly hyphenated. */
    Pattern FULL = Pattern.compile("(" + GoogleLanguage.REGEX + ")-([A-Za-z0-9]+(?:-[A-Za-z0-9]+)*)-([A-Za-z0-9]+)");

    Pattern SHORT = Pattern.compile("[A-Za-z0-9]+");

    /**
     * @throws IllegalArgumentException if {@code value} is neither a full nor a short name
     */
    static GoogleVoiceName parse(String value) {
        if (value != null) {
            Matcher full = FULL.matcher(value);
            if (full.matches()) {
                return new Full(GoogleLanguage.parse(full.group(1)), full.group(2), full.group(3));
            }
            if (SHORT.matcher(value).matches()) {
                return new Short(value);
            }
        }
        throw new IllegalArgumentException("'" + value + "' is neither a full voice name such as "
                + "uk-UA-Chirp3-HD-Charon nor a short voice name such as Charon");
    }

    /** Whether {@code value} parses; used by start-up format checks. */
    static boolean isWellFormed(String value) {
        return value != null && (FULL.matcher(value).matches() || SHORT.matcher(value).matches());
    }

    /** The voice with its first letter capitalized and the rest as written. */
    private static String capitalize(String voice) {
        return voice.substring(0, 1).toUpperCase(Locale.ROOT) + voice.substring(1);
    }

    /**
     * A full voice name.
     *
     * @param language      its language prefix
     * @param engineSegment everything between the language and the voice, as written
     * @param voice         the segment after the last hyphen, as written
     */
    record Full(GoogleLanguage language, String engineSegment, String voice) implements GoogleVoiceName {

        /** The recognized engine, or empty for an unrecognized segment such as {@code Polyglot}. */
        public Optional<GoogleEngine> engine() {
            return GoogleEngine.fromName(engineSegment);
        }

        /** The canonical engine spelling, or the segment as written if it is unrecognized. */
        public String engineSpelling() {
            return engine().map(GoogleEngine::canonical).orElse(engineSegment);
        }

        /** {@code uk-ua-chirp3-hd-charon} → {@code uk-UA-Chirp3-HD-Charon}. */
        public String canonical() {
            return language + "-" + engineSpelling() + "-" + capitalize(voice);
        }

        @Override
        public String toString() {
            return canonical();
        }
    }

    /**
     * A short voice name ({@code Charon}, {@code D}).
     *
     * @param voice the name as written
     */
    record Short(String voice) implements GoogleVoiceName {

        /** Completes this name into a full one: {@code d} + Wavenet + en-US → {@code en-US-Wavenet-D}. */
        public Full compose(GoogleEngine engine, GoogleLanguage language) {
            return new Full(language, engine.canonical(), voice);
        }

        public String canonical() {
            return capitalize(voice);
        }

        @Override
        public String toString() {
            return canonical();
        }
    }
}
