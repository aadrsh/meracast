#!/usr/bin/env python3
"""Accurate round-trip test — verifies serialize→parse preserves all fields."""
import sys
import json
sys.path.insert(0, '.')
from meracast_test import *

test_cases = [
    (ListFiles('/videos'), {'cmd': 'list_files', 'path': '/videos'}),
    (Play('test_id', 15.5), {'cmd': 'play', 'mediaId': 'test_id', 'position': 15.5}),
    (Pause(), {'cmd': 'pause'}),
    (Resume(), {'cmd': 'resume'}),
    (Seek(42.0), {'cmd': 'seek', 'position': 42.0}),
    (Stop(), {'cmd': 'stop'}),
    (SetVolume(0.75), {'cmd': 'set_volume', 'volume': 0.75}),
    (GetStatus(), {'cmd': 'status'}),
    (SetQuality(5000000), {'cmd': 'set_quality', 'maxBitrate': 5000000}),
    (StartScreenCast(19002), {'cmd': 'start_screen_cast', 'port': 19002}),
    (HelloSource('Pixel 7', 'Pixel7', 1, ['media','cast']),
     {'cmd': 'hello_source', 'deviceName': 'Pixel 7', 'deviceModel': 'Pixel7', 'version': 1, 'capabilities': ['media','cast']}),
    (HelloSink('Linux PC', 'x86_64', 1, ['playback','cast']),
     {'cmd': 'hello_sink', 'deviceName': 'Linux PC', 'deviceModel': 'x86_64', 'version': 1, 'capabilities': ['playback','cast']}),
    (FileList([FileEntry('1','m.mp4','/m.mp4',100,'video/mp4',60.0)]),
     {'event': 'file_list', 'path': '/', 'files': [{'id': '1','name': 'm.mp4','path': '/m.mp4','size': 100,'mimeType': 'video/mp4','duration': 60.0}]}),
    (MediaInfo('mid','http://x:7238/m',120.0,'video/mp4',1000000,[]),
     {'event': 'media_info', 'mediaId': 'mid', 'httpUrl': 'http://x:7238/m', 'duration': 120.0, 'mimeType': 'video/mp4', 'fileSize': 1000000, 'qualities': []}),
    (Playing('mid',30.0), {'event': 'playing', 'mediaId': 'mid', 'position': 30.0}),
    (Paused('mid',30.0), {'event': 'paused', 'mediaId': 'mid', 'position': 30.0}),
    (Seeking(30.0), {'event': 'seeking', 'position': 30.0}),
    (VolumeChanged(0.5), {'event': 'volume_changed', 'volume': 0.5}),
    (QualityChanged(4000000), {'event': 'quality_changed', 'maxBitrate': 4000000}),
    (PositionUpdate(42.0,120.0,85,'playing'), {'event': 'position', 'position': 42.0, 'duration': 120.0, 'bufferedPercent': 85, 'state': 'playing'}),
    (ScreenCastStarted(19000), {'event': 'screen_cast_started', 'port': 19000}),
    (ErrorMessage('ERR','test error'), {'event': 'error', 'code': 'ERR', 'message': 'test error'}),
]

errors = 0
for msg, expected_json in test_cases:
    # 1. Serialize to JSON
    js = message_to_json(msg)
    # 2. Parse JSON back to dict
    parsed_dict = json.loads(js)
    # Compare with expected dict
    if parsed_dict != expected_json:
        print(f'FAIL JSON: expected={expected_json}, got={parsed_dict}')
        errors += 1
        continue
    # 3. Parse back to message object
    parsed = parse_message(js)
    if parsed is None:
        print(f'FAIL: parse returned None for {js}')
        errors += 1
        continue
    # 4. Re-serialize and compare JSON again
    js2 = message_to_json(parsed)
    if js != js2:
        print(f'FAIL: double round-trip differs:\n  {js}\n  {js2}')
        errors += 1
        continue
    print(f'  OK {type(msg).__name__:20s}  <->  {js}')

print()
if errors == 0:
    print('All 20 message types round-trip correctly (JSON identical)!')
else:
    print(f'{errors} test(s) failed')
