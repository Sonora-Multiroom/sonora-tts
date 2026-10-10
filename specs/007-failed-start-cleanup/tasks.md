---

description: "Task list for 007-failed-start-cleanup"
---

# Tasks: An Announcement That Fails To Start Ends Once, As A Failure

**Input**: Design documents from `/specs/007-failed-start-cleanup/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/observability.md](contracts/observability.md),
[quickstart.md](quickstart.md)

**Tests**: Included. Principle III (Test-First) requires a failing test before production code, so
every implementation task is preceded by the test that drives it. JUnit 5, Mockito, Logback log
capture (`captureServiceLog` in `TtsServiceTest`); no live provider, no host.

**Organization**: One phase per user story, in the spec's priority order (US1 and US2 P1; US3 P2).
A failed start cannot be provoked safely on production, so the failure outcomes are proven by unit
tests; production only checks that a normal announcement is unchanged.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (no dependency on an incomplete task; tasks in the same file touch
  different methods, so coordinate edits)
- **[Story]**: the user story the task belongs to (US1–US3)

## Path Conventions

Single Maven module: `src/main/java/multiroom/tts/…`, `src/test/java/multiroom/tts/…`, `docs/`
at the repository root.

## Rules that hold for every task

- **Do not cite spec IDs in code** (`FR-`, `SC-`, `US`, `R` numbers). State the rule in the comment.
- **One remover.** Exactly one of `PlaybackCompletionListener.onRouteDestroyed` (the route went
  live) and `PlaybackCompletionListener.cancel` (refused or failed start) removes the in-flight
  entry, and only the remover releases and runs the queue's callback. The hand-off stays on
  `ConcurrentHashMap.remove`; add no other state or lock.
- **The failure path keeps removing the input.** The hub auto-removes an `autoRemove` input only
  after a route on it went live (research R2), so after a refusal or failed start
  `TtsService.abandon` must still call `unregisterInput`. Do **not** implement the backlog item's
  literal proposal ("`abandon` does nothing if the event already handled it"): it would leak the
  input.
- **`abandon` counts `playbackFailed()` unconditionally**; only the queue callback is conditional
  on `cancel` returning `true`. The listener never counts anything.
- **No new metric, tag key or tag value.** `tts_playbacks_total` keeps `started` / `failed`.
- **`multiroom.require_api_version` stays `0.1.21`.** `Route.getStartedAt()` already exists there.
- **Mockito and `cancel`'s new return type**: `TtsServiceTest` mocks `PlaybackCompletionListener`,
  and a mocked `boolean` method returns `false`. Once `abandon` depends on it, every existing
  refusal/failure test would stop running the queue callback (the second queued announcement would
  never play). Stub `cancel` to return `true` in that test's set-up rather than editing assertions.

---

## Phase 1: Setup

**Purpose**: Version and a green baseline before any behaviour changes.

- [X] T001 Confirm `unzip -l ~/.m2/repository/ai/multiroom/multiroom-api/0.1.21/multiroom-api-0.1.21.jar` lists ~70 entries (not 7) and that `javap -cp <that jar> multiroom.api.model.Route` shows `getStartedAt()`; if the jar is empty, re-run the upstream install (never add a `<repository>`)
- [X] T002 In `pom.xml` change `<version>0.1.5</version>` to `<version>0.1.6</version>`; leave the parent `multiroom-extension-starter` `0.1.21` and `<multiroom.require_api_version>0.1.21</multiroom.require_api_version>` untouched. Run `mvn clean verify` and confirm the existing suite is green (depends on T001)

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: `cancel` reports whether it removed the announcement. Both P1 stories build on it.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T003 In `src/test/java/multiroom/tts/service/PlaybackCompletionListenerTest.java` write first: `cancel` on a tracked input returns `true` (extend `cancelUnpinsCacheAndReleasesResolverWithoutInvokingCallback`); `cancel` on an untracked input returns `false` (extend `cancelOnUntrackedInputIsANoOp`); a second `cancel` for the same input returns `false`; `cancel` after a matching `onRouteDestroyed` (route with `startedAt` set) returns `false`; `cancel` of a tracked task with `temporaryFile = true` deletes its audio file (a regression net: `release` already does this). See it fail to compile
- [X] T004 In `src/main/java/multiroom/tts/service/PlaybackCompletionListener.java` change `public void cancel(InputId)` to `public boolean cancel(InputId)`: `true` when it removed and released a tracked entry, `false` when nothing was tracked. Behaviour otherwise unchanged (still never runs the callback). Make T003 pass (depends on T003)
- [X] T005 In `src/test/java/multiroom/tts/service/TtsServiceTest.java` set-up (where `completionListener = mock(PlaybackCompletionListener.class)` is created) add `when(completionListener.cancel(any(InputId.class))).thenReturn(true);` so existing failure tests keep modelling "the failure path removed the entry". Run `mvn test -Dtest=PlaybackCompletionListenerTest,TtsServiceTest`: green (depends on T004)

**Checkpoint**: `mvn test` green; no observable behaviour has changed yet.

---

## Phase 3: User Story 1 - A failed start is reported once, as a failure (Priority: P1) 🎯 MVP

**Goal**: For a route admitted and then failed to start, the logs show one `TTS_PLAYBACK_FAILED`
line, no `TTS_PLAYBACK_COMPLETED` line and no input-removal warning; one failed playback is
counted and no started one.

**Independent Test**: With a simulated hub that publishes `RouteDestroyedEvent` (route without
`startedAt`) and then throws from `createRoute`, submit one announcement and inspect the captured
log lines, the `TtsMetrics` calls and `unregisterInput`.

### Tests for User Story 1 (write first, see them fail)

- [X] T006 [US1] In `src/test/java/multiroom/tts/service/PlaybackCompletionListenerTest.java`: give `routeTo` a `.startedAt(Instant.now())` (its existing tests describe a route that went live and finished), and add a helper `neverStartedRouteTo(InputId, String)` building the same route with status `RouteStatus.STOPPING` and no `startedAt`. Add tests: a destroyed event for a tracked input whose route has no `startedAt` leaves the entry tracked (a following `cancel` returns `true`), does not unpin the cache or call `inputResolver.release`, does not run the callback, and logs no `TTS_PLAYBACK_COMPLETED` (capture the listener's Logback logger as `TtsServiceTest.captureServiceLog` does for the service's); a never-started event for an untracked input is still ignored (depends on T005)
- [X] T007 [US1] In `src/test/java/multiroom/tts/service/TtsServiceTest.java` add a way to build the service with a **real** `PlaybackCompletionListener` (constructed with the test's mocked `audioCache` and `inputResolver`) — an overload of the existing `serviceWith(...)` helper or a field swapped before it is called; read the helper first and follow its shape. Add a helper `failAfterAdmission(PlaybackCompletionListener listener, String output)` returning a Mockito `Answer` that captures the `InputId` argument, calls `listener.onRouteDestroyed(new RouteDestroyedEvent(<route for that input, status STOPPING, no startedAt>))` and then throws `new RouteException("device would not open")` — the hub 0.1.22 ordering (research R1). Generalize `captureServiceLog` into `captureLog(Runnable action, Class<?>... loggers)` (keep `captureServiceLog` delegating to it) so a test can capture `TtsService` and `PlaybackCompletionListener` together (depends on T005)
- [X] T008 [US1] In `src/test/java/multiroom/tts/service/TtsServiceTest.java` add `aStartThatFailsAfterAdmissionIsReportedOnceAsAFailure`: with the real listener and `createRoute` answering `failAfterAdmission`, one announcement yields exactly one log event starting `TTS_PLAYBACK_FAILED` at ERROR with a throwable and containing `announcementId=` and `target=living-room`; no event starting `TTS_PLAYBACK_COMPLETED` (captured from both loggers with T007's `captureLog`); `metrics.playbackFailed()` once, `metrics.playbackStarted()` never; `deviceRegistryService.unregisterInput` once with the announcement's input; no WARN event (depends on T006, T007)
- [X] T009 [P] [US1] In `src/test/java/multiroom/tts/service/TtsServiceTest.java` add: when `unregisterInput` throws `new IllegalArgumentException("Input 'tts-…' is not registered")` after a failed start, no WARN is logged (a DEBUG line is allowed); when it throws `new IllegalStateException("boom")`, one WARN with the throwable is logged, as today. Rename/extend `aNonAdmissionRouteFailureIsStillAnError` so it also asserts the ERROR line starts with `TTS_PLAYBACK_FAILED` (depends on T005)

### Implementation for User Story 1

- [X] T010 [US1] In `src/main/java/multiroom/tts/service/PlaybackCompletionListener.java` `onRouteDestroyed`: after looking up the input, if the input is tracked and `event.route().getStartedAt() == null`, log at DEBUG `announcement {} route never started; left to the failure path` with the announcement id and return **without** removing the entry. Use `inFlight.get` for this check and keep `inFlight.remove` as the single decision point for a route that went live; an untracked input still returns early. Make T006 pass (depends on T006)
- [X] T011 [US1] In `src/main/java/multiroom/tts/service/TtsService.java` `activate`: change the generic `catch (RuntimeException e)` line from `Failed to create route for announcement {} on target {}` to `log.error("TTS_PLAYBACK_FAILED announcementId={} target={}", task.announcementId(), task.targetName(), e)`. The refusal branch is unchanged (depends on T008, T009)
- [X] T012 [US1] In `src/main/java/multiroom/tts/service/TtsService.java` `abandon`: split the `unregisterInput` catch into `catch (IllegalArgumentException alreadyRemoved)` logged at DEBUG (input already removed, nothing to do) and `catch (RuntimeException unregisterFailure)` logged at WARN with the throwable, as now. The hub's only other `IllegalArgumentException` from `unregisterInput` refuses a *static* input, which an announcement's `tts-<uuid>` input never is; say so in a one-line comment on the catch, so treating every `IllegalArgumentException` as "already removed" is a stated decision. Make T008 and T009 pass (depends on T010, T011)

**Checkpoint**: US1 tests green; the failed start produces one failure line and no completion.

---

## Phase 4: User Story 2 - The queue moves on exactly once (Priority: P1)

**Goal**: After a failed start the queue's completion callback runs once, the next queued
announcement plays, and everything held for the failed one is released once.

**Independent Test**: Queue two announcements for one target with the real listener; the first
fails after admission, the second succeeds. Count callback runs and observe the second
`createRoute` and `TTS_PLAYBACK_STARTED`.

### Tests for User Story 2

T014 is the failing test that drives T015. T013 already passes once T010 is in (the listener leaves
the never-started route to `abandon`, whose `cancel` then removes it and runs the callback once); it
is the end-to-end regression net for this story, not a red test.

- [X] T013 [US2] In `src/test/java/multiroom/tts/service/TtsServiceTest.java` add `aStartThatFailsAfterAdmissionMovesTheQueueOnOnce`: real listener; `createRoute` for `living-room` answers `failAfterAdmission` first and then returns a route; two announcements queued for `living-room`. Assert `createRoute` is called twice, `playbackFailed` once, `playbackStarted` once, `audioCache.unpin` / `inputResolver.release` exactly once for the first announcement's key/id (not twice), and the first input unregistered once (depends on T012)
- [X] T014 [US2] In `src/test/java/multiroom/tts/service/TtsServiceTest.java` add `abandonDoesNotSignalTheQueueWhenTheEndWasAlreadyHandled`: with the mocked listener, stub `cancel` to return `false` for this test and `createRoute` to throw a `RouteException`; assert `playbackFailed` is still counted once, `unregisterInput` is still called once, and the queue callback is not run (observe it through the next queued announcement: queue two for the same target and verify with `after(200)` that the second `createRoute` never happens). See it fail: today `abandon` runs the callback regardless. Pins the safety net for the case where the listener ever completes a failed start (research R3 risk) (depends on T012)

### Implementation for User Story 2

- [X] T015 [US2] In `src/main/java/multiroom/tts/service/TtsService.java` `abandon`: keep `metrics.playbackFailed()` first and unconditional; replace `completionListener.cancel(...)` with `boolean removed = completionListener.cancel(task.inputId());`; always attempt `unregisterInput` (T012's catches); run `onPlaybackComplete.run()` only `if (removed)`, otherwise log at DEBUG that the announcement's end was already handled. Make T013 and T014 pass (depends on T013, T014)
- [X] T016 [US2] Rewrite the Javadoc of `TtsService.abandon` and `PlaybackCompletionListener.cancel` (`src/main/java/multiroom/tts/service/`) to describe both orders: on hub 0.1.22+ the hub publishes `RouteDestroyedEvent` for an admitted route that failed to start before `createRoute` throws, and the listener leaves such a never-started route tracked for this path; on 0.1.21 a failed start usually publishes no event, and any event that does arrive (a route stopped while starting) carries no start time and is left to this path the same way. State that removing the input is still this path's job because the hub auto-removes an input only after a route on it went live, that an already-removed input is tolerated, and that the callback runs only when `cancel` removed the entry. Replace every statement that a failed route never produces an event. In `PlaybackCompletionListener`'s class Javadoc, add that a destroyed route without a start time is left to the failure path, and keep the "never calls `unregisterInput`" paragraph accurate (the listener still never does) (depends on T015)

**Checkpoint**: US1 and US2 tests green; the queue is signalled once in every failure ordering.

---

## Phase 5: User Story 3 - Refusals and normal playback are unchanged (Priority: P2)

**Goal**: A refused announcement, a completed one, a hub 0.1.21 failed start and the "ended before
`createRoute` returned" race behave as the contract table says.

**Independent Test**: Run the existing refusal and completion tests plus the new ordering tests;
compare logs, counts and queue signals with [contracts/observability.md](contracts/observability.md).

### Tests for User Story 3

- [X] T017 [P] [US3] In `src/test/java/multiroom/tts/service/TtsServiceTest.java` add `aStartThatFailsOnAnOlderHubWithoutAnEventIsReportedTheSame`: real listener, `createRoute` throws `RouteException` with no event; assert the same outcome as T008 (one `TTS_PLAYBACK_FAILED`, no completion, `playbackFailed` once, input unregistered once) and that a second queued announcement plays (depends on T015)
- [X] T018 [P] [US3] In `src/test/java/multiroom/tts/service/TtsServiceTest.java` add `aRouteThatEndsBeforeCreateRouteReturnsIsCompleted`: real listener; `createRoute` answers by calling `listener.onRouteDestroyed` with a route **with** `startedAt` set, then returns that route; assert `TTS_PLAYBACK_STARTED` and `TTS_PLAYBACK_COMPLETED` are both logged, `playbackStarted` once, `playbackFailed` never, `unregisterInput` never, and a second queued announcement plays (the callback ran once) (depends on T015)
- [X] T019 [P] [US3] In `src/test/java/multiroom/tts/service/TtsServiceTest.java` add a refusal test with the real listener: `createRoute` throws `refusal("living-room")` (no event — a refused route is never admitted); assert one `TTS_PLAYBACK_REFUSED` WARN without a throwable, no `TTS_PLAYBACK_FAILED` and no `TTS_PLAYBACK_COMPLETED`, `playbackFailed` once, `unregisterInput` once, and the next queued announcement plays. Confirm the existing `aRefusedRouteReleasesEverythingAndMovesTheQueueOn`, `aRefusalIsLoggedWithTheHostsReasonAndOutput` and `aRefusedGroupAnnouncementReleasesEverything` pass unchanged (depends on T015)
- [X] T020 [P] [US3] In `src/test/java/multiroom/tts/service/PlaybackCompletionListenerTest.java` confirm the existing completion tests (`matchingEventUnpinsCacheAndReleasesResolver`, `duplicateEventForSameAnnouncementIsIdempotent`, `temporaryFileIsDeletedOnCompletion`, `invokesCompletionCallbackAfterReleasing`, `cancelThenMatchingEventIsIdempotent`) pass with `routeTo` now setting `startedAt`, and add: a never-started event followed by a started event for the same input completes it once (callback once) (depends on T010)

### Implementation for User Story 3

- [X] T021 [US3] Fix any production regression T017–T020 reveal in `src/main/java/multiroom/tts/service/TtsService.java` or `PlaybackCompletionListener.java` without weakening a test; if all pass, no change (depends on T017–T020)

**Checkpoint**: `mvn test` green; every row of the contract table is covered by a test.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T022 [P] Update `docs/metrics.md` under `tts_playbacks_total`: an announcement whose route was admitted but failed to start counts as `failed`, never as `started`, and is logged as `TTS_PLAYBACK_FAILED` with the exception; each announcement taken off its queue is counted exactly once, so `started + failed` equals the announcements taken off queues
- [X] T023 [P] Update `AGENTS.md`: in the gotcha "Never Unregister The Announcement's Ephemeral Input", restate the exception — after a refusal **or a route admitted and failed to start**, no route ever went live, so the hub's auto-remove never fires and `TtsService.abandon` unregisters the input; on hub 0.1.22+ a `RouteDestroyedEvent` with no `startedAt` arrives first and `PlaybackCompletionListener` leaves it to that path; an "is not registered" answer is tolerated at DEBUG. Add the `007-failed-start-cleanup` row to the Features table (version 0.1.6, path `specs/007-failed-start-cleanup/`): a failed start ends once, as `TTS_PLAYBACK_FAILED`, with one queue signal; no API bump
- [X] T024 [P] Update `README.md`: status line to version 0.1.6 with features 001 to 007, the deployment-order paragraph's version (0.1.6 still needs only multiroom-api 0.1.21 and runs unchanged on 0.1.22), and a row for `specs/007-failed-start-cleanup/` in the specs table
- [X] T025 [P] Delete `docs/backlog/failed-start-double-cleanup.md` and remove its row from `docs/backlog/INDEX.md`
- [X] T026 Run `mvn clean verify` (tests, JaCoCo 80 % floor, enforcer, no-root-`application.yml` gate) and fix anything red; confirm `unzip -p target/multiroom-tts-0.1.6.jar META-INF/MANIFEST.MF` still shows `Require-API-Version: [0.1.21,0.2.0)` (depends on T016, T021–T025)
- [X] T027 Merge gate part two (quickstart §2): `mvn deploy -Plocal`, start the local host from the sonora-multiroom checkout, and confirm `GET /actuator/extensions` lists `tts` at `0.1.6`, not `REJECTED` (depends on T026)
- [X] T028 Production check (quickstart §3, Principle VII), **only with the user's permission for the deploy, the restart and the audible announcement**: `mvn deploy`, then one `piper-local` announcement at low volume following the shared audible-test rules (record and restore volumes, stop test routes first, check `/proc/asound/card*/pcm*p/sub*/status` and `audio.output.routes` afterwards); in Loki (`app=multiroom-core`) one `TTS_PLAYBACK_STARTED` and one `TTS_PLAYBACK_COMPLETED` for its `announcementId`, and `tts_playbacks_total{outcome="started"}` up by one (depends on T027)
- [X] T029 Create `specs/007-failed-start-cleanup/verification.md` recording T026–T028 with commands, dates and counts (tests run, coverage, extension inventory, Loki lines), and the T001 API-jar check. State that the counter balance (`started + failed` = announcements taken off queues) for a failed start is verified by unit tests only (T008, T013, T014, T017), since a failed start cannot be provoked safely on production (depends on T028)
- [ ] T030 Offer the user (do not file from here) a sonora-multiroom backlog item: document on `RouteDestroyedEvent` or `Route` that `startedAt == null` means the route never went live, which this extension now relies on (plan "Follow-ups outside this repository")
- [X] T031 Review before merge (Principle V): open a pull request from `007-failed-start-cleanup` to `master` (only when the user asks to push), review the branch (e.g. `/code-review`), and fix or justify each finding; run SpotBugs on the changed classes (recommended, not a gate) and record the outcome in `verification.md` (depends on T029)

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: none.
- **Foundational (Phase 2)**: after Setup. Blocks every story (`cancel`'s return type, the mock stub).
- **US1 (Phase 3)**: after Foundational. The MVP.
- **US2 (Phase 4)**: after US1 — `abandon`'s idempotence (T015) builds on T012's restructured
  `abandon`, and its test uses T007's real-listener helper.
- **US3 (Phase 5)**: after US2 — the regression tests assert the final `abandon`.
- **Polish (Phase 6)**: after all stories.

### Story dependencies

US1 → US2 → US3 is sequential: all three change the same two methods (`TtsService.abandon`,
`PlaybackCompletionListener.onRouteDestroyed`) and the same two test classes. Independence here is
of *verification* (each story has its own tests), not of files.

### Within each story

Tests first and failing, then the production change, then the checkpoint `mvn test`.

## Parallel Opportunities

- **Phase 3**: T009 can be written alongside T006–T008 (different test methods; same file, so
  coordinate edits).
- **Phase 5**: T017, T018, T019 (same file, independent methods) and T020 (other file) in parallel.
- **Phase 6**: T022, T023, T024, T025 touch different files and run in parallel.

### Parallel example: Phase 6

```text
T022 docs/metrics.md
T023 AGENTS.md
T024 README.md
T025 docs/backlog/failed-start-double-cleanup.md + docs/backlog/INDEX.md
```

## Implementation Strategy

### MVP (US1)

Phases 1–3: the failed start is logged once as `TTS_PLAYBACK_FAILED` with no completion and no
spurious WARN. Even before US2, the double queue signal is harmless today (the queue tolerates
it), so US1 alone fixes what the operator sees.

### Incremental delivery

1. Setup + Foundational → green baseline with `cancel` returning `boolean`.
2. US1 → correct logs and counts for a failed start.
3. US2 → one queue signal, `abandon` idempotent.
4. US3 → regression net for refusal, completion, the older hub and the race.
5. Polish → docs, backlog cleanup, merge gate, production check (with permission), verification,
   review.
