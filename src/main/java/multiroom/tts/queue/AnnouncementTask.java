package multiroom.tts.queue;

import multiroom.api.model.InputId;
import multiroom.api.model.Route;
import multiroom.api.model.TargetType;
import multiroom.tts.cache.CacheKey;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * A synthesized, cached-or-spilled announcement waiting for its target output to free up. Every
 * field is known before this is enqueued — synthesis, conversion and the WAV write already
 * happened in the request thread; only {@code registerInput} + {@code createRoute} are deferred.
 *
 * @param cacheKey      {@code null} when {@code audioFile} is a spillover temp file rather than a
 *                       pinned cache entry
 */
public record AnnouncementTask(UUID announcementId, InputId inputId, TargetType targetType, String targetName,
                                Path audioFile, boolean temporaryFile, List<Route> routeSnapshot,
                                CacheKey cacheKey) {
}
