"""Probe the actual image/cgroup, including disk-full, memory kill and a stuck renderer."""
import json
import subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'artifacts/conversion-170e'; OUT.mkdir(parents=True,exist_ok=True)
common=['docker','run','--rm','--pull=never','--network','none','--memory','512m','--memory-swap','512m','--cpus','1','--pids-limit','64','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--tmpfs','/scratch:rw,noexec,nosuid,size=100m,mode=1777','--user','10001:10001']
probe=r'''
const fs=require('fs'),os=require('os'),assert=require('assert');
const read=p=>fs.readFileSync('/sys/fs/cgroup/'+p,'utf8').trim();
const r={uid:process.getuid(),memory:read('memory.max'),cpu:read('cpu.max'),pids:read('pids.max'),interfaces:Object.keys(os.networkInterfaces())};
assert.equal(r.uid,10001); assert.equal(r.memory,'536870912'); assert.equal(r.cpu,'100000 100000'); assert.equal(r.pids,'64'); assert.deepEqual(r.interfaces,['lo']);
assert.throws(()=>fs.writeFileSync('/root-write','x'),e=>['EROFS','EACCES'].includes(e.code));
fs.mkdirSync('/scratch/probe'); const fd=fs.openSync('/scratch/probe/fill','w');
try {for(let i=0;i<101;i++) fs.writeSync(fd,Buffer.alloc(1024*1024,1)); assert.fail('scratch was not capped');}
catch(e) {assert.equal(e.code,'ENOSPC'); r.diskFull=e.code;}
finally {fs.closeSync(fd); fs.unlinkSync('/scratch/probe/fill');}
console.log(JSON.stringify(r));
'''
run=subprocess.run(common+['--entrypoint','node','gm-conversion-worker:170e','-e',probe],capture_output=True,text=True,timeout=15)
assert run.returncode==0,run.stderr
report=json.loads(run.stdout)
# A real allocation beyond the cgroup cap; never runs on the host.
oom=subprocess.run(common+['--entrypoint','node','gm-conversion-worker:170e','-e','const blocks=[]; for(;;) blocks.push(Buffer.alloc(16*1024*1024,1))'],capture_output=True,timeout=20)
assert oom.returncode==137,oom.returncode
report['memoryKillExit']=oom.returncode
# Same independent timeout used by the image entrypoint, with a short test deadline.
hang=subprocess.run(common+['--entrypoint','timeout','gm-conversion-worker:170e','--signal=TERM','--kill-after=1','1','node','-e','for(;;){}'],capture_output=True,timeout=10)
assert hang.returncode==124,hang.returncode
report['hungProcessKillExit']=hang.returncode
(OUT/'isolation-report.json').write_text(json.dumps(report,indent=2)); print(json.dumps(report))
