# Native Stop and reconnect workflow implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development. Terra authors implement disjoint Host fixture and Android instrumentation; root runs all checks and processes. Independent reviewer checks contracts and evidence.

**Goal:** Establish that a visibly streaming native Pi turn can be stopped through Android, survive a transport cut without replay, and accept a new turn in the same worker.

**Architecture:** Extend the existing private joined development fixture with an explicit lifecycle mode. Preserve the successful read/bash agent oracle, use a separate bounded abort-aware provider trace for the held turn and follow-up, and cut the actual relay proxy only after native cancellation. Use production navigation, composer, Stop, transport and snapshot recovery.

**Tech stack:** Pi 1.4 public provider/extension APIs, TypeScript private fixture, Kotlin Android instrumentation.

**Spec:** docs/remote-development-goal.md session control and reconnect requirements. User has authorized autonomous implementation; no renewed design approval is needed.

## Constraints and choices

Reuse the existing actual-Pi Android workflow rather than a fake repository or protocol-only test: those already cover isolated pieces and cannot establish this joined behavior. Keep process death separate from socket disconnection. No personal Host, external provider/service, physical device or independent Linux claims. Production changes require an observed failing regression first; fixture/test authoring does not establish a product defect.

## Review focus

- Partial output must be visibly rendered before Android Stop, not inferred from an accepted prompt.
- Cancellation must be observed in the actual provider and agent end; an accepted abort acknowledgment alone is insufficient.
- Hold/deadline failure must not masquerade as successful user cancellation.
- Socket cut must be observed offline with upgrades blocked before allowing reconnect; retain worker/session and avoid silent prompt/abort replay.
- Same-worker follow-up must complete once, and the pre-existing agent/file/Git workflow must remain valid.

## Tasks

- [x] Refresh both isolated branches ff-only; read-only Host/Android/independent gap audits.
- [x] Host fixture mode, separate lifecycle provider/trace, guarded cut/reconnect actions and status contract. Own runtime-test/pr-status-android-e2e files only.
- [x] Android opt-in assertions in DevelopmentWorkflowTransportEndToEndTest: visible partial, Stop, native terminal evidence, disconnect/reconnect and follow-up before existing shell/Git chain.
- [x] Root compile/format/build, run actual private phone and tablet workflows, inspect source/APK pins and runtime evidence; fix any demonstrated defects with regression-first workflow.
- [x] Independent code/evidence review, precise qualification, local commits and canonical checkpoint. Overall goal remains active.

## Result

Phone, tablet and legacy actual-native workflows passed; see [qualification](../testing/native-lifecycle-qualification.md). No production runtime change was needed for this proof. Overall goal remains active, including the newly observed interrupted-response error classification.
