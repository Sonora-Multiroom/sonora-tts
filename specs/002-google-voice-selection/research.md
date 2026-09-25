# Research: Google Cloud Voice Selection

**Feature**: `002-google-voice-selection` | **Date**: 2026-09-24

The spec's Clarifications session settled the behaviour. This document records the technical
decisions the plan rests on. Sources: Google's `v1` REST reference (fetched 2026-09-24), the
current code on this branch, and production's `~/multiroom.yml` (read 2026-09-24).

---

## R1. Pitch and speaking-rate ranges

**Decision**: `speakingRate` ∈ **[0.25, 2.0]**, default 1.0. `pitch` ∈ **[-20.0, 20.0]** semitones,
default 0.0. Both are validated at start-up (FR-015) and per request (FR-009), inclusive bounds.

**Rationale**: From the `v1` `AudioConfig` reference: *"speakingRate … in the range [0.25, 2.0]
… 1.0 is the normal native speed"*, *"pitch … in the range [-20.0, 20.0]"*. Note that the
commonly quoted upper bound of 4.0 for speaking rate is from older documentation. The current `v1`
reference says 2.0, and validating against the smaller range is the safe side.

**Per-engine support**: Google's Chirp 3: HD page (fetched 2026-09-24) documents pace control
(speaking rate, 0.25–2.0) but no pitch control. The extension does not keep a per-engine support
table: it sends what is configured, and Google's rejection, if any, is surfaced (R11). The
configuration contract's `chirp3-hd` example therefore sets only `speaking-rate`, and the audible
pitch check uses a Neural2 voice.

**Alternatives considered**: Not validating and letting Google reject: rejected, because the spec
requires a caller error that states the range, and a start-up fault for configuration. A
per-engine support table: rejected, because Google changes support per engine without notice and a
stale table would reject valid requests.

---

## R2. Voice catalogue endpoint and fetch shape

**Decision**: `GET https://texttospeech.googleapis.com/v1/voices?key=…` with **no `languageCode`**,
so one call returns every voice. Each entry's `name` is parsed (R3). The language used for listing
and matching is the name's own prefix, not `languageCodes[]`.

**Rationale**: The reference says that omitting `languageCode` returns all supported voices. One
fetch per entry per TTL is simpler than one per-language cache per entry, and the full list is a
few hundred KB. Using the name prefix keeps the catalogue and resolution on one definition of a
voice's language (FR-003 compares with the prefix).

**Alternatives considered**: Per-language fetches: more calls and a two-level cache, for no
benefit at this size. Hardcoding the voice list: rejected by the source note (Google adds voices
often).

---

## R3. Voice-name grammar

**Decision**:

- **Language**: `[A-Za-z]{2,3}-([A-Za-z]{2}|[0-9]{3})`. Canonical form lowercases the language and
  uppercases a letter region (`UK-ua` → `uk-UA`, `CMN-cn` → `cmn-CN`, `es-419` unchanged).
- **Full name**: `{language}-{engine}-{voice}`, where `voice` is the segment after the **last**
  hyphen (`[A-Za-z0-9]+`) and `engine` is everything between the language and the voice
  (`[A-Za-z0-9]+(-[A-Za-z0-9]+)*`, non-empty). So `en-US-Chirp-HD-F` has engine `Chirp-HD` and
  voice `F`, and `en-US-Polyglot-1` has engine `Polyglot` and voice `1`.
- **Short name**: `[A-Za-z0-9]+`, which cannot contain a hyphen.
- Anything else is malformed. `uk-UA-Charon` (a language with no engine) is malformed rather than
  guessed at.

**Rationale**: Every legacy and Chirp name follows this shape, and engines with a hyphen
(`Chirp-HD`, `Chirp3-HD`) resolve correctly with a last-hyphen split. Short names never contain a
hyphen, so a string's form decides unambiguously whether it is full or short.

**Alternatives considered**: Matching engines against the known list first: this fails for
unrecognized engines, which FR-006 requires to be accepted as full names.

---

## R4. Engine set and aliases

**Decision**: `GoogleEngine` enum with canonical spellings `Standard`, `Wavenet`, `Neural2`,
`Studio`, `Chirp-HD`, `Chirp3-HD`. Aliases are matched by folding case and removing `-` and `_`,
so `wavenet`, `WaveNet`, `chirp3-hd`, `Chirp3_HD` and `chirp3hd` all match. An unrecognized engine
**segment in a full name** is kept as written (FR-004/FR-006). An unrecognized engine in the
`engine` setting or in a request is an error (a start-up fault or a caller error).

**Rationale**: FR-006 names these six. Folding separators covers the spellings people actually
write without maintaining a per-engine alias list. `Polyglot`, `News`, `Casual` and similar work
through full names and are listed by the catalogue, so they don't need to be in the enum.

---

## R5. Where resolution happens: two steps

**Decision**: `TtsProvider` gains `SynthesisSettings resolveSettings(RequestedSettings)`, which is
**pure, with no I/O**. `TtsService` calls it on every request and builds the cache key and the
`SynthesisRequest` from its result. The **existence check runs inside `synthesize`**, so only on a
cache miss.

**Rationale**:

- The cache key needs the canonical name (FR-017), so resolution must come before the cache
  lookup. It must not need the network, or a cold catalogue would slow cache hits (SC-006).
- Audio is cached only after Google accepted the voice, so a cache hit proves the voice exists and
  checking again adds nothing.
- Keeping the check in `synthesize` lets one method own the time budget (R8).

**Alternatives considered**:

- Checking before the cache lookup: this puts network work on the hit path.
- A separate resolver bean per provider, beside `ProviderRegistry`: this needs a second
  name-keyed registry that must stay in sync with the first.
- Doing Google-specific logic in `TtsService`: this breaks Principle IV (the playback path would
  know about one provider).

---

## R6. Non-Google providers keep 001 behaviour exactly

**Decision**: `TtsProviderConfig.language` loses its `"en-US"` field initialiser (it becomes
`null`), as FR-002 requires for `google-cloud`. `DefaultSettingsResolution`, which OpenAI, Piper and
local HTTP delegate to, restores 001's rule: `voice = request ?? config`,
`language = request ?? config ?? "en-US"`, `engine = config`. It rejects `engine`, `pitch` and
`speakingRate` overrides with `INVALID_REQUEST` naming the field and the provider type (FR-020).

**Rationale**: The `en-US` default is valid for the other types and FR-020 forbids changing it.
Moving it from the shared config class into the non-Google resolution is the only way to remove it
for one type alone. The resolved values are identical to 001's, so their cache keys are unchanged.

---

## R7. Cache identity

**Decision**: `CacheKey` gains `pitch` and `speakingRate`, and they are **appended to the hashed
string only when set**. For `google-cloud`:

- `voice` component = the canonical full name **case-folded** (`Locale.ROOT`), supplied as
  `SynthesisSettings.voiceKey`.
- `engineName` component = `null` (the full name already encodes the engine).
- `language` = the canonical language-region tag (R3).
- `pitch` / `speakingRate` = the effective value, but **omitted when equal to Google's default**
  (0.0 / 1.0), formatted with `BigDecimal.stripTrailingZeros().toPlainString()` (`1.10` → `1.1`).
  The omission is decided by the Google resolver, which returns it as
  `SynthesisSettings.pitchKey` / `speakingRateKey`. `TtsService` copies these into the key without
  knowing any provider's defaults, so a future provider with other neutral values needs no change
  to the playback path (Principle IV).

For the other types, `voiceKey == voice` and nothing else changes.

`SynthesisSettings` also carries `requestedVoice`, the voice as written before resolution. It
exists only so that `INVALID_VOICE` can quote the caller's spelling (`'charn'`, as in the spec's
example), and it is **excluded from the cache key**. Otherwise `charn` and `Charn` would split
entries.

**Rationale**:

- Case-folding makes the key independent of whether the catalogue or the rule chose the spelling
  (FR-004 has two sources of canonical case, and for unrecognized engines they can differ). Google
  voice names are unique regardless of case.
- Appending only when set keeps every non-Google key and every no-adjustment key stable.
- Omitting defaults satisfies FR-017's "same audio → same entry": `speakingRate: 1.0` sounds the
  same as none. The value is still *sent* whenever set (FR-014); only the key normalizes it.
- Production's existing entries miss once after the upgrade because of the case-fold. The spec
  accepts this ("may re-synthesize them once").

**Alternatives considered**: Case-folding every provider's voice: this could merge two distinct
voices of a case-sensitive local HTTP engine into one entry and serve the wrong audio.

---

## R8. One time budget for catalogue and synthesis

**Decision**: `GoogleCloudTtsProvider.synthesize` takes `deadline = now + timeout-seconds` on
entry. A catalogue fetch uses `min(voice-catalogue.fetch-timeout, remaining)`, where `fetch-timeout`
defaults to **3 s**. Synthesis then uses whatever remains, with a floor of 1 s. A catalogue timeout
starts the back-off (R9).

**Rationale**: Edge case "catalogue responds slowly" plus 001 SC-003 (an error within the
10 s budget). Capping the catalogue fetch well below the budget keeps SC-007: a dead catalogue
costs at most 3 s once per back-off period and still leaves ~7 s for synthesis.

**Alternatives considered**: Giving the catalogue the full timeout: a hung catalogue would use the
whole budget, and the announcement would then fail, breaking SC-007. Passing a deadline from
`TtsService` through `SynthesisRequest`: unnecessary once the check lives in `synthesize` (R5).

---

## R9. Catalogue lifecycle and concurrency

**Decision**: `GoogleVoiceCatalogue`, one instance per `google-cloud` entry, created in the
provider's constructor without fetching. It has three states: *empty*, *loaded(at)*,
*failed(at)*. Lookup behaves as follows:

| State | Lookup |
|---|---|
| empty, or loaded and older than `ttl` (24 h) | fetch, then look up |
| loaded and fresh, voice present | FOUND (catalogue spelling) |
| loaded and fresh, voice absent | refetch once (R10), then FOUND or MISSING |
| failed less than `failure-backoff` (60 s) ago | UNAVAILABLE, no fetch |
| fetch fails (timeout, I/O, non-2xx, unparseable body) | record *failed(now)*, UNAVAILABLE |

Fetches are **single-flight**: a `ReentrantLock`, and after acquiring it the state is checked
again, so N concurrent cold requests make one call. Snapshots are immutable and published through
a `volatile` field, so warm lookups never take the lock. Time comes from an injected `Clock`.

**Rationale**: FR-011 and FR-013 exactly. Snapshots without locking keep the warm path to a
hash-map lookup (SC-006).

A failed fetch does **not** discard an older loaded snapshot until the TTL passes. If a refresh
fails, the stale-but-loaded copy is still better than no check. Once past the TTL with a failed
refresh, the state is UNAVAILABLE.

**Alternatives considered**: Background pre-warming after start-up: the spec permits it but does
not require it. Fetching on first need is simpler and has no scheduler to stop. It can be added
later without changing the design.

---

## R10. Refetch on miss without amplifying typos

**Decision**: When a fresh snapshot lacks the voice, refetch **once**, but only if the snapshot is
older than `failure-backoff` (60 s). Otherwise answer MISSING from the snapshot.

**Rationale**: FR-011 requires one refetch so that a newly published voice is not rejected. Without
a floor, every typo would trigger a full catalogue download, so a caller could force one fetch per
request. With the floor, the copy that rejects a voice is always at most 60 s old, which still meets
the edge case's intent. It reuses the back-off setting, so no new setting is needed.

---

## R11. Google error body

**Decision**: `GoogleErrorBody.message(String body)` reads `error.message` from the standard
envelope `{"error":{"code":…,"message":"…","status":"…"}}`. The exception message becomes
`Provider '<entry name>' returned HTTP <status>: <message>`, with the message truncated to 500
characters. If the body is empty or unparseable, the message stays
`Provider '<entry name>' returned HTTP <status>`. The same applies to 429 (`PROVIDER_RATE_LIMITED`).
Error codes and HTTP statuses are unchanged (FR-001).

The literal `'google-cloud'` in today's messages is replaced by the **entry name** (for example
`'google'`), so the caller can tell entries apart.

**Rationale**: FR-001, SC-002. Truncation keeps a pathological body out of logs and responses. The
body never contains the API key, because the key is in the query string of *our* request and not
echoed by Google.

---

## R12. Configuration surface and start-up validation

**Decision**:

- **Per entry** (`multiroom.tts.providers[n]`): `pitch` (Double) and `speaking-rate` (Double) are
  new. `engine` becomes meaningful for `google-cloud`. `language` has no default.
- **Global** (`multiroom.tts.voice-catalogue`): `ttl` (Duration, 24h), `failure-backoff`
  (Duration, 60s), `fetch-timeout` (Duration, 3s). Each entry has its own catalogue *instance*
  (FR-011), and all use these timings.
- **`google-cloud` start-up faults** (all name `multiroom-tts` and the entry; no network):
  `engine` not recognized; `language` malformed; `voice` malformed; no default engine (no `engine`
  and no full `voice`); a short `voice` with no `language`; `pitch` or `speaking-rate` out of range
  (message states the range); a non-empty `extra-params` (the message points to `pitch` and
  `speaking-rate`).
- **Any other type**: `pitch` or `speaking-rate` set is a start-up fault (SC-008: never accepted
  and ignored).
- **Warning, not fault**: a configured `language` that contradicts a configured full `voice`. The
  configured voice is synthesized in its own language (FR-002), and the configured `language`
  stays the default for short names (FR-007). SC-004 forbids breaking such an entry.
- **Default language**: `language`, or else the prefix of a configured full `voice` (FR-007).
  So an entry with only `voice: uk-UA-Chirp3-HD-Charon` accepts a request for voice `puck`.
- **Deliberate break**: a `google-cloud` entry whose `engine` was a free-text cache label (the
  setup guide documented this) now aborts start-up if the label is not a recognized engine. FR-007
  forbids accepting and ignoring it. Production has no `engine` and is unaffected. The setup
  guide's own example (`engine: neural2`) is recognized.

**Rationale**: `extra-params` on `google-cloud` is rejected rather than mapped. The typed settings
are the supported path, and mapping arbitrary keys would make "take effect" depend on key spelling.
Other types' `extra-params` are also ignored today. That is outside this feature's scope (FR-020),
and is recorded as follow-up.

Global catalogue timings keep the operator's configuration small. The spec asks for "a
configurable period", and nothing needs different timings per entry.

**Production check (SC-004)**: `name: google`, `type: GOOGLE_CLOUD`, `language: en-US`,
`voice: en-US-Neural2-C`, `timeout-seconds: 10`, no `engine`, no `extra-params`. The default engine
is `Neural2`, taken from the voice. The language agrees with the voice. The entry starts and
announces with no edits. The second entry (`piper-local`, `LOCAL_HTTP`) has no language, so it
resolves `en-US` exactly as before (R6).

---

## Follow-ups (not in this feature)

- `extra-params` is accepted and ignored for OpenAI, Piper and local HTTP (pre-existing, 001).
- Gemini TTS: feature 003 (separate provider, Agent Platform endpoint).
- Optional background warming of the catalogue after start-up.
