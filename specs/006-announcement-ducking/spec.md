# Feature Specification: Announcements Over What Is Playing (Ducking)

**Feature Branch**: `006-announcement-ducking`

**Created**: 2026-10-01

**Status**: Draft

**Input**: User description (implied by the upstream specification handed over with the command):
"multiroom-ai 023 (`023-multi-route-output-mixing`, multiroom-api 0.1.21) lets several routes play on
one output and adds the *duck-others* join mode, which lowers everything else on the output while the
route plays. Its spec names this extension as the intended first adopter. Make announcements play
over whatever the room is doing — music lowered, never stopped — instead of stopping the target's
routes and recreating them afterwards."

**Upstream reference**: `D:\projects-multiroom\multiroom-ai\specs\023-multi-route-output-mixing\`
(spec, plan, research R6–R12, `contracts/java-api.md`). This feature consumes it; it changes nothing
upstream.

## Clarifications

### Session 2026-10-01

- Q: Can the playback mode be chosen per request, or only in configuration? → A: Both. The built-in
  default is *duck-others*; configuration may set another default; a request may set the mode for
  that one announcement (FR-009).
- Q: Does a host refusal get its own metric reason? → A: No. It counts as a failed playback in the
  existing metrics; the log line carries the host's reason and the output (FR-008).
- Q: Must announcements to overlapping targets (a group and one of its rooms) wait for each other? →
  A: No. Queues stay per target; overlapping announcements may play at once, the newer on top (FR-004).
- Q: Can the default playback mode be set per room or group? → A: No. One default for the whole
  extension; a request may override it (FR-009).

### Session 2026-10-02

- Q: When the host refuses an announcement because the room is at its route limit, is it retried or
  dropped? → A: Dropped: logged, counted as a failed playback, and the queue moves on (FR-007).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The music keeps playing under an announcement (Priority: P1)

Music is playing in the kitchen. An automation announces "the washing machine has finished" there.
The music does not stop: it drops to a quiet background level, the announcement is heard clearly
over it, and the music comes back up when the announcement ends — from where it is by then, with no
gap, no restart and no lag while a route is rebuilt.

**Why this priority**: This is the whole feature. Today an announcement stops the room's routes when
it is accepted, and recreates them after it ends, so every announcement costs two audible gaps and a
restart of the music (a stream re-buffers, a file starts over).

**Independent Test**: Play a continuous source on one output, send one announcement to that output,
and listen: the source never goes silent, is quieter while the announcement plays, and is back at its
previous level within a second of the announcement ending.

**Acceptance Scenarios**:

1. **Given** music is playing on an output, **When** an announcement is sent to that output, **Then**
   the music keeps playing without a gap, lowered while the announcement plays, and the announcement
   is heard at full level.
2. **Given** an announcement is playing over lowered music, **When** the announcement ends, **Then**
   the music returns to its previous level and continues from where it is, not from where it was when
   the announcement began.
3. **Given** music is playing on an output, **When** an announcement is sent to it, **Then** no route
   on that output is stopped or created by this extension apart from the announcement's own, and the
   route that was playing keeps its identity throughout (an integration showing "current source", such
   as Home Assistant, shows the music during and after the announcement).
4. **Given** nothing is playing on an output, **When** an announcement is sent to it, **Then** it
   plays exactly as it does today, and the output is silent again afterwards.
5. **Given** an announcement was accepted for a busy output, **When** it is still waiting in that
   target's queue, **Then** the music on that output keeps playing at its normal level until the
   announcement actually starts.

---

### User Story 2 - Group announcements over group music (Priority: P2)

Music plays on the "downstairs" group. An announcement is sent to the whole group, or to one room in
it.

**Why this priority**: Group targets are an existing, supported target type, and their behaviour must
be defined; but the single-room case already delivers the value.

**Independent Test**: Play music on a group, announce on the group and then on one member, and
listen in each room.

**Acceptance Scenarios**:

1. **Given** music is playing on a group, **When** an announcement is sent to the group, **Then** it
   is heard on every member, in sync, and the music is lowered on every member.
2. **Given** music is playing on a group, **When** an announcement is sent to one member output,
   **Then** the music is lowered on that output only and continues unchanged, and in sync, on the
   other members.
3. **Given** different music plays on two members of a group, **When** an announcement is sent to the
   group, **Then** each member's own music is lowered under it and restored afterwards.

---

### User Story 3 - A refused announcement explains itself and leaves the room alone (Priority: P2)

The host limits how many routes may play on one output at once. An announcement that the host refuses
must fail cleanly: the room keeps playing what it was playing, and the operator can see why.

**Why this priority**: Under the old behaviour the target was always emptied first, so the host never
refused an announcement. Joining instead of replacing introduces a new way to fail, and it must not
leave state behind.

**Independent Test**: Fill an output to the host's route limit, send an announcement to it, and
confirm that nothing already playing changes, the failure is logged with the host's reason, and it is
counted in the extension's metrics.

**Acceptance Scenarios**:

1. **Given** an output already holds the host's maximum number of routes, **When** an announcement to
   it reaches the front of its queue, **Then** it is not played, every route already on the output
   keeps playing unchanged, and the log names the announcement, the target, the output and the host's
   reason.
2. **Given** an announcement was refused by the host, **Then** nothing it reserved is left behind: no
   registered input, no pinned cache entry, no temporary file, no resolver entry, and the next queued
   announcement for that target starts.
3. **Given** a group announcement where one member is at the route limit, **Then** it plays on no
   member at all (the host admits a group route on all members or none).

---

### User Story 4 - Choose not to lower the room (Priority: P3)

Some announcements are better heard alongside the music at equal level — a short chime, or a room
where the operator does not want the music to dip. The operator sets this once in configuration; a
caller may also choose it per request.

**Why this priority**: Lowering is the right default for speech; this is a refinement for a minority
of uses, and it costs one setting because the host already supports the mode.

**Independent Test**: Configure the extension (or send a request) to mix rather than lower, announce
over music, and confirm the music level does not change.

**Acceptance Scenarios**:

1. **Given** no playback mode is configured or requested, **When** an announcement plays over music,
   **Then** the music is lowered (*duck-others*).
2. **Given** the configured playback mode is *mix*, **When** an announcement plays over music,
   **Then** both are heard and the music's level does not change.
3. **Given** any configured mode, **When** a request names a mode, **Then** the request's mode is
   used for that announcement only.
4. **Given** a request or configuration names a mode other than *duck-others* or *mix* (for example
   *replace*, or a misspelling), **Then** a request is rejected with `INVALID_REQUEST` before any
   synthesis and a configuration aborts start-up naming the extension and the key.

---

### Edge Cases

- **Two announcements for the same target**: they still play one after another, in arrival order;
  the second never lowers the first (the per-target queue is unchanged).
- **Announcements for overlapping targets** (one to the group, one to a member, each with its own
  queue): they may play at the same time on the shared output. The host puts the newer one on top and
  lowers the older one with the music; each is restored as the one above it ends. This replaces
  today's behaviour, where the second announcement stopped the first one's route.
- **Another integration starts a source on the output during an announcement** (Home Assistant source
  select, Chromecast auto-route, a REST replace): the announcement is not cut off; the new source
  starts lowered beneath it and comes up when it ends. This is the host's rule; nothing here
  restores or recreates anything.
- **The music is stopped by someone else during an announcement**: the announcement plays to its
  end, and the room is silent afterwards. This extension never recreates a route it did not create.
- **An explicit stop of everything on the output** (MQTT STOP, REST stop-all) during an announcement:
  the announcement ends too, its resources are released, and the next queued announcement starts.
- **A very short announcement** (shorter than the host's fade): handled by the host without a click;
  nothing changes here.
- **The host's ducking level and fade time**: system-wide host settings; this extension neither sets
  nor overrides them.
- **A host older than multiroom-api 0.1.21**: the host refuses to load this JAR at start-up because of
  its declared API requirement; the extension is absent rather than half-working. Upgrading the host
  first is part of deployment.
- **Shutdown during an announcement**: the current announcement finishes and the queue is discarded,
  as today; there is no longer anything to restore.
- **A route that was waiting to be restored at upgrade time**: cannot exist — restoration state was
  in memory only and is gone after the restart the upgrade requires.

## Requirements *(mandatory)*

### Functional Requirements

**Playback**

- **FR-001**: An announcement MUST be played by adding its own route to the target alongside whatever
  already plays there, in the effective playback mode (FR-009), and MUST NOT stop, pause or recreate
  any other route on the target — before, during or after playback.
- **FR-002**: The extension MUST NOT keep a snapshot of the target's routes, and MUST NOT recreate
  routes when an announcement ends or fails. Restoring the other routes' level is the host's job and
  happens without any action from this extension.
- **FR-003**: Accepting an announcement into a target's queue MUST NOT change anything audible on the
  target. Only the announcement's own start does.
- **FR-004**: The per-target queue MUST keep serializing announcements for one target in arrival
  order, as today, so two announcements to the same target never overlap. Queues MUST stay keyed by
  target, not by output: announcements to overlapping targets (a group and one of its members) are
  not serialized against each other.
- **FR-005**: Completion MUST still be observed from the host's route-destroyed signal, never a timer,
  and MUST still release everything the announcement held: cache pin, resolver entry, temporary file,
  and the queue slot. The announcement's input MUST still be registered as auto-removed and never
  unregistered by this extension after its route exists.
- **FR-006**: A group target MUST still be played as one route to the group, so its members stay in
  sync.

**Refusal by the host**

- **FR-007**: When the host refuses the announcement's route (route limit, or any other route
  failure), the extension MUST release everything the announcement reserved — including unregistering
  its input, since no route exists to auto-remove it — leave every other route on the target untouched,
  and move the target's queue on to the next announcement. A refused announcement MUST NOT be
  retried: announcements are time-sensitive, and a late one is worse than none.
- **FR-008**: A host refusal MUST be logged with the announcement id, the target, and the host's reason
  and offending output when the host gives them, and MUST be counted as a failed playback in the
  existing metrics without adding a tag key to any existing metric and without any caller text as a
  tag value.

**Playback mode**

- **FR-009**: The effective playback mode of an announcement MUST be the mode named in the request if
  any, otherwise the configured default, otherwise *duck-others*. The configured default is one value
  for the whole extension; there is no per-target default.
- **FR-010**: Only *duck-others* and *mix* MUST be accepted as playback modes, in configuration and
  per request, case-insensitively. Any other value MUST be rejected: per request with
  `INVALID_REQUEST` (400) before any cache lookup or synthesis; in configuration by aborting start-up
  with a message naming this extension and the key.
- **FR-011**: The playback mode MUST NOT be part of the cache key: the same text in another mode is
  the same audio and MUST be a cache hit.

**Compatibility and contract**

- **FR-012**: Every existing request MUST remain valid and behave as described here without change;
  the playback-mode request field is optional, and the success response's shape is unchanged.
- **FR-013**: The extension MUST declare multiroom-api 0.1.21 as its minimum, so that a host without
  join modes refuses to load it instead of loading an extension that relies on them.
- **FR-014**: The REST contract, configuration reference and README MUST describe the new playback
  behaviour, the playback-mode field and setting, the host-side ducking settings that govern the
  level and fade, and the deployment order (host first).

### Key Entities

- **Announcement task**: a ready-to-route announcement — its file, target and playback mode. It no
  longer carries a snapshot of the target's routes.
- **Playback mode**: how the announcement's route joins the target: *duck-others* (default; lowers
  everything else while it is the newest lowering route) or *mix* (equal level). A subset of the
  host's join modes; *replace* is deliberately excluded.
- **Playback settings** (configuration): the default playback mode for every announcement.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Music playing on a target is interrupted for 0 ms by an announcement — continuously
  audible before, during and after it, with no restart of the source.
- **SC-002**: The music is back at its previous level within 1 second of the announcement's last
  audible sample (the host's own target for ducking release).
- **SC-003**: An announcement on a busy output starts no later than one on an idle output does today
  (a cache hit stays under 1 second from request to sound), because nothing has to be stopped first.
- **SC-004**: Across 50 consecutive announcements over music on the production device, zero routes
  other than the announcements' own are stopped or created, as seen in the host's route events.
- **SC-005**: A refused announcement leaves 0 resources behind (registered inputs, pinned cache
  entries, temporary files, resolver entries) and changes nothing audible on the target.
- **SC-006**: 100% of existing request bodies are accepted unchanged, and every pre-upgrade cache
  entry is still a hit.
- **SC-007**: Home Assistant shows the room's music as its source throughout an announcement and
  after it, with no flicker to the announcement or to "none".

## Assumptions

- **Replace is not offered.** Replace without restoration would silence the room permanently after
  an announcement; replace with restoration is the stop–restore behaviour this feature exists to
  remove. Dropping it lets the snapshot and restore machinery go.
- **The mode is passed per route, not declared on the input.** Announcement inputs are ephemeral and
  created by this extension for each announcement, so naming the mode on the route request is the
  direct form; the result is the same.
- **The per-target queue stays.** The host would let two announcements on one output overlap, with the
  newer on top and the older lowered underneath; for speech that means a listener misses part of the
  first one, so serial playback per target is kept. Overlapping *targets* (a group and its member) are
  not coordinated, as today.
- **The host's ducking settings are the only ducking settings.** Level (about −15 dB) and fade (about
  150 ms) are system-wide host configuration; per-announcement or per-extension levels are out of
  scope.
- **Refusals happen after the request was accepted.** Routing runs when the announcement reaches the
  front of its queue, after the caller has had its success response, so a host refusal is reported in
  logs and metrics, not to the caller. Predicting a refusal at request time would be racy and is out
  of scope.
- **Deployment order**: the host (multiroom-ai with 023) is upgraded first, then this JAR is
  redeployed; the starter parent and the API requirement move to 0.1.21 together.
- **Audible behaviour is verified on the production device** after deployment, as for earlier
  playback-affecting changes; the unit tests cover the routing calls and cleanup.
