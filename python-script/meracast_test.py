#!/usr/bin/env python3
"""
Meracast Custom Control Protocol Tester
========================================

Mirrors the Kotlin protocol in `common/ControlMessage.kt` + `common/ControlSocket.kt`.

Wire format:
    [4 bytes: payload length (big-endian int)] [N bytes: UTF-8 JSON]

Commands use the       "cmd"   JSON field.
Events  use the       "event" JSON field.

Default port: 7237

Usage
-----
    # Run as a SINK server (listens for source connections)
    python3 meracast_test.py server [--port PORT] [--auto]

    # Run as a SOURCE client (connects to a running sink)
    python3 meracast_test.py client <host> [--port PORT]

In interactive mode, type message names at the prompt to send them.

Examples
--------
    # Terminal 1 — sink (auto-responds)
    python3 meracast_test.py server --auto

    # Terminal 2 — source connecting to localhost
    python3 meracast_test.py client 127.0.0.1

    # Then at the source prompt:
    > play media_abc 0.0
    > pause
    > seek 15.5
    > stop
    > list_files /
    > set_volume 0.75
    > status
    > start_screen_cast 19000
    > quit
"""

import argparse
import json
import socket
import struct
import sys
import threading
import time
from dataclasses import dataclass, field, asdict
from typing import Any, Optional

# Media playback via VLC (cvlc)
from media_player import MediaPlayer

DEFAULT_PORT = 7237
MAX_PAYLOAD = 65536

# ── Message types ──────────────────────────────────────────────────────────


@dataclass
class FileEntry:
    id: str
    name: str
    path: str
    size: int
    mimeType: str
    duration: float = 0.0


@dataclass
class QualityEntry:
    label: str
    width: int
    height: int
    bitrate: int


# ── Commands (sink → source) ───────────────────────────────────────────────


@dataclass
class ListFiles:
    path: str = "/"


@dataclass
class Play:
    mediaId: str
    position: float = 0.0


@dataclass
class Pause:
    pass


@dataclass
class Resume:
    pass


@dataclass
class Seek:
    position: float


@dataclass
class Stop:
    pass


@dataclass
class SetVolume:
    volume: float


@dataclass
class GetStatus:
    pass


@dataclass
class SetQuality:
    maxBitrate: int


@dataclass
class StartScreenCast:
    port: int = 19000


# ── Handshake messages ──────────────────────────────────────────────────────


@dataclass
class HelloSource:
    """HELLO from the source — sent immediately after TCP connect."""
    deviceName: str = ""
    deviceModel: str = ""
    version: int = 1
    capabilities: list[str] = field(default_factory=lambda: ["media", "cast"])


@dataclass
class HelloSink:
    """HELLO from the sink — sent in reply to HelloSource."""
    deviceName: str = ""
    deviceModel: str = ""
    version: int = 1
    capabilities: list[str] = field(default_factory=lambda: ["playback", "cast"])


# ── Events / responses (source → sink) ─────────────────────────────────────


@dataclass
class FileList:
    files: list[FileEntry]
    path: str = "/"


@dataclass
class MediaInfo:
    mediaId: str
    httpUrl: str
    duration: float
    mimeType: str
    fileSize: int
    availableQualities: list[QualityEntry] = field(default_factory=list)


@dataclass
class Playing:
    mediaId: str
    position: float = 0.0


@dataclass
class Paused:
    mediaId: str
    position: float


@dataclass
class Seeking:
    position: float


@dataclass
class VolumeChanged:
    volume: float


@dataclass
class QualityChanged:
    maxBitrate: int


@dataclass
class PositionUpdate:
    position: float
    duration: float
    bufferedPercent: int
    state: str  # "playing" | "paused" | "buffering" | "idle"


@dataclass
class ScreenCastStarted:
    port: int = 19000


@dataclass
class ErrorMessage:
    code: str
    message: str


# ── Message union ──────────────────────────────────────────────────────────

ControlMessage = (
    ListFiles | Play | Pause | Resume | Seek | Stop | SetVolume | GetStatus
    | SetQuality | StartScreenCast
    | HelloSource | HelloSink
    | FileList | MediaInfo | Playing | Paused | Seeking | VolumeChanged
    | QualityChanged | PositionUpdate | ScreenCastStarted | ErrorMessage
)

# ── Serialization ──────────────────────────────────────────────────────────


def message_to_json(msg: ControlMessage) -> str:
    """Encode a ControlMessage to a JSON string (mirrors ControlMessage.toJson())."""

    match msg:
        case ListFiles():
            return json.dumps({"cmd": "list_files", "path": msg.path})

        case Play():
            return json.dumps({"cmd": "play", "mediaId": msg.mediaId, "position": msg.position})

        case Pause():
            return json.dumps({"cmd": "pause"})

        case Resume():
            return json.dumps({"cmd": "resume"})

        case Seek():
            return json.dumps({"cmd": "seek", "position": msg.position})

        case Stop():
            return json.dumps({"cmd": "stop"})

        case SetVolume():
            return json.dumps({"cmd": "set_volume", "volume": msg.volume})

        case GetStatus():
            return json.dumps({"cmd": "status"})

        case SetQuality():
            return json.dumps({"cmd": "set_quality", "maxBitrate": msg.maxBitrate})

        case StartScreenCast():
            return json.dumps({"cmd": "start_screen_cast", "port": msg.port})

        # ── Handshake ──

        case HelloSource():
            return json.dumps({
                "cmd": "hello_source",
                "deviceName": msg.deviceName,
                "deviceModel": msg.deviceModel,
                "version": msg.version,
                "capabilities": msg.capabilities,
            })

        case HelloSink():
            return json.dumps({
                "cmd": "hello_sink",
                "deviceName": msg.deviceName,
                "deviceModel": msg.deviceModel,
                "version": msg.version,
                "capabilities": msg.capabilities,
            })

        # ── Events ──

        case FileList():
            files_json = []
            for f in msg.files:
                files_json.append({
                    "id": f.id, "name": f.name, "path": f.path,
                    "size": f.size, "mimeType": f.mimeType, "duration": f.duration,
                })
            return json.dumps({"event": "file_list", "path": msg.path, "files": files_json})

        case MediaInfo():
            quals_json = []
            for q in msg.availableQualities:
                quals_json.append({
                    "label": q.label, "width": q.width,
                    "height": q.height, "bitrate": q.bitrate,
                })
            return json.dumps({
                "event": "media_info", "mediaId": msg.mediaId,
                "httpUrl": msg.httpUrl, "duration": msg.duration,
                "mimeType": msg.mimeType, "fileSize": msg.fileSize,
                "qualities": quals_json,
            })

        case Playing():
            return json.dumps({"event": "playing", "mediaId": msg.mediaId, "position": msg.position})

        case Paused():
            return json.dumps({"event": "paused", "mediaId": msg.mediaId, "position": msg.position})

        case Seeking():
            return json.dumps({"event": "seeking", "position": msg.position})

        case VolumeChanged():
            return json.dumps({"event": "volume_changed", "volume": msg.volume})

        case QualityChanged():
            return json.dumps({"event": "quality_changed", "maxBitrate": msg.maxBitrate})

        case PositionUpdate():
            return json.dumps({
                "event": "position", "position": msg.position,
                "duration": msg.duration, "bufferedPercent": msg.bufferedPercent,
                "state": msg.state,
            })

        case ScreenCastStarted():
            return json.dumps({"event": "screen_cast_started", "port": msg.port})

        case ErrorMessage():
            return json.dumps({"event": "error", "code": msg.code, "message": msg.message})


def parse_message(json_str: str) -> Optional[ControlMessage]:
    """Decode a JSON string back to a ControlMessage (mirrors parseControlMessage())."""
    try:
        obj = json.loads(json_str)
    except json.JSONDecodeError:
        return None

    cmd = obj.get("cmd")
    event = obj.get("event")

    if cmd:
        match cmd:
            case "list_files":
                return ListFiles(path=obj.get("path", "/"))
            case "play":
                return Play(mediaId=obj["mediaId"], position=obj.get("position", 0.0))
            case "pause":
                return Pause()
            case "resume":
                return Resume()
            case "seek":
                return Seek(position=obj["position"])
            case "stop":
                return Stop()
            case "set_volume":
                return SetVolume(volume=float(obj["volume"]))
            case "status":
                return GetStatus()
            case "set_quality":
                return SetQuality(maxBitrate=obj["maxBitrate"])
            case "start_screen_cast":
                return StartScreenCast(port=obj.get("port", 19000))

            case "hello_source":
                return HelloSource(
                    deviceName=obj.get("deviceName", ""),
                    deviceModel=obj.get("deviceModel", ""),
                    version=obj.get("version", 1),
                    capabilities=obj.get("capabilities", ["media", "cast"]),
                )

            case "hello_sink":
                return HelloSink(
                    deviceName=obj.get("deviceName", ""),
                    deviceModel=obj.get("deviceModel", ""),
                    version=obj.get("version", 1),
                    capabilities=obj.get("capabilities", ["playback", "cast"]),
                )

    if event:
        match event:
            case "file_list":
                files = []
                for f in obj.get("files", []):
                    files.append(FileEntry(
                        id=f["id"], name=f["name"], path=f.get("path", "/"),
                        size=f["size"], mimeType=f["mimeType"],
                        duration=f.get("duration", 0.0),
                    ))
                return FileList(files=files, path=obj.get("path", "/"))

            case "media_info":
                quals = []
                for q in obj.get("qualities", []):
                    quals.append(QualityEntry(
                        label=q["label"], width=q["width"],
                        height=q["height"], bitrate=q["bitrate"],
                    ))
                return MediaInfo(
                    mediaId=obj["mediaId"], httpUrl=obj["httpUrl"],
                    duration=obj["duration"], mimeType=obj["mimeType"],
                    fileSize=obj["fileSize"], availableQualities=quals,
                )

            case "playing":
                return Playing(mediaId=obj["mediaId"], position=obj.get("position", 0.0))
            case "paused":
                return Paused(mediaId=obj["mediaId"], position=obj["position"])
            case "seeking":
                return Seeking(position=obj["position"])
            case "volume_changed":
                return VolumeChanged(volume=float(obj["volume"]))
            case "quality_changed":
                return QualityChanged(maxBitrate=obj["maxBitrate"])
            case "position":
                return PositionUpdate(
                    position=obj["position"], duration=obj["duration"],
                    bufferedPercent=obj["bufferedPercent"], state=obj["state"],
                )
            case "screen_cast_started":
                return ScreenCastStarted(port=obj.get("port", 19000))
            case "error":
                return ErrorMessage(code=obj["code"], message=obj["message"])

    return None


# ── Transport layer ────────────────────────────────────────────────────────


def _recv_exact(sock: socket.socket, n: int) -> bytes:
    """Read exactly n bytes from a socket."""
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("Peer closed connection")
        buf.extend(chunk)
    return bytes(buf)


def read_message(sock: socket.socket) -> Optional[str]:
    """
    Read one length-prefixed JSON message from the socket.
    Returns None on graceful close.
    """
    try:
        raw_len = _recv_exact(sock, 4)
        length = struct.unpack("!I", raw_len)[0]
        if length <= 0 or length > MAX_PAYLOAD:
            raise ValueError(f"Invalid payload length: {length}")
        payload = _recv_exact(sock, length)
        return payload.decode("utf-8")
    except (ConnectionError, OSError):
        return None


def send_message(sock: socket.socket, json_str: str) -> None:
    """Send one length-prefixed JSON message on the socket."""
    payload = json_str.encode("utf-8")
    sock.sendall(struct.pack("!I", len(payload)) + payload)


# ── Logging ────────────────────────────────────────────────────────────────

_lock = threading.Lock()


def log(tag: str, msg: str) -> None:
    """Thread-safe timestamped log line."""
    ts = time.strftime("%H:%M:%S.%03d")[:-3] + f"{int(time.time() * 1000) % 1000:03d}"
    with _lock:
        print(f"[{ts}] {tag}: {msg}", flush=True)


def msg_summary(msg: ControlMessage) -> str:
    """Human-readable one-liner for a message."""
    match msg:
        case ListFiles(p):
            return f"ListFiles(path={p!r})"
        case Play(m, pos):
            return f"Play(mediaId={m!r}, position={pos})"
        case Pause():
            return "Pause"
        case Resume():
            return "Resume"
        case Seek(p):
            return f"Seek(position={p})"
        case Stop():
            return "Stop"
        case SetVolume(v):
            return f"SetVolume(volume={v})"
        case GetStatus():
            return "GetStatus"
        case SetQuality(b):
            return f"SetQuality(maxBitrate={b})"
        case StartScreenCast(p):
            return f"StartScreenCast(port={p})"

        case HelloSource(n, m, v, caps):
            cap_str = ",".join(caps)
            return f"HelloSource(name={n!r}, model={m!r}, v{v}, caps=[{cap_str}])"

        case HelloSink(n, m, v, caps):
            cap_str = ",".join(caps)
            return f"HelloSink(name={n!r}, model={m!r}, v{v}, caps=[{cap_str}])"

        case FileList(files=files):
            return f"FileList({len(files)} file(s))"
        case MediaInfo(mediaId=m, httpUrl=url, duration=d):
            return f"MediaInfo(mediaId={m!r}, url={url}, duration={d}s)"
        case Playing(m, p):
            return f"Playing(mediaId={m!r}, position={p})"
        case Paused(m, p):
            return f"Paused(mediaId={m!r}, position={p})"
        case Seeking(p):
            return f"Seeking(position={p})"
        case VolumeChanged(v):
            return f"VolumeChanged(volume={v})"
        case QualityChanged(b):
            return f"QualityChanged(maxBitrate={b})"
        case PositionUpdate(p, d, bp, s):
            return f"PositionUpdate(pos={p:.1f}/{d:.1f}s, buffered={bp}%, state={s})"
        case ScreenCastStarted(p):
            return f"ScreenCastStarted(port={p})"
        case ErrorMessage(c, m):
            return f"Error(code={c!r}, message={m!r})"


# ── Server (sink) ──────────────────────────────────────────────────────────


class ControlServer:
    """
    TCP server that accepts ONE Meracast control connection.
    Mirrors ControlSocket.ControlServer in the Kotlin codebase.
    """

    def __init__(
        self,
        host: str = "0.0.0.0",
        port: int = DEFAULT_PORT,
        auto_respond: bool = False,
        device_name: str = "",
    ):
        self.host = host
        self.port = port
        self.auto_respond = auto_respond
        self.device_name = device_name or socket.gethostname()
        self.peer_device_name = "?"
        self._server_sock: Optional[socket.socket] = None
        self._client_sock: Optional[socket.socket] = None
        self._running = False
        self._reader_thread: Optional[threading.Thread] = None

    def start(self) -> None:
        self._running = True
        self._server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._server_sock.bind((self.host, self.port))
        self._server_sock.listen(1)
        self._server_sock.settimeout(5.0)
        log("SRV", f"Control server listening on {self.host}:{self.port}")

        while self._running:
            try:
                client, addr = self._server_sock.accept()
                self._client_sock = client
                client_ip = addr[0]
                log("SRV", f"Client connected from {client_ip}:{addr[1]}")

                # ── HELLO handshake ──
                log("SRV", "Awaiting HELLO from source...")
                try:
                    client.settimeout(10.0)
                    hello_json = read_message(client)
                    if hello_json is None:
                        log("SRV", "Source disconnected during HELLO")
                        client.close()
                        self._client_sock = None
                        continue
                    hello = parse_message(hello_json)
                    if not isinstance(hello, HelloSource):
                        log("SRV", f"Expected HelloSource, got: {hello_json}")
                        client.close()
                        self._client_sock = None
                        continue
                    self.peer_device_name = hello.deviceName or client_ip
                    log("SRV", f"HELLO from \"{hello.deviceName}\" (model={hello.deviceModel}) v{hello.version}")

                    # Reply with HelloSink
                    reply = HelloSink(
                        deviceName=self.device_name,
                        deviceModel="",
                        version=1,
                        capabilities=["playback", "cast"],
                    )
                    send_message(client, message_to_json(reply))
                    client.settimeout(None)
                    log("SRV", f"HELLO handshake complete with \"{self.peer_device_name}\"")
                except socket.timeout:
                    log("SRV", "HELLO timed out — closing connection")
                    client.close()
                    self._client_sock = None
                    continue

                self._reader_thread = threading.Thread(
                    target=self._read_loop, args=(client,), daemon=True
                )
                self._reader_thread.start()
                self._reader_thread.join()
                log("SRV", f"Client \"{self.peer_device_name}\" disconnected")
            except socket.timeout:
                continue
            except OSError:
                break

        log("SRV", "Server stopped")

    def _read_loop(self, sock: socket.socket) -> None:
        try:
            sock.settimeout(None)  # blocking reads
            while self._running:
                json_str = read_message(sock)
                if json_str is None:
                    break
                msg = parse_message(json_str)
                if msg is not None:
                    log("SRV", f"<<< {msg_summary(msg)}")
                    log("SRV", f"    raw: {json_str}")
                    if self.auto_respond:
                        self._auto_respond(sock, msg)
                else:
                    log("SRV", f"<<< UNPARSEABLE: {json_str}")
                    if self.auto_respond:
                        send_message(sock, json.dumps({
                            "event": "error",
                            "code": "PARSE_ERROR",
                            "message": "Unparseable message",
                        }))
        except (ConnectionError, OSError) as e:
            log("SRV", f"Read loop ended: {e}")
        finally:
            log("SRV", "Connection closed")

    def _auto_respond(self, sock: socket.socket, msg: ControlMessage) -> None:
        match msg:
            case ListFiles():
                reply = FileList(files=[
                    FileEntry("1", "Sample Movie.mp4", "/storage/emulated/0/Movies/sample.mp4",
                              52428800, "video/mp4", 120.0),
                    FileEntry("2", "Song.mp3", "/storage/emulated/0/Music/song.mp3",
                              5242880, "audio/mpeg", 240.0),
                ])
                self._send(reply)

            case Play(mediaId=mid, position=pos):
                log("SRV", f"  → Playing {mid} at {pos}s")
                self._send(Playing(mid, pos))

            case Pause():
                self._send(Paused("media_abc", 30.0))

            case Resume():
                self._send(Playing("media_abc", 30.0))

            case Seek(position=pos):
                self._send(Seeking(pos))

            case Stop():
                log("SRV", "  → Playback stopped")

            case SetVolume(volume=v):
                self._send(VolumeChanged(v))

            case GetStatus():
                self._send(PositionUpdate(
                    position=42.0, duration=120.0,
                    bufferedPercent=85, state="playing",
                ))

            case SetQuality(maxBitrate=b):
                self._send(QualityChanged(b))

            case StartScreenCast(port=p):
                self._send(ScreenCastStarted(p))

            case _:
                log("SRV", f"  → no auto-response for {type(msg).__name__}")

    def _send(self, msg: ControlMessage) -> None:
        if self._client_sock is None:
            return
        try:
            json_str = message_to_json(msg)
            send_message(self._client_sock, json_str)
            log("SRV", f">>> {msg_summary(msg)}")
            log("SRV", f"    raw: {json_str}")
        except OSError as e:
            log("SRV", f"Send failed: {e}")

    def send(self, msg: ControlMessage) -> None:
        self._send(msg)

    def stop(self) -> None:
        self._running = False
        try:
            if self._client_sock:
                self._client_sock.close()
        except OSError:
            pass
        try:
            if self._server_sock:
                self._server_sock.close()
        except OSError:
            pass


# ── Client (source) ────────────────────────────────────────────────────────


class ControlClient:
    """
    TCP client that connects to a Meracast control server.
    Mirrors ControlSocket.ControlClient in the Kotlin codebase.
    """

    def __init__(
        self,
        host: str,
        port: int = DEFAULT_PORT,
        auto_respond: bool = False,
        play_media: bool = False,
        receive_cast: bool = False,
        device_name: str = "",
    ):
        self.host = host
        self.port = port
        self.auto_respond = auto_respond
        self.play_media = play_media
        self.receive_cast = receive_cast
        self.device_name = device_name or socket.gethostname()
        self.peer_device_name = "?"
        self._player: Optional[MediaPlayer] = None
        self._sock: Optional[socket.socket] = None
        self._connected = False
        self._reader_thread: Optional[threading.Thread] = None
        self._recv_count = 0

    def connect(self) -> bool:
        try:
            self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self._sock.settimeout(10.0)
            self._sock.connect((self.host, self.port))
            log("CLI", f"TCP connected to {self.host}:{self.port}")

            # ── HELLO handshake ──
            log("CLI", "Sending HELLO to sink...")
            hello = HelloSource(
                deviceName=self.device_name,
                deviceModel="",
                version=1,
                capabilities=["media", "cast"],
            )
            send_message(self._sock, message_to_json(hello))

            reply_json = read_message(self._sock)
            if reply_json is None:
                log("CLI", "Sink disconnected during HELLO")
                self._sock.close()
                self._sock = None
                return False
            reply = parse_message(reply_json)
            if not isinstance(reply, HelloSink):
                log("CLI", f"Expected HelloSink, got: {reply_json}")
                self._sock.close()
                self._sock = None
                return False

            self.peer_device_name = reply.deviceName or self.host
            self._sock.settimeout(None)  # blocking for reads later
            self._connected = True
            log("CLI", f"HELLO handshake complete with \"{self.peer_device_name}\"")
            self._reader_thread = threading.Thread(
                target=self._read_loop, args=(self._sock,), daemon=True
            )
            self._reader_thread.start()
            return True
        except (OSError, ConnectionError, socket.timeout) as e:
            log("CLI", f"Connection failed: {e}")
            return False

    def _read_loop(self, sock: socket.socket) -> None:
        try:
            while self._connected:
                json_str = read_message(sock)
                if json_str is None:
                    break
                self._recv_count += 1
                msg = parse_message(json_str)
                if msg is not None:
                    log("CLI", f"<<< {msg_summary(msg)}")
                    log("CLI", f"    raw: {json_str}")
                    if self.auto_respond:
                        self._auto_respond(msg)
                else:
                    log("CLI", f"<<< UNPARSEABLE: {json_str}")
        except (ConnectionError, OSError) as e:
            log("CLI", f"Read loop ended: {e}")
        finally:
            self._connected = False
            log("CLI", "Disconnected from server")

    def _auto_respond(self, msg: ControlMessage) -> None:
        match msg:
            case FileList(files=files):
                if files:
                    self.send(Play(files[0].id, 0.0))
            case MediaInfo():
                log("CLI", f"  → Ready to receive media at {msg.httpUrl}")
                if self.play_media:
                    self._start_http_playback(msg.httpUrl)
            case Playing():
                log("CLI", "  → Playback confirmed")
            case ScreenCastStarted():
                log("CLI", f"  → Screen cast started on port {msg.port}")
                if self.receive_cast:
                    self._start_cast_receiver(msg.port)
            case _:
                pass

    def _start_http_playback(self, url: str) -> None:
        """Start HTTP media playback via VLC."""
        if self._player is None:
            self._player = MediaPlayer()
        log("CLI", f"  ▶ Auto-playing media: {url}")
        self._player.play_http(url)

    def _start_cast_receiver(self, port: int) -> None:
        """Start receiving and playing a screen cast stream."""
        if self._player is None:
            self._player = MediaPlayer()
        log("CLI", f"  ▶ Receiving screen cast on port {port}")
        self._player.receive_rtp(port=port, is_rtp=False, use_vlc=True)

    def disconnect(self) -> None:
        """Disconnect and stop any active playback."""
        if self._player:
            self._player.stop()
        self._connected = False
        try:
            if self._sock:
                self._sock.close()
        except OSError:
            pass

    def send(self, msg: ControlMessage) -> None:
        if not self._connected or self._sock is None:
            log("CLI", "Cannot send — not connected")
            return
        try:
            json_str = message_to_json(msg)
            send_message(self._sock, json_str)
            log("CLI", f">>> {msg_summary(msg)}")
            log("CLI", f"    raw: {json_str}")
        except OSError as e:
            log("CLI", f"Send failed: {e}")

    # ── Interactive REPL ───────────────────────────────────────────────────────


def repl_help() -> None:
    print("""
Commands:
  list_files [path=/]
  play <mediaId> [position=0.0]
  pause
  resume
  seek <position>
  stop
  set_volume <0.0-1.0>
  status
  set_quality <maxBitrate>
  start_screen_cast [port=19000]
  send <raw JSON>          Send an arbitrary JSON message
  auto                     Toggle auto-respond mode
  help                     Show this help
  quit / exit              Disconnect and exit

Numbers can be integers or floats.  Omitted optional args use defaults.
""")


def parse_repl_cmd(line: str) -> Optional[ControlMessage]:
    """Parse an interactive command line into a ControlMessage."""
    parts = line.strip().split()
    if not parts:
        return None
    cmd = parts[0].lower()

    try:
        match cmd:
            case "list_files":
                path = parts[1] if len(parts) > 1 else "/"
                return ListFiles(path=path)

            case "play":
                if len(parts) < 2:
                    print("Usage: play <mediaId> [position]")
                    return None
                mid = parts[1]
                pos = float(parts[2]) if len(parts) > 2 else 0.0
                return Play(mediaId=mid, position=pos)

            case "pause":
                return Pause()

            case "resume":
                return Resume()

            case "seek":
                if len(parts) < 2:
                    print("Usage: seek <position>")
                    return None
                return Seek(position=float(parts[1]))

            case "stop":
                return Stop()

            case "set_volume":
                if len(parts) < 2:
                    print("Usage: set_volume <0.0-1.0>")
                    return None
                return SetVolume(volume=float(parts[1]))

            case "status":
                return GetStatus()

            case "set_quality":
                if len(parts) < 2:
                    print("Usage: set_quality <maxBitrate>")
                    return None
                return SetQuality(maxBitrate=int(parts[1]))

            case "start_screen_cast":
                port = int(parts[1]) if len(parts) > 1 else 19000
                return StartScreenCast(port=port)

            case "send":
                raw = line[len(parts[0]):].strip()
                parsed = parse_message(raw)
                if parsed is None:
                    print(f"Cannot parse JSON, sending raw.  Use valid JSON with 'cmd' or 'event' key.")
                return parsed  # type: ignore — caller handles None

            case "quit" | "exit":
                return None  # sentinel

            case "help":
                repl_help()
                return None

            case "auto":
                return None  # sentinel — toggle handled by caller

            case _:
                print(f"Unknown command: {cmd}")
                repl_help()
                return None

    except (ValueError, IndexError) as e:
        print(f"Error parsing command: {e}")
        return None


def run_interactive(sender, toggle_auto_fn) -> None:
    """Run an interactive REPL over the given sender."""
    import select

    print(f"Connected.  Type 'help' for commands, 'quit' to exit.")
    while True:
        try:
            line = input("> ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not line:
            continue

        # Toggle auto mode
        if line.lower() == "auto":
            toggle_auto_fn()
            continue

        msg = parse_repl_cmd(line)
        if msg is None:
            continue  # help, empty, or quit handled inside

        if isinstance(msg, str) and msg == "__QUIT__":
            break

        if isinstance(msg, (Pause, Resume, Stop, GetStatus)):
            sender(msg)
        elif isinstance(msg, StartScreenCast):
            sender(msg)
        elif isinstance(msg, FileList):
            sender(msg)
        else:
            sender(msg)


# ── Main ───────────────────────────────────────────────────────────────────


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Meracast Custom Control Protocol Tester",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    sub = parser.add_subparsers(dest="mode", required=True)

    # Server subcommand
    sp = sub.add_parser("server", help="Run as control protocol server (sink)")
    sp.add_argument("--port", type=int, default=DEFAULT_PORT, help="TCP port")
    sp.add_argument("--auto", action="store_true", help="Auto-respond to messages")
    sp.add_argument("--host", default="0.0.0.0", help="Bind address")

    # Client subcommand
    cp = sub.add_parser("client", help="Run as control protocol client (source)")
    cp.add_argument("host", help="Server hostname or IP")
    cp.add_argument("--port", type=int, default=DEFAULT_PORT, help="TCP port")
    cp.add_argument("--auto", action="store_true", help="Auto-respond to messages")
    cp.add_argument("--play", action="store_true",
                    help="Auto-play HTTP media when MediaInfo received")
    cp.add_argument("--receive-cast", action="store_true",
                    help="Auto-receive and play screen cast streams")
    cp.add_argument("--script", help="Send commands from a file (one per line)")

    args = parser.parse_args()

    if args.mode == "server":
        auto = args.auto
        server = ControlServer(host=args.host, port=args.port, auto_respond=auto)

        def toggle_auto():
            nonlocal auto
            server.auto_respond = not server.auto_respond
            auto = server.auto_respond
            print(f"Auto-respond {'ON' if auto else 'OFF'}")

        # Run server in background
        t = threading.Thread(target=server.start, daemon=True)
        t.start()
        time.sleep(0.3)  # let server bind

        # If we're the server AND there's no client connected, we need to
        # wait until a client arrives before the REPL is useful.
        # The ControlServer.start() blocks on accept() + read loop, so
        # we run the client REPL after server starts.
        print(f"Meracast Sink server on port {args.port}")
        print(f"Auto-respond: {'ON' if auto else 'OFF'}")
        print(f"Waiting for a source to connect...")
        # The server thread blocks on accept inside start(), so the REPL
        # here runs only after a connection is handled.  Instead we give
        # brief overlap by running the REPL on the main thread and letting
        # the server run in the daemon.
        run_interactive(
            lambda msg: server.send(msg),
            toggle_auto,
        )
        server.stop()

    else:
        # Client mode
        client = ControlClient(
            host=args.host,
            port=args.port,
            auto_respond=args.auto,
            play_media=args.play,
            receive_cast=args.receive_cast,
        )

        def toggle_auto():
            client.auto_respond = not client.auto_respond
            print(f"Auto-respond {'ON' if client.auto_respond else 'OFF'}")

        if not client.connect():
            sys.exit(1)

        if args.script:
            # Scripted mode — send commands from a file
            with open(args.script) as f:
                for line in f:
                    line = line.strip()
                    if not line or line.startswith("#"):
                        continue
                    msg = parse_repl_cmd(line)
                    if msg:
                        client.send(msg)
                        time.sleep(0.2)
            time.sleep(1)
        else:
            # Interactive mode
            print(f"Connected to Meracast source at {args.host}:{args.port}")
            print(f"Auto-respond: {'ON' if args.auto else 'OFF'}")
            run_interactive(
                lambda msg: client.send(msg),
                toggle_auto,
            )

        client.disconnect()


if __name__ == "__main__":
    main()
