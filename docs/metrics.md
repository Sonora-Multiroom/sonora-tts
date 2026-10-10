# TTS metrics

From 0.1.4 the extension records its own metrics into the host's Micrometer registry. They
appear in the host's existing Prometheus endpoint, beside the host's own metrics:

```text
GET http://<host>:8080/actuator/prometheus
```

Nothing needs configuring in the extension. The host must expose the `prometheus` actuator
endpoint (core's default `management.endpoints.web.exposure.include` does).

Every metric here starts with `tts_`. To see them all:

```bash
curl -s http://multiroom.lan:8080/actuator/prometheus | grep '^tts_'
```

## Switching them off

| Setting | Effect |
|---|---|
| `multiroom.tts.enabled: false` | The extension contributes nothing, so no `tts_` metric exists |
| `management.metrics.enable.tts: false` | A host setting: TTS keeps working, and the host drops every `tts_` metric |

A host without Micrometer, or without a registry, simply gets no TTS metrics; the extension
still starts and works.

## What each metric counts

### `tts_announcements_total` (counter)

One increment per speak request the extension answers (`POST /api/tts/speak`), whatever the
answer.

| Tag | Values |
|---|---|
| `outcome` | `accepted` (answered 202), `rejected` (answered with an error) |
| `error` | `none` when accepted; otherwise the error code the caller received, or `INTERNAL` when the caller got HTTP 500 with no code |
| `reason` | `queue_full` or `shutting_down` when a target's queue refused the announcement (both have `error="PROVIDER_ERROR"`); otherwise `none` |
| `provider` | the configured provider entry that handled it; `unknown` for a name that is not configured, and for a request whose body could not be read |

The `accepted` series of every configured provider exists at 0 from start-up, so a dashboard
shows "nothing yet" rather than "no data".

A queue rejection has the caller-visible code `PROVIDER_ERROR`, like a genuine provider failure.
Use `reason` to tell them apart: `reason="none"` is the provider, anything else is the queue.

**Not counted**: requests the host's web layer refuses before this extension sees them, such as a
wrong HTTP method (405) or a wrong content type (415). They appear only in the host's
`http_server_requests_seconds`.

### `tts_synthesis_seconds` (timer, with histogram buckets)

One sample per call to a provider, i.e. per cache miss that reached synthesis. It measures from
the call to the provider until it returns audio or fails. Converting the audio to the output
format afterwards is not included.

Series: `_count`, `_sum`, `_max`, `_bucket{le=…}`.

| Tag | Values |
|---|---|
| `provider` | the provider entry's name |
| `type` | `openai`, `google-cloud`, `google-gemini`, `piper`, `local-http`: the entry's `type`, spelled as in configuration |
| `outcome` | `success`, `failure` |
| `error` | `none` on success; otherwise the code the announcement is rejected with (`PROVIDER_TIMEOUT`, `PROVIDER_RATE_LIMITED`, `PROVIDER_ERROR`, `INVALID_VOICE`, …) |

Bucket bounds (seconds): 0.1, 0.25, 0.5, 1, 2, 3, 5, 7.5, 10, 15, 20, 30, 45, 60, 90, 120. The list
stops at the first bound at or above the longest `timeout-seconds` among the enabled provider
entries, and that timeout is added as a bound if it isn't one already. With the default 10 s, the
buckets end at 10.

### `tts_synthesis_characters` (distribution summary)

The characters sent to a provider, recorded per part, once per synthesis, with the same outcome as
its timer sample. Characters are Unicode code points, so an emoji counts as one. A part with no
characters is not recorded.

Series: `_count`, `_sum`, `_max`.

| Tag | Values |
|---|---|
| `provider`, `type`, `outcome`, `error` | as for `tts_synthesis_seconds` |
| `part` | `text` (the message), `style_prompt` (a `google-gemini` style prompt) |
| `tier` | the price tier: for `google-cloud`, the voice's engine family (`Standard`, `Wavenet`, `Neural2`, `Studio`, `Chirp-HD`, `Chirp3-HD`, or `other` for an engine the extension doesn't recognise); for `google-gemini`, the entry's `model` (e.g. `gemini-2.5-flash-tts`); `none` for every other type |

### `tts_synthesis_audio_seconds` (distribution summary)

The length of the audio a successful synthesis produced, measured after conversion. Tags:
`provider`, `type`, `tier`.

### `tts_cache_requests_total` (counter)

One increment per cache lookup for an announcement. Tag `outcome`: `hit` or `miss`. A request
rejected before the lookup (an unknown target, say) is not counted.

### `tts_cache_size_bytes`, `tts_cache_max_bytes`, `tts_cache_entries` (gauges)

The cache's current size, its configured maximum (`cache.max-size-mb`) and its entry count, read
at scrape time from the same place as `GET /api/tts/cache/stats`. No tags. They drop to 0 after
`DELETE /api/tts/cache`.

### `tts_queue_depth` (gauge)

The announcements waiting for a target, not counting the one playing. A series appears on a
target's first accepted announcement and stays, reading 0 once its queue drains.

| Tag | Values |
|---|---|
| `target` | the output or output group name (only names that exist) |
| `target_type` | `output`, `group` |

### `tts_playbacks_total` (counter)

One increment per announcement taken off its queue to play. Tag `outcome`: `started` (its route
was created) or `failed` (it could not start). `failed` includes an announcement the host
refused, for instance at its route limit: it is dropped, not retried, and logged as
`TTS_PLAYBACK_REFUSED` with the host's reason and the output. An announcement whose route was
admitted but failed to start is `failed` too, never `started`, and is logged as
`TTS_PLAYBACK_FAILED` with the exception. Each announcement taken off a queue is counted exactly
once, so `started + failed` equals the announcements taken off queues.

## Billable usage

**Billable usage is the characters sent to a provider: the message plus the style prompt**
(`part="text"` plus `part="style_prompt"`). They are counted once, immediately before the provider
is called, so:

- a cache hit sends nothing and costs nothing;
- a failed call is counted too, carrying `outcome="failure"` so it can be left out. That includes
  a call that fails inside the provider before anything is sent: a voice the Google voice
  catalogue does not list (`error="INVALID_VOICE"`) or an access token that cannot be obtained.
  Those characters were never billed, so "all outcomes" slightly overstates usage and
  `outcome="success"` slightly understates it (Google may bill a call that then fails);
- a call whose audio is then refused (a full queue, audio that cannot be converted) is still a
  successful synthesis and still counted.

For `google-cloud`, sum by `tier` to compare against Google's free tier, which is set per voice
family. For `google-gemini` characters are only a proxy: Gemini bills by tokens, including audio
output tokens, so compare `tts_synthesis_characters_sum` and `tts_synthesis_audio_seconds_sum`
against a real invoice before trusting either as a cost estimate.

## Example queries

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

# p99 as a share of the provider's timeout-seconds. The timeout is configuration, not a metric:
# type it in per provider (10 is the default). Above about 0.8, the slow tail is about to become
# timeouts
histogram_quantile(0.99, sum by (le) (rate(tts_synthesis_seconds_bucket{provider="google-cloud", outcome="success"}[1h])))
  / 10

# Seconds per 1,000 characters, per provider
1000 * sum by (provider) (rate(tts_synthesis_seconds_sum{outcome="success"}[1h]))
  / sum by (provider) (rate(tts_synthesis_characters_sum{outcome="success", part="text"}[1h]))

# Real-time factor: seconds spent per second of speech, per provider
sum by (provider) (rate(tts_synthesis_seconds_sum{outcome="success"}[1h]))
  / sum by (provider) (rate(tts_synthesis_audio_seconds_sum[1h]))

# Characters sent per provider per day (successful and failed calls alike)
sum by (provider) (increase(tts_synthesis_characters_sum[1d]))

# Characters sent this calendar month, per provider and tier: billable usage, both parts, all
# outcomes. In Grafana, set the time range to "This month so far"
sum by (provider, tier) (increase(tts_synthesis_characters_sum[$__range]))

# The same, successful calls only (excludes failures that never reached the provider)
sum by (provider, tier) (increase(tts_synthesis_characters_sum{outcome="success"}[$__range]))

# Cache hit ratio
sum(rate(tts_cache_requests_total{outcome="hit"}[1h])) / sum(rate(tts_cache_requests_total[1h]))

# Cache fill
tts_cache_size_bytes / tts_cache_max_bytes

# Queue depth per target
max by (target) (tts_queue_depth)
```

## Reading them correctly

- **Restarts reset the counters.** Counters and distributions start from zero whenever the host
  restarts or the JAR is redeployed. `rate()` and `increase()` compensate for the reset, so a
  range query loses only what was counted between the last scrape and the restart. Always query
  counters through them, never raw.
- **The monthly total is only as long as Prometheus keeps data.** The extension persists nothing;
  the month's total is Prometheus's `increase()` over its own stored samples, so it is bounded by
  Prometheus's retention.
- **Time per character favours long messages.** It is a ratio over a window, so a few long
  messages outweigh many short ones. Read it per provider and compare it over time, not across
  wildly different message mixes.
- **`provider="unknown"`** is a request that named a provider that isn't configured, or whose
  body could not be read. If you actually name a provider entry `unknown`, its series merge with
  these; that is harmless, but a different name avoids it.
- **No tag carries what a caller sent.** Message text, voices, languages, style prompts and
  announcement ids never become tag values, so the number of series stays bounded by your
  configuration and your outputs, however many announcements you make.
- **Deliberately left out**: percentiles computed inside the extension (use `histogram_quantile`
  on the buckets), the voice as a tag (unbounded), and Gemini token counts (Google's response
  doesn't report them).
