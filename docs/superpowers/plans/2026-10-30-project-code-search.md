# Project code search implementation plan

> **For agentic workers:** Use subagent-driven-development. Main owns integration and serialized Android checks.

**Goal:** Search project text directly from PocketPi and open a matching file at its line without losing unsaved edits.

**Architecture:** Add a separately negotiated, read-only search command to the existing file service. Apply its current trust, Git membership and filesystem checks to each candidate. Android uses the existing Files pane and reader/editor.

**Tech stack:** TypeScript host, existing encrypted remote protocol, Kotlin/Compose Android. No new dependencies or Pi core changes.

**Spec:** `docs/remote-development-goal.md`, direct code search under worker parity and development tools.

## Contract and decisions

Command capability `session.files.search.v1` is v2-route-only. Command is `{type: "session.files.search", requestId, sessionId, query}`. Query is a nonempty, single-line, case-sensitive literal, at most 256 UTF-8 bytes, without NUL/CR/LF. Result is `{kind: "files.search", sessionId, query, matches: [{path, line, column, snippet}], truncated}`. Line and column are 1-based, columns count UTF-16 code units. Snippets are bounded to 1,024 UTF-8 bytes and include the literal match with nearby context; they are plain text.

Limits: 100 hits, 2,000 candidates, 8 MiB scanned, 2 seconds of scan work, existing 1 MiB readable-file limit, and 128 KiB encoded result budget. Any scan bound or unreadable/omitted candidate within the permitted Git file set makes the result incomplete. Denied and ignored paths are outside that set and do not by themselves mark a result incomplete. No shell or regex. Git ignored files, denied paths, symlinks, hardlinks, special files, binary/invalid UTF-8 and nested repositories stay outside normal file-read access. Search never expands those permissions. Reauthorize during scanning and at delivery. Locations can become stale; opening always uses normal file-read validation.

A native bounded host scan is preferable to app-side recursive reads, which multiply relay requests and produce an incomplete index. An external `rg` process adds an executable dependency and still needs independent authorization per hit. The existing file service is the selected implementation.

## Review focus

- Revoked sharing during scan must not deliver snippets.
- Paths in ignored or denied folders must not leak through the search.
- Unicode before a match must produce the correct editor line and column.
- Late responses after query or session changes must not replace current results.
- A search hit must not silently close a changed editor buffer.

## Task 1: Host contract and execution

Files: remote `protocol/files.ts`, `protocol/parse.ts`, `host/files.ts`, `host/daemon.ts`; their protocol, service and daemon tests. Add shared fixture `protocol/fixtures/files-search-v1.json`.

- [x] Write and run failing protocol/service tests for ordinary hits, Unicode, bounds, policy exclusions and revocation.
- [x] Add strict command/result parsing and bounded scanning through SessionFiles. Publish capability only on v2.
- [x] Run focused tests and the extension check; inspect actual encrypted daemon result coverage.
- [x] Main verifies and commits the host before the app integration commit. Independent review uses an immutable committed artifact.

## Task 2: App contract, state and UI

Files: `ProjectFiles.kt`, `ProjectFilesLoader.kt`, `DefaultRemoteRepository.kt`, `RemoteRepository.kt`, `RemoteViewModels.kt`, `FilesPane.kt`, `ChatActionPill.kt`, localized strings and relevant tests. Keep changes in existing architecture.

- [x] Add strict result parsing with the host fixture and test malformed coordinates, bounds and paths.
- [x] Add query/loading/error/results state with request identity and session checks, cancellation and clearing on selection changes.
- [x] Show an explicit search action only after negotiated capability. Display plain snippets, paths, line numbers and incomplete state. Opening a hit uses the existing line selection and editor conflict prompt; do not discard changed buffers automatically.
- [x] Run focused tests once source is stable, then one serialized `scripts/quick-check.sh`. Verify adaptive Files pane integration in UI tests.
- [x] Main commits only after independent review and checks. No push, merge, deployment, host settings change or Plane publication is included.

## Qualification

Mac filesystem and Android local tests prove only those tested paths. Real Samsung, Linux/devcontainer and unattended full-goal acceptance remain open.
