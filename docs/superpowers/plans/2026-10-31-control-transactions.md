# Local Control Transactions Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development for the independent writer and daemon tasks. Root owns broad checks and integration.

**Goal:** Failed local permission changes must never become durable through a concurrent unrelated save; successful changes must not be overwritten by stale unrelated snapshots.

**Architecture:** Extend the proven preview transaction writer to a closed set of local control groups. Each write normalizes every protected field from the last durable state, then applies only its explicitly selected group's snapshot. Update the group's durable shadow only after persistence succeeds. Device/project/secret lifecycle fields retain their existing semantics.

**Tech Stack:** TypeScript, Node 24.18.0, Vitest, existing ConfigurationWriter/SerialQueue/HostDaemon.

**Spec:** Existing remote-development-goal.md authorization and reliable remote-control requirements, and the preview persistence regression established by protected-preview-integration.md.

## Global Constraints

Preserve unknown/malformed durable values and absent-key semantics, deep-clone all snapshots, preserve ordinary ConfigurationWriter behavior when no initial configuration is provided, require initialized durable state for explicit control writes. No production grants, external configuration, push or deployment. Public Pi APIs only.

## Review Focus

- Failed enabled/grant changes leaking through push.register; require actual encrypted host/device regression.
- Successful changes overwritten by an older queued snapshot; require gated writer regression.
- Explicit preview save accidentally persisting another tentative group; require cross-group failure regression.
- Deep mutation and absent/malformed keys; require preservation tests.
- Owner commands updating live behavior or broadcasting before successful persistence; inspect every mapped handler and test effective behavior after failure.

## Interfaces and files

`host/storage.ts`: export closed `ConfigurationControlGroup` union with groups `preview`, `browser`, `terminal`, `browse`, `files`, `bash`, `git`, `tickets`. `ConfigurationWriter.saveControls(config: Configuration, group: ConfigurationControlGroup): Promise<void>` captures only that group's candidate. Keep `savePreview(config)` as a compatibility wrapper. Protected fields: previewControl/previewGrants, browserControl/browserGrants, terminalControl/terminalGrants, browseRoot, fileWrites, bashCommands, gitWrites, ticketWrites. Ordinary `save(config)` normalizes all initialized protected fields at queued execution.

`host/storage-control-transaction_test.ts`: independent deterministic gates test failed/successful writes, cross-group snapshots, ordering, malformed/absent nested values and missing initialization. Existing preview and storage tests remain green.

`host/daemon.ts`: `save(controlGroup?: ConfigurationControlGroup)` replaces boolean flag. Preview/browser/terminal policy callbacks explicitly select their group. Browse-root/file-writes/bash-commands/git-writes/ticket-writes handlers select their own group. All unrelated save callers remain ordinary; no broad mutation refactor.

`host/bash_worker_daemon_test.ts`: adapt existing encrypted race harness to a legacy control, demonstrate RED failed local save + concurrent push.register leaks authorization on restart, then GREEN. Add successful transition plus ordinary save preservation and independent-group coverage where meaningful.

## Execution

- [x] Write and observe failing grouped-writer tests; implement writer and run focused tests.
- [x] Independently reproduce legacy daemon race before wiring; wire exact explicit groups and prove persistence/live behavior.
- [x] Independently review stable combined diff, run focused and required checks; commit locally and update evidence. Preserve full-goal qualification boundaries.

Qualification: 2,878 host/client/protocol tests passed, 17 skipped (107.31 s); required package check passed with 92 existing warnings / 3 infos. Bound independent review round 1 found no actionable finding. Baseline encrypted grant and browse-root regressions were observed before correction. No live user controls changed.
