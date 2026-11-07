#!/usr/bin/env python3
"""Independent verification of GM-EB05 revision v1.1 against its MusicXML."""
import json
import xml.etree.ElementTree as ET
from pathlib import Path

P = Path(__file__).resolve().parents[1] / 'music/etudes/GM-EB05'
T = {1:64, 2:59, 3:55, 4:50, 5:45, 6:40}
STEPS = {'C':0,'D':2,'E':4,'F':5,'G':7,'A':9,'B':11}
CHORDS = ['E7','E7','A7','E7','A7','A7','E7','C#7','F#m7','B7','E7','E7']

def require(test, message):
    if not test: raise AssertionError(message)

def extract(measure, voice):
    out, cursor = [], 0
    for node in measure.findall('note'):
        if int(node.findtext('voice','-1')) != voice: continue
        dur = int(node.findtext('duration','0'))
        require(dur in {2,4,6,8,16}, 'unsupported duration')
        if node.find('rest') is not None:
            cursor += dur
            continue
        p, t = node.find('pitch'), node.find('notations/technical')
        require(p is not None and t is not None, 'missing pitch/string/fret')
        s, f = int(t.findtext('string')), int(t.findtext('fret'))
        require(s in T and 0 <= f <= 4, 'unsafe fingering')
        midi = (int(p.findtext('octave'))+1)*12 + STEPS[p.findtext('step')] + int(p.findtext('alter','0'))
        require(midi == T[s]+f, 'wrong pitch at string/fret')
        out.append((cursor,dur,s,f))
        cursor += dur
    require(cursor == 16, 'voice length not 4/4')
    return out

def run():
    raw = json.loads((P/'score_events_v1_1.json').read_text())
    meta = json.loads((P/'metadata_v1_1.json').read_text())
    root = ET.parse(P/'midnight_train_blues_v1_1.musicxml').getroot()
    require(raw['metadata'] == meta, 'metadata differs')
    require(meta['id']=='GM-EB05' and meta['version']=='1.1.0', 'incorrect version')
    require(meta['source']=='ORIGINAL' and meta['license']=='CC BY 4.0','missing rights')
    require(meta['review']['ownerListening']=='PENDING_OWNER','owner approval falsely claimed')
    require(meta['meter']=='4/4' and meta['bars']==12 and meta['bpm']==96,'wrong meter/tempo')
    bars = root.findall('./part/measure')
    require(len(bars)==len(raw['measures'])==12,'bar count')
    checked=0
    for i,(xml,bar) in enumerate(zip(bars,raw['measures'])):
        require(bar['bar']==i+1==int(xml.attrib['number']),'bar order')
        require(bar['chord']==CHORDS[i],'harmony order')
        h=xml.find('harmony')
        require(h is not None, 'missing chord symbol')
        chord=h.findtext('root/root-step')+('#' if h.findtext('root/root-alter')=='1' else '')+('m7' if h.findtext('kind')=='minor-seventh' else '7')
        require(chord==bar['chord'], 'chord mismatch at bar '+str(i+1))
        for key,voice in [('upper',1),('bass',2)]:
            expected=[]
            for event in bar[key]:
                start,dur=round(event['beat']*4),round(event['length']*4)
                require(dur>0 and start>=0 and start+dur<=16, 'outside bar')
                for n in event['notes']:expected.append((start,dur,n['string'],n['fret']))
            require(expected==extract(xml,voice),f'XML/JSON mismatch bar {i+1} voice {voice}')
            checked+=len(expected)
        require([e['beat'] for e in bar['bass']]==[0,1,2,3], 'unstable quarter bass')
    print(f'PASS: GM-EB05 1.1.0, 12 measures, 24 voices, {checked} notes; rhythm, pitch, chords, metadata consistent')

if __name__=='__main__':
    run()
