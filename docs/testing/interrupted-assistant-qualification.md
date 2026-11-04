# Interrupted assistant outcome qualification — 2026-11-01

## Product behavior

An interrupted partial assistant response is retained with a neutral localized Interrupted / Unterbrochen label and can be copied. It does not contribute to Errors. A nonempty visible partial can suggest Continue, including text-only parts when aggregate text is empty; legacy error prediction remains unchanged. Genuine assistant/tool failures and legacy error messages remain errors. The label does not assert who initiated the interruption or imply the answer is complete.

Host messages keep legacy state `error` and add strictly validated `terminalOutcome: interrupted` only for terminal assistant stopReason aborted without truthy `raw.isError`. Provider error details remain redacted and the additional field is included in the message byte budget. Existing v3 capability opt-in `message_interruption.v1` negotiates the field per connection only after successful capability delivery. Extension-to-Host register reply `messageInterruption: 1` separately protects old strict Hosts; registration/disconnection resets it. Non-negotiated live messages, snapshots, replay and local views omit the field without mutating canonical messages/journals.

## Reproduced defects and checks

- Actual Pi/Android RED: `interruption-classification-red` failed the explicit assertion after native cancellation in 14.618 s. Diagnostics identified exactly one partial bubble and one error item; fixture cleanup passed. This attributed the earlier Errors(1) observation to that interrupted response.
- Android tests first reproduced the presentation/snapshot error count, missing opt-in and visible-parts Continue behavior. Focused tests then passed.
- Host tests first reproduced missing conversion/capability/schema/local-feed projection and metadata budgeting.
- Independent review found a real delivery-order race: events/results queued behind a capability response were projected before successful negotiation. A blocked-outbox regression reproduced missing outcome in the snapshot; projection now occurs immediately before sealing using conservative full queued bytes. Busy/fallback capability results do not grant the field.
- Final combined Host regression subset: 180 passed, six existing skips across 12 files (`/private/tmp/pocketpi-interruption-final-regressions.log`), covering conversion/schema/IPC bridge, concurrent modern/legacy live/snapshot/replay, local feed, capability overflow, journal/resume and existing question projections.
- Additional final Outbox guard suite: 37 passed (`/private/tmp/pocketpi-interruption-outbox-guards.log`). New tests prove throwing/growing projections neither seal nor send nor call onSent, but drop/close, and authorization fallback skips the hook. This overlaps the combined suite; counts are not additive.
- Whole extension TypeScript check passed (`/private/tmp/pocketpi-interruption-final-types2.log`); changed TypeScript formatting and diff checks passed.
- Required Android quick-check: 3,475 app and 34 Wear tests, zero failures/errors/skips (`/private/tmp/pocketpi-interruption-quick-check.log`). App/instrumentation APK builds passed. Actual UI test clicked Copy and verified clipboard contents, OK one test, 3.475 s (`/private/tmp/pocketpi-interruption-clipboard-ui.log`).

The first mixed-peer integration setup requested an ordinary snapshot but expected a journal epoch. It was corrected to the existing resume opt-in request before the passing run; the product was not weakened to satisfy the test.

## Actual Android workflow evidence

Private lab: `/private/tmp/pocketpi-paged-production.oge2ybh1`. The actual native Pi 1.4 worker, encrypted Host transport and production Android navigation/composer/Stop were used. The lifecycle fixture holds a real streamed partial until the actual provider abort signal. Android asserts the exact interrupted bubble, neutral label and zero error items both before and after socket reconnect, and checks retained outcome and zero error items again at the end of the full file/agent/shell/explicit local commit/push/controlled PR/CI chain.

| Run | Instrumentation | Runner | Cleanup |
| --- | --- | --- | --- |
| `interruption-phone-first` | OK one test, 91.123 s | 93.326 s | Passed |
| `interruption-tablet-final` | OK one test, 89.989 s | 92.242 s | Passed |

Both runs used the same product source and APK inputs. Tablet display was 1600×2560 at density 240 and was restored to 1080×2400 at density 420.

Each run reserves private evidence and saves 28 prelaunch source snapshots plus two installed APKs checked against the built APK hashes. Result/final-status JSON, original agent and separate lifecycle traces, logs and four screenshots identify the inputs and behavior despite the then-dirty Git base. Root inspected both reconnected screenshots: retained partial, Interrupted label, copy button and no Errors chip. Independent final review verified both runs, all 30 hashes per run, identical current product inputs, owner/trace/workflow evidence and screenshots, with no remaining concrete findings. Review report: `/private/tmp/interruption-review.md`.

## Boundaries

This is controlled local-provider evidence. Genuine failures are distinguished in focused conversion/presentation tests, not injected as another external-provider runtime incident. Reconnect happens after completed Stop and idle; active-turn disconnection, lost acknowledgments and Android/Pi/Host process death remain separate unqualified cases. No physical Samsung, independent Linux/SSH host, production MainActivity/network-monitor, external model, live Plane/GitHub/CI or external deployment is qualified. The user has confirmed no devices or authorized SSH test host are currently available. Personal Host and live infrastructure remain untouched. Full remote-development goal remains active.
