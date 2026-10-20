/**
 * Local, 4/4 Web Audio metronome. No audio is created until a user calls start().
 * Keep one instance for all entry points (header and exercise card).
 */
export type MetronomeStatus = 'ready' | 'starting' | 'running' | 'stopped' | 'unavailable';

export interface MetronomeState {
  status: MetronomeStatus;
  bpm: number;
  /** Beat that has sounded (not the next scheduled beat); null when silent. */
  beat: 1 | 2 | 3 | 4 | null;
  /** Stable, localizable error code; do not surface raw browser error details. */
  error: 'web-audio-unavailable' | null;
}

type Listener = (state: Readonly<MetronomeState>) => void;
type ClickNodes = { oscillator: OscillatorNode; gain: GainNode };

const MIN_BPM = 20;
const MAX_BPM = 240;
const LOOKAHEAD_MS = 25;
const SCHEDULE_AHEAD_SECONDS = 0.1;
const FIRST_BEAT_DELAY_SECONDS = 0.03;
const CLICK_SECONDS = 0.045;

function browserAudioContext(): AudioContext {
  const browser = globalThis as typeof globalThis & { webkitAudioContext?: typeof AudioContext };
  const AudioContextClass = browser.AudioContext ?? browser.webkitAudioContext;
  if (!AudioContextClass) throw new Error('Web Audio unavailable');
  return new AudioContextClass();
}

export class MetronomeEngine {
  private state: MetronomeState;
  private readonly listeners = new Set<Listener>();
  private readonly makeContext: () => AudioContext;
  private context: AudioContext | null = null;
  private interval: ReturnType<typeof setInterval> | null = null;
  private readonly visualTimers = new Set<ReturnType<typeof setTimeout>>();
  private readonly clicks = new Set<ClickNodes>();
  private nextBeatTime = 0;
  private nextBeatIndex = 0;
  private generation = 0;
  private pendingStart: Promise<boolean> | null = null;
  /** Closing can be asynchronous: never allocate a replacement before it finishes. */
  private closing: Promise<void> | null = null;

  constructor(options: { bpm?: number; createAudioContext?: () => AudioContext } = {}) {
    const bpm = options.bpm ?? 80;
    if (!MetronomeEngine.isValidBpm(bpm)) throw new RangeError('BPM must be an integer from 20 to 240');
    this.state = { status: 'ready', bpm, beat: null, error: null };
    this.makeContext = options.createAudioContext ?? browserAudioContext;
  }

  static isValidBpm(bpm: number): boolean {
    return Number.isInteger(bpm) && bpm >= MIN_BPM && bpm <= MAX_BPM;
  }

  getState(): Readonly<MetronomeState> {
    return { ...this.state };
  }

  subscribe(listener: Listener): () => void {
    this.listeners.add(listener);
    listener(this.getState());
    return () => { this.listeners.delete(listener); };
  }

  /** Invalid values do not overwrite the previous tempo. A live change restarts on beat 1. */
  setBpm(bpm: number): boolean {
    if (!MetronomeEngine.isValidBpm(bpm)) return false;
    if (bpm === this.state.bpm) return true;
    this.state = { ...this.state, bpm };
    if (this.state.status === 'running' && this.context) {
      this.cancelScheduledBeats();
      this.nextBeatIndex = 0;
      this.nextBeatTime = this.context.currentTime + FIRST_BEAT_DELAY_SECONDS;
      this.state = { ...this.state, beat: null };
      this.emit();
      this.schedule(this.generation);
    } else {
      this.emit();
    }
    return true;
  }

  /** Call directly from a user gesture; repeated calls share one pending start. */
  start(): Promise<boolean> {
    if (this.state.status === 'running') return Promise.resolve(true);
    if (this.pendingStart) return this.pendingStart;

    const generation = ++this.generation;
    this.state = { ...this.state, status: 'starting', beat: null, error: null };
    this.emit();
    const startPromise = this.begin(generation);
    this.pendingStart = startPromise;
    void startPromise.finally(() => {
      if (this.pendingStart === startPromise) this.pendingStart = null;
    });
    return startPromise;
  }

  stop(): void {
    ++this.generation;
    this.pendingStart = null;
    this.cleanup();
    this.state = { ...this.state, status: 'stopped', beat: null, error: null };
    this.emit();
  }

  /** Call on component unmount if the component owns the engine. */
  dispose(): void {
    this.stop();
    this.listeners.clear();
  }

  private async begin(generation: number): Promise<boolean> {
    try {
      // Wait only when a previous AudioContext is still closing. Normal initial
      // start/resume stays in the user gesture; no browser autoplay is attempted.
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
      // Also handle pagehide while a browser is waiting for resume() to settle.
      this.addLifecycleListeners();
      await context.resume();
      if (generation !== this.generation) return false;
      if (typeof document !== 'undefined' && document.visibilityState === 'hidden') {
        this.stop();
        return false;
      }
      if (context.state !== 'running') throw new Error('Audio context did not resume');

      this.nextBeatIndex = 0;
      this.nextBeatTime = context.currentTime + FIRST_BEAT_DELAY_SECONDS;
      this.state = { ...this.state, status: 'running', beat: null, error: null };
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
    // Do not confuse the initial suspended state with an interruption, or a
    // deliberate close with an audio failure. Never automatically resume.
    if (this.state.status === 'running' && this.context?.state !== 'running') {
      this.failAudio();
    }
  };

  private failAudio(): void {
    ++this.generation;
    this.pendingStart = null;
    this.cleanup();
    this.state = { ...this.state, status: 'unavailable', beat: null, error: 'web-audio-unavailable' };
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
    if (context.state !== 'running') {
      this.failAudio();
      return;
    }
    try {
      const beatSeconds = 60 / this.state.bpm;
      // On a throttled tab, skip missed beats rather than playing a burst of old ones.
      if (this.nextBeatTime < context.currentTime) {
        const skipped = Math.ceil((context.currentTime - this.nextBeatTime) / beatSeconds);
        this.nextBeatIndex = (this.nextBeatIndex + skipped) % 4;
        this.nextBeatTime += skipped * beatSeconds;
        if (this.nextBeatTime < context.currentTime) this.nextBeatTime = context.currentTime;
      }
      while (this.nextBeatTime < context.currentTime + SCHEDULE_AHEAD_SECONDS) {
        const beat = (this.nextBeatIndex + 1) as 1 | 2 | 3 | 4;
        this.scheduleClick(context, this.nextBeatTime, beat);
        const delay = Math.max(0, (this.nextBeatTime - context.currentTime) * 1000);
        const timer = setTimeout(() => {
          this.visualTimers.delete(timer);
          if (generation !== this.generation || this.state.status !== 'running') return;
          this.state = { ...this.state, beat };
          this.emit();
        }, delay);
        this.visualTimers.add(timer);
        this.nextBeatTime += beatSeconds;
        this.nextBeatIndex = (this.nextBeatIndex + 1) % 4;
      }
    } catch {
      if (generation === this.generation) this.failAudio();
    }
  }

  private scheduleClick(context: AudioContext, at: number, beat: 1 | 2 | 3 | 4): void {
    const oscillator = context.createOscillator();
    const gain = context.createGain();
    const click: ClickNodes = { oscillator, gain };
    this.clicks.add(click);
    oscillator.onended = () => {
      oscillator.disconnect();
      gain.disconnect();
      this.clicks.delete(click);
    };
    oscillator.type = 'sine';
    oscillator.frequency.setValueAtTime(beat === 1 ? 1150 : 760, at);
    gain.gain.setValueAtTime(0.0001, at);
    gain.gain.exponentialRampToValueAtTime(beat === 1 ? 0.28 : 0.13, at + 0.002);
    gain.gain.exponentialRampToValueAtTime(0.0001, at + CLICK_SECONDS);
    oscillator.connect(gain);
    gain.connect(context.destination);
    oscillator.start(at);
    oscillator.stop(at + CLICK_SECONDS);
  }

  private cancelScheduledBeats(): void {
    for (const timer of this.visualTimers) clearTimeout(timer);
    this.visualTimers.clear();
    for (const { oscillator, gain } of this.clicks) {
      oscillator.onended = null;
      try { oscillator.stop(); } catch { /* already stopped */ }
      try { oscillator.disconnect(); } catch { /* already disconnected */ }
      try { gain.disconnect(); } catch { /* already disconnected */ }
    }
    this.clicks.clear();
  }

  private cleanup(): void {
    if (this.interval !== null) clearInterval(this.interval);
    this.interval = null;
    this.removeLifecycleListeners();
    this.cancelScheduledBeats();
    const context = this.context;
    this.context = null;
    if (context) {
      context.removeEventListener('statechange', this.onAudioStateChange);
      if (context.state !== 'closed') {
        try {
          // Store the promise before a new start() can create another context.
          // Even a rejected close must unblock a future explicit user start.
          const pending = Promise.resolve(context.close()).then(() => {}, () => {});
          this.closing = pending;
          void pending.then(() => {
            if (this.closing === pending) this.closing = null;
          });
        } catch { /* browser context failed to close synchronously */ }
      }
    }
  }

  private emit(): void {
    for (const listener of this.listeners) {
      // Each subscriber receives an independent snapshot. Readonly<T> is only
      // compile-time; an errant JS subscriber must not corrupt other listeners.
      try { listener(this.getState()); } catch { /* one faulty consumer must not stop audio */ }
    }
  }
}

/** Singleton to be shared by the header and exercise UI in #87. */
export const metronome = new MetronomeEngine();
