#!/usr/bin/env python3
"""Prepare a disposable Jenkinsfile Runner test of the actual Jenkinsfile guards.

Only the helper declarations before the production pipeline are reused. The
generated pipeline has no credentials, deployment, images, or external calls.
"""

from pathlib import Path
import sys


CANARY = r"""
node {
    stage('Real Jenkins CI trust boundary') {
        sh 'git --version'
        def protectedFiles = protectedCiPaths()
        if (!protectedFiles.containsAll(['Jenkinsfile', 'ci/', 'pom.xml', 'james-project'])) {
            error('Required Jenkins execution paths are not protected')
        }

        sh '''set -eu
            git init -q upstream
            git -C upstream checkout -qb master
            git -C upstream config user.name 'Disposable Jenkins'
            git -C upstream config user.email 'disposable@example.invalid'
            echo baseline > upstream/README.md
            git -C upstream add README.md
            git -C upstream commit -qm baseline
            git clone -q --bare upstream origin.git
            git clone -q origin.git consumer
            git -C consumer config user.name 'Disposable Jenkins'
            git -C consumer config user.email 'disposable@example.invalid'
            mkdir -p consumer/ci
            echo changed > consumer/ci/test-guard.sh
            echo untrusted > consumer/Jenkinsfile
            git -C consumer add ci/test-guard.sh Jenkinsfile
            git -C consumer commit -qm contributor
        '''

        dir('consumer') {
            withEnv(['CHANGE_TARGET=master']) {
                fetchChangeTarget()
                def offending = changedCiFiles()
                if (!offending.contains('ci/test-guard.sh') || !offending.contains('Jenkinsfile')) {
                    error('Trusted Jenkinsfile failed to detect contributor CI change')
                }
            }
            for (String target : [
                'master$(touch /tmp/jenkins-canary-injected)',
                'master; touch /tmp/jenkins-canary-injected',
                '../master',
                '-c core.sshCommand=evil'
            ]) {
              withEnv(["CHANGE_TARGET=${target}"]) {
                def rejected = false
                try {
                    fetchChangeTarget()
                } catch (Exception expected) {
                    rejected = true
                }
                if (!rejected) {
                    error('Unsafe CHANGE_TARGET was accepted')
                }
              }
            }
            sh 'test ! -e /tmp/jenkins-canary-injected'
        }
        echo 'PASS: actual Jenkins CI helpers rejected malicious branch and CI-file change'
    }
}
"""


def generate(repo: Path, destination: Path) -> None:
    source = (repo / "Jenkinsfile").read_text(encoding="utf-8")
    anchor = "\npipeline {\n"
    if source.count(anchor) != 1:
        raise ValueError("Cannot isolate the single production Jenkins pipeline")
    helpers = source.split(anchor, 1)[0]
    for name in ("def protectedCiPaths()", "def fetchChangeTarget()", "def changedCiFiles()"):
        if name not in helpers:
            raise ValueError(f"Required Jenkins helper unavailable: {name}")
    destination.mkdir(parents=True, exist_ok=True)
    (destination / "Jenkinsfile").write_text(helpers + "\n" + CANARY, encoding="utf-8")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: jenkins_runtime_canary.py OUTPUT_DIRECTORY")
    generate(Path(__file__).resolve().parent.parent, Path(sys.argv[1]))
