# Feature Specification: Announcement Route Volume And Ducking Level

**Feature Branch**: `034-route-volume-ducking-level` (named after the hub feature; this repository's
number is 009)

**Created**: 2026-10-25

**Status**: Implemented, not yet merged

**Upstream reference**: hub feature 034 (`sonora-multiroom/specs/034-per-route-volume-ducking/`, its
`contracts/tts.md` and tasks T036–T039). The hub adds `RouteOptions` and
`RouteService.createRoute(…, RouteOptions)` in `multiroom-api` 0.1.29. This document is the
extension's slice of that work, written after the code so the repository holds its own spec.

## Problem

An announcement plays at the target outputs' volume, so the room's volume control decides whether a
spoken alert is heard, and a `duck-others` announcement lowers the music by the hub's one system
level (`audio.mixing.ducking.level-db`). A caller cannot make one announcement louder than the room
or duck harder than usual.

## Requirements

- **R1**: `POST /api/tts/speak` accepts an optional `routeVolume`, an integer 0–100. It replaces the
  target outputs' volume for that announcement only and changes no output's volume.
- **R2**: `POST /api/tts/speak` accepts an optional `duckingLevelDb`, a number from -60.0 to 0.0, the
  amount a `duck-others` announcement lowers what else plays.
- **R3**: A value out of range, or a `duckingLevelDb` when the effective `playbackMode` is `mix`,
  answers `400 INVALID_REQUEST` before anything is synthesized or queued.
- **R4**: `tts.playback.route-volume` and `tts.playback.ducking-level-db` are optional defaults for
  requests that name none. A request value wins. A default outside its range aborts start-up
  naming the key.
- **R5**: The configured ducking default applies only to `duck-others`; for `mix` no level is sent.
- **R6**: Both values are resolved when the request is accepted, as `playbackMode` is, so a queued
  announcement plays with the values in force when it was requested.
- **R7**: Unset values leave the hub to its own defaults (outputs' volume; the input's, then the
  system's, ducking level).
- **R8**: The extension requires `multiroom-api` 0.1.29 (`multiroom.require_api_version`).

## Out of scope

Changing the volume of a playing announcement (hub feature 034 offers it on routes; this extension
does not expose it), and any change to `playbackMode` semantics.

## Success criteria

- A speak request with both fields reaches the hub as one `createRoute` call carrying a
  `RouteOptions` with the join mode, the volume and the level.
- The 400 cases in R3 and the start-up faults in R4 are covered by tests.
- With the hub from 034, a route created by an announcement reads back with both values
  (recorded in the hub's `verification.md`, T049 steps 4 and 5).
