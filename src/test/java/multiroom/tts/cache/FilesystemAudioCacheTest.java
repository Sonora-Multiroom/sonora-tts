package multiroom.tts.cache;

import multiroom.api.model.SampleFormat;
import multiroom.api.model.SampleType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FilesystemAudioCacheTest {

    private static final SampleFormat FORMAT = SampleFormat.standard();

    private static CacheKey key(String text) {
        return new CacheKey(text, "openai", "tts-1", "alloy", "en-US", FORMAT);
    }

    @Test
    void missWhenNeverWritten(@TempDir Path dir) {
        AudioCache cache = new FilesystemAudioCache(dir, 500);

        assertThat(cache.get(key("hello"))).isEmpty();
    }

    @Test
    void putThenGetReturnsAReadableWavPath(@TempDir Path dir) {
        AudioCache cache = new FilesystemAudioCache(dir, 500);
        CacheKey key = key("hello");

        Path written = cache.put(key, new byte[] {1, 2, 3, 4});

        assertThat(Files.exists(written)).isTrue();
        assertThat(cache.get(key)).contains(written);
    }

    @Test
    void sameTextAtDifferentTargetFormatIsAMissAndBothCoexist(@TempDir Path dir) {
        AudioCache cache = new FilesystemAudioCache(dir, 500);
        SampleFormat otherFormat = new SampleFormat(44100, 16, 1, SampleType.PCM);
        CacheKey keyA = key("hello");
        CacheKey keyB = new CacheKey("hello", "openai", "tts-1", "alloy", "en-US", otherFormat);

        Path pathA = cache.put(keyA, new byte[] {1});
        assertThat(cache.get(keyB)).isEmpty();
        Path pathB = cache.put(keyB, new byte[] {2});

        assertThat(pathA).isNotEqualTo(pathB);
        assertThat(cache.get(keyA)).contains(pathA);
        assertThat(cache.get(keyB)).contains(pathB);
    }

    @Test
    void missingUnderlyingFileIsATreatedAsAMiss(@TempDir Path dir) throws Exception {
        AudioCache cache = new FilesystemAudioCache(dir, 500);
        CacheKey key = key("hello");
        Path written = cache.put(key, new byte[] {1, 2});

        Files.delete(written);

        assertThat(cache.get(key)).isEmpty();
    }

    @Test
    void lruEvictsLeastRecentlyUsedWhenOverSizeLimit(@TempDir Path dir) {
        // 1 MB limit; three ~500KB entries -> the least recently used is evicted.
        AudioCache cache = new FilesystemAudioCache(dir, 1);
        byte[] chunk = new byte[500_000];

        CacheKey a = key("a");
        CacheKey b = key("b");
        cache.put(a, chunk);
        cache.put(b, chunk);
        cache.get(a); // touch a so b becomes the least recently used
        CacheKey c = key("c");
        cache.put(c, chunk);

        assertThat(cache.get(b)).isEmpty();
        assertThat(cache.get(a)).isPresent();
        assertThat(cache.get(c)).isPresent();
    }

    @Test
    void pinnedEntryIsNeverEvictedEvenWhenLeastRecentlyUsed(@TempDir Path dir) {
        AudioCache cache = new FilesystemAudioCache(dir, 1);
        byte[] chunk = new byte[500_000];

        CacheKey a = key("a");
        CacheKey b = key("b");
        cache.put(a, chunk);
        cache.pin(a);
        cache.put(b, chunk);
        CacheKey c = key("c");
        cache.put(c, chunk);

        assertThat(cache.get(a)).isPresent();
    }

    @Test
    void clearRemovesEveryEntry(@TempDir Path dir) {
        AudioCache cache = new FilesystemAudioCache(dir, 500);
        cache.put(key("a"), new byte[] {1});
        cache.put(key("b"), new byte[] {2});

        cache.clear();

        assertThat(cache.get(key("a"))).isEmpty();
        assertThat(cache.get(key("b"))).isEmpty();
        assertThat(cache.stats().totalEntries()).isZero();
    }

    @Test
    void clearKeepsAPinnedFileOnDiskSoTheQueuedAnnouncementCanStillPlay(@TempDir Path dir) {
        FilesystemAudioCache cache = new FilesystemAudioCache(dir, 500);
        CacheKey pinnedKey = key("a");
        Path file = cache.put(pinnedKey, new byte[] {1});
        cache.put(key("b"), new byte[] {2});
        cache.pin(pinnedKey);

        cache.clear();

        // TtsInputResolver is about to hand core this path — it must still resolve.
        assertThat(file).exists();
        assertThat(cache.get(key("b"))).isEmpty();
    }

    @Test
    void aPinnedEntryStopsServingNewRequestsTheMomentItIsCleared(@TempDir Path dir) {
        AudioCache cache = new FilesystemAudioCache(dir, 500);
        CacheKey pinnedKey = key("a");
        cache.put(pinnedKey, new byte[] {1});
        cache.pin(pinnedKey);

        cache.clear();

        // Surviving the clear is a courtesy to the in-flight announcement, not a cache hit for
        // the next one — an operator clearing after a voice change must not get a stale hit.
        assertThat(cache.get(pinnedKey)).isEmpty();
    }

    @Test
    void unpinCarriesOutTheRemovalThatThePinDeferred(@TempDir Path dir) {
        FilesystemAudioCache cache = new FilesystemAudioCache(dir, 500);
        CacheKey pinnedKey = key("a");
        Path file = cache.put(pinnedKey, new byte[] {1});
        cache.pin(pinnedKey);
        cache.clear();

        cache.unpin(pinnedKey);

        assertThat(file).doesNotExist();
        assertThat(cache.get(pinnedKey)).isEmpty();
        assertThat(cache.stats().totalEntries()).isZero();
    }

    @Test
    void rewritingAClearedButStillPinnedEntryCancelsTheDeferredRemoval(@TempDir Path dir) {
        FilesystemAudioCache cache = new FilesystemAudioCache(dir, 500);
        CacheKey pinnedKey = key("a");
        cache.put(pinnedKey, new byte[] {1});
        cache.pin(pinnedKey);
        cache.clear();

        Path rewritten = cache.put(pinnedKey, new byte[] {2});
        cache.unpin(pinnedKey);

        // The removal applied to the content that existed when it was issued, not to this write.
        assertThat(rewritten).exists();
        assertThat(cache.get(pinnedKey)).isPresent();
    }

    @Test
    void invalidateDefersAPinnedEntryTheSameWayClearDoes(@TempDir Path dir) {
        FilesystemAudioCache cache = new FilesystemAudioCache(dir, 500);
        CacheKey pinnedKey = key("a");
        Path file = cache.put(pinnedKey, new byte[] {1});
        cache.pin(pinnedKey);

        cache.invalidate(pinnedKey);
        assertThat(file).exists();

        cache.unpin(pinnedKey);
        assertThat(file).doesNotExist();
    }

    @Test
    void clearByProviderDefersAPinnedEntryTheSameWayClearDoes(@TempDir Path dir) {
        FilesystemAudioCache cache = new FilesystemAudioCache(dir, 500);
        CacheKey pinnedKey = key("a");
        Path file = cache.put(pinnedKey, new byte[] {1});
        cache.pin(pinnedKey);

        cache.clearByProvider("openai");
        assertThat(file).exists();

        cache.unpin(pinnedKey);
        assertThat(file).doesNotExist();
    }

    @Test
    void unpinIsAPlainReleaseWhenNoRemovalWasRequested(@TempDir Path dir) {
        FilesystemAudioCache cache = new FilesystemAudioCache(dir, 500);
        CacheKey pinnedKey = key("a");
        Path file = cache.put(pinnedKey, new byte[] {1});
        cache.pin(pinnedKey);

        cache.unpin(pinnedKey);

        assertThat(file).exists();
        assertThat(cache.get(pinnedKey)).isPresent();
    }

    @Test
    void clearByProviderOnlyClearsThatProvider(@TempDir Path dir) {
        AudioCache cache = new FilesystemAudioCache(dir, 500);
        CacheKey openaiKey = key("a");
        CacheKey piperKey = new CacheKey("a", "piper-local", "default", "ryan", "en-US", FORMAT);
        cache.put(openaiKey, new byte[] {1});
        cache.put(piperKey, new byte[] {2});

        cache.clearByProvider("openai");

        assertThat(cache.get(openaiKey)).isEmpty();
        assertThat(cache.get(piperKey)).isPresent();
    }

    @Test
    void indexEntryWithoutFormatTagIsTreatedAsAMiss(@TempDir Path dir) throws Exception {
        AudioCache cache = new FilesystemAudioCache(dir, 500);
        CacheKey key = key("hello");
        Path written = cache.put(key, new byte[] {1, 2});
        assertThat(written).exists();

        // Simulate an index.json written before targetFormat existed by writing a legacy record
        // with no formatTag field, then reloading a fresh cache instance from disk.
        Files.writeString(dir.resolve("index.json"), """
                {"%s": {"hash":"%s","providerName":"openai","sizeBytes":2,\
                "lastAccessedEpoch":1,"createdEpoch":1}}
                """.formatted(key.toHash(), key.toHash()));

        AudioCache reloaded = new FilesystemAudioCache(dir, 500);
        assertThat(reloaded.get(key)).isEmpty();
    }

    @Test
    void statsReportEntryCountsAndSize(@TempDir Path dir) {
        AudioCache cache = new FilesystemAudioCache(dir, 500);
        cache.put(key("a"), new byte[] {1, 2, 3});

        CacheStats stats = cache.stats();

        assertThat(stats.totalEntries()).isEqualTo(1);
        assertThat(stats.totalSizeBytes()).isGreaterThan(0);
        assertThat(stats.entriesByProvider()).containsEntry("openai", 1);
    }

    @Test
    void indexSurvivesRestart(@TempDir Path dir) {
        FilesystemAudioCache first = new FilesystemAudioCache(dir, 500);
        Path written = first.put(key("hello"), new byte[] {1, 2, 3});

        FilesystemAudioCache reopened = new FilesystemAudioCache(dir, 500);

        assertThat(reopened.get(key("hello"))).contains(written);
    }
}
