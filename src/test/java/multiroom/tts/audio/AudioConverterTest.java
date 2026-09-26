package multiroom.tts.audio;

import multiroom.api.conversion.FormatConverter;
import multiroom.api.exceptions.FormatConversionException;
import multiroom.api.model.SampleFormat;
import multiroom.api.model.SampleType;
import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AudioConverterTest {

    private static byte[] wavBytes(int sampleRate, int bitDepth, int channels, byte[] pcm) throws Exception {
        AudioFormat format = new AudioFormat(sampleRate, bitDepth, channels, true, false);
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(pcm), format,
                pcm.length / format.getFrameSize())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, out);
            return out.toByteArray();
        }
    }

    @Test
    void mapsOpenAiWavFormatAndDelegatesToInjectedConverter() throws Exception {
        byte[] pcm = {1, 2, 3, 4};
        byte[] wav = wavBytes(24000, 16, 1, pcm);
        SampleFormat target = SampleFormat.standard();
        SampleFormat expectedSource = new SampleFormat(24000, 16, 1, SampleType.PCM);

        FormatConverter formatConverter = mock(FormatConverter.class);
        when(formatConverter.canConvert(any(), any())).thenReturn(true);
        byte[] convertedBytes = {9, 9, 9, 9};
        when(formatConverter.convert(any(), any(), any())).thenReturn(ByteBuffer.wrap(convertedBytes));

        byte[] result = new AudioConverter(formatConverter).convert(wav, target);

        assertThat(result).isEqualTo(convertedBytes);
        verify(formatConverter).canConvert(expectedSource, target);
        verify(formatConverter).convert(any(), eq(expectedSource), eq(target));
    }

    @Test
    void mapsPiperWavFormat() throws Exception {
        byte[] wav = wavBytes(22050, 16, 1, new byte[] {5, 6});
        SampleFormat expectedSource = new SampleFormat(22050, 16, 1, SampleType.PCM);
        FormatConverter formatConverter = mock(FormatConverter.class);
        when(formatConverter.canConvert(any(), any())).thenReturn(true);
        when(formatConverter.convert(any(), any(), any())).thenReturn(ByteBuffer.wrap(new byte[] {0}));

        new AudioConverter(formatConverter).convert(wav, SampleFormat.standard());

        verify(formatConverter).canConvert(expectedSource, SampleFormat.standard());
    }

    @Test
    void mapsGoogleLinear16Format() throws Exception {
        byte[] wav = wavBytes(24000, 16, 1, new byte[] {7, 8});
        SampleFormat expectedSource = new SampleFormat(24000, 16, 1, SampleType.PCM);
        FormatConverter formatConverter = mock(FormatConverter.class);
        when(formatConverter.canConvert(any(), any())).thenReturn(true);
        when(formatConverter.convert(any(), any(), any())).thenReturn(ByteBuffer.wrap(new byte[] {0}));

        new AudioConverter(formatConverter).convert(wav, SampleFormat.standard());

        verify(formatConverter).canConvert(expectedSource, SampleFormat.standard());
    }

    @Test
    void aGeminiTargetRateNeverChangesWhatSourceRateIsRead() throws Exception {
        // Gemini's native rate (24 kHz) reaches the converter as the source format, whatever
        // sample rate the request asked for — the WAV header is authoritative, not the request.
        byte[] wav = wavBytes(24000, 16, 1, new byte[] {1, 2});
        SampleFormat expectedSource = new SampleFormat(24000, 16, 1, SampleType.PCM);
        FormatConverter formatConverter = mock(FormatConverter.class);
        when(formatConverter.canConvert(any(), any())).thenReturn(true);
        when(formatConverter.convert(any(), any(), any())).thenReturn(ByteBuffer.wrap(new byte[] {0}));

        new AudioConverter(formatConverter).convert(wav, SampleFormat.standard());

        verify(formatConverter).canConvert(expectedSource, SampleFormat.standard());
        verify(formatConverter).convert(any(), eq(expectedSource), eq(SampleFormat.standard()));
    }

    @Test
    void nonWavPayloadRaisesFormatNormalizationFailed() {
        FormatConverter formatConverter = mock(FormatConverter.class);
        AudioConverter converter = new AudioConverter(formatConverter);
        byte[] notWav = "this is not audio".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> converter.convert(notWav, SampleFormat.standard()))
                .isInstanceOf(TtsException.class)
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.FORMAT_NORMALIZATION_FAILED);
    }

    @Test
    void canConvertFalseRaisesFormatNormalizationFailed() throws Exception {
        byte[] wav = wavBytes(24000, 16, 1, new byte[] {1, 2});
        FormatConverter formatConverter = mock(FormatConverter.class);
        when(formatConverter.canConvert(any(), any())).thenReturn(false);

        AudioConverter converter = new AudioConverter(formatConverter);

        assertThatThrownBy(() -> converter.convert(wav, SampleFormat.standard()))
                .isInstanceOf(TtsException.class)
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.FORMAT_NORMALIZATION_FAILED);
    }

    @Test
    void converterThrowingFormatConversionExceptionSurfacesAsRejection() throws Exception {
        byte[] wav = wavBytes(24000, 16, 1, new byte[] {1, 2});
        FormatConverter formatConverter = mock(FormatConverter.class);
        when(formatConverter.canConvert(any(), any())).thenReturn(true);
        when(formatConverter.convert(any(), any(), any())).thenThrow(new FormatConversionException("boom"));

        AudioConverter converter = new AudioConverter(formatConverter);

        assertThatThrownBy(() -> converter.convert(wav, SampleFormat.standard()))
                .isInstanceOf(TtsException.class)
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.FORMAT_NORMALIZATION_FAILED);
    }

    @Test
    void doesNoResamplingOrBitDepthMathsOfItsOwn() throws Exception {
        byte[] pcm = {11, 22, 33, 44};
        byte[] wav = wavBytes(24000, 16, 1, pcm);
        FormatConverter formatConverter = mock(FormatConverter.class);
        when(formatConverter.canConvert(any(), any())).thenReturn(true);
        when(formatConverter.convert(any(), any(), any())).thenAnswer(invocation -> {
            ByteBuffer input = invocation.getArgument(0);
            byte[] captured = new byte[input.remaining()];
            input.get(captured);
            assertThat(captured).isEqualTo(pcm);
            return ByteBuffer.wrap(new byte[] {0});
        });

        new AudioConverter(formatConverter).convert(wav, SampleFormat.standard());
    }
}
