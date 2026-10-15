# Data Model: Announcements Over What Is Playing (Ducking)

**Feature**: 006-announcement-ducking | **Date**: 2026-10-02

Nothing is persisted. The on-disk cache and its key are unchanged (the playback mode is not part of
`CacheKey`, so every existing hash and file stays valid).

## PlaybackMode (new enum, `multiroom.tts.config`)

| Value | `configName()` | Host `RouteJoinMode` | Effect on other routes on the target's outputs |
|---|---|---|---|
| `DUCK_OTHERS` | `duck-others` | `DUCK_OTHERS` | Lowered to the host's ducking level while this is the newest unpaused ducking route there; restored by the host |
| `MIX` | `mix` | `MIX` | Unchanged level |

- `fromName(String)`: case-insensitive match on `configName()`; anything else (including `replace`,
  `duck_others`, blank) → empty.
- `joinMode()`: exhaustive switch to `RouteJoinMode`.
- `REPLACE` is deliberately absent.

**Effective mode** for one announcement: the request's `playbackMode` if given, else
`multiroom.tts.playback.default-mode`, else `DUCK_OTHERS`.

## PlaybackProperties (new, `multiroom.tts.playback`)

| Field | Type | Default | Validation (`TtsProperties.validate()`, start-up) |
|---|---|---|---|
| `defaultMode` | `String` | `"duck-others"` | Must parse with `PlaybackMode.fromName`; otherwise start-up aborts: `multiroom-tts: playback.default-mode '<v>' is not one of duck-others, mix` |

Skipped when `multiroom.tts.enabled=false`, like every other check.

## SpeakRequest / AnnounceCommand (changed)

| Field | Type | Change |
|---|---|---|
| `playbackMode` | `String`, optional | New, last component. `null` = not given. Unknown value → `INVALID_REQUEST` before target lookup, cache lookup or synthesis |

`AnnounceCommand` keeps its existing shorter constructors (pre-004 and the current ten-argument
form), each delegating with `playbackMode = null`, so existing callers and tests compile unchanged.

## AnnouncementTask (changed)

```
AnnouncementTask(UUID announcementId, InputId inputId, TargetType targetType, String targetName,
                 Path audioFile, boolean temporaryFile, PlaybackMode playbackMode, CacheKey cacheKey)
```

- **Removed**: `List<Route> routeSnapshot`.
- **Added**: `PlaybackMode playbackMode` — the effective mode, resolved when the request is
  accepted, used when the route is created.

## Announcement lifecycle

```text
request ──validate (text, playbackMode, target, provider)──► rejected (4xx, counted)
   │
   ▼
cache hit/miss → pin or spill → enqueue on targetType:targetName      (nothing audible changes)
   │                    └─ enqueue fails → unpin / delete spill → 503 (no routes to restore)
   ▼
front of queue → register input (autoRemove) → track → createRoute(input, target, mode)
   │                                                         │
   │                                                         ├─ RouteAdmissionException
   │                                                         │    → WARN TTS_PLAYBACK_REFUSED, playbacks{failed}
   │                                                         │    → cancel (unpin, release, delete) → unregisterInput → next
   │                                                         └─ other RuntimeException → ERROR, same cleanup → next
   ▼
playing (others lowered by host if duck-others)
   │
   ▼
RouteDestroyedEvent(inputId) → release (unpin, resolver, temp file) → next in queue
   (input removed by core's AutoRemoveInputListener; other routes restored by the host)
```

No state of another route is held at any point.
