# openpaas sample configuration

This directory holds a ready-to-mount configuration for the **local demo stack**
started by `docker-compose-carddav-sample.yml` in the parent directory.

## `jwt_privatekey` / `jwt_publickey`

These two files are an RSA key pair used by the sample stack to sign the JWT
that the TMail backend presents to the openpaas instance.

**They are sample credentials. They are not production credentials.**

- The key pair is published by the upstream project and has no value: it is
  never trusted by a real openpaas instance.
- The sample compose file mounts it so the demo starts with a single command,
  and the same line already tells the operator to replace it:
  `./sample_conf/jwt_privatekey:/root/conf/jwt_privatekey # Replace with absolute path to your RSA private key`.
- A real deployment never mounts this directory. It generates its own key pair
  and mounts it from the host, as documented in
  `tmail-backend/apps/distributed/README.md`:

  ```
  openssl genrsa -out jwt_privatekey 4096
  openssl rsa -in jwt_privatekey -pubout > jwt_publickey
  ```

**Never reuse this key pair outside the local demo.** Generate a dedicated one
for any environment that can be reached by another host.

The secret scanner reports this file, and it is the single entry of the
repository `.trivyignore`, with that justification. The policy test
`ci/test-supply-chain-policy.test.cjs` asserts that the ignore file contains
exactly this one path, so the accepted risk cannot silently grow.

## Known limitation

The key pair is committed to the repository, which means it can never be
"un-leaked". The correct fix is to generate the sample key pair on first use
instead of committing it. This is tracked as a residual risk in the
qualification matrix; it is deliberately not changed here because it would alter
the behaviour of the published sample stack.
