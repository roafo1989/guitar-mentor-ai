#!/usr/bin/env python3
"""Reproducible preview-media generator for the original GM-DIAG 3/8 pulse probe.
Run from repository root: python scripts/diag145/generate_media.py
Requires reportlab and ffmpeg. Uses only score_events.json as source.
"""
import json
import math
import struct
import subprocess
import tempfile
import wave
from pathlib import Path
from xml.etree import ElementTree as ET

from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "backend/src/main/resources/exercises/GM-DIAG-PULSE-3-8-01"
DATA = json.loads((ASSETS / "score_events.json").read_text(encoding="utf-8"))
SCORE = ET.parse(ASSETS / "score.musicxml").getroot()
PDF = ASSETS / "preview_v010.pdf"
MP3 = ASSETS / "preview_v010.mp3"

def assert_source():
    assert DATA["meter"] == "3/8" and DATA["bpm"] == 100
    assert DATA["tempoUnit"] == "eighth" and DATA["durationTargetSeconds"] == 22
    assert len(DATA["measures"]) == 12
    xml_measures = SCORE.findall("./part/measure")
    assert len(xml_measures) == 12
    result = []
    for bar, xml_bar in zip(DATA["measures"], xml_measures):
        evs = bar["upper"]
        xml_notes = xml_bar.findall("note")
        assert len(evs) == len(xml_notes) == 3
        assert len(bar["bass"]) == 0
        assert abs(sum(e["duration"] for e in evs) - 1.5) < 1e-9
        for ev, xml_note in zip(evs, xml_notes):
            entry = ev["notes"][0]
            assert int(xml_note.findtext("notations/technical/string")) == entry["string"]
            assert int(xml_note.findtext("notations/technical/fret")) == entry["fret"]
            assert xml_note.findtext("notations/technical/pluck") == ev["finger"]
            assert int(xml_note.findtext("duration")) == 5040
            assert xml_note.findtext("pitch/step") + str(int(xml_note.findtext("pitch/octave"))-1) == ev["pitch"]
            assert (entry["string"], entry["fret"], ev["pitch"]) in (
                (3, 0, "G3"), (2, 0, "B3"), (1, 0, "E4"))
            result.append((bar["bar"], ev["beat"], ev["pitch"], ev["finger"]))
    assert len(result) == 36
    return result

def draw_score():
    doc = canvas.Canvas(str(PDF), pagesize=A4, pageCompression=1)
    w, h = A4
    doc.setFont("Helvetica-Bold", 17)
    doc.drawString(48, h-57, "Triple-meter pulse (3/8) — technical probe")
    doc.setFont("Helvetica", 9)
    doc.drawString(48, h-75, "12 bars  |  eighth = 100 BPM  |  21.6 s excluding count-in  |  no pick")
    doc.drawString(48, h-90, "Standard-tuned guitar. Open strings G3–B3–E4, right hand i–m–a.")
    # A guitar's treble part is written one octave above its sounding pitch.
    for system in range(3):
        top = h-162-214*system
        x0, x1 = 92, 551
        width=(x1-x0)/4
        bottom = top - 44
        tab_top = bottom-58
        doc.setStrokeColorRGB(0,0,0)
        doc.setLineWidth(.65)
        for k in range(5): doc.line(x0, bottom+k*11, x1,bottom+k*11)
        for k in range(6): doc.line(x0, tab_top-k*9, x1,tab_top-k*9)
        doc.setFont("Helvetica", 8)
        doc.drawString(51,tab_top-20,"TAB")
        # The five-line staff uses E4 as its bottom line; G4/B4/E5 are
        # visually situated on 2nd line / 3rd line / 4th space, respectively.
        ys = {"G3":bottom+11,"B3":bottom+22,"E4":bottom+38.5}
        tab_ys={3:tab_top-18,2:tab_top-9,1:tab_top}
        for j in range(4):
            bar = DATA["measures"][system*4+j]
            start=x0+j*width
            end=start+width
            doc.line(start,bottom,end if j==3 else start,bottom) if False else None
            if j==0: doc.line(start,bottom,start,top)
            doc.line(end,bottom,end,top)
            doc.line(end,tab_top-45,end,tab_top)
            if j==0: doc.line(start,tab_top-45,start,tab_top)
            doc.setFont("Helvetica",8)
            doc.drawString(start+3,top+12,str(bar["bar"]))
            if system==0 and j==0:
                doc.setFont("Helvetica-Bold",10)
                doc.drawRightString(x0-10,top-10,"3")
                doc.drawRightString(x0-10,top-26,"8")
            for k, ev in enumerate(bar["upper"]):
                x=start+23+k*29
                y=ys[ev["pitch"]]
                doc.setFillColorRGB(0,0,0)
                doc.ellipse(x-4.5,y-3.2,x+4.5,y+3.2,fill=1,stroke=0)
                doc.setLineWidth(.7)
                doc.line(x+4,y,x+4,y+24)
                # Individual eighth-note flag.
                p=doc.beginPath()
                p.moveTo(x+4,y+24)
                p.curveTo(x+14,y+23,x+16,y+12,x+12,y+8)
                doc.drawPath(p,stroke=1)
                doc.setFont("Helvetica",8)
                doc.drawCentredString(x,y+37,ev["finger"])
                doc.drawCentredString(x,tab_ys[ev["notes"][0]["string"]]-2,
                                      str(ev["notes"][0]["fret"]))
        doc.setFont("Helvetica",8)
        doc.drawString(x0,tab_top-59,"Count 1–2–3; slightly emphasize the first eighth in every bar.")
    doc.setFont("Helvetica",8)
    doc.drawString(48,45,"Review draft — combined staff/TAB, not yet approved for automatic assignment.")
    doc.save()

def draw_audio():
    rate=22050
    note_duration=.6
    start=3*note_duration
    duration=start+36*note_duration+.6
    values=[0.0]*int(duration*rate)
    freqs={"G3":196.0,"B3":246.94165,"E4":329.62756}
    for index,(_,_,pitch,_) in enumerate(assert_source()):
        offset=int((start+index*note_duration)*rate)
        freq=freqs[pitch]
        loud=.30 if index%3==0 else .21
        for i in range(int(.55*rate)):
            t=i/rate
            envelope=(1-math.exp(-t*70))*math.exp(-t*5.3)
            signal=sum(math.sin(2*math.pi*freq*(k+1)*t)/((k+1)**1.6)
                       for k in range(3))
            values[offset+i]+=loud*envelope*signal
    for click in range(3):
        offset=int(click*note_duration*rate)
        for i in range(int(.045*rate)):
            t=i/rate
            values[offset+i]+=.38*math.exp(-t*55)*math.sin(2*math.pi*1550*t)
    # Soft limiting avoids clipping.
    pcm=bytearray()
    for v in values:
        v=max(-.95,min(.95,v))
        pcm+=struct.pack("<h",int(v*32767))
    with tempfile.TemporaryDirectory() as tmp:
        wav=Path(tmp)/"probe.wav"
        with wave.open(str(wav),"wb") as f:
            f.setnchannels(1);f.setsampwidth(2);f.setframerate(rate)
            f.writeframes(pcm)
        subprocess.run(["ffmpeg","-nostdin","-y","-loglevel","error","-i",str(wav),
                        "-codec:a","libmp3lame","-b:a","64k",str(MP3)],check=True)

if __name__=="__main__":
    assert_source()
    draw_score()
    draw_audio()
    assert PDF.stat().st_size>3000 and MP3.stat().st_size>3000
    print("Generated",PDF,MP3)
