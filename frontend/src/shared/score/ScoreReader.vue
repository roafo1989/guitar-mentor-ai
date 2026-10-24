<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { fetchBoundedPdf, pinnedPdfUrl, safeScoreLink, ScoreFetchError } from './score-transport';

type ViewerState = 'CLOSED' | 'CHECKING' | 'LOADING' | 'READY' | 'UNAVAILABLE' | 'ERROR';
const props = defineProps<{
  exerciseKey: string; pdfUrl: string | null; scoreUrl: string | null;
  title: string; open: boolean;
}>();
const emit = defineEmits<{
  'update:open': [value: boolean];
  'viewer-state': [value: {state: ViewerState; reasonCode?: string}];
}>();
const { t } = useI18n();
const state = ref<ViewerState>('CLOSED');
const reasonCode = ref('');
const page = ref(1);
const pageCount = ref(0);
const zoom = ref(100);
const fitWidth = ref(true);
const fullscreen = ref(false);
const focusButton = ref<HTMLButtonElement | null>(null);
const focusTrigger = ref<HTMLButtonElement | null>(null);
const frame = ref<HTMLIFrameElement | null>(null);
const container = ref<HTMLElement | null>(null);
const safeUrl = computed(() => pinnedPdfUrl(props.pdfUrl, props.exerciseKey, window.location.origin));
const fallback = computed(() => safeScoreLink(props.scoreUrl, window.location.origin));
let controller: AbortController | null = null;
let channel: MessageChannel | null = null;
let generation = 0;
const nonce = ref('');
let frameReady = false;
let pending: {bytes: ArrayBuffer; renderer: string; worker: string} | null = null;
let resizeObserver: ResizeObserver | null = null;
let timeout: ReturnType<typeof setTimeout> | null = null;
const viewerId = 'score-frame-' + Math.random().toString(36).slice(2);

function transition(value: ViewerState, reason = '') {
  state.value = value;
  reasonCode.value = reason;
  emit('viewer-state', {state:value, ...(reason ? {reasonCode:reason} : {})});
}
function stopWork() {
  ++generation;
  controller?.abort();
  controller = null;
  if (timeout) clearTimeout(timeout);
  timeout = null;
  channel?.port1.close();
  channel?.port2.close();
  channel = null;
  pending = null;
  frameReady = false;
}
function reset() {
  stopWork();
  leaveFullscreen();
  page.value = 1;
  pageCount.value = 0;
  zoom.value = 100;
  fitWidth.value = true;
  nonce.value = crypto.randomUUID();
}
function close() { emit('update:open', false); }
function leaveFullscreen() {
  if (!fullscreen.value) return;
  fullscreen.value = false;
  void nextTick(() => focusTrigger.value?.focus());
}
function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && fullscreen.value) {
    event.preventDefault();
    leaveFullscreen();
  }
  if (fullscreen.value && event.key === 'Tab') {
    const controls = Array.from(container.value?.querySelectorAll<HTMLButtonElement | HTMLAnchorElement>(
      'button:not(:disabled),a[href]'
    ) ?? []);
    if (!controls.length) return;
    const first = controls[0], last = controls[controls.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
  }
}
function focusMode() {
  if (!props.open) emit('update:open', true);
  fullscreen.value = true;
  void nextTick(() => container.value?.querySelector<HTMLButtonElement>('button.score-close')?.focus());
}
function sendRender() {
  if (!channel || state.value !== 'READY') return;
  channel.port1.postMessage({type:'render',nonce:nonce.value,page:page.value,zoom:zoom.value,fitWidth:fitWidth.value});
}
function switchPage(next: number) { page.value = Math.max(1, Math.min(pageCount.value, next)); sendRender(); }
function adjustZoom(next: number) { fitWidth.value = false; zoom.value = Math.max(50, Math.min(250,next)); sendRender(); }
function autoFit() { fitWidth.value = true; sendRender(); }
const statusText = computed(() => state.value === 'UNAVAILABLE' ? t('score.unavailable')
  : state.value === 'ERROR' ? t('score.errors.' + (reasonCode.value || 'network'))
  : t('score.states.' + state.value));

function receiveWindowMessage(event: MessageEvent) {
  if (event.source !== frame.value?.contentWindow || event.data?.type !== 'GM_SCORE_READY' || event.data.nonce !== nonce.value) return;
  frameReady = true;
  sendPending();
}
function sendPending() {
  if (!frameReady || !pending || !frame.value?.contentWindow) return;
  const data = pending; pending = null;
  const current = generation;
  channel = new MessageChannel();
  channel.port1.onmessage = event => {
    const message = event.data;
    if (current !== generation || message?.nonce !== nonce.value) return;
    if (message.type === 'loaded') {
      if (!Number.isInteger(message.pages) || message.pages < 1 || message.pages > 100) {
        transition('ERROR','limit'); return;
      }
      pageCount.value = message.pages; transition('READY'); sendRender();
    }
    if (message.type === 'rendered') { /* Canvas belongs only to sandbox. */ }
    if (message.type === 'failure') transition('ERROR', ['limit','password','corrupt','render'].includes(message.reason) ? message.reason : 'render');
  };
  frame.value.contentWindow.postMessage(
    {type:'GM_SCORE_INIT',nonce:nonce.value,renderer:data.renderer,worker:data.worker,bytes:data.bytes},
    '*',[channel.port2,data.bytes]
  );
}

const iframeDocument = computed(() => {
  const key = nonce.value;
  const javascript = `
    let port, pdf, rendering, nextRender, workerUrl, rendererUrl;
    const nonce = ${JSON.stringify(key)};
    const holder = document.getElementById('page');
    const send = (type, extra={}) => port?.postMessage({type,nonce,...extra});
    async function draw(request) {
      if (!pdf) return;
      if (rendering) { nextRender = request; await rendering.cancel().catch(()=>{}); return; }
      try {
        const p = await pdf.getPage(request.page);
        const basic = p.getViewport({scale:1});
        const scale = request.fitWidth
          ? Math.max(.05,(holder.clientWidth - 20) / basic.width)
          : Math.max(.5,Math.min(2.5,request.zoom/100));
        const view = p.getViewport({scale});
        if (view.width * view.height > 8000000) {send('failure',{reason:'limit'});return;}
        const canvas = document.createElement('canvas');
        canvas.width = Math.max(1,Math.ceil(view.width));
        canvas.height = Math.max(1,Math.ceil(view.height));
        canvas.style.width = view.width + 'px'; canvas.style.height = view.height + 'px';
        const ctx = canvas.getContext('2d',{alpha:false});
        if (!ctx) throw Error('NO_CONTEXT');
        holder.replaceChildren(canvas);
        rendering = p.render({canvasContext:ctx,viewport:view});
        await Promise.race([
          rendering.promise,
          new Promise((_, reject)=>setTimeout(()=>reject(Error('RENDER_TIMEOUT')),10000))
        ]);
        send('rendered',{page:request.page});
      } catch(e) { if (e?.name !== 'RenderingCancelledException') send('failure',{reason:'render'}); }
      finally {
        rendering = null;
        if (nextRender) {const latest=nextRender;nextRender=null;await draw(latest);}
      }
    }
    window.addEventListener('message', async e=>{
      if(e.source!==window.parent||e.data?.type!=='GM_SCORE_INIT'||e.data.nonce!==nonce||!e.ports?.[0])return;
      if(port)return;
      port=e.ports[0];
      try {
        rendererUrl=URL.createObjectURL(new Blob([e.data.renderer],{type:'text/javascript'}));
        workerUrl=URL.createObjectURL(new Blob([e.data.worker],{type:'text/javascript'}));
        const engine=await import(rendererUrl);
        engine.GlobalWorkerOptions.workerSrc=workerUrl;
        const task=engine.getDocument({data:new Uint8Array(e.data.bytes),
          isEvalSupported:false,disableAutoFetch:true,disableStream:true,useSystemFonts:true});
        pdf=await task.promise;
        if(pdf.numPages<1||pdf.numPages>100) {send('failure',{reason:'limit'});return;}
        send('loaded',{pages:pdf.numPages});
        port.onmessage=msg=>{
          if(msg.data?.nonce!==nonce)return;
          if(msg.data.type==='render')void draw(msg.data);
        };
        port.start();
      } catch(err) {send('failure',{reason:err?.name==='PasswordException'?'password':'corrupt'});}
    },{once:true});
    window.parent.postMessage({type:'GM_SCORE_READY',nonce},'*');
    window.addEventListener('pagehide',()=>{
      rendering?.cancel().catch(()=>{});
      pdf?.destroy?.();port?.close();
      if(workerUrl)URL.revokeObjectURL(workerUrl);
      if(rendererUrl)URL.revokeObjectURL(rendererUrl);
    });
  `;
  return `<!doctype html><html><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy"
    content="default-src 'none'; script-src 'nonce-${key}' blob:; worker-src blob:; connect-src 'none'; style-src 'unsafe-inline'; font-src blob: data:; img-src data: blob:">
    <style>html,body{margin:0;background:#e7ebe9;height:100%;}#page{min-height:100%;text-align:center;padding:10px;box-sizing:border-box}canvas{display:block;margin:0 auto;background:white;box-shadow:0 2px 14px #2233;max-width:none;}</style>
    </head><body><div id="page"></div><script nonce="${key}">${javascript}<\/script></body></html>`;
});

async function openReader() {
  reset();
  const current = generation;
  const url = safeUrl.value;
  if (!url) { transition('UNAVAILABLE'); return; }
  transition('CHECKING');
  controller = new AbortController();
  const c = controller;
  timeout = setTimeout(() => c.abort(), 30_000);
  try {
    // HEAD is advisory; never rely on audio HEAD or on HEAD success for renderability.
    const head = await fetch(url,{method:'HEAD',credentials:'same-origin',cache:'no-cache',signal:c.signal});
    if (head.status === 404) throw new ScoreFetchError('missing');
    if (head.status === 401 || head.status === 403) throw new ScoreFetchError('auth');
    if (!head.ok) throw new ScoreFetchError('network');
    if (current !== generation) return;
    transition('LOADING');
    const bytes = await fetchBoundedPdf(url,c.signal);
    if (current !== generation) return;
    const [renderer,worker] = await Promise.all([
      import('pdfjs-dist/build/pdf.mjs?raw').then(m=>m.default),
      import('pdfjs-dist/build/pdf.worker.mjs?raw').then(m=>m.default),
    ]);
    if (current !== generation || c.signal.aborted) return;
    pending = {bytes,renderer,worker};
    await nextTick();
    sendPending();
  } catch (error) {
    if (current !== generation) return;
    transition('ERROR',c.signal.aborted ? 'timeout' : error instanceof ScoreFetchError ? error.reasonCode : 'network');
  } finally {
    if(timeout)clearTimeout(timeout);
    timeout=null;
  }
}
watch(() => [props.exerciseKey,props.pdfUrl], () => {
  reset();
  if(props.open)emit('update:open',false);
  else transition('CLOSED');
});
watch(() => props.open, value => {
  if(value)void openReader();
  else {reset();transition('CLOSED');}
}, {immediate:true});
watch(fullscreen, value => {
  if (value) document.addEventListener('keydown',onKeydown);
  else document.removeEventListener('keydown',onKeydown);
});
watch(container, el => {
  resizeObserver?.disconnect();
  if (el && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(()=>{if(fitWidth.value)sendRender();});
    resizeObserver.observe(el);
  }
});
window.addEventListener('message',receiveWindowMessage);
onBeforeUnmount(()=>{
  stopWork();resizeObserver?.disconnect();document.removeEventListener('keydown',onKeydown);
  window.removeEventListener('message',receiveWindowMessage);
});
</script>

<template>
  <section ref="container" class="score-reader" :class="{ 'score-fullscreen': fullscreen }"
    :aria-label="t('score.heading')">
    <div class="score-toolbar">
      <button ref="focusButton" type="button" class="score-toggle" :aria-expanded="open"
        @click="open ? close() : emit('update:open', true)">{{ open ? t('score.close') : t('score.open') }}</button>
      <template v-if="open">
        <div class="score-pagination">
          <button type="button" :disabled="state!=='READY'||page<=1" :aria-label="t('score.previous')" @click="switchPage(page-1)">‹</button>
          <span aria-live="polite">{{ page }} / {{ pageCount || '—' }}</span>
          <button type="button" :disabled="state!=='READY'||page>=pageCount" :aria-label="t('score.next')" @click="switchPage(page+1)">›</button>
        </div>
        <div class="score-zoom">
          <button type="button" :disabled="state!=='READY'||(!fitWidth&&zoom<=50)" :aria-label="t('score.zoomOut')" @click="adjustZoom(fitWidth?75:zoom-25)">−</button>
          <span>{{ fitWidth ? t('score.fitWidth') : zoom+'%' }}</span>
          <button type="button" :disabled="state!=='READY'||(!fitWidth&&zoom>=250)" :aria-label="t('score.zoomIn')" @click="adjustZoom(fitWidth?125:zoom+25)">+</button>
          <button type="button" :disabled="state!=='READY'" @click="autoFit">{{ t('score.fitWidth') }}</button>
        </div>
        <button ref="focusTrigger" type="button" class="score-focus" @click="fullscreen?leaveFullscreen():focusMode()">
          {{ fullscreen?t('score.exitFocus'):t('score.focus') }}
        </button>
      </template>
    </div>
    <div v-if="open">
      <p v-if="state==='CHECKING'||state==='LOADING'" role="status">{{ statusText }}</p>
      <div v-if="state==='ERROR'||state==='UNAVAILABLE'" role="alert">
        <p>{{ statusText }}</p>
        <button v-if="state==='ERROR'" type="button" @click="openReader">{{ t('score.retry') }}</button>
      </div>
      <iframe v-if="state==='CHECKING'||state==='LOADING'||state==='READY'"
        :key="nonce" ref="frame" :id="viewerId" class="score-canvas-frame"
        sandbox="allow-scripts" :srcdoc="iframeDocument" :title="title+' — '+t('score.heading')" />
      <p class="score-fallback">
        <a v-if="safeUrl" :href="safeUrl" target="_blank" rel="noopener noreferrer">{{ t('score.openOriginal') }}</a>
        <a v-if="fallback" :href="fallback" download>{{ t('score.musicXml') }}</a>
      </p>
    </div>
  </section>
</template>

<style scoped>
.score-reader {min-width:0;border:1px solid #dce5e4;border-radius:12px;background:white;padding:12px}
.score-toolbar {display:flex;gap:10px;align-items:center;justify-content:space-between;flex-wrap:wrap}
.score-toolbar button,.score-reader [role="alert"] button {min-height:44px;min-width:44px;padding:8px 10px;border:1px solid #7c8987;border-radius:8px;background:white;color:#162331;cursor:pointer}
.score-toolbar button:disabled {opacity:.45;cursor:not-allowed}
.score-toolbar button:focus-visible,.score-fallback a:focus-visible {outline:3px solid #28776d;outline-offset:3px}
.score-pagination,.score-zoom {display:flex;align-items:center;gap:8px}
.score-pagination span,.score-zoom span {white-space:nowrap}
.score-canvas-frame {display:block;width:100%;height:min(70vh,850px);border:0;margin-top:12px;background:#e7ebe9}
.score-fallback {display:flex;flex-wrap:wrap;gap:20px;margin:12px 0 0}
.score-fallback a {color:#205f57;overflow-wrap:anywhere}
.score-fullscreen {position:fixed;inset:0;z-index:1000;overflow:auto;border-radius:0;padding:16px;background:#f7f8f7}
.score-fullscreen .score-canvas-frame {height:calc(100dvh - 175px)}
@media(max-width:600px) {.score-toolbar {justify-content:flex-start}.score-canvas-frame {height:62vh}}
</style>
