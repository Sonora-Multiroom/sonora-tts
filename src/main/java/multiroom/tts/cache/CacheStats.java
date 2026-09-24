package multiroom.tts.cache;

import java.util.Map;

/** Snapshot of cache occupancy, as published by {@code GET /api/tts/cache/stats}. */
public record CacheStats(int totalEntries, long totalSizeBytes, long maxSizeBytes,
                          Map<String, Integer> entriesByProvider) {
}
