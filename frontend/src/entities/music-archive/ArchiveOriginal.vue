<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { auth } from '../../core/auth/auth';
import { libraryService, type LibraryMaterial, type LibraryFile } from '../library/library.service';
import { bindOriginal, type SourceFile } from './music-archive.service';

const props=defineProps<{file:SourceFile}>();
const emit=defineEmits<{changed:[]}>();
const {t}=useI18n();
const picking=ref(false), busy=ref(false), error=ref('');
const choices=ref<{material:LibraryMaterial;file:LibraryFile}[]>([]);
const cursor=ref<string|null>(null);
const abort=new AbortController();
let sequence=0;
const current=(actor:typeof auth.actor, seq:number)=>!abort.signal.aborted && auth.actor===actor && sequence===seq;
onBeforeUnmount(()=>{++sequence;abort.abort();choices.value=[];});
async function load(next=false):Promise<void> {
  const actor=auth.actor,seq=++sequence;
  if(!actor)return;
  picking.value=true;busy.value=true;error.value='';
  try {
    const page=await libraryService.page(next?cursor.value||undefined:undefined,abort.signal);
    if(!current(actor,seq))return;
    choices.value=page.items.flatMap(material=>material.files
      .filter(file=>file.role==='SCORE_ORIGINAL' && file.processingState==='READY')
      .map(file=>({material,file})));
    cursor.value=page.nextCursor;
  }catch {if(current(actor,seq))error.value=t('musicArchive.originalError');}
  finally {if(current(actor,seq))busy.value=false;}
}
async function attach(material:LibraryMaterial,file:LibraryFile):Promise<void> {
  if(!auth.actor || busy.value)return;
  const actor=auth.actor,seq=++sequence;
  busy.value=true;error.value='';
  try {
    await bindOriginal(props.file.id,material.id,file.id,abort.signal);
    if(current(actor,seq)){picking.value=false;emit('changed');}
  }catch {if(current(actor,seq))error.value=t('musicArchive.originalError');}
  finally {if(current(actor,seq))busy.value=false;}
}
async function download():Promise<void> {
  if(!props.file.original || !auth.actor || busy.value)return;
  const original=props.file.original, actor=auth.actor,seq=++sequence;
  busy.value=true;error.value='';
  try {
    const material=await libraryService.detail(original.materialId,abort.signal);
    if(!current(actor,seq))return;
    const file=material.files.find(f=>f.id===original.assetId && f.role==='SCORE_ORIGINAL' && f.processingState==='READY');
    if(!file)throw new Error('unavailable');
    await libraryService.download(material.id,file,()=>current(actor,seq),abort.signal);
  }catch {if(current(actor,seq))error.value=t('musicArchive.originalError');}
  finally {if(current(actor,seq))busy.value=false;}
}
</script>
<template>
  <div class="archive-original" :aria-busy="busy">
    <p>{{t(file.originalAvailability==='AVAILABLE'?'musicArchive.originalAvailable':'musicArchive.originalUnavailable')}}</p>
    <div class="original-actions">
      <button v-if="file.original" type="button" :disabled="busy" @click="download">{{t('musicArchive.downloadOriginal')}}</button>
      <button type="button" :disabled="busy" @click="load()">{{t('musicArchive.attachOriginal')}}</button>
    </div>
    <div v-if="picking" class="original-picker">
      <p>{{t('musicArchive.originalHelp')}}</p>
      <RouterLink to="/library">{{t('musicArchive.uploadInLibrary')}}</RouterLink>
      <p v-if="!busy && !choices.length">{{t('musicArchive.noOriginals')}}</p>
      <ul>
        <li v-for="choice in choices" :key="choice.file.id">
          <button type="button" :disabled="busy" @click="attach(choice.material,choice.file)">{{choice.material.title}} · {{choice.file.originalName}}</button>
        </li>
      </ul>
      <button v-if="cursor" type="button" :disabled="busy" @click="load(true)">{{t('musicArchive.next')}}</button>
      <button type="button" :disabled="busy" @click="picking=false">{{t('musicArchive.cancelOriginal')}}</button>
    </div>
    <p v-if="busy" role="status">{{t('musicArchive.originalWorking')}}</p>
    <p v-if="error" role="alert">{{error}}</p>
  </div>
</template>
<style scoped>
.archive-original p{margin:6px 0;font-size:13px;color:var(--gm-muted,#53636d)}
.original-actions{display:flex;gap:8px;flex-wrap:wrap}
.archive-original button{font:inherit;font-size:13px;min-height:44px;padding:8px 12px;white-space:normal;text-align:left;border:1px solid var(--gm-border,#dde5e4);border-radius:8px;background:#fff;color:var(--gm-text,#162331);cursor:pointer;overflow-wrap:anywhere}
.archive-original button:disabled{opacity:.5;cursor:wait}
.archive-original button:focus-visible{outline:3px solid var(--gm-focus,#205f57);outline-offset:2px}
.original-picker{margin-top:10px;padding:12px;background:#f4f8f6;border-radius:8px}
.original-picker ul{padding:0;list-style:none;max-height:260px;overflow:auto}
.original-picker li{margin:5px 0}
.original-picker li button{width:100%}
.archive-original [role=alert]{color:#9b3434}
</style>
