# MQTT Topics: TTS Extension

**Feature**: `001-tts-extension`

**Date**: 2026-05-30 | **Status revised**: 2026-09-13

> ## ⚠️ DEFERRED — not implemented by this feature, and the mechanism is an OPEN DECISION
>
> **No task in [tasks.md](../tasks.md) depends on anything in this document.** Spec FR-014 requires
> *at least one* external trigger and the HTTP endpoints in
> [tts-rest-api.yaml](tts-rest-api.yaml) satisfy it. The topic design below is kept because it is
> sound, not because it is in scope.
>
> **What makes this hard.** `019-extension-shared-classloader` solves extension→core (dependency
> injection) and core→extension (`@EventListener`). Triggering TTS from an MQTT topic is
> **extension→extension**, which it does not address — and the three ways to address it trade off
> against each other rather than one being plainly right. **No choice has been made.**

### Option A — a contribution record in `multiroom-api`

`multiroom-api` gains one record mirroring the signature `MqttClient.subscribe` already has:

```java
public record MqttTopicSubscription(String topicFilter, BiConsumer<String, byte[]> handler) {}
```

`multiroom-tts` declares one as a `@Bean` and never asks whether MQTT is present; `MqttCommandHandler`
injects `List<MqttTopicSubscription>` (empty when nothing contributes) and subscribes each alongside
its own six topics. Order-insensitive, because all bean *definitions* are registered before any bean
is instantiated. It is the same idiom core already uses for `InputEndpointResolver`.

**Cost**: `multiroom-api` grows a type that has nothing to do with audio, and sets the precedent that
it grows once per protocol any extension wants to be pluggable over. This is the objection
[research.md](../research.md) §6 originally raised against "Option A — typed hub interfaces in
`multiroom-api`", and it applies to this smaller version too.

### Option B — an optional `provided` dependency on `multiroom-mqtt`

`multiroom-tts` declares `multiroom-mqtt` as `provided` + `optional` and guards its use with a
classpath condition:

```java
@Configuration
@ConditionalOnClass(MqttClient.class)        // absent JAR -> absent class -> config backs off
static class TtsMqttSupport {
    @EventListener(MqttConnectedEvent.class)  // fires again on every reconnect
    void subscribe() { mqttClient.subscribe(prefix + "/tts/speak", this::handle); }
}
```

**This needs no new type anywhere.** `multiroom.mqtt.client.MqttClient` is already a DIP abstraction
and `MqttConnectedEvent` is already published on connect and reconnect
(`MqttConnectionManager.java:84`); `MqttCommandHandler.onMqttConnected` is the existing precedent for
exactly this pattern. Under the shared classloader the condition is real: if `multiroom-mqtt.jar` is
not deployed, its classes are genuinely not on the classpath.

**Cost**: an extension-to-extension Maven dependency, which also couples the reactor build order.

**What argues for it**: `019-extension-shared-classloader` FR-007 already says an extension "MUST be
able to declare that it activates only when a named capability, **class**, or configuration setting is
present, and that it activates **after another named extension**" — that is `@ConditionalOnClass` plus
`@AutoConfiguration(after = …)`, and its T036 tests exactly that behaviour. The platform
anticipated optional sibling dependencies and chose this mechanism.

**What argues against it**: constitution VIII says extension modules "depend on the public API module
**only**". Read literally that forbids Option B; read against FR-007 it does not, since naming another
extension presupposes being able to see it. **These two documents disagree, and settling that is a
maintainer decision** — not something to resolve by whichever spec a reader opens first.

Note that the *build* already permits Option B: the `bannedDependencies` rule
`019-extension-shared-classloader` adds to `multiroom-extension-starter` bans only
`ai.multiroom:multiroom-core`, so a sibling dependency compiles today. The constitution's text is the
only thing standing against it, which is itself a reason to think the text is what is out of step.

#### The amendment that would settle it

Choosing B should mean **amending constitution VIII deliberately**, not quietly living with the
conflict. This is not a TTS question — any future extension that wants to be pluggable over another's
transport hits the same wall, so the amendment belongs in the constitution rather than in a per-feature
waiver.

The clause is one bullet under VIII:

> **Dependency Direction**: Extension modules depend on the public API module only; core MUST NEVER
> depend on extensions

A narrow replacement, keeping every property the rule actually protects:

> **Dependency Direction**: Core MUST NEVER depend on an extension module. Extension modules depend on
> the public API module; an extension MAY additionally depend on another extension module only when
> **all** of the following hold:
> (a) the dependency is declared `optional` and `provided` scope, so it is never transitive and never
> bundled;
> (b) the depending extension guards its use behind a runtime condition and remains fully functional,
> minus that one capability, when the dependency is absent;
> (c) the dependency is one-way — the depended-on extension MUST NOT reference the depender;
> (d) no cycle is introduced.
> Extension modules MUST NOT access core internals under any circumstances.

**Why this keeps the rule's purpose intact.** The "only" wording exists so that any distributable can
be dropped in and work on its own, and so that core stays protocol-agnostic. Condition (b) preserves
the first property exactly — a TTS JAR deployed without MQTT still serves every HTTP endpoint, it
simply has no MQTT trigger — and nothing here touches core's direction at all. What the amendment
removes is only the over-broad phrasing, not the discipline.

**Enforcement is nearly free**: (a) and (d) are Maven's own behaviour — a reactor cycle is a build
error and `optional`/`provided` are declared scopes; (b) is one `ApplicationContextRunner` test
asserting the module starts without the optional class present; (c) is review, or a
`bannedDependencies` rule on the depended-on module.

**Versioning**: this is permissive — nothing that complies today stops complying — so it reads as a
**MINOR** bump under the constitution's own increment rules ("materially expanded guidance"), 1.4.0 →
1.5.0, rather than a MAJOR redefinition. The maintainer decides. Use `/speckit-constitution`; the
governance section also asks for the dependent templates to be updated in the same change.

### Option C — do not build it

Home Assistant calls REST endpoints natively, FR-014 is already satisfied by HTTP, and no requirement
in [spec.md](../spec.md) needs MQTT. The cost of Option C is only the topic interface below.

### Recommendation

**C, then B.** MQTT triggering buys little that HTTP does not already provide here, so the cheapest
resolution of the trade-off is not to take it. If it is wanted later, B costs one `pom.xml` entry and
one nested configuration class, adds nothing to `multiroom-api`, and uses the mechanism 019 specced
for this case — but it should come **with** the constitution VIII amendment above, as one deliberate
change, not as a per-feature exception. An option that requires quietly ignoring a MUST is not
actually cheaper than the option that changes the MUST on purpose.

If Option A is chosen instead, keep the record protocol-shaped (a topic filter and a handler) rather
than TTS-shaped: an `multiroom-api` type naming TTS concepts would put one extension's vocabulary in
everyone's contract.

> **Historical note.** The original text here said "no dependency on `multiroom-mqtt` is required —
> the MQTT integration uses Spring application events to bridge the two extensions". That was written
> for a tree where each extension had its own Spring context and events could not cross between them.
> Under one shared context the events part is finally true; what is still missing is a shared *type*,
> which is what all three options above are really about.

---

## Topic Reference

### Subscribe (incoming commands)

#### `multiroom/tts/speak`

Trigger a TTS announcement. Payload is a JSON object.

**Payload schema**:
```json
{
  "text": "Dinner is ready",
  "targetName": "living-room",
  "targetType": "SINGLE_OUTPUT",
  "providerName": "openai",
  "voice": "alloy",
  "language": "en-US"
}
```

| Field | Required | Notes |
|-------|----------|-------|
| `text` | Yes | 1–500 chars (configurable), not blank |
| `targetName` | Yes | Output or output group name |
| `targetType` | Yes | `SINGLE_OUTPUT` or `OUTPUT_GROUP` (the values of `multiroom.api.model.TargetType`) |
| `providerName` | No | Omit to use default provider |
| `voice` | No | Provider-specific voice override |
| `language` | No | BCP 47 language tag override |

**QoS**: 1 (at least once)  
**Retained**: No

---

#### `multiroom/tts/cache/clear`

Clear the audio cache. Payload is optional.

**Payload schema (optional)**:
```json
{
  "providerName": "openai"
}
```

If payload is absent or `providerName` is null, clears the entire cache.

**QoS**: 1  
**Retained**: No

---

### Publish (status notifications)

#### `multiroom/tts/status`

Published after each announcement completes or fails.

**Payload schema**:
```json
{
  "announcementId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "COMPLETED",
  "targetName": "living-room",
  "targetType": "SINGLE_OUTPUT",
  "providerName": "openai",
  "cacheHit": true,
  "durationMs": 2340
}
```

| `status` value | Meaning |
|---------------|---------|
| `COMPLETED` | Announcement played successfully |
| `FAILED` | Synthesis or playback error |
| `SKIPPED` | Extension disabled mid-playback; announcement discarded |

**QoS**: 1  
**Retained**: No

---

## Home Assistant Automation Example

```yaml
# Announce motion detection in living room via multiroom TTS
automation:
  alias: "Announce Motion - Living Room"
  trigger:
    platform: state
    entity_id: binary_sensor.living_room_motion
    to: "on"
  action:
    service: mqtt.publish
    data:
      topic: multiroom/tts/speak
      payload: >
        {
          "text": "Motion detected in living room",
          "targetName": "all-rooms",
          "targetType": "OUTPUT_GROUP"
        }
      qos: 1
```
