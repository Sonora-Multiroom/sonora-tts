# Feature Specification: Google Cloud Service Account Authentication

**Feature Branch**: `003-google-service-account-auth`

**Created**: 2026-09-25

**Status**: Implemented

**Depends on**: `002-google-voice-selection` (completed). This feature changes only how a
`google-cloud` provider entry proves its identity to Google. Voice resolution, the voice catalogue,
audio parameters, routing, queueing, playback and the cache are unchanged.

**Input**: User description: "@docs/future/google-cloud-service-account-auth.md"

## Overview

Today a `google-cloud` provider entry can authenticate only with a plain API key. Google also
issues **service account keys**: a JSON file holding an identity (`client_email`), a private key
and a project. That credential is built for server-to-server sign-in. It cannot be pasted in as an
API key, and today an operator who downloads one is told to throw it away and make an API key
instead.

Operators want a service account because:

- IAM roles and conditions scope it more precisely than an API key limited to one API.
- Some organisations forbid long-lived API keys and require service accounts.
- It is rotated and revoked centrally through IAM.
- It is the only credential with which Google's Text-to-Speech endpoint serves Gemini voices
  (live tests, 2026-09-24). Gemini voices themselves are **not** part of this feature. This feature
  provides the credential a later Gemini feature can build on.

This feature lets a `google-cloud` entry name a service account key file instead of an API key.
Everything the entry sends to Google, both synthesis and the voice catalogue, then carries a
short-lived access token obtained with that key. API-key entries keep working exactly as before.

**Not in this feature: Gemini TTS voices** (model name, style prompt). They stay a separate, later
feature. 002 and the notes under `docs/future/` refer to that work as "feature 003"; it now takes a
later number.

## Clarifications

### Session 2026-09-25

- Q: Does this feature also add Gemini voices through the Text-to-Speech endpoint, now that the
  credential they need exists? → A: No. Authentication only, for the existing `google-cloud`
  provider type and its classic voices. Gemini remains a separate feature with its own number.
- Q: Is the service account key given only as a path to a file, or also as inline JSON content
  (for example from an environment variable)? → A: Path only. Inline content is not accepted
  (FR-001).
- Q: When the token service is unreachable or times out, does every request try it again, or is
  the failure remembered for a back-off? → A: Remembered for the same back-off period as the
  voice catalogue (002, default 60 seconds), for an unreachable service or a timeout only. During
  the back-off, requests fail at once without contacting the token service. An explicit rejection
  of the key is never remembered (FR-019).
- Q: Are tokens requested from the address in the key file's `token_uri`, or always from Google's
  fixed token address? → A: From the file's `token_uri` when present, which must use `https`
  except for a loopback address (tests). When it is absent, Google's standard token address is
  used (FR-003, FR-007).
- Q: Is a replaced (rotated) key file picked up without a restart? → A: No. The file is read and
  validated once, at start-up; a rotated key takes effect after a restart (FR-013).
- Q: How is a key file path that is not absolute treated? → A: Accepted and resolved against the
  host's working directory, like the existing Piper paths. Every start-up message about the file
  shows the resolved absolute path. No `~` expansion (FR-001, FR-003).
- Q: Over one hour of steady announcements on one entry, how many times may the token service be
  contacted? → A: At most 2 (the first fetch plus one renewal), when no fetch fails and Google
  rejects no token (SC-005).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Announce With a Service Account Instead of an API Key (Priority: P1)

An operator whose Google project issues service accounts, not API keys, downloads the service
account's JSON key to the host and points a `google-cloud` provider entry at the file instead of
setting an API key. Announcements through that entry play exactly as they would with an API key:
same voices, same audio, same cache.

**Why this priority**: This is the feature. Without it, an operator who cannot or will not use an
API key cannot use Google voices at all.

**Independent Test**: Configure a `google-cloud` entry with only a service account key file, start
the host, and make an announcement. Confirm that it plays, that Google received a bearer token
rather than an API key, and that a second announcement within the token's lifetime does not ask
Google for a new token.

**Acceptance Scenarios**:

1. **Given** a `google-cloud` entry configured with a valid service account key file and no API
   key, **When** an announcement is requested, **Then** the entry obtains an access token, the
   synthesis request carries it, and the announcement plays.
2. **Given** that entry has already obtained a token, **When** further announcements arrive while
   the token is still valid, **Then** they reuse it and Google's token service is not contacted
   again.
3. **Given** the entry's token is close to expiry or has expired, **When** the next announcement
   arrives, **Then** a new token is obtained before synthesis, and the announcement plays without
   the caller noticing the renewal.
4. **Given** several announcements arrive at once and no valid token is held, **When** they are
   processed, **Then** the entry asks Google for one token, not one per announcement.
5. **Given** the same text and voice announced once through an API-key entry and once through a
   service-account entry of the same configuration otherwise, **When** the cache is consulted,
   **Then** how the entry authenticates does not change the audio's cache identity beyond what
   the provider name already contributes.
6. **Given** a service-account entry, **When** a voice is validated or the entry's voices are
   listed, **Then** the voice catalogue is fetched with the same token, and 002's existence check
   and voice listing behave exactly as they do for an API-key entry.

---

### User Story 2 - A Broken Credential Is Caught at Start-up (Priority: P1)

An operator mistypes the key file path, points at the wrong kind of JSON (an OAuth client file, a
user credential), or sets both an API key and a key file. The host refuses to start the extension
and says exactly what is wrong with which entry, instead of failing the first announcement hours
later.

**Why this priority**: Configuration faults are operator-fixable and must fail fast (constitution
VIII). A credential problem found only at the first announcement is the failure mode this project
exists to avoid.

**Independent Test**: Start the host with each broken configuration in turn and confirm that
start-up stops with a message naming the extension, the entry and the fault. Confirm that no
network request is made during any of these start-ups, including a successful one.

**Acceptance Scenarios**:

1. **Given** a `google-cloud` entry with neither an API key nor a key file, **When** the host
   starts, **Then** start-up is aborted with a message that names the extension and the entry and
   says one of the two is required.
2. **Given** an entry with both an API key and a key file, **When** the host starts, **Then**
   start-up is aborted with a message saying exactly one may be set.
3. **Given** an entry whose key file does not exist or cannot be read, **When** the host starts,
   **Then** start-up is aborted with a message naming the path.
4. **Given** a key file that is not valid JSON, is not a service account key, or lacks the
   identity or the private key, **When** the host starts, **Then** start-up is aborted with a
   message naming the path and what is missing or wrong.
5. **Given** a key file whose private key cannot be read as a usable signing key, **When** the host
   starts, **Then** start-up is aborted with a message naming the path.
6. **Given** a valid key file, **When** the host starts, **Then** start-up makes no request to
   Google, and the first contact with Google's token service happens on the first announcement,
   voice check or voice listing.
7. **Given** a key file set on a provider entry whose type is not `google-cloud`, **When** the host
   starts, **Then** start-up is aborted: the setting is never accepted and then ignored.

---

### User Story 3 - A Rejected Credential Explains Itself at Run Time (Priority: P2)

A service account's key is revoked, its account is disabled, or it lacks a permission Google
requires. The next announcement fails, and the error the caller receives carries Google's own
explanation, so the operator can see which permission or key is at fault without reproducing the
call by hand.

**Why this priority**: Start-up can check only the file's shape; whether Google still honours the
key is known only at run time. 002 established that Google's explanation must reach the caller;
this story extends that to the new sign-in step. Announcements with a healthy key work without it.

**Independent Test**: Make Google's token service reject the key, then make the synthesis service
deny the token. In each case confirm that the caller receives a provider-side error whose message
contains Google's explanation and never contains the private key or the token.

**Acceptance Scenarios**:

1. **Given** Google's token service rejects the key (revoked, disabled account, wrong key), **When**
   an announcement is requested, **Then** it fails with a provider-side error whose message names
   the entry, says that obtaining an access token failed, and includes Google's explanation.
2. **Given** Google's token service cannot be reached or times out, **When** an announcement is
   requested, **Then** it fails with the same kind of error as an unreachable synthesis service
   today, and the host keeps running.
3. **Given** a token fetch has just failed because the service was unreachable, timed out, or
   answered with a server error or a rate limit,
   **When** further announcements on that entry arrive within the back-off period, **Then** they
   fail at once with the same kind of error, without contacting the token service, and the message
   says the token service is in back-off after a recent failure.
4. **Given** the synthesis service denies a valid token (for example a missing permission),
   **When** the caller receives the failure, **Then** the message includes Google's explanation,
   exactly as for an API-key entry (002).
5. **Given** any of these failures, **When** it is reported or logged, **Then** neither the private
   key, the signed assertion nor any access token appears in the message or the log.
6. **Given** the token service was unreachable, **When** it recovers, **Then** the first
   announcement after the back-off period obtains a token and plays, with no restart needed.
   **Given** Google rejected the key, **When** the operator fixes the cause on Google's side (for
   example re-enables the account), **Then** the very next announcement obtains a token.
7. **Given** the token cannot be obtained, **When** an announcement is requested, **Then** it fails
   with the token failure, and the voice existence check is skipped. The entry obtains its token
   before the existence check, so one announcement asks Google for a token at most once (plus the
   single retry for a rejected token). **When** a voice catalogue fetch itself fails because the
   token could not be obtained (voice listing, for example), **Then** 002's rule applies: the
   failure starts the catalogue back-off.

---

### Edge Cases

- **A token that Google rejects as expired although the entry believes it valid** (clock skew on
  the host): the entry discards the token, obtains a new one once and retries the request once.
  If the retry also fails, the failure is reported with Google's explanation. A single request
  never triggers more than one extra token fetch.
- **A relative key file path under a service manager** (for example systemd with a working
  directory of `/`): the path resolves against that working directory, and if no file is there,
  start-up aborts with the resolved absolute path in the message, so the mismatch is visible.
- **The key file changes on disk after start-up** (the operator rotates the key): the entry keeps
  using the key it read at start-up until the host restarts. Rotation takes effect on restart.
- **The key file is readable at start-up and deleted afterwards**: nothing changes, because the key
  was read once at start-up.
- **Two entries use the same key file**: each entry holds its own token, just as each keeps its own
  voice catalogue (002). Nothing is shared between entries.
- **The token service is slow**: the token fetch counts against the same synthesis time budget as
  the rest of the request (001). A timeout is reported as a provider timeout and starts the token
  back-off (FR-019), so an outage costs at most one slow announcement per back-off period.
- **The service account lacks the Text-to-Speech permission**: Google's denial on the synthesis
  call is passed on with its explanation (Story 3, scenario 4). The extension does not check IAM
  permissions itself.
- **An organisation policy forbids creating service account keys**: the operator cannot obtain a
  key file, and this feature cannot help. Credential sources without a key file are out of scope
  (see Assumptions). The setup documentation says so.
- **The key file sits in a world-readable location**: the extension does not inspect file
  permissions. Protecting the file is the operator's responsibility, like protecting an API key in
  an environment variable today.
- **An API-key entry after the upgrade**: behaves exactly as before, with zero configuration edits.

## Requirements *(mandatory)*

### Functional Requirements

**Configuration (Story 2)**

- **FR-001**: A `google-cloud` provider entry MUST accept, as an alternative to its API key, the
  filesystem path of a service account key file. The key is accepted only as a path; there is no
  setting that takes the key's JSON content directly. A path that is not absolute is resolved
  against the host's working directory, as the extension's other file paths are; a leading `~` is
  not expanded.
- **FR-002**: Every `google-cloud` entry MUST set **exactly one** of API key and key file. Neither,
  or both, MUST abort start-up with a message naming the extension and the entry.
- **FR-003**: At start-up the extension MUST read the key file and abort start-up, naming the
  extension, the entry and the resolved absolute path, if the file does not exist or cannot be read; is not valid
  JSON; does not declare itself a service account key; lacks the account's identity (its email)
  or its private key; holds a private key that cannot be used for signing; or names a token
  address (`token_uri`) that is not a valid URL, or that uses a scheme other than `https` without
  being a loopback address.
- **FR-004**: Start-up MUST make no network request for a service-account entry. The first request
  to Google's token service MUST happen on the entry's first synthesis, voice existence check or
  voice listing (001's rule: construct, do not contact).
- **FR-005**: The key file setting on a provider entry whose type is not `google-cloud` MUST abort
  start-up. It MUST NOT be accepted and silently ignored.
- **FR-006**: Configurations written before this feature (a `google-cloud` entry with an API key)
  MUST keep working without edits and MUST behave exactly as before.

**Obtaining and using a token (Story 1)**

- **FR-007**: A service-account entry MUST authenticate every request it sends to Google, synthesis
  and voice catalogue alike, with an access token obtained from Google's token service using the
  entry's key. It MUST NOT send an API key. The token service address MUST be the key file's
  `token_uri` when the file sets one, and Google's standard token address otherwise. There is no
  separate setting for it.
- **FR-008**: The access token MUST be requested with a scope broad enough for the Text-to-Speech
  service and for the Agent Platform permission check Google applies to Gemini voices, so that a
  later Gemini feature needs no change to how tokens are obtained.
- **FR-009**: An entry MUST reuse its token until shortly before it expires, and MUST obtain a new
  one before sending a request with a token that is expired or about to expire. Tokens MUST NOT be
  obtained per request.
- **FR-010**: When several requests on one entry need a new token at the same time, the entry MUST
  obtain one token and share it among them.
- **FR-011**: Each provider entry MUST hold its own token. Tokens MUST NOT be shared between
  entries, even entries that name the same key file.
- **FR-012**: If Google rejects a request because its token is invalid or expired, the entry MUST
  discard the token, obtain a new one, and retry that request once. A second rejection MUST be
  reported as a failure (Story 3).
- **FR-013**: The key file MUST be read and validated once, at start-up, and MUST NOT be read again
  while the host runs, not even after Google rejects the key. Changes to the file take effect when
  the host restarts, so a broken replacement file is caught by start-up validation, never by an
  announcement. The setup guide MUST state that rotating the key needs a restart.
- **FR-014**: How an entry authenticates MUST NOT change what it synthesizes, how voices are
  resolved or validated, or how the result is cached. Every 002 behaviour (voice resolution,
  catalogue, existence check, listing, pitch and speaking rate) MUST work identically for both
  credential kinds.

**Failures (Story 3)**

- **FR-015**: A failure to obtain a token MUST fail the request with a provider-side error whose
  message names the entry, states that obtaining an access token failed, and includes Google's
  explanation whenever Google gave one. If Google's response cannot be interpreted, the message
  MUST still state its status.
- **FR-016**: An unreachable or timed-out token service MUST be reported with the same error codes
  as an unreachable or timed-out synthesis service, and the token fetch MUST count against the
  request's existing synthesis time budget.
- **FR-017**: Every token failure MUST map to an error code already in the published contract. This
  feature adds no new error code: the message, not the code, carries Google's diagnosis, and the
  failure is operator-fixable rather than caller-fixable in every case.
- **FR-018**: The private key, any signed assertion and any access token MUST NOT appear in an
  error message, a log line, an exception message or a response body, at any log level.
- **FR-019**: A token fetch that fails because the token service is unreachable, times out, or
  answers that it cannot serve now (a server error or a rate limit, which say nothing about the
  key) MUST be remembered per entry for the same configurable back-off period as the voice catalogue (002,
  default 60 seconds). During that period, requests that need a new token MUST fail at once with
  the same error code, without contacting the token service, and the message MUST say the token
  service is in back-off after a recent failure. A token that is still valid keeps being used
  regardless. An explicit rejection of the key by Google MUST NOT be remembered: the next request
  tries again. No token failure ever requires a restart to recover from. (The voice catalogue
  keeps its own back-off from 002, which a token failure during a catalogue fetch starts like any
  other fetch failure.)

**Scope boundaries**

- **FR-020**: This feature applies only to the `google-cloud` provider type. OpenAI, Piper and local
  HTTP providers MUST be unaffected. Gemini voices (model name, style prompt) are out of scope.

**Documentation**

- **FR-021**: The Google Cloud setup guide MUST describe the service-account path alongside the API
  key path: which roles the service account needs for classic voices, how to create and download
  the key, where to put it, and what an organisation policy that forbids key creation means. It
  MUST no longer tell operators to discard a service account key. The configuration reference MUST
  list the new setting and its exclusivity with the API key.

### Key Entities

- **Credential**: How a `google-cloud` entry proves its identity: either an API key or a service
  account key file. Exactly one per entry.
- **Service Account Key**: The contents of the key file: the account's email, its private signing
  key, its project and, optionally, the address of the token service to use. Read once at start-up; never logged, never sent to Google as such.
- **Access Token**: A short-lived bearer credential Google issues in exchange for a signed
  assertion made with the service account key. Held per entry with its expiry, reused until close
  to expiry, then replaced.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An operator with only a service account key file can configure a `google-cloud`
  entry and hear an announcement on the first attempt, without creating an API key.
- **SC-002**: Every existing API-key `google-cloud` configuration in production starts and
  announces correctly after the upgrade with zero configuration edits.
- **SC-003**: Host start-up makes zero network requests on behalf of a service-account entry.
- **SC-004**: 100% of the malformed-credential cases in Story 2 abort start-up with a message that
  names the extension, the entry and the fault.
- **SC-005**: Over one hour of steady announcements on one entry, with no failed fetch and no
  rejected token, Google's token service is contacted at most 2 times (the first fetch plus one
  renewal), never once per announcement.
- **SC-006**: On a cache hit, an announcement through a service-account entry still starts playing
  within 1 second (001), and on a cache miss with a valid token held, it makes no network request
  beyond those an API-key entry makes (no token request, no extra round trip).
- **SC-007**: Whenever Google explains why it rejected a key or a token, the caller receives that
  explanation.
- **SC-008**: No private key, signed assertion or access token appears in any log output or error
  response produced during the feature's test suite or its smoke run.
- **SC-009**: The feature adds no third-party library dependency to the extension's JAR.

## Assumptions

- **Key files only.** The credential source is a service account JSON key file on the host. Other
  sources (a metadata server, Workload Identity Federation, user credentials, Google's
  default-credentials environment variable) are out of scope. The host is a Raspberry Pi outside
  Google Cloud, where a key file is the practical option. An organisation that forbids key creation
  needs a later feature.
- **The setting is a path, not inline JSON** (decided 2026-09-25). A multi-line JSON document is
  awkward in YAML and in environment variables; a path is how Google's own tooling names key
  files. Inline content can be added later without breaking existing entries. The exact setting
  name is decided in planning.
- **The token lifetime and renewal margin** follow Google's defaults (tokens valid about one hour);
  the entry renews a few minutes before expiry. The exact margin is decided in planning and is not
  operator configuration.
- **No third-party authentication library.** The extension already calls every provider without a
  vendor SDK, and signing the assertion needs nothing beyond the platform's standard cryptography
  (recorded here because SC-009 depends on it).
- **Error code.** Token failures reuse the existing provider-side codes (provider error, provider
  timeout) rather than adding one. The published error codes are a closed set, and every token
  failure is an operator problem that the message describes. Planning may revisit this only if a
  caller is found that would act differently on a dedicated code.
- **Roles for classic voices.** A service account needs permission to use the Text-to-Speech
  service in its project; the setup guide confirms the exact role during planning. The Agent
  Platform role is needed only for Gemini and is not required by this feature.
- **Renumbering.** 002's spec and the notes under `docs/future/` call the Gemini provider "feature
  003". That work keeps its content but takes a later number; the notes are updated when this
  feature's documentation is written. 002's spec is a record of its decisions and is not edited.
