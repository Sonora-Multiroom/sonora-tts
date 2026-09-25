# Main Implementation Plan: Sonora TTS

How this repository is built and how it docks onto the host, as implemented. Feature-level
planning lives under [specs/](../../specs/). Folded in below: `001-tts-extension`
[Source: .specify/archive/001-tts-extension/plan.md], `002-google-voice-selection`
[Source: .specify/archive/002-google-voice-selection/plan.md] and
`003-google-service-account-auth` [Source: .specify/archive/003-google-service-account-auth/plan.md].

## Shape

A single Maven module producing one shaded JAR, `multiroom-tts.jar`, dropped into the host's
`extensions/` directory.

```text
pom.xml                    # parent: ai.multiroom:multiroom-extension-starter:0.1.18, <relativePath/> EMPTY
src/main/java/multiroom/tts/
├── TtsAutoConfiguration.java  # the entry point — @AutoConfiguration,
│                              #   @ConditionalOnProperty("multiroom.tts.enabled", matchIfMissing),
│                              #   @ComponentScan("multiroom.tts"); providers, registry, cache,
│                              #   converter and resolver are @Beans here
├── TtsErrorCode, TtsException # services throw codes; nothing below rest/ knows an HTTP status
├── config/                    # TtsProperties @ConfigurationProperties("multiroom.tts") + nested
│                              #   cache/queue/provider config; ALL defaults and start-up
│                              #   validation live here
├── provider/                  # TtsProvider SPI (resolveSettings + synthesize), ProviderRegistry
│   │                          #   (first = implicit default), VoiceCatalogueProvider (optional
│   │                          #   listing capability), Requested/SynthesisSettings,
│   │                          #   DefaultSettingsResolution (001 rules for non-Google providers)
│   ├── cloud/                 #   OpenAiTtsProvider, GoogleCloudTtsProvider (orchestrator only)
│   │   └── google/            #   GoogleEngine, GoogleLanguage, GoogleVoiceName (sealed Full|Short),
│   │                          #   GoogleVoiceResolver (pure), GoogleVoiceCatalogue (per entry),
│   │                          #   CatalogueVoice, GoogleErrorBody (API + OAuth error shapes);
│   │                          #   credentials: GoogleCredential (sealed ApiKey|ServiceAccount),
│   │                          #   ServiceAccountKey, ServiceAccountAssertion (RS256 JWT, pure),
│   │                          #   GoogleTokenExchange, GoogleAccessTokenCache (per entry),
│   │                          #   TokenFailure (sealed Unavailable|Rejected)
│   └── local/                 #   PiperTtsProvider (python3 -m piper), LocalHttpTtsProvider
├── audio/                     # AudioConverter (JDK AudioSystem + injected FormatConverter),
│                              #   AudioFormatMapper, WavFileWriter,
│                              #   TtsInputResolver (tts://<uuid> → file://…/<sha256>.wav)
├── cache/                     # AudioCache / FilesystemAudioCache: index.json + <sha256>.wav, LRU, pins
├── queue/                     # AnnouncementQueueManager: per-target queue + worker, SmartLifecycle
├── service/                   # TtsService (validate → resolve → cache → synthesize → convert →
│                              #   enqueue), PlaybackCompletionListener (@EventListener
│                              #   RouteDestroyedEvent), VoiceQueryService (voice listing)
└── rest/                      # TtsController, TtsCacheController, TtsVoiceController,
                               #   scoped TtsExceptionHandler
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
| `multiroom-api` | `provided` (from the parent) | The whole contract: routing, device registry, `TargetType`, `RouteDestroyedEvent`, `FormatConverter` |
| `spring-boot-starter-web`, `-validation`, Jackson | `provided` | Supplied by the host at runtime; bundling risks a class collision with another extension |
| `spring-boot-configuration-processor` | `provided`, optional | Configuration metadata for `multiroom.tts.*` |
| `swagger-annotations-jakarta` 2.2.47 | `provided` | `@Operation` / `@ApiResponse` for springdoc; pinned to the copy `multiroom-rest` ships, never bundled |
| `javax.sound.sampled` (JDK) | — | WAV parsing, which is why there is **no** `multiroom-decoder` dependency |
| `java.net.http.HttpClient` (JDK) | — | Cloud provider calls, no vendor SDK |
| JUnit 5, Mockito, Spring Boot Test | `test` | |
| WireMock (`wiremock-standalone` 3.9.2) | `test` | Cloud provider HTTP; no test calls a real provider |
| `jacoco-maven-plugin` 0.8.14 | build | Constitution III's 80 % line-coverage floor, enforced by `mvn verify` since 002. 0.8.14 because 0.8.11 cannot read Java 25 class files |

002 and 003 added no dependency. Service-account sign-in uses the JDK's `java.security`
(`KeyFactory`, `Signature`) — `google-auth-library` was rejected because shading it would bring
Guava and its own HTTP transport onto the shared classpath.

## Configuration

`multiroom.tts.*`, every default a field initialiser (the JAR ships no `application.yml`):

| Key | Default | Notes |
|---|---|---|
| `enabled` | `true` | Read once at context refresh |
| `providers[]` | empty | `name`, `type` (`OPENAI`, `GOOGLE_CLOUD`, `PIPER`, `LOCAL_HTTP`), credentials, `voice`, `language` (no default since 002), `engine`, `timeout-seconds` (10), `extra-params` |
| `providers[].pitch`, `.speaking-rate` | not sent | `google-cloud` only: [-20, 20] and [0.25, 2.0]. A start-up fault on any other type |
| `providers[].service-account-key-file` | none | `google-cloud` only, exclusive with `api-key` (exactly one required). A path, resolved against the working directory. A start-up fault on any other type |
| `default-provider` | first in the list | |
| `max-text-length` | 500 | |
| `cache.dir` | `${user.home}/.multiroom/tts-cache` | `java.nio.file.Path` |
| `cache.max-size-mb` | 500 | LRU above it |
| `queue.max-depth-per-target` | 10 | |
| `voice-catalogue.ttl` | `24h` | Per `google-cloud` entry's own catalogue |
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

Start-up validation reads configuration and the filesystem only — never a socket, never a process.
Piper's `model-path` and its `.onnx.json` sidecar must exist; `python-executable` is a
`PATH`-resolved command and is not checked. Operator reference:
[docs/configuration.md](../../docs/configuration.md).

## HTTP surface

`/api/tts/**` on the host's single port, owned by this module and documented by the host's springdoc
with no change to `multiroom-rest`:

| Method | Path | Handler |
|---|---|---|
| `POST` | `/api/tts/speak` | `TtsController` — `202` with `cacheHit`, or `{error, message}` with 400/503 |
| `DELETE` | `/api/tts/cache` | `TtsCacheController` |
| `GET` | `/api/tts/cache/stats` | `TtsCacheController` |
| `GET` | `/api/tts/providers/{name}/voices?language=&engine=` | `TtsVoiceController` (002) — 400 for a non-Google provider, 503 `VOICE_CATALOGUE_UNAVAILABLE` |

Since 002, `POST /api/tts/speak` also takes optional `engine`, `pitch` and `speakingRate`, and the
error enum has nine codes (`INVALID_VOICE` 400, `VOICE_CATALOGUE_UNAVAILABLE` 503 added). 003
changed no endpoint and added no code: token failures reuse `PROVIDER_ERROR`, `PROVIDER_TIMEOUT`
and `PROVIDER_RATE_LIMITED`.

Deliberately not under `/api/v2/**` (`multiroom-rest`'s contract with `sonora-cli` and
`sonora-mcp`), and deliberately not RFC 7807 for the same reason. Current contract, versioned with
the JAR:
[.specify/archive/003-google-service-account-auth/contracts/tts-rest-api.yaml](../archive/003-google-service-account-auth/contracts/tts-rest-api.yaml)
(v0.1.2 — 002's v0.1.1 plus a changelog entry; 002's superseded 001's).

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
  id, restores the snapshotted routes, unpins, and drops any temp file. It does not unregister the
  input — `autoRemove` already has
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
   Pi end-to-end run — are unchecked

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
