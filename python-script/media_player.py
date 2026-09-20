#!/usr/bin/env python3
"""
Meracast Media Player — plays video/audio received via the custom protocol.

Depends on:  cvlc (VLC with no GUI interface)

Usage
-----
    # Play an HTTP media stream from a Meracast source
    python3 media_player.py http-play <http-url>

    # Receive an RTP/UDP screen cast stream (port 19000) and play it
    python3 media_player.py receive-cast [--port PORT] [--rtp]

    # Record stream to file instead of playing
    python3 media_player.py receive-cast --record output.ts

The player can also be imported and used from meracast_test.py:
    from media_player import MediaPlayer
    player = MediaPlayer()
    player.play_http("http://192.168.1.5:7238/media_0")
    player.stop()
"""

import argparse
import os
import signal
import subprocess
import socket
import struct
import sys
import threading
import time
from typing import Optional

# ── Constants ──────────────────────────────────────────────────────────────

UDP_BUFFER_SIZE = 65536
DEFAULT_RTP_PORT = 19000

# ── MediaPlayer ────────────────────────────────────────────────────────────


class MediaPlayer:
    """
    Plays media streams received through the Meracast protocol.

    Two modes:
      1. HTTP media playback — plays a file served by the source's HTTP server
      2. RTP/UDP screen cast reception — receives and plays a live stream

    Uses cvlc (headless VLC) as the playback backend.
    Falls back to writing raw data to a file if VLC is unavailable.
    """

    def __init__(self):
        self._process: Optional[subprocess.Popen] = None
        self._proc_stdin: Optional[Any] = None
        self._receiver_thread: Optional[threading.Thread] = None
        self._recording = False
        self._record_file: Optional[str] = None
        self._stop_event = threading.Event()
        self._lock = threading.Lock()

    def _has_vlc(self) -> bool:
        """Check if cvlc is available on the system."""
        try:
            subprocess.run(
                ["cvlc", "--version"],
                capture_output=True, timeout=3,
            )
            return True
        except (FileNotFoundError, subprocess.TimeoutExpired):
            return False

    def play_http(self, url: str, record: Optional[str] = None) -> bool:
        """
        Play a media file served at an HTTP URL.

        VLC handles HTTP range requests natively, so seeking works.
        Returns True if playback started.
        """
        self.stop()
        self._recording = record is not None
        self._record_file = record

        log_out(f"MEDIA: Playing HTTP stream: {url}")

        if record:
            log_out(f"MEDIA: Recording to {record}")

        if self._has_vlc():
            cmd = ["cvlc", "--no-video-title", "--play-and-exit"]
            if record:
                # Play + record simultaneously
                cmd += [f"--sout=#duplicate{{dst=display,dst=file{{dst={record}}}}}"]
            cmd.append(url)

            try:
                self._process = subprocess.Popen(
                    cmd,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    preexec_fn=os.setsid if hasattr(os, 'setsid') else None,
                )
                log_out(f"MEDIA: VLC started (PID {self._process.pid})")
                log_out("MEDIA: Close VLC window or press Ctrl+C to stop")
                return True
            except FileNotFoundError:
                log_out("MEDIA: cvlc not found — falling back to download")
                return self._download_http(url, record)
        else:
            return self._download_http(url, record)

    def _download_http(self, url: str, record: Optional[str] = None) -> bool:
        """Fallback: download the HTTP stream to a file."""
        import urllib.request

        outpath = record or f"media_download_{int(time.time())}.mp4"
        log_out(f"MEDIA: Downloading to {outpath} ...")
        try:
            with urllib.request.urlopen(url, timeout=30) as resp:
                total = int(resp.headers.get("Content-Length", 0))
                downloaded = 0
                chunk_size = 256 * 1024
                with open(outpath, "wb") as f:
                    while True:
                        chunk = resp.read(chunk_size)
                        if not chunk:
                            break
                        f.write(chunk)
                        downloaded += len(chunk)
                        if total > 0:
                            pct = int(downloaded * 100 / total)
                            sys.stdout.write(
                                f"\r  Downloaded {downloaded // (1024*1024)}M/{total // (1024*1024)}M ({pct}%)"
                            )
                            sys.stdout.flush()
            print()
            log_out(f"MEDIA: Saved to {outpath}")
            return True
        except Exception as e:
            log_out(f"MEDIA: Download failed: {e}")
            return False

    def receive_rtp(
        self,
        port: int = DEFAULT_RTP_PORT,
        is_rtp: bool = False,
        record: Optional[str] = None,
        use_vlc: bool = True,
    ) -> None:
        """
        Start receiving a UDP/RTP media stream and playing it.

        Args:
            port: UDP port to listen on (default 19000)
            is_rtp: True if packets are RTP-encapsulated (RFC 3550)
            record: Optional file path to save the raw stream
            use_vlc: If True, pipe data to VLC for playback
        """
        self.stop()
        self._stop_event.clear()
        self._recording = record is not None
        self._record_file = record

        log_out(f"MEDIA: {'RTP' if is_rtp else 'UDP/TS'} receiver on port {port}")
        if record:
            log_out(f"MEDIA: Recording to {record}")

        if use_vlc and self._has_vlc():
            self._start_vlc_pipe(port, is_rtp, record)
        elif not use_vlc and record:
            self._start_raw_recorder(port, record)
        else:
            log_out("MEDIA: No VLC and no record file — dumping stream info only")
            self._start_info_only(port, is_rtp)

    def _start_vlc_pipe(self, port: int, is_rtp: bool, record: Optional[str]) -> None:
        """Start VLC with data piped through stdin (for UDP/TS) or direct URL."""
        if is_rtp:
            # For RTP, tell VLC to listen on the UDP port directly
            url = f"rtp://@:{port}"
            cmd = ["cvlc", "--no-video-title", "--play-and-exit", url]
            cmd += ["--rtp-max-profile=0"]  # accept any RTP payload type
            if record:
                cmd += [f"--sout=#duplicate{{dst=display,dst=file{{dst={record}}}}}"]
            try:
                self._process = subprocess.Popen(
                    cmd,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    preexec_fn=os.setsid if hasattr(os, 'setsid') else None,
                )
                log_out(f"MEDIA: VLC receiving RTP on port {port}")
            except FileNotFoundError:
                log_out("MEDIA: cvlc not found")
        else:
            # For raw TS/UDP: pipe data to VLC's stdin
            cmd = ["cvlc", "--no-video-title", "--play-and-exit", "-"]
            if record:
                cmd += [f"--sout=#duplicate{{dst=display,dst=file{{dst={record}}}}}"]

            try:
                self._process = subprocess.Popen(
                    cmd,
                    stdin=subprocess.PIPE,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    preexec_fn=os.setsid if hasattr(os, 'setsid') else None,
                )
                self._proc_stdin = self._process.stdin
                log_out(f"MEDIA: VLC started (stdin pipe, PID {self._process.pid})")
            except FileNotFoundError:
                log_out("MEDIA: cvlc not found — falling back to raw recorder")
                self._start_raw_recorder(port, record)
                return

            # Start the UDP receiver that feeds VLC's stdin
            self._receiver_thread = threading.Thread(
                target=self._udp_to_stdin_loop,
                args=(port, is_rtp),
                daemon=True,
            )
            self._receiver_thread.start()

    def _start_raw_recorder(self, port: int, record: str) -> None:
        """Record raw UDP data to a file."""
        self._receiver_thread = threading.Thread(
            target=self._udp_recorder_loop,
            args=(port, record),
            daemon=True,
        )
        self._receiver_thread.start()

    def _start_info_only(self, port: int, is_rtp: bool) -> None:
        """Log stream info without playing or recording."""
        self._receiver_thread = threading.Thread(
            target=self._udp_info_loop,
            args=(port, is_rtp),
            daemon=True,
        )
        self._receiver_thread.start()

    def _udp_to_stdin_loop(self, port: int, is_rtp: bool) -> None:
        """Receive UDP packets and feed the raw payload to VLC's stdin."""
        sock = None
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("0.0.0.0", port))
            sock.settimeout(1.0)
            log_out(f"MEDIA: UDP receiver listening on port {port}")

            pkt_count = 0
            last_log = time.time()

            while not self._stop_event.is_set():
                try:
                    data, addr = sock.recvfrom(UDP_BUFFER_SIZE)
                except socket.timeout:
                    continue

                pkt_count += 1

                # Extract payload (skip RTP header if RTP mode)
                payload = data
                if is_rtp and len(data) >= 12:
                    # Parse minimal RTP header to get payload
                    first_byte = data[0]
                    cc = first_byte & 0x0F
                    header_size = 12 + cc * 4
                    if data[1] & 0x20:  # padding
                        padding = data[-1]
                    else:
                        padding = 0
                    payload = data[header_size : len(data) - padding]

                if self._proc_stdin and not self._proc_stdin.closed:
                    try:
                        self._proc_stdin.write(payload)
                        self._proc_stdin.flush()
                    except (BrokenPipeError, OSError):
                        log_out("MEDIA: VLC pipe closed — stopping")
                        break

                # Log every 5 seconds
                now = time.time()
                if now - last_log >= 5:
                    log_out(f"MEDIA: Received {pkt_count} packets ({pkt_count // 5}/s)")
                    pkt_count = 0
                    last_log = now

        except OSError as e:
            log_out(f"MEDIA: UDP error: {e}")
        finally:
            if sock:
                sock.close()
            log_out("MEDIA: UDP receiver stopped")

    def _udp_recorder_loop(self, port: int, record: str) -> None:
        """Receive UDP packets and save to file."""
        sock = None
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("0.0.0.0", port))
            sock.settimeout(1.0)
            log_out(f"MEDIA: Recording UDP stream from port {port} to {record}")

            with open(record, "wb") as f:
                pkt_count = 0
                last_log = time.time()
                while not self._stop_event.is_set():
                    try:
                        data, addr = sock.recvfrom(UDP_BUFFER_SIZE)
                    except socket.timeout:
                        continue
                    f.write(data)
                    pkt_count += 1
                    now = time.time()
                    if now - last_log >= 5:
                        sz_mb = os.path.getsize(record) / (1024 * 1024)
                        log_out(f"MEDIA: Recorded {pkt_count} packets ({sz_mb:.1f} MB)")
                        pkt_count = 0
                        last_log = now

            log_out(f"MEDIA: Saved {record}")
        except OSError as e:
            log_out(f"MEDIA: Record error: {e}")
        finally:
            if sock:
                sock.close()

    def _udp_info_loop(self, port: int, is_rtp: bool) -> None:
        """Receive UDP and log packet info without playing."""
        sock = None
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("0.0.0.0", port))
            sock.settimeout(1.0)
            log_out(f"MEDIA: Monitoring UDP port {port} (no playback)")

            pkt_count = 0
            byte_count = 0
            last_log = time.time()

            while not self._stop_event.is_set():
                try:
                    data, addr = sock.recvfrom(UDP_BUFFER_SIZE)
                except socket.timeout:
                    continue

                pkt_count += 1
                byte_count += len(data)
                now = time.time()

                if now - last_log >= 5:
                    rate = byte_count / 5 / 1024
                    log_out(
                        f"MEDIA: {pkt_count} pkts, {byte_count // 1024} KB "
                        f"({rate:.0f} KB/s) from {addr[0]}:{addr[1]}"
                    )
                    if is_rtp and len(data) >= 12:
                        pt = data[1] & 0x7F
                        seq = (data[2] << 8) | data[3]
                        ts = struct.unpack("!I", data[4:8])[0]
                        log_out(f"  RTP: PT={pt}, seq={seq}, ts={ts}, payload={len(data)-12} bytes")
                    pkt_count = 0
                    byte_count = 0
                    last_log = now

        except OSError as e:
            log_out(f"MEDIA: Monitor error: {e}")
        finally:
            if sock:
                sock.close()

    def stop(self) -> None:
        """Stop any active playback or reception."""
        self._stop_event.set()

        if self._process:
            try:
                pgid = os.getpgid(self._process.pid)
                os.killpg(pgid, signal.SIGTERM)
            except (ProcessLookupError, PermissionError, AttributeError, OSError):
                try:
                    self._process.terminate()
                except Exception:
                    pass
            try:
                self._process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                try:
                    self._process.kill()
                    self._process.wait(timeout=1)
                except Exception:
                    pass
            self._process = None
            self._proc_stdin = None

        if self._receiver_thread and self._receiver_thread.is_alive():
            self._receiver_thread.join(timeout=2)

        log_out("MEDIA: Stopped")


# ── Logging helper (reused from meracast_test) ─────────────────────────────

_lock = threading.Lock()


def log_out(tag: str) -> None:
    with _lock:
        print(tag, flush=True)


# ── CLI ────────────────────────────────────────────────────────────────────


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Meracast Media Player",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    sub = parser.add_subparsers(dest="mode", required=True)

    # HTTP playback
    hp = sub.add_parser("http-play", help="Play a media file served via HTTP")
    hp.add_argument("url", help="Full HTTP URL (e.g. http://192.168.1.5:7238/media_0)")
    hp.add_argument("--record", help="Save stream to file while playing")

    # RTP/UDP screen cast reception
    rc = sub.add_parser("receive-cast", help="Receive and play a screen cast stream")
    rc.add_argument("--port", type=int, default=DEFAULT_RTP_PORT, help=f"UDP port (default {DEFAULT_RTP_PORT})")
    rc.add_argument("--rtp", action="store_true", help="Parse RTP headers (default: raw MPEG2-TS)")
    rc.add_argument("--record", help="Save raw stream to file instead of playing")
    rc.add_argument("--info", action="store_true", help="Log stream info without playing")

    args = parser.parse_args()

    player = MediaPlayer()

    try:
        if args.mode == "http-play":
            player.play_http(args.url, record=args.record)
            # Wait for VLC to finish or user to press Ctrl+C
            try:
                while player._process and player._process.poll() is None:
                    time.sleep(0.5)
            except KeyboardInterrupt:
                log_out("\nMEDIA: Interrupted by user")
                player.stop()

        elif args.mode == "receive-cast":
            if args.info:
                player.receive_rtp(port=args.port, is_rtp=args.rtp, use_vlc=False)
            elif args.record:
                player.receive_rtp(port=args.port, is_rtp=args.rtp, record=args.record, use_vlc=False)
            else:
                player.receive_rtp(port=args.port, is_rtp=args.rtp, use_vlc=True)

            log_out("MEDIA: Press Ctrl+C to stop")
            try:
                while player._receiver_thread and player._receiver_thread.is_alive():
                    time.sleep(0.5)
            except KeyboardInterrupt:
                log_out("\nMEDIA: Interrupted by user")
                player.stop()

    except KeyboardInterrupt:
        player.stop()


if __name__ == "__main__":
    main()
