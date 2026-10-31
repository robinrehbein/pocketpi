# Linux host qualification

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

## Remaining gates

Local host review and device-only phone/tablet reconnect qualification passed. Package and qualify a reproducible Linux environment
with an appropriate production sandbox. Repeat terminal and browser transport, reconnect, input
ownership and process-failure checks on an independent remote Linux host and in a devcontainer.
Physical Galaxy device acceptance remains open. Local Docker and emulator results do not establish
those environments or the full remote-development goal.
