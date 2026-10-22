# Accepted HIGH findings — dependency, secret and misconfiguration scan

This file is the versioned inventory of the HIGH findings that the Trivy scan
of this repository reports and that are **accepted, not remediated**. Every
other HIGH finding, and every CRITICAL finding, must fail the build.

The scan itself is `.github/workflows/supply-chain.yml`. It reports every
severity, publishes the full report as the `trivy-report` artifact, and fails
the job when a **CRITICAL** finding is present. The policy test
`ci/test-supply-chain-policy.test.cjs` asserts the gate, the reporting and the
existence of this file.

**None of the entries below is remediated.** They are listed so that the
residual risk is explicit, dated and owned, not hidden.

Last reviewed: 2026-10-22, Trivy 0.76.1 database.

## 1. Sample RSA key pairs — 3 secrets (HIGH, rule `private-key`)

| File | Component |
|---|---|
| `tmail-backend/plugins/mail-reporting/sample_conf/jwt_privatekey` | mail-reporting plugin sample configuration |
| `tmail-backend/tmail-third-party/ai-bot/sample_conf/jwt_privatekey` | ai-bot third-party sample configuration |
| `tmail-backend/tmail-third-party/openpaas/sample_conf/jwt_privatekey` | openpaas third-party sample configuration |

**Why accepted.** They are sample credentials for the local demo stacks of the
third-party integrations. They are not production credentials:

- a real deployment mounts an externally generated key pair, as documented in
  `tmail-backend/apps/distributed/README.md`
  (`openssl genrsa -out jwt_privatekey 4096`);
- the sample compose files that mount them already tell the operator to
  replace them ("Replace with absolute path to your RSA private key");
- the key pairs are published by the upstream project and have no value: no
  real instance ever trusts them.

**Residual risk.** A private key in a public repository can never be
"un-leaked"; anyone can use it against a demo stack that is reachable.

**Remediation path.** Generate the sample key pair on first use instead of
committing it, so the repository never carries key material. Not done here
because it changes the behaviour of the published sample stacks.

## 2. `CVE-2026-3505` — BouncyCastle `bcpg-jdk18on` 1.82 (HIGH)

> bouncycastle: BC-JAVA: unbounded PGP AEAD chunk size leads to pre-auth
> resource exhaustion

The version is declared by this repository in `tmail-backend/pom.xml`
(`<bouncycastle.version>`), so the fork owns it.

**Remediation path.** Bump `<bouncycastle.version>` to 1.84, the first release
that fixes the advisory, and validate it with the `JMAP / Apache James real
Maven smoke` workflow. This is a crypto library bump and is therefore tracked
as its own pull request rather than folded into the scan gate, so it can be
validated and reverted independently.

## 3. `DS-0002` — "Image user should not be 'root'" (HIGH, 4 files)

| File | Why root is needed |
|---|---|
| `ci/jenkins-runtime.Dockerfile` | installs `git` at build time; the Jenkinsfile runner itself needs the privileges of the pipeline container |
| `dockerfiles/jenkins-runner/Dockerfile` | same, plus the Docker daemon access that the pipeline uses |
| `tmail-backend/deployment-tests/distributed-ldap/src/test/resources/prepopulated-ldap/Dockerfile` | the upstream LDAP image expects to start as root to materialise its configuration |
| `tmail-backend/deployment-tests/postgres/src/test/resources/prepopulated-ldap/Dockerfile` | same |

**Residual risk.** A compromised build step of these images would run as root.

**Remediation path.** Add an explicit non-root `USER` for the runtime phase
and keep the privileged phase scoped to the package installation, one image at
a time, each validated by the deployment tests that use it.

## 4. `DS-0029` — "'apt-get' missing '--no-install-recommends'" (HIGH)

**Status: fixed** in `dockerfiles/jenkins-runner/Dockerfile`; the flag was
added the same way as in `ci/jenkins-runtime.Dockerfile`.
