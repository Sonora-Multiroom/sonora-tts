# Verification: 007-failed-start-cleanup

Date: 2026-10-10. Version 0.1.6, branch `007-failed-start-cleanup`.

## Upstream API jar (T001)

`~/.m2/repository/ai/multiroom/multiroom-api/0.1.21/multiroom-api-0.1.21.jar` lists 81 entries (not 7),
and `javap multiroom.api.model.Route` shows `getStartedAt()`.

## Build and tests (T026)

- `mvn clean verify`: exit 0, 688 tests run across the surefire reports, 0 failures; JaCoCo gate and
  enforcer passed.
- Manifest of `target/multiroom-tts-0.1.6.jar`: `Extension-Id: tts`,
  `Require-API-Version: [0.1.21,0.2.0)`.
- `git ls-files --eol`: no `w/crlf` file.
- The new tests failed before the `TtsService` change (5 failures) and pass after it.

## Local smoke run (T027)

`mvn deploy -Plocal`, then `multiroom-core-0.1.22.jar` from the sonora-multiroom checkout with a dummy
OpenAI provider. `GET /actuator/extensions`: `tts` 0.1.6, `ACTIVE`, `rejectionReason: null`. Without any
provider the host refuses to start, as designed.

## Production check (T028), with the user's permission

- `mvn deploy` copied the JAR to `/home/tiger/extensions/multiroom-tts.jar`; `sudo -n systemctl restart
  multiroom.service`. `GET /actuator/extensions`: `tts` 0.1.6 `ACTIVE`, alongside rest, mqtt, dlna and
  chromecast.
- Office speaker before: volume 30, unmuted, enabled. After the restart its volume read 75; set back to
  30 before the test. Final state: volume 30, unmuted, enabled.
- One announcement: `POST /api/tts/speak`, `providerName: piper-local`, text "Test.", target `office`
  (202). `/var/log/multiroom.log` shows `TTS_REQUEST_RECEIVED`, `TTS_PLAYBACK_STARTED` (16:00:39) and
  `TTS_PLAYBACK_COMPLETED` (16:00:40) once each for the announcement id; no `TTS_PLAYBACK_FAILED`, no
  WARN. The input was auto-removed once.
- `tts_playbacks_total{outcome="started"}` = 1 after the restart; no `failed` series.
- Afterwards: `GET /api/v2/routes` is `[]`, `audio.output.routes` reads 0, and no ALSA playback device is
  open.
- Loki was not queried; the lines above come from the host log over read-only SSH.

## Not verified on production

A failed start cannot be provoked there safely. The counter balance (`started + failed` equals the
announcements taken off queues) and the single `TTS_PLAYBACK_FAILED` line for a failed start are
verified by unit tests only (`TtsServiceTest`: failure after admission, queue moves on once, `abandon`
idempotence, older hub without an event).

## Review (T031)

`/code-review` raised seven findings. Fixed: a check-then-act race in `PlaybackCompletionListener.onRouteDestroyed`
(a single `get` now). Not changed: a route destroyed before going live with no exception does not
occur on the hub, because `RouteManagerImpl.createRoute` throws "Route was stopped while starting"
whenever the route is not live; `playbackFailed` is counted unconditionally by design; any
`IllegalArgumentException` from `unregisterInput` meaning "already removed" is a recorded decision;
the `after(300)` negative check is the usual Mockito idiom; a failure before the route step
(`registerInput`, `track`) predates this feature; the reported garbled character is not in the file.
After the fix `mvn clean verify` is green. SpotBugs was not run.
