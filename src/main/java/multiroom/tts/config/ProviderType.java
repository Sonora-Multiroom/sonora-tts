package multiroom.tts.config;

import java.util.Locale;

/**
 * The kind of backend a configured {@link TtsProviderConfig} entry talks to.
 */
public enum ProviderType {
    OPENAI,
    GOOGLE_CLOUD,
    GOOGLE_GEMINI,
    PIPER,
    LOCAL_HTTP;

    /**
     * The spelling an operator writes in configuration ({@code google-cloud}), which is also the
     * value of the {@code type} metric tag, so a dashboard reads the same word as the YAML.
     */
    public String configName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
