#!/usr/bin/env python3
"""
Unit tests for host_streamer.py verifying NAL parsing, FFmpeg argument generation,
and the Android Open Accessory (AOA) protocol handshake.
"""

import io
import unittest
from unittest.mock import MagicMock, patch
import ffmpeg
import usb.core
import usb.util

from host_streamer import (
    HostStreamer,
    NalUnitReader,
    NAL_TYPES,
    AOA_GOOGLE_VID,
    AOA_REQ_GET_PROTOCOL,
    AOA_REQ_SEND_STRING,
    AOA_REQ_START_ACCESSORY,
    AOA_STRINGS,
    setup_aoa
)


class TestHostStreamer(unittest.TestCase):

    def test_nal_unit_reader_parsing(self):
        # Sample Annex-B H.264 stream with SPS (0x67) and PPS (0x68) and IDR (0x65)
        raw_stream = (
            b"\x00\x00\x00\x01\x67\x42\x00\x1e"  # SPS
            b"\x00\x00\x00\x01\x68\xce\x38\x80"  # PPS
            b"\x00\x00\x00\x01\x65\x88\x84\x00"  # IDR
            b"\x00\x00\x00\x01"                  # Trailing boundary
        )

        stream_io = io.BytesIO(raw_stream)
        reader = NalUnitReader(stream_io, chunk_size=8)
        nals = list(reader.iter_nal_units())

        self.assertEqual(len(nals), 3)

        # Check types
        self.assertEqual(nals[0][0], 7)
        self.assertEqual(NAL_TYPES[nals[0][0]], "SPS")
        self.assertEqual(nals[0][1], b"\x00\x00\x00\x01\x67\x42\x00\x1e")

        self.assertEqual(nals[1][0], 8)
        self.assertEqual(NAL_TYPES[nals[1][0]], "PPS")
        self.assertEqual(nals[1][1], b"\x00\x00\x00\x01\x68\xce\x38\x80")

        self.assertEqual(nals[2][0], 5)
        self.assertEqual(NAL_TYPES[nals[2][0]], "IDR_KEYFRAME")
        self.assertEqual(nals[2][1], b"\x00\x00\x00\x01\x65\x88\x84\x00")

    def test_ffmpeg_arguments_compilation(self):
        stream = (
            ffmpeg
            .input(
                "pipe:",
                format="rawvideo",
                pix_fmt="bgra",
                s="1920x1080",
                framerate=30
            )
            .output(
                "pipe:",
                format="h264",
                vcodec="libx264",
                preset="ultrafast",
                tune="zerolatency",
                pix_fmt="yuv420p",
                g=30,
                crf=26,
                threads=2
            )
        )

        args = stream.compile()

        # Verify critical low-latency and low-overhead parameters
        self.assertIn("-f", args)
        self.assertIn("rawvideo", args)
        self.assertIn("-pix_fmt", args)
        self.assertIn("bgra", args)
        self.assertIn("1920x1080", args)
        self.assertIn("-preset", args)
        self.assertIn("ultrafast", args)
        self.assertIn("-tune", args)
        self.assertIn("zerolatency", args)
        self.assertIn("-f", args)
        self.assertIn("h264", args)
        self.assertIn("-threads", args)
        self.assertIn("2", args)

    def test_aoa_handshake_control_transfers(self):
        mock_phone = MagicMock()
        mock_phone.idVendor = 0x04E8
        mock_phone.idProduct = 0x6860
        mock_phone.bus = 1
        mock_phone.address = 2
        mock_phone.is_kernel_driver_active.return_value = False

        switched = False

        def mock_ctrl_transfer(**kwargs):
            nonlocal switched
            req = kwargs.get("bRequest")
            if req == AOA_REQ_GET_PROTOCOL:
                return bytes([2, 0])
            elif req == AOA_REQ_START_ACCESSORY:
                switched = True
                return None
            return None

        mock_phone.ctrl_transfer.side_effect = mock_ctrl_transfer

        # Re-enumerated accessory mock
        mock_accessory = MagicMock()
        mock_accessory.idVendor = AOA_GOOGLE_VID
        mock_accessory.idProduct = 0x2D01  # AOA + ADB

        mock_cfg = MagicMock()
        mock_intf = MagicMock()
        mock_cfg.__getitem__.return_value = mock_intf
        mock_accessory.get_active_configuration.return_value = mock_cfg

        mock_ep_out = MagicMock()
        mock_ep_out.bEndpointAddress = 0x02
        mock_ep_out.bmAttributes = usb.util.ENDPOINT_TYPE_BULK

        mock_ep_in = MagicMock()
        mock_ep_in.bEndpointAddress = 0x81
        mock_ep_in.bmAttributes = usb.util.ENDPOINT_TYPE_BULK

        def mock_find(**kwargs):
            vid = kwargs.get("idVendor")
            pid = kwargs.get("idProduct")
            if vid == AOA_GOOGLE_VID:
                return mock_accessory if switched else None
            if vid == 0x04E8:
                return mock_phone
            return None

        with patch("host_streamer.find_usb_device", side_effect=mock_find), \
             patch("usb.util.find_descriptor", side_effect=[mock_ep_out, mock_ep_in]):

            dev, ep_out, ep_in = setup_aoa(vendor_id=0x04E8, product_id=0x6860)

            self.assertEqual(dev, mock_accessory)
            self.assertEqual(ep_out, mock_ep_out)
            self.assertEqual(ep_in, mock_ep_in)

            # Verify control transfer calls
            calls = mock_phone.ctrl_transfer.call_args_list
            self.assertEqual(len(calls), 8)

            # 1. Request 51 (Protocol Version)
            self.assertEqual(calls[0].kwargs["bRequest"], AOA_REQ_GET_PROTOCOL)
            self.assertEqual(calls[0].kwargs["bmRequestType"], 0xC0)

            # 2. Request 52 (Strings 0-5)
            for idx in range(6):
                call = calls[idx + 1]
                self.assertEqual(call.kwargs["bRequest"], AOA_REQ_SEND_STRING)
                self.assertEqual(call.kwargs["bmRequestType"], 0x40)
                self.assertEqual(call.kwargs["wIndex"], idx)
                expected_str = (AOA_STRINGS[idx] + "\0").encode("utf-8")
                self.assertEqual(call.kwargs["data_or_wLength"], expected_str)

            # 3. Request 53 (Start Accessory)
            self.assertEqual(calls[7].kwargs["bRequest"], AOA_REQ_START_ACCESSORY)
            self.assertEqual(calls[7].kwargs["bmRequestType"], 0x40)

    def test_aoa_device_already_in_accessory_mode(self):
        mock_accessory = MagicMock()
        mock_accessory.idVendor = AOA_GOOGLE_VID
        mock_accessory.idProduct = 0x2D00

        mock_cfg = MagicMock()
        mock_intf = MagicMock()
        mock_cfg.__getitem__.return_value = mock_intf
        mock_accessory.get_active_configuration.return_value = mock_cfg

        mock_ep_out = MagicMock()
        mock_ep_out.bEndpointAddress = 0x02
        mock_ep_out.bmAttributes = usb.util.ENDPOINT_TYPE_BULK

        mock_ep_in = MagicMock()
        mock_ep_in.bEndpointAddress = 0x81
        mock_ep_in.bmAttributes = usb.util.ENDPOINT_TYPE_BULK

        def mock_find(**kwargs):
            if kwargs.get("idVendor") == AOA_GOOGLE_VID and kwargs.get("idProduct") == 0x2D00:
                return mock_accessory
            return None

        with patch("host_streamer.find_usb_device", side_effect=mock_find), \
             patch("usb.util.find_descriptor", side_effect=[mock_ep_out, mock_ep_in]):

            dev, ep_out, ep_in = setup_aoa()

            self.assertEqual(dev, mock_accessory)
            self.assertEqual(ep_out, mock_ep_out)
            self.assertEqual(ep_in, mock_ep_in)

    def test_stdout_reader_thread_enqueues_chunks(self):
        streamer = HostStreamer(chunk_size=6)
        mock_process = MagicMock()
        mock_process.stdout = io.BytesIO(b"CHUNK1" + b"CHUNK2" + b"CHUNK3")
        streamer.process = mock_process
        streamer.running = True

        streamer._stdout_reader_loop()

        self.assertEqual(streamer.chunk_queue.get_nowait(), b"CHUNK1")
        self.assertEqual(streamer.chunk_queue.get_nowait(), b"CHUNK2")
        self.assertEqual(streamer.chunk_queue.get_nowait(), b"CHUNK3")
        self.assertIsNone(streamer.chunk_queue.get_nowait())  # EOF sentinel
        self.assertEqual(streamer.bytes_encoded, 18)

    def test_aoa_streaming_main_loop_transmission(self):
        mock_dev = MagicMock()
        mock_ep_out = MagicMock()
        mock_ep_out.bEndpointAddress = 0x02
        mock_ep_out.write.side_effect = lambda chunk, timeout: len(chunk)

        streamer = HostStreamer(aoa_dev=mock_dev, aoa_ep_out=mock_ep_out)
        streamer.running = True

        # Pre-populate chunk queue
        streamer.chunk_queue.put(b"TEST_CHUNK_1")
        streamer.chunk_queue.put(b"TEST_CHUNK_2")
        streamer.chunk_queue.put(None)  # Sentinel

        with patch.object(streamer, "start_pipeline"), \
             patch("usb.util.dispose_resources") as mock_dispose:
            streamer.stream_to_aoa(mock_dev, mock_ep_out)

            # Verify both chunks were written to ep_out
            self.assertEqual(mock_ep_out.write.call_count, 2)
            mock_ep_out.write.assert_any_call(b"TEST_CHUNK_1", timeout=2000)
            mock_ep_out.write.assert_any_call(b"TEST_CHUNK_2", timeout=2000)
            self.assertEqual(streamer.bytes_transmitted, len(b"TEST_CHUNK_1") + len(b"TEST_CHUNK_2"))
            mock_dispose.assert_called_once_with(mock_dev)

    def test_aoa_cable_detachment_clean_teardown(self):
        mock_dev = MagicMock()
        mock_ep_out = MagicMock()
        mock_ep_out.bEndpointAddress = 0x02
        # Simulate USB error when cable is detached
        mock_ep_out.write.side_effect = usb.core.USBError("[Errno 19] No such device (it may have been disconnected)")

        mock_process = MagicMock()
        mock_stdin = MagicMock()
        mock_stdin.closed = False
        mock_process.stdin = mock_stdin

        streamer = HostStreamer(aoa_dev=mock_dev, aoa_ep_out=mock_ep_out)
        streamer.process = mock_process
        streamer.running = True

        streamer.chunk_queue.put(b"CHUNK_BEFORE_DISCONNECT")

        with patch.object(streamer, "start_pipeline"), \
             patch("usb.util.dispose_resources") as mock_dispose, \
             patch("usb.util.release_interface") as mock_release:

            # Main loop must catch the USBError without throwing unhandled exception
            streamer.stream_to_aoa(mock_dev, mock_ep_out)

            # Verify USB interface release and resource disposal
            mock_release.assert_called_once_with(mock_dev, 0)
            mock_dispose.assert_called_once_with(mock_dev)

            # Verify FFmpeg process was cleanly terminated
            mock_stdin.close.assert_called_once()
            mock_process.terminate.assert_called_once()
            self.assertFalse(streamer.running)

    def test_aoa_in_reader_telemetry_feedback(self):
        mock_ep_in = MagicMock()
        mock_ep_in.bEndpointAddress = 0x81
        telemetry_json = b'{"command": "reduce_bitrate", "latency_ms": 78.5, "queue_size": 18, "dropped_nals": 3}\n'
        call_count = 0

        def mock_read(size, timeout=None):
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return telemetry_json
            raise usb.core.USBError("Simulated finish")

        mock_ep_in.read.side_effect = mock_read

        streamer = HostStreamer(aoa_ep_in=mock_ep_in)
        streamer.running = True

        with patch.object(streamer, "handle_reduce_bitrate") as mock_reduce:
            streamer._aoa_in_reader_loop()
            mock_reduce.assert_called_once()

        self.assertEqual(len(streamer.telemetry_feedback_received), 1)
        feedback = streamer.telemetry_feedback_received[0]
        self.assertEqual(feedback["command"], "reduce_bitrate")
        self.assertEqual(feedback["latency_ms"], 78.5)
        self.assertEqual(feedback["queue_size"], 18)
        self.assertEqual(feedback["dropped_nals"], 3)

    def test_handle_reduce_bitrate_adaptation_stages(self):
        streamer = HostStreamer()
        mock_proc = MagicMock()
        mock_proc.stdin = MagicMock()
        mock_proc.stdin.closed = False
        streamer.process = mock_proc

        with patch.object(streamer, "start_ffmpeg") as mock_start:
            # Stage 1 Adaptation: 720p scale filter and 1800k bitrate
            streamer.handle_reduce_bitrate(reason="Stage 1 test")
            self.assertEqual(streamer.adaptation_level, 1)
            self.assertEqual(streamer.scale_filter, "scale=-1:720")
            self.assertEqual(streamer.video_bitrate, "1800k")
            mock_start.assert_called_with(1920, 1080)

            # Stage 2 Adaptation: Lower bitrate to 1000k
            streamer.handle_reduce_bitrate(reason="Stage 2 test")
            self.assertEqual(streamer.adaptation_level, 2)
            self.assertEqual(streamer.scale_filter, "scale=-1:720")
            self.assertEqual(streamer.video_bitrate, "1000k")

            # Stage 3 Adaptation: 540p scale filter and 600k bitrate
            streamer.handle_reduce_bitrate(reason="Stage 3 test")
            self.assertEqual(streamer.adaptation_level, 3)
            self.assertEqual(streamer.scale_filter, "scale=-1:540")
            self.assertEqual(streamer.video_bitrate, "600k")


if __name__ == "__main__":
    unittest.main()
