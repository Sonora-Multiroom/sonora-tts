# Feature Specification: TTS Metrics

**Feature Branch**: `005-tts-metrics`

**Created**: 2026-09-26

**Status**: Draft

**Depends on**: `001-tts-extension` through `004-gemini-tts-provider`. This feature only
observes. Announcements, providers, the cache, the queue and the REST contract behave exactly as
they do in 0.1.3, with one exception: a speak request whose body cannot be read now gets the
published `ErrorResponse` with `INVALID_REQUEST` (still HTTP 400) instead of the framework's
default error body, which brings it in line with the 0.1.3 contract.

**Input**: User description: "Micrometer metrics for the TTS extension, exported via the host's
Prometheus endpoint. Add io.micrometer:micrometer-core at provided scope (host supplies it on the
shared classloader; version managed by spring-boot-dependencies). Obtain the registry via
ObjectProvider<MeterRegistry> with a no-op fallback so the extension still loads if the host has
no actuator; avoid ordering races with the host's registry (auto-configuration after
MetricsAutoConfiguration, or MeterBinder for gauges). Metrics: tts.synthesis timer tagged by
provider name, provider type and outcome (success / error code); tts.cache.requests counter tagged
hit/miss; gauges for cache size in bytes and entry count; per-target playback queue depth gauge;
tts.announcements counter tagged by outcome and error code. Tag cardinality must stay bounded (no
message text, no voice free-text beyond configured provider names). multiroom.tts.enabled=false
contributes no meters. Recording metrics must not add I/O to the cache-hit path."

## Overview

On 2026-09-26 the production host's metrics endpoint (`/actuator/prometheus` on
`multiroom.lan`) showed only the framework's standard series: JVM, process, Tomcat, logging and
HTTP requests. No extension publishes a metric of its own. The only way to see how the TTS
extension is doing is to read its logs or call `GET /api/tts/cache/stats` by hand.

The host already collects metrics and exposes them for Prometheus to scrape, and since feature
019 every extension shares the host's classpath. Nothing in the extension contract stops an
extension from recording its own metrics; this one simply never has.

This feature makes the extension record what an operator needs to run it:

- how many announcements arrive, and how many are rejected and why
- how long each provider takes to synthesize speech, and how often it fails
- how often the cache saves a synthesis, and how full it is
- whether announcements are piling up behind a busy target
- whether an accepted announcement actually started playing

All of it appears in the host's existing metrics endpoint. No new endpoint is added and no
existing one changes.

### Groundwork for a character budget

[docs/future/google-cloud-free-tier-tracking.md](../../docs/future/google-cloud-free-tier-tracking.md)
(a local working note, excluded from git, so the link resolves only in the author's checkout)
describes a later feature: a monthly character budget per provider entry, so an operator can stop
serving TTS (or fall back to another provider) before Google's free tier runs out or an OpenAI bill
grows. Its conclusion is that the only reliable source is the extension counting the characters it
sends itself.

This feature does not enforce a budget, but it settles the part both features share, so the
budget is a small step on top rather than a second, slightly different count:

- **One definition of billable usage**: what is counted (the text and any style prompt sent to a
  provider), when (only when a provider is actually called, never on a cache hit), and how the
  outcome is recorded (a failed call is counted but marked as failed, so the budget can decide
  whether failures count against it).
- **One place it is measured**: the count is taken once, before the provider is called, at the
  point every provider shares. A budget check needs exactly that figure at exactly that moment.
- **The billing tier alongside it**: Google's free allowance differs by voice family (Standard and
  WaveNet, Neural2 and Studio, Chirp 3 HD, Gemini models), so usage is broken down by that family.
- **Visibility before enforcement**: an operator can see how close each provider comes to its
  allowance this month now, and choose a budget from real numbers when that feature arrives.

What stays out: the metrics are not the budget's ledger. They reset when the host restarts or the
JAR is redeployed, and their history lives in Prometheus, not in the extension. A budget has to
survive restarts within a month, so it will keep its own persisted monthly totals.

## Clarifications

Decisions made from the request and the code, and recorded in Assumptions:

- the playback counter (User Story 4) is an addition to the request, because an accepted
  announcement can still fail when its route is created, and nothing else would show it
- the provider tag on a rejected announcement is kept only when the provider name is a configured
  one, so a caller cannot grow the series count by sending unknown names

### Session 2026-09-26

- Q: Should the synthesis timer also publish a latency distribution (p95/p99 and histogram
  buckets per provider), or only count, total and maximum? → A: Also publish histogram buckets,
  sized to cover each provider's configured timeout, so Prometheus computes p95/p99.
- Q: A full queue is reported to the caller as `PROVIDER_ERROR`, like a real provider failure;
  should the metrics tell the two apart, and how? → A: Add a `reason` tag on rejections from a
  fixed set (`queue_full`, `shutting_down`, `none` otherwise); the error code stays as the caller
  sees it.
- Q: Should announcements rejected by basic request checks before reaching the TTS logic (blank
  text or target, malformed JSON) be counted? → A: Yes; every speak request that gets a response
  is counted, as `rejected` with `INVALID_REQUEST` when it fails those checks.

## User Scenarios & Testing *(mandatory)*

The actor in every story is the **operator**: the person who runs the host, configures the TTS
providers and watches the system through Prometheus and Grafana.

### User Story 1 - See Announcement Volume and Failures (Priority: P1)

An operator opens their dashboard and sees how many announcements the extension accepted and
rejected over time, with each rejection broken down by its error code (`TARGET_NOT_FOUND`,
`PROVIDER_TIMEOUT`, `INVALID_VOICE` and the rest). A spike in one code tells them where to look
without reading logs.

**Why this priority**: It is the one number that answers "is TTS working?". Every other metric
explains a change in this one.

**Independent Test**: Send one valid announcement and one to a target that does not exist, then
read the host's metrics endpoint. One series shows one accepted announcement; another shows one
rejection tagged `TARGET_NOT_FOUND`.

**Acceptance Scenarios**:

1. **Given** the extension is enabled, **When** a caller sends an announcement that is accepted,
   **Then** the announcement count tagged `accepted` rises by one.
2. **Given** the extension is enabled, **When** a caller sends an announcement that is rejected
   with any error code, **Then** the announcement count tagged `rejected` and that error code
   rises by one.
3. **Given** a caller names a provider that is not configured, **When** the announcement is
   rejected with `PROVIDER_NOT_FOUND`, **Then** the rejection is counted without the unknown name
   appearing in any tag.
4. **Given** the host has just started, **When** the operator reads the metrics endpoint before
   any announcement, **Then** the announcement series are present with a count of zero for
   `accepted`.

---

### User Story 2 - Compare Provider Latency and Reliability (Priority: P1)

An operator sees, for each configured provider entry, how long synthesis takes and how often it
fails, and which way it fails. They can tell that `google-gemini` takes four times as long as
`google-cloud`, or that the local Piper engine has started timing out, and decide which provider
should be the default.

**Why this priority**: Providers are remote, billed and slow compared with everything else the
extension does. Their latency is the latency an announcement has, and their failures are the
failures an operator can act on (a quota, a key, a stopped local engine).

Raw synthesis time is not comparable on its own: a 300-character announcement takes longer than
a 20-character one on any provider. So the operator also sees how much work each synthesis was:
the characters sent and the seconds of audio returned. From those they read time per 1,000
characters and the real-time factor (seconds spent per second of speech produced), which compare
providers and spot a slowdown whatever the message mix. The character count is also what cloud
providers bill by, so it doubles as a usage figure per provider.

**Independent Test**: Configure two providers, synthesize with each (cache misses), make one
fail, and read the endpoint. Each provider has its own timing series with its type, and the
failure is recorded against the failing provider with its error code. The character total of
each provider equals the sum of the message lengths it synthesized.

**Acceptance Scenarios**:

1. **Given** a cache miss, **When** the provider synthesizes successfully, **Then** one timing is
   recorded tagged with the provider's configured name, its type and outcome `success`.
2. **Given** a cache miss, **When** the provider fails, **Then** one timing is recorded tagged
   with the provider's name, its type and the error code the announcement was rejected with
   (for example `PROVIDER_TIMEOUT`).
3. **Given** a cache hit, **When** the announcement is served, **Then** no synthesis timing is
   recorded.
4. **Given** a cache miss, **When** the provider is asked to synthesize a message of N
   characters, **Then** N is added to that provider's character total, whether the synthesis
   succeeds or fails.
5. **Given** a successful synthesis, **When** its audio lasts D seconds, **Then** D is added to
   that provider's audio-duration total.
6. **Given** two providers synthesizing messages of different lengths, **When** the operator
   divides each provider's total synthesis time by its total characters, **Then** the result is
   its time per character, comparable between the two.

---

### User Story 3 - Watch the Cache Work (Priority: P2)

An operator sees the share of announcements served from the cache, and how full the cache is in
bytes and entries against its configured limit. A falling hit rate after a change to message
templates, or a cache stuck at its limit, is visible at a glance.

**Why this priority**: The cache is what keeps repeated announcements fast and free. Its hit rate
is the main lever on provider cost, but the service still works without the metric.

**Independent Test**: Send the same announcement twice, then read the endpoint. The miss count
and the hit count each show one, and the size gauges match `GET /api/tts/cache/stats`.

**Acceptance Scenarios**:

1. **Given** an announcement whose audio is cached, **When** it is served, **Then** the cache
   request count tagged `hit` rises by one.
2. **Given** an announcement whose audio is not cached, **When** it is synthesized, **Then** the
   cache request count tagged `miss` rises by one.
3. **Given** any cache state, **When** the endpoint is read, **Then** the cache size in bytes,
   the entry count and the configured limit match what `GET /api/tts/cache/stats` reports at the
   same moment.
4. **Given** an operator clears the cache through the REST API, **When** the endpoint is next
   read, **Then** the size and entry gauges show the emptied cache.

---

### User Story 4 - Spot Backlog and Playback Failures (Priority: P3)

An operator sees how many announcements are waiting behind each target, and how many accepted
announcements failed to start playing. A queue that never drains, or routes that keep failing on
one speaker, show up before a listener complains.

**Why this priority**: Both are rare, and both happen after the caller has already been told
"accepted", so no response code reveals them. They matter for diagnosis rather than for daily
health.

**Independent Test**: Send three announcements to one target in quick succession and read the
queue depth for that target while the first plays; then make route creation fail and read the
playback failure count.

**Acceptance Scenarios**:

1. **Given** announcements are waiting for a target, **When** the endpoint is read, **Then** that
   target's queue depth equals the number of announcements waiting for it.
2. **Given** a target's queue has drained, **When** the endpoint is read, **Then** its queue depth
   is zero.
3. **Given** a queued announcement is dequeued, **When** its playback starts, **Then** the
   playback count tagged `started` rises by one.
4. **Given** a queued announcement is dequeued, **When** its playback cannot start, **Then** the
   playback count tagged `failed` rises by one.

---

### Edge Cases

- **The host collects no metrics** (no metrics support on its classpath or none configured): the
  extension starts and serves announcements exactly as before, and records nothing.
- **`multiroom.tts.enabled=false`**: the extension contributes no metrics at all, including no
  zero-valued series.
- **A caller-supplied value that is not configured** (an unknown provider name, a free-text voice,
  a style prompt, the message text): never becomes a tag value. Only configured provider names,
  provider types, target names that passed validation, error codes and fixed outcome words are
  used.
- **A target that is removed from the host after announcements were sent to it**: its queue-depth
  series may remain at zero until restart; it does not grow without bound, because only targets
  that passed validation ever get a series.
- **A synthesis that fails with an unexpected exception**: is recorded with the error code the
  announcement is rejected with (`PROVIDER_ERROR`), never with an exception class name.
- **A synthesis that succeeds but whose audio cannot be converted**: the synthesis is recorded as
  `success` (conversion is outside the timing), its characters as `success` (they were sent and
  billed), no audio duration is recorded (there is no converted audio to measure), and the
  announcement counts as `rejected` with `FORMAT_NORMALIZATION_FAILED`.
- **A cache write failure that spills the audio to a temporary file**: the request still counts
  as a `miss`, and the announcement as `accepted`.
- **A full queue**: the rejection counts as `rejected` with the code the caller receives
  (currently `PROVIDER_ERROR`) and reason `queue_full`, so an alert on provider errors can
  exclude it. A rejection while the extension shuts down gets reason `shutting_down`.
- **The extension is redeployed or the host restarts**: counts start again from zero; this is the
  normal behaviour of the monitoring system these metrics feed, which handles counter resets.
- **A malformed or incomplete request body**: counts as `rejected` with `INVALID_REQUEST` and no
  provider (the placeholder), even though it never reached the announcement logic.
- **Reading the metrics endpoint**: does not synthesize, touch the disk or contact a provider.

## Requirements *(mandatory)*

### Functional Requirements

**Publication**

- **FR-001**: The extension MUST publish its metrics through the host's existing metrics
  collection, so they appear in the host's Prometheus endpoint with no new endpoint and no
  operator configuration.
- **FR-002**: If the host collects no metrics, the extension MUST start and serve announcements
  unchanged, and MUST NOT fail start-up for that reason.
- **FR-003**: With `multiroom.tts.enabled=false`, the extension MUST contribute no metrics.
- **FR-004**: Every metric MUST be named under the `tts` prefix so an operator can find all of
  them with one query.

**Announcements**

- **FR-005**: The extension MUST count every speak request it answers, with an outcome of
  `accepted` or `rejected`. That includes requests rejected by the basic request checks before
  the announcement logic runs (a blank text or target, a malformed body), which count as
  `rejected` with `INVALID_REQUEST`, so the failure rate reflects every call a client made.
- **FR-006**: A rejected announcement MUST carry the error code the caller receives, from the
  published set of error codes, or the fixed value `INTERNAL` when the caller received HTTP 500
  with no code. An accepted one MUST carry no error code (a fixed `none` value).
- **FR-006a**: Every announcement count MUST also carry a `reason` tag from a fixed set:
  `queue_full` when the target's queue had no room, `shutting_down` when the extension was
  stopping, and `none` otherwise. It separates rejections that share an error code with a real
  provider failure (both are `PROVIDER_ERROR` today), without changing what the caller receives.
- **FR-007**: An announcement count MUST carry the provider name only when that name is a
  configured provider (named explicitly or the default); otherwise the provider tag MUST be the
  fixed placeholder `unknown`.

**Synthesis**

- **FR-008**: Every synthesis the extension asks a provider for MUST be timed, from the call to
  the provider until it returns audio or fails, and tagged with the provider's configured name,
  its provider type, an outcome of `success` or `failure`, and an error tag carrying the error
  code the announcement is rejected with (`none` on success).
- **FR-008a**: The synthesis timing MUST also publish a latency distribution as histogram
  buckets, so p95 and p99 per provider can be computed over any window and across restarts. The
  buckets MUST reach at least the longest configured provider timeout, so a provider's slow tail
  is visible before it turns into timeouts. Percentiles MUST NOT be computed inside the
  extension, since those cannot be combined across windows.
- **FR-009**: A cache hit MUST NOT record a synthesis timing.
- **FR-010**: Audio format conversion after synthesis MUST NOT be included in the synthesis
  timing.
- **FR-010a**: For every synthesis, the extension MUST record the number of characters sent to
  the provider, tagged with the provider's name, type, billing tier, the same outcome and error
  as its timing, and the part of the request the characters belong to: `text` (the message) or
  `style_prompt` (a style prompt, sent only to `google-gemini`). Time per character uses the
  `text` part; billable usage is the sum of both.
- **FR-010b**: The billing tier tag MUST be the voice family the provider bills by: for
  `google-cloud`, the engine family of the resolved voice (`Standard`, `Wavenet`, `Neural2`,
  `Studio`, `Chirp-HD`, `Chirp3-HD`, or `other` for an engine it does not recognise); for `google-gemini`, the entry's configured model; for every
  other type, a fixed `none`. It MUST come from the resolved settings or configuration, never
  from caller free text.
- **FR-010c**: For every successful synthesis, the extension MUST record the duration in seconds
  of the audio produced, tagged with the provider's name, type and billing tier (Gemini bills
  audio output, so the tier matters here too), so the real-time factor
  (synthesis time over audio duration) is computable. The duration MUST be derived from the
  audio already in memory, with no extra disk or network work.
- **FR-010d**: The character and audio-duration figures MUST keep both a running total and a
  count, so an operator can compute per-provider averages and ratios over any time window. The
  message length itself MUST NOT become a tag.

**Cache**

- **FR-011**: Every cache lookup made for an announcement MUST be counted as `hit` or `miss`.
- **FR-012**: The extension MUST publish the cache's current size in bytes, its entry count and
  its configured maximum size in bytes, consistent with `GET /api/tts/cache/stats`.
- **FR-013**: Recording a cache hit MUST add no disk, network or subprocess work to the hit path.

**Queue and playback**

- **FR-014**: The extension MUST publish, for each target that has received an announcement, the
  number of announcements waiting in that target's queue.
- **FR-015**: The extension MUST count queued announcements whose playback started, and those
  whose playback could not start, as `started` and `failed`.

**Cardinality and safety**

- **FR-016**: Tag values MUST come only from bounded sets: configured provider names, provider
  types, billing tiers (engine families, `other` and configured Gemini models), request parts
  (`text`, `style_prompt`), published error codes and `INTERNAL`, rejection reasons, fixed words
  (outcomes, `none`, `unknown`), and names of targets that passed validation. Message text, voices, languages, style prompts, announcement ids and exception
  messages MUST NOT appear in any tag.
- **FR-017**: No metric MUST reveal a key, token, key file content or request body.
- **FR-018**: A failure while recording a metric MUST NOT fail or delay an announcement.

**Documentation**

- **FR-019**: The feature MUST ship an operator guide, `docs/metrics.md`, as part of its
  completion. It MUST cover:
  - **Where the metrics are**: the host's Prometheus endpoint, what the host must expose for them
    to appear, and that `multiroom.tts.enabled=false` removes them
  - **A reference of every metric**: its name as Prometheus shows it, its type, what one
    increment or sample means, and when it is recorded (for example, synthesis only on a cache
    miss)
  - **A reference of every tag**: its possible values (configured provider names, provider
    types, the error codes, the rejection reasons, the fixed outcome words, target names), and the placeholder used
    for an unknown provider
  - **Ready-to-use queries** for: announcement failure rate by error code, timeout rate per
    provider, average, maximum, p95 and p99 synthesis time per provider (and p99 against the
    provider's configured timeout, which the operator enters into the query or the dashboard as
    a constant, because a timeout is configuration and is not published as a metric), time per
    1,000 characters,
    real-time factor, characters sent per provider per day (usage and billing), cache hit
    ratio, cache fill against its limit, and queue depth per target
  - **How to read them**: counters reset on restart or redeploy, time per character is a ratio
    over a window and favours long messages, and what the metrics deliberately leave out
    (message text, voices, style prompts)
- **FR-020**: The README's documentation table and `docs/configuration.md` MUST link to
  `docs/metrics.md`.

**Groundwork for a character budget**

- **FR-021**: The characters sent to a provider MUST be measured in exactly one place, shared by
  every provider type, immediately before the provider is called, so a later budget check can
  read the same figure at the same moment and refuse the call before it is made. The metric
  records that figure once the call ends, together with its outcome; a budget reads the measured
  figure, not the metric.
- **FR-022**: A cache hit and a request rejected before a provider is called (invalid text, unknown
  target, unknown provider, settings the provider's resolver refuses) MUST NOT add to the
  characters counted, because neither is billed. A failure raised inside a provider call before
  anything reaches the provider's billed endpoint (a voice the voice catalogue does not list, a
  token that cannot be obtained) is counted with `outcome="failure"`, like any failed call: the
  extension cannot tell it apart from a failure the provider billed, and the outcome lets a
  billed-only figure exclude it. A full queue adds characters only when its request was a cache miss:
  synthesis runs before the announcement is queued, so those characters were sent and billed. A
  full queue after a cache hit adds none.
- **FR-023**: `docs/metrics.md` MUST define billable usage in the terms above and give a query for
  characters sent (successful and failed calls alike) per provider and billing tier in the
  current calendar month. It MUST say how far to trust it: Prometheus compensates for the counter
  resetting on a host restart or redeploy, so a restart loses only what was counted since the last
  scrape; the total is only as long as Prometheus's retention; and the calendar month comes from
  the dashboard's time range, since a query on its own cannot express one.
- **FR-024**: `docs/future/google-cloud-free-tier-tracking.md` (a local working note, excluded
  from git, so this update is not part of the pull request) MUST be updated to say what this
  feature provides (the definition, the measurement point, the tier breakdown, the visibility) and
  what the budget feature still needs (persisted monthly totals, a limit per entry, and the
  choice between rejecting and falling back).

### Key Entities

- **Announcement count**: how many announcement requests arrived, by outcome, error code,
  rejection reason and provider.
- **Synthesis timing**: the duration and number of provider syntheses, with a bucketed latency
  distribution, by provider name, provider type, outcome and error code.
- **Synthesis work**: the characters sent to each provider (by outcome) and the seconds of audio
  it returned (successes only), as totals and counts, the denominators for normalised latency.
- **Cache request count**: how many cache lookups hit or missed.
- **Cache occupancy**: the cache's current bytes and entries, and its configured byte limit.
- **Queue depth**: the number of announcements waiting, per target.
- **Playback count**: how many dequeued announcements started playing or failed to.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: After the upgrade, an operator can answer "how many announcements failed in the
  last hour, and why" from the metrics endpoint alone, in one query, without reading logs.
- **SC-002**: For every configured provider that synthesized at least once, the operator can read
  its average, maximum, p95 and p99 synthesis time, its failure count, its time per 1,000
  characters and its real-time factor, each in one query.
- **SC-002a**: Sending the same provider twice as many characters in a window leaves its time per
  1,000 characters roughly unchanged, while its raw average time grows — the normalised figure
  reflects the provider, not the message mix.
- **SC-003**: The cache hit ratio can be computed from the published counts, and the published
  cache size matches `GET /api/tts/cache/stats` exactly when both are read with no announcement
  in between.
- **SC-004**: Serving an announcement from the cache takes no measurably longer than in 0.1.3
  (within 5% on the median, measured over 1,000 cache hits).
- **SC-005**: After 10,000 announcements with random text, random voices and unknown provider
  names, the number of TTS series in the endpoint is the same as after 10 announcements to the
  same targets.
- **SC-006**: A host without metrics support, and a host with `multiroom.tts.enabled=false`, both
  start with the new JAR, the first serving announcements and the second contributing nothing.
- **SC-007**: Using only `docs/metrics.md`, an operator who has not read the code can write the
  query for "what share of announcements to provider X timed out in the last day", and every
  query in that guide returns data against a running host once the events it measures have
  happened at least once (a timeout, a full queue).

## Assumptions

- The host's metrics collection is available to extensions on the shared classpath (confirmed in
  `multiroom-core`: it ships the metrics library and the Prometheus registry and exposes the
  `prometheus` endpoint). The extension uses it at `provided` scope, bundles nothing, and needs
  no change to `multiroom-api`.
- The starter's dependency ban covers only `multiroom-core`, so depending on the host's metrics
  library passes the build gates. Documenting it upstream as part of the extension contract is
  worthwhile but out of scope here.
- Target names come from the host's configured outputs and groups, and a series is created only
  after a target passes validation, so the number of queue-depth series is bounded by the host's
  device count.
- The playback count (User Story 4) is added to the request because a failure to start playback
  happens after the caller is told `accepted` and is otherwise visible only in logs.
- Work is measured in characters, not words: characters are what OpenAI and Google bill by,
  need no language-aware splitting, and are exact. Gemini is the exception: it bills by input
  tokens plus audio output tokens, so for it characters are only a rough proxy, and seconds of
  audio produced are closer to the bill. Both are recorded; choosing the budget's unit for
  Gemini is left to that feature (see Finding 5 in
  `docs/future/google-cloud-free-tier-tracking.md`). The style prompt is counted separately from
  the message because it is sent as input and may be billed (not yet confirmed), but it does not
  make synthesis take longer in proportion to its length. Synthesis time has a fixed part
  (connection, authentication, model start) that does not scale with length, so time per
  character is a ratio of totals over a window, not a per-request constant; short messages will
  look proportionally slower.
- Playback that starts and later ends normally is not timed: its length is the length of the
  audio, which says nothing about health.
- Cache evictions, provider token refreshes and voice-catalogue fetches are not measured in this
  feature; they can follow if the first metrics show a need.
- The version becomes 0.1.4.
- Dashboards and alert rules are out of scope; the documentation gives example queries only.
