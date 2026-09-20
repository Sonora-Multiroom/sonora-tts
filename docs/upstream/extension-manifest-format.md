# Extension Manifest Format

This document defines the manifest attributes for multiroom extension JAR files, current as of the
shared-runtime extension model (feature `019-extension-shared-classloader`). It supersedes the
pre-019 version of this document, which described a per-extension classloader and an `Extension-Class`
entry point that no longer exist.

For the authoritative, task-linked version of this contract see
[`specs/019-extension-shared-classloader/contracts/extension-authoring-contract.md`](../../specs/019-extension-shared-classloader/contracts/extension-authoring-contract.md).
This document is the narrative/reference companion to it.

## Overview

Extensions are packaged as JAR files with specific manifest attributes in `META-INF/MANIFEST.MF`.
`multiroom.core.extension.ExtensionScanner` reads these attributes **before Spring exists** — a plain
`java.nio`/`java.util.jar` scan, no classloading of any extension class — and decides whether each JAR
is accepted onto the shared classpath.

## Required Attributes

### Extension-Id

**Type**: String, `^[a-z0-9-]+$`
**Required**: Yes
**Description**: The stable machine key. It is also the extension's configuration namespace: the
enable switch is `multiroom.<id>.enabled` and its `@ConfigurationProperties` prefix is
`multiroom.<id>`.

```
Extension-Id: rest
```

**Constraints**:
- Must match `^[a-z0-9-]+$`. Absent, blank, or malformed → the JAR is **rejected** with that reason;
  start-up continues normally (this is never fatal).
- Never derived from the display name or the JAR filename — a guessed id would resolve to an enable
  key that matches nothing.
- Must be unique across every **accepted** JAR. Two accepted JARs sharing an id is a different failure
  mode from a malformed id: it **aborts start-up**, naming both JAR paths and the contended id, because
  both would contend for the same `multiroom.<id>.enabled` switch. Pick an id none of the four shipped
  extensions already uses: `rest`, `mqtt`, `dlna`, `chromecast`.

### Extension-Name

**Type**: String
**Required**: Yes (falls back to the JAR filename if absent or blank — never rejected for this alone)
**Description**: Human-readable display name. Carries no machine meaning: nothing derives a property
key, bean name, or path from it, so it may contain spaces and change freely between releases.

```
Extension-Name: REST API Extension
```

### Extension-Version

**Type**: String (Semantic Version)
**Required**: Yes
**Description**: Reported in the inventory (`/actuator/extensions`, `/api/v2/extensions`).

```
Extension-Version: 1.0.0
```

### Require-API-Version

**Type**: String (version pattern understood by `multiroom.api.model.SemanticVersion`)
**Required**: Yes
**Description**: Compatibility gate against core's own version — exact-match by default, with
wildcard and range forms available.

```
Require-API-Version: 0.1.x
```

| Pattern | Meaning |
|---|---|
| `x.y.z` | Exact version |
| `x.y.x` | Minor-version wildcard |
| `x.x.x` | Major-version wildcard |
| `>=x.y.z`, `>x.y.z`, `<=x.y.z`, `<x.y.z` | Comparison |
| `[x.y.z,a.b.c)` / `[x.y.z,a.b.c]` | Range, exclusive/inclusive upper bound |

An incompatible or malformed value **rejects** the JAR with a reason; it is never fatal to the rest of
the system.

## Removed Attribute: Extension-Class

`Extension-Class` named the implementation class under the pre-019 per-extension-classloader model.
It is **gone** — `ExtensionManifest` no longer declares the attribute name and nothing in the tree
reads it. The entry point is now
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (below). Do not
set it in new extensions; a legacy manifest that still carries it is not rejected, the attribute is
simply ignored.

`Extension-Exclusive-Packages`, which configured per-JAR isolation, is gone entirely — there is no
per-JAR isolation left to configure (every accepted extension shares one classloader with core).

## Entry Point

```
src/main/resources/META-INF/spring/
    org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

One fully-qualified `@AutoConfiguration` class name per line. Spring Boot's `ImportCandidates` reads
this resource from every JAR on the shared classpath via `ClassLoader.getResources` (plural), so each
extension owns its own file in its own JAR — no merging is required across JARs at start-up.

```java
@AutoConfiguration
@ConditionalOnProperty(name = "multiroom.rest.enabled", havingValue = "true", matchIfMissing = true)
@ComponentScan("multiroom.rest")
public class RestAutoConfiguration {
}
```

## Complete Example

```
Manifest-Version: 1.0
Extension-Id: rest
Extension-Name: REST API Extension
Extension-Version: 1.2.0
Require-API-Version: 0.1.x
```

## Discovery and Validation

`ExtensionScanner.scan()` runs once, before Spring exists, and never loads a class:

1. Open the JAR, read `META-INF/MANIFEST.MF`. No manifest → **rejected**.
2. Read `Extension-Id`. Absent/blank/malformed → **rejected** with that reason.
3. Read `Extension-Name`, falling back to the JAR filename if absent or blank.
4. Read `Require-API-Version`; check it against core's version. Incompatible or malformed →
   **rejected** with a reason.
5. After every JAR has been scanned individually: group the **accepted** ones by `Extension-Id`. Any
   id shared by more than one → **abort start-up**, naming the id and every contending JAR path.

A rejection never stops the scan or affects any other JAR; an id collision among accepted JARs is the
one thing this process treats as fatal (see `Extension-Id` above for why).

## Error Messages

| Message shape | Meaning | Fix |
|---|---|---|
| `JAR has no manifest` | No `META-INF/MANIFEST.MF` | Ensure your build produces one (Maven does by default) |
| `Extension-Id manifest attribute is missing or blank` | No id | Add `Extension-Id` |
| `Extension-Id '…' does not match ^[a-z0-9-]+$` | Malformed id | Use lowercase letters, digits, hyphens only |
| `Incompatible Require-API-Version: extension requires '…', core provides '…'` | Version gate failed | Loosen the pattern or update the extension |
| `Two accepted extensions declare the same Extension-Id '…': <path>, <path>` | Id collision (start-up abort) | Rename one extension's id |

## Testing Your Manifest

```bash
unzip -p my-extension-1.0.0.jar META-INF/MANIFEST.MF
```

Or programmatically, against the manifest attribute names (`ExtensionManifest` declares the names;
`ExtensionScanner` owns accept/reject policy and does not call `ExtensionManifest.fromJarFile()` — see
`ExtensionScannerTest` for the exact rules exercised):

```java
import multiroom.api.extension.ExtensionManifest;

Attributes attrs = new JarFile(new File("my-extension-1.0.0.jar")).getManifest().getMainAttributes();
System.out.println(attrs.getValue(ExtensionManifest.ATTR_EXTENSION_ID));
System.out.println(attrs.getValue(ExtensionManifest.ATTR_EXTENSION_NAME));
System.out.println(attrs.getValue(ExtensionManifest.ATTR_REQUIRE_API_VERSION));
```

Check the inventory once the system is running, rather than the log — it is the authoritative,
structured record of what was discovered and why:

```bash
curl http://localhost:8080/actuator/extensions
```

## Related Documentation

- [Extension Development Guide](extension-development-guide.md)
- [Extension Authoring Contract](../../specs/019-extension-shared-classloader/contracts/extension-authoring-contract.md) — the authoritative contract this document summarizes
- [Semantic Versioning 2.0.0](https://semver.org/)
