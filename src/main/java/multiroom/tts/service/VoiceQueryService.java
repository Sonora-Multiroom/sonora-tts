package multiroom.tts.service;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import multiroom.tts.config.TtsProperties;
import multiroom.tts.config.TtsProviderConfig;
import multiroom.tts.provider.ProviderRegistry;
import multiroom.tts.provider.TtsProvider;
import multiroom.tts.provider.VoiceCatalogueProvider;
import multiroom.tts.provider.cloud.google.CatalogueVoice;
import multiroom.tts.provider.cloud.google.GoogleLanguage;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Answers {@code GET /api/tts/providers/{name}/voices}: finds the provider, checks
 * that its type can list voices ({@code google-cloud} and {@code google-gemini}), checks the
 * form of the language filter, and delegates to its catalogue.
 */
public class VoiceQueryService {

    private final ProviderRegistry providerRegistry;
    private final Map<String, TtsProviderConfig> configsByName;

    public VoiceQueryService(TtsProperties properties, ProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
        this.configsByName = properties.getProviders().stream()
                .collect(Collectors.toMap(TtsProviderConfig::getName, Function.identity()));
    }

    /**
     * @throws TtsException {@link TtsErrorCode#PROVIDER_NOT_FOUND} for an unknown provider;
     *                      {@link TtsErrorCode#INVALID_REQUEST} for a type that cannot list voices,
     *                      or a malformed {@code language}; {@link
     *                      TtsErrorCode#VOICE_CATALOGUE_UNAVAILABLE} when the catalogue cannot be
     *                      fetched now
     */
    public List<CatalogueVoice> listVoices(String providerName, String language, String engine) {
        TtsProvider provider = providerRegistry.resolve(providerName);
        if (!(provider instanceof VoiceCatalogueProvider catalogueProvider)) {
            throw new TtsException(TtsErrorCode.INVALID_REQUEST, "Provider '" + providerName + "' of type "
                    + configsByName.get(providerName).getType() + " does not support voice listing");
        }
        if (language != null && !GoogleLanguage.isWellFormed(language)) {
            throw new TtsException(TtsErrorCode.INVALID_REQUEST, "Language '" + language
                    + "' is not a language-region tag such as uk-UA, cmn-CN or es-419");
        }
        return catalogueProvider.listVoices(language, engine);
    }
}
