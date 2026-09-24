package multiroom.tts.audio;

import multiroom.api.model.SampleFormat;
import multiroom.api.model.SampleType;

import javax.sound.sampled.AudioFormat;

/** Maps between the JDK's {@link AudioFormat} and the public API's {@link SampleFormat}. */
final class AudioFormatMapper {

    private AudioFormatMapper() {
    }

    static SampleFormat toSampleFormat(AudioFormat audioFormat) {
        SampleType sampleType = audioFormat.getEncoding() == AudioFormat.Encoding.PCM_FLOAT
                ? SampleType.FLOAT
                : SampleType.PCM;
        return new SampleFormat(
                Math.round(audioFormat.getSampleRate()),
                audioFormat.getSampleSizeInBits(),
                audioFormat.getChannels(),
                sampleType);
    }

    static AudioFormat toAudioFormat(SampleFormat sampleFormat) {
        AudioFormat.Encoding encoding = sampleFormat.sampleType() == SampleType.FLOAT
                ? AudioFormat.Encoding.PCM_FLOAT
                : AudioFormat.Encoding.PCM_SIGNED;
        return new AudioFormat(
                encoding,
                sampleFormat.sampleRate(),
                sampleFormat.bitDepth(),
                sampleFormat.channels(),
                sampleFormat.bytesPerFrame(),
                sampleFormat.sampleRate(),
                false);
    }
}
