export type Work = { id: string; title: string; artist: string; identityStatus: string };
export type Arrangement = { id: string; name: string; identityStatus: string; parserStatus: string; tempo: string|null; timeSignatures: string|null; instruments: string|null; trackCount: string|null; measureCount: string|null; noteCount: string|null };
export type SourceFile = { id: string; name: string; format: string; sizeBytes: number; rightsStatus: string; parserStatus: string; originalAvailability: 'UNAVAILABLE' };
export type WorkDetail = { work: Work; arrangements: { arrangement: Arrangement; files: SourceFile[] }[] };
export type WorkPage = { items: Work[]; limit: number; offset: number; nextOffset: number|null };
export type CatalogQuery = { q: string; format: string; parserStatus: string; sort: 'title'|'artist'|'title_desc' };
export const DEFAULT_QUERY: CatalogQuery = { q:'', format:'', parserStatus:'', sort:'title' };
export const LIMIT = 20;
export const MAX_OFFSET = 200000;
export class CatalogError extends Error {
  constructor(public readonly kind: 'NETWORK'|'HTTP'|'INVALID_RESPONSE'|'TIMEOUT'|'RESPONSE_LIMIT', public readonly status = 0) { super(kind); }
}
const obj = (v: unknown): v is Record<string,unknown> => !!v && typeof v === 'object' && !Array.isArray(v);
const str = (v: unknown): v is string => typeof v === 'string';
const id = (v: unknown): v is string => str(v) && !!v.trim() && v.length <= 64 && v !== '.' && v !== '..' && !/[\x00-\x1f\x7f-\x9f/\\]/.test(v);
const nullable = (v: unknown): v is string|null => v === null || str(v);
const work = (v: unknown): v is Work => obj(v) && id(v.id) && str(v.title) && str(v.artist) && str(v.identityStatus);
const arrangement = (v: unknown): v is Arrangement => obj(v) && id(v.id) && str(v.name) && str(v.identityStatus) && str(v.parserStatus) && ['tempo','timeSignatures','instruments','trackCount','measureCount','noteCount'].every(k=>nullable(v[k]));
const file = (v: unknown): v is SourceFile => obj(v) && id(v.id) && str(v.name) && str(v.format) && typeof v.sizeBytes === 'number' && Number.isSafeInteger(v.sizeBytes) && (v.sizeBytes as number)>=0 && str(v.rightsStatus) && str(v.parserStatus) && v.originalAvailability === 'UNAVAILABLE';
export function validQuery(q: CatalogQuery): boolean {
  return q.q.length<=150 && !/[\x00-\x1f\x7f-\x9f]/.test(q.q) && ['','gp','gp3','gp4','gp5','gpx','pdf'].includes(q.format) && ['','PARSED','METADATA_ONLY'].includes(q.parserStatus) && ['title','artist','title_desc'].includes(q.sort);
}
export function safeFileName(name: string): string {
  return name.replace(/[\x00-\x1f\x7f-\x9f]/g,'').split(/[\\/]/).pop() || '';
}
export function isPage(v: unknown, offset: number): v is WorkPage {
  return obj(v) && v.limit===LIMIT && v.offset===offset && Array.isArray(v.items) && v.items.length<=LIMIT && v.items.every(work) && (v.nextOffset===null || (typeof v.nextOffset === 'number' && Number.isSafeInteger(v.nextOffset) && v.nextOffset===offset+LIMIT));
}
export function isDetail(v: unknown, expectedId: string): v is WorkDetail {
  if (!obj(v) || !work(v.work) || v.work.id!==expectedId || !Array.isArray(v.arrangements)) return false;
  const ids = new Set<string>(); const fileIds = new Set<string>();
  return v.arrangements.every(item=>{
    if (!obj(item) || !arrangement(item.arrangement) || ids.has(item.arrangement.id) || !Array.isArray(item.files) || !item.files.every(file)) return false;
    ids.add(item.arrangement.id);
    for (const f of item.files as SourceFile[]) { if (fileIds.has(f.id)) return false; fileIds.add(f.id); }
    return true;
  });
}
async function request(url: string, signal: AbortSignal): Promise<unknown> {
  const timeout = new AbortController();
  const timer = setTimeout(()=>timeout.abort(),15000);
  const abort = ()=>timeout.abort();
  signal.addEventListener('abort',abort,{once:true});
  try {
    let response: Response;
    try { response=await fetch(url,{credentials:'same-origin',cache:'no-store',signal:timeout.signal,headers:{Accept:'application/json'}}); }
    catch { if(signal.aborted) throw new DOMException('Aborted','AbortError'); if(timeout.signal.aborted) throw new CatalogError('TIMEOUT'); throw new CatalogError('NETWORK'); }
    if (!response.ok) throw new CatalogError('HTTP',response.status);
    if (!/\bapplication\/json\b/i.test(response.headers.get('Content-Type')||'')) throw new CatalogError('INVALID_RESPONSE');
    const max = 2*1024*1024;
    const sizeHeader = Number(response.headers.get('Content-Length'));
    if (sizeHeader>max) throw new CatalogError('RESPONSE_LIMIT');
    if(!response.body) throw new CatalogError('INVALID_RESPONSE');
    const reader=response.body.getReader(); const chunks:Uint8Array[]=[];let size=0;
    try {
      while(true) {
        const part=await reader.read();if(part.done) break;
        size+=part.value.byteLength;
        if(size>max) { await reader.cancel(); throw new CatalogError('RESPONSE_LIMIT'); }
        chunks.push(part.value);
      }
    } catch(e) { if(signal.aborted) throw new DOMException('Aborted','AbortError');if(timeout.signal.aborted) throw new CatalogError('TIMEOUT'); throw e; }
    const bytes=new Uint8Array(size);let at=0;for(const chunk of chunks){bytes.set(chunk,at);at+=chunk.length;}
    try {return JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes)) as unknown;}
    catch {throw new CatalogError('INVALID_RESPONSE');}
  } finally {clearTimeout(timer);signal.removeEventListener('abort',abort);}
}
export async function listWorks(query: CatalogQuery, offset: number, signal: AbortSignal): Promise<WorkPage> {
  if (!validQuery(query) || !Number.isSafeInteger(offset) || offset<0 || offset>MAX_OFFSET || offset%LIMIT!==0) throw new CatalogError('INVALID_RESPONSE');
  const params = new URLSearchParams({q:query.q.trim(),format:query.format,parserStatus:query.parserStatus,sort:query.sort,limit:String(LIMIT),offset:String(offset)});
  const result=await request('/api/v1/music-catalog/works?'+params.toString(),signal);
  if(!isPage(result,offset)) throw new CatalogError('INVALID_RESPONSE');
  return result;
}
export async function getWork(workId: string, signal: AbortSignal): Promise<WorkDetail> {
  if(!id(workId)) throw new CatalogError('INVALID_RESPONSE');
  const result=await request('/api/v1/music-catalog/works/'+encodeURIComponent(workId),signal);
  if(!isDetail(result,workId)) throw new CatalogError('INVALID_RESPONSE');
  return result;
}
