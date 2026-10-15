# Quickstart: Validating That A Failed Start Ends Once

**Feature**: 007-failed-start-cleanup

A failed start after admission cannot be provoked safely on the production host, so the failure
outcomes are proven by unit tests ([research.md](research.md) R6); the smoke and production checks
prove the JAR loads and a normal announcement is unchanged. Expected log lines and counts per
outcome: [contracts/observability.md](contracts/observability.md).

## Prerequisites

- `unzip -l ~/.m2/repository/ai/multiroom/multiroom-api/0.1.21/multiroom-api-0.1.21.jar` lists ~70
  entries (not 7).
- For the production check: the user's permission to deploy, and to play one announcement aloud.

## 1. Build and tests

```powershell
mvn verify
mvn test -Dtest=PlaybackCompletionListenerTest,TtsServiceTest
```

Expected: green. The tests cover, at least:

- a destroyed event for a never-started route (no `startedAt`) leaves the announcement tracked, logs
  no `TTS_PLAYBACK_COMPLETED` and runs no callback;
- the 0.1.22 ordering (event, then `createRoute` throws): callback once, one failed count, no
  started count, input unregistered once, `TTS_PLAYBACK_FAILED` logged;
- the 0.1.21 ordering (throw, no event): same outcome;
- a refusal: unchanged;
- a started route whose event arrives before `createRoute` returns: completed, callback once;
- `unregisterInput` throwing `IllegalArgumentException`: no WARN; any other exception: WARN.

## 2. Local smoke (merge gate, part two)

```powershell
mvn deploy -Plocal
```

Start the local host; `GET /actuator/extensions` lists `tts` at 0.1.6, not `REJECTED`.

## 3. Production check (with permission)

After `mvn deploy` (production) and the restart the user approves, send one free announcement
(`providerName: piper-local`) at low volume, following the shared rules for audible tests
(record and restore volumes, check no ALSA device is left open). Then in Loki
(`app=multiroom-core`): one `TTS_PLAYBACK_STARTED` and one `TTS_PLAYBACK_COMPLETED` for its
`announcementId`, and `tts_playbacks_total{outcome="started"}` up by one.

Record results, with commands, dates and counts, in `verification.md`.
