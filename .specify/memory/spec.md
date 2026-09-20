# Main Specification: Sonora TTS

**Scope**: one extension for the multiroom audio system — text-to-speech announcements delivered to a
room or a group of rooms.

## What the product does

A caller (an automation, a script, Home Assistant) sends a line of text and a target. The extension
synthesizes speech through a configured provider, caches the audio, plays it on the target, and
restores whatever the room was doing before.

- **Trigger**: HTTP, on the host's single port, under this extension's own `/api/tts/**` paths. There
  is no separate authentication layer — access control is whatever guards the shared HTTP surface
- **Providers**: several may be configured at once, each with a name; one is the default (the first in
  the list if none is marked). A request may override the provider, the voice and the language
- **Cache**: on disk, keyed by text + provider type/model + voice/language + **the audio format the
  entry was converted to**. Survives restarts; LRU eviction at a configurable size
- **Playback**: the cached WAV is registered as an ephemeral input and routed to the target
  (`SINGLE_OUTPUT` or `OUTPUT_GROUP`, the host's own `TargetType`). Announcements queue behind
  whatever is playing
- **Completion**: observed, never estimated — the host destroys the announcement's route at EOF and
  publishes `RouteDestroyedEvent`; restoration hangs off that event
- **Queue**: in memory only. Unplayed announcements are dropped on restart; they are time-sensitive
  and worthless afterwards

## What it deliberately is not

- Not a second web server, not a second Spring context, not a bundled framework — it contributes into
  the host by auto-configuration (see [constitution.md](constitution.md), principle VIII)
- Not an audio-processing component: format conversion is delegated to the host's shared
  `FormatConverter`. This module implements no resampling, channel mixing or bit-depth conversion
- Not MQTT-triggered (deferred; it needs a cross-extension mechanism that is out of scope)

## Current state

**Specified, not implemented.** The feature specification lives in
`specs/001-tts-extension/` on the `001-tts-extension` branch — spec, plan, tasks, data model and the
published REST contract. It was written in the `multiroom-ai` monorepo as `021-tts-extension` and
moved here on 2026-09-20, before any code existed.

## Host coupling

Built against `multiroom-api` **0.1.18**, resolved from the local `~/.m2`; the host repository is
`D:\projects-multiroom\multiroom-ai`. The required version is pinned in `pom.xml` and advertised in
the JAR manifest as `Require-API-Version`. See the Upstream Contract in
[constitution.md](constitution.md).
