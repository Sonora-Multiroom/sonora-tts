package multiroom.tts.cache;

import multiroom.api.model.SampleFormat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CacheKeyTest {

    private static final SampleFormat FORMAT = SampleFormat.standard();

    /**
     * SHA-256 of 001's {@code String.join("|", …)} for these fields, computed with 001's
     * {@code CacheKey} before pitch and speaking rate existed. Every existing cache entry is named
     * by such a hash, so it must not move.
     */
    private static final String HASH_001 = "dab14075da8e7d0f2e73edcf312004775587448c85e231c2d70bd186e1201a58";

    @Test
    void withoutPitchOrRateTheHashIsExactly001s() {
        CacheKey key = new CacheKey("hello", "openai", "tts-1", "alloy", "en-US", null, null, FORMAT);

        assertThat(key.toHash()).isEqualTo(HASH_001);
    }

    @Test
    void sixArgumentConstructorLeavesPitchAndRateUnset() {
        CacheKey key = new CacheKey("hello", "openai", "tts-1", "alloy", "en-US", FORMAT);

        assertThat(key.pitch()).isNull();
        assertThat(key.speakingRate()).isNull();
        assertThat(key.toHash()).isEqualTo(HASH_001);
    }

    @Test
    void aPitchChangesTheHash() {
        CacheKey key = new CacheKey("hello", "openai", "tts-1", "alloy", "en-US", -2.0, null, FORMAT);

        assertThat(key.toHash()).isNotEqualTo(HASH_001);
    }

    @Test
    void aSpeakingRateChangesTheHash() {
        CacheKey key = new CacheKey("hello", "openai", "tts-1", "alloy", "en-US", null, 0.9, FORMAT);

        assertThat(key.toHash()).isNotEqualTo(HASH_001);
    }

    @Test
    void pitchAndRateAreNotInterchangeable() {
        CacheKey pitch = new CacheKey("hello", "google", null, "v", "en-US", 1.5, null, FORMAT);
        CacheKey rate = new CacheKey("hello", "google", null, "v", "en-US", null, 1.5, FORMAT);

        assertThat(pitch.toHash()).isNotEqualTo(rate.toHash());
    }

    @Test
    void trailingZerosDoNotSplitEntries() {
        CacheKey a = new CacheKey("hello", "google", null, "v", "en-US", null, 1.10, FORMAT);
        CacheKey b = new CacheKey("hello", "google", null, "v", "en-US", null, 1.1, FORMAT);
        CacheKey c = new CacheKey("hello", "google", null, "v", "en-US", -2.0, null, FORMAT);
        CacheKey d = new CacheKey("hello", "google", null, "v", "en-US", -2.00, null, FORMAT);

        assertThat(a.toHash()).isEqualTo(b.toHash());
        assertThat(c.toHash()).isEqualTo(d.toHash());
    }
}
