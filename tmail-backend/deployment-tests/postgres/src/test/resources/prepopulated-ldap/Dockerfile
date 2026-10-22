# The base image is pinned by digest: the tag alone lets the registry serve a
# different image on the next build, which would make the test fixture
# unreproducible. Update the digest deliberately, with the tag it belongs to.
FROM osixia/openldap:1.5.0@sha256:18742e9c449c9c1afe129d3f2f3ee15fb34cc43e5f940a20f3399728f41d7c28

COPY populate.ldif /container/service/slapd/assets/config/bootstrap/ldif/data.ldif
