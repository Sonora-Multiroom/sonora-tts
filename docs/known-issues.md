# Known issues

Behaviour that is accepted for now, with what to look for when it happens and how it could be fixed
later.

## A refused announcement still answers `202`

**Since**: 0.1.5 (`006-announcement-ducking`)

### Symptom

`POST /api/tts/speak` returns `202 Accepted`, but the announcement never plays. A few seconds later
core logs, at ERROR with a stack trace:

```text
RouteManager operation failed: method=createOrGetRoute, context={inputId=tts-…, outputId=office},
error=Output 'office' already has 4 routes (limit 4)
multiroom.api.exceptions.RouteAdmissionException: …
```

### Why

- The route is created when the announcement reaches the front of its target's queue, after the
  caller already has its response. A refusal at that point cannot reach the caller.
- The host allows at most `audio.mixing.max-routes-per-output` routes on one output (4 by default).
  That limit is core configuration and is not exposed through `multiroom-api`, so the extension
  cannot check it at request time without depending on a core configuration key.
- Even with the limit known, a check at request time is exact only when nothing is queued ahead for
  that target. Otherwise the room may look different when the announcement actually plays.
- The ERROR comes from core's `RouteManagerLoggingAspect`, which logs every `RouteManager`
  exception at ERROR, an expected refusal included. This extension cannot quieten it.

### What the extension does

The refusal is handled and nothing is left behind: the input is unregistered, the cache entry is
unpinned, the target's queue moves on, and the announcement is not retried. Look for:

- the log line `TTS_PLAYBACK_REFUSED announcementId=… target=… output=… reason=ROUTE_LIMIT_REACHED`
  (WARN, from this extension)
- `tts_playbacks_total{outcome="failed"}` going up (see [metrics.md](metrics.md))

Treat the core ERROR alongside these as expected.

### Possible fix

1. **multiroom-ai**: expose the route limit, or a dry-run admission check, through `multiroom-api`.
   Have `RouteManagerLoggingAspect` log `RouteAdmissionException` at WARN without a stack trace.
2. **This extension**: when the target's queue is empty and an output (any member, for a group) is
   already at the limit, refuse `/speak` straight away (e.g. `409`) instead of accepting it. Keep
   handling refusals when the route is created, for whatever still gets through.
