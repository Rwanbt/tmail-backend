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

// The Apache James submodule update and the pull request that carries it are
// manual operations owned by the maintainer. This workflow therefore reports
// the drift between the pinned gitlink and the remote head, and must never be
// able to open a pull request, push a branch or grant the token a
// pull-request scope.

test('the workflow can never open a pull request', () => {
  assert.doesNotMatch(workflow, /create-pull-request/)
  assert.doesNotMatch(workflow, /pull-requests:\s*write/)
  assert.doesNotMatch(workflow, /api\.github\.com\/repos\/.*\/pulls/)
  assert.doesNotMatch(workflow, /--request\s+POST/)
  assert.doesNotMatch(workflow, /\bgh\s+pr\s+create\b/)
  assert.doesNotMatch(workflow, /pr-capability/)
})

test('the workflow token is granted read-only access', () => {
  assert.match(workflow, /^permissions:\s*\r?\n\s+contents: read\s*$/m)
  assert.doesNotMatch(workflow, /:\s*write\b/)
})

test('the workflow never writes to the repository', () => {
  assert.doesNotMatch(workflow, /\bgit push\b/)
  assert.doesNotMatch(workflow, /git add james-project/)
  assert.doesNotMatch(workflow, /\bgit commit\b/)
  assert.doesNotMatch(workflow, /git reset --hard/)
  assert.match(workflow, /persist-credentials: false/)
})

test('the submodule update and its pull request are reported as manual maintainer work', () => {
  assert.match(
    workflow,
    /The Apache James submodule update and its pull request are managed manually by the maintainer/
  )
  assert.match(workflow, /never opens a pull request/)
  assert.match(workflow, /GITHUB_STEP_SUMMARY/)
})

test('the pinned Apache James commit can never be silently dropped', () => {
  assert.match(workflow, /merge-base --is-ancestor/)
  assert.match(workflow, /fast-forward=no/)
  assert.match(workflow, /fast-forward=yes/)
  assert.match(workflow, /git submodule update --remote james-project\b/)
  assert.doesNotMatch(workflow, /git submodule update --remote\s*\r?\n/)
})

test('the drift stays visible and the job is never permanently red', () => {
  assert.match(workflow, /schedule:/)
  assert.match(workflow, /cron:/)
  assert.doesNotMatch(workflow, /continue-on-error/)
  assert.doesNotMatch(workflow, /exit 1/)
})

test('the workflow uses exact pinned reviewable GitHub Actions revisions', () => {
  assert.match(workflow, /uses: actions\/checkout@[0-9a-f]{40}(?:\s|$)/m)
  assert.doesNotMatch(workflow, /uses:\s*[\w-]+\/[\w-]+@(?![0-9a-f]{40}\b)(?:v?\d|main|master)/)
})
