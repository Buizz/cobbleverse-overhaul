import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools/content-manager"))
sys.path.insert(0, str(ROOT / "tools/mod-builder"))
import build_data_mod
import generate_easy_npc_presets as generator
from cves.project import compile_project

PROJECT = ROOT / "content-projects/cobbleventure-main"
OWNER = "firered_cool_couple_ray_tyra"
PARTNER = "firered_cool_couple_ray"


class TrainerDoublePairTests(unittest.TestCase):
    def test_pair_metadata_reaches_placement_and_partner_is_not_randomly_placed(self):
        profiles = {p["npc"]: p for p in build_data_mod._npc_placement_profiles(ROOT)}
        owner = profiles[f"cobbleventure:npc/{OWNER}"]
        partner = profiles[f"cobbleventure:npc/{PARTNER}"]
        self.assertEqual(owner["runtime"]["double_battle"]["partner"], partner["npc"])
        self.assertFalse(partner["automatic_route_placement"])
        self.assertFalse(partner["automatic_town_placement"])
        self.assertNotEqual(owner["runtime"]["appearance"], partner["runtime"]["appearance"])

    def test_both_representations_share_the_owners_event_and_victory_flag(self):
        documents = []
        for slug in (OWNER, PARTNER):
            document = json.loads((PROJECT / f"content/source/generation_1/firered/{slug}.json").read_text(encoding="utf-8"))
            document["_cves_binding_tag"] = f"cves_binding/cobbleventure/generation_1/firered/{slug}"
            documents.append(document)
        battle = json.loads((PROJECT / f"content/battles/generation_1/firered/{OWNER}.json").read_text(encoding="utf-8"))
        expanded = generator.paired_encounter_documents(documents, {battle["id"]: battle})
        self.assertEqual(len(expanded), 2)
        self.assertEqual(battle["battle"]["battle_type"], "doubles")
        self.assertEqual(expanded[0]["event_runtime"]["script_id"], expanded[1]["event_runtime"]["script_id"])
        self.assertNotEqual(expanded[0]["_cves_binding_tag"], expanded[1]["_cves_binding_tag"])
        pair = documents[0]["npc"]["double_battle"]
        self.assertEqual(pair["shared_clear_key"], documents[0]["event_design"]["preset"]["victory_state_key"])
        build = compile_project(PROJECT)
        bindings = [a.document for a in build.bindings if a.relative_path.stem in (OWNER, PARTNER)]
        self.assertEqual(len(bindings), 2)
        self.assertEqual(bindings[0]["script_id"], bindings[1]["script_id"])


if __name__ == "__main__":
    unittest.main()
