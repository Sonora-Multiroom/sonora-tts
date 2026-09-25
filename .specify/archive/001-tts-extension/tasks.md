# Tasks: TTS Extension

**Feature**: `001-tts-extension` (this repository; `021-tts-extension` in `multiroom-ai` before the move)

**Date**: 2026-06-01, revised 2026-09-12 for the shared-runtime extension model, revised 2026-09-18
against the merged 019 tree, revised 2026-09-20 from `/speckit-analyze` findings (FormatConverter
promoted to `multiroom-api`, playback-completion listener added to the MVP, missing test tasks added,
Phase 1 compile order fixed, timeout default corrected, voice/language overrides wired), revised again
2026-09-20 to drop the `multiroom-decoder` dependency in favour of the JDK's `javax.sound.sampled` for
WAV parsing (T002, T013, T022 — plan, "Parsing the provider's WAV")

**Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

**Total tasks**: 72 (76 minus T001a–T001c and T003, all resolved by the move to this repository) |
**MVP**: Phase 1 + Phase 2 (T001–T028, including T002a, T013a, T016a, T016b, T022a, T023a, T025a–T025c and T028a)

> Letter-suffixed IDs (T002a, T013a, T016a, T016b, T022a, T023a, T025a–T025c, T028a, T032a, T040a,
> T041a, T044a) are insertions made after the original numbering; they run in the position their letter
> implies.

**Hard prerequisite**: `multiroom-api` **0.1.18** installed in the local `~/.m2`. T001 is the gate.

> **What the 2026-09-20 move changed.** This feature now lives in its own repository
> (`d:/projects-sonora/sonora-tts`), so it is no longer a module of the `multiroom-ai` reactor.
> Three tasks are gone: T001a–T001c (the `FormatConverter` promotion) were completed upstream and
> released as multiroom-api 0.1.18, and T003 (adding a `<module>` entry) has no root POM to add it
> to. T001 and T002 are rewritten against the external parent. Everything else is unchanged in
> substance — the extension contract 019 defines is identical whichever repository builds the JAR.
> The one durable difference is the merge gate: a full-reactor `mvn verify` no longer covers this
> module, so `mvn verify` here plus a deploy-and-inspect run against a live core takes its place
> (constitution V).

> **What the 2026-09-12 revision removed.** The previous list carried a 16-task Phase 2 that built an
> `ExtensionEventBus`, four protocol records in `multiroom-api`, a `contribute()` lifecycle phase and a
> topological loader ordering — infrastructure across four existing modules, needed only because an
> extension could not contribute an HTTP route into the running application. 019 removes that
> constraint, so those tasks are **deleted, not rewritten**: a `@RestController` in this module is
> mapped by core's one `DispatcherServlet`. Tasks are renumbered, since nothing has been built yet and
> no external document cites these IDs. See [plan.md](plan.md) for the artifact-by-artifact mapping.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: User story label ([US1], [US2], [US3], [US4], [US5])
- Exact file paths listed in every task description

---

## Phase 1: Setup — Module Skeleton and the 019 Contract

**Purpose**: Create the module and prove it is a well-behaved extension under the shared-runtime
model *before* any TTS logic exists. Every rule 019 enforces is cheaper to satisfy on an empty module
than to retrofit onto a finished one.

**CRITICAL**: T001 gates everything. The rest of this phase is what a reviewer will check first.

> **T002 and T002a are already done** — the repository was scaffolded with a working `pom.xml`, and
> `mvn verify` on the empty module produces a JAR whose manifest reads `Extension-Id: tts`,
> `Extension-Version: 0.1.0`, `Require-API-Version: 0.1.18`. Verify rather than rewrite it.

- [X] T001 **Gate: confirm the host contract is in place.** Three checks, all cheap, all fatal if they fail. (a) `ai.multiroom:multiroom-api:0.1.18` is installed in `~/.m2` and actually contains classes — `unzip -l ~/.m2/repository/ai/multiroom/multiroom-api/0.1.18/multiroom-api-0.1.18.jar` lists ~70 entries including `multiroom/api/conversion/FormatConverter.class`, not 7 (see AGENTS.md, empty-JAR gotcha). (b) `ai.multiroom:multiroom-extension-starter:0.1.18` is installed beside it — it is this repository's parent POM. (c) A core built from that same version starts, answers `GET /actuator/extensions`, and serves `/api/**` and `/actuator/**` on **one** port, with `multiroom.api.extension.Extension`, `ExtensionContext` and `ExtensionState` absent from the API. If any check fails, stop: every task below assumes all three

> **T001a–T001c (the `FormatConverter` promotion) were completed upstream on 2026-09-20** and
> released as `multiroom-api` 0.1.18: `FormatConverter` now lives in `multiroom.api.conversion` and
> `FormatConversionException` in `multiroom.api.exceptions`, with `FormatConverterImpl` still the
> `@Component` in `multiroom-core`. The full-reactor `mvn verify` and the Pi verification those
> tasks required were run there. Nothing remains to do here beyond T001(a), which checks the result
> is installed.

- [x] T002 Create `pom.xml` inheriting `ai.multiroom:multiroom-extension-starter:0.1.18` **with an empty `<relativePath/>`** — outside the monorepo Maven would otherwise look for a sibling `../multiroom-extension-starter` and fail before the build starts. The starter supplies the manifest attributes, the shade `AppendingTransformer` for `…AutoConfiguration.imports`, the `bannedDependencies` rule against `ai.multiroom:multiroom-core` and the root-`application.yml` gate. Dependencies: `spring-boot-starter-web`, `spring-boot-starter-validation` and Jackson, all at **`provided`** (`multiroom-api` comes from the parent, also `provided`) — core supplies every one of them at runtime under the shared classloader. **`multiroom-api` is the only artifact of the host repository on the compile path**: WAV parsing uses the JDK's `javax.sound.sampled`, so there is no `multiroom-decoder` dependency (plan, "Parsing the provider's WAV"). **Bundle nothing framework-shaped** — it is what keeps the JAR small (019 SC-001) and what keeps two independently built extensions from colliding on a class name. 019's build-time duplicate-class gate (its FR-025) was **withdrawn on 2026-09-13**, so nothing checks this for you; the `bannedDependencies` rule (019 FR-023) is still live and does fail the build
- [x] T002a **Set `<extension.id>tts</extension.id>` and pin `<multiroom.require_api_version>0.1.18</multiroom.require_api_version>`.** Two properties, two distinct traps. The first: the starter declares `extension.id` empty and writes it into the manifest as `Extension-Id`; it is **not** derived from the artifact id, and it is the single string that ties three mechanisms together — `ExtensionScanner` rejects the JAR if it is blank or does not match `^[a-z0-9-]+$`, `ExtensionStatusResolver` reads `multiroom.<id>.enabled` from it, and `TtsAutoConfiguration`'s `@ConditionalOnProperty` (T006) names that property literally. `tts` — not `multiroom-tts` — is what keeps all three on `multiroom.tts.enabled`. The second: the starter defaults `multiroom.require_api_version` to `${project.version}`, which inside the monorepo meant the API version and here means *this extension's own* version (0.1.0). Left alone it would advertise `Require-API-Version: 0.1.0` and be rejected by a 0.1.18 core

> **T003 (adding `<module>multiroom-tts</module>` to the root POM) no longer exists** — this
> repository is a single module with no reactor above it. The ID is left unused rather than
> renumbered, so task references elsewhere stay valid.

- [X] T004 [P] Create `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` naming `multiroom.tts.TtsAutoConfiguration`. **This file is the extension entry point** — there is no `Extension` interface to implement and no `Extension-Class` manifest attribute any more (FR-001, 019 FR-003, 019 FR-004)
- [X] T005 Write `src/test/java/multiroom/tts/TtsAutoConfigurationTest.java` **before** T006, using `ApplicationContextRunner`: with defaults the auto-configuration contributes its beans; with `multiroom.tts.enabled=false` it contributes **none** and the context still starts. The second half is 019 FR-008 — every extension must be independently switchable off without removing its distributable
- [X] T006 Create `src/main/java/multiroom/tts/TtsAutoConfiguration.java` — `@AutoConfiguration`, `@ConditionalOnProperty(name = "multiroom.tts.enabled", havingValue = "true", matchIfMissing = true)` so an upgrade never silently disables a working extension, and `@ComponentScan("multiroom.tts")`. Core contains no knowledge of this module (019 FR-004, 019 FR-008). **Do NOT add `@EnableConfigurationProperties(TtsProperties.class)` here** — `TtsProperties` does not exist until T017, and Phase 1 would not compile. `@ConditionalOnProperty` reads the `Environment` directly and needs no properties bean; T017 adds the annotation when the class lands
- [X] T007 Verify the built `multiroom-tts.jar` **contains no root `application.yml` or `application.properties`**. On the shared classpath such a file shadows core's own; 019's build gate fails on it. All defaults belong in `TtsProperties` field initialisers (019 FR-024)
- [X] T008 Deploy the empty JAR with `mvn deploy -Plocal` (which copies it into the host checkout's `extensions/`; a bare `mvn deploy` would scp it to production), start core, and confirm it appears in **both** inventory surfaces — `GET /actuator/extensions` and `GET /api/v2/extensions` — with its name, version and required API version. **Confirm the manifest carries `Extension-Id: tts`** (T002a) and that it is the same string `@ConditionalOnProperty` uses: a mismatch — say `multiroom-tts` — passes the scanner's `^[a-z0-9-]+$` check and then splits the switch in two, with the conditional reading `multiroom.tts.enabled` while the inventory reads `multiroom.multiroom-tts.enabled`. Nothing fails loudly; T057(c) simply reports `INERT` where it expects `DISABLED`, hours later and for a reason that looks nothing like a manifest typo. At this point its status is **`INERT`**: accepted but contributing no bean. That is the correct reading of an empty module and confirms discovery works before any TTS code exists (019 FR-030)
- [X] T009 Record the start-up time with this fifth extension deployed and compare against 019's T092 figure. 019's SC-007 budget is 7 s for the whole application; a module that does no work during bean construction should be invisible here. **A measurable jump now means something is being done eagerly that should not be** — find it before the module grows
  - **Recorded (production Pi, multiroom.lan, via Loki, `Started MultiroomApplication in` line)**: median of the 5 most recent restarts with TTS deployed (2026-09-20/21) = **50.98 s** (52.129, 50.98, 50.014, 52.122, 49.962). Median of the 5 restarts immediately before TTS existed on this host (2026-09-13/17) = **52.195 s** (52.754, 54.018, 51.698, 51.915, 52.195). No measurable jump — the post-TTS figure is marginally *lower*, within run-to-run noise. Construction is confirmed lazy as designed.
  - Both figures sit well above 019's T092 x86 dev-machine baseline (12.310 s median) and the SC-007 7 s ceiling, but that gap **predates this extension** and is not something T009 is scoped to fix — 019's own plan.md Note 2 predicted ARM64 would miss the budget "by more, not less" than x86, and this is the first real Pi measurement confirming that prediction. Flagging as a pre-existing maintainer-level finding (019/ARM64 baseline), not a TTS defect

**Checkpoint**: the module builds, loads, switches off by property, ships no global config, and costs
nothing at start-up. Only now does TTS logic begin.

---

## Phase 2: User Story 1 — Announce Text on a Specific Room (Priority: P1) 🎯 MVP

**Goal**: Accept text + a single output target, synthesize via the configured default provider
(OpenAI), convert it to the native format with the **injected** `FormatConverter`, write the audio as a
WAV in the cache directory, register an ephemeral `tts://` input that resolves to that file, route it to
the target output, play, and — on the `RouteDestroyedEvent` core publishes when the route ends — restore
the target's prior state.

**Independent Test**: `POST /api/tts/speak` with
`{"text":"Dinner is ready","targetName":"living-room","targetType":"SINGLE_OUTPUT"}`. Synthesized speech
plays through the "living-room" output within 5 s, and the target returns to its prior state within
1 s of completion.

### Tests for User Story 1

> **Write these first — verify they FAIL before implementation.**

- [X] T010 [P] [US1] Write `src/test/java/multiroom/tts/config/TtsPropertiesValidationTest.java` covering **the 019 fail-fast line, which is the easiest thing in this module to get wrong**: a provider entry missing its API key, and a Piper `model-path` (or its `.onnx.json` sidecar) that does not exist, MUST fail validation (operator-fixable configuration faults — FR-028, 019 FR-018); an API key that is present but *wrong*, or an endpoint that is present but *unreachable*, MUST NOT be detected here at all, because validation never contacts a provider (FR-029, 019 FR-019). `python-executable` is the one Piper field never path-checked — it is normally a bare `PATH`-resolved command (`python3`), not a literal path *(revised 2026-09-20 — Piper moved from the archived `rhasspy/piper` binary to piper1-gpl)*
- [X] T011 [P] [US1] Write `src/test/java/multiroom/tts/service/TtsServiceTest.java` for the single-room flow (validate → synthesize → convert → register input → route) with Mockito mocks for `RouteService`, `DeviceRegistryService`, `DeviceQueryService`, `TtsProvider`, `FormatConverter` and `AudioCache`. **Assert `TtsService` does NOT unregister the input or restore routes itself** — that is `PlaybackCompletionListener`'s job (T016a/T025a), and doing it here would race the event. **`DeviceService` no longer exists** — `015-deviceservice-isp-refactor` split it into `DeviceQueryService` / `DeviceRegistryService` / `DeviceVolumeService` / `PlaybackControlService`; TTS uses `DeviceRegistryService.registerInput`/`unregisterInput` and `DeviceQueryService.getOutput`/`getGroup`
- [X] T012 [P] [US1] Write `src/test/java/multiroom/tts/rest/TtsControllerTest.java` as a **`@WebMvcTest`** slice: `POST /api/tts/speak` returns 202; empty text returns 400; unknown target returns 400; text over the configured maximum returns 400; a body carrying the optional `voice` and `language` fields is accepted and both reach `TtsService` unchanged (FR-030). Every 400 here must carry the `ErrorResponse` body with its code — assert the code, not just the status, so the controller and T016b cannot drift apart. A real Spring MVC slice is now possible — the previous plan had to stub a hand-rolled `HttpExchange`
- [X] T013 [P] [US1] Write `src/test/java/multiroom/tts/audio/AudioConverterTest.java` with a **mocked `FormatConverter`**: the provider's WAV is read with `AudioSystem.getAudioInputStream`, its `AudioFormat` mapped to `SampleFormat`, and both that format and the PCM are handed to the injected converter. Cover the mapping itself — 24 kHz mono 16-bit (OpenAI), 22.05 kHz mono (Piper), 24 kHz LINEAR16 (Google) — and a non-WAV payload raising `UnsupportedAudioFileException`, which must surface as the FR-023 rejection, and the converter's output is what gets written. Assert the adapter does **no** resampling, channel-mixing or bit-depth maths of its own (spec FR-031). Assert FR-023's rejection path twice: `canConvert(source, target) == false`, and `convert` throwing `FormatConversionException` — both surface as a rejected request with no audio played
- [X] T013a [P] [US1] Write `src/test/java/multiroom/tts/audio/WavFileWriterTest.java`: PCM + `SampleFormat` → a RIFF/WAV file that `AudioSystem.getAudioInputStream` reads back to the identical PCM and the identical format (the same JDK reader `AudioConverter` uses, so writer and reader are verified against one another); the write is atomic (temp file + `Files.move`) so a partially written file is never visible to a reader
- [X] T014 [P] [US1] Write `src/test/java/multiroom/tts/audio/TtsInputResolverTest.java`: `supports()` matches only the `tts` scheme; a registered `tts://<uuid>` resolves to a `ResolvedInputEndpoint` whose `effectiveUri` is the **`file://` URI of the WAV on disk** (never a `tts://` URI — core would then fail to find an `InputHandler` for it); an unknown UUID raises `InputResolutionException`; a UUID whose file was deleted re-materializes or fails cleanly; the entry is released after playback so the map does not grow without bound
- [X] T015 [P] [US1] Write `src/test/java/multiroom/tts/provider/OpenAiTtsProviderTest.java` with WireMock: successful WAV response; HTTP 429 raises a rate-limit error; timeout cancels the call. **Also assert construction makes no HTTP call** — the WireMock server must see zero requests until `synthesize` is invoked (019 FR-019)
- [X] T016 [P] [US1] Write `src/test/java/multiroom/tts/provider/ProviderRegistryTest.java`: `resolveDefault()` returns the first configured provider when no explicit default is set; `resolve("name")` returns the right one; an unknown name raises a clear error
- [X] T016a [P] [US1] Write `src/test/java/multiroom/tts/service/PlaybackCompletionListenerTest.java` — **the test for how this module learns an announcement has ended**, which nothing else covers: a `RouteDestroyedEvent` whose `route().getInputId()` matches a tracked announcement restores the snapshotted routes, unpins the cache entry and deletes the temp file if one was used; an event for **any other input is ignored** (music routes are destroyed all the time); a duplicate event for the same announcement is idempotent; an announcement whose snapshot was empty restores nothing and leaves the output silent. **Assert the listener never calls `unregisterInput`** — the input was registered with `autoRemove = true`, so core's `AutoRemoveInputListener` has already removed it by then and a second call throws `IllegalArgumentException("Input '…' is not registered")` (the documented re-entrancy gotcha from 015)
- [X] T016b [P] [US1] Write `src/test/java/multiroom/tts/rest/TtsExceptionHandlerTest.java` — the published error contract, which nothing else covers: each failure type maps to the code and status `contracts/tts-rest-api.yaml` declares. Caller-fixable → **400** (`INVALID_REQUEST` for blank or over-long text, `TARGET_NOT_FOUND`, `PROVIDER_NOT_FOUND`); provider-side → **503** (`PROVIDER_TIMEOUT`, `PROVIDER_RATE_LIMITED`, `PROVIDER_ERROR`); conversion refusal → `FORMAT_NORMALIZATION_FAILED`/**503** (provider-side: the caller chose neither format, so no rewording of the request helps — and the enum name the contract already publishes stays as it is, **do not rename it**, external consumers match on the string). Assert the body carries both `error` and `message`, and that no handler invents a code outside the enum (FR-032)

### Implementation for User Story 1

- [X] T017 [P] [US1] Create `src/main/java/multiroom/tts/config/TtsProperties.java` — `@ConfigurationProperties("multiroom.tts")` with `providers`, `defaultProvider`, `cache.dir`, `cache.maxSizeMb`, `maxTextLength` (default 500), `queue.maxDepthPerTarget`, `enabled`, and **per-provider `timeoutSeconds` defaulting to 10** (values above 10 are accepted, but log a `WARN` at start-up naming each such provider — SC-003's bound holds only at the default, and that trade must be visible; the warning is emitted from validation, never from a provider call) — *not* 30: SC-003 gives the whole failure path a 10-second budget, so a 30-second default guarantees a breach (FR-022). Add `@EnableConfigurationProperties(TtsProperties.class)` to `TtsAutoConfiguration` in this task — T006 deliberately left it out so Phase 1 compiles without this class. **Every default is a field initialiser here**, because the module ships no YAML (T007). Validate with `@Validated` + JSR-380 plus a `@PostConstruct` check that configured file paths exist — configuration only, never a network call (FR-013, FR-028, FR-029, 019 FR-018 / 019 FR-019 / 019 FR-024)
- [X] T018 [P] [US1] Create `src/main/java/multiroom/tts/provider/SynthesisRequest.java` — record (text, voice, language, targetSampleRate, targetChannels). `voice` and `language` carry the **effective** values: the request's override where given, otherwise the chosen provider's configured default (FR-030)
- [X] T019 [P] [US1] Create `src/main/java/multiroom/tts/provider/SynthesisResult.java` — record (audioData `byte[]`, sampleRate, channels, bitDepth)
- [X] T020 [US1] Create `src/main/java/multiroom/tts/provider/TtsProvider.java` — SPI with the single method `SynthesisResult synthesize(SynthesisRequest request)`, with Javadoc
- [X] T021 [US1] Implement `src/main/java/multiroom/tts/provider/ProviderRegistry.java` — named `Map<String, TtsProvider>`; first entry is the implicit default; `resolveDefault()` and `resolve(name)` (depends on T020)
- [X] T022 [US1] Implement `src/main/java/multiroom/tts/audio/AudioConverter.java` — the JDK's `AudioSystem.getAudioInputStream(new ByteArrayInputStream(...))` reads the provider's WAV into PCM plus an `AudioFormat` that a small private mapper turns into `SampleFormat` (**no `multiroom-decoder` dependency** — plan, "Parsing the provider's WAV"), and the **injected `multiroom.api.conversion.FormatConverter`** (promoted upstream in multiroom-api 0.1.18, implemented by core's `FormatConverterImpl`) converts it to the native format. **Write no conversion maths here**: resampling, channel mixing and bit-depth changes belong to the one shared converter (FR-031, constitution I). Guard with `canConvert(source, target)` and let `FormatConversionException` propagate as the FR-023 rejection. Remember the converter's borrowed-buffer contract — copy the returned `ByteBuffer` before `WavFileWriter` gets it
- [X] T022a [US1] Implement `src/main/java/multiroom/tts/audio/WavFileWriter.java` — PCM `byte[]` + `SampleFormat` → a RIFF/WAV file written atomically (temp file in the same directory, then `Files.move` with `ATOMIC_MOVE`). This is what makes the cache entry directly playable, which is what lets the resolver hand core a `file://` URI
- [X] T023 [US1] Implement `src/main/java/multiroom/tts/audio/TtsInputResolver.java` as an `InputEndpointResolver` for `tts://` URIs, holding a `ConcurrentHashMap<UUID, Path>`, and declare it as a **`@Bean`** in `TtsAutoConfiguration`. **No registration call is needed**: core's `DefaultInputResolutionService` takes `List<InputEndpointResolver>` by constructor injection (`multiroom-core/src/main/java/multiroom/core/io/inputs/resolve/DefaultInputResolutionService.java:34`), so under one Spring context the bean is collected automatically. This is the clearest single illustration of what 019 buys — the previous plan needed an SPI hand-off here (019 FR-005)
- [X] T023a [US1] **The resolver MUST return a `file://` URI, not a `tts://` one.** A resolver only rewrites a URI; core then looks the resulting *scheme* up among `InputHandler` beans in `InputHandlerRegistry` and throws `UnsupportedSchemeException` if none claims it. `InputHandler` lives in `multiroom.core.io`, a package this module is forbidden to depend on, so **no `tts://` handler can exist here** and a resolver holding PCM in memory would have nothing to hand it to. Resolve `tts://<uuid>` → `file:///<cache-dir>/<sha256>.wav` (`Path.toUri()`), with `EndpointRefreshMode.NONE` and `resolverId = "tts"`; core's existing `WavFileInputHandler` takes it from there. Pin the cache entry for the announcement's lifetime so LRU eviction cannot delete the file between enqueue and playback. Compare `multiroom-resolvers`' `SoundCloudInputResolver`, which resolves its own scheme to `https://` for the same reason
- [X] T024 [US1] Implement `src/main/java/multiroom/tts/provider/cloud/OpenAiTtsProvider.java` — `POST /v1/audio/speech` with WAV `response_format`, configurable timeout, `java.net.http.HttpClient`. **Build the `HttpClient` in the constructor; contact nothing.** An unreachable OpenAI endpoint at boot must leave start-up untouched, and a failed call surfaces as a per-request error (FR-011, FR-029, 019 FR-019)
- [X] T025 [US1] Implement `src/main/java/multiroom/tts/service/TtsService.java` — validate (blank, length, target exists via `DeviceQueryService.getOutput`) → resolve provider → resolve the effective voice/language (request override, else provider default — FR-030) → synthesize → convert via `AudioConverter` → write the WAV → snapshot and stop routes already targeting the output (`getAllRoutes` + `stopRoutesByOutput`) → hand the snapshot to `PlaybackCompletionListener` keyed by the ephemeral `InputId` → `DeviceRegistryService.registerInput` with `uri = "tts://<uuid>"` and `autoRemove = true` → `RouteService.createRoute(InputId, OutputId)` (FR-006). **The service's work ends there.** It does not block waiting for playback, does not restore routes and does not unregister the input — T025a owns all three, because completion is only observable as an event (FR-008). Everything before `createRoute` runs in the request thread, and **stays there when the queue arrives in T052** — only the routing step moves behind the queue, so a provider failure remains a 503 to the caller rather than a drop after a 202. Core services (`RouteService`, `DeviceRegistryService`, `DeviceQueryService`) arrive by **ordinary constructor injection**; there is no context object to pull them from (019 FR-005). **`DeviceService` does not exist** — see T011
- [X] T025a [US1] Implement `src/main/java/multiroom/tts/service/PlaybackCompletionListener.java` — **the piece that makes FR-008 possible.** Nothing in this module can watch playback directly: `AudioInput.onComplete` lives in `multiroom.core.io`, which the enforcer forbids, and timing the WAV's duration races the pipeline's drain. The observable signal is the route lifecycle: on EOF `AudioPipeline` drains, `RouteManagerImpl.handlePipelineStop` deregisters the route and publishes `RouteDestroyedEvent` (`multiroom-api`). Hold a `ConcurrentHashMap<InputId, Announcement>` of in-flight announcements and their route snapshots; on `@EventListener(RouteDestroyedEvent)` whose `route().getInputId()` matches one, re-create the snapshotted routes, unpin the cache entry, delete the temp file if T040 spilled one, and drop the map entry. **Ignore every non-matching event** and **never call `unregisterInput`** — `autoRemove = true` means core's `AutoRemoveInputListener` already did, and a second call throws (FR-008, FR-027's "playback completed"). SC-006 allows 1 s from this event; note the drain path already spent `max(500 ms, 50 × buffer)` before it fired
- [X] T025b [US1] Create the failure vocabulary the contract publishes: `src/main/java/multiroom/tts/rest/ErrorResponse.java` — record (`String error`, `String message`) — and `src/main/java/multiroom/tts/TtsException.java` carrying one `TtsErrorCode` enum constant per value in the contract's enum (`INVALID_REQUEST`, `TARGET_NOT_FOUND`, `PROVIDER_NOT_FOUND`, `PROVIDER_TIMEOUT`, `PROVIDER_RATE_LIMITED`, `PROVIDER_ERROR`, `FORMAT_NORMALIZATION_FAILED`). Services and providers throw `TtsException`; **nothing below the `rest` package knows about HTTP status codes** (FR-032)
- [X] T025c [US1] Implement `src/main/java/multiroom/tts/rest/TtsExceptionHandler.java` — a `@RestControllerAdvice` **scoped to this module's controllers** (`assignableTypes = {TtsController.class, TtsCacheController.class}`, so it cannot intercept another module's endpoints on the shared `DispatcherServlet`), holding the **single** code→status mapping: caller-fixable codes → 400, provider-side codes → 503. Map Spring's own `MethodArgumentNotValidException` to `INVALID_REQUEST`/400 so bean-validation failures use the same shape. This is the one place the mapping lives (FR-032, FR-010, FR-011, FR-021, FR-022, FR-023). **Note it is plain `ErrorResponse`, not RFC 7807** — `/api/v2/**` uses Problem Details, but that is `multiroom-rest`'s contract; `/api/tts/**` publishes this shape and must match its own YAML
- [X] T026 [US1] Implement `src/main/java/multiroom/tts/rest/TtsController.java` — a plain `@RestController` mapping `POST /api/tts/speak`, returning 202 with the announcement id. Bind the body to a `SpeakRequest` record with `text`, `targetName`, `targetType` required and `providerName`, `voice`, `language` optional, matching `contracts/tts-rest-api.yaml` field-for-field (FR-030). **Use `/api/tts/**`, not `/api/v2/**`**: that namespace is `multiroom-rest`'s published contract with two independently released external consumers, and this module must not be able to break it. This is the external trigger FR-014 requires. Add no authentication of its own — the shared HTTP surface governs access (FR-026). Annotate with `@Operation`/`@ApiResponse` so the generated contract is as good as its siblings' (019 FR-032 permits this — the prohibition is on *core* owning an API path, not on extensions)
- [X] T027 [US1] Declare the module's beans in `TtsAutoConfiguration` (providers, registry, `AudioConverter`, resolver, service, completion listener). Controllers and the `@RestControllerAdvice` arrive via the `@ComponentScan` from T006. `FormatConverter` is **injected, not declared** — core's `FormatConverterImpl` is the bean, and declaring a second one here would shadow it
- [X] T028 [US1] **Verify 019's SC-005 and SC-008 for real.** 019 proves these with a throwaway canary — **this module is the first real instance of them**. Split the check in two, because half of it depends on another extension being deployed.

  **(a) With `multiroom-tts.jar` as the only extension in `extensions/`** — these must all hold on their own, since TTS depends on no other extension:
  - `POST /api/tts/speak` answers on port 8080, and `/actuator/**` answers on that same port. No second web server, no second listening port (019 FR-026)
  - `GET /actuator/extensions` — core-owned, available with or without any extension installed (019 FR-028, 019 FR-030) — reports this extension as **`ACTIVE`**, not `INERT`
  - The startup `RequestMappingHandlerMapping` log lists `/api/tts/speak` against `multiroom.tts.rest.TtsController`

  **(b) With `multiroom-rest.jar` also deployed**, confirm `/api/tts/speak` appears in `GET /api-docs` with **zero changes to `multiroom-rest`**, and that `GET /api/v2/extensions` also lists TTS as `ACTIVE`. **Both surfaces belong to `multiroom-rest`, not to core**: springdoc is bundled in `multiroom-rest.jar`, and both `springdoc.api-docs.path=/api-docs` and the widened `springdoc.packages-to-scan=multiroom` come from that module's `SpringDocEnvironmentPostProcessor`. Without that JAR there is no `/api-docs` and no `/api/v2/**` — the TTS endpoints still serve correctly, they are simply undocumented, exactly as [quickstart.md](quickstart.md) §3 says. **A missing `/api-docs` in part (a) is therefore the expected result, not a TTS defect** — do not go looking for a bug in this module. While here, also record what happens with `multiroom-rest.jar` deployed but `multiroom.rest.enabled=false`: springdoc is Boot-autoconfigured from the shared classpath rather than from this project's code, so `/api-docs` is expected to keep working while `/api/v2/**` disappears. Confirm that rather than assuming it, and note the answer here — it is the first time an extension's contract surface has been observed independently of its owner's enable flag

- [ ] T028a [US1] **Verify SC-001 (playback begins within 5 s on a cache miss)**, which no task measured before — the plan claimed T028 covered it, but T028 checks ports, inventory and `/api-docs`, and its "SC-005 and SC-008" are 019's criteria, not this spec's. Against a WireMock provider with a fixed **3 s** delay — the assumption SC-001 itself names — request uncached text and time from request receipt to `createRoute` returning (the same stop point as T041a: the last moment this module controls). Budget: 5 s total, 3 s of it the provider, leaving ~2 s for WAV parsing, conversion, the cache write and route creation. Record the figure and re-measure on the production Pi during T058 — conversion of the provider's 24 kHz mono to the native format costs materially more on ARM64 and the SD-card write is the other variable, so a breach there but not on the development machine points at conversion cost rather than the provider (constitution VII)

**Checkpoint**: single-output announcements work end to end through the one application, one port, and
— when `multiroom-rest` is deployed — one contract document. This is a shippable MVP.

---

## Phase 3: User Story 2 — Announce Text Across All Rooms (Priority: P2)

**Goal**: Target an output group so an announcement plays simultaneously on every output in it.

**Independent Test**: `POST /api/tts/speak` with `targetType: OUTPUT_GROUP` and a group of three
outputs; speech plays on all three within the SC-002 100 ms window.

- [X] T029 [P] [US2] Extend `src/test/java/multiroom/tts/service/TtsServiceTest.java`: `OUTPUT_GROUP` targetType routes to all group outputs via `createRoute(InputId, GroupId)`; a group with no outputs raises the documented error
- [X] T030 [P] [US2] Extend `src/test/java/multiroom/tts/rest/TtsControllerTest.java`: `OUTPUT_GROUP` targetType returns 202; an unknown group returns 400; a `targetType` value outside `multiroom.api.model.TargetType` returns 400
- [X] T031 [US2] Extend `TtsService` to resolve `OUTPUT_GROUP` targets via `DeviceQueryService.getGroup` + `RouteService.createRoute(InputId, GroupId)`, so every output in the group receives the audio simultaneously, and restore via `stopRoutesByGroup` (FR-007)
- [X] T032 [US2] Return `TARGET_NOT_FOUND` when the group exists but has no outputs (FR-010)
- [ ] T032a [US2] **Verify SC-002 (≤100 ms between rooms)**, which no task measured before. Two parts. **(a) Structural, in `TtsServiceTest`:** a group target produces exactly **one** `createRoute(InputId, GroupId)` call — not one route per output. Core builds a single `AudioPipeline` fanning one input to every output in the group, so the rooms share a frame clock and sync is a property of that structure; N separate routes would be N pipelines and would not hold. **(b) Measured, on the production Pi** with a real multi-output group (constitution VII requires end-to-end Pi verification for audio behaviour): play a click-heavy announcement and confirm no audible flam between rooms, recording the result here. If (a) holds and the routing layer's own sync budget holds, (b) is a confirmation rather than a discovery

---

## Phase 4: User Story 5 — Serve Repeated Announcements from Cache (Priority: P2)

**Goal**: Identical text + provider + voice serves from disk without contacting the provider.

**Independent Test**: Send the same request twice; the second plays within 1 s and WireMock records no
second call.

- [X] T033 [P] [US5] Write `src/test/java/multiroom/tts/cache/FilesystemAudioCacheTest.java`: hit and miss; put returns a readable WAV `Path`; LRU eviction when the size limit is exceeded; **a pinned entry is not evicted even when it is the least recently used**; clear all; clear by provider; a corrupt file read as a miss; **the same text at a different `targetFormat` is a miss** and both entries coexist (FR-015); an `index.json` written before `targetFormat` existed is treated as a miss rather than deserialized into a wrong-format hit
- [X] T034 [P] [US5] Extend `TtsServiceTest`: a cache hit skips the provider call; a miss calls the provider and writes the cache; a disk-full write failure still plays the audio. **Assert SC-008 (the caller cannot tell hit from miss)**: the same text played twice yields the same `Path` in the same `SampleFormat`, and the route is created identically both times — the only observable difference is the absence of the provider call and the `cacheHit` flag in the response
- [X] T035 [P] [US5] Create `src/main/java/multiroom/tts/cache/CacheKey.java` — record (text, providerName, engineName, voice, language, **targetFormat**) with `toHash()` → SHA-256 hex. `targetFormat` is the `SampleFormat` the file was converted to, folded into the hash as `sampleRate|channels|bitDepth|sampleType`: a cached WAV outlives restarts and output-device changes, so without it an entry written for one native format would be served to an output expecting another. A format change must be a **miss**, never a wrong-format hit (FR-015)
- [X] T036 [P] [US5] Create `src/main/java/multiroom/tts/cache/AudioCache.java` — `Optional<Path> get(CacheKey)`, `Path put(CacheKey, byte[] pcm)`, `pin`/`unpin`, `invalidate`, `clear`, `clearByProvider`, `stats`. **`put` takes no separate `SampleFormat`** — the key already carries `targetFormat` (T035), and passing it twice invites the two copies to disagree. **It deals in `Path`, not `byte[]`**: the cached file is what core plays (T023a), so the cache is the materialization step, not a side store
- [X] T037 [US5] Implement `src/main/java/multiroom/tts/cache/FilesystemAudioCache.java` — `index.json` plus SHA-256-keyed `.wav` files (written through `WavFileWriter`), `lastAccessedEpoch` updated on read, LRU eviction by total size, **skipping pinned entries**. The index and files survive restarts (FR-017, FR-018). **Use a plain JDK structure for the in-memory index** (a synchronized `LinkedHashMap` in access order, or a `ConcurrentHashMap` plus explicit timestamps) — do **not** add Caffeine or any other third-party cache. A few hundred entries need no library, and on the shared runtime every bundled dependency is one more chance of a class-name collision that nothing checks for you any more
- [X] T038 [US5] Integrate `AudioCache` into `TtsService`: build the `CacheKey` from the resolved provider, engine, voice, language **and the target `SampleFormat`** (the same one `AudioConverter` converts to, so the key describes the file that was actually written), check before synthesis, write after success, log hit/miss (FR-015, FR-016, FR-027)
- [X] T039 [US5] Treat a corrupt or missing `.wav` file as a miss: re-synthesize and replace the entry (FR-020). This is also the path taken when the resolver finds its pinned file gone at route-creation time
- [X] T040 [US5] Handle a cache write failure (disk full, I/O error) by writing the WAV to a temporary file outside the cache directory (`Files.createTempFile`, deleted on playback completion), playing from there, logging a warning, and skipping the cache write for that request only (FR-025). Playback always needs a file behind it now, so "play anyway" means "spill elsewhere", not "play from memory"
- [X] T040a [P] [US5] Write `src/test/java/multiroom/tts/rest/TtsCacheControllerTest.java` **before T041** as a `@WebMvcTest` slice — the cache controller was the one production class in this list with no test, which constitution III forbids: `DELETE /api/tts/cache` clears everything and answers 204; `DELETE /api/tts/cache?providerName=openai` clears only that provider's entries; an unknown `providerName` returns 400 and clears nothing; `GET /api/tts/cache/stats` returns the `totalEntries` / `totalSizeBytes` / `maxSizeBytes` / `entriesByProvider` shape `contracts/tts-rest-api.yaml` publishes
- [X] T041 [US5] Implement `src/main/java/multiroom/tts/rest/TtsCacheController.java` — `DELETE /api/tts/cache` with an optional **`?providerName=`** parameter (the spelling in `contracts/tts-rest-api.yaml` and quickstart §6), and `GET /api/tts/cache/stats` (FR-019)

- [ ] T041a [US5] **Verify SC-007 (cache hit begins playing within 1 s)**, which no task measured before. Send the same request twice against a WireMock provider: assert the second makes **zero** provider calls (`verify(exactly(1), ...)` across both), and time from request receipt to `createRoute` returning. The budget covers key hashing, an `index.json` lookup and a pin — hundreds of milliseconds of headroom — so a breach means real work is happening on the hit path (re-reading the WAV, re-converting, rewriting the index). Record the figure; re-check it on the Pi during T058, where the disk is slower
  - **Recorded (production Pi, multiroom.lan, 2026-09-21T03:10:18+03:00, via Loki)**: `announcementId=66de7811-9c6f-4cc4-b080-deb4052b3b3e`, target=office, `TTS_REQUEST_RECEIVED` (cacheHit=true) → `Route created and started successfully` = **184 ms** (03:10:18.352504 → 03:10:18.536987). No `TTS_SYNTHESIS_STARTED`/`COMPLETED` in the trace — zero provider calls, confirmed. Well within the 1 s budget. This is a manual live-log spot check, not the automated WireMock `verify(exactly(1), ...)` assertion the task still requires — leaving the checkbox open until that test exists

---

## Phase 5: User Story 3 — Switch Provider per Request or via Configuration (Priority: P3)

**Goal**: Multiple named providers configured at once; per-request override by name.

**Independent Test**: Configure `openai` and `piper-local`; send one request with no provider and one
with `"provider":"piper-local"`; each is served by the right backend.

- [X] T042 [P] [US3] Write `GoogleCloudTtsProviderTest.java` with WireMock: LINEAR16 response, 429 rate-limit error, timeout. Assert construction issues no request
- [X] T043 [P] [US3] Write `PiperTtsProviderTest.java`: stdout WAV captured, non-zero exit handled, `ProcessBuilder` array form invoking `<python-executable> -m piper` (piper1-gpl; no standalone binary exists). **Assert no process is spawned during construction** (019 FR-019)
- [X] T044 [P] [US3] Write `LocalHttpTtsProviderTest.java` with WireMock: POST to the configured endpoint, request template interpolation, timeout cancellation
- [X] T044a [P] [US3] Extend `TtsServiceTest` and `TtsControllerTest` **before T048/T049** — the override path is the point of this story and had no test: a request naming a configured provider is served by that provider while the default is untouched; a request naming none uses `resolveDefault()`; an unknown `providerName` produces `PROVIDER_NOT_FOUND` with **no synthesis call** (verify with `verifyNoInteractions` on every provider mock, FR-002); a request overriding `voice` or `language` reaches the provider with the overridden value and produces a **different cache key** than the same text at the provider's defaults (FR-030, FR-015)
- [X] T045 [P] [US3] Implement `provider/cloud/GoogleCloudTtsProvider.java` — Google Cloud TTS REST, LINEAR16/PCM output, `java.net.http.HttpClient`, configurable timeout
- [X] T046 [P] [US3] Implement `provider/local/PiperTtsProvider.java` — `ProcessBuilder` **array form only**, invoking `<python-executable> -m piper --model <path> --config <path>.json` (piper1-gpl, `pip install piper-tts` — `rhasspy/piper`'s own C++ binary was archived in October 2025), stdout captured as WAV, timeout enforced. `model-path` and its `.onnx.json` sidecar are validated at start-up (T017); Piper is *run* only per request, never during construction
- [X] T047 [P] [US3] Implement `provider/local/LocalHttpTtsProvider.java` — POST to a user-configured endpoint with an optional JSON request template
- [X] T048 [US3] Wire the per-request `providerName`, `voice` and `language` overrides in `TtsService`: `resolve(name)` when present, `resolveDefault()` otherwise; each of voice and language falls back independently to the chosen provider's configured default (FR-005, FR-030)
- [X] T049 [US3] Return `PROVIDER_NOT_FOUND` (400) when the requested provider name matches nothing configured, with no synthesis call made (FR-002)

---

## Phase 6: User Story 4 — Queue Multiple Announcements (Priority: P4)

**Goal**: Concurrent requests for one target play in arrival order rather than overlapping.

**Independent Test**: Two rapid requests to "kitchen"; both play, in order.

- [X] T050 [P] [US4] Write `src/test/java/multiroom/tts/queue/AnnouncementQueueManagerTest.java`: two requests to one target play in order — **this is SC-005's "100 % of queued announcements play in submission order"**, so assert the order explicitly rather than merely that both played; two targets proceed independently; shutdown discards queued items but lets the current one finish. **Assert what the queue holds** — an announcement whose WAV already exists and whose cache entry is pinned, never an un-synthesized request — and add the regression this ordering exists for, in `TtsControllerTest`: with an announcement already playing on the target, a second request whose provider times out still answers **503**, not 202 (FR-032, SC-003)
- [X] T051 [US4] Implement `src/main/java/multiroom/tts/queue/AnnouncementQueueManager.java` — `ConcurrentHashMap<target, LinkedBlockingQueue<…>>`, one daemon worker per active target, bounded by `queue.maxDepth` (FR-009)
- [X] T052 [US4] Integrate the queue into `TtsService`, **moving only the routing step behind it**. Everything up to and including the WAV write stays in the request thread exactly as T025 left it — validate, cache lookup, synthesize, convert, write, snapshot the target's routes — so the caller still learns its own outcome and the provider-side codes stay reachable as 503 (FR-032, SC-003). What is enqueued is an announcement whose file already exists: `Path` + route snapshot + pinned cache entry. The queue worker performs `registerInput` + `createRoute` when the target frees up (FR-009), and the response reports `cacheHit` and `queueDepth` because both are known by then. **Do not enqueue the request itself**: synthesis is not the contended resource — the output is — and queuing it would turn every provider failure into a silent drop after a `202`. The worker starts the next announcement when `PlaybackCompletionListener` (T025a) reports the previous one finished — **reuse that signal, do not add a second completion mechanism**; the listener gains a per-target callback, nothing more
- [X] T053 [US4] Implement graceful shutdown as a `SmartLifecycle` (or `@PreDestroy`) on the queue manager: stop accepting, let the current announcement finish, discard the rest. **Shutdown is now the application's own** — there is no extension `stop()` callback any more, because the `Extension` interface is gone (FR-024, 019 FR-003). **Shutdown is the only path FR-024 covers**: `multiroom.tts.enabled=false` is resolved once at context refresh, so a disabled extension contributes no beans for that run and there is never a playing announcement to protect. Build no "disable mid-playback" handling — the state is unreachable, and code for it would be untestable and misleading

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T054 [P] Add the structured log events FR-027 requires: request received, cache hit, cache miss, synthesis started, synthesis completed, synthesis error, playback started, playback completed. No `System.out`
- [X] T055 [P] Add Javadoc to every public type: `TtsProvider`, `AudioCache`, `TtsService`, `PlaybackCompletionListener`, `TtsProperties`, `AudioConverter`, `WavFileWriter`, `TtsInputResolver` and both controllers (constitution V). The promoted `FormatConverter` keeps the Javadoc it already had — including its borrowed-buffer `@apiNote`, which now binds extension authors too
- [X] T056 Validate every edge case end to end, **asserting the error code and status each one returns**, not merely that it failed (FR-032): unknown target → `TARGET_NOT_FOUND`/400 (FR-010), unknown provider → `PROVIDER_NOT_FOUND`/400 (FR-002), empty text and text over the maximum → `INVALID_REQUEST`/400 (FR-012), provider timeout → `PROVIDER_TIMEOUT`/503 (FR-022 — confirm the 10 s default keeps the whole failure response inside SC-003's 10 s budget), rate limit → `PROVIDER_RATE_LIMITED`/503 (FR-021), conversion refusal → `FORMAT_NORMALIZATION_FAILED`/503 (FR-023), disk full → the announcement still plays (FR-025), and a voice/language or target-format change producing a distinct cache entry (FR-030, FR-015)
- [X] T057 **Validate the 019 failure contract end to end**, which no unit test reaches: (a) with a deliberately invalid `multiroom.tts` block, start-up **aborts** and the report names this extension (019 FR-018); (b) with valid configuration but **every provider unreachable** — OpenAI endpoint blackholed, `piper-tts` installed but never invoked, the local HTTP service stopped — start-up **succeeds** within the SC-007 budget and announcements fail per-request with a clear error (019 FR-019); (c) with `multiroom.tts.enabled=false`, the application starts, the inventory reports this extension as `DISABLED`, **no `/api/tts/**` path is mapped**, and the other four extensions are unaffected (019 FR-008). This is the whole of "disabled" — a start-up state, checked once here, with no mid-run transition to test (FR-024). **(d) Confirm FR-026**: this module contributes no security, filter or interceptor bean of its own — `/api/tts/**` is governed by whatever guards the shared HTTP surface, exactly as every other path is
- [ ] T058 Run [quickstart.md](quickstart.md) end to end: single cloud provider, Piper local fallback, group announcement, cache clear. **Verify SC-004 while here**: switch the default provider by editing configuration and restarting — no code change, no rebuild — and confirm the next announcement is served by the new default (quickstart §3). **Time the restore**: from `RouteDestroyedEvent` to the snapshotted route playing again must stay inside SC-006's 1 s, which is the criterion's own clock. Record the preceding drain (`max(500 ms, 50 × buffer)`) separately — it sits outside the budget but inside what a listener perceives, so the two figures together are the honest answer to how long the room stays silent
- [X] T059 Confirm `contracts/tts-rest-api.yaml` matches the generated document — fetch `/api-docs`, extract the `/api/tts/**` paths, and reconcile **the request/response schemas, the `ErrorResponse` code enum and the worked examples** (the examples drift silently — a timeout example naming a superseded default is how FR-022's change to 10 s went unnoticed). **If `/api/tts/**` is absent from the document altogether, suspect configuration before annotations**: `/api-docs` belongs to `multiroom-rest`, which bundles springdoc and contributes `springdoc.packages-to-scan=multiroom` as a *low-precedence default* (`SpringDocEnvironmentPostProcessor`, registered `addLast`). Any narrower value in `multiroom.yml` or on the command line — `multiroom.rest`, say — overrides it and silently drops every other module's endpoints from the document while leaving them fully served. Deploying without `multiroom-rest`, or with it disabled, means there is no `/api-docs` at all, which is not a TTS fault either (a code the handler can emit but the YAML does not list, or vice versa, is a contract break — FR-032). Re-measure start-up against T009 and confirm 019's SC-007 7 s median still holds with five extensions deployed

---

## Dependencies & Execution Order

### Phase Dependencies

```
multiroom-api 0.1.18 installed  (019 merged; FormatConverter promoted upstream)
              └─► Phase 1 (Setup + 019 contract)   T001 is a hard gate
              └─► Phase 2 (US1) 🎯 MVP
                        ├─► Phase 3 (US2) ─┐
                        ├─► Phase 4 (US5) ─┤ mutually independent
                        ├─► Phase 5 (US3) ─┤
                        └─► Phase 6 (US4) ─┘
                                    └─► Phase 7 (Polish)
```

Phase 1 no longer carries any change outside this repository. The `FormatConverter` promotion that used
to open it was completed upstream and released as `multiroom-api` 0.1.18, so from here it is just another
`multiroom-api` type. Phase 1 creates the module and proves it is a well-behaved extension; Phase 2 fills
it.

### User Story Dependencies

| Story | Priority | Depends on | Can parallelize with |
|-------|----------|-----------|----------------------|
| US1 | P1 | Phase 1 | — |
| US2 | P2 | US1 (extends `TtsService`) | US5, US3, US4 |
| US5 | P2 | US1 (wraps `TtsService`) | US2, US3, US4 |
| US3 | P3 | US1 (adds to `ProviderRegistry`) | US2, US5, US4 |
| US4 | P4 | US1 (wraps the enqueue path) | US2, US5, US3 |

### Within Each Phase

1. Tests first, verified failing
2. Records and configuration classes (no dependencies)
3. SPI interfaces
4. Infrastructure implementations (registry, converter adapter, resolver, cache)
5. Provider implementations
6. Service orchestrator
7. Controllers
8. Bean declarations in `TtsAutoConfiguration`

### Parallel Opportunities

- **Phase 1**: T001 gates everything; T004 is independent of T002/T002a; T005 precedes T006
- **Phase 2 tests**: T010–T016 plus T013a, T016a and T016b are ten independent files, written together
- **Phase 2 records**: T017, T018, T019 are independent
- **Phase 5**: T042–T044 (tests) and T045–T047 (providers) are independent files
- **After Phase 2**: Phases 3, 4, 5 and 6 can proceed simultaneously by different people

---

## Implementation Strategy

**MVP Scope**: Phases 1 + 2 (T001–T028 plus T002a, T013a, T016a, T016b, T022a, T023a, T025a–T025c, T028a) — single-output TTS over
REST with the OpenAI provider, served on the one application port, documented in the one contract with
the error shape it publishes, visible in the extension inventory, with the room restored afterwards.

**Incremental Delivery**:

| Increment | Phases | Tasks | Delivers |
|-----------|--------|-------|----------|
| MVP | 1 + 2 | T001–T028 + the eleven lettered tasks (41) | `FormatConverter` in the API; single-output TTS via REST, OpenAI provider, published error contract, state restored on completion, request-to-playback latency measured |
| Increment 2 | 3 + 4 | T029–T041 + T032a/T040a/T041a (16) | Group routing (sync verified) + disk cache (hit latency verified) |
| Increment 3 | 5 | T042–T049 + T044a (9) | Google Cloud, Piper, Local HTTP providers; per-request overrides |
| Increment 4 | 6 | T050–T053 (4) | Per-target announcement queue |
| Polish | 7 | T054–T059 (6) | Logging, Javadoc, edge cases, 019 failure contract |

**Task counts by story**:

| Story | Test tasks | Implementation tasks | Total |
|-------|-----------|----------------------|-------|
| Setup (Ph1) | 1 | 12 | 13 |
| US1 (P1) | 10 | 18 | 28 |
| US2 (P2) | 2 | 3 | 5 |
| US5 (P2) | 3 | 8 | 11 |
| US3 (P3) | 4 | 5 | 9 |
| US4 (P4) | 1 | 3 | 4 |
| Polish | — | 6 | 6 |
| **Total** | **21** | **51** | **72** |

Setup lost four tasks to the repository move (T001a–T001c, T003); every remaining task builds this module
and nothing else. T028a, T032a and T041a are counted as implementation tasks although they only measure:
each verifies a success criterion (SC-001, SC-002, SC-007) that had no owner before.

### Risk Notes

- **T001 is a real gate, not a formality.** Every task here assumes one Spring context, one port, and
  no `Extension` interface. Started against a tree without 019 merged, Phase 2 fails in a way that looks like a
  TTS bug and is not.
- **The merge gate has two halves here, and the second is the one that catches real breakage.** `mvn verify` in this repository proves the module compiles against the API it was handed; it cannot prove the JAR loads. Deploy it to a live core and read `GET /actuator/extensions` before calling anything done (constitution V). A JAR that builds cleanly and is rejected at start-up is the normal failure mode of an out-of-reactor extension, not an exotic one.
- **T025a is where FR-008 actually lives.** A reviewer reading only T025 will think the service restores the room; it does not, because it cannot — playback completion is observable only as `RouteDestroyedEvent`. If the listener is skipped, every announcement leaves its room silent afterwards and the ephemeral input's `autoRemove` quietly hides half the symptom.
- **Do not unregister the ephemeral input from this module.** `autoRemove = true` + `AutoRemoveInputListener` already does it on the same event; a second call throws `IllegalArgumentException("Input '…' is not registered")`. This is the 015 re-entrancy gotcha, reached here from the other side.
- **T023a is the second real gate.** A `tts://` URI that reaches `InputHandlerRegistry` unrewritten fails with `UnsupportedSchemeException` at route-creation time — well after the request was accepted with 202, so it surfaces as "the announcement never played" rather than as an error the caller sees. Assert the resolved scheme in T014, not just the happy path.
- **019's duplicate-class build gate (its FR-025) was withdrawn on 2026-09-13** and `duplicate-finder-maven-plugin` / `multiroom-build-verification` are no longer in the tree. Keeping this module's bundled dependencies near zero still matters — more so, since nothing checks it at build time any more — but do not expect a build failure to catch a collision.
- **T010, T015, T043 and T057 guard the same rule from four angles.** Doing network or subprocess work
  during bean construction turns an unreachable provider into an application that will not boot. 019
  drew this line deliberately (its FR-018 vs its FR-019); this module is the first new extension written
  against it, so the mistake has no precedent to copy from.
- **Do not take `/api/v2/**`.** It is `multiroom-rest`'s published namespace with two out-of-repo
  consumers. `/api/tts/**` is this module's.
- **The module ships no `application.yml`.** Under the shared classpath a root `application.yml` in an
  extension JAR shadows core's — this is precisely the bug 019 found in `multiroom-mqtt` and added a
  build gate for. Defaults go in `TtsProperties`.
- **MQTT triggering is deferred**, and *how* it would be built is an open decision — see
  [contracts/tts-mqtt-topics.md](contracts/tts-mqtt-topics.md), which sets out three options, picks
  none, and flags a constitution VIII vs. 019 FR-007 conflict for the maintainer. Spec FR-014 needs
  only one transport and HTTP provides it, so nothing here is blocked on that answer.

---

## Notes

- `[P]` = different files, no dependencies
- Commit after each task or logical group; conventional commits; feature branch `001-tts-extension`
  (constitution VI)
- The extension reports `ConnectionState.NOT_APPLICABLE` in the 019 inventory: its providers are
  contacted per request, not held open, so there is no connection state to report and no reporter to
  build

## Implementation Status (2026-09-20 `/speckit-implement` run)

All of Phases 1–7 are implemented and unit/slice-tested: `mvn verify` is green (98 tests, 0
failures) and the shaded JAR carries the correct manifest, no bundled `application.yml`, and no
bundled framework classes (63 entries, all this module's own). The module was also deployed with
`mvn deploy -Plocal` and exercised against a real, locally running `multiroom-core` 0.1.18
(alongside `multiroom-rest.jar`), which confirmed live: `tts` reports `ACTIVE` on both
`/actuator/extensions` and `/api/v2/extensions`; `POST /api/tts/speak` and
`GET /api/tts/cache/stats` answer on the single port 8080 and return the documented error shape;
`/api-docs` lists all three `/api/tts/**` paths with correct status codes (202/400/503) once a
schema-name collision with `multiroom-rest`'s own `ErrorResponse` (RFC 7807) was found and fixed by
naming this module's schema `TtsErrorResponse` (`ErrorResponse.java`, `TtsController.java`,
`TtsCacheController.java`); `multiroom.tts.enabled=false` starts cleanly with `tts` reporting
`DISABLED`, no `/api/tts/**` mapped, and `rest` unaffected; a provider missing its `api-key` aborts
start-up naming the extension (`Start-up aborted: extension 'tts' ... requires api-key`) exactly as
019 FR-018 requires.

**Left unchecked, and why** — these five all need real audio hardware (an actual output device,
or a Raspberry Pi per constitution VII) that this sandboxed dev environment does not have. Nothing
about them is expected to fail; they are simply unmeasured:

- **T009** — two boot-time comparisons were taken (`multiroom.tts.enabled=false`: 5.73 s;
  enabled with one OpenAI provider: 6.85 s), both comfortably inside 019's 7 s budget, but that
  ~1.1 s delta is a single sample each way and deserves a few more runs before trusting it as
  signal rather than noise.
- **T028a** — SC-001 (5 s to playback on a cache miss) needs a real output to route to; only
  `TARGET_NOT_FOUND` could be produced end-to-end without one.
- **T032a(b)** — the structural half (exactly one `createRoute(InputId, GroupId)` call) is
  verified by `TtsServiceTest.outputGroupTargetProducesExactlyOneCreateRouteCall`; the audible
  multi-room sync check is Pi-only per constitution VII.
- **T041a** — SC-007 (1 s to playback on a cache hit) has the same real-output dependency as T028a.
- **T058** — the full quickstart walkthrough (Piper local fallback, a real multi-output group,
  SC-004's provider hot-swap, SC-006's restore timing) needs `piper-tts` installed with a model and real
  outputs; the HTTP trigger and cache-clear portions were exercised live and work.

## Phase 8: Convergence

- [X] T060 Inject `TtsInputResolver` into `PlaybackCompletionListener` and call `release(task.announcementId())` from `restore()` (`src/main/java/multiroom/tts/service/PlaybackCompletionListener.java`), alongside the existing cache-unpin and temp-file cleanup — `TtsInputResolver.filesByAnnouncement` (`src/main/java/multiroom/tts/audio/TtsInputResolver.java`) currently accumulates one entry per announcement for the life of the process because nothing calls the `release` method its own Javadoc says exists "so the map does not grow without bound" per plan: TtsInputResolver lifecycle (partial)
- [X] T061 Close the two cache/tracking leaks on paths that never reach `RouteDestroyedEvent`: unpin `cacheKey` in `TtsService.speak()` (`src/main/java/multiroom/tts/service/TtsService.java`) if `queueManager.enqueue(...)` throws before a task is created, and in `TtsService.activate()`'s catch block (reached when `RouteService.createRoute` fails), drop the corresponding entry from `PlaybackCompletionListener.inFlight` and run the same cache-unpin / temp-file-delete cleanup `restore()` performs on the success path, per FR-008 / plan: Detecting that an announcement has finished (partial)
