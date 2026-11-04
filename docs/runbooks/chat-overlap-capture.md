# Capture a persistent chat overlap

Status: investigation only. No root cause or production fix has been established.

## Evidence so far

Galaxy S25, PocketPi `0.3.19-ci.46` (versionCode 46, commit
`0bc25c06e92bcb581bd640415b3ebe052dfe6384`): a screenshot shows two sharp pink
renderings of an outgoing prompt, with different widths and heights. The front
rendering has a timestamp. Robin reports the issue when opening chats, persisting
after scrolling, generally affecting one message at a time across different chats.
Whether both drawings move together during scrolling is not yet known.

Build 46 already disabled LazyColumn placement and exit animation. Restoring those
animations in PR #231 is not a fix for this persistent defect. Robin later confirmed
the newer mixed-row screenshot is from build 47, with the animations restored;
multiple Thinking TEXT headings overlap tool/subagent cards. He suspects the defect
clears only at the very bottom of the chat; this is not yet verified.

Native API 36 emulator tests have not reproduced it:

- enabling the fork control (which narrows and increases the height of a bubble);
- enabling it while changing list insets;
- recycling user rows off screen and returning;
- real app navigation from cached preview to live history with fork capabilities.

These are negative investigation results, not proven regression tests for the
reported defect. Their pixel assertions use Compose `captureToImage()`, not a
full-device screenshot. The emulator uses SwiftShader, not the S25 graphics driver.

## Capture entirely on the phone (no computer connection)

Robin cannot connect the S25 by USB or wireless adb. Use the in-app diagnostics
instead. This needs a build containing the diagnostic controls; build 47 does not
contain them. A merge alone does not publish a new testing build.

1. Open Settings → Diagnostics → Open report. Under **Chat rendering diagnostics**,
   enable **Record recent chat layout**. Return to the affected chat.
2. When the overlap appears, take a normal Samsung screenshot **first**. This is
   separate from the report and can contain private message text.
3. Tap the diagnostic information button in the chat header. It freezes the recent
   layout report before opening its sheet. Choose **Share layout report** or copy
   it and send it with the screenshot.
4. If possible, capture another report after scrolling a little, and another after
   jumping to the bottom. Note whether the overlap disappears and whether both
   drawings move with the content.

The report is a bounded in-memory ring (24 draw samples, at most 32 visible rows
each). It includes anonymous per-export row tokens, slot positions/sizes,
unclipped row window bounds, item types, follow/thinking/scrolling mode, whether more content follows, time since the last
recorded draw, device model, SDK,
app version, density/font scale and current diagnostic options. It includes no
message text, raw message/session IDs, tool arguments, paths or screenshots.
Nothing is uploaded automatically or saved to disk. Sharing uses the Android share
chooser and requires an explicit action. Layout data is cleared when diagnostic
options change and when that chat screen is disposed.

### Controlled A/B tests

The same Diagnostics screen offers **Disable glass effects app-wide temporarily** and
**Disable chat row animations temporarily**. Both are default off and reset when
the app process restarts. Test one at a time with the same chat/scrolling pattern:

- Glass-off removes the Haze source and consumer state, not just blur tint.
- Motion-off removes the entire chat row animation modifier, including fades.

Capture the original screenshot/report before changing anything. Opening a sheet
or changing options can redraw the chat; an immediate disappearance alone does
not establish causality. Compare recurrence over repeated use and include the
selected options in each report. A report may temporarily omit row window bounds
after an option change until those rows are positioned again; missing bounds are
not zero-sized rows. These switches are diagnostic controls, not a
verified production fix. Compose geometry does not expose the complete GPU layer
or draw tree; clean bounds alone do not prove the absence of duplicate rendering.

## Optional developer capture with adb (emulator or other connected devices)

Never require this workflow from Robin: he cannot connect his S25. This is only an
optional developer tool for emulators or other devices. It is not required for the
phone-only workflow above.
Use an authorized USB or wireless adb connection from the Mac. Leave the faulty
screen open; do not restart the app or change visual settings first.

```sh
scripts/capture-chat-overlap.sh -s DEVICE_SERIAL before-scroll
# Manually scroll a short distance without leaving the chat.
scripts/capture-chat-overlap.sh -s DEVICE_SERIAL after-scroll
```

Omit `-s` if exactly one device is connected. The script prints a private temporary
directory for each capture. It does not upload anything, inject input, restart the
app, change settings, or clear data. It removes only its own temporary hierarchy
file on the phone.

**Privacy:** screenshots and UI hierarchy contain visible message text; window
and surface information may identify other apps. Inspect the files before sharing.
There is no logcat collection.

Each capture includes a screenshot **before** accessibility hierarchy collection,
window/surface names, graphics information, device geometry/font scale and app
version, then the UI hierarchy and a second screenshot. UIAutomator can itself
trigger a redraw; compare the two screenshots. If hierarchy collection fails to
reach idle, the script still keeps the other evidence and its failure log.

## Interpretation

- Compare front and rear drawings between the two scroll positions. A fixed ghost
  suggests a different drawing/window path from content moving with the list.
- Compare visible bubble pixels with hierarchy text and physical-pixel bounds.
  Two current nodes are evidence of duplicate composition. One matching node with
  two visible drawings suggests a draw/layer artifact, but is not conclusive:
  semantics can merge, omit, or expose content differently from the draw tree.
- Check window and surface information for an additional overlay.
- A disappearance between the before/after-hierarchy screenshots is useful evidence
  of redraw sensitivity; do not assume the script is observationally inert.

Only after this distinction should an A/B build isolate Haze capture or another
specific layer path. A blanket dependency update, clipping change, padding change,
or disabling animation again is not a verified fix.
