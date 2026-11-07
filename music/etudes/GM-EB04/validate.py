#!/usr/bin/env python3
"""GM-EB04 score/JSON cross-validation. Uses only Python standard library."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
OPEN = {1:64, 2:59, 3:55, 4:50, 5:45, 6:40}
PC = {"C":0,"D":2,"E":4,"F":5,"G":7,"A":9,"B":11}
def same(a,b,detail):
    if a != b:
        raise AssertionError(f"{detail}: {a} != {b}")
def run():
    hashes=json.loads((HERE/"checksums_v1.json").read_text(encoding="utf-8"))["sha256"]
    for name,expected in hashes.items():
        same(hashlib.sha256((HERE/name).read_bytes()).hexdigest(),expected,"SHA256 "+name)
    data=json.loads((HERE/"score_events_v1.json").read_text(encoding="utf-8"))
    meta=json.loads((HERE/"metadata_v1.json").read_text(encoding="utf-8"))
    same(data["metadata"],meta,"metadata")
    root=ET.parse(HERE/"two_paths_v1.musicxml").getroot()
    same(root.get("version"),"4.0","XML version")
    measures=root.findall("./part/measure")
    same(len(measures),8,"bar count")
    same(len(data["measures"]),8,"JSON bar count")
    a=measures[0].find("attributes")
    same(a.findtext("staves"),"2","staff count")
    same(a.find("clef[@number='2']").findtext("sign"),"TAB","TAB clef")
    same(a.find("staff-details").findtext("staff-lines"),"6","TAB lines")
    same(a.find("clef[@number='1']").findtext("clef-octave-change"),"-1","guitar octave")
    same(measures[0].find("direction/sound").get("tempo"),str(meta["bpm"]),"tempo")
    for measure,entry in zip(measures,data["measures"]):
        same(int(measure.get("number")),entry["bar"],"bar index")
        for field,v,staff in (("upper",1,1),("bass",2,1),("upper",3,2),("bass",4,2)):
            source=entry[field]
            rendered=[n for n in measure.findall("note") if n.findtext("voice")==str(v)]
            same(len(source),len(rendered),"voice events")
            current=0
            for event,n in zip(source,rendered):
                same(event["beat"],current,"beat")
                current+=event["length"]
                same(int(n.findtext("duration")),int(event["length"]*480),"duration")
                same(n.findtext("staff"),str(staff),"staff")
                same(n.find("rest") is not None, bool(event.get("rest")),"rest")
                if not event.get("rest"):
                    pos=event["notes"][0]
                    string,fret=pos["string"],pos["fret"]
                    if string not in OPEN or not 0 <= fret <= 24:
                        raise AssertionError("invalid string/fret")
                    midi=OPEN[string]+fret
                    same(pos["pitch"],midi,"JSON pitch")
                    pitch=n.find("pitch")
                    written=(int(pitch.findtext("octave"))+1)*12+PC[pitch.findtext("step")]+int(pitch.findtext("alter","0"))
                    same(written,midi,"MusicXML pitch")
                    same(int(n.findtext("notations/technical/string")),string,"string")
                    same(int(n.findtext("notations/technical/fret")),fret,"fret")
            same(current,4,"bar duration")
    same(data["measures"][-1]["upper"][-1]["notes"][0]["pitch"],60,"final C")
    print("PASS: hashes, 8 measures, two staves, four voices, guitar octave, TAB, notes, rhythm, rests, cadence")
if __name__=="__main__":
    run()
