import errno
import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SPEC = importlib.util.spec_from_file_location(
    "generate_easy_npc_presets",
    Path(__file__).parents[1] / "generate_easy_npc_presets.py",
)
generator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(generator)


class EasyNpcPresetWriteTests(unittest.TestCase):
    def test_creates_utf8_preset_with_lf_newlines(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "npc.npc.snbt"
            generator.write_preset(path, '{Name:"트레이너"}\n')
            self.assertEqual(path.read_bytes(), '{Name:"트레이너"}\n'.encode("utf-8"))
            self.assertEqual(list(path.parent.iterdir()), [path])

    def test_unchanged_preset_is_not_opened_for_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "npc.npc.snbt"
            path.write_bytes(b"unchanged\n")
            before = path.stat().st_mtime_ns
            with mock.patch.object(generator.tempfile, "mkstemp") as create_temp:
                with mock.patch.object(Path, "write_text", side_effect=AssertionError("direct write")):
                    generator.write_preset(path, "unchanged\n")
            create_temp.assert_not_called()
            self.assertEqual(path.stat().st_mtime_ns, before)

    def test_changed_preset_does_not_truncate_open_the_destination(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "firered_channeler_amanda.npc.snbt"
            path.write_bytes(b"old\n")
            original_open = Path.open

            def reject_destination_write(candidate, mode="r", *args, **kwargs):
                if candidate == path and mode == "w":
                    raise OSError(errno.EINVAL, "Invalid argument", str(path))
                return original_open(candidate, mode, *args, **kwargs)

            with mock.patch.object(Path, "open", reject_destination_write):
                generator.write_preset(path, "new\n")
            self.assertEqual(path.read_bytes(), b"new\n")
            self.assertEqual(list(path.parent.iterdir()), [path])

    def test_failed_replacement_preserves_previous_preset_and_reports_error(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "npc.npc.snbt"
            path.write_bytes(b"old\n")
            error = PermissionError(errno.EACCES, "locked", str(path))
            with mock.patch.object(generator.os, "replace", side_effect=error):
                with self.assertRaises(PermissionError):
                    generator.write_preset(path, "new\n")
            self.assertEqual(path.read_bytes(), b"old\n")
            self.assertEqual(list(path.parent.iterdir()), [path])


if __name__ == "__main__":
    unittest.main()
