# PocketPi Emulator Integration Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development. Workers own disjoint protocol/service/app files; root owns profiles/CLI/storage, daemon/index wiring, backend dispatch guards and all broad/SDK/Android checks.

**Goal:** Expose the qualified host-owned Android emulator through Pi and PocketPi with explicit local grants, bounded image delivery, ownership handoff and safe reconnect.

**Architecture:** One configured profile belongs to one project and names a private owned AVD/runtime. A session-aware service shares that profile's live context between eligible Pi/device participants. Public wire messages use emulator IDs and profile labels, never paths/ports/PIDs. PNG delivery is chunked and ACK-paced; local Pi snapshots fit the existing four-MiB IPC bound and return image content to the model.

**Tech Stack:** Existing public Pi extension/TypeBox/local IPC, Node24 runtime, strict JSON protocol, Android Kotlin/Compose/coroutines.

**Spec:** ../../remote-development-goal.md and ../../testing/owned-emulator-runtime.md. Full goal remains open beyond this package.

## Invariants

- Only v3 advertises `session.emulator.v1`, after actual configured/available profile + current eligible project/device grant; v1/v2 unchanged.
- New transaction group `emulator` protects `emulatorControl`, `emulatorProfiles`, `emulatorGrants`. Global switch off by default. Profiles max16, grants max256; duplicate IDs/AVDs/state dirs/ports refused. Creating/updating profiles and grants requires live owner IPC and current project/active-device validation. No production configuration changes in tests.
- Profile: `{id,label,projectId,emulatorExecutable,adbExecutable,avdHome,avdName,stateDir,consolePort,grpcPort}`. All local runtime paths explicit/canonical/private as required by backend. Grant `{deviceId,projectId,profileId}`. One profile belongs to one project; owner reassignment does not migrate old grants.
- Runtime limit two active/opening/closing contexts; failed cleanup still counts. Participants and stable known-source lifetimes bounded at64/context; pending operations32/actor. Per-profile startup may take120s, so open request deadline180s on app/local Pi. No auto-start of arbitrary profiles at authentication.
- A current actor, session incarnation, canonical trusted project, enabled profile and device grant are checked before/after awaits and at delivery/actual input dispatch. Runtime guard uses validated stable session sources independent of a dead socket. Last-reader disconnect can retain VM only while such authority remains valid.
- New peer/reconnect joins as reader, never replaying input or automatically acquiring. Explicit handoff targets a known eligible participant; lease/generation/sequence prevents stale control. Before coordinates, the participant must have received a complete frame for that generation/revision (or local snapshot delivery committed).
- Backend `inputDispatchGuard: true` plus optional third synchronous `beforeDispatch` argument is required by service: lease/actor guard and onMutationStart run at actual ADB input write, not only before a runtime queue wait. Input uncertainty revokes ownership and never retries.
- Logical wire geometry revision is separate from backend revision. Changed backend geometry fences queued input and invalidates delivered-frame stamps. No browser CSS/tab semantics on wire. No arbitrary text/shell/clipboard/install or paths/PIDs/ports from client.

## Frozen wire contract (protocol worker owns implementations)

All commands include `type,requestId,sessionId`; context commands add `emulatorId`.

- `emulator.list`.
- `emulator.open` adds `profileId`.
- `emulator.status`, `emulator.participants`, `emulator.detach` add context ID.
- `emulator.acquire` adds `expectedGeneration`.
- `emulator.handoff` adds `leaseId,generation,toOwnerId`.
- `emulator.release`, `emulator.stop` add `leaseId,generation`.
- `emulator.input` adds `leaseId,generation,geometryRevision,sequence,input` with backend finite key/tap/swipe union. Device coordinates are integer physical pixels; app maps the displayed image rectangle.
- `emulator.watch` adds `generation`; `emulator.unwatch` adds `streamId`; `emulator.frame.ack` adds `streamId,frameId,chunkIndex`.

Owner `{kind:'device'|'agent',id}`; Geometry `{width,height,rotation:0|1|2|3,revision}`; ProfileInfo `{id,label}`.

Results:
- `emulator.profiles {profiles}`.
- `emulator.opened {emulatorId,profileId,label,incarnation,generation,geometry,owner?}`.
- `emulator.status` same opened fields plus optional owner-only `control:{leaseId,nextSequence}`.
- `emulator.participants {emulatorId,generation,participants}`.
- `emulator.lease {emulatorId,generation,geometry,owner,leaseId,nextSequence}`.
- `emulator.busy {emulatorId,generation,owner}`.
- `emulator.input.ack {emulatorId,generation,sequence,status:'accepted'|'uncertain'}`.
- `emulator.released`, `emulator.detached`, `emulator.stopped` each `{emulatorId,generation}`.
- `emulator.watching {emulatorId,streamId,generation}` and `emulator.unwatched {emulatorId,streamId}`.

Events:
- `emulator.state`: `{type:'host.event',kind:'emulator.state',sessionId,emulatorId,profileId,label,incarnation,generation,geometry,owner?,control?}`; control only for its exact owner.
- `emulator.frame`: `{type:'host.event',kind:'emulator.frame',emulatorId,incarnation,streamId,generation,geometryRevision,frameId,chunkIndex,chunkCount,totalBytes,width,height,displayWidth,displayHeight,rotation,data}`. Canonical base64url PNG chunks48KiB, maxPNG1MiB/maxpixels8,388,608, ACK5s, interval250ms. All chunks share immutable metadata. Geometry revision>=1; dimensionspositive bounded16384; IDs256UTF8 bytes, label128, profiles16/participants64. Integers use JS safe range, unknown fields refused. PNG signature/IHDR dimensions checked on client before decode.

No `emulator.snapshot` wire message. Local Pi IPC endpoint is separate and returns bounded `{data:statusFields,png:<canonical base64url>}` after guarded capture; tool parses it and returns `{type:'image',mimeType:'image/png',data:<standard base64>}` plus metadata text. Agent must snapshot before coordinate input; registration identity never comes from tool parameters.

## Service / frame interfaces (service worker)

New `host/emulator-service.ts` exports EmulatorActor `{kind,id,deviceId?,authorize()}`, EmulatorSource `{projectId,canonicalProjectPath,guard(),sessionGuard?()}` and EmulatorService.
Options: `runtime.open(profileId,project)` returns OwnedEmulatorHandle; `enabled()`, `profiles(projectId,identity):ProfileInfo[]`, `allowed(identity,projectId,profileId):boolean`, `resolve(sessionId,actor):Promise<Source>`, `retentionSource(sessionId,identity):Promise<Source>`, `state?(actor,event,guard):Promise<void>`.
Methods: `execute(command,actor,{signal?,onMutationStart?}?)`, `guard(emulatorId,sessionId,actor)`, `captureAccess(emulatorId,sessionId,actor)` returns `{guard,capture(signal):Promise<FrameCapture>,delivered(generation,geometryRevision)}`; `disconnect(actorId)`, `revalidate()`, `close()`.
FrameCapture `{png,width,height,displayWidth,displayHeight,rotation,geometryRevision}`. Capture updates geometry fencing and state. delivered marks per-participant image only for the still-current generation/revision. Root uses it after a fully ACKed frame or guarded local snapshot reply.

New `host/emulator-stream.ts` exports EmulatorFrameSender options `{emulatorId,incarnation,streamId,generation,capture,authorize,send(event,guard,onDelivered,signal),onFrameAcknowledged(geometryRevision),onClose?}` with start/ack/close/completion. Mirror existing browser stream cancellation/early-ACK/backpressure protections, not browser metadata.

Service may reuse reviewed BrowserOwner internally as a generic lease queue, wrapping viewportRevision as wire geometry revision and mapping user-facing errors to Emulator; no new browser grants or state sharing. Stable authority reference counts must preserve a successful overlapping open if another same-session open fails. Failed-close tombstones retain capacity. stop requires current owner; detach leaves participant/revokes its control while valid retention may keep the VM.

New `emulator-tool.ts` uses public Pi registerTool `remote_emulator`: list/open/status/participants/acquire/handoff/input/release/detach/stop/snapshot. Owner/session/request IDs generated/bound locally; cancellation via `emulator.cancel`; request wrapper accepts optional timeoutMs. Root wires index/current peer and daemon local endpoints. Service worker owns new service/stream/tool files and tests only.

## Android interfaces (app worker)

Protocol worker owns NEW EmulatorProtocol.kt and tests, mirrored TS codec/parser types/functions. Follow BrowserProtocol naming: EmulatorCommand/Result/StateEvent/FrameEvent, encodeEmulatorCommand, parseEmulatorResult, parseEmulatorState, parseEmulatorFrame.

App worker owns new EmulatorClient/EmulatorFrames/EmulatorPane and existing repository/state/viewmodel/screens/actions/strings/tests. Independent state `RemoteState.emulator:EmulatorView?`, visible entry only negotiated v3; profile list/picker then open(profileId). Current selection/generation travels with every request. Separate control and frame state, startup status, finite input buttons/tap/swipe, participant handoff, explicit stop confirmation. Reader reconnect restores same profile/context if valid, no auto acquire/input; stale profile/route/project/session/incarnation/stream rejected. PNG assemblers ACK only validated chunks, commit image before input enable, clear control on errors/geometry changes. No fake browser IDs or text input promise. Root owns all Gradle/emulator operations.

## Root interfaces / wiring

Profile policy/CLI/storage group + tests, daemon runtime provider/capabilities/routes/frame stream map/peer lifecycle/local Pi snapshot and command endpoints, index tool registration. Profile add reads explicit owner JSON file (`emulator profile set <file>`); profile remove/list, grants/grant/revoke, config emulator-control. Offline enabling/revoking allowed under existing offline lock, offline creation refused. Actual device/project/profile validation occurs on live host. availability probes are read-only; no startup at authentication.

## Gates

- [x] Write failing strict protocol and parity tests; implement TS/Kotlin codecs and preserve old message fixtures.
- [x] Implement session/lease/retention/frame service and public Pi tool with failure/overlap/stale-owner/delivery tests.
- [x] Implement transactional owner profiles/grants/CLI and daemon/device/Pi wiring; prove no unauthorized runtime start, input or frame delivery.
- [x] Implement app client/pane/navigation/capability/reconnect handling; test independent browser/preview/emulator state and late results.
- [ ] Qualify actual encrypted phone/tablet + actual target AVD with Pi/device handoff, visible input, frame ACK, reconnect, uncertainty and revoke. Use a separate root-owned target AVD so the app does not render itself recursively.
- [ ] Independent bound review, required checks, local commits and exact remaining full-goal qualification record.
