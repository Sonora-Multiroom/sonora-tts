# Contract: Configuration Surface (`google-cloud` credentials)

**Feature**: `003-google-service-account-auth` | Binds under `multiroom.tts` in the host's
`multiroom.yml`. Everything in
[002's configuration contract](../../002-google-voice-selection/contracts/configuration.md) still
holds. This document changes only the credential.

## Provider entry: `multiroom.tts.providers[n]`, `type: google-cloud`

| Key | Type | Default | Meaning |
|---|---|---|---|
| `api-key` | string | — | A Google Cloud API key. **Exactly one of `api-key` and `service-account-key-file`** |
| `service-account-key-file` | path | — | **New.** The service account's JSON key file, as downloaded from Google. Read once at start-up; a replaced file takes effect on restart. A relative path is resolved against the host's working directory; `~` is not expanded |

A blank value counts as unset.

### Accepted shapes

```yaml
# 1. API key (unchanged; production today)
- name: google
  type: GOOGLE_CLOUD
  api-key: ${GOOGLE_TTS_API_KEY}
  voice: en-US-Neural2-C

# 2. Service account key file
- name: google
  type: google-cloud
  service-account-key-file: /home/tiger/.config/multiroom/sonora-tts-sa.json
  voice: en-US-Neural2-C
```

The file must be the service account's key, as Google issues it:

```json
{
  "type": "service_account",
  "project_id": "…",
  "private_key_id": "…",
  "private_key": "-----BEGIN PRIVATE KEY-----\n…\n-----END PRIVATE KEY-----\n",
  "client_email": "sonora-tts@<project>.iam.gserviceaccount.com",
  "token_uri": "https://oauth2.googleapis.com/token"
}
```

`type`, `client_email` and `private_key` are required. `private_key_id` and `token_uri` are used
when present. Other fields are ignored.

### Start-up faults

Each aborts host start-up with `multiroom-tts: provider '<name>' …`. None contacts Google.

| Condition | Message names |
|---|---|
| `google-cloud` with neither `api-key` nor `service-account-key-file` | "requires exactly one of api-key and service-account-key-file" |
| `google-cloud` with both | the same, "not both" |
| `service-account-key-file` on `openai`, `piper` or `local-http` | the key and the provider type, which would ignore it |
| The file does not exist, or cannot be read | the resolved absolute path |
| The file is not valid JSON | the path |
| `type` is not `service_account` (for example `authorized_user`, or an OAuth client file) | the path and the `type` found |
| `client_email` or `private_key` missing or blank | the path and the missing field |
| `private_key` cannot be used for signing (corrupt, not PKCS#8, not RSA) | the path. Never the key |
| `token_uri` is not an absolute URL, or is not `https` and not a loopback address | the path and the value |

## Run-time behaviour visible to the operator

| Situation | What the caller receives (`ErrorResponse`) | Remembered? |
|---|---|---|
| Google rejects the key (revoked key, disabled account, wrong key) | `PROVIDER_ERROR`, "Provider '<n>' could not obtain an access token: Google rejected the service account key (HTTP 400): Invalid JWT Signature. (invalid_grant)" | No: the next request tries again |
| Token service times out | `PROVIDER_TIMEOUT` | Yes, for `voice-catalogue.failure-backoff` |
| Token service unreachable, or HTTP 5xx | `PROVIDER_ERROR` | Yes |
| Token service HTTP 429 | `PROVIDER_RATE_LIMITED` | Yes |
| During that back-off | The same code, "… is in back-off after a recent failure: <reason>" | — |
| Synthesis denies a valid token (missing permission, 403) | `PROVIDER_ERROR`, Google's explanation after `HTTP 403:` (as with an API key) | No |
| Synthesis answers 401 (token expired early, clock skew) | Nothing, if the one retry with a new token succeeds; otherwise `PROVIDER_ERROR` with Google's explanation | No |

The back-off shares `multiroom.tts.voice-catalogue.failure-backoff` (default `60s`). No new
settings are added under `voice-catalogue`, and the token margin is not configurable.

Log output never contains the private key, the signed assertion or an access token, at any level.
The log names the entry and the service account's `client_email` when a token is obtained (`DEBUG`)
and when obtaining one fails (`WARN`).
