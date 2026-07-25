function normalizedId(value) {
  return String(value ?? "")
    .toLowerCase()
    .replaceAll("♀", "f")
    .replaceAll("♂", "m")
    .replace(/[^a-z0-9]/g, "");
}

const FORM_LABELS = {
  alola: "알로라의 모습",
  galar: "가라르의 모습",
  hisui: "히스이의 모습",
  paldea: "팔데아의 모습",
  paldeacombat: "팔데아의 모습·컴뱃종",
  paldeablaze: "팔데아의 모습·블레이즈종",
  paldeaaqua: "팔데아의 모습·워터종",
  mega: "메가진화",
  megax: "메가진화 X",
  megay: "메가진화 Y",
  primal: "원시회귀",
  gmax: "거다이맥스",
  rapidstrike: "연격의 태세",
  rapidstrikegmax: "연격의 태세·거다이맥스",
  singlestrike: "일격의 태세",
  singlestrikegmax: "일격의 태세·거다이맥스",
  bloodmoon: "붉은 달",
  shadow: "흑마 탄 모습",
  ice: "백마 탄 모습",
  eternamax: "무한다이맥스",
  crowned: "왕의 모습",
  crownedsteel: "검왕",
  crownedshield: "방패왕",
  origin: "오리진폼",
  therian: "영물폼",
  incarnate: "화신폼",
  attack: "어택폼",
  defense: "디펜스폼",
  speed: "스피드폼",
  sky: "스카이폼",
  dusk: "황혼의 갈기",
  dawn: "새벽의 날개",
  duskmane: "황혼의 갈기",
  dawnwings: "새벽의 날개",
  ultra: "울트라네크로즈마",
  complete: "퍼펙트폼",
  ten: "10%폼",
  school: "군집의 모습",
  solo: "단독의 모습",
  zen: "달마모드",
  galarzen: "가라르의 모습·달마모드",
  heat: "히트로토무",
  wash: "워시로토무",
  frost: "프로스트로토무",
  fan: "스핀로토무",
  mow: "커트로토무",
  trash: "모래땅도롱",
  sandy: "슈레도롱",
  plant: "초목도롱",
  east: "동쪽바다",
  west: "서쪽바다",
  lowkey: "로우한 모습",
  amped: "하이한 모습",
  antique: "진품",
  masterpiece: "걸작품",
  familythree: "세 식구",
  four: "네 식구",
  hero: "마이티폼",
  zero: "나이브폼",
  wellspring: "우물의 가면",
  hearthflame: "화덕의 가면",
  cornerstone: "주춧돌의 가면",
  teal: "벽록의 가면",
  terastal: "테라스탈폼",
  stellar: "스텔라폼",
};

const SPECIES_FORM_LABELS = {
  kyuremblack: "블랙큐레무",
  kyuremwhite: "화이트큐레무",
  basculinblue: "파란 줄무늬",
  basculinwhite: "하얀 줄무늬",
  basculinred: "빨간 줄무늬",
  miniorblue: "파란색 코어",
  minioryellow: "노란색 코어",
  zaciancrowned: "검왕",
  zamazentacrowned: "방패왕",
};

function fallbackName(value) {
  return String(value ?? "")
    .replace(/^[a-z0-9_]+:/i, "")
    .replaceAll("_", " ")
    .trim();
}

export function localizedSpeciesName(catalog, value) {
  if (!value) return "";
  const entries = Object.entries(catalog?.species ?? {});
  const targetId = normalizedId(value);
  const exact = entries.find(([key]) => normalizedId(key) === targetId)?.[1];
  if (exact?.name) return exact.name;

  const base = entries
    .filter(
      ([key, entry]) =>
        entry?.name &&
        targetId.startsWith(normalizedId(key)) &&
        normalizedId(key).length < targetId.length,
    )
    .sort(
      ([left], [right]) =>
        normalizedId(right).length - normalizedId(left).length,
    )[0];
  if (!base) return fallbackName(value);

  const [baseKey, baseEntry] = base;
  const formId = targetId.slice(normalizedId(baseKey).length);
  const formLabel = SPECIES_FORM_LABELS[targetId] ?? FORM_LABELS[formId];
  if (!formLabel) return `${baseEntry.name} (${fallbackName(value)})`;
  return `${baseEntry.name} (${formLabel})`;
}
