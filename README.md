# sonora-tts

Text-to-speech announcements for the [multiroom audio system](https://github.com/tiger-seo/multiroom-ai).

Send a line of text and a target room; the extension synthesizes it through a configured TTS
provider, caches the audio, and plays it on that output or output group over whatever is already
playing there.

```http
POST /api/tts/speak
{ "text": "Dinner is ready", "targetName": "kitchen", "targetType": "SINGLE_OUTPUT" }
```

It is a drop-in extension JAR for `multiroom-core`: it contributes beans into the host's Spring
context and ships no web server or Spring of its own.

- Providers: OpenAI, Google Cloud, Google Gemini, Piper and any local HTTP engine; several named
  entries, one of them the default
- On-disk LRU cache that survives restarts
- Announcements duck the music under them (`duck-others`) or play alongside it (`mix`)
- `tts_*` metrics in the host's `/actuator/prometheus`

## Build and deploy

Needs Java 17, Maven, and `multiroom-api` installed into `~/.m2` from a local `multiroom-ai`
checkout (`mvn -pl multiroom-api,multiroom-extension-starter -am install`).

```powershell
mvn verify                  # build and test
mvn deploy -Plocal          # copy the JAR into the local multiroom-ai checkout's extensions/
mvn deploy                  # PRODUCTION: scp to multiroom.lan
```

After deploying, `GET /actuator/extensions` should list `tts` with the expected version, not
`REJECTED`.

## Documentation

| Document | What it covers |
|---|---|
| [docs/configuration.md](docs/configuration.md) | Providers, cache, queue and playback settings; triggering announcements |
| [docs/google-cloud-tts-setup.md](docs/google-cloud-tts-setup.md) | Setting up Google Cloud and Gemini voices |
| [docs/metrics.md](docs/metrics.md) | The `tts_*` metrics and example queries |
| [docs/known-issues.md](docs/known-issues.md) | Behaviour accepted for now |
