# Data Model: TTS Metrics

**Feature**: `005-tts-metrics` | **Date**: 2026-09-26

This feature stores nothing on disk. Its "data" is the published metrics, whose contract is
[contracts/metrics.md](contracts/metrics.md), plus a few in-process types that carry the values
to them. The decisions behind each are in [research.md](research.md).

## New types

### `TtsMetrics` (interface, `multiroom.tts.metrics`)

The one seam through which the rest of the module reports events. It never exposes a Micrometer
type, so callers and their tests do not depend on Micrometer.

| Method | Called from | Records |
|---|---|---|
| `announcementAccepted(String providerTag)` | `TtsService.speak` | `tts.announcements` accepted |
| `announcementRejected(String providerTag, String error, String reason)` | `TtsService.speak`, `TtsExceptionHandler` | `tts.announcements` rejected |
| `cacheLookup(boolean hit)` | `TtsService.speak` | `tts.cache.requests` |
| `synthesisStarted(SynthesisUsage usage)` → `SynthesisTimer` | `TtsService.synthesize` | starts the sample |
| `SynthesisTimer.succeeded()` / `failed(TtsErrorCode)` | `TtsService.synthesize` | `tts.synthesis`, `tts.synthesis.characters` (per part) |
| `audioProduced(SynthesisUsage usage, double seconds)` | `TtsService.speak`, after conversion | `tts.synthesis.audio` |
| `trackQueue(TargetType type, String target, IntSupplier depth)` | `TtsService.speak`, after enqueue | `tts.queue.depth`, once per target |
| `playbackStarted()` / `playbackFailed()` | `TtsService.activate` and its wrapper | `tts.playbacks` |

**Rules**: No method throws (FR-018). Tag values are passed in already bounded; the
implementation turns a `null` into `unknown` or `none`, and never passes free text through.

### `MicrometerTtsMetrics` (class, `multiroom.tts.metrics`)

Implements `TtsMetrics` over a `MeterRegistry`.

**Fields**: the registry; the synthesis bucket bounds (research R12); a
`ConcurrentHashMap.newKeySet()` of the target keys that already have a queue gauge.

**In its constructor**: it registers the three cache gauges against the `AudioCache` bean,
with a strong reference.

**Lifecycle**: a singleton bean, alive as long as the context.

### `NoopTtsMetrics` (class, `multiroom.tts.metrics`)

Implements every method as a no-op. `synthesisStarted` returns a shared no-op `SynthesisTimer`.
It is used when Micrometer is missing from the classpath or no registry bean exists.

### `SynthesisUsage` (record, `multiroom.tts.metrics`)

The work one synthesis asks of a provider. It is built once in `TtsService.synthesize`, before
the provider is called (FR-021). A future budget check reads the same value.

| Field | Type | Source | Rule |
|---|---|---|---|
| `providerName` | `String` | the resolved provider name | always configured |
| `providerType` | `ProviderType` | `TtsProvider.type()` | `null` only for a test double; published as `unknown` |
| `tier` | `String` | `TtsProvider.billingTier(settings)` | bounded; `none` by default |
| `textCharacters` | `int` | `text.codePointCount(0, text.length())` | ≥ 1 (blank text is rejected earlier) |
| `stylePromptCharacters` | `int` | the same, on `settings.stylePrompt()` | 0 when there is no prompt; a 0 part is not recorded |

`billableCharacters()` returns `textCharacters + stylePromptCharacters`.

### `QueueRejectedException` (class, `multiroom.tts.queue`)

`extends TtsException`, always with `TtsErrorCode.PROVIDER_ERROR` and the messages the queue uses
today. It adds `Reason reason()`, where `enum Reason { QUEUE_FULL, SHUTTING_DOWN }` is published as
`queue_full` or `shutting_down`. `TtsExceptionHandler` needs no change for it: it is handled as a
`TtsException`.

## Changed types

| Type | Change |
|---|---|
| `TtsProvider` | + `ProviderType type()`, implemented by all five providers. + `default String billingTier(SynthesisSettings settings)` returning `"none"`. `GoogleCloudTtsProvider` overrides it with the engine family of the resolved voice (`GoogleEngine.canonical()`, or `other` for an unrecognized engine). `GoogleGeminiTtsProvider` overrides it with its configured model name. Both are pure, like `resolveSettings` |
| `ProviderType` | + `configName()`, e.g. `google-cloud`, the spelling used in configuration and in the `type` tag |
| `ProviderRegistry` | + `boolean isConfigured(String name)`, used to pick the `provider` tag without catching `PROVIDER_NOT_FOUND` |
| `AnnouncementQueueManager` | throws `QueueRejectedException` for a full queue and during shutdown; + `int depth(String targetKey)` (0 for an unknown key) |
| `TtsService` | takes `TtsMetrics`; records the events in the table above; wraps the activator it hands to the queue so a failure before route creation counts as `playbackFailed` |
| `TtsExceptionHandler` | takes `TtsMetrics`. The existing bean-validation handler records `announcementRejected("unknown", "INVALID_REQUEST", "none")` when the failing controller is `TtsController`. A new `HttpMessageNotReadableException` handler answers 400 `ErrorResponse(INVALID_REQUEST, "Request body could not be read")` and records the same |
| `TtsAutoConfiguration` | two nested configurations select `MicrometerTtsMetrics` or `NoopTtsMetrics` (research R2); `ttsService` takes the `TtsMetrics` bean |
| `pom.xml` | version `0.1.4`; + `micrometer-core` (`provided`); + `micrometer-registry-prometheus` (`test`) |

## State

The only state is the meters themselves, owned by the host's registry, and the set of target keys
that already have a queue gauge. Counters only grow; gauges read live values. Nothing survives a
restart, by design (see the spec's "Groundwork for a character budget").
