const assert = require('node:assert/strict')
const { execFileSync } = require('node:child_process')
const { existsSync, readFileSync } = require('node:fs')
const { join } = require('node:path')
const { test } = require('node:test')

const repoRoot = join(__dirname, '..')
const workflowPath = join(
  repoRoot,
  '.github',
  'workflows',
  'supply-chain.yml'
)
const workflow = readFileSync(workflowPath, 'utf8')

const trackedDockerfiles = execFileSync('git', ['ls-files', '*Dockerfile*'], {
  cwd: repoRoot,
  encoding: 'utf8',
})
  .split('\n')
  .map((line) => line.trim())
  .filter(Boolean)

test('every third-party action is pinned to an exact commit', () => {
  const uses = [...workflow.matchAll(/uses:\s*([^\s@]+)@([^\s#]+)/g)].map(
    (match) => `${match[1]}@${match[2]}`
  )
  assert.ok(uses.length >= 5, 'the supply chain workflow must use several actions')
  for (const use of uses) {
    assert.match(
      use,
      /@[0-9a-f]{40}$/,
      `action is not pinned to an exact commit: ${use}`
    )
  }
})

test('the supply chain workflow never grants a pull request scope', () => {
  assert.doesNotMatch(workflow, /pull-requests:\s*write/)
  assert.doesNotMatch(workflow, /contents:\s*write/)
})

test('vulnerabilities, secrets and misconfigurations are all scanned', () => {
  assert.match(workflow, /scanners:\s*vuln,secret,misconfig/)
  assert.match(workflow, /severity:\s*CRITICAL,HIGH/)
})

test('a scan finding fails the job instead of being reported and ignored', () => {
  assert.match(workflow, /exit-code:\s*'1'/)
  assert.doesNotMatch(workflow, /continue-on-error/)
  assert.doesNotMatch(workflow, /exit-code:\s*'0'/)
})

test('the bills of materials are published and identified by their digest', () => {
  assert.match(workflow, /format:\s*cyclonedx-json/)
  assert.match(workflow, /format:\s*spdx-json/)
  assert.match(workflow, /sha256sum/)
  assert.match(workflow, /GITHUB_STEP_SUMMARY/)
})

test('the bill of materials carries an attested provenance', () => {
  assert.match(workflow, /actions\/attest-build-provenance@[0-9a-f]{40}/)
  assert.match(workflow, /attestations:\s*write/)
  assert.match(workflow, /id-token:\s*write/)
  assert.match(workflow, /subject-path:\s*attest\/sbom\.cdx\.json/)
})

test('every tracked container image is pinned by digest', () => {
  assert.ok(trackedDockerfiles.length > 0, 'no tracked Dockerfile was found')
  for (const relativePath of trackedDockerfiles) {
    // The working tree is the source of truth when the file is checked out; a
    // sparse checkout hides it, and the committed content is then read from
    // git so that the policy still covers the whole repository.
    const absolutePath = join(repoRoot, relativePath)
    const content = existsSync(absolutePath)
      ? readFileSync(absolutePath, 'utf8')
      : execFileSync('git', ['show', `HEAD:${relativePath}`], {
          cwd: repoRoot,
          encoding: 'utf8',
        })
    const baseImages = [...content.matchAll(/^\s*FROM\s+(\S+)/gm)].map(
      (match) => match[1]
    )
    for (const image of baseImages) {
      assert.match(
        image,
        /@sha256:[0-9a-f]{64}$/,
        `base image is not pinned by digest in ${relativePath}: ${image}`
      )
    }
  }
})
