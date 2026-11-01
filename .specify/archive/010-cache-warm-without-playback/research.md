# Research: Warm The Cache Without Playback

The Technical Context had no unknowns; these are the design decisions. The proposal's validation
against the code and production is recorded in `docs/backlog/cache-warm-without-playback.md`.

## 1. Sharing the path with `announce`

**Decision**: Extract two private steps from `TtsService.announce` and call them from both paths.
One resolves the provider, settings and `CacheKey` into a small private record. The other
synthesizes, converts and records `audioProduced`, returning PCM. The cache lookup, its metric, the
pin and the temporary-file fallback stay in each caller, because that is where the two differ.

**Rationale**: FR-005 requires one cache identity. A second copy of key construction would pass every
test on the day it was written and silently turn warm-ups into misses after the next key dimension.

**Alternatives considered**: A `playback` flag threaded through `announce`, rejected because almost
every line after the lookup would branch. A separate `CacheWarmService`, rejected because
`TtsService.synthesize` is the single measured provider call site, and a second service would need it
made public.

## 2. Validation order

**Decision**: `warm` validates the text, then resolves the provider and settings. The `announce`
order keeps its playback and target checks between the two and does not change.

**Rationale**: Same codes as speak (FR-003). `resolveSettings` raises provider-specific 400s, so the
order among the shared checks stays the same.

## 3. Reporting an entry that was not stored

**Decision**: `warm` catches `CacheWriteException` and throws
`TtsException(CACHE_WRITE_FAILED, …)`. It also checks that the path `put` returned still exists, and
answers the same error if not. The message is a fixed sentence, never the exception's text, which
carries a path.

**Rationale**: `FilesystemAudioCache.evictIfNeeded` walks the whole index, so an entry larger than
the cap, or a new entry in a cache otherwise full of pinned entries, is evicted by its own `put`.
`put` then returns a path to a deleted file. An announcement does not notice until activation; a
warm-up would report success, which SC-007 forbids. `CACHE_WRITE_FAILED` maps to 503 with no handler
change, because the handler sends everything outside `CALLER_FIXABLE` there.

An oversized entry evicts every unpinned entry before itself. Production cannot reach that case:
text is limited to 500 characters and the cap is 500 MB.

**Accepted gap**: `persistIndex` only logs a failed `index.json` write, so a warm-up can answer 200
with the entry in memory and on disk, and lose it at the next restart. The file write has just
succeeded on the same disk, so this needs a fault in that narrow window, and the next scheduled
re-warm repairs it. Reporting it would change `AudioCache` for announcements too, which this feature
does not need.

**Alternatives considered**: Changing `evictIfNeeded` to spare the new entry, rejected as a change to
announcement behaviour that this feature does not need. Reusing `PROVIDER_ERROR`, rejected because
the provider succeeded, and a caller's retry would bill it again for a fault on the Pi's disk.

## 4. Metric shape

**Decision**: A new counter `tts.cache.warm` (`tts_cache_warm_total`) with tags `outcome` (`hit`,
`synthesized`, `failed`), `error` (a code, `INTERNAL`, or `none`) and `provider` (configured name or
`unknown`). Two `TtsMetrics` methods: `cacheWarmed(providerTag, hit)` and
`cacheWarmFailed(providerTag, error)`. `warm` wraps the work the way `speak` does, so every answer is
counted once. The `hit` and `synthesized` series of each configured provider start at 0, as the
accepted-announcement series do.

**Rationale**: A new name cannot disturb an existing series' tag keys. `warm` does not call
`cacheLookup`, whose metric is defined as lookups made for announcements (005). Synthesis metrics are
recorded inside `synthesize`, so a warm-up's provider work is counted without extra code.

**Alternatives considered**: A `kind` tag on `tts_cache_requests_total` or `tts_announcements_total`,
rejected: it changes every existing series and the documented queries.

## 5. Requests refused before the service

**Decision**: `TtsExceptionHandler.invalidRequest` gains a second branch: for a handler in
`TtsCacheApi`, call `metrics.cacheWarmFailed(UNKNOWN, INVALID_REQUEST)`.

**Rationale**: FR-014. Only `warm` has a request body in that interface, and these two exceptions
arise only from a body, so the interface check is exact. It keeps warm-ups out of the announcement
count, which is why the endpoint is not on `TtsApi`.

## 6. Speak-only fields in a warm body

**Decision**: `WarmRequest` is annotated `@JsonIgnoreProperties(ignoreUnknown = true)`.

**Rationale**: The spec lets a caller reuse its speak body. The hub's Jackson setup is not this
module's contract, so the DTO states the rule itself. Because the fields are not on the DTO, a bad
`targetType` in the body is not an error either.

## 7. Blocking answer

**Decision**: Synchronous, in the request thread, like synthesis for a speak. No async job, no batch.

**Rationale**: FR-010 answers once the entry is stored. A caller's HTTP timeout must exceed the
provider's `timeout-seconds`, which `docs/configuration.md` will say.

## 8. Concurrency

**Decision**: No single-flight or lock. Two simultaneous misses of one text both synthesize.

**Rationale**: This is already true of speak, and the spec accepts it. `WavFileWriter` replaces the
file atomically and `put` updates the index under its lock, so the result is one good entry and a
playing announcement keeps its open file.

## 9. Version

**Decision**: 0.1.11. `multiroom.require_api_version` stays at 0.1.29.
