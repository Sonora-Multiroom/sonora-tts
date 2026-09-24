# Implementation Plan: TTS Extension

**Branch**: `001-tts-extension` (this repository; `021-tts-extension` upstream before the move) | **Date**: 2026-05-30, revised 2026-09-20 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-tts-extension/spec.md`

**Hard prerequisite**: a host built from `multiroom-api` **0.1.18** — the release that carries both
`019-extension-shared-classloader` and the promoted `FormatConverter` — installed in the local `~/.m2`.
This plan is written against the shared-runtime extension model 019 delivers.

**Where this lives**: this feature was specified in the `multiroom-ai` monorepo as `021-tts-extension`
and moved to its own repository (`d:/projects-sonora/sonora-tts`) on 2026-09-20, before any code was
written. The module is no longer part of that reactor; it builds against the installed API and parent
POM. See `multiroom-ai/docs/extensions/extracting-an-extension-to-its-own-repo.md` for why, and
`AGENTS.md` here for the rules across the boundary.

## Summary

`multiroom-tts` is a new extension module that accepts a text announcement, synthesizes speech via a
configured TTS provider (cloud or local), caches the resulting audio to disk as a WAV file, and plays
it on a target output or output group by registering an ephemeral input pointing at that file and
routing it, using the existing dynamic input registration API.

It is **one module**, and — since 2026-09-20 — one repository. The module ships an `@AutoConfiguration` class, plain
`@RestController`s, and a `@Bean InputEndpointResolver`; it obtains core services by constructor
injection. It changes nothing outside itself: moving `FormatConverter` and `FormatConversionException` into
`multiroom-api` — see "Audio conversion belongs to the public API" below — was completed upstream and
released as 0.1.18, leaving this repository with a pure consumer relationship to the host.

### What changed on 2026-09-12, and why this plan got much smaller

The original plan delivered in two phases, because extensions could not contribute an HTTP route into
the running application: each ran a private Spring context behind its own classloader, so a TTS
`@RestController` was invisible to REST's Tomcat. Phase 1 built the machinery to work around that —
an `ExtensionEventBus`, `HttpRouteContribution` / `HttpExchange` / `HttpResponse` /
`MqttSubscriptionContribution` records in `multiroom-api`, a `contribute()` lifecycle phase on
`Extension`, manifest-declared dependencies and a topological load order in `ExtensionLoader` —
roughly 16 tasks across four existing modules, before a line of TTS was written.

`019-extension-shared-classloader` removes the constraint rather than the symptom: extensions are
discovered *before* the application starts and contribute into the single Spring context by ordinary
auto-configuration. **Phase 1 is therefore deleted, not simplified.** Every type it introduced is
unnecessary:

| Phase 1 artifact | Replaced by |
|---|---|
| `ExtensionEventBus` + `ExtensionEventBusImpl` | Spring's own context — extensions share one |
| `HttpRouteContribution`, `HttpExchange`, `HttpResponse` | `@RestController` with Spring MVC types |
| `MqttSubscriptionContribution` | Deferred — see "MQTT triggers" below |
| `contribute()` lifecycle phase on `Extension` | `@AutoConfiguration`; `Extension` itself is deleted by 019 |
| Manifest `Extension-Requires` / `Extension-Optional` + topo sort | `@AutoConfiguration(after = …)`, `@ConditionalOnBean`, `@ConditionalOnClass` |
| `RestExtension.contribute()` route wiring | Nothing — core's `DispatcherServlet` maps the controller |

What remains is Phase 2: the TTS module itself, essentially unchanged in substance.

## Technical Context

**Language/Version**: Java 17 LTS

**Framework**: Spring Boot 3.5.15 (the version 019 records; `multiroom-tts` inherits it and bundles
none of it)

**Build Tool**: Maven (`mvn`; single module, no `-pl`)

**Primary Dependencies**:

- `multiroom-api` (`provided`) — `RouteService`, `DeviceRegistryService`, `DeviceQueryService`,
  `InputEndpointResolver`, `TargetType`, `RouteDestroyedEvent`, and — **promoted by this feature** —
  `FormatConverter` + `FormatConversionException`. (`DeviceService` no longer exists:
  `015-deviceservice-isp-refactor` split it into `DeviceQueryService` / `DeviceRegistryService` /
  `DeviceVolumeService` / `PlaybackControlService`. TTS needs the first two only.)
- **The JDK for WAV parsing** — `javax.sound.sampled` (`java.desktop`), already used in production by
  `FlacStreamDecoder`. **No `multiroom-decoder` dependency**, and therefore no second module of this
  repository on the extension's compile path — see "Parsing the provider's WAV" below
- `spring-boot-starter-web`, `spring-boot-starter-validation`, Jackson (all `provided`) — supplied by
  core at runtime under the shared classloader
- Java 17 `java.net.http.HttpClient` — cloud provider calls, no external SDK
- SLF4J + Logback — structured logging

Everything except the module's own code is `provided`. Nothing framework-shaped is bundled: that is
what keeps the distributable small (019 SC-001) and what keeps two independently built extensions from
colliding on a class name. 019's build-time duplicate-class gate (its FR-025) was **withdrawn on
2026-09-13**, so nothing verifies this automatically; the `bannedDependencies` enforcer rule (019
FR-023) is still live.

**Storage**: File-based audio cache at a configurable directory (`java.nio.file.Path`); `index.json`
+ `<sha256>.wav` files. The cached file **is** the playback artifact — see "How synthesized audio
reaches an output" below — so it carries a RIFF header rather than being raw PCM.

**Testing**: JUnit 5 + Mockito + Spring Boot Test; `@WebMvcTest` for the controllers (a real Spring
MVC slice now, not a hand-rolled `HttpExchange` stub); WireMock for cloud provider HTTP

**Target Platform**: Windows 10+, Linux (Ubuntu 20.04+), Raspberry Pi OS ARM64

**Project Type**: Maven extension module, same shape as the four modules 019 migrates

**Performance Goals**:

- Cache miss (synthesis): < 5 s from request to playback start (SC-001, **measured by T028a**, against a
  provider held at the 3 s the criterion assumes)
- Cache hit: < 1 s from request to playback start (SC-007, **measured by T041a**)
- Multi-room sync: ≤ 100 ms (SC-002, inherited from the routing layer because a group target is one
  route fanning one pipeline to every output — **verified structurally and on the Pi by T032a**, rather
  than assumed)
- Prior state restored: < 1 s after the completion event (SC-006, timed in T058)
- **Start-up contribution: negligible.** 019's SC-007 budget is 7 s for the whole application with
  every extension loaded; a fifth extension must not eat it. Nothing in this module may do network or
  subprocess work during bean construction (see Constraints), which is also what keeps it cheap.

**Constraints** — the first four are inherited from 019 and are not negotiable:

- **No network or subprocess work during bean construction** (019 FR-018/FR-019). A provider is
  constructed, not contacted; Piper is located, not executed. An unreachable OpenAI endpoint or a
  stopped local TTS service at boot is a runtime state, not an initialisation failure, and must not
  abort application start-up. This is the same rule 019 applies to MQTT's broker connect and DLNA's
  jupnp start-up, and it is the single easiest thing to get wrong here.
- **Configuration faults *do* abort start-up** (019 FR-018). A provider entry with no API key, a
  Piper `model-path` (or its `.onnx.json` sidecar) that does not exist, an unparseable
  `multiroom.tts` block: these are operator-fixable, so fail fast at start-up with a message naming
  the extension, rather than failing the first announcement hours later. Validate paths and
  required fields in `@PostConstruct` or a `@ConfigurationProperties` validator — never by calling
  the provider. `python-executable` (the interpreter Piper runs under — see research.md §1.2) is
  the one Piper field left unchecked: it is normally a bare `PATH`-resolved command, not a literal
  path.
- **No configuration file that applies globally** (019 FR-024). `multiroom-tts.jar` MUST NOT contain
  a root `application.yml` or `application.properties` — on the shared classpath it would shadow
  core's. All defaults live in `@ConfigurationProperties` field initialisers. 019's build gate (its
  T009) fails the build otherwise.
- **No dependency on `multiroom-core`** (019 FR-023, enforced by `maven-enforcer-plugin` inherited
  from `multiroom-extension-starter`). `multiroom-api` only — no other module of this repository.
- All file paths via `java.nio.file.Path` — no hardcoded separators
- `ProcessBuilder` for Piper: always array form, never shell-string; targets piper1-gpl
  (`python3 -m piper`, per research.md §1.2), not the archived `rhasspy/piper` binary
- Cloud calls: configurable timeout (**default 10 s**, per spec FR-022 — a 30 s default would blow SC-003's 10 s error budget), no retry on error
- In-memory announcement queue: dropped on shutdown, bounded per target
- No additional auth layer — delegated to the transport (019 leaves this unchanged)
- All providers configured to return WAV/PCM — the JDK's `AudioSystem` parses the container, and the
  shared `FormatConverter` (injected, never re-implemented) handles sample rate, channel count and bit
  depth

**Scale/Scope**: Single-host home deployment; expected < 100 TTS requests/hour

## Architecture decisions this revision settles

### REST path namespace: `/api/tts/**`

TTS serves `POST /api/tts/speak`, `DELETE /api/tts/cache`, `GET /api/tts/cache/stats` on the single
unified port 8080, mapped by core's one `DispatcherServlet`.

`/api/v2/**` is deliberately **not** used. That namespace is `multiroom-rest`'s published contract
with two independently released consumers outside this repository (`sonora-cli`, `sonora-mcp`);
another module contributing paths into it would make one module's release able to break another's
contract. `/api/tts/**` sits alongside the existing unversioned `/api/devices`, `/api/routes` set and
is owned entirely by this module.

This is compatible with 019's FR-032, which prohibits **core** from owning an API path — it does not
reserve `/api/**` to `multiroom-rest`. 019's T082 asserts the prohibition on core, so a TTS-declared
path passes it.

### The endpoints document themselves

springdoc scans `multiroom` (widened by 019 from `multiroom.rest.controllers.v2`), so TTS controllers
appear in `/api-docs` with no change to `multiroom-rest`. Annotate them properly — this module is the
first real instance of 019's SC-005/SC-008 ("an endpoint contributed by another module appears in the
contract with zero changes to the control API extension"), which 019 itself proves only with a
throwaway canary.

### How synthesized audio reaches an output: `tts://` resolves to `file://`

Core's `DefaultInputResolutionService` takes `List<InputEndpointResolver>` by constructor injection
([DefaultInputResolutionService.java:34](../../multiroom-core/src/main/java/multiroom/core/io/inputs/resolve/DefaultInputResolutionService.java#L34)).
Under one Spring context a `@Bean InputEndpointResolver` declared by this module is collected
automatically — no `ExtensionContext`, no SPI file, no registration call. This is the cleanest single
illustration of what 019 buys.

**But a resolver only rewrites a URI; it does not supply audio.** Whatever URI it returns, core then
looks the *scheme* up in
[InputHandlerRegistry](../../multiroom-core/src/main/java/multiroom/core/io/inputs/InputHandlerRegistry.java#L101)
and throws `UnsupportedSchemeException` if no `InputHandler` claims it. The handlers that exist cover
`file`, `http`/`https`, the platform hardware schemes and `signal`, and `InputHandler` itself lives in
`multiroom.core.io` — a package this module is forbidden to depend on by the enforcer rule inherited
from `multiroom-extension-starter`. **A `tts://` handler therefore cannot exist in this module**, and a
resolver holding PCM bytes in memory would have nothing to hand them to. This is the same shape as
`multiroom-resolvers`' `SoundCloudInputResolver`, which resolves its custom form to `https://`.

So `TtsInputResolver` terminates on a scheme core already handles:

```
tts://<announcement-uuid>   ──resolve──►   file:///<cache-dir>/<sha256>.wav   ──►  WavFileInputHandler
```

The synthesized audio is converted to the system's native format — by the injected `FormatConverter`, see
the next section — and written as a WAV file, which is
the cache entry itself, so the common path costs no extra I/O. On a cache-write failure (spec FR-025)
the audio is written to a temporary file outside the cache directory instead, and deleted after
playback.

The `tts://` indirection is kept rather than registering the `file://` URI directly, because it gives
one place to hold the announcement's claim on its file: the resolver pins the entry against LRU
eviction between enqueue and playback, and re-materializes it if it was evicted anyway. Core resolves
lazily at route-creation time, which is exactly when that check needs to happen.

### Audio conversion belongs to the public API: `FormatConverter` moves to `multiroom-api`

A provider returns WAV at whatever rate, depth and channel count it likes — OpenAI 24 kHz mono, Piper
22.05 kHz mono, Google LINEAR16. Something has to reconcile that with what the outputs want.

The system already has exactly that component:
[FormatConverter](../../multiroom-core/src/main/java/multiroom/core/conversion/FormatConverter.java) with
`FormatConverterImpl`, a thread-safe `@Component` handling 8–192 kHz resampling, mono↔stereo and
16↔24-bit. **It is in `multiroom.core.conversion`, a package this module is forbidden to depend on**, and
parsing a WAV container yields PCM in whatever format the provider chose — nothing in the JDK resamples
it. Writing a second resampler inside
`multiroom-tts` would duplicate ~300 lines of audio maths that the pipeline already trusts, in a module
that cannot share its tests — a straight constitution I (DRY) violation, and the kind of duplicate that
drifts silently once one side is tuned.

**Decision: promote the contract, not the implementation.**

| Moves to `multiroom-api` | Stays in `multiroom-core` |
|---|---|
| `FormatConverter` → `multiroom.api.conversion.FormatConverter` | `FormatConverterImpl` (the `@Component`) |
| `FormatConversionException` → `multiroom.api.exceptions.FormatConversionException` | everything else |

`FormatConverter` already depends on nothing but `multiroom.api.model.SampleFormat` and that exception,
so the interface moves cleanly. The exception's new home is where its own Javadoc already put it:
`multiroom.api.exceptions.AudioException` has listed `FormatConversionException` as part of the published
hierarchy since 0.1.0, while the class itself sat in core extending core's parallel `AudioException`. The
move corrects that split, and nothing in the tree catches either type — verified — so no call site changes
behaviour.

`multiroom-tts` then does what every other consumer does: takes `FormatConverter` by constructor
injection. `AudioConverter` in this module is a thin adapter — read the provider's WAV with the JDK's
`AudioSystem`, hand the PCM to the injected converter, write the result with `WavFileWriter` — and
owns no conversion maths. FR-023's "cannot be converted" is then a real, testable condition:
`canConvert(source, target)` returning false, or `FormatConversionException` from `convert`.

**Consequences to respect:**

- `multiroom-api` gains two public types, so `mvn -pl multiroom-api clean install` must run before
  anything else builds (the standing gotcha), and a full-reactor `mvn verify` is the merge gate.
- This is a `multiroom-api` change, so constitution VIII's "versioned, backward compatible" clause
  applies. It is source-compatible for extensions (none referenced either type — they could not) and a
  package rename for core's own callers, which is why the promotion is behaviour-preserving by
  construction: `git mv`, fix imports, no logic touched.
- The alternative — having the module resample itself — was rejected above. A third option, leaving
  conversion entirely to the pipeline (which converts source→target per route anyway, and since
  `abaa11e` accepts 8–192 kHz sources), would work for playback but leaves FR-023 undetectable until
  audio is already routed and gives the cache entries in inconsistent formats. The extension converts
  once, before the cache write; the pipeline's own conversion then becomes a no-op.

### Parsing the provider's WAV: the JDK, not `multiroom-decoder`

Conversion needs a source `SampleFormat` and raw PCM, so something has to read the provider's RIFF
container first. The 2026-05-30 research picked `WavStreamDecoder` from `multiroom-decoder` and
rejected `javax.sound.sampled` because `AudioSystem` had "classloader issues under isolated extension
classloaders". **019 deleted the isolated classloaders**, and that was the whole of the objection:
extensions now load beside core in one classloader, and `FlacStreamDecoder` already calls
`AudioSystem.getAudioInputStream` in production on every target platform, the Pi included. There is no
jlink image and no `module-info.java` in this tree, so `java.desktop` is present wherever the
application runs.

**Decision: parse with the JDK.** `AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))`
yields an `AudioFormat` and a PCM stream; mapping that `AudioFormat` onto
`multiroom.api.model.SampleFormat` is about ten lines, and this module's own `WavFileWriter` already
owns the symmetric write side.

What that buys, beyond ten lines of mapping:

- **No `multiroom-decoder` dependency.** That module exists to decode MP3, AAC, FLAC, Vorbis, Opus and
  WebM — it carries JLayer, jaad, javasound-flac, vorbis-java, opus-java, JNA and micrometer behind it
  — and TTS wanted exactly one thing from it: a 44-byte RIFF header.
- **Nothing to maintain in `multiroom-extension-starter`.** With the dependency gone there is no
  `provided` → `compile` slip to guard against, so the starter's shade `artifactSet/excludes` is not
  touched and no second copy of the decoder can reach the shared classloader. 019's duplicate-class
  gate was withdrawn on 2026-09-13, so such a guard would have had nothing enforcing it.
- **Constitution VIII reads literally.** The principle says extension modules depend on *the public API
  module*. `multiroom-decoder` is not that — its `PCMData` and `DecoderException` are not published
  API — and it is not banned either, so the dependency sat in an unexamined grey area. `multiroom-api`
  alone removes the question.
- **A seam that fits the job.** `WavStreamDecoder` is built for the pipeline: `open(InputStream)`, then
  `decodeFrame()` in 2048-sample frames, with `InputId` and a `MeterRegistry` in one constructor. TTS
  holds the whole response in memory and wants it in one piece.

Providers are configured to return PCM WAV (`wFormatTag` 1), which every JDK `WaveFileReader` handles.
If the SPI lookup ever does prove fragile in this runtime, the backstop is a ~40-line RIFF reader in
`multiroom.tts.audio` beside `WavFileWriter` — not a module dependency.

### Detecting that an announcement has finished

FR-008 restores the target's prior state "after the announcement finishes", and SC-006 gives that 1
second. **Nothing in the module can observe that directly**: `AudioInput.onComplete` is
`multiroom.core.io`, out of reach, and guessing from the WAV's duration races the pipeline's drain.

The observable signal is the route lifecycle. When a file input hits EOF, `AudioPipeline` enters drain
mode, waits for the outputs to empty plus a driver-buffer settle, and stops; core's
`RouteManagerImpl.handlePipelineStop` then deregisters the route and publishes
[RouteDestroyedEvent](../../multiroom-api/src/main/java/multiroom/api/events/RouteDestroyedEvent.java) —
a public API event any bean can receive with `@EventListener`.

So `PlaybackCompletionListener` matches `event.route().getInputId()` against the announcement's
ephemeral input id, and on a match unpins the cache entry, deletes any temp file, restores the
snapshotted routes, and releases the queue slot. Two details this pins down:

- **`autoRemove = true` already unregisters the input.** `AutoRemoveInputListener` handles the same
  event and calls `unregisterInput` itself; a second explicit call from this module gets
  `IllegalArgumentException("Input '…' is not registered")`. The module therefore does **not**
  unregister — it lets `autoRemove` do it and cleans up only what is its own.
- **SC-006's 1 s starts when the event arrives**, not when the audio's last sample plays: the drain
  path already waits `max(500 ms, 50 × buffer)` before stopping. The criterion now says so itself, so
  this is the spec's clock rather than a reinterpretation of it. Restoration is a `createRoute` call,
  so the budget is comfortable — but measure it rather than assume it, and record the drain figure
  alongside, since the user-visible gap is drain + restore.

This listener is **MVP scope (US1)**, not part of the queue story — US1 cannot satisfy FR-008 without
it. `AnnouncementQueueManager` (US4) later subscribes to the same completion signal to release the
next announcement for that target.

### What the queue serializes: the output, not the provider

FR-009 queues announcements per target, and the natural reading of "queue the request" is to accept it,
answer `202` and do the work later. That reading breaks the endpoint's own contract: `PROVIDER_TIMEOUT`,
`PROVIDER_RATE_LIMITED`, `PROVIDER_ERROR` and `FORMAT_NORMALIZATION_FAILED` can then only happen after the
caller has been told the request was accepted, SC-003's "error within 10 seconds" becomes unobservable
over HTTP, and `SpeakAcceptedResponse.cacheHit` has no value to report.

**Only the routing step goes behind the queue.** Validation, cache lookup, synthesis, conversion and the
WAV write all happen in the request thread; what is enqueued is an announcement whose file already exists,
with its cache entry pinned and its route snapshot taken. The worker then does `registerInput` +
`createRoute` when the output frees up.

This is not a restriction, it is what the contended resource actually is. Two announcements for one room
compete for **the output** — one `AudioPipeline` at a time — not for the provider, which is a per-request
HTTP call with its own timeout. Serializing synthesis would add latency that buys nothing.

Three places in this feature already assume this ordering, which is why the alternative reading is a
defect rather than a design choice: the resolver pins the cache entry "between enqueue and playback"
(above), which presumes an entry exists at enqueue time; `data-model.md`'s state machine enqueues from
`AUDIO_READY`; and the published contract says a `202` reports `cacheHit`. The cost is that the HTTP
request blocks for up to the provider timeout (10 s, FR-022) on a cache miss — which is exactly the
latency SC-001 and SC-003 already budget for.

### The error contract: one mapping, and it is not RFC 7807

`contracts/tts-rest-api.yaml` publishes a plain `{error, message}` body with a closed enum of seven
codes, and two statuses: 400 for what the caller can fix, 503 for what the provider did.

Deliberately **not** RFC 7807 Problem Details, although `/api/v2/**` uses it. Problem Details belongs to
`multiroom-rest`'s published contract with its out-of-repo consumers; `/api/tts/**` is this module's own
namespace, and the YAML here is what an integrator reads. Matching `/api/v2`'s shape without owning it
would mean tracking someone else's error format across releases, and switching later is a visible
break either way — so the contract as published wins.

Two rules keep it from drifting:

- **Services throw `TtsException(TtsErrorCode, message)`; nothing below `rest` knows an HTTP status.**
  The `@RestControllerAdvice` holds the only code→status mapping, so a new failure mode is one enum
  constant and one table row, not a status code guessed at a fifth call site.
- **The advice is scoped** with `assignableTypes = {TtsController.class, TtsCacheController.class}`.
  Under 019 every module's controllers share one `DispatcherServlet`, so an unscoped
  `@RestControllerAdvice` here would happily convert `multiroom-rest`'s exceptions into this module's
  error shape. That is a new failure mode the shared runtime introduces and the pre-019 design could
  not have had.

`FORMAT_NORMALIZATION_FAILED` keeps its published name even though the spec now says "convert" rather
than "normalize" — the string is the contract.

### "Disabled" is a start-up state, not a runtime one

The spec previously carried an edge case for *extension disabled mid-playback*: finish the current
announcement, discard the queue, accept nothing new. Under 019 that state cannot be reached.
`multiroom.tts.enabled` is read by `@ConditionalOnProperty` when the context refreshes, once. Either
the auto-configuration contributes its beans for the whole run, or it contributes none and no
`/api/tts/**` path is ever mapped. There is no in-between and no transition to handle, so FR-024 now
governs shutdown alone, and T057(c) checks the disabled case where it actually exists — at start-up.

Writing "disable mid-playback" handling anyway would mean shipping a code path no test can enter.

### MQTT triggers: deferred, with the mechanism recorded

Spec FR-014 requires **at least one** external trigger; HTTP satisfies it, so MQTT is not on the
critical path and no task depends on it.

019 solves extension→core (injection) and core→extension (`@EventListener`). TTS→MQTT is
extension→extension, which 019 does not address: its banned-dependency rule blocks only
`multiroom-core`, so a `multiroom-tts` → `multiroom-mqtt` Maven dependency would build while
contradicting constitution VIII ("extension modules depend on the public API module only").

**How it would be done is an open decision, not a settled one.**
[contracts/tts-mqtt-topics.md](contracts/tts-mqtt-topics.md) sets out three options and picks none:
a contribution record in `multiroom-api` (clean module boundaries, but `multiroom-api` grows once per
protocol — the objection research §6 originally raised against hub interfaces); an optional
`provided` dependency on `multiroom-mqtt` guarded by `@ConditionalOnClass` (**no new type anywhere** —
`MqttClient` and `MqttConnectedEvent` already exist — and it is the mechanism 019's FR-007 specced for
optional sibling dependencies, but constitution VIII's "depend on the public API module **only**"
reads against it); or not building it at all.

That constitution VIII vs. FR-007 tension is a maintainer call, and the contract document proposes the
narrow amendment that would settle it — permitting a sibling dependency only when it is
`optional`/`provided`, guarded so the module still works without it, one-way and acyclic. That is a
constitution change to make deliberately, not a per-feature waiver, because every future extension
wanting to be pluggable over another's transport meets the same wall.

The recommendation is **don't build it** — Home Assistant calls REST natively and FR-014 is satisfied
— and if it is ever wanted, the `@ConditionalOnClass` route together with that amendment.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after design.*

| # | Principle | Status | Evidence |
|---|-----------|--------|----------|
| I | Clean Code | ✅ PASS | SRP classes; `TtsProvider` SPI has one method; `TtsService` orchestrates only. The revision **removes** ~16 tasks of platform plumbing rather than adding any. DRY is what drives the `FormatConverter` promotion: the module injects the one converter instead of growing a second |
| II | Java 17 + Spring Boot 3.x | ✅ PASS | Spring Boot 3.5.15 used as intended — auto-configuration, `@ConfigurationProperties`, records, `java.net.http.HttpClient`. No framework worked around |
| III | TDD | ✅ PASS | Every production class has a test counterpart — **both** controllers, the completion listener and the conversion adapter included; controllers testable with `@WebMvcTest` instead of a hand-rolled handler stub. **Note the pre-existing measurement gap**: no coverage tooling is configured in the build, so the 80 % floor is unmeasured — disclosed in 019's Constitution Check note 4 and unchanged by this feature |
| IV | SOLID | ✅ PASS | `AudioCache` swappable behind an interface; adding a provider needs zero changes to `TtsService`; DIP improves further — providers and core services arrive by injection, nothing is pulled from a context object |
| V | Code Quality | ✅ PASS for changes in scope | Structured logging only; Javadoc on public types. **The merge gate is this repository's two-part one** — `mvn verify` here plus a deploy-and-inspect run against a live core — since the upstream full-reactor gate no longer covers this module. **Pre-existing gap**: Checkstyle, PMD, SpotBugs and SonarQube are absent from the build (019 note 4); not caused or worsened here |
| VI | Git Workflow | ✅ PASS | Branch `001-tts-extension`, conventional commits, PR required |
| VII | Cross-Platform | ✅ PASS | `java.nio.file.Path` throughout; `ProcessBuilder` array form; `pip install piper-tts` resolves a prebuilt `onnxruntime` wheel on Pi ARM64. Start-up cost kept off the ARM64 budget by the no-work-in-construction rule |
| VIII | Modular Architecture | ✅ PASS | `multiroom-tts` depends on `multiroom-api` **alone** — the principle's "extension modules depend on the public API module only", taken literally (see "Parsing the provider's WAV"); core never depends on it; **now enforced at build time** by the enforcer rules 019 adds, not merely by convention. The **two promoted types** this feature needed (`FormatConverter`, `FormatConversionException`) were a move, not new surface, and shipped upstream in 0.1.18 — precisely the "expose core functionality through interfaces for extensions to consume" the principle calls for. The withdrawn Phase 1 would have added seven genuinely new ones |

**Gate result: ALL PASS.** Two pre-existing tree-wide gaps (III measurement, V tooling) are disclosed,
not waived; both are 019's note 4 items and are the maintainer's to schedule.

## Project Structure

### Documentation (this feature)

```text
specs/001-tts-extension/
├── plan.md                      # This file
├── research.md                  # Phase 0 decisions
├── data-model.md                # Entities, state transitions, relationships
├── quickstart.md                # Configuration guide and examples
├── contracts/
│   ├── tts-rest-api.yaml        # OpenAPI 3.0 REST contract (/api/tts/**)
│   └── tts-mqtt-topics.md       # Topic reference — DEFERRED, see above
└── tasks.md                     # Task list
```

### Source Code (repository root)

**One module. The two promoted API types it uses — `multiroom.api.conversion.FormatConverter` and
`multiroom.api.exceptions.FormatConversionException` — ship in `multiroom-api` 0.1.18 and are not
built here.**

```text
pom.xml                                           # inherits multiroom-extension-starter 0.1.18
                                                  #   with an EMPTY <relativePath/>: manifest
                                                  #   attributes, the shade AppendingTransformer
                                                  #   for …AutoConfiguration.imports, and the
                                                  #   ban on depending on multiroom-core
src/
    ├── main/
    │   ├── java/multiroom/tts/
    │   │   ├── TtsAutoConfiguration.java            # @AutoConfiguration; the entry point.
    │   │   │                                        #   @ConditionalOnProperty(
    │   │   │                                        #     "multiroom.tts.enabled",
    │   │   │                                        #     matchIfMissing = true)  [019 FR-008]
    │   │   ├── config/
    │   │   │   └── TtsProperties.java               # @ConfigurationProperties("multiroom.tts")
    │   │   │                                        #   ALL defaults live here — the module
    │   │   │                                        #   ships no application.yml [019 FR-024]
    │   │   ├── provider/
    │   │   │   ├── TtsProvider.java                 # SPI: synthesize(SynthesisRequest)
    │   │   │   ├── ProviderRegistry.java            # Named map; first = implicit default
    │   │   │   ├── SynthesisRequest.java            # record
    │   │   │   ├── SynthesisResult.java             # record
    │   │   │   ├── cloud/
    │   │   │   │   ├── OpenAiTtsProvider.java       # constructed, never contacted, at boot
    │   │   │   │   └── GoogleCloudTtsProvider.java
    │   │   │   └── local/
    │   │   │       ├── PiperTtsProvider.java        # python3 -m piper, run per request only
    │   │   │       └── LocalHttpTtsProvider.java
    │   │   ├── audio/
    │   │   │   ├── AudioConverter.java              # adapter: the JDK's AudioSystem reads
    │   │   │   │                                    #   the provider's WAV, the INJECTED
    │   │   │   │                                    #   FormatConverter converts it. No
    │   │   │   │                                    #   conversion maths lives here
    │   │   │   ├── WavFileWriter.java               # PCM byte[] + SampleFormat → RIFF/WAV file
    │   │   │   └── TtsInputResolver.java            # @Bean InputEndpointResolver: rewrites
    │   │   │                                        #   tts://<uuid> to the file:// URI of the
    │   │   │                                        #   WAV on disk, and pins it against
    │   │   │                                        #   eviction. Core collects it by List<>
    │   │   │                                        #   injection, no registration step
    │   │   ├── cache/
    │   │   │   ├── AudioCache.java                  # get/put/invalidate/clear/stats/pin;
    │   │   │   │                                    #   get/put deal in Path, not byte[]
    │   │   │   └── FilesystemAudioCache.java        # index.json + <sha256>.wav; plain JDK
    │   │   │                                        #   LRU index, no new third-party lib
    │   │   ├── queue/
    │   │   │   └── AnnouncementQueueManager.java    # per-target queue + daemon worker
    │   │   ├── service/
    │   │   │   ├── TtsService.java                  # validate → cache? → synthesize →
    │   │   │   │                                    #   convert → enqueue
    │   │   │   └── PlaybackCompletionListener.java  # @EventListener(RouteDestroyedEvent):
    │   │   │                                        #   restore snapshotted routes, unpin,
    │   │   │                                        #   drop temp file. MVP, not US4
    │   │   └── rest/
    │   │       ├── TtsController.java               # @RestController /api/tts/speak
    │   │       ├── TtsCacheController.java          # @RestController /api/tts/cache[/stats]
    │   │       ├── SpeakRequest.java                # record: text, targetName, targetType,
    │   │       │                                    #   providerName?, voice?, language?
    │   │       ├── ErrorResponse.java               # record (error, message) — the exact
    │   │       │                                    #   shape contracts/tts-rest-api.yaml
    │   │       │                                    #   publishes. NOT RFC 7807: that is
    │   │       │                                    #   /api/v2's contract, not this one
    │   │       └── TtsExceptionHandler.java         # @RestControllerAdvice scoped to this
    │   │                                            #   module's controllers; the single
    │   │                                            #   TtsErrorCode → 400/503 mapping
    │   └── resources/
    │       └── META-INF/spring/
    │           org.springframework.boot.autoconfigure.AutoConfiguration.imports
    │                                                 # names TtsAutoConfiguration — this file
    │                                                 #   IS the extension entry point; there is
    │                                                 #   no Extension interface to implement
    └── test/
        └── java/multiroom/tts/
            ├── TtsAutoConfigurationTest.java         # ApplicationContextRunner: beans present;
            │                                         #   enabled=false contributes nothing
            ├── audio/AudioConverterTest.java
            ├── audio/WavFileWriterTest.java
            ├── audio/TtsInputResolverTest.java
            ├── cache/FilesystemAudioCacheTest.java
            ├── provider/OpenAiTtsProviderTest.java        # WireMock
            ├── provider/GoogleCloudTtsProviderTest.java   # WireMock
            ├── provider/PiperTtsProviderTest.java         # ProcessBuilder stub
            ├── provider/ProviderRegistryTest.java
            ├── queue/AnnouncementQueueManagerTest.java
            ├── rest/TtsControllerTest.java                # @WebMvcTest
            ├── rest/TtsCacheControllerTest.java           # @WebMvcTest
            ├── rest/TtsExceptionHandlerTest.java          # code + status per failure type
            └── service/
                ├── TtsServiceTest.java                    # Mockito
                └── PlaybackCompletionListenerTest.java    # Mockito
```

**Structure Decision**: A single new Maven extension module following the pattern 019 leaves behind — an
`.imports` file naming one `@AutoConfiguration`, ordinary Spring stereotypes inside, every framework
dependency `provided`. There is no embedded web server, no bootstrap class, no service hand-off, and
no new type in `multiroom-api`.

## Implementation Order

| Step | Work | Verify |
|---|---|---|
| **0** | Confirm 019 is merged: `/actuator/extensions` answers, one port, no `Extension` interface in `multiroom-api` | Start the app; `curl :8080/actuator/extensions` |
| **0a** | Promote `FormatConverter` + `FormatConversionException` into `multiroom-api` (`git mv`, fix imports, `FormatConverterImpl` stays in core) | Full-reactor `mvn verify` green; audio still routes unchanged on the Pi |
| **1** | Module skeleton: `pom.xml` (external parent), `.imports`, `TtsAutoConfiguration` — **without** `@EnableConfigurationProperties`, since `TtsProperties` does not exist yet and Phase 1 would not compile (T006) | Build; JAR appears in the inventory, application starts |
| **2** | `TtsProperties` and its validation, both in Phase 2 with the rest of US1 (T017); providers and registry constructed, not contacted | Bad config aborts start-up naming TTS; broker/API down does not |
| **3** | `AudioConverter` (injecting `FormatConverter`), `WavFileWriter`, `TtsInputResolver` as a `@Bean` | Core resolves a `tts://` URI to the WAV's `file://` URI and `WavFileInputHandler` plays it, with no registration code |
| **4** | `TtsService` + `PlaybackCompletionListener` + `TtsController` + error handler — US1 end to end | quickstart §5; the room returns to its prior route; endpoint appears in `/api-docs` with no change to `multiroom-rest` |
| **5** | Group targets (US2) | quickstart §5 group example |
| **6** | Cache (US5) + cache controller | quickstart §6 |
| **7** | Remaining providers (US3) | quickstart §3 variants |
| **8** | Queue (US4) | Two rapid requests to one target |
| **9** | Polish: logging, Javadoc, edge cases, start-up budget re-check | quickstart end to end; 019 SC-007 still met with five extensions |

Step 0a comes before the module exists because it is the only change to an already-shipping module: it
lands, gets verified by a full-reactor run, and is then simply a dependency like any other. Steps 1 and 2
follow because they are where the 019 contract is either honoured or broken: the module must appear in
the inventory, must switch off by property, must not ship a global config file, and must not turn an
unreachable provider into a failed boot.

## Complexity Tracking

No constitution deviations.

The one item worth naming is the `multiroom-api` change this revision adds back: `FormatConverter` and
`FormatConversionException` move there from `multiroom-core`. That is deliberate and small — a move of
two existing types, no new concepts, no interface redesign, `FormatConverterImpl` untouched in core — and
it exists to *avoid* a deviation rather than to take one: without it the module must re-implement
resampling, channel mixing and bit-depth conversion behind a wall it cannot test against, which
constitution I forbids. Contrast the original plan's seven genuinely new `multiroom-api` types plus a
lifecycle change to a published interface, all of which existed only to work around the constraint 019
removes; those stay deleted.
