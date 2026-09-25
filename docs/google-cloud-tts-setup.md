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
  token-priced, **no free tier**). Google accepts them on the same `v1/text:synthesize` endpoint
  (`voice.modelName`), but **not with an API key**: tested on 2026-09-24, a plain key gets
  `403 IAM_PERMISSION_DENIED` (`aiplatform.endpoints.predict`) and a key bound to a service
  account gets `401`. Only OAuth works there. They can be reached with a bound API key through
  Agent Platform's own `generateContent` endpoint instead. That needs the Agent Platform API
  enabled, a service account with the **Agent Platform User** role, and a different
  request and response format. `GoogleCloudTtsProvider` doesn't call either endpoint, and support
  is planned as a separate provider (a later feature). See
  [future/google-cloud-gemini-tts-params.md](future/google-cloud-gemini-tts-params.md).

Within Legacy + Latest (what this extension can actually use): `Chirp3-HD` and `Neural2` sound
most natural; `Standard`/`WaveNet` are cheaper; `Studio` is the most expensive.

**SSML support differs by family**: per
[docs.cloud.google.com/text-to-speech/docs/list-voices-and-types](https://docs.cloud.google.com/text-to-speech/docs/list-voices-and-types),
`Neural2`/`WaveNet`/`Standard`/`Studio` support
[SSML](https://docs.cloud.google.com/text-to-speech/docs/ssml); `Chirp3-HD` does not. Not relevant
today — `GoogleCloudTtsProvider.buildRequest()` only ever populates `input.text`, never
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

Each result gives the `shortName` to use with an engine and a language, and the `fullName` to use
on its own.

**The language comes from the voice.** Google rejects a request whose `languageCode` differs from
the voice's own language, so the extension takes the language from a full voice name:
`uk-UA-Chirp3-HD-Charon` is sent as `uk-UA` without any `language` setting. A request `language`
that contradicts the voice is a `400 INVALID_REQUEST` before anything is sent. A short name such as
`charon` is completed with the request's language, or else the entry's default language (its
`language` setting, or else the language of its configured full voice). When Google does reject a
request, its own explanation follows the status, e.g.
`Provider 'google' returned HTTP 400: Requested language code 'en-US' doesn't match …`.

## See also

- [cloud.google.com/text-to-speech](https://cloud.google.com/text-to-speech) — official product
  documentation
