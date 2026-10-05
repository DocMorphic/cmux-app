#!/usr/bin/env python3
"""Generate original, silent multi-track test media; requires ffmpeg/ffprobe."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
output = root / 'app/src/androidTest/assets/media/tracks.mp4'
output.parent.mkdir(parents=True, exist_ok=True)
ffmpeg = shutil.which('ffmpeg')
ffprobe = shutil.which('ffprobe')
if not ffmpeg or not ffprobe:
    raise SystemExit('ffmpeg and ffprobe must be installed')
with tempfile.TemporaryDirectory() as temporary:
    cues = []
    for name in ['ENGLISH', 'FRENCH']:
        cue = Path(temporary) / f'{name}.srt'
        cue.write_text(f'1\n00:00:00,000 --> 00:02:00,000\nCMUX {name} CUE\n')
        cues.append(cue)
    subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-y',
        '-f', 'lavfi', '-i', 'color=c=0x245080:s=320x180:r=10:d=120,drawbox=x=0:y=0:w=160:h=180:color=0xE2A34A:t=fill',
        '-f', 'lavfi', '-i', 'anullsrc=r=16000:cl=mono',
        '-f', 'lavfi', '-i', 'anullsrc=r=16000:cl=mono',
        '-i', str(cues[0]), '-i', str(cues[1]),
        '-map', '0:v', '-map', '1:a', '-map', '2:a', '-map', '3:s', '-map', '4:s',
        '-t', '120', '-c:v', 'libx264', '-threads', '1', '-preset', 'veryfast', '-pix_fmt', 'yuv420p',
        '-c:a', 'aac', '-b:a', '24k', '-c:s', 'mov_text', '-movflags', '+faststart',
        '-metadata:s:a:0', 'language=eng', '-metadata:s:a:1', 'language=fra',
        '-metadata:s:s:0', 'language=eng', '-metadata:s:s:1', 'language=fra',
        '-disposition:a:0', 'default', '-disposition:a:1', '0',
        '-disposition:s:0', '0', '-disposition:s:1', '0', str(output)], check=True)
probe = json.loads(subprocess.check_output([ffprobe, '-v', 'error', '-show_streams', '-show_format', '-of', 'json', str(output)]))
output.with_suffix('.json').write_text(json.dumps({
    'source': 'Original generated colors, silent audio and plain text cues; no third-party media.',
    'generator': 'scripts/generate-media-track-fixture.py',
    'ffmpeg': subprocess.check_output([ffmpeg, '-version'], text=True).splitlines()[0],
    'sha256': hashlib.sha256(output.read_bytes()).hexdigest(),
    'duration': probe['format']['duration'],
    'streams': [{k: stream[k] for k in ['index', 'codec_name', 'codec_type', 'tags'] if k in stream} for stream in probe['streams']],
}, indent=2) + '\n')
print(f'Generated {output.stat().st_size} bytes of test-only media')
