# Quickstart: Validating the Gemini TTS Provider

**Feature**: `004-gemini-tts-provider` | Settings: [contracts/configuration.md](contracts/configuration.md)
| REST: [contracts/tts-rest-api.yaml](contracts/tts-rest-api.yaml) | Entities: [data-model.md](data-model.md)

## 1. Automated gate (no key, no network, no cost)

```powershell
mvn verify
```

| What is proven | Where |
|---|---|
| Every start-up fault in the configuration contract; each message names the extension, the entry and the fault | `TtsPropertiesValidationTest` |
| Production's exact `google-cloud` entry, and every other existing shape, still validates unchanged | `TtsPropertiesValidationTest` (existing cases) |
| The cost warning is logged once when the effective default (named, or first enabled) is `google-gemini`, and not otherwise | `TtsPropertiesValidationTest` (captured log) |
| A valid `google-gemini` entry starts the context with zero HTTP requests; a bad key file aborts it | `TtsAutoConfigurationTest` + WireMock request count |
| Voice form and normalization (`kore`, `KORE` → `Kore`); model form; language form | `GeminiVoiceTest`, `GeminiModelTest`, `GeminiSettingsResolverTest` |
| Prompt precedence (request → empty turns it off → default), stripping, length limit; `engine`/`pitch` rejected; rate range and neutral key | `GeminiSettingsResolverTest` |
| `stylePrompt` rejected by `google-cloud` and by the 001 types | `GoogleVoiceResolverTest`, `DefaultSettingsResolutionTest` |
| Request body: `modelName`, `name`, `languageCode`, `input.prompt` only when in effect, `speakingRate` only when set, no `pitch`, Bearer and no `?key=` | `GoogleGeminiTtsProviderTest` (WireMock) |
| 401 → one renewal and resend; 403, 400, 429 and timeout → the existing codes with Google's explanation and the entry's name | `GoogleGeminiTtsProviderTest` |
| A 24 kHz WAV reaches the converter as a 24 kHz source | `GoogleGeminiTtsProviderTest` + `AudioConverterTest` |
| The same text with three prompts gives three keys; with no prompt and with an empty prompt, one key; keys without a prompt hash exactly as before | `CacheKeyTest`, `TtsServiceTest` (real provider against WireMock: a hit makes no HTTP call) |
| `google-cloud` is unchanged after the `GoogleTtsClient` extraction | `GoogleCloudTtsProviderTest`, **unmodified** |
| Gemini listing: only bare Gemini names, canonicalized, with the model and gender, sorted by name; `language` validated but not narrowing; `engine` rejected; no fetch at construction or while announcing; remembered and backed off like `google-cloud`'s | `GoogleGeminiTtsProviderTest` (WireMock `GET v1/voices`), `GoogleVoiceCatalogueTest`, `VoiceQueryServiceTest` |
| `google-cloud` listing: same voices, filters and order, plus `gender` | `GoogleVoiceCatalogueTest`, `TtsVoiceControllerTest`; `GoogleCloudTtsProviderTest` **unmodified** |
| A Gemini voice's JSON has no `language` key | `TtsVoiceControllerTest` |
| No private key, assertion or token in any log line or message on the Gemini path | `GoogleGeminiTtsProviderTest` (captured log + messages) |
| No new runtime dependency | `mvn dependency:tree -Dscope=runtime` unchanged; no `com/google/` classes in the JAR |

## 2. Smoke run against a local core (merge gate, part 2)

Prerequisites:

- The `multiroom-ai` checkout, built.
- A real service account key file whose project has the **Agent Platform API** enabled, and a
  service account with the **Agent Platform User** role (see
  [docs/google-cloud-tts-setup.md](../../docs/google-cloud-tts-setup.md)).
- A `multiroom.yml` with a `google-gemini` entry named `gemini`, as in the configuration contract's
  example. Keep a classic entry as the default.

```powershell
mvn deploy -Plocal
# start the core, then:
curl -s http://localhost:8080/actuator/extensions
```

**Expected**: `tts` at `0.1.3`, not `REJECTED`. Nothing is sent to Google during start-up.

## 3. Behavioural checks (local core, real key; each synthesis costs Gemini tokens)

Keep this to a handful of short announcements.

```powershell
curl -s -X POST localhost:8080/api/tts/speak -H "Content-Type: application/json" `
  -d '{"text":"Gemini check.","targetName":"office","targetType":"SINGLE_OUTPUT","providerName":"gemini"}'
```

| Check | Action | Expected |
|---|---|---|
| Story 1: plays | the request above | `202`, `cacheHit: false`, speech plays **at normal speed and pitch** (this confirms the WAV header, research R3) |
| Story 1: cache | the same request again | `202`, `cacheHit: true` |
| Story 1: normalization | the same with `"voice":"KORE"` | `cacheHit: true` if the entry's voice is `Kore` |
| Story 2: prompt | the same text with `"stylePrompt":"Announce this urgently."` | `cacheHit: false`, audibly different delivery |
| Story 2: prompt off | the same text with `"stylePrompt":""` | a separate entry from the default-prompt one; plain delivery |
| Speaking rate | `"speakingRate":1.5` on new text | noticeably faster |
| Story 4: unknown voice | `"voice":"Nosuchvoice"` on new text | `503 PROVIDER_ERROR`, message names `gemini` and quotes Google |
| Story 5: wrong fields | `"pitch":2` to `gemini`; `"stylePrompt":"x"` to the classic entry | `400 INVALID_REQUEST` naming the field and the type |
| Story 6: listing | `GET /api/tts/providers/gemini/voices` | `200`, the Gemini voices (30 on 2026-09-26) with `engine` = the model and a `gender`, no `language`, no classic voices. Free: listing is not billed |
| Story 6: language | the same with `?language=uk-UA` | the same list |
| Story 6: engine | the same with `?engine=chirp3-hd` | `400 INVALID_REQUEST` naming the filter and the type |
| Story 6: classic | `GET /api/tts/providers/<classic>/voices?language=uk-UA` | the same voices as before, each with `gender` |
| Story 6: pick | announce short new text with a listed voice other than the default, e.g. `"voice":"Achird"` | `202`, plays |
| Story 3: API key | add `api-key` to the entry, restart | start-up aborts: Gemini needs a service account key file |
| Story 3: default warning | make `gemini` the `default-provider`, restart | one `WARN` about per-token billing; start-up continues. Revert |
| SC-008 | `grep -E "BEGIN PRIVATE KEY\|ya29\.\|eyJ" <core log>` | no match |

## 4. Production Raspberry Pi

This feature changes what audio is produced, so constitution VII applies. Production keeps its
existing configuration (SC-003):

1. `mvn deploy` (a production action: confirm with the user first). Restarting needs permission.
2. `GET /actuator/extensions` on `multiroom.lan`: `tts` `0.1.3`, not `REJECTED`.
3. One announcement through the existing `google` entry, with no configuration edit: it plays and
   is a **cache hit** if the same text was announced before the upgrade.
4. Always, because the Pi must play the new 24 kHz source once: add a `google-gemini` entry that is
   **not** the default (the configuration edit and the restart need the user's permission), and
   make one short announcement through it. It must play at the right speed and pitch on the Pi's
   output. If the user does not want Gemini on production, remove the entry again afterwards (with
   permission) and restart.
