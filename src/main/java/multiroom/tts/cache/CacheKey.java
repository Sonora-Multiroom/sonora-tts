package multiroom.tts.cache;

import multiroom.api.model.SampleFormat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Disk-cache lookup key: text plus every dimension that changes the audio produced for it.
 * {@code targetFormat} is folded in because a cached WAV outlives restarts and output-device
 * changes — an entry converted for one native format must be a miss for another, never served as
 * a wrong-format hit (FR-015).
 *
 * @param text         verbatim text content
 * @param providerName configured provider name (e.g. {@code "openai"})
 * @param engineName   provider model/engine (e.g. {@code "tts-1"}), may be {@code null}
 * @param voice        voice identifier after defaults are resolved, may be {@code null}
 * @param language     language tag after defaults are resolved, may be {@code null}
 * @param targetFormat the format the cached WAV was converted to
 */
public record CacheKey(String text, String providerName, String engineName, String voice, String language,
                        SampleFormat targetFormat) {

    /** SHA-256 hex of every field; used as the cache filename ({@code <hash>.wav}). */
    public String toHash() {
        String combined = String.join("|",
                text,
                nullToEmpty(providerName),
                nullToEmpty(engineName),
                nullToEmpty(voice),
                nullToEmpty(language),
                formatTag(targetFormat));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(combined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The flattened {@code sampleRate|channels|bitDepth|sampleType} tag stored in the index. */
    static String formatTag(SampleFormat format) {
        return format.sampleRate() + "|" + format.channels() + "|" + format.bitDepth() + "|" + format.sampleType();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
