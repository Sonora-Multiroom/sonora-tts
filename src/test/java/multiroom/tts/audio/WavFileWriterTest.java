package multiroom.tts.audio;

import multiroom.api.model.SampleFormat;
import multiroom.api.model.SampleType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class WavFileWriterTest {

    @Test
    void writesAWavFileThatAudioSystemReadsBackIdentically(@TempDir Path dir) throws Exception {
        SampleFormat format = new SampleFormat(24000, 16, 1, SampleType.PCM);
        byte[] pcm = {1, 2, 3, 4, 5, 6, 7, 8};
        Path target = dir.resolve("out.wav");

        WavFileWriter.write(target, pcm, format);

        try (AudioInputStream in = AudioSystem.getAudioInputStream(target.toFile())) {
            byte[] readBack = in.readAllBytes();
            assertThat(readBack).isEqualTo(pcm);
            assertThat(Math.round(in.getFormat().getSampleRate())).isEqualTo(24000);
            assertThat(in.getFormat().getSampleSizeInBits()).isEqualTo(16);
            assertThat(in.getFormat().getChannels()).isEqualTo(1);
        }
    }

    @Test
    void writeIsAtomicSoNoTempFileIsLeftBehind(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("atomic.wav");

        WavFileWriter.write(target, new byte[1024], SampleFormat.standard());

        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.filter(p -> p.getFileName().toString().endsWith(".tmp"))).isEmpty();
        }
        assertThat(Files.exists(target)).isTrue();
    }

    @Test
    void createsMissingParentDirectories(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("nested/deeper/out.wav");

        WavFileWriter.write(target, new byte[] {9, 9}, SampleFormat.standard());

        assertThat(Files.exists(target)).isTrue();
    }
}
