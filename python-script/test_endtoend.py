#!/usr/bin/env python3
"""Quick end-to-end test: connect to Android source, verify handshake + message flow."""
import sys, time
sys.path.insert(0, 'python-script')
from meracast_test import ControlClient, ListFiles

client = ControlClient(host="10.43.100.192", port=7237, auto_respond=False, play_media=False)
if not client.connect():
    print("FAIL: Connection failed")
    sys.exit(1)

print(f"\n=== CONNECTED ===")
print(f"Peer device: {client.peer_device_name}")
print(f"Host: {client.host}:{client.port}")
print()

# Wait for incoming messages (MediaInfo etc.)
print("Waiting for incoming messages...")
time.sleep(2)

print(f"\nMessages received so far: {client._recv_count}")

# Send ListFiles to request available files
print("\nSending ListFiles...")
client.send(ListFiles("/"))
time.sleep(1)
print(f"Total messages received: {client._recv_count}")

client.disconnect()
print("\n=== DONE ===")
