package multiroom.tts.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TtsPropertiesValidationTest {

    @Test
    void abortsWhenNoProvidersConfigured() {
        TtsProperties properties = new TtsProperties();

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one provider");
    }

    @Test
    void abortsWhenApiKeyMissingForCloudProvider() {
        TtsProviderConfig openai = new TtsProviderConfig();
        openai.setName("openai");
        openai.setType(ProviderType.OPENAI);

        TtsProperties properties = new TtsProperties();
        properties.setProviders(List.of(openai));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api-key");
    }

    @Test
    void abortsWhenPiperModelDoesNotExist() {
        TtsProviderConfig piper = new TtsProviderConfig();
        piper.setName("piper-local");
        piper.setType(ProviderType.PIPER);
        piper.setModelPath("/no/such/model.onnx");

        TtsProperties properties = new TtsProperties();
        properties.setProviders(List.of(piper));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model-path")
                .hasMessageContaining("does not exist");
    }

    @Test
    void abortsWhenPiperModelConfigSidecarIsMissing(@TempDir Path tempDir) throws Exception {
        Path model = tempDir.resolve("model.onnx");
        Files.createFile(model);
        // Deliberately no model.onnx.json alongside it.

        TtsProviderConfig piper = new TtsProviderConfig();
        piper.setName("piper-local");
        piper.setType(ProviderType.PIPER);
        piper.setModelPath(model.toString());

        TtsProperties properties = new TtsProperties();
        properties.setProviders(List.of(piper));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".onnx.json");
    }

    @Test
    void passesWithValidCloudProviderConfiguration() {
        TtsProviderConfig openai = new TtsProviderConfig();
        openai.setName("openai");
        openai.setType(ProviderType.OPENAI);
        openai.setApiKey("sk-test");

        TtsProperties properties = new TtsProperties();
        properties.setProviders(List.of(openai));

        properties.validate();
    }

    @Test
    void passesWhenPiperModelAndConfigSidecarExist(@TempDir Path tempDir) throws Exception {
        Path model = tempDir.resolve("model.onnx");
        Path modelConfig = tempDir.resolve("model.onnx.json");
        Files.createFile(model);
        Files.createFile(modelConfig);

        TtsProviderConfig piper = new TtsProviderConfig();
        piper.setName("piper-local");
        piper.setType(ProviderType.PIPER);
        piper.setModelPath(model.toString());

        TtsProperties properties = new TtsProperties();
        properties.setProviders(List.of(piper));

        properties.validate();
    }

    @Test
    void doesNotValidatePythonExecutableAsAFilesystemPath(@TempDir Path tempDir) throws Exception {
        // pythonExecutable defaults to "python3", a bare PATH-resolved command name, not a
        // literal path — validation must not reject it for "not existing" at cwd, and must
        // never spawn a process to check it either (FR-029).
        Path model = tempDir.resolve("model.onnx");
        Path modelConfig = tempDir.resolve("model.onnx.json");
        Files.createFile(model);
        Files.createFile(modelConfig);

        TtsProviderConfig piper = new TtsProviderConfig();
        piper.setName("piper-local");
        piper.setType(ProviderType.PIPER);
        piper.setModelPath(model.toString());
        assertThat(piper.getPythonExecutable()).isEqualTo("python3");

        TtsProperties properties = new TtsProperties();
        properties.setProviders(List.of(piper));

        properties.validate();
    }

    @Test
    void doesNotContactAnyNetworkOrProcessDuringValidation() {
        // An API key that is present but wrong, or an endpoint that is present but
        // unreachable, must never be detected here — validation touches configuration
        // and the filesystem only (FR-029).
        TtsProviderConfig localHttp = new TtsProviderConfig();
        localHttp.setName("local");
        localHttp.setType(ProviderType.LOCAL_HTTP);
        localHttp.setEndpoint("http://127.0.0.1:1/definitely-not-listening");

        TtsProperties properties = new TtsProperties();
        properties.setProviders(List.of(localHttp));

        properties.validate();
    }

    @Test
    void abortsWhenDefaultProviderNameIsUnknown() {
        TtsProviderConfig openai = new TtsProviderConfig();
        openai.setName("openai");
        openai.setType(ProviderType.OPENAI);
        openai.setApiKey("sk-test");

        TtsProperties properties = new TtsProperties();
        properties.setProviders(List.of(openai));
        properties.setDefaultProvider("does-not-exist");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist");
    }

    @Test
    void abortsWhenDefaultProviderIsConfiguredButDisabled() {
        // Only enabled providers are registered, so a disabled default would leave
        // resolveDefault() returning null and fail at request time as a PROVIDER_ERROR
        // naming a provider that is plainly present in the configuration.
        TtsProviderConfig openai = new TtsProviderConfig();
        openai.setName("openai");
        openai.setType(ProviderType.OPENAI);
        openai.setApiKey("sk-test");
        openai.setEnabled(false);

        TtsProviderConfig local = new TtsProviderConfig();
        local.setName("local");
        local.setType(ProviderType.LOCAL_HTTP);
        local.setEndpoint("http://127.0.0.1:5002/api/tts");

        TtsProperties properties = new TtsProperties();
        properties.setProviders(List.of(openai, local));
        properties.setDefaultProvider("openai");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("multiroom-tts")
                .hasMessageContaining("openai")
                .hasMessageContaining("disabled");
    }

    @Test
    void skipsValidationWhenExtensionDisabled() {
        TtsProperties properties = new TtsProperties();
        properties.setEnabled(false);

        properties.validate();

        assertThat(properties.getProviders()).isEmpty();
    }
}
