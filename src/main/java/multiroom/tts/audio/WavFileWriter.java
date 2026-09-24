package multiroom.tts.audio;

import multiroom.api.model.SampleFormat;
import multiroom.api.model.SampleType;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes PCM/float bytes plus a {@link SampleFormat} to a RIFF/WAV file.
 *
 * <p>The write is atomic: a temp file in the target's own directory is written first, then moved
 * into place with {@link StandardCopyOption#ATOMIC_MOVE}, so a reader — the resolver, or the
 * JDK's own {@code AudioSystem} on read-back — never observes a partially written file.
 */
public final class WavFileWriter {

    private static final int HEADER_LENGTH = 44;
    private static final int PCM_FORMAT_TAG = 1;
    private static final int IEEE_FLOAT_FORMAT_TAG = 3;

    private WavFileWriter() {
    }

    public static void write(Path target, byte[] pcm, SampleFormat format) {
        Path parent = target.toAbsolutePath().getParent();
        try {
            Files.createDirectories(parent);
            Path tempFile = Files.createTempFile(parent, "tts-", ".wav.tmp");
            try (OutputStream out = Files.newOutputStream(tempFile)) {
                out.write(buildHeader(pcm.length, format));
                out.write(pcm);
            }
            Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write WAV file " + target, e);
        }
    }

    private static byte[] buildHeader(int dataLength, SampleFormat format) {
        int formatTag = format.sampleType() == SampleType.FLOAT ? IEEE_FLOAT_FORMAT_TAG : PCM_FORMAT_TAG;
        int byteRate = format.sampleRate() * format.bytesPerFrame();
        short blockAlign = (short) format.bytesPerFrame();

        ByteBuffer buffer = ByteBuffer.allocate(HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(36 + dataLength);
        buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(16);
        buffer.putShort((short) formatTag);
        buffer.putShort((short) format.channels());
        buffer.putInt(format.sampleRate());
        buffer.putInt(byteRate);
        buffer.putShort(blockAlign);
        buffer.putShort((short) format.bitDepth());
        buffer.put("data".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(dataLength);
        return buffer.array();
    }
}
