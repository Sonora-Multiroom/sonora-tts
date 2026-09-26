package multiroom.tts.provider;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.config.ProviderType;

/**
 * SPI for a text-to-speech backend, cloud or local.
 *
 * <p>Implementations must build their transport (an {@code HttpClient}, a validated binary
 * path) in their constructor and contact nothing until {@link #synthesize} is called — an
 * unreachable provider at boot must not abort host start-up.
 */
public interface TtsProvider {

    /**
     * Combines per-request overrides with this provider entry's configuration into the settings
     * the cache key and the synthesis request are built from.
     *
     * <p>Called on <strong>every</strong> request, before the cache lookup, so it must be pure:
     * no I/O of any kind. Anything that needs the network (such as checking that a voice exists)
     * belongs in {@link #synthesize}, which runs only on a cache miss.
     *
     * @param requested the request's overrides; each {@code null} field means "use the default"
     * @return the resolved settings
     * @throws TtsException {@link TtsErrorCode#INVALID_REQUEST} for a caller error, such as an
     *                      override this provider type does not support
     */
    SynthesisSettings resolveSettings(RequestedSettings requested);

    /**
     * Synthesizes speech for the given request.
     *
     * @param request what to say and in what voice/language
     * @return the provider's raw WAV response
     * @throws TtsException on timeout, rate limiting, or any other provider-side failure
     */
    SynthesisResult synthesize(SynthesisRequest request);

    /** This entry's type, as configured; the {@code type} tag of its metrics. */
    ProviderType type();

    /**
     * The price tier the provider bills these settings at, the {@code tier} tag of the characters
     * sent. {@code none} for a provider with a single tier or no billing.
     *
     * <p>Pure, like {@link #resolveSettings}: no I/O. The value must come from the resolved
     * settings or the entry's configuration and belong to a small fixed set, never from caller
     * free text such as a voice name, because every distinct value is a new metric series.
     */
    default String billingTier(SynthesisSettings settings) {
        return "none";
    }
}
