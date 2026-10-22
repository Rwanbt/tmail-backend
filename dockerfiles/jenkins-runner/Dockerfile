# The base image is pinned by digest, with the same verified manifest digest as
# ci/jenkins-runtime.Dockerfile: the tag alone lets the registry serve a
# different image on the next build, which would make the runner image
# unreproducible.
FROM jenkins/jenkinsfile-runner:adoptopenjdk-11-hotspot@sha256:1510739cb658772e733899a56e7ababb44cc113ee7fa22ec915c5fd0e7bff7a2

# Install git, docker
RUN set -x && \
  apt-get update && \
  apt-get install -y --no-install-recommends wget git unzip apt-transport-https ca-certificates curl gnupg2 software-properties-common && \
  curl -fsSL https://download.docker.com/linux/$(. /etc/os-release; echo "$ID")/gpg > /tmp/dkey; apt-key add /tmp/dkey && \
  add-apt-repository \
    "deb [arch=amd64] https://download.docker.com/linux/$(. /etc/os-release; echo "$ID") \
    $(lsb_release -cs) \
    stable" && \
  apt-get update && \
  apt -y dist-upgrade && \
  apt-get -y install docker-ce && \
  apt -y autoremove && \
  apt clean && \
  rm -rf /var/lib/apt/lists/*

# Install Maven 3.6.3
ENV MAVEN_VERSION 3.9.9
RUN curl -Lf https://downloads.apache.org/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz | tar -C /opt -xzv
ENV M2_HOME /opt/apache-maven-$MAVEN_VERSION
ENV maven.home $M2_HOME
ENV M2 $M2_HOME/bin
ENV PATH $M2:$PATH
