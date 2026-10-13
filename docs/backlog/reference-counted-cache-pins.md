# Pin cache entries atomically and count the pins

**Status:** Proposed, not started. Written 2026-10-10, while moving the open findings of the
2026-09-21 correctness review into the backlog.

**Origin:** Findings 5 and 6 of the local correctness review
(`docs/reviews/2026-09-21-correctness-review.md`, not tracked), re-checked against the code at
`923380c`. The review's own re-check of 2026-09-25 noted that one change fixes both.

**Depends on:** nothing. Related: [queue-stop-start-contract.md](queue-stop-start-contract.md)
(another way a pin is left behind).

**Effort:** Small to Medium. The change is local to `FilesystemAudioCache` and its two call sites in
`TtsService`. Most of the work is the concurrency tests.

## Problem

`FilesystemAudioCache` protects an entry from eviction and from operator removal while an
announcement still needs its file. That protection has two gaps.

1. **Pins are not counted.** `pinned` is a plain `Set<String>` (`FilesystemAudioCache.java:46`).
   Take two identical announcements (same text, provider, voice, language and format) sent to
   different targets. They share one cache key and both pin it. When the first one ends, its
   `unpin` removes the hash from the set, so the second announcement, still queued, is no longer
   protected. If the next `put` pushes the cache over `max-size-mb`, eviction deletes the file and
   the second announcement fails when it activates. If an operator cleared the cache while both
   were pinned, the first `unpin` also carries out the deferred removal (`pendingDeletion`) and
   deletes the file under the second. This happens on the ordinary path: nothing has to fail first.
2. **Pinning is not atomic with the lookup.** `TtsService` calls `audioCache.get(cacheKey)` or
   `audioCache.put(...)` and then makes a separate `audioCache.pin(cacheKey)` call
   (`TtsService.java:153-171`). `get`, `put` and `pin` each take `lock`, but separately. In the
   window between them, a concurrent `put` from another `speak` can evict the entry that was just
   found or written. `pin` then marks a hash that is no longer in the index, and the announcement
   points at a deleted file.

What happens when it fires: a queued announcement fails as `TTS_PLAYBACK_FAILED` because its WAV is
missing. Both gaps need concurrent announcements and either a cache at its size cap or an operator
clearing the cache, so they have not been seen in production. Neither has a test.

## Proposal

- Replace `Set<String> pinned` with a `Map<String, Integer>` reference count, guarded by `lock`
  like the rest of the state. `unpin` decrements the count. Only when the count reaches zero does
  it drop the key and carry out a deferred removal.
- Pin inside the lookup. Either add `getAndPin(CacheKey)` / `putAndPin(CacheKey, byte[])`, or give
  `get` and `put` a pin flag, so that finding the entry and protecting it happen under one hold of
  `lock`. Drop the separate `pin` call from `TtsService`.
- `evictIfNeeded`, `stats` and the deferred-removal path test "count > 0" instead of set
  membership.
- Tests: two pins and one unpin keep the entry protected from eviction and from `clear`. The
  second unpin releases it and runs the deferred removal. `getAndPin` and a concurrent `put` that
  triggers eviction never evict the pinned entry. Keep the seven existing pinned-entry cases in
  `FilesystemAudioCacheTest` passing.

Not covered: pins left behind by discarded queue tasks, which is
[queue-stop-start-contract.md](queue-stop-start-contract.md).

## Open questions

1. API shape: should `get`/`put` always pin (every caller in this module wants a pin), or should
   pinning stay opt-in through new methods? `TtsCacheController` and the metrics read the cache
   without wanting a pin.
2. Should a count that would drop below zero (an `unpin` with no matching pin) be logged at WARN,
   since it means a bookkeeping bug, or ignored as `unpin` is today?
