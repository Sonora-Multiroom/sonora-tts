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

```yaml
multiroom:
  tts:
    providers:
      - name: google-cloud
        type: GOOGLE_CLOUD
        api-key: ${GOOGLE_TTS_API_KEY}
        voice: en-US-Neural2-C
        language: en-US
        engine: neural2
```

For obtaining the API key — enabling the API, billing, and why a service account JSON won't work
here — see [google-cloud-tts-setup.md](google-cloud-tts-setup.md).

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

### `multiroom.tts.providers[]`

| Field | Applies to | Notes |
|---|---|---|
| `name` | all | Unique; used in requests (`providerName`) and the cache key |
| `type` | all | `OPENAI`, `GOOGLE_CLOUD`, `PIPER`, or `LOCAL_HTTP` |
| `enabled` | all | `true` by default; set `false` to keep a config entry without using it |
| `api-key` | `OPENAI`, `GOOGLE_CLOUD` | Required for these two; validated as non-blank only, never contacted at boot |
| `voice` | all | Default voice; a request may override it |
| `language` | all | Default BCP 47 tag (default `en-US`); a request may override it |
| `engine` | all | Model/engine name (`tts-1`, `neural2`, ...) — folded into the cache key |
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
provider's configured default is used. `targetType` is `SINGLE_OUTPUT` (target a single output by
name) or `OUTPUT_GROUP` (target a group; every output in it plays in sync).

Response (`202 Accepted`):

```json
{ "announcementId": "…", "cacheHit": false, "queueDepth": 1 }
```

Errors use a flat `{ "error": "<CODE>", "message": "…" }` shape — see
[contracts/tts-rest-api.yaml](../specs/001-tts-extension/contracts/tts-rest-api.yaml) for the full
code list and their HTTP statuses.

## Managing the cache

```http
DELETE /api/tts/cache                       # clear everything
DELETE /api/tts/cache?providerName=openai   # clear one provider's entries
GET    /api/tts/cache/stats                 # entry count, size, per-provider breakdown
```

## See also

- [google-cloud-tts-setup.md](google-cloud-tts-setup.md) — obtaining a Google Cloud API key
- [specs/001-tts-extension/quickstart.md](../specs/001-tts-extension/quickstart.md) — build, deploy
  and end-to-end walkthrough, including installing Piper
- [specs/001-tts-extension/contracts/tts-rest-api.yaml](../specs/001-tts-extension/contracts/tts-rest-api.yaml) —
  the full OpenAPI contract
