# Implementation Plan: Answer A Malformed Target Name With 400, Not 500

**Branch**: `008-malformed-target-name` | **Date**: 2026-10-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/008-malformed-target-name/spec.md`. Written after the
fact (2026-10-15) from the spec's Proposal and [verification.md](verification.md), so that the
feature can be archived; the fix itself skipped the plan and tasks documents.

## Summary

Since hub 026 removed the unscoped `GlobalExceptionMapper`, a `targetName` that breaks the hub's ID
rule (`"living room"`) made `OutputId.of` / `GroupId.of` throw an `IllegalArgumentException` that
escaped as a 500 in Spring's `/error` shape and was counted as `INTERNAL`. `TtsService` now converts
that exception, at the one call site that builds an ID from caller text, into
`TtsException(TARGET_NOT_FOUND)`, which the existing handler answers as 400 in the contract's
`ErrorResponse` shape. The ID rule is stated in the `targetName` OpenAPI description. No API bump:
`require_api_version` stays 0.1.21.

## Technical Context

**Language/Version**: Java 17

**Primary Dependencies**: `multiroom-api` 0.1.21 (`provided`; `OutputId.of`, `GroupId.of`), starter
0.1.21 parent. No new dependency

**Storage**: Unchanged

**Testing**: JUnit 5, Mockito; `TtsServiceTest`; JaCoCo 80 % floor

**Target Platform**: Inside `multiroom-core` on Windows (development) and Raspberry Pi OS ARM64
(production); the 500 occurs only on a hub with 026 (0.1.25 in production)

**Project Type**: Single-module drop-in extension JAR

**Performance Goals**: None new

**Constraints**: No new error code, metric, tag key or tag value; no blanket mapping of
`IllegalArgumentException`; the hub's ID rule is not copied into a validation pattern here

**Scale/Scope**: 2 production classes changed (`TtsService`, `SpeakRequest`); 1 test class changed;
version 0.1.6 → 0.1.7

## Constitution Check

| Principle | Status | How |
|---|---|---|
| I. Clean Code | Pass | One small helper, `parseTargetId`; specific exception caught, never empty |
| III. Test-First | Pass | The new test failed before the change (2 failures) and passes after it ([verification.md](verification.md)) |
| V. Quality gates | Pass | `mvn verify` green (690 tests); the local smoke run was replaced by the production check |
| VII. Cross-platform | Pass | No platform code; nothing is played, so the production check is four rejected requests |
| VIII. Extension boundary | Pass | Only `multiroom-api`; nothing at start-up |
| Upstream contract | Pass | No API change; adapts to hub 026 |

## Project Structure

```text
src/main/java/multiroom/tts/service/TtsService.java   # validateTargetExists → parseTargetId(OutputId::of | GroupId::of, name)
src/main/java/multiroom/tts/rest/dto/SpeakRequest.java # targetName @Schema states the ID rule
src/test/java/multiroom/tts/service/TtsServiceTest.java # aNameThatIsNotAValidIdRaisesTargetNotFound (output and group)
pom.xml                                                # 0.1.6 -> 0.1.7
```

## Design

1. **Convert at the call site.** `TtsService.validateTargetExists` builds the ID through a private
   `parseTargetId(Function<String, T> parser, String targetName)`, which catches the
   `IllegalArgumentException` and throws `TtsException(TARGET_NOT_FOUND, "No output or output group
   named '<name>' found (not a valid ID)")`. A well-formed name that does not exist keeps the
   message without "(not a valid ID)".
2. **`TARGET_NOT_FOUND`, not `INVALID_REQUEST`.** The caller's fix is the same either way: pick a
   real output or group. sonora-cli maps `TARGET_NOT_FOUND` to "target not found" (exit 12) and
   `INVALID_REQUEST` to a generic validation error (exit 6), and the code stays right if the hub
   ever relaxes its ID rule. It is already in `CALLER_FIXABLE`, so the answer is 400 in the
   `ErrorResponse` shape, and `speak()` counts the rejection under `TARGET_NOT_FOUND`, not
   `INTERNAL`.
3. **No blanket mapping.** `TtsExceptionHandler` does not map every `IllegalArgumentException`;
   that would turn real bugs into 400s.
4. **No `@Pattern` on `SpeakRequest.targetName`.** Bean validation runs first and answers
   `INVALID_REQUEST` with provider `unknown`, undoing decision 2; the regex would copy the hub's
   `DomainId` rule into this repository, where it drifts unnoticed. The field's OpenAPI description
   states the rule instead: "1-255 letters, digits, hyphens or underscores. Any other name answers
   400 TARGET_NOT_FOUND".
5. **No other affected path.** `(OutputId|GroupId).of(` occurs only in `TtsService`; the playback
   path's `createRoute` runs on a name `validateTargetExists` already accepted, and the cache and
   voice endpoints take no output or group name.

## Testing

- `TtsServiceTest.aNameThatIsNotAValidIdRaisesTargetNotFound`, for `SINGLE_OUTPUT` and
  `OUTPUT_GROUP` with `"living room"`: asserts `TARGET_NOT_FOUND`, the name in the message, and one
  rejection counted under `TARGET_NOT_FOUND`, not `INTERNAL`.
- `TARGET_NOT_FOUND` → 400 in the `ErrorResponse` shape was already covered by
  `TtsExceptionHandlerTest`.
- Why the existing tests missed it: the test classpath has no `multiroom-rest`, so they never ran
  with the hub's global advice and never asserted what it produced.

## Complexity Tracking

No constitution violations to justify.
