package multiroom.tts.provider;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.provider.cloud.google.CatalogueVoice;

import java.util.List;

/**
 * An optional capability of a {@link TtsProvider}: listing the voices it offers. Kept out of
 * {@code TtsProvider} itself so that the types which cannot list (OpenAI, Piper, local HTTP) do
 * not have to pretend to (interface segregation); callers check for it with {@code instanceof}.
 */
public interface VoiceCatalogueProvider {

    /**
     * Lists the voices this entry offers. For {@code google-cloud}, from the same catalogue that
     * validates voices on synthesis, so a listed voice is an accepted voice, sorted by language,
     * engine, then short name. For {@code google-gemini}, from Google's published list without
     * any check on synthesis, sorted by name.
     *
     * @param language optional language-region tag filter, case-insensitive, already checked for
     *                 form; a {@code google-gemini} entry does not narrow by it
     * @param engine   optional engine filter: an engine alias, or an unrecognized engine segment;
     *                 a {@code google-gemini} entry rejects it
     * @throws TtsException {@link TtsErrorCode#VOICE_CATALOGUE_UNAVAILABLE} when the catalogue
     *                      cannot be fetched now
     */
    List<CatalogueVoice> listVoices(String language, String engine);
}
