---

description: "Task list for 005-tts-metrics"
---

# Tasks: TTS Metrics

**Input**: Design documents from `/specs/005-tts-metrics/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/metrics.md](contracts/metrics.md),
[quickstart.md](quickstart.md)

**Tests**: Included. The constitution's Principle III (Test-First) requires a failing test before
production code, so every implementation task is preceded by the test that drives it. Tests use
JUnit 5, Mockito, `SimpleMeterRegistry` / `PrometheusMeterRegistry` and `ApplicationContextRunner`;
no test calls a live provider.

**Organization**: One phase per user story, in the spec's priority order. US1 and US2 are both P1;
US1 comes first because it is the smallest end-to-end slice (the "is TTS working?" number).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: the user story the task belongs to (US1–US4)

## Path Conventions

Single Maven module: `src/main/java/multiroom/tts/…`, `src/test/java/multiroom/tts/…`, `docs/`
at the repository root.

## Rules that hold for every task

- **Do not cite spec IDs in code** (`FR-`, `SC-`, `US`, `R` numbers). State the rule in the comment.
- **Only `multiroom.tts.metrics.MicrometerTtsMetrics` (and tests) may import `io.micrometer`.**
  Everything else talks to the `TtsMetrics` interface, so the module loads without Micrometer.
- **Every series of one metric carries the same tag keys** (Prometheus drops a meter whose key set
  differs). A value that does not apply is a fixed word (`none`, `unknown`), never a missing tag.
- **No tag value from caller free text**: never the message, voice, language, style prompt,
  announcement id, an unconfigured provider name or an exception message.
- **Recording never throws into an announcement and adds no I/O to the cache-hit path.**

---

## Phase 1: Setup

**Purpose**: dependencies and version

- [X] T001 In `pom.xml`: bump `<version>` from `0.1.3` to `0.1.4`; add `io.micrometer:micrometer-core` with `<scope>provided</scope>` and no `<version>` (managed by `spring-boot-dependencies` 3.5.15 → 1.15.12), with a comment in the style of the existing ones saying the host supplies it on the shared classloader; add `io.micrometer:micrometer-registry-prometheus` with `<scope>test</scope>` and no version. Leave `multiroom.require_api_version` at `0.1.18`
- [X] T002 Run `mvn clean verify` and confirm the build still passes, the enforcer accepts the new dependency, and `unzip -l target/multiroom-tts-0.1.4.jar | grep -i micrometer` lists nothing (nothing shaded)

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: the `TtsMetrics` seam, its no-op, the Micrometer skeleton and the wiring, so every
story only fills in events. No metric is recorded yet at the end of this phase.

**⚠️ CRITICAL**: no user story can start until this phase is complete

- [X] T003 [P] Add `configName()` to `src/main/java/multiroom/tts/config/ProviderType.java` returning the configuration spelling (`openai`, `google-cloud`, `google-gemini`, `piper`, `local-http`), with a test for all five values in new `src/test/java/multiroom/tts/config/ProviderTypeTest.java` written first
- [X] T004 [P] Create record `src/main/java/multiroom/tts/metrics/SynthesisUsage.java` (`String providerName`, `ProviderType providerType`, `String tier`, `int textCharacters`, `int stylePromptCharacters`) with `billableCharacters()` = sum of both, and a static factory `of(String providerName, ProviderType type, String tier, String text, String stylePrompt)` counting Unicode code points (`codePointCount`; `null` prompt → 0). Test first in `src/test/java/multiroom/tts/metrics/SynthesisUsageTest.java`: an emoji counts as 1, a `null` prompt as 0, `billableCharacters` sums both. Javadoc on the record: it is the single measurement a future budget check reads
- [X] T005 [P] Add `boolean isConfigured(String name)` and `Set<String> names()` (an unmodifiable view of the keys) to `src/main/java/multiroom/tts/provider/ProviderRegistry.java`, reading the existing map only (constructor unchanged). Test first in `src/test/java/multiroom/tts/provider/ProviderRegistryTest.java`: `isConfigured` is true for a registered name, false for an unknown name and for `null`; `names()` returns the registered names, unmodifiable. Needed here because the Micrometer skeleton and its wiring take the configured names
- [X] T006 Create interface `src/main/java/multiroom/tts/metrics/TtsMetrics.java` with the methods in [data-model.md](data-model.md): `announcementAccepted(String providerTag)`, `announcementRejected(String providerTag, String error, String reason)`, `cacheLookup(boolean hit)`, `SynthesisTimer synthesisStarted(SynthesisUsage usage)`, `audioProduced(SynthesisUsage usage, double seconds)`, `trackQueue(TargetType type, String target, IntSupplier depth)`, `playbackStarted()`, `playbackFailed()`; and nested interface `SynthesisTimer` with `succeeded()` and `failed(TtsErrorCode error)` (always a code: a non-`TtsException` failure is wrapped as `PROVIDER_ERROR` before it is recorded). Javadoc: no method throws; callers pass only bounded values. Also define the shared tag-value constants here (`NONE = "none"`, `UNKNOWN = "unknown"`, `INTERNAL = "INTERNAL"`) so callers and the implementation spell them once (depends on T004)
- [X] T007 [P] Create `src/main/java/multiroom/tts/metrics/NoopTtsMetrics.java`: every method does nothing, `synthesisStarted` returns one shared no-op `SynthesisTimer`; class Javadoc says when it is chosen (no Micrometer, or no registry bean) (depends on T006)
- [X] T008 Create `src/main/java/multiroom/tts/metrics/MicrometerTtsMetrics.java` implementing `TtsMetrics` with: a constructor taking `MeterRegistry`, `AudioCache`, the longest `timeout-seconds` across the **enabled** entries of `TtsProperties.getProviders()` (disabled entries are not in the registry and never synthesize), and the configured provider names from `ProviderRegistry.names()` (T006); empty event bodies to be filled per story; a private `record(String metric, Runnable action)` guard that catches `RuntimeException`, logs `WARN` the first time per metric name and `DEBUG` after, and returns; and a `tag(String value, String fallback)` helper mapping `null` to the fallback. Test first in `src/test/java/multiroom/tts/metrics/MicrometerTtsMetricsTest.java`: a registry whose `counter(...)` throws (Mockito mock of `MeterRegistry`) does not propagate from any public method. Class Javadoc: the only class that imports Micrometer, and why (depends on T005, T006)
- [X] T009 Wire the bean in `src/main/java/multiroom/tts/TtsAutoConfiguration.java` with two nested `@Configuration(proxyBeanMethods = false)` classes: one `@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")` defining `TtsMetrics ttsMetrics(ObjectProvider<MeterRegistry>, AudioCache, TtsProperties, ProviderRegistry)` (passing `providerRegistry.names()`) that returns `MicrometerTtsMetrics` when `getIfAvailable()` is non-null and `NoopTtsMetrics` otherwise; one `@ConditionalOnMissingClass("io.micrometer.core.instrument.MeterRegistry")` defining the no-op. Name the class by string only, so `TtsAutoConfiguration` loads without Micrometer. No `@ConditionalOnBean` and no `after = …`: `ObjectProvider` resolves at bean creation, so there is no ordering race. Test first in new `src/test/java/multiroom/tts/TtsAutoConfigurationMetricsTest.java` (`ApplicationContextRunner`, reusing the host-service mocks `TtsAutoConfigurationTest` already supplies): with a `SimpleMeterRegistry` bean → `MicrometerTtsMetrics`; with no registry → `NoopTtsMetrics`; with `FilteredClassLoader(MeterRegistry.class)` → the context starts and holds `NoopTtsMetrics`; with `multiroom.tts.enabled=false` → no `TtsMetrics` bean and no `tts.*` meter in a supplied registry (depends on T007, T008)
- [X] T010 Make `src/main/java/multiroom/tts/service/TtsService.java` take `TtsMetrics` as its last constructor parameter (store it; record nothing yet) and pass the bean from `TtsAutoConfiguration.ttsService`. In `src/test/java/multiroom/tts/service/TtsServiceTest.java`, route all five `new TtsService(...)` sites (the shared service helper and the Google and Gemini construction helpers — find them with `grep -n "new TtsService"`) through one helper so the `TtsMetrics` mock (a `@Mock TtsMetrics metrics` field, `synthesisStarted` stubbed to return a mock `SynthesisTimer`) is added once, not per site. All existing tests must still pass (depends on T009)
- [X] T011 Make `src/main/java/multiroom/tts/rest/TtsExceptionHandler.java` take `TtsMetrics` through its constructor (record nothing yet). Because the advice is component-scanned into every `@WebMvcTest`, add `@MockBean TtsMetrics metrics` to `src/test/java/multiroom/tts/rest/TtsControllerTest.java`, `TtsExceptionHandlerTest.java`, `TtsCacheControllerTest.java` and `TtsVoiceControllerTest.java`; all existing tests must still pass (depends on T006)

**Checkpoint**: `mvn verify` passes; the extension loads with a registry, without one, without
Micrometer, and disabled; nothing is recorded yet.

---

## Phase 3: User Story 1 - See Announcement Volume and Failures (Priority: P1) 🎯 MVP

**Goal**: `tts_announcements_total{outcome, error, reason, provider}` counts every speak request the
extension answers, including body/validation rejections, with a bounded `provider` tag and a
`reason` that separates queue rejections from real provider errors.

**Independent Test**: one valid speak, one to a missing target, one with body `{`, one naming
provider `nope` → accepted = 1; rejected `TARGET_NOT_FOUND` = 1; rejected `INVALID_REQUEST`
with `provider="unknown"` = 1 (and a 400 `ErrorResponse` body); rejected `PROVIDER_NOT_FOUND` with
`provider="unknown"` = 1, and `nope` appears nowhere in the scrape. Before any request, the
`accepted` series exist at 0.

### Tests for User Story 1 (write first, see them fail)

- [X] T012 [P] [US1] In `src/test/java/multiroom/tts/queue/AnnouncementQueueManagerTest.java`: a full queue throws `QueueRejectedException` with `reason() == QUEUE_FULL`, `getErrorCode() == PROVIDER_ERROR` and the same message as today; an enqueue after `stop()` throws it with `SHUTTING_DOWN`
- [X] T013 [P] [US1] In `src/test/java/multiroom/tts/metrics/MicrometerTtsMetricsTest.java`: `announcementAccepted("a")` increments `tts.announcements{outcome=accepted,error=none,reason=none,provider=a}`; `announcementRejected` increments the `rejected` series with the given `error`/`reason`; `null` provider → `unknown`, `null` error/reason → `none`; right after construction, `tts.announcements{outcome=accepted,…,provider=<each configured name>}` exists with count 0
- [X] T014 [P] [US1] In `src/test/java/multiroom/tts/service/TtsServiceTest.java` (metrics mocked): an accepted speak calls `announcementAccepted(<resolved name>)` once, and with no provider named uses the default provider's name; `TARGET_NOT_FOUND` → `announcementRejected(<name or default>, "TARGET_NOT_FOUND", "none")`; an unconfigured provider name → `announcementRejected("unknown", "PROVIDER_NOT_FOUND", "none")`; a blank text → `INVALID_REQUEST`; a full queue → `("…", "PROVIDER_ERROR", "queue_full")`; an unexpected `RuntimeException` from a collaborator → `("…", "INTERNAL", "none")` and the exception still propagates; each request records exactly one announcement event
- [X] T015 [P] [US1] In `src/test/java/multiroom/tts/rest/TtsExceptionHandlerTest.java` (or `TtsControllerTest.java`, whichever already covers bad bodies): `POST /api/tts/speak` with body `{` answers 400 with `ErrorResponse{error:"INVALID_REQUEST", message:"Request body could not be read"}` (the body is not echoed) and calls `announcementRejected("unknown", "INVALID_REQUEST", "none")`; an unknown `targetType` value behaves the same; a bean-validation failure (blank `text`) also records that rejection; a validation failure on a non-speak controller records nothing

### Implementation for User Story 1

- [X] T016 [P] [US1] Create `src/main/java/multiroom/tts/queue/QueueRejectedException.java` `extends TtsException`, always `TtsErrorCode.PROVIDER_ERROR`, with `enum Reason { QUEUE_FULL, SHUTTING_DOWN }`, `Reason reason()` and `String tagValue()` returning `queue_full` / `shutting_down`; throw it from both rejection branches of `src/main/java/multiroom/tts/queue/AnnouncementQueueManager.java` with the existing messages, update that method's `@throws` Javadoc, and give the new class and its `Reason` enum Javadoc (makes T012 pass)
- [X] T017 [US1] Implement `announcementAccepted` / `announcementRejected` in `src/main/java/multiroom/tts/metrics/MicrometerTtsMetrics.java` on counter `tts.announcements` with tag keys `outcome`, `error`, `reason`, `provider`, and pre-register the `accepted` series (count 0) for every name in `ProviderRegistry.names()` in the constructor (makes T013 pass)
- [X] T018 [US1] Record announcements in `src/main/java/multiroom/tts/service/TtsService.java`: rename the current `speak` body to a private method and wrap it in `speak`, which computes the provider tag up front (`command.providerName()` if `providerRegistry.isConfigured(...)`, the default name when none is given, else `TtsMetrics.UNKNOWN`), records `announcementAccepted` on return, and on `TtsException` records `announcementRejected(tag, code.name(), reason)` where reason is `QueueRejectedException.tagValue()` or `none`; on any other `RuntimeException` records `INTERNAL`/`none`; always rethrows. Do not add a record call at each `throw` (makes T014 pass)
- [X] T019 [US1] In `src/main/java/multiroom/tts/rest/TtsExceptionHandler.java`: add `@ExceptionHandler(HttpMessageNotReadableException.class)` answering 400 `ErrorResponse(INVALID_REQUEST, "Request body could not be read")`; in it and in `handleValidationFailure`, take a `HandlerMethod` parameter and record `announcementRejected(TtsMetrics.UNKNOWN, "INVALID_REQUEST", TtsMetrics.NONE)` only when `handlerMethod.getBeanType()` is `TtsController`. Keep the advice's `assignableTypes` scope unchanged. Do not record in `handleTtsException` (`TtsService` already did) (makes T015 pass)

**Checkpoint**: US1 works on its own. `mvn test` passes; a scrape shows accepted and rejected
announcements by error code, reason and bounded provider.

---

## Phase 4: User Story 2 - Compare Provider Latency and Reliability (Priority: P1)

**Goal**: per provider entry, `tts_synthesis_seconds` (histogram), `tts_synthesis_characters`
(by `part` and `tier`) and `tts_synthesis_audio_seconds`, recorded only on a cache miss, with the
character count taken once, before the provider call.

**Independent Test**: two providers, one failing; synthesize on each (cache misses) → each has its
own timer series with its `type`; the failure carries its error code; each provider's
`tts_synthesis_characters_sum{part="text"}` equals the sum of its message lengths; a cache hit adds
no synthesis sample.

### Tests for User Story 2 (write first, see them fail)

- [X] T020 [P] [US2] In each provider test — `src/test/java/multiroom/tts/provider/OpenAiTtsProviderTest.java`, `GoogleCloudTtsProviderTest.java`, `GoogleGeminiTtsProviderTest.java`, `PiperTtsProviderTest.java`, `LocalHttpTtsProviderTest.java` — assert `type()` returns its `ProviderType`, and `billingTier(settings)` returns `none` for OpenAI, Piper and local HTTP
- [X] T021 [P] [US2] In `src/test/java/multiroom/tts/provider/GoogleCloudTtsProviderTest.java`: `billingTier` of resolved settings is the canonical engine family for each `GoogleEngine` (`Standard`, `Wavenet`, `Neural2`, `Studio`, `Chirp-HD`, `Chirp3-HD`), `other` for an unrecognized engine segment (`en-US-Polyglot-1`), and never the voice itself; in `GoogleGeminiTtsProviderTest.java`: `billingTier` is the entry's configured model (e.g. `gemini-2.5-flash-tts`). Neither performs I/O (WireMock sees no request)
- [X] T022 [P] [US2] In `src/test/java/multiroom/tts/metrics/MicrometerTtsMetricsTest.java`: `synthesisStarted(usage).succeeded()` records one `tts.synthesis` sample tagged `provider`, `type` (`configName()`, or `unknown` for a `null` type), `outcome=success`, `error=none`; `failed(TtsErrorCode.PROVIDER_TIMEOUT)` records `outcome=failure,error=PROVIDER_TIMEOUT`; `tts.synthesis.characters` gets one `part=text` sample of `textCharacters` and one `part=style_prompt` sample only when that count is > 0, both tagged `provider`, `type`, `tier`, `outcome`, `error`; `audioProduced(usage, 2.5)` records 2.5 on `tts.synthesis.audio` tagged `provider`, `type`, `tier`; the timer's service-level buckets are the list in [research.md](research.md) R12 cut after the first bound ≥ the longest timeout, with that timeout added (cases: 30 → …, 20, 30; 25 → …, 20, 25, 30; 200 → the full list plus 200)
- [X] T023 [P] [US2] In `src/test/java/multiroom/tts/service/TtsServiceTest.java`: a cache miss calls `synthesisStarted` once with a `SynthesisUsage` whose name, type, tier and character counts match (style-prompt count from the resolved settings for a Gemini provider), before `provider.synthesize` (verify with `InOrder`), then `succeeded()`; a provider `TtsException(PROVIDER_TIMEOUT)` → `failed(TtsErrorCode.PROVIDER_TIMEOUT)`; a provider `RuntimeException` → `failed(TtsErrorCode.PROVIDER_ERROR)`; `audioProduced` receives the duration of the converted PCM (e.g. 192,000 bytes of 48 kHz stereo 16-bit → 1.0 s) and is not called on failure; a cache hit calls neither `synthesisStarted` nor `audioProduced`; a mocked `TtsProvider` whose `type()` returns `null` still works; a cache miss followed by a full queue (`QueueRejectedException`) still records `synthesisStarted` and `succeeded()` (the characters were sent and billed), while a cache hit followed by a full queue records neither; a successful synthesis whose `audioConverter.convert` throws `FORMAT_NORMALIZATION_FAILED` records `succeeded()`, no `audioProduced`, and `announcementRejected(…, "FORMAT_NORMALIZATION_FAILED", "none")`

### Implementation for User Story 2

- [X] T024 [US2] Add to `src/main/java/multiroom/tts/provider/TtsProvider.java`: `ProviderType type()` and `default String billingTier(SynthesisSettings settings)` returning `"none"`, both documented as pure like `resolveSettings` (no I/O; the tier must come from resolved settings or configuration, never caller free text)
- [X] T025 [P] [US2] Implement `type()` in `src/main/java/multiroom/tts/provider/cloud/OpenAiTtsProvider.java`, `src/main/java/multiroom/tts/provider/local/PiperTtsProvider.java` and `src/main/java/multiroom/tts/provider/local/LocalHttpTtsProvider.java` (depends on T024)
- [X] T026 [P] [US2] In `src/main/java/multiroom/tts/provider/cloud/GoogleCloudTtsProvider.java`: implement `type()`, and override `billingTier` by parsing `settings.voice()` with `GoogleVoiceName` and returning the `Full` name's `engine().map(GoogleEngine::canonical)`, or `other` when the engine is unrecognized or the name cannot be parsed (depends on T024)
- [X] T027 [P] [US2] In `src/main/java/multiroom/tts/provider/cloud/GoogleGeminiTtsProvider.java`: implement `type()`, and override `billingTier` to return the entry's configured model (the value it sends as `voice.modelName`) (depends on T024)
- [X] T028 [US2] Implement `synthesisStarted` / `SynthesisTimer` / `audioProduced` in `src/main/java/multiroom/tts/metrics/MicrometerTtsMetrics.java`: `Timer.start(registry)` on start; on `succeeded`/`failed` stop into `Timer.builder("tts.synthesis").serviceLevelObjectives(<buckets>)` with tags `provider`, `type`, `outcome`, `error`, and record the character parts on `DistributionSummary.builder("tts.synthesis.characters").baseUnit("characters")`; `audioProduced` records on `DistributionSummary.builder("tts.synthesis.audio").baseUnit("seconds")`. Compute the bucket list once in the constructor from the longest timeout (makes T022 pass)
- [X] T029 [US2] In `src/main/java/multiroom/tts/service/TtsService.java`: in `synthesize`, build `SynthesisUsage.of(providerName, provider.type(), provider.billingTier(request.settings()), request.text(), request.settings().stylePrompt())` and call `metrics.synthesisStarted(usage)` immediately before `provider.synthesize` — this is the single place characters are counted for every provider type, and the point a future budget check will read — then `succeeded()` after it returns and `failed(e.getErrorCode())` / `failed(TtsErrorCode.PROVIDER_ERROR)` in the two catch blocks (the wrapped `PROVIDER_ERROR` for a non-`TtsException`). In `speak`, after `audioConverter.convert`, compute seconds as `pcm.length / (sampleRate × channels × bytesPerSample)` of `NATIVE_FORMAT` (read the `SampleFormat` accessors in `multiroom-api`) and call `metrics.audioProduced(usage, seconds)`; conversion stays outside the timer, so `synthesize` returns the usage alongside the result (makes T023 pass)

**Checkpoint**: US1 and US2 both work; a scrape shows per-provider latency buckets, characters by
part and tier, and audio seconds.

---

## Phase 5: User Story 3 - Watch the Cache Work (Priority: P2)

**Goal**: `tts_cache_requests_total{outcome=hit|miss}` and the `tts_cache_size_bytes`,
`tts_cache_max_bytes`, `tts_cache_entries` gauges, consistent with `GET /api/tts/cache/stats`.

**Independent Test**: the same announcement twice → hit = 1, miss = 1; the gauges equal
`/api/tts/cache/stats`; after `DELETE /api/tts/cache` they read 0.

### Tests for User Story 3 (write first, see them fail)

- [X] T030 [P] [US3] In `src/test/java/multiroom/tts/metrics/MicrometerTtsMetricsTest.java`: `cacheLookup(true/false)` increments `tts.cache.requests{outcome=hit|miss}`; the gauges `tts.cache.size` (base unit bytes), `tts.cache.max` (bytes) and `tts.cache.entries` read a stubbed `AudioCache.stats()` at read time and follow it when the stub changes (clear → 0)
- [X] T031 [P] [US3] In `src/test/java/multiroom/tts/service/TtsServiceTest.java`: a cache hit calls `cacheLookup(true)` once; a miss calls `cacheLookup(false)` once; a miss whose `audioCache.put` throws `CacheWriteException` still records `miss` and `announcementAccepted`; a request rejected before the lookup (missing target) records no lookup

### Implementation for User Story 3

- [X] T032 [US3] In `src/main/java/multiroom/tts/metrics/MicrometerTtsMetrics.java`: implement `cacheLookup` on counter `tts.cache.requests` (tag `outcome`), and register in the constructor `Gauge.builder("tts.cache.size", audioCache, c -> c.stats().<size>).baseUnit("bytes").strongReference(true)`, likewise `tts.cache.max` and `tts.cache.entries`, using the `CacheStats` accessors in `src/main/java/multiroom/tts/cache/CacheStats.java`. Confirm `FilesystemAudioCache.stats()` reads only the in-memory index (makes T030 pass)
- [X] T033 [US3] In `src/main/java/multiroom/tts/service/TtsService.java`: call `metrics.cacheLookup(cached.isPresent())` right after `audioCache.get(cacheKey)` — an in-memory increment, nothing else on the hit path (makes T031 pass)

**Checkpoint**: US1–US3 work; cache hit ratio and fill are readable from the scrape.

---

## Phase 6: User Story 4 - Spot Backlog and Playback Failures (Priority: P3)

**Goal**: `tts_queue_depth{target, target_type}` per validated target, and
`tts_playbacks_total{outcome=started|failed}`, each playback counted exactly once.

**Independent Test**: three quick speaks to one target → depth 2 while the first plays, then 0;
`started` = 3. A failing `createRoute` → `failed` = 1.

### Tests for User Story 4 (write first, see them fail)

- [X] T034 [P] [US4] In `src/test/java/multiroom/tts/queue/AnnouncementQueueManagerTest.java`: `depth(key)` is 0 for an unknown key, counts waiting tasks, excludes the task the worker has taken and is activating, and returns to 0 once drained
- [X] T035 [P] [US4] In `src/test/java/multiroom/tts/metrics/MicrometerTtsMetricsTest.java`: `trackQueue(SINGLE_OUTPUT, "kitchen", supplier)` registers `tts.queue.depth{target=kitchen,target_type=output}` reading the supplier live; `GROUP` maps to `group`; a second call for the same target registers nothing new and does not replace the supplier; `playbackStarted`/`playbackFailed` increment `tts.playbacks{outcome=started|failed}`
- [X] T036 [P] [US4] In `src/test/java/multiroom/tts/service/TtsServiceTest.java`: after an accepted speak, `trackQueue` is called with the command's target type and name and a supplier returning the queue's depth; a rejected speak (missing target) never calls it; activation whose `createRoute` succeeds → `playbackStarted` once; `createRoute` throws → `playbackFailed` once and no `playbackStarted`; `registerInput` throws before the route step → `playbackFailed` exactly once (from the wrapper), never twice

### Implementation for User Story 4

- [X] T037 [US4] Add `int depth(String targetKey)` to `src/main/java/multiroom/tts/queue/AnnouncementQueueManager.java`: the size of that target's waiting queue, 0 for a key never seen (makes T034 pass)
- [X] T038 [US4] In `src/main/java/multiroom/tts/metrics/MicrometerTtsMetrics.java`: implement `trackQueue` with a `ConcurrentHashMap.newKeySet()` of `targetType + ":" + target`, registering `Gauge.builder("tts.queue.depth", depth, IntSupplier::getAsInt)` with tags `target`, `target_type` (`output` / `group`) and a strong reference only on first sight; implement `playbackStarted`/`playbackFailed` on counter `tts.playbacks` (tag `outcome`) (makes T035 pass)
- [X] T039 [US4] In `src/main/java/multiroom/tts/service/TtsService.java`: after a successful `queueManager.enqueue`, call `metrics.trackQueue(type, name, () -> queueManager.depth(key))`; in `activate`, call `playbackStarted()` right after `createRoute` returns and `playbackFailed()` in its existing catch; construct the queue manager with a wrapped activator that calls `activate` and, if it throws, records `playbackFailed()` and rethrows — `activate`'s inner catch does not rethrow, so nothing counts twice (makes T036 pass)

**Checkpoint**: all four stories work independently.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T040 [P] Create `src/test/java/multiroom/tts/metrics/PrometheusNamesTest.java` with a `PrometheusMeterRegistry`: record one example of every tag combination the code can produce (accepted; rejected with each reason; synthesis success and failure; both character parts; audio; hit and miss; a queue gauge per target type; both playback outcomes), scrape, and assert every series name in [contracts/metrics.md](contracts/metrics.md) is present (`tts_announcements_total`, `tts_synthesis_seconds_{count,sum,max,bucket}`, `tts_synthesis_characters_{count,sum,max}`, `tts_synthesis_audio_seconds_{count,sum,max}`, `tts_cache_requests_total`, `tts_cache_size_bytes`, `tts_cache_max_bytes`, `tts_cache_entries`, `tts_queue_depth`, `tts_playbacks_total`), that each combination appears (none silently dropped for a mismatched tag-key set), and that the `le` bounds match the planned bucket list
- [X] T041 [P] Add a cardinality test to `src/test/java/multiroom/tts/service/TtsServiceTest.java`: a real `MicrometerTtsMetrics` over a `SimpleMeterRegistry`, 10 requests to fixed targets, record `registry.getMeters().size()`, then 10,000 requests with random texts, random voices and random unknown provider names to the same targets; the meter count is unchanged and no meter tag contains any of the random strings. In the same test, configure the provider entry with a recognisable API key and service-account key-file path, and assert that no tag value in the registry contains either
- [X] T042 [P] Write the operator guide `docs/metrics.md` from [contracts/metrics.md](contracts/metrics.md), in operator language: where the metrics are (host's `/actuator/prometheus`, the `prometheus` endpoint must be exposed, `multiroom.tts.enabled=false` and `management.metrics.enable.tts=false` remove them); every metric with its Prometheus name, type, what one increment/sample means and when it is recorded; every tag and its values, including the `unknown` placeholder (and its harmless collision with a provider named `unknown`); the definition of billable usage (text + style prompt, counted only when a provider is called, failures counted but marked); every example query (failure rate by code and reason, timeout rate per provider, average/max/p95/p99 synthesis time and p99 against the provider's `timeout-seconds` (entered by the operator as a constant: the timeout is configuration, not a metric), seconds per 1,000 characters, real-time factor, characters sent per provider per day, characters sent per provider and tier this calendar month (all outcomes, both parts; the month comes from the dashboard's "This month so far" range), cache hit ratio, cache fill, queue depth per target); and how to read them (counters reset on restart/redeploy, but `rate()`/`increase()` compensate, so a restart loses only what was counted since the last scrape and the monthly total is bounded by Prometheus's retention, time per character is a ratio over a window that favours long messages, Gemini characters are only a proxy for its token billing, 405/415 requests appear only in `http_server_requests_seconds`, what is deliberately left out)
- [X] T043 [P] Link `docs/metrics.md` from the documentation table in `README.md` and from `docs/configuration.md` (a short "Metrics" note near the top-level keys)
- [X] T044 [P] Update `docs/future/google-cloud-free-tier-tracking.md` (untracked, listed in `.git/info/exclude`; the change stays local): what 005 provides (the billable-usage definition, the single measurement point in `TtsService.synthesize` before the provider call, the `tier` breakdown, the Prometheus visibility) and what the budget feature still needs (persisted monthly totals, a limit per entry, the choice between rejecting and falling back)
- [X] T045 [P] Fix [quickstart.md](quickstart.md) step 2 to expect the `tts_announcements_total{outcome="accepted"}` series at 0 for each configured provider alongside the cache gauges, matching US1's "present with zero before any announcement"
- [X] T046 [P] Add the `005-tts-metrics` row (version 0.1.4, spec path `specs/005-tts-metrics/`) to the Features table in `AGENTS.md`, and a gotcha entry: "Only `MicrometerTtsMetrics` imports Micrometer; every series of one metric has the same tag keys; tags never come from caller free text"
- [X] T047 Run `mvn verify` (tests + JaCoCo 80% floor); add tests for any new class below the floor
- [X] T048 Validate against a local host per [quickstart.md](quickstart.md) steps 1–5 (`mvn deploy -Plocal`, start `multiroom-core`, `/actuator/extensions` lists `tts` 0.1.4 not `REJECTED`, every story row — including playback actually heard and `tts_playbacks_total{outcome="started"}` rising — every example query returns data — first cause a timeout (a `local-http` entry with `timeout-seconds: 1` pointing at a listener that accepts and never answers) and a full queue (more speaks than `queue.max-depth-per-target`) so the queries that measure them have something to show — both switches, the cache-hit median within 5% of 0.1.3). Do **not** run a bare `mvn deploy` (production)
- [X] T049 **Manual, performed by the user** (production; not run by the agent): the constitution's Principle VII requires any change to the playback path to be verified on the production Raspberry Pi. After T048 passes, the user deploys with a bare `mvn deploy` to `multiroom.lan`, restarts the host, and confirms: `/actuator/extensions` lists `tts` 0.1.4 not `REJECTED`; one announcement to a real output is heard; `curl -s http://multiroom.lan:8080/actuator/prometheus | grep '^tts_'` shows `tts_playbacks_total{outcome="started"}` ≥ 1, the matching `tts_announcements_total{outcome="accepted"}` and the cache gauges (quickstart step 6). The agent prepares the checklist and waits for the user's report

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: none
- **Foundational (Phase 2)**: after Setup; blocks every story
- **US1 (Phase 3)**, **US2 (Phase 4)**, **US3 (Phase 5)**, **US4 (Phase 6)**: each after
  Foundational. They touch the same three files (`MicrometerTtsMetrics`, `TtsService`,
  `TtsServiceTest`), so run them sequentially in priority order unless working on separate branches
- **Polish (Phase 7)**: T040 needs all stories; T041 needs US1 and US2; docs (T042–T046) can start
  once the contract is stable (now) but should be checked against the final code; T047 → T048 →
  T049 (manual, production, by the user) last

### User Story Dependencies

- **US1**: none beyond Foundational
- **US2**: none beyond Foundational (uses `ProviderType.configName()` from T003)
- **US3**: none beyond Foundational
- **US4**: none beyond Foundational

### Within Each Story

- Tests first, and they must fail before the implementation task
- Metrics implementation (`MicrometerTtsMetrics`) before the `TtsService` call sites
- US2: T024 (SPI) before T025–T027 (providers) before T029

---

## Parallel Examples

```text
# Phase 2
T003 ProviderType.configName()      T004 SynthesisUsage      T005 ProviderRegistry.names()
then T006 → (T007 NoopTtsMetrics ‖ T008 MicrometerTtsMetrics skeleton ‖ T011 advice ctor) → T009 → T010

# US1 tests together
T012 AnnouncementQueueManagerTest   T013 MicrometerTtsMetricsTest
T014 TtsServiceTest         T015 TtsExceptionHandlerTest
# US1 implementation
T016 QueueRejectedException → T017 → T018 → T019

# US2 providers after T024
T025 OpenAI/Piper/LocalHttp   T026 GoogleCloud   T027 GoogleGemini

# Polish docs together
T042 docs/metrics.md   T043 links   T044 future doc   T045 quickstart   T046 AGENTS.md
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1 → Phase 2 (the seam; the extension loads in all four host configurations)
2. Phase 3 (US1): announcements accepted/rejected by code, reason and bounded provider
3. **Stop and validate**: `mvn verify`, then quickstart's US1 rows against a local host

### Incremental Delivery

1. US1 → "is TTS working?"
2. US2 → provider latency, reliability and billable characters (the budget groundwork)
3. US3 → cache hit ratio and fill
4. US4 → backlog and playback failures
5. Polish → Prometheus-name and cardinality guards, operator guide, full quickstart

Commit after each task or logical group (Conventional Commits, scope `005-tts-metrics`).
