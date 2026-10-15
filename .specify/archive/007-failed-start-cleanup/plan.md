# Implementation Plan: An Announcement That Fails To Start Ends Once, As A Failure

**Branch**: `007-failed-start-cleanup` | **Date**: 2026-10-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/007-failed-start-cleanup/spec.md`

## Summary

Since hub 024 (multiroom-api 0.1.22), a route that was admitted and then failed to start publishes
one `RouteDestroyedEvent` before `createRoute` throws. `PlaybackCompletionListener` treats it as a
finished announcement, and `TtsService.abandon` then handles the same announcement again: the
queue's completion callback runs twice and the failure is logged as `TTS_PLAYBACK_COMPLETED`.

The fix gives each outcome one owner. The listener ignores a destroyed route that never went live
(`startedAt == null`) and leaves it tracked; the failure path in `TtsService` stays the sole owner
of a refused or failed start: it releases, removes the input (the hub does not, see
[research.md](research.md) R2), counts the failure, logs `TTS_PLAYBACK_FAILED` and signals the queue
once. `abandon` becomes idempotent as a safety net, and an already-removed input is tolerated at
DEBUG. No API bump: `require_api_version` stays 0.1.21.

## Technical Context

**Language/Version**: Java 17

**Primary Dependencies**: `multiroom-api` 0.1.21 (`provided`; `Route.getStartedAt`,
`RouteDestroyedEvent`, `DeviceRegistryService.unregisterInput`), `multiroom-extension-starter`
0.1.21 parent. No new dependency

**Storage**: Unchanged (on-disk audio cache; `CacheKey` untouched)

**Testing**: JUnit 5, Mockito; `PlaybackCompletionListenerTest`, `TtsServiceTest`; JaCoCo 80 % floor

**Target Platform**: Inside `multiroom-core` on Windows (development) and Raspberry Pi OS ARM64
(production); behaviour identical on a 0.1.21 and a 0.1.22 host

**Project Type**: Single-module drop-in extension JAR

**Performance Goals**: None new; the playback path gains one null check

**Constraints**: The listener may run on another thread than the queue worker (R1), so the hand-off
stays on `ConcurrentHashMap.remove`; no new metric, tag key or tag value; no caller text in logs
beyond the existing ids

**Scale/Scope**: 2 production classes changed (`TtsService`, `PlaybackCompletionListener`); 2 test
classes changed; 5 documents (`docs/metrics.md`, `AGENTS.md`, `README.md`, the backlog item and its
index)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | How |
|---|---|---|
| I. Clean Code | Pass | One owner per outcome; `abandon` gains a guard, the listener one early return; no empty catch (the `IllegalArgumentException` catch logs at DEBUG) |
| II. Java 17 & Spring Boot | Pass | No new beans or configuration |
| III. Test-First | Pass | Failing tests first: listener ignores a never-started route; the 0.1.22 ordering (event, then throw) signals the queue once, counts one failure and removes the input once; `abandon` after an already-handled task does not signal again; already-removed input logs no WARN |
| IV. SOLID | Pass | No new dependency between classes; `cancel` returns a boolean instead of `void` |
| V. Quality gates | Pass (planned) | `mvn verify`, then the local smoke run (`/actuator/extensions` shows 0.1.6, not `REJECTED`) |
| VI. Git workflow | Pass | Feature branch `007-failed-start-cleanup`; version 0.1.6 |
| VII. Cross-platform | Pass (planned) | No platform code. Touches the playback path, so a normal announcement is checked on the production Pi; a failed start cannot be provoked there safely and is covered by tests ([quickstart.md](quickstart.md)) |
| VIII. Extension boundary | Pass | Only `multiroom-api`; nothing at start-up; `enabled=false` unaffected |
| Upstream contract | Pass | No API change; relies on `Route.startedAt`, present since before 0.1.21 (checked in the installed 0.1.21 jar). Its "never went live" meaning is undocumented upstream: risk and mitigation in research R3 |

No violations. **Post-design re-check**: unchanged. The design adds no dependency, no state beyond
the existing in-flight map, and no metric.

## Project Structure

### Documentation (this feature)

```text
specs/007-failed-start-cleanup/
├── plan.md              # This file
├── research.md          # Hub behaviour (R1, R2), the never-started signal (R3), idempotency (R4), logs (R5), tests (R6)
├── data-model.md        # The announcement-in-flight lifecycle and who ends it
├── quickstart.md        # Build, tests, smoke and production check
├── contracts/
│   └── observability.md # Log lines and tts_playbacks_total per outcome
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
src/main/java/multiroom/tts/service/
├── TtsService.java                    # activate: TTS_PLAYBACK_FAILED line; abandon: idempotent, tolerant unregister, Javadoc
└── PlaybackCompletionListener.java    # onRouteDestroyed: ignore startedAt == null; cancel returns boolean; Javadoc

src/test/java/multiroom/tts/service/
├── PlaybackCompletionListenerTest.java  # routeTo sets startedAt; never-started route left tracked; cancel's return value
└── TtsServiceTest.java                  # 0.1.22 ordering simulated; 0.1.21 ordering unchanged; refusal unchanged

docs/metrics.md                          # tts_playbacks_total: an admitted route that failed to start is `failed`
AGENTS.md                                # Gotcha "Never Unregister The Announcement's Ephemeral Input": the failed-start exception, restated; feature row
README.md                                # status line 0.1.6, deployment-order paragraph, specs row
docs/backlog/failed-start-double-cleanup.md, docs/backlog/INDEX.md   # deleted / row removed when the feature ships
pom.xml                                  # 0.1.5 -> 0.1.6
```

**Structure Decision**: Single module, as every feature before it. The change stays inside
`multiroom.tts.service`.

## Design

1. **`PlaybackCompletionListener.onRouteDestroyed`**: if `event.route().getStartedAt() == null`,
   log at DEBUG (`announcement {} route never started; left to the failure path`) and return
   without removing the tracked entry. Otherwise unchanged. Applies to tracked inputs only; an
   untracked input returns early as today.
2. **`PlaybackCompletionListener.cancel`** returns `true` when it removed and released a tracked
   announcement, `false` otherwise.
3. **`TtsService.abandon`**:
   - counts `playbackFailed()` (always: the start failed, and the listener never counts);
   - `if (!completionListener.cancel(inputId))` log at DEBUG that the end was already handled and
     skip the callback;
   - `unregisterInput`: catch `IllegalArgumentException` at DEBUG, other `RuntimeException` at
     WARN with the stack trace (as now). The hub's only other `IllegalArgumentException` there
     refuses a static input, which a `tts-<uuid>` input never is, so every one means "already
     removed";
   - run `onPlaybackComplete` only when `cancel` returned `true`.
4. **`TtsService.activate`**: the generic `RuntimeException` branch logs
   `TTS_PLAYBACK_FAILED announcementId={} target={}` with the exception; the refusal branch is
   unchanged.
5. **Javadoc**: `abandon` and `cancel` describe both orders (event first on 0.1.22, no event on
   0.1.21) and why removing the input is still theirs (R2). The listener's class Javadoc notes
   that a never-started route is left to the failure path.

`activateCountingEarlyFailures` is unchanged: a failure before the route step still never reaches
`abandon`, so it is still counted once.

## Follow-ups outside this repository

- Offer the user a sonora-multiroom backlog item: document on `RouteDestroyedEvent` (or `Route`)
  that `startedAt == null` means the route never went live. Not filed from here.

## Complexity Tracking

No constitution violations to justify.
