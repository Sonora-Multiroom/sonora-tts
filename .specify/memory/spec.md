# Main Specification: Sonora TTS

> **Revision**: 2026-10-15 — archived `.specify/archive/005-tts-metrics`: a metrics story set (with full
> story blocks under "Story details"), a "Metrics" requirements group, metrics entities, edge cases
> and success criteria, a new `## Assumptions` section (AS-001–AS-009), and 001/FR-032 annotated
> for the unreadable-body answer. Earlier revisions are recorded at the end of this file.

**Scope**: one extension for the multiroom audio system — text-to-speech announcements delivered to a
room or a group of rooms.

## What the product does

A caller (an automation, a script, Home Assistant) sends a line of text and a target. The extension
synthesizes speech through a configured provider, caches the audio, plays it on the target, and
restores whatever the room was doing before.

- **Trigger**: HTTP, on the host's single port, under this extension's own `/api/tts/**` paths. There
  is no separate authentication layer — access control is whatever guards the shared HTTP surface
- **Providers**: several may be configured at once, each with a name; one is the default (the first in
  the list if none is marked). A request may override the provider, the voice and the language —
  and, for `google-gemini`, the style prompt
- **Cache**: on disk, keyed by text + provider type/model + voice/language (+ style prompt, when one
  is in effect) + **the audio format the entry was converted to**. Survives restarts; LRU eviction
  at a configurable size
- **Playback**: the cached WAV is registered as an ephemeral input and routed to the target
  (`SINGLE_OUTPUT` or `OUTPUT_GROUP`, the host's own `TargetType`). Announcements queue behind
  whatever is playing
- **Completion**: observed, never estimated — the host destroys the announcement's route at EOF and
  publishes `RouteDestroyedEvent`; restoration hangs off that event
- **Queue**: in memory only. Unplayed announcements are dropped on restart; they are time-sensitive
  and worthless afterwards
- **Metrics** *(since 005)*: announcements, provider synthesis time and work, cache use and
  occupancy, queue depth and playbacks, recorded into the host's metrics registry and scraped from
  the host's existing `/actuator/prometheus`; no endpoint of its own
  [Source: .specify/archive/005-tts-metrics/spec.md -> "Overview"]

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

**Gemini voices** in 0.1.3 by `004-gemini-tts-provider` (merged 2026-09-26, archived here
2026-09-26): a separate `google-gemini` provider type reaching Gemini-TTS through Google's
Text-to-Speech `v1` endpoint with a service account token (route A), with a model, a voice, a
language, a style prompt per entry and per request, and speaking rate (pitch rejected). Its voices
are listed from Google's own list, and listed voices of both Google types now show a gender. No new
endpoint or error code; its contract
([tts-rest-api.yaml](../archive/004-gemini-tts-provider/contracts/tts-rest-api.yaml), v0.1.3)
adds `stylePrompt` to the request and `gender` to listed voices, and supersedes 003's.

**Metrics** in 0.1.4 by `005-tts-metrics` (archived here 2026-10-15): the extension records its own
`tts_*` metrics into the host's Micrometer registry, and measures billable usage (characters sent,
by billing tier) once, before every provider call, as groundwork for a later character budget. No
new endpoint or field; one behaviour change: an unreadable speak body now gets the contract's
`ErrorResponse` with `INVALID_REQUEST`. Its metrics contract is
[.specify/archive/005-tts-metrics/contracts/metrics.md](../archive/005-tts-metrics/contracts/metrics.md)
(v0.1.4); the operator guide is [docs/metrics.md](../../docs/metrics.md). The REST contract stays
004's v0.1.3.

Features in progress live under [specs/](../../specs/) until they are archived here.

## User stories

| Story | Priority | Outcome | Source |
|---|---|---|---|
| Announce on one room | P1 | Text + `SINGLE_OUTPUT` target plays within 5 s; the room then returns to its prior route, or silence. Empty text is an error and plays nothing | 001 |
| Google failure explains itself; a full voice name just works | P1 | `uk-UA-Chirp3-HD-Charon` with no language plays in `uk-UA`; a language contradicting the voice is a 400; Google's own error message reaches the caller | 002 |
| Choose a Google voice by engine, language and short name | P1 | `engine: chirp3-hd`, `language: uk-UA`, `voice: charon` → `uk-UA-Chirp3-HD-Charon`; any part overridable per request; case-insensitive; pre-002 full-name configs unchanged | 002 |
| Announce with a Google service account | P1 | An entry with only `service-account-key-file` plays exactly as an API-key entry would — same voices, audio and cache; one token reused until near expiry, one fetch for concurrent requests | 003 |
| A broken Google credential is caught at start-up | P1 | Neither or both of API key and key file, an unreadable or wrong-kind file, a missing identity or unusable private key, or a key file on a non-Google entry — each aborts start-up naming the entry. Start-up never contacts Google | 003 |
| Announce with a Gemini voice | P1 | A `google-gemini` entry (key file, model, voice, language) plays in the Gemini voice with a bearer token; a repeat is a cache hit; request voice/language overrides are cached separately; a `google-cloud` entry sharing the key file is unaffected | 004 |
| Steer the delivery with a style prompt | P1 | An entry's default prompt, replaced per request or turned off with `""`, reaches Google separately from the text; each distinct prompt is its own cache entry; an over-long prompt is a 400 | 004 |
| A wrong Gemini configuration is caught at start-up | P1 | An API key, no key file, no or malformed model/voice/language, `engine`/`pitch`/`extra-params`, an out-of-range rate, an over-long default prompt, or `style-prompt`/`model` on another type — each aborts start-up naming the entry. A Gemini default provider only warns about per-token billing. Start-up never contacts Google | 004 |
| Announce on a group | P2 | `OUTPUT_GROUP` target plays on every output of the group at once. A group with no outputs is an error | 001 |
| Serve repeats from cache | P2 | Same text + provider + voice + language (+ format) plays with no provider call; a different provider is a miss; LRU eviction when full; manual clear forces re-synthesis | 001 |
| A wrong Google voice is caught early | P2 | A voice absent from the catalogue is a 400 `INVALID_VOICE` listing the voices for that engine and language; an unreachable catalogue skips the check | 002 |
| A rejected Google credential explains itself | P2 | A revoked key or a denied token fails with a 503 carrying Google's explanation, never a secret; an unreachable token service is held off for the back-off; recovery needs no restart | 003 |
| Gemini refusals explain themselves | P2 | A missing permission, disabled API, unknown model or voice, or an over-long text+prompt fails with the entry's name and Google's explanation, under the same codes as `google-cloud`; token failures behave exactly as in 003 | 004 |
| Choose the provider | P3 | No provider in the request uses the default; a named one overrides it; an unknown or unreachable one is an error and plays nothing | 001 |
| Adjust Google pitch and speaking rate | P3 | Configured per entry, overridable per request, sent only when set, part of the cache key; out of range is a 400 (or a start-up fault) | 002 |
| List a provider's voices | P3 | `GET /api/tts/providers/{name}/voices`, filterable by language and engine, from the same catalogue that validates | 002 |
| Settings go only to the entry that honours them | P3 | `stylePrompt` to a non-Gemini entry, or `engine`/`pitch` to a Gemini one (request or listing filter), is a 400 naming the field and type; `speakingRate` is accepted by both Google types | 004 |
| List the Gemini voices | P3 | A `google-gemini` entry lists every Gemini voice in Google's list with the entry's model and a gender, sorted by name; a language filter is form-checked but does not narrow; listing is never used to check an announcement. `google-cloud` listings are unchanged but gain gender | 004 |
| Queue announcements | P4 | Requests for one busy target play in arrival order; queued ones are discarded at shutdown | 001 |
| See announcement volume and failures | P1 | Accepted and rejected announcements counted, each rejection by its error code; an unconfigured provider name never becomes a tag; the `accepted` series exist at zero from start-up | 005 |
| Compare provider latency and reliability | P1 | Per configured provider: synthesis time (miss only), failures by error code, characters sent and seconds of audio returned, so time per 1,000 characters and the real-time factor are comparable | 005 |
| Watch the cache work | P2 | Hit and miss counts; cache bytes, entries and limit matching `GET /api/tts/cache/stats`, and emptied after a clear | 005 |
| Spot backlog and playback failures | P3 | Queue depth per target, back to zero once drained; dequeued announcements counted as playback `started` or `failed` | 005 |

Sources: [001](../archive/001-tts-extension/spec.md), [002](../archive/002-google-voice-selection/spec.md),
[003](../archive/003-google-service-account-auth/spec.md), [004](../archive/004-gemini-tts-provider/spec.md),
[005](../archive/005-tts-metrics/spec.md).

### Story details

Stories archived before 005 keep their full blocks in the archived spec named in the Source column.
The actor in every story below is the **operator**: the person who runs the host, configures the
TTS providers and watches the system through Prometheus and Grafana.

#### See announcement volume and failures (Priority: P1)

[Source: .specify/archive/005-tts-metrics/spec.md -> User Story 1]

An operator opens their dashboard and sees how many announcements the extension accepted and
rejected over time, with each rejection broken down by its error code (`TARGET_NOT_FOUND`,
`PROVIDER_TIMEOUT`, `INVALID_VOICE` and the rest). A spike in one code tells them where to look
without reading logs.

**Why this priority**: It is the one number that answers "is TTS working?". Every other metric
explains a change in this one.

**Independent Test**: Send one valid announcement and one to a target that does not exist, then
read the host's metrics endpoint. One series shows one accepted announcement; another shows one
rejection tagged `TARGET_NOT_FOUND`.

**Acceptance Scenarios**:

1. **Given** the extension is enabled, **When** a caller sends an announcement that is accepted,
   **Then** the announcement count tagged `accepted` rises by one.
2. **Given** the extension is enabled, **When** a caller sends an announcement that is rejected
   with any error code, **Then** the announcement count tagged `rejected` and that error code
   rises by one.
3. **Given** a caller names a provider that is not configured, **When** the announcement is
   rejected with `PROVIDER_NOT_FOUND`, **Then** the rejection is counted without the unknown name
   appearing in any tag.
4. **Given** the host has just started, **When** the operator reads the metrics endpoint before
   any announcement, **Then** the announcement series are present with a count of zero for
   `accepted`.

#### Compare provider latency and reliability (Priority: P1)

[Source: .specify/archive/005-tts-metrics/spec.md -> User Story 2]

An operator sees, for each configured provider entry, how long synthesis takes and how often it
fails, and which way it fails. They can tell that `google-gemini` takes four times as long as
`google-cloud`, or that the local Piper engine has started timing out, and decide which provider
should be the default.

Raw synthesis time is not comparable on its own: a 300-character announcement takes longer than a
20-character one on any provider. So the operator also sees how much work each synthesis was: the
characters sent and the seconds of audio returned. From those they read time per 1,000 characters
and the real-time factor (seconds spent per second of speech produced), which compare providers
and spot a slowdown whatever the message mix. The character count is also what cloud providers
bill by, so it doubles as a usage figure per provider.

**Why this priority**: Providers are remote, billed and slow compared with everything else the
extension does. Their latency is the latency an announcement has, and their failures are the
failures an operator can act on (a quota, a key, a stopped local engine).

**Independent Test**: Configure two providers, synthesize with each (cache misses), make one fail,
and read the endpoint. Each provider has its own timing series with its type, and the failure is
recorded against the failing provider with its error code. The character total of each provider
equals the sum of the message lengths it synthesized.

**Acceptance Scenarios**:

1. **Given** a cache miss, **When** the provider synthesizes successfully, **Then** one timing is
   recorded tagged with the provider's configured name, its type and outcome `success`.
2. **Given** a cache miss, **When** the provider fails, **Then** one timing is recorded tagged with
   the provider's name, its type and the error code the announcement was rejected with (for
   example `PROVIDER_TIMEOUT`).
3. **Given** a cache hit, **When** the announcement is served, **Then** no synthesis timing is
   recorded.
4. **Given** a cache miss, **When** the provider is asked to synthesize a message of N characters,
   **Then** N is added to that provider's character total, whether the synthesis succeeds or
   fails.
5. **Given** a successful synthesis, **When** its audio lasts D seconds, **Then** D is added to
   that provider's audio-duration total.
6. **Given** two providers synthesizing messages of different lengths, **When** the operator
   divides each provider's total synthesis time by its total characters, **Then** the result is
   its time per character, comparable between the two.

#### Watch the cache work (Priority: P2)

[Source: .specify/archive/005-tts-metrics/spec.md -> User Story 3]

An operator sees the share of announcements served from the cache, and how full the cache is in
bytes and entries against its configured limit. A falling hit rate after a change to message
templates, or a cache stuck at its limit, is visible at a glance.

**Why this priority**: The cache is what keeps repeated announcements fast and free. Its hit rate
is the main lever on provider cost, but the service still works without the metric.

**Independent Test**: Send the same announcement twice, then read the endpoint. The miss count and
the hit count each show one, and the size gauges match `GET /api/tts/cache/stats`.

**Acceptance Scenarios**:

1. **Given** an announcement whose audio is cached, **When** it is served, **Then** the cache
   request count tagged `hit` rises by one.
2. **Given** an announcement whose audio is not cached, **When** it is synthesized, **Then** the
   cache request count tagged `miss` rises by one.
3. **Given** any cache state, **When** the endpoint is read, **Then** the cache size in bytes, the
   entry count and the configured limit match what `GET /api/tts/cache/stats` reports at the same
   moment.
4. **Given** an operator clears the cache through the REST API, **When** the endpoint is next
   read, **Then** the size and entry gauges show the emptied cache.

#### Spot backlog and playback failures (Priority: P3)

[Source: .specify/archive/005-tts-metrics/spec.md -> User Story 4]

An operator sees how many announcements are waiting behind each target, and how many accepted
announcements failed to start playing. A queue that never drains, or routes that keep failing on
one speaker, show up before a listener complains.

**Why this priority**: Both are rare, and both happen after the caller has already been told
"accepted", so no response code reveals them. They matter for diagnosis rather than for daily
health.

**Independent Test**: Send three announcements to one target in quick succession and read the
queue depth for that target while the first plays; then make route creation fail and read the
playback failure count.

**Acceptance Scenarios**:

1. **Given** announcements are waiting for a target, **When** the endpoint is read, **Then** that
   target's queue depth equals the number of announcements waiting for it.
2. **Given** a target's queue has drained, **When** the endpoint is read, **Then** its queue depth
   is zero.
3. **Given** a queued announcement is dequeued, **When** its playback starts, **Then** the playback
   count tagged `started` rises by one.
4. **Given** a queued announcement is dequeued, **When** its playback cannot start, **Then** the
   playback count tagged `failed` rises by one.

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
  `INVALID_VOICE` (400) and `VOICE_CATALOGUE_UNAVAILABLE` (503) to the same mapping (002/FR-021).
  *Amended by 005*: a speak body that cannot be read (broken JSON, an unknown `targetType`) also
  answers 400 with the contract's `ErrorResponse` and `INVALID_REQUEST`, never echoing the body,
  instead of the framework's default error body
  [Source: .specify/archive/005-tts-metrics/plan.md -> "an unreadable request body now gets the published `ErrorResponse(INVALID_REQUEST)`"]
- **002/FR-009, FR-020** A request may also override `engine`, `pitch` and `speakingRate` — for
  `google-cloud` only. Sending any of them to another provider type is a 400 naming the field and
  the type, before any synthesis. *Amended by 004*: `speakingRate` is also accepted by
  `google-gemini`; `engine` and `pitch` stay `google-cloud` only (004/FR-011, FR-022)
- **004/FR-021** A request may carry `stylePrompt` — for `google-gemini` only. Any `stylePrompt`
  present (even empty or whitespace) sent to another type is a 400 naming the field and the type
- **001/FR-027** Structured log events: request received, cache hit/miss, synthesis
  started/completed/error, playback started/completed

### Providers

- **001/FR-003, FR-004, FR-013** Several named providers, cloud and local, each with its own type,
  credentials and settings (voice, language, rate, model/engine). Types: `OPENAI`, `GOOGLE_CLOUD`,
  `GOOGLE_GEMINI` (since 004), `PIPER` (piper1-gpl, `python3 -m piper`), `LOCAL_HTTP`
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
  unreachable catalogue a 503. *Amended by 004*: `google-gemini` lists too, and every listed voice
  of either Google type also shows Google's published gender (004/FR-036)

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
- **003/FR-005** `service-account-key-file` on any other provider type aborts start-up. *Amended by
  004*: `google-gemini` requires it, so only OpenAI, Piper and local HTTP reject it
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

### Google Gemini voices

[Source: .specify/archive/004-gemini-tts-provider] — a separate type, `google-gemini`, not an engine
of `google-cloud`: a per-request engine override could otherwise switch a free-tier entry to a
paid model, because Chirp3-HD and Gemini share voice names (`Kore`, `Charon`). `google-cloud`,
OpenAI, Piper and local HTTP behave exactly as before, and every existing configuration starts
unedited (004/FR-024).

- **004/FR-001, FR-010** Synthesis goes to Google's Text-to-Speech `v1` `text:synthesize` (route A)
  with the configured model, the effective voice and language, the effective style prompt (sent
  separately from the text, only when there is one) and the speaking rate (only when set), asking
  for the same audio format as `google-cloud`. Pitch is never sent
- **004/FR-002, FR-003, FR-014** Authentication is **only** a service account key file, read and
  validated by 003's rules; an `api-key` aborts start-up (Google refuses Gemini with an API key).
  Every 003 token rule applies unchanged, with a token per entry
- **004/FR-004, FR-005** `model`, `voice` and `language` are required. Start-up checks form only:
  the model is one token of lowercase letters, digits, dots and hyphens (not a closed list); the
  voice one word of ASCII letters; the language 002's language-region rule. Language is required
  because Google refuses a Gemini request without one
- **004/FR-006, FR-007, FR-008** A default `style-prompt` and `speaking-rate` are optional (rate on
  002's range; a prompt over `max-text-length` aborts start-up). `engine`, `pitch` or a non-empty
  `extra-params` on a Gemini entry, and `style-prompt` (and, by the plan, `model`) on any other
  type, abort start-up — never accepted and ignored
- **004/FR-009** Start-up makes no network request
- **004/FR-029** A `google-gemini` effective default provider (named, or the first enabled entry)
  logs one warning that unnamed announcements are billed per token; start-up continues
- **004/FR-030** The synthesis time limit has the shared 10 s default and covers the token too
- **004/FR-011, FR-012, FR-013** A request may override voice, language, style prompt and speaking
  rate, never the model. Every prompt is stripped at both ends (case and inner spacing kept); the
  effective prompt is the request's if non-empty, none if the request's is empty, else the entry's
  default. A request prompt over `max-text-length`, or a malformed request voice or language, is a
  400 before synthesis
- **004/FR-015** An announcement's voice is never checked against a catalogue. It is normalized to
  a leading capital (`kore` → `Kore`) for Google and for the cache; errors quote the caller's
  spelling
- **004/FR-016** The audio plays at the right speed and pitch whatever sample rate Google returns
  (Gemini's native rate is 24 kHz)
- **004/FR-019, FR-020** Google's refusals use the same codes as `google-cloud` (`PROVIDER_ERROR`,
  `PROVIDER_RATE_LIMITED`, `PROVIDER_TIMEOUT`) with the entry's name and Google's explanation; no
  new error code, every new caller error is `INVALID_REQUEST`
- **004/FR-025** The Google plumbing both types use — sign-in, the 401 retry, Google's explanation,
  reading the audio — is one implementation, so a fix reaches both
- **004/FR-023, FR-031, FR-032** Voice listing answers from Google's published `v1/voices` list,
  keeping only bare Gemini-form names (normalized, sorted), fetched on first need with the entry's
  own token and governed by the shared `voice-catalogue` settings, one copy per entry. Each voice
  shows its name as both short and full name, the entry's model as engine, Google's gender, and
  **no** language (Google's `en-US` tag does not say what the voice speaks). An unfetchable list
  is `VOICE_CATALOGUE_UNAVAILABLE`
- **004/FR-033, FR-034, FR-035** A `language` filter is form-checked but does not narrow; an
  `engine` filter is a 400 naming the filter and type. Announcing — hit or miss — never fetches
  the list
- **004/FR-026, FR-027, FR-028** The setup guide covers the API and role, per-token billing with no
  free tier, the models, choosing a voice (listed voices are not guaranteed for every model), style
  prompts, the time limit and the default-provider warning; the configuration reference and REST
  contract cover the type, `stylePrompt` and Gemini listing; the Gemini planning note moved to
  `docs/archive/`

### Metrics

[Source: .specify/archive/005-tts-metrics/spec.md -> FR-001–FR-024] — this group only observes:
announcements, providers, the cache, the queue and the REST contract behave as in 0.1.3, except for
the unreadable-body answer noted under 001/FR-032.

*Publication*

- **005/FR-001** Metrics are published through the host's existing metrics collection, so they
  appear in the host's Prometheus endpoint with no new endpoint and no operator configuration
- **005/FR-002** A host that collects no metrics still starts the extension and serves
  announcements unchanged; that is never a start-up failure
- **005/FR-003** With `multiroom.tts.enabled=false` the extension contributes no metrics
- **005/FR-004** Every metric is named under the `tts` prefix, so one query finds them all

*Announcements*

- **005/FR-005** Every speak request the extension answers is counted, `accepted` or `rejected` —
  including requests rejected by the basic request checks before the announcement logic runs (a
  blank text or target, a malformed body), which count as `rejected` with `INVALID_REQUEST`, so the
  failure rate reflects every call a client made
- **005/FR-006** A rejection carries the error code the caller receives, from the published set,
  or the fixed `INTERNAL` when the caller received HTTP 500 with no code; an accepted announcement
  carries the fixed `none`
- **005/FR-006a** Every announcement count also carries `reason` from a fixed set: `queue_full`
  when the target's queue had no room, `shutting_down` when the extension was stopping, `none`
  otherwise — separating rejections that share `PROVIDER_ERROR` with a real provider failure,
  without changing what the caller receives
- **005/FR-007** The provider tag is the provider name only when it is a configured provider
  (named explicitly or the default); otherwise the fixed placeholder `unknown`

*Synthesis*

- **005/FR-008** Every synthesis asked of a provider is timed, from the call until it returns audio
  or fails, tagged with the provider's configured name, its type, `outcome` `success` or `failure`,
  and `error` carrying the error code the announcement is rejected with (`none` on success)
- **005/FR-008a** The timing also publishes a latency distribution as histogram buckets, so p95
  and p99 per provider can be computed over any window and across restarts. The buckets reach at
  least the longest configured provider timeout. Percentiles are never computed inside the
  extension, since those cannot be combined across windows
- **005/FR-009** A cache hit records no synthesis timing
- **005/FR-010** Format conversion after synthesis is outside the timing
- **005/FR-010a** Every synthesis records the characters sent to the provider, tagged with the
  provider's name, type, billing tier, the timing's outcome and error, and the part: `text` (the
  message) or `style_prompt` (sent only to `google-gemini`). Time per character uses `text`;
  billable usage is the sum of both
- **005/FR-010b** The billing tier is the voice family the provider bills by: for `google-cloud`
  the engine family of the resolved voice (`Standard`, `Wavenet`, `Neural2`, `Studio`, `Chirp-HD`,
  `Chirp3-HD`, or `other` for an unrecognized engine); for `google-gemini` the entry's configured
  model; for every other type the fixed `none`. Always from the resolved settings or configuration,
  never from caller free text
- **005/FR-010c** Every successful synthesis records the seconds of audio produced, tagged with
  the provider's name, type and billing tier (Gemini bills audio output), so the real-time factor
  (synthesis time over audio duration) is computable. Derived from the audio already in memory,
  with no extra disk or network work
- **005/FR-010d** Character and audio-duration figures keep both a running total and a count, so
  per-provider averages and ratios are computable over any window. The message length itself never
  becomes a tag

*Cache*

- **005/FR-011** Every cache lookup made for an announcement is counted as `hit` or `miss`
- **005/FR-012** The cache's current size in bytes, entry count and configured maximum in bytes are
  published, consistent with `GET /api/tts/cache/stats`
- **005/FR-013** Recording a cache hit adds no disk, network or subprocess work to the hit path

*Queue and playback*

- **005/FR-014** For each target that has received an announcement, the number of announcements
  waiting in that target's queue is published
- **005/FR-015** Queued announcements whose playback started, and those whose playback could not
  start, are counted as `started` and `failed`

*Cardinality and safety*

- **005/FR-016** Tag values come only from bounded sets: configured provider names, provider
  types, billing tiers (engine families, `other` and configured Gemini models), request parts
  (`text`, `style_prompt`), published error codes and `INTERNAL`, rejection reasons, fixed words
  (outcomes, `none`, `unknown`), and names of targets that passed validation. Message text, voices,
  languages, style prompts, announcement ids and exception messages never appear in any tag
- **005/FR-017** No metric reveals a key, token, key file content or request body
- **005/FR-018** A failure while recording a metric never fails or delays an announcement

*Documentation*

- **005/FR-019** The operator guide `docs/metrics.md` ships with the feature and covers: where the
  metrics are (the host's Prometheus endpoint, what the host must expose, and that
  `multiroom.tts.enabled=false` removes them); every metric (Prometheus name, type, what one
  increment or sample means, when it is recorded); every tag and its values, including the
  `unknown` placeholder; ready-to-use queries for the failure rate by error code, timeout rate per
  provider, average/maximum/p95/p99 synthesis time per provider (and p99 against the provider's
  timeout, entered by the operator as a constant because a timeout is configuration, not a
  metric), time per 1,000 characters, real-time factor, characters per provider per day, cache hit
  ratio, cache fill against its limit and queue depth per target; and how to read them (counters
  reset on restart or redeploy, time per character is a ratio over a window that favours long
  messages, and what is deliberately left out: message text, voices, style prompts)
- **005/FR-020** The README's documentation table and `docs/configuration.md` link to
  `docs/metrics.md`

*Groundwork for a character budget*

- **005/FR-021** Characters sent to a provider are measured in exactly one place, shared by every
  provider type, immediately before the provider is called, so a later budget check can read the
  same figure at the same moment and refuse the call before it is made. The metric records that
  figure once the call ends, with its outcome; a budget reads the measured figure, not the metric
- **005/FR-022** A cache hit, and a request rejected before a provider is called (invalid text,
  unknown target, unknown provider, settings the provider's resolver refuses), add no characters,
  because neither is billed. A failure raised inside a provider call before anything reaches the
  billed endpoint (a voice the catalogue does not list, a token that cannot be obtained) is counted
  with `outcome="failure"` like any failed call, since the extension cannot tell it from a billed
  failure. A full queue adds characters only when its request was a cache miss (synthesis runs
  before queuing, so those characters were sent and billed); after a cache hit it adds none
- **005/FR-023** `docs/metrics.md` defines billable usage in these terms and gives a query for
  characters sent (successful and failed calls alike) per provider and billing tier in the current
  calendar month, saying how far to trust it: Prometheus compensates for counter resets, so a
  restart or redeploy loses only what was counted since the last scrape; the total is only as long
  as Prometheus's retention; and the calendar month comes from the dashboard's time range, since a
  query alone cannot express one
- **005/FR-024** The local working note `docs/future/google-cloud-free-tier-tracking.md` (excluded
  from git, so not part of the pull request) says what 005 provides (the definition, the
  measurement point, the tier breakdown, the visibility) and what the budget feature still needs
  (persisted monthly totals, a limit per entry, and the choice between rejecting and falling back)

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
- **004/FR-017, FR-018** For `google-gemini` the key also covers the model, the normalized voice,
  the language, the speaking rate on 002's terms, and the effective (stripped) style prompt,
  compared exactly; no prompt and an empty prompt are one identity. Keys of every other type — and
  of a Gemini request with no prompt — hash exactly as before, so every existing entry stays a hit
- **001/FR-017, FR-018, FR-019** On disk, survives restarts, LRU at a configurable size, clearable
  on request
- **001/FR-020** A missing or corrupt entry is a miss and is replaced
- **001/FR-025** A failed cache write still plays, from a temp file outside the cache that is
  deleted afterwards, with a warning

## Key entities

[Source: .specify/archive/001-tts-extension/data-model.md,
.specify/archive/002-google-voice-selection/data-model.md,
.specify/archive/003-google-service-account-auth/data-model.md,
.specify/archive/004-gemini-tts-provider/data-model.md]

- **Speak request** — text, target name, `TargetType`, optional provider / voice / language,
  (`google-cloud` only) engine / pitch, (both Google types) speaking rate, and (`google-gemini`
  only, since 004) style prompt
- **Provider configuration** — name, type, credentials, default voice and language, engine,
  timeout, and (Google only) pitch and speaking rate; since 004, `model` and `style-prompt`
  (`google-gemini` only). The list plus `default-provider` under `multiroom.tts`. Since 002,
  `language` has no declared default: the `en-US` fallback lives in the non-Google resolution
- **Gemini provider entry** *(004)* — a `google-gemini` entry: name, key file (required, no API
  key), model, default voice and language (all required), optional default style prompt and
  speaking rate
- **Gemini model** *(004)* — e.g. `gemini-2.5-flash-tts`; form-checked, fixed per entry, never
  case-folded, carried as the settings' `engine` and so as the cache key's engine name
- **Gemini voice** *(004)* — a bare name such as `Kore`, not tied to a language or engine;
  normalized to a leading capital; Google publishes a gender for each
- **Style prompt** *(004)* — a natural-language delivery instruction, stripped, sent separately
  from the text, part of the cache key when in effect; from the request, the entry's default, or
  nowhere
- **Requested / resolved settings** — the request's overrides, and what a provider resolves them to
  before the cache lookup, with no I/O: voice, language, engine, pitch, speaking rate, plus the
  normalized forms the cache key uses. Keeps `TtsService` provider-agnostic
- **Google voice name** — `Full` (language, engine segment, voice) or `Short` (voice); parsed,
  canonicalized, composed
- **Google engine** — the six recognized families, each with a canonical spelling and aliases
- **Voice catalogue** — one per Google entry: empty, loaded or failed; TTL, failure back-off,
  single-flight fetch, lock-free warm lookups. Global timings under `multiroom.tts.voice-catalogue`.
  Since 004 it takes a voice selector — `google-cloud` keeps full names and validates announcements
  against them; `google-gemini` keeps bare Gemini names (model as engine, no language) and serves
  listing only. Every catalogue voice carries Google's gender (`null` when absent)
- **Google credential** — per `google-cloud` entry, exactly one of an API key (`?key=`) or a
  service account (bearer token); per `google-gemini` entry, always a service account. Not part of
  the cache key beyond the provider name
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
- **Announcement count** *(005)* — how many announcement requests arrived, by outcome, error code,
  rejection reason and provider [Source: .specify/archive/005-tts-metrics/spec.md -> "Announcement count"]
- **Synthesis timing** *(005)* — the duration and number of provider syntheses, with a bucketed
  latency distribution, by provider name, provider type, outcome and error code
  [Source: .specify/archive/005-tts-metrics/spec.md -> "Synthesis timing"]
- **Synthesis work** *(005)* — the characters sent to each provider (by outcome) and the seconds of
  audio it returned (successes only), as totals and counts: the denominators for normalised
  latency. Built per synthesis as one value before the provider call, which a future budget check
  reads too [Source: .specify/archive/005-tts-metrics/spec.md -> "Synthesis work"]
  [Source: .specify/archive/005-tts-metrics/data-model.md -> "SynthesisUsage"]
- **Cache request count** *(005)* — how many cache lookups hit or missed
  [Source: .specify/archive/005-tts-metrics/spec.md -> "Cache request count"]
- **Cache occupancy** *(005)* — the cache's current bytes and entries, and its configured byte
  limit [Source: .specify/archive/005-tts-metrics/spec.md -> "Cache occupancy"]
- **Queue depth** *(005)* — the number of announcements waiting, per target
  [Source: .specify/archive/005-tts-metrics/spec.md -> "Queue depth"]
- **Playback count** *(005)* — how many dequeued announcements started playing or failed to
  [Source: .specify/archive/005-tts-metrics/spec.md -> "Playback count"]

## Request lifecycle

[Source: .specify/archive/001-tts-extension/data-model.md]

```text
RECEIVED → VALIDATED            (or REJECTED → 400)
  → RESOLVED    provider.resolveSettings: pure, no I/O          (or REJECTED → 400)
  → CACHE_CHECK → hit:  AUDIO_READY (pinned)                    — no catalogue work at all
                → miss: [Google service account: token first → or FAILED 503, check skipped]
                        [google-cloud: catalogue check → INVALID_VOICE 400, or skipped if unreachable]
                        [google-gemini: no catalogue step at all]
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

Since 005 the flow is unchanged but observed: every answered request is counted, the cache lookup
is counted hit or miss, a synthesis is timed and its characters measured once immediately before
the provider call (conversion outside the timing), and the playback start is counted `started` or
`failed` [Source: .specify/archive/005-tts-metrics/spec.md -> FR-005, FR-011, FR-021, FR-015].

## Edge cases

[Source: .specify/archive/001-tts-extension]

- Unknown target or provider: rejected before any synthesis
- Concurrent requests for one target: both synthesized as they arrive, played in arrival order, no
  deduplication
- Cache disk full: plays anyway, caching skipped for that request
- Disabled extension: no beans for the whole run — and so no metrics at all, not even zero-valued
  series; "disabled mid-playback" cannot happen
  [Source: .specify/archive/005-tts-metrics/spec.md -> "`multiroom.tts.enabled=false`"]
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
- *(004)* A voice name shared by Chirp3-HD and Gemini (`Kore`) means a Gemini voice only on a
  `google-gemini` entry; nothing on a `google-cloud` entry can switch to Gemini
- *(004)* A voice or language the model lacks, a retired preview model, or text plus prompt too long
  for the model: not checked locally; Google's refusal reaches the caller. A listed voice is not
  guaranteed for the entry's model, because Google's list names no model
- *(004)* A new Gemini voice from Google appears in the listing once the remembered list expires,
  with no release
- *(004)* Gemini inline markup in the text (`[whispering]`) is sent and cached as ordinary text
- *(004)* A whitespace-only request prompt is "no prompt"; a YAML block's trailing line break is
  stripped, so it shares a cache entry with the same prompt written plainly
- *(004)* A `google-gemini` default provider is allowed, with the start-up billing warning
- *(005)* The host collects no metrics (no metrics support on its classpath, or none configured):
  the extension starts and serves announcements exactly as before, and records nothing
  [Source: .specify/archive/005-tts-metrics/spec.md -> "The host collects no metrics"]
- *(005)* A caller-supplied value that is not configured (an unknown provider name, a free-text
  voice, a style prompt, the message text) never becomes a tag value; only configured provider
  names, provider types, validated target names, error codes and fixed outcome words are used
  [Source: .specify/archive/005-tts-metrics/spec.md -> "A caller-supplied value that is not configured"]
- *(005)* A target removed from the host after announcements were sent to it: its queue-depth
  series may remain at zero until restart; it does not grow without bound, because only validated
  targets get a series
  [Source: .specify/archive/005-tts-metrics/spec.md -> "A target that is removed from the host"]
- *(005)* A synthesis failing with an unexpected exception is recorded with the code the
  announcement is rejected with (`PROVIDER_ERROR`), never an exception class name
  [Source: .specify/archive/005-tts-metrics/spec.md -> "A synthesis that fails with an unexpected exception"]
- *(005)* A synthesis that succeeds but whose audio cannot be converted: the synthesis and its
  characters are recorded as `success` (they were sent and billed; conversion is outside the
  timing), no audio duration is recorded, and the announcement counts as `rejected` with
  `FORMAT_NORMALIZATION_FAILED`
  [Source: .specify/archive/005-tts-metrics/spec.md -> "A synthesis that succeeds but whose audio cannot be converted"]
- *(005)* A cache write failure that spills the audio to a temporary file still counts as a `miss`,
  and the announcement as `accepted`
  [Source: .specify/archive/005-tts-metrics/spec.md -> "A cache write failure that spills the audio"]
- *(005)* A full queue counts as `rejected` with the code the caller receives (currently
  `PROVIDER_ERROR`) and reason `queue_full`, so an alert on provider errors can exclude it; a
  rejection while the extension shuts down gets reason `shutting_down`
  [Source: .specify/archive/005-tts-metrics/spec.md -> "A full queue"]
- *(005)* After a redeploy or host restart counts start again from zero; the monitoring system
  handles counter resets [Source: .specify/archive/005-tts-metrics/spec.md -> "The extension is redeployed or the host restarts"]
- *(005)* A malformed or incomplete request body counts as `rejected` with `INVALID_REQUEST` and
  provider `unknown`, though it never reached the announcement logic
  [Source: .specify/archive/005-tts-metrics/spec.md -> "A malformed or incomplete request body"]
- *(005)* Reading the metrics endpoint does not synthesize, touch the disk or contact a provider
  [Source: .specify/archive/005-tts-metrics/spec.md -> "Reading the metrics endpoint"]

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
| 004/SC-001 first attempt | a prepared operator hears a Gemini announcement first time | Local-core smoke run (T053) |
| 004/SC-002 style prompts | three prompts → three audibly different entries; repeats are hits | Cache identity tested; audible difference in T053 |
| 004/SC-003 upgrade | production starts and announces unedited; pre-upgrade entries still hit | Hash stability tested; confirmed on `multiroom.lan` (T054) |
| 004/SC-004 malformed config | 100 % of Story 3 cases abort naming extension, entry, fault | Tested |
| 004/SC-005 start-up | zero network requests for a Gemini entry | Tested |
| 004/SC-006 hit → playback | < 1 s | No network on a hit by design; checked in T053 |
| 004/SC-007 Google's explanation | always passed on | Tested |
| 004/SC-008 no secrets | none in logs or errors | Tested by captured output; core log searched in T053 |
| 004/SC-009 no library | no new third-party dependency | No dependency added |
| 004/SC-010 Gemini listing | every published Gemini voice (30 on 2026-09-26) with gender | Tested against a fixture of the live shape |
| 005/SC-001 failures explained | "how many failed in the last hour, and why" in one query, no logs | Query in `docs/metrics.md`; local-core run (T048) |
| 005/SC-002 provider figures | average, max, p95, p99, failures, time per 1,000 characters, real-time factor — each in one query | Queries in `docs/metrics.md`; local-core run (T048) |
| 005/SC-002a normalised latency | twice the characters leaves time per 1,000 characters roughly unchanged while the raw average grows | By construction (ratio of totals) |
| 005/SC-003 cache figures | hit ratio computable; size matches `GET /api/tts/cache/stats` exactly with no announcement in between | Gauges read `AudioCache.stats()`; unit-tested |
| 005/SC-004 hit cost | no measurably slower than 0.1.3: within 5 % on the median over 1,000 hits | Measured by hand in the local-core run (T048), not a test |
| 005/SC-005 bounded series | 10,000 announcements with random text, voices and unknown providers → same series count as 10 | Cardinality test (T041) |
| 005/SC-006 no metrics / disabled | a host without metrics support serves announcements; `enabled=false` contributes nothing | `TtsAutoConfigurationMetricsTest`; local-core run (T048) |
| 005/SC-007 guide suffices | an operator writes the "share timed out per provider" query from `docs/metrics.md` alone; every query returns data once its event has happened | Local-core run with a forced timeout and full queue (T048) |

Sources for the 005 rows: [.specify/archive/005-tts-metrics/spec.md -> SC-001–SC-007], status from
[.specify/archive/005-tts-metrics/tasks.md -> T041, T047, T048].

## Assumptions

- **AS-001** The host's metrics collection is available to extensions on the shared classpath
  (confirmed in `multiroom-core`: it ships the metrics library and the Prometheus registry and
  exposes the `prometheus` endpoint). The extension uses it at `provided` scope, bundles nothing,
  and needs no change to `multiroom-api`
  [Source: .specify/archive/005-tts-metrics/spec.md -> "The host's metrics collection is available"]
- **AS-002** The starter's dependency ban covers only `multiroom-core`, so depending on the host's
  metrics library passes the build gates. Documenting it upstream as part of the extension
  contract is worthwhile but out of scope
  [Source: .specify/archive/005-tts-metrics/spec.md -> "The starter's dependency ban covers only"]
- **AS-003** Target names come from the host's configured outputs and groups, and a series is
  created only after a target passes validation, so the number of queue-depth series is bounded by
  the host's device count
  [Source: .specify/archive/005-tts-metrics/spec.md -> "Target names come from the host's configured outputs"]
- **AS-004** The playback count exists because a failure to start playback happens after the
  caller is told `accepted` and is otherwise visible only in logs
  [Source: .specify/archive/005-tts-metrics/spec.md -> "The playback count (User Story 4)"]
- **AS-005** Work is measured in characters, not words: characters are what OpenAI and Google bill
  by, need no language-aware splitting, and are exact. Gemini is the exception: it bills input
  tokens plus audio output tokens, so for it characters are only a rough proxy and seconds of audio
  are closer to the bill; both are recorded, and choosing the budget's unit for Gemini is left to
  that feature. The style prompt is counted separately because it is sent as input and may be
  billed (not yet confirmed), but it does not lengthen synthesis in proportion. Synthesis time has
  a fixed part (connection, authentication, model start), so time per character is a ratio of
  totals over a window, not a per-request constant; short messages look proportionally slower
  [Source: .specify/archive/005-tts-metrics/spec.md -> "Work is measured in characters, not words"]
- **AS-006** Playback that starts and later ends normally is not timed: its length is the audio's,
  which says nothing about health
  [Source: .specify/archive/005-tts-metrics/spec.md -> "Playback that starts and later ends normally"]
- **AS-007** Cache evictions, provider token refreshes and voice-catalogue fetches are not measured;
  they can follow if the first metrics show a need
  [Source: .specify/archive/005-tts-metrics/spec.md -> "Cache evictions, provider token refreshes"]
- **AS-008** The version becomes 0.1.4
  [Source: .specify/archive/005-tts-metrics/spec.md -> "The version becomes 0.1.4"]
- **AS-009** Dashboards and alert rules are out of scope; the documentation gives example queries
  only [Source: .specify/archive/005-tts-metrics/spec.md -> "Dashboards and alert rules are out of scope"]

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

**Revision 2026-09-26**: archived `004-gemini-tts-provider` — Gemini stories, a "Google Gemini
voices" requirements group, the Gemini cache-key rule, Gemini entities and the catalogue's voice
selector and gender, the no-catalogue step in the lifecycle, edge cases and success criteria.
002/FR-009, 002/FR-018 and 003/FR-005 annotated where 004 widened them to `google-gemini`.
