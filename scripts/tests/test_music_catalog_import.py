import copy
import importlib.util
import pathlib
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "music_catalog_import.py"
spec = importlib.util.spec_from_file_location("music_catalog_import", SCRIPT)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


def fixture():
    return [
        [{"work_id": "w1", "title": "Piece", "identity_status": "CANDIDATE"}],
        [{"arrangement_id": "a1", "work_id": "w1", "name": "Arrangement", "parser_status": "PARSED"}],
        [{"file_id": "f1", "arrangement_id": "a1", "relative_path": r"Gitara\\score.gp5",
          "original_filename": "score.gp5", "source_group": "Gitara",
          "extension": ".gp5", "rights_status": "rights_unverified",
          "visibility": "PRIVATE", "size_bytes": "12"}],
        [{"file_id": "f1", "review_reasons": "artist_missing", "parser_status": "PARSED"}],
    ]


class MusicCatalogValidationTest(unittest.TestCase):
    def test_valid_private_index(self):
        mod.verify(fixture())

    def test_deny_public_file(self):
        data = fixture()
        data[2][0]["visibility"] = "PUBLIC"
        with self.assertRaises(ValueError):
            mod.verify(data)

    def test_deny_path_traversal(self):
        for path in ("../outside.gpx", r"Gitara\\..\\outside.gpx", r"C:\\Users\\secret.gpx", "/absolute.pdf"):
            with self.subTest(path=path):
                data = fixture()
                data[2][0]["relative_path"] = path
                with self.assertRaises(ValueError):
                    mod.verify(data)

    def test_missing_work_rejected(self):
        data = fixture()
        data[1][0]["work_id"] = "no-such-work"
        with self.assertRaises(ValueError):
            mod.verify(data)

    def test_duplicate_source_id_rejected(self):
        data = fixture()
        data[2].append(copy.deepcopy(data[2][0]))
        with self.assertRaises(ValueError):
            mod.verify(data)

    def test_invalid_work_identity_status_rejected(self):
        data = fixture()
        data[0][0]["identity_status"] = "INVALID"
        with self.assertRaises(ValueError):
            mod.verify(data)

    def test_duplicate_casefold_path_rejected(self):
        data = fixture()
        second = copy.deepcopy(data[2][0])
        second["file_id"] = "f2"
        second["relative_path"] = r"gitara\\SCORE.GP5"
        data[2].append(second)
        with self.assertRaises(ValueError):
            mod.verify(data)


if __name__ == "__main__":
    unittest.main()
