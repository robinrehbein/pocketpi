# Glass design consistency

This change extends PocketPi's existing design language; it does not introduce a new palette,
shape scale or navigation model.

## Surface roles

- Floating controls and menus use the existing glass tint, subtle rim and theme shapes.
- Bottom sheets and popup surfaces may display a blurred snapshot of the parent app on supported
  Android versions. A snapshot is a still image, not live blur: streaming content behind an open
  popup is not continuously captured. Unsupported devices and failed captures must remain readable.
- Forms and information sheets use the existing sheet headers, sections and action components.
- Destructive, trust and discard confirmations remain explicit confirmations with clear actions.
- Code, diffs, recovery text, artifact contents and long lists remain opaque reading surfaces.
  A flat reading surface is not a missing glass treatment.

## Information hierarchy and constrained layouts

Ticket titles carry the primary emphasis; references are secondary metadata. Model selection in
New Session starts compact rather than placing an entire catalog above the prompt. A bounded
selection area retains provider/model identity. Tree summary selection stays inside the existing
sheet rather than opening a second modal window.

Folder actions must remain understandable at narrow widths and larger font scales, stacking when
necessary instead of clipping their labels. Viewer headers reuse the floating control family while
keeping their underlying document surfaces opaque.

## Verification

Run `scripts/quick-check.sh` before pushing. Independent review and green CI on the PR head are
required before a merge. See `CLAUDE.md` for the complete check policy.

Robin's device checks for this change:

- Light and dark themes, including Dynamic and Synthwave palettes.
- Frosted popup/sheet alignment, opening and dismissal on a real Samsung phone; readable fallback
  when backdrop capture or blur is unavailable. Include rotation and freeform-window moves/resizes
  while a sheet is open.
- Narrow phone widths and enlarged system fonts, especially folder actions and viewer titles.
- Keyboard and focus in folder creation, cloning, provider authentication and New Session.
- System Back, outside dismissal and TalkBack navigation in model selection and Tree summary steps.
- Artifact share/reload/pop-out and editor recovery export in full-screen dialogs.
