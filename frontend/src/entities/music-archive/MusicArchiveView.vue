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
let listAbort:AbortController|null=null,detailAbort:AbortController|null=null;
function abortRequests():void {++listSeq;++detailSeq;listAbort?.abort();detailAbort?.abort();listAbort=null;detailAbort=null;}
function clearDetail():void {++detailSeq;detailAbort?.abort();detailAbort=null;selected.value=null;detail.value=null;detailStatus.value='none';detailError.value='';}
function clearAll():void {abortRequests();items.value=[];offset.value=0;nextOffset.value=null;visited.value=[];listStatus.value='idle';listError.value='';selected.value=null;detail.value=null;detailStatus.value='none';detailError.value='';draft.value={...DEFAULT_QUERY};applied.value={...DEFAULT_QUERY};}
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
  abortRequests(); const seq=listSeq;const controller=new AbortController();listAbort=controller;
  selected.value=null;detail.value=null;detailStatus.value='none';
  items.value=[];nextOffset.value=null;listError.value='';listStatus.value='loading';
  try {
    const result=await listWorks(query,at,controller.signal);
    if(controller.signal.aborted || listSeq!==seq || auth.actor!==actor)return;
    applied.value={...query};offset.value=at;visited.value=[...history];items.value=result.items;nextOffset.value=result.nextOffset;listStatus.value='ready';
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
function retry():void {void search({...applied.value},offset.value,[...visited.value],true);}
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
  <h1 id="archive-title">{{t('musicArchive.title')}}</h1>
  <p class="archive-muted">{{t('musicArchive.description')}}</p>
  <p v-if="!auth.actor" role="status">{{t('musicArchive.signIn')}}</p>
  <template v-else>
    <form class="archive-search" @submit.prevent="submit">
      <div class="archive-query">
        <label for="archive-q">{{t('musicArchive.searchLabel')}}</label>
        <input id="archive-q" v-model="draft.q" maxlength="150" type="search" autocomplete="off" />
        <button type="submit">{{t('musicArchive.search')}}</button>
        <button type="button" @click="reset">{{t('musicArchive.reset')}}</button>
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
      <p v-if="formError" role="alert">{{t(formError)}}</p>
      <p v-if="dirty" role="status">{{t('musicArchive.applyChanges')}}</p>
    </form>
    <div class="archive-layout">
      <section :aria-busy="listStatus==='loading'" aria-labelledby="archive-results">
        <h2 id="archive-results">{{t('musicArchive.results')}}</h2>
        <p v-if="listStatus==='loading'" role="status">{{t('musicArchive.loading')}}</p>
        <div v-else-if="listStatus==='error'" role="alert"><p>{{t(listError)}}</p><button type="button" @click="retry">{{t('musicArchive.retry')}}</button></div>
        <p v-else-if="listStatus==='ready' && !items.length" role="status">{{t(applied.q || applied.format || applied.parserStatus ? 'musicArchive.noMatches':'musicArchive.empty')}}</p>
        <ul v-if="listStatus==='ready' && items.length" class="archive-results-list">
          <li v-for="work in items" :key="work.id">
            <button :ref="(el)=>{if(el)resultButtons[work.id]=el as HTMLElement}" type="button" :aria-pressed="selected===work.id" class="archive-work" @click="choose(work.id)">
              <strong>{{textOr(work.title,'musicArchive.untitled')}}</strong>
              <span>{{textOr(work.artist,'musicArchive.artistMissing')}}</span>
              <small>{{work.identityStatus}}</small>
            </button>
          </li>
        </ul>
        <div v-if="listStatus==='ready'" class="archive-pagination">
          <button type="button" :disabled="dirty || !visited.length" @click="goPrevious">{{t('musicArchive.previous')}}</button>
          <span role="status">{{t('musicArchive.page',{page})}}</span>
          <button type="button" :disabled="!canNext" @click="goNext">{{t('musicArchive.next')}}</button>
        </div>
        <p v-if="listStatus==='ready' && nextOffset!==null && nextOffset>MAX_OFFSET">{{t('musicArchive.limitReached')}}</p>
      </section>
      <section v-if="selected" class="archive-detail" :aria-busy="detailStatus==='loading'">
        <button type="button" @click="backToResults">{{t('musicArchive.back')}}</button>
        <p v-if="detailStatus==='loading'" role="status">{{t('musicArchive.loadingDetail')}}</p>
        <div v-else-if="detailStatus==='error'" role="alert"><p>{{t(detailError)}}</p><button type="button" @click="selected && choose(selected)">{{t('musicArchive.retry')}}</button></div>
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
      <aside v-else class="archive-muted">{{t('musicArchive.choose')}}</aside>
    </div>
  </template>
</section>
</template>
<style scoped>
.music-archive{max-width:1280px;margin:0 auto;padding:24px;min-width:0}
.archive-muted{color:var(--gm-text-muted,#667085)}
.archive-search,.archive-detail,.archive-arrangement{border:1px solid var(--gm-border,#ddd);border-radius:14px;padding:18px;margin:18px 0;background:var(--gm-surface,#fff)}
.archive-query{display:flex;align-items:end;flex-wrap:wrap;gap:12px}
.archive-query label{flex-basis:100%}
.archive-query input{flex:1 1 210px;min-width:0}
.archive-filters{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;margin-top:16px}
.archive-filters label{display:flex;flex-direction:column;gap:6px}
.archive-filters select{width:100%;min-width:0}
.archive-layout{display:grid;grid-template-columns:minmax(280px,.9fr) minmax(0,1.1fr);gap:24px;align-items:start}
.archive-results-list,.archive-files{padding:0;list-style:none}
.archive-work{display:flex;flex-direction:column;align-items:start;width:100%;text-align:left;gap:5px;margin:5px 0;white-space:normal;overflow-wrap:anywhere}
.archive-work[aria-pressed=true]{outline:2px solid var(--gm-primary,#496ad8)}
.archive-pagination{display:flex;align-items:center;justify-content:space-between;gap:10px}
.archive-metadata{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}
.archive-metadata div{min-width:0;overflow-wrap:anywhere}
.archive-metadata dt{font-size:.85em;color:var(--gm-text-muted,#667085)}
.archive-metadata dd{margin:0}
.archive-files li{display:flex;flex-direction:column;border-top:1px solid var(--gm-border,#ddd);padding:10px 0;overflow-wrap:anywhere}
button:focus-visible,input:focus-visible,select:focus-visible{outline:2px solid var(--gm-primary,#496ad8);outline-offset:2px}
@media(max-width:1023px){.archive-layout{grid-template-columns:minmax(0,1fr)}}
@media(max-width:767px){.archive-filters{grid-template-columns:minmax(0,1fr)}}
@media(max-width:600px){.music-archive{padding:16px}.archive-metadata{grid-template-columns:minmax(0,1fr)}}
</style>