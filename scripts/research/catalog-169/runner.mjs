import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import PDFDocument from 'pdfkit';
import SVGtoPDF from 'svg-to-pdfkit';
import * as alphaTab from '@coderline/alphatab';

const toolDir = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(toolDir, '../../..');
const fixtureDir = path.join(root, 'fixtures/research/catalog-169');
const generatedDir = path.join(fixtureDir, 'generated');
const outDir = process.env.GM169_OUTPUT_DIR || '/out';
const scratchDir = process.env.GM169_SCRATCH_DIR || '/tmp/gm169';
fs.mkdirSync(outDir, { recursive: true });
fs.mkdirSync(scratchDir, { recursive: true });
const maxInput = 20 * 1024 * 1024;
const expectedBytes = fs.readFileSync(path.join(fixtureDir, 'expected-events.json'));
const expected = JSON.parse(expectedBytes.toString('utf8'));
const expectedEvents = expected.events.map(e => ({
  bar: e.bar, voice: e.voice, string: e.string, fret: e.fret,
  midi: e.midi, displayMidi: e.displayMidi, duration: e.duration
}));
const sourceBytes = fs.readFileSync(path.join(fixtureDir, 'synthetic-score.alphatex'));
if (sourceBytes.length > maxInput) throw new Error('INPUT_TOO_LARGE');

const settings = new alphaTab.Settings();
settings.core.engine = 'svg';
settings.display.layoutMode = alphaTab.LayoutMode.Page;
settings.display.barsPerRow = 4;
settings.display.barCountPerPartial = 4;
settings.display.scale = 1;

function getEvents(score, trackIndex) {
  const events = [];
  for (const staff of score.tracks[trackIndex].staves) {
    for (let barIndex = 0; barIndex < staff.bars.length; barIndex++) {
      const bar = staff.bars[barIndex];
      for (const voice of bar.voices) {
        for (const beat of voice.beats) {
          for (const note of beat.notes) {
            events.push({
              bar: barIndex + 1, voice: voice.index,
              string: staff.tuning.length + 1 - note.string, fret: note.fret, midi: note.realValue, displayMidi: note.displayValue,
              duration: beat.duration
            });
          }
        }
      }
    }
  }
  return events;
}

function validateScore(score, format) {
  if (!score || score.tracks.length !== 2) throw new Error(format + '_TRACK_COUNT');
  if (!score.tracks[1].staves.some(s => s.isStringed)) throw new Error(format + '_SECOND_GUITAR_TRACK');
  if (score.title !== 'GM169 Synthetic Guitar Events') throw new Error(format + '_TITLE');
  if (score.tempo !== 96) throw new Error(format + '_TEMPO');
  if (score.masterBars.length !== 5) throw new Error(format + '_BAR_COUNT');
  if (score.masterBars[0].timeSignatureNumerator !== 4 || score.masterBars[0].timeSignatureDenominator !== 4) throw new Error(format + '_TIME_SIGNATURE');
  if (score.tracks[0].name !== 'Lead Guitar') throw new Error(format + '_TRACK_SELECTION');
  const staff = score.tracks[0].staves[0];
  if (JSON.stringify(staff.tuning) !== JSON.stringify([64, 59, 55, 50, 45, 40])) throw new Error(format + '_TUNING:' + JSON.stringify(staff.tuning));
  if (!staff.showStandardNotation || !staff.showTablature) throw new Error(format + '_STAFF_AND_TAB');
  const actual = getEvents(score, 0);
  if (JSON.stringify(actual) !== JSON.stringify(expectedEvents)) {
    fs.writeFileSync(path.join(outDir, format + '-events-actual.json'), JSON.stringify(actual, null, 2));
    throw new Error(format + '_EVENT_PARITY');
  }
  if (actual.length !== 20) throw new Error(format + '_EVENT_COUNT');
  return { status: 'PASS', events: actual.length, bars: score.masterBars.length, tracks: score.tracks.length };
}

const sourceScore = alphaTab.importer.ScoreLoader.loadAlphaTex(sourceBytes.toString('utf8'), settings);
if (!sourceScore || sourceScore.tracks.length !== 2) throw new Error('ALPHATEX_TRACK_COUNT');
const sourceEvents = getEvents(sourceScore, 0).map(e => ({
  bar: e.bar, voice: e.voice, string: e.string, fret: e.fret, midi: e.midi, duration: e.duration
}));
const sourceExpected = expected.events.map(e => ({
  bar: e.bar, voice: e.voice, string: e.string, fret: e.fret, midi: e.midi, duration: e.duration
}));
if (JSON.stringify(sourceEvents) !== JSON.stringify(sourceExpected)) { fs.writeFileSync(path.join(outDir, 'alphatex-source-actual.json'), JSON.stringify(sourceEvents, null, 2)); fs.writeFileSync(path.join(outDir, 'alphatex-source-expected.json'), JSON.stringify(sourceExpected, null, 2)); throw new Error('ALPHATEX_EXPECTED_EVENTS'); }

const formatResults = {};
const importedScores = {};
for (const ext of ['gp3', 'gp4', 'gp5']) {
  const bytes = fs.readFileSync(path.join(generatedDir, 'gm169-synthetic.' + ext));
  if (bytes.length > maxInput) throw new Error(ext.toUpperCase() + '_INPUT_TOO_LARGE');
  const score = alphaTab.importer.ScoreLoader.loadScoreFromBytes(new Uint8Array(bytes), settings);
  formatResults['.' + ext] = validateScore(score, ext.toUpperCase());
  importedScores[ext] = score;
}

// GP7 is synthetic and exported by alphaTab itself; this is not independent writer interoperability.
const gp7Bytes = new alphaTab.exporter.Gp7Exporter().export(sourceScore, settings);
if (gp7Bytes.length > maxInput) throw new Error('GP7_INPUT_TOO_LARGE');
fs.writeFileSync(path.join(scratchDir, 'gm169-synthetic-gp7.gp'), gp7Bytes);
fs.writeFileSync(path.join(outDir, 'gm169-synthetic-gp7.gp'), gp7Bytes);
const gp7Score = alphaTab.importer.ScoreLoader.loadScoreFromBytes(new Uint8Array(gp7Bytes), settings);
formatResults['.gp (GP7)'] = validateScore(gp7Score, 'GP7');
formatResults['.gp (GP8)'] = { status: 'NOT_RUN', reason: 'No independent legal GP8 writer/sample' };
formatResults['.gtp'] = { status: 'NOT_RUN', reason: 'No legal synthetic GTP1/2 writer/sample' };
formatResults['.gpx'] = { status: 'NOT_RUN', reason: 'No legal synthetic GPX/GP6 writer/sample' };

const imported = importedScores.gp5;
const selectedTrackIndex = 0;
const selectedTrack = imported.tracks[selectedTrackIndex];
const renderer = new alphaTab.rendering.ScoreRenderer(settings);
renderer.width = 1120;
const systems = [];
let attributionSvg = null;
const layoutById = new Map();
let renderError;
renderer.error.on(e => { renderError = e; });
renderer.partialLayoutFinished.on(r => {
  layoutById.set(r.id, { width: r.width, height: r.height, firstBar: r.firstMasterBarIndex + 1, lastBar: r.lastMasterBarIndex + 1 });
  renderer.renderResult(r.id);
});
renderer.partialRenderFinished.on(r => {
  if (typeof r.renderResult === 'string') {
    const layout = layoutById.get(r.id) || {};
    if (layout.firstBar >= 1 && layout.lastBar >= layout.firstBar) {
      systems.push({ svg: r.renderResult, width: layout.width || r.width, height: layout.height || r.height,
        firstBar: layout.firstBar, lastBar: layout.lastBar });
    } else if (r.renderResult.includes('rendered by alphaTab')) {
      attributionSvg = r.renderResult;
    } else {
      fs.writeFileSync(path.join(outDir, 'non-bar-partial-' + r.id + '.svg'), r.renderResult);
    }
  }
});
renderer.renderScore(imported, [selectedTrackIndex]);
if (renderError) throw renderError;
for (let i = 0; i < systems.length; i++) fs.writeFileSync(path.join(outDir, 'system-' + String(i + 1).padStart(2, '0') + '.svg'), systems[i].svg.replace(/class="at"/g, 'class="at" font-family="alphaTab" font-size="34px"'));
if (attributionSvg) fs.writeFileSync(path.join(outDir, 'attribution.svg'), attributionSvg);
if (systems.length === 0) throw new Error('NO_RENDERED_SYSTEMS');
if (systems[0].lastBar - systems[0].firstBar + 1 !== 4) throw new Error('NOT_FOUR_BARS_IN_FIRST_SYSTEM:' + JSON.stringify(systems.map(s => [s.firstBar, s.lastBar])));
if (systems[systems.length - 1].lastBar !== 5) throw new Error('FINAL_SYSTEM_RANGE:' + JSON.stringify(systems.map(s => [s.firstBar, s.lastBar])));

const pdfPath = path.join(outDir, 'gm169-synthetic-score-tab.pdf');
const pdf = new PDFDocument({ size: 'A4', margin: 24, autoFirstPage: false, compress: true });
const pdfStream = fs.createWriteStream(pdfPath);
const bravuraPath = path.join(toolDir, 'node_modules/@coderline/alphatab/dist/font/Bravura.otf');
if (!fs.existsSync(bravuraPath)) throw new Error('BRAVURA_FONT_MISSING:' + bravuraPath);
pdf.registerFont('Bravura', bravuraPath);
const svgOptions = { fontCallback: family => family === 'alphaTab' ? 'Bravura' : 'Helvetica' };
pdf.pipe(pdfStream);
pdf.addPage({ size: 'A4', margin: 24 });
let y = 24;
const maxWidth = 547;
const maxBottom = 818;
for (let i = 0; i < systems.length; i++) {
  const system = systems[i];
  const preparedSvg = system.svg.replace(/class="at"/g, 'class="at" font-family="alphaTab" font-size="34px"');
  fs.writeFileSync(path.join(outDir, 'system-' + String(i + 1).padStart(2, '0') + '.svg'), preparedSvg);
  let scale = Math.min(maxWidth / system.width, (maxBottom - y) / system.height);
  let height = system.height * scale;
  if (y + height > maxBottom && y > 24) {
    pdf.addPage({ size: 'A4', margin: 24 });
    y = 24;
    scale = Math.min(maxWidth / system.width, (maxBottom - y) / system.height);
    height = system.height * scale;
  }
  SVGtoPDF(pdf, preparedSvg, 24, y, {
    width: system.width * scale, height, preserveAspectRatio: 'xMinYMin meet', ...svgOptions
  });
  y += height + 18;
}
if (attributionSvg) {
  const attrWidth = 765, attrHeight = 12;
  const scale = Math.min(maxWidth / attrWidth, 18 / attrHeight);
  const height = attrHeight * scale;
  if (y + height > maxBottom) { pdf.addPage({ size: 'A4', margin: 24 }); y = 24; }
  SVGtoPDF(pdf, attributionSvg, 24, y, { width: attrWidth * scale, height, preserveAspectRatio: 'xMinYMin meet', ...svgOptions });
}
pdf.end();
await new Promise((resolve, reject) => {
  pdfStream.on('finish', resolve);
  pdfStream.on('error', reject);
});

const audioScore = Object.create(Object.getPrototypeOf(imported));
Object.assign(audioScore, imported);
audioScore.tracks = [selectedTrack];
const midi = new alphaTab.midi.MidiFile();
const midiHandler = new alphaTab.midi.AlphaSynthMidiFileHandler(midi);
const midiGenerator = new alphaTab.midi.MidiFileGenerator(audioScore, settings, midiHandler);
midiGenerator.generate();
const audioOptions = new alphaTab.synth.AudioExportOptions();
audioOptions.sampleRate = 44100;
audioOptions.masterVolume = 0.8;
audioOptions.metronomeVolume = 0;
const soundFontPath = path.join(toolDir, 'node_modules/@coderline/alphatab/dist/soundfont/sonivox.sf2');
audioOptions.soundFonts = [new Uint8Array(fs.readFileSync(soundFontPath))];

class Event {
  listeners = [];
  on(fn) { this.listeners.push(fn); }
  trigger(value) { for (const fn of this.listeners) fn(value); }
}
class SilentOutput {
  sampleRate = 44100;
  ready = new Event();
  sampleRequest = new Event();
  samplesPlayed = new Event();
  open() { this.ready.trigger(); }
  play() {} pause() {} destroy() {} activate() {}
  next() { this.sampleRequest.trigger(); }
  resetSamples() {}
  addSamples(samples) { this.samplesPlayed.trigger(samples.length / 2); }
  async enumerateOutputDevices() { return []; }
  async setOutputDevice() {}
  async getOutputDevice() { return null; }
}
const synth = new alphaTab.synth.AlphaSynth(new SilentOutput(), 100);
const exporter = synth.exportAudio(audioOptions, midi, midiGenerator.syncPoints, midiGenerator.transpositionPitches);
const pcmChunks = [];
let sampleCount = 0;
while (true) {
  const chunk = await exporter.render(250);
  if (!chunk) break;
  pcmChunks.push(chunk.samples);
  sampleCount += chunk.samples.length;
  if (sampleCount > 44100 * 2 * 60 * 60) throw new Error('AUDIO_EXPORT_LIMIT');
}
synth.destroy();

const pcm = Buffer.alloc(sampleCount * 2);
let offset = 0;
for (const samples of pcmChunks) {
  for (const value of samples) {
    const sample = Math.max(-1, Math.min(1, value));
    pcm.writeInt16LE(Math.round(sample < 0 ? sample * 32768 : sample * 32767), offset);
    offset += 2;
  }
}
const wav = Buffer.alloc(44 + pcm.length);
wav.write('RIFF', 0); wav.writeUInt32LE(36 + pcm.length, 4); wav.write('WAVE', 8);
wav.write('fmt ', 12); wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20);
wav.writeUInt16LE(2, 22); wav.writeUInt32LE(44100, 24); wav.writeUInt32LE(44100 * 4, 28);
wav.writeUInt16LE(4, 32); wav.writeUInt16LE(16, 34); wav.write('data', 36);
wav.writeUInt32LE(pcm.length, 40); pcm.copy(wav, 44);
const wavPath = path.join(outDir, 'gm169-synthetic-audio.wav');
fs.writeFileSync(wavPath, wav);
if (fs.statSync(pdfPath).size < 1000 || fs.readFileSync(pdfPath).subarray(0, 4).toString() !== '%PDF') throw new Error('PDF_INVALID');
if (wav.subarray(0, 4).toString() !== 'RIFF' || wav.subarray(8, 12).toString() !== 'WAVE') throw new Error('WAV_INVALID');

const cgroupRead = file => { try { return fs.readFileSync(file, 'utf8').trim(); } catch { return null; } };
const scratchFs = fs.statfsSync(scratchDir);
const cgroupMetrics = {
  memoryMaxBytes: cgroupRead('/sys/fs/cgroup/memory.max'),
  memoryPeakBytes: cgroupRead('/sys/fs/cgroup/memory.peak'),
  cpuMax: cgroupRead('/sys/fs/cgroup/cpu.max'),
  cpuStat: cgroupRead('/sys/fs/cgroup/cpu.stat'),
  pidsMax: cgroupRead('/sys/fs/cgroup/pids.max'),
  scratchCapacityBytes: scratchFs.bsize * scratchFs.blocks,
  scratchAvailableBytes: scratchFs.bsize * scratchFs.bavail,
  scratchUsedBytes: scratchFs.bsize * (scratchFs.blocks - scratchFs.bavail)
};
fs.writeFileSync(path.join(outDir, 'container-cgroup-metrics.json'), JSON.stringify(cgroupMetrics, null, 2));
const fontBytes = fs.readFileSync(bravuraPath);
const fontLicensePath = path.join(toolDir, 'node_modules/@coderline/alphatab/dist/font/Bravura-OFL.txt');
const soundFontLicensePath = path.join(toolDir, 'node_modules/@coderline/alphatab/dist/soundfont/LICENSE');
fs.copyFileSync(fontLicensePath, path.join(outDir, 'Bravura-OFL.txt'));
fs.copyFileSync(soundFontLicensePath, path.join(outDir, 'SoundFont-LICENSE.txt'));
const soundFontBytes = fs.readFileSync(soundFontPath);
const runManifest = {
  alphaTabVersion: '1.8.4',
  pythonFixtureGenerator: 'PyGuitarPro 0.11 / Python 3.12.10 (fixtures only)',
  sourceSha256: crypto.createHash('sha256').update(sourceBytes).digest('hex'),
  expectedEventsSha256: crypto.createHash('sha256').update(expectedBytes).digest('hex'),
  bravuraFont: { path: 'alphaTab 1.8.4 dist/font/Bravura.otf', license: 'Bravura-OFL.txt', sha256: crypto.createHash('sha256').update(fontBytes).digest('hex') },
  soundFont: { path: 'alphaTab 1.8.4 dist/soundfont/sonivox.sf2', license: 'SoundFont-LICENSE.txt', sha256: crypto.createHash('sha256').update(soundFontBytes).digest('hex') },
  cgroupMetrics,
  attributionIncluded: Boolean(attributionSvg),
  formats: formatResults,
  selectedTrackIndex,
  selectedTrackName: selectedTrack.name,
  pdf: path.basename(pdfPath),
  pdfBytes: fs.statSync(pdfPath).size,
  wav: path.basename(wavPath),
  wavBytes: wav.length,
  wavDurationSeconds: sampleCount / (44100 * 2),
  audioDescription: 'Synthetic SoundFont preview from same imported GP5 score revision; no human listening acceptance',
  systems: systems.map(s => ({ firstBar: s.firstBar, lastBar: s.lastBar }))
};
fs.writeFileSync(path.join(outDir, 'run-manifest.json'), JSON.stringify(runManifest, null, 2));
console.log(JSON.stringify(runManifest, null, 2));
renderer.destroy();
