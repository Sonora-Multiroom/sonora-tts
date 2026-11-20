# TinySpec: A chime before an announcement (adopts HUB-040)

**Branch**: backlog-adopt-hub-040-chime
**Date**: 2026-11-15
**Status**: implemented and reviewed locally; deployed to the Pi as 0.1.13; open: CI pin after the hub merge
**Complexity**: small (touches more than 5 files, mostly one new field passed through each layer)

## What

HUB-040 (API 0.1.34) lets a route start with a short chime. A speak request can now name one, and
`multiroom.tts.playback.chime` sets a default for announcements whose request names none. The value
reaches the hub as `RouteOptions.chime`. This replaces the backlog item `adopt-route-chime.md`.

## Context

| File | Role |
|------|------|
| `pom.xml` | Parent and `multiroom.require_api_version` 0.1.34; version 0.1.13 |
| `rest/dto/SpeakRequest.java`, `rest/controller/TtsController.java` | New `chime` field, mapped to the command |
| `service/AnnounceCommand.java`, `queue/AnnouncementTask.java` | Carry the resolved chime; old constructors kept |
| `config/PlaybackProperties.java`, `config/TtsProperties.java` | `playback.chime`; blank aborts start-up |
| `service/TtsService.java`, `TtsAutoConfiguration.java` | `ChimeService` injected; resolve, check and pass the chime |
| `.github/workflows/test.yml` | `HUB_REF` set to the hub's 0.1.34 commit |
| `docs/configuration.md`, `AGENTS.md`, `.specify/memory/*` | Docs and master spec/plan/changelog |
| `TtsServiceTest`, `TtsControllerTest`, `TtsPropertiesValidationTest`, `TtsAutoConfiguration*Test` | Tests |

## Requirements

1. A speak request may carry `chime`: a chime id the hub has loaded, or `none` for no chime.
2. With no `chime` in the request, `playback.chime` applies; a request value wins over it.
3. With neither, no chime is sent, and the target output's or group's own setting decides.
4. A requested id the hub has not loaded, or a blank one, answers 400 `INVALID_REQUEST` naming the
   loaded chimes, before any target lookup, synthesis or queueing. The check uses `ChimeService`
   because the route is created later on the queue worker, where the hub's refusal can't reach the caller.
5. A blank `playback.chime` aborts start-up. A configured id the hub has not loaded is logged once at
   start-up (`TTS_DEFAULT_CHIME_UNKNOWN`, WARN) and is not sent, so announcements still play.
6. The chime is not part of the cache identity: existing cache hashes are unchanged.
7. The JAR declares `Require-API-Version: [0.1.34,0.2.0)`.

## Plan

1. Raise the API version in the POM and the CI hub pin.
2. Add `chime` to `SpeakRequest` → `AnnounceCommand` → `AnnouncementTask` → `RouteOptions`.
3. Add `PlaybackProperties.chime`, and refuse a blank value in `TtsProperties.validate()`.
4. Inject `ChimeService` into `TtsService`; `resolveChime` runs right after the ducking level is resolved.
5. Update the docs and the master spec, plan and changelog; delete the backlog item and its index row.

## Tasks

- [X] Raise the POM parent, `require_api_version` and version; move CI `HUB_REF`
- [X] Pass `chime` from request through command and task to `RouteOptions`
- [X] Add `playback.chime` with the blank check
- [X] Check against `ChimeService`: 400 for an unknown requested id, drop an unknown default with a start-up WARN
- [X] Tests: request, default, `none` beats the default, unknown default not sent, unknown or blank request is 400, controller mapping, config validation
- [X] Docs, `AGENTS.md`, master spec, plan and changelog; delete the backlog item
- [X] Move `HUB_REF` to the hub's merge commit once HUB-040 is merged to master
- [X] On the Pi (with permission): deploy, then a `piper-local` announcement on `office` with and without a chime

## Done When

- [X] `mvn verify` passes (756 tests, 0 failures, 2026-11-15)
- [X] Manifest reads `Require-API-Version: [0.1.34,0.2.0)`
- [X] Code review against the requirements (2026-11-15): each requirement traced to code and a test;
      one line over 120 characters wrapped; no defect found
- [X] On the Pi (2026-11-15): 0.1.13 deployed, hub restarted, `tts` ACTIVE requiring `[0.1.34,0.2.0)`;
      on `office` (volume 25, restored to 75 afterwards), `piper-local`:
      `chime: gong` → 400 `Unknown chime 'gong'. Loaded: cabin, tritone, bingbong, alert, none`, nothing played;
      `chime: tritone` → hub logged `starts with chime 'tritone' (from the CALL level)`, played 5.6 s;
      `chime: none` → no chime line, played 2.1 s. The owner heard the first with the chime and the
      second without. Afterwards: no routes, no ALSA device open,
      `audio.output.routes` 0 for `office`, no `TTS_PLAYBACK_FAILED`
