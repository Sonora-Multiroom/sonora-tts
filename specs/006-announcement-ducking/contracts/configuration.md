# Contract: Configuration (006)

Additive. Every existing configuration starts unchanged and gets the new default behaviour
(announcements lower the room instead of stopping it).

## `multiroom.tts.playback`

```yaml
multiroom:
  tts:
    playback:
      default-mode: duck-others   # duck-others | mix (case-insensitive); optional
```

| Key | Default | Meaning |
|---|---|---|
| `playback.default-mode` | `duck-others` | Playback mode of every announcement whose request names none. One value for the whole extension; there is no per-target default |

Start-up aborts on any other value (including `replace`):

```text
multiroom-tts: playback.default-mode 'replace' is not one of duck-others, mix
```

## Host settings that govern the result (not this extension's)

Set in the host's `multiroom.yml`; listed here so the operator knows where to look. This extension
neither reads nor overrides them.

| Host key | Default | Effect on announcements |
|---|---|---|
| `audio.mixing.ducking.level-db` | `-15.0` | How far the music drops under a `duck-others` announcement |
| `audio.mixing.ducking.fade-millis` | `150` | Lowering and restoring ramp |
| `audio.mixing.max-routes-per-output` | `4` | An announcement that would exceed it on any target output is refused (logged `TTS_PLAYBACK_REFUSED`, counted as a failed playback, dropped) |

## Manifest

`Require-API-Version: [0.1.21,0.2.0)` (from `multiroom.require_api_version` 0.1.21). A host without
023 refuses to load the JAR. Upgrade the host first.
