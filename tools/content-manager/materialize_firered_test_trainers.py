#!/usr/bin/env python3
"""Materialize the FireRed trainers used by the generation-one test map."""

from __future__ import annotations

import copy
import json
from pathlib import Path

from cves.formatter import format_program
from cves.presets import preset_program


ROOT = Path(__file__).resolve().parents[2]
PROJECT = ROOT / "content-projects" / "cobbleventure-main"
CONTENT = PROJECT / "content"
REFERENCE_CATALOG = CONTENT / "catalogs" / "trainer-reference-entries.json"
CLASS_CATALOG = CONTENT / "catalogs" / "trainer-classes.json"
GYM_CATALOG = CONTENT / "catalogs" / "gyms.json"
NPC_DIR = CONTENT / "source" / "generation_1" / "firered"
BATTLE_DIR = CONTENT / "battles" / "generation_1" / "firered"
EVENT_DIR = CONTENT / "events" / "cobbleventure" / "generation_1" / "firered"
BINDING_DIR = CONTENT / "event-bindings" / "cobbleventure" / "generation_1" / "firered"

GYM_TRAINERS = {
    "pewter": [142],
    "cerulean": [150, 234],
    "vermilion": [141, 220, 423],
    "celadon": [132, 133, 160, 265, 266, 267, 402],
    "fuchsia": [288, 289, 292, 293, 294, 295],
    "saffron": [280, 281, 282, 283, 462, 463, 464],
    "cinnabar": [177, 178, 179, 180, 213, 214, 215],
    "viridian": [296, 297, 322, 323, 324, 392, 400, 401],
}
ROUTE_24_25_TRAINERS = [
    92, 93, 94, 95, 110, 122, 123, 125,
    143, 144, 153, 182, 183, 184, 356, 471,
    334,  # 테스트판은 플레이어가 이상해씨를 골랐을 때의 라이벌 팀으로 고정한다.
]
VICTORY_ROAD_TRAINERS = [167, 287, 290, 298, 325, 393, 394, 396, 403, 404, 406, 485]

CLASS_ALIASES = {
    "boss": "villain_boss",
    "burglar": "villain_grunt",
    "channeler": "hex_maniac",
    "cool_couple": "young_couple_female",
    "cooltrainer": "ace_trainer_male",
    "engineer": "scientist",
    "juggler": "psychic",
    "leader": "gym_leader",
    "rival_early": "rival",
    "rival_late": "rival",
    "swimmer_f": "swimmer_female",
    "swimmer_m": "swimmer_male",
    "team_rocket": "villain_grunt",
}

KOREAN_CLASS_NAMES = {
    "beauty": "아가씨", "black_belt": "태권왕", "bug_catcher": "곤충채집소년",
    "burglar": "도둑", "camper": "캠프보이", "channeler": "주술사",
    "cool_couple": "엘리트 커플", "cooltrainer": "엘리트 트레이너",
    "engineer": "기술자", "gentleman": "신사", "hiker": "등산가",
    "juggler": "저글러", "lass": "짧은치마", "picnicker": "피크닉걸",
    "pokemaniac": "포켓몬 매니아", "psychic": "초능력자", "rival_early": "라이벌",
    "sailor": "선원", "super_nerd": "이과계의 남자", "swimmer_m": "수영팬",
    "tamer": "포켓몬 조련사", "team_rocket": "로켓단 조무래기",
    "youngster": "반바지 꼬마",
}

KOREAN_PERSON_NAMES = {
    "alexa": "알렉사", "ali": "알리", "amanda": "아만다", "atsushi": "아츠시",
    "avery": "에이버리", "baily": "베일리", "bridget": "브리짓", "cale": "케일",
    "cameron": "캐머런", "caroline": "캐럴라인", "chad": "채드", "colby": "콜비",
    "cole": "콜", "daisuke": "다이스케", "dan": "댄", "dawson": "도슨",
    "derek": "데릭", "diana": "다이애나", "dusty": "더스티", "dwayne": "드웨인",
    "edgar": "에드거", "erik": "에릭", "ethan": "이선", "flint": "플린트",
    "franklin": "프랭클린", "george": "조지", "gregory": "그레고리",
    "haley": "헤일리", "jason": "제이슨", "joey": "조이", "johan": "요한",
    "kay": "케이", "kayden": "케이든", "kelsey": "켈시", "kirk": "커크",
    "kiyo": "키요", "liam": "리암", "lisa": "리사", "lori": "로리",
    "luis": "루이스", "mary": "메리", "naomi": "나오미", "nate": "네이트",
    "nelson": "넬슨", "nob": "노브", "phil": "필", "preston": "프레스턴",
    "quinn": "퀸", "ramon": "라몬", "reli": "렐리", "rolando": "롤란도",
    "samuel": "새뮤얼", "shane": "셰인", "shawn": "숀", "stacy": "스테이시",
    "takashi": "타카시", "tamia": "타미아", "tasha": "타샤", "timmy": "티미",
    "tina": "티나", "tucker": "터커", "tyron": "타이론", "vincent": "빈센트",
    "warren": "워런", "wayne": "웨인", "yuji": "유지", "zac": "잭",
}

# FireRed 원본의 전투 전, 패배, 전투 후 문구를 테스트판 문체로 번역한 값이다.
FIRERED_DIALOGUE_KO = {
    92: ("3번 선수 나간다! 만만하게 보지 마!", "으악! 납작하게 졌어!", "최선을 다했으니 후회 없어!"),
    93: ("이 근처 트레이너들은 여기서 연습해.", "제법인데.", "강한 포켓몬에게도 약점은 있어. 여러 타입을 함께 키우는 게 좋아."),
    94: ("아빠가 갈색시티의 상트앙느호 파티에 데려가 줬어.", "화난 거 아니야!", "상트앙느호에서 세계 각지의 트레이너를 봤어."),
    95: ("느낌이 왔어… 너와 꼭 싸워야 할 것 같았어!", "질 것 같더라!", "포켓몬이 혼란에 빠지면 교체하는 것도 좋은 전술이야."),
    110: ("여기는 금구슬다리! 우리 다섯을 이기면 멋진 상품을 받지. 해낼 수 있겠어?", "우와! 훌륭해!", "최선을 다했으니 후회 없어!"),
    122: ("나는 4번! 슬슬 지치지 않았어?", "나도 져 버렸네!", "최선을 다했으니 후회 없어!"),
    123: ("내가 2번이야! 이제부터 진짜 승부라고!", "내가 어떻게 진 거지?", "최선을 다했으니 후회 없어!"),
    125: ("내 친구는 귀여운 포켓몬이 많아서 정말 부러워!", "이제 별로 안 부러워!", "달맞이산에서 왔어? 삐삐 한 마리만 주면 안 돼?"),
    132: ("이 체육관에는 진정한 숙녀만 들어올 수 있어!", "너무 거칠잖아!", "흥! 민화가 널 혼내 줄 거야!"),
    133: ("여기서는 벌레나 불꽃타입 포켓몬을 좋아하지 않아!", "뭐야, 너!", "관장 민화는 조용하지만 이 근처에서는 아주 유명해."),
    141: ("여긴 꼬마가 올 곳이 아니야! 실력이 좋아도 마찬가지지!", "이런! 놀랐잖아!", "마티스 관장님이 직접 이중 잠금장치를 설치했어. 첫 스위치 바로 옆을 찾아봐."),
    142: ("거기서 멈춰! 웅이를 만나려면 아직 1만 광년은 멀었어!", "이런! 광년은 시간이 아니라 거리였지!", "제법 뜨겁군. 그래도 웅이만큼은 아니야!"),
    143: ("풀숲에서 네 활약을 봤어!", "역시 안 되는군!", "다리 위 사람들이 무서워서 숨어 있었어."),
    144: ("좋아! 내가 5번이다! 짓밟아 주지!", "우와! 너무 강해!", "최선을 다했으니 후회 없어!"),
    150: ("뭐야, 너? 이슬이가 나설 필요도 없이 내가 충분해!", "완전히 압도당했어!", "다른 트레이너와 싸워 봐야 자신의 실력을 알 수 있어."),
    153: ("안녕! 내 남자친구는 정말 멋져!", "내 컨디션이 별로였나 봐…", "내 남자친구도 너만큼 강했으면 좋겠다."),
    160: ("…아까 여기 안을 엿보던 사람 아니야?", "정말 눈이 번쩍 뜨이는 실력이네!", "민화를 보고 있었구나… 나는 아니고…"),
    167: ("여기를 통과하면 사천왕을 만날 수 있어.", "말도 안 돼!", "그래도 포켓몬 지식만큼은 내가 더 뛰어나!"),
    177: ("포켓몬의 불꽃 숨결이 얼마나 뜨거운지 알아?", "앗 뜨거! 너무 뜨거워!", "불꽃, 정확히는 연소란… 공기 중 산소가… 어쩌고저쩌고…"),
    178: ("포켓몬을 철저히 연구했어. 네가 이길 리 없어!", "으아! 공부가 부족했어!", "내 이론은 네가 이해하기엔 너무 복잡해."),
    179: ("강연이 왜 트레이너가 됐는지 알고 있어.", "아야!", "산에서 길을 잃은 강연을 불꽃새 포켓몬의 빛이 안전하게 인도했대."),
    180: ("불은 H2O에 약하지.", "이런! 불이 꺼졌어!", "물은 불에 강하지만 불은 얼음을 녹여. 그래서 불꽃타입은 얼음타입에 강해."),
    182: ("달맞이산에서 막 내려왔지만 아직 힘이 넘친다고!", "정말 열심히 하는구나!", "젠장! 동굴에서 주뱃에게 물렸어."),
    183: ("곶에 사는 포켓몬 매니아의 수집품을 보러 가는 길이야.", "제대로 당했군!", "그 포켓몬 매니아는 이름값을 해. 희귀한 포켓몬도 많이 모았대."),
    184: ("이수재를 만나러 간다고? 그 전에 나와 승부다!", "보통이 아니군.", "아래쪽 길은 블루시티로 가는 지름길이야."),
    213: ("예전엔 도둑이었지만 지금은 정직한 트레이너야.", "항복이다!", "아직도 남의 포켓몬을 훔치고 싶은 충동이 들 때가 있어."),
    214: ("난 그냥 불꽃타입 포켓몬이 좋아.", "너무 뜨거워서 못 다루겠어!", "도둑 포켓몬이 있다면 꼭 써 보고 싶군!"),
    215: ("여러 체육관에 가 봤지만 여기가 내 방식에 가장 잘 맞아.", "으악! 너무 뜨거워!", "포니타와 나인테일은 인기 있는 불꽃 포켓몬이지."),
    220: ("몸은 약해도 전기에는 자신 있어! 그래서 이 체육관에 들어왔지.", "감전됐다!", "말해 주지! 마티스 관장님은 문 스위치를 무언가 안에 숨겼어."),
    234: ("첨벙! 내가 첫 상대다! 시작하자!", "그럴 리가!", "이슬이는 계속 성장하는 트레이너야. 너 같은 상대에게 지지 않아!"),
    265: ("어서 와. 마침 지루하던 참이야.", "내 화장이!", "풀타입은 물과 바위, 땅타입에 유리해."),
    266: ("내 포켓몬 좀 봐! 키우기 쉬운 풀타입이 정말 좋아.", "안 돼!", "우리 체육관은 꽃꽂이에도 쓰려고 풀타입 포켓몬만 키워."),
    267: ("만나서 반가워. 내 취미는 포켓몬 육성이야.", "어머! 훌륭해!", "곧 소개팅이 있어서 예의 바르게 행동하는 법을 배우는 중이야."),
    280: ("보이지 않는 우리의 힘이 무섭지 않아?", "이건 예견하지 못했어!", "에스퍼 포켓몬이 두려워하는 건 고스트와 벌레뿐이야!"),
    281: ("힘만으로 포켓몬 세계에서 이길 수 없다는 걸 알고 있겠지?", "믿을 수 없어!", "초련은 바로 옆의 격투대왕을 단숨에 쓰러뜨렸어."),
    282: ("초련은 어리지만 뛰어난 관장이야. 쉽게 만나지는 못할걸!", "으윽! 완전히 졌어!", "예전 노랑시티에는 체육관이 둘이었지만 격투도장이 우리에게 져서 자격을 잃었지."),
    283: ("노랑체육관은 초능력 수련으로 유명해. 초련을 만나고 싶다는 생각이 다 보여!", "으아악!", "그래! 텔레파시로 네 마음을 읽었어!"),
    287: ("사천왕에게 도전하러 가는 모양이군?", "당했군!", "네 라이벌도 이곳을 지나갔어."),
    288: ("예전엔 마술사였지만 닌자가 되고 싶어 이 체육관에 들어왔지.", "이젠 끝장이야!", "져도 닌자 스승 독수의 가르침에 따라 계속 수련하겠어."),
    289: ("독수 관장님은 대대로 닌자인 가문 출신이지. 넌 무엇의 후예냐?", "생각보다 훨씬 뛰어나군!", "빛이 있는 곳에는 그림자가 있다! 넌 어느 쪽을 택하지?"),
    290: ("챔피언로드가 너무 힘들지 않아?", "잘했다!", "많은 트레이너가 여기서 도전을 포기하고 돌아가."),
    292: ("포켓몬은 힘만으로 싸우는 게 아니야. 전략이 완력을 이긴다는 걸 보여 주지!", "뭐라고? 대단하군!", "힘과 지혜를 섞는군. 어린 트레이너치고 훌륭한 전략이야!"),
    293: ("내 특수 기술을 이겨 낼 수 있는지 보자!", "완전히 속았군!", "독과 잠처럼 승부 뒤에도 남는 기술이 좋아!"),
    294: ("거기 멈춰! 연분홍체육관의 유명한 보이지 않는 벽 때문에 답답하지?", "오! 알아냈구나!", "힌트를 주지. 보이지 않는 벽의 틈을 자세히 살펴봐!"),
    295: ("나도 독수 스승님께 닌자의 길을 배운다! 닌자는 오래전부터 동물을 다뤘지!", "아우우!", "아직 배울 것이 많군."),
    296: ("포켓몬과 나는 함께 멋진 음악을 만들지!", "완벽한 조화로군!", "우리 체육관 관장의 정체를 알고 있나?"),
    297: ("내 채찍 소리에 네 포켓몬은 벌벌 떨 거다!", "아야! 채찍에 맞은 기분이군!", "잠깐! 방심했을 뿐이야!"),
    298: ("덤벼! 호되게 다뤄 주지!", "내가 당해 버렸군!", "챔피언로드에 설 자격이 있구나…"),
    322: ("크아아! 분노를 끌어올리고 있다!", "으아악!", "난 아직 멀었군!"),
    323: ("가라테야말로 최고의 무술이다!", "이야앗!", "내 포켓몬도 나만큼 가라테를 잘했다면…"),
    324: ("내가 가라테의 왕이다! 네 운명은 내 손에 달렸다!", "으아앗!", "포켓몬리그라고? 건방떨지 마!"),
    325: ("여기가 챔피언로드다. 트레이너에게 주어지는 마지막 시험이지!", "에취!", "막히면 바위를 이리저리 밀어 봐."),
    334: ("어이! 잘 지냈냐? 네 실력이 늘었는지 확인하러 왔어!", "흥! 너무 힘을 뺐나?", "이수재에게 가는 길이라면 아직 멀었어. 냄새나 맡고 있어!"),
    356: ("우리 로켓단에 들어오지 않겠어? 싫다고? 그렇다면 억지로라도 설득해 주지!", "으악! 제법이군!", "네 실력이라면 로켓단의 간부도 될 수 있을 텐데. 기회를 놓치지 마!"),
    392: ("상록체육관은 오래 닫혀 있었지만 관장님이 돌아오셨다!", "내가 졌다고?", "관장님을 쓰러뜨려야만 포켓몬리그로 갈 수 있어!"),
    393: ("천재 꼬마가 나타났다는 소문을 들었지.", "소문이 사실이었군!", "로켓단의 비주기를 쓰러뜨린 게 너였나?"),
    394: ("선택받은 자만 이곳을 통과할 수 있다!", "믿을 수 없어!", "여기 있는 트레이너는 모두 포켓몬리그를 향하고 있어. 방심하지 마."),
    396: ("실력이 좋아 보이는군. 얼마나 좋은지 직접 확인해 보지!", "이길 기회가 있었는데…", "인정하지. 네가 나보다 강하다!"),
    400: ("흥! 이제 슬슬 기운이 다 빠졌겠지!", "내 기운이 먼저 빠졌군!", "우리 관장님을 상대하려면 힘이 더 필요할 거다."),
    401: ("진정한 실력자는 멋지게 이기는 법이지.", "내가 중심을 잃었군!", "이렇게 지면 관장님께 혼나겠어…"),
    402: ("무지개체육관에 온 걸 환영해! 여기의 상냥한 숙녀들을 얕보지 마.", "어머! 져 버렸어!", "가장 강한 포켓몬은 데려오지 않았어. 다음에는 다를걸!"),
    403: ("네 실력이 얼마나 대단한지 보여 주지. 대단하지 않다는 걸!", "정말 화나!", "오히려 네가 내 실력이 어느 정도인지 보여 줬네…"),
    404: ("트레이너는 더 강한 상대를 찾아 살아가는 법이지.", "아! 정말 강하군!", "힘든 승부를 거치며 더욱 강해지는 거야."),
    406: ("네가 내 상대가 될 만큼 강한지 궁금하네.", "져 버렸어…", "누구에게도, 특히 어린아이에게는 지고 싶지 않았는데…"),
    423: ("군대에 있을 때 마티스는 엄격한 상관이었지. 아주 혹독했어.", "그만! 정말 훌륭하군!", "문을 여는 건 쉽지 않아. 마티스는 군대에서도 늘 신중하기로 유명했지."),
    462: ("초련은 나보다 훨씬 어리지만 존경할 만한 실력자야.", "아직 부족했군!", "대등한 승부에서는 의지가 강한 쪽이 이겨. 초련을 이기려면 정신을 집중해."),
    463: ("포켓몬은 트레이너를 닮는대. 그렇다면 네 포켓몬은 강하겠구나!", "역시 그랬군!", "아직 배울 게 많아… 사이코키네시스를 익혀 포켓몬에게 가르쳐야 해…"),
    464: ("너와 나, 그리고 우리 포켓몬들이 승부한다!", "결국 져 버렸군!", "이런 일이 일어날 줄 알고 있었어."),
    471: ("나는 멋진 남자야. 여자친구도 있다고!", "아, 이런…", "뭐, 여자친구가 위로해 주겠지."),
    485: ("레이: 우리 둘이 함께라면 위대한 트레이너가 될 운명이야!", "레이: 말도 안 돼! 이럴 수가!", "레이: 우리를 이겼군. 위대함은 아직 멀었나 봐…"),
}


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding="utf-8", newline="\n")


def class_for(entry: dict, classes: dict[str, dict]) -> dict:
    trainer_type = entry["trainer_type"]
    class_id = CLASS_ALIASES.get(trainer_type, trainer_type)
    if trainer_type == "cooltrainer" and entry["name"].split()[-1] in {
        "Alexa", "Caroline", "Julie", "Mary", "Michelle", "Naomi", "Shannon",
    }:
        class_id = "ace_trainer_female"
    if class_id not in classes:
        class_id = "custom"
    return classes[class_id]


def korean_trainer_name(entry: dict) -> str:
    trainer_type = entry["trainer_type"]
    class_name = KOREAN_CLASS_NAMES[trainer_type]
    if trainer_type == "team_rocket":
        return class_name
    if trainer_type == "cool_couple":
        return f"{class_name} 레이와 타이라"
    if trainer_type == "rival_early":
        return f"{class_name} 테리"
    person_key = entry["id"].rsplit("_", 1)[-1]
    return f"{class_name} {KOREAN_PERSON_NAMES[person_key]}"


def materialize(entry: dict, classes: dict[str, dict]) -> tuple[str, str]:
    slug = entry["id"]
    npc_id = f"cobbleventure:npc/{slug}"
    battle_id = f"cobbleventure:battle/{slug}"
    trainer_class = class_for(entry, classes)
    level = entry["max_level"]
    localized_name = korean_trainer_name(entry)
    first_text, defeat_text, post_text = FIRERED_DIALOGUE_KO[entry["entry_number"]]

    battle = {
        "$schema": "../../../schemas/battle-preset.schema.json",
        "schema_version": 1,
        "id": battle_id,
        "enabled": True,
        "name": {"ko_kr": f"{localized_name} 배틀", "en_us": f"{entry['name']} Battle"},
        "battle": copy.deepcopy(entry["battle"]),
    }
    battle["battle"]["trainer_id"] = f"cobbleventure:trainer/{slug}"

    npc = {
        "$schema": "../../../schemas/npc-event-script.schema.json",
        "schema_version": 4,
        "id": npc_id,
        "enabled": True,
        "name": {"ko_kr": localized_name, "en_us": entry["name"]},
        "description": {"ko_kr": "파이어레드 테스트 배치용 트레이너입니다."},
        "tags": ["trainer", "generation_1", "kanto", "firered", entry["trainer_type"]],
        "placement_profile": {
            "classification": "trainer",
            "expected_level": level,
            "preferred_biomes": [],
            "automatic_town_placement": False,
            "automatic_route_placement": True,
        },
        "npc": {
            "display_name": {"ko_kr": localized_name, "en_us": entry["name"]},
            "role": "default",
            "trainer_class": trainer_class["id"],
            "appearance": copy.deepcopy(trainer_class["default_appearance"]),
            "behavior": {
                "movement": "stationary",
                "look_at_player": True,
                "invulnerable": True,
                "collision": True,
            },
        },
        "event_design": {
            "mode": "preset",
            "preset": {
                "type": "battle",
                "initial_trigger": {"type": "interact", "range": 4},
                "first_text": {"ko_kr": first_text},
                "battle": battle_id,
                "after_victory_trigger": {"type": "interact", "range": 4},
                "win_text": {"ko_kr": f"{defeat_text} {post_text}"},
                "loss_text": {"ko_kr": "이번 승부는 내가 이겼어. 다시 준비해서 도전해!"},
                "victory_state_key": f"cobbleventure:flag/trainer/{slug}/defeated",
            },
        },
        "event_runtime": {
            "engine": "cves_v5",
            "authoring": "preset",
            "script_id": f"cobbleventure:event_script/generation_1/firered/{slug}",
        },
    }

    write_json(BATTLE_DIR / f"{slug}.json", battle)
    write_json(NPC_DIR / f"{slug}.json", npc)
    write_text(EVENT_DIR / f"{slug}.cves", format_program(preset_program(npc)))
    write_json(BINDING_DIR / f"{slug}.json", {
        "schema_version": 1,
        "script_id": npc["event_runtime"]["script_id"],
    })
    return npc_id, battle_id


def sync_gym_staff(references: dict[int, dict]) -> None:
    catalog = load_json(GYM_CATALOG)
    for gym in catalog["gyms"]:
        slug = gym["id"].rsplit("/", 1)[-1]
        numbers = GYM_TRAINERS.get(slug)
        if numbers is None:
            continue
        gym["staff"]["trainers"] = [
            {
                "id": f"trainer_{index}",
                "trainer_id": f"cobbleventure:npc/{references[number]['id']}",
                "anchor": f"trainer_{index}",
            }
            for index, number in enumerate(numbers, start=1)
        ]
    write_json(GYM_CATALOG, catalog)


def main() -> None:
    references = {
        entry["entry_number"]: entry
        for entry in load_json(REFERENCE_CATALOG)["entries"]
        if entry["source"] == "firered"
    }
    classes = {
        entry["id"].rsplit("/", 1)[-1]: entry
        for entry in load_json(CLASS_CATALOG)["classes"]
    }
    numbers = sorted({
        *ROUTE_24_25_TRAINERS,
        *VICTORY_ROAD_TRAINERS,
        *(number for values in GYM_TRAINERS.values() for number in values),
    })
    for number in numbers:
        materialize(references[number], classes)
    sync_gym_staff(references)
    print(f"materialized {len(numbers)} FireRed test trainers")


if __name__ == "__main__":
    main()
