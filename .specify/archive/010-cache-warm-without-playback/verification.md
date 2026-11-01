# Verification: 010-cache-warm-without-playback

## Baseline

Date: 2026-11-01. Branch `010-cache-warm-without-playback`, before any change: `mvn verify` exit 0, 713 tests, 0 failures.

## Implementation (T001-T037)

Date: 2026-11-01. `mvn verify`: exit 0, 742 tests (29 new), 0 failures. Existing test lines changed: only
`expectedStatus` gains `CACHE_WRITE_FAILED` (503) and `aFailureOnAnotherControllerIsNotAnAnnouncement` uses
`TtsVoiceController.listVoices` instead of `TtsCacheController.clearCache`. `git ls-files --eol` shows no
`w/crlf`; no `FR-`/`SC-`/`US` ids in `src`.

## Production (T038-T040)

Date: 2026-11-01, `multiroom.lan`, deployed and restarted by the user.

- T038: `GET /actuator/extensions` lists `tts` 0.1.11, `ACTIVE`, requiring `[0.1.29,0.2.0)`.
- T039 (silent, `piper-local`, unique text): first warm `200 {"cacheHit":false}`, second `200 {"cacheHit":true}`.
  `GET /api/tts/cache/stats`: `piper-local` 12 -> 13 entries (45 -> 46 total). ALSA playback check printed
  nothing before and after. `tts.cache.warm`: `hit` 1, `synthesized` 1; `tts.announcements` stayed 0 and
  `tts.cache.requests` was never registered (no announcement lookups). Refusals: blank text `400 INVALID_REQUEST`,
  unknown provider `400 PROVIDER_NOT_FOUND`.
- T040 (audible, user-approved, output `office`): before: volume 30, unmuted, enabled, no routes. Speak of the warmed
  phrase with `piper-local` answered `202 cacheHit: true`. Afterwards: no routes, ALSA playback check printed nothing,
  `audio.output.routes` for `office` read 0, the output unchanged (volume 30). Whether the phrase was heard correctly:
  user to confirm.
