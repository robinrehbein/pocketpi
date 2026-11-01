# Ticket-started native development workflow

> **For agentic workers:** Use superpowers:subagent-driven-development. Terra authors work on disjoint files; root alone executes builds, tests and processes.

**Goal:** Start a real native Pi worker from a ticket through production Android navigation, then perform the already-qualified file/agent/shell/confirmed-Git workflow in that worktree.

**Architecture:** Add an explicit private ticket-workflow mode using the existing Host PlaneApi seam and production TicketStarts, real local Git worktree creation and one native Pi worker. Publish fixture metadata before a session exists. Observe the actual start job and bind the resulting session/worktree/worker before subsequent actions. A deterministic public provider handles the automatic ticket start prompt as one real turn, then the existing read/bash agent turn after the UI file edit.

**Tech stack:** Existing tickets.v1, production TicketStarts/PlaneApi, Git, Pi 1.4 public provider API and Compose instrumentation.

**Spec:** docs/remote-development-goal.md criteria 1–6 and existing native-agent-workflow plan. This is a bounded local integration test, not external Plane/GitHub/model or physical-device acceptance.

## Constraints and review focus

- No worker/session/start job before explicit UI start confirmation. Exactly one actual successful start must create the subsequent worker.
- Exercise actual ticket list/detail/start/status and worktree Git, not mocked successful sessions or fabricated completion.
- The automatic start prompt must reach the actual native agent loop before the later file/agent workflow; bind its ticket ref/title/URL/note hash and distinct assistant response.
- Preserve exact owner/session/runtime/worktree identity after creation and existing pre-confirmation Git guards.
- Fetch must reach only the owned local bare remote; a narrowly validated test adapter may replace only that exact origin with the owned target. Never fall back to public GitHub or owner credentials.
- PR/push checks must use the actual ticket branch and worktree, not the old fixed fixture-feature/main-checkout state.
- Root alone executes all runtime; no external writes, personal Host changes, default ADB or real credentials.

## Shared contract

Ticket mode extends `--native-worker --development-workflow` with `--ticket-workflow`. Metadata carries `ticketWorkflowFixture: true`, projectId/projectName, ticket ref/title/URL/note, expected ticket-start assistant marker and start-prompt digest; no sessionId is fabricated before start. Existing workflow file/agent/shell/commit/PR constants remain available.

Private status exposes start request count, selected project/ref/note, actual job ID/state/move and worktree/branch, worker count and initial/current owner. Before start, owner/session fields are explicitly null and worker/start counts zero. After success, Android derives its selection from the returned actual start session and verifies it against the observed worker and worktree. Concrete Git refs/file hashes and mutation counters remain authoritative.

For ticket mode the provider accepts an exact `WORKFLOW_TICKET_PROMPT` and returns a separate `WORKFLOW_TICKET_MARKER` in its first real turn. Trace order is `provider_ready`, `ticket_prompt`, `ticket_model_call`, `ticket_agent_end`, then the existing prompt/model/read/model/bash/model/agent_end events (11 total). Ticket prompt/model/end records include matching PID/session, prompt SHA-256 and success. Global model call count is 1 for ticket and 2/3/4 for subsequent workflow calls; the latter may retain phase-local callCount 1/2/3 plus globalCallCount. Non-ticket mode retains its existing 8 events and 3 calls. Extra events/turns or overflow invalidate the proof. Authors agree exact field names before final build.

## Tasks

- [x] Host: controlled ticket API, actual start/job observation, deferred worker ownership binding, real worktree/branch and exact local fetch transport, metadata/status.
- [x] Provider: optional ticket first turn, exact prompt identity and real assistant marker, then existing fail-closed workflow tool/context sequence.
- [x] Android: actual ticket menu/list/detail/start confirmation, await successful auto-navigation and actual captured worker, then existing full workflow; preserve zero-before/one-after assertions.
- [x] Root: focused type/format/build checks, private actual phone/tablet runs with pre-launch source/APK snapshots, evidence capture and exact owned cleanup.
- [x] Independent review and bounded qualification documentation; local commits only.

## Discovered production regression

The first tablet ticket route loaded data but displayed projectsPlaceholder instead of Tickets.
A focused wide navigation test reproduced the existing detail being hidden when the Sessions
list was absent; a private emulator screenshot and tag diagnostics confirmed the same state.
An initial unit fixture lacked its host-list entry and was corrected before the meaningful RED.
The minimal production fix gives an existing detail precedence over placeholders, preserving both
empty-project and empty-chat states. Root must verify targeted GREEN, broad quick-check and the
actual ticket-started tablet chain before closing this increment.

## Recorded results

Phone ticket chain passed in 114.246 seconds; tablet passed in 127.041 seconds after the
reproduced production scene fix. The original non-ticket chain passed in 88.476 seconds.
Focused layout RED/GREEN and the broader quick-check passed; reports contain 3,468 App and
34 Wear tests with zero failures/errors/skips. Independent code and runtime review found no
blocking issue for these local cases. Detailed source/APK boundaries and earlier failures
are recorded in ../testing/ticket-workflow-qualification.md.
