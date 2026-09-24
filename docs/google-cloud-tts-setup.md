# Setting up Google Cloud Text-to-Speech

`GoogleCloudTtsProvider` authenticates with a plain API key on the query string (`?key=...`), not
OAuth. This walks through obtaining that key. See
[configuration.md](configuration.md#google-cloud) for the extension-side YAML.

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

## 4. Create an API key

Do this from the *general* Credentials page, not the API's own details page — that page only
offers **OAuth client ID** and **Service account**, no plain API key option.

1. ☰ (hamburger menu, top-left) → **APIs & Services** → **Credentials**
2. **+ Create credentials** → **API key**
3. When prompted for "APIs that can be accessed using this key", restrict it to **Cloud
   Text-to-Speech API**
4. Copy the generated key

## 5. Use it in config

```yaml
multiroom:
  tts:
    providers:
      - name: google-cloud
        type: GOOGLE_CLOUD
        api-key: ${GOOGLE_TTS_API_KEY}
        voice: en-US-Neural2-C
        language: en-US
        engine: neural2    # not sent to Google — see note below
```

`engine` is **not an API parameter for this provider**: `GoogleCloudTtsProvider` never reads it.
It's a free-text label folded only into the cache key, useful for keeping cache entries distinct
if you configure multiple `GOOGLE_CLOUD` entries; any string works, there's no fixed set of values.
The actual voice family (Neural2/WaveNet/Chirp3-HD/etc.) is entirely determined by `voice`. Contrast
`OPENAI`, where `engine` *is* the literal `model` field sent to the API (default `tts-1`).

## A service account JSON is not what you need here

If you instead created a **Service account** and downloaded its JSON (`private_key`,
`client_email`, `project_id`, ...), that's a different credential type built for OAuth
server-to-server auth. It will not work with the current provider as-is. Using one would require
exchanging it for a bearer token and switching `GoogleCloudTtsProvider` to
`Authorization: Bearer <token>` instead of the `?key=` query param — a code change, not just
configuration. Delete or ignore that JSON and create a plain API key via step 4 instead.

## Voices

The pricing page groups voices into three categories; **all three that use `voice.name` work
with this extension today** — only the third does not:

- **Legacy TTS models**: `WaveNet`, `Studio`, `Standard`, `Neural2`.
- **Latest TTS models**: `Chirp3-HD` — "powered by our cutting-edge LLMs", Google's own
  highest-quality, most natural-sounding option; its own free tier (first 1 million
  characters/month, same allowance as WaveNet — see [above](#2-enable-billing)).
- **Gemini-TTS models** (e.g. `gemini-3.1-flash-tts-preview`, token-priced, **no free tier**) — a
  separate native-audio-generation feature, not the classic `v1/text:synthesize` endpoint.
  `GoogleCloudTtsProvider` doesn't call it; using one would need a different provider
  implementation, not just a config change — see
  [../future/google-cloud-gemini-tts-params.md](../future/google-cloud-gemini-tts-params.md).

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

## See also

- [cloud.google.com/text-to-speech](https://cloud.google.com/text-to-speech) — official product
  documentation
