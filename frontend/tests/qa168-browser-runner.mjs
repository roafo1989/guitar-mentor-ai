import assert from 'node:assert/strict';
import {chromium} from 'playwright';
import {createServer} from 'vite';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import path from 'node:path';
const output=path.resolve('../docs/testing/qa168');await mkdir(output,{recursive:true});
process.env.VITE_GUITAR_MENTOR_AUTH_REQUIRED='true';process.env.VITE_GUITAR_MENTOR_LIGHT_MINIMAL='true';
const vite=await createServer({server:{host:'127.0.0.1',port:0}});await vite.listen();
const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||undefined,args:['--no-sandbox','--disable-dev-shm-usage']});
const origin='http://127.0.0.1:'+vite.httpServer.address().port;
const id='11111111-1111-4111-8111-111111111111',asset='22222222-2222-4222-8222-222222222222',pub='33333333-3333-4333-8333-333333333333';
const own='/api/v1/library/materials/'+id,common='/api/v1/community/publications/'+pub;
const file={id:asset,originalName:'Original.pdf',format:'PDF',byteSize:100,sha256:'a'.repeat(64),validationStatus:'PDF_HEADER_CHECKED',downloadUrl:own+'/files/'+asset+'/content',role:'SCORE_ORIGINAL',mediaType:'application/pdf',processingState:'READY',previewAvailable:false,previewReasonCode:null,previewUrl:null,durationMillis:null,channels:null,sampleRate:null,width:null,height:null};
const material={id,title:'Original score',type:'ETUDE',author:'QA168',state:'READY',visibility:'PRIVATE',reviewStatus:'UNREVIEWED',revision:1,createdAt:'2026-10-31T00:00:00Z',updatedAt:'2026-10-31T00:00:00Z',files:[file]};
const publication={id:pub,title:'Original score',type:'ETUDE',author:'QA168',state:'PUBLISHED',version:1,sourceRevision:1,revision:1,tags:['blues','fingerstyle'],assets:[{...file,downloadUrl:common+'/files/'+asset+'/content',provenance:'CREATED',rightsBasis:'OWN_WORK',attribution:'QA168'}]};
const capabilities={enabled:true,allowedExtensions:{PHOTO_ORIGINAL:['jpg','jpeg','png'],ORIGINAL_RECORDING:['mp3','wav'],SYNTHESIZED_PREVIEW:['mp3','wav']},roles:['PHOTO_ORIGINAL','ORIGINAL_RECORDING','SYNTHESIZED_PREVIEW'],maxFilesPerRequest:2,maxFilesPerMaterial:7,maxPhotosAndScores:5,maxAudio:2,maxRequestBytes:20971520,maxPhotoBytes:5242880,maxAudioBytes:20971520,maxPhotoPixels:20000000,maxPhotoSide:12000,maxAudioDurationMillis:600000,minAudioSampleRate:8000,maxAudioSampleRate:48000,audioChannels:[1,2],photoFormats:['JPEG','PNG'],audioFormats:['MP3','WAV'],wavEncoding:'PCM16'};
const pdfPath=process.env.QA168_PDF||'../backend/src/main/resources/exercises/GM-EB01/score_v08B.pdf';
const pdf=await readFile(pdfPath);
const photoId='55555555-5555-4555-8555-555555555555',audioId='66666666-6666-4666-8666-666666666666';
const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5S8AAAAASUVORK5CYII=','base64');
const wav=Buffer.alloc(46);wav.write('RIFF');wav.writeUInt32LE(38,4);wav.write('WAVEfmt ',8);wav.writeUInt32LE(16,16);wav.writeUInt16LE(1,20);wav.writeUInt16LE(1,22);wav.writeUInt32LE(8000,24);wav.writeUInt32LE(16000,28);wav.writeUInt16LE(2,32);wav.writeUInt16LE(16,34);wav.write('data',36);wav.writeUInt32LE(2,40);
let actor=id,history=[],calls=[],failSearch=0,failPublish=0,mediaEnabled=true;
const page=await browser.newPage({viewport:{width:1440,height:1000}}),exceptions=[];page.on('pageerror',e=>exceptions.push(e.message));page.on('console',m=>{if(m.type()==='error'||m.type()==='warning')console.error('browser console:',m.text());});
await page.route('**/api/**',async route=>{
 const req=route.request(),url=new URL(req.url()),p=url.pathname,method=req.method();calls.push({p,method,query:url.search});
 const json=(data,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(data)});
 if(p==='/api/v1/me')return json({accountId:actor,learnerId:null,role:'LEARNER'});
 if(p==='/api/v1/csrf')return json({headerName:'X-CSRF',token:'fixture'});
 if(p.endsWith('/username'))return json({login:'qa168'});
 if(p==='/api/v1/library/capabilities')return json({...capabilities,enabled:mediaEnabled});
 if(p===own+'/files'&&method==='POST'){
   const recording=req.postData().includes('ORIGINAL_RECORDING');
   const fid=recording?audioId:photoId;
   material.files.push({...file,id:fid,originalName:recording?'recording.wav':'photo.png',format:recording?'WAV':'PNG',role:recording?'ORIGINAL_RECORDING':'PHOTO_ORIGINAL',mediaType:recording?'audio/wav':'image/png',byteSize:recording?wav.length:png.length,downloadUrl:own+'/files/'+fid+'/content',previewAvailable:!recording,previewUrl:recording?null:own+'/files/'+fid+'/preview'});material.revision++;
   await new Promise(resolve=>setTimeout(resolve,200));return json(material,201);
 }
 if(p.startsWith(own+'/files/')&&method==='DELETE')return json({code:'CATALOG_ASSET_PUBLISHED'},409);
 if(p.endsWith('/preview'))return route.fulfill({status:200,contentType:'image/png',body:png});
 if(p.endsWith('/files/'+audioId+'/content'))return route.fulfill({status:200,contentType:'audio/wav',body:wav});
 if(p===own+'/publications'&&method==='GET')return json(history);
 if(p===own+'/publications'&&method==='POST'){
  if(failPublish)return json({},failPublish);
  assert.equal(req.headers()['if-match'],'"'+material.revision+'"');assert.ok(req.headers()['idempotency-key']);assert.equal(req.headers()['x-csrf'],'fixture');
  const body=req.postDataJSON();assert.equal(body.assets.length,1);assert.equal(body.assets[0].rightsBasis,'OWN_WORK');
  const result={...publication,title:material.title,sourceRevision:material.revision};history=[result];return json(result,201);
 }
 if(p.endsWith('/withdraw')){assert.equal(req.headers()['if-match'],'"1"');history=history.map(p=>({...p,state:'WITHDRAWN',revision:2}));return json(history[0]);}
 if(p==='/api/v1/community/publications'){
  if(failSearch)return json({},failSearch);
  const q=url.searchParams.get('q');if(q==='slow')await new Promise(resolve=>setTimeout(resolve,600));
  return json({items:q==='empty'?[]:[{id:pub,title:q==='slow'?'STALE':'Original score',type:'ETUDE',author:'QA168',version:1,publishedAt:'2026-10-31T00:00:00Z',tags:['blues','fingerstyle'],hasAudio:false,detailUrl:common}],nextCursor:url.searchParams.has('cursor')?null:'opaque+/='});
 }
 if(p===common)return json(publication);
 if(p.endsWith('/content'))return route.fulfill({status:200,contentType:'application/pdf',body:Buffer.from(pdf)});
 if(p===own&&method==='PATCH'){Object.assign(material,req.postDataJSON(),{revision:material.revision+1});return json(material);}
 if(p===own)return json(material);
 if(p==='/api/v1/library/materials')return json({items:actor===id?[material]:[],nextCursor:null});
 return json({});
});
const button=text=>page.getByRole('button',{name:text,exact:true}),wait=()=>page.waitForTimeout(420);
try{
 await page.goto(origin+'/library');await button('Original score').click();await button('Опубликовать').waitFor();assert.equal(history.length,0);
 const dialog=page.getByRole('dialog');
 async function rights(){await dialog.getByLabel('Включить этот файл').check();await dialog.getByLabel('Основание распространения').selectOption('OWN_WORK');await dialog.getByLabel('Подтверждение прав',{exact:false}).fill('Synthetic original QA168 score.');await dialog.getByLabel('Подтверждаю права',{exact:false}).check();}
 await button('Опубликовать').click();await rights();await dialog.getByRole('button',{name:'Опубликовать',exact:true}).click();await dialog.waitFor({state:'detached'});assert.equal(history.length,1);
 await page.locator('.sharing .asset-list').first().getByRole('button',{name:'Открыть',exact:true}).click();await page.waitForFunction(()=>document.querySelector('.score-reader')?.dataset.renderedPage==='1');
 await page.locator('.library-more').click();await page.getByRole('menuitem').filter({hasText:'Изменить'}).click();
 await dialog.getByLabel('Название').fill('Changed private draft');await dialog.getByRole('button',{name:'Сохранить',exact:true}).click();await dialog.waitFor({state:'detached'});await page.getByText('Черновик изменён.',{exact:false}).waitFor();assert.equal(history[0].title,'Original score');
 await button('Снять старую и опубликовать новую').click();await rights();failPublish=503;await dialog.getByRole('button',{name:'Опубликовать',exact:true}).click();await page.getByText('Старая публикация снята; новая не опубликована.',{exact:false}).waitFor();assert.equal(history[0].state,'WITHDRAWN');await page.keyboard.press('Escape');assert.equal(await page.getByRole('dialog').count(),0);assert.equal(await page.evaluate(()=>document.activeElement?.textContent?.trim()),'Обновить');failPublish=0;
 await button('Общие').click();await page.locator('.results button').first().waitFor();
 const search=page.getByLabel('Поиск по названию или автору');await search.fill('slow');await wait();await search.fill('fast');await page.waitForTimeout(800);assert.equal(await page.getByText('STALE',{exact:true}).count(),0);
 await page.getByLabel('Теги через запятую').fill('blues, fingerstyle');await wait();assert.deepEqual(new URLSearchParams(calls.filter(c=>c.p==='/api/v1/community/publications').at(-1).query).getAll('tag'),['blues','fingerstyle']);
 await button('Показать ещё').click();await wait();assert.equal(new URLSearchParams(calls.filter(c=>c.p==='/api/v1/community/publications').at(-1).query).get('cursor'),'opaque+/=');
 await page.locator('.results button').first().click();await page.getByText('Пользовательский материал, не проверен для обучения',{exact:true}).waitFor();await button('Открыть').click();
 await page.waitForFunction(()=>document.querySelector('.score-reader')?.getAttribute('data-rendered-page')==='1');assert.equal(await page.locator('iframe.score-canvas-frame').getAttribute('sandbox'),'allow-scripts');
 for(const width of [360,768,1440]){await page.setViewportSize({width,height:1000});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'overflow '+width);await page.screenshot({path:path.join(output,'shared-ru-'+width+'.png'),fullPage:true});}
 await page.evaluate(()=>document.documentElement.style.zoom='2');assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'200% overflow');await page.screenshot({path:path.join(output,'shared-zoom.png'),fullPage:true});await page.evaluate(()=>document.documentElement.style.zoom='1');
 await search.fill('empty');await wait();await page.getByText('Опубликованные материалы не найдены.',{exact:false}).waitFor();assert.equal(await page.locator('iframe.score-canvas-frame').count(),0);
 failSearch=503;await search.fill('failure');await wait();await button('Повторить чтение').waitFor();const posts=calls.filter(c=>c.method!=='GET').length;failSearch=0;await button('Повторить чтение').click();await wait();assert.equal(calls.filter(c=>c.method!=='GET').length,posts);
 await button('Мои').click();await button('Changed private draft').click();mediaEnabled=false;await page.locator('.sharing').getByRole('button',{name:'Обновить',exact:true}).click();await page.getByText('Добавление фото и аудио отключено.',{exact:false}).waitFor();assert.ok(await button('Открыть').count());
 mediaEnabled=true;await page.locator('.sharing').getByRole('button',{name:'Обновить',exact:true}).click();
 const mediaInput=page.locator('.sharing input[type=file]');
 const payload={name:'photo.png',mimeType:'image/png',buffer:png};
 await mediaInput.setInputFiles([payload,payload,payload]);const uploads=calls.filter(c=>c.p===own+'/files'&&c.method==='POST').length;await button('Добавить к материалу').click();await page.getByText('Проверьте формат, размер и количество файлов',{exact:false}).waitFor();assert.equal(calls.filter(c=>c.p===own+'/files'&&c.method==='POST').length,uploads);
 await mediaInput.setInputFiles(payload);await button('Добавить к материалу').click();await page.getByText('Файлы добавлены к личному черновику.',{exact:true}).waitFor();
 await page.locator('.sharing .asset-list article').filter({hasText:'photo.png'}).getByRole('button',{name:'Открыть',exact:true}).click();await page.locator('.sharing img').waitFor();assert.ok((await page.locator('.sharing img').getAttribute('src')).startsWith('blob:'));
 await page.getByLabel('Роль файла').selectOption('ORIGINAL_RECORDING');await mediaInput.setInputFiles({name:'recording.wav',mimeType:'audio/wav',buffer:wav});await button('Добавить к материалу').click();await wait();
 await page.locator('.sharing .asset-list article').filter({hasText:'recording.wav'}).getByRole('button',{name:'Открыть',exact:true}).click();await page.locator('audio').waitFor();assert.equal(await page.locator('audio').getAttribute('autoplay'),null);assert.equal(await page.locator('audio').getAttribute('preload'),'none');
 await page.evaluate(()=>window.__qaAudio=document.querySelector('audio'));
 await page.locator('.sharing form li').filter({hasText:'photo.png'}).getByRole('button',{name:'Удалить',exact:true}).click();await page.getByText('Файл используется действующей публикацией.',{exact:false}).waitFor();
 await page.keyboard.press('Tab');assert.ok(await page.evaluate(()=>document.activeElement!==document.body));await page.screenshot({path:path.join(output,'mine-ru.png'),fullPage:true});
 actor='44444444-4444-4444-8444-444444444444';await page.evaluate(async accountId=>{const {auth}=await import('/src/core/auth/auth.ts');auth.actor={accountId,learnerId:'',role:'LEARNER'};},actor);await wait();assert.equal(await page.getByText('Changed private draft',{exact:true}).count(),0);assert.equal(await page.evaluate(()=>window.__qaAudio.paused&&!window.__qaAudio.hasAttribute('src')),true);
 await page.evaluate(()=>localStorage.setItem('guitar-mentor.locale','en'));await page.reload();await button('Shared').click();await page.locator('.results button').first().click();await page.getByText('User material, not reviewed for learning',{exact:true}).waitFor();await page.setViewportSize({width:360,height:900});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await page.screenshot({path:path.join(output,'shared-en-360.png'),fullPage:true});
 assert.deepEqual(exceptions,[]);const result={status:'PASS',kind:'Real Chromium; explicitly mocked HTTP backend',widths:[360,768,1440],zoom:'200%',pdfRendered:true,checks:['explicit no-audio publish','draft vs snapshot','withdraw/publish failure','stale search','all tags','opaque cursor','sandbox','read-only retry','flag-off','keyboard','account change with audio teardown','photo sanitized preview','media upload limits and role','published-reference error','EN mobile']};await writeFile(path.join(output,'browser-result.json'),JSON.stringify(result,null,2)+'\n');console.log(result);
}catch(e){await page.screenshot({path:path.join(output,'failure.png'),fullPage:true});console.error((await page.locator('body').innerText()).slice(0,4000));throw e;}
finally{await browser.close();await vite.close();}
