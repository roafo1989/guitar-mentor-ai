import {createApp,defineComponent,h,ref} from 'vue';
import {createI18n} from 'vue-i18n';
import ScoreReader from '../src/shared/score/ScoreReader.vue';
import ru from '../src/i18n/ru.json';

const encoder=new TextEncoder();
function twoPagePdf():Uint8Array {
  const commands='BT /F1 24 Tf 30 300 Td (Test Score, Page 1) Tj ET';
  const second='BT /F1 24 Tf 30 300 Td (Test Score, Page 2) Tj ET';
  const parts=[
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R 6 0 R] /Count 2 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 320 420] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>',
    '<< /Length '+commands.length+' >>\nstream\n'+commands+'\nendstream',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 320 420] /Resources << /Font << /F1 5 0 R >> >> /Contents 7 0 R >>',
    '<< /Length '+second.length+' >>\nstream\n'+second+'\nendstream',
  ];
  let text='%PDF-1.4\n';
  const offsets=[0];
  for(let i=0;i<parts.length;i++){
    offsets.push(encoder.encode(text).length);
    text+=(i+1)+' 0 obj\n'+parts[i]+'\nendobj\n';
  }
  const xref=encoder.encode(text).length;
  text+='xref\n0 '+(parts.length+1)+'\n0000000000 65535 f \n';
  for(const offset of offsets.slice(1))text+=String(offset).padStart(10,'0')+' 00000 n \n';
  text+='trailer\n<< /Root 1 0 R /Size '+(parts.length+1)+' >>\nstartxref\n'+xref+'\n%%EOF\n';
  return encoder.encode(text);
}
const pdf=twoPagePdf();
const native=window.fetch.bind(window);
let requests=0;
window.fetch=((input:RequestInfo|URL,init?:RequestInit)=>{
  const url=typeof input==='string'?input:input instanceof URL?input.href:input.url;
  if(url.endsWith('/preview/pdf')){
    requests++;
    if(init?.method==='HEAD')return Promise.resolve(new Response(null,{status:200,headers:{'content-type':'application/pdf'}}));
    return Promise.resolve(new Response(pdf.slice(),{status:200,headers:{'content-type':'application/pdf'}}));
  }
  return native(input,init);
}) as typeof window.fetch;
const opened=ref(false);
const i18n=createI18n({legacy:false,locale:'ru',messages:{ru}});
createApp(defineComponent({setup(){return()=>h('main',{style:'max-width:900px;margin:0 auto;padding:24px'},[
  h(ScoreReader,{exerciseKey:'GM-EB01@1.0',pdfUrl:'/api/v1/exercises/GM-EB01/versions/1.0/preview/pdf',
    scoreUrl:'/api/v1/exercises/GM-EB01/versions/1.0/score',title:'Test Score',open:opened.value,
    'onUpdate:open':(v:boolean)=>{opened.value=v;}}),
]);}})).use(i18n).mount('#app');
const browser=window as unknown as {__scoreHarnessReady:boolean;__scoreRequests:()=>number};
browser.__scoreHarnessReady=true;
browser.__scoreRequests=()=>requests;
