from __future__ import annotations

import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "structure_builder.py"
REPOSITORY_ROOT = MODULE_PATH.parents[2]
SPEC = importlib.util.spec_from_file_location("structure_builder", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
structure_builder = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = structure_builder
SPEC.loader.exec_module(structure_builder)


class StructureBuilderTests(unittest.TestCase):
    def test_generate_packages_every_authored_nbt_without_recoloring(self) -> None:
        catalog_path = structure_builder.generate(REPOSITORY_ROOT)
        catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
        sources = sorted((REPOSITORY_ROOT / "content/structures").rglob("*.nbt"))

        self.assertEqual(len(sources), len(catalog["entries"]))
        self.assertEqual(28, len(catalog["entries"]))
        for entry in catalog["entries"]:
            source = REPOSITORY_ROOT / entry["source"]
            resource_path = entry["structure"].split(":", 1)[1]
            packaged = (
                REPOSITORY_ROOT / structure_builder.GENERATED_RESOURCES
                / "data/cobbleventure_builder/structure" / f"{resource_path}.nbt"
            )
            self.assertEqual(source.read_bytes(), packaged.read_bytes())

    def test_import_rejects_incomplete_export_before_overwriting(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            world = Path(directory)
            export = world / "generated/cobbleventure_builder/structures/export/houses"
            export.mkdir(parents=True)
            source = REPOSITORY_ROOT / "content/structures/houses/one_story_flat.nbt"
            shutil.copy2(source, export / source.name)

            with self.assertRaisesRegex(
                structure_builder.StructureBuilderError, "누락된 구조물"
            ):
                structure_builder.import_exports(REPOSITORY_ROOT, world)


if __name__ == "__main__":
    unittest.main()
