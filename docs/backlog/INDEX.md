# Backlog index

Every item in `docs/backlog/`, highest priority first. Each one is a proposal, not a requirement
(see the Backlog section of [AGENTS.md](../../AGENTS.md)). When you add, ship or delete an item,
update this list.

| Priority | Meaning |
|---|---|
| **P1** | A crash, data loss or other user-visible fault. Do it next. |
| **P2** | Wrong or confusing behaviour, or something production needs now. |
| **P3** | A worthwhile feature or cleanup with no pressure on it. |
| **P4** | Only worth doing once a concrete need appears. |

| Priority | Item | Kind | Effort | Why this priority |
|---|---|---|---|---|
| P2 | [Pin cache entries atomically and count the pins](reference-counted-cache-pins.md) | Bug | Small–Medium | Two identical announcements to different targets can delete each other's audio on the ordinary path; needs the cache at its cap or an operator clear, so not yet seen |
| P3 | [Cap each provider's monthly usage with a local character budget](provider-character-budget.md) | Feature | Medium | Protects against an accidental bill; 005 already measures the usage, so only the cap remains |
| P3 | [Adopt HUB-040: a chime before an announcement](adopt-route-chime.md) | API adoption | Small | The hub can start a route with a short sound; an announcement cannot ask for one yet. Works unchanged on a 0.1.34 hub without it |
| P4 | [Make the announcement queue survive a stop and a restart](queue-stop-start-contract.md) | Bug (latent) | Small | Leaks and a dead worker only after `stop()` then `start()` in one process, which nothing does today |
