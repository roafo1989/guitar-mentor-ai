#!/usr/bin/env python3
"""Check GM-EB05 event data against MusicXML without external dependencies."""
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

BASE = Path(__file__).resolve().parents[1] / "music/etudes/GM-EB05"
TUNING = {1: 64, 2: 59, 3: 55, 4: 50, 5: 45, 6: 40}
STEPS = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}
CHORDS = ["E7", "E7", "A7", "E7", "A7", "A7", "E7", "C#7", "F#m7", "B7", "E7", "E7"]
BASS = {"E7": ((6, 0), (5, 2)), "A7": ((5, 0), (4, 2)),
        "C#7": ((5, 4), (6, 4)), "F#m7": ((6, 2), (5, 4)),
        "B7": ((5, 2), (6, 2))}

def check(condition, message):
    if not condition:
        raise ValueError(message)

def actual_events(measure, voice):
    events, cursor = [], 0
    for node in measure.findall("note"):
        if int(node.findtext("voice", "-1")) != voice:
            continue
        duration = int(node.findtext("duration"))
        check(duration > 0, "Invalid note duration")
        if node.find("chord") is None:
            offset = cursor
            cursor += duration
        else:
            offset = cursor - duration
        if node.find("rest") is not None:
            continue
        p = node.find("pitch")
        tech = node.find("notations/technical")
        check(p is not None and tech is not None, "Missing pitch/fingering")
        string, fret = int(tech.findtext("string")), int(tech.findtext("fret"))
        check(string in TUNING, "Invalid string")
        midi = (int(p.findtext("octave")) + 1) * 12 + STEPS[p.findtext("step")] + int(p.findtext("alter", "0"))
        check(midi == TUNING[string] + fret, "Pitch differs from string/fret")
        events.append((offset, duration, string, fret))
    check(cursor == 16, "Voice does not fill 4/4")
    return events

def validate():
    data = json.loads((BASE / "score_events_v1.json").read_text(encoding="utf-8"))
    meta = json.loads((BASE / "metadata.json").read_text(encoding="utf-8"))
    root = ET.parse(BASE / "midnight_train_blues_v1.musicxml").getroot()
    check(data["metadata"] == meta, "Inconsistent metadata")
    check(meta["id"] == "GM-EB05" and meta["version"] == "1.0.1", "Wrong identity")
    check(meta["source"] == "ORIGINAL" and meta["license"] == "CC BY 4.0", "Rights missing")
    check(meta["review"]["ownerListening"].startswith("PENDING"), "Manual approval cannot be automatic")
    check(meta["bars"] == 12 and meta["meter"] == "4/4" and meta["bpm"] == 88, "Unexpected form or tempo")
    measures = root.findall("./part/measure")
    check(len(measures) == len(data["measures"]) == 12, "Wrong measure count")
    event_count = 0
    for index, (xm, jm) in enumerate(zip(measures, data["measures"])):
        context = "bar %d" % (index + 1)
        check(jm["bar"] == int(xm.attrib["number"]) == index + 1, context + ": order mismatch")
        check(jm["chord"] == CHORDS[index], context + ": chord progression")
        harmony = xm.find("harmony")
        check(harmony is not None, context + ": missing chord symbol")
        name = harmony.findtext("root/root-step")
        if harmony.findtext("root/root-alter") == "1":
            name += "#"
        name += "m7" if harmony.findtext("kind") == "minor-seventh" else "7"
        check(name == jm["chord"], context + ": chord symbol mismatch")
        for field, voice in (("upper", 1), ("bass", 2)):
            expected, cursor = [], 0
            for ev in jm[field]:
                at, duration = round(ev["beat"] * 4), round(ev["length"] * 4)
                check(at >= cursor and duration > 0 and at + duration <= 16, context + ": event overlap")
                cursor = at + duration
                for n in ev["notes"]:
                    string, fret = n["string"], n["fret"]
                    check(string in TUNING and 0 <= fret <= 4, context + ": invalid position")
                    expected.append((at, duration, string, fret))
            check(expected == actual_events(xm, voice), context + "/" + field + ": event mismatch")
            event_count += len(expected)
        bass = [((e["beat"], e["length"]), (e["notes"][0]["string"], e["notes"][0]["fret"])) for e in jm["bass"]]
        reference = [((beat, 1), BASS[jm["chord"]][beat % 2]) for beat in range(4)]
        check(bass == reference, context + ": incorrect bass pulse")
    print("PASS: 12 bars, 24 voices, %d notes; pitch, harmony, bass and metadata agree" % event_count)

if __name__ == "__main__":
    try:
        validate()
    except (ValueError, ET.ParseError, OSError, KeyError, TypeError, IndexError) as error:
        print("FAIL:", error, file=sys.stderr)
        sys.exit(1)
