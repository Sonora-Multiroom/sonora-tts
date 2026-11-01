# Data Model: Warm The Cache Without Playback

Nothing new is persisted. The cache entry, `index.json` and `CacheKey` are unchanged, so existing
hashes stay valid. A warmed entry cannot be told from one stored by an announcement.

## `WarmRequest` (REST)

`POST /api/tts/cache/warm` body. Unknown fields are ignored.

| Field | Type | Rule |
|---|---|---|
| `text` | string | Required, not blank, at most `max-text-length` |
| `providerName` | string | Optional; a configured name, else the default provider |
| `voice`, `language`, `engine` | string | Optional; as for speak |
| `pitch`, `speakingRate` | number | Optional; range-checked by the provider |
| `stylePrompt` | string | Optional; `google-gemini` only; empty or whitespace means no prompt |

Any field a provider does not support is refused as for speak.

## `WarmCommand` (service)

The same fields, so `service` does not depend on `rest`, as `AnnounceCommand` does.

## `WarmResponse` (REST)

| Field | Type | Meaning |
|---|---|---|
| `cacheHit` | boolean | `true`: already stored, no provider call. `false`: synthesized and stored now |

## `TtsErrorCode`

Adds `CACHE_WRITE_FAILED` (503): the audio was synthesized, or could have been, but is not in the
cache.

## Warm-up outcome

There is no stored state, only one transition per request:

| Starting point | Result | Answer | `tts_cache_warm_total` |
|---|---|---|---|
| Invalid request or unknown provider | Unchanged | 400 | `failed`, its code |
| Cached | Entry marked as just used | 200, `cacheHit: true` | `hit`, `none` |
| Not cached, provider succeeds, stored | New entry | 200, `cacheHit: false` | `synthesized`, `none` |
| Not cached, provider fails | Unchanged | 503, the provider code | `failed`, its code |
| Not cached, store fails | Unchanged | 503, `CACHE_WRITE_FAILED` | `failed`, `CACHE_WRITE_FAILED` |
| Not cached, entry evicted by its own store | No new entry; any entries evicted before it stay evicted | 503, `CACHE_WRITE_FAILED` | `failed`, `CACHE_WRITE_FAILED` |

Another failure is counted as `failed`, `INTERNAL`, and answered as an unexpected error is for speak.
