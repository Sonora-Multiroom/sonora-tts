# Troubleshooting Extension Loading

Diagnoses issues discovering or loading extensions under the shared-runtime model (feature
`019-extension-shared-classloader`). The single most useful diagnostic is the live inventory:

```bash
curl http://localhost:8080/actuator/extensions
```

Every JAR the scanner found is listed here, `REJECTED` ones included with a reason — check this
before digging into logs.

## Common Issues

### 1. Extension Not in the Inventory At All

**Possible causes**:
- The JAR is not in the configured `extensions/` directory (`extensionsDirectory` in the inventory
  response shows exactly where core looked).
- The file doesn't have a `.jar` extension.
- `multiroom.extensions.enabled` is `false` — the inventory then reports `loadingEnabled: false` with
  an empty list, and no extension is scanned at all.

**Solution**: Confirm the path and the enable flag, then restart.

### 2. Listed with `status: REJECTED`

**Symptom**: The extension appears in the inventory, but `status` is `REJECTED` and `rejectionReason`
explains why. This is never fatal — the rest of the system starts normally.

**Common reasons**:
- `JAR has no manifest` — the build didn't produce `META-INF/MANIFEST.MF`.
- `Extension-Id manifest attribute is missing or blank` — add `Extension-Id`.
- `Extension-Id '…' does not match ^[a-z0-9-]+$` — use lowercase letters, digits, hyphens only.
- `Require-API-Version manifest attribute is missing or blank` — add it.
- `Incompatible Require-API-Version: extension requires '…', core provides '…'` — loosen the pattern
  or rebuild against the running core's version.
- `Corrupt or unreadable JAR: …` — the file isn't a valid JAR (a partial copy, wrong file type).

### 3. Start-Up Aborted Naming an Extension

**Symptom**: Core doesn't start at all; the log names an extension (id, name, version) and a cause.

**Meaning**: Unlike a rejection, this is a fault in an **accepted** extension's own code —
`multiroom.core.extension.ExtensionFailureReporter` resolved a bean-creation failure (or an ambiguous
request-mapping collision between two extensions) back to the JAR(s) responsible. Fix the named cause
(a bad `@ConfigurationProperties` value is the most common) and restart. Two accepted JARs sharing an
`Extension-Id` produce the same shape of abort, naming the contended id and both JAR paths.

### 4. Listed but `status: DISABLED`

**Meaning**: The extension was accepted, but its own `multiroom.<id>.enabled` property resolved to
`false`. This is deliberate, independent of the other three extensions — it does not disturb them.

**Solution**: Set `multiroom.<id>.enabled=true` (or remove the override) if this wasn't intended.

### 5. Listed but `status: INERT`

**Meaning**: Accepted and enabled, but the auto-configuration contributed no bean at all — "loaded but
doing nothing," as distinct from switched off.

**Solution**: Check your `@ConditionalOnProperty`/`@ConditionalOnBean` conditions and your
`@ComponentScan` base package; something is preventing any bean from registering.

### 6. `ClassNotFoundException` / `NoClassDefFoundError` at Run Time

**Possible causes**:
- A library your extension needs isn't bundled and isn't provided by core — check your `pom.xml`
  dependency scopes; anything not `provided` (i.e. not already on core's classpath) must be bundled.
- Your extension imports a `multiroom.core.*` class — banned by `maven-enforcer-plugin` at build time
  for every extension, and will not resolve at run time either, since core never depends back on an
  extension.

**Solution**: Only import `multiroom.api.*` from core's side; bundle everything else genuinely
extension-specific.

### 7. Extension Has an External Dependency and Reports `DISCONNECTED`

**This is not a fault.** An unreachable MQTT broker, an empty network, or no discoverable devices is
the expected non-fatal case (FR-019) — the extension loads as `ACTIVE`/`REJECTED` per the scan, and
separately reports its live connection health as `DISCONNECTED` until the dependency returns, with no
restart required.

## Debugging Tools

- **The inventory itself**: `curl http://localhost:8080/actuator/extensions` — always check this
  first; it distinguishes every failure mode above.
- **Debug logging**: `logging.level.multiroom.core.extension=DEBUG` in `application.yml`.
- **Inspect the JAR**: `jar tf your-extension.jar` to confirm the manifest and the
  `META-INF/spring/...AutoConfiguration.imports` resource are both present and correctly named.
