# Implementation Plan: Announcements Over What Is Playing (Ducking)

**Branch**: `006-announcement-ducking` | **Date**: 2026-10-02 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/006-announcement-ducking/spec.md`

## Summary

Announcements stop clearing the target. Instead of `stopRoutesByOutput`/`stopRoutesByGroup` before
enqueueing and recreating the snapshot after `RouteDestroyedEvent`, the queue worker creates the
announcement's route with multiroom-api 0.1.21's `createRoute(input, target, RouteJoinMode)` in the
effective playback mode — `duck-others` by default, `mix` on request or by configuration — and the
host lowers and restores everything else. The snapshot and restore machinery is deleted. A host
refusal (`RouteAdmissionException`) reuses the existing failed-route cleanup, gets its own WARN log
line, counts as `tts_playbacks_total{outcome="failed"}`, and is not retried.
[research.md](research.md) records the decisions.

## Technical Context

**Language/Version**: Java 17

**Primary Dependencies**: `multiroom-api` 0.1.21 (`provided`; `RouteJoinMode`,
`RouteAdmissionException`, three-argument `createRoute`), `multiroom-extension-starter` 0.1.21
parent; Spring Boot 3.5, Micrometer and Lombok as before. No new dependency

**Storage**: Unchanged on-disk audio cache; `CacheKey` unchanged (the mode is not a dimension)

**Testing**: JUnit 5, Mockito (`RouteService`, `DeviceRegistryService`), `@WebMvcTest`,
`ApplicationContextRunner`; JaCoCo 80 % floor

**Target Platform**: Inside `multiroom-core` on Windows (development) and Raspberry Pi OS ARM64
(production)

**Project Type**: Single-module drop-in extension JAR

**Performance Goals**: A cache hit stays under 1 s from request to sound on a busy output
(SC-003): the route-stop step before playback disappears

**Constraints**: Nothing audible changes when a request is accepted; no route other than the
announcement's is created or stopped; no network work in `resolveSettings` (unchanged); no new
metric tag key; no caller text in tags

**Scale/Scope**: ~6 production classes changed, 2 added; ~5 test classes changed; 4 documents

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | How |
|---|---|---|
| I. Clean Code | Pass | Removes a whole responsibility (route restoration) from two classes; one parser for the mode |
| II. Java 17 & Spring Boot | Pass | Enum with exhaustive switch; new `@ConfigurationProperties` nested class with field-initialiser default |
| III. Test-First | Pass | Each change starts from a failing test: `TtsServiceTest` (mode call, no stops, refusal cleanup, invalid mode before synthesis, mode-independent cache hit), `PlaybackCompletionListenerTest` (no route calls), `TtsPropertiesValidationTest`, `TtsControllerTest` |
| IV. SOLID | Pass | `PlaybackCompletionListener` loses its `RouteService` dependency; provider path untouched |
| V. Quality gates | Pass (planned) | `mvn verify`, then deploy against a 023 host and check `/actuator/extensions` |
| VI. Git workflow | Pass | Feature branch; version 0.1.5 |
| VII. Cross-platform | Pass (planned) | No platform code; playback-affecting, so verified on the production Pi ([quickstart.md](quickstart.md)) |
| VIII. Extension boundary | Pass | Only `multiroom-api`; validation is configuration-only; `enabled=false` unaffected; no `application.yml` |
| Upstream contract | Pass | API 0.1.21 installed from the local 023 branch and inspected (not empty); `require_api_version` raised with the starter; host deployed first |

No violations. **Post-design re-check**: unchanged — the design adds no dependency, no I/O at
start-up, no new metric and no cache-key dimension.

## Project Structure

### Documentation (this feature)

```text
specs/006-announcement-ducking/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── tts-rest-api.yaml     # v0.1.5: SpeakRequest.playbackMode, playback behaviour
│   └── configuration.md      # multiroom.tts.playback.default-mode, host settings, manifest
└── tasks.md                  # /speckit-tasks
```

### Source Code (repository root)

```text
pom.xml                                   # starter 0.1.21, require_api_version 0.1.21, version 0.1.5 (done)
src/main/java/multiroom/tts/
├── config/
│   ├── PlaybackMode.java                 # NEW: DUCK_OTHERS | MIX, fromName, configName, joinMode
│   ├── PlaybackProperties.java           # NEW: multiroom.tts.playback.default-mode
│   └── TtsProperties.java                # + playback; validate() rejects an unknown default-mode
├── queue/
│   ├── AnnouncementTask.java             # - routeSnapshot, + playbackMode
│   └── AnnouncementQueueManager.java     # Javadoc only (no behaviour change)
├── service/
│   ├── AnnounceCommand.java              # + playbackMode (compat constructors kept)
│   ├── TtsService.java                   # resolve mode first; no snapshot/stop; createRoute(…, mode);
│   │                                     #   RouteAdmissionException → TTS_PLAYBACK_REFUSED WARN
│   └── PlaybackCompletionListener.java   # - RouteService, - restoreRoutes; restore → release
├── rest/dto/SpeakRequest.java            # + playbackMode (String)
└── rest/controller/TtsController.java    # pass playbackMode through
src/test/java/multiroom/tts/
├── config/PlaybackModeTest.java          # NEW
├── config/TtsPropertiesValidationTest.java
├── service/TtsServiceTest.java
├── service/PlaybackCompletionListenerTest.java
├── rest/controller/TtsControllerTest.java
└── TtsAutoConfigurationTest.java         # wiring after the listener's constructor change
README.md, docs/configuration.md, docs/metrics.md   # behaviour, setting, host settings, refusals, deploy order
```

**Structure Decision**: The existing single-module layout. The mode enum lives in `config` beside
`ProviderType`, since configuration and the request share its parser; nothing new in `queue` or
`rest` beyond fields.

## Design

### Request path (`TtsService.announce`)

1. `validateText`, then resolve the mode: `command.playbackMode()` via `PlaybackMode.fromName`
   (unknown → `INVALID_REQUEST`), else the configured default. Before target lookup, provider
   resolution, cache lookup and synthesis (FR-010).
2. Cache / synthesis exactly as today (FR-011: the mode is not passed to `CacheKey`).
3. **No** `snapshotAndStopExistingRoutes` (FR-001, FR-003). Build the task with the mode.
4. Enqueue; on failure release the pin or spill file only — there are no routes to restore.

### Activation (`TtsService.activate`, queue worker)

`register` resolver → `registerInput(autoRemove=true)` → `track` →
`createRoute(inputId, OutputId|GroupId, task.playbackMode().joinMode())` (FR-001, FR-006). The
failure branch is today's, minus restoration: `playbackFailed()`, `cancel` (unpin, resolver release,
temp file), `unregisterInput` (no route exists, so nothing auto-removes it), `onPlaybackComplete`
(queue moves on, no retry — FR-007). A `RouteAdmissionException` logs
`TTS_PLAYBACK_REFUSED announcementId= target= output= reason=` at WARN without a stack trace; any
other failure keeps the ERROR line with one (FR-008).

### Completion (`PlaybackCompletionListener`)

Still keyed on `RouteDestroyedEvent.route().getInputId()` (FR-005). On completion and on `cancel`
it releases only what is this module's: pin, resolver entry, temp file. No `RouteService` calls
(FR-002); the input is left to core's `AutoRemoveInputListener` (gotcha unchanged).

### Configuration

`multiroom.tts.playback.default-mode` (`duck-others`), bound as a `String`, validated in
`TtsProperties.validate()` with the house `multiroom-tts:` fault (FR-009, FR-010).
[contracts/configuration.md](contracts/configuration.md).

### HTTP

`SpeakRequest.playbackMode` optional `String`; response and error codes unchanged (FR-012).
[contracts/tts-rest-api.yaml](contracts/tts-rest-api.yaml) v0.1.5.

### Metrics

No change in names, tag keys or value sets. A refusal is `tts_playbacks_total{outcome="failed"}`; an
invalid mode is `tts_announcements_total{outcome="rejected", error="INVALID_REQUEST"}` through the
existing `speak()` accounting. `docs/metrics.md` notes that `failed` now includes host refusals.

### Documentation (FR-014)

README (what an announcement does to the room now; deploy host first), `docs/configuration.md`
(the new key; the host's `audio.mixing.*` keys that govern level, fade and refusals),
`docs/metrics.md` (refusals). `AGENTS.md`: the feature row, and the "Never Unregister" gotcha
amended to say a *refused* route's input is the one case this module does unregister.

## Testing

- `PlaybackModeTest`: `duck-others`/`MIX`/`Duck-Others` accepted; `replace`, `duck_others`, blank,
  `null` rejected; `joinMode()` maps both values.
- `TtsServiceTest`:
  - default → `createRoute(input, OutputId, DUCK_OTHERS)`; group → `createRoute(input, GroupId, …)`;
    `verifyNoMoreInteractions(routeService)` (no `stop*`, nothing on completion);
  - request `mix` overrides a configured default; configured `mix` used when the request names none;
  - unknown request mode → `INVALID_REQUEST`, provider and cache never touched, counted as rejected;
  - same text in two modes → the `CacheKey` passed to `audioCache.get` is equal both times;
  - `RouteAdmissionException` → playback failed counted once, `completionListener.cancel(inputId)`
    and `unregisterInput(inputId)` called, completion callback run (the queue moves on), nothing
    else on `RouteService`. The listener is a mock there, so what `cancel` releases (pin, resolver
    entry, temp file) is proven in `PlaybackCompletionListenerTest`;
  - enqueue failure → pin released, no `RouteService` call.
- `PlaybackCompletionListenerTest`: completion and `cancel` release resources and make no
  `RouteService` call (constructor no longer takes one).
- `TtsPropertiesValidationTest`: default `duck-others`; `mix`/`MIX` accepted; `replace` and a
  misspelling abort with the message naming `playback.default-mode`; ignored when `enabled=false`.
- `TtsControllerTest`: `playbackMode` reaches `AnnounceCommand`; absent → `null`.
- `CacheKeyTest`: unchanged — pinned hashes still pass, which is the SC-006 cache half.

## Complexity Tracking

No constitution violations to justify.
