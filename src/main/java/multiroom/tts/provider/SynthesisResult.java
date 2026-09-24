package multiroom.tts.provider;

/**
 * What a {@link TtsProvider} hands back: its raw WAV response plus the format it claims to be in.
 *
 * @param audioData  the provider's WAV response, as received
 * @param sampleRate actual sample rate of the audio
 * @param channels   number of channels
 * @param bitDepth   bits per sample
 */
public record SynthesisResult(byte[] audioData, int sampleRate, int channels, int bitDepth) {
}
