package multiroom.tts.provider.cloud.google;

import java.util.regex.Pattern;

/**
 * A Gemini-TTS model name, such as {@code gemini-2.5-flash-tts}. Google adds and retires models
 * often, so only the form is checked, never against a closed list; the value is never changed or
 * case-folded, so what Google receives is exactly what the operator wrote. Checked at start-up
 * only — a request cannot set the model.
 *
 * @param name the model name, unchanged
 */
public record GeminiModel(String name) {

    private static final Pattern PATTERN = Pattern.compile("[a-z0-9][a-z0-9.-]*");

    /**
     * @throws IllegalArgumentException if {@code value} is not a well-formed model name
     */
    public static GeminiModel parse(String value) {
        if (!isWellFormed(value)) {
            throw new IllegalArgumentException("'" + value + "' is not a Gemini model name");
        }
        return new GeminiModel(value);
    }

    /**
     * Whether {@code value} is one token of lower-case letters, digits, dots and hyphens, starting
     * with a letter or digit; used by start-up format checks.
     */
    public static boolean isWellFormed(String value) {
        return value != null && PATTERN.matcher(value).matches();
    }

    @Override
    public String toString() {
        return name;
    }
}
