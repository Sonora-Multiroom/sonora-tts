# Implementation Plan: Warm The Cache Without Playback

**Branch**: `010-cache-warm-without-playback` | **Date**: 2026-11-01 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/010-cache-warm-without-playback/spec.md`

## Summary

Add `POST /api/tts/cache/warm`: one text with optional provider and voice settings, no target. It
runs the part of the announcement path that comes before the queue (validate, resolve, cache lookup,
synthesize, convert, store) and stops there, so a later speak of the same text is a hit and nothing
is heard. That shared part is extracted from `TtsService.announce`, so the cache identity cannot
drift between the two paths. A failed store is a new `CACHE_WRITE_FAILED` (503), and warm-ups get
their own `tts_cache_warm_total` counter. Details and rejected alternatives are in
[research.md](research.md).

## Technical Context

**Language/Version**: Java 17

**Primary Dependencies**: Spring Boot 3.5 and Jackson (`provided`, from the host), Micrometer
(`provided`, used only in `MicrometerTtsMetrics`), `multiroom-api` 0.1.29 (unchanged)

**Storage**: The existing on-disk cache (`index.json` plus WAV files), format unchanged, no hash
changes

**Testing**: JUnit 5, Mockito, `@WebMvcTest` slices; no live providers

**Target Platform**: Windows for development; the Raspberry Pi (`multiroom.lan`) for production

**Project Type**: Drop-in extension JAR for `multiroom-core`, single Maven module

**Performance Goals**: A hit costs what a speak hit costs (one in-memory lookup and an index write);
a miss takes as long as the provider does, up to its timeout, while the request waits

**Constraints**: `resolveSettings` stays free of I/O; only `MicrometerTtsMetrics` imports Micrometer;
every series of a metric carries one tag-key set; no caller text in tags or logs

**Scale/Scope**: About 700 average phrases fit production's 500 MB cache. No concurrency limit is
added, because callers warm one phrase at a time

## Constitution Check

*GATE: passed before Phase 0, and re-checked after Phase 1 design with no change.*

| Principle | Result |
|---|---|
| I. Clean Code | Pass. The extraction removes duplication and shortens `announce`, which is well over 20 lines today |
| II. Java 17 and Spring Boot | Pass. A record DTO and constructor injection; nothing new is bundled |
| III. Test-First | Pass. Each task starts from a failing test; `TtsServiceTest` already pins the speak path through the extraction |
| IV. SOLID | Pass. Provider work stays behind `TtsProvider`, and the billing measurement stays at the one `synthesize` call site |
| V. Quality gates | Pass. `mvn verify` plus a smoke run that needs the user's permission on production; see [quickstart.md](quickstart.md) |
| VI. Git workflow | Pass. Feature branch, PR, version 0.1.11. The branch number was set by hand because the script derives 035 from a remote branch named after a hub feature |
| VII. Cross-platform | Pass, with one obligation: stored audio is played, so a warmed phrase must be heard on the Pi once, with the user's permission |
| VIII. Extension boundary | Pass. No new configuration, no start-up work, no new dependency; the cache key is untouched |

## Project Structure

### Documentation (this feature)

```text
specs/010-cache-warm-without-playback/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── tts-cache-warm-api.yaml
├── tasks.md             # /speckit-tasks
└── verification.md      # results, from T001 on
```

### Source Code (repository root)

```text
src/main/java/multiroom/tts/
├── TtsErrorCode.java                  # + CACHE_WRITE_FAILED
├── metrics/
│   ├── TtsMetrics.java                # + cacheWarmed, cacheWarmFailed
│   ├── MicrometerTtsMetrics.java      # + tts.cache.warm
│   └── NoopTtsMetrics.java            # + the two no-ops
├── rest/
│   ├── advice/TtsExceptionHandler.java    # counts a refused warm request
│   ├── api/TtsCacheApi.java               # + POST /warm
│   ├── controller/TtsCacheController.java # + TtsService, warm
│   └── dto/
│       ├── WarmRequest.java               # new
│       └── WarmResponse.java              # new
└── service/
    ├── TtsService.java                # + warm; shared steps extracted from announce
    └── WarmCommand.java               # new

src/test/java/multiroom/tts/
├── cache/FilesystemAudioCacheTest.java
├── metrics/{MicrometerTtsMetricsTest,NoopTtsMetricsTest,PrometheusNamesTest}.java
├── rest/advice/TtsExceptionHandlerTest.java
├── rest/controller/TtsCacheControllerTest.java
└── service/TtsServiceTest.java

docs/configuration.md                  # "Managing the cache", the caller's rules, the error code
docs/metrics.md                        # tts_cache_warm_total
pom.xml                                # 0.1.10 -> 0.1.11
```

**Structure Decision**: The existing single module. The endpoint joins `TtsCacheApi`, whose interface
is already in `TtsExceptionHandler`'s scope, and the orchestration joins `TtsService`, the only place
that may call a provider.

## After merge

`/speckit-archive-run` folds this into the master documents. The backlog item
`docs/backlog/cache-warm-without-playback.md` and its `INDEX.md` row are deleted when the feature
ships, and `AGENTS.md` gains a row in its Features table.
