# Quickstart: validating TTS Metrics

**Feature**: `005-tts-metrics` | **Date**: 2026-09-26

How to prove the feature works end to end against a real host. The metric names, tags and example
queries are in [contracts/metrics.md](contracts/metrics.md); they are not repeated here.

## Prerequisites

- A local `multiroom-ai` checkout with `multiroom-core` 0.1.18 built, and its `extensions/`
  directory (the `local` deploy profile's target).
- A host configuration with at least:
  - two provider entries, one of which can be made to fail (for example a `local-http` entry
    pointing at a closed port)
  - one output and one output group
- The `prometheus` endpoint exposed by the host (it is in core's default
  `management.endpoints.web.exposure.include`).

## 1. Build, deploy, start

```powershell
mvn verify
mvn deploy -Plocal
# start multiroom-core from the multiroom-ai checkout
```

Check that `GET http://localhost:8080/actuator/extensions` lists `tts` at `0.1.4` and not
`REJECTED` (the second half of the merge gate).

## 2. The metrics appear, with nothing sent yet

```powershell
curl -s http://localhost:8080/actuator/prometheus | Select-String '^tts_'
```

Expected: the cache gauges `tts_cache_size_bytes`, `tts_cache_max_bytes` and `tts_cache_entries`,
matching `GET /api/tts/cache/stats`, and one
`tts_announcements_total{outcome="accepted",error="none",reason="none",provider="…"} 0` series for
each configured provider. No synthesis series appear before the first synthesis.

## 3. The stories

| Step | Action | Expected in `/actuator/prometheus` |
|---|---|---|
| Story 1 | One valid speak; one to a target that doesn't exist; one with a body of `{` | `tts_announcements_total` accepted = 1; rejected `TARGET_NOT_FOUND` = 1; rejected `INVALID_REQUEST`, `provider="unknown"` = 1. The `{` request answers 400 with an `ErrorResponse` body |
| Story 1 | A speak naming provider `nope` | rejected `PROVIDER_NOT_FOUND`, `provider="unknown"`. The string `nope` appears nowhere in the scrape |
| Story 2 | A new text on each provider; then one on the failing provider | `tts_synthesis_seconds_count` per provider with `outcome="success"`; one `outcome="failure"` with its code. `tts_synthesis_characters_sum{part="text"}` equals the text lengths. `tts_synthesis_audio_seconds_sum` is > 0 for successes only |
| Story 2 | A `google-gemini` speak with a style prompt (if configured) | a `part="style_prompt"` sample, and `tier` = the entry's model |
| Story 3 | The same text twice | `tts_cache_requests_total` hit = 1, miss = 1; the size gauges equal `GET /api/tts/cache/stats` |
| Story 3 | `DELETE /api/tts/cache`, then scrape | size and entry gauges at 0 |
| Story 4 | Three quick speaks to one target | `tts_queue_depth{target=…}` is 2 while the first plays, then 0. `tts_playbacks_total{outcome="started"}` = 3 |
| Story 4 | More speaks than `queue.max-depth-per-target` | rejected with `error="PROVIDER_ERROR", reason="queue_full"` |
| Story 2 | A speak on a `local-http` entry with `timeout-seconds: 1` pointing at a listener that accepts and never answers (for example `python -c "import socket,time;s=socket.socket();s.bind(('127.0.0.1',5999));s.listen();c=s.accept();time.sleep(60)"`) | `tts_synthesis_seconds_count{outcome="failure", error="PROVIDER_TIMEOUT"}` = 1 for that entry |

Then paste each example query from [contracts/metrics.md](contracts/metrics.md) into Prometheus
or Grafana against this host. Each must return data (SC-007); the rows above include a timeout
and a full queue so the queries that measure them have something to show. The monthly query
needs Grafana's "This month so far" range for `$__range`.

## 4. The switches

| Configuration | Expected |
|---|---|
| `multiroom.tts.enabled: false` | the host starts; no `tts_` series at all |
| `management.metrics.enable.tts: false` | TTS works; no `tts_` series |

## 5. Cache-hit cost (SC-004)

Send the same announcement 1,000 times (a loop over `POST /api/tts/speak` to a target with a large
`max-depth-per-target`, or with playback short enough to drain) on both 0.1.3 and 0.1.4, and
compare the median response time. It must be within 5%.

## 6. Production

After the merge and a deliberate `mvn deploy` to `multiroom.lan`, repeat step 2 against
`http://multiroom.lan:8080/actuator/prometheus`.
