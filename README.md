# DisplayLink Android Application & Host Streamer

A complete, high-performance display mirroring solution comprising:
1. **Android DisplayLink Client**: Modern, minimalist, dark-themed Android mobile application built with **Kotlin**, **Jetpack Compose (Material 3)**, and the **Android Open Accessory (AOA) Protocol**.
2. **Host Screen Streamer (`host_streamer.py`)**: Low-latency desktop screen capture and H.264 encoder with integrated **AOA USB Handshake** (`pyusb`) optimized for lower-end hardware (Intel Core i3-3210 & NVIDIA GT 610).

---

## 📱 Downloading & Installing the Android APK

A pre-built, ready-to-run APK is built and published automatically on every push via GitHub Actions CI/CD:

1. **Direct Download**:
   - Download the APK directly to your phone: **[Download DisplayLink.apk (v1.0.0)](https://github.com/chiranthan123/displaylink-app/releases/download/v1.0.0/DisplayLink.apk)**
   - Alternatively, browse the latest release page on GitHub: [DisplayLink Releases](https://github.com/chiranthan123/displaylink-app/releases/tag/v1.0.0)
2. **Install on Phone**:
   - Tap the downloaded `DisplayLink.apk` file on your device.
   - If prompted, allow your mobile browser or file manager to "Install unknown apps" from device settings.
   - Complete installation and launch **DisplayLink**.
3. **Connect & Stream**:
   - Plug your phone into your PC with a USB cable.
   - Run the desktop streamer:
     ```bash
     python host_streamer.py --aoa
     ```
   - When the USB Accessory popup appears on your phone screen, tap **OK** or check **Always allow** to establish the mirror stream!

---

## 📱 Downloading & Installing the Android APK

A pre-built, ready-to-run APK is compiled and published automatically via GitHub Actions CI/CD:

1. **Direct Download**:
   - Download the APK directly onto your Android phone: **[Download DisplayLink.apk (v1.0.0)](https://github.com/chiranthan123/displaylink-app/releases/download/v1.0.0/DisplayLink.apk)**
   - Or browse releases on GitHub: [DisplayLink Releases (v1.0.0)](https://github.com/chiranthan123/displaylink-app/releases/tag/v1.0.0)
2. **Install on Phone**:
   - Tap the downloaded `DisplayLink.apk` file on your phone.
   - If prompted, allow your mobile browser or file manager to "Install unknown apps" in device settings.
   - Launch **DisplayLink**.
3. **Connect & Stream**:
   - Plug your phone into your PC with a USB cable.
   - Run the host streamer on your PC:
     ```bash
     python host_streamer.py --aoa
     ```
   - When the USB Accessory popup appears on your phone screen, tap **OK** or check **Always allow** to start mirroring in real time!

---

## 🖥️ Host Screen Streamer (`host_streamer.py`)

A dedicated Python streamer that captures the primary desktop display at 30 FPS, performs the AOA protocol handshake over USB, and streams raw Annex-B H.264 NAL units in real-time.

### AOA Protocol Handshake (`setup_aoa`)
- **Device Discovery**: Locates the connected Android phone/tablet by VID/PID or auto-detects OEM vendor IDs (Google, Samsung, Xiaomi, OnePlus, etc.). Detects if already in accessory mode (`0x18D1`).
- **Protocol Version Query (Request 51)**: Dispatches control transfer `0xC0, 51` to verify AOA version support.
- **Host Identification (Request 52)**: Dispatches control transfers `0x40, 52` sending zero-terminated UTF-8 strings matching [`res/xml/accessory_filter.xml`](file:///c:/Users/Admin/Desktop/tempfile/app/src/main/res/xml/accessory_filter.xml):
  - Index 0 (Manufacturer): `"DisplayLink"`
  - Index 1 (Model Name): `"DisplayLink Dock"`
  - Index 2 (Description): `"DisplayLink Screen Mirroring Dock"`
  - Index 3 (Version): `"1.0"`
  - Index 4 (URI): `"https://www.displaylink.com"`
  - Index 5 (Serial): `"DL-1080P-001"`
- **Start Accessory Mode (Request 53)**: Dispatches control transfer `0x40, 53` commanding the Android device to reboot its USB interface into Google Accessory Mode (`0x18D1:0x2D0x`).
- **Re-enumeration & Endpoint Binding**: Polls the USB bus for the new AOA device, sets the configuration, and binds the bulk OUT (video data) and bulk IN endpoints.

### Robust Multi-Threaded Streaming Architecture
- **Dedicated ScreenCaptureWorker**: Uses `mss.MSS()` to capture the primary display directly into raw BGRA memory (`img.raw`) at locked FPS, piping frames directly into FFmpeg stdin without CPU-heavy conversions.
- **FFmpeg Low-Overhead x264 Tuning**:
  - `preset='ultrafast'`: Eliminates complex motion search algorithms, reducing CPU load to a minimum.
  - `tune='zerolatency'`: Disables B-frames and encoder lookahead (`rc-lookahead=0`) for immediate NAL emission.
  - `threads=2`: Restricts encoding worker threads to leave headroom for OS and USB transfer on a dual-core 4-thread CPU.
  - `g=30`: 1-second keyframe (GOP) interval to ensure rapid synchronization on the Android client.
  - `crf=26`: Balanced visual quality and low bitrate for USB transmission.
- **Dedicated H264StdoutReaderThread**: Reads raw H.264 byte chunks from FFmpeg stdout pipe and enqueues them onto a bounded, zero-latency buffer (`chunk_queue`), dropping stale chunks if full to prevent display lag.
- **Robust Main Transmission Loop**: Continuously pulls byte chunks and writes them directly to the Android device's OUT bulk endpoint via `ep_out.write()` using `pyusb`.
- **Dynamic Adaptation to Android Telemetry**:
  - Background listener thread on the AOA Bulk IN endpoint (`ep_in`) continuously monitors JSON telemetry feedback from the Android device.
  - When a `{"command": "reduce_bitrate"}` control message is received (indicating decoder latency or buffer bloat), gracefully restarts the `ffmpeg-python` subprocess with adapted parameters:
    - **Stage 1**: Appends `-vf scale=-1:720` (drops encoding pixel count by >55%) and sets `-b:v 1800k` (CRF 28).
    - **Stage 2**: Keeps 720p and lowers bitrate to `-b:v 1000k` (CRF 32).
    - **Stage 3**: Further downscales to `-vf scale=-1:540` and `-b:v 600k` (CRF 36).
  - Keeps older Intel Core i3-3210 and GT 610 hardware from bottlenecking, maintaining low-latency interactive display playback.
- **Graceful Cable Detachment Teardown**: Wraps USB transmission in a `try...except usb.core.USBError` block. If the cable is detached or connection drops:
  - Catches `usb.core.USBError` without crashing.
  - Cleanly releases the USB interface and disposes device resources via `usb.util.dispose_resources(dev)`.
  - Cleanly closes FFmpeg stdin, terminates the subprocess, and joins worker threads.
  - Outputs a complete session summary (frames captured, avg FPS, total bytes encoded/transmitted).

### Running the Host Streamer

```bash
# 1. Scan and list all connected USB devices
python host_streamer.py --list-usb

# 2. Connect to Android device via AOA and stream desktop video over USB
python host_streamer.py --aoa

# 3. Connect to specific Android device (specifying VID / PID)
python host_streamer.py --aoa --vid 0x04e8 --pid 0x6860

# 4. Standalone capture with raw H.264 output piped to stdout
python host_streamer.py --stdout > stream.h264
```

---

## 📱 Downloading & Installing the Android APK

A pre-built, ready-to-run APK is built and published automatically on every push via GitHub Actions CI/CD:

1. **Direct Download**:
   - Download the APK directly to your phone: **[Download DisplayLink.apk (v1.0.0)](https://github.com/chiranthan123/displaylink-app/releases/download/v1.0.0/DisplayLink.apk)**
   - Alternatively, browse the latest release page on GitHub: [DisplayLink Releases](https://github.com/chiranthan123/displaylink-app/releases/tag/v1.0.0)
2. **Install on Phone**:
   - Tap the downloaded `DisplayLink.apk` file on your device.
   - If prompted, allow your mobile browser or file manager to "Install unknown apps" from device settings.
   - Complete installation and launch **DisplayLink**.
3. **Connect & Stream**:
   - Plug your phone into your PC with a USB cable.
   - Run the desktop streamer:
     ```bash
     python host_streamer.py --aoa
     ```
   - When the USB Accessory popup appears on your phone screen, tap **OK** or check **Always allow** to establish the mirror stream!

---

## 📱 Android Client Features

- **Bi-Directional Stream Health & Telemetry Feedback ([`H264Decoder.kt`](file:///c:/Users/Admin/Desktop/tempfile/app/src/main/java/com/displaylink/app/video/H264Decoder.kt) & [`AoaManager.kt`](file:///c:/Users/Admin/Desktop/tempfile/app/src/main/java/com/displaylink/app/aoa/AoaManager.kt))**:
  - Continuous decoding latency tracking via presentation timestamp (`ptsUs`) round-trips and chunk queue size monitoring (`pendingChunks`).
  - Active **Buffer Bloat Detection**: detects when frame processing exceeds latency thresholds (>60ms), input buffers are unavailable, or NAL units are dropped.
  - Automatically generates lightweight JSON rate control messages: `{"command": "reduce_bitrate", "latency_ms": 78.5, "queue_size": 18, "dropped_nals": 3}`.
  - Dedicated writer coroutine on `Dispatchers.IO` in `AoaManager` transmits telemetry back to the host PC using the `FileOutputStream` associated with the accessory's `ParcelFileDescriptor`.
  - Host streamer (`host_streamer.py`) listens on the AOA Bulk IN endpoint (`ep_in`) to dynamically log and adjust encoding parameters.
- **Lifecycle & Screen-Awake Optimization ([`MainActivity.kt`](file:///c:/Users/Admin/Desktop/tempfile/app/src/main/java/com/displaylink/app/MainActivity.kt))**:
  - Dynamically manages `window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)` when `connectionStatus` becomes `CONNECTED` to keep the display active as an external desktop monitor.
  - Automatically calls `window.clearFlags(FLAG_KEEP_SCREEN_ON)` upon accessory detachment or undocking to let the screen sleep normally and preserve battery.
  - Lifecycle awareness (`onStop()` / `onStart()`): safely cancels the `Dispatchers.IO` reader coroutine, resets NAL parsing buffers, and completely frees hardware `MediaCodec` resources whenever the app moves to the background, resuming seamlessly upon foreground return.
- **AOA Video Pipeline ([`AoaVideoPipeline.kt`](file:///c:/Users/Admin/Desktop/tempfile/app/src/main/java/com/displaylink/app/video/AoaVideoPipeline.kt))**:
  - Dedicated reader coroutine running on `Dispatchers.IO` that continuously streams byte chunks from the AOA `FileInputStream`.
  - Ingests bytes into [`NalUnitParser.kt`](file:///c:/Users/Admin/Desktop/tempfile/app/src/main/java/com/displaylink/app/video/NalUnitParser.kt) to detect H.264 Annex-B start codes (`0x00 0x00 0x00 0x01` and `0x00 0x00 0x01`).
  - Upon extracting a complete NAL unit, dequeues an input buffer from `MediaCodec`, copies the NAL bytes, queues for decoding with monotonic PTS, and releases output buffers to render directly onto the `Surface`.
  - Automatically performs graceful teardown of the codec, coroutine, and parser upon USB detachment or `IOException`.
- **Low-Latency Hardware H.264 Video Decoder ([`H264Decoder.kt`](file:///c:/Users/Admin/Desktop/tempfile/app/src/main/java/com/displaylink/app/video/H264Decoder.kt))**:
  - Initialized with `MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)`.
  - Configured with standard desktop resolutions ([`DesktopResolution`](file:///c:/Users/Admin/Desktop/tempfile/app/src/main/java/com/displaylink/app/video/H264Decoder.kt)) including 1920x1080 (1080p FHD), 2560x1440 (2K QHD), and 3840x2160 (4K UHD).
  - Direct rendering to the screen by passing the native `Surface` from `DisplayCanvas` into `codec.configure()`.
- **Hardware Video Surface & Active Feed ([`DisplayCanvas.kt`](file:///c:/Users/Admin/Desktop/tempfile/app/src/main/java/com/displaylink/app/ui/components/DisplayCanvas.kt))**:
  - Wraps a native Android `SurfaceView` inside Compose's `AndroidView`.
  - Exposes `onSurfaceCreated(Surface)` callback to route the underlying hardware `Surface` directly to the `MainActivity` for `MediaCodec` binding.
  - Smooth 450ms crossfade transition from `LargeStatusIndicator` to `DisplayCanvas` upon reaching `CONNECTED` state.
  - Video stream HUD overlay with resolution tags ("1080p @ 60Hz") and live feed telemetry.
- **Android Open Accessory (AOA) Integration ([`AoaManager.kt`](file:///c:/Users/Admin/Desktop/tempfile/app/src/main/java/com/displaylink/app/aoa/AoaManager.kt))**:
  - Hardware detection via `UsbManagerAdapter`.
  - Permission negotiation and intent broadcasting (`ACTION_USB_ACCESSORY_ATTACHED`, `ACTION_USB_ACCESSORY_DETACHED`, `USB_PERMISSION`).
  - Automatic `ParcelFileDescriptor` management via `openAccessory()`.
  - Real-time reactive state exposed via Kotlin `StateFlow<ConnectionStatus>`.
  - Automatic UI transition between **"Awaiting USB Connection"** and **"Display Connected"** upon physical docking or undocking.
- **Mock Test Harness & Diagnostics ([`AoaMockTestHarness.kt`](file:///c:/Users/Admin/Desktop/tempfile/app/src/main/java/com/displaylink/app/aoa/AoaMockTestHarness.kt) & [`AoaDiagnosticUtility.kt`](file:///c:/Users/Admin/Desktop/tempfile/app/src/main/java/com/displaylink/app/aoa/AoaDiagnosticUtility.kt))**:
  - Simulates physical docking, permission grant/denial, abrupt cable detachment, and unexpected file descriptor closure without physical hardware.
  - Reactive health telemetry (`AoaHealthReport`) and live event log stream.
  - Interactive simulation triggers embedded directly in `SettingsSheet.kt` for developers and QA engineers.

---

## 📱 Downloading & Installing the Android APK

A pre-built, ready-to-run APK is built and published automatically on every push via GitHub Actions CI/CD:

1. **Direct Download**:
   - Download the APK directly to your phone: **[Download DisplayLink.apk (v1.0.0)](https://github.com/chiranthan123/displaylink-app/releases/download/v1.0.0/DisplayLink.apk)**
   - Alternatively, browse the latest release page on GitHub: [DisplayLink Releases](https://github.com/chiranthan123/displaylink-app/releases/tag/v1.0.0)
2. **Install on Phone**:
   - Tap the downloaded `DisplayLink.apk` file on your device.
   - If prompted, allow your mobile browser or file manager to "Install unknown apps" from device settings.
   - Complete installation and launch **DisplayLink**.
3. **Connect & Stream**:
   - Plug your phone into your PC with a USB cable.
   - Run the desktop streamer:
     ```bash
     python host_streamer.py --aoa
     ```
   - When the USB Accessory popup appears on your phone screen, tap **OK** or check **Always allow** to establish the mirror stream!

---

## 🛠️ Project Structure

```
DisplayLink/
├── host_streamer.py               # Python desktop capture, AOA handshake & x264 streamer
├── test_host_streamer.py          # Unit tests for host streamer, NAL parser & AOA handshake
├── app/
│   ├── build.gradle.kts           # App-level dependencies & Compose configuration
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml    # AOA feature & USB_ACCESSORY_ATTACHED intent filter
│       │   ├── java/com/displaylink/app/
│       │   │   ├── MainActivity.kt    # Connects AOA input stream to AoaVideoPipeline
│       │   │   ├── aoa/
│       │   │   │   ├── AoaManager.kt           # Lifecycle, StateFlow, intent processing
│       │   │   │   ├── UsbManagerAdapter.kt    # Decoupling interface for native & test USB
│       │   │   │   ├── AoaDiagnosticUtility.kt # Health checks & event logging
│       │   │   │   └── AoaMockTestHarness.kt   # Simulation engine for QA & unit tests
│       │   │   ├── video/
│       │   │   │   ├── AoaVideoPipeline.kt     # Coroutine reader & end-to-end pipeline
│       │   │   │   ├── NalUnitParser.kt        # Annex-B H.264 start code parser
│       │   │   │   ├── H264Decoder.kt          # Low-latency MediaCodec H.264 decoder
│       │   │   │   └── VideoDecoderWorker.kt   # MediaCodec H.264 decoding worker thread
│       │   │   ├── ui/
│       │   │   │   ├── components/
│       │   │   │   │   ├── DisplayCanvas.kt          # Native SurfaceView wrapper & Surface callback
│       │   │   │   │   ├── LargeStatusIndicator.kt   # Pulsing radar & USB beacon
│       │   │   │   │   ├── InstructionSection.kt     # Connection guide cards
│       │   │   │   │   ├── TopHeaderBar.kt           # Logo & Settings icon button
│       │   │   │   │   └── SettingsSheet.kt          # Settings & interactive AOA harness
│       │   │   │   ├── screens/
│       │   │   │   │   ├── HomeScreen.kt             # Bound to AOA StateFlow & crossfades to canvas
│       │   │   │   │   ├── HomePortraitContent.kt    # Vertical layout with 16:9 DisplayCanvas
│       │   │   │   │   └── HomeLandscapeContent.kt   # Two-column layout with widescreen canvas
│       │   │   │   └── theme/
│       │   │   │       ├── Color.kt                  # OLED Dark theme colors
│       │   │   │       ├── Theme.kt                  # Material 3 Theme setup
│       │   │   │       └── Type.kt                   # Typography system
│       │   └── res/
│       │       ├── xml/
│       │       │   └── accessory_filter.xml          # DisplayLink USB accessory filter
│       │       ├── drawable/                         # Vector drawables & app icons
│       │       ├── mipmap-anydpi-v26/                # Adaptive launcher icons
│       │       └── values/                           # strings.xml, colors.xml, themes.xml
│       └── test/
│           └── java/com/displaylink/app/
│               ├── aoa/AoaManagerTest.kt             # Intent filtering, permissions & fallback tests
│               └── video/
│                   ├── NalUnitParserTest.kt          # Annex-B start code & fragmentation tests
│                   ├── AoaVideoPipelineTest.kt       # Reader coroutine & teardown tests
│                   ├── H264DecoderTest.kt            # Desktop resolution & lifecycle tests
│                   └── VideoDecoderWorkerTest.kt     # Decoder state & synthetic stream tests
├── gradle/
│   ├── libs.versions.toml         # Gradle Version Catalog
│   └── wrapper/                   # Gradle wrapper configuration & JAR
├── build.gradle.kts               # Root build file
├── settings.gradle.kts            # Project settings & repositories
└── gradlew / gradlew.bat          # Gradle wrappers for Windows & POSIX
```

---

## 📱 Downloading & Installing the Android APK

A pre-built, ready-to-run APK is built and published automatically on every push via GitHub Actions CI/CD:

1. **Direct Download**:
   - Download the APK directly to your phone: **[Download DisplayLink.apk (v1.0.0)](https://github.com/chiranthan123/displaylink-app/releases/download/v1.0.0/DisplayLink.apk)**
   - Alternatively, browse the latest release page on GitHub: [DisplayLink Releases](https://github.com/chiranthan123/displaylink-app/releases/tag/v1.0.0)
2. **Install on Phone**:
   - Tap the downloaded `DisplayLink.apk` file on your device.
   - If prompted, allow your mobile browser or file manager to "Install unknown apps" from device settings.
   - Complete installation and launch **DisplayLink**.
3. **Connect & Stream**:
   - Plug your phone into your PC with a USB cable.
   - Run the desktop streamer:
     ```bash
     python host_streamer.py --aoa
     ```
   - When the USB Accessory popup appears on your phone screen, tap **OK** or check **Always allow** to establish the mirror stream!

---

## 🔌 Complete End-to-End Architecture

```
[Host PC (Core i3-3210 & GT 610)]
       │
       ▼ (mss screen grab @ 30 FPS)
   [Raw BGRA bytes]
       │
       ▼ (Direct pipe stdin)
 [FFmpeg (ffmpeg-python)]
  -preset ultrafast
  -tune zerolatency
  -f h264
       │
       ▼ (stdout pipe: Annex-B NAL stream)
  [host_streamer.py]
       │
       ▼ (setup_aoa: PyUSB control transfers 51, 52, 53 -> Bulk OUT Endpoint)
[Android DisplayLink Client]
       │
       ▼ (AoaManager -> AoaVideoPipeline on Dispatchers.IO)
  [NalUnitParser]
       │
       ▼ (Extracted complete NAL units)
   [H264Decoder]
  (MediaCodec video/avc)
       │
       ▼ (Direct GPU render)
  [DisplayCanvas]
(Native SurfaceView)
```
