# Changelog: Sonora TTS

## Merged Features Log

Newest first.

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
