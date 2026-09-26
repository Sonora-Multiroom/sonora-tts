# Data Model: Gemini TTS Provider

**Feature**: `004-gemini-tts-provider` | **Research**: [research.md](research.md) |
**Settings**: [contracts/configuration.md](contracts/configuration.md)

Nothing here is persisted except through the existing audio cache, whose file format is
unchanged. All other state is held in memory for each entry.

## Gemini Provider Entry (`TtsProviderConfig`, `type: google-gemini`)

| Field | Required | Validation at start-up | Use |
|---|---|---|---|
| `name` | yes | unchanged (unique, non-blank) | Messages, the cache key's `providerName` |
| `service-account-key-file` | **yes** | 003's `ServiceAccountKey.load`, unchanged | The credential |
| `api-key` | **forbidden** | set → fault | — |
| `model` | **yes** | `GeminiModel` form | `voice.modelName`; the cache key's `engineName` |
| `voice` | **yes** | `GeminiVoice` form | Default voice |
| `language` | **yes** | `GoogleLanguage.isWellFormed` | Default language |
| `style-prompt` | no | stripped; blank = none; stripped length ≤ `max-text-length` | Default style prompt |
| `speaking-rate` | no | `GoogleSpeakingRate` range [0.25, 2.0] | Default rate |
| `timeout-seconds` | no (10) | unchanged; the warning above 10 s is unchanged | Budget for token + synthesis |
| `engine`, `pitch`, `extra-params` (non-empty) | **forbidden** | set → fault | — |

New fields on `TtsProviderConfig`: `model` and `stylePrompt`, both `String` with no default. Both
are forbidden on every other type. The configuration holds only the key file's path, never key
material (AGENTS.md).

## Value types (new, `provider.cloud.google`, pure)

| Type | Form | Canonical form | Notes |
|---|---|---|---|
| `GeminiVoice(String name)` | `[A-Za-z]+` | First letter upper-case, the rest lower-case (`Locale.ROOT`) | `parse` / `isWellFormed`, like `GoogleLanguage` |
| `GeminiModel(String name)` | `[a-z0-9][a-z0-9.-]*` | As written | Start-up only; never taken from a request |
| `GoogleSpeakingRate` (static helper) | finite, [0.25, 2.0] | `keyOf(rate)`: `null` when absent or exactly 1.0 | Shared with `GoogleVoiceResolver` (moved there from it) |

## Settings flow (changed records)

| Record | Change |
|---|---|
| `SpeakRequest` (rest) | + `String stylePrompt` |
| `AnnounceCommand` (service) | + `String stylePrompt` |
| `RequestedSettings` (provider) | + `String stylePrompt`. `null` = not given; `""` or whitespace = "no prompt" |
| `SynthesisSettings` (provider) | + `String stylePrompt`: the effective prompt, stripped; `null` = none. `of(…)` still produces `null` |
| `CacheKey` (cache) | + `String stylePrompt`; `toHash()` appends `\|s=<prompt>` only when it is non-null, after `\|p=` / `\|r=` |

`SynthesisSettings` for a Gemini entry:

| Field | Value |
|---|---|
| `voice`, `voiceKey` | The canonical `GeminiVoice` (`Kore`) |
| `requestedVoice` | As written by the request or the configuration |
| `language` | The canonical `GoogleLanguage` tag |
| `engine` | The model |
| `pitch`, `pitchKey` | `null` |
| `speakingRate` | The request's rate, else the entry's, else `null` |
| `speakingRateKey` | `GoogleSpeakingRate.keyOf(speakingRate)` |
| `stylePrompt` | See the algorithm below |

## Resolution algorithm (`GeminiSettingsResolver.resolve`, pure)

Inputs: `RequestedSettings`, the entry's configuration, and `max-text-length`. Any failure is
`INVALID_REQUEST`, naming the field and, for a type mismatch, the provider type.

1. The request sets `engine` or `pitch` → reject: "Field '<f>' is not supported by provider '<n>'
   of type GOOGLE_GEMINI".
2. **Voice**: the request's voice, or else the configured voice. A request voice that fails the
   `GeminiVoice` form is rejected, and the message quotes it as written. The result is
   canonicalized.
3. **Language**: the request's language, or else the configured language. A request language that
   fails the `GoogleLanguage` form is rejected. The result is canonicalized.
4. **Speaking rate**: the request's rate is range-checked (`GoogleSpeakingRate`). The effective rate
   is the request's, or else the entry's.
5. **Style prompt**:
   - `p` = the request's prompt, stripped.
   - If `p` is non-null and longer than `max-text-length`, reject.
   - The effective prompt is `p` if it is non-empty, `null` if `p` is empty, and the entry's
     stripped default (or `null`) if the request gave no prompt.
6. Return `SynthesisSettings` as in the table above.

`GoogleVoiceResolver` and `DefaultSettingsResolution` gain one rule each: a request `stylePrompt`
is rejected as `INVALID_REQUEST` naming the field and the type. Neither changes anything else.

## Synthesis (`GoogleGeminiTtsProvider.synthesize`)

```text
deadline = now + timeout-seconds
token    = client.prepare(remaining)              # 003 token cache: reuse / single-flight / back-off
body     = {input:{text, prompt?}, voice:{languageCode, name, modelName},
            audioConfig:{LINEAR16, sampleRateHertz=targetSampleRate, speakingRate?}}
audio    = client.synthesize(body, deadline)          # calls prepare itself, so it reads a renewed token
             # POST text:synthesize, Bearer; on 401: discard, renew within remaining, resend once
             # 429 → PROVIDER_RATE_LIMITED; ≥400 → PROVIDER_ERROR; timeout → PROVIDER_TIMEOUT
             # each "Provider '<n>' …: <Google's explanation>"
             # 200 → base64-decode audioContent (a WAV; its header gives the real sample rate)
```

There is no catalogue step: the Gemini voice list below is for listing only. The same `GoogleTtsClient` performs `GoogleCloudTtsProvider`'s send,
401 retry, error mapping and decoding (R1). Its order stays: token, then the catalogue check, then
synthesis.

## Gemini voice list (`GoogleVoiceCatalogue`, one per `google-gemini` entry)

Google's `GET v1/voices` answer, reduced to the Gemini voices. Held in memory, never persisted,
never shared between entries, and never fetched at start-up or while announcing.

| Aspect | Rule |
|---|---|
| Source | `GET v1/voices` with no filter, through the entry's `GoogleTtsClient.get` (its own bearer token) |
| Selected | Elements whose `name` passes `GeminiVoice.isWellFormed` (`Kore`); full names (`en-US-Chirp3-HD-Kore`) are skipped |
| Lifetime | `voice-catalogue.ttl`; a failed fetch is held off for `voice-catalogue.failure-backoff`; a fetch gets at most `voice-catalogue.fetch-timeout` |
| Unavailable | `VOICE_CATALOGUE_UNAVAILABLE` (503), as for `google-cloud` |

`CatalogueVoice` for a Gemini voice:

| Field | Value |
|---|---|
| `fullName`, `shortName` | The canonical `GeminiVoice` name (`Kore`) |
| `engine` | The entry's model (`gemini-2.5-flash-tts`) |
| `language` | `null`: Google's `en-US` tag does not say which languages the voice speaks |
| `gender` | **New, both types.** Google's `ssmlGender` (`FEMALE`, `MALE`, …), `null` when absent |

Listing (`GoogleGeminiTtsProvider.listVoices`): `engine` set → `INVALID_REQUEST` naming the filter
and the type; `language` has already passed `VoiceQueryService`'s form check and does not narrow
the list; the answer is every selected voice, sorted by name.

`google-cloud`'s catalogue keeps its selector (full names only), filters and order; its voices
gain `gender`.

## Token state

Unchanged from 003: `GoogleAccessTokenCache`, one per entry, with the states empty → valid →
renewing → valid, and back-off after an *unavailable* failure. Its state machine is in
[003's data model](../../.specify/archive/003-google-service-account-auth/data-model.md).

## Start-up output

| Situation | Output |
|---|---|
| Any fault in the entry table above | `IllegalStateException("multiroom-tts: provider '<n>' …")`: start-up aborts |
| The effective default provider is `google-gemini` | One `WARN` naming the entry: announcements without a `providerName` are billed per token |
| `timeout-seconds` > 10 | The existing `WARN`, unchanged |
