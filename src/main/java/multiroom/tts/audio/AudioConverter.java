package multiroom.tts.audio;

import multiroom.api.conversion.FormatConverter;
import multiroom.api.exceptions.FormatConversionException;
import multiroom.api.model.SampleFormat;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

/**
 * Reads a provider's WAV response with the JDK's {@code AudioSystem} and converts it to the
 * system's native format through the injected {@link FormatConverter}.
 *
 * <p>Writes no conversion maths of its own: resampling, channel mixing and bit-depth changes
 * belong to the one shared converter, and no extension may implement its own (constitution I).
 * The converter's returned buffer is borrowed, so its contents are copied before this method returns.
 */
public class AudioConverter {

    private final FormatConverter formatConverter;

    public AudioConverter(FormatConverter formatConverter) {
        this.formatConverter = formatConverter;
    }

    /**
     * Converts a provider's raw WAV response into PCM bytes in {@code targetFormat}.
     *
     * @throws TtsException with {@link TtsErrorCode#FORMAT_NORMALIZATION_FAILED} if the payload
     *                       is not a recognizable WAV file, the shared converter refuses the
     *                       conversion ({@code canConvert} returns {@code false}), or the
     *                       conversion itself fails
     */
    public byte[] convert(byte[] wavBytes, SampleFormat targetFormat) {
        SampleFormat sourceFormat;
        byte[] sourcePcm;
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wavBytes))) {
            sourceFormat = AudioFormatMapper.toSampleFormat(in.getFormat());
            sourcePcm = in.readAllBytes();
        } catch (UnsupportedAudioFileException e) {
            throw new TtsException(TtsErrorCode.FORMAT_NORMALIZATION_FAILED,
                    "Provider response is not a recognizable WAV file", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (!formatConverter.canConvert(sourceFormat, targetFormat)) {
            throw new TtsException(TtsErrorCode.FORMAT_NORMALIZATION_FAILED,
                    "Cannot convert audio from " + sourceFormat + " to " + targetFormat);
        }

        try {
            ByteBuffer converted = formatConverter.convert(ByteBuffer.wrap(sourcePcm), sourceFormat, targetFormat);
            byte[] result = new byte[converted.remaining()];
            converted.get(result);
            return result;
        } catch (FormatConversionException e) {
            throw new TtsException(TtsErrorCode.FORMAT_NORMALIZATION_FAILED,
                    "Format conversion failed: " + e.getMessage(), e);
        }
    }
}
