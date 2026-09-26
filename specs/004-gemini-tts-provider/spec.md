# Feature Specification: Gemini TTS Provider

**Feature Branch**: `004-gemini-tts-provider`

**Created**: 2026-09-25

**Status**: Draft

**Depends on**: `002-google-voice-selection` and `003-google-service-account-auth` (both
completed). This feature adds a provider type. The existing `google-cloud` type, routing,
queueing, playback and the cache format are unchanged.

**Input**: User description: "develop Gemini TTS provider @docs/future/google-cloud-gemini-tts-params.md"

## Overview

Google offers a newer family of voices, **Gemini-TTS**, alongside the classic voices the
`google-cloud` provider already speaks with. A Gemini voice is chosen by a **model** (for example
`gemini-2.5-flash-tts`) and a **voice** (`Kore`, `Achird`, …), and it can be steered with a
natural-language **style prompt** ("Read aloud in a warm, welcoming tone"). That prompt is the
reason to use it: a doorbell, a reminder and an alarm can each sound the way they should.

Live tests on 2026-09-24 showed that Google's Text-to-Speech service accepts Gemini requests, but
only from a caller authenticated as a service account, never with an API key. Feature 003 added
exactly that credential. This feature uses it.

Operators get a new provider type, **`google-gemini`**. An entry of this type names a service
account key file, a Gemini model, a default voice, a default language and, optionally, a default
style prompt and a speaking rate. A caller can override the voice, the language, the style prompt
and the speaking rate per announcement.

Gemini-TTS is billed per token and **has no free tier**. It is never chosen for an operator; they
configure it deliberately.

A caller can also list the Gemini voices a `google-gemini` entry can speak with. Google publishes
them in the same voice list as classic voices, which the extension already reads for
`google-cloud`.

## Clarifications

### Session 2026-09-25

- Q: Which Google route does the Gemini provider use: the Text-to-Speech endpoint with a service
  account token (003), or Agent Platform's `generateContent` with an API key bound to a service
  account? → A: The Text-to-Speech endpoint with a service account token. It returns the same audio
  format as classic voices, keeps the style prompt separate from the text, and reuses 003's
  credential. The Agent Platform route and bound API keys are out of scope.
- Q: Is Gemini an engine of the existing `google-cloud` type (with a service account enforced when
  the engine is Gemini), or a separate provider type? → A: A separate type, `google-gemini`. Google
  plumbing that works the same for both (service-account sign-in, sending a request, passing on
  Google's error message, reading the audio) is shared rather than duplicated. Reasons: 002's voice
  composition, voice catalogue check and voice listing do not apply to Gemini voices. A per-request
  engine override would let a caller silently switch a free-tier entry to a paid model, because
  Chirp3-HD and Gemini share voice names (`Kore`, `Charon`). And a merged type would make almost
  every configuration rule depend on the engine.
- Q: Does a Gemini entry accept pitch and speaking rate? → A: Speaking rate yes, pitch no. A live
  test on 2026-09-25 (`gemini-2.5-flash-tts`, voice `Kore`) showed that Google honours speaking rate:
  `2.0` gave 0.48× and `0.5` gave 2.10× the baseline duration. It ignores pitch: `-20` semitones
  changed the median voice pitch from 203 Hz to 192 Hz, within normal variation, where the full
  shift would have given about 64 Hz. Speaking rate therefore follows 002's rules for `google-cloud`
  (range, per-request override, cache identity). Pitch is rejected, because a setting Google
  silently ignores must not be accepted.
- Q: How is the capitalization of a Gemini voice name treated? → A: Normalized to a leading capital
  with the rest lowercase (`kore` becomes `Kore`), before it is sent to Google and before it enters
  the cache identity. Every Gemini voice Google lists has that form, and no voice list is used
  to normalize or check an announcement's voice (FR-015). (The list kept for voice listing since
  the 2026-09-26 session does not change this.)
- Q: Must every Gemini entry set a language, or may it be left out for the model to detect? → A:
  Required in configuration, overridable per request (FR-004, FR-011). Leaving it out was
  preferred, but a live test on 2026-09-25 showed that Google refuses a Gemini request without a
  language code (`400 INVALID_ARGUMENT: Empty language code`). Google accepted a Ukrainian text
  with both `uk-UA` and `en-US`.
- Q: When a Gemini entry becomes the default provider, should the extension do more than the setup
  guide's warning? → A: Yes. Start-up logs a warning naming the entry and saying that announcements
  without a provider name are billed per token. It is still allowed (FR-029).
- Q: What synthesis time limit applies to a `google-gemini` entry that does not set one? → A: The
  same default as every other type (10 s), overridable per entry. An operator whose model or texts
  need longer raises it on the entry (FR-030).
- Q: Is leading and trailing whitespace stripped from a style prompt before it is sent and cached?
  → A: Yes, at both ends, for the request's prompt and the entry's default alike. The rest is kept
  and compared exactly: case and inner spacing unchanged (FR-012, FR-017).
- Q: Does this feature guard against unexpected Gemini charges, for example with a monthly spending
  cap? → A: No; out of scope. The per-provider monthly character budget planned in
  `docs/future/google-cloud-free-tier-tracking.md` would cover Gemini entries as well. Until then,
  the start-up warning (FR-029) and the setup guide's billing warning (FR-026) are the protection.

### Session 2026-09-26

- Q: Does Google publish a list of Gemini voices, and should a `google-gemini` entry list its
  voices? → A: Yes, and yes. A live `GET v1/voices` on 2026-09-26, with a service account key,
  returned 2,066 voices, 30 of them Gemini voices with a bare name (`Kore`, `Achird`, …), each
  tagged `en-US` only, with a gender and a 24 kHz natural rate, and none with a model. Google
  rejects a `modelName` filter on that call. The earlier answer "does not support voice listing"
  was based on the wrong belief that no such list existed (FR-023).
- Q: Should an announcement through a `google-gemini` entry also be checked against that list, as
  `google-cloud` checks its voices? → A: No, listing only. The list carries no model, so a check
  could reject a voice a newer model offers, or accept one a model lacks. An unknown voice is
  still Google's refusal, with Google's explanation (FR-015, Story 4).
- Q: How does the listing's `language` filter behave for a `google-gemini` entry, when Google tags
  every Gemini voice `en-US` only although the voices speak many languages? → A: It is checked
  for form, as for `google-cloud`, and does not narrow the list: every Gemini voice is listed for
  any well-formed language (FR-033).
- Q: What does a listed Gemini voice show? → A: Its name (as both the short and the full name),
  the entry's model as its engine, Google's published gender, and no language. Listed
  `google-cloud` voices gain the gender too (FR-032, FR-036).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Announce With a Gemini Voice (Priority: P1)

An operator who already has a service account key file (003) adds a `google-gemini` entry that
names the key file, a Gemini model, a voice and a language. Announcements sent to that entry play
in the Gemini voice. A repeated announcement is served from the cache without calling Google.

**Why this priority**: This is the feature. Without it, Gemini voices cannot be used at all.

**Independent Test**: Configure one `google-gemini` entry, start the host and make an announcement
through it. Confirm that it plays, that Google received the configured model, voice and language
with a bearer token, and that the same announcement repeated is a cache hit.

**Acceptance Scenarios**:

1. **Given** a `google-gemini` entry with a valid key file, model, voice and language, **When** an
   announcement is requested through it, **Then** Google is asked to synthesize the text with that
   model, voice and language, and the announcement plays.
2. **Given** the same text announced twice through the same entry with the same settings, **When**
   the second request arrives, **Then** it plays from the cache and Google is not contacted.
3. **Given** a request that names a different voice or language than the entry's defaults, **When**
   it is processed, **Then** the request's values are used for that announcement, and the result
   is cached separately from the defaults.
4. **Given** a `google-gemini` entry and a `google-cloud` entry that name the same key file,
   **When** announcements go to each, **Then** each works independently. The `google-cloud` entry
   behaves exactly as it did before this feature.
5. **Given** the entry has already obtained an access token, **When** further announcements arrive
   while it is valid, **Then** they reuse it, following 003's rules for renewal, sharing among
   concurrent requests, the single retry after a rejected token, and back-off.

---

### User Story 2 - Steer the Delivery With a Style Prompt (Priority: P1)

The operator sets a default style prompt on the entry ("Say this calmly and warmly"), and a caller
can replace it for one announcement ("Announce this urgently"), or turn it off, without touching
configuration. The same text with a different prompt sounds different and is cached separately.

**Why this priority**: The style prompt is what Gemini offers that classic voices do not. Without
it, the feature is only a costlier way to get a plain voice.

**Independent Test**: With a default prompt configured, announce the same text three times: with
no prompt in the request, with a different prompt, and with an empty prompt. Confirm that Google
received the default prompt, the request's prompt, and no prompt, in that order, and that three
separate cache entries resulted.

**Acceptance Scenarios**:

1. **Given** an entry with a default style prompt, **When** a request gives no prompt, **Then**
   Google receives the entry's default prompt, separately from the text.
2. **Given** an entry with or without a default prompt, **When** a request gives a non-empty
   prompt, **Then** Google receives the request's prompt instead.
3. **Given** an entry with a default prompt, **When** a request gives an empty prompt, **Then**
   Google receives no prompt for that announcement.
4. **Given** an entry with no default prompt, **When** a request gives no prompt, **Then** Google
   receives no prompt.
5. **Given** the same text, voice and language with two different prompts, **When** both are
   announced, **Then** they are two separate cache entries. The same prompt given again is a cache
   hit.
6. **Given** a request whose prompt is longer than the operator's maximum text length, **When** it
   is received, **Then** it is rejected as a caller error before anything is synthesized.

---

### User Story 3 - A Wrong Configuration Is Caught at Start-up (Priority: P1)

An operator sets an API key instead of a key file, forgets the model, or copies a setting that
Gemini voices do not honour, such as `engine` or `pitch`, onto a Gemini entry. The host refuses
to start the extension and says what is wrong with which entry, instead of failing the first
announcement.

**Why this priority**: Configuration faults are operator-fixable and must fail fast (constitution
VIII). An API key in particular can never work for Gemini (live tests, 2026-09-24), so accepting
one would only postpone a certain failure.

**Independent Test**: Start the host with each broken configuration in turn and confirm that
start-up stops with a message naming the extension, the entry and the fault. Confirm that no
network request is made during any of these start-ups, including a successful one.

**Acceptance Scenarios**:

1. **Given** a `google-gemini` entry with an API key, with or without a key file, **When** the host
   starts, **Then** start-up is aborted with a message saying Gemini voices need a service account
   key file and do not accept an API key.
2. **Given** an entry with no key file, **When** the host starts, **Then** start-up is aborted with
   a message saying the key file is required.
3. **Given** a key file that is missing, unreadable or not a valid service account key, **When** the
   host starts, **Then** start-up is aborted exactly as for a `google-cloud` entry (003).
4. **Given** an entry with no model, no voice or no language, **When** the host starts, **Then**
   start-up is aborted with a message naming the missing setting.
5. **Given** an entry whose model, voice or language is malformed, **When** the host starts,
   **Then** start-up is aborted with a message naming the setting and the value.
6. **Given** an entry that sets `engine`, `pitch` or provider-specific extra parameters, **When**
   the host starts, **Then** start-up is aborted with a message naming the setting. It is never
   accepted and then ignored.
7. **Given** an entry whose speaking rate is outside the range 002 allows for `google-cloud`,
   **When** the host starts, **Then** start-up is aborted with a message naming the setting and the
   value.
8. **Given** a default style prompt longer than the maximum text length, **When** the host starts,
   **Then** start-up is aborted with a message naming the entry.
9. **Given** a style prompt set on an entry whose type is not `google-gemini`, **When** the host
   starts, **Then** start-up is aborted.
10. **Given** a valid configuration, **When** the host starts, **Then** start-up makes no request to
    Google.
11. **Given** a `google-gemini` entry that is the default provider, named or by position, **When**
    the host starts, **Then** start-up logs one warning that announcements without a provider name
    are billed per token, and continues.

---

### User Story 4 - Google's Refusals Explain Themselves (Priority: P2)

A service account that works for classic voices lacks the permission Gemini needs, the Agent
Platform API is not enabled in the project, a model name is misspelt, or a voice does not exist.
The announcement fails, and the caller receives Google's own explanation, so the operator can fix
the project from the error message.

**Why this priority**: The first Gemini set-up is expected to fail on exactly these points (live
tests, 2026-09-24). The error message is how the operator finds the missing role or API.
Announcements with a correct set-up work without this story.

**Independent Test**: Make Google refuse a synthesis for a missing permission, a disabled API, an
unknown model and an unknown voice in turn. Confirm that each time the caller receives a
provider-side error that names the entry and contains Google's explanation.

**Acceptance Scenarios**:

1. **Given** Google refuses the synthesis (missing permission, disabled API, unknown model, unknown
   voice, text or prompt too long for the model), **When** the caller receives the failure,
   **Then** the message names the entry and includes Google's explanation.
2. **Given** Google reports a rate limit, **When** the caller receives the failure, **Then** it
   carries the same rate-limit error as for a `google-cloud` entry, with Google's explanation.
3. **Given** the token cannot be obtained or Google cannot be reached, **When** an announcement is
   requested, **Then** it fails exactly as it would for a service-account `google-cloud` entry
   (003), including the back-off.
4. **Given** any of these failures, **When** it is reported or logged, **Then** no private key,
   signed assertion or access token appears in it.

---

### User Story 5 - Callers Cannot Send Gemini-Only or Classic-Only Settings to the Wrong Entry (Priority: P3)

A caller sends a style prompt to a classic or non-Google entry, or sends an engine or pitch to a
Gemini entry. The request is rejected, naming the field and the provider type,
instead of being synthesized without the setting the caller asked for.

**Why this priority**: 002 established that a setting a provider cannot honour is a caller error,
not something silently dropped. This keeps that rule for the new setting and the new type. Correct
requests work without it.

**Independent Test**: Send each mismatched field to each kind of entry and confirm a caller error
naming the field and the type, and that nothing is synthesized.

**Acceptance Scenarios**:

1. **Given** a request with a style prompt, **When** its provider is not `google-gemini`, **Then**
   it is rejected as a caller error naming the field and the provider type.
2. **Given** a request with an engine or pitch, **When** its provider is `google-gemini`, **Then**
   it is rejected as a caller error naming the field and the provider type.
3. **Given** a request with a speaking rate, **When** its provider is `google-gemini`, **Then** it
   is accepted, range-checked and applied exactly as for `google-cloud` (002).
4. **Given** a request for the voice list of a `google-gemini` entry with an `engine` filter,
   **When** it is received, **Then** it is rejected as a caller error naming the filter and the
   provider type.

---

### User Story 6 - List the Gemini Voices (Priority: P3)

An operator or caller choosing a Gemini voice asks the entry which voices it has, instead of
looking them up in Google's documentation. The answer comes from Google's own voice list, so a
voice Google adds appears without a new release.

**Why this priority**: Voice names such as `Achird` or `Sulafat` say nothing about the voice, and
the list shows each one's gender. Announcements work without it, with a voice name taken from the
setup guide.

**Independent Test**: Against a Google stand-in that publishes classic and Gemini voices, list the
voices of a `google-gemini` entry with no filter and with `language=uk-UA`. Confirm that both
answers contain exactly the Gemini voices, with the entry's model and each voice's gender, and
that no voice list was fetched at start-up.

**Acceptance Scenarios**:

1. **Given** a `google-gemini` entry, **When** its voices are listed, **Then** the answer contains
   every Gemini voice in Google's voice list and no classic voice, each with its name, the entry's
   model and its gender, sorted by name.
2. **Given** a `google-gemini` entry, **When** its voices are listed with a well-formed language
   such as `uk-UA`, **Then** the answer is the same full list, because Gemini voices are not tied
   to a language.
3. **Given** a malformed language filter, **When** the voices of a `google-gemini` entry are
   listed, **Then** it is rejected as a caller error, as for `google-cloud`.
4. **Given** a listing was answered moments ago, **When** the voices are listed again, **Then**
   Google is not contacted: the list is remembered for as long as `google-cloud`'s is.
5. **Given** Google's voice list cannot be fetched, **When** the voices are listed, **Then** the
   caller receives the same "catalogue unavailable" error as for `google-cloud`, and repeated
   requests are held off by the same back-off.
6. **Given** a `google-gemini` entry has listed its voices, **When** an announcement names a voice
   the list lacks, **Then** it is still sent to Google, and Google's refusal, if any, reaches the
   caller (Story 4).
7. **Given** a `google-cloud` entry, **When** its voices are listed, **Then** it lists exactly the
   voices it listed before this feature, filtered and sorted as before, each now also showing its
   gender.

---

### Edge Cases

- **Classic and Gemini voices share names** (`Kore`, `Charon`, …): a voice name means a Gemini voice
  only on a `google-gemini` entry. Nothing on a `google-cloud` entry can switch it to Gemini.
- **A voice that does not exist for the model**: it is not checked locally. Google's voice list is
  used for listing only, because it names no model. Google's refusal and explanation reach the
  caller (Story 4).
- **A listed voice that the entry's model lacks**: possible, because Google's list is not per
  model. Announcing with it fails with Google's explanation.
- **Google lists a new Gemini voice**: it appears in the listing once the remembered list expires,
  with no release of the extension. A bare name that is not a Gemini voice's form is not listed.
- **A voice spelled in different capitalizations** (`kore`, `KORE`, `Kore`): all are normalized
  to `Kore`, so Google receives one spelling and they share one cache entry.
- **A preview model** (for example `gemini-3.1-flash-tts-preview`): accepted like any other model
  name. If Google retires it, announcements fail with Google's explanation until the operator
  changes the configuration.
- **A language the model does not support**: passed on. Google's refusal reaches the caller.
- **A text that already contains Gemini's inline markup** (for example `[whispering]`): sent as
  part of the text, unchanged. It is cached as ordinary text.
- **Google returns audio at a sample rate other than the one requested**: the announcement still
  plays at the correct speed and pitch on the target output.
- **The text fits the extension's maximum length but Google finds text plus prompt too long for the
  model**: Google's refusal reaches the caller (Story 4). The extension does not measure Google's
  per-model limits itself.
- **A `google-gemini` entry is the default provider** (named explicitly, or the first enabled
  entry):
  allowed. Every announcement without a provider name then costs Gemini tokens, so start-up logs a
  warning saying so (FR-029), and the setup guide warns about it too.
- **A prompt of only spaces in a request**: treated as an empty prompt (no prompt).
- **A prompt with stray spaces or a trailing line break** (for example from a YAML block): trimmed
  at both ends, so it shares a cache entry with the same prompt written without them.
- **Existing `google-cloud` entries after the upgrade**: behave exactly as before, with zero
  configuration edits, whether they use an API key or a key file.

## Requirements *(mandatory)*

### Functional Requirements

**Configuration (Story 3)**

- **FR-001**: The extension MUST offer a provider type `google-gemini` that synthesizes speech with
  Google's Gemini-TTS models through Google's Text-to-Speech service.
- **FR-002**: A `google-gemini` entry MUST name a service account key file, which is read and
  validated at start-up by exactly the rules 003 applies to a `google-cloud` entry (path
  resolution, required contents, token address, read once).
- **FR-003**: A `google-gemini` entry that sets an API key MUST abort start-up, with a message
  saying Gemini voices require a service account key file and cannot use an API key.
- **FR-004**: A `google-gemini` entry MUST set a model, a default voice and a default language.
  A missing one MUST abort start-up with a message naming the extension, the entry and the setting.
- **FR-005**: Start-up MUST check only the form of these settings, never whether Google knows
  them. The model MUST be a single token of lowercase letters, digits, dots and hyphens. The voice
  MUST be a single word of ASCII letters. The language MUST be a language-region tag, checked by the same
  rule 002 uses. A malformed value MUST abort start-up naming the setting and the value.
- **FR-006**: A `google-gemini` entry MAY set a default style prompt and a default speaking rate.
  A default prompt longer than the extension's maximum text length MUST abort start-up. A speaking
  rate outside the range 002 allows for `google-cloud` MUST abort start-up.
- **FR-007**: A `google-gemini` entry that sets `engine`, `pitch` or any provider-specific extra
  parameter MUST abort start-up with a message naming the setting.
- **FR-008**: A style prompt set on an entry of any other type MUST abort start-up.
- **FR-009**: Start-up MUST make no network request for a `google-gemini` entry (001's rule:
  construct, do not contact).
- **FR-029**: When the default provider is a `google-gemini` entry, whether named as the default or
  the first enabled entry with no default named, start-up MUST log one warning that names the entry and
  says that announcements without a provider name are billed per token. Start-up MUST continue.
- **FR-030**: A `google-gemini` entry MUST honour the per-entry synthesis time limit with the same
  default and the same rules as every other type. It gets no longer default of its own. The time
  limit covers obtaining a token as well as the synthesis, as for a service-account `google-cloud`
  entry (003); running out of it fails with the existing timeout error (FR-019).

**Synthesis (Stories 1 and 2)**

- **FR-010**: A `google-gemini` entry MUST send each synthesis to Google's Text-to-Speech service
  with the configured model, the effective voice, the effective language and, when there is one,
  the effective style prompt, sent separately from the text, and, when one is set, the effective
  speaking rate. It MUST request the same audio format as a `google-cloud` entry.
- **FR-011**: A request MAY override the voice, the language, the style prompt and the speaking
  rate. The model MUST NOT be overridable per request. The speaking rate follows 002's rules for
  `google-cloud` unchanged: its range, its per-request override, and a neutral rate sharing the
  cache identity of no rate.
- **FR-012**: Every style prompt, from a request or an entry's default, MUST have leading and
  trailing whitespace removed first; case and inner spacing are kept. The effective style prompt
  MUST then be: the request's prompt when it is non-empty; none when the request gave a prompt that
  is empty after trimming; otherwise the entry's default prompt, if any. The trimmed prompt is what
  Google receives and what the length limit is checked against.
- **FR-013**: A request's style prompt longer than the extension's maximum text length MUST be
  rejected as a caller error before synthesis. The request's voice and language MUST pass the same
  form checks as in configuration (FR-005), or be rejected as a caller error.
- **FR-014**: A `google-gemini` entry MUST authenticate with an access token obtained from its
  service account key, with every 003 rule applied unchanged: reuse until close to expiry, one
  shared fetch for concurrent requests, a separate token per entry, a single renewal and retry
  after a rejected token, the back-off for an unreachable token service, and secrets kept out of
  messages and logs.
- **FR-015**: A `google-gemini` entry MUST NOT check an announcement's voice against a voice
  catalogue, although it lists voices from one (FR-023). The
  voice, from configuration or a request, MUST be normalized to a leading capital with the rest
  lowercase (`kore` becomes `Kore`), and that normalized name MUST be both what Google receives and
  what the cache identity uses. Error messages quote the voice as the caller wrote it.
- **FR-016**: The audio a `google-gemini` entry returns MUST play at the correct speed and pitch on
  the target output, whatever sample rate Google returns it at.

**Caching (Stories 1 and 2)**

- **FR-017**: The cache identity of a `google-gemini` announcement MUST include the model, the
  voice, the language, the effective style prompt and, on 002's terms, the speaking rate, in
  addition to what every announcement's identity already includes. The trimmed prompt (FR-012)
  MUST be compared exactly, so two prompts that differ after trimming never share an entry, and
  two that differ only in leading or trailing whitespace always do. No prompt and an empty prompt MUST be
  the same identity.
- **FR-018**: Cache identities of announcements through any other provider type MUST NOT change,
  so every existing cache entry stays valid.

**Failures (Story 4)**

- **FR-019**: A synthesis Google refuses MUST fail with the same error codes a `google-cloud` entry
  uses for the same refusal (provider error, rate limit, timeout), with a message that names the
  entry and includes Google's explanation whenever Google gave one.
- **FR-020**: This feature MUST NOT add an error code. Every new caller error maps to the existing
  invalid-request code, in the same single place as the existing mappings (001).

**Request fields (Story 5)**

- **FR-021**: A request that gives a style prompt for a provider that is not `google-gemini` MUST
  be rejected as a caller error naming the field and the provider type, before any synthesis. Any
  style prompt present in the request counts, including an empty or whitespace-only one: it asks
  for prompt behaviour that type does not have.
- **FR-022**: A request that gives an engine or pitch for a `google-gemini` provider MUST be
  rejected as a caller error naming the field and the provider type, before any synthesis.
**Voice listing (Story 6)**

- **FR-023**: Voice listing for a `google-gemini` entry MUST answer from Google's published voice
  list (the one `google-cloud` reads), with the voices whose name has the Gemini voice form
  (FR-005) and no others, each name normalized as in FR-015, sorted by name.
- **FR-031**: The list MUST be fetched on first need, never at start-up (FR-009), with the entry's
  own service account token, and MUST be remembered, held off after a failure and limited in time
  by the same `voice-catalogue` settings as a `google-cloud` entry's list. Each entry keeps its own
  copy. A list that cannot be fetched MUST fail the listing with the existing "catalogue
  unavailable" error.
- **FR-032**: Each listed Gemini voice MUST show its name (as both its short and its full name),
  the entry's model as its engine, and the gender Google publishes for it. It MUST show no
  language: Google's tag (`en-US`) does not say which languages the voice speaks.
- **FR-033**: A `language` filter on a `google-gemini` listing MUST pass the same form check as for
  `google-cloud`, a malformed one being a caller error, and MUST NOT narrow the list.
- **FR-034**: An `engine` filter on a `google-gemini` listing MUST be rejected as a caller error
  naming the filter and the provider type.
- **FR-035**: Announcing through a `google-gemini` entry, a cache hit or a miss, MUST NOT fetch the
  voice list.
- **FR-036**: Voice listing for a `google-cloud` entry MUST keep its voices, filters and order
  unchanged, and each listed voice MUST also show the gender Google publishes for it.

**Existing providers**

- **FR-024**: The `google-cloud`, OpenAI, Piper and local HTTP types MUST behave exactly as before.
  Every existing configuration MUST start and announce without edits.
- **FR-025**: Google-specific behaviour that both Google types need (service-account sign-in, the
  retry after a rejected token, passing on Google's explanation, reading the returned audio) MUST
  behave identically for both, so a fix to it reaches both types.

**Documentation**

- **FR-026**: The Google Cloud setup guide MUST describe Gemini voices as supported through a
  `google-gemini` entry: that they need a service account key file, which API and role the project
  and the service account need, that they have no free tier and are billed per token, which
  models are available, how to choose a voice (including listing the voices through the entry,
  and that a listed voice is not guaranteed for every model) and write a style prompt, and when
  to raise the entry's time limit (a slower model or longer texts). It MUST warn that a
  `google-gemini` default provider makes every announcement without a provider name billable.
- **FR-027**: The configuration reference MUST list the `google-gemini` type and its settings, and
  the REST contract MUST describe the request's style prompt field and which provider types accept
  it, and voice listing for `google-gemini`: which voices, which fields, and how each filter
  behaves.
- **FR-028**: The planning note for Gemini under `docs/future/` MUST move to `docs/archive/` with a
  line recording how the implementation differs from the note (route A instead of the recommended
  route B).

### Key Entities

- **Gemini Provider Entry**: An operator-configured `google-gemini` backend: a name, a service
  account key file, a model, a default voice, a default language, and an optional default style
  prompt and speaking rate.
- **Model**: Which Gemini-TTS model synthesizes (for example `gemini-2.5-flash-tts`). Fixed per
  entry; part of the cache identity.
- **Gemini Voice**: A voice name such as `Kore`, not tied to a language or engine. Normalized to a
  leading capital, then sent to Google and used in the cache identity. Google publishes a gender
  for each.
- **Gemini Voice List**: The Gemini voices in Google's published voice list, as one entry sees
  them: fetched on first need, remembered per entry, used for listing only.
- **Style Prompt**: A natural-language instruction for how the text is delivered. Sent separately
  from the text; part of the cache identity. Comes from the request, the entry's default, or
  nowhere.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An operator with a working service account key file (003) and the Google project
  prepared per the setup guide can configure a `google-gemini` entry and hear an announcement on
  the first attempt.
- **SC-002**: The same text announced with three different style prompts produces three audibly
  different announcements and three separate cache entries. Repeating any of them plays from the
  cache without contacting Google.
- **SC-003**: Every existing configuration in production starts and announces correctly after the
  upgrade with zero configuration edits, and every cache entry made before the upgrade is still a
  hit.
- **SC-004**: 100% of the malformed-configuration cases in Story 3 abort start-up with a message
  that names the extension, the entry and the fault.
- **SC-005**: Host start-up makes zero network requests on behalf of a `google-gemini` entry.
- **SC-006**: On a cache hit, a Gemini announcement starts playing within 1 second (001's target).
- **SC-007**: Whenever Google explains why it refused a Gemini synthesis, the caller receives that
  explanation.
- **SC-008**: No private key, signed assertion or access token appears in any log output or error
  response produced during the feature's test suite or its smoke run.
- **SC-009**: The feature adds no third-party library dependency to the extension's JAR.
- **SC-010**: Listing the voices of a `google-gemini` entry, with no filter or with any
  well-formed language, returns every Gemini voice Google publishes (30 on 2026-09-26) with its
  gender, and a voice picked from that list announces successfully with the entry's model.

## Assumptions

- **Route A only.** Gemini is reached through Google's Text-to-Speech service with a service
  account token (decided 2026-09-25). The Agent Platform route and API keys bound to a service
  account are out of scope. They could be added later as another credential for the same type.
- **The project is prepared by the operator.** The Agent Platform API is enabled and the service
  account has the Agent Platform User role (live tests, 2026-09-24). The extension does not check
  either; Google's refusal explains a missing one (Story 4).
- **Speaking rate yes, pitch no** (live test, 2026-09-25, one model and one voice). Google honoured
  speaking rate and ignored pitch for `gemini-2.5-flash-tts`. Other models are assumed to behave
  the same. Pitch can be steered only through the style prompt. If Google starts honouring pitch,
  accepting it is a later change.
- **Model names are not a closed list.** Google adds and retires Gemini models often, so the
  extension checks only the form of a model name. The setup guide lists the models known on
  2026-09-24 (`gemini-2.5-flash-tts`, `gemini-2.5-pro-tts`, `gemini-2.5-flash-lite-preview-tts`,
  `gemini-3.1-flash-tts-preview`).
- **Google's voice list includes the Gemini voices** (live check, 2026-09-26): as bare names, each
  tagged `en-US` with a gender and a 24 kHz natural rate, and with no model. The list is not per
  model, and Google rejects a model filter on it, so the listing cannot promise that every listed
  voice works with the entry's model. A service account that can synthesize can also read the
  list, and reading it is not billed.
- **Language is required.** Google refuses a Gemini request without a language code (live test,
  2026-09-25). Gemini voices carry no language in their name, so none can be derived from the
  voice, and a silent `en-US` fallback could mispronounce non-English announcements. The entry
  therefore sets one explicitly, and a request announcing in another language overrides it.
- **The prompt's length limit reuses the maximum text length.** A style prompt is a short
  instruction; one operator-facing limit is simpler than two. Google's own per-model limits on text
  and prompt are enforced by Google (edge cases).
- **The default time limit is enough for typical announcements.** Gemini synthesizes more slowly
  than classic voices, but short announcements are expected to finish within the shared 10 s
  default. The setup guide tells the operator to raise the entry's time limit for a slower model
  or longer texts (FR-026).
- **Setting names** (decided in planning): `model` and `style-prompt` in configuration, and
  `stylePrompt` in the request.
- **Single speaker only.** Gemini's multi-speaker dialogue is out of scope.
- **No spending cap.** The extension neither counts nor limits what a `google-gemini` entry sends
  to Google. A per-provider monthly character budget is planned separately
  (`docs/future/google-cloud-free-tier-tracking.md`) and would apply to Gemini entries too.
  Google's own billing budgets and alerts are the operator's safeguard until then.
- **The access token's scope** from 003 already covers the permission check Google applies to
  Gemini (003 FR-008), so how tokens are obtained does not change.
- **Verification on production** uses a real service account against Google, because tests never
  call a live provider (constitution III). It costs Gemini tokens and is kept to a few
  announcements.
