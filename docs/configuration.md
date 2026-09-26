# Configuring the TTS extension

This extension ships no `application.yml` of its own — on the shared classpath, one would shadow
the host's (019's build gate fails the JAR outright if it tried). Every setting below goes in the
**host's** configuration file (`multiroom.yml` in production, or `application.yml`/command-line
`-D`/`--` overrides in development), under the `multiroom.tts` key.

## Minimal config: one cloud provider (OpenAI)

```yaml
multiroom:
  tts:
    providers:
      - name: openai
        type: OPENAI
        api-key: ${OPENAI_API_KEY}
        voice: alloy
        language: en-US
        engine: tts-1          # the model name; see the field reference below
        timeout-seconds: 10
    cache:
      dir: ${user.home}/.multiroom/tts-cache
      max-size-mb: 500
```

That's enough to boot: at least one provider is required, and every other field has a default.

## With a local fallback (Piper) and an explicit default

**Recommended: Piper's own HTTP server, via `LOCAL_HTTP`.** `piper-tts` (piper1-gpl) ships an
HTTP server mode that loads the model once and keeps it warm — see
[Installing Piper](#installing-piper-on-a-raspberry-pi) below for running it as a systemd service.
This is the same `LOCAL_HTTP` provider type used for any other local TTS service; no Piper-specific
code runs in this extension at all.

```yaml
multiroom:
  tts:
    default-provider: openai        # omit this and the first entry in `providers` is used
    max-text-length: 500
    providers:
      - name: openai
        type: OPENAI
        api-key: ${OPENAI_API_KEY}
        voice: alloy
        language: en-US
        engine: tts-1
        timeout-seconds: 10
      - name: piper-local
        type: LOCAL_HTTP
        endpoint: http://127.0.0.1:5000/synthesize
        timeout-seconds: 10
    cache:
      dir: ${user.home}/.multiroom/tts-cache
      max-size-mb: 500
    queue:
      max-depth-per-target: 10
```

**Alternative: the `PIPER` type**, which spawns `python3 -m piper` as a subprocess per request
instead of talking to a running server:

```yaml
      - name: piper-local
        type: PIPER
        python-executable: python3        # must have `pip install piper-tts` done; Windows: python
        model-path: /opt/piper/models/en_US-ryan-medium.onnx
        timeout-seconds: 10
```

This is one less always-on service to run (no systemd unit sitting on port 5000, no model held
resident in RAM between announcements), but it **reloads the model from disk on every request** —
real added latency on a Pi that eats into SC-001's 5-second budget on cache misses. Prefer the
HTTP server unless avoiding a second daemon matters more than that.

`model-path` is checked for existence **at start-up**, and so is its `.onnx.json` config sidecar
(Piper won't load a model without it) — a missing one aborts host start-up and names this
extension in the error, exactly like a missing `api-key` does. `python-executable` is **not**
checked at start-up: it defaults to `python3`, a bare command resolved via `PATH` rather than a
literal file, and validation never spawns a process to test it either way (that's also why an
unreachable OpenAI endpoint or a not-yet-running local service is a per-request failure, never a
start-up one).

## Google Cloud

A `GOOGLE_CLOUD` entry accepts three shapes. Voices, engines and languages are matched ignoring
case, and are sent to Google in its own spelling.

```yaml
multiroom:
  tts:
    providers:
      # 1. A full voice name (the shape used before 0.1.1, which keeps working unchanged).
      #    The voice decides its own language and engine; the default engine for short names in
      #    requests is taken from it (Neural2 here).
      - name: google
        type: GOOGLE_CLOUD
        api-key: ${GOOGLE_TTS_API_KEY}
        voice: en-US-Neural2-C
        language: en-US          # optional; the default language for short names in requests

      # 2. Structured: engine + language + a short voice name.
      - name: google-uk
        type: GOOGLE_CLOUD
        api-key: ${GOOGLE_TTS_API_KEY}
        engine: chirp3-hd
        language: uk-UA
        voice: charon            # resolves to uk-UA-Chirp3-HD-Charon
        speaking-rate: 1.1       # Chirp3-HD supports pace; Google documents no pitch control for it

      # 3. Engine and language only: callers pick the voice; with none, Google chooses one.
      - name: google-wavenet
        type: GOOGLE_CLOUD
        api-key: ${GOOGLE_TTS_API_KEY}
        engine: wavenet
        language: en-US
```

Any of these shapes may authenticate with a service account instead of an API key: replace
`api-key` with the path of the service account's JSON key.

```yaml
      - name: google
        type: GOOGLE_CLOUD
        service-account-key-file: /home/tiger/.config/multiroom/sonora-tts-sa.json
        voice: en-US-Neural2-C
```

| Key | Meaning |
|---|---|
| `api-key` | A Google Cloud API key. **Exactly one of `api-key` and `service-account-key-file`** |
| `service-account-key-file` | The path of a service account's JSON key, as Google issues it. Read and checked once at start-up (a replaced file takes effect on restart); never contacted at boot. A relative path resolves against the host's working directory, so prefer an absolute one; `~` is not expanded |
| `voice` | A full name (`uk-UA-Chirp3-HD-Charon`) or a short name (`charon`, `D`) |
| `engine` | The default engine for short names: `standard`, `wavenet`, `neural2`, `studio`, `chirp-hd` or `chirp3-hd` (case, `-` and `_` are ignored). **Required unless `voice` is a full name** |
| `language` | A language-region tag (`uk-UA`, `cmn-CN`, `es-419`). The default language for short names; without it, a full `voice`'s own language is the default. Required when `voice` is short. No `en-US` default: a full voice is always synthesized in its own language |
| `pitch` | Semitones, **[-20.0, 20.0]**. Sent only when set. Not every engine supports it; Google's rejection, if any, is reported with Google's explanation |
| `speaking-rate` | **[0.25, 2.0]**, where 1.0 is the voice's natural speed. Sent only when set |
| `extra-params` | Must be empty for `GOOGLE_CLOUD`; use `pitch` and `speaking-rate` |
| `timeout-seconds` | The whole budget for one synthesis, including a voice-catalogue fetch |

How a request's `voice`, `engine` and `language` combine with these:

- A **full** voice name is used as given. Its language and engine come from the name, and a
  request `language` or `engine` that contradicts it is a `400 INVALID_REQUEST`.
- A **short** voice name is completed with the request's `engine` (or else the entry's default
  engine) and the request's `language` (or else the entry's default language). Other engines are
  never searched: `D` on a Chirp3-HD entry is looked up as a Chirp3-HD voice only.
- With **no voice** anywhere, the language is the request's, then the entry's, then `en-US`, and
  Google picks the voice.

**The voice catalogue.** On a cache miss, the resolved voice is checked against Google's
published voice list (`GET v1/voices`), which each entry fetches on first need and keeps in
memory. An unknown voice is a `400 INVALID_VOICE` listing the voices of the same engine and
language. If the list cannot be fetched, the check is skipped and synthesis goes ahead, and no new
fetch is tried until the back-off passes. Nothing is fetched at start-up. The timings are global:

```yaml
multiroom:
  tts:
    voice-catalogue:
      ttl: 24h               # how long a fetched list is trusted
      failure-backoff: 60s   # no new fetch this long after a failure; also the minimum age
                             # before a voice missing from the list triggers a refetch
      fetch-timeout: 3s      # cap on one fetch, taken out of the entry's timeout-seconds
```

To see which voices an entry offers:

```http
GET /api/tts/providers/google/voices?language=uk-UA&engine=chirp3-hd
```

Both filters are optional. Each voice has a `shortName`, `fullName`, `engine`, `language` and
`gender` (Google's `ssmlGender`). A `google-gemini` entry lists too (see [Google Gemini](#google-gemini));
`openai`, `piper` and `local-http` answer `400`, and an unreachable catalogue `503`.

**Start-up faults.** Each of these aborts host start-up with
`multiroom-tts: provider '<name>' …`, without contacting Google:

- `engine` is not one of the six engines above (the message lists them);
- `language` is not a language-region tag, or `voice` is neither a full nor a short name;
- there is no `engine` and `voice` is not a full name ("an engine is required");
- `voice` is short and there is no `language`;
- `pitch` or `speaking-rate` is out of range (the message states the range);
- `extra-params` is not empty;
- neither or both of `api-key` and `service-account-key-file` are set;
- the key file does not exist, cannot be read, is not a service account key, lacks
  `client_email` or a usable `private_key`, or names a `token_uri` that is neither `https` nor a
  loopback address (the message shows the absolute path).

`service-account-key-file` on any other provider type is a start-up fault too.

A `language` that differs from a full `voice`'s own language is only a warning.

> **Upgrading from 0.1.0:** `engine` on a `GOOGLE_CLOUD` entry used to be a free-text cache label.
> It is now validated, so an entry with any other label fails start-up until the label is removed
> or corrected. Existing cache entries for Google voices are synthesized again once, because the
> cache key now uses the resolved voice name.

For obtaining a credential — enabling the API, billing, creating an API key or a service account
key, and the roles a service account needs — see
[google-cloud-tts-setup.md](google-cloud-tts-setup.md).

## Google Gemini

A `google-gemini` entry synthesizes through Gemini-TTS voices (`Kore`, `Charon`, …) — a separate
provider type from `google-cloud`, not one of its engines. It always authenticates with a
**service account key**; an `api-key` never works for Gemini and aborts start-up. Its style prompt
is the reason to use it: a doorbell, a reminder and an alarm can each sound the way they should.

```yaml
multiroom:
  tts:
    default-provider: google           # keep a classic entry as the default — see the warning below
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
        style-prompt: "Say this calmly and warmly, like a friendly household announcement."
        speaking-rate: 1.05
```

| Key | Meaning |
|---|---|
| `service-account-key-file` | **Required.** Exactly as for `google-cloud`: read and checked once at start-up, never contacted at boot |
| `model` | **Required.** The Gemini-TTS model, e.g. `gemini-2.5-flash-tts`. Lower-case letters, digits, dots and hyphens. Not overridable per request |
| `voice` | **Required.** A Gemini voice name, e.g. `Kore`: one word of ASCII letters, any case (normalized to `Kore`). Listed by the voice-listing endpoint, but not checked against it — an unknown voice is Google's own refusal |
| `language` | **Required.** A language-region tag (`uk-UA`, `cmn-CN`, `es-419`); Google refuses a Gemini request without one |
| `style-prompt` | The default style prompt, sent to Google separately from the text. Leading/trailing whitespace is removed; at most `max-text-length` characters |
| `speaking-rate` | [0.25, 2.0], 1.0 natural speed. Same rules as `google-cloud`. **`pitch` is rejected**: Google silently ignores it for Gemini voices |
| `timeout-seconds` | Default `10`, covering the token fetch and the synthesis together. Raise it for a slower model or longer texts |

Every existing `google-cloud`, `openai`, `piper` and `local-http` entry keeps working unchanged;
`google-gemini` adds no field to any other type.

**Start-up faults**, each aborting host start-up with `multiroom-tts: provider '<name>' …`,
without contacting Google:

- `api-key` set (with or without a key file): Gemini voices require a service account key file and
  cannot use an API key;
- no `service-account-key-file`, `model`, `voice` or `language`, or one of them malformed;
- `engine`, `pitch`, or a non-empty `extra-params`: Gemini does not support them;
- `speaking-rate` out of range;
- `style-prompt` longer than `max-text-length` after trimming;
- `style-prompt` or `model` set on any type other than `google-gemini`;
- any key-file fault (missing, unreadable, not a service account key, …), exactly as for
  `google-cloud`.

**Voice listing.** `GET /api/tts/providers/gemini/voices` lists the Gemini voices Google publishes,
each with the entry's model as `engine`, its `gender`, and no `language`, sorted by name. The
`voice-catalogue` settings above govern it as for `google-cloud`, and nothing is fetched at
start-up. A `language` filter is checked for form but does not narrow the list, since Gemini voices
are not tied to one; an `engine` filter is a `400`. The list is for choosing a voice only: an
announcement's voice is never checked against it, and Google's list is not per model.

**Cost warning.** When the effective default provider (`default-provider`, or else the first
enabled entry) is `google-gemini`, start-up logs one `WARN`, because every announcement without a
`providerName` is then billed per token — Gemini has no free tier. Start-up still continues.

For the Agent Platform API, the service account role, Gemini models, choosing a voice and writing a
style prompt, see [google-cloud-tts-setup.md](google-cloud-tts-setup.md#gemini-voices-google-gemini).

## A local HTTP service (Ollama, a custom script, anything that speaks JSON over HTTP)

```yaml
multiroom:
  tts:
    providers:
      - name: local
        type: LOCAL_HTTP
        endpoint: http://127.0.0.1:5002/synthesize
        # Optional — without it, the default body is {"text","voice","language"}:
        # request-template: '{"say":"{{text}}","v":"{{voice}}","lang":"{{language}}"}'
```

## Disabling the extension

```yaml
multiroom:
  tts:
    enabled: false
```

The host starts normally, the extension inventory reports `tts` as `DISABLED`, and no
`/api/tts/**` path is mapped. Every other extension is unaffected.

## Field reference

### `multiroom.tts.*`

| Field | Default | Notes |
|---|---|---|
| `enabled` | `true` | Switches the whole extension off without removing the JAR |
| `providers` | — | At least one required; the first entry is the implicit default |
| `default-provider` | *(first in `providers`)* | Explicit default provider name |
| `max-text-length` | `500` | Requests over this length are rejected with `INVALID_REQUEST` |
| `cache.dir` | `${user.home}/.multiroom/tts-cache` | Cache root directory |
| `cache.max-size-mb` | `500` | LRU-evicted once the cache exceeds this |
| `queue.max-depth-per-target` | `10` | Max pending announcements per output/group |
| `voice-catalogue.ttl` | `24h` | `GOOGLE_CLOUD` only: how long a fetched voice list is trusted. Must be positive |
| `voice-catalogue.failure-backoff` | `60s` | `GOOGLE_CLOUD` only: no fetch this long after a failed one. Must be positive |
| `voice-catalogue.fetch-timeout` | `3s` | `GOOGLE_CLOUD` only: cap on one voice-list fetch. Must be positive |

### `multiroom.tts.providers[]`

| Field | Applies to | Notes |
|---|---|---|
| `name` | all | Unique; used in requests (`providerName`) and the cache key |
| `type` | all | `OPENAI`, `GOOGLE_CLOUD`, `GOOGLE_GEMINI`, `PIPER`, or `LOCAL_HTTP` |
| `enabled` | all | `true` by default; set `false` to keep a config entry without using it |
| `api-key` | `OPENAI`, `GOOGLE_CLOUD` | `OPENAI`: required. `GOOGLE_CLOUD`: exactly one of `api-key` and `service-account-key-file`. `GOOGLE_GEMINI`: forbidden — a start-up fault. Validated as non-blank only, never contacted at boot |
| `service-account-key-file` | `GOOGLE_CLOUD`, `GOOGLE_GEMINI` | A path to a service account's JSON key. Read and checked once at start-up; never contacted at boot. `GOOGLE_GEMINI`: required. A start-up fault on any other type |
| `model` | `GOOGLE_GEMINI` | **New.** The Gemini-TTS model, e.g. `gemini-2.5-flash-tts`. Required; not overridable per request. A start-up fault on any other type |
| `voice` | all | Default voice; a request may override it. `GOOGLE_CLOUD`: a full or short name (see [Google Cloud](#google-cloud)). `GOOGLE_GEMINI`: a Gemini voice name (see [Google Gemini](#google-gemini)) |
| `language` | all | Default BCP 47 tag; a request may override it. No declared default: other types fall back to `en-US`, `GOOGLE_CLOUD` to the voice's own language, `GOOGLE_GEMINI` requires one explicitly |
| `engine` | all but `GOOGLE_GEMINI` | `OPENAI`: the model (`tts-1`, ...), folded into the cache key. `GOOGLE_CLOUD`: the default engine for short voice names, validated. A start-up fault on `GOOGLE_GEMINI` |
| `pitch` | `GOOGLE_CLOUD` | Semitones, [-20.0, 20.0]. A start-up fault on any other type, `GOOGLE_GEMINI` included (Google silently ignores pitch for Gemini voices) |
| `speaking-rate` | `GOOGLE_CLOUD`, `GOOGLE_GEMINI` | [0.25, 2.0]. A start-up fault on any other type |
| `style-prompt` | `GOOGLE_GEMINI` | **New.** The default style prompt, sent separately from the text. Trimmed; at most `max-text-length` characters. A start-up fault on any other type |
| `extra-params` | all but `GOOGLE_CLOUD`, `GOOGLE_GEMINI` | Provider-specific parameters. Must be empty for `GOOGLE_CLOUD` and `GOOGLE_GEMINI` |
| `timeout-seconds` | all | Default `10`. Raising it trades away the 10 s error-response budget; a start-up `WARN` names any provider configured above 10 |
| `python-executable` | `PIPER` | Default `python3`. The interpreter with `piper-tts` installed — **not** a Piper binary; piper1-gpl ships no standalone executable, only a `python3 -m piper` module. Not checked at start-up (see above) |
| `model-path` | `PIPER` | Path to the ONNX voice model; must exist at start-up, and so must `<model-path>.json` next to it |
| `endpoint` | `LOCAL_HTTP` | URL to POST synthesis requests to |
| `request-template` | `LOCAL_HTTP` | Optional JSON template with `{{text}}`, `{{voice}}`, `{{language}}` placeholders |

## Installing Piper on a Raspberry Pi

The extension targets **piper1-gpl**, the actively developed successor to the old `rhasspy/piper`
C++ binary — that repository was archived in October 2025, frozen at v1.2.0 from 2023. piper1-gpl
ships no standalone binary or console script, only a Python module.

```bash
# 1. Install piper-tts into an isolated venv. The [http] extra pulls in the web framework
#    Option A's server needs; skip it if you're only ever using Option B.
python3 -m venv /opt/piper-venv
/opt/piper-venv/bin/pip install 'piper-tts[http]'

# 2. Download a voice — this resolves both the .onnx and its .onnx.json sidecar for you,
#    including legacy voice-name aliases, rather than fetching each file by hand:
sudo mkdir -p /opt/piper/models
sudo /opt/piper-venv/bin/python3 -m piper.download_voices \
  --data-dir /opt/piper/models en_US-ryan-medium
```

### Option A (recommended): run the HTTP server, use `LOCAL_HTTP`

```bash
# Test it directly first:
/opt/piper-venv/bin/python3 -m piper.http_server \
  --model /opt/piper/models/en_US-ryan-medium.onnx \
  --host 127.0.0.1 --port 5000
# In another shell:
curl -X POST http://127.0.0.1:5000/synthesize -H 'Content-Type: application/json' \
  -d '{"text":"Dinner is ready"}' -o /tmp/test.wav
aplay /tmp/test.wav
```

`--host 127.0.0.1` keeps the endpoint reachable only from this host; use `0.0.0.0` only if you
deliberately want it reachable from elsewhere on the LAN — it has no authentication of its own.

Then run it as a systemd service so it survives reboots and stays warm, e.g.
`/etc/systemd/system/piper-http.service`:

```ini
[Unit]
Description=Piper TTS HTTP server
After=network.target

[Service]
ExecStart=/opt/piper-venv/bin/python3 -m piper.http_server --model /opt/piper/models/en_US-ryan-medium.onnx --host 127.0.0.1 --port 5000
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now piper-http
```

Config: `type: LOCAL_HTTP`, `endpoint: http://127.0.0.1:5000/synthesize` — see above. Note one
HTTP server instance loads exactly one model; a different voice needs its own instance on its own
port (and its own `LOCAL_HTTP` provider entry).

### Option B: the `PIPER` type (no second daemon, slower per request)

```bash
echo 'Dinner is ready' | /opt/piper-venv/bin/python3 -m piper \
  --model /opt/piper/models/en_US-ryan-medium.onnx \
  --config /opt/piper/models/en_US-ryan-medium.onnx.json \
  --output-file /tmp/test.wav
aplay /tmp/test.wav
```

```yaml
providers:
  - name: piper-local
    type: PIPER
    python-executable: /opt/piper-venv/bin/python3
    model-path: /opt/piper/models/en_US-ryan-medium.onnx
```

## Triggering an announcement

```http
POST /api/tts/speak
Content-Type: application/json

{
  "text": "Dinner is ready",
  "targetName": "living-room",
  "targetType": "SINGLE_OUTPUT",
  "providerName": "piper-local",
  "voice": "en_US-ryan-medium",
  "language": "en-US"
}
```

`providerName`, `voice` and `language` are all optional — omit any of them and the chosen
provider's configured default is used. `GOOGLE_CLOUD` providers also accept `engine`, `pitch` and
`speakingRate`; `GOOGLE_GEMINI` providers accept `speakingRate` and `stylePrompt`; any provider
type rejects a field it does not support with `400 INVALID_REQUEST`:

```json
{ "text": "Dinner is ready", "targetName": "kitchen", "targetType": "SINGLE_OUTPUT",
  "providerName": "google", "engine": "neural2", "voice": "c", "language": "en-US", "speakingRate": 0.9 }
```

```json
{ "text": "Someone is at the front door", "targetName": "all-rooms", "targetType": "OUTPUT_GROUP",
  "providerName": "gemini", "voice": "Kore", "stylePrompt": "Announce this clearly and with a little urgency." }
```

`stylePrompt` (`GOOGLE_GEMINI` only): omit it to use the entry's default style prompt, if it has
one; send `""` (or only whitespace) to turn the default off for that announcement; any other value
replaces it. Leading and trailing whitespace is stripped before it is sent or cached.

`targetType` is `SINGLE_OUTPUT` (target a single output by
name) or `OUTPUT_GROUP` (target a group; every output in it plays in sync).

Response (`202 Accepted`):

```json
{ "announcementId": "…", "cacheHit": false, "queueDepth": 1 }
```

Errors use a flat `{ "error": "<CODE>", "message": "…" }` shape — see
[contracts/tts-rest-api.yaml](../.specify/archive/004-gemini-tts-provider/contracts/tts-rest-api.yaml) for the
full code list and their HTTP statuses.

## Managing the cache

```http
DELETE /api/tts/cache                       # clear everything
DELETE /api/tts/cache?providerName=openai   # clear one provider's entries
GET    /api/tts/cache/stats                 # entry count, size, per-provider breakdown
```

## See also

- [google-cloud-tts-setup.md](google-cloud-tts-setup.md) — obtaining a Google Cloud API key or service account key
- [.specify/archive/001-tts-extension/quickstart.md](../.specify/archive/001-tts-extension/quickstart.md) — build, deploy
  and end-to-end walkthrough, including installing Piper
- [.specify/archive/004-gemini-tts-provider/contracts/tts-rest-api.yaml](../.specify/archive/004-gemini-tts-provider/contracts/tts-rest-api.yaml) —
  the full OpenAPI contract (v0.1.3, which supersedes 003's v0.1.2 and every earlier one)
