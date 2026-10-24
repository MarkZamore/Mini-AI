"""Small alignment/integrity fixtures; no model or production runtime runs."""

import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location(
    "filter_evidence_index", Path(__file__).resolve().parents[1] / "filter_evidence_index.py")
filter_index = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(filter_index)


class FilterEvidenceIndexTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "evidence"
        self.source.mkdir()
        self.db = self.root / "canonical.sqlite"
        con = sqlite3.connect(self.db)
        con.execute("CREATE TABLE documents(document_id TEXT PRIMARY KEY)")
        con.execute("INSERT INTO documents VALUES ('d1')")
        con.execute("CREATE TABLE chunks(fragment_id TEXT PRIMARY KEY, document_id TEXT, text TEXT)")
        bodies = ["", " \t\n", "\u00a0\u2003\u202f", "0", ".!?", " real text ", "\u200b"]
        con.executemany("INSERT INTO chunks VALUES (?, 'd1', ?)",
                        [(f"f{i}", text) for i, text in enumerate(bodies)])
        con.commit()
        con.close()
        # Deliberately non-canonical parent order and heterogeneous line endings.
        self.order = [5, 2, 0, 4, 1, 6, 3]
        self.lines = [json.dumps({"document_id": "d1", "fragment_id": f"f{i}",
                                 "sections": [" exact spacing "]}).encode() +
                      (b"\r\n" if n % 2 else b"\n") for n, i in enumerate(self.order)]
        self.vector_rows = [bytes([i + 1]) * filter_index.ROW_BYTES for i in self.order]
        (self.source / "fragments.jsonl").write_bytes(b"".join(self.lines))
        (self.source / "vectors.f32").write_bytes(b"".join(self.vector_rows))
        (self.source / "encoder.onnx").write_bytes(b"fake encoder identity only")
        self.manifest = {"schemaVersion": 1, "baseId": "local", "dimensions": 384,
                         "rows": len(bodies), "dtype": "little-endian float32",
                         "normalization": "L2", "corpusSha256": filter_index.digest(self.db),
                         "embedderSha256": filter_index.digest(self.source / "encoder.onnx"),
                         "vectors": "evidence/vectors.f32", "fragments": "evidence/fragments.jsonl",
                         "vectorsSha256": filter_index.digest(self.source / "vectors.f32"),
                         "fragmentsSha256": filter_index.digest(self.source / "fragments.jsonl"),
                         "selectionFreezeSha256": "a" * 64}
        self.save_manifest()
        self.out = self.root / "new-index"

    def save_manifest(self):
        (self.source / "index.json").write_text(json.dumps(self.manifest), encoding="utf-8")

    def derive(self):
        return filter_index.derive(self.db, self.source, self.out, min_free_bytes=0)

    def test_alignment_raw_lines_unicode_numeric_punctuation_and_order(self):
        before = {p: filter_index.digest(p) for p in [self.db, *self.source.iterdir()]}
        result = self.derive()
        kept = [n for n, i in enumerate(self.order) if i in {3, 4, 5, 6}]
        self.assertEqual(result["rows"], 4)
        self.assertEqual(result["removed_blank_body_rows"], 3)
        self.assertEqual((self.out / "vectors.f32").read_bytes(),
                         b"".join(self.vector_rows[n] for n in kept))
        self.assertEqual((self.out / "fragments.jsonl").read_bytes(),
                         b"".join(self.lines[n] for n in kept))
        derived = json.loads((self.out / "index.json").read_text())
        for field in ("corpusSha256", "embedderSha256", "selectionFreezeSha256"):
            self.assertEqual(derived[field], self.manifest[field])
        self.assertEqual(before, {p: filter_index.digest(p) for p in before})

    def test_mismatched_parent_sha_rejected_before_output(self):
        for field in ("corpusSha256", "embedderSha256", "vectorsSha256", "fragmentsSha256"):
            with self.subTest(field=field):
                old = self.manifest[field]
                self.manifest[field] = "0" * 64
                self.save_manifest()
                with self.assertRaisesRegex(ValueError, "SHA mismatch"):
                    self.derive()
                self.assertFalse(self.out.exists())
                self.manifest[field] = old

    def test_nonoverwrite(self):
        self.out.mkdir()
        marker = self.out / "keep.txt"
        marker.write_bytes(b"do not touch")
        with self.assertRaisesRegex(ValueError, "already exists"):
            self.derive()
        self.assertEqual(marker.read_bytes(), b"do not touch")

    def test_document_mismatch_even_with_valid_metadata_sha(self):
        rows = [json.loads(line) for line in self.lines]
        rows[0]["document_id"] = "another-document"
        (self.source / "fragments.jsonl").write_text(
            "".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")
        self.manifest["fragmentsSha256"] = filter_index.digest(self.source / "fragments.jsonl")
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "document mismatch"):
            self.derive()
        self.assertFalse(self.out.exists())

    def test_duplicate_id_with_valid_sha_rejected(self):
        (self.source / "fragments.jsonl").write_bytes(self.lines[0] + b"".join(self.lines[:-1]))
        self.manifest["fragmentsSha256"] = filter_index.digest(self.source / "fragments.jsonl")
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "Duplicate metadata"):
            self.derive()
        self.assertFalse(self.out.exists())

    def test_vector_size_dimension_and_row_count_rejected(self):
        for field, value in (("dimensions", 383), ("rows", len(self.order) + 1)):
            with self.subTest(field=field):
                old = self.manifest[field]
                self.manifest[field] = value
                self.save_manifest()
                with self.assertRaises(ValueError):
                    self.derive()
                self.assertFalse(self.out.exists())
                self.manifest[field] = old
        self.save_manifest()
        with (self.source / "vectors.f32").open("ab") as stream:
            stream.write(b"!")
        with self.assertRaisesRegex(ValueError, "vector size mismatch"):
            self.derive()

    def test_source_local_paths_also_supported(self):
        self.manifest.update(vectors="vectors.f32", fragments="fragments.jsonl")
        self.save_manifest()
        self.assertEqual(self.derive()["rows"], 4)

    def test_no_parent_nested_output_or_insufficient_disk(self):
        with self.assertRaisesRegex(ValueError, "inside source"):
            filter_index.derive(self.db, self.source, self.source / "new", min_free_bytes=0)
        with self.assertRaisesRegex(ValueError, "Insufficient disk"):
            filter_index.derive(self.db, self.source, self.out, min_free_bytes=10**30)
        self.assertFalse(self.out.exists())

    def test_searchable_unicode_letters_and_numbers_preserved(self):
        for body in ('А', 'Қазақша і', 'English', '漢', '0', '١٢', '１２', '²', 'Ⅲ', '½', '-20%', '7.1 - 7.3', ' | 0.0 | '):
            with self.subTest(body=body):
                self.assertTrue(filter_index.body_eligible(body, 'searchable'))
        for body in ('', '\t\n', '\u00a0', '….', '------- <<.', '. )', '😀', '\u200b', '\x00', '\u0301'):
            with self.subTest(body=body):
                self.assertFalse(filter_index.body_eligible(body, 'searchable'))
        with self.assertRaisesRegex(ValueError, 'Unknown body policy'):
            filter_index.body_eligible('body', 'unknown')

    def test_searchable_alignment_and_truthful_counters(self):
        before = {p: filter_index.digest(p) for p in [self.db, *self.source.iterdir()]}
        result = filter_index.derive(self.db, self.source, self.out,
                                     min_free_bytes=0, body_policy='searchable')
        kept = [n for n, i in enumerate(self.order) if i in {3, 5}]
        self.assertEqual(result['rows'], 2)
        self.assertEqual(result['removed_unsearchable_body_rows'], 5)
        self.assertEqual(result['removed_blank_body_rows'], 3)
        self.assertEqual(result['removed_nonblank_unsearchable_body_rows'], 2)
        self.assertEqual((self.out / 'vectors.f32').read_bytes(), b''.join(self.vector_rows[n] for n in kept))
        self.assertEqual((self.out / 'fragments.jsonl').read_bytes(), b''.join(self.lines[n] for n in kept))
        index = json.loads((self.out / 'index.json').read_text())
        self.assertEqual(index['derivation']['kind'], 'canonical-searchable-body-subset-v2')
        self.assertEqual(index['derivation']['removedBlankBodyRows'], 3)
        self.assertEqual(index['derivation']['removedUnsearchableBodyRows'], 5)
        self.assertTrue(index['derivation']['canonicalPunctuationPreservedForClosure'])
        self.assertEqual(before, {p: filter_index.digest(p) for p in before})

    def test_default_nonblank_index_bytes_reproducible(self):
        self.derive()
        explicit = self.root / 'explicit-nonblank'
        filter_index.derive(self.db, self.source, explicit,
                            min_free_bytes=0, body_policy='nonblank')
        for name in ('vectors.f32', 'fragments.jsonl', 'index.json'):
            self.assertEqual((self.out / name).read_bytes(), (explicit / name).read_bytes())
        index = json.loads((self.out / 'index.json').read_text())
        self.assertEqual(index['derivation']['kind'], 'canonical-nonblank-body-subset-v1')
        self.assertNotIn('bodyPolicy', index['derivation'])
        self.assertNotIn('removedUnsearchableBodyRows', index['derivation'])


if __name__ == "__main__":
    unittest.main()
