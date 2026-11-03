# Android emulator capture and input feasibility — 2026-10-31

This is a local component probe, not an implemented PocketPi capability. The tested emulator was
the disposable `PocketPiBrowserView` AVD on the Mac, using SDK Emulator 37.1.11.0, a dedicated ADB
server on 5038 and serial `emulator-5558`. QEMU properties, AVD name, owned PID, command line and
listener ownership were checked. No physical device or default ADB server was used.

## Result

A promising first implementation route is authenticated emulator gRPC capture combined with
strictly enumerated ADB input commands. Existing browser ownership, frame acknowledgement and
backpressure patterns can inform a separate emulator protocol. Neither ADB serial names alone nor
successful RPC acknowledgements establish device identity or successful interaction.

| Probe | Observed result | Qualification limit |
| --- | --- | --- |
| ADB PNG at 2560×1600 | 10 captures, P50 941.3 ms / P95 1,130.6 ms, 1,937,840 bytes | One static screen; misses current browser 250 ms / 1 MiB frame bounds |
| ADB raw at 2560×1600 | 5 captures, P50 1,109.0 ms / P95 1,430.1 ms, 16,384,016 bytes | Raw capture/transfer also slow; does not isolate which stage dominates |
| ADB PNG at 1280×800 | 5 captures, P50 442.1 ms / P95 445.5 ms, 749,414 bytes | Changes guest display size; not a production scaling solution |
| Authenticated unary gRPC, natural 1080×2400 guest | Node 24.18.0, 5 captures, P50 16.1 ms / P95 20.4 ms; returned 360×800 PNGs, 331,066 bytes | Static sequential samples, not sustained FPS or remote latency |
| gRPC `GoHome` | RPC status 0, but Settings remained foreground for 21 observations through 4,916 ms | Input path not qualified; cause unproven |
| Fixed ADB HOME from fresh Settings | Launcher foreground at first observation 67.4 ms after command completion | One semantic key operation; not touch, Unicode or hardware keyboard qualification |
| Authenticated gRPC stream + fixed ADB HOME | 30 frames / 28 distinct hashes over 1,515.2 ms from stream start, 5,972,282 image bytes | One local Settings-to-Launcher animation, not sustained motion, relay, bandwidth or app qualification |

The root independently ran Node 24 unary capture and the stream/ADB positive-control variant.
The first and last stream images were visually inspected: Settings then Launcher. Native stream
sequence numbers increased with one permitted gap. A stream driven only by the ineffective gRPC
key had yielded one frame; the successful ADB-driven run isolates that earlier result from a
blanket claim that the screenshot stream cannot update.

The first captures immediately after changing `wm size` retained the old letterboxed content.
After the natural display settled, the capture filled the correct portrait image. Display geometry
must therefore be checked at execution/input boundaries; a command acknowledgement is insufficient.

## Authentication and scope

The test restarted only its own AVD with `-grpc 18556 -grpc-use-jwt`. Listener inspection confirmed
`127.0.0.1:18556`; unauthenticated calls failed with gRPC 16 and a valid JWT missing the protected
`sendKey` audience failed with gRPC 7. A transient ES256 public JWK was registered only for this
owned emulator; private keys stayed in memory, tokens were not logged and the public key file was
removed in `finally`. The installed SDK allowlist was not changed. Its issuer policy contains other
allowed methods; a narrow audience is not a replacement for the host's own strict RPC allowlist.
No emulator credentials should be exposed to the phone or agent.

The installed `emulator_controller.proto` describes scaled `getScreenshot` / `streamScreenshot`
requests, PNG/raw formats, rotation metadata and keyboard/touch messages. Its keyboard text field
does not guarantee arbitrary Unicode. The [official Android protobuf source](https://android.googlesource.com/platform/prebuilts/android-emulator/+/master/linux-x86_64/lib/emulator_controller.proto)
provides the public contract; the installed SDK was used for this actual probe. The prototype uses
bounded Node HTTP/2/protobuf framing, a five-second RPC deadline, four MiB per response and bounded
stream duration/count/aggregate size. This scratch codec has not received production parser review.

## Next gates

1. Host-owned or explicitly owner-approved emulator identity, configuration and lifecycle; reject
   physical/foreign devices and arbitrary client serials, processes, ports or shell commands.
2. Separate grants, current session/project trust, control lease, generation, input sequencing,
   revocation/disconnect behavior and frame acknowledgement/backpressure. No automatic input replay.
3. Strict finite ADB input operations, safe argument handling, touch/rotation coordinate mapping and
   meaningful visual tests. General text, Unicode, clipboard and hardware input remain unqualified.
4. Emulator protocol and app pane, actual encrypted phone/tablet flow and Pi/human ownership handoff.
5. Wear, Mac iOS simulator, Linux host packaging and physical Galaxy/DeX/real-network qualification.

Local scripts, metrics and images: `/private/tmp/pocketpi-emulator-feasibility/`. Relevant files are
`grpc_probe_node24.cjs`, `grpc-natural-phone/root-node24-settled.log`, `grpc_semantic_metrics.json`
and `stream-adb-positive/{root-node24.log,grpc_stream_metrics.json,stream-frame-1.png,stream-frame-30.png}`.
These scripts bind to the owned test PID and are not reusable product launchers. The emulator and
its private discovery credentials must be closed/removed after the probe; no capability is advertised.
