# Backlog index

Every item in `docs/backlog/`, highest priority first. Each one is a proposal, not a requirement.
When you add, ship or delete an item, update this list. Longer investigations that are not yet a
proposal stay in [../future/](../future/).

| Priority | Meaning |
|---|---|
| **P1** | A crash, data loss or other user-visible fault. Do it next. |
| **P2** | Wrong or confusing behaviour, or something production needs now. |
| **P3** | A worthwhile feature or cleanup with no pressure on it. |
| **P4** | Only worth doing once a concrete need appears. |

| Priority | Item | Kind | Effort | Why this priority |
|---|---|---|---|---|
| P3 | [A route that fails to start is cleaned up twice](failed-start-double-cleanup.md) | Bug | Small | Since hub 0.1.22 a failed start logs `TTS_PLAYBACK_COMPLETED`, a WARN stack trace, and runs the completion callback twice. Harmless today, misleading in the logs |
