const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { join } = require('node:path')
const workflow = readFileSync(join(__dirname, '..', '.github/workflows/jmap-memory-smoke.yml'), 'utf8')

test('real JMAP smoke is isolated, nonprivileged and checks pinned James provenance', () => {
  assert.match(workflow, /submodules: recursive/)
  assert.match(workflow, /persist-credentials: false/)
  assert.match(workflow, /permissions:\s*\n  contents: read/)
  assert.match(workflow, /git -C james-project rev-parse HEAD/)
  assert.match(workflow, /test -n "\$expected" && test "\$expected" = "\$actual"/)
  assert.doesNotMatch(workflow, /continue-on-error:\s*true/)
  assert.doesNotMatch(workflow, /(?:docker (?:push|login)|jib:build|gh release create)/)
})

test('a Maven zero-tests success is never presented as JMAP integration PASS', () => {
  assert.match(workflow, /-pl :memory-jmap-integration-tests -am/)
  assert.match(workflow, /-Dtest=MemoryEmailGetMethodTest/)
  assert.match(workflow, /reports = list\(location\.glob/)
  assert.match(workflow, /assert tests > skipped/)
  assert.match(workflow, /assert not failures and not errors/)
  assert.match(workflow, /if: always\(\)/)
})
