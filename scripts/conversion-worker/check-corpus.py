"""Execute the same worker in a container (CI) or directly (local musical conformance only)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
ROOT=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser(); parser.add_argument('--local',action='store_true'); args=parser.parse_args()
BASE=ROOT/'artifacts/conversion-170e'
cases=json.loads((BASE/'corpus/manifest.json').read_text()); report=[]
expected=json.loads((ROOT/'fixtures/research/catalog-169/expected-events.json').read_text())['events']
canonical=[[e['bar'],i%4,e['string'],e['fret'],e['midi'],960] for i,e in enumerate(expected)]
digest=hashlib.sha256(json.dumps(canonical,separators=(',',':')).encode()).hexdigest()
for case in cases:
    out=BASE/'results'/f"{case['name']}-{case['format']}"; out.mkdir(parents=True,exist_ok=True); out.chmod(0o777)
    source=ROOT/case['path']
    env=os.environ.copy(); env['GM_CONVERSION_FORMAT']=case['format']
    if case['track'] is not None: env['GM_CONVERSION_TRACK_INDEX']=str(case['track'])
    else: env.pop('GM_CONVERSION_TRACK_INDEX',None)
    if args.local:
        env.update(GM_CONVERSION_INPUT=str(source),GM_CONVERSION_OUTPUT=str(out),GM_CONVERSION_STREAM='1')
        command=['node',str(ROOT/'scripts/conversion-worker/worker.mjs')]
    else:
        inp=out/'input'; inp.mkdir(exist_ok=True); inp.chmod(0o755); shutil.copyfile(source,inp/'source.gp'); (inp/'source.gp').chmod(0o644)
        command=['docker','run','--rm','--pull=never','--network','none','--memory','512m','--memory-swap','512m','--cpus','1','--pids-limit','64','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--tmpfs','/scratch:rw,noexec,nosuid,size=100m,mode=1777','--user','10001:10001','--mount',f'type=bind,source={inp},target=/input,readonly','--env',f"GM_CONVERSION_FORMAT={case['format']}",'--env','GM_CONVERSION_STREAM=1']
        if case['track'] is not None: command += ['--env',f"GM_CONVERSION_TRACK_INDEX={case['track']}"]
        command += ['gm-conversion-worker:170e']
    run=subprocess.run(command,env=env,capture_output=True,timeout=75)
    if True: # both local and container runs validate the exact binary output protocol
        import struct
        data=run.stdout; assert data[:8]==b'GM170E01',(case,run.stderr[-200:]); at=8
        for name in ['result.json','score.pdf','preview.wav']:
            length=struct.unpack('>I',data[at:at+4])[0]; at+=4
            assert length<=20*1024*1024
            (out/name).write_bytes(data[at:at+length]); at+=length
        assert at==len(data)
    result=json.loads((out/'result.json').read_text())
    actual=result.get('code',result['status'])
    ok=actual==case['expected']
    if actual=='RENDERED':
        events=case.get('events',canonical)
        case_digest=hashlib.sha256(json.dumps(events,separators=(',',':')).encode()).hexdigest()
        ok=ok and result['eventDigest']==case_digest and result['eventCount']==len(events)
    report.append(dict(name=case['name'],format=case['format'],expected=case['expected'],actual=actual,passed=ok))
    print(case['format'],case['name'],actual,'PASS' if ok else 'FAIL',flush=True)
(BASE/('local-corpus-report.json' if args.local else 'container-corpus-report.json')).write_text(json.dumps(report,indent=2))
assert all(x['passed'] for x in report), 'Conformance failures'
