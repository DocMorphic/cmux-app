#!/usr/bin/env python3
"""Original two-color H.264/AAC stream with landscape/portrait/landscape SPS changes."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
output = root / 'app/src/androidTest/assets/media/adaptive-video.ts'
ffmpeg, ffprobe = shutil.which('ffmpeg'), shutil.which('ffprobe')
if not ffmpeg or not ffprobe:
    raise SystemExit('ffmpeg and ffprobe must be installed')
with tempfile.TemporaryDirectory() as temporary:
    segments = []
    for index, (width, height) in enumerate([(320, 180), (180, 320), (320, 180)]):
        segment = Path(temporary) / f'{index}.h264'
        subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi',
            '-i', f'color=c=0xE2A34A:s={width}x{height}:r=12', '-vf',
            f'drawbox=x={width // 2}:y=0:w={width // 2}:h={height}:color=0x245080:t=fill',
            '-t', '6', '-an', '-c:v', 'libx264', '-preset', 'ultrafast', '-profile:v', 'baseline',
            '-pix_fmt', 'yuv420p', '-x264-params', 'keyint=12:min-keyint=12:scenecut=0:repeat-headers=1:aud=1',
            '-f', 'h264', str(segment)], check=True)
        segments.append(segment)
    elementary = Path(temporary) / 'adaptive.h264'
    elementary.write_bytes(b''.join(segment.read_bytes() for segment in segments))
    # One muxer supplies continuous packet counters and timestamps across SPS
    # changes; concatenating independent TS files would reset their counters.
    subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', '-fflags', '+genpts',
        '-r', '12', '-i', str(elementary), '-f', 'lavfi', '-i', 'anullsrc=r=16000:cl=mono',
        '-map', '0:v:0', '-map', '1:a:0', '-c:v', 'copy', '-c:a', 'aac', '-b:a', '16k', '-t', '18',
        '-muxdelay', '0', '-muxpreload', '0',
        '-f', 'mpegts', str(output)], check=True)
frames = json.loads(subprocess.check_output([ffprobe, '-v', 'error', '-select_streams', 'v:0',
    '-show_frames', '-show_entries', 'frame=best_effort_timestamp_time,width,height', '-of', 'json', str(output)]))['frames']
transitions = []
for frame in frames:
    dimensions = [frame['width'], frame['height']]
    if not transitions or dimensions != transitions[-1]['dimensions']:
        transitions.append({'time': frame['best_effort_timestamp_time'], 'dimensions': dimensions})
if len(frames) != 216 or [entry['dimensions'] for entry in transitions] != [[320, 180], [180, 320], [320, 180]]:
    raise SystemExit(f'Unexpected decoded transitions: {transitions}')
output.with_suffix('.json').write_text(json.dumps({
    'source': 'Original generated gold/blue colors and silent AAC track; no third-party media.',
    'generator': 'scripts/generate-adaptive-video-fixture.py',
    'sha256': hashlib.sha256(output.read_bytes()).hexdigest(),
    'frames': len(frames), 'transitions': transitions,
    'scope': 'Local non-seekable MPEG-TS diagnostic fixture for native dynamic video dimensions; no iOS format claim.',
}, indent=2) + '\n')
print(f'Generated {output.stat().st_size} bytes; {len(frames)} decoded frames; transitions={transitions}')
