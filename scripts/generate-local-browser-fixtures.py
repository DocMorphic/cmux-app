#!/usr/bin/env python3
"""Export address-bar results from the unmodified pinned Swift resolver."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

PIN = '4c5272e9153eca2033c9f40ac749f0c3a5bcb291'
SOURCE = 'Packages/iOS/CmuxMobileBrowser/Sources/CmuxMobileBrowser/BrowserURLResolver.swift'
p = argparse.ArgumentParser()
p.add_argument('--source', required=True, type=Path)
p.add_argument('--output', type=Path, default=Path('app/src/test/resources/browser/local-addresses.json'))
args = p.parse_args()
oauth = ('https://auth.example.com/oauth/authorize?client_id=fixture_app'
         '&redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback'
         '&response_type=code&scope=openid%20profile%20email%20offline_access'
         '&code_challenge=abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ'
         '&code_challenge_method=S256&state=fixture_state&codex_cli_simplified_flow=true')
inputs = [
    '', '   ', '\n\t', '\u00a0\u2003', 'https://example.com/path?q=1', oauth,
    oauth.replace('&scope=', '&\nscope='), oauth.replace('&scope=', '&\tscope='),
    '  \n\t' + oauth + '\r\n  ', 'https://example.com/search?q=hello world', 'a b.com/path?x=1',
    'go\texample.com/path', 'go\nexample.com/path', 'https://trusted.example\n@evil.example/path',
    'https://trusted.example @evil.example/path', 'trusted.example\n@evil.example/path',
    'http://example.com', 'example.com', 'example.com/docs/page', 'localhost:3000',
    'example.com/path?x=1', 'example.\ncom/path?x=1', 'example.com/path?\nx=1',
    'node.js tutorial', 'node.js\ttutorial', '127.0.0.1:8080', '192.168.1.10', '10.0.0.5',
    '172.16.0.1', '8.8.8.8', 'localhost.evil.com', 'localhost:80@evil.example/path',
    '127.0.0.1:80@evil.example', 'example.com/path?email=user@example.com', '::1', '[::1]:3000',
    'how to write swift', 'swift', '  example.com  ', 'javascript:alert(1)', 'AT&T earnings',
    'C++ tutorial', 'foo.', '.foo', 'foo..example', 'hello 😀', '日本語で検索', 'x=1&y=2?#z',
    'example.com/path?\r\nx=1', 'https://example.com/pa\u2028th?q=1',
    'https://exa\tmple.com/path', 'https://example.com/path\n?q=1', 'example.com\n/path',
    'example.com/\tpath', 'example.com?\nq=hello', 'example.com#\tfragment',
    'https://example.com/a%20b?x=%26&y=%2f', 'https://example.com/a b?q=a b#c d',
    'https://example.com/über?q=é', 'https://bücher.example/straße', 'bücher.example/straße',
    'HTTPS://EXAMPLE.COM:443/path', 'localhost', 'LOCALHOST:8080', 'test.localhost',
    '127.255.0.2', '172.15.255.1', '172.31.255.1', '172.32.0.1', '192.168.1.256',
    '10.0.0.1/path?q=1', '[::1]/path', '[::1]:8080/path', '2001:db8::1',
    '[2001:db8::1]:443/path', 'localhost:3000/path#fragment',
    'https://', 'http://', 'https://example.com:abc/path', 'http:/example.com',
    'javascript://example.com/path', 'file:///tmp/example.txt', 'data:text/html,hello',
    'mailto:person@example.com', 'https://user:pass@example.com/path',
    'https://example.com/%zz', 'https://example.com/100%', 'https://example.com/[a]?x=[b]',
    'https://example.com/path\\name', 'example.com\\@evil.example',
    'https://example.com?x=one\u0085two', 'foo bar/baz:!$()*;,[]',
    'https://faß.de/', 'faß.de', 'https://ς.example/', 'https://é.example/',
    'https://例え.テスト/', 'https://example.com:99999/', 'https://example.com:/',
    'https://exa_mple.com/', 'https://foo..example/path',
]
cases = [{'input': text} for text in inputs]
cases += [{'input': 'hello world', 'template': 'https://search.example/q=%@'},
          {'input': 'a&b+c?#d', 'template': 'https://search.example/?q=%@&lang=en'}]
main = r'''
import Foundation
let samples = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))) as! [[String: String]]
let output: [[String: Any]] = samples.map { sample in
    let input = sample["input"]!
    let template = sample["template"] ?? BrowserURLResolver.defaultSearchTemplate
    let url = BrowserURLResolver.resolve(input, searchTemplate: template)
    var result: [String: Any] = ["input": input, "expected": url?.absoluteString as Any? ?? NSNull()]
    if let value = sample["template"] { result["template"] = value }
    return result
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: output, options: [.sortedKeys]))
'''
with tempfile.TemporaryDirectory(prefix='cmux-browser-golden-') as folder:
    tmp = Path(folder)
    source = subprocess.check_output(['git', '-C', str(args.source), 'show', PIN + ':' + SOURCE])
    (tmp / 'BrowserURLResolver.swift').write_bytes(source)
    (tmp / 'main.swift').write_text(main)
    (tmp / 'inputs.json').write_text(json.dumps(cases))
    subprocess.run(['xcrun', 'swiftc', str(tmp / 'BrowserURLResolver.swift'), str(tmp / 'main.swift'), '-o', str(tmp / 'export')], check=True)
    output = json.loads(subprocess.check_output([str(tmp / 'export'), str(tmp / 'inputs.json')]))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({'upstream': PIN, 'source_sha256': {SOURCE: hashlib.sha256(source).hexdigest()},
                                      'cases': output}, indent=2, ensure_ascii=False) + '\n')
    print(f'Exported {len(output)} Swift address fixtures to {args.output}')
