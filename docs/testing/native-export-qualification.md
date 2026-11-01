# Native session export qualification — 2026-11-01

## Behavior

Qualified, Host-owned native Pi workers can export and share a self-contained PocketPi HTML snapshot through the existing paginated export pipeline. It captures the public session header, every persisted entry (including inactive branches and custom data), and the live active leaf. The viewer selects the captured branch, supports an empty root with retained history, and exposes complete JSON. Unfinished streaming output is outside this snapshot. This is PocketPi snapshot HTML; Pi theme and custom extension renderers are not reproduced.

Native export requires both `session.export.native.v1` and session `nativeExport: true`. Source capability and exact current ownership qualify the session. Opt-in request IDs prevent unknown metadata reaching strict older clients. RPC export remains compatible. The source exclusively creates the Host reservation with private permissions, checks identity/trust around asynchronous writes, and never reopens the session file or imports private SDK APIs. The existing 20 MiB limit, 49,152-byte chunks, digest verification and FileProvider sharing apply.

## Checks

Tests first reproduced missing Source capability, Host routing and Android eligibility. Review regressions first reproduced eager property allocation, incorrect oversized-source error classification, benign context refresh rejection and a null-root viewer selection bug; these were corrected. Viewer behavior is exercised with a DOM shim and the generated trusted script, including retained history at a null active leaf.

- Source pure/runtime and Host session regressions: 192 passed, 11 existing skips across three files (`/private/tmp/pocketpi-native-export-final-regressions.log`).
- Additional viewer regressions: four passed (`/private/tmp/pocketpi-native-export-viewer-green.log`).
- Whole extension TypeScript check passed (`/private/tmp/pocketpi-native-export-types-final2.log`); changed TypeScript formatting passed.
- Android quick-check: 3,471 app and 34 Wear tests passed, zero failures/errors/skips (`/private/tmp/pocketpi-native-export-quick-check.log`). App and instrumentation APK builds passed.
- Initial combined Host session/daemon run: 191 passed, five skipped, one failed because an unchanged lease-test mock lacked `editorGrants`. Only the mock was corrected; the focused lease regression passed. The entire daemon file was not repeated after this test-only correction.

## Actual Android transport evidence

Private lab: `/private/tmp/pocketpi-paged-production.oge2ybh1`. Both runs used production RemoteNavigation, a real Pi 1.4 native worker, encrypted private Host transport and the existing export/share UI. An actual public Pi command appended a large inactive custom entry and navigated back to an earlier leaf. The intercepted chooser's FileProvider URI was read and checked for length, digest, complete semantic session JSON and active leaf distinct from the file tail. The exported HTML exceeded two chunk lengths. The same workflow subsequently completed shell, explicit local commit/push and controlled PR/CI checks.

| Run | Instrumentation | Shared HTML bytes | SHA-256 |
| --- | --- | --- | --- |
| `native-export-phone-first` | Passed, 86.551 s | 119568 | `8ebd1c461fe8a917d0e5ba4e6600fd27dbc94bd4b1fa927e9eff2fbf1ffaeba8` |
| `native-export-tablet-final` | Passed, 96.846 s | 119571 | `0e0ae405b6f99641eda3a5f7b8ace84c061beca901b9683ce478bbbf6fc5cbbd` |

Each run retains result/final-status JSON, instrumentation/fixture logs, export/agent traces, 21 prelaunch source snapshots and installed APK hashes checked against build outputs. The phone used the renderer before the separate null-root fix; the tablet used the corrected renderer. Both exercised a non-null active leaf. Prelaunch source hashes, rather than the then-dirty Git base alone, identify runtime inputs. Screenshots and actual shared HTML are in each run's `-proofs` directory. Root inspected the tablet success screenshot. Tablet dimensions were 1600×2560 at density 240; the owned emulator was restored to 1080×2400 at density 420. Both fixture cleanups passed.

Independent final review found no concrete remaining blockers (`/private/tmp/native-export-review.md`). The null-root regression is covered by the VM test, not the non-null-leaf emulator fixture.

## Qualification boundaries

These are local emulator and deterministic-provider checks. No physical Galaxy Tab S11/S25, independent Linux SSH machine, external model, real Plane/GitHub/CI or external share recipient was qualified. The user confirmed that devices and an authorized SSH test host are currently unavailable. No personal Host or live infrastructure was changed. Full remote-development acceptance remains open.
