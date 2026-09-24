# Main Implementation Plan: Sonora TTS

How this repository is built and how it docks onto the host. Feature-level planning lives in
[specs/001-tts-extension/plan.md](../../specs/001-tts-extension/plan.md).

## Shape

A single Maven module producing one shaded JAR, `multiroom-tts.jar`, dropped into the host's
`extensions/` directory.

```text
pom.xml                    # parent: ai.multiroom:multiroom-extension-starter:0.1.18, <relativePath/> EMPTY
src/main/java/multiroom/tts/
├── TtsAutoConfiguration.java          # the entry point — @AutoConfiguration, @ConditionalOnProperty
├── config/                            # @ConfigurationProperties("multiroom.tts"); ALL defaults here
├── provider/                          # one interface, one implementation per TTS provider
├── cache/                             # disk cache: index.json + <sha256>.wav
├── audio/                             # WAV parsing (javax.sound.sampled) + the host's FormatConverter
├── playback/                          # ephemeral input registration, routing, completion listener
└── web/                               # @RestController on /api/tts/**
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
| `javax.sound.sampled` (JDK) | — | WAV parsing, which is why there is **no** `multiroom-decoder` dependency |
| `java.net.http.HttpClient` (JDK) | — | Cloud provider calls, no vendor SDK |
| JUnit 5, Mockito, Spring Boot Test, WireMock | `test` | |

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
4. **No end-to-end test.** Nothing here boots the host, so the performance criteria (SC-001, SC-002,
   SC-006, SC-007) are measured against a deployed core and on the Pi, not in CI that does not exist
