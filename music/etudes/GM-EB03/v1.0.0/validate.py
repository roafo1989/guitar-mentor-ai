#!/usr/bin/env python3
"""Validate the independent GM-EB03 MusicXML and event-score sources (stdlib only)."""
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

BASE = Path(__file__).resolve().parent
PITCHES = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}
OPEN_MIDI = {1: 64, 2: 59, 3: 55, 4: 50, 5: 45, 6: 40}

def check():
    events = json.loads((BASE / "score_events.json").read_text(encoding="utf-8"))
    manifest = json.loads((BASE / "manifest.json").read_text(encoding="utf-8"))
    root = ET.parse(BASE / "score.musicxml").getroot()
    assert root.tag == "score-partwise" and root.get("version") == "4.0"
    assert events["code"] == manifest["code"] == "GM-EB03"
    assert events["version"] == manifest["version"] == "1.0.0"
    assert events["bpm"] == manifest["bpm"] == 76
    assert events["meter"] == manifest["meter"] == "4/4"
    measures = root.findall("./part/measure")
    assert len(measures) == len(events["measures"]) == manifest["measures"] == 8
    assert root.findtext("./part/measure/attributes/divisions") == "2"
    assert root.findtext("./part/measure/attributes/time/beats") == "4"
    assert root.findtext("./part/measure/attributes/time/beat-type") == "4"
    assert root.find("./part/measure/attributes/transpose") is None
    total_melody = 0
    for number, (measure, data) in enumerate(zip(measures, events["measures"]), 1):
        assert int(measure.get("number")) == data["number"] == number
        harmony = measure.find("./harmony/root/root-step")
        assert harmony is not None and harmony.text == data["chord"][0]
        notes = measure.findall("./note")
        assert len(notes) == len(data["melody"]) + len(data["accompaniment"])
        assert len(data["accompaniment"]) == 8
        for voice_id, key in ((1, "melody"), (2, "accompaniment")):
            expected = data[key]
            assert abs(sum(n["duration"] for n in expected) - 4) < 1e-9
            assert sorted(round(n["start"], 5) for n in expected) == [round(n["start"], 5) for n in expected]
            xml_notes = [n for n in notes if n.findtext("voice") == str(voice_id)]
            assert len(xml_notes) == len(expected)
            cursor = 0
            for note, event in zip(xml_notes, expected):
                assert round(event["start"], 5) == round(cursor, 5)
                assert int(note.findtext("duration")) == round(event["duration"] * 2)
                assert int(note.findtext("./notations/technical/string")) == event["string"]
                assert int(note.findtext("./notations/technical/fret")) == event["fret"]
                assert 0 <= event["fret"] <= 5
                assert event["voice"] == voice_id
                pitch = event["pitch"]
                midi = 12 * (pitch["octave"] + 1) + PITCHES[pitch["step"]] + pitch.get("alter", 0)
                assert midi == OPEN_MIDI[event["string"]] + event["fret"]
                assert note.findtext("./pitch/step") == pitch["step"]
                assert int(note.findtext("./pitch/octave")) == pitch["octave"]
                assert int(note.findtext("./pitch/alter") or 0) == pitch.get("alter", 0)
                if event["duration"] == 3:
                    assert note.find("dot") is not None
                cursor += event["duration"]
            assert round(cursor, 5) == 4
        total_melody += len(data["melody"])
        assert measure.findtext("./backup/duration") == "8"
    assert total_melody >= 24
    assert manifest["origin"] == "ORIGINAL"
    assert manifest["reviewStatus"] == "PENDING_MUSICAL_REVIEW"
    assert manifest["mediaDeferredToIssue"] == 81
    print("PASS: 8 bars; independent voices; MusicXML/event pitch, rhythm, string/fret and rights metadata agree")

if __name__ == "__main__":
    try:
        check()
    except (AssertionError, ValueError, OSError, ET.ParseError) as exc:
        print(f"FAIL: GM-EB03 source validation: {exc}", file=sys.stderr)
        sys.exit(1)
