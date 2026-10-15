# Contract: Outcome Of One Announcement Taken Off Its Queue

The externally visible contract this feature changes: log lines (logger names unchanged) and
`tts_playbacks_total`. No HTTP, configuration or metric-name change.

| Outcome | Log lines for the announcement (in order) | `tts_playbacks_total` | Queue told it ended | Input removed by |
|---|---|---|---|---|
| Started and played | INFO `TTS_PLAYBACK_STARTED announcementId=… target=…`, INFO `TTS_PLAYBACK_COMPLETED announcementId=… target=…` | `outcome="started"` +1 | once | hub (auto-remove) |
| Refused by the hub | WARN `TTS_PLAYBACK_REFUSED announcementId=… target=… output=… reason=…` (no stack trace) | `outcome="failed"` +1 | once | extension |
| Admitted, failed to start (hub 0.1.22+) | ERROR `TTS_PLAYBACK_FAILED announcementId=… target=…` with the exception | `outcome="failed"` +1 | once | extension |
| Failed to start (hub 0.1.21, usually no event) | same as above | `outcome="failed"` +1 | once | extension |
| Failed before the route step (input registration) | unchanged (the queue's error log) | `outcome="failed"` +1 | as today | — |

**Changed from 0.1.5**:

- A failed start no longer logs `TTS_PLAYBACK_COMPLETED`, and its queue signal is no longer sent
  twice.
- The ERROR line `Failed to create route for announcement … on target …` becomes
  `TTS_PLAYBACK_FAILED announcementId=… target=…`.
- An "input is not registered" answer when removing the input is DEBUG, not WARN. Any other removal
  failure stays WARN with its stack trace.

**Never**: a `TTS_PLAYBACK_COMPLETED` and a `TTS_PLAYBACK_FAILED` or `TTS_PLAYBACK_REFUSED` line for
the same `announcementId`; two counter increments for one announcement.
