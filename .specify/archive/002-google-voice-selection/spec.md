# Feature Specification: Google Cloud Voice Selection

**Feature Branch**: `002-google-voice-selection`

**Created**: 2026-09-24

**Status**: Implemented

**Depends on**: `001-tts-extension` (completed). This feature changes only how the `google-cloud`
provider type chooses a voice and which synthesis parameters it passes on. Routing, queueing,
playback and the cache mechanism are unchanged.

**Input**: User description: "new feature based on @docs/future/google-cloud-voice-selection.md and
@docs/future/google-cloud-gemini-tts-params.md"

## Overview

A Google Cloud voice is named in three parts: language, engine and voice (for example
`uk-UA-Chirp3-HD-Charon`). Today the extension treats the name as an opaque string and defaults the
language to `en-US`. That default contradicts most non-English voice names, and Google then rejects
the request. The extension also discards Google's explanation, so the caller sees only
`HTTP 400`. On 2026-09-24 a Ukrainian announcement failed this way, and it took many attempts to
find that `--language uk-UA` was the missing piece.

This feature does four things:

1. It makes Google's own error message visible.
2. It works out the language from the voice.
3. It lets operators and callers name a voice by its parts (engine, language, short voice), and it
   checks that the voice exists before contacting the synthesis service.
4. It passes pitch and speaking rate on to the synthesis service.

**Not in this feature: Gemini TTS voices.** Gemini voices (model, style prompt) turned out to need
a different Google endpoint, a different credential and a different response format. They will be
a separate provider implementation, feature `003`. See
[google-cloud-gemini-tts-params.md](../../../docs/future/google-cloud-gemini-tts-params.md).

## Clarifications

### Session 2026-09-24

- Q: Should a short voice given without an engine be searched across engines, or rejected? → A:
  Neither case can happen. Every `google-cloud` provider entry has a **default engine**. A request
  that gives a voice but no engine looks that voice up under the entry's default engine. The
  default engine comes from the entry's `engine` setting, which is required, **unless** the entry's
  configured voice is a full name, in which case the engine is taken from that name. The exception
  keeps existing entries such as production's `voice: en-US-Neural2-C` starting without edits
  (FR-008, SC-004).
- Q: Which settings may a request override? → A: Engine, pitch and speaking rate, in addition to
  the existing voice and language. Every override takes part in the cache key. (The answer
  originally also covered the Gemini style prompt. That override moves to feature 003 with Gemini.)
- Q: Should a per-entry setting choose the service's API version (`v1` or `v1beta1`)? → A: No.
  Everything uses the stable `v1`. A later feature that needs a beta-only capability can add the
  setting then (FR-016).
- Q: How should Gemini TTS voices be handled? → A: Not in this feature. Live tests showed that
  Gemini cannot be reached through the Text-to-Speech endpoint with any API key (OAuth only). It
  can be reached through Google's Agent Platform endpoint with an API key bound to a service
  account, but that endpoint takes a different request and returns a different audio format.
  Gemini therefore becomes a separate provider implementation, feature `003`. Pitch and speaking
  rate stay here, because classic voices accept them with the existing key.
- Q: If a request sends engine, pitch or speaking rate to a provider that is not `google-cloud`,
  should it be rejected or ignored? → A: Rejected as a caller error (HTTP 400) that names the field
  and the provider type. Nothing is synthesized (FR-020).
- Q: After a failed catalogue fetch, should the next request retry at once or back off? → A: Back
  off. The failure is remembered for a short configurable period (default 60 seconds). During it,
  the existence check is skipped without contacting the catalogue (FR-013).
- Q: How are full voice names whose engine is not recognized (`en-US-Polyglot-1`, `en-US-News-K`)
  treated? → A: They are checked for exact existence in the catalogue and synthesized if found.
  They are listed with the engine segment as it appears in the name, and the list can be filtered by
  it. Short names are never composed for them (FR-006, FR-018).
- Q: How is capitalization fixed for voices written in the wrong case, including when the catalogue
  is unreachable? → A: Full and short names both match regardless of case. The catalogue's spelling
  is used when the catalogue is available. Otherwise the name is fixed by rule: a canonical
  language-region tag (FR-004 lists the accepted forms), the canonical engine spelling (or the segment as given if the engine is unrecognized), and a voice
  with its first letter capitalized (FR-004, FR-017).
- Q: Do several `google-cloud` provider entries share one voice catalogue? → A: No. Each provider
  entry has its own catalogue, expiry and back-off, never shared. This keeps the design independent
  of how an entry authenticates, because OAuth may be supported later (FR-011).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A Google Failure Explains Itself, and a Full Voice Name Just Works (Priority: P1)

A caller asks for an announcement with a full Google voice name, such as `uk-UA-Chirp3-HD-Charon`,
and gives no language. The announcement plays, because the language comes from the voice name. When
Google still rejects a request for any reason, the error the caller receives includes Google's own
explanation, not just a status code.

**Why this priority**: This reproduces the real incident. On its own, this story turns the failure
either into a success or into an error the caller can act on. It is the smallest change that
removes the trial and error.

**Independent Test**: Send a Ukrainian announcement with only the full voice name and confirm that
it plays. Then make the synthesis service reject a request (for example, a voice that does not
exist) and confirm that the error response contains the reason the service gave.

**Acceptance Scenarios**:

1. **Given** a `google-cloud` provider with no language configured, **When** a request names the
   voice `uk-UA-Chirp3-HD-Charon` and no language, **Then** the announcement is synthesized in
   `uk-UA` and plays.
2. **Given** a `google-cloud` provider whose configured language is `en-US`, **When** a request
   names the voice `uk-UA-Chirp3-HD-Charon` and no language, **Then** the language comes from the
   voice (`uk-UA`), not from the provider's configured language.
3. **Given** a request that names the voice `uk-UA-Chirp3-HD-Charon` and explicitly gives the
   language `en-US`, **When** the request is processed, **Then** it is rejected as a caller error,
   the message names both languages, and the synthesis service is not contacted.
4. **Given** the synthesis service rejects a request with an error body that explains the cause,
   **When** the caller receives the failure, **Then** the error message contains that explanation.
5. **Given** the synthesis service fails with a body that cannot be interpreted, **When** the caller
   receives the failure, **Then** the error message still states the service's status code, and
   nothing else is lost.

---

### User Story 2 - Choose a Voice by Engine, Language and Short Name (Priority: P1)

An operator configures a Google provider with `engine: chirp3-hd`, `language: uk-UA` and
`voice: charon`, and does not have to write out the full name. A caller can then override just the
short voice (`--voice puck`), the engine or the language, and the rest comes from configuration.
Capitalization does not matter (`charon`, `Charon` and `CHARON` all work), and full names copied
from Google's documentation keep working.

**Why this priority**: This is how people think about Google voices, and it is what an operator
reaches for once the engine is chosen. Existing configurations that use full names must not break.

**Independent Test**: Configure a provider with separate engine, language and short voice. Send a
request with no voice override and one with a short-voice override. Confirm that each plays with the
expected full voice. Then confirm that a configuration using a full voice name behaves exactly as it
did before.

**Acceptance Scenarios**:

1. **Given** a provider configured with engine `chirp3-hd`, language `uk-UA` and voice `charon`,
   **When** a request gives no voice, **Then** the announcement uses the voice
   `uk-UA-Chirp3-HD-Charon`.
2. **Given** the same provider, **When** a request gives voice `Puck`, **Then** the announcement
   uses `uk-UA-Chirp3-HD-Puck`.
3. **Given** a provider configured with engine `wavenet` and language `en-US`, **When** a request
   gives voice `d`, **Then** the announcement uses `en-US-Wavenet-D`, in Google's own
   capitalization.
4. **Given** one request that gives short voice `charon` with engine `chirp3-hd` and language
   `uk-UA`, and another that gives the full name `uk-UA-Chirp3-HD-Charon`, **When** both are
   requested for the same text, **Then** the second is served from the cache that the first
   filled.
5. **Given** a configuration from before this feature with a full voice name and no engine,
   **When** the extension starts and announces, **Then** it behaves as it did before this feature,
   and the entry's default engine is the one in its voice name.
6. **Given** a provider entry with a short voice (or no voice) and no engine, **When** the
   extension starts, **Then** start-up is aborted with a message that names the extension and the
   entry and says an engine is required.
7. **Given** a provider whose default engine is `chirp3-hd` and language is `en-US`, **When** a
   request gives engine `neural2` and voice `c`, **Then** the announcement uses
   `en-US-Neural2-C`.
8. **Given** a provider configured only with the full voice `uk-UA-Chirp3-HD-Charon` (no engine,
   no language), **When** a request gives voice `puck`, **Then** the announcement uses
   `uk-UA-Chirp3-HD-Puck`: the entry's default engine and default language both come from its
   configured voice.

---

### User Story 3 - A Wrong Voice Is Caught Early and the Error Lists the Alternatives (Priority: P2)

A caller mistypes a voice (`charn`). The extension does not pass the mistake on to the synthesis
service. It rejects the request as a caller error and lists the voices that exist for that engine
and language, for example: `Voice 'charn' is not available for Chirp3-HD / uk-UA. Available:
Achernar, Achird, Algenib, …, Charon, …`.

**Why this priority**: This turns a guessing game into one corrected retry. It depends on Story 2's
voice composition, and it is useful but not essential for announcements to work.

**Independent Test**: With the voice catalogue reachable, request a voice that does not exist.
Confirm that the error is a caller error, carries the dedicated invalid-voice code, and lists the
valid voices. Repeat with the catalogue unreachable and confirm that synthesis is still attempted.

**Acceptance Scenarios**:

1. **Given** the voice catalogue is reachable, **When** a request names a voice that does not
   exist for the resolved engine and language, **Then** the request is rejected as a caller error
   with the invalid-voice code, the message lists the available voices for that engine and
   language, and no synthesis is attempted.
2. **Given** the voice catalogue cannot be reached, **When** a request names any voice, **Then**
   the existence check is skipped, synthesis is attempted, and any rejection carries the synthesis
   service's explanation (Story 1).
3. **Given** the catalogue was fetched recently, **When** further requests arrive, **Then** they
   are checked against the remembered catalogue without contacting the service again.
4. **Given** a provider whose default engine is `chirp3-hd`, **When** a request gives voice `D`
   and no engine, **Then** `D` is looked up under Chirp3-HD only. The request is rejected with the
   invalid-voice code and a list of the Chirp3-HD voices. Other engines are not searched.
5. **Given** the same provider, **When** a request gives voice `D` and engine `wavenet`, **Then**
   the voice resolves to `{language}-Wavenet-D` and is checked under Wavenet.

---

### User Story 4 - Adjust Pitch and Speaking Rate (Priority: P3)

An operator sets a speaking rate (for example `1.1`) and a pitch on a Google provider entry, so
that announcements come out at the pace and tone they want. A caller can override either one for a
single announcement, for example a slower rate for a long message.

**Why this priority**: Today these settings are accepted in configuration and then ignored, which
is a quiet defect. Fixing it is a small addition, and announcements work without it.

**Independent Test**: Configure an entry with a speaking rate and a pitch, make an announcement,
and confirm that both reach the synthesis service. Then override one of them in a request and
confirm that the override is used and that the result is cached separately.

**Acceptance Scenarios**:

1. **Given** a provider entry with a speaking rate of `1.2` and a pitch of `-2`, **When** an
   announcement is made, **Then** both values are passed to the synthesis service.
2. **Given** a provider entry with neither setting, **When** an announcement is made, **Then**
   neither value is sent, and the service's defaults apply.
3. **Given** a provider entry with a speaking rate of `1.2`, **When** a request gives a speaking
   rate of `0.9`, **Then** `0.9` is used for that announcement only.
4. **Given** two announcements with the same text and voice but a different pitch or speaking rate,
   **When** both are requested, **Then** the second is a cache miss, because it would sound
   different.
5. **Given** any Google entry, **When** a request gives a pitch or speaking rate outside the range
   the synthesis service accepts, **Then** the request is rejected as a caller error that states
   the range.

---

### User Story 5 - List the Voices a Provider Offers (Priority: P3)

A person setting up an announcement asks which voices a Google provider offers. They can filter by
language and engine (for example `uk-UA`, `chirp3-hd`). The answer comes from the same catalogue
that validates voices, so what is listed is what will be accepted.

**Why this priority**: This makes Stories 2 and 3 self-service, because nobody has to read Google's
documentation to find a name. A command-line client can build on it. Announcements work without it.

**Independent Test**: Ask for the voices of a Google provider filtered by language and engine.
Confirm that the list matches what the synthesis service publishes for that filter, and that each
entry gives both the short name and the full name.

**Acceptance Scenarios**:

1. **Given** a `google-cloud` provider named `google`, **When** its voices are requested for
   language `uk-UA` and engine `chirp3-hd`, **Then** the response lists each matching voice's
   short name, full name, engine and language.
2. **Given** a provider that is not of type `google-cloud`, **When** its voices are requested,
   **Then** the response says that voice listing is not supported for that provider type.
3. **Given** the voice catalogue cannot be reached, **When** voices are requested, **Then** the
   response is a provider-side error that says the catalogue is unavailable.
4. **Given** an unknown provider name, **When** its voices are requested, **Then** the response is
   the existing unknown-provider error.

---

### Edge Cases

- **The request language contradicts the full voice name** (`en-US` with `uk-UA-…`): the request
  is rejected as a caller error before any synthesis call, and the message names both languages.
  Languages are compared without regard to case, so `uk-ua` and `uk-UA` are the same language.
- **A full voice name differs from the configured default engine** (default `wavenet`, voice
  `uk-UA-Chirp3-HD-Charon`): the full name wins, because it is complete and unambiguous. The
  default engine applies only to short names.
- **A request gives both an engine and a full voice name that disagree** (engine `wavenet`, voice
  `uk-UA-Chirp3-HD-Charon`): the request is rejected as a caller error that names both engines. The
  caller stated two contradicting things, just as with a conflicting language (FR-003).
- **A short voice with no language anywhere**: in configuration this is a configuration fault at
  start-up. In a request whose provider has no default language either, it is a caller error. The
  `en-US` fallback applies only when no voice is given at all.
- **A configured full voice and a configured `language` that disagree** (voice
  `uk-UA-Chirp3-HD-Charon`, language `en-US`): the configured voice is synthesized in its own
  language, and start-up logs a warning. The configured `language` stays the default for short
  names, just as a configured `engine` does (FR-007), so a request for voice `puck` resolves to
  `en-US-Chirp3-HD-Puck`.
- **The voice catalogue changes between fetches** (Google adds a voice): the new voice is
  recognized once the remembered catalogue expires. Until then, a voice that the remembered
  catalogue lacks is looked up once more against a fresh catalogue before the request is rejected,
  provided the remembered copy is older than the failure back-off (default 60 seconds, FR-013). A
  newly published voice is therefore rejected only if the local copy is less than a back-off
  period old. This floor stops a caller from forcing a full catalogue download with every
  mistyped voice (FR-011).
- **The catalogue responds slowly or times out**: this is treated as unreachable (Story 3,
  scenario 2), and the lookup counts against the same synthesis time budget as the rest of the
  request (001 SC-003). The failure starts the back-off (FR-013), so the next requests do not
  wait for the catalogue again until it ends.
- **A malformed engine, language or voice in configuration** (an unknown engine name, a language
  that is not a language-region tag such as `uk-UA`, `cmn-CN` or `es-419`, a voice with invalid characters): the extension aborts start-up
  with a message that names it (001 FR-028). Only the format is checked at start-up. Whether a voice
  exists is never checked at start-up.
- **Pitch or speaking rate out of range in configuration**: start-up is aborted with a
  configuration fault that states the allowed range.
- **A parameter that a voice's engine does not support** (for example pitch on `Chirp3-HD`, for
  which Google documents speaking-rate control but no pitch control): the value is passed on, and
  any rejection surfaces with the synthesis service's explanation (Story 1). The extension does not
  keep a per-engine support table, because Google changes it without notice.
- **A full name from an engine the extension does not know** (`en-US-Polyglot-1`): it is checked
  for exact existence and synthesized if found. It appears in the voice list under its own engine
  segment. A short name cannot be composed for it (FR-006).
- **Google-only overrides sent to another provider type** (engine, pitch or speaking rate for an
  OpenAI, Piper or local HTTP provider): the request is rejected as a caller error naming the field
  and the provider type, and nothing is synthesized (FR-020).
- **Existing cache entries after upgrade**: entries written before this feature may be keyed
  differently, so the first request after the upgrade may re-synthesize them once. Nothing is
  served incorrectly.

## Requirements *(mandatory)*

### Functional Requirements

**Error visibility (Story 1)**

- **FR-001**: When the Google synthesis service returns an error, the error message the extension
  reports MUST include the explanation from the service's error body. If the body cannot be
  interpreted, the message MUST still state the status code. The error code and the HTTP status
  returned to the caller are unchanged by this requirement.

**Voice and language resolution (Stories 1–2)**

- **FR-002**: For `google-cloud` providers, the language MUST be resolved in this order: (1) the
  request's language; (2) the language prefix of the resolved voice, if it is a full name; (3) the
  provider entry's **default language** (FR-007); (4) `en-US`, used only when no voice is set
  anywhere. The
  `google-cloud` provider type MUST NOT have an implicit `en-US` default that overrides a voice's
  own language.
- **FR-003**: A request language that contradicts the language prefix of a full voice name MUST be
  rejected as a caller error (HTTP 400) before any synthesis call. The comparison MUST ignore case.
- **FR-004**: A `google-cloud` voice MUST be accepted either as a **full name**
  (`{language}-{engine}-{voice}`, for example `uk-UA-Chirp3-HD-Charon`) or as a **short name**
  (`Charon`, `D`). A full name MUST be used as given apart from capitalization. Its language and
  engine come from the name itself, and any configured engine does not apply. Full and short names
  MUST both match regardless of case, and the resolved name MUST be in canonical capitalization:
  the catalogue's spelling when the catalogue is available, and otherwise by rule (a language-region
  tag with a lower-case language and an upper-case region, such as `uk-UA`, `cmn-CN` or `es-419`;
  the canonical engine spelling or the segment as given for an unrecognized engine, and a
  voice with its first letter capitalized). `uk-ua-chirp3-hd-charon` therefore resolves to
  `uk-UA-Chirp3-HD-Charon`.
- **FR-005**: A short name MUST be combined with the resolved engine and language into a full name.
  The engine is the request's engine if it gives one, otherwise the provider entry's default engine
  (FR-007). Other engines are never searched. Matching MUST ignore case, and the composed name MUST
  use the synthesis service's own capitalization (`Wavenet`, `Chirp3-HD`, `Charon`).
- **FR-006**: The supported engines MUST include at least Standard, Wavenet, Neural2, Studio,
  Chirp-HD and Chirp3-HD. Each is recognized under the names people commonly write for it
  (`wavenet` and `WaveNet`, `chirp3-hd` and `Chirp3-HD`). A full name whose engine segment is not
  recognized (for example `en-US-Polyglot-1` or `en-US-News-K`) MUST still be accepted, taking the
  language from its prefix. It is checked for exact existence in the catalogue (FR-011) and
  synthesized if found. Short names are never composed for an unrecognized engine, and a request
  `engine` override that is not recognized stays a caller error (FR-009).
- **FR-007**: Every `google-cloud` provider entry MUST have a **default engine**, taken from its
  `engine` setting or, if that is absent, from its configured voice when that voice is a full name.
  An entry that has neither MUST abort start-up with a message naming the extension and the entry
  (001 FR-028). If both are present and disagree, the `engine` setting is the default for short
  names, and the configured full voice is used as given. The `engine` setting MUST NOT be accepted
  and then silently ignored. In the same way, every entry has a **default language** for short
  names: its `language` setting or, if that is absent, the language prefix of its configured full
  voice. An entry with a short voice and neither MUST abort start-up.
- **FR-008**: Configurations written before this feature, with a full voice name and optionally a
  language, MUST keep working without edits.
- **FR-009**: A request MAY override the engine, pitch and speaking rate, in addition to the
  existing voice and language overrides (001 FR-030). Each omitted override falls back to the
  provider entry's configured value. These overrides MUST be rejected as caller errors (HTTP 400):
  an engine that is not recognized, an engine that contradicts a full voice name in the same
  request, and a pitch or speaking rate out of range. The published HTTP contract MUST be extended
  with the new optional fields.

**Voice existence check (Story 3)**

- **FR-010**: The extension MUST NOT fetch the voice catalogue, or contact the synthesis service in
  any other way, while the application is starting (001 FR-029). At start-up it MUST check only the
  format of `engine`, `language` and `voice`, and a malformed value MUST abort start-up with a
  message that names the extension (001 FR-028).
- **FR-011**: When a `google-cloud` announcement is requested, the extension MUST check the resolved
  full voice name against the voice catalogue. It MUST remember the catalogue for a configurable
  period (default 24 hours), fetch it only when needed, and fetch it again at most once for a voice
  that the remembered catalogue lacks before rejecting the request. That refetch MUST happen only
  when the remembered catalogue is older than the failure back-off (FR-013), so that repeated
  requests for a non-existent voice cannot force a catalogue download each time. Each provider entry MUST keep
  its own catalogue, with its own expiry and failure back-off (FR-013), fetched with that entry's
  own credentials. Catalogues are never shared between entries, even entries with the same key.
- **FR-012**: A voice that is not in the catalogue MUST be rejected as a caller error (HTTP 400)
  with a new, dedicated **invalid-voice** error code, added to the published error codes. The
  message MUST name the requested voice, the engine and language it was resolved against, and the
  voices available for that engine and language.
- **FR-013**: If the voice catalogue cannot be reached, the existence check MUST be skipped and
  synthesis attempted as normal. Being unable to fetch the catalogue MUST NOT, on its own, cause an
  announcement to fail. A failed fetch MUST be remembered for a configurable back-off (default
  60 seconds). During the back-off, the check MUST be skipped without contacting the catalogue, so
  an outage costs at most one slow request per back-off period. Voice listing (FR-019) during the
  back-off returns the unavailable-catalogue error without a fetch.

**Audio parameters (Story 4)**

- **FR-014**: A `google-cloud` provider entry MAY set pitch and speaking rate, and a request MAY
  override them (FR-009). Each MUST be sent whenever it is set and MUST be omitted when it is not.
  The existing provider-specific parameters setting MUST either take effect or be rejected. It
  MUST NOT be accepted and ignored.
- **FR-015**: Pitch or speaking rate in configuration outside the range the synthesis service
  accepts MUST abort start-up with a message naming the extension, the provider entry and the
  allowed range.
- **FR-016**: Every synthesis and voice-catalogue request MUST use the synthesis service's stable
  `v1` interface. There is no per-entry interface-version setting.

**Cache identity (Stories 2 and 4)**

- **FR-017**: For `google-cloud` announcements, the cache key MUST be computed from the resolved
  full voice name (not from how it was spelled), the resolved language, pitch and speaking rate
  (after request overrides have been applied), in addition to the existing components (text,
  provider and audio format, 001 FR-015). Two requests that resolve to the same audio MUST share one
  entry, and two that would sound different MUST NOT.

**Voice listing (Story 5)**

- **FR-018**: The extension MUST provide an HTTP query, under its own paths, that lists the voices
  a named provider offers. The list can be filtered by language and by engine, and each entry gives
  the short name, full name, engine and language. The query MUST use the same remembered catalogue
  as FR-011. Voices of unrecognized engines MUST be listed too, with the engine segment as it
  appears in the name, and the engine filter MUST match that segment (ignoring case). This keeps
  the list and the existence check in agreement.
- **FR-019**: For a provider whose type does not support voice listing, the query MUST return a
  clear caller error. For an unreachable catalogue it MUST return a provider-side error (HTTP 503).

**Scope boundaries**

- **FR-020**: Voice composition, the existence check, and pitch and speaking rate apply only to the
  `google-cloud` provider type. For OpenAI, Piper and local HTTP providers, the meaning of voice
  and language MUST NOT change, and neither MUST their language default. Gemini TTS voices are
  out of scope (feature 003). A request that gives engine, pitch or speaking rate for a provider
  that is not `google-cloud` MUST be rejected as a caller error (HTTP 400) naming the field and the
  provider type, before any synthesis.
- **FR-021**: Every new error condition MUST map to exactly one error code and HTTP status in the
  published contract, and the mapping MUST live in the same single place as the existing ones
  (001 FR-032).

### Key Entities

- **Voice Reference**: What a caller or operator writes to choose a voice. It is either a full name
  (language, engine and voice in one string) or a short name that is completed from the resolved
  engine and language.
- **Resolved Voice**: The result of resolution: the canonical full name in the service's own
  capitalization, the language and the engine. This is what is sent to the service and what the
  cache key uses.
- **Engine**: A family of Google voices (Standard, Wavenet, Neural2, Studio, Chirp-HD, Chirp3-HD).
  It has a canonical spelling and accepted aliases. Each provider entry has a default engine, and a
  request may override it.
- **Audio Adjustments**: Pitch and speaking rate. They have defaults in the entry and can be
  overridden per request.
- **Voice Catalogue**: The list of voices the synthesis service currently publishes. It is fetched
  on demand, remembered for a limited time, and used both to validate voices and to list them. There
  is one per `google-cloud` provider entry, with its own expiry and failure back-off.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The announcement from the 2026-09-24 incident (a Ukrainian text with voice
  `uk-UA-Chirp3-HD-Charon` and no language) plays on the first attempt.
- **SC-002**: 100% of failures reported by the Google synthesis service reach the caller with the
  service's own explanation whenever the service provided one.
- **SC-003**: A caller who mistypes a voice can correct it from the error message alone, without
  consulting external documentation. The error lists every valid voice for the resolved engine and
  language.
- **SC-004**: Every existing `google-cloud` configuration in production starts and announces
  correctly after the upgrade with zero configuration edits.
- **SC-005**: Application start-up makes zero calls to the synthesis service or its voice catalogue.
  The only start-up work this feature adds is format validation of configuration values.
- **SC-006**: With the catalogue remembered, validating a voice adds no noticeable delay to an
  announcement. On a cache hit, an announcement still starts playing within 1 second (001 SC-007).
- **SC-007**: When the voice catalogue is unreachable, announcements that would otherwise succeed
  still succeed. The catalogue is never a new single point of failure.
- **SC-008**: Every pitch or speaking-rate setting that the configuration accepts is sent to the
  synthesis service. No setting is accepted and then ignored by the extension. On an engine that
  supports the parameter (for example Neural2), the change is audible.

## Assumptions

- **Part A of the voice-selection note (error body and language from a full voice name) is part of
  this feature** as Story 1. It is not shipped separately beforehand. If it ships first, Story 1 is
  already done.
- The valid ranges for pitch and speaking rate are confirmed against Google's documentation during
  planning. The spec requires only that they be configurable and validated.
- The set of engines is fixed in the extension. A future Google engine needs an extension release
  before it can be used through short names. Its full names keep working in the meantime, because
  a full name is used as given (FR-004).
- The catalogue's default memory period of 24 hours is a reasonable balance for how often Google
  publishes voices. It is configurable, and FR-011's single refetch covers voices published within
  that window.
- Warming the catalogue in the background after start-up is permitted, because it is not bean
  construction, but it is not required. The default is to fetch on first need.
- The invalid-voice error is a caller error (HTTP 400), consistent with 001 FR-032: the caller
  chose the voice and can fix it. This also holds when the voice comes from the entry's
  configuration and Google does not publish it. Existence is never checked at start-up (FR-010),
  so this is the first point at which it can be detected, and a caller can still proceed by naming
  a valid voice. The message names the voice, so the operator can correct the configuration.
- The command-line client that calls these endpoints (`sonora voices`, `sonora speak --voice`) is
  outside this repository. This feature provides the HTTP surface it needs.
- Existing cache entries may miss once after the upgrade because of the new key composition. This
  is acceptable and needs no migration.
