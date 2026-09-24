package multiroom.tts.provider;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Named lookup of the configured {@link TtsProvider} instances. The first entry is the implicit
 * default when no explicit {@code defaultProvider} is configured (FR-005).
 */
public class ProviderRegistry {

    private final Map<String, TtsProvider> providersByName;
    private final String defaultProviderName;

    public ProviderRegistry(Map<String, TtsProvider> providersByName, String defaultProviderName) {
        if (providersByName == null || providersByName.isEmpty()) {
            throw new IllegalArgumentException("at least one provider must be registered");
        }
        this.providersByName = new LinkedHashMap<>(providersByName);
        if (defaultProviderName != null && !defaultProviderName.isBlank()) {
            if (!this.providersByName.containsKey(defaultProviderName)) {
                // TtsProperties.validate() rejects this first; the guard is here so that
                // resolveDefault() can never return null whatever built the registry.
                throw new IllegalArgumentException("default provider '" + defaultProviderName
                        + "' is not among the registered providers " + this.providersByName.keySet());
            }
            this.defaultProviderName = defaultProviderName;
        } else {
            this.defaultProviderName = this.providersByName.keySet().iterator().next();
        }
    }

    /** Returns the configured default provider — the explicit one, or the first configured. */
    public TtsProvider resolveDefault() {
        return providersByName.get(defaultProviderName);
    }

    /** Returns the name of the provider {@link #resolveDefault()} would return. */
    public String defaultProviderName() {
        return defaultProviderName;
    }

    /**
     * Returns the named provider.
     *
     * @throws TtsException {@link TtsErrorCode#PROVIDER_NOT_FOUND} if no provider matches
     */
    public TtsProvider resolve(String name) {
        TtsProvider provider = providersByName.get(name);
        if (provider == null) {
            throw new TtsException(TtsErrorCode.PROVIDER_NOT_FOUND,
                    "No provider configured with name '" + name + "'");
        }
        return provider;
    }
}
