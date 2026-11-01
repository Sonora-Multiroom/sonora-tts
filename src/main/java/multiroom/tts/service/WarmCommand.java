package multiroom.tts.service;

/**
 * What {@link TtsService#warm} is asked to do. The REST layer's own {@code WarmRequest} maps into
 * this so the service package does not depend on {@code rest}. The fields are a speak request's
 * voice fields, so a warm-up and a later announcement of the same text share one cache entry.
 *
 * @param stylePrompt {@code google-gemini} only. {@code null} = not given; empty or whitespace =
 *                    no prompt
 */
public record WarmCommand(String text, String providerName, String voice, String language, String engine,
                          Double pitch, Double speakingRate, String stylePrompt) {
}
