# Verification: 009-route-volume-ducking-level

Date: 2026-10-25. Branch `034-route-volume-ducking-level`, built against `multiroom-api` 0.1.29
(`~/.m2` jar has 87 entries).

## Build and tests

- `mvn -o verify`: exit 0, no failures. The ERROR log lines in the output come from tests that
  provoke provider and playback failures.
- New tests are listed in [plan.md](plan.md#testing).

## Hub end to end

Recorded by the hub in `sonora-multiroom/specs/034-per-route-volume-ducking/verification.md`
(2026-10-24 20:10–20:12): a speak request with `duckingLevelDb: -25`, `routeVolume: 100`,
`duck-others`, `piper-local` over a playing stream gave 202; the hub log showed the route created with
route volume 100 and ducking level -25, lowering the music to -25.0 dB and restoring it when the
speech drained. `/actuator/extensions` showed `tts` `ACTIVE`, requiring `[0.1.29,0.2.0)`.

## Not yet done

- Changelog entry and moving the folder to `.specify/archive/`: `/speckit-archive-run` after merge.

Version 0.1.10 (`pom.xml`), Features row added to `AGENTS.md`.
