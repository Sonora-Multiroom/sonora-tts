package multiroom.tts.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One operator-configured TTS backend, bound from {@code multiroom.tts.providers[n]}.
 * Fields not used by a given {@link #type} are simply left null.
 */
@Data
public class TtsProviderConfig {

    /** Unique name; used in requests and as the {@code providerName} cache-key component. */
    @NotBlank
    private String name;

    @NotNull
    private ProviderType type;

    /** Set to {@code false} to disable this entry without removing it. */
    private boolean enabled = true;

    /** Required for {@link ProviderType#OPENAI} and {@link ProviderType#GOOGLE_CLOUD}. */
    private String apiKey;

    /** Default voice; null falls back to the provider implementation's own default. */
    private String voice;

    /**
     * Default BCP 47 language tag. Deliberately has no field default: {@code google-cloud} must
     * not have an implicit {@code en-US} that overrides a voice's own language. The other
     * provider types still fall back to {@code en-US}, in {@code DefaultSettingsResolution}.
     * For {@code google-cloud} it is the default language for short voice names.
     */
    private String language;

    /**
     * Model/engine name. For OpenAI it is the model (e.g. {@code tts-1}) and part of the cache
     * key. For {@code google-cloud} it is the <strong>default engine for short voice names</strong>
     * ({@code standard}, {@code wavenet}, {@code neural2}, {@code studio}, {@code chirp-hd},
     * {@code chirp3-hd}), validated at start-up — not a free-text label.
     */
    private String engine;

    /** Synthesis call timeout. Values above 10s are accepted but warned about at start-up. */
    private int timeoutSeconds = 10;

    /**
     * {@code google-cloud} only: pitch in semitones, [-20.0, 20.0]. Sent only when set; any other
     * provider type aborts start-up if it is set, rather than accepting and ignoring it.
     */
    private Double pitch;

    /**
     * {@code google-cloud} only: speaking rate, [0.25, 2.0], where 1.0 is the voice's natural
     * speed. Sent only when set; any other provider type aborts start-up if it is set.
     */
    private Double speakingRate;

    /**
     * Provider-specific parameters. Must be empty for {@code google-cloud}, whose audio settings
     * are the typed {@link #pitch} and {@link #speakingRate}: a non-empty map there
     * aborts start-up rather than being accepted and ignored.
     */
    private Map<String, String> extraParams = new LinkedHashMap<>();

    /**
     * {@link ProviderType#PIPER} only: the Python executable with {@code piper-tts} installed
     * (`pip install piper-tts`) — piper1-gpl ships no standalone binary or console script, only
     * a {@code python3 -m piper} module. A bare command name (the default) is resolved via
     * {@code PATH}; an absolute path is used as-is.
     */
    private String pythonExecutable = "python3";

    /**
     * {@link ProviderType#PIPER} only: path to the ONNX voice model. Its {@code .onnx.json}
     * config sidecar must sit next to it under the same name — Piper will not load without it.
     */
    private String modelPath;

    /** {@link ProviderType#LOCAL_HTTP} only: the endpoint to POST synthesis requests to. */
    private String endpoint;

    /** {@link ProviderType#LOCAL_HTTP} only: optional JSON request template. */
    private String requestTemplate;
}
