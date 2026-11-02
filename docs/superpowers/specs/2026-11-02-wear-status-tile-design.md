# Wear status tile: sessions and pull request checks at a glance

## Why

On the watch Robin answers questions, approves work and checks where the agent stands. Answers
and approvals already work. The status check takes too long: it needs the app, a Mac, a project
and a session before anything is visible. The fix is a Wear OS tile to the right of the watch
face that shows the active sessions and their pull request checks with one swipe.

## Decisions

Robin's:

- A tile, not only an in-app screen. Opening the app is the slow part.
- The tile may first show the last known state for a few seconds and refresh after the swipe.
  This deliberately differs from the phone widget, which never asks the network itself.
- Option a) for pull request status: each session keeps the last pull request status the watch
  saw, with its age; a refresh reads a fresh one for the top session only. No protocol change.

Builder's calls:

- Rows: at most two sessions that are waiting or running, waiting first, chosen like the phone
  widget (`widgetSessions`). Two chips fit a 240dp round screen with a header; three do not
  without clipping at large font scales.
- Row text: title; then status (`needs you` / `working`) and, when known, the pull request words
  of the phone pill (`PR #189 · checks running`) with their age (`5 min`).
- Header: "N sessions need you" or "Nothing needs you", and the snapshot's time ("Updated 14:02").
  Unpaired: "Not paired" and the pairing hint. No active session: the widget's empty text.
- Tap on a row opens that session through the notification intent, which the app validates like a
  tapped notification. Tap elsewhere opens the app.
- Refresh runs from `onTileRequest` when the stored snapshot is older than 30 seconds and no refresh
  is running, bounded by 10 seconds, then asks the system to redraw the tile. The tile sets no
  freshness interval: it never refreshes on its own schedule, only when the system asks to show it.
- A refresh opens its own short-lived encrypted connection (`usingPushConnection`), reads
  `projects.list` and up to 24 projects' `sessions.list`, and `session.snapshot` for the top row.
  It never calls `RemoteRepository.activate`, so the app's selection and navigation stay as they are.
  Failures keep the last snapshot and its time; the tile shows no error.
- While the app is open, its state feeds the same snapshot (`homeUpdates` and `mergeHomeSnapshot`),
  and the open session's pull request is remembered with the time it was seen.
- Pushes fold in through `withPush` (question → waiting, others → idle). No push carries pull
  request data, so checks on the tile are always "last seen, aged".
- The snapshot is a watch-local encrypted file (`watch-glance.enc`), separate from the phone's
  `home` store. Unpairing a Mac drops its rows and pull requests. A remembered pull request older
  than 24 hours is not shown.

## Non-goals

- No new in-app overview screen; the tile is the overview.
- No complication and no ongoing activity in this change.
- Ticket start stays off the watch. Choosing a ticket needs its description, priority and project
  in view, and the start is a polled operation of up to ten minutes. That is neither a brief check
  nor a one-tap confirmed action with enough context, so it stays a phone/tablet action.

## Follow-up

- b) A compact pull request summary per session in `sessions.list` (number, checks state, failed
  count), landed in noah-monorepo first. Then every tile row can show fresh checks.

## Checks

Pure functions (row choice, row text, pull request memory, JSON round trip, push and refresh folds)
have unit tests. Tile rendering, swipe refresh over WLAN/LTE, tap routing and clipping at large font
scale are physical Galaxy Watch checks for Robin.
