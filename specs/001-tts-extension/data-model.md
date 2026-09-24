# Data Model: TTS Extension

**Feature**: `001-tts-extension`  
**Date**: 2026-05-30, revised 2026-09-18, TtsProviderConfig's `PIPER` fields revised 2026-09-20

---

## Domain Overview

```text
TtsRequest
  |-- text: String                (1-500 chars, configurable max)
  |-- targetName: String          (OutputId or GroupId name)
  |-- targetType: TargetType      (SINGLE_OUTPUT | OUTPUT_GROUP)
  |-- providerName: String?       (null -> use default)
  |-- voice: String?              (null -> use provider default)
  '-- language: String?           (null -> use provider default)
          |
          v
    ProviderRegistry
          |  resolves by name (first = default)
          v
      TtsProvider  <---------------------------------------------------.
          |  synthesize(SynthesisRequest)                              |
          v                                                            |
    SynthesisResult                                                    |
          |  audioData: byte[]  (the provider's WAV response)          |
          v                                                            |
    AudioConverter (AudioSystem + FormatConverter) -> PCM -> WavFileWriter |
          |                                                            |
          v                                                            |
      AudioCache                                                       |
          |  put(CacheKey, pcm, fmt) -> Path   get(CacheKey) -> Path   |
          |  <cache-dir>/<sha256>.wav  -- this file IS the artifact    |
          |                               that gets played            |
          v                                                            |
   AnnouncementQueueManager --> queued per target --> TtsService ------'
          |                                               |
          v                                               v
  DeviceRegistryService.registerInput()        RouteService.createRoute()
  (uri = "tts://<uuid>", autoRemove = true)    SINGLE_OUTPUT -> (InputId, OutputId)
          |                                    OUTPUT_GROUP  -> (InputId, GroupId)
          v
    TtsInputResolver   (@Bean InputEndpointResolver)
          |  tts://<uuid>  ->  file:///<cache-dir>/<sha256>.wav
          v
    core's WavFileInputHandler
       (an extension cannot supply an InputHandler, so the resolver
        must terminate on a scheme core already handles -- research 2.1)
```

---

## Entities

### TtsRequest *(value object — inbound API request)*

> `providerName`, `voice` and `language` are each independently optional (FR-002, FR-030). An omitted
> field falls back to the chosen provider's configured default; an overridden one changes the
> `CacheKey`, so the same text at a different voice is a different entry. The target audio format is not
> a request field — it comes from the system — but it joins the key for the same reason (FR-015).

| Field | Type | Required | Constraints | Notes |
|-------|------|----------|-------------|-------|
| `text` | `String` | Yes | 1–`maxTextLength` chars (default 500), not blank | The speech content |
| `targetName` | `String` | Yes | Must match a known output or output group name | Target destination |
| `targetType` | `TargetType` | Yes | `multiroom.api.model.TargetType`: `SINGLE_OUTPUT` or `OUTPUT_GROUP` | The published API enum, reused verbatim — TTS defines no target-type enum of its own. `SINGLE_OUTPUT` selects `RouteService.createRoute(InputId, OutputId)`, `OUTPUT_GROUP` selects `createRoute(InputId, GroupId)` |
| `providerName` | `String` | No | Must match a configured provider name if present | Null → use default provider |
| `voice` | `String` | No | Provider-specific voice identifier | Null → use provider default voice |
| `language` | `String` | No | BCP 47 language tag (e.g., `en-US`, `de-DE`) | Null → use provider default language |

**Validation rules**:
- `text` after trimming must not be blank
- `text` length ≤ `multiroom.tts.max-text-length` (default: 500)
- `targetName` must resolve to a known `OutputId` or `GroupId`, checked against `DeviceQueryService.getOutput` / `getGroup`
- `providerName`, if present, must match a configured provider name

---

### SynthesisRequest *(internal value object — passed to TtsProvider)*

| Field | Type | Notes |
|-------|------|-------|
| `text` | `String` | Validated, trimmed text |
| `voice` | `String` | Resolved voice (from request override or provider default) |
| `language` | `String` | Resolved language (from request override or provider default) |
| `targetSampleRate` | `int` | System-native sample rate (e.g., 48000) |
| `targetChannels` | `int` | System-native channel count (e.g., 2) |

---

### SynthesisResult *(internal value object — returned by TtsProvider)*

| Field | Type | Notes |
|-------|------|-------|
| `audioData` | `byte[]` | The provider's WAV response, as received. `AudioConverter` reads it with the JDK's `AudioSystem.getAudioInputStream` and converts it to system-native PCM through the injected `multiroom.api.conversion.FormatConverter`; `WavFileWriter` then writes the cache's `.wav` file |
| `sampleRate` | `int` | Actual sample rate of the audio |
| `channels` | `int` | Number of channels |
| `bitDepth` | `int` | Bits per sample (16 for all providers) |

---

### CacheKey *(value object — disk cache lookup key)*

Computed as: `SHA-256(text || "|" || providerName || "|" || engineName || "|" || voice || "|" || language || "|" || targetFormat)`

| Field | Type | Notes |
|-------|------|-------|
| `text` | `String` | Verbatim text content |
| `providerName` | `String` | Configured provider name (e.g., `"openai"`) |
| `engineName` | `String` | Provider model/engine (e.g., `"tts-1"`, `"neural2"`) |
| `voice` | `String` | Voice identifier after defaults resolved |
| `language` | `String` | Language tag after defaults resolved |
| `targetFormat` | `SampleFormat` | The format the cached WAV was converted to, folded in as `sampleRate\|channels\|bitDepth\|sampleType`. A cached file outlives restarts and output-device changes, so an entry converted for one native format must be a **miss** for another, never a wrong-format hit |

`toHash()` → `String` (hex SHA-256, used as filename: `<hash>.wav`)

**Java Record**:
```java
public record CacheKey(String text, String providerName, String engineName, String voice, String language,
                       SampleFormat targetFormat) {
    public String toHash() { /* SHA-256 of all fields, targetFormat flattened to its components */ }
}
```

---

### CacheEntry *(persisted in index.json)*

| Field | Type | Notes |
|-------|------|-------|
| `hash` | `String` | SHA-256 hex of the full `CacheKey` including `targetFormat`; filename = `<hash>.wav` — a playable WAV in the format that key names, not raw PCM, because `TtsInputResolver` hands core this file's `file://` URI |
| `providerName` | `String` | Which provider produced this entry |
| `formatTag` | `String` | The `targetFormat` the file was written in, as `sampleRate\|channels\|bitDepth\|sampleType`. Redundant with the hash by construction, but it makes the index self-describing: an entry from an older index that carries no `formatTag` is read as a **miss** and re-synthesized, rather than being served in an unknown format |
| `sizeBytes` | `long` | File size for cache-size accounting |
| `lastAccessedEpoch` | `long` | Unix epoch milliseconds; updated on every read |
| `createdEpoch` | `long` | Creation timestamp; informational |

**Eviction rule**: When total `sizeBytes` of all entries exceeds `cacheMaxSizeMb × 1024 × 1024`, entries sorted by `lastAccessedEpoch` ascending are deleted until under limit. **Pinned entries are never evicted**: `TtsInputResolver` pins an entry from the moment an announcement is enqueued until its route ends, so a queued announcement cannot have its file deleted out from under it.

---

### TtsProviderConfig *(configuration record — bound from the operator's configuration)*

> **Defaults live in code, not in a bundled YAML file.** Under the shared runtime an
> `application.yml` inside an extension JAR shadows core's own, so
> `019-extension-shared-classloader` forbids it (its FR-024) and fails the build on it. Every
> default in the two tables below is a field initialiser on the `@ConfigurationProperties` class.
> Operators still set these in *their* `application.yml`; the module simply does not ship one.

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `name` | `String` | — | Unique identifier; used in requests and cache key |
| `type` | `ProviderType` | — | `OPENAI`, `GOOGLE_CLOUD`, `PIPER`, `LOCAL_HTTP` |
| `enabled` | `boolean` | `true` | Set to `false` to disable without removing config |
| `apiKey` | `String` | — | Cloud providers only; supports env var interpolation |
| `voice` | `String` | Provider default | Default voice for this provider |
| `language` | `String` | `"en-US"` | Default language/locale |
| `engine` | `String` | Provider default | Model/engine name; part of cache key |
| `timeoutSeconds` | `int` | `10` (all providers) | Synthesis call timeout. Values above 10 s are permitted but warned about at start-up: SC-003's bound holds only at the default (FR-022) |
| `extraParams` | `Map<String, String>` | `{}` | Provider-specific parameters (e.g., `speaking_rate`, `pitch`) |

**Provider-specific fields** *(revised 2026-09-20 — `PIPER` now targets piper1-gpl, not the
archived `rhasspy/piper` C++ binary; see research.md §1.2)*:
- `OPENAI`, `GOOGLE_CLOUD`: no extra fields beyond the generic table above — `engine` carries the
  model name (e.g. `tts-1`), and the fixed choices (`response_format: wav`,
  `audioEncoding: LINEAR16`) are hardcoded in each provider, not configurable
- `PIPER`: `pythonExecutable` (default `python3` — the interpreter with `piper-tts` installed via
  `pip install piper-tts`; piper1-gpl ships no standalone binary or console script, only a
  `python3 -m piper` module), `modelPath` (path to the `.onnx` file; its `.onnx.json` config
  sidecar must sit next to it)
- `LOCAL_HTTP`: `endpoint` (URL), `requestTemplate` (optional JSON template)

---

### TtsProperties *(Spring `@ConfigurationProperties("multiroom.tts")`)*

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `providers` | `List<TtsProviderConfig>` | — | At least one required; first = default |
| `defaultProvider` | `String` | *(first in list)* | Explicit default provider name |
| `cache.dir` | `Path` | `${user.home}/.multiroom/tts-cache` | Cache root directory |
| `cache.maxSizeMb` | `int` | `500` | Maximum total cache size in MB |
| `maxTextLength` | `int` | `500` | Maximum allowed text length per request |
| `queue.maxDepthPerTarget` | `int` | `10` | Maximum pending announcements per target |
| `enabled` | `boolean` | `true` | Switches the whole extension off without removing its distributable (019 FR-008). Backs `@ConditionalOnProperty` on `TtsAutoConfiguration`; when `false` the module contributes no bean and the inventory reports it `DISABLED` |

**Validation happens at start-up and never touches the network.** Missing credentials, an
unparseable block, or a `modelPath` (or its `.onnx.json` sidecar) that does not exist are
operator-fixable faults and MUST abort start-up naming this extension (spec FR-028).
`pythonExecutable` is deliberately not path-checked — it defaults to `python3`, a bare
`PATH`-resolved command rather than a literal file, and validation must never spawn a process to
test it. A provider that is configured correctly but unreachable is NOT a fault: the extension
loads and individual requests fail (spec FR-029). This is the line
`019-extension-shared-classloader` draws between its FR-018 and FR-019, and it is the easiest thing
in this module to get wrong.

---

### AnnouncementTask *(internal queue entry)*

| Field | Type | Notes |
|-------|------|-------|
| `request` | `TtsRequest` | The validated original request |
| `audioFile` | `Path` | The playable WAV — normally the pinned cache entry, or a temp file when the cache write failed (FR-025). Never raw bytes: core plays it through a `file://` URI |
| `announcementId` | `UUID` | The `tts://<uuid>` key the resolver maps back to `audioFile` |
| `submittedAt` | `Instant` | Submission time; informational for logging |

---

## State Transitions

### TTS Request Lifecycle

```text
RECEIVED
    |
    v  [validate: text, target exists, provider known]
VALIDATED ----- [invalid] ----> REJECTED (error response)
    |
    v  [cache lookup by CacheKey — text + provider + engine + voice + language + target format]
CACHE_CHECK
    |-- [hit]  ----> AUDIO_READY   (Path to <sha256>.wav, pinned)
    '-- [miss] ----> SYNTHESIZING
                        |  [provider call within timeout]
                        |-- [success] --> CONVERTING ---> AUDIO_READY
                        |                 (AudioSystem reads the WAV;
                        |                  the INJECTED FormatConverter converts
                        |                  to native; WavFileWriter writes it to
                        |                  the cache, or to a temp file when the
                        |                  cache write fails -- FR-025)
                        |                 [canConvert false, or
                        |                  FormatConversionException] --> FAILED
                        '-- [error/timeout] --> FAILED (error response)
AUDIO_READY
    |
    v  [enqueue for target; cache entry stays pinned]
QUEUED
    |  [queue worker picks up]
    v
ROUTING
    |   1. snapshot routes targeting this OutputId/GroupId (getAllRoutes)
    |   2. stop them (stopRoutesByOutput / stopRoutesByGroup)
    |   3. registerInput(uri = "tts://<uuid>", autoRemove = true)
    |   4. createRoute(InputId, OutputId | GroupId)
    |      -> core resolves tts://<uuid> to file:///<cache-dir>/<sha256>.wav
    v
PLAYING
    |  [file hits EOF -> pipeline drains -> core deregisters the route
    |   and publishes RouteDestroyedEvent -- the ONLY signal an extension
    |   can observe; PlaybackCompletionListener matches it by InputId]
    v
RESTORING
    |   1. re-create the snapshotted routes
    |   2. unpin the cache entry; delete the temp file if one was used
    |   3. the ephemeral input is already gone -- autoRemove + core's
    |      AutoRemoveInputListener handled the same event. This module
    |      MUST NOT call unregisterInput (it would throw)
    v
COMPLETED
```

---

## Runtime presence in the extension inventory

`019-extension-shared-classloader` makes every discovered extension queryable at run time. This
module appears on both surfaces with no work of its own beyond existing:

| Field | Value for `multiroom-tts` |
|---|---|
| `name` / `version` / `requiredApiVersion` | From the manifest, written by `multiroom-extension-starter` |
| `status` | `ACTIVE` once it contributes beans; `INERT` if the `.imports` file is missing or names the wrong class; `DISABLED` when `multiroom.tts.enabled=false`; `REJECTED` if built against a different core version |
| `connectionState` | **`NOT_APPLICABLE`** — providers are contacted per request, not held open. There is no connection to report and no reporter to build, unlike MQTT's broker or DLNA's jupnp stack |

---

## Key Relationships

- **`TtsService`** coordinates: validates request → consults `AudioCache` → calls `TtsProvider` (if miss) → enqueues in `AnnouncementQueueManager`
- **`AnnouncementQueueManager`** owns one `LinkedBlockingQueue<AnnouncementTask>` per target; spawns a worker thread per active target that serializes playback
- **`TtsInputResolver`** implements `InputEndpointResolver`; holds `ConcurrentHashMap<UUID, Path>` mapping each announcement's UUID to its WAV file, and rewrites `tts://<uuid>` to that file's `file://` URI. It cannot hand core raw bytes: core resolves a URI and then looks its *scheme* up among `InputHandler` beans, and `InputHandler` lives in `multiroom-core`, which this module may not depend on (research §2.1). It also holds the eviction pin for the duration of the announcement
- **`FilesystemAudioCache`** owns the disk index + `<sha256>.wav` files, and hands out `Path`s rather than `byte[]`; a plain JDK in-memory index (access-ordered `LinkedHashMap`) wraps it for hot entries — no third-party cache library, see research §3
- **`ProviderRegistry`** holds the named `TtsProvider` instances; `resolveDefault()` returns the first provider in the configured list when no explicit default is set (FR-005), and `resolve(name)` the one a request names
