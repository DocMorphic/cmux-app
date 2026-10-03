# Synthetic TLS fixture

`server-test-key.pem` is an intentionally public **test-only** PKCS#8 RSA key.
It belongs only to the `cmux-notice.invalid` fixture certificate in `server.pem`.
It is not an account credential or a production service key. Do not reuse it for
anything except the isolated instrumentation fixture.

The public issuing CA is bundled in the separate experiment at
`src/main/assets/probe/fixture-ca.json` (base64 DER). Its private signing key was destroyed
after issuing the leaf. The certificates expire on September 30, 2036. Regeneration
must replace all three fixture files together: create an RSA test CA with
CA/keyCertSign/cRLSign extensions, issue a CA:false serverAuth leaf with
`DNS:cmux-notice.invalid` in subjectAltName, and export its key as unencrypted
PKCS#8. Use fresh keys and destroy the CA signing key after issuance.

The bundled native-only extension temporarily resolves only that fixed hostname
to loopback, then explicitly trusts only the bundled CA. The test first requires
`ERROR_SECURITY_BAD_CERT` before adding trust. It never overrides a certificate
error or disables certificate validation. At teardown it deletes the fixture CA
and restores the prior `network.dns.localDomains` preference. This is inside the
experiment package's Gecko profile; it does not touch Android's system trust
store or cmux-app. Remove the experiment package after testing, including after
an interrupted run. These files and trust controls must not enter the main app.
