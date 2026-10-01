# Research: Announcements Over What Is Playing (Ducking)

**Feature**: 006-announcement-ducking | **Date**: 2026-10-02

The Technical Context had no open unknowns: the host side is fully specified by multiroom-ai 023
(`specs/023-multi-route-output-mixing/`, R6, R7, R8, R12 and `contracts/java-api.md`), and the
installed `multiroom-api-0.1.21.jar` was inspected (70 KB, carries `RouteJoinMode`,
`RouteAdmissionException`, `RouteOnOutput` and the three-argument `createRoute` overloads). The
decisions below are the ones this repository has to make on top of that.

## R1. How the announcement joins: per-route mode, not the input's default

**Decision**: Call `RouteService.createRoute(inputId, target, RouteJoinMode)` with the effective
mode. Leave `AudioInputDefinition.defaultJoinMode` unset on the ephemeral input.

**Rationale**: The input is created for one announcement and routed once, so the two forms are
equivalent; naming the mode at the call is direct and is what a unit test can verify with one
`verify(routeService).createRoute(..., DUCK_OTHERS)`. Upstream resolves request mode → input
default → `REPLACE`, so a non-null mode is always honoured.

**Alternatives considered**: Setting `defaultJoinMode` on the input and calling the two-argument
`createRoute` — the same result with the mode hidden one hop away, and a silent fall-back to
`REPLACE` if the field were ever dropped.

## R2. What is removed

**Decision**: Delete the stop–snapshot–restore machinery entirely:
`TtsService.snapshotAndStopExistingRoutes`, `AnnouncementTask.routeSnapshot`,
`PlaybackCompletionListener.restoreRoutes` and its call sites (completion, `cancel`, and the
enqueue-failure path in `TtsService.announce`). `PlaybackCompletionListener`'s private `restore`
becomes `release`: unpin, resolver release, temp-file delete.

**Rationale**: Under `DUCK_OTHERS`/`MIX` nothing is stopped, so there is nothing to restore; keeping
a code path that recreates routes would violate "never recreates a route it did not create". The
`RouteService` dependency of `PlaybackCompletionListener` goes with it.

**Alternatives considered**: Keeping restoration behind a `replace` mode — rejected in the spec
(Assumptions: *Replace is not offered*).

## R3. The playback-mode type

**Decision**: A new enum `multiroom.tts.config.PlaybackMode { DUCK_OTHERS, MIX }` with
`configName()` (`duck-others`, `mix`, as `ProviderType` does), `static Optional<PlaybackMode>
fromName(String)` (case-insensitive, exact `duck-others`/`mix` only) and `RouteJoinMode joinMode()`.
`REPLACE` is not representable, so no later code path can pass it.

**Rationale**: One parser serves configuration and requests (DRY), and an extension-owned enum is
the subset guarantee. The mapping to `RouteJoinMode` is an exhaustive `switch`, so a new value does
not compile until it is mapped.

**Alternatives considered**:
- `RouteJoinMode` directly in the task and configuration: `REPLACE` would be representable and
  would need a rejection at every entry point.
- Binding configuration to the enum and relying on Spring's binder: Spring's lenient enum
  conversion also accepts `duck_others`, `DuckOthers` and `duckothers`, and its failure message
  does not carry this module's `multiroom-tts:` prefix. Binding a `String` and validating in
  `TtsProperties.validate()` gives the exact accepted set and the house fault message.

## R4. Request field and where it is validated

**Decision**: `SpeakRequest.playbackMode` and `AnnounceCommand.playbackMode` are `String`
(`null` = not given). `TtsService.announce` resolves it first, right after the text check and before
the target lookup, provider resolution, cache lookup and synthesis: unknown → `TtsException(
INVALID_REQUEST, "Unknown playbackMode '<v>'. Supported: duck-others, mix")`. The resolved
`PlaybackMode` goes into `AnnouncementTask`.

**Rationale**: A `String` in the DTO keeps the error in this module's `{error, message}` shape with
an explicit message; an enum-typed field would fail Jackson deserialization and surface only as the
generic "Request body could not be read". Validating in the service (as provider fields are) means
the rejection is counted by `speak()`'s existing `announcementRejected` path with no new code. The
echoed value appears only in the response message, never in a log tag or metric tag.

**Alternatives considered**: Bean Validation `@Pattern` on the DTO — would put the accepted set in
two places and count the rejection under provider `unknown`.

## R5. Host refusal handling

**Finding** (upstream `RouteManagerImpl`, 023 branch): admission runs under the admission lock
*before* anything is reserved and throws `RouteAdmissionException` directly; a failure after
reservation (input resolution, pipeline start) runs `cleanupFailedRegister` and throws a wrapping
`RouteException`. Neither publishes `RouteCreatedEvent` or `RouteDestroyedEvent`, so core's
`AutoRemoveInputListener` never removes the input.

**Decision**: Keep the existing failure branch of `TtsService.activate` — `metrics.playbackFailed()`,
`completionListener.cancel(inputId)`, `unregisterInput`, then `onPlaybackComplete.run()` so the
queue moves on — and split the log: a `RouteAdmissionException` logs `TTS_PLAYBACK_REFUSED
announcementId=… target=… output=… reason=…` at WARN (an expected, operator-tunable condition);
any other `RuntimeException` keeps today's ERROR with stack trace. No retry.

**Rationale**: The cleanup is already correct for a route that never existed: unregistering is
required here precisely because no route exists to auto-remove the input, and it does not conflict
with the "never unregister" gotcha, which is about inputs whose route was created. Reason and output
are host enum/ID values (bounded, not caller text), and go only into the log, not into a metric tag
(spec clarification: no new metric reason).

**Alternatives considered**: A new `outcome="refused"` value on `tts_playbacks_total` — rejected in
clarification; it would also be a new tag value, not a new key, so it was viable, but not wanted.

## R6. Completion signal and timing

**Decision**: Unchanged. Completion is still `RouteDestroyedEvent` matched on `inputId`.

**Rationale**: Upstream R12 makes a finished route stop as soon as its lane has drained, and starts
the ducking release when the source has finished sounding, both independent of this module. The
queue's next announcement therefore starts after the previous one's route is destroyed, as today.

## R7. Configuration key

**Decision**: `multiroom.tts.playback.default-mode`, a new `PlaybackProperties` (Lombok `@Data`,
`String defaultMode = "duck-others"`) nested in `TtsProperties` like `queue` and `cache`.
`TtsProperties.validate()` rejects any value `PlaybackMode.fromName` does not accept:
`multiroom-tts: playback.default-mode '<v>' is not one of duck-others, mix`. Resolved once into a
`PlaybackMode` that `TtsService` reads.

**Rationale**: Matches the existing nested-properties pattern and leaves room for later playback
settings without another top-level key. Validation reads only configuration (Principle VIII).

## R8. Version and deployment order

**Decision**: Starter parent and `multiroom.require_api_version` both 0.1.21 (already in the working
tree), extension version 0.1.5. The manifest's `Require-API-Version` becomes `[0.1.21,0.2.0)`, so a
pre-023 host refuses the JAR at load. Deploy host first, then `mvn deploy`.

**Rationale**: FR-013. Patch bump per the repository's versioning practice (not public; sized by
value).

## R9. Verification

**Decision**: Unit tests (Mockito) prove the routing calls and cleanup; audible behaviour (SC-001,
SC-002, SC-004, SC-007) is verified on the production Pi after the host with 023 is deployed
(Principle VII), through the quickstart.

**Rationale**: There is no in-repo host. `RouteService` is an interface, so "nothing but the
announcement's route is created or stopped" is exactly `verify(...createRoute(..., mode))` plus
`verifyNoMoreInteractions(routeService)`.
