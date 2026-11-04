# Session Epoch and Replay Integration Plan

**Goal:** Keep the selected timeline and editor receipts consistent across journal restarts and interrupted replays.

**Architecture:** Classify epoch-bearing status and title events before routing them to a timeline; keep event-only observations provisional, reject confirmed retired epochs and request an authoritative first page for an unknown different epoch. Only a causally eligible first page confirms retirement or restores a provisionally superseded epoch. Preserve legacy epochless behavior. Separate timeline applications from effects already run on receipt, and resolve question closures against the question instance present when each close applies.

**Tech stack:** Kotlin, coroutines, JUnit, existing journal transport fixture.

**Scope:** Integrate the existing epoch, replay-deduplication and paged-editor receipt behavior. No host protocol change.

## Checks

- [x] Reproduce a retired epoch event poisoning the selected timeline; ensure a later current event still applies.
- [x] Verify an unknown event cannot permanently retire an authoritative selected or reopened epoch, while a confirmed retired page remains rejected.
- [x] Reproduce an unknown epoch arriving before a page; request a fresh page without mixing revisions.
- [x] Reproduce a live close held through replay; settle only the original editor receipt.
- [x] Reproduce a held close drained on failed read; settle the original receipt without settling a same-ID replacement.
- [x] Run the focused repository, timeline and protocol tests; preserve upstream compaction deduplication and legacy-host behavior.

## Result

The required check passes 3,527 App and 34 Wear tests. All four final phone/tablet process-recovery cases and both complete native development workflows pass, including cleanup and independent source/APK/proof review. See [qualification](../testing/session-epoch-replay-qualification.md) for exact evidence and boundaries. The overall remote-development goal remains active.
