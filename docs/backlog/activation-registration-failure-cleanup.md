# Release an announcement's resources when registering its input fails

**Status:** Proposed, not started. Written 2026-10-10, while moving the open findings of the
2026-09-21 correctness review into the backlog.

**Origin:** Finding 1 of the local correctness review
(`docs/reviews/2026-09-21-correctness-review.md`, not tracked), re-checked against the code at
`923380c`.

**Depends on:** nothing. Related: [queue-stop-start-contract.md](queue-stop-start-contract.md) and
[reference-counted-cache-pins.md](reference-counted-cache-pins.md), the other ways a reservation is
left behind.

**Effort:** Small. A reordering in `TtsService.activate` and two or three test cases.

## Problem

`TtsService.activate` (`TtsService.java:259-269`) runs three steps outside the `try` that guards
`createRoute`:

1. `inputResolver.register(announcementId, audioFile)`
2. `deviceRegistryService.registerInput(input)`
3. `completionListener.track(task, onPlaybackComplete)`

If step 2 throws, `activateCountingEarlyFailures` counts a failed playback and rethrows.
`AnnouncementQueueManager.activate` then logs the exception and moves on to the next task.
The announcement is not tracked yet, so nothing releases the resolver mapping, the cache pin or
the temporary file. The release logic already exists: `PlaybackCompletionListener.cancel` does all
three, and `abandon` calls it. It just can't reach an announcement that isn't tracked.

The review's example trigger, a duplicate input id, cannot happen: the id is `tts-<random UUID>`
and always passes the hub's format check. So this is a defensive gap against an unexpected
`RuntimeException` from the hub, not a failure anyone has seen. Since 006 there is also no longer
a route snapshot to restore, so the review's "the room goes permanently silent" no longer applies.
What remains is a leaked pin or temporary file and a stale resolver entry, plus a failure that is
logged without this module's `TTS_PLAYBACK_FAILED` code.

## Proposal

- Track the announcement before registering its input, and put `registerInput` inside the guarded
  block. A failure there then goes through `abandon`: `cancel` releases the pin, the temporary file
  and the resolver mapping; `unregisterInput` already tolerates an input that is not registered;
  the failure is logged as `TTS_PLAYBACK_FAILED` and counted once.
- Check that moving `track` earlier is safe: a `RouteDestroyedEvent` for this input cannot arrive
  before a route exists, so tracking earlier only widens a window in which nothing can happen.
- Tests: `registerInput` throwing releases the pin (or deletes the temporary file) and the resolver
  entry, counts one failed playback and signals the queue once.

## Open questions

1. Should `inputResolver.register` move inside the guarded block too? It only puts an entry into
   a map, so it cannot realistically fail, but leaving it outside keeps one unguarded step.
2. Once every activation failure goes through `abandon`, is `activateCountingEarlyFailures` still
   needed, or can the queue's own `catch` be the only fallback?
