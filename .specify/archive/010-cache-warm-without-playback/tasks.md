---

description: "Task list for 010-cache-warm-without-playback"
---

# Tasks: Warm The Cache Without Playback

**Input**: Design documents from `/specs/010-cache-warm-without-playback/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/tts-cache-warm-api.yaml](contracts/tts-cache-warm-api.yaml),
[quickstart.md](quickstart.md)

**Tests**: Included. Principle III (Test-First) requires a failing test before production code, so
every implementation task follows the test that drives it. JUnit 5, Mockito, `@WebMvcTest` slices and
Logback log capture (`captureServiceLog` in `TtsServiceTest`); no live provider, no host.

**Organization**: One phase per user story, in the spec's priority order (US1 and US2 P1, US3 P2, US4
P3). US1 builds the miss path, US2 adds the lookup, US3 the failures, US4 the metrics. Offline
playback, a failed write and self-eviction are proved by tests; production checks only that a warmed
phrase is stored silently and heard correctly.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (a different file, no dependency on an incomplete task)
- **[Story]**: the user story the task belongs to (US1–US4)

## Path Conventions

Single Maven module: `src/main/java/multiroom/tts/…`, `src/test/java/multiroom/tts/…`, `docs/` at the
repository root.

## Rules that hold for every task

- **Do not cite spec IDs in code** (`FR-`, `SC-`, `US`, `R` numbers). State the rule in the comment;
  `TTS-010` may be named.
- **One cache identity.** `warm` and `announce` build the `CacheKey` through the same private step.
  Never a second copy of the key construction.
- **Speak does not change.** No existing speak test is edited to make a task pass, apart from T028,
  which changes only the handler the test uses. If one fails, the extraction is wrong.
- **`resolveSettings` stays free of I/O**, and `TtsService.synthesize` stays the one provider call
  site.
- **Only `MicrometerTtsMetrics` imports Micrometer**, and every `tts.cache.warm` series carries the
  same tag keys.
- **No caller text** in a log line, metric tag or error message written here: text length and
  provider only.
- **A warm-up never pins, never queues, never spills to a temporary file.**
- **Production needs the user's permission each time**: `mvn deploy` (no profile), a hub restart, a
  warm-up against production and an audible test. Use `mvn deploy -Plocal` while developing.
- **Windows shell**: read a build's exit code directly (`mvn verify > log; echo $?`), not through a
  pipe. Files stay LF.

---

## Phase 1: Setup

**Purpose**: Record a green baseline and set the version

- [X] T001 Run `mvn verify` on the branch before any change and record the date, test count and result in specs/010-cache-warm-without-playback/verification.md (new file, same shape as .specify/archive/009-route-volume-ducking-level/verification.md)
- [X] T002 Raise `<version>` from 0.1.10 to 0.1.11 in pom.xml, leaving `multiroom.require_api_version` at 0.1.29

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Extract the two steps both paths share from `announce`. A pure refactor: the existing
speak tests are its safety net, so no test is added or changed in this phase.

**⚠️ CRITICAL**: US1 cannot start until this phase is complete

- [X] T003 In src/main/java/multiroom/tts/service/TtsService.java extract the provider and settings resolution from `announce` (provider name and `TtsProvider` from `providerRegistry`, `provider.resolveSettings`, `new CacheKey(…)`, with its comment) into a private method taking the text and the voice fields, returning a small private record `ResolvedAudio(TtsProvider provider, String providerName, SynthesisSettings settings, CacheKey cacheKey)`. `announce` keeps its order: text, playback mode, volume and ducking, target, then this step
- [X] T004 In src/main/java/multiroom/tts/service/TtsService.java extract synthesize, `audioConverter.convert` and `metrics.audioProduced` from `announce`'s miss branch into a private method taking the text and `ResolvedAudio`, returning the PCM `byte[]`. The cache lookup, `metrics.cacheLookup`, the pin and the temporary-file fallback stay in `announce`
- [X] T005 Run `mvn test -Dtest=TtsServiceTest` and confirm it is green with no test file changed

**Checkpoint**: `announce` is shorter, behaves the same, and the shared steps exist

---

## Phase 3: User Story 1 - Prepare an announcement silently (Priority: P1) 🎯 MVP

**Goal**: `POST /api/tts/cache/warm` synthesizes and stores a phrase and plays nothing, and a later
speak of that phrase is a hit.

**Independent Test**: Warm a phrase, confirm no input, route, queue entry or pin was created, make the
provider fail, speak the same phrase and confirm it is served from the cache.

### Tests for User Story 1 ⚠️

> Write these first and see them fail. In warm tests `audioCache.put` must return a file that exists,
> because T023 rejects a path that does not exist, and the shared `cache/hash.wav` stub and
> `useStatefulCache` return such paths. Add a warm-only stateful stub that creates a `@TempDir` file;
> do not change `useStatefulCache`, which the Gemini speak tests share.

- [X] T006 [US1] Add failing tests to src/test/java/multiroom/tts/service/TtsServiceTest.java: warming an uncached phrase calls the provider once, stores the audio through `audioCache.put`, returns `false` for "not a hit", and never touches `deviceRegistryService`, `deviceQueryService`, `routeService`, `completionListener` or `audioCache.pin`
- [X] T007 [US1] Add failing tests to src/test/java/multiroom/tts/service/TtsServiceTest.java using the warm-only stateful stub: after a warm-up, a speak with the same text and settings on any target is `cacheHit: true` and the provider was called once; with the provider then stubbed to throw, the speak still answers `cacheHit: true`; a warm-up that spells a Google voice in another case, or leaves settings to the entry's defaults, gives the same `CacheKey` as the speak (compare keys as `lookedUpKeys` does)
- [X] T008 [US1] Add a failing test to src/test/java/multiroom/tts/service/TtsServiceTest.java with `captureServiceLog`: a warm-up logs its text length and provider, and the text never appears in the log
- [X] T009 [US1] Add failing tests to src/test/java/multiroom/tts/rest/controller/TtsCacheControllerTest.java, with `@MockBean TtsService` added: `POST /api/tts/cache/warm` answers `200` and exactly `{"cacheHit":false}`, with no `announcementId` or `queueDepth`; each of `text`, `providerName`, `voice`, `language`, `engine`, `pitch`, `speakingRate`, `stylePrompt` reaches the `WarmCommand`; a body that also carries `targetName`, `playbackMode`, `routeVolume`, `duckingLevelDb` and an unparseable `targetType` still answers 200

### Implementation for User Story 1

- [X] T010 [P] [US1] Create src/main/java/multiroom/tts/service/WarmCommand.java: a record of `text`, `providerName`, `voice`, `language`, `engine`, `pitch`, `speakingRate`, `stylePrompt`, so `service` does not depend on `rest`, as `AnnounceCommand` does. Javadoc on the type
- [X] T011 [US1] Add public `boolean warm(WarmCommand command)` to src/main/java/multiroom/tts/service/TtsService.java, returning whether the audio was already stored: validate the text, run the resolve step from T003, run the synthesize step from T004, `audioCache.put`, log `TTS_CACHE_WARMED text.length={} provider={} cacheHit={}` at INFO. US2 adds the lookup and US3 the failures. Confirm T006–T008 pass
- [X] T012 [P] [US1] Create src/main/java/multiroom/tts/rest/dto/WarmRequest.java: a record annotated `@JsonIgnoreProperties(ignoreUnknown = true)` with `@NotBlank(message = "Text must not be blank") String text`, `String providerName`, `voice`, `language`, `engine`, `Double pitch`, `Double speakingRate`, `String stylePrompt`. Javadoc says the maximum length is enforced by `TtsService`, as `SpeakRequest`'s does, and that speak-only fields are ignored, so a speak body can be reused
- [X] T013 [P] [US1] Create src/main/java/multiroom/tts/rest/dto/WarmResponse.java: a record with `boolean cacheHit`, annotated `@Schema` as "true if the audio was already stored and no provider was called", with Javadoc on the type
- [X] T014 [US1] Add `@PostMapping("/warm") WarmResponse warm(@Valid @RequestBody WarmRequest request)` to src/main/java/multiroom/tts/rest/api/TtsCacheApi.java with `operationId = "warmCache"` and `@ApiResponses` for 200, 400 and 503 matching contracts/tts-cache-warm-api.yaml, including that the extension does not rate limit, and update the interface's Javadoc line
- [X] T015 [US1] Add `TtsService` to the constructor of src/main/java/multiroom/tts/rest/controller/TtsCacheController.java and implement `warm`, mapping `WarmRequest` to `WarmCommand` and the result to `WarmResponse`. Update the class Javadoc. Confirm T009 passes and `TtsAutoConfigurationTest` still finds the single controller bean

**Checkpoint**: A phrase can be stored silently and spoken from the cache. Every warm-up still calls
the provider until US2

---

## Phase 4: User Story 2 - Re-warm on a schedule at no cost (Priority: P1)

**Goal**: A phrase already stored costs nothing and counts as just used, and only missing phrases
reach the provider.

**Independent Test**: Warm a phrase twice and confirm the second answer is a hit, with no provider
call and a refreshed last-used time.

### Tests for User Story 2 ⚠️

- [X] T016 [US2] Add failing tests to src/test/java/multiroom/tts/service/TtsServiceTest.java: warming a cached phrase returns `true`, looks it up once through `audioCache.get` with the speak's key, and makes no call to `synthesize`, `audioCache.put` or `audioCache.pin`; with the warm-only stateful stub, a phrase removed from the cache is synthesized again; warming a list where one phrase's provider call fails, then warming the whole list again, calls `synthesize` only for that phrase on the second run
- [X] T017 [P] [US2] Add tests to src/test/java/multiroom/tts/cache/FilesystemAudioCacheTest.java pinning what warming relies on, unless an existing test already covers each: a `get` hit protects an entry from the next eviction; an entry removed while pinned is a miss, and a `put` of it survives the later `unpin`

### Implementation for User Story 2

- [X] T018 [US2] In `warm` in src/main/java/multiroom/tts/service/TtsService.java, after the resolve step, call `audioCache.get(cacheKey)` and on a hit log and return `true` with no provider call. Do not call `metrics.cacheLookup`, whose metric is defined as lookups made for announcements. Confirm T016 passes

**Checkpoint**: US1 and US2 together are the usable feature: the whole list can be re-warmed for free

---

## Phase 5: User Story 3 - Know that a warm-up failed (Priority: P2)

**Goal**: A warm-up that stored nothing never answers success, and refusals match speak.

**Independent Test**: Make the provider fail, and separately make the cache unwritable, and confirm
each answers an error and leaves nothing behind.

### Tests for User Story 3 ⚠️

- [X] T019 [US3] Add failing tests to src/test/java/multiroom/tts/service/TtsServiceTest.java: `audioCache.put` throwing `CacheWriteException` raises `TtsException(CACHE_WRITE_FAILED)` whose message carries no path or exception text, and no `multiroom-tts-*.wav` appears in `java.io.tmpdir`; `put` returning a path that does not exist raises the same error; `PROVIDER_TIMEOUT`, `PROVIDER_RATE_LIMITED` and `PROVIDER_ERROR` from the provider pass through unchanged after one call, with no `put`
- [X] T020 [US3] Add tests to src/test/java/multiroom/tts/service/TtsServiceTest.java: blank text and text over `max-text-length` answer `INVALID_REQUEST`, an unknown provider `PROVIDER_NOT_FOUND`, and a setting the provider rejects the same code as speak, all with no `synthesize` call and no `audioCache` call
- [X] T021 [P] [US3] Add a test to src/test/java/multiroom/tts/cache/FilesystemAudioCacheTest.java pinning the premise of the path check: an entry larger than the cap, and a new entry in a cache otherwise full of pinned entries, are evicted by their own `put`, which returns a path to a deleted file
- [X] T022 [US3] Add tests to src/test/java/multiroom/tts/rest/controller/TtsCacheControllerTest.java: a failing test that `TtsException(CACHE_WRITE_FAILED)` from the service answers 503 with both `error` and `message`, which cannot compile until T023; and tests pinning that `POST /api/tts/cache/warm` with blank text or an unreadable body answers `400 INVALID_REQUEST`, which pass already because the handler and `@Valid` exist

### Implementation for User Story 3

- [X] T023 [US3] Add `CACHE_WRITE_FAILED` to src/main/java/multiroom/tts/TtsErrorCode.java with Javadoc: the audio is not in the cache, and repeating the request is safe. The handler needs no change, because it sends everything outside `CALLER_FIXABLE` to 503. In the same change add the code to the 503 arm of `expectedStatus` in src/test/java/multiroom/tts/rest/advice/TtsExceptionHandlerTest.java, whose switch is exhaustive and would otherwise stop compiling. In `warm` in src/main/java/multiroom/tts/service/TtsService.java, catch `CacheWriteException` from `put` and also check `Files.exists` on the returned path, with a comment saying an entry can be evicted by its own store; either case throws `TtsException(CACHE_WRITE_FAILED, …)` with one fixed sentence and logs the cause at WARN. Confirm T019–T022 pass

**Checkpoint**: Every warm-up either answers 200 with the entry stored, or an error

---

## Phase 6: User Story 4 - Watch warm-ups apart from announcements (Priority: P3)

**Goal**: Every answered warm-up is counted once in `tts_cache_warm_total`, and the announcement and
cache lookup metrics do not move.

**Independent Test**: Run a warm-up and confirm the warm-up count rises, the announcement and cache
lookup counts do not, and a miss adds to the characters sent.

### Tests for User Story 4 ⚠️

- [X] T024 [P] [US4] Add failing tests to src/test/java/multiroom/tts/metrics/MicrometerTtsMetricsTest.java: `cacheWarmed(provider, hit)` and `cacheWarmFailed(provider, error)` increment `tts.cache.warm` with tags `outcome` (`hit`, `synthesized`, `failed`), `error` (`none`, a code or `INTERNAL`) and `provider` (a configured name or `unknown`); `hit` and `synthesized` series of each configured provider exist at 0 after construction; a failing registry is swallowed
- [X] T025 [P] [US4] Add the no-op calls to src/test/java/multiroom/tts/metrics/NoopTtsMetricsTest.java
- [X] T026 [P] [US4] Add to src/test/java/multiroom/tts/metrics/PrometheusNamesTest.java the name `tts_cache_warm_total`, the scrape lines for all three outcomes (with one failed `CACHE_WRITE_FAILED` and one failed `INVALID_REQUEST` on `unknown`), and the calls that produce them
- [X] T027 [US4] Add failing tests to src/test/java/multiroom/tts/service/TtsServiceTest.java: each outcome in the table in data-model.md counts exactly once with its provider tag (an unconfigured name is `unknown`, an unnamed provider is the default); an unexpected `RuntimeException` counts `failed`, `INTERNAL` and is rethrown; a warm-up calls no `announcementAccepted`, `announcementRejected`, `cacheLookup`, `trackQueue` or playback method; a miss calls `synthesisStarted` and `audioProduced`, and a hit neither
- [X] T028 [US4] In src/test/java/multiroom/tts/rest/advice/TtsExceptionHandlerTest.java, add failing tests that an invalid or unreadable warm body calls `cacheWarmFailed(UNKNOWN, INVALID_REQUEST)` once and no announcement metric. Retarget `aFailureOnAnotherControllerIsNotAnAnnouncement` from `TtsCacheController.clearCache` to a `TtsVoiceController` method: its `verifyNoInteractions` would otherwise fail, because the handler now records for every `TtsCacheApi` handler

### Implementation for User Story 4

- [X] T029 [US4] Add `cacheWarmed(String providerTag, boolean hit)` and `cacheWarmFailed(String providerTag, String error)`, with Javadoc, to src/main/java/multiroom/tts/metrics/TtsMetrics.java, and no-ops to src/main/java/multiroom/tts/metrics/NoopTtsMetrics.java
- [X] T030 [US4] In src/main/java/multiroom/tts/metrics/MicrometerTtsMetrics.java add the constant `CACHE_WARM = "tts.cache.warm"`, a private counter builder with one tag-key set (`outcome`, `error`, `provider`) and the description "Warm-up requests answered, by outcome, error code and provider", the two methods through `record(…)`, and pre-register `hit` and `synthesized` per provider in the constructor. Update the constructor's Javadoc
- [X] T031 [US4] In src/main/java/multiroom/tts/service/TtsService.java wrap the work as `speak` does: public `warm` takes `providerTag(command.providerName())`, calls a private method holding the current body, and in one place records `cacheWarmed`, or `cacheWarmFailed` with the `TtsException` code or `TtsMetrics.INTERNAL` before rethrowing. Confirm T027 passes
- [X] T032 [US4] In src/main/java/multiroom/tts/rest/advice/TtsExceptionHandler.java add to `invalidRequest` a second branch for a handler assignable to `TtsCacheApi`, calling `metrics.cacheWarmFailed(TtsMetrics.UNKNOWN, TtsErrorCode.INVALID_REQUEST.name())`, and update its Javadoc: only `warm` has a body in that interface. Confirm T028 passes

**Checkpoint**: All four stories work. Warm-ups are visible and do not disturb the hit ratio

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Documentation, the merge gate and production validation

- [X] T033 [P] Update the class Javadoc of src/main/java/multiroom/tts/service/TtsService.java so it covers `warm` as well as announcements
- [X] T034 [P] In docs/configuration.md "Managing the cache" (line 497), document `POST /api/tts/cache/warm` with an example and the caller's rules: warm the exact text that is later spoken; warm again after changing a voice, language, style prompt or default provider; name the provider, because the default may be billed; pace requests, because the extension has no rate limiter; set the HTTP timeout above the provider's `timeout-seconds`; re-warm on a schedule, because the cache evicts by least recent use and nothing keeps an entry, and keep that schedule modest, because every hit rewrites `index.json` on disk. Describe `CACHE_WRITE_FAILED` (503) in this section, since the file has no list of error codes: the audio is not in the cache, and repeating the request is safe
- [X] T035 [P] In docs/metrics.md add a `tts_cache_warm_total` section after `tts_cache_requests_total`, say that `tts_cache_requests_total` and `tts_announcements_total` exclude warm-ups while the synthesis metrics include them, and add an example query for warm-up cost
- [X] T036 Run `mvn verify > "$TEMP/verify.log"; echo $?`, read the exit code, confirm `git diff master -- src/test` changes no speak test beyond T028's retargeting, and confirm `git ls-files --eol` shows no `w/crlf`. Record date, test count and result in specs/010-cache-warm-without-playback/verification.md
- [X] T037 Confirm with `grep -rnE "\b(FR|SC|US)-?[0-9]" src` that no spec ID landed in code
- [X] T038 **With the user's permission**: deploy the JAR with `mvn deploy`, restart `multiroom.service`, and confirm `GET /actuator/extensions` lists `tts` at 0.1.11, not `REJECTED`, as in quickstart.md section 2. Record it in specs/010-cache-warm-without-playback/verification.md
- [X] T039 **With the user's permission**: run quickstart.md sections 3.1, 3.2 and 4 against `multiroom.lan` with `piper-local`, after T038. This is silent and costs nothing, but it writes one entry to production's cache. Record the results in specs/010-cache-warm-without-playback/verification.md
- [X] T040 **With the user's permission**: run quickstart.md sections 3.3 and 3.4, the audible check required by Principle VII: record the output's state, keep the volume low, speak the warmed phrase on the one output agreed with the user, confirm `cacheHit: true` and correct audio, then check that nothing was left open and restore the state. Record it in specs/010-cache-warm-without-playback/verification.md

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies
- **Foundational (Phase 2)**: after Setup; blocks US1
- **US1 (Phase 3)**: after Foundational
- **US2 and US3 (Phases 4 and 5)**: after US1, which creates `warm`; either order
- **US4 (Phase 6)**: after US1; best after US3, so T027 covers every outcome
- **Polish (Phase 7)**: after every story. T038 → T039 → T040 in order

### User Story Dependencies

Stories share one method, so they are not independent in code: US2, US3 and US4 each extend the `warm`
that US1 creates. Each is still independently testable, because each phase's tests exercise one
behaviour through its own mocks.

### Within Each User Story

- Tests are written and seen to fail before implementation
- Records and DTOs, then the service, then the API and controller

### Parallel Opportunities

- T010, T012 and T013 create separate new files
- T017 and T021 touch only `FilesystemAudioCacheTest`, independently of the `TtsServiceTest` tasks
- T024, T025 and T026 are separate test files
- T033, T034 and T035 are separate files
- Most other tasks edit `TtsService`, `TtsServiceTest` or `TtsCacheControllerTest` and run in sequence

---

## Parallel Example: User Story 4

```bash
Task: "Add failing tests to src/test/java/multiroom/tts/metrics/MicrometerTtsMetricsTest.java"
Task: "Add the no-op calls to src/test/java/multiroom/tts/metrics/NoopTtsMetricsTest.java"
Task: "Add tts_cache_warm_total to src/test/java/multiroom/tts/metrics/PrometheusNamesTest.java"
```

---

## Implementation Strategy

### MVP First

US1 and US2 are both P1 and belong together: without US2 every warm-up pays the provider, so a
scheduled re-warm would be billed. The MVP is Phases 1–4, but it is not shippable: without US3 a
failed store could answer success.

### Incremental Delivery

1. Phases 1–2: the refactor, green with no test changed
2. US1 → US2: the working endpoint
3. US3: no false success
4. US4: visible and separate from announcements
5. Phase 7: documentation, merge gate, production validation

Ship all four stories together as 0.1.11.

---

## Review, merge and release

Not implementation tasks. Commits, the push and the tag happen when the user asks.

- Open a pull request for review (Principles V and VI); it merges after both parts of the merge gate
  pass
- Tag the merge `0.1.11` (Principle VI)
- Add the `010-cache-warm-without-playback` row to the Features table in `AGENTS.md`
- Delete `docs/backlog/cache-warm-without-playback.md` and its row in `docs/backlog/INDEX.md`
- Run `/speckit-archive-run`
