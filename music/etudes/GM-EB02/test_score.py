#!/usr/bin/env python3
"""Validate GM-EB02 MusicXML against its event manifest (Python standard library)."""
import json
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OPEN_MIDI = (64, 59, 55, 50, 45, 40)  # strings 1..6
PITCH_CLASS = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


def midi(step, octave, alter=0):
    return 12 * (int(octave) + 1) + PITCH_CLASS[step] + int(alter)


def validate(xml_text, data):
    root = ET.fromstring(xml_text)
    assert root.tag == "score-partwise", "Expected score-partwise"
    assert data["code"] == "GM-EB02"
    assert data["meter"] == "4/4"
    assert data["reviewStatus"] == "PENDING_HUMAN_MUSICAL_REVIEW"
    assert data["origin"] == "ORIGINAL"
    measures = root.findall("./part/measure")
    assert len(measures) == data["measures"] == 8
    attr = measures[0].find("attributes")
    assert attr.findtext("time/beats") == "4"
    assert attr.findtext("time/beat-type") == "4"
    divisions = int(attr.findtext("divisions"))
    assert divisions > 0
    expected = []
    for measure_num, measure in enumerate(measures, 1):
        beat = 1
        duration_total = 0
        for note in measure.findall("note"):
            assert note.find("rest") is None, "Unexpected rest: manifest has no rests"
            assert note.find("chord") is None, "Unexpected chord"
            duration = int(note.findtext("duration"))
            assert duration > 0
            q = duration / divisions
            string = int(note.findtext("notations/technical/string"))
            fret = int(note.findtext("notations/technical/fret"))
            pluck = note.findtext("notations/technical/pluck")
            assert 1 <= string <= 6
            assert 0 <= fret <= 3, "Must stay in first position"
            assert pluck in {"p", "i", "m", "a"}
            p = note.find("pitch")
            step, octave = p.findtext("step"), int(p.findtext("octave"))
            alter = int(p.findtext("alter", default="0"))
            assert OPEN_MIDI[string - 1] + fret == midi(step, octave, alter), (
                f"Pitch/string/fret mismatch at measure {measure_num}, beat {beat}"
            )
            expected.append(dict(measure=measure_num, beat=beat, voice=int(note.findtext("voice")),
                                 note=f"{step}{'#' if alter == 1 else 'b' if alter == -1 else ''}{octave}",
                                 string=string, fret=fret, durationQuarter=q, finger=pluck))
            beat += q
            duration_total += duration
        assert duration_total == 4 * divisions, f"Measure {measure_num} duration"
    assert expected == data["events"], "MusicXML events differ from JSON"
    assert len(expected) == 32
    assert all(e["finger"] == "p" for e in expected if e["beat"] == 1)
    assert measures[-1].findtext("barline/bar-style") == "light-heavy"
    return True


class ScoreValidation(unittest.TestCase):
    def setUp(self):
        self.xml = (ROOT / "score.musicxml").read_text(encoding="utf-8")
        self.data = json.loads((ROOT / "score_events.json").read_text(encoding="utf-8"))

    def test_valid_score(self):
        self.assertTrue(validate(self.xml, self.data))

    def test_detect_wrong_fret(self):
        broken = json.loads(json.dumps(self.data))
        broken["events"][0]["fret"] += 1
        with self.assertRaises(AssertionError):
            validate(self.xml, broken)

    def test_detect_wrong_duration(self):
        broken = json.loads(json.dumps(self.data))
        broken["events"][0]["durationQuarter"] = 2
        with self.assertRaises(AssertionError):
            validate(self.xml, broken)


if __name__ == "__main__":
    unittest.main()
