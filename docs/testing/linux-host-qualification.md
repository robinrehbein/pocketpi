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

## Remaining gates

Install or build Linux-compatible dependencies in a separate test environment, then run actual
native Pi/PTY/browser flows over the encrypted remote transport. Repeat on an independent remote
Linux host and inside a devcontainer, including session reconnect, input ownership and process
failure. Local Docker execution, CLI version output and Mac qualification do not establish these
requirements. The repository's full development Compose stack is not needed for this isolated
runtime qualification and was not started.
