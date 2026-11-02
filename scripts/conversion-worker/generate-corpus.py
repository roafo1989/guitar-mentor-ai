"""Original synthetic musical/adversarial corpus. No private/user files."""
import copy
import importlib.util
import io
import json
import zipfile
from pathlib import Path
import guitarpro as gp
ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('fixtures169', ROOT/'scripts/research/catalog-169/generate_fixtures.py')
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
OUT = ROOT/'artifacts/conversion-170e/corpus'
OUT.mkdir(parents=True, exist_ok=True)
records = []
def save(name, fmt, data, expected, track=0):
    target=OUT/f'{name}.{fmt.lower()}'
    target.write_bytes(data)
    records.append(dict(name=name, format=fmt, path=str(target.relative_to(ROOT)), expected=expected, track=track))
def beat(song): return song.tracks[0].measures[0].voices[0].beats[0]
def note(song): return beat(song).notes[0]
for fmt, version in [('GP3',(3,0,0)),('GP4',(4,0,6)),('GP5',(5,1,0))]:
    variants=[('valid',lambda s:None,'RENDERED'),
      ('three-four',lambda s:setattr(s.measureHeaders[0].timeSignature,'numerator',3),'UNSUPPORTED_TIME_SIGNATURE'),
      ('three-eight',lambda s:(setattr(s.measureHeaders[0].timeSignature,'numerator',3),setattr(s.measureHeaders[0].timeSignature,'denominator',gp.Duration(8))),'UNSUPPORTED_TIME_SIGNATURE'),
      ('six-eight',lambda s:(setattr(s.measureHeaders[0].timeSignature,'numerator',6),setattr(s.measureHeaders[0].timeSignature,'denominator',gp.Duration(8))),'UNSUPPORTED_TIME_SIGNATURE'),
      ('rest',lambda s:(setattr(beat(s),'status',gp.BeatStatus.rest),beat(s).notes.clear()),'UNSUPPORTED_RHYTHM_OR_REST'),
      ('dotted',lambda s:setattr(beat(s).duration,'isDotted',True),'UNSUPPORTED_RHYTHM_OR_REST'),
      ('triplet',lambda s:setattr(beat(s).duration,'tuplet',gp.Tuplet(3,2)),'UNSUPPORTED_RHYTHM_OR_REST'),
      ('repeat',lambda s:setattr(s.measureHeaders[0],'isRepeatOpen',True),'UNSUPPORTED_REPEATS'),
      ('drop-d',lambda s:setattr(s.tracks[0].strings[-1],'value',38),'UNSUPPORTED_TUNING'),
      ('open-g',lambda s:[setattr(v,'value',p) for v,p in zip(s.tracks[0].strings,[62,59,55,50,43,38])],'UNSUPPORTED_TUNING'),
      ('capo',lambda s:setattr(s.tracks[0],'offset',2),'UNSUPPORTED_CAPO'),
      ('vibrato',lambda s:setattr(note(s).effect,'vibrato',True),'UNSUPPORTED_NOTE_EFFECT'),
      ('palm-mute',lambda s:setattr(note(s).effect,'palmMute',True),'UNSUPPORTED_NOTE_EFFECT'),
      ('fade',lambda s:setattr(beat(s).effect,'fadeIn',True),'UNSUPPORTED_NOTE_EFFECT'),
      ('tempo',lambda s:setattr(s.tracks[0].measures[1].voices[0].beats[0].effect,'mixTableChange',gp.MixTableChange(tempo=gp.MixTableItem(120))),'UNSUPPORTED_TEMPO_CHANGE'),
      ('instrument',lambda s:setattr(s.tracks[0].measures[1].voices[0].beats[0].effect,'mixTableChange',gp.MixTableChange(instrument=gp.MixTableItem(25))),'UNSUPPORTED_BEAT_AUTOMATION')]
    def chord(s):
        other=copy.copy(note(s)); other.string=2; beat(s).notes.append(other)
    variants.append(('chord',chord,'UNSUPPORTED_RHYTHM_OR_REST'))
    if fmt=='GP5':
        def voice(s):
            v=s.tracks[0].measures[0].voices[1]
            b=gp.Beat(v); b.start=s.tracks[0].measures[0].start; b.status=gp.BeatStatus.normal; b.duration=gp.Duration(1)
            n=gp.Note(b); n.string=2; n.value=1; n.type=gp.NoteType.normal
            b.notes.append(n); v.beats.append(b)
        variants.append(('second-voice',voice,'INVALID_OR_UNSUPPORTED_FILE'))
    for name, change, expected in variants:
        if fmt=='GP3' and name=='palm-mute': continue # GP3 cannot encode this effect
        song=base.build_song(); change(song)
        output=io.BytesIO(); gp.write(song,output,version=version)
        save(name,fmt,output.getvalue(),expected)
    data=(OUT/f'valid.{fmt.lower()}').read_bytes()
    save('tracks',fmt,data,'TRACK_SELECTION_REQUIRED',None)
    # Boundary layouts: an incomplete first row, and multiple full pages at a safe duration.
    for count, name in [(1,'one-bar'),(32,'many-bars')]:
        song=base.build_song()
        while len(song.measureHeaders)<count: song.newMeasure()
        song.measureHeaders=song.measureHeaders[:count]
        start=gp.Duration.quarterTime
        for header in song.measureHeaders: header.start=start; start+=header.length
        for ti,track in enumerate(song.tracks):
            track.measures=track.measures[:count]
            for bar in range(5,count):
                values=[(e['string'],e['fret']) for e in base.EVENTS if e['bar']==bar%5+1] if ti==0 else base.BASS[:4]
                base.add_notes(track,[track.measures[bar]],[values])
        if count>5: song.tempo=240
        output=io.BytesIO(); gp.write(song,output,version=version)
        save(name,fmt,output.getvalue(),'RENDERED')
        records[-1]['events']=[[bar+1,beat,e['string'],e['fret'],e['midi'],960]
            for bar in range(count) for beat,e in enumerate([e for e in base.EVENTS if e['bar']==bar%5+1])]

    song=base.build_song(); song.tracks.pop()
    output=io.BytesIO(); gp.write(song,output,version=version)
    save('single',fmt,output.getvalue(),'RENDERED',None)
    save('empty',fmt,b'','EMPTY_OR_INVALID_SOURCE')
    save('corrupt',fmt,b'not a guitarpro score','UNSUPPORTED_FORMAT_VERSION')
    save('truncated',fmt,data[:40],'INVALID_OR_UNSUPPORTED_FILE')
    save('oversized',fmt,b'\0'*(20*1024*1024+1),'SOURCE_TOO_LARGE')
save('wrong-extension','GP3',(OUT/'valid.gp5').read_bytes(),'UNSUPPORTED_FORMAT_VERSION')
bomb=io.BytesIO()
with zipfile.ZipFile(bomb,'w',compression=zipfile.ZIP_DEFLATED) as z: z.writestr('huge.gp',b'0'*(25*1024*1024))
save('zip-bomb','GP5',bomb.getvalue(),'UNSUPPORTED_FORMAT_VERSION')
(OUT/'manifest.json').write_text(json.dumps(records,indent=2))
print(f'{len(records)} cases generated')
