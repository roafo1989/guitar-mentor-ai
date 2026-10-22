/* #109: audio-render-thread clock; no window timers or DOM. Static same-origin worklet. */
const valid = c => c && Number.isInteger(c.bpm) && c.bpm >= 20 && c.bpm <= 240 &&
  Number.isInteger(c.numerator) && c.numerator >= 1 && c.numerator <= 16 &&
  [2,4,8,16].includes(c.denominator) && ['quarter','eighth','dotted-quarter'].includes(c.tempoUnit) &&
  [1,2,3,4].includes(c.subdivision) && Array.isArray(c.positions) && c.positions.length === c.numerator &&
  Array.isArray(c.groups) && c.groups.length > 0 && c.groups.length <= c.numerator &&
  c.groups.every(g => Number.isInteger(g) && g > 0) && c.groups.reduce((a,b) => a+b,0) === c.numerator &&
  c.positions.every(p => p && ['strong','medium','weak','mute'].includes(p.accent) && pct(p.volumePercent)) &&
  c.accentVolumes && pct(c.accentVolumes.strong) && pct(c.accentVolumes.medium) &&
  pct(c.accentVolumes.weak) && pct(c.subdivisionVolumePercent) && pct(c.masterVolumePercent);
const pct = v => Number.isInteger(v) && v >= 0 && v <= 100;
const clone = c => ({...c, groups:[...c.groups], positions:c.positions.map(p=>({...p})), accentVolumes:{...c.accentVolumes}});
const seconds = c => 60 / c.bpm * (4 / c.denominator) /
  (c.tempoUnit === 'quarter' ? 1 : c.tempoUnit === 'eighth' ? .5 : 1.5);
const gainAt = (c,i,sub) => {
  const p = c.positions[i];
  if (p.accent === 'mute') return 0;
  return (sub ? c.subdivisionVolumePercent : c.accentVolumes[p.accent]) / 100 * p.volumePercent / 100;
};
class MetronomeProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.session = -1; this.lastSeq = -1; this.config = null; this.pending = null;
    this.running = false; this.index = 0; this.sub = 0; this.nextFrame = 0;
    this.revision = 0; this.pendingRevision = 0; this.clickRemaining = 0;
    this.phase = 0; this.frequency = 0; this.amp = 0; this.clickLength = 0;
    this.lastMaster = 0; this.master = 0; this.deferPending = false;
    this.port.onmessage = e => this.command(e.data);
  }
  send(type, seq = this.lastSeq, extra = {}) {
    this.port.postMessage({v:1,type,sessionId:this.session,seq,frame:currentFrame,
      revision:this.revision,...extra});
  }
  command(m) {
    if (!m || m.v !== 1 || !Number.isSafeInteger(m.sessionId) ||
        !Number.isSafeInteger(m.seq) || m.seq < 0) return;
    if (m.type === 'INIT') {
      if (!valid(m.config) || (this.session !== -1 && m.sessionId <= this.session)) return;
      this.session = m.sessionId; this.lastSeq = m.seq; this.config = clone(m.config);
      this.pending = null; this.running = false; this.revision = 0;
      this.master = this.config.masterVolumePercent / 100; this.lastMaster = this.master;
      this.send('READY'); return;
    }
    if (m.sessionId !== this.session || m.seq <= this.lastSeq || !this.config) return;
    this.lastSeq = m.seq;
    if (m.type === 'START') {
      this.running = true; this.index = 0; this.sub = 0;
      this.nextFrame = currentFrame + Math.round(sampleRate * .03);
      this.clickRemaining = 0; this.deferPending = false;
      this.send('STARTED'); return;
    }
    if (m.type === 'STOP') {
      this.running = false; this.clickRemaining = 0;
      if (this.pending) { this.config = this.pending; this.pending = null; }
      this.send('STOPPED'); return;
    }
    if (m.type === 'BPM' && Number.isInteger(m.value) && m.value >= 20 && m.value <= 240) {
      this.config.bpm = m.value; if (this.pending) this.pending.bpm = m.value;
      this.nextFrame = currentFrame + Math.round(sampleRate * .03);
      this.index = 0; this.sub = 0; this.clickRemaining = 0;
      this.deferPending = !!this.pending;
      this.send('BPM',m.seq,{bpm:m.value}); return;
    }
    if (m.type === 'MASTER' && pct(m.value)) {
      this.config.masterVolumePercent = m.value;
      if (this.pending) this.pending.masterVolumePercent = m.value;
      this.master = m.value / 100; this.send('MASTER'); return;
    }
    if (m.type === 'CONFIG' && valid(m.config) && Number.isSafeInteger(m.revision)) {
      this.pending = clone(m.config); this.pendingRevision = m.revision;
      this.send('QUEUED',m.seq,{pendingRevision:this.pendingRevision}); return;
    }
    if (m.type === 'SNAPSHOT') this.send('SNAPSHOT',m.seq,{
      beat:this.running ? this.index + 1 : null, activeConfig:clone(this.config),
      pendingConfig:this.pending ? clone(this.pending) : null, pendingRevision:this.pendingRevision,
      running:this.running,
    });
  }
  tick(frame) {
    if (this.index === 0 && this.sub === 0 && this.pending) {
      if (this.deferPending) this.deferPending = false;
      else {
        this.config = this.pending; this.pending = null; this.revision = this.pendingRevision;
        this.master = this.config.masterVolumePercent / 100;
        this.send('APPLIED',this.lastSeq,{activeConfig:clone(this.config)});
      }
    }
    const c = this.config;
    const value = gainAt(c,this.index,this.sub !== 0);
    this.amp = .28 * value;
    this.frequency = this.sub !== 0 ? 560 : c.positions[this.index].accent === 'strong' ? 1150 :
      c.positions[this.index].accent === 'medium' ? 940 : 760;
    this.phase = 0;
    const step = seconds(c) / c.subdivision;
    this.clickLength = Math.max(1,Math.round(sampleRate * Math.min(.018,step * .48)));
    this.clickRemaining = value > 0 ? this.clickLength : 0;
    if (this.sub === 0) this.send('BEAT',this.lastSeq,{beat:this.index + 1});
    this.sub++;
    if (this.sub >= c.subdivision) { this.sub = 0; this.index = (this.index + 1) % c.numerator; }
    this.nextFrame = frame + Math.max(1, Math.round(sampleRate * step));
  }
  process(inputs, outputs) {
    const out = outputs[0];
    if (!out || !out.length) return true;
    const len = out[0].length;
    for (let i=0;i<len;i++) {
      const frame = currentFrame + i;
      if (this.running && this.config && frame >= this.nextFrame) this.tick(frame);
      let sample = 0;
      if (this.running && this.clickRemaining > 0) {
        const elapsed = this.clickLength - this.clickRemaining;
        const attack = Math.min(1,elapsed / Math.max(1,Math.round(sampleRate * .002)));
        const decay = Math.exp(-7 * elapsed / this.clickLength);
        sample = Math.sin(this.phase) * this.amp * attack * decay;
        this.phase += 2 * Math.PI * this.frequency / sampleRate;
        this.clickRemaining--;
      }
      this.lastMaster += (this.master - this.lastMaster) * .01;
      sample *= this.lastMaster;
      for (let ch=0;ch<out.length;ch++) out[ch][i] = sample;
    }
    return true;
  }
}
registerProcessor('guitar-mentor-metronome',MetronomeProcessor);
