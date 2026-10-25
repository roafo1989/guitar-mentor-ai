import test from 'node:test';
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
