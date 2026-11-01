# Feature Specification: Warm The Cache Without Playback

**Feature Branch**: `010-cache-warm-without-playback`

**Created**: 2026-11-01

**Status**: Completed

**Input**: User description: "docs/backlog/cache-warm-without-playback.md": put an announcement's
audio into the cache in advance, without anything being heard.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Prepare an announcement silently (Priority: P1)

A home automation prepares a phrase it may need later, such as "The power is expected back at half
past two", while the network is up. The audio is synthesized and stored, and nothing plays in any
room. When a power outage takes the internet down, the same phrase, spoken as an ordinary
announcement, plays from the stored audio with no provider involved.

**Why this priority**: This is the feature. Today the only way to store an announcement is to play
it aloud, and the announcements that matter most are the ones needed when a cloud provider cannot
be reached.

**Independent Test**: Warm a phrase, confirm no playback started, make the provider unreachable,
speak the same phrase and confirm it is served from the cache.

**Acceptance Scenarios**:

1. **Given** a phrase that is not cached, **When** it is warmed, **Then** the provider synthesizes
   it once, the audio is stored, the answer reports `cacheHit: false`, and no input, route or queue
   entry is created.
2. **Given** a warmed phrase, **When** it is spoken with the same text, provider and settings on
   any target, **Then** it is a cache hit and the provider is not contacted.
3. **Given** a warmed phrase and an unreachable provider, **When** it is spoken, **Then** it plays.
4. **Given** a request that spells its settings differently from the later speak but resolves to
   the same ones, or relies on the entry's defaults, **When** the phrase is spoken, **Then** it is a
   cache hit.

---

### User Story 2 - Re-warm on a schedule at no cost (Priority: P1)

The automation warms its whole list of phrases regularly. Phrases already stored cost nothing and
count as recently used, so they stay ahead of eviction, and any phrase that was evicted or never
stored is synthesized again. A run that stopped half way can simply be repeated.

**Why this priority**: The cache is an ordinary least-recently-used cache, and an outage may come
months after a phrase was last spoken. A free, repeatable warm-up is what keeps the phrases there
without a separate "keep" mechanism.

**Independent Test**: Warm a phrase twice and confirm the second answer is a hit with no provider
call and a refreshed last-used time.

**Acceptance Scenarios**:

1. **Given** a cached phrase, **When** it is warmed, **Then** the answer reports `cacheHit: true`,
   the provider is not contacted, and the entry counts as just used.
2. **Given** an entry that was evicted or cleared, **When** it is warmed, **Then** it is
   synthesized and stored again.
3. **Given** a run that failed part way through a list, **When** the whole list is warmed again,
   **Then** only the phrases still missing reach the provider.

---

### User Story 3 - Know that a warm-up failed (Priority: P2)

The automation is told plainly when a phrase was not stored, so it can retry rather than discover
the gap during an outage.

**Why this priority**: A warm-up has one purpose. Reporting success without a stored entry would
hide the failure until the moment the phrase is needed.

**Independent Test**: Make the provider fail, and separately make the cache unwritable, and confirm
each answers an error and leaves nothing behind.

**Acceptance Scenarios**:

1. **Given** a provider that times out, is rate limited or fails, **When** a phrase is warmed,
   **Then** the answer is the same error a speak request gets, and it is not retried.
2. **Given** an unwritable cache, **When** a phrase is synthesized for a warm-up, **Then** the answer
   is an error naming the cache failure, and no temporary audio file is left behind.
3. **Given** a blank or over-long text, an unknown provider, or a setting the provider rejects,
   **When** it is warmed, **Then** it is refused as a speak request would be, before any synthesis.

---

### User Story 4 - Watch warm-ups apart from announcements (Priority: P3)

An operator reads the extension's metrics and can tell warm-ups from announcements: the announcement
counts and the cache hit ratio still describe what was played, while the cost of warming stays
visible.

**Why this priority**: A scheduled re-warm would otherwise distort the hit ratio with hundreds of
hits nobody heard, but the feature works without this.

**Independent Test**: Run a warm-up and confirm the warm-up count rises, the announcement counts and
cache lookup counts do not, and a miss adds to the characters sent to the provider.

**Acceptance Scenarios**:

1. **Given** any warm-up, **When** it is answered, **Then** the warm-up count rises by one with its
   outcome, and no announcement, cache lookup, queue or playback count changes.
2. **Given** a warm-up that reaches the provider, **When** it finishes, **Then** synthesis time,
   characters and audio produced are recorded as for an announcement.

---

### Edge Cases

- **Two warm-ups, or a warm-up and a speak, of one uncached phrase at the same moment**: both may
  synthesize, as two identical speak requests do today. One stored entry results, and an
  announcement playing it is not disturbed, because a stored file is replaced atomically.
- **A phrase removed by an operator while an announcement still holds it**: a warm-up treats it as
  not cached, synthesizes it again, and the new entry stands; the deferred removal no longer applies
  to it.
- **An entry whose file has gone from the disk**: a miss, and it is stored again.
- **A warm-up pushes the cache over its size limit**: the least recently used entries are evicted
  as for any new entry. Entries held by queued or playing announcements are protected as they are
  today, subject to the known gaps in `docs/backlog/reference-counted-cache-pins.md`.
- **A body that also carries speak-only fields** (`targetName`, `targetType`, `playbackMode`,
  `routeVolume`, `duckingLevelDb`): they are ignored, not checked, so a caller may reuse its speak
  body unchanged.
- **Warming during an outage**: a phrase already cached is still a hit; a missing one fails with the
  provider's error.
- **`multiroom.tts.enabled=false`**: the endpoint does not exist, like the rest of `/api/tts/**`.

## Requirements *(mandatory)*

### Functional Requirements

*The request*

- **FR-001**: The extension MUST offer `POST /api/tts/cache/warm`, taking one text per request and
  the same provider and voice fields as a speak request: `text`, `providerName`, `voice`,
  `language`, `engine`, `pitch`, `speakingRate`, `stylePrompt`.
- **FR-002**: A warm-up MUST need no target, and MUST NOT check that any output or group exists.
- **FR-003**: Text, provider and settings MUST be validated exactly as for a speak request, with the
  same error codes, before any synthesis.
- **FR-004**: `POST /api/tts/speak` MUST keep its contract: same fields, same validation, same `202`.

*The cache*

- **FR-005**: A warm-up MUST identify the audio exactly as a speak request does, so that a speak
  with the same text, provider and resolved settings is a hit on any target. The playback fields
  and the target are not part of that identity.
- **FR-006**: When the audio is cached, a warm-up MUST mark the entry as just used, MUST NOT contact
  the provider, and MUST answer without doing network work.
- **FR-007**: When the audio is not cached, a warm-up MUST synthesize it, convert it to the same
  format as an announcement, and store it as an ordinary cache entry.
- **FR-008**: A warmed entry MUST behave as any other entry: evicted by least recent use, removed by
  a cache clear, and counted in the cache statistics. There is no way to keep an entry.

*Silence*

- **FR-009**: A warm-up MUST create no announcement, input, route, queue entry or cache hold, and
  MUST NOT affect anything playing or queued, other than through ordinary eviction.

*The answer*

- **FR-010**: A successful warm-up MUST answer `200` with `{ "cacheHit": true|false }` once the
  entry is stored, and no announcement id or queue depth.
- **FR-011**: A provider failure MUST answer as for a speak request, with no automatic retry, and the
  provider timeout applies unchanged.
- **FR-012**: A failed cache write MUST answer an error with a new published code,
  `CACHE_WRITE_FAILED`, as a `503`. Unlike an announcement, a warm-up MUST NOT fall back to a
  temporary file.
- **FR-013**: The published REST contract and the operator documentation MUST describe the endpoint,
  the new code, and what a caller needs to know: warm the exact text that is later spoken, warm
  again after changing a voice, language, style prompt or default provider, name the provider, and
  pace requests, because the extension has no rate limiter.

*Observability*

- **FR-014**: Every answered warm-up MUST be counted exactly once in a new metric, by a configured
  provider or `unknown` and by outcome (`hit`, `synthesized`, `failed`), with an error tag on the same
  rules as the announcement metric. Requests refused before they reach the service count too.
- **FR-015**: A warm-up MUST NOT change the announcement, cache lookup, queue or playback metrics, and
  MUST NOT add tags to any existing metric.
- **FR-016**: A warm-up that reaches the provider MUST record synthesis time, characters and audio
  produced as an announcement does, because the provider bills it.
- **FR-017**: Warm-ups MUST be logged as structured events carrying the text length and provider,
  never the text.

### Key Entities

- **Warm-up request**: one text with optional provider and voice settings; no target and no playback
  settings. Not stored anywhere once answered.
- **Cache entry**: the existing stored audio, unchanged. A warmed entry cannot be told from one
  stored by an announcement.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: After warming a phrase, speaking it with the provider unreachable plays it, with no
  provider call and no failed attempt.
- **SC-002**: Warming any number of phrases produces no sound and leaves no route, input or queue
  entry on the hub.
- **SC-003**: Re-warming a list of cached phrases makes no provider calls, costs nothing, and every
  phrase answers without waiting on the network.
- **SC-004**: After a failed run, repeating the list sends the provider only the phrases still
  missing.
- **SC-005**: Over any period of warm-ups with no announcements, the announcement counts and the
  cache hit ratio do not change.
- **SC-006**: Every existing speak request behaves and answers exactly as before.
- **SC-007**: A warm-up that stored nothing never answers success.

## Assumptions

- The power-outage automation speaks each phrase as its own request (confirmed by the owner,
  2026-11-01), so each phrase is one cache entry.
- The caller loops over its phrases, one request each. No batch request is in scope: synthesis runs
  while the request waits, and a batch would need per-text results and partial-failure rules.
- The warmed set stays well under the cache's size limit. Production's 500 MB fits about 700 phrases
  of average length; the operator raises the limit, or ships
  `docs/backlog/reference-counted-cache-pins.md` first, if it gets close.
- The warm-up is authorized as the rest of `/api/tts/**` is: the hub's LAN, with no authentication of
  its own.
- Out of scope: MQTT (the extension has none), keeping entries from eviction, a fallback to another
  provider during an outage, and any interaction with a monthly budget (open question 6 in
  `docs/backlog/provider-character-budget.md`).
- No new hub API is needed: `multiroom.require_api_version` stays as it is.
