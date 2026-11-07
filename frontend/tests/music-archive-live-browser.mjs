import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { createServer } from 'vite';
import { chromium } from 'playwright';

assert.equal(process.env.GM_TEST_REAL_BROWSER,'true');
const backend=new URL(process.env.GM_TEST_BACKEND_URL);
assert.equal(backend.hostname,'127.0.0.1');
const evidence=path.resolve('../artifacts/catalog-196/browser');
await mkdir(evidence,{recursive:true});
process.env.VITE_GUITAR_MENTOR_AUTH_REQUIRED='true';
process.env.VITE_GUITAR_MENTOR_LIGHT_MINIMAL='true';
const server=await createServer({server:{host:'127.0.0.1',port:0,proxy:{'/api':{target:backend.origin,changeOrigin:false},'/logout':{target:backend.origin,changeOrigin:false}}}});
let browser;
const errors=[],checks=[];
const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
try {
  await server.listen();
  const base='http://127.0.0.1:'+server.httpServer.address().port;
  browser=await chromium.launch({headless:true,...(process.env.GM_TEST_BROWSER_EXECUTABLE?{executablePath:process.env.GM_TEST_BROWSER_EXECUTABLE}:{})});
  async function login(name) {
    const context=await browser.newContext({viewport:{width:1440,height:1050},acceptDownloads:true});
    await context.addInitScript(()=>localStorage.setItem('guitar-mentor.locale','en'));
    const tab=await context.newPage();tab.setDefaultTimeout(20000);tab.on('pageerror',e=>errors.push(e.message));
    await tab.goto(base+'/library');
    await tab.locator('#local-identifier').fill(name);await tab.locator('#local-password').fill('qa196-synthetic-password');
    await tab.locator('.auth-form button[type=submit]').click();await tab.locator('.library-page').waitFor();
    return {context,tab};
  }
  const alice=await login(process.env.GM_TEST_LOGIN_A),page=alice.tab;
  const pdf=Buffer.from('%PDF-1.7\nSynthetic original for private catalog 196\n');
  const gp=await readFile('../fixtures/research/catalog-169/generated/gm169-synthetic.gp5');
  async function upload(name,bytes,title) {
    await page.getByRole('button',{name:'Add material',exact:true}).click();
    const dialog=page.getByRole('dialog');
    await dialog.locator('input[type=file]').setInputFiles({name,mimeType:'application/octet-stream',buffer:bytes});
    await dialog.getByLabel('Title',{exact:true}).fill(title);
    const received=page.waitForResponse(r=>r.request().method()==='POST' && new URL(r.url()).pathname==='/api/v1/library/materials');
    await dialog.getByRole('button',{name:'Upload',exact:true}).click();const response=await received;
    assert.equal(response.status(),201,await response.text());
    const material=await response.json();await page.locator('.sharing h2').filter({hasText:title}).waitFor();return material;
  }
  const pdfMaterial=await upload('original196.pdf',pdf,'PDF original 196');
  // Return from the selected material before starting another upload.
  const gpMaterial=await upload('original196.gp5',gp,'GP original 196');
  checks.push('Real UI upload: PDF and GP5 through existing quota/validation/storage path');
  await page.goto(base+'/music-archive');
  await page.getByRole('button',{name:'Synthetic 196',exact:false}).click();
  const row=id=>page.locator('.archive-files > li').filter({has:page.locator('strong',{hasText:id})});
  await row('pdf196').getByText('Original unavailable',{exact:true}).waitFor();
  assert.equal(await row('empty196').getByRole('button',{name:'Download original',exact:true}).count(),0);
  checks.push('Metadata without an original stays UNAVAILABLE and has no download button');
  for(const [id,material,bytes] of [['pdf196',pdfMaterial,pdf],['gp196',gpMaterial,gp]]) {
    await row(id).getByRole('button',{name:'Attach original',exact:true}).click();
    await row(id).getByText('Choose your uploaded PDF or Guitar Pro file.',{exact:false}).waitFor();
    const received=page.waitForResponse(r=>r.request().method()==='PUT' && r.url().includes('/music-catalog/files/'+id+'/original'));
    await row(id).getByRole('button',{name:material.title+' · '+material.files[0].originalName,exact:true}).click();
    assert.equal((await received).status(),204);
    await row(id).getByText('Original attached · only you can access it',{exact:true}).waitFor();
    const downloaded=page.waitForEvent('download');
    await row(id).getByRole('button',{name:'Download original',exact:true}).click();
    const download=await downloaded;
    assert.equal(download.suggestedFilename(),material.files[0].originalName);
    assert.equal(sha(await readFile(await download.path())),sha(bytes));
  }
  checks.push('Real UI attach and protected download: PDF and GP5, filenames and SHA-256 match');
  await page.screenshot({path:path.join(evidence,'desktop.png'),fullPage:true});
  await page.setViewportSize({width:390,height:844});
  await page.screenshot({path:path.join(evidence,'mobile.png'),fullPage:true});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth));
  checks.push('390px mobile layout: no horizontal overflow');
  const bob=await login(process.env.GM_TEST_LOGIN_B);
  await bob.tab.goto(base+'/music-archive');
  const search=await bob.context.request.get(base+'/api/v1/music-catalog/works');
  assert.equal(search.status(),200);assert.equal((await search.json()).items.length,0);
  assert.equal((await bob.context.request.get(base+'/api/v1/music-catalog/works/w196')).status(),404);
  for(const material of [pdfMaterial,gpMaterial])assert.equal((await bob.context.request.get(base+material.files[0].downloadUrl)).status(),404);
  checks.push('Second real local account cannot see private metadata or download either original');
  const guest=await browser.newContext();
  assert.equal((await guest.request.get(base+'/api/v1/music-catalog/works/w196')).status(),401);
  for(const material of [pdfMaterial,gpMaterial])assert.equal((await guest.request.get(base+material.files[0].downloadUrl)).status(),401);
  checks.push('Guest cannot read metadata or download originals');
  assert.deepEqual(errors,[]);
  await writeFile(path.join(evidence,'result.json'),JSON.stringify({status:'PASS',checks,pageErrors:errors},null,2)+'\n');
  console.log(JSON.stringify({status:'PASS',checks}));
} catch(e) {
  await writeFile(path.join(evidence,'result.json'),JSON.stringify({status:'FAIL',checks,error:String(e),pageErrors:errors},null,2)+'\n');
  throw e;
} finally {await browser?.close();await server.close();}
