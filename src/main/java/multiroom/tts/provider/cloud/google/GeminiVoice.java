package multiroom.tts.provider.cloud.google;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A Gemini voice name such as {@code Kore}, canonicalized to a leading capital with the rest
 * lower-case, both in {@link Locale#ROOT}. Only ASCII letters are accepted so the case mapping is
 * locale-independent — no Turkish dotless i, and no length change — and every Gemini voice Google
 * lists has this form.
 *
 * @param name the canonical spelling
 */
public record GeminiVoice(String name) {

    private static final Pattern PATTERN = Pattern.compile("[A-Za-z]+");

    /**
     * @throws IllegalArgumentException if {@code value} is not one word of ASCII letters
     */
    public static GeminiVoice parse(String value) {
        if (!isWellFormed(value)) {
            throw new IllegalArgumentException("'" + value + "' is not a Gemini voice name");
        }
        return new GeminiVoice(value.substring(0, 1).toUpperCase(Locale.ROOT)
                + value.substring(1).toLowerCase(Locale.ROOT));
    }

    /** Whether {@code value} is one word of ASCII letters; used by start-up format checks. */
    public static boolean isWellFormed(String value) {
        return value != null && PATTERN.matcher(value).matches();
    }

    @Override
    public String toString() {
        return name;
    }
}
