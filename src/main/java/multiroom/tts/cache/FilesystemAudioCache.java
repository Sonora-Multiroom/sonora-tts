package multiroom.tts.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import multiroom.tts.audio.WavFileWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A disk-backed LRU cache: {@code index.json} plus SHA-256-keyed {@code .wav} files, wrapped by a
 * plain JDK access-ordered {@link LinkedHashMap} — no third-party cache library. The index and
 * files survive restarts (FR-017, FR-018); pinned entries are skipped by eviction, never deleted
 * out from under a queued announcement.
 *
 * <p>That guarantee covers operator-initiated removal too, not just eviction: {@link #invalidate},
 * {@link #clear} and {@link #clearByProvider} cannot simply unlink a file that a queued
 * announcement is about to play, or {@code TtsInputResolver} would hand core a path that no longer
 * exists. Nor may they silently keep the entry — an operator clearing the cache after changing a
 * voice would then get a stale hit on the next identical announcement. So a pinned entry is marked
 * for deletion instead, and {@link #unpin} purges it the moment its announcement finishes.
 */
public class FilesystemAudioCache implements AudioCache {

    private static final Logger log = LoggerFactory.getLogger(FilesystemAudioCache.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path cacheDir;
    private final Path indexFile;
    private final long maxSizeBytes;
    private final Object lock = new Object();
    private final LinkedHashMap<String, IndexEntry> index;
    private final Set<String> pinned = ConcurrentHashMap.newKeySet();

    /**
     * Hashes an operator asked to remove while they were pinned. Guarded by {@code lock} together
     * with {@link #pinned}, so a removal can never decide "still pinned, defer it" in the same
     * instant that {@link #unpin} decides "nothing deferred, done" and the entry survives both.
     */
    private final Set<String> pendingDeletion = ConcurrentHashMap.newKeySet();

    public FilesystemAudioCache(Path cacheDir, long maxSizeMb) {
        this.cacheDir = cacheDir;
        this.indexFile = cacheDir.resolve("index.json");
        this.maxSizeBytes = maxSizeMb * 1024L * 1024L;
        this.index = new LinkedHashMap<>(16, 0.75f, true);
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create cache directory " + cacheDir, e);
        }
        loadIndex();
    }

    @Override
    public Optional<Path> get(CacheKey key) {
        synchronized (lock) {
            String hash = key.toHash();
            IndexEntry entry = index.get(hash);
            if (entry == null) {
                return Optional.empty();
            }
            if (pendingDeletion.contains(hash)) {
                // An operator already removed this; it survives only so the announcement holding
                // it can finish. Serving it to a new request would be exactly the stale hit the
                // removal was meant to prevent.
                return Optional.empty();
            }
            if (!CacheKey.formatTag(key.targetFormat()).equals(entry.formatTag)) {
                // Either a different target format, or an entry written before formatTag existed
                // (null): both must be a miss, never a wrong-format hit.
                return Optional.empty();
            }
            Path file = fileFor(hash);
            if (!Files.exists(file)) {
                index.remove(hash);
                persistIndex();
                return Optional.empty();
            }
            entry.lastAccessedEpoch = Instant.now().toEpochMilli();
            persistIndex();
            return Optional.of(file);
        }
    }

    @Override
    public Path put(CacheKey key, byte[] pcm) {
        String hash = key.toHash();
        Path file = fileFor(hash);
        try {
            WavFileWriter.write(file, pcm, key.targetFormat());
        } catch (RuntimeException e) {
            throw new CacheWriteException("Failed to write cache entry " + hash, e);
        }
        synchronized (lock) {
            long size;
            try {
                size = Files.size(file);
            } catch (IOException e) {
                throw new CacheWriteException("Failed to stat cache entry " + hash, e);
            }
            long now = Instant.now().toEpochMilli();
            IndexEntry entry = new IndexEntry();
            entry.hash = hash;
            entry.providerName = key.providerName();
            entry.formatTag = CacheKey.formatTag(key.targetFormat());
            entry.sizeBytes = size;
            entry.lastAccessedEpoch = now;
            entry.createdEpoch = now;
            index.put(hash, entry);
            // This hash has just been rewritten, so a removal that predates the new content no
            // longer applies to it — otherwise the next unpin would delete what was just written.
            pendingDeletion.remove(hash);
            evictIfNeeded();
            persistIndex();
        }
        return file;
    }

    @Override
    public void pin(CacheKey key) {
        synchronized (lock) {
            pinned.add(key.toHash());
        }
    }

    @Override
    public void unpin(CacheKey key) {
        synchronized (lock) {
            String hash = key.toHash();
            pinned.remove(hash);
            if (pendingDeletion.remove(hash)) {
                // An operator asked for this entry while it was playing; honour that now.
                purge(hash);
                persistIndex();
            }
        }
    }

    @Override
    public void invalidate(CacheKey key) {
        synchronized (lock) {
            removeOrDefer(key.toHash());
            persistIndex();
        }
    }

    @Override
    public void clear() {
        synchronized (lock) {
            for (String hash : new ArrayList<>(index.keySet())) {
                removeOrDefer(hash);
            }
            persistIndex();
        }
    }

    @Override
    public void clearByProvider(String providerName) {
        synchronized (lock) {
            List<String> toRemove = new ArrayList<>();
            for (IndexEntry entry : index.values()) {
                if (providerName.equals(entry.providerName)) {
                    toRemove.add(entry.hash);
                }
            }
            for (String hash : toRemove) {
                removeOrDefer(hash);
            }
            persistIndex();
        }
    }

    /**
     * Drops {@code hash} now, or marks it for {@link #unpin} to drop if an announcement is holding
     * it. Callers hold {@code lock} and persist the index once for the whole batch.
     */
    private void removeOrDefer(String hash) {
        if (pinned.contains(hash)) {
            pendingDeletion.add(hash);
            log.debug("Cache entry {} is pinned by an in-flight announcement; "
                    + "deferring removal until playback completes", hash);
            return;
        }
        purge(hash);
    }

    private void purge(String hash) {
        deleteQuietly(fileFor(hash));
        index.remove(hash);
    }

    @Override
    public CacheStats stats() {
        synchronized (lock) {
            long totalSize = 0;
            Map<String, Integer> byProvider = new LinkedHashMap<>();
            for (IndexEntry entry : index.values()) {
                totalSize += entry.sizeBytes;
                byProvider.merge(entry.providerName, 1, Integer::sum);
            }
            return new CacheStats(index.size(), totalSize, maxSizeBytes, byProvider);
        }
    }

    private void evictIfNeeded() {
        long total = index.values().stream().mapToLong(e -> e.sizeBytes).sum();
        if (total <= maxSizeBytes) {
            return;
        }
        var iterator = index.entrySet().iterator();
        while (total > maxSizeBytes && iterator.hasNext()) {
            var candidate = iterator.next();
            if (pinned.contains(candidate.getKey())) {
                continue;
            }
            total -= candidate.getValue().sizeBytes;
            deleteQuietly(fileFor(candidate.getKey()));
            iterator.remove();
        }
    }

    private Path fileFor(String hash) {
        return cacheDir.resolve(hash + ".wav");
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete cache file {}", path, e);
        }
    }

    private void loadIndex() {
        if (!Files.exists(indexFile)) {
            return;
        }
        try {
            Map<String, IndexEntry> loaded = MAPPER.readValue(indexFile.toFile(),
                    new TypeReference<LinkedHashMap<String, IndexEntry>>() {
                    });
            index.putAll(loaded);
        } catch (IOException e) {
            log.warn("Cache index at {} is unreadable; starting with an empty cache", indexFile, e);
        }
    }

    private void persistIndex() {
        try {
            Path tempFile = Files.createTempFile(cacheDir, "index-", ".json.tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), index);
            Files.move(tempFile, indexFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Failed to persist cache index to {}", indexFile, e);
        }
    }

    /** Plain mutable bean so Jackson leaves a field absent from an older index file as {@code null}. */
    public static class IndexEntry {
        public String hash;
        public String providerName;
        public String formatTag;
        public long sizeBytes;
        public long lastAccessedEpoch;
        public long createdEpoch;
    }
}
