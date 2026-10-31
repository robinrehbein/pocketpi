# Pull-request creation: local qualification, 2026-10-31

The current local Host and Android worktrees implement an explicit PR workflow with separate
V3 create and status capabilities. Preparation binds the session, device, source, repository,
branch, intended HEAD and offered base; the Host checks the actual remote branch rather than
using a potentially stale local tracking ref as proof. The Android form retains its edit draft
separately from the immutable intent persisted before dispatch. An unknown outcome remains
pending until an explicit status observation or acknowledgement; neither reconnect nor recovery
automatically pushes or creates again. A current absence is not proof that creation never occurred.
Status rechecks current authorization and remains usable with the owner write switch off.

## Local evidence

| Check | Result and scope |
| --- | --- |
| Android phone, English, normal response | Passed in 46.15 s through the actual PR pane, repository and encrypted transport, with a controlled provider. |
| Android phone, English, dropped response | Passed in 72.359 s. The test reconstructed the repository, controller and encrypted `DraftStore` from the same file, then explicitly checked the existing PR. |
| Android tablet, German, dropped response | Passed in 74.515 s with the same reconstruction and explicit recovery. |
| Provider CLI boundary | Local HTTP fixture passed pagination, create, rate-limit and lost-response cases, including private title/body through stdin. |
| Android broad unit run | 3,179 passed and one glossary failure; after correcting the new strings, the five glossary/PR-pane tests passed. This is not a fresh all-green broad run. |
| Wear unit tests | 34 passed, no failures or skips. |
| Wear debug assembly | `:wear:assembleDebug` completed; no Wear emulator was used. This is a build check, not watch usability qualification. |
| GitHub connection | A separate real GitHub HTTPS/authentication check passed **read-only**; it did not create a PR. |

The Android fixtures use a synthetic idle Pi peer, a synthetic network-availability signal and a
pane harness. They exercise the real encrypted transport, repository, panes and an actual private
local HTTPS Git remote, while PR provider responses come from a controlled GitHub-like fixture.
Each qualified flow observes exactly one provider create and one client create attempt, with no
push; the source HEAD, index, staging and worktree remain unchanged. Reconstructing objects and
reading the encrypted file is evidence of durable recovery in that harness, not an OS process-death
test. The test harness is not the full RemoteScreen navigation or a real Pi worker.

The recovery source binding was corrected to carry the session's `sourceFile` and compare it when
reserving/revalidating the binding. This prevents a changed source file from being treated as the
same authority while still allowing a newly authorized actor to observe the original target. The
binding does not retain the old actor's authorization closure.

## Open gates

- A real GitHub PR create, actual Pi worker and CI/check-status path, and the complete unattended
  ticket-to-PR workflow remain unqualified. No real GitHub mutation was performed.
- Physical Samsung phone/tablet and an independent Linux host were unavailable for this run.
  DeX, device switching and long-session behavior remain separate device gates.
- The broad Host run passed 3,732 tests with 35 skips and two integration failures still under
  investigation. Focused passes do not establish a passing broad run or release readiness.
- The provider and Git remote can change concurrently outside the Host; an observation describes
  current state and is not an atomic lock or attribution to a specific create attempt.
