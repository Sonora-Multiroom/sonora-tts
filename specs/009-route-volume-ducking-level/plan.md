# Implementation Plan: Announcement Route Volume And Ducking Level

Spec: [spec.md](spec.md). Contract: hub `034-per-route-volume-ducking/contracts/tts.md`.

## Approach

Carry two nullable values from the request to `createRoute`; resolve defaults and refuse the
`mix` + level combination once, in `TtsService.announce`.

| Component | Change |
|---|---|
| `pom.xml` | `multiroom.require_api_version` 0.1.21 → 0.1.29 (the manifest range becomes `[0.1.29,0.2.0)`) |
| `rest/dto/SpeakRequest` | `routeVolume` (`@Min 0`, `@Max 100`) and `duckingLevelDb` (`@DecimalMin -60.0`, `@DecimalMax 0.0`), documented with `@Schema`; a violation is the existing `INVALID_REQUEST` path |
| `rest/controller/TtsController` | passes both to `AnnounceCommand` |
| `service/AnnounceCommand` | two fields; the earlier constructor shapes stay and pass `null` |
| `config/PlaybackProperties`, `TtsProperties.validate()` | `route-volume`, `ducking-level-db`; range checks that throw the usual start-up fault |
| `service/TtsService` | `announce` resolves request → default; `resolveDuckingLevel` refuses a level with `mix` (400 `INVALID_REQUEST`) and drops the default for `mix`; the queued path builds `RouteOptions` and calls the `RouteOptions` overload of `createRoute` for output and group |
| `queue/AnnouncementTask` | two fields; the old constructor passes `null` |
| `docs/configuration.md`, `AGENTS.md` | the keys, the request fields, the API requirement |

## Decisions

- The request bounds are checked by Bean Validation and `RouteOptions`' own constructor bounds are the
  same numbers, so a value the extension accepts the hub accepts. The mix check cannot be Bean
  Validation (it depends on the default mode), so it lives in `TtsService`.
- A configured ducking default with `mix` is dropped silently, since the operator set it for
  `duck-others`; an explicit request value with `mix` is a caller error.
- The old `AnnounceCommand` and `AnnouncementTask` constructors are kept so existing tests and
  callers do not change.

## Testing

`TtsPropertiesValidationTest` (default ranges, in and out), `TtsControllerTest` (fields reach the
command; out-of-range answers 400 and synthesizes nothing), `TtsServiceTest` (options reach both
`createRoute` overloads, default vs request, `mix` sends no level, `mix` + level refused before
synthesis, values resolved at acceptance). `mvn verify` is the gate.
