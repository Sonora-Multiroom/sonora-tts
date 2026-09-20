# Extension Development Guide

This guide explains how to develop extension modules for the multiroom-core audio system under the
shared-runtime extension model (feature `019-extension-shared-classloader`). It supersedes the
pre-019 version of this guide, which had extensions implement an `Extension` interface and bootstrap
their own private Spring context — that interface and mechanism are deleted.

The authoritative, task-linked contract is
[`specs/019-extension-shared-classloader/contracts/extension-authoring-contract.md`](../../specs/019-extension-shared-classloader/contracts/extension-authoring-contract.md).
This guide is the narrative walkthrough of it.

## Overview

Extensions are modular components that join core's own Spring context via ordinary Spring Boot
auto-configuration, rather than bootstrapping a private context of their own. They are packaged as JAR
files and discovered automatically at start-up from the `extensions/` directory — but discovery now
happens **before** Spring exists, and every accepted JAR shares one classloader and one context with
core (research.md R5, R12).

## Quick Start

### 1. Create a New Maven Project

Inherit `multiroom-extension-starter`, which declares the shared build gates (build-time dependency
bans, the manifest transformer that stamps `Extension-Id`/`Extension-Name`/`Extension-Version`/
`Require-API-Version`, and the shade transformer that keeps
`META-INF/spring/...AutoConfiguration.imports` intact):

```xml
<project>
    <parent>
        <groupId>ai.multiroom</groupId>
        <artifactId>multiroom-extension-starter</artifactId>
        <version>${multiroom.version}</version>
        <relativePath>../multiroom-extension-starter</relativePath>
    </parent>

    <artifactId>my-extension</artifactId>
    <packaging>jar</packaging>

    <properties>
        <extension.name>My Extension</extension.name>
        <extension.id>my-extension</extension.id>
    </properties>

    <dependencies>
        <!-- Provided by core at runtime once this JAR shares its classpath. -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
            <scope>provided</scope>
        </dependency>
    </dependencies>
</project>
```

`extension.id` must be unique across every extension you deploy alongside this one, and becomes the
configuration namespace (`multiroom.my-extension.*`) — see
[Extension Manifest Format](extension-manifest-format.md).

### 2. Write an Auto-Configuration, Not an Extension Class

There is no interface to implement. An extension contributes to core's context the same way any
Spring Boot auto-configuration does:

```java
package com.example.myextension;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;

@AutoConfiguration
@ConditionalOnProperty(name = "multiroom.my-extension.enabled", havingValue = "true", matchIfMissing = true)
@ComponentScan("com.example.myextension")
public class MyExtensionAutoConfiguration {

    // @Bean methods for anything the extension needs to contribute directly.
}
```

`matchIfMissing = true` matters: it means an upgrade never silently disables a working extension
just because a new version forgot to set the property (FR-008).

Name the class in the entry-point resource:

```
src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

```
com.example.myextension.MyExtensionAutoConfiguration
```

Ordinary `@Component`-annotated classes picked up by the `@ComponentScan` above are beans in core's
own context, exactly like core's own components — there is no child context, no `ExtensionContext`
to fetch services from.

### 3. Obtain Core Services

Ordinary constructor injection — the same as any Spring bean:

```java
@Component
@RequiredArgsConstructor
public class MyExtensionService {

    private final RouteService routeService;
    private final DeviceQueryService deviceQueryService;
    private final PlaybackControlService playbackControlService;

    // ...
}
```

### 4. React to Core Events

`@EventListener`, directly on the consuming bean — no bridging component, no cross-context forwarding:

```java
@Component
public class MyExtensionEventHandler {

    @EventListener
    public void onRouteCreated(RouteCreatedEvent event) {
        // ...
    }
}
```

### 5. Build and Deploy

```bash
mvn clean package
cp target/my-extension-1.0.0.jar /path/to/multiroom/extensions/
```

Restart core. Check what was discovered:

```bash
curl http://localhost:8080/actuator/extensions
```

## Extension Status, Not Lifecycle States

There is no `DISCOVERED → LOADING → LOADED → STARTING → STARTED` state machine, and no per-extension
classloader to fail during "loading". Every accepted extension's code runs in the same start-up as
core's own; a fault while your auto-configuration is being processed **aborts start-up**, naming your
extension (FR-018) — the same as a fault in any of core's own beans.

The inventory instead reports one of four statuses, settled at two different times:

| Status | Settled | Meaning |
|---|---|---|
| `ACTIVE` | After context refresh | Accepted and contributed at least one bean |
| `REJECTED` | At scan, before Spring exists | Excluded before loading — bad manifest, incompatible version, malformed id. Never fatal |
| `DISABLED` | After context refresh | Accepted, but `multiroom.<id>.enabled` resolved to `false` |
| `INERT` | After context refresh | Accepted and enabled, but contributed no bean — present but doing nothing |

## Network and External Resources: Never During Bean Construction

This is the rule most likely to be got wrong, and the one FR-019 exists to enforce. Anything that
fails **during bean construction or `@PostConstruct`** aborts start-up — correct for an
operator-fixable fault (a bad config value, a missing bean), wrong for a broker or device that simply
isn't reachable yet.

If your extension talks to something external, bring that connection up in `SmartLifecycle.start()`,
not in a constructor, and catch failures there rather than letting them propagate:

```java
@Component
public class MyExtensionLifecycle implements SmartLifecycle {

    private volatile boolean running;
    private volatile ConnectionState connectionState = ConnectionState.NOT_APPLICABLE;

    @Override
    public void start() {
        try {
            // bring up the connection
            connectionState = ConnectionState.CONNECTED;
        } catch (Exception e) {
            log.error("Failed to connect; will retry unattended", e);
            connectionState = ConnectionState.DISCONNECTED;
        } finally {
            running = true;
        }
    }

    @Override
    public void stop() {
        // tear down
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
```

An unreachable broker, an empty network, or no discoverable devices at start-up **must not** abort —
the extension loads, reports `DISCONNECTED`, and recovers unattended when the dependency returns. See
`multiroom-mqtt/.../MqttConnectionManager.java`, `multiroom-dlna/.../DlnaLifecycle.java`, and
`multiroom-chromecast/.../ChromecastLifecycle.java` for three real implementations of this contract.

### Reporting Connection State

If your extension has an external dependency worth reporting, inject
`multiroom.api.extension.ExtensionInventory` and push your state into your own record whenever it
changes:

```java
extensionInventory.byId("my-extension")
        .ifPresent(ext -> ext.connectionState().set(ConnectionState.CONNECTED));
```

This is safe from any thread — the cell is designed for exactly this (one writer, concurrent readers).

## Configuration

An extension **must not** ship `application.yml` or `application.properties` at its JAR root (FR-024)
— extension JARs share core's classpath, so a root config file would shadow core's own. Bind your
settings under your own namespace instead:

```java
@ConfigurationProperties(prefix = "multiroom.my-extension")
public class MyExtensionProperties {
    private boolean enabled = true;
    private int port = 8080;
    // getters/setters
}
```

```yaml
multiroom:
  my-extension:
    enabled: true
    port: 8080
```

If you need to contribute low-precedence defaults that aren't simple `@ConfigurationProperties`
fields (springdoc settings, for example), use an `EnvironmentPostProcessor` registered in
`META-INF/spring.factories` rather than a YAML file — see `multiroom-rest`'s
`SpringDocEnvironmentPostProcessor` for a worked example.

## Reading the Extension Inventory

Inject `multiroom.api.extension.ExtensionInventory` — a read-only view over core's registry, published
in `multiroom-api` specifically so an extension can render it without depending on `multiroom-core`
(extensions are banned from that dependency by `maven-enforcer-plugin`). `multiroom-rest` uses this
for its published `GET /api/v2/extensions`; core keeps its own operator-facing
`GET /actuator/extensions`. The two report the same facts but are not required to serialize
identically — different audiences, different stability promises.

## Version Compatibility

```
Require-API-Version: 0.1.x
```

See [Extension Manifest Format](extension-manifest-format.md) for the full pattern table.

## Best Practices

1. Depend on `multiroom-api` (and anything core provides) with `provided` scope — bundling it defeats
   the point of a shared classpath and bloats your JAR.
2. Never do network or file I/O in a constructor or `@PostConstruct` — use `SmartLifecycle.start()`.
3. Pick an `Extension-Id` no shipped extension already uses, and never derive it from your class or
   package name after the fact — decide it once, since renaming it later changes your configuration
   namespace and enable switch for every existing deployment.
4. Log with SLF4J; a caught-and-logged failure in `SmartLifecycle.start()` is the correct response to
   an unreachable dependency, not a rethrown exception.
5. Run your module's own test suite unmodified — it is your evidence that a refactor changed nothing
   observable (the same discipline this feature's own migration followed, FR-017).

## Example Extensions

- `multiroom-rest` — the control API extension; the largest and most complete worked example
  (auto-configuration, a `SmartLifecycle`-free case, published `/api/v2/**` contract)
- `multiroom-mqtt`, `multiroom-dlna`, `multiroom-chromecast` — each demonstrates the
  `SmartLifecycle` + connection-state-reporting pattern for a different external dependency

## Troubleshooting

### Extension Not Loading

1. Check the JAR is in `extensions/` and query `curl http://localhost:8080/actuator/extensions` —
   every discovered JAR appears there, `REJECTED` ones included with a reason.
2. Confirm the manifest has `Extension-Id`, `Extension-Name`, `Extension-Version`, and
   `Require-API-Version`.
3. Confirm your `.imports` file names the exact fully-qualified auto-configuration class.

### Services Not Available / Beans Not Found

1. Confirm your `@ComponentScan` base package actually covers the bean in question.
2. Confirm `multiroom.<id>.enabled` isn't resolving to `false` (inventory status `DISABLED`).
3. If the inventory reports `INERT`, your auto-configuration ran but contributed no bean — check your
   `@ConditionalOnProperty`/`@ConditionalOnBean` conditions.

### Configuration Not Found

1. Verify the property prefix matches your `Extension-Id`: `multiroom.<id>.*`.
2. Confirm you didn't ship a root `application.yml` (FR-024) — check for it in the built JAR.
