#!/usr/bin/env python3
"""GM-EB02 v2: two-voice MusicXML/JSON consistency checks; standard library only."""
import copy
import json
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).parent
OPEN_MIDI = {1: 64, 2: 59, 3: 55, 4: 50, 5: 45, 6: 40}
PC = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


def validate(xml, data):
    score = ET.fromstring(xml)
    assert score.tag == "score-partwise"
    assert data["code"] == "GM-EB02"
    assert data["reviewStatus"] == "PENDING_HUMAN_MUSICAL_REVIEW"
    assert data["origin"] == "ORIGINAL"
    measures = score.findall("./part/measure")
    assert len(measures) == data["measures"] == 8
    a = measures[0].find("attributes")
    assert a.findtext("time/beats") == "4"
    assert a.findtext("time/beat-type") == "4"
    divisions = int(a.findtext("divisions"))
    assert divisions == 10080
    expected = []
    for i, measure in enumerate(measures, 1):
        per_voice = {1: 0, 2: 0}
        voices = {1: [], 2: []}
        for note in measure.findall("note"):
            assert note.find("rest") is None
            voice = int(note.findtext("voice"))
            assert voice in per_voice
            q = int(note.findtext("duration")) / divisions
            assert q in (0.5, 1.0, 1.5, 2.0)
            beat = per_voice[voice] + 1
            string = int(note.findtext("notations/technical/string"))
            fret = int(note.findtext("notations/technical/fret"))
            finger = note.findtext("notations/technical/pluck")
            step = note.findtext("pitch/step")
            octave = int(note.findtext("pitch/octave"))
            alter = int(note.findtext("pitch/alter", "0"))
            pitch = 12 * (octave + 1) + PC[step] + alter
            assert 1 <= string <= 6 and 0 <= fret <= 3
            assert OPEN_MIDI[string] + fret == pitch, f"Pitch/fret mismatch at bar {i}"
            assert finger in ("p", "i", "m", "a")
            if voice == 2:
                assert finger == "p"
            event = dict(measure=i, beat=beat, voice=voice, note=f"{step}{octave}",
                         string=string, fret=fret, durationQuarter=q, finger=finger)
            voices[voice].append(event)
            per_voice[voice] += q
        assert all(abs(x - 4) < 1e-10 for x in per_voice.values()), f"Incomplete bar {i}"
        assert len(voices[2]) == 2 and [x["beat"] for x in voices[2]] == [1, 3]
        expected.extend(voices[1] + voices[2])
    assert expected == data["events"], "MusicXML / score_events.json divergence"
    assert len(expected) == 50
    assert len([e for e in expected if e["voice"] == 1]) == 34
    assert len([e for e in expected if e["voice"] == 2]) == 16
    assert measures[-1].findtext("barline/bar-style") == "light-heavy"
    return True


class ScoreConsistencyTest(unittest.TestCase):
    def setUp(self):
        self.xml = (ROOT / "score.musicxml").read_text(encoding="utf-8")
        self.data = json.loads((ROOT / "score_events.json").read_text(encoding="utf-8"))

    def test_xml_matches_json(self):
        self.assertTrue(validate(self.xml, self.data))

    def test_detect_wrong_fret(self):
        broken = copy.deepcopy(self.data)
        broken["events"][0]["fret"] += 1
        with self.assertRaises(AssertionError):
            validate(self.xml, broken)

    def test_detect_wrong_duration(self):
        broken = copy.deepcopy(self.data)
        broken["events"][0]["durationQuarter"] = 2
        with self.assertRaises(AssertionError):
            validate(self.xml, broken)

    def test_detect_wrong_voice(self):
        broken = copy.deepcopy(self.data)
        broken["events"][0]["voice"] = 2
        with self.assertRaises(AssertionError):
            validate(self.xml, broken)


if __name__ == "__main__":
    unittest.main()
