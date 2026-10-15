# Feature Specification: Answer A Malformed Target Name With 400, Not 500

**Feature Branch**: `008-malformed-target-name`

**Created**: 2026-10-13

**Status**: Implemented

**Input**: the backlog item "Answer a malformed target name with 400, not 500", written 2026-10-13
from the owner-judge review of `multiroom-ai` branch `026-remove-rest-api-v1`. Its text follows,
unchanged except for this header. A small fix, so it skips the plan and tasks documents; what was run
is in [verification.md](verification.md).

**Upstream reference**: hub feature 026 removes the unscoped `GlobalExceptionMapper` that used to turn
the `IllegalArgumentException` into a 400. No API bump (still multiroom-api 0.1.21).

## Problem

`TtsService.validateTargetExists` (`TtsService.java:342-357`) calls `OutputId.of(targetName)` or
`GroupId.of(targetName)` with the caller's text. A name that breaks the hub's ID rule (1–255
letters, digits, hyphens or underscores, `DomainId.validateFormat`), such as `"living room"` or a
display name with an apostrophe, makes `of` throw `IllegalArgumentException`. `speak()` counts it
as `INTERNAL` (`TtsService.java:111-112`) and rethrows it, and `TtsExceptionHandler` handles only
`TtsException`, bean validation and unreadable bodies.

Until hub 026, the hub's global `GlobalExceptionMapper` caught the exception and answered
**400** `{status, message, timestamp}`. With it gone, the exception reaches Spring's `/error`:

- The caller gets **500** `{"error": "Internal Server Error", ...}` instead of this extension's
  `ErrorResponse`.
- sonora-cli's `decodeTTSError` (`hub/tts.go:84`) reads the non-empty `error` field as the TTS code,
  so the CLI and sonora-mcp report `TTSError{500, Code: "Internal Server Error"}`. A caller mistake,
  plausible from an LLM passing a room's display name, looks like a hub fault.
- The metrics count a caller error as `INTERNAL`, and the hub log gets a servlet stack trace per
  request.

The tests cannot see this: this repository's test classpath has no `multiroom-rest`, so they never
ran with the global advice and never asserted what it produced.

## Proposal

- In `validateTargetExists`, catch the `IllegalArgumentException` from `OutputId.of` /
  `GroupId.of` and throw `TtsException(TARGET_NOT_FOUND, "No output or output group named '…'
  found (not a valid ID)")`. It is already in `CALLER_FIXABLE`, so the response is a 400 in the
  contract's `ErrorResponse` shape, and `speak()` counts it as `TARGET_NOT_FOUND` instead of
  `INTERNAL`.
- `TARGET_NOT_FOUND`, not `INVALID_REQUEST` (decided 2026-10-13): either way the caller's fix is to
  pick a real output or group name. sonora-cli classifies `TARGET_NOT_FOUND` as "target not found"
  (exit 12, `hub/errors.go:115`) and `INVALID_REQUEST` as a generic validation error (exit 6), so
  the CLI and sonora-mcp tell the caller its name was wrong. The code also stays the same if the
  hub ever relaxes its ID rule.
- Keep the conversion at the call site rather than mapping every `IllegalArgumentException` in
  `TtsExceptionHandler`: a blanket mapping would also turn real bugs into 400s.
- No `@Pattern` on `SpeakRequest.targetName` (decided 2026-10-13). Bean validation runs first and
  `handleValidationFailure` always answers `INVALID_REQUEST` with provider `unknown`, which would
  undo the code chosen above. The regex would also copy the hub's `DomainId` rule into this
  repository, where it drifts without notice, and it covers only the REST path. Instead, state the
  allowed characters (letters, digits, hyphens, underscores, 1–255) as text in the field's OpenAPI
  description, so clients and sonora-mcp's tool description see them without a second check.
- Tests: `speak` with `targetName` `"living room"` for `SINGLE_OUTPUT` and for `OUTPUT_GROUP`
  answers 400 `TARGET_NOT_FOUND` and counts a rejection under it, not `INTERNAL`.
- Check the other places that build an ID from caller text (cache and voice endpoints, if any) for
  the same path.

## Open questions

None. Both questions raised when this item was written are decided above.
