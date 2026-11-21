# TMulator code audit and action plan

Audit date: November 14, 2026. App version: 0.2.0, version code 3.

The app needs corrective work before it can reliably capture arbitrary POS traffic. The main risks are lost receipts on persistent connections, excessive memory allocation from valid raster input, parser framing errors, and listener lifecycle races. The existing structure is suitable for fixing these problems incrementally; a rewrite is unnecessary.

This audit made no production application edits. Audit probes and evidence are in [tools/audit](D:/Dev/tmulator/tools/audit). This checkout has no Git metadata, so the reviewed production files are identified by SHA-256 hashes in [source-manifest.json](D:/Dev/tmulator/tools/audit/source-manifest.json).

> **Post-audit status (2026-11-14, v0.3.1).** The 0.3.1 correctness pass fixed
> the reproduced failures: A01/A12 (per-cut receipts; Clear dismisses, Reset
> resets), A04 (correct framed-command skipping), A09 (`GS r` and ASB status),
> A10 (column images), A11 (positioning aliases/one-shot absolute X), A05/A06
> (listener ownership and unique peer keys), A02/A03 (raster/text/content
> budgets), and A18 (lint: 0 errors, 11 warnings). A20's About version is also
> fixed. The 14 probes now run in the normal app test suite and pass.
>
> Still open for a later pass: A07 (archive truncation), A08 (render
> scheduling), A13 (transactional persistence), A14 (lifecycle recovery), A15
> (power/lock ownership), A16 (LAN interface selection), A17 (log allocation).
> A19 remains intentional for personal sideload builds. A20's theme/excerpt
> polish remains.
>
> The audit's latency paragraph is superseded: v0.3.0 fixed the ~7 s stall by
> answering the ENPC hold command (`EPSONC 03 00 00 15`), not by ESC/POS
> changes. The A05 probe now asserts port reuse even when immediate stop wins
> the startup race, rather than requiring `onListening` to fire.
>
> The final 0.3.1 release APK was installed and revalidated on 2026-11-14:
> version 0.3.1 (code 5), ENPC hold reply intact, PC TCP 9100 receipt accepted,
> and a real iPos print saved as a separate 6.8 KB / 52-block cut receipt.
>
> 0.3.2 is a rendering follow-up, not an audit change: anti-aliased text,
> nearest-neighbour receipt scaling, split raster-band reassembly, and recovery
> of POS-rasterized QR codes into clean scannable QR blocks.


## Scope and verification

Reviewed all 30 production Kotlin files, all 3 existing Kotlin test files, the manifest and resources, Gradle configuration, README, specification, and the three Python diagnostic tools. The final production Kotlin files contain 7,003 physical lines. Historical diagnostic captures were treated as background evidence, not as new device measurements.

| Check | Result | Meaning |
| --- | --- | --- |
| Final existing JVM suite | 35 passed, 0 failed | 28 parser tests, 5 ENPC tests, 2 TCP tests |
| Isolated audit suite | 14 probes: 13 failed, 1 observation passed | Failures reproduce additional defects; these probes are opt-in |
| Combined audit evidence run | 48 tests, 13 failures | 34 existing tests plus 14 audit probes; all failures belong to the probes |
| Android lint debug | Failed: 9 errors, 11 warnings | Includes issues requiring fixes and analyzer false positives discussed below |
| Release assembly | Passed, incremental build | R8/resource shrinking and release packaging completed |

Checks used the existing JDK 21 installation and Gradle 8.9 wrapper. The initial offline lint attempt lacked a dependency; the online retry completed dependency resolution. Test XML and lint output are preserved under tools/audit.

Concurrent workspace work added the TM-m30III profile, one parser test, and extra ENPC holder logging after the audit evidence run. Those additions were reviewed, and the final standard test count is therefore 35 rather than 34. The reproduced defects remain applicable; the final production source manifest includes the profile and logging additions.

The new tests exercise real parser logic, real loopback TCP sockets, and JobManager state transitions. Android graphics calls use the project's default-value JVM stubs. Therefore they do **not** demonstrate actual Android bitmap rendering, PNG persistence, frame rates, Bluetooth operation, battery drain, or an on-device out-of-memory crash. Raster allocation estimates below follow directly from accepted dimensions and the renderer's allocation sizes.

## Prioritized findings

P1 means fix in the next corrective release. P2 means schedule after the P1 fixes, or alongside the affected component. P3 means maintenance or an optional improvement. A reproduced failure is stronger evidence than a static risk; the evidence column makes that distinction explicit.

| ID | Priority | Finding | Evidence |
| --- | --- | --- | --- |
| A01 | P1 | Only the first receipt on a connection is finalized | Reproduced |
| A02 | P1 | Small packed raster input causes very large temporary allocations | Input accepted; allocation calculation |
| A03 | P1 | Session content has no meaningful aggregate memory bound | Retention reproduced; static analysis |
| A04 | P1 | Unsupported extension framing can block subsequent printing | Reproduced |
| A05 | P1 | Stopping a listener during startup can leave its socket bound | Reproduced twice |
| A06 | P1 | Client keys repeat across listener instances and stale callbacks mutate current state | Key reuse reproduced; race analysis |
| A07 | P1 | Preview truncation also truncates saved and shared receipts | Static analysis |
| A08 | P2 | Rendering and snapshot scheduling do redundant work and allow stale publication | Static analysis |
| A09 | P2 | GS r responses and simulated ASB behavior are incorrect | GS r reproduced; reference comparison |
| A10 | P2 | Column images have bit order, density, and framing defects | Three reproduced failures |
| A11 | P2 | Positioning and parameter interpretation produce incorrect output | Two reproduced failures; static analysis |
| A12 | P2 | Clear/reset leave stale state and can erase other sessions | Two reproduced failures; static analysis |
| A13 | P2 | Persistence can report success after failure and has initialization gaps | Static analysis |
| A14 | P2 | Listener recovery and service shutdown lack consistent ownership | Static analysis; device validation needed |
| A15 | P2 | Wake locks and periodic polling waste resources in failure/idle states | Static analysis |
| A16 | P2 | Discovery can advertise the wrong interface or subnet | Static analysis; device validation needed |
| A17 | P2 | Log traffic causes avoidable allocation and broad UI invalidation | Static analysis; profiling needed |
| A18 | P2 | Standard lint currently fails | Tool result |
| A19 | P3 | Release signing and build verification need a reproducible workflow | Configuration review |
| A20 | P3 | UI, documentation, and diagnostics contain inconsistencies | Source review; counter failure reproduced |

### A01 Persistent connections lose subsequent receipts

Source: [JobManager.kt:156](D:/Dev/tmulator/app/src/main/java/dev/tmulator/service/JobManager.kt:156), [finalization guard:205](D:/Dev/tmulator/app/src/main/java/dev/tmulator/service/JobManager.kt:205), [disconnect fallback:187](D:/Dev/tmulator/app/src/main/java/dev/tmulator/service/JobManager.kt:187).

The first cut sets finalized and saveLaunched permanently on the connection's Session. Later bytes still enter the same parser/document, but requestPreview rejects finalized sessions and disconnect skips another save. A first receipt followed by a second receipt on the same socket leaves the second receipt without an active job or later save. Clearing the receipt does not rearm these flags. Multiple receipts arriving in one feed can instead be saved together, because cuts are inspected only after the entire chunk has been parsed. Receipt history therefore depends on TCP chunk boundaries.

**Action:** Separate connection state from receipt state. Emit a completed receipt at each cut and allocate a new job ID, counters, and document segment for subsequent printable data. Preserve the connection's printer configuration as appropriate. Handle bytes following a cut within the same feed instead of resetting away that tail. Keep disconnect as the fallback for the remaining uncut receipt.

**Acceptance:** Three receipts over one connection produce three history entries and previews, identically whether sent together, byte by byte, or in arbitrary chunks. Repeat after Clear and Reset.

### A02 Raster expansion can exhaust memory

Source: [ReceiptRenderer.kt:280](D:/Dev/tmulator/app/src/main/java/dev/tmulator/render/ReceiptRenderer.kt:280), [raster acceptance:938](D:/Dev/tmulator/app/src/main/java/dev/tmulator/escpos/EscPosParser.kt:938).

The renderer checks the scaled target dimensions, then creates a full-resolution ARGB bitmap and an equally large IntArray for the original image. The audit fed an accepted 8192 × 4096 image using 4,194,304 packed bytes. Its scaled target is only 576 × 288, so it passes the guard. The original bitmap and array each require approximately 128 MiB, for **256 MiB of temporary pixel storage**, before the receipt bitmap and other app memory. The 14,000-dot receipt height cap does not protect this path.

**Action:** Bound source pixel counts and per-job memory before allocating. Rasterize directly into the clipped/scaled destination or process source rows in bounded strips. Avoid allocating both an original-size bitmap and original-size pixel array. Use checked Long arithmetic for pixel/byte budgets, and release temporary bitmaps in finally blocks.

**Acceptance:** The supplied wide raster and multiple consecutive rasters render or fail with a clear bounded-input error on a low-memory device. Peak allocation must be measured; catching OutOfMemoryError is insufficient protection.

### A03 Parsed documents can retain unbounded content

Source: [EscPosParser.kt:1034](D:/Dev/tmulator/app/src/main/java/dev/tmulator/escpos/EscPosParser.kt:1034), [ReceiptModel.kt:182](D:/Dev/tmulator/app/src/main/java/dev/tmulator/escpos/ReceiptModel.kt:182), [NetworkPrintServer.kt:37](D:/Dev/tmulator/app/src/main/java/dev/tmulator/transport/NetworkPrintServer.kt:37).

The 12 MiB limit covers bytes waiting to be parsed, not text and images retained after parsing. The audit retained 1,048,576 characters as a single unterminated text block. Text/StringBuilder length and accumulated image bytes are not capped. A 5,000-block limit does not bound block size, and silently discards later blocks. Up to eight clients can hold such documents. Idle connections also have no inactivity/progress policy and can occupy all slots indefinitely.

**Action:** Define aggregate limits for retained text, image bytes/pixels, block count, and concurrent jobs. Split long text into bounded internal chunks without inventing paper feeds. Reject or explicitly truncate over-limit input with a visible reason; do not silently continue after losing content. Add a generous, configurable idle/progress policy compatible with persistent POS sockets.

**Acceptance:** Stress unterminated text, many images, many blocks, and eight clients. Heap usage must plateau at the configured budget, with a clear outcome for every rejected job.

### A04 Extension framing can stall the print stream

Source: [EscPosParser.kt:486](D:/Dev/tmulator/app/src/main/java/dev/tmulator/escpos/EscPosParser.kt:486), [FS extension branch:546](D:/Dev/tmulator/app/src/main/java/dev/tmulator/escpos/EscPosParser.kt:546).

The generic ESC ( branch reads its length at index + 2, which is the command kind, and uses an incorrect header size. A valid nine-byte beeper command followed by OK and LF leaves OK unprinted because the parser waits for a falsely large payload. The FS ( branch has the same framing pattern. [Epson's ESC ( A format](https://download4.epson.biz/sec_pubs/pos/reference_en/escpos/esc_lparen_ca_fn48.html) places the kind before the two length bytes.

Some recognized extensions also check bytes available in the whole receive buffer rather than the declared command payload. Short malformed commands can borrow bytes from the next command or wait unnecessarily. Oversized raster/GS 8 L rejection consumes only the command prefix, allowing binary payload to be reinterpreted as text and commands.

**Action:** Centralize framing into a reader that validates header size, declared payload size, and each function's minimum payload before accessing fields. Consume exactly the framed unsupported command. For invalid/oversized frames, use a bounded discard strategy or terminate that job/session with a diagnostic; avoid interpreting its binary payload as a fresh stream.

**Acceptance:** An unsupported framed command followed by a valid receipt still prints that receipt. Test every header split, short payload, zero length, oversized declaration, and recovery boundary.

### A05 Listener startup and stop race can leak sockets

Source: [NetworkPrintServer.kt:48](D:/Dev/tmulator/app/src/main/java/dev/tmulator/transport/NetworkPrintServer.kt:48), [socket publication:67](D:/Dev/tmulator/app/src/main/java/dev/tmulator/transport/NetworkPrintServer.kt:67), [EnpcDiscovery.kt:129](D:/Dev/tmulator/app/src/main/java/dev/tmulator/transport/EnpcDiscovery.kt:129).

start creates a thread before that thread binds and publishes serverSocket. An immediate stop can run while the field is null. The worker then binds anyway, observes running = false, and exits without closing the local ServerSocket. The audit reproduced an open bound socket after stop in two runs. TCP acceptLoop has no finally block to close the listener on other exits either. ENPC has the same startup publication window, and its finally cleanup reads the mutable field that stop can null out.

**Action:** Give each listener generation explicit synchronized ownership. Check cancellation while publishing a newly opened socket, close that local socket in finally, and join/await termination before replacing an instance. Never rely on Thread.interrupt alone to cancel blocking socket I/O.

**Acceptance:** Repeated immediate start/stop and port changes leave no bound sockets or worker threads. The port must be immediately reusable after stop, including startup and bind-failure cases. Add the analogous UDP test.

### A06 Restarted listeners reuse client identities

Source: [NetworkPrintServer.kt:44](D:/Dev/tmulator/app/src/main/java/dev/tmulator/transport/NetworkPrintServer.kt:44), [PrinterService.kt:233](D:/Dev/tmulator/app/src/main/java/dev/tmulator/service/PrinterService.kt:233), [Bluetooth disconnect:335](D:/Dev/tmulator/app/src/main/java/dev/tmulator/service/PrinterService.kt:335).

Each new TCP server starts IDs at 1, so its first peer is always net-1. Bluetooth instances similarly restart bt- IDs. JobManager indexes sessions using those keys. Old disconnect callbacks call jobManager.end and adjust shared counters before checking their generation. A delayed callback from an old listener can therefore remove a new session with the same key or decrement the new generation's client count. Plain generation/stop fields are also shared across threads without consistent synchronization.

**Action:** Include a unique listener-instance token in peer keys, and perform generation checks before all state mutation. Close the exact Session belonging to the peer object rather than removing by a potentially reused string alone. Confine service state transitions to one dispatcher/actor or use explicit atomic synchronization.

**Acceptance:** Hold an old disconnect callback until after a new client connects, then release it. The new session, counts, holder address, and status must remain intact. Exercise both transports.

### A07 Saved and shared receipts inherit the preview cutoff

Source: [ReceiptRenderer.kt:29](D:/Dev/tmulator/app/src/main/java/dev/tmulator/render/ReceiptRenderer.kt:29), [JobManager.kt:209](D:/Dev/tmulator/app/src/main/java/dev/tmulator/service/JobManager.kt:209), [ShareUtils.kt:19](D:/Dev/tmulator/app/src/main/java/dev/tmulator/util/ShareUtils.kt:19).

RenderOptions caps output at 14,000 dots, about 1.75 metres at 203 dpi. Final history PNGs and Share use the same default renderer. The warning calls this a preview truncation, but the saved/exported receipt also loses its bottom. Only 500 characters of text are persisted, so reopening history cannot recover the lost content. The document also silently drops blocks at its separate cap.

**Action:** Separate preview limits from archival output. Persist a bounded full receipt representation and export in tiles/pages or another format that preserves every accepted block. If a hard product limit is intentional, expose truncation in the job metadata/UI and avoid presenting it as a complete capture.

**Acceptance:** A receipt longer than 14,000 dots retains its last item, total, QR, and cut in history and export. Oversized/rejected receipts must be explicitly marked.

### A08 Preview work is repeated and can publish stale state

Source: [JobManager.kt:104](D:/Dev/tmulator/app/src/main/java/dev/tmulator/service/JobManager.kt:104), [preview scheduling:275](D:/Dev/tmulator/app/src/main/java/dev/tmulator/service/JobManager.kt:275), [publishActive:318](D:/Dev/tmulator/app/src/main/java/dev/tmulator/service/JobManager.kt:318), [ReceiptRenderer.kt:61](D:/Dev/tmulator/app/src/main/java/dev/tmulator/render/ReceiptRenderer.kt:61).

Every receive chunk snapshots the whole block list, scans cuts, and may snapshot again for active state and rendering. Status-only traffic after printable content can trigger rendering without a document revision change. Each render lays out the entire document before applying the height cap, creates Glyph objects per character, and encodes QR matrices once for measuring and again for drawing.

The 140 ms gate is a throttle, not strict backpressure. The immediate branch can schedule work even when a delayed render is already queued, and slow rendering can accumulate launches. A delayed preview checks finishing/session identity but not finalized before publishing; it can publish an active preview after cut finalization. Clear/reset have no epoch to invalidate already captured render work.

**Action:** Use one conflated pending render per receipt, keyed by receipt ID, document revision, and clear/reset epoch. Skip unchanged revisions, cancel/supersede obsolete work, and validate identity immediately before publishing. Read counters without copying whole snapshots; snapshot only for an actual render/save. Cache layout and QR/image results for unchanged blocks, stop preview layout at its viewport budget, and keep persistence off the render critical path.

**Acceptance:** Under sustained input, at most one render is in flight and one newer revision pending per job. Clear must stay cleared, cut must stay finalized, and status polling must not increase the render count. Compare render counts, allocations, and p95 latency before and after.

### A09 Status responses do not consistently follow Epson semantics

Source: [EscPosParser.kt:641](D:/Dev/tmulator/app/src/main/java/dev/tmulator/escpos/EscPosParser.kt:641), [ASB bytes:1136](D:/Dev/tmulator/app/src/main/java/dev/tmulator/escpos/EscPosParser.kt:1136), [PrinterService.kt:152](D:/Dev/tmulator/app/src/main/java/dev/tmulator/service/PrinterService.kt:152).

GS r incorrectly reuses DLE EOT responses. The reproduced ready-paper request GS r 1 returns 0x16 instead of the appropriate paper sensor byte, 0x00 when both paper sensors report adequate paper. [Epson documents distinct GS r sensor bits](https://download4.epson.biz/sec_pubs/pos/reference_en/escpos/gs_lr.html).

ASB remembers only a Boolean instead of the selected status mask. Changing simulated cover/paper state updates the UI/settings but no caller uses currentAsb to notify connected clients. The encoder places those changes into the second byte rather than the documented cover/offline and paper-sensor positions. [Epson ASB](https://download4.epson.biz/sec_pubs/pos/reference_en/escpos/gs_la.html) specifies both change notifications and the affected bit positions.

**Action:** Implement separate encoders for each status family and parameter alias. Retain the ASB selection mask and send a status block when an enabled item changes. Serialize writes per peer. Preserve known working TM-m30 handshake fixtures while adding paper-out/cover-open transition fixtures; verify model-specific differences against a physical printer or authoritative capture.

**Acceptance:** Correct ready, paper-out, cover-open, and restored states for GS r, DLE EOT, and ASB. A client subscribed to ASB receives changes without sending another command, and receives none when the relevant notifications are disabled.

### A10 Column images are flipped, scaled incorrectly, or consume text

Source: [ReceiptModel.kt:65](D:/Dev/tmulator/app/src/main/java/dev/tmulator/escpos/ReceiptModel.kt:65), [EscPosParser.kt:504](D:/Dev/tmulator/app/src/main/java/dev/tmulator/escpos/EscPosParser.kt:504), [column command framing:522](D:/Dev/tmulator/app/src/main/java/dev/tmulator/escpos/EscPosParser.kt:522).

Three focused probes fail: 0x80 lights the bottom dot instead of the top dot in an eight-dot column; ESC * mode 33 doubles width even though it is the full-density mode; and an ESC K image followed by OK consumes O, leaving only K. parseColumnImage treats its four-byte header as five bytes. The 24-dot path reverses bits within each eight-dot group. [Epson's ESC * reference](https://download4.epson.biz/sec_pubs/pos/reference_en/escpos/esc_asterisk.html) gives the density modes and data-to-dot diagrams.

**Action:** Decode columns most-significant-bit first, normalize density into the selected profile's physical dot grid, and correct each legacy command's explicit header size. Validate rasterized golden images, including asymmetric patterns that reveal flipping.

**Acceptance:** Asymmetric 8/24-dot patterns render upright at the right size, with adjacent text preserved, for all supported modes and chunk boundaries.

### A11 Text positioning and barcode interpretation are incomplete

Source: [EscPosParser.kt:349](D:/Dev/tmulator/app/src/main/java/dev/tmulator/escpos/EscPosParser.kt:349), [justification:420](D:/Dev/tmulator/app/src/main/java/dev/tmulator/escpos/EscPosParser.kt:420), [Code128 conversion:1011](D:/Dev/tmulator/app/src/main/java/dev/tmulator/escpos/EscPosParser.kt:1011), [ReceiptRenderer.kt:147](D:/Dev/tmulator/app/src/main/java/dev/tmulator/render/ReceiptRenderer.kt:147).

ESC a 49 is valid centered justification but produces LEFT. [Epson accepts both numeric and ASCII forms](https://download4.epson.biz/sec_pubs/pos/reference_en/escpos/esc_la.html). Other commands also need parameter-by-parameter review; they do not all use the same Boolean convention.

After ESC $ 100, a style change leaves absoluteX = 100 on both text runs, so the renderer restarts the second run at the first character's position. The audit reproduces the duplicated position. Custom tab stops, relative positioning, and character-spacing state are also incompletely wired despite broad documentation claims.

Code128 handling only removes an initial code-set prefix. It does not interpret later set switches, escaping, or function characters before passing the string to ZXing. This can encode a different value than the POS requested. [Epson's GS k reference](https://download4.epson.biz/sec_pubs/pos/reference_en/escpos/gs_lk.html) describes those control sequences.

**Action:** Make positioning commands explicit cursor events instead of persistent style attributes. Normalize allowed aliases for each command. Implement the supported Code128 control grammar and preserve binary semantics, or document/reject unsupported forms explicitly. Build fixtures from more than the built-in sample.

**Acceptance:** Positioned mixed-style text does not overlap; numeric/ASCII aliases agree; custom tabs and relative positions work as documented; scanner-decoded barcodes equal the original intended values.

### A12 Reset and display clearing are entangled

Source: [EscPosParser.kt:146](D:/Dev/tmulator/app/src/main/java/dev/tmulator/escpos/EscPosParser.kt:146), [JobManager.kt:257](D:/Dev/tmulator/app/src/main/java/dev/tmulator/service/JobManager.kt:257), [PrinterViewModel.kt:105](D:/Dev/tmulator/app/src/main/java/dev/tmulator/ui/PrinterViewModel.kt:105).

resetDocument clears document/style state but leaves the receive-buffer length intact. Feeding an incomplete ESC E, resetting, then printing Hello produces ello because H becomes the old command's parameter. It also leaves job lifecycle flags unchanged. clearCurrent resets every connected parser, so dismissing the visible receipt can discard another client's in-progress content. Clear and Reset route to the same service behavior.

**Action:** Define display dismissal, receipt cancellation, and printer reset separately. Display clearing should invalidate preview publication without resetting every connection. A protocol reset should discard incomplete input and reset all relevant counters/lifecycle state deliberately. Coordinate reset with the same per-session serialization used for parsing and rendering.

**Acceptance:** Reset at every incomplete-command boundary followed by a fresh receipt preserves the entire new receipt. Clearing one preview leaves unrelated clients intact and does not let an older queued preview reappear.

### A13 Persistence can claim success without a durable receipt

Source: [JobStore.kt:33](D:/Dev/tmulator/app/src/main/java/dev/tmulator/data/JobStore.kt:33), [add:50](D:/Dev/tmulator/app/src/main/java/dev/tmulator/data/JobStore.kt:50), [persist:114](D:/Dev/tmulator/app/src/main/java/dev/tmulator/data/JobStore.kt:114), [JobManager.kt:240](D:/Dev/tmulator/app/src/main/java/dev/tmulator/service/JobManager.kt:240).

PNG compression's Boolean result is ignored. PNG exceptions are swallowed and converted into an entry without an image; index-write exceptions are logged but not returned. JobManager then marks the session saved, increments jobsHandled, and logs Print job saved. The in-memory list is updated and older PNGs can be deleted before the new index is durable. A failed write can therefore lose the old representation while reporting success.

Initialization launches load asynchronously without a readiness barrier. The mutex serializes calls but does not guarantee that load executes before the first add/clear/delete. An early add can persist an index built from the initially empty list. Orphan PNGs after interrupted writes and images excluded from the loaded index are not reconciled.

**Action:** Await initialization before mutations. Return a structured persistence result, check compression, write PNG/index through temporary files with atomic replacement where supported, and commit metadata before garbage-collecting obsolete files. Reconcile orphan/temp files on load. Surface failures without a success toast/log or counter increment.

**Acceptance:** Test first-write-before-load, disk full, failed compression, interrupted index replacement, corrupt index, and process restart. A reported save must remain readable after restart; failure must preserve the previous valid history.

### A14 Lifecycle recovery and error ownership need tightening

Source: [PrinterService.kt:118](D:/Dev/tmulator/app/src/main/java/dev/tmulator/service/PrinterService.kt:118), [applySettings:147](D:/Dev/tmulator/app/src/main/java/dev/tmulator/service/PrinterService.kt:147), [cleanup:553](D:/Dev/tmulator/app/src/main/java/dev/tmulator/service/PrinterService.kt:553), [BluetoothSppServer.kt:129](D:/Dev/tmulator/app/src/main/java/dev/tmulator/transport/BluetoothSppServer.kt:129).

Foreground promotion failure is logged but startup continues and serviceRunning becomes true. External Bluetooth adapter/permission changes are not observed, so an enabled setting can remain in a dead/error state until manually toggled. Bluetooth accept-loop failure can simply set isRunning false without notifying the service. TCP bind failure leaves its running flag true, and broad Throwable catches hide application callback/parser failures as if a client merely disconnected.

cleanup cancels the collectors but never closes the service's parent CoroutineScope. Final saves and other work can outlive service teardown without a defined drain/join policy. Settings use an initial enabled PrinterSettings value before persisted settings finish loading, creating a startup race that can briefly start listeners with defaults even when saved settings disable them or specify another port.

**Action:** Await settings readiness before opening listeners. Make foreground promotion return success/failure and stop cleanly on failure. Observe adapter/permission/network changes, publish terminal listener errors, and restart only when appropriate with bounded backoff. Define a shutdown drain for accepted receipts, then cancel/join the owned scope. Catch expected I/O failures separately, rethrow coroutine cancellation, and log unexpected failures with their causes.

**Acceptance:** Device-test Bluetooth off/on, permission revocation/regrant, foreground-start failure, port conflict/recovery, rapid Stop/Start, process recreation, and a save during shutdown. Runtime state must agree with actual listeners and accepted jobs must have explicit outcomes.

### A15 Power use is tied to settings rather than working listeners

Source: [PrinterService.kt:139](D:/Dev/tmulator/app/src/main/java/dev/tmulator/service/PrinterService.kt:139), [wake lock:427](D:/Dev/tmulator/app/src/main/java/dev/tmulator/service/PrinterService.kt:427), [discovery identity:264](D:/Dev/tmulator/app/src/main/java/dev/tmulator/service/PrinterService.kt:264).

The default-enabled indefinite wake lock remains needed according to settings even when listeners fail to bind or Bluetooth is unavailable. The five-second loop reinspects interfaces and republishes the notification even when nothing changed. Every UDP packet triggers another full identity inspection before the query header is validated, including possible ANDROID_ID lookup and hashing for a synthetic MAC.

**Action:** Tie lock ownership to operational listener policy, and release it on terminal failure/stop. Preserve the documented background-reliability option and measure before changing its default. Replace periodic inspection with connectivity callbacks and a cached identity; validate ENPC headers before accessing identity. Update the notification only when its displayed content changes. [Android documents callbacks as an alternative to expensive connectivity polling](https://developer.android.com/develop/connectivity/network-ops/reading-network-state).

**Acceptance:** With listeners unavailable, the app holds no unnecessary lock. Idle listening avoids repeated scans/identical notification writes. Compare battery and wake time on a real device with the option on and off.

### A16 Discovery identity assumes the default network is the LAN

Source: [NetworkAddresses.kt:17](D:/Dev/tmulator/app/src/main/java/dev/tmulator/util/NetworkAddresses.kt:17), [fallback identity:68](D:/Dev/tmulator/app/src/main/java/dev/tmulator/util/NetworkAddresses.kt:68), [PrinterService.kt:220](D:/Dev/tmulator/app/src/main/java/dev/tmulator/service/PrinterService.kt:220).

Identity and displayed IP prefer activeNetwork, which is the app's default route and can be cellular or a VPN while the POS uses local Wi-Fi. A Wi-Fi network without internet is a relevant case. The fallback hardcodes a /24 mask instead of reading the selected interface's actual prefix. Multiple TCP clients are allowed, but holderIp records only the latest connection and is not recomputed when that client leaves while others remain.

**Action:** Select the reachable Wi-Fi/Ethernet interface for LAN advertisement, using query source/interface information where available; retain actual prefix/gateway information and offer explicit interface selection if ambiguity remains. Derive holder reporting from the current client set and a documented policy.

**Acceptance:** Test Wi-Fi without internet plus cellular, VPN enabled, Ethernet, a non-/24 LAN, hotspot use, and two clients connecting/disconnecting in different orders. Discovery must return an address reachable by its requester.

### A17 Logging allocates and invalidates more UI state than necessary

Source: [PrinterRuntime.kt:195](D:/Dev/tmulator/app/src/main/java/dev/tmulator/data/PrinterRuntime.kt:195), [TMulatorAppScreen.kt:59](D:/Dev/tmulator/app/src/main/java/dev/tmulator/ui/TMulatorAppScreen.kt:59), [JobManager.kt:109](D:/Dev/tmulator/app/src/main/java/dev/tmulator/service/JobManager.kt:109).

Each log entry prepends to and truncates a 500-element list, then publishes a new whole RuntimeState. The root collects that entire state and passes it through screens, so protocol logging invalidates broader UI scopes even when Debug is hidden. Hex logging formats each byte with String.format on receive/reply paths. The first eight RX chunks are retained even when verbose logging is disabled. The added ENPC holder-log path logs every matching function query, bypassing the ordinary 64-query diagnostic limit and running before full header validation. JVM observations and source inspection also expose repeated copying of an unfinished text line as it grows.

**Action:** Separate logs, preview, and connection status into focused flows; collect them at their consumers. Keep a bounded ring buffer and publish log batches rather than copying the list for every command. Use a small hex encoder, gate/rate-limit expensive diagnostics after packet validation, and avoid constructing full pending strings for every tiny packet. Keep receipt data logging opt-in if it is unnecessary for normal operation.

**Acceptance:** On a device, compare allocations, GC pauses, root recomposition counts, and p95 print-to-preview latency during verbose and ordinary traffic. Retain immediate status responses.

### A18 Lint failure needs targeted resolution

Full output: [lint-results-debug.txt](D:/Dev/tmulator/tools/audit/lint-results-debug.txt).

| Category | Count | Interpretation and action |
| --- | --- | --- |
| MissingPermission in BluetoothSppServer | 6 errors | A helper checks permission and most calls are already inside exception handling, which lint does not fully recognize. Make the checked boundary explicit with appropriate annotations and narrowly documented suppression where needed. Also guard adapter state reads outside the try block against revocation. These are not six independently demonstrated crashes. |
| NewApi in day/night theme XML | 2 errors | windowLightNavigationBar requires API 27 while minSdk is 26. Move the attribute into version-qualified resources. |
| ProduceStateDoesNotAssignValue | 1 error | The producer visibly assigns value at JobsScreen.kt:381. This appears to be an analyzer false positive, not evidence of a missing thumbnail update. Resolve through a compatible tooling fix or narrow documented suppression. |
| Dependency update advisories | 6 warnings | Plan upgrades together with compatibility testing; these warnings do not establish a bug or vulnerability. |
| Wake lock checks | 2 warnings | No timeout and release-path analysis. Reconcile with the intentional long-running listener design and A15; cleanup does contain release logic. |
| HardwareIds | 1 warning | Consider a persisted random local identifier instead of deriving a network-visible MAC from ANDROID_ID. Preserve stable identity across normal restarts. |
| DataExtractionRules | 1 warning | Explicitly define modern backup/device-transfer behavior to match the local-data promise. |
| ModifierParameter | 1 warning | Routine composable API cleanup. |

**Acceptance:** Standard lintDebug completes with no unexplained errors. Do not hide all findings with a blanket baseline or disable permission checks globally.

### A19 Release and build maintenance

Source: [app/build.gradle.kts:29](D:/Dev/tmulator/app/build.gradle.kts:29), [app/proguard-rules.pro](D:/Dev/tmulator/app/proguard-rules.pro), [README build instructions](D:/Dev/tmulator/README.md).

The release variant uses the local debug signing configuration. That is adequate for a personal development APK, but does not provide a controlled signing identity for broader distribution or consistent updates from other machines. The default shell uses JDK 25, while validation needed the separate JDK 21 installation. No automated CI verification workflow is present. The broad keep rule preserves all of ZXing; the release APK is already about 1.64 MB, so changing that rule is low priority.

**Action:** If distributing beyond personal testing, configure a dedicated protected signing key outside source control. Document/pin a supported JDK and add a repeatable build command or CI job for tests, lint, and release compilation. Review dependency/tooling upgrades in compatible groups. Tune shrinking only if measurements justify it and retain barcode/QR coverage.

**Acceptance:** A fresh documented environment builds and checks the app predictably; published updates use the same intended signing identity. Developer-only builds remain simple.

### A20 UI and diagnostic consistency

Source: [SettingsScreen.kt:547](D:/Dev/tmulator/app/src/main/java/dev/tmulator/ui/SettingsScreen.kt:547), [profile explanation:446](D:/Dev/tmulator/app/src/main/java/dev/tmulator/ui/SettingsScreen.kt:446), [Theme.kt:65](D:/Dev/tmulator/app/src/main/java/dev/tmulator/ui/theme/Theme.kt:65), [EscPosParser.kt:326](D:/Dev/tmulator/app/src/main/java/dev/tmulator/escpos/EscPosParser.kt:326).

About displays 0.1.0 while Gradle and the APK are 0.2.0. Compose always uses the dark palette, while the platform day theme and README still imply light-mode behavior. The profile header changes immediately but existing parsers retain their original profile, including ID replies, despite wording that suggests selected-profile replies are immediate. The captured text feature shows only the stored 500-character excerpt. Custom tabs/relative positioning claims exceed current implementation.

Command counts increase before some commands are complete. Feeding ESC E 1 whole counts one command, while byte-by-byte input counts two. Unsupported counts also depend on the per-feed log cap because the counter increments inside log after that cap check.

**Action:** Read version text from BuildConfig; align the platform theme, README, and actual theme policy. State whether profile/logging changes apply to connections or receipts after fixing A01. Label excerpts as excerpts. Count commands only when consumed and count unsupported commands independently of logging. Check compact landscape layouts, large text, scroll behavior, and accessible labels for custom switches/actions on a device.

**Acceptance:** UI/version/documentation agree, diagnostics are independent of network fragmentation and logging limits, and the compact/large-text layouts remain usable.

## Performance work in order of value

| Order | Work | Why it matters | How to measure |
| --- | --- | --- | --- |
| 1 | Bound raster expansion and retained content | Prevents memory exhaustion and GC pressure | Peak heap/native bitmap allocation under wide images and concurrent jobs |
| 2 | One pending preview per receipt and skip unchanged revisions | Removes duplicate full-document work and stale publication | Render count per revision, queue depth, p50/p95 preview latency |
| 3 | Avoid per-feed full snapshots and pending-string copies | Keeps parsing cost from growing with all prior content | Bytes/sec and allocation rate for long lines and small chunks |
| 4 | Cache unchanged layout, image conversion, and QR matrices | Reduces repeated work while a receipt grows | CPU profiles and allocations per incremental update |
| 5 | Split/batch logging state | Reduces list copying and unrelated UI invalidation | Recomposition counts, GC pauses, frame times while logging |
| 6 | Cache interface identity and use callbacks | Reduces idle work and UDP query cost | Network inspections/sec, wake time, battery during idle listening |
| 7 | Cache/crop job thumbnails and reuse current rendered output for Share where safe | Avoids repeat decoding and rendering on navigation/sharing | Decode/render counts, navigation memory, share preparation latency |

The bounded JVM observation used 1 KiB chunks and measured about 3.9 ms for a 64 KiB unfinished line, 11.1 ms for 128 KiB, and 28.8 ms for 256 KiB in the final probe run. These are single desktop observations with JIT/GC variability, not Android benchmark claims. The relevant code repeatedly copies an ever-growing pending string; the memory-retention finding is independent of these timings.

The README's historical iPos initial delay occurs before receipt transmission. Optimizing rendering cannot by itself remove that upstream wait. Preserve the working job-ID echo correction and handshake regression fixtures; use a physical-printer packet capture or POS/SDK trace to resolve the remaining initial delay.

## Implementation sequence and release gates

1. **Receipt and transport integrity:** A01, A05, A06, A12. Separate jobs from connections, assign unique transport identities, serialize lifecycle changes, and make Clear/Reset semantics explicit. Gate on persistent connections, arbitrary packet boundaries, delayed callbacks, rapid restart, and reset during partial input.
2. **Memory and archive integrity:** A02, A03, A07, A13. Introduce resource budgets and bounded rasterization, preserve full accepted output, and make persistence transactional with visible failures. Gate on constrained-memory devices, long receipts, concurrent clients, disk failure, and restart recovery.
3. **Parser and compatibility:** A04, A09, A10, A11. Fix framed skipping, status families, bit images, positioning, aliases, and Code128 controls. Gate on authoritative fixtures plus scanner/image checks. Convert the audit probes into normal regression tests as fixes land.
4. **Performance and operational reliability:** A08, A14, A15, A16, A17. Add render backpressure/caching, state-specific flows, callback-driven connectivity, and owned recovery/shutdown. Gate on measured latency/allocation improvements and adapter/network failure recovery.
5. **Build and product cleanup:** A18, A19, A20. Make lint clean, document the toolchain/signing policy, correct UI/docs, and add the minimum repeatable CI checks.

Device verification should include Android API 26 and a current supported release; a constrained-memory phone; portrait, landscape, tablet, and large-font layouts; Wi-Fi with and without internet; VPN/cellular coexistence; Bluetooth permission/adapter changes; and foreground/background/notification Stop flows. The current source tree has no instrumented UI or graphics tests.

## Reproducing the audit

The original source set stays unchanged. To run its normal tests:

```powershell
# Use a supported local JDK, such as JDK 21, and an installed Android SDK.
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-configuration-cache
```

To opt into the audit probes:

```powershell
.\gradlew.bat :app:testDebugUnitTest -I tools/audit/audit.init.gradle --console=plain --no-configuration-cache
```

The audit command is expected to fail until the reproduced defects are fixed. The init script adds only tools/audit/src to that invocation's test sources and forces test execution. It does not modify production source or app build configuration. Normal test reports are under app/build; the evidence from this audit has been copied separately so later test runs do not overwrite it.

| Probe | Observed result |
| --- | --- |
| persistentConnectionStartsAnotherJobAfterCut | Second receipt has no active job |
| clearAllowsPersistentConnectionToPrintAgain | Clear does not rearm finalization |
| stoppedTcpListenerClosesItsBoundSocket | Socket remains bound after immediate stop |
| tcpConnectionsHaveUniqueKeysAcrossServerInstances | Both first connections use net-1 |
| unsupportedEscExtensionPreservesFollowingText | OK is not printed |
| resetDiscardsIncompleteCommandBytes | Hello becomes ello |
| gsRPresentsPaperSensorBitsRatherThanRealtimeStatusBits | Expected 0x00, received 0x16 |
| columnImageUsesMostSignificantBitForTopDot | Top dot is not set for 0x80 |
| escStarDoubleDensityDoesNotDoublePhysicalWidth | Expected width 1, received 2 |
| escKDoesNotConsumeFollowingText | OK becomes K |
| asciiJustificationParameterIsAccepted | Expected CENTER, received LEFT |
| absolutePositionDoesNotRewindAfterStyleChange | Second styled run repeats absolute position 100 |
| commandCountDoesNotDependOnPacketFragmentation | Same command counts as 1 or 2 |
| parserTimingAndMemoryObservations | Confirms 1 MiB retained text and acceptance of the 4 MiB packed raster; prints timing observations |

Evidence: [audit test source](D:/Dev/tmulator/tools/audit/src/AuditRegressionTest.kt), [audit results XML](D:/Dev/tmulator/tools/audit/results/TEST-dev.tmulator.audit.AuditRegressionTest.xml), [initial baseline results](D:/Dev/tmulator/tools/audit/baseline-results), [final standard results](D:/Dev/tmulator/tools/audit/final-results), and [lint output](D:/Dev/tmulator/tools/audit/lint-results-debug.txt).

