"""
Meracast mDNS / Zeroconf Discovery
====================================
Registers and discovers _meracast._tcp services on the local network,
so Python devices can find Android sources and vice versa.

Usage
-----
    from meracast_discovery import Discoverer

    # Register this device as a source
    d = Discoverer()
    d.register_source(port=7237)

    # Discover sinks on the network
    for svc in d.discover(timeout=5.0):
        print(f"{svc.name} at {svc.host}:{svc.port}")

    d.close()
"""

import socket
import threading
import time
from dataclasses import dataclass, field
from typing import Callable, Optional

try:
    from zeroconf import (
        Zeroconf, ServiceInfo, ServiceBrowser,
        ServiceStateChange,
    )
    HAVE_ZEROCONF = True
except ImportError:
    HAVE_ZEROCONF = False


SERVICE_TYPE = "_meracast._tcp.local."


@dataclass
class MeracastService:
    """A discovered Meracast device on the network."""
    name: str
    host: str
    port: int
    device_name: str = ""
    device_model: str = ""
    mode: str = "sink"       # "source" or "sink"
    version: str = "1"


class Discoverer:
    """
    Register and discover Meracast devices on the local network
    using mDNS / Zeroconf.
    """

    def __init__(self):
        self._zc: Optional[Zeroconf] = None
        self._browser: Optional[ServiceBrowser] = None
        self._registered: list[ServiceInfo] = []
        self._lock = threading.Lock()

    # ── Registration ─────────────────────────────────────────────────

    def _get_local_ip(self) -> str:
        """Get the local IP address for mDNS registration."""
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
        except Exception:
            return "127.0.0.1"
        finally:
            s.close()

    def register_source(
        self,
        port: int = 7237,
        device_name: str = "",
        device_model: str = "",
    ) -> None:
        """Register this device as a Meracast source on the network."""
        if not HAVE_ZEROCONF:
            print("⚠  zeroconf not installed — cannot register on mDNS")
            return
        name = device_name or socket.gethostname()
        hostname = socket.gethostname().replace(".", "-") + ".local."
        local_ip = self._get_local_ip()

        info = ServiceInfo(
            type_=SERVICE_TYPE,
            name=f"Meracast-{name}.{SERVICE_TYPE}",
            addresses=[socket.inet_aton(local_ip)],
            port=port,
            properties={
                "mode": "source",
                "name": name,
                "model": device_model or name,
                "version": "1",
            },
            server=hostname,
        )

        self._ensure_zc()
        self._zc.register_service(info)
        with self._lock:
            self._registered.append(info)
        print(f"  ✓ Registered as Meracast source \"{name}\" on {local_ip}:{port}")

    def register_sink(
        self,
        port: int = 7236,
        device_name: str = "",
    ) -> None:
        """Register this device as a Meracast sink on the network."""
        if not HAVE_ZEROCONF:
            print("⚠  zeroconf not installed — cannot register on mDNS")
            return
        name = device_name or socket.gethostname()
        local_ip = self._get_local_ip()
        hostname = socket.gethostname().replace(".", "-") + ".local."

        info = ServiceInfo(
            type_=SERVICE_TYPE,
            name=f"Meracast-{name}.{SERVICE_TYPE}",
            addresses=[socket.inet_aton(local_ip)],
            port=port,
            properties={
                "mode": "sink",
                "name": name,
                "model": socket.gethostname(),
                "version": "1",
            },
            server=hostname,
        )

        self._ensure_zc()
        self._zc.register_service(info)
        with self._lock:
            self._registered.append(info)
        print(f"  ✓ Registered as Meracast sink \"{name}\" on {local_ip}:{port}")

    # ── Discovery ────────────────────────────────────────────────────

    def discover(self, timeout: float = 5.0) -> list[MeracastService]:
        """
        Discover Meracast devices on the local network.
        Blocks for *timeout* seconds collecting results.
        """
        if not HAVE_ZEROCONF:
            print("⚠  zeroconf not installed — cannot discover")
            return []

        found: list[MeracastService] = []
        event = threading.Event()

        def on_change(
            zc: Zeroconf,
            type_: str,
            name: str,
            state_change: ServiceStateChange,
        ) -> None:
            if state_change != ServiceStateChange.Added:
                return
            info = zc.get_service_info(type_, name)
            if info is None:
                return
            host = socket.inet_ntoa(info.addresses[0]) if info.addresses else "?"
            props = {k.decode(): v.decode() if isinstance(v, bytes) else v
                     for k, v in (info.properties or {}).items()}
            svc = MeracastService(
                name=info.name.replace(f".{SERVICE_TYPE}", ""),
                host=host,
                port=info.port,
                device_name=props.get("name", ""),
                device_model=props.get("model", ""),
                mode=props.get("mode", "sink"),
                version=props.get("version", "1"),
            )
            with self._lock:
                # Deduplicate
                for existing in found:
                    if existing.host == svc.host and existing.port == svc.port:
                        return
                found.append(svc)
                event.set()

        self._ensure_zc()
        browser = ServiceBrowser(self._zc, SERVICE_TYPE, handlers=[on_change])

        try:
            event.wait(timeout=timeout)
            # Give a little more time for late arrivals
            time.sleep(0.5)
        finally:
            browser.cancel()

        return found

    # ── Lifecycle ────────────────────────────────────────────────────

    def _ensure_zc(self) -> None:
        if self._zc is None:
            self._zc = Zeroconf()

    def close(self) -> None:
        """Unregister all services and close the zeroconf instance."""
        with self._lock:
            for info in self._registered:
                try:
                    self._zc.unregister_service(info)
                except Exception:
                    pass
            self._registered.clear()
        if self._zc:
            try:
                self._zc.close()
            except Exception:
                pass
            self._zc = None
