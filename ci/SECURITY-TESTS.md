# Deterministic security checks for the Apache James updater

Base fork SHA: `33b205af9557439b48ba8033f950958f30e8889b`.

The four-hour scheduled GitHub Action must **only** propose an upstream
Apache James gitlink update as a reviewable pull request. The checkout stays
at the SHA of the workflow trigger; an unreviewed moving `origin/master`
must never replace it during a privileged job. Actions are pinned to exact
Git commits, checkout credentials are not persisted, and the GitHub token
receives only `contents: write` plus `pull-requests: write` for PR creation.

Reproduction:

```sh
node --test ci/test-update-james-workflow.test.cjs
```

The three baseline tests failed against the unchanged workflow: moving
action tags, missing explicit token scopes and silent `git reset --hard`.
The new `ci-policy.yml` runs these checks on every PR and default-branch
push, including changes that affect only CI.

**Not certified by these checks:** Jenkins jobs, Maven unit/integration
tests, external Apache James behavior, container provenance and runtime
mail security. Their credentials, actual workflow runs and exact SHA
must be validated independently. Do not merge a Draft PR based only on
these three static workflow tests.
