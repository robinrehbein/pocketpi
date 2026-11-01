# Native initial prompt lost-ACK qualification — 2026-11-01

## Behavior

An ordinary initial prompt consumed by a capable Pi source now receives a durable user-message receipt containing its exact phone request ID and `delivery: prompt`. When Android loses both acknowledgment and echo, it keeps a visible NOT_CONFIRMED journal rather than automatically retrying. An authoritative receipt after reconnect resolves that journal without another initial prompt write. Later composer edits, quote and attachment changes are preserved, including editing text away and back to its original value. A receipt proves delivery/consumption, not successful completion of the agent task.

Three independent negotiations protect old combinations: source capability `session.remote_prompt_delivery.v1`, local register reply `remotePromptDelivery: 1`, and per-phone v3 opt-in/capability `remote_prompt_delivery.v1`. New Hosts do not mark input for old sources or unsuitable legacy request IDs. Old bridge/client/local-view messages omit prompt receipts while preserving existing queued steer/follow-up receipts. Live, full snapshot and nested replay project immediately before sealing; grant/revoke follows successful capability delivery, including dropped replies and downgrade. Android trusts only a successful v3 reply in the current connection generation; a handshake claim or missing capability does not grant it, including while a fallback is pending.

The public Pi message_start hook consumes the existing envelope into the actual source user message. The phone ID is different from the local IPC request ID; acknowledgment of marked input matches receipt identity, not matching body text. Initial prompt dispatch explicitly sets expandPromptTemplates false, the pinned SDK wrapper's existing default. Early trusted input/before-agent hooks may see the envelope. Supported preserving transformations retain correlation; handled input or a removed envelope produces no receipt. This is not universal arbitrary-extension compatibility or an invisible-marker API.

## SDK image-normalization correction

A real pinned Pi SDK test with a generated valid 2001×1 PNG reproduced a resize hint appended after the trailing envelope: the model text still contained the marker and the receipt was absent. The fix leaves the generic trailing decoder unchanged and accepts the tested SDK image-hint suffix only when the receipt belongs to an outstanding source-owned initial prompt. The fallback preserves image hints, quote and image content and leaves non-owned embedded/quoted candidates unchanged.

Ownership is bounded to 64 IDs. It outlives the 10-second IPC ACK timeout and same-session registration/disconnect, is forgotten on consumption, and clears on actual session change/shutdown. New marked dispatch is refused when the bounded ownership store cannot admit it. SDK-file persistence and clean model text are tested separately from a simulated native IPC owner/re-registration case. This is not an actual Android image-upload/lost-ACK end-to-end qualification.

## Failure evidence and local checks

- Actual old product `lost-ack-phone-red`: failed after 75.811 s at the 60-second receipt wait, cleanup passed. Pi had accepted one initial prompt and was held. Original outbox ACK was queued once and never sent; user echo was queued twice under the same source message ID and never sent. Android independently observed NOT_CONFIRMED/offline and no matching decoded ACK/echo. Replay and snapshot returned that user message without remoteDelivery. Existing queued follow-up delivery was not used as a substitute.
- Host/source codec, consumption and capability tests first failed five cases; Android tests first failed four receipt/capability cases. Further RED covered no-capability receipt acceptance and a v3 omission while v2 fallback was held. Matching text alone, wrong session/ID/role/delivery, stale handshake claims and composer edits remain negative controls.
- Android required quick-check: 3,482 app and 34 Wear tests, zero failures/errors/skips (`/private/tmp/pocketpi-lost-ack-final-quick-check.log`). Product and instrumentation APK builds passed. Later changes to instrumentation added direct write-attempt counting and tested EditableText rather than its placeholder; no later Android product change occurred.
- Source image regression RED: two failures (`/private/tmp/pocketpi-lost-ack-image-red.log`), including actual SDK model text. Final source/codec/runtime subset: 38 passed, six existing skips (`/private/tmp/pocketpi-lost-ack-image-final-green.log`).
- Final combined Host/source/protocol suite: 313 passed, eleven existing skips across eleven files (`/private/tmp/pocketpi-lost-ack-qualified-host-tests.log`), including the image correction, compatibility matrix, capability barriers, sessions and replay. The smaller source subset overlaps this run; counts are not additive.
- Whole extension TypeScript check passed (`/private/tmp/pocketpi-lost-ack-image-final-types.log`); changed TypeScript formatting/diff checks passed. The SDK inline test factory was corrected to return void rather than an unsubscribe function.

The initial broad Android run exposed a test which simulated only a ready handshake after disconnect, without repository revalidation; it was corrected to negotiate v3 before expecting receipt processing. A Phone attempt then reached correct receipt resolution but failed an empty-composer assertion because Compose included placeholder Text. The selector now explicitly requires empty EditableText. Neither was treated as a successful full workflow.

Runtime fixture readiness also needed correction: server receipt of registration did not establish source readiness. A shell recheck captured the concrete reply Session changed; refresh. The tests now await public remote:bridge available after completed registration; assertions were retained. A separate old call-count expectation was updated to explicitly check the two plain false-expansion calls and queued follow-up. No shell product workaround was added.

## Actual transport proof

The private fixture pauses the sole authenticated original device outbox only after drain plus zero queued/current entries, guards its pause reason against relay resume, and blocks replacement upgrades. It requires actual native acceptance, held provider state and successfully queued-but-unsent acknowledgment/echo before closing the real socket. It never skips a sealed encrypted frame. Android's bounded decoded-payload observer independently rejects matching inbound proof before disconnect. The source prompt is not resent by the fixture.

The final instrumentation independently counts matching Android send/sendGuarded write attempts before possible write. It catches the same prompt body even if a retry used a new request ID and asserts exactly one attempt offline, after receipt and at workflow completion. This counts write attempts, not physical delivered frames. Actual Pi request/provider counters separately require one initial prompt/model start. The later distinct follow-up is allowed once.

| Run | Instrumentation | Runner | Cleanup |
| --- | --- | --- | --- |
| `lost-ack-phone-qualified` | OK one test, 88.177 s | 90.214 s | Passed |
| `lost-ack-tablet-qualified` | OK one test, 89.926 s | 92.264 s | Passed |

Both final runs used identical product/test inputs. Tablet display was 1600×2560/density 240 and restored to 1080×2400/density 420. Root inspected the tablet offline screenshot, including the visible unconfirmed pending message and restored draft. Private lab: `/private/tmp/pocketpi-paged-production.oge2ybh1`. Each final runner captures 31 relevant source files and two installed APKs checked against build artifacts. Four screenshots use offline, recovered-running, stopped and success names; no other screenshot set is implied. Original agent plus separate lifecycle traces, final status and result logs qualify matching source ID, same native PID/session/runtime, clean model text, actual Stop/interruption, follow-up and existing file/shell/local commit/push/controlled PR/CI chain.

## Boundaries

This is controlled local-provider and emulator evidence, with the test Activity supplying foreground/network signals. Lost ACK on this accepted initial plain-text prompt differs from process death, native IPC delivery uncertainty and a fresh-device handoff. Real model output quality, external Plane/GitHub/CI, production MainActivity/network monitors, physical Galaxy Tab S11/S25 and independent Linux remain unqualified. Devices and an authorized SSH test host are unavailable per the user; do not request them again. Personal Host/live infrastructure remain untouched. The complete remote-development goal remains active.

Independent final review verified both final runs, all 31 current source hashes and two installed/build APK hashes per run, matching worker/session/traces, withheld ACK/echo, receiver assertions and one matching Android write attempt through completion. No remaining concrete blocker was reported (`/private/tmp/lost-ack-review.md`). Local build/artifact comparisons are not independent compilation attestation. The Host upstream advanced during this pinned qualification; these results describe the captured local source, not an untested later upstream integration.
