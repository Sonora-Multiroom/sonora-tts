package multiroom.tts.provider.cloud.google;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * One voice parsed from Google's {@code GET v1/voices}.
 *
 * @param fullName  the catalogue's own spelling ({@code uk-UA-Chirp3-HD-Charon}); for a Gemini
 *                  voice, its canonical name ({@code Kore})
 * @param shortName the part after the last hyphen ({@code Charon}): what to pass as {@code voice}
 *                  together with an engine and a language; for a Gemini voice, the same as
 *                  {@code fullName}
 * @param engine    the canonical engine spelling, or the segment as published for an unrecognized
 *                  engine ({@code Polyglot}); for a Gemini voice, the entry's model
 * @param language  the canonical language of the name's prefix — not {@code languageCodes[]}, so
 *                  the catalogue and resolution share one definition of a voice's language;
 *                  {@code null} for a Gemini voice, whose published {@code en-US} does not say
 *                  which languages it speaks
 * @param gender    Google's {@code ssmlGender} ({@code FEMALE}, {@code MALE}, …), or {@code null}
 *                  when Google gives none
 */
public record CatalogueVoice(String fullName, String shortName, String engine, String language, String gender) {

    /** A voice with no gender, as 002 parsed it. */
    public CatalogueVoice(String fullName, String shortName, String engine, String language) {
        this(fullName, shortName, engine, language, null);
    }

    /**
     * {@code google-cloud}'s reading of one {@code voices[]} element.
     *
     * @return the parsed voice, or empty for a name that is not a full voice name (Google publishes
     *         the Gemini voices as bare ones, such as {@code Achird}); it could not be requested
     *         through a {@code google-cloud} entry anyway
     */
    public static Optional<CatalogueVoice> fromJson(JsonNode voice) {
        String name = voice.path("name").asText(null);
        if (name == null || !(GoogleVoiceName.isWellFormed(name)
                && GoogleVoiceName.parse(name) instanceof GoogleVoiceName.Full full)) {
            return Optional.empty();
        }
        return Optional.of(new CatalogueVoice(name, full.voice(), full.engineSpelling(), full.language().tag(),
                gender(voice)));
    }

    /** {@code ssmlGender}, or {@code null} when the element has none. */
    static String gender(JsonNode voice) {
        return voice.path("ssmlGender").asText(null);
    }
}
