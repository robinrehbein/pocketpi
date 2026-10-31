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

## Dev Containers CLI lifecycle — 2026-10-31

The local adapter was exercised with the real public `@devcontainers/cli` 0.90.0
on macOS/Docker Desktop Linux arm64, using an isolated synthetic workspace. Host
checkout `02a22cc487` includes the detached-host environment fix `d3684a1a7`.
The tested image ID was
`sha256:ca5b4cd161806356332b4edcc5116352d9ba2da1ae3587e4667edd9dd5b5e007`.

Observed: CLI `up` starts the nonroot UID-1000 supervisor with read-only root,
no published ports or Docker socket, and an owner-labelled private state volume.
Before explicit setup, no host configuration or running host is created. A
one-shot synthetic setup starts the host and makes its local IPC status available;
`relay.invalid` deliberately leaves the relay disconnected. Provisioning token
variables are absent from the host process. Docker stop exits zero; restart and
container replacement preserve the exact configuration SHA-256. A second guarded
`up` reuses the same container. An older container missing the expected basis-image
label is refused, without deletion. Only the root qualification explicitly stopped
and removed its verified container and labelled test volume afterwards.

All 44 deployment-helper tests pass. Biome from the host repository root reports
zero errors (two intentional literal devcontainer-placeholder warnings and eight
style infos). Evidence: `/private/tmp/pocketpi-devcontainer-{real-up,guarded-up,reuse-up}.log`,
`/private/tmp/pocketpi-devcontainer-runtime-evidence.json`, and
`/private/tmp/pocketpi-devcontainer-all-unit-final.log`.

This proves a local disconnected lifecycle, not independent Linux-host operation,
UID-1001 migration, a connected Pi worker turn, Chromium sandboxing, Android SDK
inside the container, or physical Samsung qualification. Existing incompatible
state volumes are never silently chowned; Docker administrators remain trusted.

Follow-up checks passed against the same image: the existing core verifier repeats
all nine lifecycle checks, including duplicate setup/host refusal, TERM/INT shutdown
and restart (`/private/tmp/pocketpi-devcontainer-core-requalification.log`). The
packaged detached `start` retains custom HOME, state and Pi directories, strips
the provisioning token, answers status and stops cleanly
(`/private/tmp/pocketpi-packaged-background-runtime-final.log`).

The unmodified CLI 0.90.0 `updateUID.Dockerfile` was manually built with UID/GID
1001. A read-only nonroot runtime check confirms UID/GID 1001, passwd home
`/var/lib/pocketpi`, and ownership/mode 0700 for all three private state directories.
This qualifies the recipe on local Docker Desktop, not an independent Linux CLI
host or migration of existing volumes. Evidence:
`/private/tmp/pocketpi-devcontainer-uid1001-{build.log,evidence.json}`.
The merged upstream artifact-notice change also passes all 23 SessionControlsTest
cases (`/private/tmp/pocketpi-upstream-session-controls-final.log`).

## Packaged encrypted worker turn — 2026-10-31

The first standalone-image worker run exposed a real packaging defect: the compiled
host resolved hosted extensions relative to `dist/sessions`, where their entrypoints
are absent. Host commit `9c24350b0` resolves the exact installed Atlas package's shipped
source entries, verifies its package identity and rejects escaping paths. Source-checkout
and explicit test-override behavior remain covered. The consumer verifier now exercises
both actual installed extension resolutions; it rejects the old image before worker start.
Seven path regressions and the root combined 39-test path/background suite pass.

A rebuilt image, `sha256:4a46342c19b693c67eda4670c9004a6ff1c13c0296ccc9c90574a588ede14a69`,
passes the installed consumer verifier and the complete adapted encrypted worker smoke:
paired/authenticated device traffic through the real local relay, host-created native PTY
session, resolved native identity, extension registration, matching worker/PTY/owner PIDs,
a deterministic offline provider turn, and remote `session.bash` output `LINUX_WORKER_OK`.
Only the standalone fixture and relay source files were mounted read-only. Production
HostDaemon/native PTY/protocol/session modules and Pi were loaded from the installed image;
no checkout, Mac native dependencies, user home or credentials were mounted. Network was
disabled except container-internal loopback; the nonroot container had read-only root,
no capabilities, no-new-privileges, private temporary state and automatic removal.

Boundary: this is the production installed HostDaemon driven by a test harness with explicit
nativeSpawn/provider/connect/secret-store injections, not the packaged CLI or Devcontainer
entrypoint establishing a real remote connection. The provider is deterministic and offline.
`daemon.stop()` returned, with the existing control-lost/identity-unresolved shutdown diagnostic;
no stronger worker-recovery or independent remote-host claim follows.

Reproduction artifacts: `/private/tmp/pocketpi-packaged-worker/smoke.ts`, its `relay/`
subdirectory, `/private/tmp/pocketpi-packaged-worker-{first,second,build,root-tests}.log`,
and `/private/tmp/pocketpi-packaged-extension-verifier-red.log`.

## Regular packaged CLI connected flow — 2026-10-31

The same corrected image now passes the connected flow through its regular
`pocketpi-host setup` and `serve` entrypoint. The fixture creates a private CA and
TLS relay, configures the file secret store with synthetic credentials, installs
an offline provider through ordinary private Pi settings/extensions, records
project trust and shares the project through owner IPC. No HostDaemon, nativeSpawn,
relay-connect, prepare, persistence or trust callback is injected.

Observed through authenticated encrypted device commands: pairing, authentication,
`sessions.create`, successful `session.snapshot`, a real Pi process attached to a
PTY, accepted prompt and deterministic provider completion, and `session.bash`
returning exactly `PACKAGED_CLI_WORKER_OK` with exit zero. Host SIGTERM shutdown
also exits zero; the fixture verifies worker exit and closes its sockets/servers.
Both the successful first flow and the strengthened shutdown repetition pass.
Container restrictions remain nonroot, read-only root, network none with internal
loopback, capability drop, no-new-privileges, tmpfs state and fixture-only bind.
The automatically removed container never mounts a checkout or user credentials.

An initial fixture timeout was traced to event observation: Remote can emit its
bridge event inside its awaited session_start before the global provider's own
session_start handler runs. The fixture now latches that event and uses successful
public snapshot as the readiness proof. No product change was needed for this
fixture correction. The corrected image also passes all nine core lifecycle checks.

Evidence: `/private/tmp/pocketpi-packaged-cli/smoke.ts`, its isolated `relay/`
sources, `/private/tmp/pocketpi-packaged-cli-{final,shutdown}.log`, and
`/private/tmp/pocketpi-packaged-worker-core-lifecycle.log`.
This remains a local Docker Desktop Linux-arm64 proof with an offline provider;
Devcontainer connected entrypoint, remote network interruption, independent Linux
host, real provider credentials and physical Samsung devices are not qualified by it.

## Maintained CLI reconnect fixture — 2026-10-31

The connected smoke is now maintained under Host
`extensions/remote/runtime-test/linux-packaged-cli/`, with runner
`deploy/linux-host/verify-connected-cli.mjs`. The final runner passes against image
`sha256:4a46342c19b693c67eda4670c9004a6ff1c13c0296ccc9c90574a588ede14a69`
with `--reconnect`: cut every upgraded TLS tunnel, observe owner status offline then
online, authenticate the same paired device, snapshot the same session, confirm the
original worker PID/start time and one start record, then execute a new shell marker.
No mutating request is automatically retried; lost ACK/replay safety is not tested here.

The runner verifies its successful Docker-create ID, immutable image and nonce before
start and cleanup. Public fixture code is staged read-only and readable independently
of the host UID; no secrets enter staging. Review caught a premature PASS output before
outer cleanup, now covered by a failing-cleanup regression. Success is printed only after
container and staging removal. All 49 deployment helper tests pass; Biome reports zero
errors and eight fixture typing warnings. No application runtime changed in this increment.
Evidence: `/private/tmp/pocketpi-connected-maintained-{final,unit,biome}.log`.
Independent remote Linux and connected Devcontainer-supervisor qualification remain open.

## Connected supervisor entrypoint — 2026-10-31

The maintained runner now accepts `--supervisor`, combinable with `--reconnect`.
The actual image entrypoint starts its supervisor before setup and remains alive
for one second without config, IPC socket or child process. Explicit owner setup
then causes that supervisor to launch the regular host. The complete connected
TLS/pairing/native-Pi/snapshot/provider/bash/reconnect flow passes, followed by
supervisor exit zero, worker exit and verified outer cleanup. Image ID remains
`sha256:4a46342c19b693c67eda4670c9004a6ff1c13c0296ccc9c90574a588ede14a69`.

All 52 deployment-helper tests pass; Biome has zero errors and eight existing
fixture typing warnings. Evidence:
`/private/tmp/pocketpi-connected-supervisor-{reconnect,unit,biome}.log`.
This exercises the real entrypoint and supervisor in an isolated container, not
an actual connected Dev Containers CLI `up`; that composition remains open.

## Actual connected Dev Containers CLI up — 2026-10-31

After Git-first refresh, Host merged origin/dev at `e80a795203`; its reload/offline
and package-path tests pass all 68 cases. The updated archive SHA-256 is
`12002c70df64fe5a6af62dfa070b5dedc5cefe8be110fa51a5fb7d0531cb5633`.
The new core image is
`sha256:43194838911f0eb1e6c80e205001643e4731ab5f49211e4e76901319f9d73be9`.
A qualification-only derived image,
`sha256:40d893719c042bf7ef303e129cd4122695e05a732110c3527e414fae4e35d983`,
adds only fixed CA/trace paths and offline-test environment flags. It contains no
fixture credentials or private keys. The CA and keys are created in the private
volume before the supervisor starts its Host child; TLS verification stays enabled.

The real public CLI 0.90.0, invoked through the unchanged guarded runner and generated
profile, successfully starts that image. The owner fixture runs via Docker exec and
uses the already running supervisor, with no nested Host/Supervisor spawn. It passes
unconfigured waiting, explicit setup, TLS relay connection, encrypted pairing,
native Pi/PTY, public snapshot, deterministic provider turn, shell and reconnect
with the original worker. The outer owner runner then confirms container stop exit
zero, restart with identical configuration digest and live host IPC, a second clean
stop and removal of only its verified container/nonce-labelled volume. After the
fixture relay closes, restart proves IPC readiness, not a second connected relay.

Review found the external fixture could accept preexisting secrets without config.
It now requires remote and agent directories to be entirely empty before writes.
An actual negative container test confirms an existing synthetic Secret file is
unchanged and no qualification directory is created. The final positive Devcontainer
flow was repeated with a new instance and volume after this correction.

The external fixture intentionally returns `owner-container-stop-required`; its own
PASS does not claim the outer container/volume cleanup. The outer runner's final
proof and `cleaned: true` are in
`/private/tmp/pocketpi-devcontainer-connected-current.json`; commands are retained in
`/private/tmp/pocketpi-devcontainer-connected-run.py`. Other evidence:
`/private/tmp/pocketpi-devcontainer-connected-{up,flow,negative,core-build,qualification-build}.log`,
`/private/tmp/pocketpi-devcontainer-upstream-tests.log`, and
`/private/tmp/pocketpi-devcontainer-connected-unit.log` (52 helper tests).

This is local macOS/Docker Desktop Linux arm64 with a qualification-derived image,
synthetic credentials and an offline provider. It does not qualify an independent
Linux host, physical Galaxy devices, containerized Chromium/Android SDK, real provider
access, lost ACK/replay protection or connected-worker recovery after Host restart.
The ordinary self-contained supervisor/reconnect fixture also passes against the
updated core image after the external-mode refactor
(`/private/tmp/pocketpi-devcontainer-connected-core-normal.log`). Final Biome has
zero errors and eight existing fixture typing warnings.

## Locked Chromium layer and read-only sandbox proof — 2026-10-31

Git-first integrated the newer worker harness/busy fixes from origin/dev into Host
`16382bc498`; affected runtime tests pass 26 cases with six explicit skips. The current
archive SHA-256 is `a98f90b7266a91c9fbad66aad5ac3fd6078cdc36d2ca5930cec93bcfa6f7a45a`.
The qualified Core image is
`sha256:7415fea9b2afd8143436bcb98c8a88d7a5928105f36599547081546e364dbed9`.

The experimental `Dockerfile.browser` installs Chromium 156.0.8078.73-1~deb12u1
from signed Debian snapshots fixed at 20261030T000000Z. The maintained OS lock and
verifier check the three signed Release files, SHA-256 manifest of 111 downloaded
Debian packages and the installed package/version manifest. Only expiration checking
is disabled on these archived sources; archive authentication remains enabled.
The build wrapper binds its input alias to the exact qualified Linux-arm64 Core
image ID, uses one immutable snapshot of the lock bytes, refuses an existing output
tag, verifies the built image ID, and reports only after staging cleanup. The raw
Dockerfile alone does not establish Core provenance; use the guarded wrapper.

The Moby vendor profile was fetched again from its recorded official 6a43e3d URL:
its SHA-256 matches `536529b665dd0972c37bfb569f5d4ac8a53592e7b00752bc39ff063ca9864c74`.
The maintained experiment retains that baseline plus exactly three namespace/chroot
rules. JSON byte preservation has a scoped formatter exception; profile hashes and
structural-delta tests remain active. This is an experimental container-wide kernel
policy, not equivalence to Docker's built-in default or a completed security audit.

Actual installed production BrowserRuntime in the locked image passes local page
loading, text input, a 390x600 JPEG, confirmed owned browser exit, root write refusal,
nonroot UID 1000, zero outer effective capabilities, no-new-privileges and seccomp.
Chromium's own sandbox is enabled. CDP identifies the renderer PID and `/proc`
ancestry ties it to the owned browser; the renderer has two seccomp filters versus
one outer filter. The first argv-based renderer check timed out because sandboxed
child command lines were empty; CDP attribution fixed this test observation without
changing runtime security flags. User-namespace creation works; mount, IPC and UTS
namespace probes are denied. Those denials can also depend on dropped capabilities.

Root proof images include the locked build
`sha256:5f394f357f94ece34cbf05f487955255990e96b3a63ad77a033cfbd4d7cffc5e`
and the Core-bound wrapper build
`sha256:ac61ee5c95c1d3c2278b8d7ed1b474c7979744e6c28f8f40a7ba958d31749c4d`.
The source fixture is maintained at
`extensions/remote/runtime-test/linux-browser-layer/smoke.mjs` in Host. Evidence:
`/private/tmp/pocketpi-browser-{locked-build,locked-readonly-proof,locked-namespace-proof,bound-build,bound-readonly-proof,final-unit,final-biome}.log`,
`/private/tmp/pocketpi-browser-os-evidence.json` and component qualification metadata.
All 66 deployment/profile helper tests pass. Final explicit Biome check has no errors
and one optional-chain style warning in the fixture.

This qualification is local Docker Desktop Engine Git 6a43e3d, LinuxKit arm64 only.
The default Core/Devcontainer configuration does not automatically apply the profile
or enable browser grants. Remaining gates include maintained fail-closed runtime
engine/profile selection, broader sandbox review, encrypted browser/preview integration
against this image, intended independent Linux-host qualification and physical devices.
The final builder with the same-lock-bytes regression fix produced
`sha256:df328119714be21be7a1d0a332e43ebda790dd12327d0407306463b517564b4e`;
the exact image also passes the maintained read-only browser proof
(`/private/tmp/pocketpi-browser-final-readonly-proof.log`). Its build record is
`/private/tmp/pocketpi-browser-bound-build-final.log`.
