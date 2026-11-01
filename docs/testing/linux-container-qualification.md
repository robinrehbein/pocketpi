# Local Linux container runtime qualification

Status: **a production Host-owned RPC Pi session passed in an isolated local Linux/ARM64 container**. This is not independent remote-host, native PTY, Devcontainer, Android, model-turn or universal Linux qualification.

## Environment and isolation

The already-running local Docker Desktop daemon used cached official `node:24-bookworm`, immutable image `sha256:14078204b6bb268c107ceff6d3c43f45a888105e5c2b18b14c32b3409d93a1b0`, Linux/ARM64, Node v24.22.0. No image was pulled, network enabled, port published, Docker socket mounted or existing service changed. Containers had a read-only root and task-owned source mounts, dropped capabilities, no-new-privileges, bounded CPU/memory/PIDs, and private temporary storage. Temporary execution was enabled only inside the container for its private Pi launcher.

A task-owned copy contained the runtime source and resolved pnpm dependency closure: 238 wrappers, 21,082 regular files, 204,765,815 bytes before copying. It was based on Host commit `557a9664e` with in-progress, disabled paged-source changes; therefore this is evidence for the staged snapshot, not a claim that a later commit was tested. The staged remote/source manifest SHA-256 is `832e1220f09bc7193d59190347bd93a18c61985606fa086b28d6943959519a9c`. The installed portable Pi CLI reported 1.4.0. Original dependency installations and personal Host configuration were not modified.

## Observed production path

The actual `cli.ts serve-local` ran as a directly observed child. Separate real admin and viewer IPC connections shared a private trusted project, negotiated a viewer identity, and launched a Host-owned RPC session. Assertions verified:

- A live worker's `/proc/<pid>/exe` resolved to `/usr/local/bin/node`, and its parent PID matched that directly observed Host.
- The exact launched session appeared in the Host list with Host ownership and the private project CWD.
- The listed session file's header matched the session ID/CWD; describe and snapshot matched its session and incarnation.
- The Host's successful launch path waits for the real worker's `get_state` response. This last point is a source-based prerequisite of the observed launch result; the probe did not independently print that response.
- Host shutdown returned exit code zero, the recorded worker PID disappeared, and private temporary state was removed. The container command exited zero; an additional successful Docker listing showed no remaining task-labelled containers.

Pi changes its displayed process name to `pi`. The probe records that name without claiming the complete original command line remains available. Process executable/ancestry, the owned exec launcher and matching Host/session observations provide the evidence instead.

## Failures retained and limits

Earlier candidates failed on mixing admin/view roles on one socket, then a non-executable temporary mount, then an overstrict command-line assertion after Pi changed its process name. Each Host exited cleanly; the corrected final run passed. These were probe/setup corrections, not production fixes.

Private evidence: `/private/tmp/pocketpi-linux-host-run5.log`, `pocketpi-linux-cli-diagnosis.log`, `pocketpi-linux-full-image.txt`, `pocketpi-linux-container-cleanup.log`, and `pocketpi-linux-stage-source-manifest.json`. Earlier run logs 1–4 remain available. The private scripts are `pocketpi-linux-stage.py` and `pocketpi-linux-host-probe.ts`.

The original installed node-pty has no Linux/ARM64 addon. A subsequent isolated offline build copied node-pty/node-addon-api to private container storage and used the cached compiler, Python, make and Node headers. The build exited zero. Its actual PTY shell accepted input, produced the expected output with echo disabled, reported `37 91` after resize to 37 rows and 91 columns, and exited zero. The build and PTY child exits were observed, and the private copy was removed. Proof: `/private/tmp/pocketpi-linux-pty-build-run1.log`. No addon was installed in the original tree or persisted from the container. This is a working Linux PTY-library path, not qualification of a Host-managed native Pi session. The successful RPC test did not require that addon. An independent Linux host remains unavailable; no external SSH access or physical Samsung evidence is implied.
