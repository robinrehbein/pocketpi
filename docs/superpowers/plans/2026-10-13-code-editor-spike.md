# Code Editor Spike Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans
> to implement this plan task by task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hand-edit and save project files from PocketPi (tablet first), with a version-checked
`session.files.write` on the pi Remote host, and hand an edit to the agent as a diff.

**Spec:** `docs/superpowers/specs/2026-10-13-code-editor-design.md`.
**Tracker:** DEV-1417 "Spike: session.files.write for hand edits from PocketPi".

**Branches (spike, not merged):**
- pocketpi worktree `.claude/worktrees/code-editor-spike` → remote branch `ccr-34e85c73-dc4tcz`
  (draft PR #76).
- noah-monorepo worktree `.claude/worktrees/files-write` (from `origin/dev`) → remote branch
  `ccr-34e85c73-dc4tcz`.

## Contract (done before dispatch)

`packages/pi-remote/protocol/fixtures/files-write-v1.json` in noah-monorepo, copied byte for byte
to pocketpi `src/test/resources/files-write-v1.json`. Both sides test against it. Key facts:

- Command `session.files.write {type, requestId, sessionId, path, baseVersion, content}`. All keys
  are required, no others are allowed. `path` follows the read rules and must be non-empty.
  `baseVersion` is validated like `version` (22 base64url chars, decodes to 16 bytes). `content`
  is a string whose JSON encoding is at most `MAX_FILES_CONTENT_JSON_BYTES`.
- Result `files.written {kind, sessionId, path, version, size}`, with `size` an integer ≥ 0. No
  other keys.
- New error code `conflict`.
- Capability `session.files.write.v1`, only in the `capabilities.v2:` list and only when the host
  runs with `PI_REMOTE_FILE_WRITES=1`.

## Global constraints

- The host never creates, deletes or renames a project file, and never follows a symlink. A write
  replaces an existing regular text file that a read of the same path would serve.
- Read behavior and the existing fixtures stay unchanged.
- pocketpi: new strings go in `values/` and `values-de/`. Use theme roles and `monoTextStyle()`.
  48 dp touch targets. No new Gradle dependency.
- Agents edit only the files their task names, never stage or commit; the integrator commits.

## Task H: host write (noah-monorepo, one agent)

**Files:** `packages/pi-remote/protocol/files.ts`, `protocol/types.ts` (ErrorCode), `protocol/parse.ts`
(ERROR_CODES, command/result dispatch), `protocol/files_test.ts` or a new `protocol/files_write_test.ts`,
`protocol/README.md`, `host/files.ts`, `host/daemon.ts`, `host/files_test.ts` or a new
`host/files_write_test.ts`, and the daemon test that covers capabilities.

- [ ] Protocol: `FILES_WRITE_CAPABILITY`, command and result types, validators, `conflict` in
  ErrorCode and ERROR_CODES. Fixture test: every `valid` entry parses and every `invalid` entry
  fails, as the existing files fixture test does.
- [ ] Host: `SessionFiles` handles `session.files.write` through the same gate, per-device limit
  and `target()` path checks as read (deny list, session folder, shared and trusted project, git
  membership, `walk` with no symlinked folders, regular file, `nlink === 1`, realpath below the
  folder). Refuse a target whose current content is binary (same sniff as read) with
  `invalid_path`. Compare the current `fileVersion` with `baseVersion`; on mismatch throw
  `conflict`.
- [ ] Write: temp file next to the target (`O_CREAT|O_EXCL`, target's mode, unique name starting
  with `.pi-remote-write-`), write, fsync, re-stat the target and re-check its version, then
  `rename`. Remove the temp file on any failure. Return the new file's version and size. Re-check
  authorization before returning.
- [ ] Daemon: route the command; append the capability to the `capabilities.v2:` list only when
  `Deno.env.get("PI_REMOTE_FILE_WRITES") === "1"`; without it answer the command with
  `invalid_request` (or the code the daemon uses for an unadvertised command — check and follow it).
- [ ] Tests: success and new version; stale `baseVersion` → `conflict`; `.env` denied; ignored
  file; symlinked file and symlinked folder; hard link; binary target; oversize content rejected by
  the parser; capability absent and command refused without the flag; read after write returns the
  new content; temp file gone after success and after failure.
- [ ] README: a "Saving files (`session.files.write.v1`)" section next to "Project files" with the
  flag, the checks, the temp-and-rename write, and the accepted risks (lost xattrs/ACLs, the gap
  between the second version check and `rename`, the agent not being told).
- [ ] Run `deno task pi-remote:check` and `deno task pi-remote:test`; report output.

## Task D: diff (pocketpi, one agent, parallel with H and A)

**Files:** new `src/main/java/de/joinnoah/pi/remote/UnifiedDiff.kt`, new
`src/test/java/de/joinnoah/pi/remote/UnifiedDiffTest.kt`.

- [ ] `fun unifiedDiff(path: String, original: String, edited: String, context: Int = 3): String?`
  returns null when equal. Line-based Myers diff; lines keep their terminator so CRLF and a missing
  final newline survive; hunks with `@@ -a,b +c,d @@`; headers `--- a/<path>` and `+++ b/<path>`;
  `\ No newline at end of file` after the affected line.
- [ ] Tests: insert, delete, replace, two distant hunks, merged nearby hunks, empty to text, text to
  empty, missing final newline on either side, CRLF, an unchanged file returns null. Check one
  output against `git diff --no-index` text written into the test.

## Task A: data layer (pocketpi, one agent, parallel with H and D)

**Files:** `ProjectFiles.kt`, `RemoteRepository.kt`, `DefaultRemoteRepository.kt`,
`ProjectFilesLoader.kt`, `RemoteViewModels.kt`, `NavigationFakeRepository.kt` (test), new
`src/test/java/.../ProjectFilesWriteTest.kt`, `ProjectFilesLoaderTest.kt`.

- [ ] `FILES_WRITE_CAPABILITY = "session.files.write.v1"`, `canWriteFiles(state)`, a strict
  `parseFilesWritten` and a `FileWritten` model; parser tests against `files-write-v1.json`.
- [ ] Repository `filesWrite(sessionId, path, baseVersion, content): FileWritten`, sending only when
  the capability is advertised (same pattern as `requireFiles`). Map error `conflict` to a new
  `FilesFailure` value.
- [ ] `FileEditor` model in `ProjectFiles.kt`: `path`, `baseVersion`, `original`, `text`,
  `saving`, `saveFailure: FilesFailure?`, `conflict: Boolean`, `savedAt` (monotonic counter for UI
  feedback). `FilesState.editor: FileEditor?`.
- [ ] Loader: `startEditing()` (only when the open file is editable per the spec: text, one page,
  not too large, capability present), `updateEditorText(text)`, `saveEditor()`, `discardEditor()`,
  `reloadAfterConflict()`; `editorPrompt()` builds the agent prompt from `unifiedDiff` (Task D;
  until D lands, code against its signature) and returns null when unchanged or a failure when
  over `MAX_FILE_QUOTE_BYTES`. A successful save sets the new base version and `original = text`
  and refreshes the open file's content. Late save replies after session change or discard are
  ignored.
- [ ] ViewModel and `FilesActions` hooks for the above. Loader tests: save success, conflict,
  offline, capability missing, stale reply ignored, prompt building.

## Task U: editor UI (pocketpi, one agent, after A and D)

**Files:** new `CodeEditor.kt` (composable), `FilesPane.kt`, `ChatActionPill.kt` (`filesActions`),
`src/main/res/values/remote_files.xml`, `values-de/remote_files.xml`, new
`src/test/java/.../CodeEditorUiTest.kt`, `FilesPaneUiTest.kt`.

- [ ] `FileActionBar`: **Edit** when `editor == null` and the file is editable. `FilesPane` shows
  `CodeEditor` when `state.editor != null`. Back order: close find, discard prompt if changed,
  leave editor.
- [ ] `CodeEditor`: `BasicTextField(TextFieldState)` seeded from `editor.text` with changes pushed
  through `updateEditorText`; monospace, no wrap, horizontal scroll, line-number gutter scrolled
  with the text; highlighting via `SyntaxHighlight` applied after a 150 ms debounce off the main
  thread (OutputTransformation with styles, or the `TextFieldValue` fallback from the spec).
- [ ] Header: back, file name with change dot, undo, redo, save (progress while saving). Footer:
  **Send to agent**.
- [ ] Keys via `onPreviewKeyEvent`: Ctrl+S, Ctrl+Z, Ctrl+Shift+Z, Ctrl+Y, Ctrl+F, Tab and
  Shift+Tab on selected lines (four spaces, or a tab when the file indents with tabs), Esc.
- [ ] Find bar: query, previous, next, "n of m", selects the match.
- [ ] Accessory row above the soft keyboard when no hardware keyboard is attached
  (`LocalConfiguration.keyboard == KEYBOARD_NOKEYS` or `hardKeyboardHidden`).
- [ ] Dialogs: discard changes; conflict with Reload and Send to agent. Snackbar or inline message
  for save errors and "Saved".
- [ ] Strings EN and DE. UI tests: Edit appears only when editable; type, Ctrl+S calls save;
  Back with changes shows the discard dialog; conflict dialog actions; Send to agent disabled when
  unchanged.

## Integration (main agent)

- [ ] Commit H in noah-monorepo; run `review-diff.ts`; reviewer pass; push to the session branch;
  open a draft PR against `dev` marked spike, linked to DEV-1417.
- [ ] Commit D, A, U in pocketpi in order; run `python3 scripts/i18n-check.py .` and
  `./gradlew testDebugUnitTest assembleDebug lintDebug`; independent review subagent; fix; push
  to PR #76.
- [ ] Hand Robin the device-check list from the spec and the host start command
  (`PI_REMOTE_FILE_WRITES=1 deno task pi-remote:host …` from the spike branch).
