# Research: An Announcement That Fails To Start Ends Once, As A Failure

**Feature**: [spec.md](spec.md) | **Date**: 2026-10-09

Sources read: sonora-multiroom at `e11d4a4` (multiroom-api 0.1.22), in particular
`RouteManagerImpl.createRouteInternal`, `RouteSession`, `RouteControlPlane`,
`AutoRemoveInputListener` and `DeviceRegistryServiceImpl.unregisterInput`; and this repository's
`TtsService.activate`/`abandon` and `PlaybackCompletionListener`.

## R1. When does the hub publish `RouteDestroyedEvent` for a failed start, and on which thread?

**Finding**: `createRouteInternal` reserves the route (status `STARTING`, no `startedAt`), then
opens the input, outputs and pipeline outside the lock. Any exception in that stretch reaches its
`catch (Exception e)`, which runs `session.stop(section)` inside `plane.run(...)` and then throws
`RouteException`. `RouteSession.stop` queues exactly one `RouteDestroyedEvent` carrying the route
with status `STOPPING`. `RouteControlPlane` drains its event FIFO when the outermost section exits,
before control returns to the caller ("a caller returns only after its own events were
published"). The FIFO may be drained by another thread that holds the dispatch lock, but the
caller still waits for it to empty.

So on a 0.1.22 hub, for a route that was admitted and failed to start, the extension's
`onRouteDestroyed` **has run (possibly on another thread) before `createRoute` throws** into
`TtsService.activate`. On a 0.1.21 hub the `catch` path publishes no event; only a route stopped
while starting (below) can produce one.

The same applies when the route was stopped while starting (by another caller, or because its
pipeline ended before the route went live): `createRoute` throws "Route was stopped while
starting", and the one event was queued by whoever stopped it.

## R2. Does the hub remove the announcement's input after a failed start?

**Decision**: No. The extension must keep removing it on the failure path.

**Finding**: `AutoRemoveInputListener.onRouteDestroyed` removes an `autoRemove` input only if
`hasHadRoutes` is set for it, and that is set only by `onRouteCreated`. `RouteCreatedEvent` is
published only from `RouteSession.markLive`, i.e. when a route goes live. A route that failed to
start never went live, so the hub leaves its input registered. Every announcement has its own input
(`tts-<uuid>`), so no earlier route can have set the flag.

This contradicts step 2 of the backlog item, which expected the hub to remove the input and the
extension's second removal to fail with a WARN. With the current code, the extension's
`unregisterInput` succeeds. Following the backlog's proposal literally ("`abandon()` does nothing
if the destroyed event already handled it") would therefore **leak the input** in the hub's
registry for the life of the process.

**Consequence for the design**: the failure path stays the owner of input removal after a refusal
or a failed start. Tolerating an already-removed input (`IllegalArgumentException` "is not
registered", as `DeviceRegistryServiceImpl` throws) is kept only as robustness against a future hub
that removes it.

## R3. How to tell a route that never started from one that finished

**Decision**: `event.route().getStartedAt() == null` means the route never went live. The
listener leaves such an announcement tracked and does nothing else; the failure path in
`TtsService.activate` (which is certain to run, see below) owns its whole cleanup.

**Rationale**:
- `startedAt` is set only by `Route.withStarted`, called only from `RouteSession.markLive`, and a
  transfer keeps it. The reserved route is built without it. So in 0.1.22 it is null exactly for a
  route that never went live.
- It is correct for the race the spec names: a short announcement that went live and ended on the
  hub's cleanup thread before `createRoute` returned carries a `startedAt` and is completed
  normally. Option 1 (an extension-held "starting" mark until `createRoute` returns) would misreport
  that announcement as failed.
- Every never-started route ends with `createRoute` throwing (both the `catch` path and the
  "stopped while starting" path throw), so `abandon` always runs for it and nothing is left
  tracked. The announcement's own input is unique, so `createRoute` cannot return an existing
  route for it.
- The `Route` model of multiroom-api 0.1.21 already has `getStartedAt`, so
  `multiroom.require_api_version` stays at 0.1.21.
- The meaning holds on a 0.1.21 hub too (sonora-multiroom `da3dee9~1`, the commit before 024):
  `RouteManagerImpl.createRoute` sets `withStarted(Instant.now())` after `pipeline.start()` and
  before publishing `RouteCreatedEvent`, and a transfer copies `startedAt`. A route destroyed while
  starting (its pipeline ended before `withStarted`) carries no start time and `createRoute` then
  throws "Route was removed while starting", so it too is handled by `abandon`, once.

**Alternatives considered**:
- *Option 1, a "starting" mark*: needs extra state and a second lock-free handshake between the
  queue worker and the event thread, and misreports the race above.
- *Let the listener do the cleanup and make `abandon` skip what was done*: splits one outcome
  across two threads; the listener cannot know the failure reason or count the failure without
  duplicating `abandon`.

**Risk and mitigation**: the hub does not document `startedAt == null` as "never started". If a
future hub set it earlier, the listener would again complete the announcement on the event. The
idempotent `abandon` (R4) then still guarantees one queue signal and one failed count, and only the
log line regresses. Pin the 0.1.22 behaviour in a test with a route built without `startedAt`, and
offer the user a hub backlog item to document it (not filed from here: hub proposals go into the
sonora-multiroom backlog).

## R4. Making `abandon` idempotent

**Decision**: `PlaybackCompletionListener.cancel` returns whether it removed a tracked
announcement. `abandon` always counts one failed playback (the start did fail) and always attempts
to remove the input, but runs the queue's completion callback only when `cancel` removed the task;
otherwise it logs at DEBUG that the end was already handled.

**Rationale**: the completion callback is the one action that must not repeat (the queue's signal).
Counting the failure in `abandon` regardless keeps `started + failed` equal to the announcements
taken off queues even under the R3 risk, because the listener never counts.

**Alternatives considered**: counting the failure only when `cancel` removed the task. Under the R3
risk an announcement would then be neither started nor failed, breaking the counter balance.

## R5. Log lines

**Decision**: Rename the existing ERROR line on the non-refusal path to
`TTS_PLAYBACK_FAILED announcementId={} target={}` (with the exception, as now), next to
`TTS_PLAYBACK_STARTED`, `TTS_PLAYBACK_REFUSED` and `TTS_PLAYBACK_COMPLETED`. The listener logs a
never-started route at DEBUG only. The unregister failure stays WARN, except
`IllegalArgumentException`, which is DEBUG.

**Rationale**: an operator searching for `TTS_PLAYBACK_` sees every outcome under one prefix; a
failed start is a fault (stack trace useful), a refusal is not (no stack trace, unchanged).

## R6. Testing

**Decision**: Unit tests only, with Mockito, in the existing `PlaybackCompletionListenerTest` and
`TtsServiceTest`. The 0.1.22 ordering is simulated by a `createRoute` stub that invokes the real
listener's `onRouteDestroyed` with a route without `startedAt` before throwing. The listener test's
`routeTo` helper must set `startedAt`, since its existing tests describe a finished route.

**Rationale**: constitution III; a failed start cannot be safely provoked on production on demand
(spec assumption), so the smoke run only confirms the JAR loads and a normal announcement still
plays.
