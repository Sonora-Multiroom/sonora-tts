---

description: "Task list for 003-google-service-account-auth"
---

# Tasks: Google Cloud Service Account Authentication

**Input**: Design documents from `/specs/003-google-service-account-auth/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/configuration.md](contracts/configuration.md),
[contracts/tts-rest-api.yaml](contracts/tts-rest-api.yaml), [quickstart.md](quickstart.md)

**Tests**: **Required.** Constitution III mandates test-first: each test task comes before the
implementation it covers and must fail (red) before that implementation starts. No test calls real
Google, and no key material is committed: every test builds its key pair at run time through
`TestServiceAccountKeys` (T004) and points `token_uri` at WireMock on loopback. Time is an injected
`java.time.Clock`.

**Organization**: Tasks are grouped by user story. US1 and US2 are P1, US3 is P2.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: The user story (US1–US3) the task serves
- Paths are relative to the repository root. Main code is `src/main/java/multiroom/tts/`, tests are
  `src/test/java/multiroom/tts/`

## Conventions every task follows

- A start-up fault is `IllegalStateException("multiroom-tts: provider '<name>' …")`. In
  `TtsProperties` it goes through `fault(...)`. Outside `config`, the new classes throw the same
  exception type with the same prefix. No start-up code makes a network call (Constitution VIII).
- A run-time failure is a `TtsException` with an **existing** `TtsErrorCode`. No new constant
  (FR-017). Its message names the entry (`Provider '<name>' …`), as the provider's messages do today.
- **No secret in any output**: never pass the private key, the signed assertion or a token to a
  logger, an exception message, or a `toString()`. Records that hold one override `toString()`.
- Do not cite spec IDs (`FR-`, `SC-`, `US`, `R`) in code comments. State the rule itself. Feature
  numbers (`002`, `003`) and constitution principles may be named.
- Match the surrounding style: 4-space indent, existing import order, Javadoc on public types,
  comments that explain *why*. `GoogleVoiceCatalogue` is the model for the cache's concurrency and
  back-off code.

---

## Phase 1: Setup

**Purpose**: Version and contract bookkeeping. No behaviour change.

- [X] T001 In `pom.xml`, bump `<version>` from `0.1.1` to `0.1.2`. Leave `multiroom.require_api_version` at `0.1.18` (plan.md, "Depends on").
- [X] T002 [P] Update the Javadoc of `src/main/java/multiroom/tts/TtsErrorCode.java` to point at `specs/003-google-service-account-auth/contracts/tts-rest-api.yaml` (v0.1.2) as the published error-code set. That file supersedes 002's copy, and adds no code.

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: The new setting, the key loader's happy path, the test key helper and the credential
seam in the provider. At the end of this phase, API-key entries behave **exactly as before**, and
the whole existing suite stays green.

- [X] T003 Add `private String serviceAccountKeyFile;` to `src/main/java/multiroom/tts/config/TtsProviderConfig.java` (YAML `service-account-key-file`), with Javadoc: `google-cloud` only; a path to the service account's JSON key, read once at start-up; relative paths resolve against the working directory; exactly one of this and `apiKey`. Update `apiKey`'s Javadoc to "Required for `OPENAI`. For `GOOGLE_CLOUD`, exactly one of this and `serviceAccountKeyFile`".
- [X] T004 [P] Create the test helper `src/test/java/multiroom/tts/provider/cloud/google/TestServiceAccountKeys.java`. It generates one RSA-2048 `KeyPair` per JVM (a static, lazily initialised field; key generation is slow). It offers `Path write(Path dir, String tokenUri)`, which writes a valid key file (`type`, `project_id`, `private_key_id`, PKCS#8 PEM `private_key` with `\n` line breaks as Google writes it, `client_email` `sonora-tts@test-project.iam.gserviceaccount.com`, `token_uri`), and `Path write(Path dir, Map<String, Object> overrides)` for malformed variants (a `null` value removes the field). It also exposes `publicKey()` for signature checks and `privateKeyBase64()` for the secret-leak assertions.
- [X] T005 [P] Write `src/test/java/multiroom/tts/provider/cloud/google/ServiceAccountKeyTest.java`, happy path only: a file from T004 loads; `clientEmail`, `privateKeyId` and `tokenUri` are read; `path()` is absolute even when given a relative path (resolve it against `Path.of("").toAbsolutePath()`); a missing `token_uri` defaults to `https://oauth2.googleapis.com/token`; `toString()` contains the email and the path and not the private key's base64. Must fail (the class does not exist).
- [X] T006 Create `src/main/java/multiroom/tts/provider/cloud/google/ServiceAccountKey.java`: `record ServiceAccountKey(Path path, String clientEmail, PrivateKey privateKey, String privateKeyId, URI tokenUri)` with `static ServiceAccountKey load(String providerName, Path file)`. It resolves `file.toAbsolutePath()`, reads it with `Files.readString`, parses it with Jackson, strips the PEM armour and whitespace, and builds the key with `KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(…))` (research R2). Override `toString()` as in data-model.md. In this task, throw a plain "cannot be loaded" `IllegalStateException` for any failure. T025 adds the specific messages. Makes T005 pass.
- [X] T007 [P] Write `src/test/java/multiroom/tts/provider/cloud/google/GoogleCredentialTest.java` first (must fail, the type does not exist): for `ApiKey`, `prepare` returns empty, `authorize(endpoint, null, timeout)` builds a request whose URI is `endpoint + "?key=" + key` and which has no `Authorization` header, and `toString()` does not contain the key. Then create `src/main/java/multiroom/tts/provider/cloud/google/GoogleCredential.java`: `sealed interface GoogleCredential` with `record ApiKey(String key)` only, for now. Methods: `Optional<String> prepare(Duration budget)`, which returns empty for `ApiKey`, and `HttpRequest.Builder authorize(URI endpoint, String token, Duration timeout)`, which for `ApiKey` builds `HttpRequest.newBuilder(URI.create(endpoint + "?key=" + key))` exactly as the provider does today. Override `ApiKey.toString()` to omit the key.
- [X] T008 Refactor `src/main/java/multiroom/tts/provider/cloud/GoogleCloudTtsProvider.java` to hold a `GoogleCredential` instead of `apiKey`. `buildRequest` and `fetchCatalogue` obtain their builder from `credential.authorize(...)` instead of concatenating `?key=`. Keep the constructors' signatures for now, so every existing test in `src/test/java/multiroom/tts/provider/GoogleCloudTtsProviderTest.java` passes unchanged. That the API-key request shape is untouched is proven by those existing tests, which match `key` as a query parameter.

**Checkpoint**: `mvn verify` green, with no behaviour change.

---

## Phase 3: User Story 1 - Announce With a Service Account Instead of an API Key (Priority: P1) 🎯 MVP

**Goal**: A `google-cloud` entry with only a key file obtains a token lazily, reuses it, renews it
before expiry, shares one fetch among concurrent callers, and sends `Authorization: Bearer` to
synthesis and to the voice catalogue.

**Independent Test**: A service-account entry against WireMock: the announcement's synthesis
request carries `Authorization: Bearer <token>` and no `key` parameter; a second announcement
within the token's lifetime makes no second token request; a simulated hour makes at most 2.

### Tests for User Story 1 (write first, must fail)

- [X] T009 [P] [US1] Write `src/test/java/multiroom/tts/provider/cloud/google/ServiceAccountAssertionTest.java`. Split the JWT at `.`, base64url-decode, and assert: header `alg` `RS256`, `typ` `JWT`, `kid` equals `private_key_id` and is absent when the file has none; claims `iss` = `client_email`, `scope` = `https://www.googleapis.com/auth/cloud-platform`, `aud` = the key's `tokenUri`, `iat` = the fixed clock's epoch second, `exp` = `iat + 3600`; no `=` padding in any part; the signature verifies with `SHA256withRSA` and `TestServiceAccountKeys.publicKey()`.
- [X] T010 [P] [US1] Write `src/test/java/multiroom/tts/provider/cloud/google/GoogleTokenExchangeTest.java`, success path against WireMock: it POSTs `application/x-www-form-urlencoded` with `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer` and `assertion=<the given jwt>` to the key's `tokenUri`; a 200 body `{"access_token":"ya29.test","expires_in":3599,"token_type":"Bearer"}` yields `("ya29.test", 3599 s)`; the request's timeout is the budget given.
- [X] T011 [P] [US1] Write `src/test/java/multiroom/tts/provider/cloud/google/GoogleAccessTokenCacheTest.java`, reuse and renewal. Use a mutable test `Clock` and a counting fake exchange (a functional interface, as `GoogleVoiceCatalogue.CatalogueFetcher` is):
  - (a) the first `token()` fetches, the second returns the same value without fetching;
  - (b) at `expires_in − 5 min − 1 s` no fetch, at `expires_in − 5 min` a new fetch;
  - (c) a token with `expires_in` 300 s is renewed at 150 s;
  - (d) steady calls every 10 s over a simulated hour with `expires_in` 3599 cause exactly 2 fetches;
  - (e) 8 threads released together on an empty cache cause 1 fetch and all get the same token;
  - (f) `discard(t)` clears only when the cache still holds `t`: after `discard("stale")` while holding `"fresh"`, no fetch;
  - (g) the expiry is measured from the clock reading taken before the exchange (advance the clock inside the fake exchange).
- [X] T012 [US1] Extend `src/test/java/multiroom/tts/provider/GoogleCloudTtsProviderTest.java` with a service-account entry. Add a factory building the provider with a `ServiceAccountKey` loaded from a T004 file whose `token_uri` is `server.baseUrl() + "/token"`. Cases:
  - (a) synthesis sends `Authorization: Bearer ya29.test` and no `key` query parameter;
  - (b) the `voices` fetch sends the same header;
  - (c) two announcements → one `POST /token`;
  - (d) the token request precedes the `voices` request (WireMock `getAllServeEvents` order);
  - (e) a 401 from `text:synthesize` followed by 200 → two `POST /token`, two synthesis requests, success;
  - (f) 401 twice → failure `PROVIDER_ERROR` whose message has Google's explanation, and exactly two token requests;
  - (g) a 403 → no retry, one token request;
  - (h) a 401 from `voices` → one renewal and one resend of `voices`;
  - (i) `listVoices` on a cold entry fetches a token, then the catalogue;
  - (j) two providers built from the same key file → each announcement on each provider leads to its own `POST /token` (two in total), so tokens are never shared between entries;
  - (k) pitch and speaking rate reach the synthesis body unchanged on a service-account entry (parameterise one existing audio-parameter test over both credentials).
- [X] T013 [P] [US1] Extend `src/test/java/multiroom/tts/service/TtsServiceTest.java`: the same text, voice and provider name produce the same cache key whether the entry's config has `apiKey` or `serviceAccountKeyFile`, so the credential never enters the key. (A cache hit returns before the provider is called, so a hit never touches the token path and keeps 001's 1-second start.)

### Implementation for User Story 1

- [X] T014 [P] [US1] Create `src/main/java/multiroom/tts/provider/cloud/google/ServiceAccountAssertion.java` (research R1): the constructor takes `(ServiceAccountKey key, String scope, Clock clock)`; `String sign()` writes header and claims with Jackson (a `LinkedHashMap`, so field order is stable), base64url without padding, and signs with a new `Signature.getInstance("SHA256withRSA")` per call. Public constant `CLOUD_PLATFORM_SCOPE`. Makes T009 pass.
- [X] T015 [P] [US1] Create `src/main/java/multiroom/tts/provider/cloud/google/TokenFailure.java`: `sealed abstract class TokenFailure extends TtsException permits Unavailable, Rejected`, both `static final` nested classes taking `(TtsErrorCode code, String message)`. `TtsException` is already non-final, so it is not changed.
- [X] T016 [US1] Create `src/main/java/multiroom/tts/provider/cloud/google/GoogleTokenExchange.java`: the constructor takes `(String providerName, HttpClient client, URI tokenUri)`; `Token exchange(String assertion, Duration timeout)` returns `record Token(String value, Duration expiresIn)` (`toString()` omits `value`). This task covers success only; a non-2xx response throws a generic `TokenFailure.Rejected`, and T032 classifies the failures. It also declares the functional interface `Exchanger` that `GoogleAccessTokenCache` depends on, so tests can fake it. Makes T010 pass.
- [X] T017 [US1] Create `src/main/java/multiroom/tts/provider/cloud/google/GoogleAccessTokenCache.java` per data-model.md, modelled on `GoogleVoiceCatalogue`:
  - state: a `volatile AccessToken current` (private record `(value, renewAt, expiresAt)`, `toString()` without the value) and a `ReentrantLock`;
  - `String token(Duration budget)`: a lock-free read of a token still valid; otherwise `tryLock(budget)`, re-check, fetch. A lock wait that runs out returns the current token if it is before `expiresAt`, and otherwise throws `TtsException(PROVIDER_TIMEOUT, …)` directly, not a `TokenFailure`, so it can never start the back-off T033 adds (research R5: `failedAt` is written only under the lock, as in `GoogleVoiceCatalogue.refetch`). Add a T011 case (h): one thread holds the lock inside a blocking fake exchange, a second caller with a short budget fails with `PROVIDER_TIMEOUT`; once the first fetch completes, the next call returns its token;
  - renewal margin 5 minutes, or half the lifetime if the lifetime is under 10 minutes (research R4); `renewAt` measured from `clock.instant()` read before the exchange;
  - `void discard(String token)`.
  
  Log at `DEBUG` "multiroom-tts: provider '{}' obtained an access token for {} (valid for {})" with the entry name, `client_email` and the lifetime, never the token. The constructor takes `(String providerName, ServiceAccountAssertion, Exchanger, String clientEmail, Duration failureBackoff, Clock)`, and back-off is added in T033. Makes T011 pass.
- [X] T018 [US1] Extend `src/test/java/multiroom/tts/provider/cloud/google/GoogleCredentialTest.java` first (must fail): for `ServiceAccount` over a stubbed cache, `prepare` returns the cache's token, `authorize` sets `Authorization: Bearer <token>` and adds no `key` parameter, `retriesUnauthorized()` is true (false for `ApiKey`), `discard(t)` reaches the cache, and `toString()` does not contain the token. Then add `record ServiceAccount(GoogleAccessTokenCache tokens)` to `src/main/java/multiroom/tts/provider/cloud/google/GoogleCredential.java`: `prepare(budget)` returns `Optional.of(tokens.token(budget))`; `authorize(endpoint, token, timeout)` builds `HttpRequest.newBuilder(endpoint).header("Authorization", "Bearer " + token)`. Add `void discard(String token)` to the interface, a no-op for `ApiKey`, and `boolean retriesUnauthorized()`, true only for `ServiceAccount`. `toString()` must not print the token.
- [X] T019 [US1] Change `src/main/java/multiroom/tts/provider/cloud/GoogleCloudTtsProvider.java`:
  - Constructors become `(TtsProviderConfig, VoiceCatalogueProperties, ServiceAccountKey)` and `(TtsProviderConfig, VoiceCatalogueProperties, ServiceAccountKey, URI apiBase, Clock)`, with a `null` key meaning the API key. The constructor builds the credential; for a key it builds `ServiceAccountAssertion` → `GoogleTokenExchange` (same `HttpClient`) → `GoogleAccessTokenCache` with `catalogueProperties.getFailureBackoff()`. Update existing test call sites to pass `null`.
  - `synthesize`: call `credential.prepare(remaining(deadline))` **before** the voice check (research R8). A `TtsException` from `prepare` propagates unchanged, so the voice check is skipped. The catalogue fetch does not receive the token (`CatalogueFetcher` takes only a budget): it calls `prepare` itself (next bullet), which right after this call is a cache read, not a second token request. After the voice check, call `prepare` again (a cache read) and use that token for synthesis, so a token renewed by a 401 on the catalogue fetch is used instead of the discarded one. Add a T012 case (l): a 401 from `voices` then 200 → synthesis is sent once, with the renewed token, and there are two `POST /token` in total.
  - Extract one `send(Function<String, HttpRequest> build, Optional<String> token, Instant deadline)` used by synthesis and by `fetchCatalogue`. On HTTP 401 with `credential.retriesUnauthorized()`, it calls `discard(token)`, `prepare(remaining)` and resends once (research R9). Never on 403, never twice.
  - `fetchCatalogue` always calls `prepare(fetchBudget)` itself (on the listing path this is the first token request; after `synthesize` it hits the cache). A `TtsException` from it is rethrown as `IOException(message)`, so the catalogue records the failure and starts its own back-off as 002 defines.
  
  Makes T012 pass. T013 needs no main change (verify it passes).
- [X] T020 [US1] Change `buildProvider` in `src/main/java/multiroom/tts/TtsAutoConfiguration.java`: for `GOOGLE_CLOUD`, when `serviceAccountKeyFile` is set (non-blank), call `ServiceAccountKey.load(config.getName(), Path.of(config.getServiceAccountKeyFile()))` and pass the result; otherwise pass `null`. Extend `src/test/java/multiroom/tts/TtsAutoConfigurationTest.java` (test first): a context with a service-account entry whose `token_uri` is a WireMock URL starts, contains a `ProviderRegistry`, and WireMock has received **zero** requests; with `multiroom.tts.enabled=false` and a key file path that does not exist, the context still starts (the file is never read).

**Checkpoint**: US1 works end to end against WireMock; API-key entries are unchanged.

---

## Phase 4: User Story 2 - A Broken Credential Is Caught at Start-up (Priority: P1)

**Goal**: Every malformed credential configuration aborts start-up with a message naming the
extension, the entry and the fault (the path, as an absolute path, where there is one), and
contacts nothing.

**Independent Test**: Start with each broken configuration in turn; each aborts with the expected
message; WireMock records zero requests throughout.

### Tests for User Story 2 (write first, must fail)

- [X] T021 [P] [US2] Extend `src/test/java/multiroom/tts/config/TtsPropertiesValidationTest.java`:
  - (a) `google-cloud` with neither `api-key` nor `service-account-key-file` → "requires exactly one of api-key and service-account-key-file";
  - (b) both → the same message, with "not both";
  - (c) blank `api-key` plus a key file → passes (blank counts as unset);
  - (d) `service-account-key-file` on `openai`, `piper` and `local-http` → a fault naming `service-account-key-file` and the type;
  - (e) `openai` still requires `api-key`;
  - (f) a pre-003 `google-cloud` entry with only `api-key` passes unchanged.
  
  Also replace any existing assertion that `google-cloud` "requires api-key" with the new message.
- [X] T022 [P] [US2] Extend `src/test/java/multiroom/tts/provider/cloud/google/ServiceAccountKeyTest.java` with one case per fault in contracts/configuration.md: missing file; a directory instead of a file; not JSON; JSON that is not an object; `type` `authorized_user`; no `type` with an `installed` object (an OAuth client file); missing or blank `client_email`; missing `private_key`; `private_key` with a corrupted body; a PKCS#1 `BEGIN RSA PRIVATE KEY` block; an EC key in PKCS#8; `token_uri` `not a url`; `http://oauth2.googleapis.com/token`; `ftp://…`; a path `~/sa.json` that does not exist, whose message shows `<working directory>/~/sa.json` (a leading `~` is not expanded).
  
  Each asserts that the message starts `multiroom-tts: provider 'g'`, contains the **absolute** path, and names the fault (the `type` found, the missing field, or the `token_uri` value). None contains the key's base64 body. `http://localhost:…`, `http://127.0.0.1:…` and `http://[::1]:…` are accepted. A relative path that does not exist reports the absolute path it resolved to.
- [X] T023 [P] [US2] Extend `src/test/java/multiroom/tts/TtsAutoConfigurationTest.java`: a `google-cloud` entry pointing at a missing key file makes context start-up fail with a root cause whose message starts `multiroom-tts: provider` and contains the absolute path; a key file of the wrong `type` fails likewise.

### Implementation for User Story 2

- [X] T024 [US2] In `src/main/java/multiroom/tts/config/TtsProperties.java`, replace `requireApiKey` for `GOOGLE_CLOUD` with `requireExactlyOneGoogleCredential(provider)`. Keep `requireApiKey` for `OPENAI`. After the type switch, reject a non-blank `serviceAccountKeyFile` on every non-Google type, the way `rejectGoogleOnlySetting` does for `pitch`. Update the class Javadoc's example ("missing API key") to "a missing or doubly-set credential". Makes T021 pass.
- [X] T025 [US2] In `src/main/java/multiroom/tts/provider/cloud/google/ServiceAccountKey.java`, replace T006's generic failure with the specific messages in data-model.md and research R2/R3:
  - `NoSuchFileException` → "does not exist"; other `IOException` → "cannot be read";
  - `type` check; required fields;
  - the PEM check (PKCS#1 → "must be the PKCS#8 key Google issues");
  - `Signature.getInstance("SHA256withRSA").initSign(key)` to prove the key can sign;
  - `token_uri`: absolute, and `https` or `http` on a loopback host decided from the host text only (`localhost`, `127.x.x.x`, `[::1]`), with no DNS lookup.
  
  Messages carry the absolute path and never the key. Makes T022 and T023 pass.

**Checkpoint**: US1 and US2 both work; every Story 2 acceptance scenario is covered by a test.

---

## Phase 5: User Story 3 - A Rejected Credential Explains Itself at Run Time (Priority: P2)

**Goal**: Token failures are classified, carry Google's explanation, map to existing codes, are
held off only when the service is unavailable, and never leak a secret.

**Independent Test**: With WireMock, make the token endpoint reject the key, time out, return
5xx, then recover; and make synthesis return 403. Each failure's code, message and back-off
behaviour match contracts/configuration.md's run-time table, and no captured log line or message
contains the key, the assertion or a token.

### Tests for User Story 3 (write first, must fail)

- [X] T026 [P] [US3] Extend `src/test/java/multiroom/tts/provider/cloud/google/GoogleErrorBodyTest.java`: `{"error":"invalid_grant","error_description":"Invalid JWT Signature."}` → `Invalid JWT Signature. (invalid_grant)`; `{"error":"invalid_scope"}` → `invalid_scope`; the standard envelope is unchanged; a 600-character `error_description` is truncated to 500 plus `…`; `{"error":123}` → empty.
- [X] T027 [P] [US3] Extend `src/test/java/multiroom/tts/provider/cloud/google/GoogleTokenExchangeTest.java` with the classification table of research R6. Assert the subclass, the code and the message for each:
  - a WireMock fixed delay beyond the timeout → `Unavailable`, `PROVIDER_TIMEOUT`;
  - a connection reset (`Fault.CONNECTION_RESET_BY_PEER`) → `Unavailable`, `PROVIDER_ERROR`;
  - 503 → `Unavailable`, `PROVIDER_ERROR`;
  - 429 → `Unavailable`, `PROVIDER_RATE_LIMITED`;
  - 400 `invalid_grant` → `Rejected`, `PROVIDER_ERROR`, whose message contains "could not obtain an access token", "rejected the service account key", "HTTP 400" and `Invalid JWT Signature. (invalid_grant)`;
  - 401 with a non-JSON body → `Rejected`, and the message still states `HTTP 401`;
  - 200 without `access_token` → `Rejected`, "unusable response".
  
  Every message starts `Provider 'g'`.
- [X] T028 [P] [US3] Extend `src/test/java/multiroom/tts/provider/cloud/google/GoogleAccessTokenCacheTest.java` with back-off, using a fake exchange that throws on demand:
  - (a) after `Unavailable`, calls within `failure-backoff` throw at once, with the same code and a message containing "in back-off after a recent failure" and the original reason, without calling the exchange;
  - (b) after the back-off period the next call fetches;
  - (c) after `Rejected`, the very next call fetches again;
  - (d) a token still valid is used through a failed renewal. Build this cache with `failure-backoff` 10 minutes, longer than the 5-minute margin, so the back-off is still running at `expiresAt(A)`. Fetch `A` at t0 (`expires_in` 3599); advance to `renewAt(A)` with the exchange throwing `Unavailable` → `token()` returns `A` (one exchange call) and the back-off starts; a second call within the back-off returns `A` without calling the exchange; advancing to `expiresAt(A)` (still in back-off) throws the back-off failure without calling the exchange. In a separate cache, with `A` inside its margin and a back-off running, `discard("A")` makes the next call throw instead of returning `A`;
  - (e) a success clears the back-off;
  - (f) an `Unavailable` failure logs one `WARN` naming the entry, the `client_email`, the reason and the back-off duration, and containing no token or assertion; a `Rejected` failure logs a `WARN` too, without the back-off duration.
- [X] T029 [US3] Extend `src/test/java/multiroom/tts/provider/GoogleCloudTtsProviderTest.java`:
  - (a) the token endpoint returns 400 `invalid_grant` → `synthesize` throws `PROVIDER_ERROR` naming the entry with Google's explanation, and no `voices` or synthesis request is sent;
  - (b) the token endpoint delays past the budget → `PROVIDER_TIMEOUT` within `timeout-seconds`, then an immediate back-off failure on the next call, with no second token request;
  - (c) synthesis returns 403 with an envelope → `PROVIDER_ERROR` with the explanation, exactly as for an API key;
  - (d) `listVoices` when the token endpoint returns 503 → `VOICE_CATALOGUE_UNAVAILABLE` whose message contains "could not obtain an access token" and `HTTP 503`, and a second `listVoices` within the back-off makes no request at all;
  - (e) after (a), the stub is switched to success and the next call plays with no restart;
  - (f) the key file is never read again: build the provider, delete the key file, make the token endpoint answer 400 `invalid_grant` once and then 200 → the first call fails with the rejection, the next succeeds, and the missing file is never reported.
- [X] T030 [US3] Add a secret-leak test to `src/test/java/multiroom/tts/provider/GoogleCloudTtsProviderTest.java`. Attach a Logback `ListAppender` to the `multiroom.tts` logger at `TRACE` and run cases (a)–(e) of T029 plus a successful announcement. Assert that no formatted log message, no exception message (walking the causes) and no `toString()` of the provider, the credential or the key contains `TestServiceAccountKeys.privateKeyBase64()`'s first 40 characters, the token `ya29.test`, or the assertion (capture it from WireMock's recorded token request body).

### Implementation for User Story 3

- [X] T031 [P] [US3] Extend `GoogleErrorBody.message` in `src/main/java/multiroom/tts/provider/cloud/google/GoogleErrorBody.java` to read the OAuth shape when `error` is textual (research R7), keeping the length cap and never throwing. Update its class Javadoc to name both shapes. Makes T026 pass.
- [X] T032 [US3] In `src/main/java/multiroom/tts/provider/cloud/google/GoogleTokenExchange.java`, classify failures per research R6, with messages "Provider '<n>' could not obtain an access token: …" and the explanation from `GoogleErrorBody`. Build messages only from the status, the explanation and exception messages from `HttpClient`, never from the request or a success body. Makes T027 pass.
- [X] T033 [US3] In `src/main/java/multiroom/tts/provider/cloud/google/GoogleAccessTokenCache.java`, add the back-off exactly as `GoogleVoiceCatalogue` does: `volatile Instant failedAt` and `volatile String failureReason`, set only on `TokenFailure.Unavailable` thrown by the exchange, and only while holding the lock (the code is kept too, so the back-off failure repeats it), and cleared on success. A lock wait that runs out never sets them (T017); `inBackoff()` checked before and after taking the lock. A token before `renewAt` short-circuits before any back-off check; after `renewAt`, a back-off or a failed fetch still returns the current token while it is before `expiresAt` (data-model.md). Log every token failure at `WARN` with the entry name, the `client_email` and the reason, plus the back-off duration for `Unavailable`, never a token or the assertion. Makes T028, then T029 and T030, pass. If T030 finds a leak, fix it at its source.

**Checkpoint**: All three stories work independently; the full suite is green.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T034 [P] Rewrite the service-account part of `docs/google-cloud-tts-setup.md`:
  - replace "A service account JSON is not what you need here" and the opening line ("authenticates with a plain API key … not OAuth") with a section "Option B: a service account key". It covers creating the account in the project where the API is enabled, the role (research R13: none at first, **Service Usage Consumer** if Google answers `serviceusage.services.use`; finalise after T038), creating and downloading a JSON key, where to put it (outside any web root, readable by the host's user), and the YAML;
  - add that rotating the key requires a restart, and that a relative path resolves against the host's working directory (under systemd, usually `/`), so an absolute path is recommended;
  - add what an organisation policy blocking key creation (`iam.disableServiceAccountKeyCreation`) means: this feature cannot help, so use an API key;
  - change the Gemini paragraph's "feature `003`" to "a later feature".
- [X] T035 [P] Update `docs/configuration.md`: in the field reference table, `api-key` becomes "`OPENAI`: required. `GOOGLE_CLOUD`: exactly one of `api-key` and `service-account-key-file`", and add a `service-account-key-file` row (`GOOGLE_CLOUD` only; a path; read once at start-up; never contacted at boot). Add the service-account YAML shape to the Google Cloud section, and a pointer to the setup guide.
- [X] T036 [P] Move `docs/future/google-cloud-service-account-auth.md` to `docs/archive/google-cloud-service-account-auth.md` with `git mv`, and prepend a one-paragraph note saying it was implemented as feature 003, which differs from the sketch in three ways: the setting is named `service-account-key-file`, token failures reuse existing error codes, and the token is obtained before the voice check. In `docs/future/google-cloud-gemini-tts-params.md`, replace every "feature 003" / "feature `003`" with "a later feature", and fix links to the moved file (search the whole `docs/` tree and `specs/002-google-voice-selection/` for links to it, but do not edit 002's spec text itself).
- [X] T037 Run `mvn verify` (JaCoCo's 80% gate included), then `mvn dependency:tree -Dscope=runtime`, and confirm that no artifact was added and that `unzip -l target/multiroom-tts-0.1.2.jar` lists no `com/google/` entries (SC-009). Run SpotBugs on the changed classes if available and fix or justify its findings (Constitution V).
- [X] T038 Run [quickstart.md](quickstart.md) §2–§4 against the local core with a real service account key (merge gate part 2): `tts` `0.1.2`, not `REJECTED`; the behavioural table; the role check, with the outcome recorded in `docs/google-cloud-tts-setup.md`. Afterwards, search the core's log from the run (at the level it ran) for `ya29.`, `BEGIN PRIVATE KEY`, the key's first 40 base64 characters and `eyJ` (a JWT's opening), and confirm none are present. Needs the user's key file; ask for its path.
- [X] T039 Run [quickstart.md](quickstart.md) §5 on production **only with the user's explicit permission** for `mvn deploy` and the restart: production's API-key entry announces with no configuration edit.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: none.
- **Foundational (Phase 2)**: after Setup. **Blocks every story.** T003 → (T004 ∥ T007) → T005 → T006 → T008.
- **US1 (Phase 3)**: after Foundational. T009–T013 before T014–T020; T014 ∥ T015 → T016 → T017 → T018 → T019 → T020.
- **US2 (Phase 4)**: after Foundational. Independent of US1 except that T023 uses the loader path wired in T020; run T023 after T020, or stub it without the token flow.
- **US3 (Phase 5)**: after US1 (it classifies and backs off the token flow US1 builds).
- **Polish (Phase 6)**: after the stories it documents; T038 and T039 last.

### User story dependencies

```text
Setup → Foundational ─┬─▶ US1 (P1, MVP) ─▶ US3 (P2)
                      └─▶ US2 (P1)  (T023 after T020)
                                                  └─▶ Polish
```

### Within each story

Tests first and red → pure classes (assertion, error body) → HTTP exchange → cache → credential →
provider → auto-configuration.

## Parallel Example: User Story 1

```text
# Tests, all at once (different files):
T009 ServiceAccountAssertionTest
T010 GoogleTokenExchangeTest
T011 GoogleAccessTokenCacheTest
T013 TtsServiceTest (cache identity)

# Then these two implementations together:
T014 ServiceAccountAssertion
T015 TokenFailure
```

## Parallel Example: User Story 2 and 3 tests

```text
T021 TtsPropertiesValidationTest   T022 ServiceAccountKeyTest
T026 GoogleErrorBodyTest           T027 GoogleTokenExchangeTest
T028 GoogleAccessTokenCacheTest
```

## Implementation Strategy

### MVP first (User Story 1)

1. Phases 1–2 (behaviour unchanged, suite green).
2. Phase 3: a service-account entry announces against WireMock.
3. **Stop and validate** with quickstart §1 rows for Story 1. A real key can already be tried
   locally, but start-up messages are still generic until US2.

### Incremental delivery

1. + US2: operator-facing start-up faults. Now safe to hand to an operator.
2. + US3: run-time diagnosis and back-off. Feature complete.
3. Polish: documentation, the gate, the smoke run, then production with permission.

## Notes

- `[P]` = different files and no dependency on an incomplete task.
- Commit after each task or logical group (Conventional Commits, scope `003-google-service-account-auth`).
- `mvn deploy` without `-Plocal` is a production action: never run it without asking.
