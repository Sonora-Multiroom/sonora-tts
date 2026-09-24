<!--
Sync Impact Report:
- Version: 1.0.0 (initial constitution for this repository)
- Derived from: multiroom-ai constitution 2.0.0 (D:\projects-multiroom\multiroom-ai\.specify\memory\constitution.md)
- Principles carried over essentially unchanged: I (Clean Code), II (Java 17 & Spring Boot),
  III (Test-First), IV (SOLID), VI (Git Workflow)
- Principles rewritten for a standalone extension repository:
  * V. Code Quality & Reviews — the upstream "full-reactor mvn verify" merge gate cannot exist
    here; replaced by this repository's `mvn verify` plus a deploy-and-inspect smoke run against
    a live multiroom-core
  * VII. Cross-Platform Compatibility — Raspberry Pi verification is now the only end-to-end
    channel rather than an additional one
  * VIII. Extension Boundary — what was an internal architectural rule upstream is an external
    contract here, and no reactor enforces it on our behalf. Promoted in importance accordingly
- Added section: Upstream Contract (the multiroom-ai coupling)
- Templates requiring updates: none (plan/spec/tasks templates read principles at runtime)

- Version: 1.0.1 (patch — clarification, no principle added, removed or redefined)
- Generalized the fail-fast example under Core Principles: named a specific config field
  (`piper-binary`) that no longer exists after 001-tts-extension's Piper provider moved from the
  archived `rhasspy/piper` binary to piper1-gpl (`python3 -m piper`, no standalone executable);
  replaced with a field-agnostic statement plus the PATH-vs-path distinction that motivated the
  change, so the principle stops naming implementation details of one feature
- Templates requiring updates: none
-->

# Sonora TTS Constitution

This repository builds **one multiroom extension**: a drop-in JAR that adds text-to-speech
announcements to a running `multiroom-core`. The host, the API it compiles against and the parent
POM live in a separate repository (`multiroom-ai`). Everything below follows from that.

## Core Principles

### I. Clean Code Practices

- **Meaningful Names**: Variables, methods and classes MUST have clear, self-explanatory names
  that reveal intent
- **Single Responsibility**: Each class and method MUST do one thing and do it well
- **Small Functions**: Methods MUST be small (ideally <20 lines) and operate at one level of
  abstraction
- **DRY**: Duplication MUST be eliminated through abstraction
- **Comments When Necessary**: Code should be self-documenting; comments MUST explain "why", not
  "what"
- **Error Handling**: No empty catch blocks; specific exceptions over generic ones

**Rationale**: Clean code reduces technical debt and makes onboarding faster. Poor quality
compounds and becomes exponentially harder to maintain.

### II. Java 17 & Spring Boot Standard

- **Java Version**: Java 17 LTS, matching the host application
- **Spring Boot**: 3.x, **supplied by the host at runtime and never bundled** — every Spring
  dependency in this repository is `provided` scope (see VIII)
- **Modern Java Features**: Records, sealed classes, pattern matching and text blocks are
  encouraged
- **Dependency Injection**: Constructor injection through the host's single application context;
  no manual object graphs and no second context
- **Spring Best Practices**: Contribute through `@AutoConfiguration`, `@ConfigurationProperties`
  and conditional beans, the way a starter does

**Rationale**: The extension runs inside someone else's JVM and someone else's Spring context.
Matching the host's stack exactly is what makes that possible.

### III. Test-First Development (TDD)

- **Red-Green-Refactor**: Write a failing test, implement the minimum to pass, then refactor
- **Tests Before Code**: No production code without a failing test first
- **Coverage**: Minimum 80%; the cache, the provider adapters and the request-to-playback path
  require more
- **Frameworks**: JUnit 5, Mockito, Spring Boot Test; `@WebMvcTest` slices for controllers;
  WireMock for cloud provider HTTP
- **No Live Providers In Tests**: A test MUST NOT call a real TTS provider or require an API key
- **Fast Tests**: Unit tests run in milliseconds; a slow test signals a design problem

**Rationale**: TDD is the only cheap correctness signal available here, because this repository
cannot run the host's integration suite.

### IV. SOLID Principles

Single Responsibility, Open/Closed, Liskov Substitution, Interface Segregation and Dependency
Inversion apply as upstream. In particular, provider integrations MUST sit behind one interface,
so adding a provider is an addition rather than an edit to the playback path.

### V. Code Quality & Reviews

Quality gates MUST be enforced at every stage:

- **Code Reviews**: All code MUST be reviewed before merge
- **Style**: New and changed code MUST match the surrounding style (4-space indentation, the
  existing import ordering and naming conventions), verified in review. No automated formatter is
  mandated
- **Merge Gate (two parts, both required)**:
  1. `mvn verify` in this repository MUST be green
  2. A **smoke run against a live `multiroom-core`** MUST pass: deploy the built JAR to the host's
     `extensions/` directory, start the application, and confirm the extension appears in
     `GET /actuator/extensions` with the expected version and **is not `REJECTED`**
- **Why both**: this repository has no reactor and no integration suite that boots the host. A
  green local build proves only that the module compiles against the API it was handed; it cannot
  prove the JAR loads. Until a real core has loaded it, it is not verified
- **Static Analysis (recommended)**: SpotBugs SHOULD be run on changed code, with the upstream
  noise filter (`EI_EXPOSE_REP`, `EI_EXPOSE_REP2`, `CT_CONSTRUCTOR_THROW`,
  `RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE`). Not a gate, but findings on changed code MUST be
  fixed or justified
- **Documentation**: Public types MUST have Javadoc; architectural decisions MUST be documented

### VI. Git Workflow & Version Control

- **Feature Branches**: `[###-feature-name]`, matching the directory under `specs/`
- **Commit Messages**: Conventional Commits (`type(scope): description`), explaining the "why"
- **Atomic Commits**: One logical change per commit
- **No Direct Commits** to `master`
- **Pull Request Required** for all changes
- **Tag Releases** with the semantic version of the extension
- **Version Independence**: this extension carries its own version, independent of the host's. The
  API version it requires is pinned separately (see the Upstream Contract below)

### VII. Cross-Platform Compatibility

The extension MUST run wherever the host runs: Windows, Linux and Raspberry Pi OS (ARM64).

- **Platform Agnostic Code**: No platform-specific branches
- **Path Handling**: `java.nio.file.Path` everywhere; never a hardcoded separator. The audio cache
  directory is configuration, not a constant
- **Line Endings**: `.gitattributes` required
- **Case Sensitivity**: Assume case-sensitive filesystems — cache keys and file names MUST behave
  identically on NTFS and ext4
- **Local Providers**: A locally executed engine (e.g. Piper) MUST be located through
  configuration, never assumed at a fixed path, and its absence MUST be a clear configuration
  error rather than a crash at first use
- **Dependencies**: Cross-platform and ARM64-safe; no native libraries without an abstraction
- **Testing**: The suite MUST pass on Windows (the development platform). **Any change that
  affects audio or playback MUST additionally be verified on the production Raspberry Pi**, which
  is now the only end-to-end channel this repository has — there is no in-repo test that boots the
  host

### VIII. Extension Boundary (the rules the host enforces)

These are contracts of the host's extension loader. Upstream they were an internal architectural
principle; here they are the terms on which this JAR is admitted, and **nothing in this repository
re-checks them beyond the inherited build gates**:

- **Depend on `multiroom-api` only**, at `provided` scope. A dependency on `multiroom-core` is an
  architectural violation, and the inherited `bannedDependencies` enforcer rule fails the build
- **Bundle nothing framework-shaped**: Spring, Jackson, SLF4J/Logback and the API itself are
  supplied by the host. Shading them risks a class-name collision with another independently built
  extension
- **Ship no root `application.yml` or `application.properties`**: extension JARs share the host's
  classpath, so a root config file shadows the host's own. All defaults belong in
  `@ConfigurationProperties` field initialisers. The inherited build gate fails on this
- **No network or subprocess work during bean construction**: a provider is constructed, not
  contacted; a local engine is located, not executed. An unreachable endpoint at boot is a runtime
  state, not an initialisation failure, and MUST NOT abort host start-up
- **Configuration faults DO abort start-up**: a missing API key, a configured model/binary path
  that does not exist, an unparseable configuration block — these are operator-fixable, so fail
  fast with a message naming the extension, rather than failing the first announcement hours later.
  A bare command name meant to be resolved via `PATH` (e.g. `python3`) is not a path and MUST NOT
  be checked for filesystem existence
- **Start-up cost stays negligible**: the host budgets seconds for its entire start-up with every
  extension loaded. A measurable jump caused by this module means something is being done eagerly
  that should not be
- **Switchable off**: `multiroom.tts.enabled=false` MUST leave the host starting normally with no
  beans contributed

**Rationale**: every one of these rules exists because the extension shares a classloader, a
context and a process with code it does not own. Breaking one does not fail this repository's
build — it fails someone's audio system at start-up.

## Upstream Contract

- The host repository is `multiroom-ai`, available locally at `D:\projects-multiroom\multiroom-ai`.
  It is **read-only from here**: an API change is made there, on its own branch, verified there and
  released there
- `ai.multiroom:multiroom-api` and `ai.multiroom:multiroom-extension-starter` are resolved from the
  **local Maven repository** (`~/.m2`). Nothing is published to a remote repository. A missing
  artifact means "install it upstream", never "add a `<repository>`"
- The required API version is **pinned explicitly** in `pom.xml`
  (`multiroom.require_api_version`), because the inherited default would otherwise track this
  extension's own version. That property is what the JAR manifest advertises as
  `Require-API-Version`, and the host's compatibility check reads it
- After any upstream API change: rebuild and redeploy this extension. Incompatibility surfaces only
  at runtime, and a stale JAR beside a rebuilt host is a known, silent failure mode
- `multiroom.lan` is **production**. Reading the deployed `multiroom.yml` over SSH is
  pre-authorised; writes, restarts and service control are not

## Development Workflow

1. Work from a spec under `specs/[###-feature-name]/` on a matching branch
2. Write the failing test first (III)
3. Implement, keeping the extension boundary rules (VIII) in view — they are cheapest to satisfy
   before the code exists
4. `mvn verify` locally
5. Deploy the JAR to a live core and confirm the inventory reports it correctly (V)
6. For anything touching audio: verify on the production Raspberry Pi (VII)
7. Review, then merge

## Governance

- This constitution supersedes ad-hoc practice in this repository
- Amendments require a documented rationale in the Sync Impact Report above and a version bump:
  MAJOR for removing or redefining a MUST, MINOR for a new principle or materially expanded
  guidance, PATCH for clarifications
- Where this constitution is silent, the upstream `multiroom-ai` constitution applies
- Where the two conflict on anything inside this repository, **this one wins** — it was written
  knowing there is no reactor here

**Version**: 1.0.1 | **Ratified**: 2026-09-20 | **Last Amended**: 2026-09-20
