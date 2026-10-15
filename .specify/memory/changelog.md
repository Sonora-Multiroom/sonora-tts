# Changelog: Sonora TTS

## Merged Features Log

Newest first.

### 006 Announcements Over What Is Playing (Ducking) — archived 2026-10-15
**Branch:** `006-announcement-ducking`
**Spec:** [.specify/archive/006-announcement-ducking/spec.md](../archive/006-announcement-ducking/spec.md)
**Release:** `multiroom-tts` 0.1.5, requires `multiroom-api` 0.1.21 (multiroom-ai 023; host first)

**What was added:**

- An announcement joins its target beside whatever plays there instead of stopping the target's
  routes and recreating them afterwards: in `duck-others` (default; the host lowers the rest and
  restores it) or `mix` (equal level). Accepting it into the queue changes nothing audible
- The playback mode per request (`playbackMode`) and by configuration
  (`multiroom.tts.playback.default-mode`); only `duck-others` and `mix`, case-insensitive; anything
  else is 400 `INVALID_REQUEST` before any synthesis, or a start-up fault. Not part of the cache key
- A route the host refuses (route limit) releases everything the announcement reserved, unregisters
  its input, is logged `TTS_PLAYBACK_REFUSED` at WARN with the host's reason and output, counts as a
  failed playback and is not retried; the queue moves on
- Group announcements stay one route; queues stay per target, so overlapping targets may play at
  once, the newer on top
- README, `docs/configuration.md`, `docs/metrics.md` and REST contract v0.1.5 updated

**New Components:**

- `PlaybackMode` and `PlaybackProperties` in `multiroom.tts.config`; `playbackMode` on
  `SpeakRequest`, `AnnounceCommand` and `AnnouncementTask` (which lost its route snapshot)
- Removed: `TtsService.snapshotAndStopExistingRoutes`, `PlaybackCompletionListener.restoreRoutes` and
  the listener's `RouteService` dependency
- Starter parent and `multiroom.require_api_version` raised to 0.1.21

**Superseded:**

- RETIRED: 001/SC-006 (from .specify/archive/001-tts-extension/spec.md) → replaced by 006/SC-002. Reason: the route restore it timed no longer exists (006/FR-002); the host's ducking release, measured by 006/SC-002, took its place
- RETIRED: entity "Announcement task" — 001's definition (from .specify/archive/001-tts-extension/data-model.md; directory-level ref only) → replaced by 006's definition of "Announcement task". Reason: the task carries the playback mode instead of a route snapshot; the bounded-queue sentence was kept

**Tasks Completed:** 36/36 tasks — including T035 (local-core smoke run against the 023 host) and
T036 (production verification, results recorded in the pull request)

### 005 TTS Metrics — archived 2026-10-15

**Branch:** `005-tts-metrics`
**Spec:** [.specify/archive/005-tts-metrics/spec.md](../archive/005-tts-metrics/spec.md)
**Release:** `multiroom-tts` 0.1.4, still requires `multiroom-api` 0.1.18 (no upstream change)

**What was added:**

- `tts_*` metrics in the host's Micrometer registry, on the host's existing `/actuator/prometheus`:
  announcements by outcome, error code, rejection reason and (configured) provider; synthesis time
  per provider as a histogram reaching the longest timeout; characters sent by part and billing
  tier, and seconds of audio returned; cache hits and misses, size, entries and limit; queue depth
  per target; playbacks started and failed
- Billable usage defined and measured once, in `TtsService.synthesize` before the provider call,
  with the billing tier — groundwork for a later monthly character budget
- A queue rejection carries `queue_full` or `shutting_down` without changing the caller's
  `PROVIDER_ERROR`
- An unreadable speak body now answers the contract's `ErrorResponse` with `INVALID_REQUEST`
- Bounded tags only; no metrics, and no failure, when the host has no Micrometer; none with
  `multiroom.tts.enabled=false`
- Operator guide `docs/metrics.md`, linked from the README and `docs/configuration.md`

**New Components:**

- `multiroom.tts.metrics`: `TtsMetrics`, `MicrometerTtsMetrics`, `NoopTtsMetrics`, `SynthesisUsage`
- `QueueRejectedException`; `TtsProvider.type()` and `billingTier(settings)`;
  `ProviderType.configName()`; `ProviderRegistry.isConfigured(name)`;
  `AnnouncementQueueManager.depth(key)`
- Dependencies: `micrometer-core` (`provided`), `micrometer-registry-prometheus` (`test`)

**Tasks Completed:** 49/49 tasks — including T048 (local-core run) and T049 (production Pi check,
performed by the user)

### 004 Gemini TTS Provider — 2026-09-26 (archived 2026-09-26)

**Branch:** `004-gemini-tts-provider`
**Spec:** .specify/archive/004-gemini-tts-provider
**Release:** `multiroom-tts` 0.1.3, still requires `multiroom-api` 0.1.18 (no upstream change)
**Commit:** `d7bf31f`

**What was added:**

- A `google-gemini` provider type: Gemini-TTS through Google's Text-to-Speech `v1` endpoint with a
  service account token (route A), with a required model, voice and language per entry
- A style prompt per entry (`style-prompt`) and per request (`stylePrompt`, `""` turns the default
  off), stripped, sent separately from the text, and part of the cache key only when in effect —
  every existing cache entry stays a hit
- Speaking rate on 002's rules; pitch, `engine`, `extra-params` and an API key rejected at start-up;
  a warning when a Gemini entry is the default provider, since it has no free tier
- Voice listing for `google-gemini` from Google's own voice list (model as engine, no language,
  never used to check an announcement); listed voices of both Google types now show gender
- Setup guide, configuration reference, README and REST contract v0.1.3 updated; the Gemini planning
  note moved to `docs/archive/`

**New Components:**

- `GoogleGeminiTtsProvider`; in `provider.cloud.google`: `GoogleTtsClient` (shared Google
  plumbing extracted from `GoogleCloudTtsProvider`), `GeminiSettingsResolver`, `GeminiVoice`,
  `GeminiModel`, `GoogleSpeakingRate`
- `ProviderType.GOOGLE_GEMINI`; `model` and `stylePrompt` on `TtsProviderConfig`; `stylePrompt` on
  `SpeakRequest`, `AnnounceCommand`, `RequestedSettings`, `SynthesisSettings` and `CacheKey`
- A voice selector on `GoogleVoiceCatalogue`; `gender` on `CatalogueVoice` and `VoiceDescriptor`

**Tasks Completed:** 67/67 tasks — including T053 (local-core smoke run with a real key, merge gate
part 2) and T054 (production Pi check of the Gemini 24 kHz path and the unedited classic entry)

### 003 Google Cloud Service Account Authentication — 2026-09-25 (archived 2026-09-25)

**Branch:** `003-google-service-account-auth`
**Spec:** .specify/archive/003-google-service-account-auth
**Release:** `multiroom-tts` 0.1.2, still requires `multiroom-api` 0.1.18 (no upstream change)
**Commits:** `750e1f2` (feature), `ef9612a` and `f308964` (docs)

**What was added:**

- `service-account-key-file` on a `google-cloud` entry, as the alternative to `api-key` (exactly one
  of the two). Synthesis and the voice catalogue then carry a short-lived bearer token
- The key file is read and validated once at start-up; every credential fault aborts start-up
  naming the entry and the resolved path, and start-up still contacts nothing
- One token per entry, renewed 5 minutes before expiry, one fetch for concurrent requests, a
  single renew-and-resend on 401, and the voice catalogue's back-off for an unreachable token
  service
- Google's explanation — including the OAuth error shape — in every token failure, with no new
  error code and no secret in any output
- Setup guide: a service account in the API's own project needs no role; Service Usage Consumer
  fixes a cross-project 403

**New Components:**

- `provider.cloud.google`: `GoogleCredential`, `ServiceAccountKey`, `ServiceAccountAssertion`,
  `GoogleTokenExchange`, `GoogleAccessTokenCache`, `TokenFailure`; `GoogleErrorBody` reads OAuth
  errors
- Test helper `TestServiceAccountKeys` (key pairs generated per test, none committed)

**Tasks Completed:** 39/39 tasks

### 002 Google Cloud Voice Selection — 2026-09-25 (archived 2026-09-25)

**Branch:** `002-google-voice-selection`
**Spec:** .specify/archive/002-google-voice-selection
**Release:** `multiroom-tts` 0.1.1, still requires `multiroom-api` 0.1.18 (no upstream change)
**Commit:** `25f8b35`

**What was added:**

- Google's own error message in every `google-cloud` failure, and the language taken from a full
  voice name — fixes the 2026-09-24 Ukrainian announcement that failed with a bare `HTTP 400`
- Google voices by full name or by engine + language + short name, case-insensitive, with a
  default engine and language per entry; pre-002 configurations start unchanged
- A per-entry voice catalogue (24 h TTL, 60 s failure back-off, never fetched at start-up) that
  rejects an unknown voice with `INVALID_VOICE` and the list of valid ones, and is skipped when
  unreachable
- Pitch and speaking rate for `google-cloud`, per entry and per request, in the cache key
- `GET /api/tts/providers/{name}/voices`, filterable by language and engine

**New Components:**

- `provider.cloud.google`: `GoogleEngine`, `GoogleLanguage`, `GoogleVoiceName`,
  `GoogleVoiceResolver`, `GoogleVoiceCatalogue`, `CatalogueVoice`, `GoogleErrorBody`
- `TtsProvider.resolveSettings`, `RequestedSettings`, `SynthesisSettings`,
  `DefaultSettingsResolution`, `VoiceCatalogueProvider`
- `VoiceQueryService`, `TtsVoiceController`, `VoiceListResponse`, `VoiceCatalogueProperties`
- Error codes `INVALID_VOICE` (400) and `VOICE_CATALOGUE_UNAVAILABLE` (503)
- JaCoCo 80 % coverage gate in `mvn verify`

**Breaking for operators:** a `google-cloud` `engine` used as a free-text cache label now aborts
start-up unless it names a recognized engine; `extra-params` on `google-cloud` must be empty.

**Tasks Completed:** 70/70 tasks

### 001 TTS Extension — 2026-09-24 (archived 2026-09-25)

**Branch:** `001-tts-extension` (`021-tts-extension` in `multiroom-ai` before the move)
**Spec:** .specify/archive/001-tts-extension
**Release:** `multiroom-tts` 0.1.0, requires `multiroom-api` 0.1.18
**Commit:** `7967629`

**What was added:**

- Spoken announcements on one output (`SINGLE_OUTPUT`) or an output group (`OUTPUT_GROUP`), with
  the target's prior routes restored when the announcement's route is destroyed
- Several named providers with a default and per-request provider, voice and language overrides
- A persistent on-disk LRU cache keyed by text, provider, engine, voice, language and target format
- An in-memory per-target queue that orders playback; synthesis and conversion stay in the request,
  so every failure is reported synchronously with a code and a 400/503
- Fail-fast on configuration faults; no provider contacted at start-up

**New Components:**

- `TtsAutoConfiguration` (the entry point) and `TtsProperties`
- Providers: `OpenAiTtsProvider`, `GoogleCloudTtsProvider`, `PiperTtsProvider` (piper1-gpl),
  `LocalHttpTtsProvider`, behind `TtsProvider` + `ProviderRegistry`
- `AudioConverter`, `WavFileWriter`, `TtsInputResolver` (`tts://` → `file://`)
- `FilesystemAudioCache`, `AnnouncementQueueManager`, `TtsService`, `PlaybackCompletionListener`
- REST: `POST /api/tts/speak`, `DELETE /api/tts/cache`, `GET /api/tts/cache/stats`, with the
  scoped `TtsExceptionHandler`

**Tasks Completed:** 70/74 tasks — open: T028a (SC-001 timing), T032a (SC-002 on the Pi), T041a
(SC-007 timing), T058 (quickstart end to end on a live host, SC-004/SC-006)
