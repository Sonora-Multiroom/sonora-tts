# Make the announcement queue survive a stop and a restart

**Status:** Proposed, not started. Written 2026-10-10, while moving the open findings of the
2026-09-21 correctness review into the backlog.

**Origin:** Findings 2 and 8 of the local correctness review
(`docs/reviews/2026-09-21-correctness-review.md`, not tracked), re-checked against the code at
`923380c`.

**Depends on:** nothing. Related: [reference-counted-cache-pins.md](reference-counted-cache-pins.md).

**Effort:** Small. Two methods in `AnnouncementQueueManager`, a release hook for discarded tasks,
and tests.

## Problem

`TtsService` is a `SmartLifecycle` whose `stop()`/`start()` delegate to `AnnouncementQueueManager`,
and the manager's `start()` is written to be resumable. Two paths break that contract:

1. **`stop()` drops queued tasks without releasing what they hold**
   (`AnnouncementQueueManager.java:116-119`). `queues.values().forEach(LinkedBlockingQueue::clear)`
   discards tasks that `TtsService.announce` has already pinned in the cache, or spilled to a
   temporary file. Those tasks never reach `PlaybackCompletionListener`, so the pin stays until the
   process exits and the temporary file is never deleted. Since 006 there is no longer a route
   snapshot to restore, so the review's "the room stays silent" no longer applies. What remains is
   the leak.
2. **A worker can leave a dead entry in `workers`** (`AnnouncementQueueManager.java:92-93`). After
   a successful activation, `runWorker` returns on `!running` without calling
   `workers.remove(targetKey)`. If `start()` runs later, `ensureWorkerRunning` finds the dead thread
   through `computeIfAbsent` and never starts a new one, so that target's queue is never consumed
   again.

Both fire only when the extension is stopped and then started again inside one host process.
Nothing in this repository does that today. At context shutdown, the leak and the stale map entry
disappear with the process. Both are latent.

## Proposal

- In `runWorker`, remove the worker from `workers` on every exit path, for example in a `finally`
  that uses `workers.remove(targetKey, Thread.currentThread())` so it never removes a successor.
  Keep the existing re-check for an item that slipped in after the last `poll`.
- In `stop()`, drain each queue instead of clearing it, and release every drained task through a
  callback the manager receives from `TtsService` (unpin the cache key, or delete the temporary
  file). This is the same release `TtsService.announce` already performs when `enqueue` throws.
  Count each discarded task in the metrics, in whichever outcome the open question below settles.
- Tests: `stop()` with two tasks queued releases both reservations. `stop()` during an activation,
  then `start()` and a new `enqueue` for the same target, plays the new task.

## Open questions

1. How should a discarded announcement be counted: as a failed playback, as a queue rejection with
   reason `SHUTTING_DOWN`, or not at all? Today it disappears from the metrics.
2. Is this worth doing before the hub actually stops and restarts an extension's context on its
   own (`multiroom-ai` has no such feature today)? If not, this item could drop to "fix when that
   lands upstream".
