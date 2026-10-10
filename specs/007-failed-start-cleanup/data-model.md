# Data Model: An Announcement That Fails To Start Ends Once, As A Failure

No stored data changes. The model is the lifecycle of one **announcement in flight**: an
`AnnouncementTask` from the moment the queue worker takes it until its end is handled.

## Announcement in flight

| Field (existing) | Meaning here |
|---|---|
| `inputId` | The announcement's own ephemeral input (`tts-<uuid>`), unique per announcement. The key of the listener's in-flight map and of the hub's `RouteDestroyedEvent.route().inputId` |
| `announcementId`, `targetName` | Logged on every outcome line |
| `cacheKey`, `audioFile`, `temporaryFile` | What `release` gives back: cache pin, resolver entry, temp file |

Held in `PlaybackCompletionListener.inFlight` (a `ConcurrentHashMap`), together with the queue's
completion callback. Removing the entry is the single point that decides who ends the announcement.

## States and transitions

```text
                 activate: register input, track
   (queued) ───────────────────────────────────────▶ TRACKED
                                                        │
          ┌─────────────────────────────┬───────────────┼───────────────────────────────┐
          │ createRoute returns         │ refused       │ admitted, failed to start     │
          ▼                             ▼               ▼                               │
       PLAYING                       FAILED          (0.1.22) destroyed event,          │
   log STARTED, count started   log REFUSED          startedAt == null:                 │
          │                                          listener leaves it TRACKED ────────┤
          │ destroyed event,                                  then createRoute throws   │
          │ startedAt set                                     (0.1.21: usually no event)│
          ▼                                                                             ▼
      COMPLETED                                                                      FAILED
  listener removes entry:                                                    log TTS_PLAYBACK_FAILED
  release, log COMPLETED,                         abandon: count failed, cancel removes entry (release),
  callback once                                   unregister input, callback once
```

## Rules

- **One remover.** Exactly one of `onRouteDestroyed` (route went live) and `cancel` (refused or
  failed start) removes the entry, and only the remover releases and runs the callback.
- **The listener never handles a route that never went live.** `startedAt == null` on the event
  means the hub never published `RouteCreatedEvent` for it, so `createRoute` throws and `abandon`
  runs.
- **`abandon` counts the failure unconditionally** and runs the callback only if `cancel` removed
  the entry. The counter balance `started + failed = taken off queues` holds even if the listener
  ever completed a failed start (research R3 risk).
- **The input after a failure is the extension's to remove**: the hub auto-removes an input only
  after a route on it went live. An "is not registered" answer is tolerated at DEBUG.
