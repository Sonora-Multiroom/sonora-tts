# Setting up Google Cloud Text-to-Speech

A `google-cloud` entry authenticates with **exactly one** of two credentials:

- **Option A: an API key** (`api-key`), sent on the query string (`?key=...`). Steps 4 and 5.
- **Option B: a service account key** (`service-account-key-file`), the JSON file Google issues
  for a service account. The entry exchanges it for a short-lived access token and sends
  `Authorization: Bearer ...` instead. See [Option B](#option-b-a-service-account-key).

Steps 1 to 3 are the same for both. See [configuration.md](configuration.md#google-cloud) for the
extension-side YAML.

## 1. Create or select a project

[console.cloud.google.com](https://console.cloud.google.com/) → project picker in the top bar →
**New Project** if you don't have one already.

## 2. Enable billing

☰ (hamburger menu, top-left) → **Billing** → **Create account** (or link an existing one) and add
a payment method.
The Text-to-Speech API refuses to enable without this, even for free-tier usage. Per
[cloud.google.com/text-to-speech/pricing](https://cloud.google.com/text-to-speech/pricing), the
free tier is per model category and per voice type, not a blanket allowance — and it only applies
to the voice types this extension actually uses (see [Voices](#voices) below):

- **Legacy TTS models** (`WaveNet`, `Studio`, `Standard`, `Neural2`): **WaveNet** and **Standard**
  get the first 4 million characters/month free; **Studio** and **Neural2** get the first 1
  million/month free.
- **Latest TTS models** (`Chirp3-HD`): first 1 million characters/month free.

Priced per 1 million characters beyond each free allowance. **Gemini-TTS has no free tier at
all** — irrelevant here since `GoogleCloudTtsProvider` doesn't call it, but worth knowing if
you're comparing prices on the pricing page.

## 3. Enable the API

Direct link: [console.cloud.google.com/marketplace/product/google/texttospeech.googleapis.com](https://console.cloud.google.com/marketplace/product/google/texttospeech.googleapis.com)
→ **Enable** (make sure the right project is selected in the top bar first).

Or navigate manually: search bar at the top → "Cloud Text-to-Speech API" → open it → **Enable**.

## 4. Option A: create an API key

Do this from the *general* Credentials page, not the API's own details page — that page only
offers **OAuth client ID** and **Service account**, no plain API key option.

1. ☰ (hamburger menu, top-left) → **APIs & Services** → **Credentials**
2. **+ Create credentials** → **API key**
3. When prompted for "APIs that can be accessed using this key", restrict it to **Cloud
   Text-to-Speech API**. Do **not** select **Agent Platform API** in the same key: selecting it
   turns the key into one bound to a service account and disables every other API in the list.
   The Text-to-Speech endpoint then rejects the key (`401` *"API keys are not supported by this
   API"*). Agent Platform matters only for Gemini voices (see [Voices](#voices)).
4. Copy the generated key

## 5. Option A: use it in config

```yaml
multiroom:
  tts:
    providers:
      - name: google-cloud
        type: GOOGLE_CLOUD
        api-key: ${GOOGLE_TTS_API_KEY}
        voice: en-US-Neural2-C
        language: en-US
        engine: neural2    # optional here: the default engine for short voice names
```

`engine` is the entry's **default engine for short voice names** (`c` becomes
`en-US-Neural2-C`). It must be one of `standard`, `wavenet`, `neural2`, `studio`, `chirp-hd` or
`chirp3-hd`, and an unrecognized value aborts start-up. It may be left out when `voice` is a full
name, as above: the engine is then taken from the voice. Before 0.1.1 it was a free-text cache
label, so an entry with any other label now needs it removed or corrected. See
[configuration.md](configuration.md#google-cloud) for the other accepted shapes, `pitch` and
`speaking-rate`.

## Option B: a service account key

Use this when your organisation forbids long-lived API keys, when you want IAM to scope and revoke
the credential, or when you already have a service account's JSON key.

1. **Create the service account in the project where the Text-to-Speech API is enabled** (step
   3): ☰ → **IAM & Admin** → **Service accounts** → **+ Create service account**. Give it a name
   such as `sonora-tts`.
2. **Roles**: none. A service account in the project where the API is enabled can synthesize
   classic voices without any role (confirmed 2026-09). Only if an announcement fails with
   `HTTP 403` and Google's explanation names `serviceusage.services.use`, for example because the
   account lives in a different project, grant it **Service Usage Consumer**
   (`roles/serviceusage.serviceUsageConsumer`) on the API's project. The **Agent Platform User**
   role is only for Gemini voices and is not needed here.
3. **Create and download a JSON key**: open the account → **Keys** → **Add key** → **Create new
   key** → **JSON**. The browser downloads a file with `"type": "service_account"`,
   `client_email` and `private_key`. Google shows this key once; keep the file.
4. **Put it on the host** outside any web root, readable by the user the host runs as, and by no
   one else if you can (`chmod 600`). The extension does not check the file's permissions.
5. **Point the entry at it**, instead of `api-key`:

   ```yaml
   multiroom:
     tts:
       providers:
         - name: google-cloud
           type: GOOGLE_CLOUD
           service-account-key-file: /home/tiger/.config/multiroom/sonora-tts-sa.json
           voice: en-US-Neural2-C
   ```

Things to know:

- **Exactly one of `api-key` and `service-account-key-file`.** Both, or neither, aborts start-up.
- **Use an absolute path.** A relative path resolves against the host's working directory, which
  under systemd is usually `/`. If no file is there, start-up aborts and the message shows the
  absolute path it tried. `~` is not expanded.
- **The file is read once, at start-up.** It is checked then (a service account key, with an
  identity and a usable private key), and a broken file aborts start-up naming the entry and the
  path. Start-up makes no request to Google: the first token is fetched on the first announcement,
  voice check or voice listing.
- **Rotating the key needs a restart.** Replace the file, then restart the host. Until then the
  entry keeps using the key it read at start-up.
- **Token failures explain themselves.** A revoked key or a disabled account fails the
  announcement with Google's own explanation, for example
  `Provider 'google-cloud' could not obtain an access token: Google rejected the service account key (HTTP 400): Invalid JWT Signature. (invalid_grant)`.
  Fix the cause on Google's side; the next announcement tries again with no restart. An
  unreachable token service is held off for `voice-catalogue.failure-backoff` (default 60 s).
- **An organisation policy that blocks key creation** (`iam.disableServiceAccountKeyCreation`)
  means you cannot download a key, and this option cannot help. Use an API key (Option A) instead.

## Voices

The pricing page groups voices into three categories. **The first two work with this extension
today. The third does not:**

- **Legacy TTS models**: `WaveNet`, `Studio`, `Standard`, `Neural2`.
- **Latest TTS models**: `Chirp3-HD` — "powered by our cutting-edge LLMs", Google's own
  highest-quality, most natural-sounding option; its own free tier (first 1 million
  characters/month, same allowance as WaveNet — see [above](#2-enable-billing)).
- **Gemini-TTS models** (for example `gemini-2.5-flash-tts` or `gemini-3.1-flash-tts-preview`,
  token-priced, **no free tier**). Reached through a separate provider type, `google-gemini` — see
  [Gemini voices](#gemini-voices-google-gemini) below.

Within Legacy + Latest (what this extension can actually use): `Chirp3-HD` and `Neural2` sound
most natural; `Standard`/`WaveNet` are cheaper; `Studio` is the most expensive.

**SSML support differs by family**: per
[docs.cloud.google.com/text-to-speech/docs/list-voices-and-types](https://docs.cloud.google.com/text-to-speech/docs/list-voices-and-types),
`Neural2`/`WaveNet`/`Standard`/`Studio` support
[SSML](https://docs.cloud.google.com/text-to-speech/docs/ssml); `Chirp3-HD` does not. Not relevant
today — `GoogleCloudTtsProvider` only ever populates `input.text`, never
`input.ssml` — but matters if SSML input is ever added: it would need to be conditional on the
selected voice family, unavailable for `Chirp3-HD`.

### `voice.name` encodes the language and family in the string itself

Example: `uk-UA-Chirp3-HD-Achernar` = language (`uk-UA`) + voice family (`Chirp3-HD`) + a
per-voice identifier (`Achernar`). This is what `voice` in this extension's config maps directly
to `voice.name` — the family is baked into that one string, not a separate parameter. Sample the
options at [cloud.google.com/text-to-speech/docs/voices](https://cloud.google.com/text-to-speech/docs/voices)
by filtering on the family prefix for your language, e.g. `uk-UA-Chirp3-HD-*` or `en-US-Neural2-*`.

Full voice list: [cloud.google.com/text-to-speech/docs/voices](https://cloud.google.com/text-to-speech/docs/voices) —
filter by name prefix (`en-US-Chirp3-HD-*`, `en-US-Neural2-*`, etc.), not by the Gemini model names.
Or ask the extension, which lists exactly the voices an entry accepts:

```http
GET /api/tts/providers/google/voices?language=uk-UA&engine=chirp3-hd
```

Each result gives the `shortName` to use with an engine and a language, the `fullName` to use
on its own, and the voice's `gender`.

**The language comes from the voice.** Google rejects a request whose `languageCode` differs from
the voice's own language, so the extension takes the language from a full voice name:
`uk-UA-Chirp3-HD-Charon` is sent as `uk-UA` without any `language` setting. A request `language`
that contradicts the voice is a `400 INVALID_REQUEST` before anything is sent. A short name such as
`charon` is completed with the request's language, or else the entry's default language (its
`language` setting, or else the language of its configured full voice). When Google does reject a
request, its own explanation follows the status, e.g.
`Provider 'google' returned HTTP 400: Requested language code 'en-US' doesn't match …`.

## Gemini voices (`google-gemini`)

Gemini-TTS voices (`Kore`, `Charon`, …) are a **separate provider type**, `google-gemini`, not an
engine of `google-cloud`. They synthesize through Google's Text-to-Speech `v1` `text:synthesize`
endpoint, the same one `google-cloud` uses, but only from a caller authenticated as a **service
account** — an API key never works for them.

### Set-up, beyond the classic-voice steps above

1. Everything through [step 3](#3-enable-the-api) (project, billing, the Text-to-Speech API) is
   shared with classic voices.
2. Also enable the **Agent Platform API** (`aiplatform.googleapis.com`, formerly Vertex AI API):
   search bar → "Agent Platform API" → **Enable**.
3. Create a service account key exactly as in [Option B](#option-b-a-service-account-key), and
   additionally grant it the **Agent Platform User** role
   (`roles/aiplatform.user`): ☰ → **IAM & Admin** → **IAM** → **Grant access** → paste the service
   account's email → select **Agent Platform User**.

Without either of these, Gemini synthesis fails with one of:

- `Provider '<name>' returned HTTP 403: Agent Platform API has not been used in project … or it is disabled` —
  step 2 above.
- `Provider '<name>' returned HTTP 403: Permission 'aiplatform.endpoints.predict' denied on resource '…'` —
  step 3 above.

### Cost

**There is no free tier.** Gemini-TTS is billed per token (input text plus audio output tokens),
unlike the character-based free allowances the classic voices get (see
[Enable billing](#2-enable-billing)). It is never chosen for an operator automatically; an entry
configures it deliberately. If a `google-gemini` entry is the **default provider** (named, or the
first enabled entry with none named), start-up logs a warning, because every announcement without a
`providerName` is then billed per token:

```text
multiroom-tts: provider '<name>' (google-gemini) is the default provider; every announcement
without a providerName is synthesized by Gemini and billed per token
```

### Models

Known on 2026-09-24 (Google adds and retires models without notice; only the form of the name is
checked, never against this list):

| Model | Stage |
|---|---|
| `gemini-2.5-flash-tts` | GA |
| `gemini-2.5-pro-tts` | GA |
| `gemini-2.5-flash-lite-preview-tts` | Preview |
| `gemini-3.1-flash-tts-preview` | Preview |

### Choosing a voice

A Gemini voice is a name such as `Kore`, `Charon` or `Zubenelgenubi` — one word of letters, any
case (`kore` and `KORE` both mean `Kore`, and share one cache entry). To see them, ask the entry:

```http
GET /api/tts/providers/gemini/voices
```

```json
{ "providerName": "gemini", "voices": [
  { "shortName": "Achernar", "fullName": "Achernar", "engine": "gemini-2.5-flash-tts", "gender": "FEMALE" },
  { "shortName": "Achird",   "fullName": "Achird",   "engine": "gemini-2.5-flash-tts", "gender": "MALE" }
] }
```

- The list is Google's own (the bare-named voices of `GET v1/voices`, 30 on 2026-09-26), fetched on
  first need and remembered under the same `voice-catalogue` settings as a `google-cloud` entry's.
  Reading it is not billed.
- `engine` is the entry's model, and there is no `language`: Gemini voices are not tied to one.
  A `language` filter is checked for form but does not narrow the list; an `engine` filter is a
  `400`.
- Google's list is **not per model**, so a listed voice is not guaranteed for every model.
- Unlike classic voices, an announcement's voice is **not checked** against the list: Google's own
  rejection of an unknown voice reaches the caller with its explanation.

### Writing a style prompt

The style prompt (`style-prompt` on the entry, `stylePrompt` per request) is a natural-language
instruction for how the text is delivered, for example `Say this calmly and warmly, like a
friendly household announcement.` It is sent to Google separately from the text — never
concatenated with it — and is part of the cache identity, so the same text with a different prompt
is a fresh synthesis. Per request:

- Omit `stylePrompt` to use the entry's default, if it has one.
- Send `stylePrompt: ""` (or only whitespace) to turn the default off for that announcement.
- Any other value replaces the default for that announcement.

Leading and trailing whitespace is stripped from every prompt before it is sent or cached; the rest
is kept exactly, including case and inner spacing.

### Speaking rate, and what does not work

`speaking-rate` (config) / `speakingRate` (request) works exactly as for `google-cloud`: [0.25, 2.0],
1.0 is natural speed. **Pitch does not**: Google silently ignores it for Gemini voices, so the
extension rejects `pitch` rather than accept a setting that would have no effect. `engine` is
`google-cloud` only and is rejected on a `google-gemini` entry too.

### Raising the time limit

Gemini synthesizes more slowly than classic voices. The shared default (`timeout-seconds: 10`,
covering the token fetch and the synthesis together) is enough for short announcements; raise it on
the entry for a slower model or longer texts.

### Example entry

```yaml
multiroom:
  tts:
    providers:
      - name: gemini
        type: google-gemini
        service-account-key-file: /home/tiger/.config/multiroom/sonora-tts-sa.json
        model: gemini-2.5-flash-tts
        voice: Kore
        language: en-US
        style-prompt: "Say this calmly and warmly, like a friendly household announcement."
        speaking-rate: 1.05
```

See [configuration.md](configuration.md#google-gemini) for the full field reference.

## See also

- [cloud.google.com/text-to-speech](https://cloud.google.com/text-to-speech) — official product
  documentation
