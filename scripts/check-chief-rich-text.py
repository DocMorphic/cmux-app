#!/usr/bin/env python3
"""Execute pinned whole Swift rich-text semantics and all eight effect motions."""
import argparse
import hashlib
import json
from pathlib import Path
import random
import subprocess


def vectors():
    rng = random.Random(20261025)
    effects = ['big', 'small', 'shake', 'nod', 'explode', 'ripple', 'bloom', 'jitter']
    strings = ['', 'abc', 'aaaaaa', '  hello\n', '\u200b\u00a0a\u202f', 'a😀b', '👩🏽‍💻👨‍👩‍👧‍👦',
               'e\u0301x', 'مرحبا אבג', 'first\nsecond\n', '\r\n\t\u3000', 'a' * 81]
    cases = []
    length = lambda s: len(s.encode('utf-16-le')) // 2
    for i in range(96):
        text = strings[i % len(strings)]
        n = length(text)
        runs = [{'location': rng.randint(-3, n + 3), 'length': rng.randint(-1, n + 4),
                 'style': rng.choice([0, 1, 2, 3, 4, 8, 15, 32, 33, (1 << 40) | 3, (1 << 63) - 1, -(1 << 63)]), 'effect': rng.choice([None] + effects)}
                for _ in range(1 + i % 7)]
        # Include erased/overlapping runs, split surrogate/combining/ZWJ boundaries, and plain overrides.
        if i % 3 == 0:
            runs = [{'location': 0, 'length': n, 'style': 3, 'effect': 'ripple'}] + runs
        start = rng.randint(0, n)
        count = rng.randint(0, n - start)
        actions = [{'style': v} for v in [1, 1, 2, 4, 8, 3, 32, 1 << 40]]
        actions += [{'effect': e} for e in effects for _ in range(2)]
        cases.append(dict(name=f'case-{i}', text=text, runs=runs, start=start, length=count,
                          newText=rng.choice([text, 'prefix' + text, text + 'suffix', 'replacement', '', text[:len(text)//2]]),
                          actions=actions))
    # Explicit uniform selection, removal and mixed selection fixtures independent of random coverage.
    for name, text, start, count, runs in [
        ('mixed-styles', 'abcd', 0, 4, [dict(location=0, length=2, style=1, effect=None)]),
        ('mixed-effects', 'abcd', 0, 4, [dict(location=0, length=2, style=3, effect='nod'), dict(location=2, length=2, style=2, effect='ripple')]),
        ('outside-upper', 'abc', 2, 5, [dict(location=0, length=3, style=15, effect='bloom')]),
    ]:
        cases.append(dict(name=name, text=text, start=start, length=count, runs=runs, newText='  ' + text + '\n',
                          actions=[dict(style=1), dict(style=1)] + [dict(effect=e) for e in effects for _ in range(2)]))
    # Inputs from upstream ConversationRichTextTests; generic wire/Store cases are separate contracts.
    for name, text, new, runs in [
        ('source-edit-middle', 'make it bold now', 'make this bold now',
         [dict(location=0, length=4, style=1, effect=None), dict(location=13, length=3, style=0, effect='explode')]),
        ('source-retype-start', 'make it bold now', 'take it bold now',
         [dict(location=0, length=4, style=1, effect=None), dict(location=13, length=3, style=0, effect='explode')]),
        ('source-send-trim', '  hi there \n', 'hi there',
         [dict(location=0, length=4, style=1, effect=None), dict(location=5, length=7, style=0, effect='bloom')]),
        ('source-round-trip', 'hello world', 'hello world',
         [dict(location=0, length=3, style=9, effect=None), dict(location=3, length=2, style=1, effect='nod')]),
        ('source-emoji-offset', '👋🏽 wave', '👋🏽 wave', [dict(location=5, length=4, style=0, effect='explode')]),
    ]:
        cases.append(dict(name=name, text=text, newText=new, runs=runs, start=0, length=length(text),
                          actions=[dict(style=v) for v in [1, 1, 2, 4, 8, 3, 32]] +
                                  [dict(effect=e) for e in effects for _ in range(2)]))
    motion = []
    # Boundaries, 60 Hz points and off-frame points; large runs compress stagger; UInt64 wraparound seeds.
    for effect in effects:
        for index, count in [(0, 0), (0, 1), (1, 3), (79, 80), (80, 81), (999, 1000)]:
            for time in [-.01, 0, 1/60, .12345, .35, .55, .7, .8, .9, 1.1, 1.2, 1.5, 2.99, 3.0]:
                motion.append(dict(effect=effect, index=index, count=count, time=time,
                                   seed=rng.choice(['0', '1', '18446744073709551615', '14695981039346656037'])))
    keyframes = [dict(effect=e, index=i, count=n, fontSize=size, seed=seed)
                 for e in effects for i, n in [(0, 1), (3, 8), (80, 81)]
                 for size, seed in [(17, '0'), (13, '18446744073709551615'), (34.5, '14695981039346656037')]]
    return dict(richText=cases, motion=motion, keyframes=keyframes,
                seeds=['', 'big', 'row-1', 'مرحبا', '👩🏽‍💻', 'e\u0301', '\x00'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--vectors', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    manifest = json.loads((root / 'third_party/cmux-chief/manifest.json').read_text())
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    executable_sources = []
    rendering = None
    for path, digest in manifest['richTextFiles'].items():
        data = subprocess.check_output(['git', '-C', str(args.upstream.resolve()), 'show', manifest['revision'] + ':' + path])
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError('Unreviewed rich-text source: ' + path)
        if Path(path).name in ['ConversationRichText.swift', 'ConversationTextEffectMotion.swift', 'ConversationRichTextRendering.swift']:
            target = out / Path(path).name
            target.write_bytes(data)
            if target.name == 'ConversationRichTextRendering.swift':
                rendering = target
            else:
                executable_sources.append(str(target))
    command = ['/usr/bin/swiftc', '-swift-version', '6', '-emit-library', '-emit-module',
               '-module-name', 'CmuxConversationCore', *executable_sources,
               '-emit-module-path', str(out / 'CmuxConversationCore.swiftmodule'), '-o', str(out / 'libCmuxConversationCore.dylib')]
    result = subprocess.run(command, capture_output=True, timeout=90)
    (out / 'core-build.log').write_bytes(result.stdout + result.stderr)
    result.check_returncode()
    assert rendering is not None
    command = ['/usr/bin/swiftc', '-swift-version', '6', '-I', str(out), '-L', str(out), '-lCmuxConversationCore',
               '-Xlinker', '-rpath', '-Xlinker', str(out), str(rendering),
               str(root / 'scripts/chief-rich-text-fixture.swift'), '-o', str(out / 'fixture')]
    result = subprocess.run(command, capture_output=True, timeout=90)
    (out / 'build.log').write_bytes(result.stdout + result.stderr)
    result.check_returncode()
    inputs = json.dumps(vectors(), ensure_ascii=False).encode()
    (out / 'inputs.json').write_bytes(inputs)
    result = subprocess.run([str(out / 'fixture')], input=inputs, capture_output=True, timeout=30)
    (out / 'run.stderr').write_bytes(result.stderr)
    result.check_returncode()
    reference = json.loads(result.stdout)
    encoded = (json.dumps(reference, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n').encode()
    (out / 'vectors.json').write_bytes(encoded)
    if args.vectors:
        args.vectors.parent.mkdir(parents=True, exist_ok=True)
        args.vectors.write_bytes(encoded)
    elif encoded != (root / 'app/src/test/resources/parity/chief-rich-text.json').read_bytes():
        raise ValueError('Original rich-text vectors differ')
    receipt = dict(revision=manifest['revision'], sourcePins=manifest['richTextFiles'],
                   compiler=subprocess.check_output(['/usr/bin/swiftc', '--version'], text=True).strip(),
                   generatorSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                   driverSha256=hashlib.sha256((root / 'scripts/chief-rich-text-fixture.swift').read_bytes()).hexdigest(),
                   inputsSha256=hashlib.sha256(inputs).hexdigest(),
                   vectorsSha256=hashlib.sha256(encoded).hexdigest(), richTextCases=len(reference['richText']),
                   mutationSteps=sum(len(row['actions']) for row in reference['richText']),
                   motionProbes=len(reference['motion']), seedCases=len(reference['seeds']),
                   keyframeTracks=len(reference['keyframes']),
                   transformFrames=sum(len(row['transforms']) for row in reference['keyframes']),
                   opacityFrames=sum(len(row['opacities']) for row in reference['keyframes']),
                   scope='Whole unmodified Swift core and shared rendering file; executes Foundation semantic keys, eight pure pose functions and original 60 Hz keyframe generation with real QuartzCore matrices. UIKit formatting/host/backend files hash checked for applicability only. Not UIKit typing, composed glyph/word segmentation, actual animation rendering, Compose, IME, undo, retained draft, backend or device acceptance.')
    (out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
