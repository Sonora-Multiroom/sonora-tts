# Feature Specification: TTS Extension

**Feature Branch**: `001-tts-extension` (this repository; `021-tts-extension` upstream before the move)

**Created**: 2026-05-29 | **Revised**: 2026-09-20

**Status**: Completed

**Depends on**: `019-extension-shared-classloader` — merged before this work starts. That feature
changes *how* an extension joins the application, which removes an entire infrastructure phase this
spec previously required. Nothing about what TTS does changed; see [plan.md](plan.md).


**Input**: User description: "new feature - extension with TTS (text-to-speech) integration; idea here is that we will have an extension which can use preconfigured TTS provider (either cloud-based or local-based), generates an audio file for requested text and play it on target single input or group input"

## Overview

This feature introduces a Text-to-Speech (TTS) extension for the multiroom audio system. The extension accepts a text message and a target destination (a single audio output or an output group), synthesizes speech audio using a preconfigured TTS provider, and plays the resulting audio on the designated output(s). This enables use cases such as announcements, notifications, and voice alerts delivered across one or all rooms.

## Clarifications

### Session 2026-05-29

- Q: Should callers be required to authenticate to trigger TTS? → A: Inherit from host extension (REST/MQTT auth applies; no separate TTS auth layer)
- Q: What constitutes "provider identity" in the cache key? → A: Provider type + model/engine name (e.g., `google-tts:neural2`)
- Q: Should the TTS extension enforce a maximum text length per request? → A: Configurable limit, default 500 characters; requests exceeding it are rejected
- Q: What operational signals should the extension expose? → A: Structured logging only (request received, cache hit/miss, synthesis started/completed, errors)
- Q: Should queued announcements be recovered after a system restart? → A: No — the queue is in-memory only; unplayed announcements are silently dropped on restart
- Q: How many providers can be active simultaneously? → A: Multiple named providers can be configured at once; a default provider is set in configuration; individual requests may specify a provider by name to override the default
- Q: What happens if no default provider is explicitly configured? → A: The first provider in the configuration list becomes the implicit default

### Session 2026-09-20 (from `/speckit-analyze`)

- Q: How does the extension know an announcement has finished, so it can restore the target's prior state? → A: Through the route lifecycle — core destroys the announcement's route once the file input reaches EOF and the pipeline drains, and publishes `RouteDestroyedEvent`. The extension listens for it; it does not poll and does not estimate from audio duration
- Q: What is the default provider synthesis timeout? → A: 10 seconds, so that a hung provider still satisfies SC-003's 10-second error budget. Individual providers may configure a different value
- Q: May a request override voice and language, not just the provider? → A: Yes — both are optional per-request overrides, falling back to the chosen provider's configured defaults
- Q: Who converts provider audio to the format the outputs need? → A: The system's existing `FormatConverter`, which moves from `multiroom-core` to `multiroom-api` as part of this feature so extensions can use it. The extension does not implement its own resampler
- Q: Does the cache key need the audio format? → A: Yes. A cached file is converted to a specific target format and then survives restarts and output-device changes; without the format in the key it could be served to an output expecting something else. A format change is a miss, not a wrong-format hit
- Q: What happens if the extension is disabled while an announcement is playing? → A: The state is unreachable. `multiroom.tts.enabled` is resolved once at context refresh, so the extension is present or absent for a whole run; the flag takes effect at the next start, when nothing is playing. FR-024 governs shutdown only
- Q: What shape do error responses take? → A: The one published in `contracts/tts-rest-api.yaml` — a machine-readable code plus a message — mapped in a single place: 400 for caller-fixable faults, 503 for provider-side ones
- Q: Which status does a refused format conversion return, the one code the mapping left unassigned? → A: 503. The caller chose neither the source format nor the target one, so rewording the request cannot help; it is provider-side. Every code in the published enum now has exactly one status
- Q: May a provider's timeout exceed 10 seconds, given SC-003? → A: Yes — a local Piper model on modest hardware is a legitimate reason. SC-003 is stated as holding at the default, and start-up logs a warning naming any provider configured above 10 s, so the trade is visible rather than silent
- Q: When does SC-006's 1-second restore clock start? → A: At the route-destroyed event, which is the only completion signal an extension can observe. The pipeline's drain (outputs emptying plus a driver-buffer settle) precedes that event and lies outside the budget; both figures are recorded separately so the audible gap is not hidden

## Assumptions

- The TTS extension integrates with the extension architecture as `019-extension-shared-classloader` leaves it: a drop-in JAR discovered before start-up, contributing into the single application context by auto-configuration, obtaining core services by ordinary dependency injection. It ships no private framework copy, runs no second web server, and implements no extension lifecycle interface.
- **Announcements are triggered over HTTP**, on the application's single port under the extension's own `/api/tts/**` paths. MQTT triggering is deferred: it would require a cross-extension mechanism that is out of scope here (see [plan.md](plan.md)), and FR-014 requires only one transport.
- The TTS trigger endpoint does not implement its own authentication layer; access control is fully delegated to the host extension (REST or MQTT) that exposes it.
- A "TTS request" is triggered externally — e.g., via an HTTP REST call, MQTT message, or Home Assistant automation — not through direct user interaction with a UI.
- Generated audio is cached on disk, keyed by the combination of text content, provider type + model/engine
  name, voice/language settings, **and the audio format the entry was converted to**. Cached audio survives
  system restarts, and the format belongs in the key because it does: an entry written for one native format
  must not be served to an output expecting another.
- The cache has a configurable maximum size; entries are evicted using a least-recently-used (LRU) policy when the limit is reached.
- Cache is invalidated per-entry (text+provider key), not globally; switching providers does not clear entries from the previous provider. Changing the model/engine within the same provider also produces a cache miss.
- Multiple TTS providers (cloud-based and/or local) can be configured simultaneously, each identified by a unique user-defined name. A default provider is designated in configuration; if no explicit default is set, the first provider in the list is used as the default.
- Individual TTS requests may specify a provider name to override the default.
- Supported target types reuse the public API's `multiroom.api.model.TargetType`: `SINGLE_OUTPUT` (a named audio
  output) or `OUTPUT_GROUP` (a named output group). The extension defines no target-type enum of its own.
- Synthesized audio is materialized as a WAV file on disk (the cache entry itself) and played through an ephemeral
  registered input pointing at that file. Playback therefore always has a file behind it, which is what makes
  FR-025's "play anyway" behaviour concrete: on a cache-write failure the audio spills to a temporary file outside
  the cache directory.
- Playback interrupts or queues behind any currently playing audio on the target output (assumption: queue by default; interruption is a stretch goal).
- Completion of an announcement is observed, not estimated: core tears the announcement's route down when the
  synthesized file reaches EOF and the pipeline has drained, publishing `RouteDestroyedEvent`. Restoration of
  the target's prior state hangs off that event.
- The announcement queue is held in memory only; unplayed announcements are silently dropped on system restart. Announcements are considered time-sensitive and have no value after a restart.
- The TTS extension does not require a specific audio format from the provider — it converts the provider's
  audio to the system's native audio format using the shared `FormatConverter`, which was promoted
  from `multiroom-core` into the public API module so extensions can use it. The extension implements no
  resampling, channel-mixing or bit-depth conversion of its own.
- Cloud-based providers require API credentials stored in configuration; local providers require the provider endpoint/binary path in configuration.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Announce Text on a Specific Room (Priority: P1)

A user (or an automation system) sends a text announcement (e.g., "Dinner is ready") and specifies a target output by name (`TargetType.SINGLE_OUTPUT`). The TTS extension synthesizes the speech, registers an ephemeral audio input backed by the synthesized file, and creates a route from that input to the target output.

**Why this priority**: This is the core use case — triggering a spoken announcement on a specific room. All other stories build on this capability.

**Independent Test**: Can be fully tested by sending a TTS request with a target output name and verifying that synthesized speech audio plays through that output.

**Acceptance Scenarios**:

1. **Given** the TTS extension is enabled and a TTS provider is configured, **When** a TTS request is received with text "Hello" and target output "living-room", **Then** synthesized speech audio plays through the "living-room" output within 5 seconds.
2. **Given** a TTS request is in progress, **When** the audio finishes playing, **Then** the target output returns to its prior state (previous route restored, or silence).
3. **Given** a TTS request with empty text, **When** the request is processed, **Then** no audio is played and an error response is returned.

---

### User Story 2 - Announce Text Across All Rooms (Output Group) (Priority: P2)

A user sends a text announcement targeting an output group (e.g., "all-rooms", `TargetType.OUTPUT_GROUP`). The TTS extension synthesizes the speech and routes it simultaneously to all outputs belonging to the group, enabling synchronized multi-room announcements.

**Why this priority**: Multi-room announcements are a high-value use case (e.g., fire alarms, household alerts) and are a natural extension of the single-room story.

**Independent Test**: Can be fully tested by sending a TTS request with an output group target and verifying that audio plays in all rooms assigned to that group.

**Acceptance Scenarios**:

1. **Given** an output group "all-rooms" exists with three outputs, **When** a TTS request targets "all-rooms", **Then** synthesized speech plays simultaneously on all three outputs.
2. **Given** a TTS request targets a group that has no outputs, **When** the request is processed, **Then** the system returns an error indicating no outputs are available for the target.

---

### User Story 3 - Switch TTS Provider per Request or via Configuration (Priority: P3)

A system administrator configures both a cloud-based and a local TTS provider. Most announcements use the default (cloud) provider, but specific requests can override this and target the local provider by name — useful for offline scenarios or privacy-sensitive rooms.

**Why this priority**: Provider flexibility is important for different deployment environments but does not block the core announcement functionality.

**Independent Test**: Can be tested by configuring two providers, sending requests with and without an explicit provider override, and verifying each uses the correct provider.

**Acceptance Scenarios**:

1. **Given** two providers are configured ("cloud" and "local"), **When** a TTS request arrives with no provider specified, **Then** the default provider is used.
2. **Given** two providers are configured, **When** a TTS request arrives specifying provider "local", **Then** the local provider is used regardless of the default.
3. **Given** a provider name is specified in the request that does not match any configured provider, **When** the request is processed, **Then** an error is returned and no audio plays.
4. **Given** an invalid or unreachable provider is specified, **When** a TTS request is received, **Then** the extension returns a clear error and no audio is played.

---

### User Story 5 - Serve Repeated Announcements from Cache (Priority: P2)

When the same text is requested for the same TTS provider (and voice/language settings), the system plays previously synthesized audio directly from cache instead of contacting the provider again. This reduces latency for repeated announcements and avoids unnecessary provider API calls.

**Why this priority**: Frequently repeated announcements (e.g., recurring alerts like "Motion detected") are a common pattern. Caching directly reduces provider cost and improves response time, making it as important as multi-room support.

**Independent Test**: Can be tested by sending the same TTS request twice and verifying that the second request plays faster and does not contact the provider.

**Acceptance Scenarios**:

1. **Given** a TTS request for text "Motion detected" has been played before with the current provider, **When** a new request arrives for the same text and provider, **Then** the cached audio is used and no provider call is made.
2. **Given** a cached entry exists for text "Hello" with provider A, **When** the provider is switched to provider B and a request for "Hello" arrives, **Then** a new synthesis call is made to provider B (cache miss — different provider key).
3. **Given** the cache is full (at configured maximum), **When** a new unique text is synthesized, **Then** the least recently used cache entry is evicted to make room.
4. **Given** a cached audio entry exists, **When** an administrator explicitly clears the cache, **Then** the next request for that text triggers fresh synthesis.

---

### User Story 4 - Queue Multiple TTS Announcements (Priority: P4)

When multiple TTS requests arrive for the same target in quick succession, announcements are queued and played in order rather than dropped or overlapped.

**Why this priority**: Reliability of delivery is important for notifications, but ordered queuing is a secondary concern compared to core playback functionality.

**Independent Test**: Can be tested by sending two TTS requests to the same target in rapid succession and verifying both announcements play in order.

**Acceptance Scenarios**:

1. **Given** a TTS announcement is currently playing on output "kitchen", **When** a second TTS request arrives for "kitchen", **Then** the second announcement queues and plays immediately after the first finishes.
2. **Given** a queue of pending announcements, **When** the system is shut down, **Then** any unplayed queued announcements are discarded gracefully.

---

### Edge Cases

- **Unknown target output or output group**: The request is rejected immediately with a clear error response; no synthesis call is made. *(FR-010)*
- **Unknown provider name in request**: The request is rejected immediately with a clear error response; no synthesis call is made. *(FR-002)*
- **Cloud provider rate limit exceeded**: The request fails with an error response describing the rate limit condition. The system does not retry automatically — retry is the caller's responsibility. *(FR-021)*
- **Provider response timeout**: A configurable timeout applies to every synthesis call. If exceeded, the call is cancelled and an error is returned; no audio plays. *(FR-022)*
- **Incompatible audio format from provider**: The extension converts the provider's audio to the native format before routing, using the shared `FormatConverter`. If that converter cannot handle the source/target pair, the request is rejected with an error and no audio plays. *(FR-023)*
- **Concurrent requests for the same target**: Both are synthesized as they arrive — the provider is never the contended resource — then enqueued and played in arrival order; no deduplication is applied. Each caller learns its own synthesis outcome from its own response. *(FR-009)*
- **Shutdown mid-playback**: The currently playing announcement is allowed to complete; all queued announcements are discarded; no new requests are accepted. *(FR-024)*
- **Extension disabled**: `multiroom.tts.enabled=false` is resolved once, when the application context refreshes, so the extension is either present for the whole run or absent for the whole run. **Disabling mid-playback is not a reachable state** — the flag takes effect on the next start, at which point there is nothing playing. Disabling therefore needs no runtime behaviour beyond contributing no beans. *(019 FR-008)*
- **Cache storage location out of disk space**: The announcement plays normally using the freshly synthesized audio; only the cache write is skipped. A warning is logged and the system continues operating without caching until space is available. *(FR-025)*

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST provide a TTS extension that integrates with the multiroom extension architecture as delivered by `019-extension-shared-classloader`: discovered as a drop-in distributable, contributing its functionality into the single running application, with no per-extension bootstrap code and no private copy of the shared framework. It MUST be independently switchable off by configuration without removing its distributable, and MUST appear in the runtime extension inventory.
- **FR-002**: The TTS extension MUST accept a text string, a target destination (an output name or output group name, discriminated by the public API's `TargetType`), and an optional provider name as inputs for each announcement request. When no provider name is supplied, the configured default provider is used.
- **FR-003**: The TTS extension MUST synthesize speech audio from the provided text using the configured TTS provider.
- **FR-004**: The TTS extension MUST support at least two provider types: cloud-based (e.g., network API) and local (e.g., local service or binary).
- **FR-005**: Multiple TTS providers MAY be configured simultaneously, each identified by a unique user-defined name. A default provider MUST be designated either explicitly in configuration or implicitly as the first provider in the list. The set of configured providers and the default MUST be changeable via configuration without code changes.
- **FR-006**: The TTS extension MUST route synthesized audio to the specified single output destination (`TargetType.SINGLE_OUTPUT`), by registering an ephemeral input for the synthesized audio and creating a route from it to that output.
- **FR-007**: The TTS extension MUST route synthesized audio to the specified output group destination (`TargetType.OUTPUT_GROUP`), playing it simultaneously across all outputs in the group.
- **FR-008**: The TTS extension MUST return the target output (or every output in the target group) to its prior state — the route that was active before the announcement, or silence — after the announcement finishes, and MUST release the ephemeral input it created — which means registering that input with auto-remove enabled and letting the platform unregister it on the same completion event, **not** calling unregister itself; a second unregister for an input the platform has already removed raises an error. Completion MUST be detected from the route lifecycle event core publishes when the announcement's route ends, not from a timer or an estimate of the audio's duration.
- **FR-009**: The TTS extension MUST queue multiple consecutive TTS requests for the same target and play them in order. **The queue serializes playback, not synthesis**: validation, the cache lookup, the provider call and the format conversion all complete before the request is acknowledged, and what is enqueued is an announcement whose audio file already exists. Two announcements for one target therefore contend for the output, never for the provider. The queue is in-memory only and is not persisted across restarts.
- **FR-010**: The TTS extension MUST return a clear error response when the target output or output group does not exist.
- **FR-011**: The TTS extension MUST return a clear error response when the TTS provider is unreachable or returns an error.
- **FR-012**: The TTS extension MUST reject requests with empty or whitespace-only text, and MUST reject requests whose text exceeds a configurable maximum length (default: 500 characters).
- **FR-013**: The TTS extension configuration MUST support defining multiple named providers, each with its own provider type, credentials (e.g., API keys), and provider-specific settings (e.g., voice, language, speaking rate, model/engine name).
- **FR-014**: The TTS extension MUST be triggerable via at least one external mechanism. This is satisfied by HTTP endpoints served on the application's single port; MQTT triggering is deferred and is not required for this feature to be complete.
- **FR-015**: The TTS extension MUST cache synthesized audio keyed by the combination of text content, provider type, provider model/engine name, voice/language settings, and the audio format (sample rate, channel count, bit depth, sample type) the cached file was converted to. Including the format keeps entries self-describing across restarts and output-device changes: a file converted for one target format is a miss for another rather than being served in the wrong format.
- **FR-016**: The TTS extension MUST serve subsequent requests for the same text+provider+voice combination from cache without contacting the provider.
- **FR-017**: The cache MUST persist across system restarts (disk-based storage).
- **FR-018**: The cache MUST enforce a configurable maximum size and evict the least recently used entries when the limit is reached.
- **FR-019**: The TTS extension MUST provide a mechanism to manually clear the audio cache (e.g., via an administrative request).
- **FR-020**: If a cached audio file is missing, corrupt, or unreadable, the system MUST treat it as a cache miss, re-synthesize the audio from the provider, and replace the entry.
- **FR-021**: The TTS extension MUST return a clear error response when the provider reports a rate limit or quota exceeded condition, without retrying automatically.
- **FR-022**: The TTS extension MUST apply a configurable timeout to provider synthesis calls, defaulting to 10 seconds so that a hung provider still fits inside SC-003's 10-second error budget; if the timeout is exceeded, the request MUST be cancelled and an error response returned to the caller. A longer value MUST remain configurable — a local Piper model on modest hardware is a legitimate reason for one — but the extension MUST log a warning at start-up naming any provider whose timeout exceeds 10 seconds, since that provider's failures no longer fit SC-003's bound.
- **FR-023**: If the audio returned by the provider cannot be converted to the system's native audio format — reported by the shared `FormatConverter` refusing the source/target format pair — the TTS extension MUST reject the request with an error and play no audio.
- **FR-024**: When the system shuts down, any currently playing announcement MUST be allowed to complete and all queued announcements MUST be discarded gracefully. Disabling the extension (`multiroom.tts.enabled=false`) is **not** a runtime transition: the flag is evaluated once at context refresh, so a disabled extension contributes no beans for that run and there is never a playing announcement to protect. Shutdown is the only path this requirement governs.
- **FR-025**: If a cache write fails due to insufficient disk space or other I/O error, the TTS extension MUST still play the synthesized audio — written to a temporary file outside the cache directory, deleted after playback — log a warning, and skip caching for that request only.
- **FR-026**: The TTS extension MUST NOT implement its own authentication or authorization mechanism; access control is fully delegated to whatever guards the application's HTTP surface, exactly as it is for every other endpoint. (Before `019-extension-shared-classloader` this read "the host extension"; with one application there is no host to delegate to, only the shared surface.) It MUST NOT introduce a TTS-specific auth layer.
- **FR-028**: A configuration fault in the TTS extension — a provider entry missing required credentials, a configured binary or directory path that does not exist, an unparseable configuration block — MUST abort application start-up with a report naming this extension, rather than starting in a state where the first announcement fails. This is the operator-fixable half of the platform's failure policy.
- **FR-029**: Unavailability of a TTS provider MUST NOT be treated as an initialisation failure. With every configured provider unreachable, the application MUST start normally and the extension MUST load; individual announcement requests then fail with a clear error (FR-011) and succeed again once a provider is reachable, with no restart. No provider may be contacted during start-up.
- **FR-027**: The TTS extension MUST emit structured log events for: request received, cache hit, cache miss, synthesis started, synthesis completed, synthesis error, playback started, and playback completed.
- **FR-030**: An announcement request MAY override the voice and the language independently of the provider. When either is omitted, the chosen provider's configured default for that setting applies. Both participate in the cache key (FR-015), so the same text at a different voice or language is a cache miss.
- **FR-031**: Audio format conversion MUST be performed by the system's shared converter, `multiroom.api.conversion.FormatConverter`, obtained by dependency injection. (It was promoted from `multiroom-core` into `multiroom-api` upstream and released in 0.1.18; that work is done and is not part of this repository's scope.) No extension may implement its own resampling, channel-mixing or bit-depth conversion. The promotion MUST be behaviour-preserving for existing callers.
- **FR-032**: Every rejected or failed announcement request MUST produce a **synchronous** response carrying a machine-readable error code alongside a human-readable message. This is what FR-009's ordering of work exists to preserve: because synthesis and conversion precede the acknowledgement, a provider timeout, a rate limit or a refused conversion is still reportable to the caller rather than discovered after a `202`. The response takes the shape in the single shape published in `contracts/tts-rest-api.yaml`. Caller-fixable faults (empty or over-long text, unknown target, unknown provider) MUST return HTTP 400; provider-side faults (timeout, rate limit, provider error, **and audio the provider returned in a format the shared converter refuses**) MUST return HTTP 503. A refused conversion is provider-side rather than caller-fixable because the caller chose neither the source format nor the target one: rewording the request cannot change the outcome. Every code in the published enum MUST have exactly one status. The mapping from failure to code and status MUST live in one place, so that no endpoint can drift from the published contract.

### Key Entities

- **TTS Request**: A request to synthesize and play a specific text. Attributes: text content, target name (an output or an output group), target type (`multiroom.api.model.TargetType`: `SINGLE_OUTPUT` | `OUTPUT_GROUP`), optional provider name override, optional voice/language override.
- **TTS Provider**: A configured speech synthesis backend, identified by a unique user-defined name. Attributes: provider name, provider type (cloud/local), connection details, credentials, default voice settings.
- **TTS Provider Configuration**: Configuration block defining all named providers and which is the default. Stored in the system configuration file.
- **Audio Cache**: A persistent store of previously synthesized audio files, keyed by (text + provider type + model/engine name + voice/language settings + target audio format). Supports lookup, insertion, eviction (LRU), and manual invalidation.
- **Announcement Queue**: A per-target in-memory ordered queue of pending TTS requests waiting to be played. Not persisted — all entries are dropped on system restart.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A TTS announcement begins playing on the target output(s) within 5 seconds of receiving the request (assuming provider responds within 3 seconds).
- **SC-002**: Announcements play on all outputs in an output group with no more than 100ms of timing difference between rooms (synchronized playback).
- **SC-003**: With the default provider timeout in force, an unavailable provider produces an error response within 10 seconds (no indefinite hang). A deployment that raises `timeout-seconds` above 10 for a slow local provider trades this bound away deliberately, and the system warns about it at start-up (FR-022).
- **SC-004**: Switching TTS providers requires only a configuration change and system restart — no code modifications.
- **SC-005**: 100% of queued announcements are played in submission order when multiple requests arrive for the same target.
- **SC-006**: The target output returns to its prior state within 1 second of the route-destroyed event that signals the announcement has finished. The clock starts at that event rather than at the last audible sample because the event is the only completion signal an extension can observe (FR-008); the pipeline's own drain — which waits for the outputs to empty plus a driver-buffer settle — precedes it and lies outside this budget.
- **SC-007**: For a cache hit, the announcement begins playing within 1 second of receiving the request (no provider round-trip).
- **SC-008**: Cache hit/miss behavior is transparent to the caller — the same audio plays regardless of whether it came from cache or fresh synthesis.
