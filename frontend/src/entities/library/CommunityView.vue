<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRoute, useRouter } from 'vue-router';
import { auth } from '../../core/auth/auth';
import { communityService, normalizeTags, type Summary, type Publication } from './community.service';
import { libraryError } from './library-errors';
import LibraryAssets from './LibraryAssets.vue';
const {t}=useI18n(), route=useRoute(), router=useRouter();
const scalar=(v:unknown)=>typeof v==='string'?v:'';
const q=ref(scalar(route.query.q).slice(0,100)), type=ref(scalar(route.query.type)), tags=ref(scalar(route.query.tags)), hasAudio=ref(scalar(route.query.hasAudio));
const items=ref<Summary[]>([]), selected=ref<Publication|null>(null), cursor=ref<string|null>(null), loading=ref(false), detailLoading=ref(false), error=ref(''), detailError=ref(''), lastId=ref('');
let controller:AbortController|null=null, detailController:AbortController|null=null, generation=0, detailGeneration=0, timer:ReturnType<typeof setTimeout>|null=null, active=true;
const filterKey=computed(()=>JSON.stringify([q.value,type.value,tags.value,hasAudio.value]));
function clear(){++generation;++detailGeneration;controller?.abort();detailController?.abort();if(timer)clearTimeout(timer);items.value=[];selected.value=null;cursor.value=null;error.value='';detailError.value='';lastId.value='';loading.value=false;detailLoading.value=false;}
async function load(more=false){
  const ticket=++generation, actor=auth.actor?.accountId;controller?.abort();controller=new AbortController();
  if(!actor)return;loading.value=true;error.value='';
  try {
    const filters={q:q.value.trim(),type:type.value,tags:normalizeTags(tags.value),hasAudio:hasAudio.value};
    const page=await communityService.search(filters,more?cursor.value:null,controller.signal);
    if(!active||ticket!==generation||auth.actor?.accountId!==actor)return;
    items.value=more?[...items.value,...page.items.filter(p=>!items.value.some(old=>old.id===p.id))]:page.items;cursor.value=page.nextCursor;
  }catch(e){if(active&&ticket===generation&&!controller.signal.aborted)error.value=libraryError(e,t);}
  finally{if(ticket===generation)loading.value=false;}
}
async function open(id:string){
  ++detailGeneration;detailController?.abort();detailController=new AbortController();const ticket=detailGeneration, actor=auth.actor?.accountId;
  selected.value=null;detailError.value='';lastId.value=id;detailLoading.value=true;
  try{const card=await communityService.detail(id,detailController.signal);if(active&&ticket===detailGeneration&&actor===auth.actor?.accountId)selected.value=card;}
  catch(e){if(active&&ticket===detailGeneration&&!detailController.signal.aborted)detailError.value=libraryError(e,t);}
  finally{if(ticket===detailGeneration)detailLoading.value=false;}
}
watch(filterKey,()=>{
  clear();
  const safe={mode:'common',q:q.value.slice(0,100)||undefined,type:type.value||undefined,tags:tags.value||undefined,hasAudio:hasAudio.value||undefined};
  void router.replace({query:safe});timer=setTimeout(()=>void load(),300);
});
watch(()=>[route.query.q,route.query.type,route.query.tags,route.query.hasAudio],()=>{q.value=scalar(route.query.q).slice(0,100);type.value=scalar(route.query.type);tags.value=scalar(route.query.tags);hasAudio.value=scalar(route.query.hasAudio);});
watch(()=>auth.actor?.accountId,()=>{clear();if(auth.actor)void load();},{immediate:true,flush:'sync'});
onBeforeUnmount(()=>{active=false;clear();});
function reset(){q.value='';type.value='';tags.value='';hasAudio.value='';}
function close(){selected.value=null;++detailGeneration;detailController?.abort();lastId.value='';detailError.value='';}
</script>
<template>
<section class="community-panel">
  <h2>{{t('community.common')}}</h2>
  <form class="filters" @submit.prevent="load()">
    <label>{{t('community.search')}}<input v-model="q" type="search" maxlength="100" /></label>
    <label>{{t('library.type')}}<select v-model="type"><option value="">{{t('community.all')}}</option><option v-for="v in ['COMPOSITION','ETUDE','EXERCISE','OTHER']" :key="v" :value="v">{{t('library.types.'+v)}}</option></select></label>
    <label>{{t('community.tags')}}<input v-model="tags" maxlength="410" :aria-describedby="'tag-help'" /></label>
    <label>{{t('community.audio')}}<select v-model="hasAudio"><option value="">{{t('community.all')}}</option><option value="true">{{t('community.yes')}}</option><option value="false">{{t('community.no')}}</option></select></label>
    <button type="button" @click="reset">{{t('community.reset')}}</button>
  </form>
  <p id="tag-help">{{t('community.tagHelp')}}</p>
  <p v-if="loading" role="status">{{t('library.loading')}}</p>
  <div v-if="error" role="alert"><p>{{error}}</p><button :disabled="loading" @click="load()">{{t('community.retry')}}</button></div>
  <p v-else-if="!loading&&!items.length">{{t('community.empty')}}</p>
  <ul class="results"><li v-for="item in items" :key="item.id"><button @click="open(item.id)"><strong>{{item.title}}</strong><span>{{t('library.types.'+item.type)}} · {{item.author||'—'}}</span><span>{{item.tags.join(' · ')}}</span></button></li></ul>
  <button v-if="cursor" :disabled="loading" @click="load(true)">{{t('community.more')}}</button>
  <p v-if="detailLoading" role="status">{{t('library.loading')}}</p>
  <div v-if="detailError" role="alert">{{detailError}} <button @click="open(lastId)">{{t('community.retry')}}</button></div>
  <section v-if="selected" :key="selected.id" class="detail" :aria-label="selected.title">
    <button @click="close">{{t('library.close')}}</button><h3>{{selected.title}}</h3>
    <p>{{t('library.types.'+selected.type)}} · {{selected.author||'—'}}</p><p>{{selected.tags.join(' · ')}}</p>
    <p class="badge">{{t('community.unverified')}}</p>
    <LibraryAssets kind="publication" :id="selected.id" :assets="selected.assets" />
  </section>
</section>
</template>
<style scoped>
.community-panel{background:#fff;border:1px solid #dbe8e4;border-radius:18px;padding:24px;min-width:0}.filters{display:flex;gap:12px;flex-wrap:wrap;align-items:end}label{display:flex;flex-direction:column;gap:6px;flex:1 1 160px;min-width:0}input,select,button{font:inherit;min-height:44px;border:1px solid #b8c9ca;border-radius:8px;padding:8px;box-sizing:border-box;background:white;color:#285a54;max-width:100%}button{cursor:pointer}button:disabled{opacity:.5}input:focus-visible,select:focus-visible,button:focus-visible{outline:3px solid #28776d;outline-offset:2px}.results{list-style:none;padding:0;display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,260px),1fr));gap:12px}.results button{width:100%;text-align:left;overflow-wrap:anywhere;padding:16px}.results span{display:block;margin-top:8px;color:#657487}.detail{border-top:1px solid #dbe8e4;margin-top:24px;padding-top:16px;overflow-wrap:anywhere}.badge{padding:12px;background:#f4f6ef;border-radius:8px}
</style>
