import test from 'node:test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import assert from 'node:assert/strict';
import { isPage, isDetail, validQuery, safeFileName, DEFAULT_QUERY, LIMIT, MAX_OFFSET } from '../src/entities/music-archive/music-archive.service.ts';
const work={id:'abc',title:'Yesterday',artist:'The Beatles',identityStatus:'CANDIDATE'};
const arr={id:'arr',name:'Fingerstyle',identityStatus:'UNRESOLVED',parserStatus:'PARSED',tempo:null,timeSignatures:'4/4',instruments:null,trackCount:'1',measureCount:null,noteCount:null};
const file={id:'file',name:'C:\\private\\score.gpx',format:'.gpx',sizeBytes:0,rightsStatus:'unknown',parserStatus:'PARSED',originalAvailability:'UNAVAILABLE'};
test('strict query validation and literal GP format',()=>{
 assert.ok(validQuery({...DEFAULT_QUERY,q:'Ветер перемен',format:'gp'}));
 assert.equal(validQuery({...DEFAULT_QUERY,format:'gtp'}),false);
 assert.equal(validQuery({...DEFAULT_QUERY,q:'a'.repeat(151)}),false);
 assert.equal(validQuery({...DEFAULT_QUERY,q:'x\u0000y'}),false);
});
test('page validation does not invent total or accept invalid offset',()=>{
 assert.ok(isPage({items:[work],limit:LIMIT,offset:0,nextOffset:20},0));
 assert.ok(isPage({items:[],limit:LIMIT,offset:MAX_OFFSET,nextOffset:MAX_OFFSET+20},MAX_OFFSET));
 assert.equal(isPage({items:[work],limit:LIMIT,offset:2,nextOffset:null},0),false);
 assert.equal(isPage({items:[{...work,id:''}],limit:LIMIT,offset:0,nextOffset:null},0),false);
 assert.equal(isPage({items:[],limit:LIMIT,offset:0,nextOffset:100},0),false);
});
test('detail requires consistent identity and unavailable originals',()=>{
 assert.ok(isDetail({work,arrangements:[{arrangement:arr,files:[file]}]},'abc'));
 assert.equal(isDetail({work,arrangements:[{arrangement:arr,files:[{...file,originalAvailability:'AVAILABLE'}]}]},'abc'),false);
 assert.equal(isDetail({work,arrangements:[]},'another'),false);
 assert.equal(isDetail({work,arrangements:[{arrangement:arr,files:[]},{arrangement:arr,files:[]}]},'abc'),false);
});
test('file name sanitized to basename without control characters',()=>{
 assert.equal(safeFileName('D:\\secret\\Yesterday.gpx'),'Yesterday.gpx');
 assert.equal(safeFileName('/private/file\u0000.gp'),'file.gp');
});

test('private archive stays visible in primary navigation and both routes coexist',()=>{
 const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
 const shell=readFileSync(resolve(root,'src/App.vue'),'utf8');
 const routes=readFileSync(resolve(root,'src/router/index.ts'),'utf8');
 const view=readFileSync(resolve(root,'src/entities/music-archive/MusicArchiveView.vue'),'utf8');
 assert.match(shell,/<RouterLink v-if="auth.actor" to="\/music-archive" @click="closeMenu\(\)"/);
 assert.match(shell,/to="\/library"/);
 assert.match(routes,/name: 'music-archive'/);
 assert.match(routes,/name: 'library'/);
 assert.match(view,/archive-empty/);
 assert.match(view,/archive-button archive-primary/);
 assert.match(view,/archive-input-wrap/);
 const packageJson=JSON.parse(readFileSync(resolve(root,'package.json'),'utf8'));
 assert.match(packageJson.scripts.test,/private-library\.test\.mjs/);
 assert.match(packageJson.scripts.test,/music-archive\.test\.mjs/);
});
