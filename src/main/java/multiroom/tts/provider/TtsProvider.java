package multiroom.tts.provider;

import multiroom.tts.TtsException;

/**
 * SPI for a text-to-speech backend, cloud or local.
 *
 * <p>Implementations must build their transport (an {@code HttpClient}, a validated binary
 * path) in their constructor and contact nothing until {@link #synthesize} is called — an
 * unreachable provider at boot must not abort host start-up (019 FR-019).
 */
public interface TtsProvider {

    /**
     * Synthesizes speech for the given request.
     *
     * @param request what to say and in what voice/language
     * @return the provider's raw WAV response
     * @throws TtsException on timeout, rate limiting, or any other provider-side failure
     */
    SynthesisResult synthesize(SynthesisRequest request);
}
