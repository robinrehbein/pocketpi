# Native custom dialog through the Android terminal

Status: **two scoped native Pi 1.4.0 / actual MainActivity cases passed**, with independent source/APK/trace audit and root visual screenshot inspection. This qualifies the deterministic public custom component below, not arbitrary components or the full remote-development goal.

## Actual path

The private `--native-custom-dialog` fixture starts a real Host-owned native Pi PTY, loads a public extension command and grants the isolated paired device terminal access. `/probe-custom <nonce>` opens a public `ctx.ui.custom` component with a title, nonce and two choices. No model call or Pi core change is involved.

The actual RemoteApplication-created repository, pairing store, MainActivity, composer and SecureRemoteTransport dispatch the command once. A fixture-owned local native terminal attachment initially holds control. The Android test opens the real terminal action and requires the `mac` holder response, clicks the actual takeover confirmation, then waits for attachment and the fixture holder's revoked callback. An empty write through the old handle is rejected by the native registry's generation check before reaching PTY input.

The test checks xterm DOM rows and the same WebView's software bitmap, records geometry, then checks actual screenshot pixels inside the visible WebView rectangle. The pixel oracle proves foreground/background presence only; root separately inspected each final screenshot for title, nonce and both choice lines. It does not replace text identity inspection. The test uses the terminal's Down and Enter UI controls. The source trace must contain exactly `opened`, `rendered`, `down`, `enter`, `result` with value `second`, and `followup`, all with the same nonce, PID, session and TUI mode. Closing the pane releases the device terminal attachment; a subsequent real composer command verifies continued use of the same worker. All nine Host booleans and the completed Android proof are required.

## Final recorded cases

| Label | Layout | Result / cleanup | Controller duration |
| --- | --- | --- | --- |
| `custom-terminal-takeover-phone-02` | 1080 × 2400, 420 dpi | PASS / PASS | 18.292 s |
| `custom-terminal-takeover-tablet-01` | 1600 × 2560, 240 dpi override | PASS / PASS | 20.365 s |

The tablet screenshot shows the custom component in the inspector beside the chat; the phone screenshot shows the full terminal overlay. Display overrides were reset to physical 1080 × 2400 / 420 dpi. Each case pins 43 runtime/test sources, controller, installed APKs and build APKs; independent checks found the saved snapshots, current sources and hashes consistent. The fixture PID and session in the six-event trace match the native runtime identity and Android proof. Evidence is in the private lab `/private/tmp/pocketpi-paged-production.oge2ybh1`, not a distributable artifact.

`custom-terminal-text-process-compat-phone-01` passed both existing actual MainActivity process phases and cleanup with the extended fixture's custom mode disabled. It is text-process recovery compatibility, not custom-component process-death qualification.

## Historical attempts and corrections

- `custom-terminal-phone-01` passed source-result and followup assertions, but its captured physical screen was blank despite populated DOM/software bitmap. It is not a visual pass. The diagnostic was strengthened to retain actual screenshot crop, native/DOM geometry and bounded physical-screen readiness. The cause of the earlier blank capture is not established.
- `custom-terminal-screen-diagnostic-phone-01` passed and root inspected the visible component; this preceded the forced local-holder gate and does not prove confirmed takeover.
- `custom-terminal-takeover-phone-01` failed waiting for terminal attachment. Its screenshot shows the still-open confirmation dialog; an indexed text selector had selected the title instead of the button. The final test selects exactly one enabled clickable node inside the dialog, and records confirmed takeover only after Host revocation and attachment. The old controller reported cleanup failed solely because it treated an expected incomplete failure proof as a cleanup error; its separate evidence records fixture exit zero, media fallback zero and unchanged inputs. That original failure record is retained. The controller now separates incomplete failure proof from resource cleanup and validates optional diagnostic formats.

## Limits

This is an owned emulator on the current Mac with Pi 1.4.0 and one deterministic custom component. No physical Galaxy Tab S11/S25, independent Linux host, arbitrary extension/editor, distant-network behavior, terminal reconnect during the component, physical keyboard/mouse/DeX or iOS simulator is qualified here. The local attachment is a controlled fixture holder; no personal terminal or Host was modified. Default production capabilities and trust boundaries are unchanged. The public paged-editor capability remains disabled pending its separate gates.


## Build and repository checks

The final selector/source APK build passed (`/private/tmp/pocketpi-custom-terminal-selector-build.log`), and the Host TypeScript check passed after the holder gate (`/private/tmp/pocketpi-custom-terminal-takeover-types.log`). Exact changed-file Host formatting/whitespace checks passed. The final `scripts/quick-check.sh` passed in 7 seconds with all 98 Gradle tasks up-to-date; existing App/Wear unit results were reused, not newly executed for these test-only additions. Both final runtime cases used the same built/installed APKs and current pinned sources.
