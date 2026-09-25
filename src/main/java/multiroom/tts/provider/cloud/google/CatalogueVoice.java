package multiroom.tts.provider.cloud.google;

import java.util.Optional;

/**
 * One voice parsed from Google's {@code GET v1/voices}.
 *
 * @param fullName  the catalogue's own spelling ({@code uk-UA-Chirp3-HD-Charon})
 * @param shortName the part after the last hyphen ({@code Charon}): what to pass as {@code voice}
 *                  together with an engine and a language
 * @param engine    the canonical engine spelling, or the segment as published for an unrecognized
 *                  engine ({@code Polyglot})
 * @param language  the canonical language of the name's prefix — not {@code languageCodes[]}, so
 *                  the catalogue and resolution share one definition of a voice's language
 */
public record CatalogueVoice(String fullName, String shortName, String engine, String language) {

    /**
     * @return the parsed voice, or empty for a name that is not a full voice name (Google publishes
     *         some bare ones, such as {@code Achird}); it could not be requested through this
     *         provider anyway
     */
    public static Optional<CatalogueVoice> fromName(String name) {
        if (name == null || !(GoogleVoiceName.isWellFormed(name)
                && GoogleVoiceName.parse(name) instanceof GoogleVoiceName.Full full)) {
            return Optional.empty();
        }
        return Optional.of(new CatalogueVoice(name, full.voice(), full.engineSpelling(), full.language().tag()));
    }
}
