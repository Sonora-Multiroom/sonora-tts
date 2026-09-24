package multiroom.tts.cache;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The synthesized-audio disk cache. Deals in {@link Path}, not {@code byte[]}: the cached file is
 * what core plays (a {@code TtsInputResolver} hands out its {@code file://} URI), so this is the
 * materialization step, not a side store.
 */
public interface AudioCache {

    /** Returns the cached WAV for {@code key}, if present and written for the same format. */
    Optional<Path> get(CacheKey key);

    /**
     * Writes {@code pcm} as a WAV in {@code key.targetFormat()} and returns its path.
     *
     * @throws CacheWriteException if the write fails (disk full, I/O error)
     */
    Path put(CacheKey key, byte[] pcm);

    /**
     * Protects the entry's file from LRU eviction, and from removal by {@link #invalidate},
     * {@link #clear} and {@link #clearByProvider}, until {@link #unpin} is called.
     */
    void pin(CacheKey key);

    /** Releases a pin taken by {@link #pin}, carrying out any removal that was deferred by it. */
    void unpin(CacheKey key);

    /**
     * Removes a single entry, if present. A pinned entry's file survives until its {@link #unpin},
     * so the announcement holding it can still play, but the entry stops serving new requests
     * immediately.
     */
    void invalidate(CacheKey key);

    /** Removes every entry, with the same treatment of pinned entries as {@link #invalidate}. */
    void clear();

    /** Removes every entry for one provider, pinned entries as per {@link #invalidate}. */
    void clearByProvider(String providerName);

    CacheStats stats();
}
