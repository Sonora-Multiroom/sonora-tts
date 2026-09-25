package multiroom.tts.provider.cloud.google;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A language-region tag in the form Google uses in voice names: {@code uk-UA}, {@code cmn-CN},
 * {@code es-419}. Always held in canonical case — language lower, a letter region
 * upper — so record equality compares tags ignoring case.
 *
 * @param tag the canonical tag
 */
public record GoogleLanguage(String tag) {

    /** The tag's grammar; also the prefix of every full voice name. */
    static final String REGEX = "[A-Za-z]{2,3}-(?:[A-Za-z]{2}|[0-9]{3})";

    private static final Pattern PATTERN = Pattern.compile(REGEX);

    /**
     * @throws IllegalArgumentException if {@code value} is not a language-region tag
     */
    public static GoogleLanguage parse(String value) {
        if (!isWellFormed(value)) {
            throw new IllegalArgumentException("'" + value + "' is not a language-region tag");
        }
        int hyphen = value.indexOf('-');
        return new GoogleLanguage(value.substring(0, hyphen).toLowerCase(Locale.ROOT)
                + "-" + value.substring(hyphen + 1).toUpperCase(Locale.ROOT));
    }

    /** Whether {@code value} is a language-region tag; used by start-up format checks. */
    public static boolean isWellFormed(String value) {
        return value != null && PATTERN.matcher(value).matches();
    }

    @Override
    public String toString() {
        return tag;
    }
}
