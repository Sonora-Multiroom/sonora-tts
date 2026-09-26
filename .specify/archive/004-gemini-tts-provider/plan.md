# Implementation Plan: Gemini TTS Provider

**Branch**: `004-gemini-tts-provider` | **Date**: 2026-09-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/004-gemini-tts-provider/spec.md`

**Depends on**: `002-google-voice-selection` and `003-google-service-account-auth` (merged).
**No upstream change**: nothing here touches `multiroom-api`, so `multiroom.require_api_version`
stays `0.1.18` and no `multiroom-ai` branch is needed. The extension's version goes from `0.1.2`
to `0.1.3`.

## Summary

A new provider type, `google-gemini`, synthesizes through Google's Text-to-Speech `v1`
`text:synthesize` with a service account's bearer token (003). A request names the model
(`voice.modelName`), a Gemini voice and a language. It may carry a style prompt (`input.prompt`,
kept separate from the text) and a speaking rate.

An entry must set a key file, `model`, `voice` and `language`. It may set a default `style-prompt`
and `speaking-rate`. It must not set `api-key`, `engine`, `pitch` or `extra-params`. A request may
override the voice, the language, the prompt (`""` turns the default off) and the rate, never the
model.

Resolution stays pure:

- the voice is normalized to `Kore` form
- the prompt is stripped, and an empty one means none
- the prompt's length is checked against `max-text-length`

The cache key gains the prompt only when one is in effect. The model rides in the existing
`engineName` component, so every existing key hashes as before.

The Google plumbing both types need moves out of `GoogleCloudTtsProvider` into a shared
`GoogleTtsClient`:

- the authorised send and the single retry after a 401
- turning Google's refusals into the existing error codes, with Google's explanation
- decoding the audio

`GoogleCloudTtsProvider`'s existing tests must pass unmodified as proof. Start-up warns when a
Gemini entry is the effective default provider.

*Added 2026-09-26* (spec Story 6, research R13): a Gemini entry lists its voices. Google's
`GET v1/voices` publishes them as bare names, so the provider implements `VoiceCatalogueProvider`
through its own `GoogleVoiceCatalogue`, which gains a voice selector: `google-cloud` keeps full
names, Gemini keeps bare `GeminiVoice`-form names with the model as the engine and no language.
`CatalogueVoice` and the REST `VoiceDescriptor` gain `gender`. The listing's `language` filter is
validated but does not narrow, `engine` is rejected, and announcing never consults the list.

## Technical Context

**Language/Version**: Java 17 LTS

**Primary Dependencies**: `multiroom-api` 0.1.18 (`provided`), Spring Boot 3.5.x (`provided`),
Jackson (`provided`), `java.net.http.HttpClient`, and 003's JDK-only token exchange. **No new
dependency** (SC-009).

**Storage**: The existing on-disk audio cache. Its file format is unchanged; keys gain a component
only when a style prompt is in effect.

**Testing**: JUnit 5, Mockito, Spring Boot Test and WireMock 3.9.2. WireMock plays the token
endpoint, `text:synthesize` and `GET v1/voices`, through a loopback `token_uri`. `TestServiceAccountKeys` generates
key pairs, a 24 kHz WAV fixture covers the sample rate, and a `Clock` is injected. JaCoCo keeps its
80% floor.

**Target Platform**: Inside `multiroom-core`, on Windows (development) and on Raspberry Pi OS
ARM64 (production).

**Project Type**: A drop-in Spring Boot extension JAR.

**Performance Goals**:

- A cache hit starts playing within 1 s (SC-006). Resolution is pure, so a hit costs no network
  work, and announcing never fetches the voice list.
- A warm voice listing reads an immutable snapshot with no lock, as for `google-cloud`; a cold one
  is bounded by `voice-catalogue.fetch-timeout`.
- A miss is bounded by `timeout-seconds` (default 10 s), which covers the token and the synthesis.

**Constraints**:

- Zero network requests at start-up (FR-009, SC-005).
- Existing configurations start unedited, and existing cache keys hash unchanged (FR-018, FR-024).
- No secret in any output (SC-008).
- No error code added (FR-020).

**Scale/Scope**:

- Main code: about 6 new classes and about 21 changed classes (8 of them for voice listing:
  `CatalogueVoice`, `GoogleVoiceCatalogue`, `GoogleTtsClient`, `GoogleCloudTtsProvider`,
  `VoiceCatalogueProvider`, `VoiceQueryService`, `TtsVoiceController`, `VoiceListResponse`).
- Configuration: one new provider type and two new settings (`model`, `style-prompt`).
- REST: one new request field (`stylePrompt`); one new response field (`gender`); no new endpoint.
- Documentation: 3 documents updated and 1 moved to the archive.

No NEEDS CLARIFICATION remains. The spec's Clarifications settled the behaviour, three points of it
by live tests. [research.md](research.md) settles the technical choices in R1–R15.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | How this plan satisfies it |
|---|---|---|
| I. Clean Code | ✅ | The Gemini provider only orchestrates. Resolution (`GeminiSettingsResolver`), value forms (`GeminiVoice`, `GeminiModel`) and speaking-rate rules (`GoogleSpeakingRate`) are separate. The shared Google plumbing (`GoogleTtsClient`) exists once (DRY) |
| II. Java 17 & Spring Boot | ✅ | Records for the value types. New settings are fields on `TtsProviderConfig` with no default. No new bean: the provider is built in `TtsAutoConfiguration`'s existing switch |
| III. Test-First | ✅ | Each new class gets its test first. WireMock only, no live Gemini call and no committed key. The existing `GoogleCloudTtsProviderTest` is kept unmodified as the extraction's regression test |
| IV. SOLID | ✅ | `TtsProvider` is unchanged, and adding the type is an addition behind it. `TtsService` gains only one field copied into `RequestedSettings` and `CacheKey`, and interprets nothing |
| V. Code Quality & Reviews | ✅ | Merge gate: `mvn verify`, plus a local-core smoke run showing `tts` 0.1.3, not `REJECTED`, with a `google-gemini` entry |
| VI. Git Workflow | ✅ | Branch `004-gemini-tts-provider`; Conventional Commits |
| VII. Cross-Platform | ✅ | No platform code. `Locale.ROOT` for voice case, over ASCII letters only. The audio path is unchanged but receives a new source rate (24 kHz), so the Pi check always plays one Gemini announcement (through a temporary, non-default entry if production keeps none) and re-checks the classic entry |
| VIII. Extension Boundary | ✅ | No network at construction: the token and the voice list are lazy, and the provider contacts nothing. The key file is read at construction, as in 003. Every configuration fault aborts start-up naming `multiroom-tts`. Nothing is bundled. `enabled=false` contributes nothing and reads no key file |
| Upstream Contract | ✅ | No API change; `require_api_version` unchanged |

**Post-design re-check (after Phase 1)**: ✅ still passes. Three points were examined and
accepted:

- **Refactoring `GoogleCloudTtsProvider`** (R1) touches a working type in a feature about another
  one. The spec requires the shared behaviour to stay identical (FR-025), and the unmodified
  existing test class bounds the risk.
- **The model in `SynthesisSettings.engine`** (R5) reuses OpenAI's precedent rather than adding a
  field. `TtsService` copies it into `CacheKey.engineName` as it does for every type, so no hash
  changes.
- **Rejecting `model` on non-Gemini types** (R10) goes one step beyond the spec, which asks this
  only for `style-prompt`. It follows the constitution's "fail fast on an operator-fixable fault"
  and the project's never-accept-and-ignore rule.

**Re-check after the voice-listing addition (2026-09-26)**: ✅. The selector keeps one catalogue
implementation for both types (I, DRY); `google-cloud`'s catalogue behaviour is unchanged and
`GoogleCloudTtsProviderTest` stays unmodified; the list is fetched only on a listing request
(VIII); the new response field is additive for `google-cloud` clients.

No Complexity Tracking entries.

## Project Structure

### Documentation (this feature)

```text
specs/004-gemini-tts-provider/
├── plan.md                    # This file
├── research.md                # Phase 0: decisions R1–R15
├── data-model.md              # Phase 1: entry, value types, settings flow, resolution, synthesis
├── quickstart.md              # Phase 1: validation (automated, local core, Pi)
├── contracts/
│   ├── configuration.md       # google-gemini settings, faults, warning, run-time failures
│   └── tts-rest-api.yaml      # REST contract 0.1.3 = JAR version (+ stylePrompt)
└── tasks.md                   # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
src/main/java/multiroom/tts/
├── TtsAutoConfiguration.java              # GOOGLE_GEMINI → GoogleGeminiTtsProvider(config, catalogue
│                                          #   props (back-off), ServiceAccountKey, max-text-length)
├── TtsErrorCode.java                      # Javadoc points at 004's contract (0.1.3); no new constant
├── config/
│   ├── ProviderType.java                  # + GOOGLE_GEMINI
│   ├── TtsProviderConfig.java             # + model, stylePrompt; Javadoc on speakingRate / key file
│   └── TtsProperties.java                 # GOOGLE_GEMINI branch; style-prompt/model elsewhere rejected;
│                                          #   speaking-rate / key file allowed on gemini; cost warning
├── cache/CacheKey.java                    # + stylePrompt, "|s=" only when set
├── provider/
│   ├── RequestedSettings.java             # + stylePrompt
│   ├── SynthesisSettings.java             # + stylePrompt (of(…) → null)
│   ├── DefaultSettingsResolution.java     # rejects stylePrompt
│   └── cloud/
│       ├── GoogleCloudTtsProvider.java    # send / 401 retry / error mapping / decode → GoogleTtsClient
│       ├── GoogleGeminiTtsProvider.java   # NEW: token → body → client.synthesize;
│       │                                  #   + VoiceCatalogueProvider: its own catalogue, Gemini selector
│       └── google/
│           ├── GoogleTtsClient.java       # NEW: HttpClient + credential; authorised send, 401 retry,
│           │                              #   status → TtsErrorCode with explanation, audioContent decode;
│           │                              #   + fetchVoices (moved from GoogleCloudTtsProvider, shared)
│           ├── GoogleVoiceCatalogue.java  # + voice selector (default: full names); null-safe order
│           ├── CatalogueVoice.java        # + gender (ssmlGender); 4-arg constructor kept
│           ├── GeminiSettingsResolver.java# NEW: pure resolution (voice, language, rate, prompt)
│           ├── GeminiVoice.java           # NEW: form + Kore-form canonicalization
│           ├── GeminiModel.java           # NEW: form check
│           ├── GoogleSpeakingRate.java    # NEW: range, NaN, neutral key (moved from GoogleVoiceResolver)
│           └── GoogleVoiceResolver.java   # uses GoogleSpeakingRate; rejects stylePrompt
├── rest/
│   ├── SpeakRequest.java                  # + stylePrompt; Javadoc names google-gemini
│   ├── TtsController.java                 # maps stylePrompt into AnnounceCommand
│   ├── TtsVoiceController.java            # Javadoc: google-gemini lists too, unchecked on announce
│   └── VoiceListResponse.java             # VoiceDescriptor + gender; nulls omitted (Gemini: no language)
└── service/
    ├── AnnounceCommand.java               # + stylePrompt
    └── TtsService.java                    # copies stylePrompt into RequestedSettings and CacheKey

src/test/java/multiroom/tts/
├── provider/cloud/google/GeminiVoiceTest, GeminiModelTest, GeminiSettingsResolverTest,
│                         GoogleSpeakingRateTest, GoogleTtsClientTest                  # NEW
├── provider/GoogleGeminiTtsProviderTest                                               # NEW (WireMock; + listing)
├── provider/GoogleCloudTtsProviderTest                                                # UNMODIFIED
├── provider/cloud/google/GoogleVoiceResolverTest, provider/DefaultSettingsResolutionTest  # + stylePrompt rejected
├── cache/CacheKeyTest                     # prompt identity; old hashes unchanged
├── config/TtsPropertiesValidationTest     # gemini faults; cross-type rules; cost warning
├── service/TtsServiceTest                 # Gemini cache identity against WireMock
├── rest/TtsControllerTest                 # stylePrompt mapped
├── service/VoiceQueryServiceTest          # gemini lists; engine rejected; language not narrowing
├── provider/cloud/google/GoogleVoiceCatalogueTest  # selector; gender; null-language order
├── rest/TtsVoiceControllerTest            # gender; no language key for a Gemini voice
└── TtsAutoConfigurationTest               # gemini entry builds with no HTTP; bad key file aborts

pom.xml                                    # 0.1.2 → 0.1.3
README.md                                  # provider list gains google-gemini
docs/google-cloud-tts-setup.md             # Gemini section: API + role, no free tier, models, voices,
│                                          #   style prompts, time limit, default-provider warning (FR-026)
docs/configuration.md                      # google-gemini type and its settings (FR-027)
docs/future/google-cloud-gemini-tts-params.md  # → docs/archive/, noting route A was built, not B (FR-028)
AGENTS.md                                  # features table: 004 version once merged
```

**Structure Decision**: Single module, existing layout. The Gemini provider sits beside
`GoogleCloudTtsProvider` in `provider.cloud`. Its pure helpers and the shared client go into
`provider.cloud.google` with the rest of the Google code. `config` validates forms through the
Gemini value types, as it already does through `GoogleLanguage` and `GoogleVoiceName`.

## Complexity Tracking

No constitution violations to justify.
