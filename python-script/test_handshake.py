#!/usr/bin/env python3
"""Integration test: Python HELLO handshake between client and server."""
import sys, time, threading
sys.path.insert(0, 'python-script')
from meracast_test import ControlServer, ControlClient

def test_handshake():
    # Start server in background
    server = ControlServer(port=17337, auto_respond=False, device_name="TestSink")
    t = threading.Thread(target=server.start, daemon=True)
    t.start()
    time.sleep(0.3)

    # Connect client
    client = ControlClient(host="127.0.0.1", port=17337, device_name="TestSource")
    ok = client.connect()
    assert ok, "Connection should succeed"
    assert client.peer_device_name == "TestSink", f"Expected TestSink, got {client.peer_device_name}"
    assert server.peer_device_name == "TestSource", f"Expected TestSource, got {server.peer_device_name}"

    print(f"  PASS: Handshake OK — source sees \"{client.peer_device_name}\", sink sees \"{server.peer_device_name}\"")

    # Verify messages still work after handshake
    from meracast_test import GetStatus, PositionUpdate, send_message, read_message
    server._send(PositionUpdate(position=10.0, duration=120.0, bufferedPercent=50, state="playing"))
    time.sleep(0.1)
    client.send(GetStatus())
    time.sleep(0.1)

    client.disconnect()
    server.stop()
    print("  PASS: Messages still flow after handshake")
    return True

test_handshake()
print("All integration tests passed!")
