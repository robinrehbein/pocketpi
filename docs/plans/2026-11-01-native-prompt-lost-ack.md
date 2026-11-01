# Native prompt lost-acknowledgment implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development. Terra HIGH Host author/reviewer and MEDIUM Android author. Root alone runs builds, tests and processes.

**Goal:** Recover a plain initial native prompt accepted by Pi when Android received neither its acknowledgment nor user echo, without resending it.

**Architecture:** A private fixture drains and pauses the sole authenticated original device outbox before dispatch, blocks replacement upgrades, independently attests actual accepted prompt/provider-held state and queued-but-unsent acknowledgment/echo, then cuts the real socket without skipping sealed frames. Android observes NOT_CONFIRMED and verifies zero matching decoded payloads before reconnect. Reconnect must resolve the exact mutation from a durable source user-message receipt, retain a single prompt and same native worker, and continue the existing Stop/follow-up/development chain.

**Tech stack:** Existing TypeScript Host/extension/protocol and Kotlin Android repository/instrumentation.

**Spec:** docs/remote-development-goal.md reliable remote control and reconnect. Initial plain prompts currently lack the durable steer/follow-up receipt. Existing follow-up delivery is not a substitute for this scenario. Autonomous implementation is authorized.

## Review focus

Prove actual acceptance before cut, no receipt received on Android, no in-progress seal when pausing, no alternate matching peer, no automatic duplicate through recovery, and preserve genuine uncertainty when no authoritative receipt exists. Durable receipt changes must preserve strict legacy Host/client/source compatibility, exact request/session identity, metadata redaction and visible prompt text. No second writer, Pi core patch or fabricated acceptance. Lost acknowledgment differs from ordinary active reconnect and process death.

## Tasks

- [x] Clean ff-only update of both worktrees and independent audit/contract.
- [x] Root build/typecheck, run actual plain-prompt lost-ack RED with bounded Android receiver observation.
- [x] Regression-first durable initial-prompt receipt contract/implementation if RED demonstrates gap; focused and required broader checks.
- [x] Actual phone/tablet recovery proof plus unchanged profile regressions as needed; source/APK pinning and cleanup.
- [x] Independent final review, precise qualification, local commits and canonical checkpoint. Overall goal remains active.

## Result

Implemented after actual and focused RED evidence. Both current phone/tablet text workflows, SDK image-normalization/source-correlation checks and required local checks pass; see [qualification](../testing/native-prompt-lost-ack-qualification.md). The complete remote-development goal remains active.
