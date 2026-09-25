# Contract: Configuration Surface (`google-cloud` voice selection)

**Feature**: `002-google-voice-selection` | Binds under `multiroom.tts` in the host's `multiroom.yml`.
All defaults are `@ConfigurationProperties` field initialisers; the JAR ships no `application.yml`.

## Provider entry: `multiroom.tts.providers[n]`, `type: google-cloud`

| Key | Type | Default | Meaning |
|---|---|---|---|
| `api-key` | string | — | Required (unchanged) |
| `voice` | string | none | A full name (`uk-UA-Chirp3-HD-Charon`) or a short name (`charon`), case-insensitive |
| `engine` | string | none | Default engine for short names: `standard`, `wavenet`, `neural2`, `studio`, `chirp-hd`, `chirp3-hd` (case- and `-`/`_`-insensitive). **Required unless `voice` is a full name** |
| `language` | string | **none** (was `en-US`) | Language-region tag (`uk-UA`, `cmn-CN`, `es-419`), case-insensitive. The default language for short names; if absent, a full `voice`'s own language is the default. Required when `voice` is short. A full `voice` is always synthesized in its own language |
| `pitch` | number | none (not sent) | Semitones, **[-20.0, 20.0]**. Not every engine supports it: Google documents no pitch control for `Chirp3-HD`. The value is sent as given, and Google's rejection, if any, is reported |
| `speaking-rate` | number | none (not sent) | **[0.25, 2.0]**, where 1.0 is the voice's natural speed |
| `timeout-seconds` | int | 10 | The whole budget for one synthesis, including a voice-catalogue fetch |
| `extra-params` | map | empty | **Must be empty** for `google-cloud`. Use `pitch` and `speaking-rate` |

### Accepted shapes

```yaml
# 1. Pre-002 (production today): a full name. The default engine comes from it (Neural2).
- name: google
  type: GOOGLE_CLOUD
  api-key: ${GOOGLE_TTS_API_KEY}
  voice: en-US-Neural2-C
  language: en-US            # optional; agrees with the voice

# 2. Structured
- name: google-uk
  type: google-cloud
  api-key: ${GOOGLE_TTS_API_KEY}
  engine: chirp3-hd
  language: uk-UA
  voice: charon              # resolves to uk-UA-Chirp3-HD-Charon
  speaking-rate: 1.1         # Chirp3-HD supports pace; it has no documented pitch control

# 2b. Pitch on an engine that supports it
- name: google-neural
  type: google-cloud
  api-key: ${GOOGLE_TTS_API_KEY}
  voice: en-US-Neural2-C
  pitch: -2

# 3. Engine and language only: callers pick the voice; with no voice Google chooses one
- name: google-wavenet
  type: google-cloud
  api-key: ${GOOGLE_TTS_API_KEY}
  engine: wavenet
  language: en-US
```

### Start-up faults

Each of these aborts host start-up with `multiroom-tts: provider '<name>' …`. None contacts Google.

| Condition | Message names |
|---|---|
| `engine` not recognized | the value and the supported engines |
| `language` not a language-region tag | the value |
| `voice` neither a full name nor a short name | the value |
| no `engine` and `voice` is not a full name (short or absent) | "an engine is required" |
| short `voice` and no `language` | "a language is required for short voice '<v>'" |
| `pitch` / `speaking-rate` out of range | the value and the range |
| `extra-params` not empty | the keys, and `pitch`/`speaking-rate` as the replacement |

**Warning only**: a `language` that differs from a full `voice`'s own language. The configured
voice is synthesized in its own language, and `language` remains the default for short names in
requests.

**Compatibility note**: before 002, `engine` on a `google-cloud` entry was a free-text cache label.
It is now validated. An entry with an unrecognized label fails start-up until the label is removed
or corrected.

## Other provider types

| Key | Rule |
|---|---|
| `pitch`, `speaking-rate` | Start-up fault if set: never accepted and ignored (SC-008) |
| `language` | No declared default. Resolution falls back to `en-US` exactly as before |
| `engine`, `voice`, `extra-params` | Unchanged from 001 |

## Voice catalogue: `multiroom.tts.voice-catalogue`

These apply to each `google-cloud` entry's own catalogue. Catalogues are never shared between
entries.

| Key | Type | Default | Meaning |
|---|---|---|---|
| `ttl` | duration | `24h` | How long a fetched catalogue is trusted |
| `failure-backoff` | duration | `60s` | After a failed fetch, no new fetch for this long. Also the minimum age before a miss triggers a refetch |
| `fetch-timeout` | duration | `3s` | Cap on one catalogue fetch, within the entry's `timeout-seconds` |

Each must be positive, or start-up is aborted.
