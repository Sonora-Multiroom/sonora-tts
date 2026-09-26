package multiroom.tts.cache;

import multiroom.api.model.SampleFormat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Disk-cache lookup key: text plus every dimension that changes the audio produced for it.
 * {@code targetFormat} is folded in because a cached WAV outlives restarts and output-device
 * changes — an entry converted for one native format must be a miss for another, never served as
 * a wrong-format hit.
 *
 * <p>{@code pitch} and {@code speakingRate} enter the hash only when set, so every key without
 * them — every non-Google key, and every Google key with no adjustment — hashes exactly as in 001
 * and existing cache entries stay valid.
 *
 * @param text         verbatim text content
 * @param providerName configured provider name (e.g. {@code "openai"})
 * @param engineName   provider model/engine (e.g. {@code "tts-1"}), may be {@code null}
 * @param voice        the resolved voice's cache-key form, may be {@code null}
 * @param language     language tag after defaults are resolved, may be {@code null}
 * @param pitch        the pitch's cache-key form, {@code null} when neutral or unset
 * @param speakingRate the speaking rate's cache-key form, {@code null} when neutral or unset
 * @param targetFormat the format the cached WAV was converted to
 * @param stylePrompt  the effective style prompt, already stripped ({@code google-gemini} only);
 *                      {@code null} when there is none. Last in the hash, and appended only when
 *                      set, so every pre-004 key — every non-Gemini key, and every Gemini request
 *                      with no prompt — hashes exactly as before and stays a hit
 */
public record CacheKey(String text, String providerName, String engineName, String voice, String language,
                       Double pitch, Double speakingRate, SampleFormat targetFormat, String stylePrompt) {

    /** A key with no audio adjustments and no prompt — the 001 shape. */
    public CacheKey(String text, String providerName, String engineName, String voice, String language,
                    SampleFormat targetFormat) {
        this(text, providerName, engineName, voice, language, null, null, targetFormat, null);
    }

    /** A key with no prompt — 002's shape. */
    public CacheKey(String text, String providerName, String engineName, String voice, String language,
                    Double pitch, Double speakingRate, SampleFormat targetFormat) {
        this(text, providerName, engineName, voice, language, pitch, speakingRate, targetFormat, null);
    }

    /** SHA-256 hex of every field; used as the cache filename ({@code <hash>.wav}). */
    public String toHash() {
        StringBuilder combined = new StringBuilder(String.join("|",
                text,
                nullToEmpty(providerName),
                nullToEmpty(engineName),
                nullToEmpty(voice),
                nullToEmpty(language),
                formatTag(targetFormat)));
        if (pitch != null) {
            combined.append("|p=").append(plain(pitch));
        }
        if (speakingRate != null) {
            combined.append("|r=").append(plain(speakingRate));
        }
        if (stylePrompt != null) {
            combined.append("|s=").append(stylePrompt);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(combined.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The flattened {@code sampleRate|channels|bitDepth|sampleType} tag stored in the index. */
    static String formatTag(SampleFormat format) {
        return format.sampleRate() + "|" + format.channels() + "|" + format.bitDepth() + "|" + format.sampleType();
    }

    /** {@code 1.10} and {@code 1.1} are the same rate, so they must not name different files. */
    private static String plain(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
