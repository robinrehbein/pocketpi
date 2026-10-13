# Code Editor Spike Design

## Goal

Let Robin fix code by hand from PocketPi on a Galaxy Tab S11 with a hardware keyboard when he is away
from his Mac. Agents still write most code; the editor covers the small edits that are quicker to
type than to prompt. The spike runs end to end: a direct save to the Mac through a new host command,
and an editor in the app's existing file browser. It answers two questions:

1. Does a Compose-native editor feel fluent with a hardware keyboard on files up to about 190 KiB?
2. Is a direct, version-checked save over pi Remote safe and simple enough to ship?

Spike branches are not merged. A follow-up decides what ships, based on the findings at the end of
this document.

## Decisions so far

- **Engine:** Compose `BasicTextField(TextFieldState)` with the existing `SyntaxHighlight.kt`. No
  new dependency. If typing lags at the size limit, that is the evidence for Sora or CodeMirror
  later.
- **Save:** New host command `session.files.write`, guarded by the `version` that
  `session.files.read` already returns.
- **Agent hand-off stays:** "Send to agent" turns the unsaved edit into a unified diff and fills the
  composer with it (existing `prefillPrompt`), for edits Robin starts and wants an agent to finish.
- **Tablet first:** The tablet inspector already shows chat and files side by side. The editor
  takes the file view's slot there and in the phone overlay. No new navigation route.

## Protocol: `session.files.write` (noah-monorepo, `packages/pi-remote`)

- Capability `session.files.write.v1`, appended to the `capabilities.v2:` list. The client sends the
  command only when the host advertises it.
- **Ruling:** The host advertises and accepts it only when started with `PI_REMOTE_FILE_WRITES=1`.
  Reason: it is the first command that changes project files from a phone, and a spike branch
  should not turn it on for every host. Cost if wrong: one environment variable to set while
  testing.
- Request: `{type: "session.files.write", requestId, sessionId, path, baseVersion, content}`.
  `baseVersion` has the same shape as `version` and is required. `content` is the full new file as
  a UTF-8 JSON string, at most `MAX_FILES_CONTENT_JSON_BYTES` (192 KiB, JSON-encoded), so one frame
  carries it.
- Result: `{kind: "files.written", sessionId, path, version, size}` with the version of the new
  file.
- Errors reuse the files error codes (`invalid_path`, `not_found`, `forbidden`, `busy`,
  `unavailable`) and add `conflict` when the file's current version differs from `baseVersion`.
- Checks run in the same order as reads and reuse `SessionFiles.target`: path shape, deny list,
  session folder, shared and trusted project, git membership (`ls-files --cached --others
  --exclude-standard` must list the path), per-segment `lstat` with no symlinked folders, a regular
  file with `nlink === 1`, and a realpath below the folder. A write only replaces an existing
  readable text file. It never creates, deletes or renames a file, and it never writes a file whose
  current content is binary.
- A write-only deny list also refuses files the Mac runs on its own: any path segment `.pi`,
  `.claude`, `.husky`, `.githooks`, `.vscode` or `.idea`, and a final `.envrc`. These stay
  readable, and the app hides Edit for them. `.github/workflows` stays writable because it runs on
  GitHub behind review. Decided by Robin on 2026-10-13. Reason: a direct save skips the agent's
  guards and the chat record, so a misused pairing could turn one save into code running on the
  Mac. Cost if wrong: these files are edited through the agent instead.
- Writes from one host are serialized, and a target must be owned by the host user and writable by
  them, so a save never does more than an in-place edit could.
- **Ruling:** Write a sibling temp file (`O_CREAT|O_EXCL`, the target's mode), fsync it, re-check
  the target's version, then `rename` it over the target. Reason: an agent or editor on the Mac
  never reads a half-written file. Cost if wrong: extended attributes or ACLs on the file are lost,
  and a write can still slip into the short gap between the second check and the rename. Both are
  documented as accepted.
- The host does not normalise line endings or add a trailing newline. The client sends the text
  exactly as edited.
- Writes share the files gate and the per-device limit with reads, and authorization is re-checked
  before the result is sent.
- The agent is not told that a file changed. **Ruling:** Leave that out of the spike. Reason: pi
  re-reads files before it edits them. Cost if wrong: an agent edit made from stale context fails or
  overwrites Robin's save. The findings section records whether this happens.
- A new `files-write-v1.json` fixture covers valid and invalid commands, results and errors. It is
  copied into pocketpi `src/test/resources`.

## App: editor (pocketpi)

### Entry and layout

- `FileView` gets an **Edit** action in `FileActionBar` when the host advertises
  `session.files.write.v1` and the open file is text, fully loaded in one page
  (`nextOffset == null`), not `tooLarge`, and not binary. Other files stay read-only.
- Edit replaces `FileView` in the same pane. Header: back, file name with a dot when changed, undo,
  redo, save. Footer: **Send to agent**. Back with unsaved changes asks whether to discard them.
- The editor uses monospace text, no soft wrap, horizontal scroll, a line-number gutter and the
  current syntax colours. Highlighting is recomputed off the main thread about 150 ms after typing
  stops.
- **Ruling:** Apply the colours through an `OutputTransformation` that adds styles. If the
  Compose version in use cannot style spans that way, fall back to `TextFieldValue` with a
  `VisualTransformation`. Cost if wrong: more recomposition while typing.

### Keyboard

- Hardware shortcuts: `Ctrl+S` save, `Ctrl+Z` undo, `Ctrl+Shift+Z` and `Ctrl+Y` redo, `Ctrl+F`
  find, `Tab` and `Shift+Tab` indent and outdent the selected lines. `Esc` closes find.
- Find is a bar with a query field, next and previous, and a match count. Replace is out of scope.
- Without a hardware keyboard, a small accessory row above the soft keyboard offers Tab, Shift+Tab
  and `{ } ( ) [ ] ; : = " '`.

### Save and conflicts

- Save sends the full text with the version from the last read or save. On success the editor
  keeps the new version as its baseline and clears the changed mark.
- On `conflict` a dialog offers **Reload** (discard my edit and open the new file) or **Send to
  agent** (my edit as a diff against the version I opened). No merge view.
- Offline, `busy` and other errors leave the edit in place and show a retryable message.

### Send to agent

- `unifiedDiff(path, original, edited)`: a line-based Myers diff with three lines of context,
  `a/` and `b/` headers and `\ No newline at end of file` markers. It is a pure Kotlin function with
  unit tests.
- The prompt reads: "Here is a suggested change to `<path>`. Apply it, adapting it if needed, and
  say what you changed." followed by a `diff` fence. It goes to the composer and the pane closes,
  the same way line quoting works today.
- A diff above `MAX_FILE_QUOTE_BYTES` (32 KiB) is refused with a message. An unchanged file disables
  the action.

### State

- `FilesState.editor: FileEditor?` holds path, base version, original text, the save status and a
  transient `EditorBuffer` with the live `TextFieldState`. It lives with the loader so rotation and
  the phone/tablet switch keep text, cursor and undo. The text is pushed to the loader debounced and
  at once on save, send, leave and dispose. Drafts are not persisted across process death.
- Leaving with unsaved changes asks first: Back, Files, Changes, and on the tablet opening another
  session, a child, a new session or a fork. Switches the user did not choose (notification tap,
  restore) park the changed editor with its session in memory and bring it back when that session
  opens again.
- While keyboard focus is in the editor, the app's own shortcuts (session search, new session,
  session up/down, sidebar) are not handled, so the editor keeps Ctrl+F and the arrow keys.
- Saves check the JSON-encoded size (192 KiB) before sending. A save whose reply was lost is retried
  with the same request ID, which the host answers from its ledger. On `conflict` the app reads the
  file once; if it already holds what was sent, the save counts as done.
- "Send to agent" diffs off the main thread and gives up above 1,000 edits, which would exceed the
  32 KiB prompt limit anyway.
- New strings go into `values/` and `values-de/`.

## Out of scope

Creating, renaming or deleting files; files over 192 KiB or binary files; merge views; replace,
autocomplete, folding, multi-cursor; persisted drafts; telling the agent about a save.

## Tests

- Host: write success, `conflict`, deny list, ignored file, symlink, hardlink, binary target,
  missing capability flag, oversize content, authorization revoked between check and result.
- Protocol: fixture-driven parser tests in both repos.
- App: `unifiedDiff` cases (insert, delete, replace, no trailing newline, CRLF, empty file); loader
  tests for save, conflict and offline through the fake transport; a Robolectric UI test for
  Edit → type → Ctrl+S → saved, and for the discard prompt.
- Checks: `deno task check` and the pi-remote tests in noah-monorepo;
  `python3 scripts/i18n-check.py .` and `./gradlew testDebugUnitTest assembleDebug lintDebug` here.
- Device checks are Robin's: the Tab S11 with the Book Cover keyboard (shortcuts, typing latency on
  a ~190 KiB file, IME behaviour with Samsung Keyboard) and a phone with the soft keyboard.

## Findings

To be filled in after the device test: typing latency, highlighting cost, keyboard quirks, whether
agent edits collided with saves, and a recommendation on what to ship.
