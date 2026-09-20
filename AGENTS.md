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

- `specs/` — active feature specs. `001-tts-extension` is the feature this repository exists for
- `.specify/memory/` — the consolidated, authoritative view: `constitution.md` first
- After a feature merges, `/speckit-archive-run` folds its spec into `.specify/memory/`

**Lookup order**: `specs/` → `.specify/memory/`. Never assume behaviour — read the spec.

## Known Issues & Gotchas

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
