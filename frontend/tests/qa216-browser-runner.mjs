import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createServer } from 'vite';

const chrome=['google-chrome','chromium','chromium-browser'].find(x =>
  spawnSync('which',[x],{stdio:'ignore'}).status===0);
assert.ok(chrome,'Chrome required for real next-lesson browser QA');
const dir=await mkdtemp(path.join(os.tmpdir(),'qa216-'));
const server=await createServer({server:{host:'127.0.0.1',port:0}});
let browser,socket;
try {
 await server.listen();const port=server.httpServer.address().port;
 browser=spawn(chrome,['--headless=new','--no-sandbox','--disable-gpu','--disable-dev-shm-usage',
 '--remote-allow-origins=*','--remote-debugging-port=0','--user-data-dir='+dir,'about:blank'],{stdio:'ignore'});
 let debugPort;
 for(let i=0;i<300;i++){try{debugPort=Number((await readFile(path.join(dir,'DevToolsActivePort'),'utf8')).split('\n')[0]);if(debugPort)break;}catch{}
 await new Promise(resolve=>setTimeout(resolve,100));}
 assert.ok(debugPort,'Chrome CDP did not start');
 const tabs=await(await fetch('http://127.0.0.1:'+debugPort+'/json')).json();
 const tab=tabs.find(x=>x.type==='page');assert.ok(tab?.webSocketDebuggerUrl);
 socket=new WebSocket(tab.webSocketDebuggerUrl);await new Promise((resolve,reject)=>{
 socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',reject,{once:true});});
 let serial=0;const pending=new Map();
 socket.addEventListener('message',e=>{const msg=JSON.parse(e.data);if(!msg.id)return;
 const cb=pending.get(msg.id);if(cb){pending.delete(msg.id);msg.error?cb.reject(new Error(msg.error.message)):cb.resolve(msg.result);}});
 const cdp=(method,params={})=>new Promise((resolve,reject)=>{
 const id=++serial;pending.set(id,{resolve,reject});socket.send(JSON.stringify({id,method,params}));});
 const evaluate=async expression=>{
 const r=await cdp('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});
 if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result.value;};
 await cdp('Page.enable');await cdp('Runtime.enable');
 await cdp('Emulation.setDeviceMetricsOverride',{width:360,height:820,deviceScaleFactor:1,mobile:false});
 await cdp('Page.navigate',{url:'http://127.0.0.1:'+port+'/qa216-browser.html'});
 const ready=await evaluate(`new Promise(resolve=>{let i=0;const t=setInterval(()=>{if(window.__qa216Ready){clearInterval(t);resolve(true);}else if(++i>200){clearInterval(t);resolve(false);}},100);})`);
 assert.equal(ready,true,'Real Vue fixture did not mount');
 const shown=await evaluate(`new Promise(resolve=>{let n=0;const timer=setInterval(()=>{
 if(document.body.textContent.includes('Evening Blues')){clearInterval(timer);resolve(true);}
 else if(++n>120){clearInterval(timer);resolve(false);}},50);})`);
 assert.ok(shown,'Approved repertoire should render');
 assert.equal(await evaluate("document.body.textContent.includes('Diagnostic probe')"),false,'Diagnostic probes must not be selectable');
 assert.ok(await evaluate("document.documentElement.scrollWidth<=innerWidth"),'360px viewport overflows');
 const buttonCount=await evaluate("document.querySelectorAll('main button').length");
 assert.ok(buttonCount>0,'Picker must be keyboard-operable');
 await evaluate(`([...document.querySelectorAll('main button')].find(b=>b.textContent.includes('Просмотр')||b.textContent.includes('Preview')||b.textContent.includes('Просмотреть')))?.click()`);
 const hasStart=await evaluate(`new Promise(resolve=>{let n=0;const t=setInterval(()=>{
 const found=[...document.querySelectorAll('main button')].some(b=>b.textContent.includes('Начать занятие')||b.textContent.includes('Start lesson'));
 if(found){clearInterval(t);resolve(true);}else if(++n>80){clearInterval(t);resolve(false);}},50);})`);
 assert.ok(hasStart,'Explicit Start CTA missing after preview');
 await evaluate(`[...document.querySelectorAll('main button')].find(b=>b.textContent.includes('Начать занятие')||b.textContent.includes('Start lesson')).click()`);
 const navigated=await evaluate(`new Promise(resolve=>{let n=0;const t=setInterval(()=>{
 if(document.querySelector('#current-marker')){clearInterval(t);resolve(true);}
 else if(++n>100){clearInterval(t);resolve(false);}},50);})`);
 assert.ok(navigated,'Successful next-lesson POST must navigate to current workspace');
 const posts=await evaluate(`window.__qa216Calls.filter(x=>x.method==='POST'&&x.path.endsWith('/next-lesson'))`);
 assert.equal(posts.length,1,'Exactly one POST should occur');
 const command=JSON.parse(posts[0].body);
 assert.equal(command.exerciseVersion,'0.8B');assert.equal(command.expectedVersion,2);

 assert.match(command.requestId,/^[0-9a-f-]{36}$/i);
 await evaluate("window.__qa216Router.push('/lessons')");
 const historyReady=await evaluate(`new Promise(resolve=>{let i=0;const timer=setInterval(()=>{
 if(document.querySelector('ol li')?.textContent.includes('Evening Blues')){clearInterval(timer);resolve(true);}
 else if(++i>100){clearInterval(timer);resolve(false);}},50);})`);
 assert.ok(historyReady,'Read-only history should display the previously assigned exercise');
 const noHistoryWrites=await evaluate("window.__qa216Calls.filter(x=>x.method==='POST').length");
 assert.equal(noHistoryWrites,1,'Viewing history must not create another session');
 await cdp('Emulation.setDeviceMetricsOverride',{width:180,height:410,deviceScaleFactor:1,mobile:false});
 const zoomWidth=await evaluate("({layout:innerWidth,scroll:document.documentElement.scrollWidth})");
 assert.ok(zoomWidth.scroll<=zoomWidth.layout,'History overflows at effective 200% reflow: '+JSON.stringify(zoomWidth));
 console.log('PASS: Chrome picker, one pinned POST, history read-only, 360px and 200% effective reflow');
} finally {
 socket?.close();browser?.kill('SIGTERM');
 await server.close();await rm(dir,{recursive:true,force:true,maxRetries:12,retryDelay:250}).catch(()=>{});
}
