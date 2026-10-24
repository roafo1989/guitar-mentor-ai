import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createServer } from 'vite';

const chrome = ['google-chrome', 'chromium', 'chromium-browser'].find(name =>
  spawnSync('which', [name], { stdio: 'ignore' }).status === 0);
assert.ok(chrome, 'Chromium/Chrome is required for the real-browser regression suite');
const dir = await mkdtemp(path.join(os.tmpdir(), 'qa126-chrome-'));
const server = await createServer({server:{host:'127.0.0.1',port:0,strictPort:false}});
let browser;
let socket;
try {
  await server.listen();
  const port = server.httpServer.address().port;
  browser = spawn(chrome, [
    '--headless=new','--no-sandbox','--disable-gpu','--disable-dev-shm-usage',
    '--no-first-run','--no-default-browser-check',
    '--remote-allow-origins=*','--remote-debugging-port=0',
    '--user-data-dir='+dir,'about:blank'
  ],{stdio:'ignore'});
  let debugPort;
  for(let i=0;i<100;i++) {
    try {
      debugPort = Number((await readFile(path.join(dir,'DevToolsActivePort'),'utf8')).split('\n')[0]);
      if(debugPort)break;
    } catch {}
    await new Promise(resolve=>setTimeout(resolve,100));
  }
  assert.ok(debugPort,'Chrome DevTools port did not start');
  const tabs=await (await fetch('http://127.0.0.1:'+debugPort+'/json')).json();
  const tab=tabs.find(x=>x.type==='page');
  assert.ok(tab?.webSocketDebuggerUrl,'Cannot connect to Chrome page');
  socket=new WebSocket(tab.webSocketDebuggerUrl);
  await new Promise((resolve,reject)=>{
    socket.addEventListener('open',resolve,{once:true});
    socket.addEventListener('error',reject,{once:true});
  });
  let serial=0;
  const pending=new Map();
  socket.addEventListener('message',event=>{
    const message=JSON.parse(event.data);
    if(!message.id)return;
    const callback=pending.get(message.id);
    if(callback){pending.delete(message.id);message.error?callback.reject(new Error(message.error.message)):callback.resolve(message.result);}
  });
  const cdp=(method,params={})=>new Promise((resolve,reject)=>{
    const id=++serial;pending.set(id,{resolve,reject});
    socket.send(JSON.stringify({id,method,params}));
  });
  const evaluate=async expression=>{
    const result=await cdp('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});
    if(result.exceptionDetails)throw new Error(JSON.stringify(result.exceptionDetails));
    return result.result.value;
  };
  await cdp('Page.enable');
  await cdp('Runtime.enable');
  await cdp('Emulation.setDeviceMetricsOverride',{width:360,height:820,deviceScaleFactor:1,mobile:false});
  await cdp('Page.navigate',{url:'http://127.0.0.1:'+port+'/qa126-browser.html'});
  const ready=await evaluate(`new Promise(resolve => {
    let n=0;const timer=setInterval(()=>{
      if(window.__qa126Ready===true){clearInterval(timer);resolve(true);}
      else if(++n>=150){clearInterval(timer);resolve(false);}
    },100);
  })`);
  assert.equal(ready,true,'Actual Vue panels failed to render');
  const layout=()=>evaluate(`({
    width:innerWidth,
    scrollWidth:document.documentElement.scrollWidth,
    nav:!!document.querySelector('.homework-history nav[aria-label]'),
    pageRows:document.querySelectorAll('.homework-history .attempt-card').length,
    selector:!!document.querySelector('select#learning-attempt'),
    labels:[...document.querySelectorAll('.homework-history nav button')].map(n=>n.textContent.trim()),
    alerts:[...document.querySelectorAll('[role="alert"]')].length
  })`);
  const compact=await layout();
  assert.equal(compact.width,360,'Chrome did not apply 360 CSS pixel viewport');
  assert.equal(compact.pageRows,20,'Actual template should render exactly 20 page entries');
  assert.ok(compact.nav && compact.selector,'Semantic navigation and labelled attempt select required');
  assert.equal(compact.labels.length,4);
  assert.ok(compact.scrollWidth<=compact.width,'Horizontal overflow at 360 CSS px: '+JSON.stringify(compact));
  await cdp('Page.bringToFront');
  await cdp('Emulation.setFocusEmulationEnabled',{enabled:true});
  // Expand native details, then ensure the keyboard can move between enabled
  // history actions without scripts swapping focus.
  const startingFocus=await evaluate(`(() => {
    document.querySelector('details.homework-history').open=true;
    const buttons=[...document.querySelectorAll('.homework-history nav button')];
    buttons[2].focus();
    return {focused:buttons[2]===document.activeElement,disabled:buttons[2].disabled,active:document.activeElement?.outerHTML};
  })()`);
  assert.ok(startingFocus.focused && !startingFocus.disabled,'Latest must be enabled and focused: '+JSON.stringify(startingFocus));
  await cdp('Input.dispatchKeyEvent',{type:'keyDown',key:'Tab',code:'Tab',windowsVirtualKeyCode:9});
  await cdp('Input.dispatchKeyEvent',{type:'keyUp',key:'Tab',code:'Tab',windowsVirtualKeyCode:9});
  const keyboard=await evaluate(`(() => {
    const buttons=[...document.querySelectorAll('.homework-history nav button')];
    return {active:buttons.indexOf(document.activeElement),focusVisible:document.activeElement?.matches(':focus-visible'),activeHtml:document.activeElement?.outerHTML};
  })()`);
  assert.equal(keyboard.active,3,'Tab must focus Refresh after Latest: '+JSON.stringify(keyboard));
  assert.ok(keyboard.focusVisible,'Keyboard focus indicator must remain visible');
  // Chrome page-scale tests visual zoom; keep CSS viewport at 360px to catch
  // overflow while text and controls are magnified.
  await cdp('Emulation.setPageScaleFactor',{pageScaleFactor:2});
  const zoom=await layout();
  assert.ok(zoom.scrollWidth<=zoom.width,'Horizontal overflow with 200% page scale');
  const scale=await evaluate('visualViewport.scale');
  assert.ok(scale>=1.9,'Could not apply 200% page scale');
  // Reflow check: a 360-physical-pixel viewport at browser zoom 200% has
  // approximately 180 CSS layout pixels. Unlike pageScaleFactor, shrinking
  // the CSS viewport exercises responsive reflow and horizontal overflow.
  await cdp('Emulation.setPageScaleFactor',{pageScaleFactor:1});
  await cdp('Emulation.setDeviceMetricsOverride',{width:180,height:410,deviceScaleFactor:1,mobile:false});
  const reflow=await layout();
  assert.equal(reflow.width,180,'200% effective layout width was not applied');
  assert.ok(reflow.scrollWidth<=reflow.width,'Horizontal overflow at 200% effective reflow: '+JSON.stringify(reflow));

  process.stdout.write(JSON.stringify({status:'PASS',viewport360:compact,keyboard,zoom200:{...zoom,pageScale:scale},reflow200:reflow},null,2)+'\n');
} finally {
  socket?.close();
  browser?.kill('SIGTERM');
  await server.close();
  await rm(dir,{recursive:true,force:true});
}
