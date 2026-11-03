# Android browser keyboard qualification

The current delivery priority is Galaxy S25 and Galaxy Tab S11. These checks exercise Android Compose and hardware-key dispatch under Robolectric, not physical Samsung hardware or DeX.

## Behavior

Losing focus from the remote browser viewport queues releases for its held keys using their recorded descriptors. Navigation remains blocked until the release receipt is accepted. No release is sent after control is lost.

Hardware repeats for arrows, Home/End, Page Up/Down and backward/forward deletion are represented as discrete release/press pairs. The existing Host rejects duplicate key-down transitions; this change preserves that contract without a new wire capability. Both events are admitted together within the bounded queue, retaining capacity for the final release. Intermediate release receipts keep the logical held-key reservation. Modifier changes are applied to the next repeated press. Excess held-key repeats are consumed locally to avoid moving Android focus when the queue is full.

These discrete presses do not expose DOM `KeyboardEvent.repeat=true`. Printable text and pointer transitions retain their existing paths; shortcuts outside the named navigation/deletion keys do not gain repetition.

## Verification

The original implementation fails both `BrowserPaneTest.losingViewportFocusReleasesHeldKeyAndUnblocksNavigation` and `BrowserPaneTest.hardwareRepeatUsesValidTransitionsAndFinalRelease` at the expected missing-release/repeat waits. The corrected implementation passes those two tests. Additional coverage checks bounded repeat bursts with final release and discarding queued input on control loss.

The final focused suites pass all 30 tests (27 BrowserPane and 3 BrowserKeyboard), with zero failures, errors or skips. App and instrumentation APK assembly pass. `scripts/quick-check.sh` also passes, including resource parity, all affected source-set compilation and the full app/Wear unit suites. Physical Galaxy devices, DeX and external keyboard/mouse qualification remain open.
