/** Singleton Web Audio metronome. All timing comes from the audio clock. */
import {
  type MetronomeConfig, copyConfig, defaultMetronomeConfig, validConfig,
  positionSeconds, cellGain,
} from './metronome-config.ts';
export type MetronomeStatus = 'ready' | 'starting' | 'running' | 'stopped' | 'unavailable';
export interface MetronomeState {
  status: MetronomeStatus;
  bpm: number;
  /** The last sounded denominator-position, never a future scheduled position. */
  beat: number | null;
  error: 'web-audio-unavailable' | null;
}
type Listener = (state: Readonly<MetronomeState>) => void;
type ClickNodes = { oscillator: OscillatorNode; gain: GainNode };
const LOOKAHEAD_MS = 25;
const AHEAD_SECONDS = 0.1;
const INITIAL_DELAY_SECONDS = 0.03;
const MAX_CLICK_SECONDS = 0.018;

function browserAudioContext(): AudioContext {
  const browser = globalThis as typeof globalThis & { webkitAudioContext?: typeof AudioContext };
  const AudioContextClass = browser.AudioContext ?? browser.webkitAudioContext;
  if (!AudioContextClass) throw new Error('Web Audio unavailable');
  return new AudioContextClass();
}
export class MetronomeEngine {
  private state: MetronomeState;
  private config: MetronomeConfig;
  private pendingConfig: MetronomeConfig | null = null;
  private readonly listeners = new Set<Listener>();
  private readonly makeContext: () => AudioContext;
  private context: AudioContext | null = null;
  private masterGain: GainNode | null = null;
  private interval: ReturnType<typeof setInterval> | null = null;
  private readonly visualTimers = new Set<ReturnType<typeof setTimeout>>();
  private readonly clicks = new Set<ClickNodes>();
  private nextBeatTime = 0;
  private nextBeatIndex = 0;
  private nextSubIndex = 0;
  private generation = 0;
  private pendingStart: Promise<boolean> | null = null;
  private closing: Promise<void> | null = null;

  constructor(options: { bpm?: number; createAudioContext?: () => AudioContext } = {}) {
    const config = defaultMetronomeConfig();
    if (options.bpm !== undefined) {
      if (!MetronomeEngine.isValidBpm(options.bpm)) throw new RangeError('BPM must be an integer from 20 to 240');
      config.bpm = options.bpm;
    }
    this.config = config;
    this.state = { status:'ready', bpm:config.bpm, beat:null, error:null };
    this.makeContext = options.createAudioContext ?? browserAudioContext;
  }
  static isValidBpm(bpm: number): boolean {
    return Number.isInteger(bpm) && bpm >= 20 && bpm <= 240;
  }
  getState(): Readonly<MetronomeState> { return { ...this.state }; }
  getConfig(): MetronomeConfig { return copyConfig(this.config); }
  getPendingConfig(): MetronomeConfig | null {
    return this.pendingConfig ? copyConfig(this.pendingConfig) : null;
  }
  subscribe(listener: Listener): () => void {
    this.listeners.add(listener);
    try { listener(this.getState()); } catch { /* one subscriber must not break audio */ }
    return () => { this.listeners.delete(listener); };
  }
  /** A live BPM-only change restarts immediately on beat one (contract of #87). */
  setBpm(bpm: number): boolean {
    if (!MetronomeEngine.isValidBpm(bpm)) return false;
    if (bpm === this.config.bpm && (!this.pendingConfig || this.pendingConfig.bpm === bpm)) return true;
    this.config.bpm = bpm;
    if (this.pendingConfig) this.pendingConfig.bpm = bpm;
    this.state = { ...this.state, bpm };
    if (this.state.status === 'running' && this.context) {
      this.cancelScheduledBeats();
      this.nextBeatIndex = 0;
      this.nextSubIndex = 0;
      this.nextBeatTime = this.context.currentTime + INITIAL_DELAY_SECONDS;
      this.state.beat = null;
      this.emit();
      this.schedule(this.generation);
    } else this.emit();
    return true;
  }
  /** Master is immediately effective even during playback. */
  setMasterVolume(value: number): boolean {
    if (!Number.isInteger(value) || value < 0 || value > 100) return false;
    this.config.masterVolumePercent = value;
    if (this.pendingConfig) this.pendingConfig.masterVolumePercent = value;
    if (this.context && this.masterGain)
      this.masterGain.gain.setValueAtTime(value / 100, this.context.currentTime);
    this.emit();
    return true;
  }
  /** Changes to meter, groups, accent or unit are atomic at the next bar line. */
  applyConfig(requested: MetronomeConfig): boolean {
    if (!validConfig(requested)) return false;
    const next = copyConfig(requested);
    if (this.state.status === 'running') {
      // A separate BPM operation must still follow #87 even with an already pending config.
      // Composite changes are held in full until the next bar.
      next.bpm = this.config.bpm;
      next.masterVolumePercent = this.config.masterVolumePercent;
      this.pendingConfig = next;
    } else {
      this.config = next;
      this.pendingConfig = null;
      this.state = { ...this.state, bpm:next.bpm, beat:null };
    }
    this.emit();
    return true;
  }
  start(): Promise<boolean> {
    if (this.state.status === 'running') return Promise.resolve(true);
    if (this.pendingStart) return this.pendingStart;
    const generation = ++this.generation;
    this.state = { ...this.state, status:'starting', beat:null, error:null };
    this.emit();
    const p = this.begin(generation);
    this.pendingStart = p;
    void p.finally(() => { if (this.pendingStart === p) this.pendingStart = null; });
    return p;
  }
  stop(): void {
    ++this.generation;
    this.pendingStart = null;
    this.cleanup();
    // Stopping accepts the last requested valid config, not an obsolete bar.
    if (this.pendingConfig) {
      this.config = this.pendingConfig;
      this.pendingConfig = null;
    }
    this.state = { ...this.state, bpm:this.config.bpm, status:'stopped', beat:null, error:null };
    this.emit();
  }
  dispose(): void { this.stop(); this.listeners.clear(); }
  private async begin(generation: number): Promise<boolean> {
    try {
      if (this.closing) {
        await this.closing;
        if (generation !== this.generation) return false;
      }
      const context = this.makeContext();
      if (generation !== this.generation) {
        void context.close().catch(() => {});
        return false;
      }
      this.context = context;
      context.addEventListener('statechange', this.onAudioStateChange);
      this.addLifecycleListeners();
      await context.resume();
      if (generation !== this.generation) return false;
      if (typeof document !== 'undefined' && document.visibilityState === 'hidden') {
        this.stop(); return false;
      }
      if (context.state !== 'running') throw new Error('Audio context did not resume');
      const master = context.createGain();
      master.gain.setValueAtTime(this.config.masterVolumePercent / 100, context.currentTime);
      master.connect(context.destination);
      this.masterGain = master;
      this.nextBeatIndex = 0;
      this.nextSubIndex = 0;
      this.nextBeatTime = context.currentTime + INITIAL_DELAY_SECONDS;
      this.state = { ...this.state, status:'running', beat:null, error:null };
      this.emit();
      this.schedule(generation);
      if (generation !== this.generation) return false;
      this.interval = setInterval(() => this.schedule(generation), LOOKAHEAD_MS);
      return true;
    } catch {
      if (generation === this.generation) this.failAudio();
      return false;
    }
  }
  private readonly onVisibilityChange = (): void => {
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') this.stop();
  };
  private readonly onPageHide = (): void => { this.stop(); };
  private readonly onAudioStateChange = (): void => {
    if (this.state.status === 'running' && this.context?.state !== 'running') this.failAudio();
  };
  private failAudio(): void {
    ++this.generation;
    this.pendingStart = null;
    this.cleanup();
    this.state = { ...this.state, status:'unavailable', beat:null, error:'web-audio-unavailable' };
    this.emit();
  }
  private addLifecycleListeners(): void {
    if (typeof document !== 'undefined') document.addEventListener('visibilitychange', this.onVisibilityChange);
    if (typeof window !== 'undefined') window.addEventListener('pagehide', this.onPageHide);
  }
  private removeLifecycleListeners(): void {
    if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', this.onVisibilityChange);
    if (typeof window !== 'undefined') window.removeEventListener('pagehide', this.onPageHide);
  }
  private schedule(generation: number): void {
    const context = this.context;
    if (generation !== this.generation || this.state.status !== 'running' || !context) return;
    if (context.state !== 'running') { this.failAudio(); return; }
    try {
      // Clock stalls skip old events without a catch-up burst. Do not accumulate
      // nextBeatTime using repeated additions when thousands of ticks were missed.
      let interval = positionSeconds(this.config) / this.config.subdivision;
      if (this.nextBeatTime < context.currentTime) {
        const skipped = Math.ceil((context.currentTime - this.nextBeatTime) / interval);
        const ticksPerBar = this.config.numerator * this.config.subdivision;
        const nextIndex = (this.nextBeatIndex * this.config.subdivision + this.nextSubIndex + skipped) % ticksPerBar;
        this.nextBeatIndex = Math.floor(nextIndex / this.config.subdivision);
        this.nextSubIndex = nextIndex % this.config.subdivision;
        this.nextBeatTime += skipped * interval;
        if (this.nextBeatTime < context.currentTime) this.nextBeatTime = context.currentTime;
      }
      while (this.nextBeatTime < context.currentTime + AHEAD_SECONDS) {
        if (this.nextBeatIndex === 0 && this.nextSubIndex === 0 && this.pendingConfig) {
          this.config = this.pendingConfig;
          this.pendingConfig = null;
          interval = positionSeconds(this.config) / this.config.subdivision;
          this.state = { ...this.state, bpm:this.config.bpm };
          this.emit();
        }
        const beatIndex = this.nextBeatIndex;
        const beat = beatIndex + 1;
        const sub = this.nextSubIndex;
        const at = this.nextBeatTime;
        this.scheduleClick(context, at, beatIndex, sub, interval);
        if (sub === 0) {
          const delay = Math.max(0, (at - context.currentTime) * 1000);
          const timer = setTimeout(() => {
            this.visualTimers.delete(timer);
            if (generation !== this.generation || this.state.status !== 'running') return;
            this.state = { ...this.state, beat };
            this.emit();
          }, delay);
          this.visualTimers.add(timer);
        }
        this.nextBeatTime += interval;
        ++this.nextSubIndex;
        if (this.nextSubIndex >= this.config.subdivision) {
          this.nextSubIndex = 0;
          this.nextBeatIndex = (this.nextBeatIndex + 1) % this.config.numerator;
        }
      }
    } catch { if (generation === this.generation) this.failAudio(); }
  }
  private scheduleClick(context: AudioContext, at: number, beatIndex: number, subIndex: number, stepSeconds: number): void {
    const gainValue = cellGain(this.config, beatIndex, subIndex !== 0);
    if (gainValue <= 0 || this.config.masterVolumePercent === 0) return;
    const accent = this.config.positions[beatIndex].accent;
    const frequency = subIndex !== 0 ? 560 : accent === 'strong' ? 1150 : accent === 'medium' ? 940 : 760;
    const length = Math.min(MAX_CLICK_SECONDS, stepSeconds * 0.48);
    const oscillator = context.createOscillator();
    const gain = context.createGain();
    const click: ClickNodes = { oscillator, gain };
    this.clicks.add(click);
    oscillator.onended = () => {
      oscillator.disconnect(); gain.disconnect(); this.clicks.delete(click);
    };
    oscillator.type = 'sine';
    oscillator.frequency.setValueAtTime(frequency, at);
    gain.gain.setValueAtTime(0.0001, at);
    gain.gain.exponentialRampToValueAtTime(Math.max(0.0002, 0.28 * gainValue), at + Math.min(.002, length / 4));
    gain.gain.exponentialRampToValueAtTime(0.0001, at + length);
    oscillator.connect(gain);
    gain.connect(this.masterGain ?? context.destination);
    oscillator.start(at);
    oscillator.stop(at + length);
  }
  private cancelScheduledBeats(): void {
    for (const timer of this.visualTimers) clearTimeout(timer);
    this.visualTimers.clear();
    for (const { oscillator, gain } of this.clicks) {
      oscillator.onended = null;
      try { oscillator.stop(); } catch {}
      try { oscillator.disconnect(); } catch {}
      try { gain.disconnect(); } catch {}
    }
    this.clicks.clear();
  }
  private cleanup(): void {
    if (this.interval !== null) clearInterval(this.interval);
    this.interval = null;
    this.removeLifecycleListeners();
    this.cancelScheduledBeats();
    try { this.masterGain?.disconnect(); } catch {}
    this.masterGain = null;
    const context = this.context;
    this.context = null;
    if (context) {
      context.removeEventListener('statechange', this.onAudioStateChange);
      if (context.state !== 'closed') {
        try {
          const pending = Promise.resolve(context.close()).then(() => {}, () => {});
          this.closing = pending;
          void pending.then(() => { if (this.closing === pending) this.closing = null; });
        } catch {}
      }
    }
  }
  private emit(): void {
    for (const listener of this.listeners) {
      try { listener(this.getState()); } catch {}
    }
  }
}
/** One engine shared by header, practice card and modal. */
export const metronome = new MetronomeEngine();
