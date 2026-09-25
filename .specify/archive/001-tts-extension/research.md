# Research: TTS Extension

**Feature**: `001-tts-extension`

**Date**: 2026-05-30, section 6 rewritten 2026-09-12, sections 2.1/2.2/3 rewritten 2026-09-18,
section 1.2 rewritten 2026-09-20 (`rhasspy/piper` archived; piper1-gpl targeted instead; piper1-gpl's
HTTP server mode documented as the recommended `LOCAL_HTTP` config alongside the `PIPER` type)

**Status**: Complete — all NEEDS CLARIFICATION resolved

**Baseline**: written against `019-extension-shared-classloader` (a hard prerequisite). Section 6
previously documented the extension-isolation constraint that spec has since removed, and the
infrastructure phase built to work around it; both are withdrawn — see that section.

---

## 1. TTS Provider Library Choices

### 1.1 Cloud-Based Providers

| Decision | Rationale | Alternatives Considered |
|----------|-----------|------------------------|
| **OpenAI TTS** as first-class cloud provider | Simplest REST API (`POST /v1/audio/speech`), 6 natural-sounding voices, returns WAV directly (no SDK needed), ~$0.000015/char | Google Cloud TTS (excellent quality, more voices, but OAuth2 setup complexity); AWS Polly (good quality, SDK-heavy); Azure (WebSocket streaming, native SDK required) |
| **Google Cloud TTS** as second cloud provider | Best voice quality, 200+ voices, returns LINEAR16 (raw PCM) eliminating format conversion, REST-only mode possible | AWS Polly (comparable quality, more setup); IBM Watson (less common) |
| **No vendor SDK dependencies** | Use Java 17 `java.net.http.HttpClient` for all cloud HTTP calls — no extra Maven coordinates, no dependency maintenance | Google Cloud SDK (`google-cloud-texttospeech:2.40.0`) — high quality SDK but adds ~50MB transitive deps |

### 1.2 Local/Offline Providers

| Decision | Rationale | Alternatives Considered |
|----------|-----------|------------------------|
| **Piper TTS** via `ProcessBuilder` subprocess, targeting **piper1-gpl** *(reversed 2026-09-20 — was the `rhasspy/piper` C++ binary)* | `rhasspy/piper` was archived by its owner in October 2025, frozen at v1.2.0 (2023); its actively developed successor, [OHF-Voice/piper1-gpl](https://github.com/OHF-Voice/piper1-gpl) (`pip install piper-tts`, currently 1.8.x), ships no standalone binary or console script at all — only a `python3 -m piper` module. ONNX models (~20-60MB each, a `.onnx` plus a required `.onnx.json` sidecar), fully offline, Raspberry Pi compatible via a normal `pip install` (`onnxruntime` publishes aarch64 wheels) | Coqui TTS (Python subprocess, 2GB deps, slow init); MaryTTS (Java server, good quality, but requires separate server process/port); staying on the archived `rhasspy/piper` binary (simpler process model, but frozen — no fixes, no new voices) |
| **Local HTTP endpoint** as generic local provider | Proxy to any local TTS service (Ollama, FastAPI, custom Python); user provides endpoint URL | Shell subprocess (too brittle for varied local setups) |
| **Piper CLI invocation form** *(reversed 2026-09-20 — was `--output-raw` with headerless PCM)* | `python3 -m piper --model <path> --config <path>.json` with text on stdin; with neither `--output-file` nor `--output-raw` given, this CLI's documented default is a **complete WAV on stdout** — the same shape every other provider's response arrives in, so `AudioConverter` needs no Piper-specific handling | `--output-raw` (headerless PCM — would need the target sample rate known out-of-band, and every other provider is asked for a real WAV specifically so it doesn't need this); `--output-file <path>` to a temp file (works, but adds a filesystem round trip the stdout default doesn't need) |
| **Keep the dedicated `PIPER` provider type *and* document piper1-gpl's `python3 -m piper.http_server` mode as a `LOCAL_HTTP` config** *(added 2026-09-20)* | piper1-gpl also ships an HTTP server that loads the model once and keeps it resident, avoiding the CLI form's per-request model-load cost — a real latency concern against SC-001's 5 s budget on Pi hardware. That server is just another local HTTP service, so `LocalHttpTtsProvider` already covers it with zero new code; docs steer operators there first | Dropping `PIPER` entirely in favour of `LOCAL_HTTP` + the HTTP server (rejected: `PIPER` is the only option that needs no second always-on daemon, which some single-room setups may prefer over the latency win); making `LOCAL_HTTP`'s default request body Piper-aware (rejected: `LOCAL_HTTP` must stay generic — its whole point is not knowing what it's talking to) |

### 1.3 Audio Output Format Strategy

| Decision | Rationale | Alternatives Considered |
|----------|-----------|------------------------|
| **Request WAV/PCM from all providers** (not MP3/OGG) | Eliminates format conversion library; WAV supported by OpenAI, Google LINEAR16, Piper native, and local HTTP | Accept MP3 + use JLayer for decoding (extra dep); ffmpeg subprocess (cross-platform complexity, 30MB binary) |
| **JDK `javax.sound.sampled` for WAV → PCM byte[]** *(reversed 2026-09-20 — was `WavStreamDecoder` from `multiroom-decoder`)* | `AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))` gives an `AudioFormat` and a PCM stream; mapping that onto `multiroom.api.model.SampleFormat` is ~10 lines, and `WavFileWriter` already owns the symmetric write side. **The original rationale lapsed with 019**: the objection to `AudioSystem` was "classloader issues under isolated extension classloaders", and 019 deleted those classloaders — `FlacStreamDecoder` calls the same API in production on every platform including the Pi, and this tree has no jlink image or `module-info.java`, so `java.desktop` is always present. Keeping the module dependency would have pulled JLayer, jaad, javasound-flac, vorbis-java, opus-java, JNA and micrometer behind a 44-byte RIFF header, required a shade exclusion in `multiroom-extension-starter` that nothing would enforce (019's duplicate-class gate was withdrawn), and left the extension depending on a module that is not the public API (constitution VIII) | `WavStreamDecoder` from `multiroom-decoder` (the previous decision — a pipeline-shaped API: `open(InputStream)` then `decodeFrame()` in 2048-sample frames, `InputId` + `MeterRegistry` in the constructor, and `PCMData` / `DecoderException` types that are not published API); a ~40-line RIFF reader in `multiroom.tts.audio` (kept as the backstop if the SPI lookup ever proves fragile); Tritonus (archived) |
| **Google Cloud: use `AUDIO_ENCODING_LINEAR16`** | Returns raw PCM without WAV container overhead; pair with `SampleRateHertz` matching system format | `AUDIO_ENCODING_MP3` (requires MP3 decoding library) |

---

## 2. Audio Playback Integration

### 2.1 Dynamic Input Registration

> **Rewritten 2026-09-18.** The previous version had the extension hold synthesized PCM in memory and
> assumed a `tts://` `InputEndpointResolver` would turn that into a playing stream. It will not. A
> resolver only rewrites a URI; core then looks the *scheme* up in `InputHandlerRegistry` and throws
> `UnsupportedSchemeException` when no `InputHandler` claims it. The existing handlers cover `file`,
> `http`/`https`, the platform hardware schemes and `signal`, and `InputHandler` lives in
> `multiroom.core.io` — a package extensions are forbidden to depend on. A `tts://` handler cannot be
> written here, so the resolver has to terminate on a scheme that already works. This is the same
> shape as `multiroom-resolvers`' `SoundCloudInputResolver`, which resolves its own form to `https://`.

| Decision | Rationale | Alternatives Considered |
|----------|-----------|------------------------|
| **`DeviceRegistryService.registerInput()` + an `InputEndpointResolver` that rewrites `tts://<uuid>` to the `file://` URI of a WAV on disk** | `registerInput` is already published on `multiroom-api` (spec 006; moved from `DeviceService` to `DeviceRegistryService` by spec 015). The extension registers an ephemeral input with `uri = "tts://<uuid>"`; the resolver returns `file:///<cache-dir>/<sha256>.wav`; core's existing `WavFileInputHandler` plays it. The audio is already being written to disk for the cache, so the common path costs no extra I/O | An in-process HTTP endpoint serving the bytes as `http://127.0.0.1:${server.port}/api/tts/audio/<uuid>` — works equally well and keeps the bytes in memory, but adds a fourth endpoint and an HTTP hop to every announcement; moving `InputHandler` into `multiroom-api` so a real `tts://` handler could exist — a core change, outside this feature's "no existing module is touched" boundary |
| **Registering the `file://` URI directly, without `tts://`, was rejected** | The indirection is the one place an announcement's claim on its file lives: the resolver pins the cache entry against LRU eviction between enqueue and playback, and re-materializes it if it was evicted anyway. Core resolves lazily at route-creation time, which is precisely when that check is needed | Registering `file://` directly (one class fewer, but a queued announcement can have its file evicted out from under it) |
| **UUID→`Path` map within `TtsInputResolver`** *(was UUID→`byte[]`)* | Decouples synthesis (writes the WAV, records the path under a UUID) from route activation (core calls the resolver on demand); thread-safe via `ConcurrentHashMap`. Entries are removed on playback completion so the map does not grow | Direct `InputStream` reference (lifecycle management complexity across thread boundary) |
| **The resolver is declared as a plain `@Bean`; nothing registers it** *(revised 2026-09-12)* | Core's `DefaultInputResolutionService` already takes `List<InputEndpointResolver>` by constructor injection (`multiroom-core/src/main/java/multiroom/core/io/inputs/resolve/DefaultInputResolutionService.java:34`). Under the single Spring context 019 delivers, a resolver bean declared by this module is collected automatically | An explicit registration call through `ExtensionContext` — the pre-020 route, and no longer possible: that type is deleted |
| **Ephemeral input lifecycle** | Register before `RouteService.createRoute()`, unregister in the `AnnouncementQueueManager` completion callback once the route ends. `AudioInputDefinition.autoRemove` (API since 0.1.3) is the belt-and-braces backstop: core removes a registered input when its last route is destroyed | Timeout-based cleanup (unreliable), global cleanup on shutdown (already handled by FR-024) |

### 2.2 State Restoration

| Decision | Rationale | Alternatives Considered |
|----------|-----------|------------------------|
| **Snapshot active route for target before announcing; re-create after** | `RouteService.getAllRoutes()` returns current state; filter for routes whose target is the requested `OutputId`/`GroupId`, stop them (`stopRoutesByOutput` / `stopRoutesByGroup`), play the announcement, then re-create the captured `InputId` → target mapping | Mute/unmute approach (doesn't restore stream position); relying on caller to restore (shifts responsibility, violates FR-008) |
| **Completion is detected from `RouteDestroyedEvent`, by a `PlaybackCompletionListener` that is MVP scope** *(added 2026-09-20)* | Nothing in an extension can watch an input finish: `AudioInput.onComplete` is `multiroom.core.io`, banned. The route lifecycle is the public signal — on EOF `AudioPipeline` drains, `RouteManagerImpl.handlePipelineStop` deregisters the route and publishes `RouteDestroyedEvent` from `multiroom-api`. Matching `event.route().getInputId()` against the announcement's ephemeral input identifies it exactly, with no polling and no duration arithmetic. US4's queue later reuses the same signal rather than inventing a second one | Timing the WAV's duration (races the drain, which itself waits `max(500 ms, 50 × buffer)` after the queues empty); polling `getRoute(routeId)` (a busy-wait for an event that already exists); putting the callback in `AnnouncementQueueManager` as the earlier draft did (that component is US4 — US1 would have no way to satisfy FR-008) |
| **The module does not unregister its own ephemeral input** *(added 2026-09-20)* | It registers with `autoRemove = true`, so core's `AutoRemoveInputListener` handles the same `RouteDestroyedEvent` and unregisters first; a second call gets `IllegalArgumentException("Input '…' is not registered")`. One owner, not two | Registering with `autoRemove = false` and owning the unregister (equivalent, but loses the backstop if the module's listener never fires) |

---

### 2.3 Audio Format Conversion *(added 2026-09-20)*

| Decision | Rationale | Alternatives Considered |
|----------|-----------|------------------------|
| **Promote `FormatConverter` + `FormatConversionException` from `multiroom-core` into `multiroom-api`; inject the converter** | Providers return WAV at their own rate/depth/channels (OpenAI 24 kHz mono, Piper 22.05 kHz mono, Google LINEAR16). the JDK's `AudioSystem` parses the container but does not resample, and the system's only resampler — `FormatConverterImpl`, thread-safe, 8–192 kHz, mono↔stereo, 16↔24-bit — sits behind a package extensions may not touch. Moving the *interface* (the implementation stays a core `@Component`) makes it injectable with no duplication. The interface already depends only on `multiroom.api.model.SampleFormat`, and `multiroom.api.exceptions.AudioException`'s Javadoc has listed `FormatConversionException` in the published hierarchy since 0.1.0 — the move puts the class where the docs always said it was. Nothing in the tree catches either type, so no call site changes behaviour | Re-implementing resampling inside `multiroom-tts` (≈300 lines of audio maths duplicated where it cannot share core's tests — constitution I); leaving all conversion to the pipeline, which does convert source→target per route and since `abaa11e` accepts 8–192 kHz sources (playback would work, but FR-023 becomes undetectable until after routing, and cache entries end up in whatever format each provider chose); moving `FormatConverterImpl` too (core keeps its own maths; extensions need the contract, not the algorithm) |

---

## 3. Audio Cache Design

| Decision | Rationale | Alternatives Considered |
|----------|-----------|------------------------|
| **File-based LRU cache**: JSON index file + SHA-256-keyed `.wav` files | Zero external dependencies, transparent to debug, survives restarts, human-inspectable, easy manual clear (delete directory) | Caffeine + disk spillover (requires custom `CacheWriter`, more complex); Redis (server required, overkill); Ehcache (XML config, extra dep) |
| **Cache key = SHA-256(text \|\| providerName \|\| engineName \|\| voice \|\| language)** | Deterministic, collision-resistant, fixed-length filename, naturally includes all dimensions that affect audio output | MD5 (collision risk for security-sensitive deployments, though acceptable here); URL-encoding of key (length unbounded) |
| **Index file: JSON array sorted by `lastAccessed`** | Simple LRU eviction: load index, sort, delete tail entries until under limit. Atomic write via temp file + rename | SQLite (dep); in-memory index only (lost on restart, defeats disk cache purpose); custom binary format (fragile) |
| **Cache stores a WAV file** in the system's native format (`<sha256>.wav`) *(revised 2026-09-18 — was raw `.pcm`)* | The cached file **is** the artifact played back: `TtsInputResolver` hands core its `file://` URI and `WavFileInputHandler` opens it (§2.1). A 44-byte RIFF header is the price of not needing a separate materialization step, a second copy on disk, or a `tts://` handler that cannot be written from an extension. Conversion to the native format still happens once, before the write, through the injected `FormatConverter` (§2.3) | Raw `.pcm` plus a separate playable file written per announcement (two copies, and the cache stops being the thing that is played); store the provider's native format (re-normalization on every cache hit) |
| **Plain JDK in-memory index** wrapping the file-based cache for hot entries *(revised 2026-09-12 — was Caffeine)* | A synchronized `LinkedHashMap` in access order, or a `ConcurrentHashMap` with explicit timestamps, keeps repeated announcements (e.g. "Motion detected") under 100 ms for an index of a few hundred entries. **Caffeine was dropped deliberately**: on the now-shared runtime every bundled third-party library is one more chance of a class-name collision between independently built extensions, and this one buys nothing at this scale. (019's build-time duplicate-class gate, its FR-025, was *withdrawn* on 2026-09-13 precisely because it could not see independently built extensions — so the risk is now carried by keeping bundled dependencies to a minimum, which makes this decision more load-bearing, not less) | Caffeine (a dependency for an eviction policy that is ~20 lines here); pure file I/O per lookup (slow on hot paths); in-memory only (defeats restart survival) |

---

## 4. Announcement Queue Design

| Decision | Rationale | Alternatives Considered |
|----------|-----------|------------------------|
| **`LinkedBlockingQueue<AnnouncementTask>` per target, stored in `ConcurrentHashMap<String, AnnouncementQueue>`** | Lock-free per-target queuing; bounded queue prevents runaway memory growth; thread-per-target worker polls and plays sequentially | Single global queue with routing (complex scheduler); Disruptor (over-engineered for low-volume home automation) |
| **Bounded queue depth (configurable, default 10 per target)** | Prevents memory explosion on repeated automated triggers (e.g., HA automation misconfiguration); newest-drops-oldest policy | Unbounded queue (OOM risk); reject new on full (caller gets error, acceptable but less user-friendly) |
| **Queue worker thread per active target, created on-demand, self-terminating** | No idle threads when no announcements pending; thread starts on first queue entry, stops when queue drains | Thread pool (over-provisioned); virtual threads (Java 21 feature, not available on Java 17) |

---

## 5. Configuration Structure

Resolved decisions for `application.yml` property namespace:

```yaml
multiroom:
  tts:
    cache:
      dir: ${user.home}/.multiroom/tts-cache     # cross-platform, java.nio.file.Path
      max-size-mb: 500
    providers:
      - name: openai
        type: openai
        api-key: ${OPENAI_API_KEY}
        voice: alloy
        language: en
        engine: tts-1
        timeout-seconds: 10   # default; SC-003 allows 10 s for the whole failure path
      - name: piper-local
        type: piper
        model-path: /opt/piper/models/en_US-ryan-medium.onnx
        python-executable: python3   # piper1-gpl: `pip install piper-tts`, no standalone binary
        timeout-seconds: 10
    # default-provider is optional; first entry is used if absent
    default-provider: openai
```

---

## 6. REST Trigger Endpoint

**Status**: Rewritten 2026-09-12. The constraint this section previously documented no longer exists.

### What this section used to say, and why it is gone

The original finding was accurate for the tree as it stood: `multiroom-core` ran Spring WebFlux with
no servlet container, `RestExtension` started a **fully isolated standalone Tomcat** via
`SpringApplicationBuilder` with no parent context, and extensions loaded behind isolated classloaders
— so TTS classes were invisible to REST's Spring context and there was no shared web server a new
extension could register a `@RestController` into. Spring's own event system was evaluated and
rejected for bridging, because events propagate child → parent and REST's context had no parent at
all; `CoreEventBridge` in `multiroom-mqtt` existed precisely as the manual workaround.

From that the plan derived a required infrastructure phase: an `ExtensionEventBus` in `multiroom-api`,
protocol-level `HttpRouteContribution` / `HttpExchange` / `HttpResponse` /
`MqttSubscriptionContribution` records, a `contribute()` lifecycle phase on `Extension`, manifest
dependency declarations and a topological load order in `ExtensionLoader`. Three design options were
weighed (typed hub interfaces, OSGi-style package export, event bus with protocol records) and the
third chosen.

**`019-extension-shared-classloader` removes the premise underneath all of it.** Extensions are now
discovered *before* the application starts and contribute into the **single** core Spring context by
ordinary auto-configuration; core runs `spring-boot-starter-web` with one `DispatcherServlet` on one
port; there are no isolated classloaders and no second Tomcat. The right answer to "how does a new
extension contribute an HTTP route" is now: it declares a `@RestController`.

The whole option analysis is therefore moot — every option solved a problem that no longer exists.
It is preserved in git history rather than here; nothing downstream should reason from it.

### Decision

| Decision | Rationale | Alternatives considered |
|----------|-----------|------------------------|
| **Plain `@RestController` classes in `multiroom.tts.rest`** | Under one Spring context, core's `DispatcherServlet` maps them like any other handler. No bus, no contribution records, no lifecycle phase, no new `multiroom-api` type | The withdrawn event-bus design — now unnecessary, not merely heavier |
| **Path namespace `/api/tts/**`** | Sits alongside the existing unversioned `/api/devices`, `/api/routes` set and is owned entirely by this module | `/api/v2/**` rejected: that is `multiroom-rest`'s published contract with two independently released consumers outside this repository (`sonora-cli`, `sonora-mcp`); a second module contributing paths there would let one module's release break another's contract. A separate port is rejected by 019 FR-026 |
| **`POST /api/tts/speak`, `DELETE /api/tts/cache`, `GET /api/tts/cache/stats`** | Three ordinary handler methods across two controllers | Unchanged in substance from the original design; only the transport mechanism and prefix changed |
| **springdoc picks them up with no change to `multiroom-rest`** | 019 widened `springdoc.packages-to-scan` from `multiroom.rest.controllers.v2` to `multiroom` precisely so other modules' endpoints reach `/api-docs` | Nothing to consider — the alternative was the endpoints serving correctly but being invisible in the contract |
| **MQTT triggering deferred; the mechanism left open** | Spec FR-014 requires *at least one* external trigger and HTTP satisfies it. TTS→MQTT is extension→extension, which 019 does not address: it solves extension→core (injection) and core→extension (`@EventListener`) | Three options are set out in [contracts/tts-mqtt-topics.md](contracts/tts-mqtt-topics.md) and **none is chosen**: a contribution record in `multiroom-api`; an optional `provided` dependency on `multiroom-mqtt` behind `@ConditionalOnClass`, which needs no new type and is what 019 FR-007 specced for this, but which constitution VIII reads against; or not building it. The choice turns on a documented conflict between FR-007 and constitution VIII that is the maintainer's to settle |

### Lifecycle for TTS

There is no extension lifecycle to describe any more. The module's beans are created during the one
context refresh, in the order Spring already resolves, and destroyed with it.

```
Application start-up
  pre-Spring : ExtensionScanner finds multiroom-tts.jar, reads its manifest, adds it
               to the shared classloader (019)
  refresh    : TtsAutoConfiguration contributes TtsProperties, providers, ProviderRegistry,
               AudioConverter, TtsInputResolver (@Bean), AudioCache, TtsService,
               PlaybackCompletionListener;
               @ComponentScan picks up TtsController and TtsCacheController.
               Core's DefaultInputResolutionService receives TtsInputResolver through its
               existing List<InputEndpointResolver> constructor parameter — no registration.
               NOTHING contacts a provider here (019 FR-019).
  ready      : DispatcherServlet maps /api/tts/**; springdoc documents it.
  shutdown   : AnnouncementQueueManager's SmartLifecycle stops accepting, lets the current
               announcement finish, discards the rest.
```

### Consequence for fail-fast

019 aborts start-up when an extension fails to initialise (its FR-018), and explicitly does **not**
when an external resource is merely unreachable (its FR-019). That line runs straight through this
module:

- **Aborts**: a provider with no API key, a Piper `model-path` that does not exist (or whose
  `.onnx.json` config sidecar is missing), an unparseable `multiroom.tts` block. Operator-fixable;
  failing at start-up is better than failing the first announcement at 3 a.m. `python-executable`
  itself is *not* checked — it is normally a bare `PATH`-resolved command (`python3`), not a
  literal path, and validation must not spawn a process to test it either way.
- **Must not abort**: OpenAI unreachable, the local HTTP TTS service stopped, DNS down. These are
  runtime states; the announcement fails with a clear per-request error and the appliance still boots.

Validation therefore reads configuration and touches the filesystem, and never opens a socket or
spawns a process.


## 7. Cross-Platform Notes

- **Piper model path**: configured by user in YAML; extension validates the `.onnx` and its
  `.onnx.json` sidecar exist on startup. `python-executable` is not path-validated (see above)
- **ProcessBuilder invocation**: always array form `new ProcessBuilder(pythonExecutable, "-m", "piper", "--model", modelPath, "--config", configPath)` — never shell-string form
- **File paths**: all using `java.nio.file.Path`; `Paths.get(cacheDir)` — no hardcoded `/` or `\`
- **Raspberry Pi**: `pip install piper-tts` pulls a prebuilt `onnxruntime` wheel for aarch64; no
  JVM-level ARM issues either way
