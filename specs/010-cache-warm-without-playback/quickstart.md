# Quickstart: Validating Cache Warm-Up

The endpoint is defined in [contracts/tts-cache-warm-api.yaml](contracts/tts-cache-warm-api.yaml).
Record results in `verification.md` with commands, dates and counts.

## 1. Build

```bash
mvn verify
```

Expected: green, including the new `TtsServiceTest`, `TtsCacheControllerTest`,
`TtsExceptionHandlerTest` and metrics cases, and every existing speak test unchanged.

## 2. Load check (merge gate)

Deploy to a live `multiroom-core` and confirm `GET /actuator/extensions` lists `tts` at 0.1.11, not
`REJECTED`. On production this needs the user's permission: a bare `mvn deploy` and a hub restart,
which drops every playing route.

## 3. Silent warm-up on the Pi

Uses `piper-local`, which costs nothing. Warming plays nothing, but it writes to production's cache, so
ask the user first. Ask again before step 3, which is audible, and follow the audible-test rules in the
shared `AGENTS.md`.

Use a text unique to the run, so the first request is a miss. The entry stays in the cache until it is
evicted: the only way to remove it sooner is to clear the whole provider, which is not worth doing for
one short phrase.

1. Warm it twice:

   ```bash
   curl -s -X POST http://multiroom.lan:8080/api/tts/cache/warm \
     -H 'Content-Type: application/json' \
     -d '{"text":"<unique text>","providerName":"piper-local"}'
   ```

   Expected: `{"cacheHit":false}`, then `{"cacheHit":true}`. During both, `grep -h "^state"
   /proc/asound/card*/pcm*p/sub*/status` over SSH prints nothing, and
   `GET /api/tts/cache/stats` shows `piper-local` one entry higher afterwards.

2. Check the metrics: `/actuator/metrics/tts.cache.warm` reads 2, split between `hit` and
   `synthesized`. `tts.announcements` and `tts.cache.requests` do not change.

3. **Audible, with permission.** Record the output's state, keep the volume low, and speak the same
   text with `providerName: piper-local` on the one output agreed with the user.

   Expected: `202` with `cacheHit: true`, and the phrase is heard correctly.

4. Check nothing was left open: the ALSA check prints nothing, and
   `/actuator/metrics/audio.output.routes?tag=output:<id>` reads 0.

## 4. Refusals

No sound and no provider call:

```bash
curl -s -i -X POST http://multiroom.lan:8080/api/tts/cache/warm \
  -H 'Content-Type: application/json' -d '{"text":" "}'
curl -s -i -X POST http://multiroom.lan:8080/api/tts/cache/warm \
  -H 'Content-Type: application/json' -d '{"text":"x","providerName":"nope"}'
```

Expected: `400 INVALID_REQUEST`, then `400 PROVIDER_NOT_FOUND`.

## 5. Cases covered by tests only

Offline playback from a warmed entry, the failed cache write, and an entry evicted by its own store
are proved in `TtsServiceTest` with a failing mock provider and cache. Do not take the Pi's network or
disk down to test them.
