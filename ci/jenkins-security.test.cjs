const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync, mkdtempSync, existsSync, rmSync } = require('node:fs')
const { tmpdir } = require('node:os')
const { join } = require('node:path')
const { spawnSync } = require('node:child_process')
const jenkins = readFileSync(join(__dirname, '..', 'Jenkinsfile'), 'utf8')

test('untrusted fork cannot change trusted Jenkins/Maven/James execution contracts', () => {
  const block = jenkins.match(/def protectedCiPaths\(\)\s*\{([\s\S]*?)\}/)?.[1] ?? ''
  for (const path of ['Jenkinsfile', 'ci/', '.mvn/', '.gitmodules', 'pom.xml', 'james-project', 'tmail-backend/pom.xml']) {
    assert.ok(block.includes("'" + path + "'"), 'missing trust boundary: ' + path)
  }
  assert.match(jenkins, /if \(isTrustedContributor\(\)\)/)
  assert.match(jenkins, /error\("""This change request modifies files the CI executes:/)
})

test('target branch and temporary report names are data, not Groovy-interpolated shell code', () => {
  assert.match(jenkins, /withEnv\(\["SAFE_CHANGE_TARGET=\$\{env\.CHANGE_TARGET\}"\]\)/)
  assert.match(jenkins, /git check-ref-format --branch "\$SAFE_CHANGE_TARGET"/)
  assert.match(jenkins, /git diff --name-only "origin\/\$SAFE_CHANGE_TARGET\.\.\.HEAD"/)
  assert.match(jenkins, /git show "origin\/\$SAFE_CHANGE_TARGET:ci\/report-build-failure\.sh"/)
  assert.match(jenkins, /mktemp "\$TRUSTED_TMP_DIR\/report-build-failure-XXXXXXXX\.sh"/)
  assert.doesNotMatch(jenkins, /sh\s+"git (?:fetch|diff|show).*\$\{env\.CHANGE_TARGET\}/)
})

test('privileged Maven Jib publish avoids verbose credentials and remains gated for outside forks', () => {
  assert.match(jenkins, /if \(env\.CHANGE_FORK && !isTrustedContributor\(\)\)/)
  assert.match(jenkins, /if \(!approvedByMember\)/)
  assert.match(jenkins, /credentialsId: 'dockerHub'/)
  const jibLines = jenkins.split('\n').filter(line => line.includes('jib:build'))
  assert.ok(jibLines.length >= 5)
  for (const line of jibLines) {
    assert.doesNotMatch(line, /(?:^|\s)-X(?:\s|'|$)/)
  }
})

test('shell expansion of an untrusted branch name cannot become a command', { skip: process.platform === 'win32' }, () => {
  const dir = mkdtempSync(join(tmpdir(), 'twake-jenkins-shell-'))
  const badBranch = "feature$(touch " + join(dir, 'EXECUTED') + ")"
  try {
    // Replicates the exact single-quoted Groovy sh source. Shell variable
    // expansion inside double quotes does NOT evaluate nested shell syntax.
    const cmd = 'printf "%s" "origin/$SAFE_CHANGE_TARGET...HEAD"'
    const result = spawnSync('sh', ['-c', cmd], {
      env: { ...process.env, SAFE_CHANGE_TARGET: badBranch },
      encoding: 'utf8'
    })
    assert.equal(result.status, 0)
    assert.equal(result.stdout, 'origin/' + badBranch + '...HEAD')
    assert.equal(existsSync(join(dir, 'EXECUTED')), false)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})
