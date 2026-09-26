package multiroom.tts.config;

/**
 * The kind of backend a configured {@link TtsProviderConfig} entry talks to.
 */
public enum ProviderType {
    OPENAI,
    GOOGLE_CLOUD,
    GOOGLE_GEMINI,
    PIPER,
    LOCAL_HTTP
}
