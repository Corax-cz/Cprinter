**English** · [简体中文](README.md)

Cprinter turns a spare Android phone into a wireless IPP/AirPrint server for a USB printer.
No root, no CUPS, and no Linux running on the phone.
Windows, macOS, iOS and Android can all use it as a network printer.
(Current scope: 1、printers that support native rendering plus PCL/PostScript passthrough;
2、brlaser covers Brother mono laser engines and the models rebadged from them.)

Verified on real hardware: a Lenovo LJ2600D (Brother HL-2240D engine) over USB OTG becomes a
LAN IPP printer, and both printing from the phone itself and AirPrint produce paper.

Four layers: IPP server → PDF rasterization → native encoder (brlaser) → USB bulk transfer.

Three ways to print:

1. LAN IPP service
2. iPhone / iPad / Mac: AirPrint
3. Pick a file inside the app and print it

## 0. How to use

1. Connect the printer over OTG, install the APK, open the app and follow the on-screen setup.
2. Tap "Start IPP service" and note the address shown.
3. Add the printer in your computer's printer settings — either by discovery or by address.
   On iPhone, just pick the printer from the print sheet.

The "Quality 300/600" and "Simplex/Duplex" toggles at the bottom **only affect jobs started
by the phone itself** (system printing and the test page), and the choice is persisted.
For network jobs (IPP / AirPrint), resolution, copies and duplex **always come from the
client** and do not inherit the UI — see `docs/当前状态.md` §6.1 (Chinese).
Windows vendor drivers go through PCL passthrough, so quality is decided by the Windows driver.

**iPhone / iPad / Mac**: no configuration. With the phone and the device on the same Wi-Fi,
"Cprinter" shows up in the print sheet (AirPrint uses the `_universal` subtype + URF, see
`docs/AirPrint.md`). Copies, duplex and resolution are chosen and submitted by iOS; we
simply honour them. The engine only samples at 600 dpi, so a 300 dpi job is resampled to
the 600 dpi grid before encoding.

---

## 1. Repository layout

```
app/src/main/cpp/brlaser/     brlaser encoder core (upstream, GPL-2.0-or-later)
app/src/main/cpp/brcore.cpp   dithering + encoding (JNI-agnostic, shared with hostref)
app/src/main/cpp/bridge.cpp   JNI marshalling only
app/src/main/java/.../usb/    USB discovery, permissions, chunked transfer (L1)
app/src/main/java/.../print/  JNI declarations, PDF rendering, PCL passthrough, PWG/URF decoding, format sniffing
app/src/main/java/.../ipp/    IPP codec + HTTP server + job queue (Stage 3)
app/src/main/java/.../androidprint/  Android print framework plugin (print from the phone)
app/src/main/java/.../usb/UsbGate.kt  cross-component USB lock (shared by IPP and the plugin)
app/src/main/java/.../mdns/   hand-written mDNS responder (AirPrint `_universal` subtype) + TXT records
app/src/main/java/.../net/    outbound address selection (skips VPN / cellular, Wi-Fi subnets only)
app/src/main/java/.../PrintService.kt  foreground service (resident IPP server)
app/src/main/java/.../PrintOptions.kt  quality / duplex settings (shared by UI and IPP)
app/src/main/java/.../Stage0.kt  USB endpoint confirmation
app/src/main/java/.../Stage1.kt  byte-level self-check + physical output
tools/ipptest/                IPP protocol probe (Python, stdlib only) + PWG/URF sample generators + mDNS query
tools/adb.sh                  wireless adb wrapper (mDNS discovery + reconnect)
testdata/reference/           reference output produced by hostref
testdata/stage3/              IPP acceptance samples (PDF / PCL / PWG Raster)
testdata/stage4/              AirPrint acceptance samples (URF 8bpp / 24bpp / two-page duplex)
docs/                         feasibility study, risk log, per-stage acceptance records, current status
docs/开发日志.md               per-version "why" notes and troubleshooting handbook (Chinese)
backup/                       per-version source + APK snapshots (zip)
toolchain/                    local toolchain (JDK / Android SDK / NDK), not a project artifact
reference/brlaser-src/        upstream source snapshot (evidence)
```

## 2. Environment

Everything lives under `toolchain/` and nothing pollutes the system:

| Component | Path | Version |
|---|---|---|
| JDK | `toolchain/jdk` | Microsoft OpenJDK 17 |
| Android SDK | `toolchain/android-sdk` | platform 35 / build-tools 35.0.0 |
| platform-tools | `toolchain/android-sdk/platform-tools` | includes adb |
| NDK | `toolchain/android-sdk/ndk` | 27.0.12077973 |
| Gradle | downloaded by the wrapper | 8.13 |

Build:

```bash
./build.sh            # handles the path mapping, then calls gradlew
```

### Build path

The project sits in a directory whose name contains Chinese characters, which hits two
Windows toolchain bugs. Measured conclusions:

| Symptom | Cause | Fix |
|---|---|---|
| `Your project path contains non-ASCII characters` | AGP rejects non-ASCII paths outright | `android.overridePathCheck` is set, but that alone is not enough |
| CMake exits with `-1073740791` (0xC0000409), log stops at `CXX compiler identification` | CMake crashes on UTF-8↔GBK conversion in a Chinese path; reproduced on both 3.22.1 and 3.31.6 | Map the project to an ASCII path with `subst W:` |

So the standard procedure is: **build from `W:\`** (`./build.sh` creates the mapping).
The files always live in `D:\workbody\无线打印安卓客户端`; `W:` is only a drive alias that
disappears on reboot and is recreated by `build.sh`.

`sdk.dir` in `local.properties` therefore points at `W:\toolchain\android-sdk`.

Install:

```bash
./toolchain/android-sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

## 3. Acceptance records

| Item | Criterion | Status |
|---|---|---|
| Stage 0 | `Bulk OUT endpoint found: 0xXX` in logcat | **Passed** (real device: `0x01`, see `docs/Stage0-1验收记录.md`) |
| Stage 1-① | Grayscale entry and 1bpp entry produce identical bytes | **Passed** (identical sha256) |
| Stage 1-② | Decoding the stream back to a bitmap matches the source pixel for pixel | **Passed** (`brdecode` + `pbmstat`, 744×744 with zero margin error) |
| Stage 1-③ | Printer outputs paper, position and scale correct | **Passed** (user confirmed paper output, correct position) |
| Stage 1-④ | Duplex direction correct | **Passed**. The original code used `ESC&l2S` (short edge); on 2026-11-16 we confirmed HL-2240D silently ignores it, so it now sends `ESC&l1S` (long edge) |
| Stage 2 | PDF prints, peak memory bounded | **Passed**: both full-page 300 dpi and striped 600 dpi print; the user judged 600 dpi visibly sharper, **now the default**, switchable in the UI |
| Stage 3-① | IPP attribute query and job submission | **Passed**: Get-Printer-Attributes / Validate-Job / Print-Job / Get-Job-Attributes all work (`tools/ipptest`) |
| Stage 3-② | Real IPP print over the LAN | **Passed**: 600 dpi PDF → 226873 bytes → 180 ms of USB → `job-state=9` |
| Stage 3-③ | PCL passthrough (Windows vendor driver path) | **Passed**: 8045 bytes forwarded verbatim, 1 ms |
| Stage 3-④ | Foreground service + mDNS | **Passed**: `_ipp._tcp` registered, service stays resident |
| Stage 3-⑤ | Manual add on Windows and print a test page | **Passed**: queue created as `\\http://192.168.123.60:8631\WPrint-LJ2600D`, test page printed (PCL 161812 bytes, 76 ms of USB). `WPrint-LJ2600D` was the default name at the time; since v1.0 the default is `Cprinter` |
| Stage 3-⑥ | PWG Raster (driverless Windows path) | **Passed**: single page / two pages / duplex, decoder output matches the generator's CRC32 byte for byte (see §4) |
| Stage 3-⑦ | Survives screen lock and Doze | **Passed**: still accepts jobs and prints under `force-idle` deep Doze |
| Stage 4-① | mDNS carries the `_universal` subtype | **Passed**: subtype PTR query from a PC returns a complete PTR+SRV+TXT+A answer (515 bytes), compression pointers and cache-flush bits correct |
| Stage 4-② | URF decoding (8bpp / 24bpp / two-page duplex) | **Passed**: grayscale CRC32 of all three samples matches the generator (`FC1D398D` / `B76FDAA1`) |
| Stage 4-③ | iOS Create-Job → Send-Document → Close-Job | **Passed**: `job-state` 3→5→9, `job-impressions=2`, 95098 bytes over USB |
| Stage 4-④ | iPhone prints on real hardware | **Passed** (2026-11-16: photo from the gallery, 334066 B URF → 28812 B PCL → paper) |
| Stage 4-⑤ | Duplex selected on iOS | **Passed**: URF header `duplex=3` → long-edge duplex, both sides of the sheet used |
| Stage 4-⑥ | Copies selected on iOS | **Passed**: 3 copies × 2 pages × duplex = 3 duplex sheets (PCL `ESC&l3X`, byte count does not grow with copies) |

### Printing from the phone (print framework plugin)

| Item | Result |
|---|---|
| Print service registered with the system | **Passed**: `dumpsys print` lists `UsbPrintPlugin` under `installed_services` |
| Printer discovered | **Passed**: `printers` section shows `name=Lenovo LJ2600D status=1` |
| Visible in the print UI | **Passed**: the dropdown shows "Lenovo LJ2600D / USB direct · no driver needed · mono laser" |
| Capabilities honoured | **Passed**: the options panel shows colour=mono, orientation=portrait, duplex, A4 |
| Preview correct after selection | **Passed**: printer icon appears, preview renders normally |
| Tap print → paper | **Passed**: 88652-byte PDF → strip rendering → 225182 bytes → 150 ms of USB → `state=5 completed` |

Mechanical checks that also pass:

- `./build.sh` produces `app/build/outputs/apk/debug/app-debug.apk` (19.5 MB)
- The APK contains `lib/arm64-v8a/libwprint.so` (475 KB, stripped)
- The `.so` exports 9 JNI symbols: `nativeRender` / `nativeRender1bpp` / `nativePack1bpp` /
  `nativeTestPatternGray` / `nativeJobBegin` / `nativeJobAddPageGray` /
  `nativeJobEnd` / `nativeJobPageCount` / `nativeJobAbort`
- `aapt2 dump badging`: package `com.cawzen.directprint`, label `Cprinter`, native-code `arm64-v8a`

## 4. Risk log

| Risk | Mitigation | Status |
|---|---|---|
| brlaser not cleanly isolated | The encoder is compiled verbatim; only `next_line()` has a different data source; verified pixel-exact via `brdecode` | Verified |
| **`/dev/bus/usb` missing the execute bit** | The directory had been set to `0666`, so a non-root process cannot traverse it and the framework enumerates nothing. Traced to a local Termux script, `printserver/daemon.sh:140`, running `chmod -R 666 /dev/bus/usb`. Fixed with `chmod 0755`. **This is local pollution, not normal Android behaviour** — AOSP ships the directory as `0755`, and this app needs no chmod and no root | Located and fixed; the script itself still needs its `-R` removed |
| bulkTransfer > 16384 | `UsbPrinter.CHUNK_SIZE = 16384`, and progress advances by `sent`, not `len` | Handled, verified by paper output |
| bulkTransfer returns -1 | Back off and retry 3 times (500/1000/1500 ms), then throw `IOException` | Handled, never triggered |
| PdfRenderer transparent background | `eraseColor(WHITE)` added (`PdfPrint.renderPageToGray`) | Handled |
| **PdfRenderer only accepts ARGB_8888** | A full A4 page at 600 dpi needs 132.7 MB and `RGB_565` throws. Pages above 64 MB switch to strips of 256 rows, cut with `destClip` + `transform` | Handled, runs in practice |
| 600 dpi peak memory is high | Measured 122 MB of a 256 MB heap: grayscale 34.8 + JNI copy 34.8 + 1bpp 4.3. Row-streaming could cut it to ~10 MB | Known, not the bottleneck |
| **Duplex selected but two single-sided sheets come out** | Three causes: ① upstream sends `ESC&l2S` (short edge), the engine only flips on the long edge and silently falls back to simplex → replaced in `brcore.cpp`'s `finish()` with `ESC&l1S`; ② network jobs inherited the UI's simplex setting → `fromClient ?: false`; ③ iOS only signals duplex in the URF page header and never sends IPP `sides` → `RasterPage.duplexHint` | Fixed, confirmed one duplex sheet on hardware |
| **The app cannot diagnose USB permission problems** | `untrusted_app` can read neither `/dev/bus/usb` nor sysfs, so judging by `canExecute()==false` gives a 100% false positive. Diagnostics now only list likely causes and the repair command | Known limitation |
| mDNS multicast unreachable | Custom responder (5353 + MulticastLock), falls back to `NsdManager` and marks "AirPrint unavailable" in the UI | Implemented; the Windows Public-profile firewall drops unicast answers, so verify with multicast |
| Encrypted / complex PDFs | Not covered by PdfRenderer, listed as a known limitation | Known limitation |
| 600 dpi full-page OOM | Switched to strip rendering (256 rows per strip) | Handled, measured 122 MB / 256 MB |
| AirPrint entry is a **subtype**, not a TXT record | `NsdManager` has no subtype API, so a custom mDNS responder advertises `_universal._sub._ipp._tcp` | Fixed |
| iOS uses Create-Job / Send-Document | `JobStore` gained an intermediate "job created, document not yet arrived" state that is not queued | Fixed |
| URF's PackBits is **inverted** versus the standard, and `0x80` means "rest of line blank" | Three independent implementations cross-checked, then implemented per URF semantics and reconciled by CRC | Fixed |
| **A VPN steals the outbound address** | With Tailscale/Clash running, the VPN network also reports the WIFI transport type and gets picked as `tun0` + `100.64/10`. `NetInfo` skips VPN networks and prefers the site-local address | Fixed |
| **Port cannot be 631** | A non-root app cannot bind below 1024. We use 8631 and adjust the port in the Windows URL | Handled, users change it once |
| **Windows IPP Class Driver sends PWG Raster** | PWG Raster decoding implemented (uncompressed, sGray 8bpp / sRGB 24bpp / 1bpp), reconciled against the generator by CRC32 | Fixed |
| **Edited C++ but the APK still ships the old library** | `./build.sh` running only `assembleDebug` does not necessarily rebuild native code (`packageDebug` reports UP-TO-DATE). After touching a `.cpp` you must run `./build.sh :app:externalNativeBuildDebug`; verify by searching for a marker string inside the `.so`, not by mtime | Known, documented in `docs/当前状态.md` §8 |

## 5. Known limitations

- Only `arm64-v8a` is built. Other ABIs can be enabled in `app/build.gradle.kts` if needed.
- AirPrint is supported (URF + the `_universal` subtype) but there is **no AAAA record**: on a
  pure IPv6 LAN, iOS will not find the phone. There is also no name-conflict probing, so a
  second device advertising `Cprinter` on the same LAN will clash.
- **The IPP port is fixed at 8631**: binding below 1024 needs `CAP_NET_BIND_SERVICE`, which a
  non-root app cannot get. The server accepts any POST path, so on Windows it is enough to
  change the port and keep the old path.
- With PCL passthrough, the in-app quality and duplex settings do nothing — Windows renders.
- Job state is not persisted: job IDs restart from 1 when the process restarts.
- `hostref` is built as an Android arm64 executable and runs on the phone rather than on a
  Windows host: NDK clang on Windows needs the MSVC CRT to link a `.exe`. Its independence
  comes from bypassing JNI, which holds equally on the phone.

## 6. Reproducing the Stage 1 test case

1. Install the APK, connect the printer, tap "Stage 0" and note the endpoint address and status word.
2. Tap "byte self-check" and record the `sha256` and both entries' byte counts. Artifacts land in
   `/data/data/com.cawzen.directprint/files/stage1/`.
3. Run `tools/hostref/build_hostref.sh` to build the reference implementation, then
   `tools/hostref/run_on_device.sh` to generate reference output on the phone and pull it back
   into `testdata/reference/`.
4. Compare:

```bash
adb pull /data/data/com.cawzen.directprint/files/stage1/pattern_200x200_stream.bin /tmp/apk.bin
cmp -l /tmp/apk.bin testdata/reference/pattern_200x200_stream.bin | head
```

Identical sizes plus identical sha256 means it passes; if they differ, the first line of
`cmp -l` gives the offset of the first differing byte.

## 7. License

- `app/src/main/cpp/brlaser/`: brlaser, **GPL-2.0-or-later** (© 2013 Peter De Wachter).
- The rest of the project: **GPL-3.0-or-later**, including the hand-written IPP/HTTP server
  (`ipp/`, `mdns/`, `PrintService.kt`) — no google/virtual-printer or any Apache-2.0 component
  is used, so there is no multi-license interpretation cost.

**Therefore: once this APK is distributed, the complete source must be made available under GPL-3.0.**
