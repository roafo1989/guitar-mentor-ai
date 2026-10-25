<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { auth } from '../../core/auth/auth';
import { CatalogError, DEFAULT_QUERY, LIMIT, MAX_OFFSET, getWork, listWorks, safeFileName, validQuery, type CatalogQuery, type Work, type WorkDetail } from './music-archive.service';

const {t,locale}=useI18n();
const draft=ref<CatalogQuery>({...DEFAULT_QUERY});
const applied=ref<CatalogQuery>({...DEFAULT_QUERY});
const items=ref<Work[]>([]);
const offset=ref(0);
const visited=ref<number[]>([]);
const nextOffset=ref<number|null>(null);
const listStatus=ref<'idle'|'loading'|'ready'|'error'>('idle');
const detailStatus=ref<'none'|'loading'|'ready'|'error'>('none');
const listError=ref('');
const detailError=ref('');
const formError=ref('');
const selected=ref<string|null>(null);
const detail=ref<WorkDetail|null>(null);
const heading=ref<HTMLElement|null>(null);
const resultButtons=ref<Record<string,HTMLElement>>({});
const dirty=computed(()=>JSON.stringify(draft.value)!==JSON.stringify(applied.value));
const page=computed(()=>Math.floor(offset.value/LIMIT)+1);
const canNext=computed(()=>!dirty.value && listStatus.value==='ready' && nextOffset.value!==null && nextOffset.value<=MAX_OFFSET);
let listSeq=0,detailSeq=0;
let failedRequest:{query:CatalogQuery;offset:number;history:number[]}|null=null;
let listAbort:AbortController|null=null,detailAbort:AbortController|null=null;
function abortRequests():void {++listSeq;++detailSeq;listAbort?.abort();detailAbort?.abort();listAbort=null;detailAbort=null;}
function clearDetail():void {++detailSeq;detailAbort?.abort();detailAbort=null;selected.value=null;detail.value=null;detailStatus.value='none';detailError.value='';}
function clearAll():void {failedRequest=null;abortRequests();items.value=[];offset.value=0;nextOffset.value=null;visited.value=[];listStatus.value='idle';listError.value='';selected.value=null;detail.value=null;detailStatus.value='none';detailError.value='';draft.value={...DEFAULT_QUERY};applied.value={...DEFAULT_QUERY};}
function errorKey(cause:unknown,isDetail=false):string {
  if(cause instanceof CatalogError) {
    if(cause.kind==='TIMEOUT')return 'musicArchive.timeout';
    if(cause.kind==='RESPONSE_LIMIT')return 'musicArchive.tooLarge';
    if(cause.kind==='INVALID_RESPONSE')return 'musicArchive.invalidResponse';
    if(cause.kind==='NETWORK')return 'musicArchive.network';
    if(cause.status===404)return isDetail?'musicArchive.notFound':'musicArchive.unavailable';
    if(cause.status===403)return 'musicArchive.denied';
    if(cause.status===400)return 'musicArchive.invalidQuery';
  }
  return 'musicArchive.unavailable';
}
async function search(query:CatalogQuery,at:number,history:number[],force=false):Promise<void> {
  if(!auth.actor)return;
  if(!force && listStatus.value==='loading' && at===offset.value && JSON.stringify(query)===JSON.stringify(applied.value))return;
  const actor=auth.actor;
  failedRequest={query:{...query},offset:at,history:[...history]};
  abortRequests(); const seq=listSeq;const controller=new AbortController();listAbort=controller;
  selected.value=null;detail.value=null;detailStatus.value='none';
  items.value=[];nextOffset.value=null;listError.value='';listStatus.value='loading';
  try {
    const result=await listWorks(query,at,controller.signal);
    if(controller.signal.aborted || listSeq!==seq || auth.actor!==actor)return;
    applied.value={...query};offset.value=at;visited.value=[...history];items.value=result.items;nextOffset.value=result.nextOffset;listStatus.value='ready';failedRequest=null;
  } catch(e) {
    if(controller.signal.aborted || listSeq!==seq || auth.actor!==actor)return;
    listError.value=errorKey(e);listStatus.value='error';
  } finally {
    if(listSeq===seq && auth.actor===actor)listAbort=null;
  }
}
function submit():void {
  const q={...draft.value,q:draft.value.q.trim()};
  if(!validQuery(q)){formError.value='musicArchive.invalidQuery';return;}
  formError.value='';
  if(listStatus.value==='loading' && JSON.stringify(q)===JSON.stringify(applied.value) && offset.value===0)return;
  void search(q,0,[]);
}
function reset():void {draft.value={...DEFAULT_QUERY};formError.value='';void search({...DEFAULT_QUERY},0,[]);}
function goNext():void {if(canNext.value && nextOffset.value!==null)void search({...applied.value},nextOffset.value,[...visited.value,offset.value]);}
function goPrevious():void {if(!dirty.value && visited.value.length){const offsets=visited.value.slice(0,-1);void search({...applied.value},visited.value[visited.value.length-1],offsets);}}
function retry():void {const target=failedRequest; if(target)void search({...target.query},target.offset,[...target.history],true);}
async function choose(id:string):Promise<void> {
  if(!auth.actor)return;
  const actor=auth.actor;clearDetail();selected.value=id;const seq=detailSeq;const controller=new AbortController();detailAbort=controller;detailStatus.value='loading';
  try {
    const result=await getWork(id,controller.signal);
    if(controller.signal.aborted || seq!==detailSeq || auth.actor!==actor || selected.value!==id)return;
    detail.value=result;detailStatus.value='ready';
    await nextTick();
    if(seq===detailSeq && auth.actor===actor)heading.value?.focus();
  }catch(e){
    if(controller.signal.aborted || seq!==detailSeq || auth.actor!==actor)return;
    detailError.value=errorKey(e,true);detailStatus.value='error';
  }finally{if(seq===detailSeq && auth.actor===actor)detailAbort=null;}
}
function backToResults():void {const id=selected.value;clearDetail();void nextTick(()=>{if(id)resultButtons.value[id]?.focus();});}
const textOr=(value:string|null|undefined,key='musicArchive.notSpecified')=>value?.trim()||t(key);
watch(()=>auth.actor,(actor,previous)=>{if(actor!==previous){clearAll();if(actor)void search({...DEFAULT_QUERY},0,[]);}}, {immediate:true,flush:'sync'});
onBeforeUnmount(clearAll);
</script>
<template>
<section class="music-archive" aria-labelledby="archive-title">
  <header class="archive-heading"><div><p class="archive-eyebrow">{{t('musicArchive.eyebrow')}}</p><h1 id="archive-title">{{t('musicArchive.title')}}</h1><p class="archive-intro">{{t('musicArchive.description')}}</p></div></header>
  <p v-if="!auth.actor" role="status">{{t('musicArchive.signIn')}}</p>
  <template v-else>
    <form class="archive-search" @submit.prevent="submit">
      <label class="archive-search-label" for="archive-q">{{t('musicArchive.searchLabel')}}</label>
      <div class="archive-query">
        <div class="archive-input-wrap"><svg aria-hidden="true" viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="10.8" cy="10.8" r="6.3"/><path d="m16 16 4.5 4.5"/></svg><input id="archive-q" v-model="draft.q" maxlength="150" type="search" autocomplete="off" :placeholder="t('musicArchive.searchPlaceholder')" /></div>
        <button class="archive-button archive-primary" type="submit">{{t('musicArchive.search')}}</button>
        <button class="archive-button archive-secondary" type="button" @click="reset">{{t('musicArchive.reset')}}</button>
      </div>
      <div class="archive-filters">
        <label>{{t('musicArchive.format')}}<select v-model="draft.format">
          <option value="">{{t('musicArchive.all')}}</option><option v-for="f in ['pdf','gp','gp3','gp4','gp5','gpx']" :key="f" :value="f">{{f.toUpperCase()}}</option>
        </select></label>
        <label>{{t('musicArchive.parser')}}<select v-model="draft.parserStatus">
          <option value="">{{t('musicArchive.all')}}</option><option value="PARSED">{{t('musicArchive.parsed')}}</option><option value="METADATA_ONLY">{{t('musicArchive.metadataOnly')}}</option>
        </select></label>
        <label>{{t('musicArchive.sort')}}<select v-model="draft.sort">
          <option value="title">{{t('musicArchive.sortTitle')}}</option><option value="artist">{{t('musicArchive.sortArtist')}}</option><option value="title_desc">{{t('musicArchive.sortTitleDesc')}}</option>
        </select></label>
      </div>
      <p v-if="formError" class="archive-alert" role="alert">{{t(formError)}}</p>
      <p v-if="dirty" class="archive-hint" role="status">{{t('musicArchive.applyChanges')}}</p>
    </form>
    <div class="archive-layout">
      <section :aria-busy="listStatus==='loading'" aria-labelledby="archive-results">
        <h2 id="archive-results" class="archive-section-title">{{t('musicArchive.results')}}</h2>
        <p v-if="listStatus==='loading'" class="archive-state" role="status">{{t('musicArchive.loading')}}</p>
        <div v-else-if="listStatus==='error'" class="archive-state archive-alert" role="alert"><p>{{t(listError)}}</p><button class="archive-button archive-secondary" type="button" @click="retry">{{t('musicArchive.retry')}}</button></div>
        <div v-else-if="listStatus==='ready' && !items.length" class="archive-empty" role="status">
          <div class="archive-empty-icon" aria-hidden="true"><svg viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M9 18V5l12-2v13"/><ellipse cx="6" cy="18" rx="3" ry="2"/><ellipse cx="18" cy="16" rx="3" ry="2"/></svg></div>
          <h3>{{t(applied.q || applied.format || applied.parserStatus ? 'musicArchive.noMatchesTitle':'musicArchive.emptyTitle')}}</h3>
          <p>{{t(applied.q || applied.format || applied.parserStatus ? 'musicArchive.noMatches':'musicArchive.empty')}}</p>
          <button v-if="applied.q || applied.format || applied.parserStatus" class="archive-button archive-secondary" type="button" @click="reset">{{t('musicArchive.reset')}}</button>
        </div>
        <ul v-if="listStatus==='ready' && items.length" class="archive-results-list">
          <li v-for="work in items" :key="work.id">
            <button :ref="(el)=>{if(el)resultButtons[work.id]=el as HTMLElement}" type="button" :aria-pressed="selected===work.id" class="archive-work" @click="choose(work.id)">
              <strong>{{textOr(work.title,'musicArchive.untitled')}}</strong>
              <span>{{textOr(work.artist,'musicArchive.artistMissing')}}</span>
              
            </button>
          </li>
        </ul>
        <div v-if="listStatus==='ready' && (items.length>0 || visited.length>0)" class="archive-pagination">
          <button class="archive-button archive-secondary" type="button" :disabled="dirty || !visited.length" @click="goPrevious">{{t('musicArchive.previous')}}</button>
          <span role="status">{{t('musicArchive.page',{page})}}</span>
          <button class="archive-button archive-secondary" type="button" :disabled="!canNext" @click="goNext">{{t('musicArchive.next')}}</button>
        </div>
        <p v-if="listStatus==='ready' && nextOffset!==null && nextOffset>MAX_OFFSET">{{t('musicArchive.limitReached')}}</p>
      </section>
      <section v-if="selected" class="archive-detail" :aria-busy="detailStatus==='loading'">
        <button class="archive-button archive-secondary archive-back" type="button" @click="backToResults">{{t('musicArchive.back')}}</button>
        <p v-if="detailStatus==='loading'" role="status">{{t('musicArchive.loadingDetail')}}</p>
        <div v-else-if="detailStatus==='error'" role="alert"><p>{{t(detailError)}}</p><button class="archive-button archive-secondary" type="button" @click="selected && choose(selected)">{{t('musicArchive.retry')}}</button></div>
        <template v-else-if="detailStatus==='ready' && detail">
          <h2 ref="heading" tabindex="-1">{{textOr(detail.work.title,'musicArchive.untitled')}}</h2>
          <p>{{textOr(detail.work.artist,'musicArchive.artistMissing')}}</p>
          <p class="archive-muted">{{t('musicArchive.allArrangements')}}</p>
          <article v-for="entry in detail.arrangements" :key="entry.arrangement.id" class="archive-arrangement">
            <h3>{{textOr(entry.arrangement.name)}}</h3>
            <dl class="archive-metadata">
              <div v-for="field in (['parserStatus','tempo','timeSignatures','instruments','trackCount','measureCount','noteCount'] as const)" :key="field">
                <dt>{{t('musicArchive.fields.'+field)}}</dt><dd>{{textOr(entry.arrangement[field])}}</dd>
              </div>
            </dl>
            <ul class="archive-files">
              <li v-for="file in entry.files" :key="file.id">
                <strong>{{textOr(safeFileName(file.name))}}</strong>
                <span>{{file.format}} · {{new Intl.NumberFormat(locale).format(file.sizeBytes)}} B · {{file.rightsStatus}} · {{file.parserStatus}}</span>
                <span>{{t('musicArchive.originalUnavailable')}}</span>
              </li>
            </ul>
          </article>
        </template>
      </section>
      <aside v-else-if="listStatus==='ready' && items.length" class="archive-placeholder">{{t('musicArchive.choose')}}</aside>
    </div>
  </template>
</section>
</template>
<style scoped>

.music-archive{max-width:1280px;margin:0 auto;padding:32px 24px 56px;min-width:0;color:var(--gm-text,#162331)}
.archive-heading{margin-bottom:26px}
.archive-eyebrow{font-size:12px;letter-spacing:.11em;font-weight:750;text-transform:uppercase;color:var(--gm-primary,#28776d);margin:0 0 6px}
.archive-heading h1{font-size:clamp(26px,2.5vw,36px);line-height:1.2;letter-spacing:-.025em;margin:0 0 12px}
.archive-intro{margin:0;line-height:1.55;color:var(--gm-muted,#53636d);font-size:14px;max-width:780px}
.archive-muted{color:var(--gm-muted,#53636d)}
.archive-search{border:1px solid var(--gm-border,#dde5e4);border-radius:var(--gm-radius-lg,16px);background:var(--gm-surface,#fff);padding:22px 24px;margin:0 0 30px;box-shadow:var(--gm-shadow,0 4px 22px #1623310f)}
.archive-search-label{display:block;font-size:13px;font-weight:650;margin:0 0 9px}
.archive-query{display:flex;align-items:stretch;flex-wrap:wrap;gap:10px}
.archive-input-wrap{display:flex;align-items:center;gap:10px;border:1px solid var(--gm-control-border,#7c8987);border-radius:var(--gm-radius-sm,8px);min-height:44px;flex:1 1 260px;padding:0 13px;min-width:0;color:var(--gm-muted,#53636d);background:#fff}
.archive-input-wrap:focus-within{border-color:var(--gm-primary,#28776d);box-shadow:0 0 0 2px #28776d26}
.archive-input-wrap input{border:0!important;outline:0!important;box-shadow:none!important;flex:1;min-width:0;width:100%;font:inherit;font-size:14px;color:var(--gm-text,#162331);background:transparent;padding:10px 0}
.archive-input-wrap input::placeholder{color:#889491}
.archive-button{font:inherit;font-size:13px;font-weight:650;min-height:44px;display:inline-flex;align-items:center;justify-content:center;gap:8px;padding:10px 19px;border-radius:var(--gm-radius-sm,8px);white-space:nowrap;cursor:pointer;transition:background .12s,border-color .12s}
.archive-button.archive-primary{background:var(--gm-primary,#28776d);border:1px solid var(--gm-primary,#28776d);color:#fff}
.archive-button.archive-primary:hover:not(:disabled){background:var(--gm-primary-hover,#205f57)}
.archive-button.archive-secondary{background:#fff!important;color:var(--gm-text,#162331)!important;border:1px solid var(--gm-border,#dde5e4)!important}
.archive-button.archive-secondary:hover:not(:disabled){border-color:#b6cbc5!important;background:#eef5f2!important}
.archive-button:disabled{cursor:not-allowed;opacity:.45}
.archive-filters{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;margin-top:20px}
.archive-filters label{display:flex;flex-direction:column;gap:7px;min-width:0;font-size:13px;font-weight:600;color:var(--gm-muted,#53636d)}
.archive-filters select{width:100%;min-width:0;height:44px;background:#fff;color:var(--gm-text,#162331);font:inherit;font-size:14px;font-weight:450;border:1px solid var(--gm-border,#dde5e4);border-radius:var(--gm-radius-sm,8px);padding:0 12px}
.archive-section-title{font-size:19px;letter-spacing:-.01em;line-height:1.35;margin:0 0 16px;font-weight:750}
.archive-layout{display:grid;grid-template-columns:minmax(280px,.9fr) minmax(0,1.1fr);gap:24px;align-items:start}
.archive-layout>section{min-width:0}
.archive-results-list,.archive-files{padding:0;margin:0;list-style:none}
.archive-results-list{border:1px solid var(--gm-border,#dde5e4);border-radius:14px;background:#fff;overflow:hidden}
.archive-results-list li+li{border-top:1px solid var(--gm-border,#dde5e4)}
.archive-work{display:flex;flex-direction:column;align-items:stretch;width:100%;min-height:66px;text-align:left;gap:5px;padding:16px 18px;white-space:normal;overflow-wrap:anywhere;background:#fff;border:0;border-left:3px solid transparent;border-radius:0;color:var(--gm-text,#162331);cursor:pointer;font:inherit}
.archive-work strong{font-size:14px;font-weight:700;line-height:1.3}
.archive-work span{font-size:13px;color:var(--gm-muted,#53636d)}
.archive-work:hover{background:#f4f8f6}
.archive-work[aria-pressed=true]{background:#eef5f2;border-left-color:var(--gm-primary,#28776d)}
.archive-pagination{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:15px}
.archive-pagination span{font-size:13px;color:var(--gm-muted,#53636d)}
.archive-empty{display:flex;flex-direction:column;align-items:center;text-align:center;justify-content:center;min-height:270px;padding:36px 24px;border:1px dashed #cbdad5;border-radius:14px;background:#fff}
.archive-empty-icon{width:60px;height:60px;border-radius:16px;background:#eaf5ef;color:var(--gm-primary,#28776d);display:grid;place-items:center;margin-bottom:16px}
.archive-empty h3{margin:0 0 7px;font-size:16px;line-height:1.35}
.archive-empty p{font-size:13px;color:var(--gm-muted,#53636d);line-height:1.5;max-width:340px;margin:0 0 12px}
.archive-state,.archive-placeholder{border:1px solid var(--gm-border,#dde5e4);border-radius:14px;padding:28px;background:#fff;color:var(--gm-muted,#53636d);font-size:14px}
.archive-placeholder{margin-top:42px;text-align:center;min-height:180px;display:grid;place-items:center}
.archive-alert{color:#9b3434}
.archive-hint{font-size:13px;color:var(--gm-muted,#53636d);margin:12px 0 0}
.archive-detail{border:1px solid var(--gm-border,#dde5e4);border-radius:14px;padding:24px;margin-top:42px;background:#fff;box-shadow:var(--gm-shadow,0 4px 22px #1623310f);min-width:0;overflow-wrap:anywhere}
.archive-detail h2{font-size:21px;margin:20px 0 8px}
.archive-detail h3{font-size:15px;margin:0 0 12px}
.archive-detail p{font-size:14px}
.archive-arrangement{border-top:1px solid var(--gm-border,#dde5e4);padding:20px 0 2px;margin-top:20px}
.archive-metadata{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}
.archive-metadata div{min-width:0;overflow-wrap:anywhere}
.archive-metadata dt{font-size:12px;color:var(--gm-muted,#53636d)}
.archive-metadata dd{margin:3px 0 0;font-size:14px}
.archive-files li{display:flex;flex-direction:column;gap:4px;border-top:1px solid var(--gm-border,#dde5e4);padding:12px 0;overflow-wrap:anywhere;font-size:13px}
.archive-files li span{color:var(--gm-muted,#53636d)}
.archive-back{margin-bottom:5px}
.music-archive button:focus-visible,.music-archive input:focus-visible,.music-archive select:focus-visible{outline:3px solid var(--gm-focus,#205f57);outline-offset:3px}
@media(max-width:1023px){.archive-layout{grid-template-columns:minmax(0,1fr)}.archive-detail{margin-top:0}.archive-placeholder{display:none}}
@media(max-width:767px){.archive-filters{grid-template-columns:minmax(0,1fr)}}
@media(max-width:600px){.music-archive{padding:22px 16px}.archive-search{padding:18px 16px}.archive-query .archive-input-wrap{flex-basis:100%}.archive-query .archive-button{flex:1 1 0}.archive-metadata{grid-template-columns:minmax(0,1fr)}.archive-detail{padding:18px}.archive-pagination .archive-button{padding:9px 11px}}
@media(prefers-reduced-motion:reduce){.archive-button{transition:none}}
</style>
