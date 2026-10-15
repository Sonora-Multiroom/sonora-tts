# Main Implementation Plan: Sonora TTS

> **Revision**: 2026-10-15 — archived `.specify/archive/005-tts-metrics`: the `metrics/` package,
> `QueueRejectedException` and the SPI/registry/queue additions in the tree; Micrometer
> (`provided`) and the Prometheus registry (`test`) as dependencies; the metrics surface on the
> host's `/actuator/prometheus` and the unreadable-body answer; metrics design decisions and
> testing notes; T048 recorded and T049 recorded as user-performed. Earlier revisions are recorded
> at the end of this file.
> **Revision**: 2026-10-15 — archived `.specify/archive/006-announcement-ducking`: `PlaybackMode` and
> `PlaybackProperties` in the tree, `PlaybackCompletionListener` without `RouteService`; starter and
> `multiroom-api` 0.1.21; `multiroom.tts.playback.default-mode` and the host's `audio.mixing.*`
> keys; `playbackMode` on the HTTP surface and 006's contract v0.1.5; refusals under
> `tts_playbacks_total{outcome="failed"}`; the completion decision rewritten (no restore) and 006's
> design decisions added; testing notes; T035/T036 recorded.
> **Revision**: 2026-10-15 — archived `.specify/archive/007-failed-start-cleanup`: the completion and refusal
> decisions corrected for hub 0.1.22 (a failed start publishes its end first; the listener leaves a
> never-started route to the failure path; `TTS_PLAYBACK_FAILED` replaces the old ERROR line;
> `abandon` idempotent; an already-removed input is DEBUG); 007's design decisions, testing notes and
> verification (T026–T028) added. No dependency, configuration or HTTP change.
> **Revision**: 2026-10-15 — re-archived `.specify/archive/007-failed-start-cleanup` after T030 closed: the
> `startedAt` decision now records that the hub documents its meaning (`d7f3b46`).
> **Revision**: 2026-10-15 — archived `.specify/archive/008-malformed-target-name`: a malformed target name
> answers 400 `TARGET_NOT_FOUND` (HTTP surface), 008's design decisions, testing note and production
> check. No dependency or configuration change; version 0.1.7.

How this repository is built and how it docks onto the host, as implemented. Feature-level
planning lives under [specs/](../../specs/). Folded in below: `001-tts-extension`
[Source: .specify/archive/001-tts-extension/plan.md], `002-google-voice-selection`
[Source: .specify/archive/002-google-voice-selection/plan.md],
`003-google-service-account-auth` [Source: .specify/archive/003-google-service-account-auth/plan.md]
`004-gemini-tts-provider` [Source: .specify/archive/004-gemini-tts-provider/plan.md],
`005-tts-metrics` [Source: .specify/archive/005-tts-metrics/plan.md],
`006-announcement-ducking` [Source: .specify/archive/006-announcement-ducking/plan.md],
`007-failed-start-cleanup` [Source: .specify/archive/007-failed-start-cleanup/plan.md] and
`008-malformed-target-name` [Source: .specify/archive/008-malformed-target-name/plan.md].

## Shape

A single Maven module producing one shaded JAR, `multiroom-tts.jar`, dropped into the host's
`extensions/` directory.

```text
pom.xml                    # parent: ai.multiroom:multiroom-extension-starter:0.1.21 (since 006),
                           #   <relativePath/> EMPTY; multiroom.require_api_version 0.1.21
src/main/java/multiroom/tts/
├── TtsAutoConfiguration.java  # the entry point — @AutoConfiguration,
│                              #   @ConditionalOnProperty("multiroom.tts.enabled", matchIfMissing),
│                              #   @ComponentScan("multiroom.tts"); providers, registry, cache,
│                              #   converter and resolver are @Beans here
├── TtsErrorCode, TtsException # services throw codes; nothing below rest/ knows an HTTP status
├── config/                    # TtsProperties @ConfigurationProperties("multiroom.tts") + nested
│                              #   cache/queue/provider config; ALL defaults and start-up
│                              #   validation live here; ProviderType.configName() (005: the
│                              #   configuration spelling, also the `type` metric tag);
│                              #   006: PlaybackMode (DUCK_OTHERS|MIX, fromName, configName,
│                              #   joinMode) and PlaybackProperties (playback.default-mode)
├── metrics/                   # 005: TtsMetrics (the event seam), MicrometerTtsMetrics (the ONLY
│                              #   class importing io.micrometer: meters, tag rules, buckets, cache
│                              #   and queue gauges), NoopTtsMetrics, SynthesisUsage (the work one
│                              #   synthesis asks, built before the provider call)
├── provider/                  # TtsProvider SPI (resolveSettings + synthesize; since 005 type()
│   │                          #   and a pure default billingTier(settings) = "none"),
│   │                          #   ProviderRegistry (first = implicit default; isConfigured(name)
│   │                          #   since 005), VoiceCatalogueProvider (optional
│   │                          #   listing capability), Requested/SynthesisSettings,
│   │                          #   DefaultSettingsResolution (001 rules for non-Google providers)
│   ├── cloud/                 #   OpenAiTtsProvider, GoogleCloudTtsProvider (orchestrator only),
│   │   │                      #   GoogleGeminiTtsProvider (004: token → body → client; lists voices)
│   │   └── google/            #   GoogleEngine, GoogleLanguage, GoogleVoiceName (sealed Full|Short),
│   │                          #   GoogleVoiceResolver (pure), GoogleVoiceCatalogue (per entry, with
│   │                          #   a voice selector since 004), CatalogueVoice (+ gender),
│   │                          #   GoogleErrorBody (API + OAuth error shapes);
│   │                          #   shared (004): GoogleTtsClient (authorised send, 401 retry, error
│   │                          #   mapping, audio decode, voice fetch), GoogleSpeakingRate;
│   │                          #   Gemini (004): GeminiSettingsResolver (pure), GeminiVoice, GeminiModel;
│   │                          #   credentials: GoogleCredential (sealed ApiKey|ServiceAccount),
│   │                          #   ServiceAccountKey, ServiceAccountAssertion (RS256 JWT, pure),
│   │                          #   GoogleTokenExchange, GoogleAccessTokenCache (per entry),
│   │                          #   TokenFailure (sealed Unavailable|Rejected)
│   └── local/                 #   PiperTtsProvider (python3 -m piper), LocalHttpTtsProvider
├── audio/                     # AudioConverter (JDK AudioSystem + injected FormatConverter),
│                              #   AudioFormatMapper, WavFileWriter,
│                              #   TtsInputResolver (tts://<uuid> → file://…/<sha256>.wav)
├── cache/                     # AudioCache / FilesystemAudioCache: index.json + <sha256>.wav, LRU, pins
├── queue/                     # AnnouncementQueueManager: per-target queue + worker, SmartLifecycle;
│                              #   depth(key) and QueueRejectedException (QUEUE_FULL|SHUTTING_DOWN,
│                              #   still PROVIDER_ERROR to the caller) since 005; AnnouncementTask
│                              #   carries the playback mode, no route snapshot, since 006
├── service/                   # TtsService (validate → resolve → cache → synthesize → convert →
│                              #   enqueue; createRoute in the playback mode), PlaybackCompletionListener
│                              #   (@EventListener RouteDestroyedEvent; no RouteService since 006),
│                              #   VoiceQueryService (voice listing)
└── rest/
    ├── api/                   # TtsApi, TtsCacheApi, TtsVoiceApi: mappings + OpenAPI annotations
    ├── controller/            # TtsController, TtsCacheController, TtsVoiceController (implement them)
    ├── dto/                   # SpeakRequest, SpeakAcceptedResponse, VoiceListResponse, ErrorResponse
    └── advice/                # TtsExceptionHandler, scoped to the api/ interfaces; records
                               #   pre-service rejections and answers an unreadable body (005)
src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

## How it joins the host

The host discovers extension JARs before it starts and puts them on its own classpath, so this module
contributes through ordinary Spring auto-configuration and gets core services by constructor
injection. There is no extension lifecycle interface, no manifest `Extension-Class`, no private
classloader — all of that was removed by `019-extension-shared-classloader` upstream.

The manifest, written by the inherited parent POM, carries `Extension-Id: tts`,
`Extension-Version` (this module's own version) and `Require-API-Version` (pinned, the host's).

## Dependencies

| Dependency | Scope | Why |
|---|---|---|
| `multiroom-api` 0.1.21 | `provided` (from the parent) | The whole contract: routing, device registry, `TargetType`, `RouteDestroyedEvent`, `FormatConverter`; since 006 `RouteJoinMode`, `RouteAdmissionException` and the three-argument `createRoute` [Source: .specify/archive/006-announcement-ducking/plan.md -> "Primary Dependencies"] |
| `spring-boot-starter-web`, `-validation`, Jackson | `provided` | Supplied by the host at runtime; bundling risks a class collision with another extension |
| `spring-boot-configuration-processor` | `provided`, optional | Configuration metadata for `multiroom.tts.*` |
| `swagger-annotations-jakarta` 2.2.47 | `provided` | `@Operation` / `@ApiResponse` for springdoc; pinned to the copy `multiroom-rest` ships, never bundled |
| `micrometer-core` 1.15.12 | `provided`, no version (managed by `spring-boot-dependencies` 3.5.15) | Since 005: the host's metrics registry; host-supplied on the shared classpath, never bundled (constitution 1.0.2, VIII) [Source: .specify/archive/005-tts-metrics/plan.md -> "Primary Dependencies"] |
| `micrometer-registry-prometheus` 1.15.12 | `test` | Since 005: asserts the scraped Prometheus names and tag-key sets [Source: .specify/archive/005-tts-metrics/plan.md -> "Primary Dependencies"] |
| `javax.sound.sampled` (JDK) | — | WAV parsing, which is why there is **no** `multiroom-decoder` dependency |
| `java.net.http.HttpClient` (JDK) | — | Cloud provider calls, no vendor SDK |
| JUnit 5, Mockito, Spring Boot Test | `test` | |
| WireMock (`wiremock-standalone` 3.9.2) | `test` | Cloud provider HTTP; no test calls a real provider |
| `jacoco-maven-plugin` 0.8.14 | build | Constitution III's 80 % line-coverage floor, enforced by `mvn verify` since 002. 0.8.14 because 0.8.11 cannot read Java 25 class files |

002, 003 and 004 added no dependency; 005 added only Micrometer, which the host already supplies
(no `multiroom-api` change, `multiroom.require_api_version` stays 0.1.18). Service-account sign-in uses the JDK's `java.security`
(`KeyFactory`, `Signature`) — `google-auth-library` was rejected because shading it would bring
Guava and its own HTTP transport onto the shared classpath. 006 added no dependency either, but
raised the starter parent and `multiroom.require_api_version` to 0.1.21 together, so the manifest
reads `Require-API-Version: [0.1.21,0.2.0)` and a host without multiroom-ai 023 refuses the JAR;
deploy the host first [Source: .specify/archive/006-announcement-ducking/research.md -> R8].

## Configuration

`multiroom.tts.*`, every default a field initialiser (the JAR ships no `application.yml`):

| Key | Default | Notes |
|---|---|---|
| `enabled` | `true` | Read once at context refresh |
| `providers[]` | empty | `name`, `type` (`OPENAI`, `GOOGLE_CLOUD`, `GOOGLE_GEMINI`, `PIPER`, `LOCAL_HTTP`), credentials, `voice`, `language` (no default since 002), `engine`, `timeout-seconds` (10), `extra-params` |
| `providers[].pitch` | not sent | `google-cloud` only: [-20, 20]. A start-up fault on any other type |
| `providers[].speaking-rate` | not sent | `google-cloud` and `google-gemini` (since 004): [0.25, 2.0]. A start-up fault on any other type |
| `providers[].service-account-key-file` | none | `google-cloud` (exclusive with `api-key`, exactly one required) and `google-gemini` (required; `api-key` forbidden). A path, resolved against the working directory. A start-up fault on any other type |
| `providers[].model` | none | `google-gemini` only, required (004): the Gemini-TTS model (`gemini-2.5-flash-tts`), form `[a-z0-9][a-z0-9.-]*`. A start-up fault on any other type |
| `providers[].style-prompt` | none | `google-gemini` only (004): the default style prompt, stripped, ≤ `max-text-length`. A start-up fault on any other type |
| `default-provider` | first in the list | |
| `max-text-length` | 500 | |
| `cache.dir` | `${user.home}/.multiroom/tts-cache` | `java.nio.file.Path` |
| `cache.max-size-mb` | 500 | LRU above it |
| `queue.max-depth-per-target` | 10 | |
| `playback.default-mode` | `duck-others` | Since 006: the playback mode of every announcement whose request names none; `duck-others` or `mix`, case-insensitive; one value for the whole extension. Bound as a `String` and checked in `TtsProperties.validate()`; anything else (`replace` included) aborts start-up: `multiroom-tts: playback.default-mode '<v>' is not one of duck-others, mix` [Source: .specify/archive/006-announcement-ducking/contracts/configuration.md -> "`multiroom.tts.playback`"] |
| `voice-catalogue.ttl` | `24h` | Per Google entry's own catalogue (`google-gemini` too, since 004) |
| `voice-catalogue.failure-backoff` | `60s` | No refetch after a failure for this long; also the minimum age before a miss refetches. Since 003, also the token service's back-off |
| `voice-catalogue.fetch-timeout` | `3s` | Cap on one fetch, inside the entry's `timeout-seconds` |

For `google-cloud` entries, start-up also aborts on: an unrecognized `engine`; a malformed
`language` or `voice`; no default engine (no `engine` and no full `voice`); a short `voice` with no
`language`; pitch or rate out of range; a non-empty `extra-params`. A `language` that contradicts a
full `voice` only warns. Full surface:
[002 contracts/configuration.md](../archive/002-google-voice-selection/contracts/configuration.md).

Credential faults (003) abort start-up too: neither or both of `api-key` and
`service-account-key-file`; a key file on a non-Google entry (both in `TtsProperties.validate()`);
and, while `TtsAutoConfiguration` builds the provider, a key file that is missing, unreadable, not
JSON, not `"type": "service_account"`, missing `client_email` or `private_key`, holding a key that
is not PKCS#8 RSA usable for signing, or naming a non-`https`, non-loopback `token_uri`. Full
surface:
[003 contracts/configuration.md](../archive/003-google-service-account-auth/contracts/configuration.md).

`google-gemini` entries (004) abort start-up on: an `api-key` (checked first); no key file; a
missing or malformed `model`, `voice` or `language`; `engine`, `pitch` or a non-empty
`extra-params`; a rate out of range; a default prompt over `max-text-length` after stripping; and
every 003 key-file fault. `style-prompt` or `model` on any other type is a fault too. When the
effective default provider (`default-provider`, else the first **enabled** entry) is
`google-gemini`, `TtsProperties.validate()` logs one `WARN` about per-token billing and continues.
Full surface:
[004 contracts/configuration.md](../archive/004-gemini-tts-provider/contracts/configuration.md).

Start-up validation reads configuration and the filesystem only — never a socket, never a process.
Piper's `model-path` and its `.onnx.json` sidecar must exist; `python-executable` is a
`PATH`-resolved command and is not checked. Operator reference:
[docs/configuration.md](../../docs/configuration.md).

005 adds no `multiroom.tts.*` key. The metrics follow `multiroom.tts.enabled` (off: no meters), and
the host-side Spring Boot filter `management.metrics.enable.tts=false` drops them all with no code
here; synthesis histogram buckets are derived from the longest configured `timeout-seconds`
[Source: .specify/archive/005-tts-metrics/research.md -> R3, R12].

Host settings that govern announcements since 006, set in the host's `multiroom.yml` and neither
read nor overridden here: `audio.mixing.ducking.level-db` (`-15.0`, how far the music drops under
`duck-others`), `audio.mixing.ducking.fade-millis` (`150`, the lowering and restoring ramp) and
`audio.mixing.max-routes-per-output` (`4`; an announcement that would exceed it on any target
output is refused) [Source: .specify/archive/006-announcement-ducking/contracts/configuration.md -> "Host settings that govern the result"].

## HTTP surface

`/api/tts/**` on the host's single port, owned by this module and documented by the host's springdoc
with no change to `multiroom-rest`:

| Method | Path | Handler |
|---|---|---|
| `POST` | `/api/tts/speak` | `TtsController` — `202` with `cacheHit`, or `{error, message}` with 400/503 |
| `DELETE` | `/api/tts/cache` | `TtsCacheController` |
| `GET` | `/api/tts/cache/stats` | `TtsCacheController` |
| `GET` | `/api/tts/providers/{name}/voices?language=&engine=` | `TtsVoiceController` (002) — 400 for a non-Google provider, 503 `VOICE_CATALOGUE_UNAVAILABLE`. Since 004 also `google-gemini` (an `engine` filter is a 400; `language` is form-checked but does not narrow); every voice carries `gender`, and null fields are omitted |

Since 002, `POST /api/tts/speak` also takes optional `engine`, `pitch` and `speakingRate`, and the
error enum has nine codes (`INVALID_VOICE` 400, `VOICE_CATALOGUE_UNAVAILABLE` 503 added). 003
changed no endpoint and added no code: token failures reuse `PROVIDER_ERROR`, `PROVIDER_TIMEOUT`
and `PROVIDER_RATE_LIMITED`. 004 added no endpoint and no code: `POST /api/tts/speak` takes an
optional `stylePrompt` (`google-gemini` only), and listed voices gain `gender`. 006 added no
endpoint and no code: `POST /api/tts/speak` takes an optional `playbackMode` (`duck-others` or
`mix`, case-insensitive; anything else is 400 `INVALID_REQUEST` before the cache lookup and any
synthesis, and not part of the cache identity); the response is unchanged
[Source: .specify/archive/006-announcement-ducking/plan.md -> "HTTP"].

Deliberately not under `/api/v2/**` (`multiroom-rest`'s contract with `sonora-cli` and
`sonora-mcp`), and deliberately not RFC 7807 for the same reason. Current contract, versioned with
the JAR:
[.specify/archive/006-announcement-ducking/contracts/tts-rest-api.yaml](../archive/006-announcement-ducking/contracts/tts-rest-api.yaml)
(v0.1.5 — adds `playbackMode` and the playback behaviour; supersedes 004's v0.1.3, which added
`stylePrompt` and `gender` and superseded 003's v0.1.2, itself 002's v0.1.1 plus a changelog entry;
002's superseded 001's).

005 added no endpoint and no field. An unreadable `POST /api/tts/speak` body
(`HttpMessageNotReadableException`) now answers 400 `ErrorResponse(INVALID_REQUEST, "Request body
could not be read")`, which brings the implementation in line with v0.1.3; a 405 or 415 that Spring
refuses before any advice is chosen is not answered by this module
[Source: .specify/archive/005-tts-metrics/research.md -> R6].

008 added no endpoint, field or code. Since hub 026 (no global `GlobalExceptionMapper`), a
`targetName` that `OutputId.of` / `GroupId.of` refuses answers 400 `TARGET_NOT_FOUND` in the
`ErrorResponse` shape instead of a 500 from Spring's `/error`; the `targetName` OpenAPI description
states the rule ("1-255 letters, digits, hyphens or underscores")
[Source: .specify/archive/008-malformed-target-name/plan.md -> "Summary"].

### Metrics

[Source: .specify/archive/005-tts-metrics/research.md -> R3, R7] — published through the host's Micrometer
registry on the host's existing `GET /actuator/prometheus`; no endpoint of this module's own.

| Micrometer name | Type | Prometheus series | Tags |
|---|---|---|---|
| `tts.announcements` | Counter | `tts_announcements_total` | `provider`, `outcome` (`accepted`/`rejected`), `error`, `reason` |
| `tts.synthesis` | Timer, fixed histogram buckets | `tts_synthesis_seconds_{count,sum,max,bucket}` | `provider`, `type`, `outcome` (`success`/`failure`), `error` |
| `tts.synthesis.characters` | DistributionSummary | `tts_synthesis_characters_{count,sum,max}` | `provider`, `type`, `tier`, `outcome`, `error`, `part` (`text`/`style_prompt`) |
| `tts.synthesis.audio` | DistributionSummary (seconds) | `tts_synthesis_audio_seconds_{count,sum,max}` | `provider`, `type`, `tier` |
| `tts.cache.requests` | Counter | `tts_cache_requests_total` | `outcome` (`hit`/`miss`) |
| `tts.cache.size`, `tts.cache.max`, `tts.cache.entries` | Gauges | `tts_cache_size_bytes`, `tts_cache_max_bytes`, `tts_cache_entries` | — |
| `tts.queue.depth` | Gauge per target | `tts_queue_depth` | `target`, `target_type` (`output`/`group`) |
| `tts.playbacks` | Counter | `tts_playbacks_total` | `outcome` (`started`/`failed`) |

Tag values: `provider` is a configured name or `unknown`; `type` the configuration spelling
(`openai`, `google-cloud`, `google-gemini`, `piper`, `local-http`); `error` a `TtsErrorCode` name,
`INTERNAL` (HTTP 500 with no code) or `none`; `reason` `queue_full`, `shutting_down` or `none`;
`tier` a `GoogleEngine` canonical name or `other` (`google-cloud`), the configured model
(`google-gemini`), or `none`. Since 006, `tts_playbacks_total{outcome="failed"}` also counts
announcements the host refused (logged `TTS_PLAYBACK_REFUSED`), and since 007 an admitted route
that failed to start is `failed`, never `started`, counted once
[Source: .specify/archive/007-failed-start-cleanup/contracts/observability.md], and an invalid `playbackMode` is
`tts_announcements_total{outcome="rejected", error="INVALID_REQUEST"}`; no name, tag key or value
set changed [Source: .specify/archive/006-announcement-ducking/plan.md -> "Metrics"]. Contract (v0.1.4):
[.specify/archive/005-tts-metrics/contracts/metrics.md](../archive/005-tts-metrics/contracts/metrics.md);
operator guide with every query: [docs/metrics.md](../../docs/metrics.md).

## Architecture decisions

[Source: .specify/archive/001-tts-extension/plan.md, research.md]

- **Audio reaches an output through a file.** `TtsInputResolver`, a plain `@Bean
  InputEndpointResolver` that core collects by `List<>` injection, rewrites `tts://<uuid>` to the
  cached WAV's `file://` URI, and core's `WavFileInputHandler` plays it. An extension cannot supply
  an `InputHandler` (it lives in `multiroom.core.io`). The `tts://` indirection stays because it is
  where an announcement pins its cache entry against eviction until its route ends
- **The cached file is the playback artifact**: a WAV already in the native format, so a hit costs
  no conversion and no extra write
- **Conversion is the host's.** The JDK's `AudioSystem` parses the provider's WAV; the injected
  `FormatConverter` converts it once, before the cache write
- **Completion is `RouteDestroyedEvent`.** `PlaybackCompletionListener` matches the event's input
  id, unpins, releases the resolver entry and drops any temp file. It does not unregister the
  input — `autoRemove` already has. *Since 006* it restores nothing and holds no `RouteService`:
  the snapshot and restore it did until then are gone
  [Source: .specify/archive/006-announcement-ducking/plan.md -> "Completion (`PlaybackCompletionListener`)"].
  *Since 007* it ignores a destroyed route that never went live (`startedAt == null`), leaving it
  tracked for the failure path
  [Source: .specify/archive/007-failed-start-cleanup/plan.md -> "`PlaybackCompletionListener.onRouteDestroyed`"]
- **The queue guards the output, not the provider.** Synthesis runs in the request thread so every
  provider failure is reported synchronously; the queue orders only `registerInput` + `createRoute`
- **One error mapping.** `TtsExceptionHandler` holds the only `TtsErrorCode` → status table and is
  scoped to this module's controllers
- **No caching library.** The index is plain JDK; every bundled third-party class is a collision
  risk on the shared classpath
- **MQTT deferred, mechanism undecided.** Options and the constitution VIII tension are in
  [.specify/archive/001-tts-extension/contracts/tts-mqtt-topics.md](../archive/001-tts-extension/contracts/tts-mqtt-topics.md);
  the recommendation is not to build it

[Source: .specify/archive/002-google-voice-selection/plan.md, research.md]

- **Resolve, then synthesize.** `TtsProvider.resolveSettings` is pure and runs on every request;
  its result builds the cache key. The Google catalogue check runs inside `synthesize`, so only on a
  miss — audio is cached only after Google accepted the voice, so a hit already proves it exists
- **The playback path stays provider-agnostic.** The resolver returns the cache-key forms
  (`voiceKey`, `pitchKey`, `speakingRateKey`); `TtsService` copies them without knowing any
  provider's defaults. Non-Google providers delegate to one `DefaultSettingsResolution`
- **Google keys are case-folded** (`Locale.ROOT`) and omit pitch/rate at Google's defaults (0.0 /
  1.0). Other providers' voices are not case-folded: a case-sensitive local engine could otherwise
  share one entry between two voices. `requestedVoice` exists only for error messages and never
  enters the key
- **The `en-US` default moved** from the shared `TtsProviderConfig.language` initialiser into the
  non-Google resolution — the only way to drop it for `google-cloud` alone
- **Voice grammar**: a full name splits at the **last** hyphen for the voice; the engine is
  everything between language and voice, so `Chirp3-HD` and unrecognized engines both parse. Short
  names contain no hyphen, so the form decides full vs short unambiguously
- **One catalogue per entry**: one unfiltered `GET v1/voices` per entry per TTL; states empty /
  loaded / failed; single-flight fetch under a lock with a re-check, immutable snapshots behind a
  `volatile` for lock-free warm lookups; an injected `Clock`. A failed refresh keeps a loaded
  snapshot until its TTL ends. Refetch-on-miss only when the snapshot is older than the back-off,
  so typos cannot force a download per request
- **One time budget**: `synthesize` sets a deadline of `timeout-seconds`; the catalogue gets
  `min(fetch-timeout, remaining)`, synthesis the rest with a 1 s floor
- **No per-engine support table**: Google changes support without notice; send what is configured
  and surface Google's rejection

[Source: .specify/archive/003-google-service-account-auth/plan.md, research.md]

- **The credential is a strategy.** `GoogleCloudTtsProvider` holds a `GoogleCredential`: `ApiKey`
  adds `?key=`, `ServiceAccount` adds `Authorization: Bearer`. Synthesis and the voice catalogue go
  through one authorised send, so API-key entries gain no branch and `TtsProvider` is unchanged
- **The token exchange, by hand**: an RS256 JWT (`iss` = `client_email`, `cloud-platform` scope,
  `aud` = the token address, one hour) signed with `SHA256withRSA`, posted as a `jwt-bearer`
  grant to the key file's `token_uri`. `cloud-platform` also covers the Agent Platform check a
  Gemini provider will need
- **The key file is read in `TtsAutoConfiguration`, not in `TtsProperties.validate()`**: reading it
  there would either read it twice or park a private key in a `@ConfigurationProperties` bean that
  Lombok's `toString` and `/actuator/configprops` can print. Still start-up, still fail-fast;
  `initSign` proves the key is usable without signing or contacting anything
- **Token cache mirrors the voice catalogue**: an immutable `(value, renewAt, expiresAt)` snapshot
  behind a `volatile`; a fetch under `tryLock(remaining budget)` with a re-check, so concurrent
  callers make one request; an injected `Clock`. Renewal 5 minutes before expiry (half the lifetime
  for a token under 10 minutes), measured from the clock reading **before** the request. A failed
  early renewal still lets the old token serve until it expires
- **Failure classes decide the back-off**: a timeout, an I/O failure, a 5xx or a 429 is
  *unavailable* and is remembered for `failure-backoff`; any other 4xx is Google's verdict on the
  key and is never remembered; a caller that times out waiting for another's fetch is not
  remembered either. Every class maps to an existing error code
- **Token first, then the voice check.** A service-account entry gets its token before the
  catalogue check, so an outage or a rejected key is reported as itself — not as a misleading
  catalogue back-off — and Google is asked for a token at most once per announcement (plus one
  401 retry)
- **Only 401 is retried** — discard that token (only if still current, so concurrent 401s renew
  once), renew within the remaining budget, resend once. 403 is a permission denial and is never
  retried
- **Secrets never reach a string**: `ServiceAccountKey` and the token snapshot override `toString`;
  exception messages are built from classified reasons and `GoogleErrorBody`, never from a
  request or raw response
- **Roles**: a service account in the API's own project needs **no role** for classic voices
  (settled by the smoke run with a real key). Service Usage Consumer is the fix for a 403 naming
  `serviceusage.services.use`, when the account lives in another project

[Source: .specify/archive/004-gemini-tts-provider/plan.md, research.md]

- **A separate type, not a `google-cloud` engine**: 002's voice composition and catalogue check do
  not fit bare Gemini voices, and a per-request engine override could silently switch a free-tier
  entry to a paid model through a shared voice name (`Kore`)
- **Route A**: Text-to-Speech `v1` `text:synthesize` with a bearer token; body
  `{input:{text, prompt?}, voice:{languageCode, name, modelName}, audioConfig:{LINEAR16,
  sampleRateHertz, speakingRate?}}`. Agent Platform `generateContent` with a bound API key was
  rejected
- **Shared plumbing extracted, not inherited**: `GoogleTtsClient` owns the `HttpClient`, the
  credential, the authorised send with the single 401 renew-and-resend, status → error code with
  Google's explanation, `audioContent` decoding and the `v1/voices` fetch. A subclass would have
  dragged `google-cloud`'s catalogue check into Gemini. `GoogleCloudTtsProviderTest` passed
  **unmodified** as the extraction's regression gate
- **The model rides in `SynthesisSettings.engine`** (OpenAI's precedent), so `TtsService` copies it
  into `CacheKey.engineName` as for any engine. `CacheKey` appends `|s=<prompt>` only when a prompt
  is in effect, after `|p=`/`|r=` — every pre-004 key, and every unprompted Gemini key, hashes as
  before
- **Prompts are resolved in the provider's pure resolver**: `GeminiSettingsResolver` strips
  (`String.strip()`), picks request / none / default, and checks the length against
  `max-text-length` (given at construction), so `TtsService` still knows nothing about prompts.
  `GoogleVoiceResolver` and `DefaultSettingsResolution` reject a request `stylePrompt`
- **Gemini voice canonical form**: `[A-Za-z]+`, first letter upper, rest lower in `Locale.ROOT`;
  letters only keeps the mapping locale-independent. The canonical name is the key, not folded
  further
- **Speaking-rate rules shared**: range, NaN rejection and "1.0 is left out of the key" moved from
  `GoogleVoiceResolver` into `GoogleSpeakingRate`; the pitch constants stay in the resolver
- **`model` is rejected on non-Gemini types** — beyond the spec, which asks it only for
  `style-prompt`, following never-accept-and-ignore
- **The source sample rate comes from the WAV header**, never the request: Gemini returns 24 kHz
  and `FormatConverter` resamples to native, with no special case
- **One catalogue, two selectors**: `GoogleVoiceCatalogue` takes a selector from one `voices[]`
  element to an optional `CatalogueVoice`. The default keeps full names (`google-cloud`
  unchanged); the Gemini selector keeps `GeminiVoice`-form names with the model as engine and no
  language, sorted null-safe on language. The Gemini catalogue is per entry, uses the entry's own
  token, and synthesis never consults it — the list names no model
- **The cost warning lives in `TtsProperties.validate()`**, reproducing `ProviderRegistry`'s
  default rule, because validation runs once, before the registry exists

[Source: .specify/archive/005-tts-metrics/plan.md, research.md]

- **The host's Micrometer, injected** (R1): `micrometer-core` at `provided` scope, no version of
  its own. A metrics facade in `multiroom-api` (an upstream change for something the host already
  provides) and `Metrics.globalRegistry` (hidden dependency, shared global state in tests) were
  rejected
- **One seam, two implementations** (R2): callers report events through `TtsMetrics`, never a
  Micrometer type, so their tests stay plain Mockito. `TtsAutoConfiguration` holds two nested
  configurations: `@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")` builds
  `MicrometerTtsMetrics` from `ObjectProvider<MeterRegistry>.getIfAvailable()`, else the no-op;
  `@ConditionalOnMissingClass` of the same name builds `NoopTtsMetrics`. The class is named by
  string so the auto-configuration loads without Micrometer. `ObjectProvider` resolves at bean
  creation, after every definition is registered, so there is no ordering race and no `after =`
  reference to an actuator class. Neither branch can fail start-up; `enabled=false` skips the
  whole auto-configuration and so every meter. Injecting `MeterRegistry` directly, `MeterBinder`
  for everything, and a `SimpleMeterRegistry` fallback were rejected
- **One tag-key set per metric name** (R4): `PrometheusMeterRegistry` silently drops a meter whose
  tag keys differ from the first of its name, so a value that does not apply is a fixed word
  (`none`), never a missing tag
- **Where each event is recorded** (R5): accepted, and rejected by the announcement logic, in
  `TtsService.speak` around its whole body (a `finally`-style wrapper, not at each `throw`, which
  also covers `INTERNAL`); rejected by the basic request checks in `TtsExceptionHandler`
  (bean-validation, only when the failing controller is `TtsController`, and the new
  unreadable-body handler); synthesis time, characters and audio in `TtsService.synthesize` and
  the line after conversion; hit or miss at the `audioCache.get` branch; cache gauges on
  `AudioCache.stats()`, which walks the in-memory index under its lock
- **Bounded tags** (R7): the provider tag is used only when `ProviderRegistry.isConfigured` says
  so; an unrecognized Google engine maps to tier `other`, so a free-text voice cannot mint a tier;
  a Gemini model is configuration, bounded by the entries. A provider actually named `unknown`
  collides harmlessly with the placeholder
- **Queue rejections carry a reason** (R8): `QueueRejectedException extends TtsException`, always
  `PROVIDER_ERROR` with the existing messages, so the handler maps it unchanged
- **Queue depth** (R9): `depth(key)` counts waiting tasks, excluding the one playing; after a
  successful enqueue `TtsService` calls `trackQueue`, and `MicrometerTtsMetrics` registers one
  gauge per target key, reading the live queue at scrape time. Queues are never removed from the
  manager's map, so the gauge's reference stays valid
- **Playback started/failed** (R10): started right after `routeService.createRoute` returns;
  failed in `activate`'s catch, plus a wrapper around the activator handed to the queue for a
  `registerInput` or `track` failure before that `try`. The inner catch does not rethrow, so
  nothing is counted twice
- **Usage measured once, before the call** (R11): `TtsService.synthesize` builds `SynthesisUsage`
  (provider name, `type()`, `billingTier(settings)`, text and style-prompt characters as Unicode
  code points) immediately before `provider.synthesize`, then starts a `Timer.Sample`; outcome and
  error are set when it returns or fails, and a 0-length `style_prompt` part is not recorded. Audio
  seconds are `pcm.length / (sampleRate × channels × bytesPerSample)` of the native format, after
  conversion, which stays outside the timer. A mocked provider returning `null` from `type()`
  publishes `unknown`
- **Fixed histogram buckets** (R12): 0.1, 0.25, 0.5, 1, 2, 3, 5, 7.5, 10, 15, 20, 30, 45, 60, 90,
  120 s, cut after the first value at or above the longest configured `timeout-seconds`, which is
  added if not already a bucket — at most 17 buckets per series instead of Micrometer's ~70
- **Recording never breaks an announcement** (R13): every public `MicrometerTtsMetrics` method
  catches `RuntimeException`, logs `WARN` once per metric (then `DEBUG`) and returns. Micrometer
  does no I/O on record; the scrape runs on the actuator's thread. A cache hit costs one in-memory
  increment

[Source: .specify/archive/006-announcement-ducking/plan.md, research.md]

- **Join, do not replace** (R1): the queue worker calls `createRoute(inputId, OutputId|GroupId,
  task.playbackMode().joinMode())`; `AudioInputDefinition.defaultJoinMode` stays unset on the
  ephemeral input, so the mode is visible at the call and there is no silent fall-back to `REPLACE`
- **The stop–snapshot–restore machinery is deleted** (R2): `snapshotAndStopExistingRoutes`,
  `AnnouncementTask.routeSnapshot`, `PlaybackCompletionListener.restoreRoutes` and its call sites
  (completion, `cancel`, the enqueue-failure path). Keeping restoration behind a `replace` mode was
  rejected
- **An extension-owned `PlaybackMode` enum** (R3) in `config` beside `ProviderType`: one parser
  (`fromName`, case-insensitive, exactly `duck-others`/`mix`) for configuration and requests, and an
  exhaustive `switch` to `RouteJoinMode`; `REPLACE` is not representable. Using `RouteJoinMode`
  directly, or binding configuration to the enum (Spring's lenient enum conversion also accepts
  `duck_others`, `DuckOthers`, `duckothers`, and its message lacks the `multiroom-tts:` prefix),
  were rejected
- **The request field is a `String`, validated in the service** (R4): `TtsService.announce` resolves
  it right after the text check, before target lookup, provider resolution, cache lookup and
  synthesis, so the rejection is this module's `{error, message}` (`Unknown playbackMode '<v>'.
  Supported: duck-others, mix`) and is counted by `speak()`'s existing rejection path. An enum DTO
  field or a Bean Validation `@Pattern` was rejected
- **A host refusal reuses the failed-route cleanup** (R5): `RouteAdmissionException` (admission,
  before anything is reserved) and a wrapping `RouteException` (after reservation) never make a
  route go live, so core never auto-removes the input. `activate`'s catch counts `playbackFailed()`,
  calls `completionListener.cancel` and `unregisterInput`, then lets the queue move on — no retry. A
  refusal logs `TTS_PLAYBACK_REFUSED announcementId= target= output= reason=` at WARN without a
  stack trace; any other failure logs `TTS_PLAYBACK_FAILED announcementId= target=` at ERROR with
  one (since 007; before it, `Failed to create route for announcement …`). Reason and output go to
  the log only, never a tag; a new `outcome="refused"` value was rejected
  [Source: .specify/archive/007-failed-start-cleanup/research.md -> R5]
- **Completion timing unchanged** (R6): the host stops a finished route as soon as its lane has
  drained and starts the ducking release itself, so the next queued announcement still starts after
  the previous route is destroyed
- **Configuration nested like `queue` and `cache`** (R7): `PlaybackProperties` (`String defaultMode
  = "duck-others"`) in `TtsProperties`, resolved once into a `PlaybackMode` that `TtsService` reads

[Source: .specify/archive/007-failed-start-cleanup/plan.md, research.md]

- **A failed start publishes its end first on hub 0.1.22+** (R1): the hub reserves the route (no
  `startedAt`), and a failure while opening input, outputs or pipeline queues one
  `RouteDestroyedEvent` that is drained — possibly on another thread — before `createRoute` throws.
  On 0.1.21 that path publishes none; a route stopped while starting can on either
- **The hub never removes a failed start's input** (R2): `AutoRemoveInputListener` removes an
  `autoRemove` input only after a `RouteCreatedEvent`, published only when a route goes live. The
  failure path therefore keeps owning `unregisterInput`; skipping it in `abandon` (the backlog
  item's proposal) would leak the input for the life of the process
- **`startedAt == null` means "never went live"** (R3): set only by `Route.withStarted` from
  `RouteSession.markLive` and kept by a transfer, on 0.1.21 as on 0.1.22, so
  `require_api_version` stays 0.1.21. A short announcement that went live and ended before
  `createRoute` returned carries a start time and completes normally; an extension-held "starting"
  mark was rejected because it misreports that race. The meaning was undocumented upstream when
  007 was planned; sonora-multiroom `d7f3b46` now documents it in `multiroom-api` and pins it in hub
  tests. Even if a hub broke it, only the log line would regress
  [Source: .specify/archive/007-failed-start-cleanup/tasks.md -> T030]
- **One remover** (R4): `PlaybackCompletionListener.cancel` returns whether it removed the
  in-flight entry; `abandon` always counts one failed playback and always removes the input, but
  runs the queue callback only when `cancel` removed the entry (else DEBUG "already handled"). The
  listener never counts, so started + failed stays equal to the announcements taken off queues.
  `onRouteDestroyed` reads the map with a single `get`, avoiding a check-then-act race found in
  review
- **Removal answers** (R5): an `IllegalArgumentException` from `unregisterInput` ("is not
  registered") is DEBUG — the hub's only other one there refuses a static input, which a
  `tts-<uuid>` input never is; any other `RuntimeException` stays WARN with the stack trace. A
  never-started route is logged by the listener at DEBUG only

[Source: .specify/archive/008-malformed-target-name/plan.md]

- **Convert at the one call site**: `TtsService.validateTargetExists` builds the ID through
  `parseTargetId(OutputId::of | GroupId::of, name)`, which turns the `IllegalArgumentException` into
  `TtsException(TARGET_NOT_FOUND, "… (not a valid ID)")`. `(OutputId|GroupId).of(` occurs nowhere
  else on caller text: `createRoute` runs on an accepted name, and the cache and voice endpoints
  take no target. A blanket `IllegalArgumentException` mapping in `TtsExceptionHandler` was
  rejected because it would turn real bugs into 400s
- **`TARGET_NOT_FOUND`, not `INVALID_REQUEST`**: same fix for the caller, sonora-cli maps it to
  "target not found" (exit 12) rather than a generic validation error (exit 6), and it stays right
  if the hub relaxes its ID rule
- **No `@Pattern` on `SpeakRequest.targetName`**: bean validation runs first and would answer
  `INVALID_REQUEST` with provider `unknown`; the regex would copy the hub's `DomainId` rule here,
  where it drifts unnoticed. The OpenAPI description states the rule instead

## Testing strategy

Unit and slice tests only; nothing boots the host.

- `ApplicationContextRunner` for the auto-configuration: beans present, `enabled=false` contributes
  none, configuration faults fail start-up, unreachable providers do not
- `@WebMvcTest` for every controller and for the exception handler's code → status table
- WireMock for OpenAI and Google (`v1/voices` and `text:synthesize`), a stubbed process for Piper,
  Mockito for `TtsService`, the completion listener and the queue, a temp directory for the
  filesystem cache
- The pure Google classes (engine, language, voice name, resolver) are tested without HTTP; the
  catalogue's TTL and back-off through an injected `Clock`, never by sleeping
- `TtsServiceTest` drives a real `GoogleCloudTtsProvider` against WireMock to prove a cache hit
  makes no HTTP call and that spellings of one voice share an entry
- `TtsPropertiesValidationTest` includes the exact production `google-cloud` entry, which must pass
  unchanged
- Service-account tests generate an RSA key pair per test (`TestServiceAccountKeys`) — no key
  material is committed — and point `token_uri` at WireMock on loopback, so one server plays both
  Google's token endpoint and the API. A test captures every log line and exception message across
  the token failures and asserts that no private key, assertion or token appears
- Gemini (004): WireMock plays the token endpoint, `text:synthesize` and `v1/voices` (a fixture
  mixing full names and bare Gemini names in the live 2026-09-26 shape); a 24 kHz WAV fixture must
  reach `FormatConverter` as a 24 kHz source; `CacheKeyTest` pins pre-004 hashes; a captured-log
  test covers Gemini secrets; the value types and resolver are tested without HTTP
- Metrics (005) [Source: .specify/archive/005-tts-metrics/research.md -> R16]: `MicrometerTtsMetricsTest`
  over a `SimpleMeterRegistry` (exact tags per event, gauges following a stubbed cache and queue
  supplier, a throwing registry does not propagate); `PrometheusNamesTest` over a
  `PrometheusMeterRegistry` records every tag combination, scrapes, and asserts every contract
  series name, no combination dropped for a mismatched key set, and the bucket bounds;
  `TtsAutoConfigurationMetricsTest` (`ApplicationContextRunner`: registry → Micrometer, none →
  no-op, `FilteredClassLoader(MeterRegistry.class)` → starts with the no-op, `enabled=false` → no
  `TtsMetrics`); `TtsServiceTest` with a mocked `TtsMetrics` for every event, plus a cardinality
  loop of 10,000 random texts, voices and unknown providers whose meter count must not grow and
  whose tags carry no secret; handler/controller tests for the unreadable body. The cache-hit cost
  is measured by hand against a live host, never asserted as a timing
- Ducking (006) [Source: .specify/archive/006-announcement-ducking/plan.md -> "Testing"]: `PlaybackModeTest`
  (accepted and rejected spellings, `joinMode()`); `TtsServiceTest` proves the three-argument
  `createRoute` for output and group with `verifyNoMoreInteractions(routeService)` (no `stop*`,
  nothing on completion), request-over-configuration precedence, an unknown mode rejected before the
  provider and cache are touched, one `CacheKey` for the same text in two modes, the refusal cleanup
  (`playbackFailed` once, `cancel`, `unregisterInput`, the queue callback) and an enqueue failure
  that only releases the pin; `PlaybackCompletionListenerTest` (release, no `RouteService`);
  `TtsPropertiesValidationTest` (default, `mix`/`MIX`, `replace` and a misspelling abort, ignored
  when disabled); `TtsControllerTest` (`playbackMode` reaches `AnnounceCommand`). `CacheKeyTest` is
  unchanged: its pinned hashes are the cache half of the compatibility criterion
- Failed starts (007) [Source: .specify/archive/007-failed-start-cleanup/research.md -> R6]: unit tests only.
  `TtsServiceTest` simulates the 0.1.22 ordering with a `createRoute` stub that calls the real
  listener's `onRouteDestroyed` with a route without `startedAt` before throwing (queue told once,
  one failure counted, input removed once), the 0.1.21 ordering (no event), an unchanged refusal and
  `abandon` after an already-handled task; `PlaybackCompletionListenerTest`'s `routeTo` helper sets
  `startedAt`, a never-started route stays tracked, `cancel` reports its removal, and an
  already-removed input logs no WARN
- Malformed target names (008) [Source: .specify/archive/008-malformed-target-name/plan.md -> "Testing"]:
  `TtsServiceTest.aNameThatIsNotAValidIdRaisesTargetNotFound` for an output and a group. The test
  classpath has no `multiroom-rest`, so no test here ever ran with the hub's global advice — the
  500 was invisible to the suite until a test asserted the code itself
- JaCoCo's 80 % floor fails `mvn verify`

## Build, deploy, verify

```powershell
mvn verify                  # half the merge gate
mvn deploy -Plocal          # into the local multiroom-ai checkout's extensions/
mvn deploy                  # PRODUCTION (the inherited `remote` profile is active by default)
```

The other half of the gate is a live host: start core with the JAR deployed and read
`GET /actuator/extensions`. A build here proves compilation against the installed API; only a running
core proves the JAR loads.

## Risks specific to the split

1. **Silent API drift.** `provided` scope plus a runtime-only compatibility check means an
   incompatible pairing shows up as a rejected extension at start-up, not as a compile error. Rebuild
   and redeploy after every host release
2. **Stale JAR beside a rebuilt host.** The 2026-09-13 upstream incident, now twice as easy to hit
   with two repositories. Check `Extension-Version` in the inventory after each deploy
3. **Accidental production deploy.** `remote` is the inherited default profile; `mvn deploy` with no
   `-P` uploads to `multiroom.lan`
4. **No end-to-end test.** Nothing here boots the host, so 001's performance criteria (SC-001,
   SC-002, SC-006, SC-007) are measured against a deployed core and on the Pi, not in CI that does
   not exist. **Open at archival**: 001's tasks T028a, T032a, T041a and T058 — the timings and the
   Pi end-to-end run — are unchecked. 004's live runs, by contrast, are done: T053 (the local-core
   smoke run with a real key) and T054 (the production Pi check of the 24 kHz Gemini path and the
   unedited classic entry). 005's are marked done in its tasks: T048 (local-core run with a forced
   timeout and full queue, both switches, and the cache-hit median within 5 % of 0.1.3) and T049
   (the production Pi check, performed by the user)
   [Source: .specify/archive/005-tts-metrics/tasks.md -> T048, T049]. 006's are marked done too:
   T035 (local core from the multiroom-ai 023 checkout, `tts` at 0.1.5 not `REJECTED`, the invalid
   mode and the start-up fault) and T036 (production: music lowered and back, busy-output cache hit,
   Home Assistant, 50 announcements with no foreign route events, groups, `mix`, stop-all; the route
   limit only with the user's permission), whose results were recorded in the pull request
   [Source: .specify/archive/006-announcement-ducking/tasks.md -> T035, T036]. 007's are recorded:
   `mvn clean verify` green (688 tests), a local smoke run on a 0.1.22 core (`tts` 0.1.6 `ACTIVE`),
   and a production check with one normal announcement (STARTED and COMPLETED once, `started` 1, no
   `failed`); a failed start cannot be provoked safely on production and is covered by unit tests
   only [Source: .specify/archive/007-failed-start-cleanup/verification.md]. 008's: `mvn verify`
   green (690 tests), the local smoke run skipped, and a production check on hub 0.1.25 — four
   rejected requests (`living room` and an unknown valid name, output and group) all 400
   `TARGET_NOT_FOUND`, counted under that code, no `INTERNAL` series and no stack trace
   [Source: .specify/archive/008-malformed-target-name/verification.md]

---

**Revision 2026-09-25**: archived `001-tts-extension` — structure tree corrected to the implemented
packages (`rest/`, `service/`, `queue/` rather than `web/` and `playback/`); dependencies,
configuration, HTTP surface, architecture decisions and testing strategy added; open verification
tasks noted under risks.

**Revision 2026-09-25**: archived `002-google-voice-selection` — `provider.cloud.google` package,
`VoiceQueryService` and `TtsVoiceController` added to the tree; voice-catalogue and Google
pitch/rate configuration; the voices endpoint and new error codes; resolution and catalogue
decisions; Google testing notes. The JaCoCo gate is attributed to 002, where it was added.

**Revision 2026-09-25**: archived `003-google-service-account-auth` — credential classes in the
tree; `service-account-key-file` and the credential start-up faults; the current contract moved to
003's v0.1.2; service-account design decisions (credential strategy, JDK-only JWT exchange, where
the key file is read, token cache, failure classes, token-first order, 401-only retry, secrets,
roles); service-account testing notes.

**Revision 2026-09-26**: archived `004-gemini-tts-provider` — `GoogleGeminiTtsProvider`,
`GoogleTtsClient`, `GoogleSpeakingRate` and the Gemini value types in the tree; `model`,
`style-prompt`, the widened `speaking-rate` and key-file rows, Gemini start-up faults and the cost
warning; `stylePrompt`, `gender` and Gemini listing on the HTTP surface; the current contract moved
to 004's v0.1.3; Gemini design decisions and testing notes; T053/T054 recorded as verified.
