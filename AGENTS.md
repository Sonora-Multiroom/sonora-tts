# sonora-tts Agent Knowledge

## What this repository is

**One multiroom extension**: a drop-in JAR that adds text-to-speech announcements to a running
`multiroom-core`. It accepts a text message and a target (an audio output or an output group),
synthesizes speech through a configured provider, caches the audio on disk, and plays it by
registering an ephemeral input and routing it.

- **Language**: Java 17 LTS
- **Framework**: Spring Boot 3.5.x — **supplied by the host, never bundled** (every Spring
  dependency is `provided`)
- **Artifact**: `ai.multiroom:multiroom-tts`, package `multiroom.tts`, extension id `tts`
- **Build**: Maven (`mvn`), no module list — this repository is a single module

## Upstream: multiroom-ai (D:\projects-multiroom\multiroom-ai)

This repository builds one extension; the host, the contract and the parent POM live in the sibling
repository, which is available locally. **Read it when you need to know how the core behaves. Never
edit it from here.**

| What | Where | Role here |
|---|---|---|
| `multiroom-api` | `multiroom-ai/multiroom-api/` | The only dependency (`provided`). Source of truth for `RouteService`, `DeviceRegistryService`, `DeviceQueryService`, `InputEndpointResolver`, `TargetType`, `RouteDestroyedEvent`, `FormatConverter` |
| `multiroom-extension-starter` | `multiroom-ai/multiroom-extension-starter/pom.xml` | Parent POM: shading, JAR manifest, enforcer gates, `local`/`remote` deploy profiles |
| `multiroom-core` | `multiroom-ai/multiroom-core/` | The runtime host. Read it; never depend on it — the enforcer fails the build |
| Extension guides | `multiroom-ai/docs/extensions/` | The loading contract; key documents are snapshotted in `docs/upstream/` |
| Extraction playbook | `multiroom-ai/docs/extensions/extracting-an-extension-to-its-own-repo.md` | Why this repository is split out, and the cross-repo rules |
| Feature archive | `multiroom-ai/.specify/archive/` | 019 (shared classloader), 015 (DeviceService split), 013/016 (resolver SPI) — the spec cites these constantly |

**Rules across the repo boundary:**

1. A change to `multiroom-api` is a **multiroom-ai** change: its own branch there, full-reactor
   `mvn verify` there, version bump, then
   `mvn -pl multiroom-api,multiroom-extension-starter -am install`, and only then raise
   `multiroom.require_api_version` here. Never work around it by copying classes.
2. Artifacts resolve from the **local `~/.m2` only** — nothing is published to a remote repository.
   `Could not resolve ai.multiroom:*` means "go install it upstream", never "add a `<repository>`".
3. After the host is rebuilt, rebuild and redeploy this JAR. Incompatibility shows up only at
   runtime (`Require-API-Version` → `SemanticVersion.isCompatibleWith`), and a stale JAR beside a
   rebuilt core is a known silent failure (2026-09-13: all four extensions dropped out at once).
4. `multiroom.lan` is **production**. Reading `~/multiroom.yml` over SSH is pre-authorised:
   `ssh -o BatchMode=yes -o ConnectTimeout=10 tiger@multiroom.lan 'cat /home/tiger/multiroom.yml'`.
   Writes, restarts and `systemctl` need the user's permission each time.

## Commands

```powershell
mvn verify                      # build + tests (the first half of the merge gate)
mvn test -Dtest=ClassName       # one test class
mvn clean package -DskipTests   # just build the JAR

mvn deploy -Plocal              # copy the JAR into the local multiroom-ai checkout's extensions/
mvn deploy                      # `remote` profile is active by default: scp to multiroom.lan
```

`mvn deploy` with no profile uploads to **production**. Use `-Plocal` while developing, and treat a
bare `mvn deploy` as a deliberate production action.

There is no `-pl` here: this repository is one module, unlike `multiroom-ai`.

## The extension contract (what the host enforces)

These come from `019-extension-shared-classloader` and are the terms on which the JAR is admitted:

- **`multiroom-api` only**, `provided` scope. `multiroom-core` is banned by the inherited
  `bannedDependencies` enforcer rule
- **The entry point is** `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`,
  naming `multiroom.tts.TtsAutoConfiguration`. There is no `Extension` interface to implement and no
  `Extension-Class` manifest attribute — 019 deleted both
- **No root `application.yml` / `application.properties`** in the JAR: it would shadow the host's
  own on the shared classpath. The inherited antrun gate fails the build. Defaults live in
  `@ConfigurationProperties` field initialisers
- **No network or subprocess work during bean construction**: construct a provider, do not contact
  it; locate Piper, do not run it. An unreachable provider at boot must not abort host start-up
- **Configuration faults DO abort start-up**: a missing API key or a non-existent binary path is
  operator-fixable, so fail fast naming the extension
- **`multiroom.tts.enabled=false`** must leave the host starting normally with no beans contributed

## Specification Sources (Speckit)

| Where | What |
|---|---|
| `.specify/memory/constitution.md` | The non-negotiable rules. Read first |
| `.specify/memory/spec.md` | **Master spec**: what the extension does today — stories, requirements, entities, lifecycle, success criteria, merged from every archived feature |
| `.specify/memory/plan.md` | **Master plan**: how it is built as implemented — package layout, dependencies, configuration keys, HTTP surface, architecture decisions, testing strategy |
| `.specify/memory/changelog.md` | One entry per archived feature: what it added, its components, open tasks |
| `specs/` | Features in progress, or merged but not yet archived |
| `.specify/archive/` | Archived features: the original spec, plan, research, data model, contracts and tasks — the *why* behind the master documents |

After a feature merges, `/speckit-archive-run` folds it into the master spec and plan, and its folder
moves from `specs/` to `.specify/archive/`.

**Lookup order**: `specs/` (for a feature not yet archived) → the master spec and plan →
`.specify/archive/` (for the reasoning behind a decision). Never assume behaviour — read the spec.

### Features

| Feature | What it delivered | Version | Spec |
|---|---|---|---|
| `001-tts-extension` | The extension itself: announcements to an output or group, four providers (OpenAI, Google Cloud, Piper, local HTTP), on-disk LRU cache, per-target playback queue, `/api/tts/**` | 0.1.0 | [.specify/archive/001-tts-extension/](.specify/archive/001-tts-extension/) |
| `002-google-voice-selection` | Google voices by full name or engine + language + short name, checked against a per-entry voice catalogue (`INVALID_VOICE` lists the alternatives); Google's error message surfaced; pitch and speaking rate; `GET /api/tts/providers/{name}/voices` | 0.1.1 | [.specify/archive/002-google-voice-selection/](.specify/archive/002-google-voice-selection/) |
| `003-google-service-account-auth` | `service-account-key-file` as the alternative to `api-key` for `google-cloud`: a JDK-only JWT exchange for per-entry bearer tokens, validated once at start-up, no new error code | 0.1.2 | [.specify/archive/003-google-service-account-auth/](.specify/archive/003-google-service-account-auth/) |
| `004-gemini-tts-provider` | A `google-gemini` provider type through Google's Text-to-Speech endpoint with a service account (route A): model + voice + language, a style prompt per entry and per request, speaking rate honoured (pitch rejected), start-up faults, a default-provider cost warning, and Gemini voice listing from Google's list (with gender, also added for `google-cloud`) | 0.1.3 | [specs/004-gemini-tts-provider/](specs/004-gemini-tts-provider/) — merged, not yet archived |

Add a row when a feature merges, and change its path when it is archived.

**Do not cite spec IDs in code.** `FR-`, `SC-`, user-story (`US`) and research (`R`) numbers are
local to one feature's `spec.md` / `research.md`, so every feature reuses them (002's `SC-004` is
not 001's). State the rule or the reason itself in the comment. A feature number (`001`, `019`) or a
constitution principle is unambiguous and may be named.

## Known Issues & Gotchas

### ⚠️ Never Hold Key Material In A `@ConfigurationProperties` Bean
**Issue:** A private key or token shows up in a log line or in `/actuator/configprops`.
**Root Cause:** `TtsProviderConfig` is Lombok `@Data`, so its generated `toString` prints every
field, and Spring's actuator can print the whole bound configuration.
**Prevention Rule:** Configuration holds only the key file's **path**. Read the file in
`TtsAutoConfiguration` while building the provider, keep the key in `ServiceAccountKey` (whose
`toString` prints only the email and path), and build exception messages from classified reasons,
never from a request or a raw response.

### ⚠️ `resolveSettings` Must Stay Free Of I/O
**Issue:** Cache hits slow down, or fail while Google is unreachable, although nothing needed
synthesizing.
**Root Cause:** `TtsService` calls `TtsProvider.resolveSettings` on every request, before the cache
lookup, because the cache key is built from its result. Any network work there lands on the hit
path.
**Prevention Rule:** Keep resolution pure. Anything that needs the network — the Google voice
catalogue check included — belongs in `synthesize`, which runs only on a miss.

### ⚠️ Only Google Voice Keys Are Case-Folded
**Issue:** Two voices of a local engine start sharing one cache entry and the wrong audio plays.
**Root Cause:** Google voice names are unique regardless of case, so the Google resolver case-folds
its `voiceKey`; a local HTTP engine may be case-sensitive.
**Prevention Rule:** Put cache-key normalization in the provider's resolver (`voiceKey`, `pitchKey`,
`speakingRateKey`), never in `TtsService`, and never fold case for a provider whose names can
differ only by case.

### ⚠️ Never Unregister The Announcement's Ephemeral Input
**Issue:** `IllegalArgumentException("Input '…' is not registered")` when an announcement ends.
**Root Cause:** The input is registered with `autoRemove = true`, so core's `AutoRemoveInputListener`
unregisters it on the same `RouteDestroyedEvent` this module listens for. A second unregister finds
nothing.
**Prevention Rule:** Let `autoRemove` own the input. On completion, clean up only what is this
module's: the route snapshot, the cache pin, any temp file.

### ⚠️ A Custom Input Scheme Must Resolve To One Core Already Plays
**Issue:** A `tts://` URI reaches the pipeline and fails with `UnsupportedSchemeException`.
**Root Cause:** An `InputEndpointResolver` only rewrites a URI; core then needs an `InputHandler` for
the resulting scheme, and `InputHandler` is in `multiroom.core.io`, which an extension cannot touch.
**Prevention Rule:** `TtsInputResolver` must return a `file://` URI to a real WAV on disk. Never
hold audio in memory expecting a resolver to stream it.

### ⚠️ An Unscoped `@RestControllerAdvice` Hijacks Other Extensions' Errors
**Issue:** `multiroom-rest` endpoints start answering in this module's `{error, message}` shape.
**Root Cause:** Every extension's controllers share the host's one `DispatcherServlet`, so an advice
with no scope applies to all of them.
**Prevention Rule:** Keep `TtsExceptionHandler` scoped with `assignableTypes` to this module's
controllers, and add each new controller to that list.

### ⚠️ Piper Has No Binary, And `python-executable` Is Not A Path
**Issue:** Start-up validation rejects `python-executable: python3`, or an operator looks for a
`piper` executable that does not exist.
**Root Cause:** `rhasspy/piper` is archived; piper1-gpl (`pip install piper-tts`) ships only
`python3 -m piper`. The interpreter is normally a bare `PATH`-resolved command.
**Prevention Rule:** Validate `model-path` and its `.onnx.json` sidecar for existence; never
path-check or spawn `python-executable` during start-up.

### ⚠️ `extension.id` Is Not Derived From The Artifact Id
**Issue:** The extension loads but its enable/disable switch does nothing, or the inventory reports
`INERT` where `DISABLED` was expected.
**Root Cause:** `<extension.id>` is a POM property written into the manifest as `Extension-Id`.
`multiroom-tts` passes the scanner's `^[a-z0-9-]+$` check and then splits the switch in two:
`@ConditionalOnProperty` reads `multiroom.tts.enabled` while the inventory reads
`multiroom.multiroom-tts.enabled`. Nothing fails loudly.
**Prevention Rule:** `tts`, everywhere — the POM property, the `@ConditionalOnProperty` name and the
configuration key are the same string.

### ⚠️ The Parent POM Needs An Empty `<relativePath/>`
**Issue:** The build fails immediately with a non-resolvable parent.
**Root Cause:** Maven looks for `../multiroom-extension-starter` by default, which exists only
inside the monorepo.
**Prevention Rule:** Keep `<relativePath/>` empty so the parent resolves from `~/.m2`, and keep the
starter installed there.

### ⚠️ A Successful Upstream `install` Can Leave An Empty JAR In `.m2`
**Issue:** Compilation fails with `package multiroom.api.model does not exist` although the API
plainly has it, right after an upstream install reported success.
**Root Cause:** Observed 2026-09-20 upstream: the installed `multiroom-api-<version>.jar` contained
only its 7 `META-INF` entries. A reactor build there keeps working because it reads
`target/classes`; this repository has no such fallback.
**Prevention Rule:** Inspect the artifact before debugging imports —
`unzip -l ~/.m2/repository/ai/multiroom/multiroom-api/<version>/multiroom-api-<version>.jar` should
list ~70 entries, not 7. Re-run the upstream install.
