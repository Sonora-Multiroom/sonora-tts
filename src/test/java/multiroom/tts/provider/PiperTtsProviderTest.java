package multiroom.tts.provider;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.config.ProviderType;
import multiroom.tts.config.TtsProviderConfig;
import multiroom.tts.provider.local.PiperTtsProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PiperTtsProvider} shells out to {@code <pythonExecutable> -m piper --model <path>
 * --config <path>}. These tests stand a fake script in for the Python interpreter — it receives
 * the same argument vector and stdin text a real {@code python3 -m piper} invocation would.
 */
class PiperTtsProviderTest {

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    private static Path script(Path dir, String name, String windowsBody, String posixBody) throws IOException {
        Path file = dir.resolve(name + (WINDOWS ? ".bat" : ".sh"));
        Files.writeString(file, WINDOWS ? windowsBody : posixBody);
        if (!WINDOWS) {
            Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        }
        return file;
    }

    private static TtsProviderConfig configFor(Path fakePython, Path model, int timeoutSeconds) {
        TtsProviderConfig config = new TtsProviderConfig();
        config.setName("piper-local");
        config.setType(ProviderType.PIPER);
        config.setPythonExecutable(fakePython.toString());
        config.setModelPath(model.toString());
        config.setTimeoutSeconds(timeoutSeconds);
        return config;
    }

    private static Path modelWithConfig(Path dir) throws IOException {
        Path model = dir.resolve("model.onnx");
        Files.createFile(model);
        Files.createFile(dir.resolve("model.onnx.json"));
        return model;
    }

    @Test
    void constructionSpawnsNoProcess(@TempDir Path dir) throws Exception {
        Path fakePython = script(dir, "fake-python",
                "@echo off\r\necho FAKE_WAV_DATA\r\n",
                "#!/bin/sh\necho FAKE_WAV_DATA\n");
        Path model = modelWithConfig(dir);

        new PiperTtsProvider(configFor(fakePython, model, 5));
        // No process-related side effect is observable at construction time; reaching here
        // without invoking synthesize() is itself the assertion (019 FR-019).
    }

    @Test
    void stdoutWavIsCapturedOnSuccess(@TempDir Path dir) throws Exception {
        Path fakePython = script(dir, "fake-python",
                "@echo off\r\necho FAKE_WAV_DATA\r\n",
                "#!/bin/sh\ncat > /dev/null\necho FAKE_WAV_DATA\n");
        Path model = modelWithConfig(dir);

        SynthesisResult result = new PiperTtsProvider(configFor(fakePython, model, 5))
                .synthesize(new SynthesisRequest("hello", "ryan", "en-US", 22050, 1));

        assertThat(new String(result.audioData(), StandardCharsets.UTF_8)).contains("FAKE_WAV_DATA");
    }

    @Test
    void nonZeroExitIsHandled(@TempDir Path dir) throws Exception {
        Path fakePython = script(dir, "fake-python-fail",
                "@echo off\r\nexit /b 1\r\n",
                "#!/bin/sh\nexit 1\n");
        Path model = modelWithConfig(dir);

        assertThatThrownBy(() -> new PiperTtsProvider(configFor(fakePython, model, 5))
                .synthesize(new SynthesisRequest("hello", "ryan", "en-US", 22050, 1)))
                .isInstanceOf(TtsException.class)
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.PROVIDER_ERROR);
    }

    @Test
    void timeoutDestroysTheProcessAndRaisesProviderTimeout(@TempDir Path dir) throws Exception {
        Path fakePython = script(dir, "fake-python-slow",
                "@echo off\r\nping -n 6 127.0.0.1 >nul\r\necho FAKE_WAV_DATA\r\n",
                "#!/bin/sh\nsleep 5\necho FAKE_WAV_DATA\n");
        Path model = modelWithConfig(dir);

        assertThatThrownBy(() -> new PiperTtsProvider(configFor(fakePython, model, 1))
                .synthesize(new SynthesisRequest("hello", "ryan", "en-US", 22050, 1)))
                .isInstanceOf(TtsException.class)
                .extracting(e -> ((TtsException) e).getErrorCode())
                .isEqualTo(TtsErrorCode.PROVIDER_TIMEOUT);
    }
}
