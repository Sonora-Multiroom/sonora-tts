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
     * Lists the voices from the same catalogue that validates voices on synthesis, so a listed
     * voice is an accepted voice. Sorted by language, engine, then short name.
     *
     * @param language optional language-region tag filter, case-insensitive
     * @param engine   optional engine filter: an engine alias, or an unrecognized engine segment
     * @throws TtsException {@link TtsErrorCode#VOICE_CATALOGUE_UNAVAILABLE} when the catalogue
     *                      cannot be fetched now
     */
    List<CatalogueVoice> listVoices(String language, String engine);
}
