package multiroom.tts.audio;

import multiroom.api.io.resolve.InputResolutionException;
import multiroom.api.model.AudioInputDefinition;
import multiroom.api.model.InputId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TtsInputResolverTest {

    private static AudioInputDefinition inputFor(String uri) {
        return AudioInputDefinition.builder()
                .inputId(InputId.of("tts-test"))
                .displayName("tts-test")
                .uri(uri)
                .enabled(true)
                .autoRemove(true)
                .build();
    }

    @Test
    void supportsOnlyTheTtsScheme() {
        TtsInputResolver resolver = new TtsInputResolver();

        assertThat(resolver.supports(inputFor("tts://" + UUID.randomUUID()))).isTrue();
        assertThat(resolver.supports(inputFor("file:///tmp/x.wav"))).isFalse();
        assertThat(resolver.supports(inputFor("http://example.com/x.wav"))).isFalse();
    }

    @Test
    void resolvesRegisteredUuidToTheFileUriOfItsWav(@TempDir Path dir) throws Exception {
        Path wav = dir.resolve("a.wav");
        Files.createFile(wav);
        UUID id = UUID.randomUUID();
        TtsInputResolver resolver = new TtsInputResolver();
        resolver.register(id, wav);

        var resolved = resolver.resolve(inputFor("tts://" + id));

        assertThat(resolved.effectiveUri().getScheme()).isEqualTo("file");
        assertThat(Path.of(resolved.effectiveUri())).isEqualTo(wav);
    }

    @Test
    void unknownUuidRaisesInputResolutionException() {
        TtsInputResolver resolver = new TtsInputResolver();

        assertThatThrownBy(() -> resolver.resolve(inputFor("tts://" + UUID.randomUUID())))
                .isInstanceOf(InputResolutionException.class);
    }

    @Test
    void deletedFileFailsCleanlyRatherThanThrowingUnchecked(@TempDir Path dir) throws Exception {
        Path wav = dir.resolve("gone.wav");
        Files.createFile(wav);
        UUID id = UUID.randomUUID();
        TtsInputResolver resolver = new TtsInputResolver();
        resolver.register(id, wav);
        Files.delete(wav);

        assertThatThrownBy(() -> resolver.resolve(inputFor("tts://" + id)))
                .isInstanceOf(InputResolutionException.class);
    }

    @Test
    void releasedEntryIsNoLongerResolvable(@TempDir Path dir) throws Exception {
        Path wav = dir.resolve("b.wav");
        Files.createFile(wav);
        UUID id = UUID.randomUUID();
        TtsInputResolver resolver = new TtsInputResolver();
        resolver.register(id, wav);

        resolver.release(id);

        assertThatThrownBy(() -> resolver.resolve(inputFor("tts://" + id)))
                .isInstanceOf(InputResolutionException.class);
    }
}
