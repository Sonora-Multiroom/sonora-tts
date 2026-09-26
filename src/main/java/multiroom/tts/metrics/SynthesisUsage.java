package multiroom.tts.metrics;

import multiroom.tts.config.ProviderType;

/**
 * The work one synthesis asks of a provider, measured once, immediately before the provider is
 * called. It is the single measurement of billable usage: the metrics record it, and a future
 * monthly character budget is meant to read this same value at the same point rather than count
 * again.
 *
 * <p>Characters are Unicode code points, not UTF-16 {@code char}s, so an emoji counts as one, as
 * the providers bill it.
 *
 * @param providerName          the configured provider entry's name
 * @param providerType          the entry's type; {@code null} only for a test double
 * @param tier                  the billing tier the provider reported for the resolved settings
 * @param textCharacters        code points of the message
 * @param stylePromptCharacters code points of the style prompt, 0 when there is none
 */
public record SynthesisUsage(String providerName, ProviderType providerType, String tier, int textCharacters,
                             int stylePromptCharacters) {

    /**
     * @param stylePrompt the resolved style prompt, or {@code null} for none
     */
    public static SynthesisUsage of(String providerName, ProviderType type, String tier, String text,
                                    String stylePrompt) {
        return new SynthesisUsage(providerName, type, tier, codePoints(text), codePoints(stylePrompt));
    }

    /** The message and the style prompt together: everything the provider bills for. */
    public int billableCharacters() {
        return textCharacters + stylePromptCharacters;
    }

    private static int codePoints(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }
}
