import assert from 'node:assert/strict';
import {spawn,spawnSync} from 'node:child_process';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {createServer} from 'vite';

const chrome=['google-chrome','chromium','chromium-browser'].find(name=>
  spawnSync('which',[name],{stdio:'ignore'}).status===0);
assert.ok(chrome,'Chrome/Chromium needed for score reader integration');
const temp=await mkdtemp(path.join(os.tmpdir(),'gm-score-browser-'));
const vite=await createServer({server:{host:'127.0.0.1',port:0,strictPort:false}});
let browser,socket;
try {
  await vite.listen();
  const port=vite.httpServer.address().port;
  browser=spawn(chrome,['--headless=new','--no-sandbox','--disable-gpu',
    '--disable-dev-shm-usage','--remote-allow-origins=*',
    '--remote-debugging-port=0','--user-data-dir='+temp,'about:blank'],{stdio:'ignore'});
  let debugPort;
  for(let n=0;n<300;n++){
    try {
      debugPort=Number((await readFile(path.join(temp,'DevToolsActivePort'),'utf8')).split('\n')[0]);
      if(debugPort)break;
    }catch{}
    await new Promise(resolve=>setTimeout(resolve,100));
  }
  assert.ok(debugPort,'Chrome debugging socket never started');
  const tabs=await(await fetch('http://127.0.0.1:'+debugPort+'/json')).json();
  const tab=tabs.find(x=>x.type==='page');
  assert.ok(tab?.webSocketDebuggerUrl);
  socket=new WebSocket(tab.webSocketDebuggerUrl);
  await new Promise((resolve,reject)=>{
    socket.addEventListener('open',resolve,{once:true});
    socket.addEventListener('error',reject,{once:true});
  });
  let serial=0;
  const requests=new Map();
  socket.addEventListener('message',e=>{
    const msg=JSON.parse(e.data);
    const task=requests.get(msg.id);
    if(!task)return;
    requests.delete(msg.id);
    msg.error?task.reject(new Error(msg.error.message)):task.resolve(msg.result);
  });
  const cdp=(method,params={})=>new Promise((resolve,reject)=>{
    const id=++serial;requests.set(id,{resolve,reject});
    socket.send(JSON.stringify({id,method,params}));
  });
  async function evaluate(expression){
    const answer=await cdp('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});
    if(answer.exceptionDetails)throw Error(JSON.stringify(answer.exceptionDetails));
    return answer.result.value;
  }
  await cdp('Page.enable');await cdp('Runtime.enable');
  await cdp('Emulation.setDeviceMetricsOverride',{width:1200,height:900,deviceScaleFactor:1,mobile:false});
  await cdp('Page.navigate',{url:'http://127.0.0.1:'+port+'/score-reader-test.html'});
  const boot=await evaluate(`new Promise(resolve=>{let tries=0;const timer=setInterval(()=>{
    if(window.__scoreHarnessReady){clearInterval(timer);resolve(true);}
    if(++tries>200){clearInterval(timer);resolve(false);}
  },100)})`);
  assert.ok(boot,'score harness failed to start');
  assert.equal(await evaluate('window.__scoreRequests()'),0,'closed PDF must never prefetch');
  await evaluate("document.querySelector('button.score-toggle').click()");
  const loaded=await evaluate(`new Promise(resolve=>{let tries=0;const timer=setInterval(()=>{
    const root=document.querySelector('.score-reader');
    if(root?.dataset.renderedPage==='1'){clearInterval(timer);resolve({ok:true});}
    if(root?.querySelector('[role=alert]') || ++tries>250){
      clearInterval(timer);resolve({ok:false,error:root?.querySelector('[role=alert]')?.textContent,html:root?.outerHTML?.slice(0,500)});
    }
  },100)})`);
  assert.ok(loaded.ok,'actual isolated PDF canvas must render: '+JSON.stringify(loaded));
  assert.equal(await evaluate('window.__scoreRequests()'),2,'viewer performs advisory HEAD and bounded GET only');
  assert.equal(await evaluate("document.querySelector('.score-pagination input').value"),'1');
  assert.equal(await evaluate("document.querySelector('.score-pagination span').textContent.trim()"),'/ 1');
  assert.equal(await evaluate("document.querySelector('.score-pagination button').disabled"),true);
  await evaluate("(() => { const input=document.querySelector('.score-pagination input');input.value='0';input.dispatchEvent(new Event('input',{bubbles:true}));input.dispatchEvent(new Event('change',{bubbles:true})); })()");
  assert.equal(await evaluate("document.querySelector('.score-pagination input').getAttribute('aria-invalid')"),'true');
  assert.ok(await evaluate("document.querySelector('.score-page-error')!==null"),'out of range page must be announced');
  await evaluate("(() => { const input=document.querySelector('.score-pagination input');input.value='1';input.dispatchEvent(new Event('input',{bubbles:true}));input.dispatchEvent(new Event('change',{bubbles:true})); })()");
  assert.equal(await evaluate("document.querySelector('.score-pagination input').getAttribute('aria-invalid')"),'false');
  assert.equal(await evaluate("document.querySelectorAll('.score-reader iframe[sandbox=\"allow-scripts\"]').length"),1);
  assert.equal(await evaluate("document.querySelector('.score-zoom span').textContent.trim()"),'По ширине');
  await evaluate("document.querySelector('.score-zoom button:nth-child(3)').click()");
  assert.equal(await evaluate("document.querySelector('.score-zoom span').textContent.trim()"),'125%');
  await evaluate("document.querySelector('button.score-focus').click()");
  assert.equal(await evaluate("document.querySelector('.score-reader').classList.contains('score-fullscreen')"),true);
  await cdp('Input.dispatchKeyEvent',{type:'keyDown',key:'Escape',code:'Escape',windowsVirtualKeyCode:27});
  await cdp('Input.dispatchKeyEvent',{type:'keyUp',key:'Escape',code:'Escape',windowsVirtualKeyCode:27});
  assert.equal(await evaluate("document.querySelector('.score-reader').classList.contains('score-fullscreen')"),false);
  for(const width of [1200,768,360]){
    await cdp('Emulation.setDeviceMetricsOverride',{width,height:820,deviceScaleFactor:1,mobile:false});
    const dimensions=await evaluate('({width:innerWidth,scrollWidth:document.documentElement.scrollWidth})');
    assert.ok(dimensions.scrollWidth<=dimensions.width,'body overflow at '+width+': '+JSON.stringify(dimensions));
  }
  await evaluate("document.querySelector('button.score-toggle').click()");
  assert.equal(await evaluate("document.querySelector('iframe.score-canvas-frame')===null"),true);
  process.stdout.write(JSON.stringify({status:'PASS',rendered:true,headGetOnly:true,fullscreenEscape:true,responsive:[1200,768,360]},null,2)+'\n');
}finally{
  socket?.close();
  if(browser && browser.exitCode===null)browser.kill('SIGTERM');
  await vite.close();
  await rm(temp,{recursive:true,force:true,maxRetries:3,retryDelay:100});
}
