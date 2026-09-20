# sonora-tts

Text-to-speech announcements for the [multiroom audio system](https://github.com/tiger-seo/multiroom-ai).

Send a line of text and a target room; the extension synthesizes it through a configured TTS
provider, caches the audio, and plays it on that output or output group.

```http
POST /api/tts/announce
{ "text": "Dinner is ready", "targetType": "SINGLE_OUTPUT", "target": "kitchen" }
```

## What it is

A single **drop-in extension JAR** for `multiroom-core`. It is discovered before the host starts,
contributes into the host's Spring context by auto-configuration, and obtains core services by
ordinary dependency injection. It runs no web server of its own and ships no copy of Spring.

- Multiple named providers (cloud or local), one of them the default; a request may override the
  provider, voice and language
- Disk cache keyed by text + provider/model + voice/language + audio format, surviving restarts,
  LRU-evicted at a configurable size
- Playback through an ephemeral registered input; the target's prior state is restored when the
  host destroys the announcement's route

Status: **repository scaffold.** The feature is specified and built on the `001-tts-extension` branch.

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
credentials belong there — via environment variables — and never in this repository.

`multiroom.tts.enabled=false` disables the extension without removing the JAR.

## Documentation

| Document | What it covers |
|---|---|
| [AGENTS.md](AGENTS.md) | How to work in this repository, and the rules across the repo boundary |
| [.specify/memory/constitution.md](.specify/memory/constitution.md) | Engineering principles, the merge gate, the extension boundary |
| [docs/upstream/](docs/upstream/) | Read-only snapshots of the host's extension guides |
