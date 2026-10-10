# Feature Specification: An Announcement That Fails To Start Ends Once, As A Failure

**Feature Branch**: `007-failed-start-cleanup`

**Created**: 2026-10-09

**Status**: Draft

**Input**: User description: the backlog item
[docs/backlog/failed-start-double-cleanup.md](../../docs/backlog/failed-start-double-cleanup.md),
"A route that fails to start is cleaned up twice". It came from the 2026-10-08 review of the hub's
`024-route-membership-single-owner` branch (sonora-multiroom
`docs/reviews/Code_Review_2026-10-08_route-membership-single-owner.md`, row 6).

**Upstream reference**: hub feature 024 (multiroom-api 0.1.22). Since 024 the hub announces the end
of every route that it admitted, including one that was admitted and then failed to start (an
unreachable source, a device that would not open), and it does so before telling the caller that
the start failed. Before 024 such a route ended silently. This feature adapts to that; it changes
nothing upstream.

## Background

When the hub admits an announcement's route but the route then fails to start, the extension today
handles the end of that announcement twice:

1. On the hub's end-of-route notice it treats the announcement as finished normally: it releases
   what it held, logs `TTS_PLAYBACK_COMPLETED` and tells the target's queue to move on.
2. The start failure then reaches the extension, which handles it as a failure as well: it removes
   the announcement's temporary input, counts a failed playback and tells the queue to move on a
   **second** time.

The backlog item also expected the hub to remove the temporary input on the notice, making the
extension's own removal fail with a warning. Planning research found otherwise: the hub removes such
an input only after a route on it has actually started, so after a failed start the extension's
removal is the only one, and it must keep happening (see [research.md](research.md)).

Nothing breaks today, but an operator reading the logs sees a failed announcement reported as
completed. A refusal by the hub (route limit reached, input
already on the output, disabled target) is not affected: a refused route is never admitted, the hub
sends no end-of-route notice, and the failure path remains the only cleanup.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A failed start is reported once, as a failure (Priority: P1)

An operator sends an announcement to a speaker that cannot open its device. The announcement does
not play. The logs show one failure for it, with the reason, and no "completed" line and no
unrelated warning. The metrics count one failed playback for it and no started one.

**Why this priority**: The logs and metrics are how the operator learns that announcements are not
being heard. Reporting a failure as a completion hides exactly the fault they need to see.

**Independent Test**: With a hub whose route start fails after admission (simulated), submit one
announcement and inspect the log lines and the playback counters for it.

**Acceptance Scenarios**:

1. **Given** the hub admits an announcement's route and the route then fails to start, **When**
   the extension handles the outcome, **Then** exactly one failure line is logged for that
   announcement and no `TTS_PLAYBACK_COMPLETED` line is logged for it.
2. **Given** the same failed start, **When** the playback counters are read, **Then** the failed
   count has risen by exactly one and the started count has not risen.
3. **Given** the same failed start, **When** handling completes, **Then** the announcement's
   temporary input is no longer registered with the hub, and no warning reports that it could not
   be removed.

---

### User Story 2 - The queue moves on exactly once (Priority: P1)

After a failed start, the next announcement waiting for the same target plays, and the target's
queue is told only once that the failed announcement is over.

**Why this priority**: The queue tolerates a second signal today only by accident. A future change
to the queue (for example counting what it has played, or chaining work on that signal) would turn
the second signal into a real fault, such as skipping or double-starting the next announcement.

**Independent Test**: Queue two announcements for one target, make the first one's start fail after
admission, and observe how many times the queue is told the first has ended and that the second
plays.

**Acceptance Scenarios**:

1. **Given** two announcements queued for one target and the first fails to start after
   admission, **When** the failure is handled, **Then** the queue is told once that the first has
   ended, and the second starts.
2. **Given** an announcement that fails to start after admission, **When** handling completes,
   **Then** the extension holds nothing more for it: its cached audio is no longer held for
   playback, its audio is no longer reachable by its address, and any one-off audio file is
   deleted.

---

### User Story 3 - Refusals and normal playback are unchanged (Priority: P2)

An announcement the hub refuses, and an announcement that plays to the end, are reported exactly
as before.

**Why this priority**: The fix touches the paths both of them use; they must not regress.

**Independent Test**: Run a refused announcement and a normally completed one and compare their
logs, counters and queue signals with the current behaviour.

**Acceptance Scenarios**:

1. **Given** the hub refuses an announcement's route, **When** the refusal is handled, **Then**
   `TTS_PLAYBACK_REFUSED` is logged with the hub's reason, one failed playback is counted, the
   announcement's input is removed by the extension, and the queue is told once.
2. **Given** an announcement whose route starts and plays to the end, **When** its route ends,
   **Then** `TTS_PLAYBACK_STARTED` and `TTS_PLAYBACK_COMPLETED` are logged, one started playback
   is counted, and the queue is told once.

---

### Edge Cases

- **A hub older than 024** usually sends no end-of-route notice for a route that failed to start
  (it may for a route stopped while starting). The extension must still clean up once, report one
  failure and remove the input itself, as it does today.
- **A very short announcement finishes on the hub's own thread before the start call returns to
  the extension.** If the hub had already reported it started, it played and must be reported as
  completed, not as a failure. If its audio ran out before the hub reported it started, the hub
  itself reports the start as failed, and so does the extension.
- **Removing the input fails for a reason other than "already removed"** (an unexpected hub
  error). It is still logged as a warning with its detail.
- **The hub sends the end-of-route notice twice** for one route. The announcement is still handled
  once (already true today).
- **A failure before the route step** (registering the input or tracking the announcement fails).
  Counted once as a failed playback, as today.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Every announcement taken off a queue MUST end exactly once: its resources released
  once, its queue told once, and its outcome counted once — whichever of the hub's end-of-route
  notice and the start failure reaches the extension first.
- **FR-002**: An announcement whose route was admitted but failed to start MUST be reported as a
  failure: one failure log line naming the announcement and its target, one failed playback
  counted, and no completed log line.
- **FR-003**: An announcement whose route started MUST be reported as completed when its route
  ends, including when the route ends before the start call has returned to the extension.
- **FR-004**: After a refusal or a failed start, the extension MUST remove the announcement's
  temporary input, since the hub does not. Finding it already removed (should a future hub remove
  it) MUST NOT produce a warning; it MAY be noted at debug level. Any other failure to remove the
  input MUST still be logged as a warning with its detail.
- **FR-005**: A route the hub refuses MUST be handled as today: `TTS_PLAYBACK_REFUSED` with the
  hub's reason and output, one failed playback, the input removed by the extension, the queue told
  once.
- **FR-006**: The behaviour MUST be the same on a hub that usually sends no end-of-route notice for
  a route that failed to start (multiroom-api 0.1.21) and on one that always does (0.1.22 and later). The
  extension's minimum required API version MUST NOT be raised for this feature.
- **FR-007**: The descriptions in the code of the start-failure cleanup and of the cancellation of
  a tracked announcement MUST describe both orders of events (notice first, or no notice at all),
  replacing the statements that a failed route never produces a notice.
- **FR-008**: The metrics documentation MUST state that an announcement that was admitted but
  failed to start counts as `failed`, never as `started`.

### Key Entities

- **Announcement in flight**: an announcement taken off its target's queue whose end has not yet
  been handled. It ends exactly once, with one outcome: completed (it started) or failed (it was
  refused, or it was admitted and did not start).
- **End-of-route notice**: the hub's announcement that a route has ended. Since 024 it is sent for
  every admitted route, whether or not it started.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For an announcement whose route fails to start after admission, the logs contain 0
  completion lines and 0 input-removal warnings for it, and exactly 1 failure line.
- **SC-002**: For every announcement taken off a queue, in each of the four cases (completed,
  refused, admitted-but-failed on a current hub, failed on an older hub), the queue is told of its
  end exactly once and exactly one playback outcome is counted.
- **SC-003**: The playback counters on a running hub add up: started plus failed equals the number
  of announcements taken off their queues.
- **SC-004**: Refused and normally completed announcements produce the same log lines, counts and
  queue signals as before this feature.

## Assumptions

- The hub's end-of-route notice for a route that failed to start is delivered before the start
  failure reaches the extension, though possibly on another thread (the hub's event dispatch may be
  drained by another thread while the starting caller waits; see research R1). The extension must
  still be correct if this ordering changes or the notice never arrives.
- How the extension tells a route that never started from one that finished is a planning decision
  (resolved in [research.md](research.md)). The chosen signal, the route's missing start time in
  the notice, is not documented by the hub as meaning that; documenting it would be a hub backlog
  item, not part of this feature.
- `tts_playbacks_total` keeps its existing `outcome` values (`started`, `failed`); no new tag value
  or metric is added. A failed start was already counted as `failed`; this feature only removes the
  completion that was reported alongside it.
- Verifying on the production Raspberry Pi requires a route that fails after admission, which
  cannot be produced safely there on demand. Verification relies on tests with a simulated hub plus
  the usual deploy-and-inspect smoke run.
- The backlog item is deleted once this feature ships.
