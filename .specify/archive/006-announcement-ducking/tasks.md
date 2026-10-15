---

description: "Task list for 006-announcement-ducking"
---

# Tasks: Announcements Over What Is Playing (Ducking)

**Input**: Design documents from `/specs/006-announcement-ducking/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/tts-rest-api.yaml](contracts/tts-rest-api.yaml),
[contracts/configuration.md](contracts/configuration.md), [quickstart.md](quickstart.md)

**Tests**: Included. Principle III (Test-First) requires a failing test before production code, so
every implementation task is preceded by the test that drives it. JUnit 5, Mockito,
`@WebMvcTest`, `ApplicationContextRunner`; no live provider, no host.

**Organization**: One phase per user story, in the spec's priority order (US1 P1; US2 and US3 P2;
US4 P3). Audible behaviour is verified on the production Pi in the Polish phase.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: the user story the task belongs to (US1–US4)

## Path Conventions

Single Maven module: `src/main/java/multiroom/tts/…`, `src/test/java/multiroom/tts/…`, `docs/`
at the repository root.

## Rules that hold for every task

- **Do not cite spec IDs in code** (`FR-`, `SC-`, `US`, `R` numbers). State the rule in the comment.
- **This module never stops, pauses or recreates a route it did not create**, and makes no
  `RouteService` call other than the announcement's own `createRoute(input, target, mode)`.
- **Only `DUCK_OTHERS` and `MIX` are ever passed to the host.** `RouteJoinMode.REPLACE` must not
  appear in production code.
- **Never unregister the input of a route that was created** (`autoRemove` owns it). Unregister only
  when `createRoute` threw, because then no route exists to auto-remove it.
- **No new metric tag key, and no caller text as a tag value.** The playback mode, the host's
  refusal reason and the output id go to logs only.
- **The playback mode is not part of `CacheKey`.** `CacheKeyTest`'s pinned hashes must keep passing
  untouched.
- **Mockito and the new overloads**: production now calls the *three-argument* `createRoute`. A stub
  or `verify` on the two-argument overload no longer matches anything; migrate it rather than
  deleting the assertion.

---

## Phase 1: Setup

**Purpose**: Confirm the build moves to API 0.1.21 cleanly before any behaviour changes.

- [X] T001 Confirm `pom.xml` (already edited in the working tree) has parent `multiroom-extension-starter` `0.1.21`, `<version>0.1.5</version>` and `<multiroom.require_api_version>0.1.21</multiroom.require_api_version>`, with the comment's range reading `[0.1.21,0.2.0)`. Confirm `unzip -l ~/.m2/repository/ai/multiroom/multiroom-api/0.1.21/multiroom-api-0.1.21.jar` lists ~70 entries including `multiroom/api/model/RouteJoinMode.class` and `multiroom/api/exceptions/RouteAdmissionException.class` (if it lists 7, re-run the upstream install; never add a `<repository>`)
- [X] T002 Run `mvn clean verify` and confirm the existing suite is green against API 0.1.21 with no production change, and that `unzip -p target/multiroom-tts-0.1.5.jar META-INF/MANIFEST.MF` shows `Require-API-Version: [0.1.21,0.2.0)` (depends on T001)

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: The playback-mode type and its configuration, which every story reads.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T003 [P] Write `src/test/java/multiroom/tts/config/PlaybackModeTest.java` first: `fromName` accepts `duck-others`, `DUCK-OTHERS`, `Duck-Others` → `DUCK_OTHERS` and `mix`, `MIX` → `MIX`; returns empty for `replace`, `duck_others`, `duckothers`, `""`, `"  "` and `null`; `configName()` is `duck-others` / `mix`; `joinMode()` maps to `RouteJoinMode.DUCK_OTHERS` / `RouteJoinMode.MIX`; `supportedList()` is `"duck-others, mix"`. See it fail to compile
- [X] T004 Create `src/main/java/multiroom/tts/config/PlaybackMode.java`: enum `DUCK_OTHERS, MIX` with `configName()` (lower-case, `_` → `-`, as `ProviderType.configName()`), `static Optional<PlaybackMode> fromName(String)` (null-safe, `equalsIgnoreCase` against `configName()` only — no Spring-style leniency), `static String supportedList()`, and `RouteJoinMode joinMode()` as an exhaustive `switch` expression. Javadoc: the subset of the host's join modes an announcement may use, and why replace is excluded (it would silence the room after the announcement, or need the stop–restore behaviour this replaced). Make T003 pass (depends on T003)
- [X] T005 [P] Create `src/main/java/multiroom/tts/config/PlaybackProperties.java` (Lombok `@Data`, Javadoc "Bound from `multiroom.tts.playback`", like `QueueProperties`) with `private String defaultMode = "duck-others";` and a method `PlaybackMode resolvedDefaultMode()` returning `PlaybackMode.fromName(defaultMode).orElseThrow()` (only called after validation) (depends on T004)
- [X] T006 In `src/test/java/multiroom/tts/config/TtsPropertiesValidationTest.java`, write first: with no `playback` set, validation passes and `getPlayback().resolvedDefaultMode()` is `DUCK_OTHERS`; `default-mode` `mix` and `MIX` pass and resolve to `MIX`; `replace`, `duck_others` and `louder` throw `IllegalStateException` with the exact message `multiroom-tts: playback.default-mode '<value>' is not one of duck-others, mix`; with `enabled=false` an invalid value does not throw (depends on T005)
- [X] T007 In `src/main/java/multiroom/tts/config/TtsProperties.java` add `private PlaybackProperties playback = new PlaybackProperties();` next to `queue`, and in `validate()` (after the provider-list check, before the per-provider loop) reject a value `PlaybackMode.fromName` does not accept with `fault("playback.default-mode '" + value + "' is not one of " + PlaybackMode.supportedList())`. Make T006 pass. Add the key to the class Javadoc's list of configuration faults only if such a list exists there (depends on T006)

**Checkpoint**: `mvn test` green; nothing yet uses the mode.

---

## Phase 3: User Story 1 - The music keeps playing under an announcement (Priority: P1) 🎯 MVP

**Goal**: An announcement joins its target in `duck-others` mode; nothing on the target is stopped
when it is accepted, and nothing is recreated when it ends.

**Independent Test**: In `TtsServiceTest`, an announcement to an output results in exactly one
`RouteService` interaction — `createRoute(input, OutputId, DUCK_OTHERS)` — before, during and after
completion. On the Pi: quickstart §4.

### Tests for User Story 1 (write first, see them fail)

- [X] T008 [P] [US1] In `src/test/java/multiroom/tts/service/PlaybackCompletionListenerTest.java`: construct the listener as `new PlaybackCompletionListener(audioCache, inputResolver)` everywhere (no `RouteService`); build tasks with `PlaybackMode.DUCK_OTHERS` in place of the route list. Rewrite `matchingEventRestoresSnapshottedRoutesAndUnpinsCache` as `matchingEventUnpinsCacheAndReleasesResolver`; delete `emptySnapshotRestoresNothing` (no snapshot exists); rename `invokesCompletionCallbackAfterRestoring` → `invokesCompletionCallbackAfterReleasing`; rewrite `cancelRestoresRoutesUnpinsCacheAndReleasesResolverWithoutInvokingCallback` → `cancelUnpinsCacheAndReleasesResolverWithoutInvokingCallback`. In `duplicateEventForSameAnnouncementIsIdempotent` and `cancelThenMatchingEventIsIdempotent`, replace the `verify(routeService, times(1)).createRoute(…)` idempotency check with `verify(audioCache, times(1)).unpin(key)` and `verify(inputResolver, times(1)).release(id)`. Drop the `List<Route> snapshot` parameter from the task helper (around line 46), every local `RouteService` mock, and the now-unused `RouteService`/`Route`/`List` imports. Keep the temp-file and non-matching-event tests
- [X] T009 [P] [US1] In `src/test/java/multiroom/tts/queue/AnnouncementQueueManagerTest.java`: change `dummyTask` to the new `AnnouncementTask` shape (`PlaybackMode.DUCK_OTHERS` in place of `List.of()`); no behaviour change expected
- [X] T010 [US1] In `src/test/java/multiroom/tts/service/TtsServiceTest.java`, migrate to the new routing call: remove the `stopRoutesByOutput`/`stopRoutesByGroup` stubs in the shared setup (around line 140); change every `createRoute(any(InputId.class), eq(OutputId.of(…)))` / `eq(GroupId.of(…))` / `any(OutputId.class)` stub and `verify` to the three-argument form with `eq(RouteJoinMode.DUCK_OTHERS)` (or `any(RouteJoinMode.class)` where the mode is not the point of the test). Find them with `grep -n "createRoute\|stopRoutesBy\|restoreRoutes" TtsServiceTest.java`
- [X] T011 [US1] In `src/test/java/multiroom/tts/service/TtsServiceTest.java`, replace `snapshotsAndStopsRoutesAlreadyOnTheTargetBeforeQueuing` with `joinsTheTargetInDuckOthersWithoutTouchingItsRoutes`: after a speak to `living-room`, `verify(routeService, timeout(ASYNC_TIMEOUT_MS)).createRoute(any(InputId.class), eq(OutputId.of("living-room")), eq(RouteJoinMode.DUCK_OTHERS))` and then `verifyNoMoreInteractions(routeService)`; add `acceptingAnAnnouncementTouchesNoRoute`: send two announcements to the same target — the mocked completion listener never runs the completion callback, so the worker stays blocked on the first after its `createRoute` with no latch needed — and, after the second `speak` has returned its `202`, assert with `verify(routeService, after(200).times(1)).createRoute(any(InputId.class), any(OutputId.class), any(RouteJoinMode.class))` that the accepted-but-queued second announcement caused no `RouteService` interaction, then `verifyNoMoreInteractions(routeService)`
- [X] T012 [US1] In `src/test/java/multiroom/tts/service/TtsServiceTest.java`, replace `enqueueFailureRestoresTheRoutesTheTargetWasAlreadyStrippedOf` with `enqueueFailureReleasesThePinAndTouchesNoRoute` (cache hit, full queue → `audioCache.unpin(key)` called, `verifyNoInteractions(routeService)`; do not reference `restoreRoutes`, which T014 deletes); rework `anUnexpectedFailureIsCountedAsInternalAndStillPropagates` (around line 944) to throw from another collaborator on the request path, e.g. `deviceQueryService.getOutput(any())` (not caught by `validateTargetExists`), since `stopRoutesByOutput` is no longer called, and rename its exception message from `route service down` to `device query down`

### Implementation for User Story 1

- [X] T013 [US1] In `src/main/java/multiroom/tts/queue/AnnouncementTask.java` replace `List<Route> routeSnapshot` with `PlaybackMode playbackMode` (same position, before `cacheKey`); drop the `Route`/`List` imports; update the record Javadoc (`@param playbackMode` the effective mode, resolved when the request was accepted). Update the class Javadoc of `src/main/java/multiroom/tts/queue/AnnouncementQueueManager.java` only if it mentions restoring routes (depends on T004)
- [X] T014 [US1] In `src/main/java/multiroom/tts/service/PlaybackCompletionListener.java`: remove the `RouteService` field and constructor parameter, delete `restoreRoutes`, rename private `restore` → `release` (unpin, resolver release, temp-file delete). Rewrite the class Javadoc: the listener releases what this module holds once the announcement's route is destroyed; restoring the level of other routes is the host's job; still never unregisters the input. Update `track`/`cancel` Javadoc to drop "restored"/"snapshotted". Make T008 pass (depends on T013)
- [X] T015 [US1] In `src/main/java/multiroom/tts/TtsAutoConfiguration.java` change the `playbackCompletionListener` bean to `new PlaybackCompletionListener(audioCache, ttsInputResolver)` and drop its `RouteService` parameter; `TtsAutoConfigurationTest` and `TtsAutoConfigurationMetricsTest` must still pass (depends on T014)
- [X] T016 [US1] In `src/main/java/multiroom/tts/service/TtsService.java`: delete `snapshotAndStopExistingRoutes` and its call; build the task with `properties.getPlayback().resolvedDefaultMode()` (US4 adds the per-request override); on enqueue failure release only the pin or spill file (delete the `restoreRoutes` call and rewrite that comment: there are no routes to restore); in `activate` call `routeService.createRoute(task.inputId(), OutputId.of(…) | GroupId.of(…), task.playbackMode().joinMode())`; rewrite the class Javadoc ("snapshot and stop whatever is already playing" → joins the target alongside what plays there; the host lowers and restores). Make T009–T012 pass (depends on T007, T013, T015)
- [X] T017 [US1] Run `mvn verify`; `grep -rn "stopRoutesBy\|restoreRoutes\|routeSnapshot\|REPLACE" src/main/java` must print nothing, and `grep -rni "restore\|snapshot\|stopRoutesBy" src/test/java` must print only unrelated hits (e.g. Micrometer's `takeSnapshot()` in `MicrometerTtsMetricsTest`) — any test still naming or asserting route restoration is deleted or rewritten, not left passing vacuously (depends on T016)

**Checkpoint**: US1 complete and testable alone: the MVP.

---

## Phase 4: User Story 2 - Group announcements over group music (Priority: P2)

**Goal**: A group announcement is one route to the group in the effective mode; a member
announcement routes to that output only.

**Independent Test**: `TtsServiceTest` group and member cases; on the Pi, quickstart §5.

### Tests for User Story 2 (write first)

- [X] T018 [US2] In `src/test/java/multiroom/tts/service/TtsServiceTest.java`, extend `outputGroupTargetProducesExactlyOneCreateRouteCall` to verify `createRoute(any(InputId.class), eq(GroupId.of("all-rooms")), eq(RouteJoinMode.DUCK_OTHERS))`, no `createRoute` on any member `OutputId`, and `verifyNoMoreInteractions(routeService)`; add `aGroupAndItsMemberAreQueuedIndependently`: with the `all-rooms` worker blocked on an announcement, an announcement to member `kitchen` still reaches `createRoute(…, OutputId.of("kitchen"), DUCK_OTHERS)` (queues are keyed by target, not output) (depends on T016)

### Implementation for User Story 2

- [X] T019 [US2] No production change is expected (T016 routes groups through `createRoute(…, GroupId, mode)`). If T018 fails, fix `TtsService.activate` in `src/main/java/multiroom/tts/service/TtsService.java`; otherwise record "covered by T016" when ticking this task (depends on T018)

**Checkpoint**: US1 and US2 green.

---

## Phase 5: User Story 3 - A refused announcement explains itself and leaves the room alone (Priority: P2)

**Goal**: A host refusal releases everything the announcement reserved, touches no other route,
moves the queue on without retrying, logs the host's reason and output, and counts one failed
playback.

**Independent Test**: `TtsServiceTest` with `createRoute` throwing `RouteAdmissionException`; on a
host at its route limit, quickstart §7.

### Tests for User Story 3 (write first, see them fail)

- [X] T020 [US3] In `src/test/java/multiroom/tts/service/TtsServiceTest.java` add `aRefusedRouteReleasesEverythingAndMovesTheQueueOn`: stub `createRoute(any(InputId.class), eq(OutputId.of("living-room")), any(RouteJoinMode.class))` to throw `new RouteAdmissionException("limit", RouteAdmissionException.Reason.ROUTE_LIMIT_REACHED, OutputId.of("living-room"))` for the first call and return a route for the second; send two announcements to `living-room`. Assert: `metrics.playbackFailed()` exactly once and `playbackStarted()` once; for the first, `completionListener.cancel(inputId)` and `deviceRegistryService.unregisterInput(inputId)` were each called once (the listener is a mock here, so what `cancel` releases — pin, resolver entry, temp file — is asserted in `PlaybackCompletionListenerTest` by T008, not here); the second's `createRoute` happened (queue moved on); `createRoute` was called exactly twice (no retry); no other `RouteService` method was called (depends on T016)
- [X] T021 [US3] In `src/test/java/multiroom/tts/service/TtsServiceTest.java` add `aRefusalIsLoggedWithTheHostsReasonAndOutput` using a logback `ListAppender` on `TtsService`'s logger (pattern as in `TtsPropertiesValidationTest` around line 781): exactly one `WARN` event whose formatted message starts with `TTS_PLAYBACK_REFUSED` and contains `announcementId=`, `target=living-room`, `output=living-room` and `reason=ROUTE_LIMIT_REACHED`, with no throwable attached; and `aNonAdmissionRouteFailureIsStillAnError`: a plain `RouteException` produces an `ERROR` event with the throwable, and the same cleanup (extends the existing `routeCreationFailureCancelsTrackingAndUnregistersTheOrphanedInput`) (depends on T020)
- [X] T022 [US3] In `src/test/java/multiroom/tts/service/TtsServiceTest.java` add `aRefusedGroupAnnouncementReleasesEverything`: `createRoute(…, eq(GroupId.of("all-rooms")), any())` throws `RouteAdmissionException` naming one member output; `completionListener.cancel(inputId)` and `unregisterInput(inputId)` called once, the queue moves on, no other `RouteService` call, and the log's `output=` is that member (depends on T020)

### Implementation for User Story 3

- [X] T023 [US3] In `TtsService.activate` (`src/main/java/multiroom/tts/service/TtsService.java`) split the failure handling: `catch (RouteAdmissionException e)` → `log.warn("TTS_PLAYBACK_REFUSED announcementId={} target={} output={} reason={}", id, target, e.getOutputId(), e.getReason())` with no throwable; `catch (RuntimeException e)` → today's `log.error(…, e)`. Both then run one shared private method holding today's cleanup (`metrics.playbackFailed()`, `completionListener.cancel`, guarded `unregisterInput`, `onPlaybackComplete.run()`), so the cleanup is written once. Rewrite the comment: unregistering is right here because no route was created, so core will never auto-remove the input; the announcement is dropped, not retried, because a late announcement is worse than none. Null-safe: `getOutputId()` may be null. Make T020–T022 pass (depends on T021, T022)
- [X] T024 [US3] In `AGENTS.md`, amend the gotcha "Never Unregister The Announcement's Ephemeral Input": the one exception is a route the host refused (or that failed to start), where no route exists and so nothing auto-removes the input; `TtsService.activate` unregisters it then, and only then

**Checkpoint**: US1–US3 green.

---

## Phase 6: User Story 4 - Choose not to lower the room (Priority: P3)

**Goal**: `mix` (and `duck-others`) can be chosen per request; the configured default applies
otherwise; any other value is a `400 INVALID_REQUEST` before any synthesis.

**Independent Test**: `TtsServiceTest` and `TtsControllerTest` mode cases; on the Pi, quickstart §3
and §6.

### Tests for User Story 4 (write first, see them fail)

- [X] T025 [P] [US4] In `src/test/java/multiroom/tts/rest/controller/TtsControllerTest.java`: a body with `"playbackMode":"mix"` reaches `TtsService.speak` with `AnnounceCommand.playbackMode() == "mix"` (use an `ArgumentCaptor`); a body without it gives `null`; the `202` body shape is unchanged
- [X] T026 [US4] In `src/test/java/multiroom/tts/service/TtsServiceTest.java` add: `aRequestedModeOverridesTheConfiguredDefault` (default `duck-others`, request `MIX` → `createRoute(…, eq(RouteJoinMode.MIX))`); `theConfiguredDefaultAppliesWhenTheRequestNamesNone` (properties `playback.default-mode=mix`, no request mode → `MIX`); `anUnknownModeIsRejectedBeforeAnyWork` (request `replace` → `TtsException` with `INVALID_REQUEST` and message `Unknown playbackMode 'replace'. Supported: duck-others, mix`; `verifyNoInteractions` on the provider, `audioCache`, `deviceQueryService` and `routeService`; `metrics.announcementRejected(<provider tag>, "INVALID_REQUEST", "none")`); `theModeIsNotPartOfTheCacheKey` (same text and voice, first `duck-others` then `mix`; capture the `CacheKey` passed to `audioCache.get` on both requests with an `ArgumentCaptor` and assert the two keys are equal and have the same `toHash()`) (depends on T016)

### Implementation for User Story 4

- [X] T027 [P] [US4] Add `String playbackMode` as the last component of `src/main/java/multiroom/tts/rest/dto/SpeakRequest.java` (no validation annotation; add a Javadoc sentence: optional, `duck-others` or `mix`, checked by `TtsService`) and of `src/main/java/multiroom/tts/service/AnnounceCommand.java` (`@param playbackMode` `null` = not given). Keep `AnnounceCommand`'s nine-argument constructor and add a ten-argument one (the current full shape) both delegating with `playbackMode = null`, so existing callers compile unchanged (depends on T025)
- [X] T028 [US4] In `src/main/java/multiroom/tts/rest/controller/TtsController.java` pass `request.playbackMode()` into the new `AnnounceCommand` component. Make T025 pass (depends on T027)
- [X] T029 [US4] In `TtsService.announce` (`src/main/java/multiroom/tts/service/TtsService.java`) add `private PlaybackMode resolvePlaybackMode(String requested)`: `null` → `properties.getPlayback().resolvedDefaultMode()`; otherwise `PlaybackMode.fromName(requested).orElseThrow(() -> new TtsException(INVALID_REQUEST, "Unknown playbackMode '" + requested + "'. Supported: " + PlaybackMode.supportedList()))`. Call it immediately after `validateText`, before `validateTargetExists`, and pass the result into the task (replacing T016's default-only value). Make T026 pass (depends on T026, T027)

**Checkpoint**: all four stories green.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T030 [P] Update `README.md`: an announcement now plays over what the room is playing (lowered by default, `mix` optional), the new `playbackMode` request field and `multiroom.tts.playback.default-mode`, that level and fade are the host's `audio.mixing.ducking.*`, and the deployment order (host with multiroom-api 0.1.21 first, then this JAR)
- [X] T031 [P] Update `docs/configuration.md`: a `playback` section with `default-mode` (values, default, start-up fault message), and the host keys `audio.mixing.ducking.level-db`, `audio.mixing.ducking.fade-millis` and `audio.mixing.max-routes-per-output` with what each does to announcements, per [contracts/configuration.md](contracts/configuration.md)
- [X] T032 [P] Update `docs/metrics.md` under `tts_playbacks_total`: `failed` now also counts announcements the host refused (route limit), which are dropped, not retried, and logged as `TTS_PLAYBACK_REFUSED` with the reason and output
- [X] T033 [P] Update `AGENTS.md`: add the `006-announcement-ducking` row to the Features table (version 0.1.5, path `specs/006-announcement-ducking/`), summarising: announcements join in `duck-others`/`mix` instead of stopping and restoring the target; `playbackMode` per request and `playback.default-mode`; host refusals logged and counted as failed playbacks; requires multiroom-api 0.1.21
- [X] T034 Run `mvn clean verify` (tests, JaCoCo 80 % floor, enforcer, no-root-`application.yml` gate) and fix anything red; optionally run SpotBugs on the changed classes with the constitution's noise filter (depends on T017–T033)
- [X] T035 Merge gate part two: `mvn deploy -Plocal`, start multiroom-core from the multiroom-ai `023-multi-route-output-mixing` checkout, and confirm `GET /actuator/extensions` lists `tts` at `0.1.5`, not `REJECTED`; then run quickstart §3 locally (unknown mode → 400, no synthesis; the start-up fault with `default-mode: replace`) (depends on T034)
- [x] T036 Production verification (Principle VII), **only with the user's permission for each deploy, restart or config change**, and only after the 023 host is deployed on `multiroom.lan`: `mvn deploy`, then quickstart §4–§6 and §8 (music continues lowered, back within ~1 s, a cache hit on a busy output reaches `TTS_PLAYBACK_STARTED` in under 1 s, Home Assistant keeps showing the music, 50 consecutive announcements with no route events but their own, groups including different music on two members, mix, stop-all), and §7 only if the user allows a temporary `audio.mixing.max-routes-per-output: 1`. Record the results in the PR description; after the merge, tag the release `v0.1.5` (depends on T035)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: none
- **Foundational (Phase 2)**: after Setup; blocks every story
- **US1 (Phase 3)**: after Foundational. It is the structural change (task shape, listener,
  routing call), so **US2, US3 and US4 all depend on T016**
- **US2, US3, US4**: after T016; independent of each other, but all edit
  `TtsServiceTest.java` and (US3, US4) `TtsService.java`, so run them one after another unless
  working in separate branches
- **Polish (Phase 7)**: T030–T033 any time after their story; T034–T036 last, in order

### User Story Dependencies

- **US1 (P1)**: foundation only
- **US2 (P2)**: US1's T016 (no production change of its own)
- **US3 (P2)**: US1's T016
- **US4 (P3)**: US1's T016; T025/T027/T028 (controller and DTO) can start right after Foundational

### Within Each Story

- Tests first, seen failing (or failing to compile), then the implementation that makes them pass
- Records and types before the services that use them (T013 before T014 before T016)

---

## Parallel Examples

### Foundational

```text
T003 PlaybackModeTest            (new file)
T005 PlaybackProperties          (new file; after T004)
```

### User Story 1

```text
T008 PlaybackCompletionListenerTest
T009 AnnouncementQueueManagerTest
```

T010–T012 share `TtsServiceTest.java` and run in sequence.

### User Story 4

```text
T025 TtsControllerTest
T027 SpeakRequest + AnnounceCommand
```

### Polish

```text
T030 README.md
T031 docs/configuration.md
T032 docs/metrics.md
T033 AGENTS.md
```

---

## Implementation Strategy

### MVP First (User Story 1)

Phases 1–3 deliver the whole value: music lowered, never stopped, under every announcement, with
the default mode. Stop at T017, run the local smoke (T035), and the change is shippable.

### Incremental Delivery

1. Setup + Foundational → the mode type and setting exist, unused
2. US1 → announcements join in `duck-others` (MVP)
3. US2 → group behaviour pinned by tests
4. US3 → refusals logged clearly and cleaned up (the cleanup itself already works after US1)
5. US4 → `mix` per request; the REST contract v0.1.5 is fully implemented
6. Polish → documents, gates, production verification
