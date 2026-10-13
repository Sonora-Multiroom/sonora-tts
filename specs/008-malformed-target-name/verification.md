# Verification: 008-malformed-target-name

Date: 2026-10-13. Version 0.1.7, branch `008-malformed-target-name`.

## Build and tests

- New test `TtsServiceTest.aNameThatIsNotAValidIdRaisesTargetNotFound`, for `SINGLE_OUTPUT` and
  `OUTPUT_GROUP` with `targetName` `"living room"`: both cases failed before the `TtsService` change
  (2 failures, the `IllegalArgumentException` escaped) and pass after it. Each asserts
  `TARGET_NOT_FOUND`, the name in the message, and exactly one rejection counted under
  `TARGET_NOT_FOUND` (not `INTERNAL`).
- `TARGET_NOT_FOUND` → 400 in the `ErrorResponse` shape was already covered by
  `TtsExceptionHandlerTest`.
- `mvn verify`: exit 0, 690 tests run across the surefire reports, 0 failures; JaCoCo gate and
  enforcer passed.
- Manifest of `target/multiroom-tts-0.1.7.jar`: `Extension-Id: tts`,
  `Require-API-Version: [0.1.21,0.2.0)`.

## Other places that build an ID from caller text

`grep "(OutputId|GroupId)\.of\("` over `src/main`: only `TtsService`. The two calls in the playback
path (`createRoute`) run on a name that `validateTargetExists` already accepted. The cache and voice
endpoints take no output or group name.

## Production

2026-10-14, after the owner deployed 0.1.7 to `multiroom.lan` and restarted the hub (hub 0.1.25,
which includes 026). `/actuator/extensions`: `tts` 0.1.7 `ACTIVE`, `[0.1.21,0.2.0)`.

`POST /api/tts/speak` with `providerName: piper-local`; nothing was synthesized or played:

| `targetType` | `targetName` | Answer |
|---|---|---|
| `SINGLE_OUTPUT` | `living room` | 400 `TARGET_NOT_FOUND`, "… named 'living room' found (not a valid ID)" |
| `OUTPUT_GROUP` | `living room` | 400 `TARGET_NOT_FOUND`, same message |
| `SINGLE_OUTPUT` | `no-such-target-008` | 400 `TARGET_NOT_FOUND`, no "(not a valid ID)" |
| `OUTPUT_GROUP` | `no-such-target-008` | 400 `TARGET_NOT_FOUND`, same |

- `/actuator/prometheus`: `tts_announcements_total{outcome="rejected",error="TARGET_NOT_FOUND",provider="piper-local"}`
  read 4 (all four requests), and no `error="INTERNAL"` series exists.
- `/var/log/multiroom.log`: no `IllegalArgumentException` or servlet stack trace for these requests;
  the malformed name never reached `DeviceQueryService` (only the two well-formed lookups were
  logged).

The local smoke run (`mvn deploy -Plocal`) was skipped: the production check covers it.
