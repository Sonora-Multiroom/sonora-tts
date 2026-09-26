# Contract: Configuration Surface (`google-gemini`)

**Feature**: `004-gemini-tts-provider` | Binds under `multiroom.tts` in the host's `multiroom.yml`.
Everything in [002's contract](../../002-google-voice-selection/contracts/configuration.md)
and [003's contract](../../003-google-service-account-auth/contracts/configuration.md)
still holds for the other types. This document adds one type and two settings.

## Provider entry: `multiroom.tts.providers[n]`, `type: google-gemini`

| Key | Type | Default | Meaning |
|---|---|---|---|
| `service-account-key-file` | path | — | **Required.** Exactly as for `google-cloud` (003): read once at start-up, relative to the working directory |
| `model` | string | — | **Required. New.** The Gemini-TTS model, for example `gemini-2.5-flash-tts`. Lower-case letters, digits, dots and hyphens. Not overridable per request |
| `voice` | string | — | **Required.** A Gemini voice name, for example `Kore`: one word of ASCII letters, any case (normalized to `Kore`) |
| `language` | string | — | **Required.** A language-region tag, for example `uk-UA`. Google refuses a Gemini request without one |
| `style-prompt` | string | none | **New.** The default style prompt, for example `Say this calmly and warmly.` Leading and trailing whitespace is removed. At most `max-text-length` characters |
| `speaking-rate` | number | not sent | [0.25, 2.0], where 1.0 is natural speed. The same rules as for `google-cloud` |
| `timeout-seconds` | int | `10` | Covers obtaining a token and synthesizing. Raise it for a slower model or longer texts |
| `enabled` | bool | `true` | Unchanged |

### Example

```yaml
multiroom:
  tts:
    default-provider: google           # a classic entry stays the default (see the warning below)
    providers:
      - name: google
        type: google-cloud
        api-key: ${GOOGLE_TTS_API_KEY}
        voice: en-US-Neural2-C
      - name: gemini
        type: google-gemini
        service-account-key-file: /home/tiger/.config/multiroom/sonora-tts-sa.json
        model: gemini-2.5-flash-tts
        voice: Kore
        language: en-US
        style-prompt: >-
          Say this calmly and warmly, like a friendly household announcement.
        speaking-rate: 1.05
```

### Start-up faults

Each aborts host start-up with `multiroom-tts: provider '<name>' …`. None contacts Google.

| Condition | The message says |
|---|---|
| `google-gemini` with `api-key` (with or without a key file) | Gemini voices require a service account key file and cannot use an API key |
| `google-gemini` without `service-account-key-file` | service-account-key-file is required |
| `model`, `voice` or `language` missing | the missing key |
| `model`, `voice` or `language` malformed | the key and the value |
| `engine`, `pitch` or a non-empty `extra-params` on `google-gemini` | the key, which google-gemini does not support |
| `speaking-rate` outside [0.25, 2.0], or NaN | the key, the value and the range |
| `style-prompt` longer than `max-text-length` after trimming | the entry, the length and the limit |
| `style-prompt` or `model` on any other type | the key, and that only google-gemini supports it |
| `speaking-rate` or `service-account-key-file` on `openai`, `piper` or `local-http` | unchanged, except that the message now names google-cloud and google-gemini |
| Any key-file fault (missing, unreadable, not a service account key, unusable key, bad `token_uri`) | exactly as for `google-cloud` (003) |

### Start-up warning

When the effective default provider is a `google-gemini` entry, start-up logs one `WARN` and
continues. The effective default is `default-provider` if it is set, or else the first **enabled**
entry.

```text
multiroom-tts: provider 'gemini' (google-gemini) is the default provider; every announcement
without a providerName is synthesized by Gemini and billed per token
```

## Run-time behaviour visible to the operator

| Situation | Caller receives |
|---|---|
| Missing Agent Platform API, missing permission, unknown model, unknown voice, unsupported language, text or prompt too long for the model | `PROVIDER_ERROR` 503, "Provider '<n>' returned HTTP <s>: <Google's explanation>" |
| Google rate limit | `PROVIDER_RATE_LIMITED`, with Google's explanation |
| No answer within `timeout-seconds` (token + synthesis) | `PROVIDER_TIMEOUT` |
| Token service unavailable or the key rejected | Exactly as for a service-account `google-cloud` entry (003), including the back-off from `voice-catalogue.failure-backoff` |
| `GET /api/tts/providers/<n>/voices` | The Gemini voices in Google's voice list, each with the entry's model and its gender; fetched on first need and remembered under the existing `voice-catalogue` settings, which now apply to `google-gemini` entries too |
| The same with `engine=…` | `400 INVALID_REQUEST`, "Filter 'engine' is not supported by provider '<n>' of type GOOGLE_GEMINI" |
| Google's voice list cannot be fetched | `503 VOICE_CATALOGUE_UNAVAILABLE`, as for `google-cloud` |

No private key, signed assertion or access token appears in any message or log line.

## Unchanged

- Every existing `google-cloud`, `openai`, `piper` and `local-http` entry starts with no edit.
- Every existing cache entry stays valid: the key gains a component only when a style prompt is in
  effect.
- There is no new `voice-catalogue` setting (the existing ones now also govern a `google-gemini`
  entry's voice list), no spending cap and no configurable endpoint.
