# Implementation Plan: TTS Metrics

**Branch**: `005-tts-metrics` | **Date**: 2026-09-26 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/005-tts-metrics/spec.md`

**Depends on**: `001`–`004` (merged).

**No upstream change**: the host already provides Micrometer and the Prometheus registry on the
shared classpath, so `multiroom.require_api_version` stays `0.1.18` and no `multiroom-ai` branch
is needed.

**Version**: the extension goes from `0.1.3` to `0.1.4`.

## Summary

The extension records its own metrics into the host's Micrometer registry, so they appear in the
host's existing `/actuator/prometheus` endpoint:

- announcements accepted and rejected, by error code, rejection reason and provider
- provider synthesis time, as a histogram, with the characters sent and the seconds of audio
  returned, so time per 1,000 characters and the real-time factor can be computed
- cache hits and misses, and cache occupancy
- queue depth per target
- playbacks started and failed

The rest of the module reports events through one small interface, `TtsMetrics`. A Micrometer
implementation is used when the host has a registry, and a no-op otherwise. The character count
is taken once, before any provider is called, and carries the billing tier, which lays the
groundwork for the future monthly character budget.

Two small side changes come with it:

- a queue rejection now carries its reason, without changing the caller-visible error code
- an unreadable request body now gets the published `ErrorResponse(INVALID_REQUEST)` answer
  instead of Spring's default body

## Technical Context

**Language/Version**: Java 17 LTS

**Primary Dependencies**:

- `multiroom-api` 0.1.18 (`provided`), Spring Boot 3.5.15 (`provided`)
- **New**: `io.micrometer:micrometer-core` 1.15.12 (`provided`; version managed by
  `spring-boot-dependencies`) and `io.micrometer:micrometer-registry-prometheus` 1.15.12 (`test`)

**Storage**: None. Meters live in the host's registry; nothing is persisted.

**Testing**: JUnit 5, Mockito, Spring Boot Test (`ApplicationContextRunner` with
`FilteredClassLoader`), Micrometer's `SimpleMeterRegistry` and `PrometheusMeterRegistry`. JaCoCo
keeps its 80% floor. Details in [research.md](research.md) R16.

**Target Platform**: Inside `multiroom-core`, on Windows (development) and Raspberry Pi OS ARM64
(production).

**Project Type**: A drop-in Spring Boot extension JAR.

**Performance Goals**:

- A cache hit adds one in-memory counter increment and no I/O (FR-013). SC-004 allows it to be at
  most 5% slower on the median.
- A scrape reads the in-memory cache index under its existing lock, and one queue size per target.

**Constraints**:

- Tag cardinality is bounded by configuration and device count (FR-016, SC-005).
- Every series of a metric has the same tag keys, or Prometheus drops it (research R4).
- Recording never throws into an announcement (FR-018).
- The host starts with or without Micrometer, and with the extension disabled (FR-002, FR-003).

**Scale/Scope**:

- Main code: 5 new classes (`TtsMetrics`, `MicrometerTtsMetrics`, `NoopTtsMetrics`,
  `SynthesisUsage`, `QueueRejectedException`) and about 12 changed ones (`TtsProvider`, its five
  implementations, `ProviderType`, `ProviderRegistry`, `AnnouncementQueueManager`, `TtsService`,
  `TtsExceptionHandler`, `TtsAutoConfiguration`).
- REST: no new endpoint or field. An unreadable body now gets the contract's `ErrorResponse`.
- Documentation: new `docs/metrics.md`; `README.md` and `docs/configuration.md` link to it;
  `docs/future/google-cloud-free-tier-tracking.md` is updated (local-only, research R15).

No NEEDS CLARIFICATION remains. [research.md](research.md) settles the choices in R1–R16.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | How |
|---|---|---|
| I. Clean Code | ✅ | One seam (`TtsMetrics`) with event-named methods; no metric code inside providers beyond the two pure SPI methods |
| II. Java 17 & Spring Boot | ✅ | Micrometer is `provided` like every other host library; contributed through `@AutoConfiguration` nested configurations; constructor injection |
| III. Test-First | ✅ | Each event has a failing test before code (R16); no live provider; `SimpleMeterRegistry` tests run in milliseconds |
| IV. SOLID | ✅ | Callers depend on the `TtsMetrics` interface (dependency inversion). `billingTier` and `type` are provider knowledge, so they live on `TtsProvider`, and a new provider adds them without editing the playback path |
| V. Code Quality | ✅ | Merge gate unchanged: `mvn verify` plus a smoke run on a live core. The quickstart adds the scrape checks to that smoke run |
| VI. Git Workflow | ✅ | Feature branch `005-tts-metrics`; Conventional Commits; version bumped to 0.1.4 |
| VII. Cross-Platform | ✅ | No paths, no processes, no native code |
| VIII. Extension Boundary | ✅ | Depends on nothing but host-provided libraries, all `provided`: the enforcer bans only `multiroom-core`, and nothing is shaded. No root `application.yml`. No network or subprocess work at construction: gauges are registered, not read. Missing Micrometer is not a fault. `multiroom.tts.enabled=false` contributes no beans and so no meters |

**Post-design re-check**: still ✅. The design adds one host-provided `provided` dependency, which
is the same kind the constitution already accepts for Spring and Jackson. Constitution 1.0.2
words Principle VIII that way explicitly: `multiroom-api` is the only multiroom artifact, and
other host-supplied libraries, Micrometer among them, are `provided`. No violation to justify.

## Project Structure

### Documentation (this feature)

```text
specs/005-tts-metrics/
├── plan.md              # This file
├── research.md          # Phase 0: R1–R16
├── data-model.md        # Phase 1: new and changed types
├── quickstart.md        # Phase 1: end-to-end validation against a live host
├── contracts/
│   └── metrics.md       # Phase 1: every metric, tag and example query (the source of docs/metrics.md)
└── tasks.md             # Phase 2 (/speckit-tasks)
```

The REST contract has no new endpoint or field, so there is no new `tts-rest-api.yaml`. The
unreadable-body change brings the implementation in line with the published 0.1.3 contract,
which already says every 400 carries an `ErrorResponse`.

### Source Code (repository root)

```text
pom.xml                                         # 0.1.4; + micrometer-core (provided), + micrometer-registry-prometheus (test)

src/main/java/multiroom/tts/
├── TtsAutoConfiguration.java                   # + nested Micrometer / no-op TtsMetrics configurations; ttsService takes TtsMetrics
├── metrics/                                    # NEW package
│   ├── TtsMetrics.java                         # the event seam
│   ├── MicrometerTtsMetrics.java               # meters, tag rules, buckets, cache gauges, queue gauges
│   ├── NoopTtsMetrics.java
│   └── SynthesisUsage.java                     # the per-synthesis work, built before the provider call
├── config/ProviderType.java                    # + configName()
├── provider/
│   ├── TtsProvider.java                        # + type(), + default billingTier(settings)
│   ├── ProviderRegistry.java                   # + isConfigured(name)
│   ├── cloud/GoogleCloudTtsProvider.java       # type(); billingTier = engine family or "other"
│   ├── cloud/GoogleGeminiTtsProvider.java      # type(); billingTier = configured model
│   ├── cloud/OpenAiTtsProvider.java            # type()
│   ├── local/PiperTtsProvider.java             # type()
│   └── local/LocalHttpTtsProvider.java         # type()
├── queue/
│   ├── AnnouncementQueueManager.java           # throws QueueRejectedException; + depth(key)
│   └── QueueRejectedException.java             # NEW
├── service/TtsService.java                     # records every event; wraps the activator
└── rest/TtsExceptionHandler.java               # records pre-service rejections; + HttpMessageNotReadableException handler

src/test/java/multiroom/tts/
├── metrics/MicrometerTtsMetricsTest.java       # NEW
├── metrics/PrometheusNamesTest.java            # NEW: scraped names, tag-key consistency, buckets
├── TtsAutoConfigurationMetricsTest.java        # NEW: registry / no registry / no Micrometer / disabled
├── service/TtsServiceTest.java                 # + metrics events, cardinality loop
├── queue/AnnouncementQueueManagerTest.java     # + QueueRejectedException reasons, depth()
├── provider/*ProviderTest.java                 # + type(), billingTier()
├── provider/ProviderRegistryTest.java          # + isConfigured()
└── rest/TtsExceptionHandlerTest.java, TtsControllerTest.java  # + unreadable body, rejection recorded

docs/metrics.md                                 # NEW operator guide (from contracts/metrics.md)
docs/configuration.md, README.md                # links to docs/metrics.md
docs/future/google-cloud-free-tier-tracking.md  # what 005 provides for the budget (local-only file)
```

**Structure Decision**: a single module, as before. Metrics get their own package,
`multiroom.tts.metrics`, so that Micrometer imports stay in one place. Only
`MicrometerTtsMetrics` imports `io.micrometer`, which is what lets the rest of the module load
without it.

## Implementation notes for tasks

- **Order**: the seam and the no-op come first, so `TtsService` can take `TtsMetrics` without
  anything else changing. Then the SPI additions, then the recording story by story (P1: announcements
  and synthesis; P2: cache; P3: queue and playback), then the Prometheus-name test, then
  documentation.
- **`TtsServiceTest` constructs `TtsService` directly**, five times. Add the `TtsMetrics`
  parameter once, through a helper, rather than at each site.
- **`ProviderRegistry` is constructed in 11 places in tests.** Its constructor is unchanged;
  `isConfigured` only reads the existing map.
- **Mocked `TtsProvider`s return `null` from `type()`**: `SynthesisUsage` maps that to `unknown`,
  so the existing tests keep passing unchanged.
- **Record rejections in a `finally`-style wrapper around the body of `speak`**, not at each
  `throw`. That keeps the method's existing structure readable and covers `INTERNAL`.
- **Version and docs**: bump `pom.xml` to `0.1.4`. `docs/metrics.md` is written from
  [contracts/metrics.md](contracts/metrics.md), in operator language. Add the feature's row to
  AGENTS.md's feature table.

## Complexity Tracking

No constitution violations; nothing to justify.
