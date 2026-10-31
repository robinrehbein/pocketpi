# Remote development environment implementation plan

**Goal:** Implement the expanded goal in `docs/remote-development-goal.md` without presenting partial capabilities as complete.

**Architecture:** The existing encrypted Pi Remote channel carries authenticated control messages. Native workers retain process and terminal ownership on Mac or Linux. Android exposes negotiated capabilities and treats an uncertain write as uncertain rather than resending it. Media streaming and shared browser interaction require their own bounded transport design and qualification.

**Execution:** Independent Terra implementation agents, medium reasoning for bounded changes and high reasoning for terminal ownership and protocol integration. Main owns integration and tracker duties. Review each increment independently before shipping. No merge, deployment or release is implied.

## Constraints

- Mac and independent Linux hosts with devcontainers have equal priority.
- Galaxy Tab S11 and Galaxy S25 require real-device acceptance; an emulator or passing build is insufficient.
- An Android emulator and the Mac iOS simulator are in scope. Wear features follow watch-specific usefulness as defined in `docs/remote-development-goal.md`; a Wear emulator is not required.
- Human and agent share browser tabs and login state with explicit input handoff.
- Use public Pi APIs and the existing protocol. Keep unimplemented capabilities disabled.
- Preserve existing local trust, device revocation and Git-write restrictions.

## Increments

1. Host readiness and platform terminology. Add read-only configured-host diagnostics and remove Mac assumptions from generic UI text. Verify version boundaries, missing PTY support, state-directory checks and resource parity.
2. Native terminal host integration. Reuse `session.terminal.v1`, resolve an owned worker through the authorized session and bind input/output to the authenticated connection and exact runtime incarnation. Enforce generations, input sequences, explicit takeover, bounded ordered encrypted output, paste/key semantics and cleanup. Verify stale sessions, revoked access, slow output, disconnect and worker exit.
3. Android terminal. Implement strict v1 parsing and attachment state first, then the interactive renderer, input controls, resize and keyboard/mouse support. Expose it only after host negotiation. Verify malformed and stale events, sequence gaps, early events, reconnect and uncertain input.
4. Worker parity and development tools. Qualify existing shell/PR paths, add missing native lifecycle controls and project search, and preserve editor drafts with explicit file conflicts. Each protocol addition precedes its Android integration.
5. Browser and preview. Prove a protected project-bound HTTP/WebSocket preview independently of the isolated artifact viewer. Then integrate a shared agent/human browser with explicit ownership. Qualify cookies, redirects, HMR, authentication and network boundaries.
6. Emulators. Prove supported host/container configurations before choosing a streaming implementation. Integrate live video and touch/keyboard input with bounded latency, exclusive ownership and reconnect. Qualify Android on suitable Mac/Linux hosts and iOS on Mac; no Wear emulator is required.
7. Device acceptance. Complete ticket to PR/CI workflows on both real Samsung devices, unattended Mac and independent Linux/devcontainer targets. Exercise DeX, orientation, hardware input, Wi-Fi/mobile transitions, app process death and host/container restart. Record interruption and recovery rather than claiming processes survive their owner.

## Verification

Run focused regression tests while implementing. Run the repository's required checks once on the combined stable change, plus an independent diff review. Record actual commands and failures. Linux, browser, simulator and real-device qualification remain open until the corresponding environment has been exercised.
