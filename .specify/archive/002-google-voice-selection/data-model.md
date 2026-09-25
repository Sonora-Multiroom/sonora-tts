# Data Model: Google Cloud Voice Selection

**Feature**: `002-google-voice-selection` | **Date**: 2026-09-24

Everything here lives in memory: the only persisted state is the existing audio cache, whose key
gains components (see [Cache Key](#cache-key)). Grammar and ranges are from
[research.md](research.md) R1–R4.

## Entities

### GoogleEngine (enum)

A family of Google voices.

| Constant | Canonical spelling | Recognized as (case- and `-`/`_`-insensitive) |
|---|---|---|
| `STANDARD` | `Standard` | `standard` |
| `WAVENET` | `Wavenet` | `wavenet`, `WaveNet` |
| `NEURAL2` | `Neural2` | `neural2` |
| `STUDIO` | `Studio` | `studio` |
| `CHIRP_HD` | `Chirp-HD` | `chirp-hd`, `chirphd` |
| `CHIRP3_HD` | `Chirp3-HD` | `chirp3-hd`, `Chirp3_HD`, `chirp3hd` |

- `Optional<GoogleEngine> fromName(String)`: alias lookup.
- An engine *segment* of a full name that does not match stays a plain string (an
  **unrecognized engine**). No enum constant exists for it.

### GoogleLanguage

A language tag in the form Google uses in voice names.

- Format: `[A-Za-z]{2,3}-([A-Za-z]{2}|[0-9]{3})`.
- Canonical: the language lowercased, a letter region uppercased (`uk-ua` → `uk-UA`).
- Equality compares canonical forms, so comparison ignores case (FR-003).

### GoogleVoiceName (sealed: `Full` | `Short`) — the spec's *Voice Reference*

What a caller or operator writes.

| Variant | Fields | Example | Canonical by rule |
|---|---|---|---|
| `Full` | `language`, `engineSegment`, `voice` | `uk-ua-chirp3-hd-charon` | `uk-UA-Chirp3-HD-Charon` |
| `Short` | `voice` | `charon` | `Charon` |

- `parse(String)` returns `Full`, `Short`, or throws `IllegalArgumentException("malformed")`.
- Rule canonicalization (FR-004): canonical language, then the canonical engine spelling if the
  engine is recognized (otherwise the segment as given), then the voice with its first letter
  uppercased and the rest as given.
- `Full.engine()` returns `Optional<GoogleEngine>` (empty for an unrecognized segment).
- `Short.compose(GoogleEngine, GoogleLanguage)` returns a `Full`.

### RequestedSettings

The per-request overrides passed to `TtsProvider.resolveSettings`. Every field is nullable.

| Field | Type | Source |
|---|---|---|
| `voice` | String | `SpeakRequest.voice` (001) |
| `language` | String | `SpeakRequest.language` (001) |
| `engine` | String | `SpeakRequest.engine` (new) |
| `pitch` | Double | `SpeakRequest.pitch` (new) |
| `speakingRate` | Double | `SpeakRequest.speakingRate` (new) |

### SynthesisSettings — the spec's *Resolved Voice* + *Audio Adjustments*

The result of resolution, used for the cache key and for the synthesis request.

| Field | Type | Google | Other providers |
|---|---|---|---|
| `voice` | String, nullable | canonical full name by rule; `null` when no voice anywhere | request ?? config (001) |
| `voiceKey` | String, nullable | `voice` case-folded (`Locale.ROOT`) | `== voice` |
| `requestedVoice` | String, nullable | the voice exactly as the caller or config wrote it (`charn`), before resolution; **used only in error messages, never in the cache key** | `== voice` |
| `language` | String | canonical tag | request ?? config ?? `en-US` (001) |
| `engine` | String, nullable | `null` (encoded in `voice`) | config `engine` (001) |
| `pitch` | Double, nullable | request ?? config | always `null` |
| `speakingRate` | Double, nullable | request ?? config | always `null` |
| `pitchKey` | Double, nullable | `pitch`, but `null` when it equals Google's neutral `0.0` | always `null` |
| `speakingRateKey` | Double, nullable | `speakingRate`, but `null` when it equals Google's neutral `1.0` | always `null` |

`pitchKey` and `speakingRateKey` are the cache-key forms of the audio adjustments, just as
`voiceKey` is the cache-key form of the voice. The provider's resolver computes them, because only
the provider knows which value is neutral. `TtsService` copies them into the `CacheKey` and never
interprets them, so it stays provider-agnostic (Principle IV). The values that are *sent* are
`pitch` and `speakingRate`, unchanged (FR-014).

`SynthesisRequest` becomes `(text, SynthesisSettings settings, targetSampleRate, targetChannels)`
and keeps `voice()` / `language()` accessors, so the non-Google providers' bodies do not change.

### CatalogueVoice

One entry parsed from `GET v1/voices`.

| Field | Example |
|---|---|
| `fullName` | `uk-UA-Chirp3-HD-Charon` (the catalogue's own spelling) |
| `shortName` | `Charon` |
| `engine` | `Chirp3-HD` (the canonical spelling if recognized, otherwise the segment as published) |
| `language` | `uk-UA` (from the name prefix, R2) |

A catalogue `name` that does not parse as a full name is dropped. It could not be requested
through this provider anyway.

### VoiceCatalogueSnapshot

An immutable snapshot of one successful fetch.

- `fetchedAt: Instant`
- `byFoldedName: Map<String, CatalogueVoice>`: the key is `fullName` case-folded.
- `voices(language?, engine?) → List<CatalogueVoice>`: filtered (engine matched by
  `GoogleEngine` alias *or* by raw segment, ignoring case, FR-018) and sorted by language, then
  engine, then short name.

### GoogleVoiceCatalogue (one per `google-cloud` entry) — the spec's *Voice Catalogue*

```text
            ┌──────────── fetch ok ────────────┐
            ▼                                  │
 EMPTY ──fetch──► LOADED(at) ──age ≥ ttl──► (fetch) ──fail──► FAILED(at)
   │                  ▲                                          │
   └──fetch fail──► FAILED(at) ◄────────────────────────────────┘
                      │  age < failure-backoff → UNAVAILABLE, no fetch
                      └─ age ≥ failure-backoff → fetch on next need
```

Stored as `volatile` state `{snapshot: VoiceCatalogueSnapshot?, failedAt: Instant?}`. A failed
refresh keeps an older snapshot, but only while that snapshot is still within `ttl` (R9).

Operations:

- `check(String canonicalFullName, Duration budget) → Found(CatalogueVoice) | Missing(List<CatalogueVoice> alternatives) | Unavailable`.
  `alternatives` holds the voices with the same language and engine segment (case-folded).
  At most one miss-refetch, and only if the snapshot is older than `failure-backoff` (R10).
- `list(language?, engine?, Duration budget) → List<CatalogueVoice>`, or throws
  `VOICE_CATALOGUE_UNAVAILABLE`.

### Provider entry additions (`TtsProviderConfig`)

| Field | Type | Default | Applies to |
|---|---|---|---|
| `language` | String | **`null`** (was `"en-US"`) | all. Non-Google types fall back to `en-US` at resolution (R6) |
| `engine` | String | `null` | `google-cloud`: must be a recognized engine; the default engine for short names |
| `pitch` | Double | `null` | `google-cloud` only, [-20.0, 20.0] |
| `speakingRate` | Double | `null` | `google-cloud` only, [0.25, 2.0] |
| `extraParams` | Map | empty | must be empty for `google-cloud` |

**Derived: default engine** (FR-007) = `GoogleEngine.fromName(engine)` if `engine` is set,
otherwise the engine of the configured full `voice` (which may be an unrecognized segment),
otherwise a start-up fault.

**Derived: default language** (FR-007) = `GoogleLanguage.parse(language)` if `language` is set,
otherwise the language prefix of the configured full `voice`, otherwise none. None is a start-up
fault only when the configured `voice` is short. With no voice configured, a request can still
supply the language, and a voice-less request falls back to `en-US` (step 5).

### VoiceCatalogueProperties (`multiroom.tts.voice-catalogue`)

| Field | Type | Default | Validation |
|---|---|---|---|
| `ttl` | Duration | `24h` | > 0 |
| `failureBackoff` | Duration | `60s` | > 0 |
| `fetchTimeout` | Duration | `3s` | > 0 |

### TtsErrorCode additions

| Code | HTTP | Raised when |
|---|---|---|
| `INVALID_VOICE` | 400 | The resolved voice is not in a loaded catalogue (FR-012) |
| `VOICE_CATALOGUE_UNAVAILABLE` | 503 | Voice listing while the catalogue cannot be fetched or is in back-off (FR-019) |

The new conflict, format and range errors, and Google-only overrides sent to another provider
type, all use the existing `INVALID_REQUEST` (400). An unsupported listing type also uses
`INVALID_REQUEST`. A Google-side rejection stays `PROVIDER_ERROR` (503), now with Google's message.

## Resolution algorithm (Google, `resolveSettings`: pure, no I/O)

Inputs: `RequestedSettings r`, entry config `c` (already format-validated at start-up), the entry's
derived default engine `E` and derived default language `L` (possibly none).

1. `r.engine` set → `GoogleEngine.fromName`, or `INVALID_REQUEST` "unknown engine … supported: …".
2. `r.language` set → `GoogleLanguage.parse`, or `INVALID_REQUEST`.
3. `r.pitch` / `r.speakingRate` set → range check, or `INVALID_REQUEST` stating the range.
4. `text = r.voice ?? c.voice`. `r.voice` malformed → `INVALID_REQUEST`.
5. **No voice anywhere**: if `r.engine` is set → `INVALID_REQUEST` "an engine needs a voice".
   Otherwise `language = r.language ?? L ?? en-US`, `voice = null`. (With no voice anywhere,
   `L` can only come from `c.language`.)
6. **Full name**: `language` = its prefix. `r.language` set and different → `INVALID_REQUEST`
   naming both languages (FR-003). `r.engine` set and different from the name's engine →
   `INVALID_REQUEST` naming both engines. `c.engine` is ignored (the full name wins).
7. **Short name**: `engine = r.engine ?? E`. If `E` is an unrecognized segment and no `r.engine`
   is given → `INVALID_REQUEST` "provider … has no engine that short names can use".
   `language = r.language ?? L`, and neither → `INVALID_REQUEST`. Compose the full name.
8. `pitch = r.pitch ?? c.pitch`, `speakingRate = r.speakingRate ?? c.speakingRate`.
   `pitchKey` = `pitch` unless it equals `0.0`; `speakingRateKey` = `speakingRate` unless it
   equals `1.0` (R7).
9. Return `SynthesisSettings` with the rule-canonical full name.

Steps 6 and 7 apply to a full name wherever it came from. A request `engine` or `language` that
contradicts the *configured* full voice (when the request gives no voice) is rejected the same way.

## Synthesis (Google, `synthesize`: cache miss only)

1. `deadline = now + timeout-seconds`.
2. If `voice != null`: `catalogue.check(voice, min(fetchTimeout, remaining))`.
   - `Found` → send the catalogue's spelling.
   - `Missing` → `INVALID_VOICE`: *"Voice '{requestedVoice}' is not available for {engine} /
     {language}. Available: A, B, …"* (e.g. `Voice 'charn' is not available for Chirp3-HD / uk-UA`) (short names). If there are no alternatives: *"No {engine}
     voices are available for {language}"*.
   - `Unavailable` → send the rule spelling.
3. `POST v1/text:synthesize` with timeout `max(remaining, 1s)`. The body is `voice.languageCode`,
   `voice.name` (if set), and `audioConfig.{audioEncoding, sampleRateHertz, pitch?, speakingRate?}`.
4. Non-2xx → `GoogleErrorBody` message appended (R11).

## Cache Key

`CacheKey(text, providerName, engineName, voice, language, pitch, speakingRate, targetFormat)`.
The hashed string is 001's string, followed by `|p=<pitch>` and `|r=<rate>` **only when non-null**.
The service fills it from `SynthesisSettings` without interpreting anything: `voice ← voiceKey`,
`pitch ← pitchKey`, `speakingRate ← speakingRateKey`. The Google resolver has already nulled the
neutral values 0.0 and 1.0 (R7).

| Request | Key voice / language / p / r |
|---|---|
| `voice: charon`, engine `chirp3-hd`, language `uk-UA` | `uk-ua-chirp3-hd-charon` / `uk-UA` / – / – |
| `voice: uk-UA-Chirp3-HD-Charon` | same → shared entry (US2-4) |
| same + `speakingRate: 1.0` | same → shared entry |
| same + `speakingRate: 0.9` | `… / r=0.9` → a separate entry (US4-4) |
