#!/usr/bin/env python3
"""
Meracast — CLI protocol tester with media playback.

Usage
-----
    python3 meracast.py

Navigatable menu with:
  [1] Start/Stop Server (SINK)    — listens for source connections
  [2] Connect/Disconnect (SOURCE) — connects to a sink
  [3] Play HTTP Media             — play a file from the source
  [4] Receive Screen Cast         — receive live screen mirroring
  [5] Send Control Command        — send any protocol command
  [6] View Log                    — live protocol log viewer
  [7] Settings                    — ports, auto-respond, play mode
  [0] Exit

The recipient sees the peer device name after connection handshake.
"""

import os
import sys
import threading
import time
from typing import Optional

# Ensure we can import sibling modules
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR:
    sys.path.insert(0, _SCRIPT_DIR)

from meracast_discovery import Discoverer, HAVE_ZEROCONF

from meracast_test import (
    ControlServer, ControlClient, MediaPlayer,
    ListFiles, Play, Pause, Resume, Seek,
    Stop, SetVolume, GetStatus, SetQuality, StartScreenCast,
    log, DEFAULT_PORT,
)

# ── Terminal helpers ───────────────────────────────────────────────

def clear() -> None:
    os.system("clear" if os.name == "posix" else "cls")

def banner(title: str = "") -> None:
    print("╔══════════════════════════════════════════════╗")
    print("║           Meracast Protocol Tester           ║")
    print("╚══════════════════════════════════════════════╝")
    if title:
        print(f"  ── {title} ──")
    print()

def status_line(server, client, player) -> str:
    parts = []
    if server and getattr(server, '_running', False):
        peer = server.peer_device_name if server.peer_device_name != "?" else ""
        parts.append(f"[SINK] {f'<- {peer}' if peer else 'waiting'}")
    if client and getattr(client, '_connected', False):
        peer = client.peer_device_name if client.peer_device_name != "?" else getattr(client, 'host', '?')
        parts.append(f"[SRC] -> {peer}")
    if player:
        parts.append(f"[PLAY] active")
    return "  |  ".join(parts) if parts else "  (idle)"

def await_input(prompt: str = "  Enter choice") -> str:
    try:
        return input(f"\n  {prompt}: ").strip().lower()
    except (EOFError, KeyboardInterrupt):
        return "0"

def pause(msg: str = "Press Enter to continue...") -> None:
    input(f"\n  {msg}")

# ── Global state ──────────────────────────────────────────────────

_server: Optional[ControlServer] = None
_client: Optional[ControlClient] = None
_player: Optional[MediaPlayer] = None
_server_thread: Optional[threading.Thread] = None

SETTINGS = {
    "ctrl_port": DEFAULT_PORT,
    "http_port": 7238,
    "rtp_port": 19000,
    "rtp_mode": "ts",
    "auto_respond": False,
    "auto_play": False,
    "receive_cast": False,
    "server_host": "0.0.0.0",
    "client_host": "127.0.0.1",
}


# ── Main menu ─────────────────────────────────────────────────────

def menu_main() -> str:
    clear()
    banner()
    print(f"  Status: {status_line(_server, _client, _player)}")
    print()
    print("  [1] Start / Stop Server (SINK mode)")
    print("  [2] Connect / Disconnect (SOURCE mode)")
    print("  [3] Play HTTP Media")
    print("  [4] Receive Screen Cast")
    print("  [5] Send Control Command")
    print("  [6] View Protocol Log")
    print("  [7] Settings")
    print()
    print("  [0] Exit")
    print()
    return await_input()


# ── Server screen ─────────────────────────────────────────────────

def screen_server() -> None:
    global _server, _server_thread
    while True:
        clear()
        banner("Server (SINK mode)")
        if _server and getattr(_server, '_running', False):
            peer = _server.peer_device_name if _server.peer_device_name != "?" else "none yet"
            print(f"  ● Server on port {SETTINGS['ctrl_port']}")
            print(f"  ● Paired with: {peer}")
            print()
            print("  [1] STOP server")
        else:
            print("  ○ Server STOPPED")
            print(f"  Will bind: {SETTINGS['server_host']}:{SETTINGS['ctrl_port']}")
            print()
            print("  [1] START server")
        print("  [b] Back")
        choice = await_input()

        if choice == "b":
            return
        elif choice == "1":
            if _server and getattr(_server, '_running', False):
                _server.stop()
                if _server_thread:
                    _server_thread.join(timeout=2)
                _server = None
                _server_thread = None
                print("\n  ✓ Server stopped")
            else:
                _server = ControlServer(
                    host=SETTINGS["server_host"],
                    port=SETTINGS["ctrl_port"],
                    auto_respond=SETTINGS["auto_respond"],
                )
                _server_thread = threading.Thread(
                    target=_server.start, daemon=True
                )
                _server_thread.start()
                print(f"\n  ✓ Server on {SETTINGS['server_host']}:{SETTINGS['ctrl_port']}")
                print("    (sources can now connect)")
            pause()


# ── Client screen ─────────────────────────────────────────────────

def screen_client() -> None:
    global _client, _player
    while True:
        clear()
        banner("Client (SOURCE mode)")
        connected = _client and getattr(_client, '_connected', False)
        if connected:
            peer = _client.peer_device_name if _client and _client.peer_device_name != "?" else getattr(_client, 'host', '?')
            print(f"  ● Connected to {peer}:{SETTINGS['ctrl_port']}")
            print(f"  ● Auto-respond: {'ON' if SETTINGS['auto_respond'] else 'OFF'}")
            print(f"  ● Auto-play:    {'ON' if SETTINGS['auto_play'] else 'OFF'}")
            print()
            print("  [1] DISCONNECT")
        else:
            print("  ○ Not connected")
            print(f"  Target: {SETTINGS['client_host']}:{SETTINGS['ctrl_port']}")
            print()
            print("  [1] CONNECT")
            print("  [s] SCAN network for devices")
        print("  [2] Send ListFiles")
        print("  [3] Send Play")
        print("  [4] Send Pause / Resume")
        print("  [5] Send Seek")
        print("  [6] Send Stop")
        print("  [7] Send SetVolume")
        print("  [8] Send Status")
        print("  [9] Send StartScreenCast")
        print("  [b] Back")
        choice = await_input()

        if choice == "b":
            return
        elif choice == "1":
            if connected:
                _client.disconnect()
                _client = None
                print("\n  ✓ Disconnected")
            else:
                _client = ControlClient(
                    host=SETTINGS["client_host"],
                    port=SETTINGS["ctrl_port"],
                    auto_respond=SETTINGS["auto_respond"],
                    play_media=SETTINGS["auto_play"],
                    receive_cast=SETTINGS["receive_cast"],
                )
                if _client.connect():
                    peer = _client.peer_device_name
                    print(f"\n  ✓ Connected — paired with \"{peer}\"")
                else:
                    print(f"\n  ✗ Connection failed")
                    _client = None
            pause()
        elif choice == "s" and not connected and HAVE_ZEROCONF:
            scan_network()
        elif choice == "2":
            if connected:
                _client.send(ListFiles("/"))
                print("  → ListFiles sent")
            else:
                print("  ✗ Not connected")
            pause()
        elif choice == "3":
            if not connected:
                print("  ✗ Not connected")
                pause()
                continue
            mid = input("  Media ID: ").strip()
            pos = input("  Position (default 0): ").strip()
            _client.send(Play(mid, float(pos) if pos else 0.0))
            pause()
        elif choice == "4":
            if not connected:
                print("  ✗ Not connected")
                pause()
                continue
            cmd = input("  [p]ause or [r]esume? ").strip().lower()
            _client.send(Pause() if cmd == "p" else Resume())
            pause()
        elif choice == "5":
            if not connected:
                print("  ✗ Not connected")
                pause()
                continue
            pos = input("  Position (seconds): ").strip()
            try:
                _client.send(Seek(float(pos)))
            except ValueError:
                print("  ✗ Invalid position")
            pause()
        elif choice == "6":
            if connected:
                _client.send(Stop())
            else:
                print("  ✗ Not connected")
            pause()
        elif choice == "7":
            if not connected:
                print("  ✗ Not connected")
                pause()
                continue
            vol = input("  Volume (0.0 - 1.0): ").strip()
            try:
                _client.send(SetVolume(float(vol)))
            except ValueError:
                print("  ✗ Invalid volume")
            pause()
        elif choice == "8":
            if connected:
                _client.send(GetStatus())
            else:
                print("  ✗ Not connected")
            pause()
        elif choice == "9":
            if connected:
                port = input("  RTP port (default 19000): ").strip()
                _client.send(StartScreenCast(int(port) if port else 19000))
            else:
                print("  ✗ Not connected")
            pause()


# ── HTTP Media Playback ────────────────────────────────────────────

def screen_play_media() -> None:
    global _player
    while True:
        clear()
        banner("Play HTTP Media")
        proc = getattr(_player, '_process', None)
        if proc and proc.poll() is None:
            print("  ● Playing...")
            print("  [1] STOP")
        else:
            print("  ○ Idle")
            print("  Example URL from source: http://192.168.1.5:7238/media_0")
            print()
            print("  [1] Play URL")
            print("  [2] Record URL to file")
        print("  [b] Back")
        choice = await_input()
        if choice == "b":
            return
        elif choice == "1":
            if proc and proc.poll() is None:
                _player.stop()
            else:
                url = input("  HTTP URL: ").strip()
                if url:
                    if _player is None:
                        _player = MediaPlayer()
                    _player.play_http(url)
            pause()
        elif choice == "2":
            url = input("  HTTP URL: ").strip()
            fname = input("  Output file: ").strip() or "download.mp4"
            if url:
                if _player is None:
                    _player = MediaPlayer()
                _player.play_http(url, record=fname)
            pause()


# ── Screen Cast receiver ──────────────────────────────────────────

def screen_receive_cast() -> None:
    global _player
    while True:
        clear()
        banner("Receive Screen Cast")
        port = SETTINGS["rtp_port"]
        rcv_thread = getattr(_player, '_receiver_thread', None) if _player else None
        receiving = rcv_thread and rcv_thread.is_alive()
        if receiving:
            print(f"  ● Receiving on port {port}")
            print("  [1] STOP")
        else:
            print(f"  ○ Not receiving — UDP port {port}")
            print()
            print("  [1] START receiving (VLC)")
            print("  [2] Record to file")
            print("  [3] Monitor only")
        print("  [4] Change RTP port")
        print(f"      Mode: {'RTP' if SETTINGS['rtp_mode'] == 'rtp' else 'Raw MPEG2-TS'}")
        print("  [5] Toggle RTP/TS")
        print("  [b] Back")
        choice = await_input()
        if choice == "b":
            return
        elif choice == "1":
            if receiving:
                _player.stop()
            else:
                if _player is None:
                    _player = MediaPlayer()
                _player.receive_rtp(port=port, is_rtp=SETTINGS["rtp_mode"] == "rtp", use_vlc=True)
            pause()
        elif choice == "2":
            fname = input("  Output file: ").strip() or "capture.ts"
            if _player is None:
                _player = MediaPlayer()
            _player.receive_rtp(port=port, is_rtp=SETTINGS["rtp_mode"] == "rtp",
                                record=fname, use_vlc=False)
            pause()
        elif choice == "3":
            if _player is None:
                _player = MediaPlayer()
            _player.receive_rtp(port=port, is_rtp=SETTINGS["rtp_mode"] == "rtp", use_vlc=False)
            pause()
        elif choice == "4":
            new_port = input(f"  New port [{port}]: ").strip()
            if new_port:
                try:
                    SETTINGS["rtp_port"] = int(new_port)
                except ValueError:
                    print("  ✗ Invalid port")
            pause()
        elif choice == "5":
            SETTINGS["rtp_mode"] = "rtp" if SETTINGS["rtp_mode"] == "ts" else "ts"
            pause()


# ── Send Custom Command ───────────────────────────────────────────

def screen_send_command() -> None:
    while True:
        clear()
        banner("Send Control Command")
        connected = _client and getattr(_client, '_connected', False)
        print(f"  Connected: {'Yes' if connected else 'No'}")
        print()
        print("  Presets:")
        print("  [1] list_files /")
        print("  [2] play <mediaId>")
        print("  [3] pause")
        print("  [4] resume")
        print("  [5] seek <pos>")
        print("  [6] stop")
        print("  [7] set_volume <0-1>")
        print("  [8] status")
        print("  [9] set_quality <bitrate>")
        print("  [0] start_screen_cast <port>")
        print()
        print("  [c] Custom JSON")
        print("  [b] Back")
        choice = await_input()
        if choice == "b":
            return
        if not connected:
            print("  ✗ Not connected")
            pause()
            continue

        presets = {
            "1": ListFiles("/"),
            "2": Play("media_abc", 0.0),
            "3": Pause(),
            "4": Resume(),
            "5": Seek(30.0),
            "6": Stop(),
            "7": SetVolume(0.5),
            "8": GetStatus(),
            "9": SetQuality(4000000),
            "0": StartScreenCast(19000),
        }
        if choice in presets:
            _client.send(presets[choice])
            print(f"  → {type(presets[choice]).__name__} sent")
        elif choice == "c":
            from meracast_test import parse_message
            raw = input("  JSON: ").strip()
            msg = parse_message(raw)
            if msg:
                _client.send(msg)
                print(f"  → {type(msg).__name__} sent")
            else:
                _client.send(UnknownMessage(raw))
                print("  → Raw JSON sent")
        pause()


# Wrapper for raw JSON
class UnknownMessage:
    def __init__(self, raw_json: str):
        self._json = raw_json

import meracast_test
_orig_msg_to_json = meracast_test.message_to_json
def _patched_msg_to_json(msg):
    return msg._json if isinstance(msg, UnknownMessage) else _orig_msg_to_json(msg)
meracast_test.message_to_json = _patched_msg_to_json


# ── Log viewer ────────────────────────────────────────────────────

def screen_log() -> None:
    if not hasattr(meracast_test, '_log_buffer'):
        meracast_test._log_buffer = []
        meracast_test.MAX_LOG_LINES = 500
        _orig_log = meracast_test.log
        def _capture_log(tag, msg):
            entry = f"[{time.strftime('%H:%M:%S')}] {tag}: {msg}"
            meracast_test._log_buffer.append(entry)
            if len(meracast_test._log_buffer) > meracast_test.MAX_LOG_LINES:
                meracast_test._log_buffer = meracast_test._log_buffer[-meracast_test.MAX_LOG_LINES:]
            _orig_log(tag, msg)
        meracast_test.log = _capture_log

    while True:
        clear()
        banner("Protocol Log")
        print("  Last 30 log entries:")
        print()
        buf = getattr(meracast_test, '_log_buffer', [])
        lines = buf[-30:] if buf else []
        if not lines:
            print("    (no entries)")
        else:
            for entry in lines:
                print(f"  {entry}")
        print()
        print("  [r] Refresh  [c] Clear  [b] Back")
        choice = await_input()
        if choice == "b":
            return
        elif choice == "c":
            meracast_test._log_buffer.clear()
            pause()
        # else: loop re-renders


# ── Scan network ─────────────────────────────────────────────────

def scan_network() -> None:
    """Scan the network for Meracast devices and let user pick one."""
    clear()
    banner("Scan Network")
    print("  Scanning for Meracast devices (5 seconds)...")
    d = Discoverer()
    devices = d.discover(timeout=5.0)
    d.close()

    clear()
    banner("Devices Found")

    if not devices:
        print("  No Meracast devices found on the network.")
        print("  (Make sure the other device is on the same Wi-Fi)")
        print()
        print("  You can still enter the IP manually in Settings [7].")
        pause()
        return

    print(f"  {len(devices)} device(s) found:")
    print()
    for i, svc in enumerate(devices, 1):
        name = svc.device_name or svc.name
        model = f" ({svc.device_model})" if svc.device_model else ""
        icon = "📤" if svc.mode == "source" else "📺"
        print(f"  [{i}] {icon} {name}{model}  —  {svc.host}:{svc.port}")
    print()
    print("  Pick a number to set as target, or [b] back.")
    choice = await_input()
    if choice == "b":
        return
    try:
        idx = int(choice) - 1
        if 0 <= idx < len(devices):
            svc = devices[idx]
            SETTINGS["client_host"] = svc.host
            SETTINGS["ctrl_port"] = svc.port
            print(f"\n  ✓ Target set to {svc.device_name or svc.name} at {svc.host}:{svc.port}")
        else:
            print("  ✗ Invalid selection")
    except ValueError:
        print("  ✗ Enter a number")
    pause()


# ── Settings ──────────────────────────────────────────────────────

def screen_settings() -> None:
    while True:
        clear()
        banner("Settings")
        print(f"  [1] Control port:        {SETTINGS['ctrl_port']}")
        print(f"  [2] HTTP media port:     {SETTINGS['http_port']}")
        print(f"  [3] RTP cast port:       {SETTINGS['rtp_port']}")
        print(f"  [4] Server bind host:    {SETTINGS['server_host']}")
        print(f"  [5] Client target host:  {SETTINGS['client_host']}")
        print(f"  [6] Auto-respond:        {'ON' if SETTINGS['auto_respond'] else 'OFF'}")
        print(f"  [7] Auto-play media:     {'ON' if SETTINGS['auto_play'] else 'OFF'}")
        print(f"  [8] Auto-receive cast:   {'ON' if SETTINGS['receive_cast'] else 'OFF'}")
        print()
        print("  [b] Back")
        choice = await_input()
        if choice == "b":
            return
        elif choice == "1":
            v = input(f"  Ctrl port [{SETTINGS['ctrl_port']}]: ").strip()
            if v:
                try: SETTINGS['ctrl_port'] = int(v)
                except: print("  ✗ Invalid")
        elif choice == "2":
            v = input(f"  HTTP port [{SETTINGS['http_port']}]: ").strip()
            if v:
                try: SETTINGS['http_port'] = int(v)
                except: print("  ✗ Invalid")
        elif choice == "3":
            v = input(f"  RTP port [{SETTINGS['rtp_port']}]: ").strip()
            if v:
                try: SETTINGS['rtp_port'] = int(v)
                except: print("  ✗ Invalid")
        elif choice == "4":
            v = input(f"  Server host [{SETTINGS['server_host']}]: ").strip()
            if v: SETTINGS['server_host'] = v
        elif choice == "5":
            v = input(f"  Client host [{SETTINGS['client_host']}]: ").strip()
            if v: SETTINGS['client_host'] = v
        elif choice == "6":
            SETTINGS['auto_respond'] = not SETTINGS['auto_respond']
        elif choice == "7":
            SETTINGS['auto_play'] = not SETTINGS['auto_play']
        elif choice == "8":
            SETTINGS['receive_cast'] = not SETTINGS['receive_cast']


# ── Cleanup ───────────────────────────────────────────────────────

def cleanup() -> None:
    global _server, _client, _player
    if _server:
        try: _server.stop()
        except: pass
        _server = None
    if _client:
        try: _client.disconnect()
        except: pass
        _client = None
    if _player:
        try: _player.stop()
        except: pass
        _player = None
    if _orig_msg_to_json:
        meracast_test.message_to_json = _orig_msg_to_json


# ── Main loop ─────────────────────────────────────────────────────

def main() -> None:
    try:
        while True:
            choice = menu_main()
            if choice in ("0", "exit", "quit"):
                print("\n  Shutting down...")
                break
            elif choice == "1":    screen_server()
            elif choice == "2":    screen_client()
            elif choice == "3":    screen_play_media()
            elif choice == "4":    screen_receive_cast()
            elif choice == "5":    screen_send_command()
            elif choice == "6":    screen_log()
            elif choice == "7":    screen_settings()
            else:
                print("\n  ✗ Invalid choice")
                pause()
    except KeyboardInterrupt:
        print("\n\n  Interrupted.")
    finally:
        cleanup()
        clear()
        print("Meracast Tester — goodbye.\n")

if __name__ == "__main__":
    main()
