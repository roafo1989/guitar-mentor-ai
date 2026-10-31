import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
const toolDir = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(toolDir, '../../..');
const manifest = JSON.parse(fs.readFileSync(path.join(root, 'fixtures/research/catalog-169/fixture-manifest.json'), 'utf8'));
const expected = JSON.parse(fs.readFileSync(path.join(root, 'fixtures/research/catalog-169/expected-events.json'), 'utf8'));
const sourcePath = path.join(root, 'fixtures/research/catalog-169/synthetic-score.alphatex');
const source = fs.readFileSync(sourcePath);
const fixture = manifest.fixtures.find(f => f.id === 'gm169-synthetic-alphaTex-001');
assert.equal(crypto.createHash('sha256').update(source).digest('hex'), fixture.sha256, 'synthetic source SHA-256');
assert.equal(crypto.createHash('sha256').update(fs.readFileSync(path.join(root, fixture.expectedEvents))).digest('hex'), fixture.expectedEventsSha256, 'expected event SHA-256');
assert.ok(source.length <= 20 * 1024 * 1024, 'fixture within 20 MiB');
assert.equal(expected.events.length, 20, '20 expected note events');
assert.equal(new Set(expected.events.map(e => e.bar)).size, 5, 'five bars');
const openStringMidi = [64, 59, 55, 50, 45, 40];
for (const event of expected.events) {
  assert.ok(event.string >= 1 && event.string <= 6, 'string index in range');
  assert.ok(event.fret >= 0 && event.fret <= 24, 'fret in range');
  assert.equal(event.midi, openStringMidi[event.string - 1] + event.fret, 'MIDI pitch matches standard tuning at bar ' + event.bar);
  assert.equal(event.displayMidi, event.midi + 12, 'written guitar notation is one octave above sounding pitch');
  assert.equal(event.duration, 4, 'quarter-note duration');
}
for (let bar = 1; bar <= 5; bar++) assert.equal(expected.events.filter(e => e.bar === bar).length, 4, 'four events in bar ' + bar);
for (const generated of manifest.fixtures.filter(f => f.format?.startsWith('.gp'))) {
  const generatedPath = path.join(root, generated.path);
  assert.ok(fs.existsSync(generatedPath), 'generated fixture exists: ' + generated.path);
  assert.equal(crypto.createHash('sha256').update(fs.readFileSync(generatedPath)).digest('hex'), generated.sha256, 'generated fixture SHA-256: ' + generated.format);
  assert.ok(fs.statSync(generatedPath).size <= 20 * 1024 * 1024, 'generated fixture within 20 MiB: ' + generated.format);
}
console.log('PASS: fixture provenance, SHA-256, size cap, expected events, tuning, durations, and five-bar structure');
