# A route that fails to start is cleaned up twice

**Status:** Proposed — not started. Written 2026-10-09.

**Origin:** The 2026-10-08 code review of the hub's `024-route-membership-single-owner` branch
(sonora-multiroom `docs/reviews/Code_Review_2026-10-08_route-membership-single-owner.md`, row 6).

**Depends on:** Hub `multiroom-api` 0.1.22 (024), which changed when `RouteDestroyedEvent` is
published. Nothing in this extension has to change its `multiroom.require_api_version` for the fix.

**Effort:** Small.

## Problem

Since 024 the hub publishes exactly one `RouteDestroyedEvent` for every route that entered its route
index, including a route that was admitted but then failed to start (an unreachable source, a device
that would not open). The event is published synchronously, before `RouteService.createRoute`
throws. Before 024 such a route published nothing.

`TtsService.activate` still assumes the old behaviour. When `createRoute` throws a
`RuntimeException` that is not a `RouteAdmissionException`:

1. `PlaybackCompletionListener.onRouteDestroyed` runs first, on the event. It releases the task,
   logs `TTS_PLAYBACK_COMPLETED` and runs `onComplete`.
2. The hub's `AutoRemoveInputListener` unregisters the ephemeral input, since it was registered
   with `autoRemove=true`.
3. `createRoute` throws, and `abandon()` runs: `completionListener.cancel` finds nothing (harmless),
   `unregisterInput` throws because the input is already gone, which logs a WARN with a stack
   trace, and `onPlaybackComplete` runs a **second** time. `metrics.playbackFailed()` is counted as
   well as the completion.

The result is harmless today, since `onComplete` only drives a latch and the queue tolerates it. But
the logs are misleading: a failed announcement reports `TTS_PLAYBACK_COMPLETED` followed by a
WARN stack trace. The Javadoc of `abandon` ("no route exists, so core will never auto-remove it") and
of `PlaybackCompletionListener.cancel` ("will never reach `RouteDestroyedEvent` because its route was
never created") are no longer true for this path.

A `RouteAdmissionException` (a refusal: route limit, input already on the output, disabled
target) is not affected. A refusal happens before the route enters the index, so no event is
published and `abandon()` is still the only cleanup.

## Proposal

Make the two cleanup paths agree that each announcement ends once:

- **`abandon()` becomes idempotent.** It runs `onPlaybackComplete` and counts `playbackFailed` only
  if `completionListener.cancel(inputId)` actually removed a tracked task. If the destroyed event
  already handled it, `abandon()` does nothing but log at DEBUG.
- **Unregistering tolerates an input that is already gone.** Catch `IllegalArgumentException`
  from `unregisterInput` at DEBUG, as the hub's DLNA and REST play paths do, and keep the WARN for
  other failures.
- **Report a failed start as a failure.** `PlaybackCompletionListener.onRouteDestroyed` cannot
  tell a finished route from one that never played, because the event carries a route with status
  `STOPPING` in both cases. Two options:
  1. Let `activate` mark the task as "starting" until `createRoute` returns; a destroyed event for
     a task still marked starting is logged as `TTS_PLAYBACK_FAILED` and counted as a failure,
     and leaves `onComplete` to `abandon()`.
  2. Check `event.route().getStartedAt() == null`: a route that never became active has no start
     time. Simpler, but depends on a field the hub does not document as meaning that.
- Update the Javadoc of `abandon` and `cancel` to describe both paths.

## Open questions

- Should the hub document that `RouteDestroyedEvent` for a route that never started carries
  `startedAt == null`? If it does, option 2 is the clean one, and the same rule helps other
  consumers. That would be a hub backlog item.
- Is counting a failed start in `playbackFailed` only (and not in `playbackCompleted`) what the
  metrics dashboard expects? See [../metrics.md](../metrics.md).
