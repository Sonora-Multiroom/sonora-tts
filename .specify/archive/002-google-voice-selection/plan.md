# Implementation Plan: Google Cloud Voice Selection

**Branch**: `002-google-voice-selection` | **Date**: 2026-09-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/002-google-voice-selection/spec.md`

**Depends on**: `001-tts-extension` (merged). **No upstream change**: nothing here touches
`multiroom-api`, so `multiroom.require_api_version` stays `0.1.18` and no `multiroom-ai` branch is
needed. The extension's own version goes from `0.1.0` to `0.1.1`: every REST change is additive
(new optional fields, a new endpoint, new error codes), so a small increment is enough.

## Summary

The `google-cloud` provider stops treating the voice as an opaque string. A voice is parsed as
either a full name (`uk-UA-Chirp3-HD-Charon`) or a short name (`charon`) and resolved against an
engine and a language into one canonical full name. The language comes from the voice when the
voice carries one, so the `en-US` default no longer overrides it. Google's error body is surfaced
on every failure. Before synthesizing, the resolved name is checked against a per-entry, in-memory
copy of `GET v1/voices` (24 h expiry, 60 s failure back-off, never fetched at start-up), and an
unknown voice is rejected with a new `INVALID_VOICE` code that lists the alternatives. Pitch and
speaking rate become real, validated settings that reach Google and take part in the cache key.
A new `GET /api/tts/providers/{name}/voices` lists what the catalogue offers.

The design splits resolution into two steps so that a cache hit costs no network work:

1. **Resolve** (pure, no I/O, every request): request overrides + entry defaults → a canonical
   `SynthesisSettings`. This is what the cache key is built from.
2. **Synthesize** (cache miss only): the Google provider checks existence against the catalogue,
   then calls `text:synthesize`. Both share one time budget.

## Technical Context

**Language/Version**: Java 17 LTS

**Primary Dependencies**: `multiroom-api` 0.1.18 (`provided`), Spring Boot 3.5.x (`provided`, from
the host), Jackson (`provided`), `java.net.http.HttpClient`. No new dependency.

**Storage**: Existing filesystem audio cache (unchanged mechanism, new key components). The voice
catalogue is in memory only, per provider entry.

**Testing**: JUnit 5, Mockito, Spring Boot Test, `@WebMvcTest`, WireMock 3.9.2 (already in the POM).
A `java.time.Clock` is injected into the catalogue so expiry and back-off are tested without
sleeping.

**Target Platform**: Inside `multiroom-core` on Windows (development) and Raspberry Pi OS ARM64
(production, `multiroom.lan`).

**Project Type**: Drop-in Spring Boot extension JAR (library contributing beans and controllers).

**Performance Goals**: A cache hit does no catalogue work at all (SC-006: playback within 1 s). A
warm-catalogue check is a hash-map lookup. One catalogue fetch is one HTTP call (~2,000 voices,
a few hundred KB of JSON) per entry per 24 h.

**Constraints**: Zero network calls during start-up (FR-010, SC-005). The catalogue fetch and the
synthesis call share the entry's `timeout-seconds` budget (001 SC-003). An unreachable catalogue
never fails an announcement (FR-013, SC-007). Existing production config
(`voice: en-US-Neural2-C`, `language: en-US`, no `engine`) must start unchanged (SC-004).

**Scale/Scope**: Two `google-cloud`-sized code paths (resolution, catalogue), 15 new and 16 changed
main classes (see the source tree below), one new endpoint, two new error codes, three new
optional request fields (`engine`, `pitch`, `speakingRate`).

No NEEDS CLARIFICATION remains: the spec's Clarifications session settled every behavioural
question, and [research.md](research.md) settles the technical ones (ranges, catalogue endpoint,
name grammar, cache identity, time budget).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | How this plan satisfies it |
|---|---|---|
| I. Clean Code | ✅ | Parsing (`GoogleVoiceName`), engine aliases (`GoogleEngine`), resolution (`GoogleVoiceResolver`) and caching (`GoogleVoiceCatalogue`) are separate single-purpose classes; the provider only orchestrates |
| II. Java 17 & Spring Boot | ✅ | Records and a sealed `GoogleVoiceName`; new settings are `@ConfigurationProperties` field initialisers; the only new beans are `VoiceQueryService` (`@Bean` in `TtsAutoConfiguration`) and `TtsVoiceController` (component scan) |
| III. Test-First | ✅ | Each class above gets a unit test before its implementation; the 80% coverage floor becomes a build gate (JaCoCo `check` in `mvn verify`, T001); WireMock for `v1/voices` and `text:synthesize`; no live Google call and no key in tests |
| IV. SOLID | ✅ | `TtsProvider` gains one method (`resolveSettings`); voice listing is a separate, optional `VoiceCatalogueProvider` interface only Google implements (ISP). The playback path is unchanged, and `TtsService` stays provider-agnostic: cache-key normalization (`voiceKey`, `pitchKey`, `speakingRateKey`) is computed by the provider's resolver |
| V. Code Quality & Reviews | ✅ | Merge gate unchanged: `mvn verify` + deploy to a live core and confirm `GET /actuator/extensions` shows `tts` 0.1.1, not `REJECTED` |
| VI. Git Workflow | ✅ | Branch `002-google-voice-selection`; Conventional Commits |
| VII. Cross-Platform | ✅ | No platform code. Cache keys are ASCII hex; case-folding uses `Locale.ROOT`. Audio parameters change what is heard, so the Pi verification in [quickstart.md](quickstart.md) is required |
| VIII. Extension Boundary | ✅ | No network at bean construction (catalogue is lazy); malformed engine/language/voice, out-of-range pitch/rate, and ignored `extra-params` abort start-up naming `multiroom-tts`; nothing bundled; `multiroom.tts.enabled=false` unaffected |
| Upstream Contract | ✅ | No API change; `require_api_version` unchanged |

**Post-design re-check (after Phase 1)**: ✅ still passes. One point was examined and accepted:
changing the `TtsProvider` SPI touches all four providers, but the three non-Google ones delegate to
one shared `DefaultSettingsResolution` helper, so the change is additive and the playback path
(`TtsService` → queue → route) is untouched. No Complexity Tracking entries.

## Project Structure

### Documentation (this feature)

```text
specs/002-google-voice-selection/
├── plan.md                    # This file
├── research.md                # Phase 0: decisions R1–R12
├── data-model.md              # Phase 1: entities, grammar, resolution algorithm
├── quickstart.md              # Phase 1: validation scenarios (local + production Pi)
├── contracts/
│   ├── tts-rest-api.yaml      # Full REST contract v0.1.1 = JAR version (supersedes 001's v0.3.0)
│   └── configuration.md       # Configuration surface for google-cloud entries
└── tasks.md                   # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
src/main/java/multiroom/tts/
├── TtsAutoConfiguration.java              # passes VoiceCatalogueProperties; + VoiceQueryService bean
├── TtsErrorCode.java                      # + INVALID_VOICE, VOICE_CATALOGUE_UNAVAILABLE
├── config/
│   ├── TtsProperties.java                 # google-cloud validation; + voiceCatalogue
│   ├── TtsProviderConfig.java             # + pitch, speakingRate; language default → null
│   └── VoiceCatalogueProperties.java      # NEW: ttl, failure-backoff, fetch-timeout
├── provider/
│   ├── TtsProvider.java                   # + resolveSettings(RequestedSettings)
│   ├── VoiceCatalogueProvider.java        # NEW: optional listing capability
│   ├── RequestedSettings.java             # NEW: per-request overrides
│   ├── SynthesisSettings.java             # NEW: resolved voice/language/engine/pitch/rate + cache-key forms
│   ├── DefaultSettingsResolution.java     # NEW: 001 behaviour for non-Google providers
│   ├── SynthesisRequest.java              # carries SynthesisSettings
│   ├── cloud/
│   │   ├── GoogleCloudTtsProvider.java    # error body, audioConfig, catalogue check, listing
│   │   ├── OpenAiTtsProvider.java         # delegates resolveSettings
│   │   └── google/                        # NEW package
│   │       ├── GoogleEngine.java          # enum: canonical spelling + aliases
│   │       ├── GoogleLanguage.java        # tag format + canonical case
│   │       ├── GoogleVoiceName.java       # sealed: Full | Short; parse + canonicalize
│   │       ├── GoogleVoiceResolver.java   # request + entry → SynthesisSettings (pure)
│   │       ├── GoogleVoiceCatalogue.java  # per-entry cache, TTL, back-off, refetch-on-miss
│   │       ├── CatalogueVoice.java        # one parsed catalogue entry
│   │       └── GoogleErrorBody.java       # extracts error.message from a failure body
│   └── local/
│       ├── LocalHttpTtsProvider.java      # delegates resolveSettings
│       └── PiperTtsProvider.java          # delegates resolveSettings
├── cache/CacheKey.java                    # + pitch, speakingRate (appended only when set)
├── service/
│   ├── AnnounceCommand.java               # + engine, pitch, speakingRate
│   ├── TtsService.java                    # uses provider.resolveSettings for key + request
│   └── VoiceQueryService.java             # NEW: listing, provider-type check
└── rest/
    ├── SpeakRequest.java                  # + engine, pitch, speakingRate
    ├── TtsController.java                 # maps new fields
    ├── TtsVoiceController.java            # NEW: GET /api/tts/providers/{name}/voices
    ├── VoiceListResponse.java             # NEW
    └── TtsExceptionHandler.java           # + new codes, + TtsVoiceController in scope

src/test/java/multiroom/tts/               # mirrors the above
├── provider/cloud/google/GoogleEngineTest, GoogleLanguageTest, GoogleVoiceNameTest,
│                         GoogleVoiceResolverTest, GoogleVoiceCatalogueTest,
│                         GoogleErrorBodyTest      # NEW
├── provider/DefaultSettingsResolutionTest # NEW
├── provider/GoogleCloudTtsProviderTest    # extended (WireMock: voices, synthesize, listVoices)
├── cache/CacheKeyTest                     # NEW
├── config/TtsPropertiesValidationTest     # extended (google-cloud faults, SC-004 config)
├── service/TtsServiceTest                 # extended (cache identity, hit makes no HTTP call)
├── service/VoiceQueryServiceTest          # NEW
├── rest/TtsControllerTest                 # extended (new request fields reach AnnounceCommand)
├── rest/TtsVoiceControllerTest            # NEW
└── TtsAutoConfigurationTest               # extended (new beans; enabled=false)

docs/configuration.md, docs/google-cloud-tts-setup.md   # engine is no longer a free label
```

**Structure Decision**: Single module, existing package layout. Google-specific logic goes into a
new `provider.cloud.google` subpackage so the provider class stays an orchestrator and the pure
parts (grammar, engines, resolution) are testable without HTTP. `config` calls the pure
`google` classes for start-up format checks; they have no dependencies back on `config`.

## Complexity Tracking

No constitution violations to justify.
