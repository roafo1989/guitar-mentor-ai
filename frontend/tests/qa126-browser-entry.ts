// Real Vue components and styles; only server responses are stubbed.
import { createApp, nextTick } from 'vue';
import { createI18n } from 'vue-i18n';
import SubmissionPanel from '../src/entities/attempt/SubmissionPanel.vue';
import en from '../src/i18n/en.json';
import '../src/assets/main.css';

const attempts = Array.from({length:45}, (_,i) => {
  const number=45-i;
  return {
    id:'id-'+number,taskId:'t',number,status:'PROCESSED',
    submittedAt:'2026-10-24T00:00:00Z',comment:null,format:'wav',
    bytes:500,assessmentAvailable:false,
    processingStartedAt:null,processingFinishedAt:null,processingErrorCode:null,
    audioDurationSeconds:20,mediaState:'AVAILABLE',
  };
});
const counts:Record<string,number>={};
const reply=(body:unknown,status=200) => new Response(JSON.stringify(body), {
  status,headers:{'Content-Type':'application/json','Cache-Control':'no-store'}
});
window.fetch=(async (input:RequestInfo | URL) => {
  const u=new URL(typeof input==='string'?input:input instanceof URL?input.toString():input.url,location.origin);
  counts[u.pathname]=(counts[u.pathname]??0)+1;
  if(u.pathname.endsWith('/attempts/capabilities')) return reply({enabled:true,maxBytes:10000000,formats:['wav']});
  if(u.pathname.endsWith('/attempts/page')) {
    const before=Number(u.searchParams.get('beforeNumber')??Infinity);
    const items=attempts.filter(a=>a.number<before).slice(0,20);
    return reply({items,hasMore:attempts.some(a=>a.number<(items.at(-1)?.number??0)),nextBeforeNumber:items.length===20?items.at(-1)?.number:null});
  }
  if(u.pathname.endsWith('/learning')) return reply({
    taskId:'t',taskState:'ACTIVE',version:1,availableActions:[],
    latestAttemptId:'id-45',assessmentState:'NO_ASSESSMENT',
    latestAssessmentId:null,latestDecision:null,nextStepAvailability:'NOT_IMPLEMENTED'
  });
  if(u.pathname.endsWith('/learning/evidence') || u.pathname.endsWith('/decisions') || u.pathname.endsWith('/assessments'))
    return reply({items:[],nextCursor:null});
  const id=u.pathname.split('/').at(-1);
  if(id?.startsWith('id-')) return reply(attempts.find(a=>a.id===id)??{code:'RESOURCE_NOT_FOUND'},attempts.some(a=>a.id===id)?200:404);
  return reply({code:'UNEXPECTED_RESPONSE'},404);
}) as typeof fetch;

createApp(SubmissionPanel,{learnerId:'l',sessionId:'s',taskId:'t'})
 .use(createI18n({legacy:false,locale:'en',messages:{en}}))
 .mount('#qa-root');
declare global {interface Window {__qa126Ready?:boolean; __qa126Requests?:Record<string,number>}}
window.__qa126Requests=counts;
nextTick().then(async()=>{
  for(let i=0;i<120;i++){
    if(document.querySelector('.homework-history nav button') && document.getElementById('learning-attempt')) {
      window.__qa126Ready=true;return;
    }
    await new Promise(resolve=>setTimeout(resolve,50));
  }
  window.__qa126Ready=false;
});
