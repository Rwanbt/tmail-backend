const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')

const workflowPath = path.join(
  __dirname,
  '..',
  '.github',
  'workflows',
  'update-james-submodule.yml'
)
const workflow = fs.readFileSync(workflowPath, 'utf8')

test('updater uses exact pinned reviewable GitHub Actions revisions', () => {
  assert.match(workflow, /uses: actions\/checkout@[0-9a-f]{40}(?:\s|$)/m)
  assert.match(workflow, /uses: peter-evans\/create-pull-request@[0-9a-f]{40}(?:\s|$)/m)
  assert.match(workflow, /persist-credentials: false/)
})

test('updater grants only explicitly required write scopes', () => {
  assert.match(
    workflow,
    /^permissions:\s*\r?\n  contents: write\s*\r?\n  pull-requests: write\s*$/m
  )
  assert.doesNotMatch(workflow, /\bactions: write\b/)
  assert.doesNotMatch(workflow, /\bpackages: write\b/)
})

test('only a PR proposes a pinned James update; checkout is not silently rebased', () => {
  assert.match(workflow, /git submodule update --remote/)
  assert.match(workflow, /git add james-project/)
  assert.match(workflow, /peter-evans\/create-pull-request/)
  assert.match(workflow, /base: master/)
  assert.doesNotMatch(workflow, /git reset --hard/)
  assert.doesNotMatch(workflow, /\bgit push\b/)
  assert.doesNotMatch(workflow, /\bgit submodule update --remote\s+&&\s+(mvn|sh|bash)\b/)
})

test('privileged scheduled/manual update cannot run against a non-master ref or other submodules', () => {
  assert.match(workflow, /^\s+if: github\.ref == 'refs\/heads\/master'\s*$/m)
  assert.match(workflow, /git submodule update --remote james-project\b/)
  assert.doesNotMatch(workflow, /git submodule update --remote\s*\r?\n/)
})

test('pull request creation is probed before it is attempted', () => {
  assert.match(workflow, /id: pr-capability/)
  assert.match(workflow, /api\.github\.com\/repos\/\$\{GITHUB_REPOSITORY\}\/pulls/)
  assert.match(
    workflow,
    /if: \$\{\{ steps\.check-submodule\.outputs\.changed == 'yes' && steps\.check-submodule\.outputs\.fast-forward == 'yes' && steps\.pr-capability\.outputs\.capability == 'allowed' \}\}/
  )
})

test('a pull request creation refused by the repository policy is reported, never a red job', () => {
  assert.match(workflow, /capability=blocked/)
  assert.match(workflow, /not permitted to create or approve pull requests/)
  assert.match(workflow, /GITHUB_STEP_SUMMARY/)
  assert.doesNotMatch(workflow, /continue-on-error:\s*true/)
})

test('the pinned Apache James commit is never silently dropped by the updater', () => {
  assert.match(workflow, /merge-base --is-ancestor/)
  assert.match(workflow, /fast-forward=no/)
  assert.match(workflow, /fast-forward=yes/)
})
