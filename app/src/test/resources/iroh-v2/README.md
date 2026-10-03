# Upstream V2 signing vectors

`signing.json` is copied unchanged from `manaflow-ai/cmux` commit
`4c5272e9153eca2033c9f40ac749f0c3a5bcb291`:

`Packages/Shared/CmuxIrxTransport/Tests/CmuxIrxTransportTests/V2/Fixtures/signing.json`

This public test fixture deliberately uses an `07` repeated seed, fake account
identifiers, and precomputed Worker signatures. It is not a user credential and
must never become an installation key. JVM test resources are not production
assets. It follows cmux's GPL-3.0-or-later license; see root LICENSE/NOTICE.
Tests check canonical bytes and all three Ed25519 signatures using JDK 17's test
provider. This does not select Android's production signer or enroll a device.
