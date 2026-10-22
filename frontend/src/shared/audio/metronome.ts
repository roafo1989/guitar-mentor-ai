/** #109 — singleton metronome; all audible timing happens on the AudioWorklet render thread. */
// Vite rewrites this static asset URL; Node unit tests can import this module unchanged.
const processorUrl = new URL('./metronome-processor.js', import.meta.url).href;
import { type MetronomeConfig, copyConfig, defaultMetronomeConfig, validConfig } from './metronome-config.ts';
export type MetronomeStatus = 'ready' | 'starting' | 'running' | 'stopped' | 'unavailable';
export interface MetronomeState {
  status: MetronomeStatus;
  bpm: number;
  beat: number | null;
  error: 'web-audio-unavailable' | null;
}
type Listener = (state: Readonly<MetronomeState>) => void;
type WorkletMessage = {v:number;type:string;sessionId:number;seq:number;beat?:number;
  activeConfig?:MetronomeConfig;pendingConfig?:MetronomeConfig;running?:boolean;revision?:number};
const MODULE_TIMEOUT_MS = 5000;
const browserAudioContext = (): AudioContext => {
  const browser = globalThis as typeof globalThis & {webkitAudioContext?: typeof AudioContext};
  const Constructor = browser.AudioContext ?? browser.webkitAudioContext;
  if (!Constructor) throw new Error('Web Audio unavailable');
  return new Constructor();
};
export class MetronomeEngine {
  private config: MetronomeConfig;
  private pending: MetronomeConfig | null = null;
  private state: MetronomeState;
  private listeners = new Set<Listener>();
  private makeContext: () => AudioContext;
  private context: AudioContext | null = null;
  private node: AudioWorkletNode | null = null;
  private closing: Promise<void> | null = null;
  private generation = 0;
  private sequence = 0;
  private revision = 0;
  private pendingStart: Promise<boolean> | null = null;
  private awaiting = new Map<string, {resolve: (value:boolean)=>void; timer:ReturnType<typeof setTimeout>}>();
  private lastBeat = 0;
  constructor(options: {bpm?:number;createAudioContext?:()=>AudioContext} = {}) {
    this.config = defaultMetronomeConfig();
    if (options.bpm !== undefined) {
      if (!MetronomeEngine.isValidBpm(options.bpm)) throw new RangeError('Invalid BPM');
      this.config.bpm = options.bpm;
    }
    this.state = {status:'ready',bpm:this.config.bpm,beat:null,error:null};
    this.makeContext = options.createAudioContext ?? browserAudioContext;
  }
  static isValidBpm(value:number): boolean { return Number.isInteger(value) && value>=20 && value<=240; }
  getState(): Readonly<MetronomeState> { return {...this.state}; }
  getConfig(): MetronomeConfig { return copyConfig(this.config); }
  getPendingConfig(): MetronomeConfig | null { return this.pending ? copyConfig(this.pending) : null; }
  subscribe(listener:Listener):()=>void {
    this.listeners.add(listener);
    try { listener(this.getState()); } catch {}
    return () => {this.listeners.delete(listener);};
  }
  private emit():void { for(const listener of this.listeners) {try {listener(this.getState());}catch{}} }
  private send(type:string, payload:Record<string,unknown> = {}):number {
    const seq=++this.sequence;
    this.node?.port.postMessage({v:1,sessionId:this.generation,seq,type,...payload});
    return seq;
  }
  private receive = (event:MessageEvent<WorkletMessage>):void => {
    const msg=event.data;
    if (!msg || msg.v!==1 || msg.sessionId!==this.generation || !Number.isSafeInteger(msg.seq)) return;
    const key=msg.type+':'+msg.seq;
    const waiter=this.awaiting.get(key);
    if (waiter) {clearTimeout(waiter.timer);this.awaiting.delete(key);waiter.resolve(true);}
    if (msg.type==='APPLIED' && msg.activeConfig && validConfig(msg.activeConfig)) {
      // Last requested revision wins; a stale APPLIED must not erase newer pending settings.
      this.config=copyConfig(msg.activeConfig);
      if (this.pending && this.config.bpm===this.pending.bpm &&
          JSON.stringify(this.config)===JSON.stringify(this.pending)) this.pending=null;
      this.state={...this.state,bpm:this.config.bpm};
      this.emit();
    } else if (msg.type==='BEAT' && this.state.status==='running' &&
      typeof msg.beat==='number' && msg.beat>=1 && msg.beat<=16) {
      // Hidden tabs may delay messages. Never queue or replay visual beats.
      if (typeof document!=='undefined' && document.visibilityState==='hidden') return;
      this.lastBeat=msg.beat;
      this.state={...this.state,beat:msg.beat};this.emit();
    } else if (msg.type==='SNAPSHOT' && this.state.status==='running') {
      if (msg.activeConfig && validConfig(msg.activeConfig)) {
        this.config=copyConfig(msg.activeConfig);
        if (msg.pendingConfig===null) this.pending=null;
        this.state={...this.state,bpm:this.config.bpm};
      }
      if (typeof msg.beat==='number') this.state={...this.state,beat:msg.beat};
      this.emit();
    } else if (msg.type==='ERROR') this.fail();
  };
  private waitFor(type:string, seq:number, generation:number):Promise<boolean> {
    return new Promise(resolve=>{
      if (generation!==this.generation) {resolve(false);return;}
      const key=type+':'+seq;
      const timer=setTimeout(()=>{
        this.awaiting.delete(key);resolve(false);
      },MODULE_TIMEOUT_MS);
      this.awaiting.set(key,{resolve,timer});
    });
  }
  start():Promise<boolean> {
    if (this.state.status==='running') return Promise.resolve(true);
    if (this.pendingStart) return this.pendingStart;
    const generation=++this.generation;
    this.sequence=0;
    this.state={...this.state,status:'starting',beat:null,error:null};this.emit();
    const pending=this.begin(generation);
    this.pendingStart=pending;
    void pending.finally(()=>{if(this.pendingStart===pending)this.pendingStart=null;});
    return pending;
  }
  private async begin(generation:number):Promise<boolean> {
    try {
      if (this.closing) await this.closing;
      if (generation!==this.generation) return false;
      // Instantiate/resume in the original user-gesture task, before module loading.
      const context=this.makeContext();
      if (generation!==this.generation) {void context.close().catch(()=>{});return false;}
      this.context=context;
      context.addEventListener('statechange',this.onAudioState);
      this.addLifecycle();
      if (!context.audioWorklet || typeof AudioWorkletNode==='undefined') throw new Error('AudioWorklet unavailable');
      const resumed=context.resume();
      const module=context.audioWorklet.addModule(processorUrl);
      await Promise.all([resumed,module]);
      if (generation!==this.generation) return false;
      if (context.state!=='running') throw new Error('AudioContext suspended');
      const node=new AudioWorkletNode(context,'guitar-mentor-metronome',{numberOfInputs:0,
        numberOfOutputs:1,outputChannelCount:[1]});
      if (generation!==this.generation) {node.disconnect();return false;}
      this.node=node;
      node.port.onmessage=this.receive;
      node.onprocessorerror=()=>this.fail();
      node.connect(context.destination);
      const initSeq=this.send('INIT',{config:copyConfig(this.config)});
      if (!await this.waitFor('READY',initSeq,generation) || generation!==this.generation) {
        if(generation===this.generation) this.fail();return false;
      }
      const startSeq=this.send('START');
      if (!await this.waitFor('STARTED',startSeq,generation) || generation!==this.generation) {
        if(generation===this.generation)this.fail();return false;
      }
      if (context.state!=='running') {this.fail();return false;}
      this.state={...this.state,status:'running',beat:null,error:null};this.emit();
      return true;
    } catch {if(generation===this.generation)this.fail();return false;}
  }
  setBpm(bpm:number):boolean {
    if(!MetronomeEngine.isValidBpm(bpm))return false;
    this.config.bpm=bpm;
    if(this.pending)this.pending.bpm=bpm;
    this.state={...this.state,bpm,beat:null};this.emit();
    if(this.node && this.state.status==='running')this.send('BPM',{value:bpm});
    return true;
  }
  setMasterVolume(value:number):boolean {
    if(!Number.isInteger(value)||value<0||value>100)return false;
    this.config.masterVolumePercent=value;
    if(this.pending)this.pending.masterVolumePercent=value;
    if(this.node&&this.state.status==='running')this.send('MASTER',{value});
    this.emit();return true;
  }
  applyConfig(value:MetronomeConfig):boolean {
    if(!validConfig(value))return false;
    const next=copyConfig(value);
    if(this.state.status==='running'&&this.node) {
      this.pending=next;
      this.send('CONFIG',{config:next,revision:++this.revision});
    } else {this.config=next;this.pending=null;this.state={...this.state,bpm:next.bpm,beat:null};}
    this.emit();return true;
  }
  private onVisibility = ():void => {
    if(typeof document!=='undefined'&&document.visibilityState==='visible'&&this.state.status==='running')
      this.send('SNAPSHOT');
  };
  private onPageHide = ():void => {this.stop();};
  private onAudioState = ():void => {
    if(this.context && this.context.state!=='running' &&
       (this.state.status==='running'||this.state.status==='starting'))this.fail();
  };
  private addLifecycle():void {
    if(typeof document!=='undefined')document.addEventListener('visibilitychange',this.onVisibility);
    if(typeof window!=='undefined')window.addEventListener('pagehide',this.onPageHide);
  }
  private removeLifecycle():void {
    if(typeof document!=='undefined')document.removeEventListener('visibilitychange',this.onVisibility);
    if(typeof window!=='undefined')window.removeEventListener('pagehide',this.onPageHide);
  }
  private cleanup():void {
    this.removeLifecycle();
    for(const waiter of this.awaiting.values()){clearTimeout(waiter.timer);waiter.resolve(false);}
    this.awaiting.clear();
    const node=this.node;this.node=null;
    if(node) {
      try {node.port.postMessage({v:1,sessionId:this.generation,seq:++this.sequence,type:'STOP'});}catch{}
      node.port.onmessage=null;node.onprocessorerror=null;
      try {node.disconnect();}catch{}
      try {node.port.close();}catch{}
    }
    const context=this.context;this.context=null;
    if(context) {
      context.removeEventListener('statechange',this.onAudioState);
      if(context.state!=='closed') {
        try {
          const close=Promise.resolve(context.close()).then(()=>{},()=>{});
          this.closing=close;
          void close.then(()=>{if(this.closing===close)this.closing=null;});
        }catch{}
      }
    }
  }
  private fail():void {
    ++this.generation;this.pendingStart=null;this.cleanup();
    this.state={...this.state,status:'unavailable',beat:null,error:'web-audio-unavailable'};
    this.emit();
  }
  stop():void {
    ++this.generation;this.pendingStart=null;
    this.cleanup();
    if(this.pending){this.config=this.pending;this.pending=null;}
    this.state={...this.state,status:'stopped',beat:null,bpm:this.config.bpm,error:null};
    this.emit();
  }
  dispose():void {this.stop();this.listeners.clear();}
}
export const metronome=new MetronomeEngine();
