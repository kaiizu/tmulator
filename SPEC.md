# TMulator — Android ESC/POS Printer Emulator

## 1. Feasibility assessment

### Network (primary, fully feasible)

A TM-m30 exposes raw ESC/POS on TCP **9100**, bidirectional. Most "generic network
printer" POS configurations use exactly this path: connect to `IP:9100` and stream
ESC/POS bytes. This is fully reproducible on Android with a `ServerSocket`.

Epson's own network stack adds three things beyond plain 9100:

| Layer | Reality | Plan |
|---|---|---|
| RAW 9100 | Raw ESC/POS stream, status bytes returned on same socket | Implement fully |
| ENPC | Proprietary UDP **3289** query/command protocol; used by Epson ePOS SDK/Utilities and some POS apps | Implement the reversed query set (device name, network info, who-is-holding) and the print hold/release commands |
| Epson ESC/POS extensions | Real-time status (`DLE EOT`), ASB (`GS a`), job queue (`GS ( H`) and job status replies | Implement observed behavior sufficiently for Epson SDK-style clients |
| ePOS-Print XML | HTTP/XML API on 80/443 (and ePOS-Device on 8008/8009/8043/8143) | Out of scope for v1; documented |

Discovery protocols such as Bonjour/mDNS and AirPrint/IPP are **not** the same as
raw 9100. Some iPad apps only print through AirPrint or a vendor SDK. Those apps
will not see TMulator through raw 9100 alone. Manual IP / "generic raw" setup is
required. ENPC discovery is included to cover Epson-SDK-style discovery.

### Bluetooth (best-effort, not an iPad solution)

The TM-m30 Bluetooth interface is **Bluetooth 3.0 EDR**, used as a serial (SPP)
transport for ESC/POS. iOS uses a proprietary MFi/iAP path with Epson's bundle
seed ID; that cannot be emulated by a normal Android app. Android also cannot
advertise an arbitrary Bluetooth Class of Device as a printer.

What Android can do:
- Act as an RFCOMM/SPP **server** with the standard Serial Port service UUID.
- Be renamed to the configured printer identity and made discoverable (user
  consent required; typically 300 s max).
- Accept SPP connections from Android/Windows/legacy POS clients that support
  manual SPP connection and pair with the OS dialog.

What it cannot do:
- Appear as an MFi printer to iPadOS.
- Imitate Epson's proprietary BLE GATT transport.
- Set a fixed pairing PIN (`0000`) or printer Class of Device.
- Guarantee discoverability while the screen is off.

Bluetooth is therefore shipped as a documented best-effort phase-2 transport,
independent from the network server and able to run simultaneously.

### Background execution

A foreground service with a persistent notification keeps listeners alive while
the app is backgrounded. It holds a partial wake lock and a low-latency Wi-Fi
lock (when the network listener is enabled) so the radio does not sleep between
the Epson handshake and the receipt burst. Android may still stop it under
extreme memory pressure or OEM battery management; the app exposes the service
state and a keep-awake option. There is no cloud dependency.

## 2. Architecture

```
POS / iPad ──TCP 9100──┐
POS discovery ─UDP 3289┤
POS / Android ─SPP─────┤
                       ▼
              transport layer
        NetworkPrintServer / EnpcResponder / BluetoothSppServer
                       │ raw bytes + status writer
                       ▼
              EscPosParser (pure Kotlin)
        incremental parser, code pages, command log
                       ▼
              ReceiptDocument model
        text runs, images, barcodes, QR, cuts
                       ▼
           ReceiptRenderer (Android Canvas)
           576/384-dot thermal raster + paper
                       ▼
        Compose UI / PNG export / job history
```

- Single Android app module, separated packages, no dependency on Android in the
  parser or network transport (JVM-testable).
- One parser/document per connection. The most recent job drives the live preview;
  completed jobs become history.
- Status responses are produced by the parser and written back on the same socket.
- Rendering is a real 203-dpi-style bitmap (576 dots for 80 mm), not a text mockup.
  The UI scales it into a paper roll and animates feed/cut.
- Settings in DataStore; runtime state in StateFlows; sessions persisted as PNGs
  plus a small JSON index (last 20).

## 3. ESC/POS support (v1)

Text: code pages (CP437/850/852/858/1252, common ESC t values), Font A/B, bold,
underline, double strike, reverse, character width/height 1–8x, alignment,
line spacing, feeds, absolute/relative position, tabs, CR/LF, initialize.

Graphics: `GS v 0` raster, `GS 8 L` / `GS ( L` stored raster (Square/Epson SDK
style), `ESC *` column images. Images are rendered as 1-bit thermal output.

Barcodes: UPC-A/E, EAN-8/13, Code39, Code93, Code128, ITF, Codabar via ZXing.
QR code via `GS ( k` (model, module size, error correction, store, print).

Paper: full/partial cut markers, feed commands, cut animation, cash-drawer pulse
logging. Status: `DLE EOT 1–4`, `DLE ENQ`, `GS r`, `GS a` ASB, `GS I`, Epson job
queue echo. Unsupported commands are skipped safely and logged with hex + reason.

## 4. Known limitations

- Not cycle-accurate; obscure Epson commands, macros, user-defined characters,
  page mode and some advanced graphics functions are ignored (and logged).
- No ePOS-Print XML / AirPrint / IPP / mDNS / SNMP.
- Status is simulated (ready, paper-out, cover-closed, no errors); no real hardware
  state.
- Some POS apps require a precise model handshake, proprietary driver or cloud
  service and may refuse a generic raw printer.
- iPad Bluetooth printing requires MFi and is out of scope.
- Android may limit background sockets under OEM battery savers.
- Ports below 1024 (e.g. 80) are not used; ePOS HTTP is not implemented.

## 5. Implementation plan

1. Build scaffold + Material 3 theme + navigation shell.
2. Pure Kotlin receipt model + incremental ESC/POS parser, code pages, status.
3. Compose receipt renderer (thermal bitmap, barcodes/QR/images, cut).
4. TCP 9100 server + ENPC 3289 discovery + job lifecycle/history.
5. Foreground service, settings, IP detection, keep-awake, permissions.
6. Main/settings/jobs/debug screens with live updates and export/share.
7. Bluetooth SPP server + discoverability flow, clearly labelled best-effort.
8. JVM tests (parser, incremental chunks, status, real TCP loopback, ENPC) and a
   realistic ESC/POS fixture; build installable APK; document test procedure.

## 6. Validation

- Unit tests for parser/model/status, including split-across-reads commands.
- Loopback TCP test: start the real server, send a full styled receipt, read the
  `DLE EOT` response, assert preview text/blocks and job completion.
- ENPC unit test with real query packets.
- Manual: install APK, add `IP:9100` as generic raw/ESC-POS printer in a POS app,
  print; send fixture from a PC with `tools/send-test-receipt.py`; test barcodes,
  QR, image, cut and status. Bluetooth tested separately on Android/Windows SPP
  client; iPad limitation documented.
