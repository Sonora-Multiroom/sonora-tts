# Keep the Hub's API JAR Out of the Public CI Cache

**Status:** Implemented 2026-11-13, awaiting verification. Delete this item once a new cache is shown
to hold no `ai/multiroom`.

**Origin:** Found while planning `sonora-youtube` (`sonora-multiroom` backlog
`youtube-resolver-as-extension.md`), whose CI will copy this repository's workflow.

**Depends on:** nothing.

**Effort:** Small.

## Problem

`test.yml` installs the private hub's `multiroom-api` JAR and POMs into `~/.m2`, and
`setup-java`'s `cache: maven` saved them as an Actions cache that a fork's pull-request workflow of this
public repository could restore.

## Done

- `.github/workflows/test.yml` ends with an `if: always()` step that runs
  `rm -rf ~/.m2/repository/ai/multiroom` before the cache is saved.
- The two existing caches were deleted with `gh cache delete --all`.
- The hub's extraction procedure (`docs/extensions/extracting-an-extension-to-its-own-repo.md`, section 5)
  names the step, on hub branch `docs/extension-ci-cache-note`.

## Open questions

- **Verify by unpacking.** After the workflow's first run on `master`, check the new cache (or the
  post-step log) for `ai/multiroom`. Not yet possible: the change is not pushed.
- **`sonora-youtube`.** Its workflow must carry the same step from its first commit.
- **Still needed if `multiroom-api` and the starter are published?** Then the step can go.
- **Raise the approval rule?** "Require approval for all external contributors" would also cover a
  returning contributor, at a click per outside pull request.
