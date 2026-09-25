# Data Model: Google Cloud Service Account Authentication

**Feature**: `003-google-service-account-auth` | **Date**: 2026-09-25

Nothing here is persisted. Every entity lives in memory for one `google-cloud` entry, from start-up
to shutdown. The audio cache and its key are unchanged.

## Entities

### Provider entry addition (`TtsProviderConfig`)

| Field | YAML key | Type | Default | Rule |
|---|---|---|---|---|
| `serviceAccountKeyFile` | `service-account-key-file` | `String` (a path) | none | `google-cloud` only. Exactly one of this and `api-key` per `google-cloud` entry. Resolved against the working directory when not absolute |

`apiKey`'s Javadoc changes from "Required for `OPENAI` and `GOOGLE_CLOUD`" to "Required for
`OPENAI`. For `GOOGLE_CLOUD`, exactly one of this and `serviceAccountKeyFile`".

### ServiceAccountKey (record, `provider.cloud.google`): the spec's *Service Account Key*

| Field | Type | Source in the file | Rule |
|---|---|---|---|
| `path` | `Path` | the setting | Absolute (resolved); used in every message about the file |
| `clientEmail` | `String` | `client_email` | Required, non-blank. The JWT's `iss` |
| `privateKey` | `PrivateKey` (RSA) | `private_key` | Required. PKCS#8 PEM; must pass `Signature.initSign` |
| `privateKeyId` | `String` or null | `private_key_id` | Optional. The JWT header's `kid` |
| `tokenUri` | `URI` | `token_uri` | Optional; default `https://oauth2.googleapis.com/token`. `https`, or `http` on a loopback host |

Also required: `"type": "service_account"`. `project_id` and other fields are read past and
ignored.

- **Created by** `ServiceAccountKey.load(String providerName, Path file)`, once per entry, while `TtsAutoConfiguration`
  builds the provider. It reads the file and does no network work.
- **Failure**: `IllegalStateException("multiroom-tts: provider '<name>' has service-account-key-file
  '<absolute path>' …")`, with one reason per rule: does not exist / cannot be read / is not valid
  JSON / has type '<t>', not a service account key / has no client_email / has no private_key /
  holds a private key that cannot be used for signing / has token_uri '<u>', which is not an
  absolute URL, or is neither `https` nor a loopback address. `load` takes the entry name so the
  message can name it.
- **`toString()`**: `ServiceAccountKey[clientEmail=…, path=…]`. Never the key.

### ServiceAccountAssertion (`provider.cloud.google`, pure)

Builds the signed JWT (research R1) from a `ServiceAccountKey`, a scope and a `Clock`. No I/O. One
public method: `String sign()`. Signing uses a new `Signature` instance per call, so it is safe
for concurrent use.

### AccessToken (record, private to `GoogleAccessTokenCache`): the spec's *Access Token*

| Field | Type | Meaning |
|---|---|---|
| `value` | `String` | The bearer token |
| `renewAt` | `Instant` | `sentAt + expires_in − margin` (margin: 5 min, or half the lifetime if shorter than 10 min) |
| `expiresAt` | `Instant` | `sentAt + expires_in`: until then the token is still *valid*, even after `renewAt` |

`toString()` omits `value`.

### GoogleTokenExchange (`provider.cloud.google`)

Sends one assertion to `tokenUri` and returns `(value, expiresIn)`, or throws a
`TokenFailure` carrying the classification from research R6:

```text
sealed TokenFailure (extends TtsException, so it already carries a code)
├── Unavailable   timeout, I/O failure, 5xx, 429           → remembered for failure-backoff
└── Rejected      other 4xx, unusable 200                  → never remembered
```

Its HTTP timeout is the budget it is given.

### GoogleAccessTokenCache (one per service-account entry)

| State | Type | Meaning |
|---|---|---|
| `current` | `volatile AccessToken` | Null until the first fetch |
| `failedAt` | `volatile Instant` | Set by an `Unavailable` failure; cleared by a success |
| `failureReason` | `volatile String` | The message repeated during the back-off |
| `fetchLock` | `ReentrantLock` | Single-flight fetches |

Operations:

- `String token(Duration budget)`: `current` if `now < renewAt`; else, if in back-off, return
  `current` while `now < expiresAt` and throw at once otherwise; else take `fetchLock` within
  `budget`, re-check both, and fetch. A lock wait that runs out returns `current` if
  `now < expiresAt`, and otherwise throws `PROVIDER_TIMEOUT` **without** starting a back-off (the
  holder's fetch records its own outcome; `failedAt` is written only under the lock). When that fetch fails, the
  failure is recorded as usual (back-off for `Unavailable`), and `current` is still returned if
  `now < expiresAt`; only without a valid token does the call throw.
- `void discard(String rejected)`: clears `current` only if it still holds `rejected`.

State transitions:

```text
            first need / renewAt passed
 [empty] ───────────────────────────────▶ fetching ──success──▶ [valid] ──renewAt passed──▶ fetching
    ▲                                        │                     │
    │                           Rejected ────┤                     └──401 from Google──▶ discard ──▶ [empty]
    └────────────────────────────────────────┤
                                 Unavailable └──▶ [back-off] ──failure-backoff elapsed──▶ [empty]
```

A token that is still valid (`now < expiresAt`, not discarded) is returned in every state, the
back-off included: a renewal that fails inside the margin costs nothing while the old token lasts.
`discard` after a 401 removes the token, so a rejected token is never reused this way.

### GoogleCredential (sealed, `provider.cloud.google`): the spec's *Credential*

```text
sealed GoogleCredential
├── ApiKey(String key)                         appends ?key=<key>; retries nothing
└── ServiceAccount(GoogleAccessTokenCache)     sets Authorization: Bearer <token>; retries one 401
```

Built once per entry in `GoogleCloudTtsProvider`'s constructor from the config: an API key when
`api-key` is set, otherwise a `ServiceAccount` around the loaded `ServiceAccountKey`.

## Synthesis (Google, `synthesize`: cache miss only), with this feature's changes marked

```text
deadline = now + timeout-seconds
1. NEW  token = credential.prepare(remaining)            # service account: token first (R8);
                                                         # failure → the announcement fails here
2.      voice check against the catalogue                # a catalogue fetch calls prepare itself
                                                         # (a cache hit right after step 1)
3.      token = credential.prepare(remaining)            # a cache read; picks up a token renewed
                                                         # by a 401 on the catalogue fetch
        POST text:synthesize                             # authorised with `token`
4. NEW  on 401 (service account only): discard(token), token = prepare(remaining), resend once
5.      map the response exactly as today
```

`prepare` returns nothing for an API key. The catalogue's fetcher and `listVoices` go through the
same authorised send with the same one-retry rule. In the listing path no token is obtained ahead,
so a token failure there fails the catalogue fetch and starts the catalogue's back-off, as 002
defines.

## Validation rules (start-up)

| Rule | Where |
|---|---|
| `google-cloud`: exactly one of `api-key`, `service-account-key-file` (blank counts as unset) | `TtsProperties.validate()` |
| Any other type: `service-account-key-file` set → fault | `TtsProperties.validate()` |
| `openai`: `api-key` required (unchanged) | `TtsProperties.validate()` |
| The key file's existence, readability, shape and signing key (above) | `ServiceAccountKey.load`, at provider construction |

## Cache key

Unchanged. The provider name is already a component, so an API-key entry and a service-account entry
never share an entry by accident, and switching an entry's credential under the same name keeps its
cached audio (FR-014).
