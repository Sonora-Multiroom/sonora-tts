# Research: Google Cloud Service Account Authentication

**Feature**: `003-google-service-account-auth` | **Date**: 2026-09-25

The spec's two Clarifications sessions settled the behaviour. This document records the technical
decisions the plan rests on. Sources: Google's *Using OAuth 2.0 for Server to Server Applications*
(fetched 2026-09-25), `docs/future/google-cloud-service-account-auth.md`, the live Gemini tests of
2026-09-24 (`docs/future/google-cloud-gemini-tts-params.md`), and the current code on this branch.

---

## R1. The token exchange, without a library

**Decision**: The JDK alone, as the source note proposed:

1. Header `{"alg":"RS256","typ":"JWT","kid":"<private_key_id>"}` (`kid` only when the file has
   `private_key_id`).
2. Claims `{"iss":"<client_email>","scope":"https://www.googleapis.com/auth/cloud-platform",
   "aud":"<token address>","iat":<now>,"exp":<now + 3600>}`, both times in epoch seconds.
3. `base64url(header) + "." + base64url(claims)`, signed with `SHA256withRSA`
   (`java.security.Signature`), all three parts base64url without padding.
4. `POST <token address>`, `Content-Type: application/x-www-form-urlencoded`, body
   `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=<jwt>`.
5. A 200 response carries `access_token`, `token_type` (`Bearer`) and `expires_in` (seconds).
   The token is held until `expires_in` minus the renewal margin (R4). The JWT's own `exp` is
   irrelevant after the exchange.

**Rationale**: Google's reference fixes every field above. `exp` may be at most one hour after
`iat`, which is also what Google returns as `expires_in` in practice. JSON is written with the
host's Jackson, which the provider already uses. SC-009 rules out `google-auth-library`.

**Alternatives considered**: `google-auth-library-oauth2-http` would have to be shaded, which
brings Guava and its own HTTP transport into a shared classloader (constitution VIII). A
self-signed JWT sent directly as the bearer token (no exchange) works for some Google APIs, but it
is not documented for Text-to-Speech, and it does not serve the Agent Platform check that FR-008
prepares for.

---

## R2. The private key

**Decision**: `private_key` is a PEM `-----BEGIN PRIVATE KEY-----` block (PKCS#8, which is what
Google issues). Strip the armour and whitespace, base64-decode it, and build
`KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(…))`. At start-up the key
is also passed to `Signature.initSign`, which proves it is usable for signing (FR-003) without
signing anything or making a network call. Any failure aborts start-up naming the path, never the
key's content.

**Rationale**: `initSign` rejects a key of the wrong algorithm or a corrupted one, which is what
"cannot be used for signing" means. A PKCS#1 (`BEGIN RSA PRIVATE KEY`) block is not something
Google's console produces. It is rejected with a message saying the key must be PKCS#8 as Google
issues it, not converted.

---

## R3. Which file counts as a service account key

**Decision**: The file must be a JSON object with `"type": "service_account"`, a non-blank
`client_email` and a non-blank `private_key`. `private_key_id`, `project_id` and `token_uri` are
optional. Any other `type` (`authorized_user`, `external_account`, an OAuth client file with no
`type` but an `installed`/`web` object) is rejected with a message naming the `type` found and
saying a service account key is required. Unknown extra fields are ignored.

`token_uri`, when present, must parse as an absolute URI with scheme `https`, or `http` with a
loopback host (`localhost`, `127.0.0.0/8`, `::1`). Loopback is decided from the host text, never
by DNS, so start-up stays free of network work. When it is absent, `https://oauth2.googleapis.com/token`
is used. The same address is the JWT's `aud` (Google requires them to match).

**Rationale**: The `type` field is the only thing that tells a service-account key apart from the
other JSON files a Google console hands out, and the user story names exactly those as the
operator's likely mistakes. Accepting `http` on loopback is what lets the tests run the token
service in WireMock while production cannot send an assertion in clear text.

---

## R4. Token reuse and renewal

**Decision**: A token is reused until **5 minutes** before it expires. For a token that lives less
than 10 minutes (not seen in practice), it is reused for half its lifetime instead. The expiry is
computed from the clock reading taken **before** the request was sent, so a slow token response
shortens the reuse window instead of extending it.

**Rationale**: Google tokens live 3,599 s. With a 5-minute margin a steady hour costs the first
fetch plus one renewal at about 55 minutes, which is SC-005's limit of 2. The margin also absorbs
host clock drift of a few minutes. Google's own client library uses a similar margin (a few
minutes). The margin is not operator configuration (spec Assumptions).

---

## R5. One fetch at a time, and the back-off

**Decision**: `GoogleAccessTokenCache` follows `GoogleVoiceCatalogue`'s pattern exactly:

- The current token is an immutable `(value, renewAt, expiresAt)` record in a `volatile` field; a valid token
  is returned without locking.
- A fetch happens under a `ReentrantLock` taken with `tryLock(budget)`. The holder re-checks the
  field, so N concurrent callers make one request (FR-010). A caller whose budget runs out waiting
  for the lock gets the current token if it is still valid, and otherwise fails with
  `PROVIDER_TIMEOUT`. That failure is **not** remembered: the lock holder's own fetch is still
  running and records its own outcome, and `failedAt` is only ever written under the lock. This is
  what `GoogleVoiceCatalogue.refetch` does when its lock wait runs out.
- Failures are classified (R6). An **unavailable** result stores `failedAt` and its reason. For
  `voice-catalogue.failure-backoff` after that, a caller that needs a new token fails at once with
  the stored reason and the words "in back-off after a recent failure" (FR-019). A **rejection**
  stores nothing.
- A token that is still valid (before `expiresAt`, not discarded) is returned even while a
  back-off is running, and when a renewal inside the margin fails: the margin exists to renew
  early, so a failed early renewal must not cost an announcement the old token could still serve.

**Rationale**: The same design is already tested and reviewed for the catalogue. Reusing
`failure-backoff` rather than adding a second property is what the Clarifications session decided.

---

## R6. Classifying token-service failures

| What happened | Code | Back-off? | Message |
|---|---|---|---|
| `HttpTimeoutException` | `PROVIDER_TIMEOUT` | yes | "Provider '<n>' could not obtain an access token: the token service did not respond within <t>" |
| The budget ran out waiting for the lock (another request is fetching) | `PROVIDER_TIMEOUT` | **no** (R5) | "… could not obtain an access token: another request was still fetching one after <t>" |
| Other `IOException` (DNS, refused connection, TLS) | `PROVIDER_ERROR` | yes | "… could not obtain an access token: <exception message>" |
| HTTP 5xx, or HTTP 429 | `PROVIDER_ERROR` / `PROVIDER_RATE_LIMITED` | yes | "… : token service returned HTTP <s>[: <explanation>]" |
| HTTP 4xx other than 429 (`invalid_grant`, `invalid_scope`, `unauthorized_client`, disabled account, deleted key) | `PROVIDER_ERROR` | **no** | "… : Google rejected the service account key (HTTP <s>)[: <explanation>]" |
| 200 without a usable `access_token` / `expires_in` | `PROVIDER_ERROR` | no | "… : the token service returned an unusable response (HTTP 200)" |

**Rationale**: FR-019 remembers an unreachable or timed-out service and one that cannot serve now. A 5xx or 429 says nothing about
the key; it says the service cannot serve now, so it is treated like an unreachable service.
Remembering it keeps an outage to one slow announcement per period. A 4xx is Google's verdict on
the key or the request, which the operator may fix on Google's side at any moment, so it is never
remembered (Story 3, scenario 6). 429 maps to `PROVIDER_RATE_LIMITED`, as it does for synthesis.
No new error code (FR-017).

---

## R7. Google's explanation from the token service

**Decision**: `GoogleErrorBody.message` learns the OAuth error shape
`{"error":"invalid_grant","error_description":"Invalid JWT Signature."}` in addition to the
standard `{"error":{"message":…}}` envelope. For the OAuth shape the explanation is
`"<error_description> (<error>)"`, or the bare `error` when there is no description. Truncation
(500 characters) and "never throws" stay.

**Rationale**: The token endpoint does not use the API envelope, so without this every rejection
would reach the caller as a bare status, which FR-015 and SC-007 forbid. One reader for "Google's
explanation" keeps a single place where the length cap and the never-throw rule live.

---

## R8. The order inside `synthesize`

**Decision**: A service-account entry obtains its token **first**, before the voice existence
check. The catalogue fetch (if any) and synthesis each read the cache again (`prepare`), which
returns that same token unless a 401 on the catalogue fetch renewed it, so synthesis never sends
a token already discarded. Neither read asks Google for a token. If the token
cannot be obtained, the announcement fails at once with the token failure. The catalogue is not
consulted, and its back-off is not started.

**Rationale**: The alternative (catalogue first, as today) makes the catalogue fetch the first thing
to ask for a token, under the catalogue's 3-second `fetch-timeout`. On an outage the catalogue
fetch times out, starts the token back-off, and the synthesis step then fails at once with "in
back-off after a recent failure" for the very first failure, which misreports it. On a rejected
key, it would ask Google twice in one announcement. Token-first gives the caller the real failure
and asks Google once. The caller-visible outcome of Story 3, scenario 7 holds: the existence check
is skipped, and the announcement reports the token failure. The spec's scenario 7 is reworded to
match: its catalogue back-off applies when a **catalogue fetch** fails for want of a token, which
now happens on voice listing and when the token is renewed between the two steps.

---

## R9. Retrying a rejected token

**Decision**: On **HTTP 401** from `text:synthesize` or `voices`, a service-account entry
discards that token (only if the cache still holds that same token, so concurrent 401s cause one
renewal), obtains a new one within the remaining budget, and resends the request once. A second
401 is reported like any other failure, with Google's explanation. 403 is never retried: it is a
permission denial (Story 3, scenario 4), not a stale token. API-key entries never retry.

**Rationale**: Google answers an expired or revoked token with `401 UNAUTHENTICATED`, and a missing
permission with `403 PERMISSION_DENIED`. Retrying only 401 bounds each request to one extra token
fetch (spec Edge Cases).

---

## R10. The time budget

**Decision**: The token fetch runs inside the entry's `timeout-seconds` budget: its HTTP timeout is
the remaining budget, like the existing lock wait. The catalogue fetch and synthesis then get what
is left, with synthesis keeping its existing floor of 1 second. A 401 retry's renewal and resend
also come out of what is left.

**Rationale**: FR-016. No new setting: the token service normally answers in well under a second,
and a hung one is reported as `PROVIDER_TIMEOUT` within the entry's budget, then held off for
`failure-backoff`.

---

## R11. Where the key file is read

**Decision**: `TtsProperties.validate()` checks the configuration shape: exactly one of `api-key`
and `service-account-key-file` on a `google-cloud` entry, and no `service-account-key-file` on any
other type. `TtsAutoConfiguration` reads and parses the file once, through
`ServiceAccountKey.load(Path)`, while it builds that entry's provider. A fault there throws the
same `IllegalStateException("multiroom-tts: provider '<name>' …")` that validation throws, so host
start-up aborts naming the extension, the entry and the resolved absolute path.

**Rationale**: FR-013 requires the file to be read exactly once. Parsing in `validate()` would mean
either reading it twice or keeping a private key inside a `@ConfigurationProperties` bean, where
Lombok's `toString` and Spring's `/actuator/configprops` could expose it. Bean construction may
read local files (constitution VIII forbids network and subprocess work only), and a fault there
still aborts start-up. A path that is not absolute resolves with `Path.toAbsolutePath()` against the
host's working directory, exactly as `model-path` does today (Clarifications).

---

## R12. The setting's name

**Decision**: `service-account-key-file` (`TtsProviderConfig.serviceAccountKeyFile`).

**Rationale**: It says what the file is in Google's own words ("service account key"), and `-file`
says it is a path, not the key's content. The source note's `credentials-path` was rejected because
"credentials" covers the API key too, and the Clarifications session ruled out inline content.

---

## R13. The service account's roles for classic voices

**Decision**: The setup guide tells the operator to create the service account in the project where
the Text-to-Speech API is enabled, and to grant no role at first. If Google then answers
`403` with `serviceusage.services.use`, grant **Service Usage Consumer**
(`roles/serviceusage.serviceUsageConsumer`). The live check in [quickstart.md](quickstart.md) §4
records which case production hit, and the guide is finalised from it.

**Rationale**: Google's Text-to-Speech authentication page names only Service Usage Consumer, and
only for calls billed to a project the caller does not own. Classic synthesis has no documented
IAM permission of its own. A secondary source mentions a `roles/texttospeech.user` role, which
Google's IAM reference does not list for classic synthesis. Rather than document a role from memory,
the smoke run settles it. The **Agent Platform User** role is only for Gemini, which is out of
scope.

---

## R14. Keeping secrets out of every output

**Decision**:

- `ServiceAccountKey` is a record whose `toString()` is overridden to print only `client_email` and
  the path. `PrivateKey` objects are never formatted.
- The access token and assertion live only in local variables, the token cache's record (also with
  an overridden `toString()`) and the outgoing request. No log statement takes them as an argument.
- Exception messages are built from classified reasons (R6) and `GoogleErrorBody`'s explanation,
  never from a response body or the request.
- `TtsProviderConfig` is Lombok `@Data`, and its generated `toString()` already prints `apiKey`. It
  holds only the key file's path, not the key. This feature does not change `api-key`'s exposure.
- A test (SC-008) captures all log output and every exception message across the token failure
  scenarios and asserts that none of them contain the private key's base64 body, the assertion or
  the issued token.

---

## R15. Version

**Decision**: `0.1.1` → `0.1.2`. `multiroom.require_api_version` stays `0.1.18`. The REST contract
(`specs/002-google-voice-selection/contracts/tts-rest-api.yaml`) changes only its `info.version`
and a changelog paragraph: no path, field or error code changes.

**Rationale**: The feature is additive configuration and does not touch `multiroom-api`. A small
increment, as for 002.
