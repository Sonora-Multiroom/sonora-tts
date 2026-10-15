# Research: TTS Metrics

**Feature**: `005-tts-metrics` | **Date**: 2026-09-26 | **Spec**: [spec.md](spec.md)

Every decision below was checked against the code on `005-tts-metrics` (commit `43ccc17`), the
host `multiroom-core` 0.1.18, and the local `~/.m2` artifacts.

## R1. The metrics library is the host's Micrometer, at `provided` scope

**Decision**: Depend on `io.micrometer:micrometer-core` at `provided` scope, with no version: the
parent `multiroom-parent` 0.1.18 imports `spring-boot-dependencies` 3.5.15, which manages
Micrometer 1.15.12. Add `io.micrometer:micrometer-registry-prometheus` at `test` scope only, to
check the Prometheus names in a test.

**Rationale**: `multiroom-core` already ships `micrometer-core` and
`micrometer-registry-prometheus`, exposes the `prometheus` actuator endpoint, and since 019 every
extension shares its classloader. The starter's `bannedDependencies` rule excludes only
`ai.multiroom:multiroom-core`, and its shade configuration never bundles a `provided` artifact.
The upstream guide says to depend on "`multiroom-api` (and anything core provides) with
`provided` scope", which is how this repository already takes Spring, Jackson and the Swagger
annotations.

**Alternatives considered**:

- *A metrics facade in `multiroom-api`*: an upstream change for something the host already
  provides. Rejected; documenting Micrometer as host-provided in the upstream guide is worthwhile
  but separate.
- *`Metrics.globalRegistry`*, as core's `AudioPipeline` uses: works, but hides the dependency and
  makes tests share global state. Rejected in favour of an injected registry.

## R2. Wiring: an injected `TtsMetrics`, with a no-op when there is nothing to record into

**Decision**: A small interface, `TtsMetrics`, with two implementations:

- `MicrometerTtsMetrics`, built from the host's `MeterRegistry`
- `NoopTtsMetrics`, which records nothing

`TtsAutoConfiguration` gains two nested configurations, and exactly one of them applies:

- `@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")` defines the bean
  from `ObjectProvider<MeterRegistry>.getIfAvailable()`: Micrometer when a registry exists,
  otherwise the no-op.
- `@ConditionalOnMissingClass` of the same name defines the no-op.

The class is named by string so that `TtsAutoConfiguration` still loads if Micrometer is missing.

**Rationale**:

- *No ordering race*: `ObjectProvider` resolves the registry when the bean is created, after
  every bean definition (the actuator's included) is registered. Auto-configuration order matters
  only for `@ConditionalOnBean`, which this design does not use, so no `after = …` reference to an
  actuator class is needed.
- *Missing metrics are not a fault* (FR-002): neither branch can fail start-up.
- *Disabled means nothing* (FR-003): with `multiroom.tts.enabled=false` the whole
  `TtsAutoConfiguration` is skipped, so no `TtsMetrics` exists and no meter is registered.
- *One seam*: `TtsService`, the queue and the REST advice call `TtsMetrics` methods named after
  events (`announcementAccepted`, `synthesisFinished`, …). None of them sees a Micrometer type,
  and their tests stay plain Mockito.

**Alternatives considered**:

- *Inject `MeterRegistry` directly*: every class would need a null path, and the no-Micrometer
  case could not even load the classes. Rejected.
- *`MeterBinder` for everything*: good for gauges, but counters and timers need the registry
  on every event anyway. `MicrometerTtsMetrics` registers the cache gauges once in its constructor
  instead, which amounts to the same thing.
- *`SimpleMeterRegistry` as the fallback*: it would hold meters nobody reads. Rejected for the
  no-op.

## R3. Names, types and the Prometheus names they produce

**Decision** (the full contract is [contracts/metrics.md](contracts/metrics.md)):

| Micrometer name | Type | Base unit | Prometheus series |
|---|---|---|---|
| `tts.announcements` | Counter | – | `tts_announcements_total` |
| `tts.synthesis` | Timer, with histogram buckets | seconds | `tts_synthesis_seconds_{count,sum,max,bucket}` |
| `tts.synthesis.characters` | DistributionSummary | characters | `tts_synthesis_characters_{count,sum,max}` |
| `tts.synthesis.audio` | DistributionSummary | seconds | `tts_synthesis_audio_seconds_{count,sum,max}` |
| `tts.cache.requests` | Counter | – | `tts_cache_requests_total` |
| `tts.cache.size` | Gauge | bytes | `tts_cache_size_bytes` |
| `tts.cache.max` | Gauge | bytes | `tts_cache_max_bytes` |
| `tts.cache.entries` | Gauge | – | `tts_cache_entries` |
| `tts.queue.depth` | Gauge | – | `tts_queue_depth` |
| `tts.playbacks` | Counter | – | `tts_playbacks_total` |

**Rationale**:

- Everything starts with `tts.` (FR-004), which also makes the standard Spring Boot filter
  `management.metrics.enable.tts=false` switch all of them off with no code here.
- A test registers them in a real `PrometheusMeterRegistry` and asserts the scraped names, so a
  Micrometer naming change cannot silently break the guide's queries.

## R4. Prometheus requires one tag-key set per metric name

**Finding**: `PrometheusMeterRegistry` 1.15.12 refuses a meter whose name is already registered
with a different set of tag keys ("Prometheus requires that all meters with the same name have
the same set of tag keys"). The refused meter is simply missing from the scrape, which is easy to
miss in a test that uses `SimpleMeterRegistry`.

**Decision**: Every metric has a fixed key set, and a value that does not apply is a fixed word,
never a missing tag:

- an accepted announcement carries `error=none` and `reason=none`
- a provider with no billing tier carries `tier=none`

The Prometheus name test also registers one example of every tag combination the code can
produce, and asserts that each appears in the scrape.

## R5. Where each event is recorded

| Event | Where | Why there |
|---|---|---|
| Announcement accepted, or rejected by the announcement logic | `TtsService.speak`, around its whole body | It is the one place that knows the resolved provider name and every error the logic throws |
| Rejected by the basic request checks | `TtsExceptionHandler`, for `MethodArgumentNotValidException` and a new `HttpMessageNotReadableException` handler | These are rejected before `speak` runs (see R6) |
| Synthesis time, characters, audio duration | `TtsService.synthesize` and the line after conversion in `speak` | The single call site of `TtsProvider.synthesize` for every provider type (FR-021) |
| Cache hit or miss | `TtsService.speak`, at the `audioCache.get` branch | An in-memory counter increment; no I/O (FR-013) |
| Cache size, entries, maximum | Gauges on `AudioCache.stats()` | `FilesystemAudioCache.stats()` walks the in-memory index under its lock; no disk access |
| Queue depth | A gauge per target, registered on its first enqueue | See R9 |
| Playback started or failed | `TtsService.activate`, plus a wrapper around the activator | See R10 |

## R6. Malformed request bodies get the published `INVALID_REQUEST` answer

**Finding**: A body that cannot be parsed (broken JSON, or an unknown `targetType`) raises
`HttpMessageNotReadableException`, which `TtsExceptionHandler` does not handle today. Spring
answers 400 with its default error body, not the contract's `ErrorResponse`. That already
contradicts the published contract, which says every 400 from `/api/tts/speak` carries an
`ErrorResponse`.

**Decision**: Add a handler for `HttpMessageNotReadableException` to `TtsExceptionHandler`. It
answers 400 with `ErrorResponse(INVALID_REQUEST, …)` and records the rejection. The message says
the body could not be read and never echoes the body. The handler is scoped like the others
(`assignableTypes`), so no other extension's endpoints are affected. The status is unchanged, so
the only visible difference is that the body now matches the contract.

**Not counted**: Requests Spring refuses before any controller or advice is chosen, such as a
wrong method (405) or a wrong content type (415). These are not answered by this extension, and
they still appear in the host's own `http_server_requests` metric. The guide says so.

## R7. Tag values, and how each stays bounded

| Tag | Values | Source |
|---|---|---|
| `provider` | a configured provider name, or `unknown` | the request's provider name only if `ProviderRegistry` has it, the default provider's name when none is given, otherwise `unknown` |
| `type` | `openai`, `google-cloud`, `google-gemini`, `piper`, `local-http`, or `unknown` | new `TtsProvider.type()`, spelled as in the configuration |
| `outcome` | announcements: `accepted`, `rejected`; synthesis: `success`, `failure`; playbacks: `started`, `failed`; cache: `hit`, `miss` | fixed |
| `error` | a `TtsErrorCode` name, `INTERNAL`, or `none` | `TtsException.getErrorCode()`; `INTERNAL` for any other exception (the caller gets 500 with no code) |
| `reason` | `queue_full`, `shutting_down`, `none` | new `QueueRejectedException` (R8) |
| `tier` | a `GoogleEngine.canonical()` name, `other`, the Gemini entry's configured model, or `none` | new `TtsProvider.billingTier(SynthesisSettings)` |
| `part` | `text`, `style_prompt` | fixed |
| `target`, `target_type` | a target name that passed validation; `output` or `group` | `AnnounceCommand` after `validateTargetExists` |

**Notes**:

- The synthesis timer's outcome is `success` or the error code, as FR-008 asks. It uses tag
  `error` beside `outcome=success|failure`, which keeps the shape of the announcement counter and
  makes `outcome="failure"` a simple filter.
- `unknown` could collide with an operator's provider actually named `unknown`. That is harmless
  (both mean "that name"), and the guide mentions it.
- Unrecognized Google engine segments (`Polyglot`, a future engine) map to `other`, so a free-text
  voice name cannot mint a tier.
- A Gemini model is configuration, not request input, so it is bounded by the number of entries.

## R8. Queue rejections carry a reason without changing the error code

**Decision**: `AnnouncementQueueManager` throws `QueueRejectedException extends TtsException`,
with a `Reason` enum (`QUEUE_FULL`, `SHUTTING_DOWN`), keeping `TtsErrorCode.PROVIDER_ERROR` and
the same messages. `TtsExceptionHandler` maps it exactly as before, because it is a
`TtsException`. `TtsService` reads the reason when it records the rejection.

**Rationale**: the caller-visible contract is untouched (spec clarification). A subtype is the
smallest change that keeps the fact where it is known.

## R9. Queue depth per target

**Decision**: `AnnouncementQueueManager` gains `int depth(String targetKey)`, which returns the
number of tasks waiting, excluding the one playing (the worker takes a task off the queue before
activating it). After a successful enqueue, `TtsService` calls
`metrics.trackQueue(targetType, targetName, () -> queueManager.depth(key))`.
`MicrometerTtsMetrics` registers the gauge once per target key, remembering which keys it has
seen, so later calls do nothing.

**Rationale**: the gauge reads the live queue at scrape time, so it drops to zero by itself once
the queue drains. Only validated targets get a series (the target check runs before the enqueue).
Queues are never removed from the manager's map, so the gauge's reference stays valid.

## R10. Playback started and failed

**Decision**:

- *Started*: in `TtsService.activate`, right after `routeService.createRoute` returns.
- *Failed*: in `activate`'s existing catch block. `activate` also fails if `registerInput` or
  `track` throw before that `try`. Those failures reach `AnnouncementQueueManager`'s own catch,
  so the activator passed to the queue is wrapped to count them. Because `activate`'s inner catch
  does not rethrow, no failure is counted twice.

## R11. Synthesis: timing, characters, audio duration, tier

**Decision**:

- *Before the provider is called* (FR-021): `TtsService.synthesize` builds a `SynthesisUsage`
  value with the provider name, type, tier, text characters and style-prompt characters, then
  starts a `Timer.Sample`. A later budget check reads the same value at the same point.
- *After*: stop the sample with `outcome` and `error`, and record both character parts with the
  same outcome.
- *Characters*: counted as Unicode code points (`String.codePointCount`), not UTF-16 `char`s, so
  an emoji is one character, as the providers bill it. The style prompt is
  `SynthesisSettings.stylePrompt()`, which is non-null only for `google-gemini`. A `style_prompt`
  sample of 0 is not recorded.
- *Audio duration* (FR-010c): after `audioConverter.convert` returns the native PCM,
  `pcm.length / (sampleRate × channels × bytesPerSample)` of `SampleFormat.standard()`. This is
  arithmetic on bytes already in memory, and it is exact because the audio is already converted.
  Conversion itself stays outside the timer (FR-010).
- *A synthesis that fails before anything is sent* (for example, `INVALID_VOICE` from the Google
  voice check, or a token failure) is still recorded with its error code. The `outcome` tag lets
  a budget exclude it.

## R12. Histogram buckets

**Decision**: Fixed buckets (`serviceLevelObjectives`), not Micrometer's generated percentile
histogram: 0.1, 0.25, 0.5, 1, 2, 3, 5, 7.5, 10, 15, 20, 30, 45, 60, 90 and 120 s. The list is cut
after the first value at or above the longest configured `timeout-seconds`, and that timeout
itself is added as a bucket if it is not already one. The longest timeout is read from
`TtsProperties` when the bean is built.

**Rationale**:

- FR-008a asks for buckets up to the longest timeout, so p99 against the timeout is readable.
- Micrometer's generated histogram produces about 70 buckets per series. With 4 providers and 3
  outcomes that is over 800 series. The fixed list gives at most 17 buckets per series, and
  `histogram_quantile` interpolates well enough between them for p95/p99.
- Percentiles computed inside the extension are ruled out by the clarification.

## R13. Recording never breaks an announcement

**Decision**: every public method of `MicrometerTtsMetrics` catches `RuntimeException`, logs it
at `WARN` once per metric (then `DEBUG`), and returns (FR-018). Micrometer does no I/O on record;
the scrape happens on the actuator's thread, not the announcement's.

## R14. Versions and upstream

- The extension goes from `0.1.3` to `0.1.4` (the spec's assumption; the project isn't public, so
  a patch bump fits).
- No `multiroom-api` change, so `multiroom.require_api_version` stays `0.1.18` and there is no
  multiroom-ai branch.

## R15. Documentation

- New `docs/metrics.md` (FR-019, FR-023), linked from the README's documentation table and from
  `docs/configuration.md` (FR-020).
- `docs/future/google-cloud-free-tier-tracking.md` (FR-024) is listed in this clone's
  `.git/info/exclude`, so it is not tracked. It is updated here, but the change stays local.
  Tracking it is the owner's decision.

## R16. Testing approach

- *`MicrometerTtsMetricsTest`*: a `SimpleMeterRegistry`. Every event method records the right
  meter with exactly the right tags. Gauges follow a stubbed `AudioCache` and a queue-depth
  supplier. A throwing registry does not propagate (FR-018).
- *`PrometheusNamesTest`*: a `PrometheusMeterRegistry`. It records one example of every tag
  combination, scrapes, and asserts every series name in [contracts/metrics.md](contracts/metrics.md)
  is present and every bucket boundary is as planned (R4, R12).
- *`TtsServiceTest`* (extended, `TtsMetrics` mocked): accepted/rejected with the right provider
  tag; `unknown` for an unconfigured name; a hit records no synthesis; a miss records time,
  characters by part, and audio duration; a queue rejection carries its reason; playback
  started/failed, each counted once.
- *`TtsExceptionHandlerTest` / `TtsControllerTest`*: a malformed body answers 400 `ErrorResponse`
  with `INVALID_REQUEST` and records a rejection; bean-validation failures record one too.
- *`TtsAutoConfigurationMetricsTest`*: an `ApplicationContextRunner`.
  - With a `SimpleMeterRegistry` bean → Micrometer metrics.
  - With no registry → no-op.
  - With `FilteredClassLoader(MeterRegistry.class)` → the context starts with the no-op (SC-006).
  - With `multiroom.tts.enabled=false` → no `TtsMetrics` bean.
- *Cardinality* (SC-005): a `TtsServiceTest`-level loop of 10,000 random texts, voices and
  unknown provider names through a `SimpleMeterRegistry`-backed `MicrometerTtsMetrics`. The meter
  count equals the count after 10 requests.
- *Cache-hit cost* (SC-004): measured by hand in [quickstart.md](quickstart.md), not as a test,
  since a timing assertion would be flaky on a shared machine.
