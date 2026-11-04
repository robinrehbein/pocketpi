# Active native turn reconnect implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development. Terra HIGH Host fixture author/reviewer, MEDIUM Android instrumentation author; root executes every build/test/process.

**Goal:** Preserve and resume control of the same actively streaming native Pi turn across an actual Android transport loss without replaying the prompt.

**Architecture:** Add a dedicated active-cut variant of the existing native lifecycle fixture. The controlled public provider holds after emitting visible partial output. Cut the real proxy socket and block upgrades while it remains held; require observed offline, same native identity and zero aborts. Reconnect to a running chat with the same partial once, then use the real Android Stop, verify native cancellation and interrupted UI, and complete the existing follow-up/development workflow.

**Tech stack:** Existing TypeScript real-Pi fixture/Host transport and Kotlin Android instrumentation.

**Spec:** docs/remote-development-goal.md reliable reconnect/device control. Existing idle-after-Stop qualification is distinct. User authorized autonomous execution; no renewed design gate.

## Constraints / review focus

No fake Stop, fixture-side worker abort, second writer, core patch or arbitrary provider claim. Guard one-shot actions by actual trace and owner state; preserve idle mode. Require event barriers rather than sleeps or blind timeout increases. Capture held/no-replay evidence while offline and after reconnect. If partial/state recovery fails, retain the failing evidence and add regression-first production correction; do not weaken assertions. Process death and lost acknowledgment remain distinct cases.

## Tasks

- [x] Clean branch/upstream check and ff-only update for both worktrees before analysis.
- [x] Host fixture active-cut opt-in/metadata, guarded actions and offline/reconnected held-state counters.
- [x] Android real UI assertions/screenshots: visible streaming partial → offline → same-worker running recovery → Stop/interruption → follow-up/full Git workflow.
- [x] Root compile/build and actual phone/tablet proof, preserving source/APK inputs and cleanup; fix proven defects with RED first.
- [x] Independent review, precise qualification, local commits and canonical checkpoint; overall goal stays active.

## Result

The observed full-snapshot prefix loss was corrected after actual and unit RED evidence. Phone/tablet active-turn and existing idle workflows pass; independent review and bounded qualification are recorded in [qualification](../testing/active-turn-reconnect-qualification.md). The complete remote-development goal remains active.
