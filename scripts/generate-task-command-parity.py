#!/usr/bin/env python3
"""Run pinned, unmodified cmux provider option rewriting to generate JVM reference cases."""
import gzip, hashlib, json, subprocess, sys, tempfile
from pathlib import Path
PIN='4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0'
REL='Packages/iOS/CmuxMobileShellModel/Sources/CmuxMobileShellModel/MobileTaskAgentProvider.swift'
root=Path(__file__).resolve().parents[1]
source=subprocess.check_output(['git','-C',sys.argv[1],'show',f'{PIN}:{REL}'])
commands=[]
for agent in ['claude','codex','opencode']:
    for suffix in ['', ' -- "$CMUX_TASK_PROMPT"', ' --model old --model=again -m short',
                   ' --model "old value" --prompt \'--model untouched\'', ' --model old; echo --model untouched',
                   ' --model=old&&next --model untouched', ' --model; echo next', ' --model --',
                   ' --model\nnext --model untouched', ' --model', '  # --model untouched',
                   ' -- \'--model old\'', ' 2>&1 --model old >|log &>file',
                   ' --model "a; | b" --prompt "quoted \\\"end"',
                   ' -c model_reasoning_effort=low --config=model_reasoning_effort=high',
                   ' --config model_reasoning_effort="old effort"; next',
                   ' -c other=value --model=old|cat', ' --model \'unterminated',
                   ' --prompt first\\ word -m old', ' --model old\r\nnext',
                   ' --model old\u2028next', ' --prompt 中👩🏽‍💻 --model old']:
        commands += [agent+suffix, ' \t/opt/tools/'+agent+suffix]
commands += ['', 'echo claude --model old', 'ENV=x codex --model old', '"codex" --model old',
             "'claude' --model old", 'codex-not --model old', ' # claude --model old']
cases=[dict(command=c, model=m, effort=e) for c in commands for m,e in
       [(None,None),('test/model',None),(None,'high'),("a' b;$HOME",'xhigh'),('中/🧪',"a'b")]]
driver=r'''
import Foundation
let input = try JSONSerialization.jsonObject(with: FileHandle.standardInput.readDataToEndOfFile()) as! [[String: Any]]
let output = input.map { row -> [String: Any] in
    let command = row["command"] as! String
    let provider = MobileTaskAgentProvider(command: command)
    var result = command
    if let provider {
        if let model = row["model"] as? String { result = provider.command(applying: model, to: result) }
        if let effort = row["effort"] as? String { result = provider.command(applyingEffort: effort, to: result) }
    }
    var value = row
    value["provider"] = provider?.rawValue ?? NSNull() as Any
    value["expected"] = result
    return value
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: output, options: [.sortedKeys]))
'''
with tempfile.TemporaryDirectory() as temp:
    temp=Path(temp); (temp/'Provider.swift').write_bytes(source); (temp/'main.swift').write_text(driver)
    subprocess.run(['swiftc',str(temp/'Provider.swift'),str(temp/'main.swift'),'-o',str(temp/'reference')],check=True)
    expected=json.loads(subprocess.check_output([str(temp/'reference')],input=json.dumps(cases).encode()))
result=dict(upstream=PIN,source=REL,sha256=hashlib.sha256(source).hexdigest(),cases=expected)
output=root/'app/src/test/resources/tasks/ios-commands.json.gz'; output.parent.mkdir(parents=True,exist_ok=True)
output.write_bytes(gzip.compress(json.dumps(result,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode(),mtime=0))
print(f'Generated {len(expected)} provider/command cases from unmodified Swift')
