#!/usr/bin/env python3
"""Remux the original silent colors fixture with changing/gapped bilingual cues."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = root / 'app/src/androidTest/assets/media/tracks.mp4'
source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
if source_hash != 'b82ca9d8eb574b181bd47a09d52ebfde37c0ca723e528b6022fbba26e3a235db':
    raise SystemExit('Original fixture hash changed; review the new source first')
output = source.with_name('paused-captions.mp4')
ffmpeg = shutil.which('ffmpeg')
ffprobe = shutil.which('ffprobe')
if not ffmpeg or not ffprobe:
    raise SystemExit('ffmpeg and ffprobe must be installed')
with tempfile.TemporaryDirectory() as temporary:
    paths = []
    for language, labels in [('eng', ['FIRST', 'SECOND', 'THIRD']), ('fra', ['PREMIER', 'DEUXIÈME', 'TROISIÈME'])]:
        path = Path(temporary) / f'{language}.srt'
        path.write_text('\n\n'.join(f'{i + 1}\n00:00:{start:02d},000 --> 00:00:{end:02d},000\nCMUX {label}'
            for i, (start, end, label) in enumerate(zip([0, 8, 14], [4, 12, 18], labels))) + '\n', encoding='utf-8')
        paths.append(path)
    subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', '-i', str(source),
        '-i', str(paths[0]), '-i', str(paths[1]), '-map', '0:v', '-map', '0:a', '-map', '1:s', '-map', '2:s',
        '-t', '20', '-c:v', 'copy', '-c:a', 'copy', '-c:s', 'mov_text', '-movflags', '+faststart',
        '-metadata:s:s:0', 'language=eng', '-metadata:s:s:1', 'language=fra',
        '-disposition:s:0', '0', '-disposition:s:1', '0', str(output)], check=True)
probe = json.loads(subprocess.check_output([ffprobe, '-v', 'error', '-show_streams', '-show_format', '-of', 'json', str(output)]))
output.with_suffix('.json').write_text(json.dumps({
    'source': 'Original generated colors, silent audio and changing/gapped bilingual text; no third-party media.',
    'generator': 'scripts/generate-paused-caption-fixture.py', 'inputSha256': source_hash,
    'sha256': hashlib.sha256(output.read_bytes()).hexdigest(), 'duration': probe['format']['duration'],
    'streams': [{k: s[k] for k in ['index', 'codec_name', 'codec_type', 'tags'] if k in s} for s in probe['streams']],
}, indent=2, ensure_ascii=False) + '\n')
print(f'Generated {output.stat().st_size} bytes of test-only media')
