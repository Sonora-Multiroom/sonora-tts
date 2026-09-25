# Quickstart: TTS Extension

**Feature**: `001-tts-extension`

**Date**: 2026-05-30 | **Revised**: 2026-09-18

---

## 1. Prerequisites

- **A host at `multiroom-api` 0.1.18 or later.** That release carries both
  `019-extension-shared-classloader` and the promoted `FormatConverter`. Check before anything else:
  `/actuator/extensions` answers, and the application serves both `/api/**` and `/actuator/**` on one
  port. If your build still starts a second web server for the REST API, this guide does not apply.
- **The API and the parent POM installed in your local `~/.m2`** — in the `multiroom-ai` checkout:
  `mvn -pl multiroom-api,multiroom-extension-starter -am install`. Nothing is published remotely.
- At least one TTS provider configured (cloud credentials or local binary)
- Java 17+ and Maven

---

## 2. Build and Deploy the Extension

This repository is a single module and is not part of the host's reactor — build it here, then drop
the JAR into the host's `extensions/` directory:

```powershell
mvn verify                  # build and test
mvn deploy -Plocal          # copy into the local multiroom-ai checkout's extensions/
mvn deploy                  # PRODUCTION: scp to multiroom.lan:/home/tiger/extensions
```

`remote` is the inherited default profile, so a bare `mvn deploy` uploads to production. After
deploying, restart the host and confirm it accepted the JAR — `GET /actuator/extensions` must list
`tts` with the expected version and **not** `REJECTED`. That check is the half of the merge gate a
local build cannot give you.

---

## 3. Configuration (`application.yml`)

### Minimal: Single Cloud Provider (OpenAI)

> **`multiroom-rest` is not required.** TTS serves its own `/api/tts/**` paths through core's single
> `DispatcherServlet`; it needs no other extension present. (`multiroom-rest` is still what serves
> `/api/v2/**` and generates `/api-docs`, so without it your TTS paths work but are undocumented.)
> `multiroom-mqtt` is irrelevant here — MQTT triggering is deferred.

```yaml
multiroom:
  tts:
    providers:
      - name: openai
        type: openai
        api-key: ${OPENAI_API_KEY}   # or paste key directly (not recommended)
        voice: alloy
        language: en-US
        model: tts-1
        timeout-seconds: 10
    cache:
      dir: ${user.home}/.multiroom/tts-cache
      max-size-mb: 500
```

### With Local Fallback (Piper TTS)

> **Revised 2026-09-20.** The `rhasspy/piper` C++ binary this section originally targeted was
> archived by its owner in October 2025, frozen at v1.2.0 (2023). The extension now targets its
> actively developed successor, **piper1-gpl** (`pip install piper-tts`), which ships no
> standalone binary or console script — only a `python3 -m piper` module, plus an HTTP server
> mode (`python3 -m piper.http_server`) that keeps the model warm instead of reloading it per
> request. **The HTTP server via the existing `LOCAL_HTTP` provider type is the recommended path**
> — the `PIPER` type (below) still works but reloads the model from disk on every request. See
> [../../docs/configuration.md](../../../docs/configuration.md#installing-piper-on-a-raspberry-pi)
> for the install steps and the full comparison.

```yaml
multiroom:
  tts:
    default-provider: openai        # first in list is used if this is absent
    providers:
      - name: openai
        type: openai
        api-key: ${OPENAI_API_KEY}
        voice: alloy
        language: en-US
        engine: tts-1
        timeout-seconds: 10
      - name: piper-local              # recommended: piper.http_server + LOCAL_HTTP
        type: local_http
        endpoint: http://127.0.0.1:5000/synthesize
        timeout-seconds: 10
      # - name: piper-local            # alternative: the PIPER type (no server, slower per call)
      #   type: piper
      #   python-executable: python3   # must have `pip install piper-tts` done; Windows: python
      #   model-path: /opt/piper/models/en_US-ryan-medium.onnx
      #   timeout-seconds: 10
    cache:
      dir: ${user.home}/.multiroom/tts-cache
      max-size-mb: 500
    max-text-length: 500
    queue:
      max-depth-per-target: 10
```

### Google Cloud TTS

```yaml
multiroom:
  tts:
    providers:
      - name: google-cloud
        type: google_cloud
        api-key: ${GOOGLE_TTS_API_KEY}
        voice: en-US-Neural2-C
        language: en-US
        engine: neural2
        timeout-seconds: 10
```

> `timeout-seconds` defaults to **10** and every example leaves it there on purpose: SC-003 gives the
> whole "provider is unavailable" path a 10-second budget, so a longer per-provider value trades that
> guarantee away for that provider. Raising it is supported — a local Piper model on a Raspberry Pi is
> the usual reason — and the application logs a warning at start-up naming any provider configured
> above 10 seconds, so the trade is visible rather than silent.

---

## 4. Install Piper TTS (Local Provider)

Full walkthrough, including the recommended systemd service for `piper.http_server`:
[../../docs/configuration.md](../../../docs/configuration.md#installing-piper-on-a-raspberry-pi).
Summary — piper1-gpl is a Python module (`pip install piper-tts`), not a binary:

### Linux / Raspberry Pi

```bash
python3 -m venv /opt/piper-venv
/opt/piper-venv/bin/pip install piper-tts

sudo mkdir -p /opt/piper/models
wget https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ryan/medium/en_US-ryan-medium.onnx \
  -O /opt/piper/models/en_US-ryan-medium.onnx
wget https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ryan/medium/en_US-ryan-medium.onnx.json \
  -O /opt/piper/models/en_US-ryan-medium.onnx.json

# Recommended: run the HTTP server (keeps the model warm) and use it via LOCAL_HTTP:
/opt/piper-venv/bin/python3 -m piper.http_server \
  --model /opt/piper/models/en_US-ryan-medium.onnx --host 127.0.0.1 --port 5000
```

Both the `.onnx` and its `.onnx.json` sidecar are required either way — the extension's own
start-up validation checks for both and aborts naming the extension if either is missing (this
only applies to the `PIPER` type; `LOCAL_HTTP` has no knowledge of Piper's files at all).

### Windows

```powershell
python -m venv C:\tools\piper-venv
C:\tools\piper-venv\Scripts\pip install piper-tts
# Download the .onnx and .onnx.json for a voice from https://huggingface.co/rhasspy/piper-voices
# Either run: C:\tools\piper-venv\Scripts\python.exe -m piper.http_server --model ... (+ LOCAL_HTTP)
# Or set python-executable: C:\tools\piper-venv\Scripts\python.exe in application.yml (PIPER type)
```

---

## 5. Trigger an Announcement

### Via HTTP

Everything answers on the one application port — there is no separate TTS or REST port.

```http
POST http://localhost:8080/api/tts/speak
Content-Type: application/json

{
  "text": "Hello, this is a test announcement",
  "targetName": "living-room",
  "targetType": "SINGLE_OUTPUT"
}
```

**Response** (202 Accepted):
```json
{
  "announcementId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "cacheHit": false,
  "queueDepth": 1
}
```

`targetType` is the public API's `multiroom.api.model.TargetType`: `SINGLE_OUTPUT` names an output,
`OUTPUT_GROUP` names an output group.

### Announce to an output group

```http
POST http://localhost:8080/api/tts/speak
Content-Type: application/json

{
  "text": "Dinner is ready",
  "targetName": "all-rooms",
  "targetType": "OUTPUT_GROUP"
}
```

### Via MQTT — not available

MQTT triggering is **deferred**; see [contracts/tts-mqtt-topics.md](contracts/tts-mqtt-topics.md) for
the topic design and the one-record mechanism that would enable it. HTTP is the supported transport.

### Specify a Provider Explicitly

```json
{
  "text": "Offline announcement via local provider",
  "targetName": "kitchen",
  "targetType": "SINGLE_OUTPUT",
  "providerName": "piper-local",
  "voice": "en_US-ryan-medium",
  "language": "en-US"
}
```

---

## 6. Manage the Cache

```http
# Clear entire cache
DELETE http://localhost:8080/api/tts/cache

# Clear cache for a specific provider
DELETE http://localhost:8080/api/tts/cache?providerName=openai

# View cache stats
GET http://localhost:8080/api/tts/cache/stats
```

---

## 7. Build and Run

```powershell
# Build the TTS module (Windows). No `-pl multiroom-api install` step any more:
# this feature changes nothing in multiroom-api.
.\mvnw.cmd -pl multiroom-tts clean package

# Run tests
.\mvnw.cmd -pl multiroom-tts test

# Deploy: extensions are discovered from the extensions directory before start-up
Copy-Item multiroom-tts\target\multiroom-tts.jar extensions\

# Run the application; the extension joins it
.\mvnw.cmd -pl multiroom-core spring-boot:run
```

### Confirm it loaded

```powershell
# listed, ACTIVE, with its version
(Invoke-RestMethod http://localhost:8080/actuator/extensions).extensions |
    Where-Object name -like "*tts*"

# its endpoints are in the shared contract, with no change to multiroom-rest
(Invoke-RestMethod http://localhost:8080/api-docs).paths.PSObject.Properties.Name |
    Where-Object { $_ -like "/api/tts*" }
```

Expected: one `ACTIVE` entry and three `/api/tts/**` paths. An entry reporting `INERT` means the
`.imports` file is missing or names the wrong class — the JAR was found, but contributed nothing.

### Switch it off without removing it

```powershell
.\mvnw.cmd -pl multiroom-core spring-boot:run `
  "-Dspring-boot.run.arguments=--multiroom.tts.enabled=false"
```

Expected: the application starts, the inventory reports this extension as `DISABLED`, the
`/api/tts/**` paths are absent, and every other extension is unaffected.

---

## 8. Logging

Enable verbose TTS logging in `application.yml`:

```yaml
logging:
  level:
    multiroom.tts: DEBUG
```

Structured log events emitted (FR-027):

| Event | Level | When |
|-------|-------|------|
| `TTS_REQUEST_RECEIVED` | DEBUG | Request arrives |
| `TTS_CACHE_HIT` | DEBUG | Audio served from cache |
| `TTS_CACHE_MISS` | DEBUG | Cache miss; synthesis required |
| `TTS_SYNTHESIS_STARTED` | DEBUG | Provider call initiated |
| `TTS_SYNTHESIS_COMPLETED` | INFO | Synthesis successful |
| `TTS_SYNTHESIS_ERROR` | ERROR | Provider returned error or timed out |
| `TTS_PLAYBACK_STARTED` | INFO | Audio route created, playback begun |
| `TTS_PLAYBACK_COMPLETED` | INFO | Announcement finished, target restored |
