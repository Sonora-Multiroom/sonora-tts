package multiroom.tts.provider.local;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.config.TtsProviderConfig;
import multiroom.tts.provider.DefaultSettingsResolution;
import multiroom.tts.provider.RequestedSettings;
import multiroom.tts.provider.SynthesisRequest;
import multiroom.tts.provider.SynthesisResult;
import multiroom.tts.provider.SynthesisSettings;
import multiroom.tts.provider.TtsProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs {@code piper-tts} (piper1-gpl, {@code pip install piper-tts}) as a subprocess per request.
 * There is no standalone binary or console script for this package — only a {@code python3 -m
 * piper} module — so {@link TtsProviderConfig#getPythonExecutable()} names the interpreter, not
 * Piper itself. The interpreter is resolved (via {@code PATH} or as an absolute path) only when
 * this method runs, never during construction.
 *
 * <p>With neither {@code --output-file} nor {@code --output-raw} given, this CLI writes a
 * complete WAV to stdout and reads the text to speak from stdin — the same shape every other
 * provider's response arrives in, so no special-casing is needed downstream.
 */
public class PiperTtsProvider implements TtsProvider {

    private final TtsProviderConfig config;
    private final String pythonExecutable;
    private final String modelPath;
    private final String configPath;
    private final Duration timeout;

    public PiperTtsProvider(TtsProviderConfig config) {
        this.config = config;
        this.pythonExecutable = config.getPythonExecutable();
        this.modelPath = config.getModelPath();
        this.configPath = modelPath + ".json";
        this.timeout = Duration.ofSeconds(config.getTimeoutSeconds());
    }

    @Override
    public SynthesisSettings resolveSettings(RequestedSettings requested) {
        return DefaultSettingsResolution.resolve(config, requested);
    }

    @Override
    public SynthesisResult synthesize(SynthesisRequest request) {
        ProcessBuilder processBuilder = new ProcessBuilder(
                pythonExecutable, "-m", "piper", "--model", modelPath, "--config", configPath);
        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException e) {
            throw new TtsException(TtsErrorCode.PROVIDER_ERROR,
                    "Failed to start '" + pythonExecutable + " -m piper': " + e.getMessage(), e);
        }

        // stdout carries the WAV; stderr carries piper's own logging. Both are drained on
        // separate threads so neither pipe can fill up and deadlock this process while it
        // waits — and so the timeout below bounds the whole wait, not just a post-read join.
        AtomicReference<byte[]> capturedAudio = new AtomicReference<>(new byte[0]);
        Thread stdoutReader = drain(process.getInputStream(), capturedAudio::set, "piper-stdout-reader");
        Thread stderrDrain = drain(process.getErrorStream(), ignored -> { }, "piper-stderr-drain");

        try {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(request.text().getBytes(StandardCharsets.UTF_8));
            }

            boolean finished = process.waitFor(timeout.getSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new TtsException(TtsErrorCode.PROVIDER_TIMEOUT,
                        "Piper did not finish within " + timeout.getSeconds() + " seconds");
            }
            stdoutReader.join(TimeUnit.SECONDS.toMillis(5));
            stderrDrain.join(TimeUnit.SECONDS.toMillis(5));
            if (process.exitValue() != 0) {
                throw new TtsException(TtsErrorCode.PROVIDER_ERROR,
                        "Piper exited with status " + process.exitValue());
            }
        } catch (IOException e) {
            throw new TtsException(TtsErrorCode.PROVIDER_ERROR, "Failed to run Piper: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new TtsException(TtsErrorCode.PROVIDER_ERROR, "Interrupted while running Piper", e);
        }

        return new SynthesisResult(capturedAudio.get(), request.targetSampleRate(), 1, 16);
    }

    private static Thread drain(InputStream stream, java.util.function.Consumer<byte[]> onRead, String name) {
        Thread thread = new Thread(() -> {
            try (InputStream in = stream) {
                onRead.accept(in.readAllBytes());
            } catch (IOException ignored) {
                // The process was destroyed mid-read on timeout; nothing more to capture.
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
