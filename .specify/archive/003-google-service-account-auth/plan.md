# Implementation Plan: Google Cloud Service Account Authentication

**Branch**: `003-google-service-account-auth` | **Date**: 2026-09-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-google-service-account-auth/spec.md`

**Depends on**: `002-google-voice-selection` (merged). **No upstream change**: nothing here touches
`multiroom-api`, so `multiroom.require_api_version` stays `0.1.18` and no `multiroom-ai` branch is
needed. The extension's version goes from `0.1.1` to `0.1.2`.

## Summary

A `google-cloud` entry may set `service-account-key-file` instead of `api-key`, exactly one of the
two. The file is read and checked once, while the provider is built at start-up, with no network
work. On first need the entry signs a JWT with the key (JDK `Signature`, RS256, `cloud-platform`
scope) and exchanges it at the file's `token_uri` for an access token. It then sends
`Authorization: Bearer` on synthesis and on the voice catalogue, instead of `?key=`.

Tokens are held per entry and reused until 5 minutes before expiry. Fetches are single-flight, and
an unreachable token service is held off for the catalogue's `failure-backoff`. A 401 causes one
renewal and one resend. Every token failure maps to an existing error code and carries Google's
explanation, which now also covers OAuth error bodies. The design mirrors `GoogleVoiceCatalogue`
(immutable snapshot, `volatile` read, `tryLock` within the budget, injected `Clock`) and adds no
library.

## Technical Context

**Language/Version**: Java 17 LTS

**Primary Dependencies**: `multiroom-api` 0.1.18 (`provided`), Spring Boot 3.5.x (`provided`),
Jackson (`provided`), `java.net.http.HttpClient`, `java.security` (`KeyFactory`, `Signature`).
**No new dependency** (SC-009).

**Storage**: None new. Key and token are in memory per entry. Audio cache unchanged.

**Testing**: JUnit 5, Mockito, Spring Boot Test, WireMock 3.9.2 (token endpoint and API on one
server, reached through a loopback `token_uri`). Key pairs are generated per test with
`KeyPairGenerator`, and no key material is committed. `Clock` injected for expiry and back-off.

**Target Platform**: Inside `multiroom-core` on Windows (development) and Raspberry Pi OS ARM64
(production).

**Project Type**: Drop-in Spring Boot extension JAR.

**Performance Goals**: With a valid token held, a request adds one `volatile` read (SC-006). One
token fetch per entry per ~55 minutes (SC-005: ≤ 2 per hour). RSA-2048 signing costs about a
millisecond, even on the Pi.

**Constraints**: Zero network calls at start-up (FR-004, SC-003). The token fetch counts against
`timeout-seconds` (FR-016). The key file is read once (FR-013). No secret in any output (FR-018).
Production's API-key entry must start unchanged (FR-006, SC-002).

**Scale/Scope**: 6 new main classes in `provider.cloud.google`, 6 changed main classes
(`TtsException` is already non-final, so `TokenFailure` subclasses it unchanged), 1 new
setting, no new endpoint or error code, 2 documents rewritten in part.

No NEEDS CLARIFICATION remains: two Clarifications sessions settled the behaviour, and
[research.md](research.md) settles the technical questions (R1–R15). R13 (the service account's
role) is settled by a live check in [quickstart.md](quickstart.md) §4, not before implementation.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | How this plan satisfies it |
|---|---|---|
| I. Clean Code | ✅ | Separate classes for loading the key (`ServiceAccountKey`), signing (`ServiceAccountAssertion`), the HTTP exchange (`GoogleTokenExchange`), caching (`GoogleAccessTokenCache`) and choosing the credential (`GoogleCredential`); the provider still only orchestrates |
| II. Java 17 & Spring Boot | ✅ | Records and sealed types (`GoogleCredential`, `TokenFailure`); the new setting is a field on `TtsProviderConfig`; no new bean |
| III. Test-First | ✅ | Each new class has its test written first; WireMock for both endpoints; no live Google call, no committed key; the JaCoCo 80% gate stays |
| IV. SOLID | ✅ | `TtsProvider` and the playback path are untouched. The credential is a strategy the provider holds, so an API-key entry's code path gains no branch beyond "apply the credential" |
| V. Code Quality & Reviews | ✅ | Merge gate: `mvn verify` + local core smoke run showing `tts` 0.1.2, not `REJECTED`, with a service-account entry |
| VI. Git Workflow | ✅ | Branch `003-google-service-account-auth`; Conventional Commits |
| VII. Cross-Platform | ✅ | `Path` for the key file; no platform code; `java.security` RSA exists on every JDK the host runs. Audio unchanged; Pi check confirms production still announces |
| VIII. Extension Boundary | ✅ | No network at construction (tokens are lazy); local file read at provider construction is allowed; every credential fault aborts start-up naming `multiroom-tts`; nothing bundled; `enabled=false` contributes no beans and reads no key file |
| Upstream Contract | ✅ | No API change; `require_api_version` unchanged |

**Post-design re-check (after Phase 1)**: ✅ still passes. Two points were examined and accepted:

- **Reading the key file at provider construction** rather than in `TtsProperties.validate()`
  (research R11). It is still start-up and still fail-fast. Doing it in `validate()` would read the
  file twice or keep a private key in a `@ConfigurationProperties` bean that actuator can print.
- **Token first, then the voice check** (research R8) changes the order of 002's synthesis steps
  for service-account entries only. The spec's Story 3, scenario 7 was reworded to match. The
  caller-visible outcome it required is kept.

No Complexity Tracking entries.

## Project Structure

### Documentation (this feature)

```text
specs/003-google-service-account-auth/
├── plan.md                    # This file
├── research.md                # Phase 0: decisions R1–R15
├── data-model.md              # Phase 1: entities, token state machine, synthesis order
├── quickstart.md              # Phase 1: validation (automated, local core, roles, Pi)
├── contracts/
│   ├── configuration.md       # service-account-key-file, faults, run-time failures
│   └── tts-rest-api.yaml      # REST contract 0.1.2 = JAR version (002's 0.1.1 + changelog only)
└── tasks.md                   # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
src/main/java/multiroom/tts/
├── TtsAutoConfiguration.java              # google-cloud: load ServiceAccountKey when configured
├── TtsErrorCode.java                      # Javadoc points at 003's contract (0.1.2); no new constant
├── config/
│   ├── TtsProperties.java                 # exactly-one rule; reject the key file on other types
│   └── TtsProviderConfig.java             # + serviceAccountKeyFile; apiKey Javadoc
└── provider/cloud/
    ├── GoogleCloudTtsProvider.java        # holds a GoogleCredential; token first; 401 retry;
    │                                      #   one authorised send for synthesize and voices
    └── google/
        ├── ServiceAccountKey.java         # NEW: load(Path) + validation; redacted toString
        ├── ServiceAccountAssertion.java   # NEW: signed JWT (pure)
        ├── GoogleTokenExchange.java       # NEW: POST assertion → token; classifies failures
        ├── TokenFailure.java              # NEW: sealed Unavailable | Rejected (TtsException)
        ├── GoogleAccessTokenCache.java    # NEW: reuse, renewal margin, single-flight, back-off
        ├── GoogleCredential.java          # NEW: sealed ApiKey | ServiceAccount
        └── GoogleErrorBody.java           # + OAuth error shape (error, error_description)

src/test/java/multiroom/tts/
├── provider/cloud/google/ServiceAccountKeyTest, ServiceAccountAssertionTest,
│                         GoogleTokenExchangeTest, GoogleAccessTokenCacheTest,
│                         GoogleCredentialTest                     # NEW
├── provider/cloud/google/GoogleErrorBodyTest                      # extended (OAuth shape)
├── provider/cloud/google/TestServiceAccountKeys.java              # NEW test helper: key pair + file
├── provider/GoogleCloudTtsProviderTest                            # extended (bearer, 401, order, secrets)
├── config/TtsPropertiesValidationTest                             # extended (exactly-one, other types)
├── service/TtsServiceTest                                         # extended (cache identity)
└── TtsAutoConfigurationTest                                       # extended (bad file aborts; no HTTP at start)

pom.xml                                   # 0.1.1 → 0.1.2
docs/google-cloud-tts-setup.md            # service-account path; drop "discard the JSON"; restart to rotate
docs/configuration.md                     # service-account-key-file in the field table and google-cloud section
docs/future/google-cloud-service-account-auth.md  # → docs/archive/ (implemented)
docs/future/google-cloud-gemini-tts-params.md     # "feature 003" → a later, unnumbered feature
```

**Structure Decision**: Single module, existing layout. Everything Google-specific goes into
`provider.cloud.google` next to the catalogue it resembles. `config` gains no dependency on the new
classes: the file is parsed in `TtsAutoConfiguration`, which already turns configuration into
providers.

## Complexity Tracking

No constitution violations to justify.
