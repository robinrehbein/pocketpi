# Native Android image delivery acceptance

## Intended path

Use the real Application/MainActivity, system photo picker, photo importer, encrypted attachment
and draft stores, SecureRemoteTransport and native Pi SDK. Seed only owned MediaStore rows in the
existing private API-36 emulator; remove those exact rows on success or failure.

A deterministic black 2001 × 1 PNG becomes an actually measured 2001 × 1 JPEG through Android's
photo importer. Bind those imported bytes, MIME, size and digest to the real attachment upload
begin/chunk/commit and initial prompt attachment IDs. They differ from the original PNG and from
the SDK-normalized model image. Independently check the model's 2000 × 1 image against normalization
of the actual uploaded JPEG, and preserve the exact SDK resize hint without a delivery envelope.

## Receipt and recovery

Complete the fixture's real file/agent prelude before its lifecycle image turn; do not invent
agentVerified or bypass the default ordering guard. Finish image-upload acknowledgments before
holding the initial prompt's acceptance result and source user echo. Cut/reconnect on the same
native worker and require one exact user/source/request/delivery receipt, matching attachment
metadata and zero repeated image prompt attempts. Model proof is scoped to that image turn.

Qualify unchanged attachments clearing after receipt and a later distinct white replacement
remaining in the actual draft/store after the original receipt. The replacement is not sent.
Exercise phone and tablet layouts. The current emulator uses the Google Compose photo picker
with accessible photo descriptions and Done; verify actual UI selection rather than injecting a URI.

## Evidence and boundaries

Pin sources, controller and installed/build APKs; retain image hashes/dimensions and structured
proofs without image bytes, pairing secrets or raw encoded model payloads. Keep upload integrity,
source attachment metadata and post-SDK image proof separate. Run required checks after source
freeze and preserve failed attempts. Release TLS, physical devices, production providers and
independent remote hosts retain their separate qualification. The overall goal remains active.
