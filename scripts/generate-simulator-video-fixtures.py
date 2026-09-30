#!/usr/bin/env python3
"""Encode synthetic red/green frames with ffmpeg for Android decoder pixel checks."""
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

WIDTH, HEIGHT = 64, 96
raw = bytes([255, 0, 0]) * WIDTH * HEIGHT + bytes([0, 255, 0]) * WIDTH * HEIGHT
version = subprocess.check_output(['ffmpeg', '-version'], text=True).splitlines()[0]
output = Path('app/src/androidTest/assets/simulator')
output.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='cmux-sim-video-') as folder:
    for codec in ['h264', 'hevc']:
        path = Path(folder) / codec
        options = 'keyint=60:min-keyint=60:scenecut=0:bframes=0:aud=1:repeat-headers=1'
        if codec == 'hevc':
            options += ':pools=1:frame-threads=1:log-level=error'
        command = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgb24',
                   '-s', f'{WIDTH}x{HEIGHT}', '-r', '10', '-i', 'pipe:0', '-frames:v', '2', '-pix_fmt', 'yuv420p',
                   '-c:v', 'libx264' if codec == 'h264' else 'libx265', '-preset', 'ultrafast', '-tune', 'zerolatency',
                   '-x264-params' if codec == 'h264' else '-x265-params', options, '-f', codec, str(path)]
        subprocess.run(command, input=raw, check=True)
        encoded = path.read_bytes()
        nals = [part for part in re.split(b'\x00\x00(?:\x00)?\x01', encoded) if part]
        nal_type = lambda b: (b[0] & 31) if codec == 'h264' else ((b[0] >> 1) & 63)
        parameter_types = [7, 8] if codec == 'h264' else [32, 33, 34]
        parameter_sets = [next(b for b in nals if nal_type(b) == t) for t in parameter_types]
        units, unit = [], []
        for nal in nals:
            kind = nal_type(nal)
            if kind == (9 if codec == 'h264' else 35):
                if unit: units.append(unit)
                unit = []
            elif kind not in parameter_types:
                unit.append(nal)
        if unit: units.append(unit)
        assert len(units) == 2, len(units)
        frames = []
        for index, unit in enumerate(units):
            key = any(nal_type(n) == 5 if codec == 'h264' else nal_type(n) in [19, 20, 21] for n in unit)
            assert key == (index == 0), (codec, index, key)
            data = b''.join(len(n).to_bytes(4, 'big') + n for n in unit)
            frames.append({'sequence': index + 1, 'keyframe': key, 'payload': base64.b64encode(data).decode(),
                           'expected_rgb': [255, 0, 0] if index == 0 else [0, 255, 0]})
        fixture = {'generator': version, 'raw_rgb_sha256': hashlib.sha256(raw).hexdigest(),
                   'annex_b_sha256': hashlib.sha256(encoded).hexdigest(), 'codec': codec,
                   'width': WIDTH, 'height': HEIGHT, 'nal_header_length': 4,
                   'parameter_sets': [base64.b64encode(p).decode() for p in parameter_sets], 'frames': frames}
        (output / f'{codec}.json').write_text(json.dumps(fixture, indent=2) + '\n')
        print(codec, len(encoded), 'bytes; IDR + dependent frame')
