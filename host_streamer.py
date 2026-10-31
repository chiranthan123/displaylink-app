#!/usr/bin/env python3
"""
Host Screen Streamer for DisplayLink
-------------------------------------
Captures the primary desktop display at 30 FPS using `mss` and encodes it into
a raw H.264 Annex-B stream using `ffmpeg-python`.

Implements the Android Open Accessory (AOA) protocol handshake using `pyusb` to
initiate USB communication and stream video directly to the Android DisplayLink app.

Optimized for lower-end hardware (Intel Core i3-3210 dual-core CPU & NVIDIA GT 610):
- Directly pipes raw BGRA bytes from mss memory into FFmpeg without intermediate PIL/OpenCV conversions.
- Configures FFmpeg with 'ultrafast' preset, 'zerolatency' tune, and multi-threading limit.
- Low-latency rate control (GOP=30 for 1-second keyframe recovery).
- Real-time NAL unit extraction directly from FFmpeg stdout.
- Direct USB bulk transmission via AOA OUT endpoint.
"""

import sys
import os
import time
import queue
import json
import argparse
import signal
import threading
import logging
from typing import Generator, Tuple, Optional, List

import mss
import ffmpeg

# PyUSB and LibUSB imports
import usb.core
import usb.util

try:
    import libusb_package
    def find_usb_device(*args, **kwargs):
        return libusb_package.find(*args, **kwargs)
except ImportError:
    def find_usb_device(*args, **kwargs):
        return usb.core.find(*args, **kwargs)

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.StreamHandler(sys.stderr)]
)
logger = logging.getLogger("HostStreamer")

# Android Open Accessory (AOA) Protocol Constants
AOA_GOOGLE_VID = 0x18D1
AOA_ACCESSORY_PIDS = [0x2D00, 0x2D01, 0x2D04, 0x2D05]

AOA_REQ_GET_PROTOCOL = 51
AOA_REQ_SEND_STRING = 52
AOA_REQ_START_ACCESSORY = 53

# Identifying strings matching res/xml/accessory_filter.xml in the Android app
AOA_STRINGS = {
    0: "DisplayLink",                          # Manufacturer
    1: "DisplayLink Dock",                     # Model Name
    2: "DisplayLink Screen Mirroring Dock",    # Description
    3: "1.0",                                  # Version
    4: "https://www.displaylink.com",          # URI
    5: "DL-1080P-001"                          # Serial Number
}

# H.264 NAL Unit Types
NAL_TYPES = {
    1: "NON_IDR_SLICE",
    5: "IDR_KEYFRAME",
    6: "SEI",
    7: "SPS",
    8: "PPS",
    9: "AUD"
}


# ==========================================
# 1. ANDROID OPEN ACCESSORY (AOA) HANDSHAKE
# ==========================================

def setup_aoa(
    vendor_id: Optional[int] = None,
    product_id: Optional[int] = None,
    timeout_ms: int = 5000,
    max_wait_seconds: float = 6.0
) -> Tuple[usb.core.Device, usb.core.Endpoint, usb.core.Endpoint]:
    """
    Executes the Android Open Accessory (AOA) protocol handshake using pyusb.

    Steps:
    1. Finds the connected Android device by VID/PID, or checks if already in AOA mode (0x18D1).
    2. Sends USB control transfer (Request 51) to query AOA protocol support.
    3. Sends identifying control transfers (Request 52) matching accessory_filter.xml.
    4. Sends control transfer (Request 53) to switch the Android device into accessory mode.
    5. Re-enumerates the USB bus to locate and configure the new AOA accessory endpoints.

    :param vendor_id: Target Android vendor ID (e.g. 0x04e8, 0x18d1), or None to auto-detect.
    :param product_id: Target Android product ID, or None to match any.
    :param timeout_ms: USB control transfer timeout in milliseconds.
    :param max_wait_seconds: Maximum seconds to wait for re-enumeration.
    :return: Tuple of (device, bulk_out_endpoint, bulk_in_endpoint).
    """
    logger.info("Initializing Android Open Accessory (AOA) handshake...")

    # Step 0: Check if device is ALREADY in AOA accessory mode (0x18D1:0x2D0x)
    for aoa_pid in AOA_ACCESSORY_PIDS:
        aoa_dev = find_usb_device(idVendor=AOA_GOOGLE_VID, idProduct=aoa_pid)
        if aoa_dev is not None:
            logger.info(f"Android device already in AOA mode: VID=0x18D1, PID=0x{aoa_pid:04X}")
            return _claim_and_locate_endpoints(aoa_dev)

    # Step 1: Locate target device
    dev = None
    if vendor_id is not None and product_id is not None:
        dev = find_usb_device(idVendor=vendor_id, idProduct=product_id)
    elif vendor_id is not None:
        dev = find_usb_device(idVendor=vendor_id)
    else:
        # Scan USB devices for potential Android phones
        dev = _auto_detect_android_device()

    if dev is None:
        vid_str = f"0x{vendor_id:04X}" if vendor_id else "ANY"
        pid_str = f"0x{product_id:04X}" if product_id else "ANY"
        raise RuntimeError(
            f"Android device (VID={vid_str}, PID={pid_str}) not found on USB bus. "
            f"Please verify device is connected with USB Debugging/File Transfer enabled."
        )

    logger.info(
        f"Found target device: VID=0x{dev.idVendor:04X}, PID=0x{dev.idProduct:04X} "
        f"(Bus {dev.bus}, Address {dev.address})"
    )

    # Detach active kernel driver if running on Linux
    _detach_kernel_driver_if_needed(dev)

    # Step 2: Query AOA Protocol Version (Request 51)
    logger.info("Sending AOA Protocol Version query (Request 51)...")
    try:
        # bmRequestType: 0xC0 (Device-to-Host | Vendor | Device)
        protocol_bytes = dev.ctrl_transfer(
            bmRequestType=0xC0,
            bRequest=AOA_REQ_GET_PROTOCOL,
            wValue=0,
            wIndex=0,
            data_or_wLength=2,
            timeout=timeout_ms
        )
        protocol_version = protocol_bytes[0] | (protocol_bytes[1] << 8)
        logger.info(f"Android device returned AOA protocol version: {protocol_version}")

        if protocol_version < 1:
            raise RuntimeError(f"Android device reported unsupported AOA protocol version: {protocol_version}")

    except usb.core.USBError as e:
        logger.error(f"Failed to query AOA protocol (Request 51): {e}")
        raise RuntimeError("Connected USB device does not support Android Open Accessory (AOA) protocol.") from e

    # Step 3: Send Identifying Strings matching accessory_filter.xml (Request 52)
    logger.info("Sending identifying host strings (Request 52)...")
    for str_index, str_value in AOA_STRINGS.items():
        # Zero-terminated UTF-8 byte payload
        payload = (str_value + "\0").encode("utf-8")
        try:
            # bmRequestType: 0x40 (Host-to-Device | Vendor | Device)
            dev.ctrl_transfer(
                bmRequestType=0x40,
                bRequest=AOA_REQ_SEND_STRING,
                wValue=0,
                wIndex=str_index,
                data_or_wLength=payload,
                timeout=timeout_ms
            )
            logger.debug(f"AOA String [{str_index}] '{str_value}' sent successfully.")
        except usb.core.USBError as e:
            logger.error(f"Failed to send AOA string [{str_index}] '{str_value}': {e}")
            raise

    # Step 4: Switch Device to Accessory Mode (Request 53)
    logger.info("Sending start accessory command (Request 53)...")
    try:
        dev.ctrl_transfer(
            bmRequestType=0x40,
            bRequest=AOA_REQ_START_ACCESSORY,
            wValue=0,
            wIndex=0,
            data_or_wLength=None,
            timeout=timeout_ms
        )
    except usb.core.USBError as e:
        # Many Android devices immediately reset the USB PHY, which can trigger a broken pipe or I/O error
        logger.debug(f"Control transfer request 53 completed with expected disconnect notification: {e}")

    # Step 5: Re-enumerate the USB bus to locate new AOA accessory endpoints
    logger.info("Waiting for Android device to re-enumerate in accessory mode (VID=0x18D1)...")
    accessory_dev = None
    start_time = time.time()

    while (time.time() - start_time) < max_wait_seconds:
        time.sleep(0.4)
        for aoa_pid in AOA_ACCESSORY_PIDS:
            candidate = find_usb_device(idVendor=AOA_GOOGLE_VID, idProduct=aoa_pid)
            if candidate is not None:
                accessory_dev = candidate
                break
        if accessory_dev is not None:
            break

    if accessory_dev is None:
        raise TimeoutError(
            f"Timed out after {max_wait_seconds}s waiting for Android device to re-enumerate "
            f"in AOA mode (Google VID 0x18D1:0x2D0x)."
        )

    logger.info(
        f"Android device successfully re-enumerated in AOA mode: "
        f"VID=0x{accessory_dev.idVendor:04X}, PID=0x{accessory_dev.idProduct:04X}"
    )

    return _claim_and_locate_endpoints(accessory_dev)


def _claim_and_locate_endpoints(dev: usb.core.Device) -> Tuple[usb.core.Device, usb.core.Endpoint, usb.core.Endpoint]:
    """
    Configures the AOA device and locates the bulk IN and bulk OUT endpoints.
    """
    _detach_kernel_driver_if_needed(dev)

    try:
        dev.set_configuration()
    except Exception as e:
        logger.debug(f"set_configuration note: {e}")

    cfg = dev.get_active_configuration()
    intf = cfg[(0, 0)]

    # Locate bulk endpoints
    ep_out = usb.util.find_descriptor(
        intf,
        custom_match=lambda e: (
            usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_OUT and
            usb.util.endpoint_type(e.bmAttributes) == usb.util.ENDPOINT_TYPE_BULK
        )
    )

    ep_in = usb.util.find_descriptor(
        intf,
        custom_match=lambda e: (
            usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_IN and
            usb.util.endpoint_type(e.bmAttributes) == usb.util.ENDPOINT_TYPE_BULK
        )
    )

    if not ep_out or not ep_in:
        raise RuntimeError("Failed to locate bulk IN and OUT endpoints on the AOA accessory interface.")

    logger.info(
        f"AOA endpoints bound: Bulk OUT=0x{ep_out.bEndpointAddress:02X}, Bulk IN=0x{ep_in.bEndpointAddress:02X}"
    )
    return dev, ep_out, ep_in


def _detach_kernel_driver_if_needed(dev: usb.core.Device):
    """
    Detaches OS kernel drivers if claimed (primarily for Linux hosts).
    """
    try:
        if hasattr(dev, "is_kernel_driver_active") and dev.is_kernel_driver_active(0):
            dev.detach_kernel_driver(0)
            logger.debug("Detached active kernel driver on interface 0.")
    except Exception:
        pass


def _auto_detect_android_device() -> Optional[usb.core.Device]:
    """
    Scans the USB bus for common Android OEM vendor IDs.
    """
    # Common Android OEM Vendor IDs
    ANDROID_VENDORS = {
        0x18D1: "Google",
        0x04E8: "Samsung",
        0x2717: "Xiaomi",
        0x22D9: "Oppo",
        0x2A70: "OnePlus",
        0x12D1: "Huawei",
        0x0BB4: "HTC",
        0x05C6: "Qualcomm",
        0x2B4C: "Vivo",
        0x0E8D: "MediaTek",
        0x17EF: "Lenovo",
        0x0489: "Foxconn"
    }

    try:
        all_devs = list(find_usb_device(find_all=True))
    except Exception as e:
        logger.warning(f"Error enumerating USB devices: {e}")
        return None

    for d in all_devs:
        if d.idVendor in ANDROID_VENDORS:
            logger.info(
                f"Auto-detected Android device: {ANDROID_VENDORS[d.idVendor]} "
                f"(VID=0x{d.idVendor:04X}, PID=0x{d.idProduct:04X})"
            )
            return d

    return None


def list_connected_usb_devices() -> List[Tuple[int, int, str]]:
    """
    Lists all USB devices currently enumerated on the host.
    """
    results = []
    try:
        for d in find_usb_device(find_all=True):
            results.append((d.idVendor, d.idProduct, f"Bus {d.bus} Addr {d.address}"))
    except Exception as e:
        logger.error(f"Failed to scan USB devices: {e}")
    return results


# ==========================================
# 2. H.264 NAL UNIT READER
# ==========================================

class NalUnitReader:
    """
    Parses raw Annex-B H.264 byte chunks from an input stream into discrete NAL units.
    Scans for 4-byte (0x00 00 00 01) and 3-byte (0x00 00 01) start codes.
    """

    def __init__(self, stream, chunk_size: int = 16384):
        self.stream = stream
        self.chunk_size = chunk_size
        self.buffer = bytearray()

    def iter_nal_units(self) -> Generator[Tuple[int, bytes], None, None]:
        """
        Yields (nal_type, nal_bytes) for every fully framed NAL unit.
        """
        while True:
            chunk = self.stream.read(self.chunk_size)
            if not chunk:
                # Stream reached EOF
                if len(self.buffer) > 4:
                    start, length = self._find_start_code(self.buffer, 0)
                    if start == 0:
                        nal = bytes(self.buffer)
                        nal_type = self._parse_nal_type(nal, length)
                        yield nal_type, nal
                break

            self.buffer.extend(chunk)

            while True:
                first_match = self._find_start_code(self.buffer, 0)
                if not first_match:
                    retain = min(len(self.buffer), 3)
                    self.buffer = self.buffer[-retain:] if retain > 0 else bytearray()
                    break

                start1, len1 = first_match
                second_match = self._find_start_code(self.buffer, start1 + len1)
                if not second_match:
                    if start1 > 0:
                        self.buffer = self.buffer[start1:]
                    break

                start2, _ = second_match
                nal_bytes = bytes(self.buffer[start1:start2])
                nal_type = self._parse_nal_type(nal_bytes, len1)

                self.buffer = self.buffer[start2:]
                yield nal_type, nal_bytes

    @staticmethod
    def _find_start_code(data: bytearray, start_pos: int) -> Optional[Tuple[int, int]]:
        limit = len(data) - 3
        i = start_pos
        while i < limit:
            if data[i] == 0 and data[i + 1] == 0:
                if data[i + 2] == 1:
                    return i, 3
                elif i < len(data) - 3 and data[i + 2] == 0 and data[i + 3] == 1:
                    return i, 4
            i += 1
        return None

    @staticmethod
    def _parse_nal_type(nal_bytes: bytes, start_code_len: int) -> int:
        if len(nal_bytes) > start_code_len:
            return nal_bytes[start_code_len] & 0x1F
        return 0


# ==========================================
# 3. HOST CAPTURE & STREAMING PIPELINE
# ==========================================

class HostStreamer:
    """
    Captures desktop frames via `mss`, encodes them into raw H.264 Annex-B stream via `ffmpeg`,
    and transmits byte chunks directly to an Android device via PyUSB in a robust main loop.

    Key low-latency architecture:
    - ScreenCaptureThread: Captures display frames via mss and writes raw BGRA directly to FFmpeg stdin.
    - H264StdoutReaderThread: Dedicated thread reading raw H.264 byte chunks from FFmpeg stdout pipe.
    - Main Loop: Pulls chunks and continuously writes them to Android OUT bulk endpoint via pyusb.write().
    - Robust Teardown: Catches usb.core.USBError (e.g. cable detach), cleanly releasing USB interface
      and terminating FFmpeg process.
    """

    def __init__(
        self,
        fps: int = 30,
        crf: int = 26,
        threads: int = 2,
        ffmpeg_bin: str = "ffmpeg",
        pipe_to_stdout: bool = False,
        aoa_dev: Optional[usb.core.Device] = None,
        aoa_ep_out: Optional[usb.core.Endpoint] = None,
        aoa_ep_in: Optional[usb.core.Endpoint] = None,
        chunk_size: int = 16384,
        max_queue_size: int = 120,
        scale_filter: Optional[str] = None,
        video_bitrate: Optional[str] = None
    ):
        self.fps = fps
        self.crf = crf
        self.threads = threads
        self.ffmpeg_bin = ffmpeg_bin
        self.pipe_to_stdout = pipe_to_stdout
        self.aoa_dev = aoa_dev
        self.aoa_ep_out = aoa_ep_out
        self.aoa_ep_in = aoa_ep_in
        self.chunk_size = chunk_size
        self.max_queue_size = max_queue_size
        self.scale_filter = scale_filter
        self.video_bitrate = video_bitrate

        self.running = False
        self.process = None
        self.capture_thread = None
        self.reader_thread = None
        self.stderr_thread = None
        self.telemetry_thread = None
        self.telemetry_feedback_received = []
        self.chunk_queue = queue.Queue(maxsize=max_queue_size)

        # Thread synchronization and dynamic reconfiguration state
        self.process_lock = threading.RLock()
        self.restarting_ffmpeg = False
        self.adaptation_level = 0
        self.capture_width = 1920
        self.capture_height = 1080

        # Telemetry metrics
        self.frames_captured = 0
        self.bytes_encoded = 0
        self.bytes_transmitted = 0
        self.start_time = 0.0

    def start_ffmpeg(self, width: int, height: int):
        """
        Initializes FFmpeg subprocess configured for low-overhead, zero-latency H.264 encoding.
        Dynamically applies scale filter (e.g. -vf scale=-1:720) and video bitrate (-b:v).
        """
        self.capture_width = width
        self.capture_height = height

        output_kwargs = {
            "format": "h264",
            "vcodec": "libx264",
            "preset": "ultrafast",       # Minimum CPU overhead for Core i3-3210
            "tune": "zerolatency",       # Disable B-frames & lookahead for real-time display
            "pix_fmt": "yuv420p",        # Standard 4:2:0 profile compatible with Android MediaCodec
            "g": self.fps,               # 1 keyframe per second for instant docking sync
            "crf": self.crf,             # Constant Rate Factor
            "threads": self.threads      # Restrict worker threads to keep host responsive
        }

        if self.scale_filter:
            output_kwargs["vf"] = self.scale_filter
        if self.video_bitrate:
            output_kwargs["video_bitrate"] = self.video_bitrate

        filter_desc = f", vf='{self.scale_filter}'" if self.scale_filter else ""
        bitrate_desc = f", b:v='{self.video_bitrate}'" if self.video_bitrate else ""
        logger.info(
            f"Initializing FFmpeg pipeline: {width}x{height} @ {self.fps} FPS"
            f"{filter_desc}{bitrate_desc} (crf={self.crf})"
        )

        try:
            stream = (
                ffmpeg
                .input(
                    "pipe:",
                    format="rawvideo",
                    pix_fmt="bgra",
                    s=f"{width}x{height}",
                    framerate=self.fps
                )
                .output("pipe:", **output_kwargs)
            )

            self.process = stream.run_async(
                cmd=self.ffmpeg_bin,
                pipe_stdin=True,
                pipe_stdout=True,
                pipe_stderr=True
            )
            logger.info("FFmpeg subprocess running. Ready for raw frame ingestion.")
        except Exception as e:
            logger.error(f"Failed to spawn FFmpeg process: {e}")
            logger.error("Please verify that FFmpeg is installed and added to PATH or specify --ffmpeg-path.")
            raise

    def _drain_stderr(self):
        """
        Drains FFmpeg stderr in a background thread to prevent buffer clogging on Windows.
        """
        if not self.process or not self.process.stderr:
            return
        try:
            while self.running:
                line = self.process.stderr.readline()
                if not line:
                    break
        except Exception:
            pass

    def _capture_loop(self):
        """
        Dedicated background worker thread capturing desktop screen frames via `mss`
        and piping raw BGRA bytes directly to FFmpeg stdin at locked target FPS.
        """
        logger.info(f"Screen capture worker started ({self.fps} FPS target).")
        frame_interval = 1.0 / self.fps

        with mss.MSS() as sct:
            primary_monitor = sct.monitors[1]
            width = primary_monitor["width"]
            height = primary_monitor["height"]
            fallback_frame = bytes(width * height * 4)

            while self.running:
                t_frame_start = time.perf_counter()

                try:
                    sct_img = sct.grab(primary_monitor)
                    raw_bytes = sct_img.raw
                except Exception:
                    # Fallback for headless or locked desktop environments
                    raw_bytes = fallback_frame

                with self.process_lock:
                    proc = self.process
                    if proc and proc.stdin and not proc.stdin.closed:
                        try:
                            proc.stdin.write(raw_bytes)
                            self.frames_captured += 1
                        except (BrokenPipeError, OSError) as e:
                            logger.debug(f"FFmpeg stdin write note: {e}")
                    elif not self.restarting_ffmpeg:
                        break

                elapsed = time.perf_counter() - t_frame_start
                sleep_time = frame_interval - elapsed
                if sleep_time > 0:
                    time.sleep(sleep_time)

        logger.info("Screen capture worker finished.")

    def _stdout_reader_loop(self):
        """
        Dedicated background thread that continuously reads raw H.264 byte chunks
        from the FFmpeg stdout pipe and enqueues them for USB transmission.
        Automatically rebinds to new stdout if FFmpeg is dynamically restarted.
        """
        logger.info("Dedicated H.264 stdout reader thread started.")
        chunk_size = self.chunk_size

        try:
            while self.running:
                with self.process_lock:
                    proc = self.process
                    stdout_pipe = proc.stdout if proc else None

                if stdout_pipe is None:
                    time.sleep(0.05)
                    continue

                try:
                    chunk = stdout_pipe.read(chunk_size)
                except (ValueError, OSError):
                    chunk = None

                if not chunk:
                    if not self.running:
                        break
                    # Wait briefly and check if a process restart occurred
                    time.sleep(0.05)
                    with self.process_lock:
                        if self.process is not None and self.process.stdout != stdout_pipe:
                            logger.info("H.264 stdout reader seamlessly switched to restarted FFmpeg process.")
                            continue
                        elif self.restarting_ffmpeg:
                            continue
                        else:
                            # Stream genuinely ended and no restart pending
                            logger.info("FFmpeg stdout reached EOF.")
                            break
                    continue

                self.bytes_encoded += len(chunk)

                # Maintain zero-latency: if queue is full, drop oldest chunk to avoid buffering lag
                if self.chunk_queue.full():
                    try:
                        self.chunk_queue.get_nowait()
                    except queue.Empty:
                        pass

                self.chunk_queue.put(chunk)

        except Exception as e:
            if self.running:
                logger.warning(f"Exception in H.264 stdout reader thread: {e}")
        finally:
            # Signal EOF / stream end to consumer loop only if completely stopping
            if not self.restarting_ffmpeg:
                try:
                    self.chunk_queue.put_nowait(None)
                except Exception:
                    pass
            logger.info("Dedicated H.264 stdout reader thread finished.")

    def handle_reduce_bitrate(self, reason: str = "Client buffer bloat"):
        """
        Gracefully restarts the ffmpeg-python subprocess with a lower -b:v (video bitrate)
        or appends a -vf scale=-1:720 filter to drop the capture resolution.

        Ensures older Intel Core i3-3210 and GT 610 hardware does not get bottlenecked,
        maintaining low-latency playback at the expense of visual fidelity.
        """
        with self.process_lock:
            self.restarting_ffmpeg = True
            try:
                self.adaptation_level += 1
                logger.warning(
                    f"[STREAM RECONFIGURATION] Dynamically adapting stream parameters (Level {self.adaptation_level}): {reason}"
                )

                if self.adaptation_level == 1:
                    # Level 1: Append scale filter to drop resolution to 720p and set -b:v 1800k
                    self.scale_filter = "scale=-1:720"
                    self.video_bitrate = "1800k"
                    self.crf = max(self.crf, 28)
                    logger.info("Applied Stage 1: Added -vf scale=-1:720 and -b:v 1800k (cuts encoding pixels >50%)")
                elif self.adaptation_level == 2:
                    # Level 2: Keep 720p and lower bitrate further to 1000k
                    self.scale_filter = "scale=-1:720"
                    self.video_bitrate = "1000k"
                    self.crf = max(self.crf, 32)
                    logger.info("Applied Stage 2: Lowered -b:v to 1000k and CRF to 32")
                else:
                    # Level 3+: Aggressive downscaling to 540p and -b:v 600k
                    self.scale_filter = "scale=-1:540"
                    self.video_bitrate = "600k"
                    self.crf = max(self.crf, 36)
                    logger.info("Applied Stage 3: Aggressive downscale -vf scale=-1:540 and -b:v 600k")

                # Gracefully terminate previous FFmpeg subprocess
                logger.info("Terminating previous FFmpeg instance for graceful restart...")
                old_proc = self.process
                if old_proc:
                    try:
                        if old_proc.stdin and not old_proc.stdin.closed:
                            old_proc.stdin.close()
                    except Exception:
                        pass
                    try:
                        old_proc.terminate()
                        old_proc.wait(timeout=1.0)
                    except Exception:
                        try:
                            old_proc.kill()
                        except Exception:
                            pass

                # Restart FFmpeg subprocess with adapted parameters
                self.start_ffmpeg(self.capture_width, self.capture_height)
                logger.info("FFmpeg subprocess restarted successfully. New sequence parameters active.")

            except Exception as e:
                logger.error(f"Error during FFmpeg dynamic restart: {e}")
            finally:
                self.restarting_ffmpeg = False

    def start_pipeline(self, width: Optional[int] = None, height: Optional[int] = None):
        """
        Spawns FFmpeg, launches screen capture worker, and starts the dedicated H.264 reader thread.
        """
        self.running = True

        if width is None or height is None:
            with mss.MSS() as sct:
                primary = sct.monitors[1]
                width = primary["width"]
                height = primary["height"]

        logger.info(f"Display resolution configured: {width}x{height}")

        # 1. Spawn FFmpeg encoder
        self.start_ffmpeg(width, height)

        # 2. Spawn stderr drainer
        self.stderr_thread = threading.Thread(
            target=self._drain_stderr,
            name="FFmpegStderrDrainer",
            daemon=True
        )
        self.stderr_thread.start()

        # 3. Spawn dedicated screen capture worker
        self.capture_thread = threading.Thread(
            target=self._capture_loop,
            name="ScreenCaptureWorker",
            daemon=True
        )
        self.capture_thread.start()

        # 4. Spawn dedicated H.264 stdout reader thread
        self.reader_thread = threading.Thread(
            target=self._stdout_reader_loop,
            name="H264StdoutReaderThread",
            daemon=True
        )
        self.reader_thread.start()

        # 5. Spawn bi-directional telemetry listener if AOA IN endpoint is configured
        if self.aoa_ep_in is not None:
            self.telemetry_thread = threading.Thread(
                target=self._aoa_in_reader_loop,
                name="AoaInTelemetryReader",
                daemon=True
            )
            self.telemetry_thread.start()

    def _aoa_in_reader_loop(self):
        """
        Background listener thread using PyUSB to continuously read from the Android accessory's
        bulk IN endpoint.

        When a reduce_bitrate command is received, gracefully restarts the ffmpeg-python
        subprocess with a lower -b:v (video bitrate) or appends a -vf scale=-1:720 filter to
        drop capture resolution.
        """
        if self.aoa_ep_in is None:
            return
        logger.info("AOA Bulk IN telemetry listener thread started.")
        buffer = bytearray()
        last_adaptation_time = 0.0

        while self.running:
            try:
                # Continuously read from the Android accessory's bulk IN endpoint
                data = self.aoa_ep_in.read(512, timeout=1000)
                if data:
                    buffer.extend(data)
                    while b"\n" in buffer:
                        line, buffer = buffer.split(b"\n", 1)
                        line_str = line.decode("utf-8", errors="ignore").strip()
                        if line_str:
                            logger.info(f"[AOA Feedback from Android] Received: {line_str}")
                            try:
                                msg = json.loads(line_str)
                                self.telemetry_feedback_received.append(msg)
                                if msg.get("command") == "reduce_bitrate":
                                    now = time.time()
                                    if now - last_adaptation_time >= 2.0:
                                        last_adaptation_time = now
                                        lat = msg.get("latency_ms", "N/A")
                                        qs = msg.get("queue_size", "N/A")
                                        drop = msg.get("dropped_nals", "N/A")
                                        logger.warning(
                                            f"Android client reported buffer bloat (latency={lat}ms, "
                                            f"queue={qs}, dropped={drop}). Gracefully restarting FFmpeg..."
                                        )
                                        self.handle_reduce_bitrate(
                                            reason=f"Android buffer bloat (latency={lat}ms, queue={qs}, dropped={drop})"
                                        )
                            except Exception as ex:
                                logger.debug(f"JSON parse error on telemetry feedback: {ex}")
            except usb.core.USBTimeoutError:
                continue
            except usb.core.USBError as e:
                if self.running:
                    logger.debug(f"AOA IN read note: {e}")
                break
        logger.info("AOA Bulk IN telemetry listener thread finished.")

    def stream_to_aoa(
        self,
        dev: usb.core.Device,
        ep_out: usb.core.Endpoint,
        ep_in: Optional[usb.core.Endpoint] = None,
        duration: Optional[float] = None,
        usb_timeout_ms: int = 2000
    ):
        """
        Combines the capture/encoding pipeline and the AOA USB connection into a robust main loop.

        In the main loop, uses pyusb to continuously write() byte chunks directly to the Android
        device's OUT bulk endpoint. The USB transmission is wrapped in a try-except block
        to gracefully handle usb.core.USBError exceptions, ensuring the script cleanly releases
        the USB interface and FFmpeg process if the cable is detached.
        """
        if ep_in is not None:
            self.aoa_ep_in = ep_in
        logger.info("Starting combined capture/encoding pipeline with AOA USB streaming loop...")

        self.start_pipeline()
        self.start_time = time.perf_counter()

        last_stats_time = self.start_time
        bytes_in_window = 0

        try:
            while self.running:
                now = time.perf_counter()
                if duration and (now - self.start_time) >= duration:
                    logger.info(f"Specified run duration ({duration}s) reached. Stopping streaming loop.")
                    break

                # 1. Read next raw H.264 byte chunk from dedicated reader thread queue
                try:
                    chunk = self.chunk_queue.get(timeout=0.2)
                except queue.Empty:
                    # Check if FFmpeg process or capture died unexpectedly
                    if not self.running or (self.process and self.process.poll() is not None):
                        logger.warning("FFmpeg process or capture ended.")
                        break
                    continue

                if chunk is None:
                    logger.info("H.264 byte stream EOF sentinel received.")
                    break

                # 2. Write byte chunk directly to Android device's OUT bulk endpoint via pyusb
                try:
                    bytes_written = ep_out.write(chunk, timeout=usb_timeout_ms)
                    self.bytes_transmitted += bytes_written
                    bytes_in_window += bytes_written
                except usb.core.USBError as e:
                    # Cable detached, device disconnected, or bulk transfer error
                    logger.error(
                        f"USB transmission error on endpoint 0x{ep_out.bEndpointAddress:02X} "
                        f"(cable detached or device disconnected?): {e}"
                    )
                    break

                # 3. Telemetry reporting
                if now - last_stats_time >= 2.0:
                    elapsed = now - last_stats_time
                    mbps = (bytes_in_window * 8 / 1_000_000) / elapsed
                    logger.info(
                        f"AOA Streaming: {mbps:.2f} Mbps | "
                        f"Transmitted: {self.bytes_transmitted / 1024:.1f} KB | "
                        f"Frames Captured: {self.frames_captured}"
                    )
                    bytes_in_window = 0
                    last_stats_time = now

        except KeyboardInterrupt:
            logger.info("KeyboardInterrupt received in main loop. Exiting...")
        finally:
            logger.info("Executing graceful teardown of USB interface and FFmpeg process...")
            # 1. Cleanly release USB interface
            self.release_usb(dev)
            # 2. Cleanly terminate FFmpeg process and worker threads
            self.stop()

    def stream_to_stdout(self, duration: Optional[float] = None):
        """
        Streams raw H.264 chunks directly to sys.stdout.buffer for piping into external players (e.g. mpv, ffplay).
        """
        self.start_pipeline()
        self.start_time = time.perf_counter()

        try:
            while self.running:
                now = time.perf_counter()
                if duration and (now - self.start_time) >= duration:
                    break

                try:
                    chunk = self.chunk_queue.get(timeout=0.2)
                except queue.Empty:
                    if not self.running or (self.process and self.process.poll() is not None):
                        break
                    continue

                if chunk is None:
                    break

                sys.stdout.buffer.write(chunk)
                sys.stdout.buffer.flush()
                self.bytes_transmitted += len(chunk)

        except KeyboardInterrupt:
            pass
        finally:
            self.stop()

    def start(
        self,
        aoa_dev: Optional[usb.core.Device] = None,
        aoa_ep_out: Optional[usb.core.Endpoint] = None,
        duration: Optional[float] = None
    ):
        """
        Main execution entry point. Dispatches to AOA USB streaming or stdout piping.
        """
        dev = aoa_dev or self.aoa_dev
        ep_out = aoa_ep_out or self.aoa_ep_out

        if dev is not None and ep_out is not None:
            self.stream_to_aoa(dev=dev, ep_out=ep_out, duration=duration)
        elif self.pipe_to_stdout:
            self.stream_to_stdout(duration=duration)
        else:
            # Standalone benchmarking / testing mode
            self.start_pipeline()
            self.start_time = time.perf_counter()
            try:
                while self.running:
                    now = time.perf_counter()
                    if duration and (now - self.start_time) >= duration:
                        break
                    try:
                        chunk = self.chunk_queue.get(timeout=0.2)
                        if chunk is None:
                            break
                        self.bytes_transmitted += len(chunk)
                    except queue.Empty:
                        if not self.running or (self.process and self.process.poll() is not None):
                            break
            except KeyboardInterrupt:
                pass
            finally:
                self.stop()

    @staticmethod
    def release_usb(dev: Optional[usb.core.Device]):
        """
        Cleanly releases the USB interface and disposes device resources.
        """
        if dev is None:
            return
        logger.info("Releasing USB interface and disposing resources...")
        try:
            try:
                usb.util.release_interface(dev, 0)
            except Exception:
                pass
            usb.util.dispose_resources(dev)
            logger.info("USB device interface released cleanly.")
        except Exception as e:
            logger.debug(f"USB release completed with note: {e}")

    def stop(self):
        """
        Gracefully terminates the FFmpeg process, shuts down worker threads,
        and logs session statistics.
        """
        if not self.running:
            return

        self.running = False
        logger.info("Stopping capture pipeline and terminating FFmpeg process...")

        # 1. Close stdin to allow FFmpeg to finish encoding cleanly
        if self.process:
            try:
                if self.process.stdin and not self.process.stdin.closed:
                    self.process.stdin.close()
            except Exception:
                pass

            # 2. Terminate subprocess
            try:
                self.process.terminate()
                self.process.wait(timeout=2.0)
            except Exception:
                try:
                    self.process.kill()
                except Exception:
                    pass

        # 3. Join threads with timeout
        if self.capture_thread and self.capture_thread.is_alive():
            self.capture_thread.join(timeout=1.0)
        if self.reader_thread and self.reader_thread.is_alive():
            self.reader_thread.join(timeout=1.0)
        if self.stderr_thread and self.stderr_thread.is_alive():
            self.stderr_thread.join(timeout=1.0)
        if self.telemetry_thread and self.telemetry_thread.is_alive():
            self.telemetry_thread.join(timeout=1.0)

        total_time = max(0.001, time.perf_counter() - self.start_time)
        avg_fps = self.frames_captured / total_time
        logger.info("==========================================")
        logger.info("Host Streamer Session Summary")
        logger.info(f"Total Duration    : {total_time:.2f}s")
        logger.info(f"Frames Captured   : {self.frames_captured} ({avg_fps:.1f} avg FPS)")
        logger.info(f"Bytes Encoded     : {self.bytes_encoded / 1024:.2f} KB")
        logger.info(f"Bytes Transmitted : {self.bytes_transmitted / 1024:.2f} KB")
        logger.info("==========================================")


# ==========================================
# 4. CLI ENTRY POINT
# ==========================================

def parse_hex_int(value: str) -> int:
    """Parses a hex or decimal string into an int (e.g. '0x18d1' or '6353')."""
    return int(value, 16 if value.lower().startswith("0x") else 10)


def main():
    parser = argparse.ArgumentParser(
        description="Low-latency desktop screen capture and H.264 AOA streamer for DisplayLink"
    )
    parser.add_argument(
        "--aoa",
        action="store_true",
        help="Initiate Android Open Accessory (AOA) handshake and stream over USB"
    )
    parser.add_argument(
        "--vid",
        type=parse_hex_int,
        default=None,
        help="Target Android USB Vendor ID in hex (e.g. 0x18d1, 0x04e8)"
    )
    parser.add_argument(
        "--pid",
        type=parse_hex_int,
        default=None,
        help="Target Android USB Product ID in hex (e.g. 0x6860)"
    )
    parser.add_argument(
        "--list-usb",
        action="store_true",
        help="List all connected USB devices and exit"
    )
    parser.add_argument(
        "--fps",
        type=int,
        default=30,
        help="Target capture framerate (default: 30 FPS)"
    )
    parser.add_argument(
        "--crf",
        type=int,
        default=26,
        help="x264 Constant Rate Factor (default: 26, optimal for i3-3210)"
    )
    parser.add_argument(
        "--threads",
        type=int,
        default=2,
        help="FFmpeg worker threads limit (default: 2)"
    )
    parser.add_argument(
        "--chunk-size",
        type=int,
        default=16384,
        help="USB bulk chunk size in bytes (default: 16384)"
    )
    parser.add_argument(
        "--ffmpeg-path",
        type=str,
        default="ffmpeg",
        help="Path to ffmpeg executable (default: 'ffmpeg')"
    )
    parser.add_argument(
        "--duration",
        type=float,
        default=None,
        help="Optional run duration in seconds (for benchmarking)"
    )
    parser.add_argument(
        "--stdout",
        action="store_true",
        help="Output raw H.264 NAL bytes directly to stdout for piping"
    )
    parser.add_argument(
        "--scale",
        type=str,
        default=None,
        help="Initial video scale filter (e.g. 'scale=-1:720')"
    )
    parser.add_argument(
        "--bitrate",
        type=str,
        default=None,
        help="Initial video bitrate (e.g. '2000k', '1500k')"
    )

    args = parser.parse_args()

    # List USB devices mode
    if args.list_usb:
        logger.info("Scanning connected USB devices on host...")
        devices = list_connected_usb_devices()
        print("\n--- Connected USB Devices ---")
        for vid, pid, location in devices:
            print(f"  VID: 0x{vid:04X} | PID: 0x{pid:04X} | {location}")
        print("----------------------------\n")
        return

    aoa_dev = None
    aoa_ep_out = None
    aoa_ep_in = None

    if args.aoa or (args.vid is not None):
        try:
            aoa_dev, aoa_ep_out, aoa_ep_in = setup_aoa(vendor_id=args.vid, product_id=args.pid)
            logger.info("AOA USB streaming channel established successfully.")
        except Exception as e:
            logger.error(f"Failed to establish AOA connection: {e}")
            sys.exit(1)

    streamer = HostStreamer(
        fps=args.fps,
        crf=args.crf,
        threads=args.threads,
        ffmpeg_bin=args.ffmpeg_path,
        pipe_to_stdout=args.stdout,
        aoa_dev=aoa_dev,
        aoa_ep_out=aoa_ep_out,
        aoa_ep_in=aoa_ep_in,
        chunk_size=args.chunk_size,
        scale_filter=args.scale,
        video_bitrate=args.bitrate
    )

    def handle_signal(sig, frame):
        logger.info(f"Signal {sig} received. Cleaning up...")
        streamer.stop()
        if aoa_dev:
            HostStreamer.release_usb(aoa_dev)
        sys.exit(0)

    signal.signal(signal.SIGINT, handle_signal)
    signal.signal(signal.SIGTERM, handle_signal)

    if aoa_dev and aoa_ep_out:
        streamer.stream_to_aoa(dev=aoa_dev, ep_out=aoa_ep_out, ep_in=aoa_ep_in, duration=args.duration)
    else:
        streamer.start(duration=args.duration)


if __name__ == "__main__":
    main()
