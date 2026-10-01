# Quickstart: Validating Announcements Over Music

**Feature**: 006-announcement-ducking

Routing calls and cleanup are proven by unit tests; what the room *sounds like* can only be proven
on a host running multiroom-ai 023 (Principle VII). Request shapes:
[contracts/tts-rest-api.yaml](contracts/tts-rest-api.yaml). Settings:
[contracts/configuration.md](contracts/configuration.md).

## Prerequisites

- multiroom-ai with 023 built and its API installed:
  `unzip -l ~/.m2/repository/ai/multiroom/multiroom-api/0.1.21/multiroom-api-0.1.21.jar` lists ~70
  entries, including `RouteJoinMode.class` and `RouteAdmissionException.class`.
- For the production checks: the 023 host is already deployed on `multiroom.lan` (host first,
  then this JAR). Deploying, restarting and editing `multiroom.yml` there need the user's
  permission each time.

## 1. Build and gate

```powershell
mvn verify
unzip -p target/multiroom-tts-0.1.5.jar META-INF/MANIFEST.MF   # Require-API-Version: [0.1.21,0.2.0)
```

Expected: green build; the manifest carries the range above.

## 2. Local smoke (merge gate, part two)

```powershell
mvn deploy -Plocal
# start multiroom-core in the multiroom-ai checkout, then:
curl -s http://localhost:8080/actuator/extensions
```

Expected: `tts` at version `0.1.5`, not `REJECTED`. Against a pre-023 host the same JAR is
`REJECTED` for its API requirement — that is the intended failure (spec edge case).

## 3. Request validation (local)

```powershell
curl -s -XPOST localhost:8080/api/tts/speak -H 'Content-Type: application/json' `
  -d '{"text":"hi","targetName":"<output>","targetType":"SINGLE_OUTPUT","playbackMode":"replace"}'
```

Expected: `400 {"error":"INVALID_REQUEST","message":"Unknown playbackMode 'replace'. Supported: duck-others, mix"}`,
no synthesis (no `TTS_SYNTHESIS_STARTED` in the log). `"DUCK-OTHERS"` and `"Mix"` are accepted.
A body without `playbackMode` behaves as before and gets the same `202` shape.

Start-up check: set `multiroom.tts.playback.default-mode: replace` and start core. Expected: start-up
aborts with `multiroom-tts: playback.default-mode 'replace' is not one of duck-others, mix`.

## 4. Music keeps playing (production, story 1)

1. Start music on one output (Home Assistant, or `POST /api/v2/play`).
2. `POST /api/tts/speak` to that output with no `playbackMode`.
3. Listen, and read `GET /api/v2/outputs/<output>/routes` while it plays.

Expected:
- a repeated announcement (`"cacheHit":true`) reaches `TTS_PLAYBACK_STARTED` in the extension's
  log less than 1 s after the request's `TTS_REQUEST_RECEIVED`, the same as on an idle output
  (SC-003) — compare the two log timestamps;
- the music never goes silent, drops under the announcement and is back within ~1 s of its end,
  continuing from where it is (SC-001, SC-002);
- during playback the routes list shows the music `LOWERED` and the `tts-…` route `FULL`;
- Home Assistant keeps showing the music as the source (SC-007);
- the host log shows one route created and destroyed for the announcement and no other route
  events (SC-004 — repeat 50 times and count).

## 5. Groups (story 2)

Music on a group; announce to the group, then to one member. Expected: group announcement in sync
and music lowered on every member; member announcement lowers that member only.

## 6. Mix (story 4)

Repeat step 4 with `"playbackMode":"mix"`. Expected: the music level does not change. The second
request is a cache hit (`"cacheHit":true`) — the mode is not part of the cache key.

## 7. Refusal (story 3)

On a host with `audio.mixing.max-routes-per-output: 1` (a test host, or production with the user's
permission), play music on an output and announce to it.

Expected:
- `202` from the request; then `WARN TTS_PLAYBACK_REFUSED announcementId=… target=… output=… reason=ROUTE_LIMIT_REACHED`;
- the music continues untouched;
- `tts_playbacks_total{outcome="failed"}` increments by 1;
- no `tts-…` input remains (`GET /api/v2/inputs`), and the next queued announcement to that
  target starts. (Pin, resolver and temp-file release are not observable over HTTP; the unit tests
  cover them.)

## 8. Shutdown and stop-all

During an announcement, stop all routes on the output (MQTT STOP or
`DELETE /api/v2/outputs/<output>/routes`). Expected: the announcement ends with the music, the log
shows `TTS_PLAYBACK_COMPLETED`, and nothing is recreated afterwards.
