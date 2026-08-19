from __future__ import annotations

import copy
import json
import sys
import unittest
from decimal import Decimal
from pathlib import Path


ROOT = Path(__file__).parents[3]
CONTENT_MANAGER = ROOT / "tools" / "content-manager"
PROJECT_ROOT = ROOT / "content-projects" / "cobbleventure-main"
sys.path.insert(0, str(CONTENT_MANAGER))

from cves import (  # noqa: E402
    battle_event_contract_from_cves,
    compare_battle_event_migration,
    compare_item_reward_migration,
    compare_gym_leader_migration,
    compare_simple_dialogue_migration,
    compare_starter_event_migration,
    item_reward_contract_from_cves,
    gym_leader_contract_from_cves,
    simple_dialogue_contract_from_cves,
    starter_event_contract_from_cves,
    parse,
)


V4_SOURCE = PROJECT_ROOT / "content/source/samples/sample_potion_giver.json"
V5_SOURCE = PROJECT_ROOT / "content/events/cobbleventure/samples/sample_potion_giver.cves"
V4_BATTLE_SOURCE = PROJECT_ROOT / "content/source/examples/ai_test.json"
V5_BATTLE_SOURCE = PROJECT_ROOT / "content/events/cobbleventure/examples/ai_test.cves"
BATTLE_PRESET = PROJECT_ROOT / "content/battles/examples/ai_test.json"
V4_STARTER_SOURCE = PROJECT_ROOT / "content/source/story/professor_oak.json"
V5_STARTER_SOURCE = PROJECT_ROOT / "content/events/cobbleventure/story/professor_oak.cves"
V4_GATEKEEPER_SOURCE = PROJECT_ROOT / "content/source/story/starter_town_gatekeeper_minho.json"
V5_GATEKEEPER_SOURCE = PROJECT_ROOT / "content/events/cobbleventure/story/starter_town_gatekeeper_minho.cves"
LEAGUE_SOURCE = PROJECT_ROOT / "content/catalogs/league-progression.json"
V5_BROCK_SOURCE = PROJECT_ROOT / "content/events/cobbleventure/gym_leaders/brock.cves"


class CvesItemRewardMigrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.legacy = json.loads(V4_SOURCE.read_text(encoding="utf-8"))
        cls.program = parse(V5_SOURCE.read_text(encoding="utf-8"), str(V5_SOURCE))

    def test_potion_giver_preserves_v4_dialogue_condition_and_reward_contract(self) -> None:
        contract = item_reward_contract_from_cves(self.program)

        self.assertEqual((), compare_item_reward_migration(self.legacy, self.program))
        self.assertEqual("cobbleventure:flag/sample/sample_potion_giver/claimed", contract.state_key)
        self.assertEqual("cobblemon:potion", contract.item)
        self.assertEqual(3, contract.count)
        self.assertTrue(contract.notify)

    def test_comparison_reports_the_first_changed_contract_field(self) -> None:
        changed = copy.deepcopy(self.legacy)
        changed["event_design"]["preset"]["item_count"] = 2

        differences = compare_item_reward_migration(changed, self.program)

        self.assertEqual(1, len(differences))
        self.assertIn("count", differences[0])
        self.assertIn("V4=2", differences[0])
        self.assertIn("V5=3", differences[0])

    def test_claim_flag_requires_a_guarded_complete_item_grant(self) -> None:
        unsafe = V5_SOURCE.read_text(encoding="utf-8").replace(
            '    if reward.remaining_count > 0 {\n'
            '      narrate "가방에 빈 공간이 없어 보상을 모두 받을 수 없습니다."\n'
            '      stop\n'
            '    }\n',
            "",
        )

        with self.assertRaisesRegex(ValueError, "remaining_count"):
            item_reward_contract_from_cves(parse(unsafe, "unsafe-item-reward.cves"))


class CvesBattleEventMigrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.legacy = json.loads(V4_BATTLE_SOURCE.read_text(encoding="utf-8"))
        cls.program = parse(V5_BATTLE_SOURCE.read_text(encoding="utf-8"), str(V5_BATTLE_SOURCE))
        cls.battle = json.loads(BATTLE_PRESET.read_text(encoding="utf-8"))

    def test_ai_test_preserves_v4_choices_battle_rewards_and_outcomes(self) -> None:
        contract = battle_event_contract_from_cves(self.program, self.battle)

        self.assertEqual((), compare_battle_event_migration(self.legacy, self.program, self.battle))
        self.assertEqual("cobbleventure:battle/ai_test", contract.battle)
        self.assertEqual("cobbleventure:trainer/ai_test_rewards", contract.loot)
        self.assertEqual(
            (("ko_kr", "승부한다"), ("ko_kr", "다음에")),
            contract.default_choices,
        )
        self.assertEqual((("ko_kr", "승부한다"),), contract.prepared_choices)
        self.assertIn(("held_item_multiplier", 2), contract.money_reward)

    def test_comparison_detects_changed_battle_money_metadata(self) -> None:
        changed = copy.deepcopy(self.battle)
        changed["battle"]["money_reward"]["per_level"] = 30

        differences = compare_battle_event_migration(self.legacy, self.program, changed)

        self.assertEqual(1, len(differences))
        self.assertIn("money_reward", differences[0])

    def test_victory_state_requires_guarded_complete_loot_grant(self) -> None:
        unsafe = V5_BATTLE_SOURCE.read_text(encoding="utf-8").replace(
            '            if reward.remaining_count > 0 {\n'
            '              narrate "가방에 빈 공간이 없어 승리 보상을 모두 받을 수 없습니다."\n'
            '              stop\n'
            '            }\n',
            "",
            1,
        )

        with self.assertRaisesRegex(ValueError, "remaining_count"):
            battle_event_contract_from_cves(parse(unsafe, "unsafe-battle.cves"), self.battle)


class CvesStarterEventMigrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.legacy = json.loads(V4_STARTER_SOURCE.read_text(encoding="utf-8"))
        cls.source = V5_STARTER_SOURCE.read_text(encoding="utf-8")
        cls.program = parse(cls.source, str(V5_STARTER_SOURCE))

    def test_professor_oak_preserves_v4_story_and_guarded_pokedex_reward(self) -> None:
        contract = starter_event_contract_from_cves(self.program)

        self.assertEqual((), compare_starter_event_migration(self.legacy, self.program))
        self.assertEqual("cobbleventure:flag/story/starter_received", contract.starter_state_key)
        self.assertEqual("cobbleventure:flag/story/pokedex_received", contract.pokedex_state_key)
        self.assertEqual("cobblemon:pokedex_red", contract.pokedex_item)
        self.assertEqual(1, contract.pokedex_count)

    def test_initial_starter_praise_requires_localized_result_name_and_josa(self) -> None:
        unsafe = self.source.replace("${starter.name|josa:을/를}", "좋은 포켓몬을")

        with self.assertRaisesRegex(ValueError, "결과 name과 조사"):
            starter_event_contract_from_cves(parse(unsafe, "unsafe-starter.cves"))

    def test_both_pokedex_paths_require_guard_before_completion_state(self) -> None:
        unsafe = self.source.replace(
            '    if pokedex.remaining_count > 0 {\n'
            '      narrate "가방에 빈 공간이 없어 포켓몬 도감을 받을 수 없습니다."\n'
            '      stop\n'
            '    }\n',
            "",
            1,
        )

        with self.assertRaisesRegex(ValueError, "remaining_count"):
            starter_event_contract_from_cves(parse(unsafe, "unsafe-pokedex.cves"))


class CvesSimpleDialogueMigrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.legacy = json.loads(V4_GATEKEEPER_SOURCE.read_text(encoding="utf-8"))
        cls.program = parse(V5_GATEKEEPER_SOURCE.read_text(encoding="utf-8"), str(V5_GATEKEEPER_SOURCE))

    def test_gatekeeper_preserves_v4_trigger_and_dialogue(self) -> None:
        contract = simple_dialogue_contract_from_cves(self.program)

        self.assertEqual((), compare_simple_dialogue_migration(self.legacy, self.program))
        self.assertEqual(Decimal("4"), contract.trigger_range)
        self.assertIn(("ko_kr", "이 앞부터는 야생 포켓몬이 나타나. 파트너가 없다면 오박사님께 먼저 다녀오렴."), contract.text)


class CvesGymLeaderMigrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        league = json.loads(LEAGUE_SOURCE.read_text(encoding="utf-8"))
        cls.entry = next(
            value for value in league["entries"]
            if value.get("encounter", {}).get("battle_id")
                == "cobbleventure:battle/gym_leader/brock"
        )
        cls.program = parse(V5_BROCK_SOURCE.read_text(encoding="utf-8"), str(V5_BROCK_SOURCE))

    def test_brock_preserves_league_battle_dialogue_badge_and_next_cap(self) -> None:
        contract = gym_leader_contract_from_cves(self.program)

        self.assertEqual((), compare_gym_leader_migration(self.entry, self.program, 21))
        self.assertEqual("cobbleventure:battle/gym_leader/brock", contract.battle)
        self.assertEqual("cobbleventure:badge/kanto/boulder", contract.badge)
        self.assertEqual(21, contract.post_victory_level_cap)

    def test_comparison_rejects_a_stale_post_victory_level_cap(self) -> None:
        differences = compare_gym_leader_migration(self.entry, self.program, 24)

        self.assertEqual(1, len(differences))
        self.assertIn("post_victory_level_cap", differences[0])

    def test_every_kanto_gym_leader_matches_the_league_progression_contract(self) -> None:
        league = json.loads(LEAGUE_SOURCE.read_text(encoding="utf-8"))
        entries = sorted(
            (value for value in league["entries"] if value.get("role") == "gym_leader"),
            key=lambda value: value["order"],
        )
        self.assertEqual(8, len(entries))
        for index, entry in enumerate(entries):
            slug = entry["encounter"]["battle_id"].rsplit("/", 1)[-1]
            cap = entries[index + 1]["level_cap"] if index + 1 < len(entries) else 100
            source = PROJECT_ROOT / f"content/events/cobbleventure/gym_leaders/{slug}.cves"
            program = parse(source.read_text(encoding="utf-8"), str(source))
            with self.subTest(slug=slug):
                self.assertEqual((), compare_gym_leader_migration(entry, program, cap))


if __name__ == "__main__":
    unittest.main()
