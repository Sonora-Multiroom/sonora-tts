# Main Specification: Sonora TTS

> **Revision**: 2026-10-15 — archived `.specify/archive/005-tts-metrics`: a metrics story set (with full
> story blocks under "Story details"), a "Metrics" requirements group, metrics entities, edge cases
> and success criteria, a new `## Assumptions` section (AS-001–AS-009), and 001/FR-032 annotated
> for the unreadable-body answer. Earlier revisions are recorded at the end of this file.
> **Revision**: 2026-10-15 — archived `.specify/archive/006-announcement-ducking`: announcements join their
> target in `duck-others` or `mix` instead of stopping and restoring it — four stories, the playback
> mode, host-refusal and compatibility requirements, the `Playback mode` and `Playback settings`
> entities, the lifecycle without snapshot or restore, edge cases, success criteria and AS-010–AS-016.
> 001/FR-006–FR-009 and the `Speak request` entity annotated; the 001 definition of `Announcement
> task` and 001/SC-006 superseded (user-confirmed); host coupling raised to API 0.1.21.
> **Revision**: 2026-10-15 — archived `.specify/archive/007-failed-start-cleanup`: an announcement that fails
> to start ends once, as `TTS_PLAYBACK_FAILED` — three stories, 007/FR-001–FR-008 (FR-004 folded
> into 001/FR-008, FR-005 into 006/FR-007), the `Announcement in flight` and `End-of-route notice`
> entities, the failed-start branch of the lifecycle, edge cases, success criteria and AS-017–AS-021.
> **Revision**: 2026-10-15 — re-archived `.specify/archive/007-failed-start-cleanup` after T030 closed: AS-018
> now records that the hub documents `startedAt == null` as "never went live".
> **Revision**: 2026-10-15 — archived `.specify/archive/008-malformed-target-name`: a target name that breaks
> the hub's ID rule answers 400 `TARGET_NOT_FOUND` — 001/FR-010 annotated, the "Unknown target"
> edge case extended, a 0.1.7 paragraph under "Current state". 008 states no stories, numbered
> requirements, success criteria or assumptions, so none were added.
> **Revision**: 2026-10-25 — archived `.specify/archive/009-route-volume-ducking-level`: a speak
> request may name its own volume and ducking level — 009/R1–R8 (request fields, their 400s, the
> `tts.playback` defaults and when they apply, API 0.1.29), the `Speak request` and `Playback
> settings` entities, three edge cases, three success criteria, an out-of-scope line, host
> coupling raised to API 0.1.29 and a 0.1.10 paragraph under "Current state". 009 states no
> stories or assumptions, so none were added.

**Scope**: one extension for the multiroom audio system — text-to-speech announcements delivered to a
room or a group of rooms.

## What the product does

A caller (an automation, a script, Home Assistant) sends a line of text and a target. The extension
synthesizes speech through a configured provider, caches the audio, and plays it on the target over
whatever the room is doing — lowered by default, never stopped *(since 006; until then it stopped
the room and restored it afterwards)* [Source: .specify/archive/006-announcement-ducking/spec.md -> FR-001].

- **Trigger**: HTTP, on the host's single port, under this extension's own `/api/tts/**` paths. There
  is no separate authentication layer — access control is whatever guards the shared HTTP surface
- **Providers**: several may be configured at once, each with a name; one is the default (the first in
  the list if none is marked). A request may override the provider, the voice and the language —
  and, for `google-gemini`, the style prompt
- **Cache**: on disk, keyed by text + provider type/model + voice/language (+ style prompt, when one
  is in effect) + **the audio format the entry was converted to**. Survives restarts; LRU eviction
  at a configurable size
- **Playback**: the cached WAV is registered as an ephemeral input and routed to the target
  (`SINGLE_OUTPUT` or `OUTPUT_GROUP`, the host's own `TargetType`). Since 006 the route joins the
  target beside what already plays, in the playback mode `duck-others` (default: the host lowers
  everything else and restores it afterwards) or `mix` (equal level); announcements to one target
  queue behind each other, not behind the room's music
  [Source: .specify/archive/006-announcement-ducking/spec.md -> FR-001, FR-004, FR-009]
- **Completion**: observed, never estimated — the host destroys the announcement's route at EOF and
  publishes `RouteDestroyedEvent`; releasing the announcement's resources hangs off that event.
  Since 006 nothing is restored by this extension [Source: .specify/archive/006-announcement-ducking/spec.md -> FR-002, FR-005]
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
- Not a way to change the volume of an announcement that is already playing: hub 034 offers that on
  routes, and this extension does not expose it [Source: .specify/archive/009-route-volume-ducking-level/spec.md -> "Out of scope"]

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

**Announcements over what is playing** in 0.1.5 by `006-announcement-ducking` (archived here
2026-10-15): an announcement no longer stops the target's routes and recreates them afterwards; its
route joins the target in `duck-others` (the host lowers the rest) or `mix`, chosen per request
(`playbackMode`) or by `multiroom.tts.playback.default-mode`. A route the host refuses (route
limit) is logged, counted as a failed playback and dropped. Requires multiroom-api 0.1.21
(multiroom-ai 023). Its contract
([tts-rest-api.yaml](../archive/006-announcement-ducking/contracts/tts-rest-api.yaml), v0.1.5) adds
`playbackMode` and supersedes 004's
[Source: .specify/archive/006-announcement-ducking/spec.md -> FR-012, FR-013].

**Failed starts end once** in 0.1.6 by `007-failed-start-cleanup` (archived here 2026-10-15): since
hub 024 (multiroom-api 0.1.22) a route that was admitted and then failed to start announces its end
before the start failure reaches the extension, which reported it as completed and signalled the
queue twice. Now such an announcement is reported once, as `TTS_PLAYBACK_FAILED`, with one failed
playback and one queue signal, identically on a 0.1.21 and a 0.1.22 hub. No API bump, no endpoint,
field or metric change [Source: .specify/archive/007-failed-start-cleanup/spec.md -> FR-001, FR-006].

**Malformed target names** in 0.1.7 by `008-malformed-target-name` (archived here 2026-10-15): once
hub 026 removed its global exception mapper, a `targetName` breaking the hub's ID rule (`"living
room"`) escaped as a 500 counted as `INTERNAL`. It now answers 400 `TARGET_NOT_FOUND` in the
contract's `ErrorResponse` shape and is counted under that code. No API bump (still 0.1.21), no new
code or field [Source: .specify/archive/008-malformed-target-name/spec.md -> "Proposal"].

**Announcement volume and ducking level** in 0.1.10 by `009-route-volume-ducking-level` (archived
here 2026-10-25): hub feature 034 (multiroom-api 0.1.29) lets a route carry its own volume and
ducking level, so a speak request may name `routeVolume` (0-100, this announcement only, so the
room's volume control does not reach it) and, with `duck-others`, `duckingLevelDb` (-60.0 to
0.0), with `playback.route-volume` and `playback.ducking-level-db` as defaults. Requires
multiroom-api 0.1.29. No new endpoint or error code
[Source: .specify/archive/009-route-volume-ducking-level/spec.md -> "Problem", R1, R2, R4].

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
| The music keeps playing under an announcement | P1 | Music on the target never stops: lowered while the announcement plays, back at its level from where it is by then; no route but the announcement's is stopped or created | 006 |
| Group announcements over group music | P2 | A group announcement plays in sync on every member over each member's own lowered music; an announcement to one member lowers only that member | 006 |
| A refused announcement explains itself and leaves the room alone | P2 | An announcement the host refuses (route limit) plays nowhere, changes nothing playing, leaves nothing reserved behind, is logged with the host's reason and counted; the queue moves on | 006 |
| Choose not to lower the room | P3 | `mix` by configuration or per request keeps the music's level; the request's mode wins; any other mode is a 400 or a start-up fault | 006 |
| A failed start is reported once, as a failure | P1 | An announcement whose route is admitted and fails to start logs one failure line and no completion, counts one failed and no started playback, and leaves no input registered and no warning | 007 |
| The queue moves on exactly once | P1 | After a failed start the queue is told once that it ended, the next announcement plays, and nothing is held for the failed one | 007 |
| Refusals and normal playback are unchanged | P2 | A refused and a normally completed announcement log, count and signal the queue exactly as before 007 | 007 |

Sources: [001](../archive/001-tts-extension/spec.md), [002](../archive/002-google-voice-selection/spec.md),
[003](../archive/003-google-service-account-auth/spec.md), [004](../archive/004-gemini-tts-provider/spec.md),
[005](../archive/005-tts-metrics/spec.md), [006](../archive/006-announcement-ducking/spec.md),
[007](../archive/007-failed-start-cleanup/spec.md).

### Story details

Stories archived before 005 keep their full blocks in the archived spec named in the Source column.
The actor in every 005 story below is the **operator**: the person who runs the host, configures the
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

#### The music keeps playing under an announcement (Priority: P1)

[Source: .specify/archive/006-announcement-ducking/spec.md -> User Story 1]

Music is playing in the kitchen. An automation announces "the washing machine has finished" there.
The music does not stop: it drops to a quiet background level, the announcement is heard clearly
over it, and the music comes back up when the announcement ends — from where it is by then, with no
gap, no restart and no lag while a route is rebuilt.

**Why this priority**: This is the whole feature. Before 006 an announcement stopped the room's
routes when it was accepted, and recreated them after it ended, so every announcement cost two
audible gaps and a restart of the music (a stream re-buffers, a file starts over).

**Independent Test**: Play a continuous source on one output, send one announcement to that output,
and listen: the source never goes silent, is quieter while the announcement plays, and is back at its
previous level within a second of the announcement ending.

**Acceptance Scenarios**:

1. **Given** music is playing on an output, **When** an announcement is sent to that output, **Then**
   the music keeps playing without a gap, lowered while the announcement plays, and the announcement
   is heard at full level.
2. **Given** an announcement is playing over lowered music, **When** the announcement ends, **Then**
   the music returns to its previous level and continues from where it is, not from where it was when
   the announcement began.
3. **Given** music is playing on an output, **When** an announcement is sent to it, **Then** no route
   on that output is stopped or created by this extension apart from the announcement's own, and the
   route that was playing keeps its identity throughout (an integration showing "current source", such
   as Home Assistant, shows the music during and after the announcement).
4. **Given** nothing is playing on an output, **When** an announcement is sent to it, **Then** it
   plays exactly as before 006, and the output is silent again afterwards.
5. **Given** an announcement was accepted for a busy output, **When** it is still waiting in that
   target's queue, **Then** the music on that output keeps playing at its normal level until the
   announcement actually starts.

#### Group announcements over group music (Priority: P2)

[Source: .specify/archive/006-announcement-ducking/spec.md -> User Story 2]

Music plays on the "downstairs" group. An announcement is sent to the whole group, or to one room in
it.

**Why this priority**: Group targets are an existing, supported target type, and their behaviour must
be defined; but the single-room case already delivers the value.

**Independent Test**: Play music on a group, announce on the group and then on one member, and
listen in each room.

**Acceptance Scenarios**:

1. **Given** music is playing on a group, **When** an announcement is sent to the group, **Then** it
   is heard on every member, in sync, and the music is lowered on every member.
2. **Given** music is playing on a group, **When** an announcement is sent to one member output,
   **Then** the music is lowered on that output only and continues unchanged, and in sync, on the
   other members.
3. **Given** different music plays on two members of a group, **When** an announcement is sent to the
   group, **Then** each member's own music is lowered under it and restored afterwards.

#### A refused announcement explains itself and leaves the room alone (Priority: P2)

[Source: .specify/archive/006-announcement-ducking/spec.md -> User Story 3]

The host limits how many routes may play on one output at once. An announcement that the host refuses
must fail cleanly: the room keeps playing what it was playing, and the operator can see why.

**Why this priority**: Under the old behaviour the target was always emptied first, so the host never
refused an announcement. Joining instead of replacing introduces a new way to fail, and it must not
leave state behind.

**Independent Test**: Fill an output to the host's route limit, send an announcement to it, and
confirm that nothing already playing changes, the failure is logged with the host's reason, and it is
counted in the extension's metrics.

**Acceptance Scenarios**:

1. **Given** an output already holds the host's maximum number of routes, **When** an announcement to
   it reaches the front of its queue, **Then** it is not played, every route already on the output
   keeps playing unchanged, and the log names the announcement, the target, the output and the host's
   reason.
2. **Given** an announcement was refused by the host, **Then** nothing it reserved is left behind: no
   registered input, no pinned cache entry, no temporary file, no resolver entry, and the next queued
   announcement for that target starts.
3. **Given** a group announcement where one member is at the route limit, **Then** it plays on no
   member at all (the host admits a group route on all members or none).

#### Choose not to lower the room (Priority: P3)

[Source: .specify/archive/006-announcement-ducking/spec.md -> User Story 4]

Some announcements are better heard alongside the music at equal level — a short chime, or a room
where the operator does not want the music to dip. The operator sets this once in configuration; a
caller may also choose it per request.

**Why this priority**: Lowering is the right default for speech; this is a refinement for a minority
of uses, and it costs one setting because the host already supports the mode.

**Independent Test**: Configure the extension (or send a request) to mix rather than lower, announce
over music, and confirm the music level does not change.

**Acceptance Scenarios**:

1. **Given** no playback mode is configured or requested, **When** an announcement plays over music,
   **Then** the music is lowered (*duck-others*).
2. **Given** the configured playback mode is *mix*, **When** an announcement plays over music,
   **Then** both are heard and the music's level does not change.
3. **Given** any configured mode, **When** a request names a mode, **Then** the request's mode is
   used for that announcement only.
4. **Given** a request or configuration names a mode other than *duck-others* or *mix* (for example
   *replace*, or a misspelling), **Then** a request is rejected with `INVALID_REQUEST` before any
   synthesis and a configuration aborts start-up naming the extension and the key.

#### A failed start is reported once, as a failure (Priority: P1)

[Source: .specify/archive/007-failed-start-cleanup/spec.md -> User Story 1]

An operator sends an announcement to a speaker that cannot open its device. The announcement does
not play. The logs show one failure for it, with the reason, and no "completed" line and no
unrelated warning. The metrics count one failed playback for it and no started one.

**Why this priority**: The logs and metrics are how the operator learns that announcements are not
being heard. Reporting a failure as a completion hides exactly the fault they need to see.

**Independent Test**: With a hub whose route start fails after admission (simulated), submit one
announcement and inspect the log lines and the playback counters for it.

**Acceptance Scenarios**:

1. **Given** the hub admits an announcement's route and the route then fails to start, **When**
   the extension handles the outcome, **Then** exactly one failure line is logged for that
   announcement and no `TTS_PLAYBACK_COMPLETED` line is logged for it.
2. **Given** the same failed start, **When** the playback counters are read, **Then** the failed
   count has risen by exactly one and the started count has not risen.
3. **Given** the same failed start, **When** handling completes, **Then** the announcement's
   temporary input is no longer registered with the hub, and no warning reports that it could not
   be removed.

#### The queue moves on exactly once (Priority: P1)

[Source: .specify/archive/007-failed-start-cleanup/spec.md -> User Story 2]

After a failed start, the next announcement waiting for the same target plays, and the target's
queue is told only once that the failed announcement is over.

**Why this priority**: Before 007 the queue tolerated a second signal only by accident. A future
change to the queue (for example counting what it has played, or chaining work on that signal)
would turn the second signal into a real fault, such as skipping or double-starting the next
announcement.

**Independent Test**: Queue two announcements for one target, make the first one's start fail after
admission, and observe how many times the queue is told the first has ended and that the second
plays.

**Acceptance Scenarios**:

1. **Given** two announcements queued for one target and the first fails to start after
   admission, **When** the failure is handled, **Then** the queue is told once that the first has
   ended, and the second starts.
2. **Given** an announcement that fails to start after admission, **When** handling completes,
   **Then** the extension holds nothing more for it: its cached audio is no longer held for
   playback, its audio is no longer reachable by its address, and any one-off audio file is
   deleted.

#### Refusals and normal playback are unchanged (Priority: P2)

[Source: .specify/archive/007-failed-start-cleanup/spec.md -> User Story 3]

An announcement the hub refuses, and an announcement that plays to the end, are reported exactly
as before.

**Why this priority**: The fix touches the paths both of them use; they must not regress.

**Independent Test**: Run a refused announcement and a normally completed one and compare their
logs, counters and queue signals with the behaviour before 007.

**Acceptance Scenarios**:

1. **Given** the hub refuses an announcement's route, **When** the refusal is handled, **Then**
   `TTS_PLAYBACK_REFUSED` is logged with the hub's reason, one failed playback is counted, the
   announcement's input is removed by the extension, and the queue is told once.
2. **Given** an announcement whose route starts and plays to the end, **When** its route ends,
   **Then** `TTS_PLAYBACK_STARTED` and `TTS_PLAYBACK_COMPLETED` are logged, one started playback
   is counted, and the queue is told once.

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
- **006/FR-013** The extension declares multiroom-api 0.1.21 as its minimum, so that a host without
  join modes refuses to load it instead of loading an extension that relies on them
  [Source: .specify/archive/006-announcement-ducking/spec.md -> FR-013]

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
- **006/FR-012** Every existing request remains valid and behaves as described under Playback
  without change; the playback-mode request field is optional, and the success response's shape is
  unchanged [Source: .specify/archive/006-announcement-ducking/spec.md -> FR-012]
- **004/FR-021** A request may carry `stylePrompt` — for `google-gemini` only. Any `stylePrompt`
  present (even empty or whitespace) sent to another type is a 400 naming the field and the type
- **001/FR-027** Structured log events: request received, cache hit/miss, synthesis
  started/completed/error, playback started/completed
- **009/R1** A request may carry `routeVolume`, an integer 0-100: the volume of this announcement
  only. It replaces the target outputs' volume for the announcement and changes no output's
  volume, so the room's volume control does not reach it
  [Source: .specify/archive/009-route-volume-ducking-level/spec.md -> R1]
- **009/R2** A request may carry `duckingLevelDb`, a number from -60.0 to 0.0: how far a
  `duck-others` announcement lowers what else plays on the target
  [Source: .specify/archive/009-route-volume-ducking-level/spec.md -> R2]
- **009/R3** A `routeVolume` or `duckingLevelDb` out of range, or a `duckingLevelDb` when the
  effective playback mode is `mix`, answers 400 `INVALID_REQUEST` before anything is synthesized
  or queued; a request naming neither is unchanged
  [Source: .specify/archive/009-route-volume-ducking-level/spec.md -> R3]

### Providers

- **001/FR-003, FR-004, FR-013** Several named providers, cloud and local, each with its own type,
  credentials and settings (voice, language, rate, model/engine). Types: `OPENAI`, `GOOGLE_CLOUD`,
  `GOOGLE_GEMINI` (since 004), `PIPER` (piper1-gpl, `python3 -m piper`), `LOCAL_HTTP`
- **001/FR-005** Default provider: explicit, or the first in the list
- **001/FR-010, FR-011, FR-021** Clear errors for an unknown target, an unreachable or failing
  provider, and a rate limit — never an automatic retry. *Amended by 008*: a target name that breaks
  the hub's ID rule (1–255 letters, digits, hyphens or underscores; `"living room"`, a display name
  with an apostrophe) is an unknown target too: 400 `TARGET_NOT_FOUND`, message "No output or output
  group named '…' found (not a valid ID)", counted under `TARGET_NOT_FOUND`, never `INTERNAL`. Not
  `INVALID_REQUEST`, because the caller's fix is the same (pick a real output or group) and
  sonora-cli then reports "target not found"; the allowed characters are stated in the `targetName`
  OpenAPI description rather than checked by a pattern here
  [Source: .specify/archive/008-malformed-target-name/spec.md -> "Proposal"]
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
  the group as **one** route (one pipeline, hence rooms in sync). *Since 006 unchanged*: a group
  target is still played as one route to the group (006/FR-006)
  [Source: .specify/archive/006-announcement-ducking/spec.md -> FR-006]
- **001/FR-008** Restore the target's prior routes when the announcement ends, detected from
  `RouteDestroyedEvent`, never a timer. The input is registered with `autoRemove = true` and this
  module never unregisters it itself. *Amended by 006*: nothing is restored — no snapshot is kept
  and no route is recreated (006/FR-002). Completion is still observed from the route-destroyed
  signal, never a timer, and still releases everything the announcement held: cache pin, resolver
  entry, temporary file and the queue slot. The input is still auto-removed and never unregistered
  by this extension **after its route exists**; a route the host refused leaves no route to
  auto-remove it, so then the extension unregisters it (006/FR-007)
  [Source: .specify/archive/006-announcement-ducking/spec.md -> FR-005]
  [Source: .specify/archive/006-announcement-ducking/spec.md -> FR-002]
  *Amended by 007*: "after its route exists" means after its route **went live** — the hub
  auto-removes an input only then. After a refusal **or a failed start** the extension removes the
  input itself, since the hub does not. Finding it already removed (should a future hub remove it)
  produces no warning and may be noted at debug level; any other failure to remove it is still a
  warning with its detail [Source: .specify/archive/007-failed-start-cleanup/spec.md -> FR-004]
- **001/FR-009** A per-target in-memory queue that serializes **playback only**: validation, cache,
  synthesis and conversion finish before the response, so what is queued already has its file.
  *Amended by 006*: it keeps serializing announcements for one target in arrival order, so two
  announcements to the same target never overlap; queues stay keyed by target, not by output, so
  announcements to overlapping targets (a group and one of its members) are not serialized against
  each other [Source: .specify/archive/006-announcement-ducking/spec.md -> FR-004]
- **006/FR-001** An announcement is played by adding its own route to the target alongside whatever
  already plays there, in the effective playback mode (006/FR-009), and never stops, pauses or
  recreates any other route on the target — before, during or after playback
  [Source: .specify/archive/006-announcement-ducking/spec.md -> FR-001]
- **006/FR-002** The extension keeps no snapshot of the target's routes, and recreates no routes
  when an announcement ends or fails. Restoring the other routes' level is the host's job and
  happens without any action from this extension
  [Source: .specify/archive/006-announcement-ducking/spec.md -> FR-002]
- **006/FR-003** Accepting an announcement into a target's queue changes nothing audible on the
  target; only the announcement's own start does
  [Source: .specify/archive/006-announcement-ducking/spec.md -> FR-003]
- **006/FR-007** When the host refuses the announcement's route (route limit, or any other route
  failure), the extension releases everything the announcement reserved — including unregistering
  its input, since no route exists to auto-remove it — leaves every other route on the target
  untouched, and moves the target's queue on to the next announcement. A refused announcement is
  never retried: announcements are time-sensitive, and a late one is worse than none
  [Source: .specify/archive/006-announcement-ducking/spec.md -> FR-007]. *Since 007 unchanged*: a
  refusal logs `TTS_PLAYBACK_REFUSED` with the hub's reason and output, counts one failed playback,
  has its input removed by the extension and tells the queue once
  [Source: .specify/archive/007-failed-start-cleanup/spec.md -> FR-005]
- **006/FR-008** A host refusal is logged with the announcement id, the target, and the host's
  reason and offending output when the host gives them, and is counted as a failed playback in the
  existing metrics without adding a tag key to any existing metric and without any caller text as a
  tag value [Source: .specify/archive/006-announcement-ducking/spec.md -> FR-008]
- **006/FR-009** The effective playback mode of an announcement is the mode named in the request if
  any, otherwise the configured default, otherwise *duck-others*. The configured default is one
  value for the whole extension; there is no per-target default
  [Source: .specify/archive/006-announcement-ducking/spec.md -> FR-009]
- **006/FR-010** Only *duck-others* and *mix* are accepted as playback modes, in configuration and
  per request, case-insensitively. Any other value is rejected: per request with `INVALID_REQUEST`
  (400) before any cache lookup or synthesis; in configuration by aborting start-up with a message
  naming this extension and the key [Source: .specify/archive/006-announcement-ducking/spec.md -> FR-010]
- **006/FR-014** The REST contract, configuration reference and README describe the playback
  behaviour, the playback-mode field and setting, the host-side ducking settings that govern the
  level and fade, and the deployment order (host first)
  [Source: .specify/archive/006-announcement-ducking/spec.md -> FR-014]
- **007/FR-001** Every announcement taken off a queue ends exactly once: its resources released
  once, its queue told once, and its outcome counted once — whichever of the hub's end-of-route
  notice and the start failure reaches the extension first
  [Source: .specify/archive/007-failed-start-cleanup/spec.md -> FR-001]
- **007/FR-002** An announcement whose route was admitted but failed to start is reported as a
  failure: one failure log line naming the announcement and its target, one failed playback
  counted, and no completed log line [Source: .specify/archive/007-failed-start-cleanup/spec.md -> FR-002]
- **007/FR-003** An announcement whose route started is reported as completed when its route ends,
  including when the route ends before the start call has returned to the extension
  [Source: .specify/archive/007-failed-start-cleanup/spec.md -> FR-003]
- **007/FR-006** The behaviour is the same on a hub that usually sends no end-of-route notice for a
  route that failed to start (multiroom-api 0.1.21) and on one that always does (0.1.22 and later).
  The extension's minimum required API version is not raised for this
  [Source: .specify/archive/007-failed-start-cleanup/spec.md -> FR-006]
- **007/FR-007** The descriptions in the code of the start-failure cleanup and of the cancellation
  of a tracked announcement describe both orders of events (notice first, or no notice at all),
  replacing the statements that a failed route never produces a notice
  [Source: .specify/archive/007-failed-start-cleanup/spec.md -> FR-007]
- **007/FR-008** The metrics documentation states that an announcement that was admitted but failed
  to start counts as `failed`, never as `started`
  [Source: .specify/archive/007-failed-start-cleanup/spec.md -> FR-008]
- **001/FR-024** Shutdown lets the current announcement finish and discards the queue.
  `enabled=false` is a start-up state, never a runtime transition
- **009/R4** `multiroom.tts.playback.route-volume` and `multiroom.tts.playback.ducking-level-db` are
  optional defaults for a request that names none; a request value wins. A default outside its
  range aborts start-up naming the key, and is skipped when `multiroom.tts.enabled=false`
  [Source: .specify/archive/009-route-volume-ducking-level/spec.md -> R4]
- **009/R5** The configured ducking default applies only to a `duck-others` announcement; for
  `mix` no level is sent. An explicit request level with `mix` is a caller error (009/R3), a
  configured default is not
  [Source: .specify/archive/009-route-volume-ducking-level/spec.md -> R5]
- **009/R6** Both values are resolved when the request is accepted, as the playback mode is, so a
  queued announcement plays with the values in force when it was requested
  [Source: .specify/archive/009-route-volume-ducking-level/spec.md -> R6]
- **009/R7** A value left unset anywhere is not sent, so the hub's own defaults apply: the
  outputs' volume, and the input's then the system's ducking level
  [Source: .specify/archive/009-route-volume-ducking-level/spec.md -> R7]
- **009/R8** The extension requires `multiroom-api` 0.1.29 (`multiroom.require_api_version`); the
  values reach the hub through `RouteService.createRoute(..., RouteOptions)`
  [Source: .specify/archive/009-route-volume-ducking-level/spec.md -> R8]

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
- **006/FR-011** The playback mode is not part of the cache key: the same text in another mode is
  the same audio and is a cache hit [Source: .specify/archive/006-announcement-ducking/spec.md -> FR-011]

## Key entities

[Source: .specify/archive/001-tts-extension/data-model.md,
.specify/archive/002-google-voice-selection/data-model.md,
.specify/archive/003-google-service-account-auth/data-model.md,
.specify/archive/004-gemini-tts-provider/data-model.md]

- **Speak request** — text, target name, `TargetType`, optional provider / voice / language,
  (`google-cloud` only) engine / pitch, (both Google types) speaking rate, and (`google-gemini`
  only, since 004) style prompt. Since 006 also an optional `playbackMode` (a `String`, `null` = not
  given); an unknown value is `INVALID_REQUEST` before target lookup, cache lookup or synthesis
  [Source: .specify/archive/006-announcement-ducking/data-model.md -> "SpeakRequest / AnnounceCommand"].
  Since 009 also an optional `routeVolume` (`Integer`, 0-100) and `duckingLevelDb` (`Double`,
  -60.0 to 0.0), each `null` = not given [Source: .specify/archive/009-route-volume-ducking-level/spec.md -> R1, R2]
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
- **Announcement task** *(redefined by 006)* — a ready-to-route announcement: its file, target and
  playback mode (the effective mode, resolved when the request is accepted and used when the route
  is created). It no longer carries a snapshot of the target's routes. The per-target queue is
  bounded (default 10) and never persisted
  [Source: .specify/archive/006-announcement-ducking/spec.md -> "Announcement task"]
  [Source: .specify/archive/006-announcement-ducking/data-model.md -> "AnnouncementTask (changed)"]
- **Playback mode** *(006)* — how the announcement's route joins the target: *duck-others*
  (default; lowers everything else on the target's outputs to the host's ducking level while it is
  the newest lowering route there, restored by the host) or *mix* (equal level). A subset of the
  host's join modes; *replace* is deliberately excluded and not representable
  [Source: .specify/archive/006-announcement-ducking/spec.md -> "Playback mode"]
  [Source: .specify/archive/006-announcement-ducking/data-model.md -> "PlaybackMode (new enum, `multiroom.tts.config`)"]
- **Playback settings** *(006)* — configuration: the default playback mode for every announcement
  (`multiroom.tts.playback.default-mode`, `duck-others`), validated at start-up and skipped when
  `multiroom.tts.enabled=false` [Source: .specify/archive/006-announcement-ducking/spec.md -> "Playback settings"].
  Since 009 also `playback.route-volume` (0-100) and `playback.ducking-level-db` (-60.0 to 0.0),
  both unset by default and validated at start-up [Source: .specify/archive/009-route-volume-ducking-level/spec.md -> R4]
  [Source: .specify/archive/006-announcement-ducking/data-model.md -> "PlaybackProperties (new, `multiroom.tts.playback`)"]
- **Announcement in flight** *(007)* — an announcement taken off its target's queue whose end has
  not yet been handled. It ends exactly once, with one outcome: completed (it started) or failed (it
  was refused, or it was admitted and did not start). Held in the completion listener's in-flight
  map by its unique input id together with the queue's completion callback; removing that entry is
  the single point that decides who ends the announcement
  [Source: .specify/archive/007-failed-start-cleanup/spec.md -> "Announcement in flight"]
  [Source: .specify/archive/007-failed-start-cleanup/data-model.md -> "Announcement in flight"]
- **End-of-route notice** *(007)* — the hub's announcement that a route has ended
  (`RouteDestroyedEvent`). Since hub 024 it is sent for every admitted route, whether or not it
  started; a route that never went live carries no start time
  [Source: .specify/archive/007-failed-start-cleanup/spec.md -> "End-of-route notice"]
  [Source: .specify/archive/007-failed-start-cleanup/research.md -> R3]
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
RECEIVED → VALIDATED            text, then playbackMode (006), target, provider (or REJECTED → 400)
  → RESOLVED    provider.resolveSettings: pure, no I/O          (or REJECTED → 400)
  → CACHE_CHECK → hit:  AUDIO_READY (pinned)                    — no catalogue work at all
                → miss: [Google service account: token first → or FAILED 503, check skipped]
                        [google-cloud: catalogue check → INVALID_VOICE 400, or skipped if unreachable]
                        [google-gemini: no catalogue step at all]
                        SYNTHESIZING (a 401 renews the token and resends once)
                        → CONVERTING → AUDIO_READY                (or FAILED → 503)
                        token, catalogue fetch and synthesis share the entry's timeout
  → QUEUED      nothing audible changes; an enqueue failure unpins / deletes the spill file → 503
  → ROUTING   track; registerInput("tts://<uuid>", autoRemove); createRoute(input, target, playback
              mode) — core resolves tts:// to the WAV's file:// URI. No snapshot, nothing stopped
              (since 006)
              registerInput failure → ERROR TTS_PLAYBACK_FAILED, handled as a failed start below
              (an input that was never registered is tolerated by unregisterInput)
              host refusal (RouteAdmissionException) → WARN TTS_PLAYBACK_REFUSED, playback failed;
              admitted but failed to start → ERROR TTS_PLAYBACK_FAILED (since 007); on hub
              0.1.22+ its end-of-route notice (no startedAt) arrives first and is left to this
              path; either way: playback failed counted once, unpin, release, delete,
              unregisterInput (an already-removed input is DEBUG) → next, signalled once
  → PLAYING   others lowered by the host under duck-others … RouteDestroyedEvent
  → RELEASING unpin, release the resolver entry, drop any temp file → next in queue
              (input removed by core's auto-remove; other routes restored by the host)
  → COMPLETED
```

Until 006 the ROUTING step snapshotted and stopped the target's routes, and a RESTORING step
recreated them after `RouteDestroyedEvent`; both are gone
[Source: .specify/archive/006-announcement-ducking/data-model.md -> "Announcement lifecycle"].

Since 007 exactly one of the completion listener (route went live) and the failure path (refused
or failed start) removes the in-flight entry, and only the remover releases and runs the queue
callback; the failure path counts the failure unconditionally, so started plus failed equals the
announcements taken off queues
[Source: .specify/archive/007-failed-start-cleanup/data-model.md -> "States and transitions"].

Since 005 the flow is unchanged but observed: every answered request is counted, the cache lookup
is counted hit or miss, a synthesis is timed and its characters measured once immediately before
the provider call (conversion outside the timing), and the playback start is counted `started` or
`failed` [Source: .specify/archive/005-tts-metrics/spec.md -> FR-005, FR-011, FR-021, FR-015].

## Edge cases

[Source: .specify/archive/001-tts-extension]

- Unknown target or provider: rejected before any synthesis. Since 008 this includes a target name
  the hub's ID rule refuses (`"living room"`), which never reaches the device lookup
  [Source: .specify/archive/008-malformed-target-name/spec.md -> "Problem"]
  [Source: .specify/archive/008-malformed-target-name/verification.md -> "Production"]
- Concurrent requests for one target: both synthesized as they arrive, played in arrival order, no
  deduplication. Since 006 the second never lowers the first (the per-target queue is unchanged)
  [Source: .specify/archive/006-announcement-ducking/spec.md -> "Two announcements for the same target"]
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
- *(006)* Announcements for overlapping targets (one to the group, one to a member, each with its
  own queue) may play at the same time on the shared output. The host puts the newer one on top and
  lowers the older one with the music; each is restored as the one above it ends. This replaced the
  pre-006 behaviour, where the second announcement stopped the first one's route
  [Source: .specify/archive/006-announcement-ducking/spec.md -> "Announcements for overlapping targets"]
- *(006)* Another integration starts a source on the output during an announcement (Home Assistant
  source select, Chromecast auto-route, a REST replace): the announcement is not cut off; the new
  source starts lowered beneath it and comes up when it ends. This is the host's rule; nothing here
  restores or recreates anything
  [Source: .specify/archive/006-announcement-ducking/spec.md -> "Another integration starts a source on the output"]
- *(006)* The music is stopped by someone else during an announcement: the announcement plays to
  its end, and the room is silent afterwards. This extension never recreates a route it did not
  create [Source: .specify/archive/006-announcement-ducking/spec.md -> "The music is stopped by someone else"]
- *(006)* An explicit stop of everything on the output (MQTT STOP, REST stop-all) during an
  announcement: the announcement ends too, its resources are released, and the next queued
  announcement starts
  [Source: .specify/archive/006-announcement-ducking/spec.md -> "An explicit stop of everything on the output"]
- *(006)* A very short announcement (shorter than the host's fade): handled by the host without a
  click; nothing changes here [Source: .specify/archive/006-announcement-ducking/spec.md -> "A very short announcement"]
- *(006)* The host's ducking level and fade time are system-wide host settings; this extension
  neither sets nor overrides them
  [Source: .specify/archive/006-announcement-ducking/spec.md -> "The host's ducking level and fade time"]
- *(006)* A host older than multiroom-api 0.1.21 refuses to load this JAR at start-up because of its
  declared API requirement; the extension is absent rather than half-working. Upgrading the host
  first is part of deployment
  [Source: .specify/archive/006-announcement-ducking/spec.md -> "A host older than multiroom-api 0.1.21"]
- *(006)* Shutdown during an announcement: the current announcement finishes and the queue is
  discarded; there is no longer anything to restore
  [Source: .specify/archive/006-announcement-ducking/spec.md -> "Shutdown during an announcement"]
- *(006)* A route that was waiting to be restored at upgrade time cannot exist — restoration state
  was in memory only and is gone after the restart the upgrade requires
  [Source: .specify/archive/006-announcement-ducking/spec.md -> "A route that was waiting to be restored at upgrade time"]
- *(007)* A hub older than 024 usually sends no end-of-route notice for a route that failed to start
  (it may for a route stopped while starting). The extension still cleans up once, reports one
  failure and removes the input itself
  [Source: .specify/archive/007-failed-start-cleanup/spec.md -> "A hub older than 024"]
- *(007)* A very short announcement finishes on the hub's own thread before the start call returns
  to the extension: if the hub had already reported it started, it played and is reported as
  completed, not as a failure; if its audio ran out before the hub reported it started, the hub
  itself reports the start as failed, and so does the extension
  [Source: .specify/archive/007-failed-start-cleanup/spec.md -> "A very short announcement finishes on the hub's own thread"]
- *(007)* Removing the input fails for a reason other than "already removed" (an unexpected hub
  error): still logged as a warning with its detail
  [Source: .specify/archive/007-failed-start-cleanup/spec.md -> "Removing the input fails for a reason other than"]
- *(007)* The hub sends the end-of-route notice twice for one route: the announcement is still
  handled once [Source: .specify/archive/007-failed-start-cleanup/spec.md -> "The hub sends the end-of-route notice twice"]
- *(007)* A failure before the route step (registering the input or tracking the announcement
  fails): counted once as a failed playback, as before 007
  [Source: .specify/archive/007-failed-start-cleanup/spec.md -> "A failure before the route step"]
- *(009)* A request names `duckingLevelDb` while the effective mode is `mix` (requested, or the
  configured default): 400 `INVALID_REQUEST` before the target lookup, any cache lookup or
  synthesis, and nothing is queued [Source: .specify/archive/009-route-volume-ducking-level/spec.md -> R3]
- *(009)* A configured `playback.ducking-level-db` with a `mix` announcement: not an error; no
  level is sent [Source: .specify/archive/009-route-volume-ducking-level/spec.md -> R5]
- *(009)* The configured defaults change while an announcement waits in the queue: it plays with
  the values in force when it was accepted [Source: .specify/archive/009-route-volume-ducking-level/spec.md -> R6]

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

| 006/SC-001 no interruption | music on the target interrupted for 0 ms, no restart of the source | Unit-tested (no `stop*` call); production check T036 |
| 006/SC-002 level back | music at its previous level within 1 s of the announcement's last audible sample (the host's ducking-release target) | Production check T036 |
| 006/SC-003 busy output | a cache hit on a busy output starts no later than on an idle one (< 1 s request to sound) | Nothing is stopped first by design; production check T036 |
| 006/SC-004 no foreign route events | 50 consecutive announcements over music on the production device: zero routes other than the announcements' own stopped or created | `verifyNoMoreInteractions(routeService)`; production check T036 |
| 006/SC-005 refusal leaves nothing | 0 registered inputs, pinned entries, temp files or resolver entries left; nothing audible changed | Unit-tested (`TtsServiceTest`, `PlaybackCompletionListenerTest`); quickstart §7 only with the user's permission |
| 006/SC-006 compatibility | 100 % of existing request bodies accepted unchanged; every pre-upgrade cache entry still a hit | `CacheKeyTest` pinned hashes unchanged; controller tests |
| 006/SC-007 Home Assistant | shows the room's music as its source during and after an announcement, no flicker | Production check T036 |
| 007/SC-001 failed start logged once | 0 completion lines and 0 input-removal warnings, exactly 1 failure line, for a route that fails after admission | Unit-tested only; a failed start cannot be provoked safely on production |
| 007/SC-002 one end per announcement | in each of four cases (completed, refused, admitted-but-failed on a current hub, failed on an older hub) the queue is told once and one outcome is counted | Unit-tested (`TtsServiceTest`, `PlaybackCompletionListenerTest`) |
| 007/SC-003 counters add up | started + failed = announcements taken off their queues | Unit-tested; on production after the 0.1.6 deploy one normal announcement gave `started` 1 and no `failed` series |
| 007/SC-004 no regression | refused and completed announcements produce the same lines, counts and queue signals as before 007 | Unit-tested; one normal announcement on production logged STARTED and COMPLETED once each |
| 009 one `createRoute` call | a request with both fields reaches the hub as one `createRoute` call whose `RouteOptions` carry the join mode, the volume and the level | Unit-tested (`TtsServiceTest`) |
| 009 refusals and faults | out-of-range values and a level with `mix` answer 400 before synthesis; out-of-range defaults abort start-up | Unit-tested (`TtsControllerTest`, `TtsServiceTest`, `TtsPropertiesValidationTest`) |
| 009 live route | with hub 034 a route started by an announcement reads back with both values | Recorded by the hub on 2026-10-24 (route volume 100, level -25 dB over a playing stream); not repeated in this repository |

Sources for the 005 rows: [.specify/archive/005-tts-metrics/spec.md -> SC-001–SC-007], status from
[.specify/archive/005-tts-metrics/tasks.md -> T041, T047, T048].
Sources for the 006 rows: [.specify/archive/006-announcement-ducking/spec.md -> SC-001–SC-007], status from
[.specify/archive/006-announcement-ducking/tasks.md -> T035, T036] and
[.specify/archive/006-announcement-ducking/plan.md -> "Testing"]. T036 is marked done; its results were
recorded in the pull request, not in the feature's artifacts.
Sources for the 007 rows: [.specify/archive/007-failed-start-cleanup/spec.md -> SC-001–SC-004], status from
[.specify/archive/007-failed-start-cleanup/verification.md].
Sources for the 009 rows: [.specify/archive/009-route-volume-ducking-level/spec.md -> "Success criteria"], status from
[.specify/archive/009-route-volume-ducking-level/verification.md].

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
- **AS-010** Replace is not offered as a playback mode. Replace without restoration would silence
  the room permanently after an announcement; replace with restoration is the stop–restore
  behaviour 006 removed. Dropping it let the snapshot and restore machinery go
  [Source: .specify/archive/006-announcement-ducking/spec.md -> "Replace is not offered."]
- **AS-011** The mode is passed per route, not declared on the input. Announcement inputs are
  ephemeral and created by this extension for each announcement, so naming the mode on the route
  request is the direct form; the result is the same
  [Source: .specify/archive/006-announcement-ducking/spec.md -> "The mode is passed per route, not declared on the input."]
- **AS-012** The per-target queue stays. The host would let two announcements on one output overlap,
  with the newer on top and the older lowered underneath; for speech that means a listener misses
  part of the first one, so serial playback per target is kept. Overlapping *targets* (a group and
  its member) are not coordinated
  [Source: .specify/archive/006-announcement-ducking/spec.md -> "The per-target queue stays."]
- **AS-013** The host's ducking settings are the only ducking settings. Level (about −15 dB) and
  fade (about 150 ms) are system-wide host configuration; per-announcement or per-extension levels
  are out of scope
  [Source: .specify/archive/006-announcement-ducking/spec.md -> "The host's ducking settings are the only ducking settings."]
- **AS-014** Refusals happen after the request was accepted. Routing runs when the announcement
  reaches the front of its queue, after the caller has had its success response, so a host refusal
  is reported in logs and metrics, not to the caller. Predicting a refusal at request time would be
  racy and is out of scope. A known issue: a full room still gets `202`. A later feature may refuse
  at request time when the target's queue is empty and an output is already at the host's route
  limit, which needs that limit in multiroom-api first
  [Source: .specify/archive/006-announcement-ducking/spec.md -> "Refusals happen after the request was accepted."]
- **AS-015** Deployment order: the host (multiroom-ai with 023) is upgraded first, then this JAR is
  redeployed; the starter parent and the API requirement move to 0.1.21 together
  [Source: .specify/archive/006-announcement-ducking/spec.md -> "Deployment order"]
- **AS-016** Audible behaviour is verified on the production device after deployment, as for
  earlier playback-affecting changes; the unit tests cover the routing calls and cleanup
  [Source: .specify/archive/006-announcement-ducking/spec.md -> "Audible behaviour is verified on the production device"]
- **AS-017** The hub's end-of-route notice for a route that failed to start is delivered before the
  start failure reaches the extension, though possibly on another thread (the hub's event dispatch
  may be drained by another thread while the starting caller waits). The extension is still correct
  if this ordering changes or the notice never arrives
  [Source: .specify/archive/007-failed-start-cleanup/spec.md -> "The hub's end-of-route notice for a route that failed to start"]
- **AS-018** A route that never started is told from one that finished by the route's missing start
  time in the notice. When 007 was specified the hub did not document that meaning; it now does
  (sonora-multiroom `d7f3b46`, on `Route.startedAt`, `RouteDestroyedEvent` and
  `RouteService.createRoute`, pinned by hub tests), so no hub backlog item was filed
  [Source: .specify/archive/007-failed-start-cleanup/spec.md -> "How the extension tells a route that never started"]
  [Source: .specify/archive/007-failed-start-cleanup/tasks.md -> T030]
- **AS-019** `tts_playbacks_total` keeps its `outcome` values (`started`, `failed`); no tag value or
  metric is added. A failed start was already counted as `failed`; 007 only removed the completion
  reported alongside it
  [Source: .specify/archive/007-failed-start-cleanup/spec.md -> "`tts_playbacks_total` keeps its existing `outcome` values"]
- **AS-020** A route that fails after admission cannot be produced safely on the production
  Raspberry Pi on demand, so failed starts are verified by tests with a simulated hub plus the usual
  deploy-and-inspect smoke run
  [Source: .specify/archive/007-failed-start-cleanup/spec.md -> "Verifying on the production Raspberry Pi requires"]
- **AS-021** The backlog item `failed-start-double-cleanup` is deleted once 007 ships
  [Source: .specify/archive/007-failed-start-cleanup/spec.md -> "The backlog item is deleted once this feature ships."]

## Host coupling

Built against `multiroom-api` **0.1.29** since 009 (0.1.21 since 006, 0.1.18 before; 009 needs
`RouteOptions` and the options overload of `createRoute`) [Source: .specify/archive/009-route-volume-ducking-level/spec.md -> R8]; 006 needed its join modes and
`RouteAdmissionException` [Source: .specify/archive/006-announcement-ducking/plan.md -> "Primary Dependencies"],
resolved from the local `~/.m2`; the host repository is
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
