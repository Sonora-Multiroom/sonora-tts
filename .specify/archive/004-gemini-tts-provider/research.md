# Research: Gemini TTS Provider

**Feature**: `004-gemini-tts-provider` | **Date**: 2026-09-25 | **Spec**: [spec.md](spec.md)

The behaviour was settled in the spec's Clarifications, three points of it by live tests. The
decisions below are the technical ones the spec left to planning. Each gives the decision, the
reason for it, and what was rejected.

## R1. A separate provider class, with the shared Google plumbing extracted

**Decision**: A new `GoogleGeminiTtsProvider` in `provider.cloud`, beside
`GoogleCloudTtsProvider`. What both need moves out of `GoogleCloudTtsProvider` into a new
`GoogleTtsClient` in `provider.cloud.google`:

- the `HttpClient`, built with the entry's timeout
- the entry's `GoogleCredential`
- the authorised send with 003's single renewal and resend after a 401
- turning a 429 into `PROVIDER_RATE_LIMITED`, any other status of 400 or above into
  `PROVIDER_ERROR`, and a timeout into `PROVIDER_TIMEOUT`, each with Google's explanation from
  `GoogleErrorBody`
- decoding `audioContent`

`GoogleCloudTtsProvider` keeps what only it needs: the voice resolver, the catalogue and the
catalogue check, and the order "token, catalogue, synthesis". Its request body is unchanged.

**Rationale**: The spec requires that a fix to the shared behaviour reaches both types. Two copies
of the 401 retry would drift. Pulling a client out, rather than making the Gemini provider a
subclass, keeps `GoogleCloudTtsProvider`'s catalogue out of the Gemini path. The existing
`GoogleCloudTtsProviderTest` stays unchanged and serves as the regression gate for the extraction.

**Alternatives rejected**:

- *A `google-cloud` subclass*: it would inherit the catalogue and `VoiceCatalogueProvider`, and
  would then have to switch both off again.
- *Copying the send and error code*: this breaks the requirement to keep the shared behaviour
  identical.
- *A generic "Google provider" with a strategy for the body*: two implementations do not justify
  that abstraction yet.

## R2. What is sent to Google

**Decision**: `POST https://texttospeech.googleapis.com/v1/text:synthesize` with
`Authorization: Bearer`:

```json
{
  "input":  { "text": "…", "prompt": "…" },
  "voice":  { "languageCode": "uk-UA", "name": "Kore", "modelName": "gemini-2.5-flash-tts" },
  "audioConfig": { "audioEncoding": "LINEAR16", "sampleRateHertz": 48000, "speakingRate": 1.2 }
}
```

`prompt` is sent only when there is an effective style prompt, and `speakingRate` only when one is
set. `pitch` is never sent. `audioConfig` otherwise matches a `google-cloud` request. The field is
spelled `modelName`, as in the live tests of 2026-09-24/25: Google's JSON mapping also accepts
`model_name`.

**Rationale**: This is the body the live tests used, and it keeps the style prompt out of the text
(Clarifications, route A).

## R3. Audio at a sample rate Google chooses

**Decision**: No special handling. `AudioConverter` takes the source format from the WAV header of
`audioContent` (`AudioSystem.getAudioInputStream`), never from `SynthesisResult.sampleRate`. The
shared `FormatConverter` then resamples to the native 48 kHz. Whatever rate Gemini returns
(Gemini's native rate is 24 kHz, as the 2026-09-24 test on the other route showed), the announcement
plays at the right speed and pitch.

A test pins this down: a 24 kHz WAV returned by WireMock must reach the `FormatConverter` as a
24 kHz source. The smoke run confirms that Google's LINEAR16 `audioContent` carries a WAV header
for Gemini as it does for classic voices. If it did not, the result would be
`FORMAT_NORMALIZATION_FAILED` on the first announcement, never audio at the wrong speed.

**Alternatives rejected**: omitting `sampleRateHertz` for Gemini. Sending it costs nothing when it
is honoured and is harmless when it is ignored, and one `audioConfig` shape for both types is
simpler.

## R4. Setting names

**Decision**:

| Where | Name | Meaning |
|---|---|---|
| Configuration | `model` | The Gemini model; `google-gemini` only |
| Configuration | `style-prompt` | The default style prompt; `google-gemini` only |
| Request (`POST /api/tts/speak`) | `stylePrompt` | Per-announcement style prompt; `""` turns the default off |

`voice`, `language`, `speaking-rate`, `service-account-key-file` and `timeout-seconds` keep their
existing names and meanings.

**Rationale**:

- `model` is Google's own term (`modelName`). `engine` already means a classic voice family on
  `google-cloud`, and the spec requires `engine` to be rejected on a Gemini entry.
- `stylePrompt` rather than `prompt`: next to `text`, a bare `prompt` reads as "the text to
  speak".
- The request's field is not called `model`, because the model cannot be overridden per request.

**Alternatives rejected**: reusing `engine` for the model (OpenAI does so). On a Google entry that
would make `engine` mean two different things.

## R5. Where the new values travel, and the cache key

**Decision**:

- `RequestedSettings` and `AnnounceCommand` gain `stylePrompt`.
- `SynthesisSettings` gains `stylePrompt`: the effective prompt, already trimmed, and `null` when
  there is none. Because it is already normalized, it is its own cache-key form, so it gets no
  separate `*Key` field.
- The model travels in `SynthesisSettings.engine`, the field OpenAI already uses for its model.
  `TtsService` copies that field into `CacheKey.engineName` unchanged, so the model needs no new
  cache field.
- `CacheKey` gains `stylePrompt`. `toHash()` appends `|s=<prompt>` **only when it is set**, after
  pitch and rate, exactly the way 002 added `|p=` and `|r=`.

**Rationale**:

- Every key without a prompt hashes exactly as today. That covers every existing entry of every
  type, and every Gemini request with no prompt. This keeps existing cache entries valid.
- "No prompt" and "empty prompt" both resolve to `null`, so they share one identity.
- The prompt is the last field, and the format tag before it is fixed digits, so a prompted key
  cannot hash like an unprompted one.
- `TtsService` still interprets nothing: it copies the fields.

**Alternatives rejected**: folding the prompt into `voiceKey`. That would hide a separate
dimension inside another one and make the key harder to reason about.

## R6. Gemini voice names

**Decision**: A new `GeminiVoice` value type. Its form is one word of ASCII letters
(`[A-Za-z]+`), and its canonical spelling is the first letter upper-case and the rest lower-case,
both in `Locale.ROOT`: `kore` and `KORE` become `Kore`. The canonical name is what Google receives
and what the cache key uses, with no further case-folding. `requestedVoice` keeps the caller's
spelling for error messages.

**Rationale**: This follows the spec's normalization clarification. Letting only letters through
makes the case mapping locale-independent: no Turkish dotless i, and no length change.

## R7. Model names

**Decision**: A new `GeminiModel` value type with the form `[a-z0-9][a-z0-9.-]*`: one token of
lower-case letters, digits, dots and hyphens, starting with a letter or a digit. It is validated at
start-up only, because a request cannot set it, and it is never changed or case-folded. There is no
list of known models.

**Rationale**: The spec requires that model names are not a closed list. Start-up rejects an
upper-case or blank model, so what Google receives is exactly what the operator wrote.

## R8. Style prompt handling

**Decision**:

- Trimming uses `String.strip()`, which removes Unicode whitespace at both ends; `trim()` removes
  only characters up to U+0020.
- The effective prompt is decided by `GeminiSettingsResolver`, the Gemini counterpart of
  `GoogleVoiceResolver`:
  1. a request prompt that is non-empty after stripping, or else
  2. none, if the request gave a prompt that is empty after stripping, or else
  3. the entry's stripped default prompt, if it has one.
- The length limit is `max-text-length`, counted with `String.length()` as the text is, and applied
  after stripping:
  - to the request's prompt in the resolver, which receives `max-text-length` when it is built, so
    a caller error is reported before any cache lookup or synthesis;
  - to the entry's default in `TtsProperties.validate()`.
- A default prompt that is blank after stripping counts as no default.

**Rationale**: The resolver runs on every request and must stay free of I/O. Stripping and a length
check are pure. `TtsService` stays unaware of prompts except for copying one field.

**Alternatives rejected**: checking the prompt's length in `TtsService` next to the text check.
The service would then have to know which provider types take a prompt.

## R9. Speaking rate: the same rules as `google-cloud`

**Decision**: The range `[0.25, 2.0]`, the rejection of NaN, and the rule that the neutral 1.0 is
left out of the cache key move from `GoogleVoiceResolver` into a small shared helper,
`GoogleSpeakingRate`. Both resolvers and `TtsProperties` call it.

**Rationale**: The spec requires 002's rules unchanged. One implementation cannot drift, and the
pitch constants stay in `GoogleVoiceResolver`, the only place that uses them.

## R10. Start-up validation

**Decision**: `ProviderType` gains `GOOGLE_GEMINI`. `type: google-gemini` binds through Spring's
relaxed enum binding, as `google-cloud` binds to `GOOGLE_CLOUD`. `TtsProperties.validateProvider`
gains a `GOOGLE_GEMINI` branch:

1. `api-key` set → fault: Gemini voices need a service account key file and cannot use an API key
   (checked first, so an entry with both gets this message).
2. `service-account-key-file` missing → fault: it is required.
3. `model`, `voice` or `language` missing → fault naming the setting. Malformed → fault naming the
   setting and the value (R6, R7, and `GoogleLanguage.isWellFormed`).
4. `engine`, `pitch` or a non-empty `extra-params` → fault naming the setting.
5. `speaking-rate` out of range → fault (R9).
6. `style-prompt` longer than `max-text-length` after stripping → fault naming the entry.

Rules for the other types:

- `style-prompt` or `model` on any type other than `google-gemini` → fault. The spec requires this
  for the prompt; `model` follows the same never-accept-and-ignore rule, since no other type reads
  it.
- The existing "`google-cloud` only" checks on non-`google-cloud` types are relaxed:
  `speaking-rate` and `service-account-key-file` are allowed on `google-gemini` as well, and the
  message names both types. `pitch` stays `google-cloud` only.

The key file itself is still read in `TtsAutoConfiguration` (003's decision), by the same
`ServiceAccountKey.load`, so every 003 fault applies unchanged.

## R11. The default-provider cost warning

**Decision**: At the end of `TtsProperties.validate()`, work out the effective default by the same
rule `ProviderRegistry` applies: the named `default-provider`, or else the **first enabled** entry.
If it is `google-gemini`, log one `WARN`:
`multiroom-tts: provider '<name>' (google-gemini) is the default provider; every announcement
without a providerName is synthesized by Gemini and billed per token`.

**Rationale**: Validation runs once, before the registry exists, and already knows which entries
are enabled. The warning gives no reason to fail start-up.

## R12. Credential, token cache and time budget

**Decision**:

- A Gemini entry always holds a `GoogleCredential.ServiceAccount` with its **own**
  `GoogleAccessTokenCache`. Two entries naming one key file still get separate caches, as in 003.
- The back-off is `voice-catalogue.failure-backoff`, 003's shared setting. There is no new
  setting.
- `synthesize` sets its deadline to `timeout-seconds` from its start. The token is obtained within
  that budget, then the request is sent with what remains, floored at 1 s as for `google-cloud`.
- There is no catalogue step, so no `fetch-timeout` share is taken out of the budget.

**Rationale**: The spec requires that the time limit covers the token as well as the synthesis,
with the same default. The shared client owns the floor, so both types keep it.

## R13. Voice listing

*Revised 2026-09-26.* The first decision was that `GoogleGeminiTtsProvider` does not implement
`VoiceCatalogueProvider`, on the belief that Google publishes no Gemini voice list. A live
`GET v1/voices` disproved it: Google lists 30 Gemini voices there as bare names (spec
Clarifications, 2026-09-26).

**Decision**:

- `GoogleGeminiTtsProvider` implements `VoiceCatalogueProvider`, answered from its own
  `GoogleVoiceCatalogue`: one per entry, fetched with its own token through
  `GoogleTtsClient.fetchVoices`, which moves there out of `GoogleCloudTtsProvider.fetchCatalogue`
  so both types share one fetcher (R1), under the shared `voice-catalogue` settings (`ttl`, `failure-backoff`,
  `fetch-timeout`). Nothing is fetched at construction.
- `GoogleVoiceCatalogue` gains a **voice selector**, a function from one JSON `voices[]` element to
  an optional `CatalogueVoice`. Its existing constructor keeps today's selector (full names only),
  so `google-cloud` is unchanged. The Gemini entry's selector keeps names that pass
  `GeminiVoice.isWellFormed`, canonicalized by `GeminiVoice.parse`, with `engine` = the entry's
  model and `language` = `null`.
- `CatalogueVoice` gains `gender`, from `ssmlGender` (`null` when absent), for both types. Its
  four-component constructor stays as a secondary one passing `null`.
- The listing order handles a `null` language (`Comparator.nullsFirst`), so Gemini voices sort by
  name.
- `GoogleGeminiTtsProvider.listVoices(language, engine)` rejects an `engine` filter with
  `INVALID_REQUEST` "Filter 'engine' is not supported by provider '<n>' of type GOOGLE_GEMINI", and
  lists with no filter. `VoiceQueryService` has already rejected a malformed `language` before the
  provider is asked, so a well-formed one needs no handling there.
- The synthesis path never touches the catalogue: no voice check, no fetch (spec FR-015, FR-035).
- `VoiceDescriptor` gains `gender`, and a `null` field is left out of the JSON
  (`@JsonInclude(NON_NULL)`), so a Gemini voice has no `language` key rather than `"language": null`.

**Rationale**:

- The catalogue already does the hard part (single-flight fetch, `ttl`, back-off, a warm path with
  no lock), and it already sees the Gemini voices: `CatalogueVoice.fromName` drops them today. A
  selector reuses all of it, and the existing tests keep covering it.
- A per-entry catalogue follows 002's rule that entries never share state, and uses the entry's
  own credential, so a Gemini entry needs no `google-cloud` entry beside it.
- The model as the engine tells the caller what the entry would synthesize with. Google's `en-US`
  tag would suggest the voice speaks only English, so no language is shown, and the language
  filter does not narrow the list.

**Alternatives rejected**:

- *A separate `GeminiVoiceCatalogue`*: it would copy the fetch, `ttl` and back-off logic.
- *A voice check on announcing*: the list names no model (spec Clarifications, 2026-09-26).
- *Filtering on Google's `languageCodes`*: `uk-UA` would list nothing although every Gemini voice
  speaks it.
- *Sharing one catalogue between a Gemini and a `google-cloud` entry*: entries share no state, and
  the two keep different voices.

## R14. Versions

**Decision**: The extension goes from `0.1.2` to `0.1.3`, and so does the REST contract, which
tracks the JAR. There is no upstream change: `multiroom.require_api_version` stays `0.1.18`.

**Rationale**: The project prefers small increments sized by the value a feature adds.

## R15. Testing approach

**Decision**:

- WireMock plays both Google's token endpoint and `text:synthesize`, reached through a loopback
  `token_uri`, as in 003. `TestServiceAccountKeys` generates each key pair, and no key material is
  committed.
- A 24 kHz WAV fixture covers R3.
- WireMock also plays `GET v1/voices` for the Gemini listing, with a fixture mixing full names and
  bare Gemini names in the shape the 2026-09-26 live call returned.
- The existing `GoogleCloudTtsProviderTest` must pass **unmodified** after the `GoogleTtsClient`
  extraction.
- A captured-log test covers the Gemini path for secrets, as 003's does for classic voices.
- There is no live Gemini call in any test (constitution III). Live checks are in
  [quickstart.md](quickstart.md).

## Rejected wholesale

- **Route B** (Agent Platform `generateContent` with a bound API key). Decided in Clarifications.
- **A Gemini engine inside `google-cloud`**. Decided in Clarifications.
- **A built-in list of Gemini voices or models**, for voice listing or for checks. Google changes
  both without notice. Listing reads Google's live list instead (R13).
- **Mapping `extra-params` onto Gemini fields.** Whether a setting took effect would then depend on
  how its key was spelled. 002 rejected the same idea for `google-cloud`.
- **A spending cap.** Out of scope (Clarifications). `docs/future/google-cloud-free-tier-tracking.md`
  holds the planned budget.
