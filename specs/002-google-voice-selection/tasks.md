---

description: "Task list for 002-google-voice-selection"
---

# Tasks: Google Cloud Voice Selection

**Input**: Design documents from `/specs/002-google-voice-selection/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/tts-rest-api.yaml](contracts/tts-rest-api.yaml),
[contracts/configuration.md](contracts/configuration.md), [quickstart.md](quickstart.md)

**Tests**: **Required.** Constitution III mandates test-first: each test task comes before the
implementation it covers, and it must fail (red) before that implementation starts. No test calls
real Google or needs a key. Use WireMock for HTTP and an injected `java.time.Clock` for time.

**Organization**: Tasks are grouped by user story. Story order follows spec priority: US1 and US2
(P1), US3 (P2), US4 and US5 (P3).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: The user story (US1–US5) the task serves
- Paths are relative to the repository root. Main code is `src/main/java/multiroom/tts/`, and tests
  are `src/test/java/multiroom/tts/`

## Conventions every task follows

- A malformed configuration is a start-up fault: `IllegalStateException("multiroom-tts: provider '<name>' …")`,
  raised through `TtsProperties.fault(...)`. It never makes a network call (Constitution VIII).
- A caller error is `new TtsException(TtsErrorCode.INVALID_REQUEST, message)`, or
  `TtsErrorCode.INVALID_VOICE` for a voice missing from the catalogue (US3). Only
  `TtsExceptionHandler` maps error codes to HTTP statuses (FR-021).
- Match the surrounding style: 4-space indent, existing import order, Javadoc on public types,
  and comments that explain *why*.

---

## Phase 1: Setup

**Purpose**: Version and contract bookkeeping. No behaviour change.

- [X] T001 In `pom.xml`, bump `<version>` from `0.1.0` to `0.1.1`, and leave `multiroom.require_api_version` at `0.1.18`: this feature makes no API change (plan.md, "Depends on"). Also make the constitution's 80% coverage floor (III) a build gate. Add `org.jacoco:jacoco-maven-plugin` `0.8.11`, the version `multiroom-ai`'s root POM manages. Pin it explicitly, because the starter does not manage it. Configure three executions:
  - `prepare-agent`;
  - `report` in phase `verify`;
  - `check` in phase `verify`, with a `BUNDLE` rule of `LINE` `COVEREDRATIO` minimum `0.80`.

  It is a build plugin, not a dependency, so nothing is shaded into the JAR. The inherited surefire configuration sets no `argLine`, so the agent attaches without an `@{argLine}` merge. Run `mvn verify` on the unchanged code first and record the baseline coverage in the commit message. If 001's code is already below 80%, stop and report it rather than lowering the threshold.
- [X] T002 [P] Update the Javadoc of `src/main/java/multiroom/tts/TtsErrorCode.java` so that it points at `specs/002-google-voice-selection/contracts/tts-rest-api.yaml` (v0.1.1, which matches the JAR version from now on) as the published error-code set. That file supersedes 001's independently numbered v0.3.0.

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: Change the provider SPI so that each provider resolves its own voice, language, engine
and audio settings **before** the cache lookup (research R5). Add the pure Google name grammar that
US1 and US2 both build on. At the end of this phase, observable behaviour for every provider,
Google included, is **identical to 001**. The whole existing suite stays green.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Tests for the foundation (write first, must fail)

- [X] T003 [P] Write `src/test/java/multiroom/tts/provider/DefaultSettingsResolutionTest.java`. It checks the 001 rule (research R6): the request voice wins over the config voice; the language is request, then config, then `"en-US"`; the engine comes from config; and `voiceKey` equals `voice`. It also checks that a non-null `engine`, `pitch` or `speakingRate` override throws `INVALID_REQUEST`, with a message that names the field and the provider type, e.g. `"Field 'pitch' is not supported by provider 'piper-local' of type LOCAL_HTTP"` (FR-020).
- [X] T004 [P] Write `src/test/java/multiroom/tts/cache/CacheKeyTest.java`:
  - For null `pitch` and `speakingRate`, `toHash()` equals the 001 hash for the same fields. Pin a literal SHA-256 computed from 001's `String.join("|", …)`.
  - A non-null `pitch` changes the hash, and so does a non-null `speakingRate`.
  - `1.10` and `1.1` hash the same (`BigDecimal.stripTrailingZeros().toPlainString()`, research R7).
- [X] T005 [P] Write `src/test/java/multiroom/tts/provider/cloud/google/GoogleEngineTest.java`. `GoogleEngine.fromName` should resolve every alias in the data-model table (`wavenet`, `WaveNet`, `chirp3-hd`, `Chirp3_HD`, `chirp3hd`, `chirp-hd`, `NEURAL2`, …) to the right constant, and each constant's `canonical()` should give `Standard`, `Wavenet`, `Neural2`, `Studio`, `Chirp-HD`, `Chirp3-HD`. `chirp4`, `polyglot`, empty and null should all give `Optional.empty()`. `supportedList()` should render `"Standard, Wavenet, Neural2, Studio, Chirp-HD, Chirp3-HD"`.
- [X] T006 [P] Write `src/test/java/multiroom/tts/provider/cloud/google/GoogleLanguageTest.java`:
  - `parse` canonicalizes `uk-ua` → `uk-UA`, `CMN-cn` → `cmn-CN`, `es-419` → `es-419`.
  - It rejects `ukrainian`, `uk`, `uk_UA`, `uk-UAA` and the empty string with `IllegalArgumentException`.
  - `equals` ignores case.
- [X] T007 [P] Write `src/test/java/multiroom/tts/provider/cloud/google/GoogleVoiceNameTest.java`:
  - `parse("uk-ua-chirp3-hd-charon")` gives `Full(uk-UA, "chirp3-hd", "charon")`, and its rule-canonical `toString()` is `uk-UA-Chirp3-HD-Charon`.
  - `en-US-Chirp-HD-F` gives engine `Chirp-HD` and voice `F`.
  - `en-US-Polyglot-1` gives the unrecognized engine segment `Polyglot` kept as written, voice `1`, and `engine()` empty.
  - `en-us-news-k` canonicalizes to `en-US-news-K` (segment as given, FR-004).
  - `charon` gives `Short`, canonical `Charon`.
  - `Short("d").compose(WAVENET, en-US)` gives `en-US-Wavenet-D`.
  - `uk-UA-Charon`, `uk-UA-`, `-Charon`, `Char on`, `Charon!` and the empty string are malformed (`IllegalArgumentException`).
- [X] T008 [P] Extend `src/test/java/multiroom/tts/rest/TtsControllerTest.java`. A `POST /api/tts/speak` with `"engine":"neural2"`, `"pitch":-2.0` and `"speakingRate":0.9` hands `TtsService.speak` an `AnnounceCommand` (captured with an `ArgumentCaptor`) whose `engine()`, `pitch()` and `speakingRate()` are exactly those values. Use distinct pitch and rate values, so that a swapped mapping fails. A request without the three fields gives `null` for each. It fails to compile until T016 adds the fields.

### Implementation of the foundation

- [X] T009 Create `src/main/java/multiroom/tts/provider/RequestedSettings.java`, a record `(String voice, String language, String engine, Double pitch, Double speakingRate)`, all nullable, with Javadoc ("per-request overrides; null means use the entry's configured value"). Also create `src/main/java/multiroom/tts/provider/SynthesisSettings.java`, a record `(String voice, String voiceKey, String requestedVoice, String language, String engine, Double pitch, Double speakingRate, Double pitchKey, Double speakingRateKey)`. Its Javadoc must explain `voiceKey` per data-model.md (equal for any two spellings that give the same audio), `requestedVoice` (the voice as the caller or config wrote it, for error messages only, and **never** part of the cache key), and `pitchKey`/`speakingRateKey` (the cache-key forms of `pitch`/`speakingRate`, `null` when the provider considers the value neutral; computed by the provider so that `TtsService` never knows any provider's defaults, Principle IV). Add a static factory `SynthesisSettings.of(voice, language, engine)` that sets `voiceKey = requestedVoice = voice` and leaves pitch, rate and both keys null.
- [X] T010 Change `src/main/java/multiroom/tts/provider/SynthesisRequest.java` to the record `(String text, SynthesisSettings settings, int targetSampleRate, int targetChannels)`. Keep convenience accessors `voice()` and `language()` that delegate to `settings`, so the OpenAI, Piper and local HTTP provider bodies compile unchanged. Update every `new SynthesisRequest(...)` in `src/test/java/multiroom/tts/provider/*ProviderTest.java` and `src/test/java/multiroom/tts/service/TtsServiceTest.java` to use `SynthesisSettings.of(...)`.
- [X] T011 Add `SynthesisSettings resolveSettings(RequestedSettings requested)` to `src/main/java/multiroom/tts/provider/TtsProvider.java`. Its Javadoc contract: pure, **no I/O**, called on every request before the cache lookup, and throws `TtsException(INVALID_REQUEST)` for caller errors (research R5).
- [X] T012 Implement `src/main/java/multiroom/tts/provider/DefaultSettingsResolution.java` (makes T003 pass). It is a final utility with `static SynthesisSettings resolve(TtsProviderConfig config, RequestedSettings requested)`. It rejects the Google-only fields `engine`, `pitch` and `speakingRate` (FR-020), then applies the 001 rule, including the `"en-US"` fallback.
- [X] T013 Make `OpenAiTtsProvider`, `PiperTtsProvider` and `LocalHttpTtsProvider` (`src/main/java/multiroom/tts/provider/cloud/OpenAiTtsProvider.java`, `src/main/java/multiroom/tts/provider/local/PiperTtsProvider.java`, `src/main/java/multiroom/tts/provider/local/LocalHttpTtsProvider.java`) keep their `TtsProviderConfig` and implement `resolveSettings` by delegating to `DefaultSettingsResolution.resolve(config, requested)`. In `src/main/java/multiroom/tts/provider/cloud/GoogleCloudTtsProvider.java`, delegate the same way **for now**. US1 replaces this, and until then Google behaves exactly as in 001.
- [X] T014 In `src/main/java/multiroom/tts/config/TtsProviderConfig.java`, remove the `"en-US"` initialiser from `language` (the default is now `null`; the fallback lives in `DefaultSettingsResolution`, research R6). Rewrite its Javadoc to say so. Also rewrite the `engine` Javadoc: for `google-cloud` it is the default engine for short names, not a free-text label.
- [X] T015 Extend `src/main/java/multiroom/tts/cache/CacheKey.java` (makes T004 pass). Add components `Double pitch, Double speakingRate` after `language`. Append `|p=<pitch>` and `|r=<rate>` to the hashed string **only when non-null**, formatted with `BigDecimal.valueOf(x).stripTrailingZeros().toPlainString()`. Keep a 6-argument convenience constructor (pitch and rate null) so that `FilesystemAudioCacheTest`, `PlaybackCompletionListenerTest` and the index code compile unchanged. Update the record Javadoc.
- [X] T016 Extend `src/main/java/multiroom/tts/rest/SpeakRequest.java` with the optional `String engine, Double pitch, Double speakingRate` (after `language`), and `src/main/java/multiroom/tts/service/AnnounceCommand.java` with the same three fields. Update `src/main/java/multiroom/tts/rest/TtsController.java` to map them (makes T008 pass). Update the `new AnnounceCommand(...)` calls in `src/test/java/multiroom/tts/rest/TtsControllerTest.java` and `src/test/java/multiroom/tts/service/TtsServiceTest.java`, passing `null`s.
- [X] T017 Rewire `src/main/java/multiroom/tts/service/TtsService.java#speak`. Build `RequestedSettings` from the command and call `provider.resolveSettings(...)` **before** the cache lookup. Build the `CacheKey` from the result (`requestedVoice` is deliberately **not** a component): `voice ← settings.voiceKey()`, `engineName ← settings.engine()`, `language`, `pitch ← settings.pitchKey()`, `speakingRate ← settings.speakingRateKey()`. `TtsService` copies these values and never interprets them (Principle IV: no provider's defaults in the playback path). Build `new SynthesisRequest(text, settings, …)` from the same settings. Delete the inline voice/language/engine defaulting (lines 105–107 today). Keep `providerConfigsByName` only if it is still used. In `TtsServiceTest`, stub `resolveSettings` on the mocked providers (for example, `when(openaiProvider.resolveSettings(any())).thenAnswer(inv -> DefaultSettingsResolution.resolve(openaiConfig, inv.getArgument(0)))`). Add one test showing that a `pitch` override on an OpenAI provider throws `INVALID_REQUEST` and makes no synthesis call.
- [X] T018 [P] Implement `src/main/java/multiroom/tts/provider/cloud/google/GoogleEngine.java` (makes T005 pass): an enum with `canonical()`, `static Optional<GoogleEngine> fromName(String)` (folds case and removes `-` and `_`), and `static String supportedList()`.
- [X] T019 [P] Implement `src/main/java/multiroom/tts/provider/cloud/google/GoogleLanguage.java` (makes T006 pass): a record wrapping the canonical tag, `static GoogleLanguage parse(String)`, `static boolean isWellFormed(String)`, and the pattern `[A-Za-z]{2,3}-([A-Za-z]{2}|[0-9]{3})` (research R3).
- [X] T020 Implement `src/main/java/multiroom/tts/provider/cloud/google/GoogleVoiceName.java` (makes T007 pass). It is a sealed interface with records `Full(GoogleLanguage language, String engineSegment, String voice)` and `Short(String voice)`, and provides:
  - `static GoogleVoiceName parse(String)`, which splits the voice at the **last** hyphen (research R3).
  - `Full.engine()` returning `Optional<GoogleEngine>`.
  - `Full.canonical()`: the canonical engine spelling or the segment as given, and the voice with its first letter uppercased.
  - `Short.compose(GoogleEngine, GoogleLanguage)` returning a `Full`.
  - `static boolean isWellFormed(String)` for start-up checks.

  Depends on T018 and T019.

**Checkpoint**: `mvn verify` is green. Behaviour is unchanged for every provider. Google-only request
fields are rejected for **every** provider type, Google included, until US2 and US4 enable them.

---

## Phase 3: User Story 1 — A Google failure explains itself, and a full voice name just works (P1) 🎯 MVP

**Goal**: The 2026-09-24 incident request (`uk-UA-Chirp3-HD-Charon`, no language) plays. Every
Google rejection carries Google's own message.

**Independent test**: Using WireMock, check that a full uk-UA voice with no language sends
`languageCode: uk-UA`. Check that a 400 with an error body surfaces that body's `error.message` in
the `TtsException`. Check that an explicit conflicting language is a 400 with no HTTP call.

### Tests for User Story 1 (write first, must fail)

- [X] T021 [P] [US1] Write `src/test/java/multiroom/tts/provider/cloud/google/GoogleErrorBodyTest.java`. For `{"error":{"code":400,"message":"Requested language code 'en-US' doesn't match…","status":"INVALID_ARGUMENT"}}`, `message(...)` should return that message. For empty, non-JSON, `{}` and `{"error":{}}`, it should return `Optional.empty()`. A 2,000-character message should be truncated to 500 characters plus `…`.
- [X] T022 [P] [US1] Write `src/test/java/multiroom/tts/provider/cloud/google/GoogleVoiceResolverTest.java` for full-name resolution (data-model "Resolution algorithm", steps 2, 4, 5, 6 and 9):
  - US1-1: an entry with no language plus request voice `uk-UA-Chirp3-HD-Charon` gives language `uk-UA`, voice `uk-UA-Chirp3-HD-Charon`, `requestedVoice` equal to the request string as sent, and `voiceKey` `uk-ua-chirp3-hd-charon`.
  - US1-2: an entry language `en-US` plus the same voice still gives `uk-UA`.
  - US1-3: request language `en-US` plus that voice throws `INVALID_REQUEST`, and the message contains both `en-US` and `uk-UA`.
  - A request language `uk-ua` with that voice is accepted (case-insensitive).
  - With no voice anywhere, the language is request, then config, then `en-US`.
  - A configured full voice with a request language that contradicts it is `INVALID_REQUEST`.
  - A malformed request voice (`uk-UA-Charon`) is `INVALID_REQUEST`.
  - A malformed request language (`ukrainian`) is `INVALID_REQUEST`.
  - `engine` in the result is always `null` (research R7).
- [X] T023 [US1] Extend `src/test/java/multiroom/tts/provider/GoogleCloudTtsProviderTest.java`. Switch the test constructor to the new base-URI form, `new GoogleCloudTtsProvider(config, catalogueProps, URI.create(server.baseUrl() + "/v1/"), Clock)` (see T027). For now, stub `GET /v1/voices` to return 503, so the catalogue is unavailable and the check is skipped until US3. Add these tests:
  - (a) A 400 with a Google error body gives `PROVIDER_ERROR`, and the message is `Provider 'google-cloud' returned HTTP 400: <google message>`. `google-cloud` here is the entry **name** from the test config (research R11).
  - (b) A 500 with an HTML body gives a message ending in `HTTP 500` with no suffix.
  - (c) A 429 with a body gives `PROVIDER_RATE_LIMITED`, and the message includes Google's text.
  - (d) The request body sent for settings with language `uk-UA` has `voice.languageCode == "uk-UA"`. Verify this with WireMock `matchingJsonPath`.
  - (e) `resolveSettings` makes no HTTP call.
- [X] T024 [P] [US1] Extend `src/test/java/multiroom/tts/config/TtsPropertiesValidationTest.java`:
  - (a) The **exact production entry** passes and makes no network call: `name: google`, `type: GOOGLE_CLOUD`, `api-key`, `language: en-US`, `voice: en-US-Neural2-C`, `timeout-seconds: 10`, no engine (SC-004, research R12).
  - (b) A `google-cloud` entry with a malformed `language` (`english`) fails, naming the entry and the value.
  - (c) A malformed `voice` (`uk-UA-Charon`) fails, naming the entry and the value.
  - (d) A `language` that contradicts a full `voice` starts (warning only, no fault).
  - (e) A `LOCAL_HTTP` entry with no language still starts.

### Implementation for User Story 1

- [X] T025 [P] [US1] Implement `src/main/java/multiroom/tts/provider/cloud/google/GoogleErrorBody.java` (makes T021 pass): `static Optional<String> message(String body)` using Jackson `readTree`, with truncation to 500 characters. It never throws.
- [X] T026 [US1] Implement `src/main/java/multiroom/tts/provider/cloud/google/GoogleVoiceResolver.java`. Its constructor is `(TtsProviderConfig config)`, and it has `SynthesisSettings resolve(RequestedSettings requested)`. Cover the full-name and no-voice paths of the data-model algorithm (steps 2, 4, 5, 6 and 9). For a **short** name, temporarily throw `INVALID_REQUEST` ("short voice names need an engine"); US2 completes this. Still reject `engine`, `pitch` and `speakingRate` with the FR-020 message; US2 and US4 lift these. All conflict messages follow `contracts/tts-rest-api.yaml` examples. Makes T022 pass.
- [X] T027 [US1] Rework `src/main/java/multiroom/tts/provider/cloud/GoogleCloudTtsProvider.java`:
  - Change the constructors to `(TtsProviderConfig, VoiceCatalogueProperties)` and, for tests, `(TtsProviderConfig, VoiceCatalogueProperties, URI apiBase, Clock)`. The endpoints are `apiBase.resolve("text:synthesize")` and `apiBase.resolve("voices")`, and the default `apiBase` is `https://texttospeech.googleapis.com/v1/` (FR-016).
  - Keep `config.getName()` for messages.
  - `resolveSettings` delegates to `GoogleVoiceResolver`.
  - On a non-2xx response, append `": " + GoogleErrorBody.message(body)` when present, for both 429 and other codes, and use the entry name in place of the literal `'google-cloud'` (research R11).
  - Only send `voice.name` when the voice is non-null.

  Create the minimal `src/main/java/multiroom/tts/config/VoiceCatalogueProperties.java` needed to compile: `@Data`, with `Duration ttl = Duration.ofHours(24)`, `failureBackoff = Duration.ofSeconds(60)` and `fetchTimeout = Duration.ofSeconds(3)`. Add `private VoiceCatalogueProperties voiceCatalogue = new VoiceCatalogueProperties();` to `TtsProperties`, and pass `properties.getVoiceCatalogue()` in `TtsAutoConfiguration.buildProvider`. Makes T023 pass.
- [X] T028 [US1] Extend `src/main/java/multiroom/tts/config/TtsProperties.java#validateProvider` for `GOOGLE_CLOUD` (makes T024 pass). Faults for a non-null `language` that is not `GoogleLanguage.isWellFormed`, and for a non-null `voice` that is not `GoogleVoiceName.isWellFormed`. Log a `warn` (not a fault) when a configured language contradicts a configured full voice, saying that the configured voice is synthesized in its own language and that `language` remains the default for short names. No network call.
- [X] T029 [US1] Add a test to `src/test/java/multiroom/tts/service/TtsServiceTest.java` using a mocked Google provider whose `resolveSettings` returns `voiceKey` `uk-ua-chirp3-hd-charon`. The `CacheKey` handed to `AudioCache.get` should carry that `voiceKey`, a `null` engine and `uk-UA`.

**Checkpoint**: SC-001 and SC-002 hold in tests. Run quickstart §3 rows US1-1 and US1-3 against a
local core if one is available.

---

## Phase 4: User Story 2 — Choose a voice by engine, language and short name (P1)

**Goal**: `engine: chirp3-hd`, `language: uk-UA`, `voice: charon` works. Request overrides of voice,
engine and language compose correctly. A full name and the equivalent short name share one cache
entry.

**Independent test**: Resolver unit tests cover US2-1 to US2-7. A service test shows that the full
name and the short name produce one `CacheKey` hash.

### Tests for User Story 2 (write first, must fail)

- [X] T030 [P] [US2] Extend `src/test/java/multiroom/tts/provider/cloud/google/GoogleVoiceResolverTest.java` for short names and engines:
  - US2-1: entry `chirp3-hd`/`uk-UA`/`charon` with no request voice gives `uk-UA-Chirp3-HD-Charon`.
  - US2-2: request voice `Puck` gives `uk-UA-Chirp3-HD-Puck`, with `requestedVoice` `Puck`. With no request voice (US2-1), `requestedVoice` is the configured `charon`.
  - US2-3: entry `wavenet`/`en-US` with request `d` gives `en-US-Wavenet-D`.
  - US2-7: default `chirp3-hd`, request engine `neural2` and voice `c` give `en-US-Neural2-C`.
  - US3-5: request `D` with engine `wavenet` gives `{lang}-Wavenet-D`.
  - `CHARON` and `charon` both give `Charon`.
  - A request engine `wavenet` with request voice `uk-UA-Chirp3-HD-Charon` is `INVALID_REQUEST` naming `Wavenet` and `Chirp3-HD`.
  - A request engine that contradicts a *configured* full voice (with no request voice) is `INVALID_REQUEST`.
  - An unknown request engine `chirp4` is `INVALID_REQUEST` listing the supported engines.
  - A request engine with no voice anywhere is `INVALID_REQUEST` ("an engine needs a voice").
  - A short request voice with no language anywhere is `INVALID_REQUEST`.
  - An entry configured only with `voice: en-US-Polyglot-1` (no engine, no language) gets `INVALID_REQUEST` for a short request voice with no request engine. It succeeds when the request names `neural2`, giving `en-US-Neural2-<V>` with the language taken from the configured voice's prefix.
  - US2-8: an entry configured only with `voice: uk-UA-Chirp3-HD-Charon` (no engine, no language) and request voice `puck` gives `uk-UA-Chirp3-HD-Puck`. The default language comes from the configured voice (FR-007).
  - An entry with `voice: uk-UA-Chirp3-HD-Charon` and `language: en-US`: with no request voice, the result is `uk-UA-Chirp3-HD-Charon` in `uk-UA`. With request voice `puck`, it is `en-US-Chirp3-HD-Puck`, because the configured `language` is the default for short names (FR-007).
  - Config `engine: wavenet` with config voice `uk-UA-Chirp3-HD-Charon`: the full voice is used as given, and the edge case "full name wins" holds.
  - The full name and the equivalent short name produce the same `voiceKey` (US2-4).
- [X] T031 [P] [US2] Extend `src/test/java/multiroom/tts/config/TtsPropertiesValidationTest.java`. Each of the following fails, naming the extension and the entry:
  - US2-6: an entry with short `voice: charon` and no `engine` ("an engine is required").
  - An entry with no voice and no engine.
  - `engine: chirp4` (the message lists the supported engines).
  - A short voice with an engine but no language ("a language is required for short voice 'charon'").

  These pass:
  - US2-5: production shape with no engine.
  - Only `voice: uk-UA-Chirp3-HD-Charon`, with no engine and no language (the default language comes from the voice).
  - `engine: WaveNet` with `language: en-US` and no voice.
  - `engine: chirp3-hd`, `language: uk-UA`, `voice: charon`.
- [X] T032 [P] [US2] Add a test to `src/test/java/multiroom/tts/service/TtsServiceTest.java` using a **real** `GoogleCloudTtsProvider` built against a WireMock base URI, with the audio cache mock returning a hit. A request with `voice: charon`, `engine: chirp3-hd`, `language: uk-UA` and a request with `voice: uk-UA-Chirp3-HD-Charon` should look up an identical `CacheKey` (US2-4). Also verify SC-006 and research R5: WireMock records **0 requests of any kind**, so neither `/v1/voices` nor `text:synthesize` is called on a cache hit. The companion miss case, which shows that the zero really comes from the hit path, needs US3's catalogue and is T039.

### Implementation for User Story 2

- [X] T033 [US2] Complete `src/main/java/multiroom/tts/provider/cloud/google/GoogleVoiceResolver.java` (makes T030 and T032 pass). Compute the entry's **default engine** once in the constructor (FR-007): `GoogleEngine.fromName(config.engine)`, or else the engine segment of a configured full voice (possibly unrecognized). Compute the **default language** the same way: `config.language`, or else the prefix of a configured full voice, or else none (data-model "Derived: default language"). Then implement:
  - step 1: request-engine validation;
  - step 6: the engine-conflict check;
  - step 7: short-name composition, where the engine is request, then default engine, and the language is request, then default language;
  - the engine override is no longer rejected.

  Match with `GoogleEngine` equality for recognized engines and case-insensitive segment equality otherwise.
- [X] T034 [US2] Extend the `GOOGLE_CLOUD` branch of `src/main/java/multiroom/tts/config/TtsProperties.java#validateProvider` (makes T031 pass). Add faults for:
  - an unrecognized `engine`;
  - no default engine (neither `engine` nor a full `voice`);
  - a short `voice` with no `language`.

  Message texts follow `contracts/configuration.md` "Start-up faults".

**Checkpoint**: US1 and US2 both pass. This is the full P1 scope, and it is shippable on its own.

---

## Phase 5: User Story 3 — A wrong voice is caught early and the error lists the alternatives (P2)

**Goal**: On a cache miss, the resolved voice is checked against a per-entry, lazily fetched
`v1/voices` catalogue. An unknown voice gives `400 INVALID_VOICE` with the list of alternatives. An
unreachable catalogue never blocks synthesis.

**Independent test**: Catalogue unit tests with a fixed `Clock`. Provider tests with WireMock
stubbing `GET /v1/voices`: a typo gives `INVALID_VOICE` and no `text:synthesize` call; a catalogue
503 still synthesizes.

### Tests for User Story 3 (write first, must fail)

- [X] T035 [P] [US3] Create `src/test/resources/google-voices.json`, a trimmed `v1/voices` response with:
  - the ~30 `uk-UA-Chirp3-HD-*` voices (Achernar … Zubenelgenubi, including Charon and Puck);
  - `en-US-Wavenet-A..D`;
  - `en-US-Neural2-C`;
  - `en-US-Polyglot-1`;
  - `en-US-News-K`;
  - one entry whose `name` is a bare `Achird` (no language or engine; Google publishes such entries), which must be dropped. The full `uk-UA-Chirp3-HD-Achird` above must still be kept.

  Each entry has `languageCodes`, `name`, `ssmlGender` and `naturalSampleRateHertz`.
- [X] T036 [P] [US3] Write `src/test/java/multiroom/tts/provider/cloud/google/GoogleVoiceCatalogueTest.java` using a stub fetcher (a `Supplier`/functional interface returning JSON or throwing, counting calls) and a mutable test `Clock`. Cover:
  - (a) Construction makes 0 fetches.
  - (b) The first `check` fetches once. A second `check` within the `ttl` makes no fetch (US3-3).
  - (c) After the `ttl` passes, the next check fetches again.
  - (d) `check("UK-UA-CHIRP3-HD-CHARON")` gives `Found` with the catalogue spelling `uk-UA-Chirp3-HD-Charon`.
  - (e) `check("uk-UA-Chirp3-HD-Charn")` on a snapshot younger than `failureBackoff` gives `Missing`, with alternatives equal to the uk-UA Chirp3-HD voices and **no refetch** (research R10).
  - (f) The same check on a snapshot older than `failureBackoff` refetches exactly once and then gives `Missing`.
  - (g) A voice that appears in the refetched JSON gives `Found`.
  - (h) A fetch failure gives `Unavailable`. Within `failureBackoff`, further checks make no fetch and give `Unavailable` (FR-013). After the back-off, one fetch happens.
  - (i) A failed refresh while the old snapshot is still within `ttl` keeps answering from that snapshot. Past the `ttl`, it gives `Unavailable` (research R9).
  - (j) Ten threads calling `check` concurrently on an empty catalogue produce exactly one fetch (single-flight).
  - (k) The bare `Achird` entry is dropped: `check("Achird", …)` is not `Found`, and no parsed voice has `fullName` `Achird`. `uk-UA-Chirp3-HD-Achird` is still `Found`, with `shortName` `Achird`. `en-US-Polyglot-1` is `Found`, and its alternatives are grouped by segment `Polyglot`.
- [X] T037 [US3] Extend `src/test/java/multiroom/tts/provider/GoogleCloudTtsProviderTest.java`, with `GET /v1/voices` stubbed from `google-voices.json`:
  - (a) US3-1: `synthesize` with settings resolved from short voice `charn` (voice `uk-UA-Chirp3-HD-Charn`, `requestedVoice` `charn`) throws `INVALID_VOICE`. The message is exactly `Voice 'charn' is not available for Chirp3-HD / uk-UA. Available: Achernar, …`, quoting the caller's spelling and not the resolved name. It lists **every** uk-UA Chirp3-HD short name (SC-003). WireMock verifies 0 calls to `text:synthesize`.
  - (b) A valid voice sent in the wrong case is sent to Google in the catalogue spelling.
  - (c) US3-2: `/v1/voices` returns 503. Synthesis still happens, and the next call within the back-off makes no `/v1/voices` request.
  - (d) With a `/v1/voices` fixed delay longer than `fetchTimeout` (set to 200 ms in the test), synthesis still succeeds, and the total elapsed time stays under the entry timeout (research R8).
  - (e) Constructing the provider makes 0 requests to either path.
  - (f) A voice-less request (language only) never calls `/v1/voices`.
  - (g) US3-4: on an entry with `engine: chirp3-hd` and `language: en-US`, request voice `D` with no engine throws `INVALID_VOICE`. The message says `Chirp3-HD / en-US` and lists only Chirp3-HD voices, and 0 calls go to `text:synthesize`. Add the needed `en-US-Chirp3-HD-*` entries to `google-voices.json` (T035). Wavenet `D` exists in the fixture, which proves that other engines are not searched.
  - (h) FR-011, catalogues per entry: build two providers with **the same api-key** against one WireMock server. Stub `/v1/voices` to return 503 for the first fetch, then 200 (a WireMock scenario). Provider A's first call fails and puts A into back-off. Provider B's first call still fetches and gets `Found`. A second call on A within A's back-off makes no request. Assert the per-provider request counts with WireMock `verify`.
- [X] T038 [P] [US3] Extend `src/test/java/multiroom/tts/rest/TtsExceptionHandlerTest.java`: `TtsException(INVALID_VOICE, …)` maps to 400 with `error: INVALID_VOICE`.
- [X] T039 [US3] Add the miss case to T032 in `src/test/java/multiroom/tts/service/TtsServiceTest.java`. Use the same real `GoogleCloudTtsProvider` against WireMock, with `/v1/voices` stubbed from `google-voices.json`, `text:synthesize` stubbed with audio, and the audio cache mock returning empty. A request for `voice: charon`, `engine: chirp3-hd`, `language: uk-UA` makes exactly one `/v1/voices` request and one `text:synthesize` request. Together with T032's zero, this proves that the catalogue is consulted only on a miss (SC-006, research R5).

### Implementation for User Story 3

- [X] T040 [P] [US3] Add `INVALID_VOICE` and `VOICE_CATALOGUE_UNAVAILABLE` to `src/main/java/multiroom/tts/TtsErrorCode.java`, each with a Javadoc line. Add `INVALID_VOICE` to `CALLER_FIXABLE` in `src/main/java/multiroom/tts/rest/TtsExceptionHandler.java` (makes T038 pass). `VOICE_CATALOGUE_UNAVAILABLE` falls through to 503.
- [X] T041 [P] [US3] Create `src/main/java/multiroom/tts/provider/cloud/google/CatalogueVoice.java`, a record `(String fullName, String shortName, String engine, String language)` with `static Optional<CatalogueVoice> fromName(String name)`. It parses via `GoogleVoiceName`: the engine is canonical if recognized and otherwise the segment as published, and the language is the canonical prefix.
- [X] T042 [US3] Implement `src/main/java/multiroom/tts/provider/cloud/google/GoogleVoiceCatalogue.java` (makes T036 pass), following data-model.md "GoogleVoiceCatalogue" and research R9–R10:
  - Constructor: `(String providerName, VoiceCatalogueProperties props, Clock clock, CatalogueFetcher fetcher)`, where `CatalogueFetcher` is a nested functional interface `String fetch(Duration timeout) throws IOException`, and `IOException` covers timeouts and non-2xx.
  - An immutable snapshot `{Instant fetchedAt, Map<String, CatalogueVoice> byFoldedName}` held in a `volatile` field, plus a `volatile Instant failedAt`.
  - A `ReentrantLock` for single-flight, re-checking state after acquiring it.
  - A sealed result `CheckResult` = `Found(CatalogueVoice)` | `Missing(List<CatalogueVoice> alternatives)` | `Unavailable(String reason)`.
  - `check(String fullName, Duration budget)`.
  - `List<CatalogueVoice> list(String language, String engine, Duration budget)`, which throws `TtsException(VOICE_CATALOGUE_UNAVAILABLE, "The voice catalogue of provider '<name>' is unavailable: <reason>")`.
  - Log `debug` on each fetch, and `warn` on a fetch failure (the reason, never the key).
- [X] T043 [US3] Wire the catalogue into `src/main/java/multiroom/tts/provider/cloud/GoogleCloudTtsProvider.java` (makes T037 pass):
  - Construct one `GoogleVoiceCatalogue` per provider instance, with no fetch. The fetcher does `GET {apiBase}voices?key=…` with the given timeout, and a non-2xx response throws `IOException` with the status and `GoogleErrorBody` message.
  - In `synthesize`: set `deadline = clock.instant() + timeoutSeconds`. If a voice is set, call `catalogue.check(voice, min(fetchTimeout, remaining))`:
    - `Found`: use its `fullName`.
    - `Missing`: throw `INVALID_VOICE`, with the message per data-model.md "Synthesis", quoting `settings.requestedVoice()` (not the resolved name) and joining the alternatives' `shortName`s with `", "`. If there are no alternatives, use `No <engine> voices are available for <language>`.
    - `Unavailable`: use the rule spelling.
  - Then `POST text:synthesize` with `HttpRequest.timeout(max(remaining, 1s))`.
- [X] T044 [US3] Complete `src/main/java/multiroom/tts/config/VoiceCatalogueProperties.java`: Javadoc on each field (per `contracts/configuration.md`). In `TtsProperties.validate()`, fault if any of `ttl`, `failureBackoff` or `fetchTimeout` is zero or negative. Add the matching cases to `src/test/java/multiroom/tts/config/TtsPropertiesValidationTest.java` first.

**Checkpoint**: US1–US3 pass. SC-003 and SC-007 hold in tests.

---

## Phase 6: User Story 4 — Adjust pitch and speaking rate (P3)

**Goal**: `pitch` and `speaking-rate` in configuration and in requests reach Google, are validated
against [-20, 20] and [0.25, 2.0], and take part in the cache key. `extra-params` is no longer
silently ignored on `google-cloud`.

**Independent test**: A WireMock `matchingJsonPath` on `audioConfig.pitch` and
`audioConfig.speakingRate`. Resolver range tests. Start-up fault tests. A service test showing a
cache miss on a rate change and a hit for an explicit `1.0`.

### Tests for User Story 4 (write first, must fail)

- [X] T045 [P] [US4] Extend `src/test/java/multiroom/tts/provider/cloud/google/GoogleVoiceResolverTest.java`:
  - US4-1: an entry with rate `1.2` and pitch `-2` produces both in the settings.
  - US4-2: no values produces nulls.
  - US4-3: request rate `0.9` overrides the entry's `1.2`.
  - US4-5: request rate `3.0` gives `INVALID_REQUEST` with the message containing `[0.25, 2.0]`, and pitch `-21` gives `INVALID_REQUEST` with `[-20.0, 20.0]`. The bounds `0.25`, `2.0`, `-20` and `20` are accepted.
  - Cache-key forms (research R7): pitch `0.0` gives `pitch` `0.0` and `pitchKey` `null`, and rate `1.0` gives `speakingRate` `1.0` and `speakingRateKey` `null`. Pitch `-2` and rate `1.2` give keys equal to the values.
- [X] T046 [P] [US4] Extend `src/test/java/multiroom/tts/provider/GoogleCloudTtsProviderTest.java`: with pitch `-2.0` and rate `1.2`, the request body has `audioConfig.pitch == -2.0` and `audioConfig.speakingRate == 1.2`. With both null, the body has **no** `pitch` or `speakingRate` keys.
- [X] T047 [P] [US4] Extend `src/test/java/multiroom/tts/config/TtsPropertiesValidationTest.java`:
  - A `google-cloud` entry fails with `speaking-rate: 2.5`, and with `pitch: 25`. Each message names the entry and the range (FR-015).
  - A `google-cloud` entry fails with `extra-params: {speaking_rate: "1.1"}`. The message names the key and suggests `pitch`/`speaking-rate` (FR-014).
  - An `OPENAI` entry fails with `pitch: 1` (SC-008).
  - A `google-cloud` entry with `speaking-rate: 1.1` and `pitch: -2` passes.
- [X] T048 [P] [US4] Extend `src/test/java/multiroom/tts/service/TtsServiceTest.java` using the **real** `GoogleCloudTtsProvider` from T032 (WireMock base URI, cache mock returning a hit), so the Google resolver and not a stub decides the key:
  - US4-4: request rate `0.9` and no rate look up different `CacheKey` hashes.
  - An explicit rate `1.0` with pitch `0.0` looks up the same hash as none (research R7).
  - A non-Google `CacheKey` is byte-identical to 001's.

  It fails until T050 stops rejecting `pitch`/`speakingRate` and computes the keys.

### Implementation for User Story 4

- [X] T049 [US4] Add `private Double pitch;` and `private Double speakingRate;` to `src/main/java/multiroom/tts/config/TtsProviderConfig.java`, with Javadoc stating "`google-cloud` only" and the ranges. Rewrite the `extraParams` Javadoc: it must be empty for `google-cloud`.
- [X] T050 [US4] Extend `src/main/java/multiroom/tts/provider/cloud/google/GoogleVoiceResolver.java` (makes T045 pass). Implement step 3 (the range check, stating the range) and step 8 (request, then config), including `pitchKey`/`speakingRateKey`, which are `null` for the neutral `0.0`/`1.0`. Put the neutral values in private constants with a *why* comment (Google's defaults, so an explicit default shares the cache entry of no value). Stop rejecting `pitch` and `speakingRate`. Also makes T048 pass. Declare the range bounds once, as `public static final` constants on `GoogleVoiceResolver` (`MIN_PITCH`, `MAX_PITCH`, `MIN_SPEAKING_RATE`, `MAX_SPEAKING_RATE`), and reuse them from `TtsProperties`. Do not add a separate constants class.
- [X] T051 [US4] In `src/main/java/multiroom/tts/provider/cloud/GoogleCloudTtsProvider.java#buildRequest`, add `pitch` and `speakingRate` to `audioConfig` only when non-null, using a `LinkedHashMap` in place of `Map.of` (makes T046 pass).
- [X] T052 [US4] Extend `src/main/java/multiroom/tts/config/TtsProperties.java#validateProvider` (makes T047 pass):
  - For `GOOGLE_CLOUD`: range faults for `pitch` and `speakingRate`, and a fault for a non-empty `extraParams`.
  - For **every other type**: a fault if `pitch` or `speakingRate` is set.

**Checkpoint**: SC-008 holds. Settings that were previously ignored now take effect or abort start-up.

---

## Phase 7: User Story 5 — List the voices a provider offers (P3)

**Goal**: `GET /api/tts/providers/{name}/voices?language=&engine=` returns the voices from the same
catalogue as US3.

**Independent test**: A `@WebMvcTest` slice with a mocked `VoiceQueryService` covers 200, 400
unsupported type, 400 unknown provider and 503 unavailable. A catalogue filter unit test.

**Depends on**: US3 (`GoogleVoiceCatalogue`, `VOICE_CATALOGUE_UNAVAILABLE`).

### Tests for User Story 5 (write first, must fail)

- [X] T053 [P] [US5] Extend `src/test/java/multiroom/tts/provider/cloud/google/GoogleVoiceCatalogueTest.java` for `list`:
  - `list("uk-ua", "chirp3-hd")` returns the uk-UA Chirp3-HD voices, sorted by short name.
  - `list(null, "polyglot")` returns `en-US-Polyglot-1` with engine `Polyglot` (FR-018).
  - `list(null, null)` returns everything parsed, sorted by language, then engine, then short name.
  - During the back-off, `list` throws `VOICE_CATALOGUE_UNAVAILABLE` without a fetch (FR-013).
- [X] T054 [P] [US5] Write `src/test/java/multiroom/tts/service/VoiceQueryServiceTest.java`:
  - For a Google provider (a `VoiceCatalogueProvider` mock), results are delegated and mapped.
  - US5-2: a `LOCAL_HTTP` provider gives `INVALID_REQUEST` `"Provider 'piper-local' of type LOCAL_HTTP does not support voice listing"`.
  - US5-4: an unknown name gives `PROVIDER_NOT_FOUND`.
  - A malformed `language` filter gives `INVALID_REQUEST`.
- [X] T055 [P] [US5] Write `src/test/java/multiroom/tts/rest/TtsVoiceControllerTest.java` (`@WebMvcTest(TtsVoiceController.class)` plus `TtsExceptionHandler`):
  - US5-1: 200 with body `{providerName, voices:[{shortName, fullName, engine, language}]}`.
  - 400 `INVALID_REQUEST`.
  - 400 `PROVIDER_NOT_FOUND`.
  - US5-3: 503 `VOICE_CATALOGUE_UNAVAILABLE`.

  The shapes are as in `contracts/tts-rest-api.yaml`.
- [X] T056 [P] [US5] Extend `src/test/java/multiroom/tts/provider/GoogleCloudTtsProviderTest.java` for `listVoices`, with `GET /v1/voices` stubbed from `google-voices.json`:
  - `listVoices("uk-UA", "chirp3-hd")` returns the uk-UA Chirp3-HD voices as `CatalogueVoice`s, and makes one `/v1/voices` request.
  - With a `/v1/voices` fixed delay longer than `fetchTimeout` (200 ms in the test), `listVoices` throws `VOICE_CATALOGUE_UNAVAILABLE` within roughly `fetchTimeout`, not the entry's timeout.
  - A second `listVoices` within the back-off makes no further request.

### Implementation for User Story 5

- [X] T057 [P] [US5] Create `src/main/java/multiroom/tts/provider/VoiceCatalogueProvider.java`, an interface with `List<CatalogueVoice> listVoices(String language, String engine)` and a Javadoc noting it is an optional capability (ISP). Implement it on `GoogleCloudTtsProvider`, delegating to `catalogue.list(language, engine, fetchTimeout)`. Makes T056 pass (together with T058).
- [X] T058 [US5] Implement `list` in `src/main/java/multiroom/tts/provider/cloud/google/GoogleVoiceCatalogue.java` (makes T053 pass). Filter the language by `GoogleLanguage` equality. Match the engine by `GoogleEngine.fromName` equality *or* by case-insensitive raw segment. Sort as in data-model.md.
- [X] T059 [US5] Create `src/main/java/multiroom/tts/service/VoiceQueryService.java` (makes T054 pass). Its constructor is `(TtsProperties, ProviderRegistry)`. `listVoices(providerName, language, engine)` resolves the provider, checks `instanceof VoiceCatalogueProvider` (and otherwise raises the unsupported error naming the configured type), and validates the `language` filter format. Register it as a `@Bean` in `src/main/java/multiroom/tts/TtsAutoConfiguration.java`.
- [X] T060 [US5] Create `src/main/java/multiroom/tts/rest/VoiceListResponse.java` (records `VoiceListResponse(String providerName, List<VoiceDescriptor> voices)` and `VoiceDescriptor(shortName, fullName, engine, language)`). Give them `@Schema(name = "TtsVoiceListResponse")` and `@Schema(name = "TtsVoiceDescriptor")` to avoid springdoc name collisions, following the `ErrorResponse` precedent. Create `src/main/java/multiroom/tts/rest/TtsVoiceController.java`, with `@RestController`, `@RequestMapping("/api/tts/providers")`, and `@GetMapping("/{providerName}/voices")` taking the optional `language` and `engine` query parameters and carrying `@Operation`/`@ApiResponses` matching the contract. Add `TtsVoiceController.class` to `assignableTypes` in `src/main/java/multiroom/tts/rest/TtsExceptionHandler.java`. Makes T055 pass.

**Checkpoint**: All five stories pass.

---

## Phase 8: Polish & cross-cutting concerns

- [X] T061 Extend `src/test/java/multiroom/tts/TtsAutoConfigurationTest.java`: with a `google-cloud` entry (plus `pitch`/`speaking-rate` and a `multiroom.tts.voice-catalogue` block), the context starts and contains `VoiceQueryService` and `TtsVoiceController`. With `multiroom.tts.enabled=false`, it contains none of the new beans. This test does **not** claim SC-005: the context always targets the real Google endpoint, and the production code deliberately has no hidden test-only override. SC-005 (zero start-up calls) is proven at the provider level by T023(e) and T037(e), where construction and `resolveSettings` make zero WireMock requests. The live check is quickstart §2 (no Google request in the core's start-up log).
- [X] T062 [P] Update `docs/configuration.md` "Google Cloud" with the three accepted shapes, `pitch`/`speaking-rate` and their ranges, `multiroom.tts.voice-catalogue.*`, the start-up faults and the compatibility note, all from `contracts/configuration.md`. Link to the voices endpoint.
- [X] T063 [P] Update `docs/google-cloud-tts-setup.md`:
  - Step 5: `engine` is now the default engine and is validated, not a label.
  - Remove the "`engine` is not an API parameter" paragraph.
  - Replace the "`language` must match the voice's prefix" warning with the new resolution rule.
  - Mention `GET /api/tts/providers/{name}/voices` for discovering names.
- [X] T064 [P] Update `README.md` (if it lists endpoints or config keys) with the voices endpoint and the new request fields.
- [X] T065 [P] Keep `docs/future/google-cloud-voice-selection.md` in place and add a status line directly under its title: `**Status**: delivered by [specs/002-google-voice-selection](../../specs/002-google-voice-selection/spec.md) (2026-09).` Also add a short "What changed from this note" paragraph listing where the delivery differs from the proposal: a short voice uses the entry's default engine and is never searched across engines; the 60 s refetch floor; the invalid-voice message format. The note stays where it is because `spec.md`'s **Input** line cites it by path, and it records the incident and the reasoning that the spec condenses. Do not delete or move it.
- [X] T066 Run `mvn verify` and fix every failure, including the JaCoCo `check` from T001. Then open `target/site/jacoco/index.html` and confirm that the classes the constitution singles out have more than 80% line coverage: the cache (`multiroom.tts.cache`), the provider adapters (`multiroom.tts.provider..`, including `cloud.google`) and the request-to-playback path (`TtsService`). Run SpotBugs on the changed classes with the constitution's noise filter, and fix or justify each finding (Constitution V).
- [X] T067 Run quickstart §2 (merge gate, part 2): `mvn deploy -Plocal`, start the local core, and check that `GET /actuator/extensions` shows `tts` `0.1.1` and not `REJECTED`, with no Google request in the start-up log. Then run §3 and §4 against the local core with a real key.
- [X] T068 Run quickstart §5 on production (`multiroom.lan`). **Every** deploy, restart or config change there needs the user's explicit permission first; the config read is pre-authorised. Confirm SC-004 (no config edits) and SC-001, and that a `speakingRate` change is audible (Constitution VII).

---

## Dependencies & execution order

### Phase dependencies

```text
Setup (T001–T002)
   └─► Foundational (T003–T020)  ── blocks everything below
          └─► US1 (T021–T029) ──► US2 (T030–T034) ──► US3 (T035–T044) ──► US5 (T053–T060)
                              └─► US4 (T045–T052)
          all stories ───────────────────────────────────────────────────► Polish (T061–T068)
```

- **US1** needs Foundational only.
- **US2** needs US1: it completes `GoogleVoiceResolver`, which US1 creates.
- **US3** needs US1 (the provider rework T027 and `VoiceCatalogueProperties`) and US2: T037(g)
  resolves a short voice with no engine through the entry's default engine, which US2 implements.
- **US4** needs US1 (the resolver and the provider body builder). Its service test T048 reuses the
  real-provider setup of T032 (US2), so run it after US2. It is otherwise independent of US2 and US3.
- **US5** needs US3 (the catalogue and the new error code).
- US2, US3 and US4 all edit `GoogleVoiceResolver`, `GoogleCloudTtsProvider` or `TtsProperties`, so
  run them **one after another** (US2 → US3 → US4) unless separate branches are merged with care.

### Within each story

The tests are written first and fail. Then pure types, then the resolver or catalogue, then the
provider wiring, then config validation, then the service or REST layer.

### Parallel opportunities

- Foundational tests T003–T008 are in six different files.
- After T009–T017, T018 and T019 can run in parallel (T020 needs both).
- US1: T021, T022 and T024 in parallel, then T025 in parallel with T026.
- US3: T035, T036 and T038 in parallel, and T040 and T041 in parallel. T039 needs T035.
- US4: T045–T048 in parallel (four different test files).
- US5: T053, T054, T055 and T056 in parallel. T057 can run in parallel with T058.
- Polish: T062–T065 in parallel.

### Parallel example: Foundational tests

```text
T003 DefaultSettingsResolutionTest   T004 CacheKeyTest   T005 GoogleEngineTest
T006 GoogleLanguageTest              T007 GoogleVoiceNameTest
```

### Parallel example: User Story 4 tests

```text
T045 GoogleVoiceResolverTest (ranges)    T046 GoogleCloudTtsProviderTest (audioConfig)
T047 TtsPropertiesValidationTest         T048 TtsServiceTest (cache identity)
```

---

## Implementation strategy

### MVP (User Story 1 only)

Phases 1–3 fix the real incident (SC-001) and make every Google failure readable (SC-002), with the
production config unchanged (SC-004). Stop there, run T066–T068, and ship it as a 0.1.1 pre-release
if the rest has to wait.

### Incremental delivery

1. Foundation plus US1: the incident is fixed.
2. Plus US2: structured configuration. This completes the P1 scope and is the recommended first
   production deploy.
3. Plus US3: typos are caught with a list of alternatives.
4. Plus US4: pitch and rate take effect.
5. Plus US5: voices are discoverable, and the CLI can build on the endpoint.

Each step keeps `mvn verify` green. The production smoke run (T068) is required at least once, after
the last step that ships, because audio parameters change what is heard.

## Notes

- `GoogleCloudTtsProviderTest` stays in `src/test/java/multiroom/tts/provider/` (where it is today).
  The new pure tests go in `src/test/java/multiroom/tts/provider/cloud/google/`.
- Never put the API key in a log line or an exception message. It is only a query parameter on the
  outgoing requests.
- Commit after each task or logical group, using Conventional Commits scoped
  `002-google-voice-selection`.

## Phase 9: Convergence

- [x] T069 Reject a non-finite pitch or speaking rate in both places that range-check them. The checks are `value < min || value > max`, and both comparisons are false for `NaN`. So `"pitch": "NaN"` in a request (Jackson converts the string to `Double.NaN` by default) gets through `GoogleVoiceResolver.checkRange`, becomes a non-neutral `pitchKey`, and makes `CacheKey.toHash` throw `NumberFormatException` in `BigDecimal.valueOf`: the caller gets an unmapped HTTP 500, not a 400. In configuration, `pitch: .nan` gets through `TtsProperties.requireInRange`, start-up succeeds, and then every announcement on that entry fails. Add `NaN` cases to `src/test/java/multiroom/tts/provider/cloud/google/GoogleVoiceResolverTest.java` (request pitch and speakingRate → `INVALID_REQUEST` stating the range) and `src/test/java/multiroom/tts/config/TtsPropertiesValidationTest.java` (configured pitch and speaking-rate → start-up fault naming the entry and the range). Then treat `!Double.isFinite(value)` as out of range in `src/main/java/multiroom/tts/provider/cloud/google/GoogleVoiceResolver.java#checkRange` and `src/main/java/multiroom/tts/config/TtsProperties.java#requireInRange` per FR-009, FR-015, US4/AC5 (partial)
- [X] T070 [P] Repair the comment in `src/main/java/multiroom/tts/service/TtsService.java#speak` above `provider.resolveSettings(...)`. Removing a spec ID left a line that starts with `//.`: the sentence "…and a hit costs no network work" has lost its full stop, and the next line starts with a stray one. Rejoin the two sentences. Change no behaviour, per Constitution I (Comments When Necessary) (partial)
