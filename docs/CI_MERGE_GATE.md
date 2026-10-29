# CI merge gate

`ci-required` is the only required status-check name. It combines the existing
core jobs with applicable supplemental checks in the **same CI run and tested
revision**. A passing workflow is not evidence that GitHub enforces that check:
the repository must separately activate the main ruleset below.

## Execution contract

`ci.yml` starts for every pull request, every main push and `merge_group`.
The existing nine core job IDs (including the two engine-process matrix legs)
remain mandatory, with their original commands, assertions and timeout budgets.

For a PR, `change-scope` compares the checked-out test merge with its first
parent. This checks the proposed change against the actual integrated base,
not a cached PR head or another workflow's result. The checkout includes both
parents. Git's NUL-delimited diff has no API file-count limit and includes both
sides of a rename; a failed diff or unexpected checkout fails scope selection.
Main pushes and merge groups run all supplemental lanes, including for
multi-commit pushes, rather than guessing a partial change range.

The path policy lives in `scripts/ci_merge_gate.py`:

| Caller | Existing verification retained |
| --- | --- |
| `fox-search` | Linux/Xvfb and native Windows Fox search, including the required keyboard probe |
| `b11-speed-notice` | Native Windows Swing windows at four scales and all supported locales |
| `native-focus` | Pinned source-built Windows CPU engine, move-focus and dual-engine parameters |
| `acceptance-integration` | Catalog-bound Linux CPU engine and real SGF dialogs |

The original PR paths remain covered. Common CI/runner/build inputs select all
lanes. The engine catalog also selects native Windows acceptance; native engine
build dependencies and board/rules changes are included. The Windows lane
still uses its pinned **source-built CPU engine**, not every catalog binary;
this change does not claim GPU or final-package acceptance.

The four workflow files expose `workflow_call` and manual `workflow_dispatch`.
CI calls their local paths so code, workflows and scope share a revision.
They no longer create duplicate path-filtered PR runs. Each retains its existing
job implementation, matrix, execution assertions, evidence and read-only
permissions. Manual runs have separate concurrency groups and cannot supply
results to a PR's gate.

`ci-required` uses `always()` and waits for every core job, scope selection and
all four callers. The original core-result validator is retained. The additional
validator requires successful scope selection and every literal `true`/`false`
output. Selected lanes must finish `success`; only a lane explicitly selected
`false` may finish `skipped`, reported as **not applicable, not executed**.
Failure, cancellation, a selected skip, missing output/job or an unknown result
blocks the gate. There is no polling, cross-run status borrowing, write token,
new secret, relaxed test assertion or `continue-on-error`.

## Activate main protection separately

[main-ci.json](../.github/rulesets/main-ci.json) is an importable GitHub branch
ruleset proposal. Committing or merging this file does **not** activate it.
Its scope is only `refs/heads/main`. It requires PRs, but adds zero required
approvals and no signing/linear-history policy. It requires `ci-required` from
the GitHub Actions app (integration ID 15368), enables strict up-to-date checks
and has no bypass actors. Adding `merge_group` support does not enable a merge
queue.

An authorized repository administrator should:

1. Verify this PR's exact-head CI and merge it, then verify that main SHA's push
   CI. Inspect existing branch protection and all repository/inherited rulesets;
   reconcile existing policy rather than replacing or disabling other rules.
2. Import the reviewed JSON through **Settings → Rules → Rulesets → Import a
   ruleset** and activate it. Existing rulesets should be edited/reconciled, not
   duplicated. The JSON's `active` value is an instruction at import time, not
   a statement about current live settings.
3. Re-read the live rules and inspect a PR merge box. Verify main targeting,
   the expected GitHub Actions source, strict base freshness and the absence of
   administrator/routine bypass. A current failure or pending `ci-required`
   must block merging; an older head's pass does not satisfy the rule. Do not
   weaken checks to complete this verification.

Read-only inspection examples (administration permission may be needed):

```bash
gh api repos/22nsuk/KG-next/branches/main
gh api 'repos/22nsuk/KG-next/rulesets?includes_parents=true'
gh api repos/22nsuk/KG-next/rules/branches/main
# Use an ID returned by the list, not a guessed ID:
gh api repos/22nsuk/KG-next/rulesets/RULESET_ID
```

A permission error is not proof that protection is absent. Record the live
ruleset ID and enforcement state after activation. Neither tests of this JSON
nor a successful CI run establish that server-side enforcement is enabled.
Repository administrators can still deliberately edit policy; this is not an
immutable security boundary or a substitute for reviewing workflow changes.

GitHub references: [required-check troubleshooting](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks),
[available rules](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets),
[importing rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/managing-rulesets-for-a-repository).

## Verification

```bash
python3 -m unittest scripts.test_ci_merge_gate
python3 -m unittest scripts.test_publish_release_request scripts.test_run_local_ci
python3 scripts/check_line_endings.py
python3 scripts/check_markdown_links.py
git diff --check
```

The focused tests exercise real Git merge/deletion/rename diffs, a change beyond
350 files, failed scope discovery, mixed applicability, all non-success states,
CLI failure exits, same-run workflow wiring and the ruleset's exact policy.
They do not simulate GitHub's server-side merge enforcement. Published CI must
also validate reusable workflow expansion and execute the selected real lanes.
Release publishing, native/GPU package policy and the application's Java behavior
are unchanged.
