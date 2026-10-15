# Cap each provider's monthly usage with a local character budget

**Status:** Proposed, not started. The investigation was written 2026-09-26 as
`docs/future/google-cloud-free-tier-tracking.md` (not tracked). It was updated by 005, which built
the measuring half, and moved here on 2026-10-10.

**Origin:** The wish to stay within the Google Cloud free tier, so an operator is never billed by
accident. Feature 004 deferred a spending cap to this item (`.specify/archive/004-gemini-tts-provider/`).
Feature 005 measured billable usage with it in mind (`.specify/archive/005-tts-metrics/`).

**Depends on:** nothing. 005 (`tts_synthesis_characters`, 0.1.4) already provides the measurement.
This item does not need service-account auth or the Cloud Monitoring API.

**Effort:** Medium. A persisted monthly counter, new configuration keys, a check in
`TtsService.synthesize`, and maybe a new error code in the REST contract.

## Problem

Nothing stops the extension from sending a billed provider more text than the operator meant to
pay for. Google Cloud's free tier is 1M or 4M characters a month, depending on the voice family
(see [../google-cloud-tts-setup.md](../google-cloud-tts-setup.md#2-enable-billing)). OpenAI has no
free tier but bills per character. Gemini has no free tier and bills per token. A loop in an
automation, or a large stream of uncached announcements, runs up a bill unnoticed.

The mechanism this started from was Google-specific: read usage back from Google. That turned out
not to work (findings 1–4 below), which leads to a general mechanism. Count locally, per provider
entry, and treat "match Google's free tier" as one configuration of it.

### What was investigated (2026-09-26)

1. **The synthesize response carries no usage or cost metadata.** Neither Google's documentation
   nor the response `GoogleCloudTtsProvider` parses (only `audioContent`) shows a cost, a running
   total or a remaining free-tier balance. Not a viable source.
2. **Cloud Monitoring's quota metrics track rate limits, not the monthly bill.** The documented TTS
   quotas ([text-to-speech/quotas](https://docs.cloud.google.com/text-to-speech/quotas)) are
   requests per minute by voice tier (Chirp3: 200/min; Neural2, Polyglot, General: 1000/min) and
   5000 bytes per request. The free-tier allowance is a pricing tier. Whether a queryable
   `characters_used`-style quota metric exists was **not confirmed**. It would need checking in a
   live project's Metrics Explorer.
3. **Even if it exists, it cannot gate a request.** Monitoring metrics are sampled every 60 s and
   can take up to 30 minutes to become queryable
   ([latency and retention](https://docs.cloud.google.com/monitoring/api/v3/latency-n-retention)).
   That is good enough for a periodic report, but not for deciding whether *this* request crosses
   the line.
4. **The Monitoring API needs OAuth and `roles/monitoring.viewer`.** Service-account auth exists
   since 003, but polling Monitoring would still add a scope, a role and a network dependency for
   no gain over counting locally.
5. **Gemini bills by tokens, not characters.** Gemini-TTS charges input tokens for the text and
   audio output tokens for the speech
   ([../google-cloud-tts-setup.md](../google-cloud-tts-setup.md#cost)), and it has no free tier.
   Characters are only a rough proxy:
   - Input tokens are not characters, and the tokenizer is not exposed.
   - Output tokens probably dominate. They grow with the length of the speech, which depends on
     the voice, speaking rate and style prompt as well as the text.
   - Whether the style prompt is billed as input has not been confirmed.
   - The response gives no token counts either.

### What 005 already provides

- **A definition of billable usage:** the characters sent to a provider, counted as Unicode code
  points, with the message and the style prompt counted separately. A cache hit sends nothing. A
  failed call is still counted, with `outcome="failure"`.
- **One measurement point:** `TtsService.synthesize` builds a `SynthesisUsage` (provider name,
  type, tier, text and style-prompt characters) just before the provider call. It is the only call
  site of `TtsProvider.synthesize` for every provider type, so a budget check belongs there and can
  refuse the request that would cross the line.
- **A tier breakdown:** `TtsProvider.billingTier(settings)` gives the Google Cloud engine family
  (`Standard`, `Wavenet`, `Neural2`, `Studio`, `Chirp-HD`, `Chirp3-HD`, `other`), the Gemini model,
  or `none`. That is the key Google's per-family free-tier allowances need.
- **Visibility:** `tts_synthesis_characters_sum` by `provider` and `tier`, with a "this month so
  far" query in [../metrics.md](../metrics.md). The counts can be compared with a real invoice
  before any cap is enforced. The audio-seconds metric allows the same comparison for Gemini.

## Proposal

Count locally, per provider entry, and check before calling the provider.

- **A `CharacterBudgetTracker`** (name open), next to `TtsService` and `ProviderRegistry` rather
  than in `multiroom.tts.provider.cloud`, because it is not Google-specific. It keeps persisted
  monthly totals keyed by provider entry and, for Google, by tier, similar to the cache's
  `index.json` bookkeeping, so a restart or redeploy does not reset the month. The Prometheus
  metrics cannot serve here: they reset on restart, and the extension cannot read Prometheus.
- **Configuration:** `monthly-character-budget` on any provider entry, an explicit integer. A
  per-tier limit is needed for Google's per-family allowances.
- **The check** goes in `TtsService.synthesize`, reading the same `SynthesisUsage` that 005
  records, so it is implemented once, not per provider. It applies only on a cache miss, because
  a hit sends nothing.
- **When the budget is reached:** either reject with a new `TtsErrorCode` (for example
  `PROVIDER_BUDGET_EXCEEDED`), which changes the REST error contract, or fall back to a configured
  provider. This is open question 1.
- **Documentation for operators:** for Google, the count is an estimate the extension controls,
  not a ledger Google confirms. It undercounts if another client uses the same project, and it
  cannot follow Google changing its free-tier thresholds. For OpenAI it is a plain spend cap. For
  Gemini, a character cap is only a proxy for the token bill.

Not covered: reading usage back from Google (findings 1–4), and any cap on cached announcements,
which cost nothing.

## Open questions

1. **Reject or fall back** when the budget is reached? Rejecting adds an error code to the REST
   contract. Falling back needs a `fallback-provider` key and a rule for when the fallback is also
   over its budget.
2. **Gemini's unit:** characters (text plus style prompt: simple, but can drift far from the bill),
   seconds of audio (closer to output tokens, but known only after the call, so it stops the
   *next* request), or tokens estimated from both using Google's published ratios (closest to the
   bill, but drifts when Google changes the tokenizer or prices)? Compare 005's metrics with a
   real Gemini invoice before choosing.
3. **`free-tier-only: true` sugar for `google-cloud`**, resolving to Google's current published
   allowance for the entry's voice family? Or just document "set `monthly-character-budget:
   4000000` for WaveNet", which cannot silently drift when Google changes its numbers?
4. **Month boundary:** UTC or the host's local time zone? Either way, document it as an
   approximation of Google's billing cycle.
5. **Counting failures:** should a failed provider call count against the budget, as 005's metric
   counts it? Google may or may not bill a call that errors.
