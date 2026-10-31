# Linux host qualification

## Persistent core image, 2026-10-31

A repository-maintained multi-stage core image now builds from the locked standalone archive and
pinned Node base. Its final runtime is nonroot, starts the installed Host CLI in the foreground,
and keeps private Pi/Remote state in an explicit owner volume. The entrypoint forwards original
arguments with exec and removes provisioning variables for normal Host and Pi invocations.
It never configures a relay, grants project trust or imports host credentials automatically.

Actual local qualification uses Docker Engine 29.7.2 (6a43e3d), Linux arm64 / kernel
7.0.12-linuxkit, Node 24.22.0 and final image ID
sha256:dbcab2780f5e8174626669b309ee3deb5b876e8b0fa00b8ecd9e00afbd2bffaa.
The installed Pi/CLI/native PTY verifier passes as node with read-only root, no network and no
capabilities. A separate image-level lifecycle flow passes explicit setup, private 0700 state /
0600 configuration and secrets, refused duplicate setup, refused simultaneous host ownership,
identity persistence across container replacement, actual Host-process environment scrubbing,
SIGTERM and SIGINT shutdown, restart and owned-resource cleanup.

The first final-runtime check failed because copied metadata was readable only by root; the
runtime COPY modes were corrected and the actual nonroot check then passed. Independent review
also found that an initial test helper could delete an existing resource after a name collision.
The corrected helper tracks successful Docker create IDs, verifies an instance label and immutable
image identity before start/removal, and checks volume ownership before and after creation and
before removal. Four failure regressions cover foreign resources, collisions and changed labels.
The final bounded review found the reported defect resolved.

Sixteen helper/resource tests and Biome pass. The local lifecycle fixture uses a synthetic token,
network none and no published ports; it removes only its owned test containers/volume. This is
not a connected relay, worker turn, Chromium renderer, Dev Containers or independent-host proof.
The image intentionally contains no configured Chromium renderer or Android SDK. Same-UID project
code is trusted; separate state and workspace paths are not a secret-isolation boundary.

Build, explicit owner setup, status and stop commands are maintained in the Host repository under
packages/pi-atlas/deploy/linux-host/README.md. Evidence:
 /private/tmp/pocketpi-linux-core-build2.log,
 /private/tmp/pocketpi-linux-core-readonly2.log,
 /private/tmp/pocketpi-linux-core-lifecycle-final.log,
 /private/tmp/pocketpi-linux-core-verified-tests.log,
 /private/tmp/pocketpi-linux-core-verified-biome.log.


## Current standalone package installation, 2026-10-31

The current Atlas archive was built from Host commit `1db5fcb4`, then installed into a separate
Linux consumer with its own npm lockfile. No checkout, Mac dependencies, user home or credentials
were mounted into that consumer. Pi packages are pinned to 1.4.0; Atlas resolves Typebox 1.1.38,
while the declaring Pi packages resolve their own pinned Typebox 1.3.27. Linux-native node-pty
1.1.0 was built against the pinned Node image (24.22.0 / ABI 137); the artifact is ELF aarch64.

The independent root verifier passed both normally and with no network, read-only root and
consumer, all capabilities dropped, no-new-privileges and temporary `/tmp`. It proves package
realpaths stay inside the consumer, public Pi `--version`, exact output/exit through the installed
production PTY wrapper, and the installed Host CLI's stopped-status path. It does not configure a
relay or prove a worker turn, Chromium sandbox, shipped OCI image, devcontainer or independent
remote host. The prior overlay-based runs below retain their narrower historical scope.

The new packaging helpers, consumer dependency lock, artifact integrity record and detailed
reproduction/limits live in the Host repository under
`packages/pi-atlas/deploy/linux-host/README.md`. Four context-helper tests and Biome pass. The
bounded independent review found no additional concrete code issue and correctly distinguished
matching a supplied hash from independently proving package provenance.

Evidence: `/private/tmp/pocketpi-linux-clean-install2.log`,
`/private/tmp/pocketpi-linux-clean-verify3.log`, `/private/tmp/pocketpi-linux-clean-readonly.log`,
`/private/tmp/pocketpi-linux-context-root-tests.log`. Scratch consumer and archive are retained
under `/private/tmp/pocketpi-linux-clean-consumer` and `/private/tmp/pocketpi-linux-package-artifacts`.
All qualification containers were automatically removed; unrelated containers were untouched.


## Local execution probe, 2026-10-31

The existing Docker Engine 29.7.2 can run isolated Linux aarch64 containers on this Mac.
Two existing images were inspected with no network, read-only root and checkout, dropped
capabilities, `no-new-privileges`, a temporary `/tmp`, and automatic container removal:

- `node:24.18.0-bookworm-slim`, image `6f7b03f7c2c8e2e784dcf9295400527b9b1270fd37b7e9a7285cf83b6951452d`.
- `noah-pi-atlas-task5-tools:ci`, image `7289518febb422cfd98a1939fdc1fbb3e67cb89cebcf7d596042d98e625c0708`.

Both report Node 24.18.0. The existing actual Pi CLI starts in the Node image and reports 1.4.0
when invoked by its full `packages/pi-atlas/node_modules/@earendil-works/pi-coding-agent/dist/cli.js`
path. A package-resolution check using the wrong package name initially failed; that was not a
Pi availability failure. The CLI version does not prove a complete Pi turn.

The mounted `node-pty@1.1.0` resolves but cannot load: its installed package has Darwin/Windows
prebuilds and no Linux-arm64 `pty.node`. Chromium and tmux were absent from the checked PATHs.
A third existing image, `dev-1767-compose-pi-share:latest`
(`1356c0ba6cdc0845b4140f81514c924b6f3d75263f0aabb2a0d4c632fd1facc4`), also reports Linux
arm64 and Node 24.18.0. It has no resolvable node-pty at the checked `/app` or global Node
module paths, and no Pi, Chromium, tmux, Python, make or g++ in PATH. Its default entrypoint
was overridden so no application service started. No packages were downloaded, user
configuration mounted, services started or containers retained.

## Native worker probe, 2026-10-31

The existing full `node:24-bookworm` image
(`14078204b6bb268c107ceff6d3c43f45a888105e5c2b18b14c32b3409d93a1b0`, Linux/arm64)
contains the required compiler tools and Node headers. Its Node is **24.22.0, ABI 137**;
the Mac checks use Node 24.18.0. These versions must not be conflated.

The installed node-pty 1.1.0 source and node-addon-api 7.1.1 were copied into a separate
scratch package. An offline node-gyp 12.4.0 build against `/usr/local` succeeded, using
Python 3.11.2 and make. No Mac dependency was overwritten. A container-only nested bind
mount places that compiled package over the package's canonical pnpm location inside the
read-only checkout. All runs disable network access, drop capabilities, use
`no-new-privileges`, read-only root and temporary `/tmp`, and remove the container afterwards.

The raw PTY and actual `spawnNativePty` wrapper produced the expected text/exit results;
actual Pi CLI 1.4.0 also started through that wrapper. A stronger direct smoke adapted the
existing `worker-controls_integration_test.ts` setup while avoiding the unavailable Linux
Vitest/Rolldown binding. It uses the production HostDaemon, native spawn wrapper, public Pi
provider API, real extension registration, and authenticated encrypted loopback device transport.

The agent run and independent root rerun both passed:

- `sessions.create` creates a host-owned worker, not an externally spawned substitute.
- Runtime is `pty`, native identity is `resolved`, and the extension peer is registered.
- Actual Pi start PID, PTY PID and registered owner PID match.
- A local offline provider turn completes; `session.bash` returns `LINUX_WORKER_OK`.
- `daemon.stop()` returns. A control-lost/identity-unresolved message occurs during shutdown;
  no stronger shutdown recovery or child-lifecycle claim is made.

Scratch sources and invocation are retained for this workspace under
`/private/tmp/pocketpi-linux-native/host-worker-smoke.ts` and
`run-host-worker-smoke.sh`. The independent output is `root-host-worker-rerun.log` in that
directory; `repro-notes.txt` records the offline build. These are local qualification artifacts,
not a shipped devcontainer or a new CI gate. The existing Vitest attempt failed before running
tests because its Linux Rolldown binding is absent; no dependency was downloaded to bypass it.

## Browser and worker qualification, 2026-10-31

A local Linux arm64 diagnostic image, `pocketpi-linux-host:qualification-20261031` (939 MB),
was prepared from the Node image with repository digest
`14078204b6bb268c107ceff6d3c43f45a888105e5c2b18b14c32b3409d93a1b0`. It uses Node 24.22.0,
Debian Chromium 156.0.8078.73, Pi 1.4.0 and a frozen pnpm 12.11.2 install with Linux-native
PTY and Rolldown dependencies. The Mac checks use Node 24.18.0. APT inputs were mutable, so the
image is not bit-reproducible and no Linux configuration has yet been packaged in the repository.

The root qualification run passed 26 tests: 24 browser tests, including three using real Chromium,
and two worker tests. Its log is
`/private/tmp/pocketpi-linux-environment/root-tab-order-qualification.log`. The product changes
use a short private `/tmp/pp-` location for Linux Chromium's `SingletonSocket` path limit, hide
scrollbars to avoid a classic 15-pixel viewport discrepancy while retaining strict viewport guards,
and preserve stable managed-tab order. These are local results for the browser-runtime changes;
see [browser context retention](browser-context-retention.md) for the integrated Android evidence.

The diagnostic Docker run used `seccomp=unconfined`. Chromium's own sandbox was active in the
observed processes (`NoNewPrivs: 1`, `Seccomp: 2`), but that observation does not qualify Docker's
production sandbox, a devcontainer, or an independent remote host.

## Bounded outer-seccomp experiment, 2026-10-31

A second local run replaces `seccomp=unconfined` with a bounded experimental profile based on the
[official Moby vendor profile at Engine Git revision 6a43e3d](https://raw.githubusercontent.com/moby/moby/6a43e3d/vendor/github.com/moby/profiles/seccomp/default.json).
The saved baseline SHA-256 is `536529b665dd0972c37bfb569f5d4ac8a53592e7b00752bc39ff063ca9864c74`;
the experimental profile is `cfae78bec8b973f940b33863c45d79296b63f539ed957fc43b16e303b71183e0`.
A structural comparison verifies the baseline is unchanged apart from three appended syscall rules:
restricted namespace flag combinations for `clone` and `unshare`, and `chroot` needed by Chromium's
own user-namespace sandbox after outer capabilities are dropped. `setns` and `clone3` rules remain
unchanged. The upstream JSON was parsed as data, not executed.

Root independently reran the same 26 browser/worker tests successfully with all outer capabilities
dropped, `no-new-privileges`, no network, no published ports and the bounded profile. Chromium's own
sandbox remained enabled. Agent inspection observed two seccomp filters in the renderer and denied
mount/IPC/UTS namespace probes while permitting the user namespace. Those observations do not
establish a complete syscall audit or equivalence to Docker's built-in policy.

The namespace and chroot allowances apply to the whole container and expose additional kernel attack
surface. This is validated only on local ARM64 Docker Desktop, with a writable container root and
read-only runtime/test source overlays; it is not a shipped production configuration. The profile's
clone argument index must not be reused on architectures with a different argument order. Secure
packaging, version update policy, intended-host qualification and threat review remain open.

Exact scratch provenance, delta verifier and invocation are in
`/private/tmp/pocketpi-linux-environment/SECCOMP-EXPERIMENT.md`, `verify-moby-profile-delta.py` and
`verify-linux-moby-experiment.sh`; the independent output is `root-moby-profile-tests.log`.

## Protected preview runtime, 2026-10-31

The endpoint-scoped preview component now passes 49 local Linux tests, including the real Chromium
preview, 25 browser-runtime tests, 10 proxy tests, 11 lifecycle tests and two real Pi worker tests.
The final invocation uses a disposable internal Docker network so the unprotected STUN control can
actually transmit; `--network none` could not supply that positive control. This exposed and fixed
an effective-runtime difference between Full Chromium and Headless Shell WebRTC switches. The
assertion of zero preview UDP packets was preserved. See [runtime qualification](protected-preview-runtime.md)
for the exact failed and passing runs and remaining security/production boundaries.

## Remaining gates

Local host review and device-only phone/tablet reconnect qualification passed. Package and qualify a reproducible Linux environment
with an appropriate production sandbox. Repeat terminal and browser transport, reconnect, input
ownership and process-failure checks on an independent remote Linux host and in a devcontainer.
Physical Galaxy device acceptance remains open. Local Docker and emulator results do not establish
those environments or the full remote-development goal.
