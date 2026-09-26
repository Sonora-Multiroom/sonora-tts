# Contract: TTS Metrics (v0.1.4)

The metrics this extension publishes through the host's Micrometer registry, as they appear in
`GET /actuator/prometheus`. This file is the source for `docs/metrics.md`; a name, a tag key or a
tag value listed here is part of the operator-facing contract, and changing one breaks dashboards.

Rules that hold for every metric:

- Every metric's name starts with `tts` (Micrometer `tts.…`, Prometheus `tts_…`).
- Every series of one metric carries **the same tag keys**. A value that does not apply is a
  fixed word (`none`), never an absent tag (see research R4).
- No tag value comes from free text a caller sends. Message text, voices, languages, style
  prompts, announcement ids and exception messages never appear.
- With `multiroom.tts.enabled=false`, none of these exists. With
  `management.metrics.enable.tts=false` (a host setting), the host drops them all.
- Counters and distributions reset when the host restarts or the JAR is redeployed. `rate()` and
  `increase()` compensate for the reset, so a range query loses only what was counted between the
  last scrape and the restart.

## Tag values

| Tag | Values |
|---|---|
| `provider` | A configured provider entry's name; `unknown` for a name that is not configured, and for a request whose body could not be read |
| `type` | `openai`, `google-cloud`, `google-gemini`, `piper`, `local-http` |
| `error` | A published error code (`INVALID_REQUEST`, `TARGET_NOT_FOUND`, `PROVIDER_NOT_FOUND`, `PROVIDER_TIMEOUT`, `PROVIDER_RATE_LIMITED`, `PROVIDER_ERROR`, `FORMAT_NORMALIZATION_FAILED`, `INVALID_VOICE`, `VOICE_CATALOGUE_UNAVAILABLE`); `INTERNAL` when the caller got HTTP 500 with no code; `none` on success |
| `reason` | `queue_full`, `shutting_down`, `none` |
| `tier` | `google-cloud`: `Standard`, `Wavenet`, `Neural2`, `Studio`, `Chirp-HD`, `Chirp3-HD`, or `other` for an unrecognized engine; `google-gemini`: the entry's configured model (e.g. `gemini-2.5-flash-tts`); every other type: `none` |
| `part` | `text` (the message), `style_prompt` (a `google-gemini` style prompt) |
| `target` | The name of an output or output group that passed validation |
| `target_type` | `output`, `group` |

## Metrics

### `tts_announcements_total` — counter

One increment per speak request the extension answers (`POST /api/tts/speak`).

| Tag | Values |
|---|---|
| `outcome` | `accepted` (answered 202), `rejected` (answered 4xx/5xx) |
| `error` | `none` when accepted; otherwise the code the caller received, or `INTERNAL` |
| `reason` | `queue_full`, `shutting_down` for those two rejections (both `error="PROVIDER_ERROR"`); otherwise `none` |
| `provider` | as above |

Not counted: requests Spring refuses before this extension handles them (wrong method, wrong
content type); they appear in the host's `http_server_requests_seconds`.

### `tts_synthesis_seconds` — timer with histogram

One sample per call to a provider, i.e. per cache miss that reached synthesis. It measures from
the call to the provider until it returns audio or fails; format conversion is not included.

Series: `_count`, `_sum`, `_max`, `_bucket{le=…}`.

| Tag | Values |
|---|---|
| `provider`, `type` | as above (never `unknown`: synthesis only follows a resolved provider) |
| `outcome` | `success`, `failure` |
| `error` | `none` on success; otherwise the code the announcement is rejected with |

Buckets (seconds): 0.1, 0.25, 0.5, 1, 2, 3, 5, 7.5, 10, 15, 20, 30, 45, 60, 90, 120. The list is
cut after the first bound at or above the longest configured `timeout-seconds`, and that timeout
is added as a bound if it is not already one. `+Inf` is always present.

### `tts_synthesis_characters` — distribution summary

The characters sent to a provider, recorded once per part per synthesis, with the same outcome
as the timer sample. Characters are Unicode code points. An empty part is not recorded.

Series: `_count`, `_sum`, `_max`.

| Tag | Values |
|---|---|
| `provider`, `type`, `tier` | as above |
| `outcome`, `error` | as for `tts_synthesis_seconds` |
| `part` | `text`, `style_prompt` |

**Billable usage** is `sum` over both parts. Time per character uses `part="text"` only.

A call that fails inside the provider before anything is sent (a voice missing from the Google
voice catalogue, an access token that cannot be obtained) is still recorded, with
`outcome="failure"` and its error code; it was not billed, so a billed-only figure filters on
`outcome="success"`.

### `tts_synthesis_audio_seconds` — distribution summary

The duration of the audio a successful synthesis produced, after conversion to the native format.

Series: `_count`, `_sum`, `_max`.

| Tag | Values |
|---|---|
| `provider`, `type`, `tier` | as above |

### `tts_cache_requests_total` — counter

One increment per cache lookup made for an announcement.

| Tag | Values |
|---|---|
| `outcome` | `hit`, `miss` |

### `tts_cache_size_bytes`, `tts_cache_max_bytes`, `tts_cache_entries` — gauges

The cache's current size, its configured maximum and its entry count, read at scrape time from
the same source as `GET /api/tts/cache/stats`. No tags.

### `tts_queue_depth` — gauge

Announcements waiting for a target, excluding the one playing. A series appears on a target's
first announcement and stays, reading 0 once the queue drains.

| Tag | Values |
|---|---|
| `target`, `target_type` | as above |

### `tts_playbacks_total` — counter

One increment per queued announcement taken off its queue.

| Tag | Values |
|---|---|
| `outcome` | `started` (its route was created), `failed` (it could not start) |

## Example queries

These are required in `docs/metrics.md` (FR-019, FR-023), and must return data against a
running host (SC-007).

```promql
# Share of announcements rejected in the last hour, by error code and reason
sum by (error, reason) (increase(tts_announcements_total{outcome="rejected"}[1h]))
  / scalar(sum(increase(tts_announcements_total[1h])))

# Timeout rate per provider over a day
sum by (provider) (increase(tts_synthesis_seconds_count{error="PROVIDER_TIMEOUT"}[1d]))
  / sum by (provider) (increase(tts_synthesis_seconds_count[1d]))

# Average, maximum, p95 and p99 successful synthesis time per provider
sum by (provider) (rate(tts_synthesis_seconds_sum{outcome="success"}[1h]))
  / sum by (provider) (rate(tts_synthesis_seconds_count{outcome="success"}[1h]))
max by (provider) (max_over_time(tts_synthesis_seconds_max{outcome="success"}[1h]))
histogram_quantile(0.95, sum by (provider, le) (rate(tts_synthesis_seconds_bucket{outcome="success"}[1h])))
histogram_quantile(0.99, sum by (provider, le) (rate(tts_synthesis_seconds_bucket{outcome="success"}[1h])))

# p99 as a share of the provider's timeout-seconds (the timeout is configuration, not a metric:
# enter it per provider; 10 is the default). Above ~0.8, the slow tail is about to become timeouts
histogram_quantile(0.99, sum by (le) (rate(tts_synthesis_seconds_bucket{provider="google-cloud", outcome="success"}[1h])))
  / 10

# Seconds per 1,000 characters, per provider
1000 * sum by (provider) (rate(tts_synthesis_seconds_sum{outcome="success"}[1h]))
  / sum by (provider) (rate(tts_synthesis_characters_sum{outcome="success", part="text"}[1h]))

# Real-time factor (seconds spent per second of speech), per provider
sum by (provider) (rate(tts_synthesis_seconds_sum{outcome="success"}[1h]))
  / sum by (provider) (rate(tts_synthesis_audio_seconds_sum[1h]))

# Characters sent per provider per day (usage; successful and failed calls alike)
sum by (provider) (increase(tts_synthesis_characters_sum[1d]))

# Characters sent this calendar month, per provider and tier (billable usage: both parts, all
# outcomes). A query cannot express a calendar month: in Grafana set the time range to
# "This month so far" and use $__range. Survives restarts (increase() compensates for resets);
# limited by Prometheus's retention
sum by (provider, tier) (increase(tts_synthesis_characters_sum[$__range]))

# The same, successful calls only (excludes failures that never reached the provider)
sum by (provider, tier) (increase(tts_synthesis_characters_sum{outcome="success"}[$__range]))

# Cache hit ratio, cache fill, queue depth
sum(rate(tts_cache_requests_total{outcome="hit"}[1h])) / sum(rate(tts_cache_requests_total[1h]))
tts_cache_size_bytes / tts_cache_max_bytes
max by (target) (tts_queue_depth)
```
