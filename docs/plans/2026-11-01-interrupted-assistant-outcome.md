# Interrupted assistant outcome implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development. Terra HIGH Host author and independent reviewer, MEDIUM Android author. Root alone runs tests/builds/processes.

**Goal:** Preserve an interrupted partial assistant response without presenting it as a genuine failure, including restored timelines.

**Architecture:** Keep legacy wire state `error`, add a narrowly validated optional `terminalOutcome: interrupted` only for terminal assistant aborts without truthy raw isError. Negotiate existing capabilities-v3 opt-in `message_interruption.v1`; omit the field at every non-negotiated delivery boundary. Android gives explicit interruption a neutral localized label, preserves partial text/copy and excludes it from Errors; genuine and legacy failures remain errors.

**Tech stack:** Existing TypeScript protocol/Host journals and Kotlin/Compose presentation.

**Spec:** docs/remote-development-goal.md reliable native lifecycle; observed actual Stop/reconnect screenshot and source audit /private/tmp/native-stop-error-chip-audit.md. User authorized autonomous implementation; no renewed design approval.

## Constraints / review focus

No raw provider errors or asserted user origin. Incomplete text must not become completed. Legacy strict parsers, concurrent mixed clients, live upserts, snapshot results, nested replay and local views must remain compatible. Opt-in becomes effective only after successful capability response delivery. Preserve message byte bounds and truthful genuine error classification. Projection never mutates shared canonical messages or journal entries.

## Tasks

- [x] Both worktrees refreshed clean ff-only before analysis; contract and independent review.
- [x] Root observed actual Pi/Android RED: exactly one partial bubble counted as error, assertion failed after real native cancellation (14.618 s), cleanup passed.
- [x] Root observed Host conversion/capability/parser/local-feed/budget RED and Android presentation/live-upsert/snapshot RED.
- [x] Host conversion/schema/capability/per-peer live+snapshot+replay/local projection, focused GREEN and independent review.
- [x] Android localized neutral marker, Errors/copy/prediction semantics and opt-in, focused GREEN.
- [x] Root exact regression suites, required broad checks, actual phone/tablet native Stop+reconnect proof with interrupted label/error count before and after recovery; real failures remain covered by unit/protocol tests.
- [x] Precise qualification, local commits, canonical checkpoint. Overall goal remains active.

## Result

Implemented and independently reviewed. Phone/tablet actual native workflows and required local checks passed; see [qualification](../testing/interrupted-assistant-qualification.md). Overall remote development goal remains active.
