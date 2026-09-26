---

description: "Task list for 004-gemini-tts-provider"
---

# Tasks: Gemini TTS Provider

**Input**: Design documents from `/specs/004-gemini-tts-provider/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/configuration.md](contracts/configuration.md),
[contracts/tts-rest-api.yaml](contracts/tts-rest-api.yaml), [quickstart.md](quickstart.md)

**Tests**: **Required.** Constitution III requires test-first development, so every test task
comes before the code it covers and must fail (red) before that code is written. The exception is
a test that pins down behaviour the code already has (T013, T034, T038–T040, T044): it is written
first all the same and is expected to pass at once. Each such task says so.

- No test calls real Google.
- No key material is committed: key files come from `TestServiceAccountKeys`, written into a JUnit
  `@TempDir`, with `token_uri` pointing at WireMock on loopback.
- Time comes from an injected `java.time.Clock`.

**Organization**: Tasks are grouped by user story. US1, US2 and US3 are P1, US4 is P2 and US5 is P3.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: The task can run in parallel with others: it touches different files and depends on no
  incomplete task.
- **[Story]**: The user story (US1–US5) the task serves.
- Paths are relative to the repository root. Main code is under `src/main/java/multiroom/tts/` and
  tests under `src/test/java/multiroom/tts/`.

## Conventions every task follows

- **Start-up faults**: A start-up fault is
  `IllegalStateException("multiroom-tts: provider '<name>' …")`, raised through `fault(...)` in
  `TtsProperties`. No start-up code makes a network call (constitution VIII).
- **Run-time failures**: A run-time failure is a `TtsException` with an **existing** `TtsErrorCode`.
  Add no new constant. A caller error is `INVALID_REQUEST`. A provider message names the entry
  (`Provider '<name>' …`).
- **Secrets**: Never pass a private key, a signed assertion or a token to a logger, an exception
  message or a `toString()`.
- **Keep old constructors**: When a record gains `stylePrompt`, keep its previous constructor as a
  secondary one that passes `null`. `GoogleCloudTtsProviderTest` must stay **unmodified** for the
  whole feature, and the dozens of existing call sites stay untouched. This applies to
  `RequestedSettings`, `SynthesisSettings`, `CacheKey` and `AnnounceCommand`.
- **Spec IDs**: Do not cite spec IDs (`FR-`, `SC-`, `US`, `R`) in code comments; state the rule
  itself. Feature numbers (`002`, `003`, `004`) and constitution principles may be named.
- **Style**: Match the surrounding code: 4-space indent, the existing import order, Javadoc on
  public types, and comments that explain *why*. `GoogleLanguage` is the model for the new value
  types, and `GoogleVoiceResolver` for the new resolver.

---

## Phase 1: Setup

**Purpose**: Version and contract bookkeeping. No behaviour change.

- [X] T001 In `pom.xml`, bump `<version>` from `0.1.2` to `0.1.3`. Leave `multiroom.require_api_version` at `0.1.18` (plan.md, "Depends on").
- [X] T002 [P] In `src/main/java/multiroom/tts/TtsErrorCode.java`, point the Javadoc at `specs/004-gemini-tts-provider/contracts/tts-rest-api.yaml` (v0.1.3) as the published error-code set. It adds no code.

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: The new type and settings exist, and the Google plumbing both types need is extracted
into `GoogleTtsClient` (research R1) along with `GoogleSpeakingRate` (R9). At the end of this phase
`google-cloud` behaves **exactly as before**, the whole existing suite is green, and
`GoogleCloudTtsProviderTest` is unmodified.

- [X] T003 Add `GOOGLE_GEMINI` to `src/main/java/multiroom/tts/config/ProviderType.java`. In `src/main/java/multiroom/tts/TtsAutoConfiguration.java`, make the `buildProvider` switch compile with a temporary `case GOOGLE_GEMINI -> throw new IllegalStateException("multiroom-tts: provider '" + config.getName() + "': google-gemini is not implemented yet");`. T020 replaces it.
- [X] T004 In `src/main/java/multiroom/tts/config/TtsProviderConfig.java`, add `private String model;` and `private String stylePrompt;`. Neither has a default.
  - Javadoc for `model`: `google-gemini` only; the Gemini-TTS model, such as `gemini-2.5-flash-tts`; required; not overridable per request.
  - Javadoc for `stylePrompt`: `google-gemini` only; the default style prompt; leading and trailing whitespace is removed; at most `max-text-length` characters.
  - Update the Javadoc of `speakingRate` and `serviceAccountKeyFile` to name `google-gemini` as well. The key file is required there.
- [X] T005 [P] Write `src/test/java/multiroom/tts/provider/cloud/google/GoogleSpeakingRateTest.java`. Must fail, because the class does not exist. Cover:
  - `check("speakingRate", value)` accepts `null`, 0.25, 1.0 and 2.0.
  - It rejects 0.2, 2.5, NaN and ±Infinity with `INVALID_REQUEST` "speakingRate <v> is outside the allowed range [0.25, 2.0]".
  - `keyOf(null)` and `keyOf(1.0)` are `null`; `keyOf(1.1)` is `1.1`.
  - `isInRange` gives the same verdicts as a boolean, for start-up use.
- [X] T006 Create `src/main/java/multiroom/tts/provider/cloud/google/GoogleSpeakingRate.java`: a final class with static members only.
  - Constants: `MIN = 0.25`, `MAX = 2.0`, `NEUTRAL = 1.0`.
  - Methods: `check(String field, Double value)` (throws `INVALID_REQUEST`), `isInRange(Double)` and `keyOf(Double)`.
  - Change `src/main/java/multiroom/tts/provider/cloud/google/GoogleVoiceResolver.java` to use it for the rate: `GoogleSpeakingRate.check` for the range and `GoogleSpeakingRate.keyOf` for the rate's key. Remove its own `MIN_SPEAKING_RATE`/`MAX_SPEAKING_RATE`/`NEUTRAL_SPEAKING_RATE`; keep the pitch constants and the generic `keyOf(value, neutral)` helper, which pitch still uses.
  - Change `src/main/java/multiroom/tts/config/TtsProperties.java` to take the rate range from `GoogleSpeakingRate`.

  Makes T005 pass. `GoogleVoiceResolverTest` and `TtsPropertiesValidationTest` must stay green unchanged.
- [X] T007 [P] Write `src/test/java/multiroom/tts/provider/cloud/google/GoogleTtsClientTest.java` against WireMock. Must fail, because the class does not exist. Cover:
  - `synthesize(String jsonBody, Instant deadline)` POSTs to `<base>/text:synthesize` with `Content-Type: application/json` and returns the base64-decoded `audioContent`.
  - An API-key credential sends `?key=`. A service-account credential sends `Authorization: Bearer` and obtains its token from a WireMock token endpoint, with key files from `TestServiceAccountKeys`.
  - A 401 answered by a service account discards the token, renews it and resends exactly once. A 403 is never retried.
  - Errors map as follows, each message naming the entry and carrying `GoogleErrorBody`'s explanation when there is one:
    - 429 → `PROVIDER_RATE_LIMITED` "Provider '<n>' reported rate limit exceeded (HTTP 429): <explanation>"
    - 400 → `PROVIDER_ERROR` "Provider '<n>' returned HTTP 400: <explanation>"
    - a response delayed beyond the deadline → `PROVIDER_TIMEOUT` "Provider '<n>' did not respond within <s> seconds"
    - a 200 without `audioContent` → `PROVIDER_ERROR`
  - `get(URI endpoint, Instant deadline)` returns the raw response for the catalogue fetcher.
- [X] T008 Create `src/main/java/multiroom/tts/provider/cloud/google/GoogleTtsClient.java` by **moving** code out of `src/main/java/multiroom/tts/provider/cloud/GoogleCloudTtsProvider.java`: the `HttpClient` built with the entry's timeout, the `GoogleCredential` construction (`ApiKey`, or `ServiceAccount` with its own `GoogleAccessTokenCache`), `send` with the 401 retry, `explanation`, the status-to-code mapping, `decodeAudioContent`, `remaining` and the 1 s synthesis floor. Suggested shape:
  - Constructor: `GoogleTtsClient(String name, Duration timeout, URI apiBase, ServiceAccountKey keyOrNull, String apiKeyOrNull, Duration failureBackoff, Clock clock)`.
  - `Optional<String> prepare(Duration budget)`
  - `byte[] synthesize(String jsonBody, Instant deadline)`, which calls `prepare` internally so it reads a token renewed by an earlier 401.
  - `HttpResponse<String> get(URI endpoint, Instant deadline)`
  - `URI resolve(String relative)`

  Keep every message text **identical** to today's, including "Failed to call Google Cloud TTS: …". Then rewrite `GoogleCloudTtsProvider` to delegate to it. It keeps its resolver, its catalogue, the order "prepare token → catalogue check → synthesize" and `requestBody`. Both public constructors keep their signatures. Makes T007 pass. **`GoogleCloudTtsProviderTest` must pass unmodified.**

**Checkpoint**: `mvn verify` is green; `git diff src/test/java/multiroom/tts/provider/GoogleCloudTtsProviderTest.java` is empty.

---

## Phase 3: User Story 1 - Announce With a Gemini Voice (Priority: P1) 🎯 MVP

**Goal**: A `google-gemini` entry with a key file, a model, a voice and a language announces
through Google's Text-to-Speech with a bearer token. A repeated announcement is a cache hit, and a
request may override the voice, the language and the speaking rate.

**Independent Test**: One `google-gemini` entry against WireMock. The synthesis request carries
`voice.modelName`, `voice.name: Kore`, `voice.languageCode` and `Authorization: Bearer`; the same
announcement repeated makes no HTTP call; a request with `voice: "kore"` shares the entry of `Kore`.

### Tests for User Story 1 (write first, must fail)

- [X] T009 [P] [US1] Write `src/test/java/multiroom/tts/provider/cloud/google/GeminiVoiceTest.java`:
  - `isWellFormed` is true for `Kore`, `kore`, `KORE` and `Zubenelgenubi`, and false for `null`, `""`, `Ko re`, `Kore1`, `en-US-Kore` and `Kóre`.
  - `parse("kORE").name()` is `Kore`.
  - `parse` of a malformed value throws `IllegalArgumentException`.
- [X] T010 [P] [US1] Write `src/test/java/multiroom/tts/provider/cloud/google/GeminiModelTest.java`:
  - `isWellFormed` is true for `gemini-2.5-flash-tts` and `gemini-3.1-flash-tts-preview`.
  - It is false for `null`, `""`, `Gemini-2.5-flash-tts` (upper case), `gemini 2.5`, `-gemini`, `.gemini` and `gemini_2`.
- [X] T011 [P] [US1] Write `src/test/java/multiroom/tts/provider/cloud/google/GeminiSettingsResolverTest.java`, first part (voice, language and rate only). Build a `TtsProviderConfig` with model `gemini-2.5-flash-tts`, voice `Kore` and language `en-US`. Cover:
  - With no overrides: `voice` and `voiceKey` are `Kore`, `requestedVoice` is `Kore`, `language` is `en-US`, `engine` is the model, pitch and `pitchKey` are `null`, and rate and `speakingRateKey` are `null`.
  - A request voice `charon` gives `Charon` with `requestedVoice` `charon`.
  - A request language `uk-ua` gives `uk-UA`.
  - A malformed request voice (`en-US-Kore`) gives `INVALID_REQUEST`, and the message quotes it as written.
  - A malformed request language gives `INVALID_REQUEST`.
  - A request rate of 1.5 is used with key 1.5. The entry's rate 1.2 applies when the request has none. A rate of 1.0 is sent but its key is `null`. A rate of 3.0 gives `INVALID_REQUEST`.
- [X] T012 [P] [US1] Write `src/test/java/multiroom/tts/provider/GoogleGeminiTtsProviderTest.java`, first part. Use WireMock as both the token endpoint and `v1/`, and a key file from `TestServiceAccountKeys` with a loopback `token_uri`. Cover:
  - Constructing the provider makes zero HTTP requests.
  - `synthesize` makes one token request, then one `POST /v1/text:synthesize` with `Authorization: Bearer <token>` and no `key` query parameter.
  - The JSON body is exactly `input.text`, `voice.languageCode`, `voice.name` (canonical), `voice.modelName` (the model), `audioConfig.audioEncoding: LINEAR16`, `audioConfig.sampleRateHertz` equal to the request's `targetSampleRate` (48000 in the fixture, as `google-cloud` sends it), and `speakingRate` only when one is set. It has **no** `pitch` and **no** `input.prompt` in this story.
  - A second `synthesize` within the token's lifetime makes no second token request.
  - A 24 kHz mono 16-bit WAV returned as `audioContent` (build it with `AudioSystem.write` into a byte array) comes back byte-for-byte as `SynthesisResult.audioData()`.
  - The provider does **not** implement `VoiceCatalogueProvider`.
- [X] T013 [P] [US1] In `src/test/java/multiroom/tts/audio/AudioConverterTest.java`, add a case: a 24 kHz mono WAV reaches the mocked `FormatConverter` with a source `SampleFormat` of 24000 Hz and 1 channel, whatever the target. This proves the sample rate is read from the header. Expected green: the converter already reads it.
- [X] T014 [P] [US1] In `src/test/java/multiroom/tts/service/TtsServiceTest.java`, add Gemini cases that drive a real `GoogleGeminiTtsProvider` against WireMock, in the style of the existing Google cases:
  - The first announcement is a miss that calls synthesis. The same announcement again is a hit with no HTTP call.
  - `voice: "KORE"` hits the entry created with `Kore`.
  - A different `language` is a separate entry, and so is a different voice (`Charon` vs `Kore`).
  - The `CacheKey` passed to the cache has `engineName` equal to the model.
- [X] T015 [P] [US1] In `src/test/java/multiroom/tts/config/TtsPropertiesValidationTest.java`, add these cases:
  - A minimal valid `google-gemini` entry (key-file path, model, voice, language) validates.
  - The same entry with `speaking-rate: 1.2` validates. It must not trip the existing "only google-cloud supports" rule.
  - The same entry with `service-account-key-file` validates. It must not trip the same rule.
  - In `src/test/java/multiroom/tts/TtsAutoConfigurationTest.java`: a context with a valid `google-gemini` entry (a real key file from `TestServiceAccountKeys`) starts, `ProviderRegistry.resolve(name)` returns a `GoogleGeminiTtsProvider`, and WireMock recorded zero requests.

### Implementation for User Story 1

- [X] T016 [P] [US1] Create `src/main/java/multiroom/tts/provider/cloud/google/GeminiVoice.java`: `record GeminiVoice(String name)`, modelled on `GoogleLanguage`, holding the canonical spelling. It has `static boolean isWellFormed(String)` (`[A-Za-z]+`) and `static GeminiVoice parse(String)`. `parse` upper-cases the first letter and lower-cases the rest in `Locale.ROOT`, and throws `IllegalArgumentException` for a malformed value. Javadoc: why only ASCII letters (a locale-independent case mapping), and that every Gemini voice Google lists has this form. Makes T009 pass.
- [X] T017 [P] [US1] Create `src/main/java/multiroom/tts/provider/cloud/google/GeminiModel.java`: `record GeminiModel(String name)` with `static boolean isWellFormed(String)` (`[a-z0-9][a-z0-9.-]*`) and `static GeminiModel parse(String)`, which never changes the value. Javadoc: model names are not a closed list, so only the form is checked. Makes T010 pass.
- [X] T018 [US1] Create `src/main/java/multiroom/tts/provider/cloud/google/GeminiSettingsResolver.java`, modelled on `GoogleVoiceResolver`, pure and with no I/O. The constructor takes `(TtsProviderConfig config, int maxTextLength)`, parses the configured voice, language and model once, and stores `maxTextLength` for T030.
  - `resolve(RequestedSettings)` follows data-model.md "Resolution algorithm" steps 2–4 and 6: voice, language and rate through `GoogleSpeakingRate`. The prompt is always `null` for now.
  - It returns `new SynthesisSettings(voice, voice, requestedVoice, language, model, null, rate, null, GoogleSpeakingRate.keyOf(rate))`.
  - Makes T011 pass.
- [X] T019 [US1] Create `src/main/java/multiroom/tts/provider/cloud/GoogleGeminiTtsProvider.java` implementing `TtsProvider` only. Class Javadoc: route A, the service account only, no voice catalogue, and a pointer to `GoogleTtsClient` for the shared behaviour.
  - Constructors:
    - `(TtsProviderConfig, VoiceCatalogueProperties, ServiceAccountKey, int maxTextLength)` for production.
    - `(… , URI apiBase, Clock clock)`, visible for tests.
  - Build a `GoogleTtsClient` with the key and `catalogueProperties.getFailureBackoff()`.
  - `resolveSettings` delegates to `GeminiSettingsResolver`.
  - `synthesize` sets the deadline from `timeout-seconds`, calls `client.prepare(remaining)`, builds the body described in data-model.md "Synthesis" with Jackson, and returns `new SynthesisResult(client.synthesize(body, deadline), request.targetSampleRate(), 1, 16)`.
  - Javadoc on `synthesize`: the reported sample rate is the one requested, but Google may answer at another (24 kHz for Gemini). The WAV header is authoritative, because `AudioConverter` reads the source format from it.
  - Makes T012 pass.
- [X] T020 [US1] In `src/main/java/multiroom/tts/TtsAutoConfiguration.java`, replace the temporary `GOOGLE_GEMINI` case with `new GoogleGeminiTtsProvider(config, properties.getVoiceCatalogue(), serviceAccountKey(config), properties.getMaxTextLength())`.

  In `src/main/java/multiroom/tts/config/TtsProperties.java`, add the `GOOGLE_GEMINI` branch to `validateProvider`:
  - First reject `api-key`, with or without a key file: "Gemini voices require a service account key file and cannot use an API key". It comes first so that it wins over "missing key file", and it is here rather than in US3 so that the MVP checkpoint never accepts a credential that cannot work.
  - Require `service-account-key-file`, `model`, `voice` and `language`, and check their forms with `GeminiModel`, `GeminiVoice` and `GoogleLanguage`.
  - Range-check `speaking-rate` with `GoogleSpeakingRate`.
  - Stop the existing non-`google-cloud` guard from rejecting `speaking-rate` and `service-account-key-file` on `google-gemini`. Change its message to "which only google-cloud and google-gemini support" for those two keys; `pitch` stays "only google-cloud".

  Makes T013–T015 pass. The existing assertions on `pitch` + `OPENAI` must stay green.

**Checkpoint**: A Gemini entry announces against WireMock, and a repeat is a cache hit. MVP.

---

## Phase 4: User Story 2 - Steer the Delivery With a Style Prompt (Priority: P1)

**Goal**: An entry's default style prompt is sent separately from the text. A request can replace
it, or turn it off with `""`. Each distinct trimmed prompt is its own cache entry, and "no prompt"
and "empty prompt" share one.

**Independent Test**: With a default prompt configured, announce the same text with no prompt,
with another prompt, and with `""`. Google receives the default, the request's prompt, and no
`input.prompt`, in that order, and the cache holds three entries.

### Tests for User Story 2 (write first, must fail)

- [X] T021 [P] [US2] In `src/test/java/multiroom/tts/cache/CacheKeyTest.java`, add these cases:
  - A key with `stylePrompt` `null` hashes **exactly** like the same key built with the existing 8-argument constructor.
  - Add a pinned-literal test: compute the hash of one fixed pre-004 key once, and assert that literal hex. This guards every existing cache entry.
  - Two keys that differ only in prompt (`"Calm."` vs `"calm."`) hash differently.
  - The same prompt twice hashes the same.
  - A prompted key hashes differently from the same key without a prompt.
- [X] T022 [P] [US2] In `src/test/java/multiroom/tts/provider/cloud/google/GeminiSettingsResolverTest.java`, add the prompt cases, with an entry default of `"  Say this calmly.\n"` (note the whitespace):
  - With no request prompt, the effective prompt is `Say this calmly.`
  - A request prompt of `" Announce urgently. "` gives `Announce urgently.`
  - A request prompt of `""` or `"   "` gives `null`.
  - With no default and no request prompt, the prompt is `null`.
  - Inner spacing and case are kept (`"Say  THIS"` stays as is).
  - A request prompt whose stripped length is exactly `maxTextLength` is accepted. One character longer gives `INVALID_REQUEST` "stylePrompt length <n> exceeds maximum allowed length of <max>".
  - A long prompt that fits once stripped is accepted.
- [X] T023 [P] [US2] In `src/test/java/multiroom/tts/provider/GoogleGeminiTtsProviderTest.java`, add these cases:
  - An effective prompt is sent as `input.prompt`, and the text is sent unchanged as `input.text`, never concatenated with the prompt.
  - No effective prompt means no `prompt` key in `input` at all.
- [X] T024 [P] [US2] In `src/test/java/multiroom/tts/service/TtsServiceTest.java`, add the three-prompt scenario from the Independent Test above: three misses and three distinct keys. Repeating each is a hit with no HTTP call. A request with `stylePrompt: ""` and one with `"  "` share an entry. With no default configured, a request with no prompt and one with `""` share an entry.
- [X] T025 [P] [US2] In `src/test/java/multiroom/tts/rest/TtsControllerTest.java`, add a case: a JSON body with `"stylePrompt":"Calm."` reaches `TtsService.speak` as `AnnounceCommand.stylePrompt() == "Calm."`, and a body without it passes `null`.
- [X] T026 [P] [US2] In `src/test/java/multiroom/tts/config/TtsPropertiesValidationTest.java`, add these cases:
  - A `style-prompt` whose stripped length is at most `max-text-length` validates, even when surrounding whitespace pushes the raw value over the limit.
  - A longer one aborts with a message naming the entry, the length and the limit.

### Implementation for User Story 2

- [X] T027 [US2] Add `String stylePrompt` as the **last** component of these records, keeping each one's previous constructor as a secondary constructor that passes `null`:
  - `src/main/java/multiroom/tts/provider/RequestedSettings.java`, documented as: `null` = not given; empty or whitespace = no prompt; `google-gemini` only.
  - `src/main/java/multiroom/tts/provider/SynthesisSettings.java`, documented as the effective prompt, already stripped, and its own cache-key form. `of(…)` passes `null`.
  - `src/main/java/multiroom/tts/service/AnnounceCommand.java`.
  - `src/main/java/multiroom/tts/rest/SpeakRequest.java`. Update its Javadoc: `stylePrompt` is `google-gemini` only, and `speakingRate` is accepted by `google-cloud` and `google-gemini`.

  All existing code and tests compile unchanged.
- [X] T028 [US2] In `src/main/java/multiroom/tts/cache/CacheKey.java`, add `String stylePrompt` as the last component. Keep the 6- and 8-argument constructors, both passing `null`. In `toHash()`, append `"|s=" + stylePrompt` after the pitch and rate parts, **only when it is non-null**. Document why it comes last and why it is absent otherwise: existing entries stay valid. Makes T021 pass.
- [X] T029 [US2] Wire the prompt through:
  - `src/main/java/multiroom/tts/rest/TtsController.java` maps `request.stylePrompt()` into `AnnounceCommand`.
  - `src/main/java/multiroom/tts/service/TtsService.java` passes `command.stylePrompt()` into `RequestedSettings`, and `settings.stylePrompt()` into the `CacheKey`. It still interprets nothing.

  Makes T025 pass.
- [X] T030 [US2] In `src/main/java/multiroom/tts/provider/cloud/google/GeminiSettingsResolver.java`, implement data-model.md step 5 using `String.strip()`. Strip the configured default once, in the constructor; blank means none. Makes T022 pass.

  In `src/main/java/multiroom/tts/provider/cloud/GoogleGeminiTtsProvider.java`, put `prompt` into `input` only when `settings.stylePrompt()` is non-null. Makes T023 and T024 pass.
- [X] T031 [US2] In the `GOOGLE_GEMINI` branch of `src/main/java/multiroom/tts/config/TtsProperties.java`, check the entry's `style-prompt` after `strip()` against `maxTextLength`. Makes T026 pass.

**Checkpoint**: The style prompt works end to end, and every pre-004 cache key hashes unchanged.

---

## Phase 5: User Story 3 - A Wrong Configuration Is Caught at Start-up (Priority: P1)

**Goal**: Every broken `google-gemini` configuration aborts start-up with a message that names the
extension, the entry and the fault, without contacting Google. A Gemini default provider logs one
cost warning.

**Independent Test**: Each fault in [contracts/configuration.md](contracts/configuration.md)
"Start-up faults" aborts validation or context start with the stated message, and WireMock records
zero requests throughout.

### Tests for User Story 3 (write first, must fail)

- [X] T032 [P] [US3] In `src/test/java/multiroom/tts/config/TtsPropertiesValidationTest.java`, add one case per row of the contract's start-up fault table, asserting `multiroom-tts`, the entry name and the stated wording. The rows are:
  - `api-key` with a key file, and `api-key` alone: "require a service account key file" and "cannot use an API key". This check wins over "missing key file".
  - No key file.
  - Missing `model`, `voice` or `language`, one case each.
  - Malformed `model` (`Gemini-2.5`), `voice` (`en-US-Kore`) or `language` (`english`); the message names the key and the value.
  - `engine`, `pitch` or a non-empty `extra-params`; the message names the key.
  - `speaking-rate` 2.5 and NaN.
  - `style-prompt` on `google-cloud`, `openai` and `local-http`.
  - `model` on `google-cloud` and `openai`.

  Also add: production's exact `google-cloud` entry, already in this file, still validates.
- [X] T033 [US3] In `src/test/java/multiroom/tts/config/TtsPropertiesValidationTest.java` (after T032, same file), add cost-warning cases with a captured log, using the log-capture technique of `GoogleAccessTokenCacheTest` / `GoogleCloudTtsProviderTest` (or a Logback `ListAppender` on `TtsProperties`):
  - Exactly one `WARN` naming the entry and "billed per token" when `default-provider` names a `google-gemini` entry.
  - The same when no default is named and the first **enabled** entry is Gemini, including the case where a disabled classic entry comes before it.
  - No such warning when the effective default is not Gemini.
- [X] T034 [P] [US3] In `src/test/java/multiroom/tts/TtsAutoConfigurationTest.java`, add these cases:
  - A `google-gemini` entry whose key file does not exist fails context start with 003's message naming the absolute path.
  - An entry whose file is an OAuth client JSON fails the same way as for `google-cloud`.
  - `multiroom.tts.enabled=false` with a broken Gemini entry starts with no TTS beans.
  - WireMock recorded zero requests in every case.

  Expected green: `serviceAccountKey(config)` already serves the Gemini case (T037).

### Implementation for User Story 3

- [X] T035 [US3] Complete the `GOOGLE_GEMINI` branch in `src/main/java/multiroom/tts/config/TtsProperties.java`, in the order given in research R10:
  1. `api-key` set → fault, already checked in T020; keep it first.
  2. No key file → fault, already checked in T020.
  3. Missing or malformed `model`/`voice`/`language`, already checked in T020; keep that check after steps 1–2.
  4. `engine`, `pitch` or a non-empty `extra-params` → fault. Give `extra-params` its own Gemini message; do not reuse `validateGoogleAudioSettings`, whose message suggests `pitch`. The existing guard for non-`google-cloud` types also rejects `pitch`, but this branch runs first, so its message is the one T032 asserts. Keep the guard for the other types.
  5. Rate and prompt, already checked in T020 and T031 (US2).

  For every other type, reject `style-prompt` and `model` ("which only google-gemini supports"). Keep each check a small private method, matching the file's existing style (constitution I). Makes T032 pass.
- [X] T036 [US3] At the end of `validate()` in `src/main/java/multiroom/tts/config/TtsProperties.java`, work out the effective default: `default-provider`, or else the first enabled entry, the same rule as `ProviderRegistry`. If it is `GOOGLE_GEMINI`, log the one `WARN` from contracts/configuration.md "Start-up warning". Makes T033 pass.
- [X] T037 [US3] Make T034 pass. `serviceAccountKey(config)` in `src/main/java/multiroom/tts/TtsAutoConfiguration.java` already runs for the Gemini case, so this is expected to need no change. If a message differs, fix it in `ServiceAccountKey`, not in a Gemini-specific path.

**Checkpoint**: Every start-up fault in the contract is covered, and start-up makes no network request.

---

## Phase 6: User Story 4 - Google's Refusals Explain Themselves (Priority: P2)

**Goal**: A missing permission, a disabled API, an unknown model or voice, a rate limit, a timeout
or a token failure reaches the caller with the existing codes, the entry's name and Google's
explanation, and never with a secret.

**Independent Test**: WireMock answers the Gemini synthesis with each refusal in turn, and the
caller-visible `TtsException` has the expected code and a message containing the entry name and
Google's `error.message`.

### Tests for User Story 4 (write first; expected green through `GoogleTtsClient`, and any failure is fixed in T041)

- [X] T038 [P] [US4] In `src/test/java/multiroom/tts/provider/GoogleGeminiTtsProviderTest.java`, add refusal cases with realistic Google bodies:
  - 403 `PERMISSION_DENIED` "Permission 'aiplatform.endpoints.predict' denied …" → `PROVIDER_ERROR`.
  - 403 "Agent Platform API has not been used in project … or it is disabled" → `PROVIDER_ERROR`.
  - 400 `INVALID_ARGUMENT` for an unknown model, and one for an unknown voice → `PROVIDER_ERROR`.
  - 429 → `PROVIDER_RATE_LIMITED`.
  - A delay beyond `timeout-seconds` → `PROVIDER_TIMEOUT`.
  - A 401 followed by 200 → success after exactly one renewal. A 401 followed by 401 → `PROVIDER_ERROR`.
  - Each message contains the entry's name and Google's message.
- [X] T039 [US4] In `src/test/java/multiroom/tts/provider/GoogleGeminiTtsProviderTest.java`, add token-path cases mirroring 003's:
  - The token endpoint times out → `PROVIDER_TIMEOUT`, and a second call within `failure-backoff` makes no token request (back-off).
  - The token endpoint answers 400 `invalid_grant` → `PROVIDER_ERROR` with "could not obtain an access token", and it is not remembered.
  - Two Gemini entries built from the **same** key file each make their own token request, so they do not share a token cache.
  - A Gemini entry and a service-account `google-cloud` entry built from the **same** key file each make their own token request, and both synthesize successfully. The `google-cloud` request body carries no `modelName` and no `prompt`.
- [X] T040 [US4] In `src/test/java/multiroom/tts/provider/GoogleGeminiTtsProviderTest.java`, add a secrets test mirroring the one in `GoogleCloudTtsProviderTest`: across the T038–T039 failures, capture every log line and exception message, and assert that none contains the private key's base64 (`TestServiceAccountKeys.privateKeyBase64()`), the WireMock-issued token, or `eyJ` (a JWT's opening).

### Implementation for User Story 4

- [X] T041 [US4] Fix any failure T038–T040 expose **in `src/main/java/multiroom/tts/provider/cloud/google/GoogleTtsClient.java`** (or 003's token classes), never in a Gemini-only path. That keeps the shared behaviour identical for both types. Re-run `GoogleCloudTtsProviderTest`, which is still unmodified.

**Checkpoint**: The failures carry the operator-facing detail, and no secret leaks.

---

## Phase 7: User Story 5 - Callers Cannot Send Gemini-Only or Classic-Only Settings to the Wrong Entry (Priority: P3)

**Goal**: `stylePrompt` sent to any non-Gemini entry, and `engine` or `pitch` sent to a Gemini
entry, are `INVALID_REQUEST` naming the field and the type, before anything is synthesized. Voice
listing on a Gemini entry says it is not supported.

**Independent Test**: Each mismatched field is sent to each kind of entry and gives
`INVALID_REQUEST` with "Field '<f>' is not supported by provider '<n>' of type <TYPE>", and
WireMock and the provider mocks see no synthesis.

### Tests for User Story 5 (write first, must fail)

- [X] T042 [P] [US5] In `src/test/java/multiroom/tts/provider/cloud/google/GeminiSettingsResolverTest.java`, add these cases: a request `engine` (`"chirp3-hd"`) and a request `pitch` (`2.0`) each give `INVALID_REQUEST` "Field 'engine' / 'pitch' is not supported by provider '<n>' of type GOOGLE_GEMINI". A request `speakingRate` is still accepted.
- [X] T043 [P] [US5] Add a test that a non-null `stylePrompt` gives `INVALID_REQUEST` "Field 'stylePrompt' is not supported by provider '<n>' of type <TYPE>", in each of:
  - `src/test/java/multiroom/tts/provider/cloud/google/GoogleVoiceResolverTest.java`
  - `src/test/java/multiroom/tts/provider/DefaultSettingsResolutionTest.java`, for `OPENAI`, `PIPER` and `LOCAL_HTTP`

  An empty `stylePrompt` (`""`) is rejected too: the caller asked for a prompt behaviour this type does not have.
- [X] T044 [P] [US5] In `src/test/java/multiroom/tts/service/VoiceQueryServiceTest.java`, add a case: listing voices of a `google-gemini` entry gives `INVALID_REQUEST` "Provider '<n>' of type GOOGLE_GEMINI does not support voice listing". Expected green: the provider does not implement `VoiceCatalogueProvider`.

### Implementation for User Story 5

- [X] T045 [US5] In `src/main/java/multiroom/tts/provider/cloud/google/GeminiSettingsResolver.java`, reject a request `engine` or `pitch` first, with the same wording `DefaultSettingsResolution.rejectIfSet` uses (data-model.md step 1). Makes T042 pass.
- [X] T046 [US5] Reject a non-null `stylePrompt` with that wording in `src/main/java/multiroom/tts/provider/DefaultSettingsResolution.java` (alongside `engine`, `pitch` and `speakingRate`) and in `src/main/java/multiroom/tts/provider/cloud/google/GoogleVoiceResolver.java` (first, before any parsing). Update both classes' Javadoc. Makes T043 pass. T044 is expected to pass with no main-code change.

**Checkpoint**: All five stories are complete.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T047 [P] Add a "Gemini voices (`google-gemini`)" section to `docs/google-cloud-tts-setup.md`:
  - Gemini voices need a service account key file (link the existing "Option B"), because API keys never work for them.
  - Enable the **Agent Platform API** and grant the service account **Agent Platform User**; quote the two 403 messages an operator will see without them.
  - There is **no free tier**, and Gemini is billed per token.
  - The models known on 2026-09-24, from spec.md Assumptions.
  - Choosing a voice: names such as `Kore` and `Charon`, any case, and not listable through `/voices`.
  - Writing a style prompt: default versus per request, `""` to turn it off, and that each distinct prompt is its own cache entry.
  - Raise `timeout-seconds` for a slower model or longer texts.
  - A warning that a `google-gemini` default provider makes every announcement without a `providerName` billable.
- [X] T048 [P] In `docs/configuration.md`, add `google-gemini` to the type list. Add `model` and `style-prompt` rows to the field reference table, and mark `speaking-rate` and `service-account-key-file` as `google-cloud` and `google-gemini`. Add a Google Gemini section with the YAML example from contracts/configuration.md and a pointer to the setup guide. Document `stylePrompt` wherever that file describes the request fields.
- [X] T049 [P] In `README.md`, add `google-gemini` to the provider list, with one line on the style prompt and one on its cost. Document the `stylePrompt` request field wherever README describes the request fields (beside `speakingRate`), and that only `google-gemini` accepts it.
- [X] T050 [P] Move `docs/future/google-cloud-gemini-tts-params.md` to `docs/archive/google-cloud-gemini-tts-params.md` with `git mv`. Prepend a one-paragraph note: it was implemented as feature 004 through **route A** (Text-to-Speech with a service account token), not the route B this note recommended, as a separate type `google-gemini`; speaking rate is honoured and pitch is rejected. Search `docs/`, `README.md` and `specs/004-gemini-tts-provider/` for links to the old path and fix them. Do not edit archived specs.
- [X] T051 In `AGENTS.md`, fill the `004-gemini-tts-provider` row of the Features table: what it delivered (a `google-gemini` type through Text-to-Speech with a service account, a style prompt per entry and per request, speaking rate, start-up faults and a cost warning) and version `0.1.3`. Leave its path under `specs/` until it is archived.
- [X] T052 Run `mvn verify`, including JaCoCo's 80% gate. Then:
  - Confirm `git diff master -- src/test/java/multiroom/tts/provider/GoogleCloudTtsProviderTest.java` is empty.
  - Confirm `mvn dependency:tree -Dscope=runtime` is unchanged and `unzip -l target/multiroom-tts-0.1.3.jar` lists no `com/google/` entries.
  - Run SpotBugs on the changed classes if it is available, and fix or justify its findings (constitution V).
- [ ] T053 Run [quickstart.md](quickstart.md) §2–§3 against the local core with a real service account key (merge gate, part 2):
  - `tts` is at `0.1.3` and not `REJECTED`.
  - Work through the behavioural table. It costs Gemini tokens, so keep it to a few short texts.
  - Confirm normal-speed playback (research R3).
  - Repeat one Gemini announcement and confirm the cache hit starts playing within 1 s, with no Google call in the log.
  - Search the core's log for secrets as in the SC-008 row.

  This needs the user's key file and a project with the Agent Platform API enabled; ask the user for both.
- [ ] T054 Run [quickstart.md](quickstart.md) §4 on production **only with the user's explicit permission** for `mvn deploy`, each restart and each configuration edit. Constitution VII requires all of it:
  - The existing `google` entry must announce with no edit, and a pre-upgrade text must be a cache hit.
  - One short announcement through a **non-default** `google-gemini` entry must play at the right speed and pitch on the Pi, which proves the 24 kHz source path there. If the user does not want Gemini on production, remove the entry afterwards.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: No dependencies.
- **Foundational (Phase 2)**: After Setup, and **blocks every story**. Order: T003 → T004 → (T005 ∥ T007) → T006 → T008. T006 and T008 both touch Google classes, so run them in sequence.
- **US1 (Phase 3)**: After Foundational. Tests T009–T015 come before T016–T020. T016 and T017 run in parallel, then T018 → T019 → T020.
- **US2 (Phase 4)**: After US1. It extends US1's resolver and provider. T027 comes before T028–T031.
- **US3 (Phase 5)**: After US2. It completes the branch T020 created and keeps T031's prompt check, and its tests share `TtsPropertiesValidationTest` with T026.
- **US4 (Phase 6)**: After US2, because T023 edits the same test file. Mostly tests of Phase 2's client.
- **US5 (Phase 7)**: T043 needs T027 (`RequestedSettings.stylePrompt`), and T042 needs T018.
- **Polish (Phase 8)**: After the stories it documents. T052 → T053 → T054 come last.
- **US6 (Phase 10)**, added 2026-09-26: after Phase 8's T052, and **before** T053 and T054, whose
  smoke run now covers it. Its own order is at the end of Phase 10.

### User story dependencies

```text
Setup → Foundational → US1 (P1, MVP) ─▶ US2 (P1) ─┬─▶ US3 (P1)
                                                   ├─▶ US4 (P2)
                                                   └─▶ US5 (P3)
                                                         └─▶ Polish
```

### Within each story

Write the tests first and see them fail. Then build in this order: pure value types → resolver →
provider → configuration and wiring.

## Parallel Example: User Story 1

```text
# Tests, all at once (different files):
T009 GeminiVoiceTest        T010 GeminiModelTest       T011 GeminiSettingsResolverTest
T012 GoogleGeminiTtsProviderTest   T013 AudioConverterTest   T014 TtsServiceTest
T015 TtsPropertiesValidationTest + TtsAutoConfigurationTest

# Then these two implementations together:
T016 GeminiVoice            T017 GeminiModel
```

## Parallel Example: After US2

```text
# US3, US4 and US5 tests touch different files from each other:
T032 → T033 TtsPropertiesValidationTest   T034 TtsAutoConfigurationTest
T038 → T039 → T040 GoogleGeminiTtsProviderTest (refusals, tokens, secrets)
T042 GeminiSettingsResolverTest   T043 GoogleVoiceResolverTest + DefaultSettingsResolutionTest
T044 VoiceQueryServiceTest
```

## Implementation Strategy

### MVP first (User Story 1)

1. Complete Phases 1–2. The Google plumbing is extracted, `google-cloud` is unchanged and the suite
   is green.
2. Complete Phase 3. A Gemini entry announces against WireMock, and a real key can already be
   tried locally.
3. **Stop and validate** with the quickstart §1 rows for Story 1.

### Incremental delivery

1. Add US2: the style prompt, which is the reason to use Gemini at all.
2. Add US3: operator-facing start-up faults and the cost warning. After this it is safe to hand the
   feature to an operator.
3. Add US4 and US5: run-time diagnosis and field-mismatch errors. The feature is then complete.
4. Polish: documentation, the gate, the smoke run, then production with permission.

## Notes

- `[P]` means the task touches different files and depends on no incomplete task.
- Commit after each task or logical group, using Conventional Commits with the scope
  `004-gemini-tts-provider`.
- `GoogleCloudTtsProviderTest` stays unmodified for the whole feature. Any need to change it means
  the extraction changed behaviour. Stop and fix `GoogleTtsClient` instead.
- `mvn deploy` without `-Plocal` is a production action: never run it without asking.
- Each live Gemini call costs tokens. Keep smoke-run texts short and few.

---

## Phase 9: Convergence

- [X] T055 In `README.md`, update the request-override bullet (line 19–20, "for Google Cloud also the engine, pitch and speaking rate") to say that a `google-gemini` entry accepts `speakingRate` and `stylePrompt` (and not `engine` or `pitch`), and that only `google-gemini` accepts `stylePrompt`; also mention Gemini voices in the Documentation table's `docs/google-cloud-tts-setup.md` row per T049 / FR-027 (partial)

---

## Phase 10: User Story 6 - List the Gemini Voices (Priority: P3)

**Added 2026-09-26**, after a live `GET v1/voices` showed that Google publishes the Gemini voices
as bare names (spec Clarifications 2026-09-26, research R13 revised). It replaces the "does not
support voice listing" behaviour that T044 tests and the "does not implement
`VoiceCatalogueProvider`" assertion of T012. Both stay checked as history; T057 and T058 replace
their tests.

**Goal**: `GET /api/tts/providers/<gemini>/voices` lists the Gemini voices from Google's list, each
with the entry's model as `engine`, its `gender` and no `language`. A well-formed `language` does
not narrow the list, `engine` is rejected, and announcing never consults the list. `google-cloud`
lists the same voices as before, each with `gender`.

**Independent Test**: WireMock serves a `v1/voices` fixture mixing full names and bare Gemini
names. The Gemini entry lists exactly the bare ones, the same with `language=uk-UA`; a
`google-cloud` entry lists exactly the full ones, as before; no fetch happens at construction or
while announcing.

The conventions at the top of this file apply, including **Keep old constructors** (now also for
`CatalogueVoice`) and an **unmodified** `GoogleCloudTtsProviderTest`.

### Tests for User Story 6 (write first, must fail)

- [X] T056 [P] [US6] In `src/test/java/multiroom/tts/provider/cloud/google/GoogleVoiceCatalogueTest.java`, add these cases (and add `ssmlGender` to the fixture where useful):
  - With the default selector, a fixture holding `uk-UA-Chirp3-HD-Kore` (`FEMALE`) and a bare `Kore` lists only the full name, now with `gender` `FEMALE`. Update the existing `Polyglot` equality assertion to the five-component form where its fixture gains a gender; a voice without `ssmlGender` has `gender` `null`.
  - With a Gemini selector (built as `GoogleGeminiTtsProvider` will build it, or through a small static factory on the catalogue, e.g. `GoogleVoiceCatalogue.geminiSelector(model)`), the same fixture lists only `Kore`, with `fullName` and `shortName` `Kore`, `engine` equal to the model and `language` `null`. A bare `kORE` comes back as `Kore`; a bare name that fails `GeminiVoice.isWellFormed` (e.g. `Kore1`) is skipped.
  - `list(null, null, …)` over Gemini voices (all with `null` language) sorts by short name and does not throw.
  - TTL, back-off and single-flight need no new cases: they are selector-independent.
- [X] T057 [P] [US6] In `src/test/java/multiroom/tts/provider/GoogleGeminiTtsProviderTest.java`, add listing cases against WireMock (`GET /v1/voices` stubbed with the mixed fixture, token endpoint as before):
  - The provider **implements** `VoiceCatalogueProvider`. Replace the T012 assertion that it does not.
  - Construction makes zero requests (the existing case stays green).
  - `listVoices(null, null)` makes one token request and one `GET /v1/voices` with `Authorization: Bearer` and no `key` parameter, and returns only the Gemini voices, sorted by name, with the model and gender.
  - `listVoices("uk-UA", null)` returns the same list; a second listing within `voice-catalogue.ttl` makes no second `GET`.
  - `listVoices(null, "chirp3-hd")` gives `INVALID_REQUEST` "Filter 'engine' is not supported by provider '<n>' of type GOOGLE_GEMINI", and makes no request.
  - A `GET /v1/voices` answered 403 gives `VOICE_CATALOGUE_UNAVAILABLE` naming the entry and carrying Google's explanation; a second listing within `failure-backoff` makes no `GET`.
  - `synthesize` for a voice absent from an already-fetched list still POSTs to `text:synthesize`, and a `synthesize` with no listing before it makes no `GET /v1/voices`.
  - Secrets on the listing path, mirroring T040: across a token endpoint answering 400 `invalid_grant`, a token endpoint timing out and a `GET /v1/voices` answered 403, capture every log line (the catalogue's `WARN` included) and every exception message, and assert that none contains the private key's base64 (`TestServiceAccountKeys.privateKeyBase64()`), the WireMock-issued token, or `eyJ`.
  - In `src/test/java/multiroom/tts/provider/cloud/google/GoogleTtsClientTest.java`, add `fetchVoices` cases for the shared fetcher T062 moves there: a 200 returns the body; a 403 throws `IOException` whose message is "HTTP 403: <Google's explanation>"; a token failure throws `IOException` carrying the token failure's message.
- [X] T058 [P] [US6] In `src/test/java/multiroom/tts/service/VoiceQueryServiceTest.java`, replace T044's case: a `google-gemini` entry backed by a mocked `VoiceCatalogueProvider` (a mock implementing both interfaces, as the `google-cloud` case does) is asked for its voices, and a malformed `language` is still `INVALID_REQUEST` before the provider is called.
- [X] T059 [P] [US6] In `src/test/java/multiroom/tts/rest/TtsVoiceControllerTest.java`, add these cases:
  - A `google-cloud` voice's JSON carries `gender` alongside the four existing fields.
  - A Gemini voice (`language` `null`) is serialized **without** a `language` key, and with `engine` equal to the model.

### Implementation for User Story 6

- [X] T060 [US6] In `src/main/java/multiroom/tts/provider/cloud/google/CatalogueVoice.java`, add `String gender` as the last component, keeping the four-component constructor as a secondary one passing `null`. Update the Javadoc: `language` is `null` for a Gemini voice; `gender` is Google's `ssmlGender`. Replace `fromName(String)` with `fromJson(JsonNode)`, a factory reading a whole `voices[]` element (`name` and `ssmlGender`), so the gender is read; it is the default selector T061 uses. Update `fromName`'s callers (tests included) to `fromJson` rather than keeping both.
- [X] T061 [US6] In `src/main/java/multiroom/tts/provider/cloud/google/GoogleVoiceCatalogue.java`:
  - Add a voice selector, `Function<JsonNode, Optional<CatalogueVoice>>`, as a constructor parameter. The existing constructor keeps its signature and uses the full-name selector, so `GoogleCloudTtsProvider` needs no change beyond what T062 does.
  - Add the Gemini selector (a static factory taking the model): a name passing `GeminiVoice.isWellFormed`, canonicalized by `GeminiVoice.parse`, gives `CatalogueVoice(name, name, model, null, gender)`; anything else is skipped.
  - Make `ORDER` null-safe on `language` (`Comparator.nullsFirst`).
  - Update the class Javadoc: one entry's copy, of either Google type; "the spec's *Voice Catalogue*" (002) or *Gemini Voice List* (004).

  Makes T056 pass.
- [X] T062 [US6] In `src/main/java/multiroom/tts/provider/cloud/GoogleGeminiTtsProvider.java`, implement `VoiceCatalogueProvider`:
  - **Move** the fetcher out of `GoogleCloudTtsProvider.fetchCatalogue` into `src/main/java/multiroom/tts/provider/cloud/google/GoogleTtsClient.java` as `String fetchVoices(Duration budget) throws IOException`: `get(resolve("voices"), deadline)`, `TtsException` and non-2xx → `IOException` with Google's explanation, message texts unchanged. Fetching the voice list is now Google plumbing both types need, so it exists once (constitution I, DRY; spec FR-025). `GoogleCloudTtsProvider` builds its catalogue with `client::fetchVoices` and drops its own `fetchCatalogue`, `voicesEndpoint` and `explanation`; `GoogleCloudTtsProviderTest` must stay unmodified.
  - Build one `GoogleVoiceCatalogue` per entry in the constructor, with the Gemini selector for the configured model and `client::fetchVoices` as its fetcher.
  - `listVoices(language, engine)`: `engine` non-null → `INVALID_REQUEST` "Filter 'engine' is not supported by provider '<n>' of type GOOGLE_GEMINI"; otherwise `catalogue.list(null, null, fetchTimeout)`. The `language` parameter is ignored here because `VoiceQueryService` has already checked its form, and Gemini voices are not tied to a language: say so in the method's Javadoc.
  - `synthesize` and `resolveSettings` stay untouched: no catalogue call.
  - Update the class Javadoc: it lists voices from Google's list but never checks an announcement's voice against it, because the list names no model.

  Makes T057 pass.
- [X] T063 [US6] In `src/main/java/multiroom/tts/rest/VoiceListResponse.java`, add `String gender` to `VoiceDescriptor`, map it in `of`, and annotate the record `@JsonInclude(JsonInclude.Include.NON_NULL)`. Update its Javadoc (Gemini: `shortName` = `fullName`, `engine` = the model, no `language`; sorted by name). In `src/main/java/multiroom/tts/rest/TtsVoiceController.java`, change the `@ApiResponse(responseCode = "200")` description to "Matching voices; `google-cloud`: sorted by language, engine, then short name; `google-gemini`: sorted by name", and match it in the contract's `'200'` description in `specs/004-gemini-tts-provider/contracts/tts-rest-api.yaml`. Update the Javadoc of `TtsVoiceController`, `src/main/java/multiroom/tts/provider/VoiceCatalogueProvider.java` (its sort order too) ("a listed voice is an accepted voice" holds for `google-cloud` only; `google-gemini` lists without checking) and `src/main/java/multiroom/tts/service/VoiceQueryService.java` if its Javadoc names the listing types. Makes T058 and T059 pass.

### Documentation for User Story 6

- [X] T064 [P] [US6] In `docs/google-cloud-tts-setup.md`, rewrite the Gemini "Choosing a voice" text (around line 237, "no catalogue to check it against and no `/voices` listing"): `GET /api/tts/providers/<gemini>/voices` lists the Gemini voices with their gender, the list comes from Google and is not per model, a `language` filter does not narrow it, and an announcement's voice is still not checked. Add `gender` to the `google-cloud` listing example if it shows a response.
- [X] T065 [P] [US6] In `docs/configuration.md`: document Gemini voice listing where the voice-listing section is (around line 152–170), including the `gender` field for both types; note that the `voice-catalogue` settings now also govern `google-gemini` entries.
- [X] T066 [P] [US6] In `README.md`, extend the voice-listing mention (line 21–24) so it covers `google-gemini` too; in `AGENTS.md`, add voice listing to the `004-gemini-tts-provider` row of the Features table.

### Gate

- [X] T067 [US6] Run `mvn verify` with JaCoCo's 80% gate; confirm `git diff master -- src/test/java/multiroom/tts/provider/GoogleCloudTtsProviderTest.java` is still empty and the runtime dependency tree unchanged. Then T053 and T054 (still open) cover the new quickstart §3 "Story 6" rows in the same smoke run; nothing extra is needed for them.

### Dependencies within Phase 10

T056–T059 can be written in parallel. Then T060 → T061 → T062 → T063 (T063 may run beside T062). The documentation tasks T064–T066 can run in parallel with the implementation. T067 comes last, before T053 and T054.
