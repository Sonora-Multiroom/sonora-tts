# Quickstart: Validating Service Account Authentication

**Feature**: `003-google-service-account-auth` | Settings: [contracts/configuration.md](contracts/configuration.md)
| Entities: [data-model.md](data-model.md)

## 1. Automated gate (no key, no network)

```powershell
mvn verify
```

Every test generates its own RSA key pair and writes its key file into a JUnit `@TempDir`. No real
key is committed, and the token service is a WireMock stub reached through a loopback `token_uri`.

| What is proven | Where |
|---|---|
| Start-up faults: neither/both credentials, key file on a non-Google type, missing file, bad JSON, wrong `type`, missing `client_email`/`private_key`, unusable key, bad `token_uri`; the message names the entry and the absolute path | `TtsPropertiesValidationTest`, `ServiceAccountKeyTest`, `TtsAutoConfigurationTest` |
| A valid key file starts the context with zero HTTP requests | `TtsAutoConfigurationTest` + WireMock request count |
| The assertion: header, claims, `aud` = token address, RS256 signature verifiable with the public key | `ServiceAccountAssertionTest` |
| Reuse, renewal 5 min before expiry, at most 2 fetches in a simulated hour (injected `Clock`) | `GoogleAccessTokenCacheTest` |
| N concurrent callers make one token request | `GoogleAccessTokenCacheTest` |
| Back-off after a timeout / 5xx; none after a 400; a valid token still used during back-off | `GoogleAccessTokenCacheTest` |
| OAuth error bodies (`error`, `error_description`) become the explanation | `GoogleErrorBodyTest` |
| `Authorization: Bearer` on synthesis and on `voices`; no `?key=`; token-first order; one retry on 401, none on 403 | `GoogleCloudTtsProviderTest` |
| API-key entries: request shape unchanged | `GoogleCloudTtsProviderTest` (existing cases) |
| Same text and voice: same cache key for both credential kinds | `TtsServiceTest` |
| No private key, assertion or token in any log line or exception message | `GoogleCloudTtsProviderTest` (captured log + messages) |
| No new runtime dependency | `mvn dependency:tree -Dscope=runtime` shows no new artifact; the JAR has no `com/google/` classes |

## 2. Smoke run against a local core (merge gate, part 2)

Prerequisite: the `multiroom-ai` checkout, built, with a `multiroom.yml` whose `google-cloud` entry
uses `service-account-key-file` pointing at a real key file (see
[docs/google-cloud-tts-setup.md](../../docs/google-cloud-tts-setup.md)).

```powershell
mvn deploy -Plocal
# start the core, then:
curl -s http://localhost:8080/actuator/extensions
```

**Expected**: `tts` at version `0.1.2`, not `REJECTED`. Nothing is sent to `oauth2.googleapis.com`
during start-up (the `DEBUG` log line "obtained an access token" appears only after step 3).

## 3. Behavioural checks (local core, real key)

```powershell
curl -s -X POST localhost:8080/api/tts/speak -H "Content-Type: application/json" `
  -d '{"text":"Service account check.","targetName":"office","targetType":"SINGLE_OUTPUT","providerName":"google"}'
```

| Check | Action | Expected |
|---|---|---|
| Story 1: plays | the request above | `202`, `cacheHit: false`, speech plays |
| Story 1: reuse | a second request with new text within the hour | `202`; no second "obtained an access token" line |
| Story 1: catalogue | `GET /api/tts/providers/google/voices?language=en-US` | `200`, voices listed |
| Story 1: cache identity | the same text through an API-key entry with the same name | `cacheHit: true` |
| Story 2: both set | add `api-key` beside the file, restart | start-up aborts: "exactly one of api-key and service-account-key-file" |
| Story 2: wrong file | point at an OAuth client JSON, restart | start-up aborts naming the path and that it is not a service account key |
| Story 2: relative path | a relative path that does not resolve, restart | the message shows the absolute path it tried |
| Story 3: rejected | disable the service account in IAM, new text | `503 PROVIDER_ERROR`, message contains Google's explanation. Re-enable it; the next request plays with no restart |
| SC-008 | `grep -E "BEGIN PRIVATE KEY|ya29\.|eyJ" <core log>` | no match |

## 4. Roles (settles research R13)

On the first request of step 3, note Google's answer before granting any role:

- plays → the guide says no role is needed in the key's own project
- `403` naming `serviceusage.services.use` → grant **Service Usage Consumer**, retry, and the guide
  says so

Record the result in `docs/google-cloud-tts-setup.md`.

## 5. Production Raspberry Pi

Audio and playback are unchanged, so Principle VII's Pi check is only a confirmation. Production
keeps its API key (SC-002):

1. `mvn deploy` (a production action: confirm with the user first), restart with permission.
2. `GET /actuator/extensions` on `multiroom.lan`: `tts` `0.1.2`, not `REJECTED`.
3. One announcement through the existing `google` entry: plays, with no configuration edit.
