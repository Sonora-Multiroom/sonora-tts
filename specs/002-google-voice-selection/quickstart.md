# Quickstart: Validating Google Cloud Voice Selection

**Feature**: `002-google-voice-selection` | Contracts: [tts-rest-api.yaml](contracts/tts-rest-api.yaml),
[configuration.md](contracts/configuration.md) | Model: [data-model.md](data-model.md)

## 1. Automated gate (no key, no network)

```powershell
mvn verify
```

The suite must prove the following. None of it needs a Google key, per Principle III:

| Scenario | Where |
|---|---|
| Name grammar, canonical case, engine aliases, `es-419` | `GoogleVoiceNameTest`, `GoogleEngineTest`, `GoogleLanguageTest` |
| Resolution order, language/engine conflicts, short-name composition, range errors (US1-1..3, US2-1..7, US4-5) | `GoogleVoiceResolverTest` |
| TTL, back-off, single-flight, refetch-on-miss floor, stale snapshot on failed refresh | `GoogleVoiceCatalogueTest` (fixed `Clock`) |
| Error body surfaced, unparseable body keeps status, `INVALID_VOICE` with list, catalogue down → still synthesizes, pitch/rate sent only when set, shared time budget | `GoogleCloudTtsProviderTest` (WireMock) |
| Production config (SC-004) starts; every start-up fault in the configuration contract | `TtsPropertiesValidationTest` |
| Google-only fields on other types → 400 | `DefaultSettingsResolutionTest`, `TtsServiceTest` |
| New request fields reach `AnnounceCommand` | `TtsControllerTest` |
| Constructing the provider and resolving settings make **zero** HTTP calls (SC-005). The fully wired context is checked live in §2 | `GoogleCloudTtsProviderTest` (WireMock `verify(0, …)`) |
| Context wiring; `enabled=false` contributes nothing | `TtsAutoConfigurationTest` |
| Full vs short name share a cache entry; pitch/rate change the key; non-Google keys unchanged from 001 | `TtsServiceTest`, `CacheKeyTest` |
| Listing: 200 / unsupported 400 / unknown 400 / down 503 | `VoiceQueryServiceTest`, `TtsVoiceControllerTest`, `GoogleCloudTtsProviderTest` |

## 2. Smoke run against a local core (merge gate, part 2)

Prerequisite: the `multiroom-ai` checkout at `D:\projects-multiroom\multiroom-ai`, built, with a
`multiroom.yml` that has a `google-cloud` entry named `google` and a real `api-key`.

```powershell
mvn deploy -Plocal          # copies the JAR into the local core's extensions/
# start the core, then:
curl -s http://localhost:8080/actuator/extensions
```

**Expected**: `tts` is listed with version `0.1.1`, and its state is not `REJECTED`. The core's log
shows no request to `texttospeech.googleapis.com` during start-up.

## 3. Behavioural checks (local core, real key)

Replace `office` with any configured output.

```powershell
# SC-001 / US1-1: the incident, no language
curl -s -X POST localhost:8080/api/tts/speak -H "Content-Type: application/json" `
  -d '{"text":"Мені тринадцятий минало.","targetName":"office","targetType":"SINGLE_OUTPUT","providerName":"google","voice":"uk-UA-Chirp3-HD-Charon"}'
```

Expected: `202`, `cacheHit: false`. Uk-UA speech plays.

| Check | Request change | Expected |
|---|---|---|
| US2-4 cache identity | `"voice":"charon","engine":"chirp3-hd","language":"uk-ua"`, same text | `202`, `cacheHit: true` |
| US1-3 conflict | full name + `"language":"en-US"` | `400 INVALID_REQUEST` naming `en-US` and `uk-UA` |
| US3-1 typo | `"voice":"charn","engine":"chirp3-hd","language":"uk-UA"` | `400 INVALID_VOICE`, lists Chirp3-HD uk-UA voices including `Charon` |
| US3-4 no cross-engine search | `"voice":"D"` on an entry whose default engine is Chirp3-HD | `400 INVALID_VOICE` listing Chirp3-HD voices |
| US4-3/4 rate | same text as above + `"speakingRate":0.8` | `202`, `cacheHit: false`, audibly slower |
| US4-5 range | `"speakingRate":3` | `400 INVALID_REQUEST` stating `[0.25, 2.0]` |
| FR-020 | `"providerName":"piper-local","pitch":2` | `400 INVALID_REQUEST` naming `pitch` and `LOCAL_HTTP` |
| FR-001 | with the catalogue disabled as in step 4, `"voice":"en-US-Wavenet-Zz"` | `503 PROVIDER_ERROR` whose message has Google's explanation after `HTTP 400:` |

```powershell
# US5-1 listing
curl -s "localhost:8080/api/tts/providers/google/voices?language=uk-UA&engine=chirp3-hd"
# US5-2
curl -s "localhost:8080/api/tts/providers/piper-local/voices"      # 400 INVALID_REQUEST
```

## 4. Catalogue outage (SC-007)

Blocking the host would block synthesis too, so make only the catalogue fail: set
`multiroom.tts.voice-catalogue.fetch-timeout: 1ms` in the local core's config and restart. Every
catalogue fetch then times out. Restore the setting afterwards.

**Expected**: announcements with valid voices still return `202` and play. The log shows at most
one catalogue attempt per `failure-backoff` (60 s). `GET …/voices` returns
`503 VOICE_CATALOGUE_UNAVAILABLE`.

## 5. Production Raspberry Pi (Principle VII: audio parameters changed)

Deploying (`mvn deploy`), restarting the service and editing `~/multiroom.yml` on `multiroom.lan`
each need the user's explicit permission. Reading the config is pre-authorised.

1. **Before** deploying, confirm that the production config is still the SC-004 shape (a full voice,
   no `engine`):
   `ssh -o BatchMode=yes -o ConnectTimeout=10 tiger@multiroom.lan 'cat /home/tiger/multiroom.yml'`
2. With permission: `mvn deploy`, restart, then `GET /actuator/extensions` → `tts` `0.1.1`, not
   `REJECTED`. **No config edits.**
3. An English announcement through `google` plays as before (the first request re-synthesizes once:
   the cache key's case-folding is new, per research R7).
4. Run the SC-001 request from step 3 against `multiroom.lan:8080`. It plays on the first attempt.
5. A `speakingRate: 1.3` request is audibly faster than the same text without it, and a
   `pitch: -6` request through `google` (Neural2, which supports pitch) is audibly lower (SC-008).
