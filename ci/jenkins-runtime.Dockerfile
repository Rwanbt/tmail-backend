# Historical official Jenkinsfile Runner contains the pipeline engine but no Git.
# Pin the verified upstream manifest digest from Actions run 39156165399.
FROM jenkins/jenkinsfile-runner@sha256:1510739cb658772e733899a56e7ababb44cc113ee7fa22ec915c5fd0e7bff7a2

USER root
RUN apt-get update \
    && apt-get install --no-install-recommends -y git \
    && rm -rf /var/lib/apt/lists/*

RUN git --version
