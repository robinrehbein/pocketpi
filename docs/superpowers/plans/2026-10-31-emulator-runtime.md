# Owned Android Emulator Runtime Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development with the disjoint file ownership below. Root owns lifecycle integration, broad checks and real emulator operations.

**Goal:** Build the real host-owned Android emulator capture/input backend that the mobile/Pi emulator capability will use, without advertising an incomplete capability.

**Architecture:** A managed emulator and private ADB server are child processes owned by the runtime. An authenticated local gRPC client captures bounded PNG frames; a private ADB smart-socket client dispatches only finite validated input commands to one bound transport. Every asynchronous boundary rechecks the stable project/target authority. A later service/protocol/app layer adds device grants and control leases before exposing this backend.

**Tech Stack:** Node 24.18.0 builtins, Android SDK Emulator 37.1.11 public protobuf API, public ADB smart-socket protocol, Vitest.

**Spec:** ../../testing/android-emulator-feasibility.md and ../../remote-development-goal.md.

## Constraints

- Public Pi APIs only. No core patch, live user AVD/config/grant mutation, dependency installation, external publication or default ADB server access.
- Only explicitly configured, runtime-started emulator processes; never attach a client-chosen serial, PID, endpoint or physical device.
- Runtime state/private socket/keys must be private, owned, canonical and bounded. JWT private key stays in memory; public key cleanup occurs on success/failure/close. No credentials enter frames, results or logs.
- gRPC captures use JWT auth, exact protected-method audience, loopback only, strict HTTP/2 status/content type/framing/protobuf/PNG checks, five-second deadline, at most four MiB response and one MiB PNG. No gRPC key input: controlled semantic test failed.
- ADB client uses a private Unix smart socket and a bound transport ID; it must never auto-start or fall back to another server/device. Raw smart-socket client avoids adb CLI's implicit daemon restart behavior. Requests and shell-v2 output have strict size/deadline limits.
- Input whitelist: tap, swipe (50..1000 ms) and Home/Back/Enter/Tab/Escape/ArrowLeft/ArrowRight/ArrowUp/ArrowDown/Backspace. Integer coordinates inside confirmed geometry. No arbitrary shell, text, clipboard, installation or process commands.
- Input is serialized, checked against the expected geometry revision, never retried after dispatch; ambiguous completion is explicit. No CSS/browser tab semantics are reused.
- Display overrides are refused until correctly qualified; frame/ADB geometry and rotation must agree before coordinates are admitted.
- Failed child termination retains ownership/lock accounting; cleanup must not kill a process solely because a PID appears in a file. Host crash adoption and all remaining full-goal gates stay explicit.

## Shared interfaces

`host/emulator-types.ts`: immutable target contract with own PID, canonical AVD identity, loopback gRPC port/discovery path, private ADB socket, serial, and authority guard. Geometry width/height/rotation 0..3/revision, bounded PNG frame and finite input union. Guards are server-owned functions, never remote payload fields.

`host/emulator-grpc.ts`: `EmulatorGrpcClient({target,authorize})`, `connect():Promise<void>`, `capture({maxWidth,maxHeight}):Promise<EmulatorFrame>`, `close():Promise<void>`. It owns JWK/HTTP2 resources and parsing. It does not launch or choose targets. Related codec may live in emulator-grpc-codec.ts. Worker owns those files and tests.

`host/emulator-adb.ts`: `EmulatorAdbChannel({target,authorize})`, `connect():Promise<void>`, `geometry():Promise<EmulatorGeometry>`, `input(command,expectedRevision):Promise<void>`, `close():Promise<void>`. Bind transport once through the private server, verify qemu and boot identity; read only fixed geometry commands. Register the owned emulator ADB port through the documented host service if required. Worker owns this file and tests.

`host/emulator-runtime.ts`: root-owned supervisor. Validate explicit SDK/AVD/profile and private state; own lock and children; start private ADB server and emulator with explicit JWT gRPC on owned ports; construct target guards, await bounded readiness; compose captures with before/after geometry checks; stop admission on guard/child failure; close channels, terminate only owned children and release locks only after confirmed death. Real integration test uses only the disposable PocketPiBrowserView AVD.

## Review Focus

- Fragmented/coalesced malformed gRPC/ADB messages, length overflow, duplicate protobuf fields, PNG dimension/payload mismatch and cancellation cleanup.
- Forged/replaced discovery/JWK paths, foreign PID/AVD/server, physical device/transport rebinding and expired authority across awaits.
- Child exits during open/capture/input/close; failed cleanup retains ownership without leaking unbounded resources.
- Geometry changes while queued or during capture, untrusted coordinates and input acknowledgement versus ambiguous dispatch.
- Private ADB server startup/socket isolation, real JWT rejection and visual capture/input effect rather than fake-success adapters.

## Tasks

- [x] Write failing malformed/authorization/cleanup tests, implement strict gRPC capture client and pass focused checks.
- [x] Write failing private-server/transport/geometry/input uncertainty tests, implement bounded ADB channel and pass focused checks.
- [x] Implement owned supervisor with lifecycle failure tests and a real SDK/AVD test proving authenticated capture plus actual key/tap effect.
- [x] Independently review the stable combined patch, run affected/required checks, commit locally and record exact qualification limits.
- [ ] Next full-goal package: owner settings/grants and CLI, session/Pi/device service, versioned emulator protocol and ACK-paced app pane, actual encrypted phone/tablet/handoff tests. Backend alone does not complete that package or the full goal.

Qualified backend evidence: [owned emulator runtime](../../testing/owned-emulator-runtime.md). The final next-package checkbox deliberately remains open: no app/device emulator capability is implemented yet.
