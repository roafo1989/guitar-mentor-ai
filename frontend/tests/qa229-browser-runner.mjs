import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { createServer } from 'vite';

const chromeCandidates = [
  process.env.CHROME_PATH,
  process.env.CHROME_BIN,
  ...(process.platform === 'win32' ? [
    path.join(process.env.PROGRAMFILES || 'C:\\Program Files', 'Google', 'Chrome', 'Application', 'chrome.exe'),
    path.join(process.env['PROGRAMFILES(X86)'] || 'C:\\Program Files (x86)', 'Google', 'Chrome', 'Application', 'chrome.exe'),
    path.join(process.env.LOCALAPPDATA || '', 'Google', 'Chrome', 'Application', 'chrome.exe'),
    path.join(process.env.PROGRAMFILES || 'C:\\Program Files', 'Microsoft', 'Edge', 'Application', 'msedge.exe'),
    path.join(process.env['PROGRAMFILES(X86)'] || 'C:\\Program Files (x86)', 'Microsoft', 'Edge', 'Application', 'msedge.exe'),
  ] : []),
  'google-chrome', 'chromium', 'chromium-browser', 'chrome', 'msedge',
];
const chrome = chromeCandidates.find(candidate => candidate && (
  existsSync(candidate) ||
  spawnSync(process.platform === 'win32' ? 'where.exe' : 'which', [candidate], {stdio:'ignore'}).status === 0
));
assert.ok(chrome,'Chrome required for real next-lesson browser QA');
const dir=await mkdtemp(path.join(os.tmpdir(),'qa229-'));
process.env.VITE_GUITAR_MENTOR_AUTH_REQUIRED='true';
process.env.VITE_GUITAR_MENTOR_LIGHT_MINIMAL='true';
const server=await createServer({server:{host:'127.0.0.1',port:0}});
let browser,socket;
try {
 await server.listen();const port=server.httpServer.address().port;
 browser=spawn(chrome,['--headless=new','--no-zygote','--no-sandbox','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader','--disable-dev-shm-usage',
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
 const id=++serial;const timer=setTimeout(()=>{pending.delete(id);reject(Error('CDP timeout '+method+' '+JSON.stringify(params).slice(0,240)));},15000);pending.set(id,{resolve:v=>{clearTimeout(timer);resolve(v)},reject:e=>{clearTimeout(timer);reject(e)}});socket.send(JSON.stringify({id,method,params}));});
 const evaluate=async expression=>{
 const r=await cdp('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});
 if(r.exceptionDetails)throw Error(JSON.stringify(r.exceptionDetails));return r.result.value;};

 await cdp('Page.enable');await cdp('Runtime.enable');
 const errors=[];
 socket.addEventListener('message',e=>{const m=JSON.parse(e.data);if(m.method==='Runtime.exceptionThrown')errors.push(m.params.exceptionDetails.text);});
 const base='http://127.0.0.1:'+port;
 await cdp('Page.addScriptToEvaluateOnNewDocument',{source:`
 const id='11111111-1111-4111-8111-111111111111';
 const instrumentId='22222222-2222-4222-8222-222222222222';
 window.__qa={calls:[], failRead:false, failSave:false, failNext:false, failMethods:false, google:false, bare:false,
   learner:{id,displayName:'Roman',instruments:[{id:instrumentId,instrumentCode:'ACOUSTIC_GUITAR',techniqueCode:'FINGERSTYLE',preferences:[{styleCode:'BLUES',rating:'LIKE'}]}]},
   background:{experienceLevel:'INTERMEDIATE',yearsPlaying:2,skills:['CHORDS'],knownPieces:[{title:'Windy & Warm',artist:'Tommy Emmanuel'}],goals:['FINGERSTYLE'],practiceMinutes:40,sessionsPerWeek:3,notes:'No percussion'},
 };
 const native=window.fetch.bind(window);
 window.fetch=async(input,init)=>{
   const url=new URL(typeof input==='string'?input:input.url||input.toString(),location.origin);
   if(!url.pathname.startsWith('/api/')&&url.pathname!='/logout')return native(input,init);
   const q=window.__qa, path=url.pathname, method=init?.method||'GET';
   q.calls.push({path,method});
   const reply=(value,status=200)=>new Response(status===204?null:JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});
   if(path.endsWith('/me'))return reply({accountId:'account-1',learnerId:id,role:'LEARNER'});
   if(path.endsWith('/csrf'))return reply({headerName:'X-CSRF',token:'fixture'});
   if(path.endsWith('/methods'))return q.failMethods?reply({},503):reply({passwordEnabled:!q.google,googleEnabled:q.google,googleAvailable:true});
   if(path.endsWith('/change'))return q.failSave?reply({},400):reply({});
   if(path.endsWith('/logout'))return reply({},204);
   if(path.endsWith('/background')){
     if(method==='PUT'){if(q.failSave)return reply({},503);q.background=JSON.parse(init.body);return reply(q.background);}
     return q.failRead?reply({},503):q.background?reply(q.background):reply(null,204);
   }
   if(path.endsWith('/skills'))return reply([]);
   if(path.endsWith('/current'))return q.failNext?reply({},503):reply(null,204);
   if(path.endsWith('/preferences')){if(q.failSave)return reply({},503);let body=JSON.parse(init.body);q.learner.instruments=[{id:instrumentId,instrumentCode:'ACOUSTIC_GUITAR',techniqueCode:'FINGERSTYLE',preferences:body.preferences}];return reply(q.learner);}
   if(path.endsWith('/learners/'+id))return q.failRead?reply({},503):reply(q.bare?{...q.learner,instruments:[]}:q.learner);
   return reply({},404);
 };`});
 const waitFor=async expression=>{
   const ok=await evaluate(`new Promise(resolve=>{let n=0;const t=setInterval(()=>{if(${expression}){clearInterval(t);resolve(true);}else if(++n>200){clearInterval(t);resolve(false);}},25);})`);
   assert.ok(ok,'Timed out: '+expression);
 };
 const clickText=async text=>evaluate(`[...document.querySelectorAll('main button')].find(b=>b.textContent.includes(${JSON.stringify(text)}))?.click()`);
 const navigate=async path=>{await cdp('Page.navigate',{url:base+path});await waitFor("document.querySelector('main h1')");};
 const ui=async expression=>evaluate(expression);
 const puts=()=>ui("__qa.calls.filter(c=>c.method==='PUT').length");
 const next=()=>evaluate("document.querySelector('main form').requestSubmit()");
 const screenshotDir=path.resolve('../docs/testing/qa229');await mkdir(screenshotDir,{recursive:true});
 const screenshot=async name=>{await ui('window.scrollTo(0,0)');const png=await cdp('Page.captureScreenshot',{format:'png',captureBeyondViewport:true});await writeFile(path.join(screenshotDir,name+'.png'),Buffer.from(png.data,'base64'));};
 await cdp('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
 await navigate('/profile');await waitFor("document.querySelector('.profile-overview')&&document.body.textContent.includes('No playing observations')===false");
 await waitFor("document.querySelector('.learning-summary .skill-chips')");
 assert.equal(await ui("!!document.querySelector('.password-settings')"),false,'password has moved out');
 await screenshot('profile-1440-ru');
 // Profile save updates the shared store and survives a re-read.
 await clickText('Редактировать');await waitFor("!!document.querySelector('.preference-rows')");
 await ui("document.querySelector('input[name=ROCK][value=LIKE]').click()");
 await next();await waitFor("!!document.querySelector('.saved-confirmation')");
 assert.equal(await puts(),1);
 assert.ok(await ui("document.querySelector('.preference-summary').textContent.includes('Рок')"));
 assert.ok(await ui("document.querySelector('.header-account-name').textContent.includes('Roman')"));
 // Route navigation, browser refresh and saved known-piece fields.
 await ui("import('/src/router/index.ts').then(m=>m.router.push('/onboarding'))");
 await waitFor("document.querySelector('[data-piece-title]')");
 assert.equal(await ui("document.querySelector('[data-piece-title]').value"),'Windy & Warm');
 await screenshot('onboarding-step1-1440-ru');
 // Acceptance UX229-A01: explicit save on early steps stays in place.
 const writesBeforeEarlySave=await puts();
 const nextReadsBeforeEarlySave=await ui("__qa.calls.filter(c=>c.path.endsWith('/current')).length");
 await ui("{const f=document.querySelector('main input[type=number]');f.value='3';f.dispatchEvent(new Event('input',{bubbles:true}));}");
 await clickText('Сохранить изменения');await waitFor("document.querySelector('.wizard-steps')&&document.querySelector('main .saved-confirmation')");
 assert.equal(await puts(),writesBeforeEarlySave+1);
 assert.equal(await ui('__qa.background.yearsPlaying'),3);
 assert.equal(await ui("document.querySelector('.wizard-steps [aria-current=step] span').textContent"),'1');
 assert.equal(await ui("__qa.calls.filter(c=>c.path.endsWith('/current')).length"),nextReadsBeforeEarlySave);
 await screenshot('onboarding-save-step1-1440-ru');

 // Structured title preserves a literal separator.
 await ui("let f=document.querySelector('[data-piece-title]');f.value='A | B';f.dispatchEvent(new Event('input',{bubbles:true}))");
 await ui("[...document.querySelectorAll('main button')].find(b=>b.textContent.includes('Добавить произведение')).click()");
 await waitFor("document.querySelectorAll('[data-piece-title]').length===2");
 assert.equal(await ui("document.activeElement===document.querySelectorAll('[data-piece-title]')[1]"),true,'new row gets keyboard focus');
 await ui("[...document.querySelectorAll('main button')].filter(b=>b.textContent.trim()==='Удалить').at(-1).click()");
 // Unsaved-route guard: cancel stays, confirm discards.
 const navAttempt=cdp('Runtime.evaluate',{expression:"import('/src/router/index.ts').then(m=>m.router.push('/profile'))",awaitPromise:true,returnByValue:true});
 await new Promise(resolve=>{const fn=e=>{const m=JSON.parse(e.data);if(m.method==='Page.javascriptDialogOpening'){socket.removeEventListener('message',fn);resolve();}};socket.addEventListener('message',fn);});
 await cdp('Page.handleJavaScriptDialog',{accept:false});await navAttempt;
 assert.equal(await ui('location.pathname'),'/onboarding');
 const discardAttempt=cdp('Runtime.evaluate',{expression:"import('/src/router/index.ts').then(m=>m.router.push('/profile'))",awaitPromise:true,returnByValue:true});
 await new Promise(resolve=>{const fn=e=>{if(JSON.parse(e.data).method==='Page.javascriptDialogOpening'){socket.removeEventListener('message',fn);resolve();}};socket.addEventListener('message',fn);});
 await cdp('Page.handleJavaScriptDialog',{accept:true});await discardAttempt;
 await ui("import('/src/router/index.ts').then(m=>m.router.push('/onboarding'))");await waitFor("document.querySelector('[data-piece-title]')");
 assert.equal(await ui("document.querySelector('[data-piece-title]').value"),'Windy & Warm','discarded draft is not persisted');
 await ui("{const field=document.querySelector('[data-piece-title]');field.value='A | B';field.dispatchEvent(new Event('input',{bubbles:true}));}");

 await next();await waitFor("document.querySelector('main h2')?.textContent.includes('освоить')");
 await clickText('Сохранить изменения');await waitFor("document.querySelector('.wizard-steps')&&document.querySelector('main .saved-confirmation')");
 assert.equal(await ui("document.querySelector('.wizard-steps [aria-current=step] span').textContent"),'2');
 await screenshot('onboarding-step2-1440-ru'); 
 await clickText('Назад');await waitFor("document.querySelector('[data-piece-title]')");
 assert.equal(await ui("document.querySelector('[data-piece-title]').value"),'A | B');
 await next();await next();await waitFor("document.querySelector('input[min=\"10\"]')");
 await screenshot('onboarding-step3-1440-ru'); 
 // Failed write keeps answers, next-action failure retries only reads.
 await ui('__qa.failSave=true');await next();await waitFor("!!document.querySelector('main .error')");
 assert.ok(await ui("!!document.querySelector('input[min=\"10\"]')"));
 await ui('__qa.failSave=false;__qa.failNext=true');await next();await waitFor("document.querySelector('.completion-panel [role=alert]')");
 const count=await puts();await ui('__qa.failNext=false');await clickText('Повторить');
 await waitFor("!!document.querySelector('.completion-panel .primary-link')");assert.equal(await puts(),count);
 assert.equal(await ui('__qa.background.knownPieces[0].title'),'A | B');
 assert.equal(await ui("document.querySelector('.completion-panel .primary-link').getAttribute('href')"),'/diagnostics');
 assert.equal(await ui("__qa.calls.some(c=>c.method==='POST'&&c.path.includes('diagnostic'))"),false,'editing never creates diagnostics');
 await screenshot('onboarding-complete-1440-ru');
 // Open from scratch and refresh each route with the real router/AuthGate.
 await navigate('/settings/account');await waitFor("document.querySelector('#current-password')");
 await screenshot('account-1440-ru');
 await ui("__qa.failSave=true;const set=(id,value)=>{const f=document.getElementById(id);f.value=value;f.dispatchEvent(new Event('input',{bubbles:true}));};set('current-password','test-old-password');set('new-account-password','test-new-password');set('confirm-account-password','test-new-password');document.querySelector('.password-settings form').requestSubmit()");
 await waitFor("document.querySelector('.auth-error')");
 await ui("__qa.failSave=false;document.querySelector('.password-settings form').requestSubmit()");
 await waitFor("document.querySelector('.password-settings .success')");
 assert.equal(await ui("document.querySelector('#new-account-password').value"),'');
 // Google methods are server supplied. Failed method reads must show no invented status/forms.
 await ui("__qa.google=true;import('/src/router/index.ts').then(async m=>{await m.router.push('/profile');await m.router.push('/settings/account')})");
 await waitFor("document.querySelector('#link-email')");
 assert.equal(await ui("!!document.querySelector('#current-password')"),false);
 await ui("__qa.failMethods=true;import('/src/router/index.ts').then(async m=>{await m.router.push('/profile');await m.router.push('/settings/account')})");
 await waitFor("document.querySelector('.password-settings .auth-error')");
 assert.equal(await ui("document.querySelectorAll('.password-settings form').length"),0);
 await ui('__qa.failMethods=false');await clickText('Повторить');await waitFor("document.querySelector('#link-email')");
 // Legacy emailed links redirect and scrub the token without placing it in an input.
 await navigate('/profile#authAction=link&token='+'a'.repeat(64));
 await waitFor("location.pathname==='/settings/account'&&document.querySelector('.password-settings')");
 assert.equal(await ui('location.hash'),'');
 assert.equal(await ui("[...document.querySelectorAll('input')].some(i=>i.value==='a'.repeat(64))"),false);
 // New learner: adding first instrument reuses the existing profile API.
 await ui("__qa.bare=true;import('/src/router/index.ts').then(m=>m.router.push('/profile'))");
 await waitFor("document.querySelector('.preference-rows')");
 await next();await waitFor("document.querySelector('.profile-overview')");
 assert.equal(await ui('__qa.learner.instruments.length'),1);
 // Locale/layout matrix, full pages and effective 200% reflow.
 for(const locale of ['ru','en'])for(const width of [360,768,1440])for(const route of ['/profile','/onboarding','/settings/account']){
   await cdp('Emulation.setDeviceMetricsOverride',{width,height:1000,deviceScaleFactor:1,mobile:false});
   await navigate(route);await waitFor("!document.querySelector('main [role=status]') || document.querySelector('.profile-overview') || document.querySelector('.password-settings form') || document.querySelector('.wizard-steps')");
   await ui(`import('/src/core/i18n.ts').then(m=>m.i18n.global.locale.value='${locale}')`);
   await waitFor("document.querySelector('main h1')");
   assert.ok(await ui('document.documentElement.scrollWidth<=innerWidth'),locale+' '+width+' '+route+' overflow');
   assert.equal(await ui("[...document.querySelectorAll('main input:not([type=checkbox]):not([type=radio]),main select,main textarea')].every(f=>f.labels?.length>0)"),true,'field labels '+route);
   if(width===360)await screenshot(route.split('/').at(-1)+'-360-'+locale);
 }
 for(const route of ['/profile','/onboarding','/settings/account']){
   await cdp('Emulation.setDeviceMetricsOverride',{width:384,height:500,deviceScaleFactor:1,mobile:false});await navigate(route);
   await waitFor("document.querySelector('.profile-overview')||document.querySelector('.wizard-steps')||document.querySelector('.password-settings form')");
   assert.ok(await ui('document.documentElement.scrollWidth<=innerWidth'),'200% reflow '+route);
 }
 await ui("document.activeElement?.blur()");
 await cdp('Input.dispatchKeyEvent',{type:'keyDown',key:'Tab',code:'Tab',windowsVirtualKeyCode:9});
 await cdp('Input.dispatchKeyEvent',{type:'keyUp',key:'Tab',code:'Tab',windowsVirtualKeyCode:9});
 assert.ok(await ui("document.activeElement!==document.body&&document.activeElement.matches('a,button,input,select,textarea')"),'Tab reaches an interactive control');
 assert.deepEqual(errors,[],'No uncaught browser errors');
 console.log('PASS #229: real Vue+router+AuthGate; profile save/header; 3-step draft/back; piece add/remove/focus; failed save; read-only completion retry; auth local/Google/method failure; deep links/F5; RU/EN 360/768/1440; 200% effective reflow; labelled controls; screenshots. Backend mocked.');
} finally {
 socket?.close();browser?.kill('SIGTERM');
 await server.close();await rm(dir,{recursive:true,force:true,maxRetries:12,retryDelay:250}).catch(()=>{});
}
