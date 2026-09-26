# sonora-tts

Text-to-speech announcements for the [multiroom audio system](https://github.com/tiger-seo/multiroom-ai).

Send a line of text and a target room; the extension synthesizes it through a configured TTS
provider, caches the audio, and plays it on that output or output group.

```http
POST /api/tts/speak
{ "text": "Dinner is ready", "targetName": "kitchen", "targetType": "SINGLE_OUTPUT" }
```

## What it is

A single **drop-in extension JAR** for `multiroom-core`. It is discovered before the host starts,
contributes into the host's Spring context by auto-configuration, and obtains core services by
ordinary dependency injection. It runs no web server of its own and ships no copy of Spring.

- Multiple named providers (cloud or local), one of them the default; a request may override the
  provider, voice and language; for Google Cloud also the engine, pitch and speaking rate; and for
  Google Gemini also the speaking rate and a `stylePrompt`, which only `google-gemini` accepts
- Google Cloud voices by full name (`uk-UA-Chirp3-HD-Charon`) or by engine + language + short
  name (`charon`), checked against Google's published voice list, which
  `GET /api/tts/providers/{name}/voices` also exposes; for a `google-gemini` entry it lists the
  Gemini voices with their gender
- Google Cloud authentication by API key or by a service account's JSON key, exchanged for
  short-lived access tokens with no Google library bundled
- Google Gemini voices (`Kore`, `Charon`, …) through a separate `google-gemini` provider type, with
  a per-entry and per-request **style prompt** to steer delivery. Service-account authentication
  only; billed per token with no free tier, so it's never a default an operator falls into by
  accident
- Disk cache keyed by text + provider/model + voice/language + audio format, surviving restarts,
  LRU-evicted at a configurable size
- Playback through an ephemeral registered input; the target's prior state is restored when the
  host destroys the announcement's route

Status: **implemented, `mvn verify` green**, at version 0.1.3 with features 001 to 004. See
[.specify/archive/001-tts-extension/](.specify/archive/001-tts-extension/) for the base spec and
[tasks.md](.specify/archive/001-tts-extension/tasks.md) for what's verified versus what still needs real audio
hardware.

## Requirements

- Java 17
- Maven
- A local checkout of `multiroom-ai`, with its API installed into `~/.m2`:
  ```powershell
  cd D:\projects-multiroom\multiroom-ai
  mvn -pl multiroom-api,multiroom-extension-starter -am install
  ```
  Nothing is published to a remote repository — the local Maven repository is the distribution
  channel. This repository currently builds against **multiroom-api 0.1.18**.

## Build and deploy

```powershell
mvn verify                  # build and test
mvn deploy -Plocal          # copy the JAR into the local multiroom-ai checkout's extensions/
mvn deploy                  # PRODUCTION: scp to multiroom.lan:/home/tiger/extensions
```

A plain `mvn deploy` uploads to the production host, because the inherited `remote` profile is
active by default. Use `-Plocal` while developing.

After deploying, confirm the host accepted the JAR:

```
GET /actuator/extensions      →  tts, with the expected version, not REJECTED
```

That check is the second half of the merge gate: a green local build proves the module compiles,
not that it loads.

## Configuration

Lives in the **host's** configuration file (`multiroom.yml`), under `multiroom.tts`. Provider
credentials belong there — API keys via environment variables, a Google service account key as a
file on the host — and never in this repository.

`multiroom.tts.enabled=false` disables the extension without removing the JAR.

See **[docs/configuration.md](docs/configuration.md)** for the full field reference, worked
examples for every provider type, and how to trigger an announcement and manage the cache.

## Documentation

| Document | What it covers |
|---|---|
| [docs/configuration.md](docs/configuration.md) | How to configure providers, cache and queue; triggering announcements |
| [docs/google-cloud-tts-setup.md](docs/google-cloud-tts-setup.md) | Setting up Google Cloud: billing, the API, an API key or a service account key, and Gemini voices (`google-gemini`) |
| [AGENTS.md](AGENTS.md) | How to work in this repository, and the rules across the repo boundary |
| [.specify/memory/constitution.md](.specify/memory/constitution.md) | Engineering principles, the merge gate, the extension boundary |
| [.specify/archive/001-tts-extension/](.specify/archive/001-tts-extension/) | The feature: spec, plan, tasks, contracts |
| [.specify/archive/002-google-voice-selection/](.specify/archive/002-google-voice-selection/) | Google Cloud voice selection |
| [.specify/archive/003-google-service-account-auth/](.specify/archive/003-google-service-account-auth/) | Google Cloud service account authentication |
| [specs/004-gemini-tts-provider/](specs/004-gemini-tts-provider/) | Gemini TTS provider (`google-gemini`); its REST contract (v0.1.3) is the current one |
| [docs/upstream/](docs/upstream/) | Read-only snapshots of the host's extension guides |
