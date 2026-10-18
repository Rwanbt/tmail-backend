const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { join } = require('node:path')
const workflow = readFileSync(join(__dirname, '..', '.github/workflows/jmap-memory-smoke.yml'), 'utf8')
const modulePom = readFileSync(join(__dirname, '..', 'tmail-backend', 'pom.xml'), 'utf8')

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
  assert.match(workflow, /-Dtest=MemoryEmailGetMethodTest,MemoryEmailSetMethodTest,MemoryMailboxSetMethodTest,MemoryTeamMailboxMemberSetMethodTest,MemoryTicketAuthenticationTest,MemoryUnauthenticatedBlobAccessSetMethodTest/)
  assert.match(workflow, /python3 ci\/verify_jmap_surefire\.py/)
  assert.match(workflow, /MemoryUnauthenticatedBlobAccessSetMethodTest/)
  assert.match(workflow, /MemoryTeamMailboxMemberSetMethodTest/)
  assert.match(workflow, /MemoryTicketAuthenticationTest/)
  assert.match(workflow, /if: always\(\)/)
})

test('real Maven reactor does not contain duplicated managed MongoDB coordinates', () => {
  const managedDependencies = modulePom.match(/<dependencyManagement>([\s\S]*?)<\/dependencyManagement>/)?.[1] ?? ''
  assert.ok(managedDependencies.length, 'missing reactor dependencyManagement')
  const coordinates = [...managedDependencies.matchAll(/<dependency>\s*<groupId>org\.mongodb<\/groupId>\s*<artifactId>mongodb-driver-reactivestreams<\/artifactId>/g)]
  assert.equal(coordinates.length, 1, 'Maven rejects duplicate dependency coordinates before JMAP can run')
})

test('the Maven reactor has no duplicate coordinates in repaired failing modules', () => {
  const modules = [
    ['integration-tests/webadmin/postgres-webadmin-integration-tests/pom.xml', 'tmail-guice-distributed'],
    ['integration-tests/healthcheck/postgres-healthcheck-integration-tests/pom.xml', 'james-server-postgres-app'],
    ['jmap/extensions/pom.xml', 'james-server-data-ldap'],
  ]
  for (const [relativePath, artifact] of modules) {
    const pom = readFileSync(join(__dirname, '..', 'tmail-backend', relativePath), 'utf8')
    const dependencies = pom.split(/<dependency>/).slice(1).map(block => block.split('</dependency>')[0])
    const found = dependencies.filter(block =>
      block.includes('<artifactId>' + artifact + '</artifactId>'))
    const coordinates = found.map(block => ({
      artifact,
      group: block.match(/<groupId>([^<]+)<\/groupId>/)?.[1],
      type: block.match(/<type>([^<]+)<\/type>/)?.[1] || 'jar',
      classifier: block.match(/<classifier>([^<]+)<\/classifier>/)?.[1] || '',
    }))
    assert.ok(found.length > 0, 'missing required dependency ' + artifact)
    assert.equal(
      new Set(coordinates.map(c => [c.group, c.artifact, c.type, c.classifier].join(':'))).size,
      coordinates.length,
      'duplicate Maven dependency coordinates: ' + relativePath + ' ' + artifact
    )
  }
})
