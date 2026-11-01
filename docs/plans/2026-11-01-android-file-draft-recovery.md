# Android file-editor draft recovery: next increment

Scope: Galaxy S25 and Galaxy Tab S11 Android app. iPhone integration is deferred. This is a design checkpoint, not implemented or qualified behavior.

## Problem

Manual `FileEditor` content and pending writes currently live in process state. The transient editor buffer and UI highlighting/debounce can leave recent typing outside repository state. `saveEditor` records write uncertainty only in memory; retaining only the last four superseded writes can silently forget unresolved receipts.

## Proposed bounded implementation

Use a separate encrypted, atomically replaced journal backed by the existing `EncryptedFileStore` facility. Identify entries by route, session and normalized relative file path. Store the original content, base version, current draft snapshot and unresolved request IDs with exact base versions and transmitted content. Keep chat draft storage independent.

Serialize mutations through a repository-owned writer. Before every Host write, including rebase retries, durably record the exact pending request and wait for successful persistence. On storage error or capacity exhaustion, do not transmit. Do not evict unresolved receipts to make room. Closing the editor or discarding text must not silently discard unresolved write evidence.

Observe editor text independently of highlighting and UI handover. Flush on lifecycle transitions when possible. Async snapshots have a bounded recent-keystroke loss window on abrupt process termination; the UI must not promise zero-loss typing. Choose and document byte/count/time budgets in implementation after checking existing file-read and editor bounds.

On reopening, read the complete current Host file and compare it with the stored original and pending contents. Offer explicit draft restoration with the comparison visible. An unavailable, incomplete or unverifiable Host read leaves uncertainty unresolved. Never automatically save or replay a request during restoration.

## Tests and acceptance

Cover persisted draft restart, lost write reply, unchanged and changed Host content, intervening edits, isolated route/session/path identities, failed atomic persistence and capacity refusal. Prove pending intent persistence precedes every transmission, including rebase. Exercise actual Android process recreation separately from unit tests. Physical Galaxy/DeX acceptance remains unavailable until devices can be tested.

This increment must not enable the separately gated paged editor or widen Host authority.
