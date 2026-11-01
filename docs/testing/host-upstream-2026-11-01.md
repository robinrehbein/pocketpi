# Host upstream integration — 2026-11-01

The qualified Host branch `codex/emulator-integration` remains at `f13f4d2f9`. A separate local integration branch, `codex/remote-environment-upstream-nov01`, merges the freshly fetched `origin/dev` commit `b6ebb559f` at `d66f848d8`. No remote push, PR merge, deployment or release was performed.

The incoming changes include the NOAH payment work and Pi relay tests/documentation plus journal epochs on phone status/title events. Two textual conflicts in `host/sessions.ts` and `host/sessions_test.ts` were resolved by preserving both the native editor question ownership mapping and upstream epoch stamping/tests. Epoch stamping precedes serialized byte accounting; extension-supplied epochs remain excluded from local feed entries. Package manifest and workspace lockfile did not change.

## Local verification

- Six targeted Host/protocol files: **261 passed, 5 existing skips** (`/private/tmp/pocketpi-upstream-nov01-tests.log`). Includes forged epoch/local-feed behavior, resume across journal restart, push gate and relay diagnostics.
- Five real local socket integration files: **24 passed** (`/private/tmp/pocketpi-upstream-nov01-integrations.log`). Covers prompt receipts, interruption projection and native editor question projections.
- Whole extension TypeScript check: passed (`/private/tmp/pocketpi-upstream-nov01-types.log`).
- Changed conflict files: Biome passed; `git diff --check` passed.
- Independent focused semantic review found no additional Host conflict.

These checks qualify the integration at the package/socket level. Earlier full native Android workflow runs used the previous Host revision and must not be described as current integration device evidence. Four subsequent Android process-recovery runs used this integrated Host with file editing, the prerequisite native agent turn and lost-ACK recovery; see [process qualification](prompt-process-recovery-qualification.md). They do not rerun the entire shell/Git/PR/CI chain.

## Android follow-up

Static inspection confirms status/title events are parsed as raw JSON objects and the added epoch field does not cause a decoder rejection. This is not an old-client runtime qualification. The current Android status/title revision overrides still ignore the event epoch: a live session-incarnation replacement can race before a fresh snapshot, or an old-incarnation event can arrive afterward. A future change must validate against an authoritative snapshot/resynchronization rather than treating every differing epoch as newer. Cover live and replay events, delayed old epochs, lower revisions of a new epoch, and absent-epoch legacy Hosts.
