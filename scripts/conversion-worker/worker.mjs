import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import PDFDocument from 'pdfkit';
import SVGtoPDF from 'svg-to-pdfkit';
import * as alphaTab from '@coderline/alphatab';

const INPUT = '/input/source.gp';
const OUTPUT = '/out';
const MAX_INPUT = 20 * 1024 * 1024;
const MAX_OUTPUT = 80 * 1024 * 1024;
const FORMAT = process.env.GM_CONVERSION_FORMAT || '';
const selectedArg = process.env.GM_CONVERSION_TRACK_INDEX;
const fail = (code, details = {}) => {
  const result = { status: 'ERROR', code, ...details };
  fs.writeFileSync(path.join(OUTPUT, 'result.json'), JSON.stringify(result));
  console.log(JSON.stringify(result));
  process.exit(code === 'TRACK_SELECTION_REQUIRED' ? 10 : 20);
};
fs.mkdirSync(OUTPUT, { recursive: true });
try {
  if (!['GP3', 'GP4', 'GP5'].includes(FORMAT)) fail('UNSUPPORTED_FORMAT');
  const stat = fs.statSync(INPUT);
  if (!stat.isFile() || stat.size < 1) fail('EMPTY_OR_INVALID_SOURCE');
  if (stat.size > MAX_INPUT) fail('SOURCE_TOO_LARGE');
  const bytes = fs.readFileSync(INPUT);
  const expectedHeaders = { GP3: 'FICHIER GUITAR PRO v3.00', GP4: 'FICHIER GUITAR PRO v4.06', GP5: 'FICHIER GUITAR PRO v5.10' };
  const headerLength = bytes[0];
  const versionHeader = headerLength > 0 && headerLength <= 30 && bytes.length >= headerLength + 1
    ? bytes.subarray(1, headerLength + 1).toString('ascii') : '';
  if (versionHeader !== expectedHeaders[FORMAT]) fail('UNSUPPORTED_FORMAT_VERSION');
  const settings = new alphaTab.Settings();
  settings.core.engine = 'svg';
  settings.display.layoutMode = alphaTab.LayoutMode.Page;
  settings.display.barsPerRow = 4;
  settings.display.barCountPerPartial = 4;
  settings.display.scale = 1;

  let score;
  try { score = alphaTab.importer.ScoreLoader.loadScoreFromBytes(new Uint8Array(bytes), settings); }
  catch { fail('INVALID_OR_UNSUPPORTED_FILE'); }
  if (!score || !Array.isArray(score.tracks) || score.tracks.length < 1 || score.tracks.length > 32) fail('INVALID_SCORE');
  const guitarTracks = [];
  for (let i = 0; i < score.tracks.length; i++) {
    const track = score.tracks[i];
    if (track.staves?.some(staff => staff.isStringed
        && JSON.stringify(staff.tuning) === JSON.stringify([64, 59, 55, 50, 45, 40]))) {
      guitarTracks.push({ trackIndex: i, name: String(track.name || 'Guitar').slice(0, 80), guitar: true, instrument: 'Guitar' });
    }
  }
  if (guitarTracks.length === 0) fail('NO_GUITAR_TRACK');
  let selectedIndex;
  if (selectedArg !== undefined && selectedArg !== '') {
    if (!/^(0|[1-9][0-9]*)$/.test(selectedArg)) fail('INVALID_TRACK_SELECTION');
    selectedIndex = Number(selectedArg);
    if (!guitarTracks.some(track => track.trackIndex === selectedIndex)) fail('INVALID_TRACK_SELECTION');
  } else if (guitarTracks.length === 1) {
    selectedIndex = guitarTracks[0].trackIndex;
  } else {
    fail('TRACK_SELECTION_REQUIRED', { tracks: guitarTracks });
  }

  const track = score.tracks[selectedIndex];
  if (track.staves.length !== 1) fail('UNSUPPORTED_MULTI_STAFF');
  const staff = track.staves[0];
  if (JSON.stringify(staff.tuning) !== JSON.stringify([64, 59, 55, 50, 45, 40])) fail('UNSUPPORTED_TUNING');
  if (staff.capo !== 0) fail('UNSUPPORTED_CAPO');
  if (!staff.showStandardNotation || !staff.showTablature) fail('UNSUPPORTED_NOTATION');
  if (!score.masterBars?.length || score.masterBars.length > 256) fail('INVALID_BAR_COUNT');
  if (score.tempoAutomations?.length > 1) fail('UNSUPPORTED_TEMPO_CHANGE');
  for (let barIndex = 0; barIndex < score.masterBars.length; barIndex++) {
    const bar = score.masterBars[barIndex];
    if (bar.timeSignatureNumerator !== 4 || bar.timeSignatureDenominator !== 4) fail('UNSUPPORTED_TIME_SIGNATURE');
    if (bar.isRepeatStart || bar.isRepeatEnd || bar.repeatCount > 0 || bar.alternateEndings) fail('UNSUPPORTED_REPEATS');
    const tempos = bar.tempoAutomations || [];
    for (const automation of tempos) {
      const isInitialTempo = barIndex === 0 && automation.ratioPosition === 0 && automation.value === score.tempo;
      if (!isInitialTempo) fail('UNSUPPORTED_TEMPO_CHANGE');
    }
  }
  for (const bar of staff.bars) {
    if (bar.voices.length > 2 || bar.voices[1]?.beats?.length > 0) fail('UNSUPPORTED_POLYPHONY');
    const voice = bar.voices[0];
    if (!voice.beats?.length) fail('UNSUPPORTED_EMPTY_BAR');
    for (const beat of voice.beats) {
      if (beat.duration !== 4 || beat.notes?.length !== 1) fail('UNSUPPORTED_RHYTHM_OR_REST');
      const note = beat.notes[0];
      if (note.isDead || note.isGhost || note.isTieDestination || note.isTieOrigin
          || note.tieOrigin || note.tieDestination || note.vibrato || note.trill
          || note.bend || note.bendPoints?.length > 0 || note.hasBend || note.harmonicType
          || note.isHarmonic || note.slideInType || note.slideOutType || note.slideOrigin || note.slideTarget
          || note.isHammerPullOrigin || note.isHammerPullDestination || note.hammerPullOrigin || note.hammerPullDestination
          || note.isPalmMute || note.palmMuteDestination || note.isLetRing || note.letRingDestination
          || note.isStaccato || note.isSlurOrigin || note.isSlurDestination || note.slurOrigin || note.slurDestination
          || note.isEffectSlurOrigin || note.isEffectSlurDestination || note.effectSlurOrigin || note.effectSlurDestination
          || note.isLeftHandTapped || note.isTrill || note.isSlapped || note.isPopped
          || beat.whammyBarPoints?.length > 0 || beat.automations?.length > 0)
        fail('UNSUPPORTED_NOTE_EFFECT');
      if (note.fret < 0 || note.fret > 24 || note.string < 1 || note.string > staff.tuning.length)
        fail('INVALID_NOTE', { noteString: note.string, noteFret: note.fret, tuningLength: staff.tuning.length });
    }
  }

  const renderer = new alphaTab.rendering.ScoreRenderer(settings);
  renderer.width = 1120;
  const systems = [];
  const layoutById = new Map();
  let renderFailed = false;
  renderer.error.on(() => { renderFailed = true; });
  renderer.partialLayoutFinished.on(result => {
    layoutById.set(result.id, { width: result.width, height: result.height,
      firstBar: result.firstMasterBarIndex + 1, lastBar: result.lastMasterBarIndex + 1 });
    renderer.renderResult(result.id);
  });
  renderer.partialRenderFinished.on(result => {
    const layout = layoutById.get(result.id);
    if (typeof result.renderResult === 'string' && layout
        && layout.firstBar >= 1 && layout.lastBar >= layout.firstBar) {
      systems.push({ svg: result.renderResult.replace(/class="at"/g, 'class="at" font-family="alphaTab" font-size="34px"'),
        width: layout.width, height: layout.height, firstBar: layout.firstBar, lastBar: layout.lastBar });
    }
  });
  renderer.renderScore(score, [selectedIndex]);
  if (renderFailed || systems.length === 0) fail('PDF_RENDER_FAILED');
  if (systems[0].lastBar - systems[0].firstBar + 1 !== 4
      || systems[systems.length - 1].lastBar !== score.masterBars.length) fail('PDF_LAYOUT_VALIDATION_FAILED');
  const pdfPath = path.join(OUTPUT, 'score.pdf');
  const pdf = new PDFDocument({ size: 'A4', margin: 24, autoFirstPage: false, compress: true });
  const pdfStream = fs.createWriteStream(pdfPath);
  const moduleDir = path.dirname(new URL(import.meta.url).pathname);
  const fontPath = path.join(moduleDir, 'node_modules/@coderline/alphatab/dist/font/Bravura.otf');
  if (!fs.existsSync(fontPath)) fail('RENDERER_ASSET_MISSING');
  pdf.registerFont('Bravura', fontPath);
  pdf.pipe(pdfStream);
  pdf.addPage({ size: 'A4', margin: 24 });
  let y = 24;
  const maxWidth = 547, maxBottom = 818;
  for (const system of systems) {
    let scale = Math.min(maxWidth / system.width, (maxBottom - y) / system.height);
    let height = system.height * scale;
    if (y + height > maxBottom && y > 24) {
      pdf.addPage({ size: 'A4', margin: 24 }); y = 24;
      scale = Math.min(maxWidth / system.width, (maxBottom - y) / system.height);
      height = system.height * scale;
    }
    SVGtoPDF(pdf, system.svg, 24, y, { width: system.width * scale, height,
      preserveAspectRatio: 'xMinYMin meet',
      fontCallback: family => family === 'alphaTab' ? 'Bravura' : 'Helvetica' });
    y += height + 18;
  }
  if (y === 24) pdf.addPage({ size: 'A4', margin: 24 });
  pdf.end();
  await new Promise((resolve, reject) => { pdfStream.on('finish', resolve); pdfStream.on('error', reject); });
  renderer.destroy();

  const audioScore = Object.create(Object.getPrototypeOf(score));
  Object.assign(audioScore, score);
  audioScore.tracks = [track];
  const midi = new alphaTab.midi.MidiFile();
  const midiHandler = new alphaTab.midi.AlphaSynthMidiFileHandler(midi);
  const midiGenerator = new alphaTab.midi.MidiFileGenerator(audioScore, settings, midiHandler);
  midiGenerator.generate();
  const audioOptions = new alphaTab.synth.AudioExportOptions();
  audioOptions.sampleRate = 44100;
  audioOptions.masterVolume = 0.8;
  audioOptions.metronomeVolume = 0;
  const sfPath = path.join(moduleDir, 'node_modules/@coderline/alphatab/dist/soundfont/sonivox.sf2');
  audioOptions.soundFonts = [new Uint8Array(fs.readFileSync(sfPath))];
  class Signal { listeners=[]; on(fn){this.listeners.push(fn);} trigger(v){for(const fn of this.listeners)fn(v);} }
  class SilentOutput {
    sampleRate=44100; ready=new Signal(); sampleRequest=new Signal(); samplesPlayed=new Signal();
    open(){this.ready.trigger();} play(){} pause(){} destroy(){} activate(){}
    next(){this.sampleRequest.trigger();} resetSamples(){} addSamples(v){this.samplesPlayed.trigger(v.length/2);}
    async enumerateOutputDevices(){return [];} async setOutputDevice(){} async getOutputDevice(){return null;}
  }
  const synth = new alphaTab.synth.AlphaSynth(new SilentOutput(), 100);
  const exporter = synth.exportAudio(audioOptions, midi, midiGenerator.syncPoints, midiGenerator.transpositionPitches);
  const chunks=[]; let sampleCount=0;
  while(true) {
    const chunk=await exporter.render(250);
    if(!chunk) break;
    chunks.push(chunk.samples); sampleCount += chunk.samples.length;
    if(sampleCount > 44100*2*60*2) fail('AUDIO_DURATION_LIMIT');
  }
  synth.destroy();
  const pcm=Buffer.alloc(sampleCount*2); let offset=0;
  for(const chunk of chunks) for(const value of chunk) {
    const v=Math.max(-1,Math.min(1,value));
    pcm.writeInt16LE(Math.round(v<0?v*32768:v*32767),offset); offset+=2;
  }
  const wav=Buffer.alloc(44+pcm.length);
  wav.write('RIFF',0); wav.writeUInt32LE(36+pcm.length,4); wav.write('WAVE',8);
  wav.write('fmt ',12); wav.writeUInt32LE(16,16); wav.writeUInt16LE(1,20);
  wav.writeUInt16LE(2,22); wav.writeUInt32LE(44100,24); wav.writeUInt32LE(176400,28);
  wav.writeUInt16LE(4,32); wav.writeUInt16LE(16,34); wav.write('data',36);
  wav.writeUInt32LE(pcm.length,40); pcm.copy(wav,44);
  fs.writeFileSync(path.join(OUTPUT,'preview.wav'),wav);
  for(const name of ['score.pdf','preview.wav']) {
    const p=path.join(OUTPUT,name); const s=fs.statSync(p);
    if(s.size<1000 || s.size>MAX_OUTPUT) fail('OUTPUT_INVALID');
  }
  const result={status:'RENDERED',format:FORMAT,sourceSha256:crypto.createHash('sha256').update(bytes).digest('hex'),
    selectedTrackIndex:selectedIndex,selectedTrackName:String(track.name||'Guitar').slice(0,80),
    scoreRevision:crypto.createHash('sha256').update(bytes).digest('hex'),
    pdfBytes:fs.statSync(pdfPath).size,wavBytes:wav.length,wavDurationSeconds:sampleCount/(44100*2),
    alphaTabVersion:'1.8.4'};
  fs.writeFileSync(path.join(OUTPUT,'result.json'),JSON.stringify(result));
  console.log(JSON.stringify(result));
} catch (error) {
  fail('CONVERSION_INTERNAL_ERROR');
}
