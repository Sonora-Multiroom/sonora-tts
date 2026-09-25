# Main Specification: Sonora TTS

**Scope**: one extension for the multiroom audio system — text-to-speech announcements delivered to a
room or a group of rooms.

## What the product does

A caller (an automation, a script, Home Assistant) sends a line of text and a target. The extension
synthesizes speech through a configured provider, caches the audio, plays it on the target, and
restores whatever the room was doing before.

- **Trigger**: HTTP, on the host's single port, under this extension's own `/api/tts/**` paths. There
  is no separate authentication layer — access control is whatever guards the shared HTTP surface
- **Providers**: several may be configured at once, each with a name; one is the default (the first in
  the list if none is marked). A request may override the provider, the voice and the language
- **Cache**: on disk, keyed by text + provider type/model + voice/language + **the audio format the
  entry was converted to**. Survives restarts; LRU eviction at a configurable size
- **Playback**: the cached WAV is registered as an ephemeral input and routed to the target
  (`SINGLE_OUTPUT` or `OUTPUT_GROUP`, the host's own `TargetType`). Announcements queue behind
  whatever is playing
- **Completion**: observed, never estimated — the host destroys the announcement's route at EOF and
  publishes `RouteDestroyedEvent`; restoration hangs off that event
- **Queue**: in memory only. Unplayed announcements are dropped on restart; they are time-sensitive
  and worthless afterwards

## What it deliberately is not

- Not a second web server, not a second Spring context, not a bundled framework — it contributes into
  the host by auto-configuration (see [constitution.md](constitution.md), principle VIII)
- Not an audio-processing component: format conversion is delegated to the host's shared
  `FormatConverter`. This module implements no resampling, channel mixing or bit-depth conversion
- Not MQTT-triggered (deferred; it needs a cross-extension mechanism that is out of scope)

## Current state

**Implemented** in 0.1.0 by `001-tts-extension` (merged 2026-09-24, archived here 2026-09-25). It
was specified in the `multiroom-ai` monorepo as `021-tts-extension` and moved here on 2026-09-20,
before any code existed. The published REST contract is
[.specify/archive/001-tts-extension/contracts/tts-rest-api.yaml](../archive/001-tts-extension/contracts/tts-rest-api.yaml).

**Google voice selection** in 0.1.1 by `002-google-voice-selection` (merged and archived here
2026-09-25): `google-cloud` voices by full name or by engine + language + short name, checked
against a per-entry voice catalogue, with pitch and speaking rate, and a voice-listing endpoint. Its
contract ([tts-rest-api.yaml](../archive/002-google-voice-selection/contracts/tts-rest-api.yaml),
v0.1.1) supersedes 001's.

**Service-account authentication** in 0.1.2 by `003-google-service-account-auth` (merged and
archived here 2026-09-25): a `google-cloud` entry may name a service account key file instead of an
API key, and then signs in with short-lived access tokens. No new endpoint or error code; its
contract ([tts-rest-api.yaml](../archive/003-google-service-account-auth/contracts/tts-rest-api.yaml),
v0.1.2) is 002's plus a changelog entry.

In progress, not yet archived: `004-gemini-tts-provider` — read [specs/](../../specs/).

## User stories

| Story | Priority | Outcome | Source |
|---|---|---|---|
| Announce on one room | P1 | Text + `SINGLE_OUTPUT` target plays within 5 s; the room then returns to its prior route, or silence. Empty text is an error and plays nothing | 001 |
| Google failure explains itself; a full voice name just works | P1 | `uk-UA-Chirp3-HD-Charon` with no language plays in `uk-UA`; a language contradicting the voice is a 400; Google's own error message reaches the caller | 002 |
| Choose a Google voice by engine, language and short name | P1 | `engine: chirp3-hd`, `language: uk-UA`, `voice: charon` → `uk-UA-Chirp3-HD-Charon`; any part overridable per request; case-insensitive; pre-002 full-name configs unchanged | 002 |
| Announce with a Google service account | P1 | An entry with only `service-account-key-file` plays exactly as an API-key entry would — same voices, audio and cache; one token reused until near expiry, one fetch for concurrent requests | 003 |
| A broken Google credential is caught at start-up | P1 | Neither or both of API key and key file, an unreadable or wrong-kind file, a missing identity or unusable private key, or a key file on a non-Google entry — each aborts start-up naming the entry. Start-up never contacts Google | 003 |
| Announce on a group | P2 | `OUTPUT_GROUP` target plays on every output of the group at once. A group with no outputs is an error | 001 |
| Serve repeats from cache | P2 | Same text + provider + voice + language (+ format) plays with no provider call; a different provider is a miss; LRU eviction when full; manual clear forces re-synthesis | 001 |
| A wrong Google voice is caught early | P2 | A voice absent from the catalogue is a 400 `INVALID_VOICE` listing the voices for that engine and language; an unreachable catalogue skips the check | 002 |
| A rejected Google credential explains itself | P2 | A revoked key or a denied token fails with a 503 carrying Google's explanation, never a secret; an unreachable token service is held off for the back-off; recovery needs no restart | 003 |
| Choose the provider | P3 | No provider in the request uses the default; a named one overrides it; an unknown or unreachable one is an error and plays nothing | 001 |
| Adjust Google pitch and speaking rate | P3 | Configured per entry, overridable per request, sent only when set, part of the cache key; out of range is a 400 (or a start-up fault) | 002 |
| List a provider's voices | P3 | `GET /api/tts/providers/{name}/voices`, filterable by language and engine, from the same catalogue that validates | 002 |
| Queue announcements | P4 | Requests for one busy target play in arrival order; queued ones are discarded at shutdown | 001 |

Sources: [001](../archive/001-tts-extension/spec.md), [002](../archive/002-google-voice-selection/spec.md),
[003](../archive/003-google-service-account-auth/spec.md).

## Requirements

IDs belong to the feature that defined them and are cited as `NNN/FR-nnn` (`001/FR-008`,
`002/FR-017`); they are not a memory-wide sequence, because each feature restarts at FR-001. Read
the archived spec behind an ID for its full wording.

### Platform contract

- **001/FR-001** A drop-in extension under the 019 shared-classloader model: no bootstrap code, no
  private framework, switchable off by `multiroom.tts.enabled`, listed in the extension inventory
- **001/FR-028** Configuration faults (missing credentials, a configured path that does not exist,
  an unparseable block) abort start-up with a report naming this extension
- **001/FR-029** An unreachable provider is never an initialisation failure: no provider is
  contacted at start-up, and requests recover without a restart once it returns
- **001/FR-026** No TTS-specific authentication; access control is whatever guards the shared HTTP
  surface
- **001/FR-031** Format conversion only through the injected
  `multiroom.api.conversion.FormatConverter` (promoted from core, released in API 0.1.18); no
  resampling, mixing or bit-depth code here

### Request and trigger

- **001/FR-002, FR-030** Input: text, target name, `TargetType`, optional provider, voice and
  language — each override falls back to the chosen provider's configured default
- **001/FR-012** Blank text is rejected, and so is text over `max-text-length` (default 500)
- **001/FR-014** Triggered over HTTP under `/api/tts/**`; MQTT deferred
- **001/FR-032** Every failure answers **synchronously** with `{error, message}` — 400 for
  caller-fixable faults (`INVALID_REQUEST`, `TARGET_NOT_FOUND`, `PROVIDER_NOT_FOUND`), 503 for
  provider-side ones (`PROVIDER_TIMEOUT`, `PROVIDER_RATE_LIMITED`, `PROVIDER_ERROR`,
  `FORMAT_NORMALIZATION_FAILED`). One mapping, exactly one status per code. 002 added
  `INVALID_VOICE` (400) and `VOICE_CATALOGUE_UNAVAILABLE` (503) to the same mapping (002/FR-021)
- **002/FR-009, FR-020** A request may also override `engine`, `pitch` and `speakingRate` — for
  `google-cloud` only. Sending any of them to another provider type is a 400 naming the field and
  the type, before any synthesis
- **001/FR-027** Structured log events: request received, cache hit/miss, synthesis
  started/completed/error, playback started/completed

### Providers

- **001/FR-003, FR-004, FR-013** Several named providers, cloud and local, each with its own type,
  credentials and settings (voice, language, rate, model/engine). Types: `OPENAI`, `GOOGLE_CLOUD`,
  `PIPER` (piper1-gpl, `python3 -m piper`), `LOCAL_HTTP`
- **001/FR-005** Default provider: explicit, or the first in the list
- **001/FR-010, FR-011, FR-021** Clear errors for an unknown target, an unreachable or failing
  provider, and a rate limit — never an automatic retry
- **001/FR-022** Per-provider synthesis timeout, default 10 s; a longer one is allowed and warned
  about by name at start-up
- **001/FR-023** Audio the shared converter refuses is rejected; nothing plays

### Google Cloud voices

[Source: .specify/archive/002-google-voice-selection] — none of this changes OpenAI, Piper or local
HTTP: their voice, language and `en-US` fallback behave exactly as in 001 (002/FR-020).

- **002/FR-001** A Google failure's message carries Google's own `error.message` (truncated to 500
  characters), or at least the status code; codes and HTTP statuses are unchanged
- **002/FR-004** A voice is a **full name** `{language}-{engine}-{voice}` (`uk-UA-Chirp3-HD-Charon`)
  or a **short name** (`Charon`), matched case-insensitively and resolved to canonical case — the
  catalogue's spelling when available, otherwise by rule
- **002/FR-002, FR-003** Language order: request → the full voice's own prefix → the entry's
  default language → `en-US` only when no voice is set anywhere. A request language contradicting a
  full voice name is a 400
- **002/FR-005, FR-006** A short name is composed with the request's engine or the entry's default
  engine — other engines are never searched. Engines: Standard, Wavenet, Neural2, Studio, Chirp-HD,
  Chirp3-HD, under their common spellings. A full name with an unrecognized engine
  (`en-US-Polyglot-1`) is still accepted and checked for exact existence; short names are never
  composed for it
- **002/FR-007, FR-008** Every entry has a default engine (`engine`, or the engine of a full
  `voice`) and a default language (`language`, or the prefix of a full `voice`); an entry with
  neither aborts start-up. Pre-002 full-name configurations start unchanged
- **002/FR-010** Start-up checks only the **format** of engine, language and voice — never the
  catalogue
- **002/FR-011, FR-012** On a cache miss, the resolved voice is checked against the entry's own
  catalogue (remembered 24 h, never shared between entries). A voice the catalogue lacks triggers
  one refetch if the copy is older than the back-off, then a 400 `INVALID_VOICE` naming the voice,
  engine, language and the available voices
- **002/FR-013** An unreachable catalogue skips the check and synthesis proceeds; the failure is
  remembered for a back-off (60 s) during which the catalogue is not contacted
- **002/FR-014, FR-015** Pitch [-20, 20] and speaking rate [0.25, 2.0] per entry and per request,
  sent only when set; out of range aborts start-up or is a 400. `extra-params` on `google-cloud` must
  be empty — never accepted and ignored
- **002/FR-016** Everything uses Google's stable `v1` interface
- **002/FR-018, FR-019** `GET /api/tts/providers/{name}/voices?language=&engine=` lists short name,
  full name, engine and language from the same catalogue; a non-Google provider is a 400, an
  unreachable catalogue a 503

### Google Cloud credentials

[Source: .specify/archive/003-google-service-account-auth] — `google-cloud` only; OpenAI, Piper and
local HTTP are unaffected (003/FR-020). How an entry authenticates never changes what it
synthesizes, how voices resolve or validate, or how audio is cached (003/FR-014).

- **003/FR-001, FR-002** An entry sets **exactly one** of `api-key` and `service-account-key-file`
  (a path, never inline JSON; relative to the host's working directory; no `~` expansion). Neither
  or both aborts start-up
- **003/FR-003, FR-013** The key file is read and validated **once**, at start-up: it must exist,
  be JSON with `"type": "service_account"`, a `client_email`, a PKCS#8 `private_key` usable for
  signing, and a `token_uri` (if any) that is `https` or loopback. Every fault names the entry and
  the resolved absolute path. Rotating the key needs a restart
- **003/FR-004** Start-up makes no network request; the first token fetch happens on the first
  synthesis, voice check or listing
- **003/FR-005** `service-account-key-file` on any other provider type aborts start-up
- **003/FR-006** API-key entries behave exactly as before, with no edits
- **003/FR-007, FR-008** A service-account entry sends a bearer token — never an API key — on
  synthesis and the voice catalogue, obtained from the key file's `token_uri` (or Google's standard
  address) with the `cloud-platform` scope, broad enough for a later Gemini provider
- **003/FR-009, FR-010, FR-011** One token per entry, reused until shortly before expiry, one fetch
  shared by concurrent requests, never shared between entries
- **003/FR-012** A token Google rejects as invalid (401) is discarded, renewed and the request
  resent once; a second rejection is reported
- **003/FR-015, FR-016, FR-017** A token failure is a provider-side error naming the entry and
  carrying Google's explanation, with **existing** codes (`PROVIDER_ERROR`, `PROVIDER_TIMEOUT`,
  `PROVIDER_RATE_LIMITED`); the fetch counts against the synthesis time budget
- **003/FR-018** No private key, signed assertion or access token appears in any message, log line,
  exception or response
- **003/FR-019** An unreachable or timed-out token service, a 5xx or a 429 is remembered for the
  voice catalogue's `failure-backoff` (60 s); requests needing a new token fail at once meanwhile.
  A still-valid token keeps being used. An explicit rejection of the key is never remembered
- **003/FR-021** The setup guide documents the service-account path; the configuration reference
  lists the setting and its exclusivity with `api-key`

### Playback

- **001/FR-006, FR-007** Register an ephemeral input for the WAV and route it to the output, or to
  the group as **one** route (one pipeline, hence rooms in sync)
- **001/FR-008** Restore the target's prior routes when the announcement ends, detected from
  `RouteDestroyedEvent`, never a timer. The input is registered with `autoRemove = true` and this
  module never unregisters it itself
- **001/FR-009** A per-target in-memory queue that serializes **playback only**: validation, cache,
  synthesis and conversion finish before the response, so what is queued already has its file
- **001/FR-024** Shutdown lets the current announcement finish and discards the queue.
  `enabled=false` is a start-up state, never a runtime transition

### Cache

- **001/FR-015, FR-016** Keyed by text + provider type + model/engine + voice + language + target
  audio format; a hit never contacts the provider
- **002/FR-017** For `google-cloud` the key uses the resolved full voice name (case-folded, so
  spelling doesn't matter), the resolved language, and pitch and speaking rate when they differ from
  Google's defaults. Same audio → one entry; different audio → never shared. Pre-002 entries may
  miss once after the upgrade
- **001/FR-017, FR-018, FR-019** On disk, survives restarts, LRU at a configurable size, clearable
  on request
- **001/FR-020** A missing or corrupt entry is a miss and is replaced
- **001/FR-025** A failed cache write still plays, from a temp file outside the cache that is
  deleted afterwards, with a warning

## Key entities

[Source: .specify/archive/001-tts-extension/data-model.md,
.specify/archive/002-google-voice-selection/data-model.md,
.specify/archive/003-google-service-account-auth/data-model.md]

- **Speak request** — text, target name, `TargetType`, optional provider / voice / language, and
  (Google only) engine / pitch / speaking rate
- **Provider configuration** — name, type, credentials, default voice and language, engine,
  timeout, and (Google only) pitch and speaking rate; the list plus `default-provider` under
  `multiroom.tts`. Since 002, `language` has no declared default: the `en-US` fallback lives in the
  non-Google resolution
- **Requested / resolved settings** — the request's overrides, and what a provider resolves them to
  before the cache lookup, with no I/O: voice, language, engine, pitch, speaking rate, plus the
  normalized forms the cache key uses. Keeps `TtsService` provider-agnostic
- **Google voice name** — `Full` (language, engine segment, voice) or `Short` (voice); parsed,
  canonicalized, composed
- **Google engine** — the six recognized families, each with a canonical spelling and aliases
- **Voice catalogue** — one per `google-cloud` entry: empty, loaded or failed; TTL, failure
  back-off, single-flight fetch, lock-free warm lookups. Global timings under
  `multiroom.tts.voice-catalogue`
- **Google credential** — per `google-cloud` entry, exactly one of an API key (`?key=`) or a
  service account (bearer token). Not part of the cache key beyond the provider name
- **Service account key** — the key file's `client_email`, PKCS#8 private key, optional
  `private_key_id`, `project_id` and `token_uri`. Read once at start-up; its `toString` prints only
  the email and path
- **Access token** — per entry: value, renew-at (5 min before expiry) and expires-at, in an
  immutable snapshot; single-flight fetch; back-off state for an unavailable token service
- **Cache entry** — SHA-256 over every key dimension names `<hash>.wav`, a playable WAV in the
  native format; `index.json` records provider, `formatTag`, size and last access. An entry without
  a `formatTag` reads as a miss. Pinned entries are never evicted
- **Announcement task** — a ready-to-route announcement: its file, target and route snapshot. The
  per-target queue is bounded (default 10) and never persisted

## Request lifecycle

[Source: .specify/archive/001-tts-extension/data-model.md]

```text
RECEIVED → VALIDATED            (or REJECTED → 400)
  → RESOLVED    provider.resolveSettings: pure, no I/O          (or REJECTED → 400)
  → CACHE_CHECK → hit:  AUDIO_READY (pinned)                    — no catalogue work at all
                → miss: [Google service account: token first → or FAILED 503, check skipped]
                        [Google: catalogue check → INVALID_VOICE 400, or skipped if unreachable]
                        SYNTHESIZING (a 401 renews the token and resends once)
                        → CONVERTING → AUDIO_READY                (or FAILED → 503)
                        token, catalogue fetch and synthesis share the entry's timeout
  → QUEUED
  → ROUTING   snapshot + stop the target's routes; registerInput("tts://<uuid>", autoRemove);
              createRoute — core resolves tts:// to the WAV's file:// URI
  → PLAYING   … RouteDestroyedEvent
  → RESTORING re-create the snapshot, unpin, drop any temp file
  → COMPLETED
```

## Edge cases

[Source: .specify/archive/001-tts-extension]

- Unknown target or provider: rejected before any synthesis
- Concurrent requests for one target: both synthesized as they arrive, played in arrival order, no
  deduplication
- Cache disk full: plays anyway, caching skipped for that request
- Disabled extension: no beans for the whole run; "disabled mid-playback" cannot happen
- *(002)* A full voice name beats the default engine; a request engine that contradicts a full
  voice name is a 400 naming both
- *(002)* A short voice with no language anywhere: a start-up fault in configuration, a 400 in a
  request
- *(002)* A configured `language` contradicting a configured full `voice`: a start-up warning, not
  a fault. The voice keeps its own language; `language` stays the default for short names
- *(002)* A slow catalogue counts as unreachable, capped at `fetch-timeout` (3 s) inside the
  synthesis budget, and starts the back-off
- *(002)* A parameter the engine doesn't support (pitch on Chirp3-HD) is still sent; Google's
  rejection surfaces with its explanation. No per-engine support table
- *(002)* A `google-cloud` `engine` that was a free-text label before 002 now aborts start-up
  unless it names a recognized engine
- *(003)* Clock skew makes Google reject a token the entry thinks valid: one renewal and one resend,
  never more per request
- *(003)* A relative key path under systemd (working directory `/`) resolves there; the start-up
  error shows the resolved absolute path
- *(003)* The key file rotated or deleted after start-up: nothing changes until a restart
- *(003)* Two entries naming one key file: each holds its own token
- *(003)* A 403 (missing permission) is never retried — it is not a stale token — and is passed on
  with Google's explanation. The extension checks no IAM permissions itself
- *(003)* An organisation policy that forbids key creation: out of scope; only key files are
  supported
- *(003)* File permissions on the key are the operator's responsibility

## Success criteria

[Source: .specify/archive/001-tts-extension] — measured against a deployed core and on the Pi; nothing in this
repository boots the host.

| Criterion | Target | Status at archival |
|---|---|---|
| 001/SC-001 miss → playback | < 5 s with a 3 s provider | Not yet measured |
| 001/SC-002 group sync | ≤ 100 ms between rooms | One route structurally; Pi check open |
| 001/SC-003 unavailable provider | error < 10 s at the default timeout | By the 10 s default |
| 001/SC-004 switch provider | configuration + restart only | By design |
| 001/SC-005 queue order | 100 % in submission order | Unit-tested |
| 001/SC-006 restore | < 1 s after `RouteDestroyedEvent` (drain excluded) | Not yet timed |
| 001/SC-007 hit → playback | < 1 s | Not yet measured |
| 001/SC-008 cache transparency | same audio either way | By design |
| 002/SC-001 the 2026-09-24 incident | `uk-UA-Chirp3-HD-Charon`, no language, plays first time | Tests + production run (T068) |
| 002/SC-002 Google's explanation | 100 % of failures carry it when Google gives one | Unit-tested |
| 002/SC-003 self-correcting typo | the error lists every valid voice for the engine and language | Unit-tested |
| 002/SC-004 no config edits | production entries start and announce unchanged | Tested against the exact production entry; confirmed on `multiroom.lan` |
| 002/SC-005 start-up | zero calls to Google | Tested |
| 002/SC-006 warm check | no noticeable delay; a hit still < 1 s | A hit does no catalogue work by design |
| 002/SC-007 catalogue not a SPOF | unreachable catalogue → announcement still succeeds | Unit-tested |
| 002/SC-008 no ignored settings | every accepted pitch/rate reaches Google | Unit-tested; audible on Neural2 |
| 003/SC-001 key file only | an announcement plays first time, no API key created | Smoke run with a real key |
| 003/SC-002 API-key configs | production starts and announces with zero edits | Tested |
| 003/SC-003 start-up | zero network requests for a service-account entry | Tested |
| 003/SC-004 malformed credentials | 100 % abort start-up naming extension, entry and fault | Tested |
| 003/SC-005 token fetches | ≤ 2 per steady hour per entry | By the 5-minute renewal margin; tested with a `Clock` |
| 003/SC-006 overhead | a hit < 1 s; a miss with a valid token adds no request | Tested |
| 003/SC-007 Google's explanation | always passed on for a rejected key or token | Tested (OAuth error shape too) |
| 003/SC-008 no secrets | none in any log or error during tests or the smoke run | Tested by capturing all output |
| 003/SC-009 no library | no new third-party dependency | JDK `java.security` only |

## Host coupling

Built against `multiroom-api` **0.1.18**, resolved from the local `~/.m2`; the host repository is
`D:\projects-multiroom\multiroom-ai`. The required version is pinned in `pom.xml` and advertised in
the JAR manifest as `Require-API-Version`. See the Upstream Contract in
[constitution.md](constitution.md).

---

**Revision 2026-09-25**: archived `001-tts-extension` — state moved from specified to implemented;
user stories, requirements, entities, lifecycle, edge cases and success criteria folded in.

**Revision 2026-09-25**: archived `002-google-voice-selection` — Google voice stories, a "Google
Cloud voices" requirements group, cache-key and error-code additions, new entities, the resolve
step in the lifecycle, edge cases and success criteria. Corrected the earlier note that 002 added
one error code: it added two.

**Revision 2026-09-25**: archived `003-google-service-account-auth` — service-account stories, a
"Google Cloud credentials" requirements group, credential/key/token entities, the token step in the
lifecycle, edge cases and success criteria.
