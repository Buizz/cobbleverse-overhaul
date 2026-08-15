import {
  createPartyClipboardEntry,
  parsePartyClipboardText,
  readClipboardText,
  toContentManagerParty,
  writeClipboardText,
} from "/pokemon-entry-clipboard.mjs";

const structureViewPitch = {
  default: -.68,
  minimum: -Math.PI / 2 + .02,
  maximum: -.30
};

const state = {
  project: null,
  trainers: [], battles: [], settlements: [], caves: [], forests: [], trainer: null, battlePreset: null, settlement: null, cave: null, forest: null, settlementOrderSaving: false,
  gymCatalog: { schema_version: 1, gyms: [], leagues: [] }, selectedGymId: "",
  trainerPath: "", battlePath: "", settlementPath: "", cavePath: "", forestPath: "", buildCommands: [], exportLanguages: [], trainerClasses: [], trainerRoster: { organizations: [], league_characters: [] },
  trainerReferences: { sources: [], entries: [] },
  selectedPokemonIndex: 0, editorCatalog: null, choice: null,
  biomeCatalog: { profiles: [], sets: [] }, pokemonHabitats: [], selectedBiomeProfile: null,
  worldLayout: null, worldGenerations: [1], selectedGeneration: 1,
  worldPokemonMap: { locations: [], available_pokemon: [], unavailable_pokemon: [], summary: {} },
  pokemonMapTab: "available", pokemonMapQuery: "",
  selectedHex: null, selectedEntrance: null, mapRadius: 6, mapZoom: 1, mapCenter: { x: 490, y: 330 }, mapViewInitialized: false,
  mapPan: null, suppressMapClick: false, draggedSettlement: null, entranceDrag: null, routeDraft: null, worldDirty: false,
  activeMapTool: "select", paintStroke: null, brushPreview: null, levelOverlayVisible: false, spacePanActive: false, selectedRouteId: null, routeAnchorDrag: null, routePokemonQuery: "",
  routePokemonPicker: { query: "", generation: "all", type: "all", habitat: "all", rarity: "all", special: "all", availability: "all", selected: new Set() },
  routePokemonLevelSpecies: null, encounterPokemonTarget: null, encounterPokemonQuery: "", encounterPokemonLevelSpecies: null,
  encounterPokemonPicker: { query: "", generation: "all", type: "all", habitat: "all", rarity: "all", special: "all", availability: "all", selected: new Set() },
  structureSizes: {}, villageView: { zoom: 1, panX: 0, panY: 0, drag: null },
  gymLayout: { selected: null, drag: null, hitTargets: [] },
  buildingSettings: { query: "", category: "all", selected: "", model: null, structures: {}, npcs: [], yaw: -.75, pitch: structureViewPitch.default, zoom: 1, drag: null, requestId: 0, dirty: false },
  cavePreview: { yaw: -.72, pitch: -.52, zoom: 1, view: "perspective", tool: "select", pathDraft: null, drag: null, selected: null, hitTargets: [], projection: null, placement: { anchor: { idPrefix: "anchor", kind: "room", radiusX: 12, radiusZ: 12, height: 12 }, entrance: { idPrefix: "entrance", displayName: "입출구", requiredProgress: "", fallbackX: 4, fallbackY: 1, fallbackZ: 0 }, path: { idPrefix: "connection", kind: "tunnel", width: 5 } } },
  forestPreview: { selectedPath: null, selectedAnchor: null, selectedEntranceIndex: null, seedOffset: 0, tool: "select", zoom: 1, panX: 0, panY: 0, heightBrushRadius: 0, heightBrushTarget: 1, brushHover: null, stairPlacement: { kind: "stairs", direction: "auto", block: "minecraft:oak_stairs" }, drag: null, hitTargets: [] },
  customTownTool: "cell",
  leagueProgression: { schema_version: 1, entries: [] }, selectedLeagueId: "",
  badgeCatalog: { schema_version: 1, badges: [], regions_without_gym_badges: [] },
  gameDefinitions: { schema_version: 1, items: [], variables: [] },
  musicCatalog: { schema_version: 1, tracks: [], defaults: {} },
  economy: { schema_version: 2, vanilla_crafting_disabled: true, standard_prices: [], shop_catalogs: [], vendor_units: [], pokemon_drop_rules: [], pokemon_drop_overrides: [], npc_recipes: [], resolved_shop_catalogs: [], resolved_vendor_units: [], resolved_standard_prices: [], resolved_pokemon_drops: [], editor_catalog: { items: [], species: [], filters: {} } },
  economyView: { catalogSearch: "", vendorSearch: "", selectedVendorId: "", selectedCatalogId: "", vendorProductGroup: "balls", vendorProductSearch: "", pokemonSearch: "", pokemonType: "", pokemonGeneration: "", pokemonLimit: 50 },
  structureBuilder: null
};
const lazyDataLoaded = { trainers: false, biomes: false, structures: false, buildingSettings: false, definitions: false, economy: false };
const lazyDataPromises = { trainers: null, biomes: null, structures: null, buildingSettings: null, definitions: null, economy: null };
const reservedWorldObjectTypes = new Map([
  ["villain_base", "빌런기지"],
  ["legendary_site", "전설 포켓몬 장소"],
]);
const defaultWorldEntranceStructures = {
  cave: "cobbleventure:cave_entrance/stone_mountain",
  caveVariants: {
    high_forest: "cobbleventure:cave_entrance/plains",
    dense_forest: "cobbleventure:cave_entrance/plains",
    desert: "cobbleventure:cave_entrance/red_rock_mountain",
    ocean: "cobbleventure:cave_entrance/ocean",
    deep_ocean: "cobbleventure:cave_entrance/ocean",
    stone_mountain: "cobbleventure:cave_entrance/stone_mountain",
    red_rock_mountain: "cobbleventure:cave_entrance/red_rock_mountain",
    snow_mountain: "cobbleventure:cave_entrance/snow_mountain"
  },
  forest: "cobbleventure:forest_entrance/forest_gate"
};
const biomeChoices = {
  habitat: [["plains", "평원"], ["forest", "숲"], ["arid", "건조지"], ["mountain", "산악"], ["cave", "동굴"], ["wetland", "습지"], ["freshwater", "담수"], ["ocean", "해양"], ["snow", "설원"], ["volcanic", "화산"], ["urban", "도시"], ["special", "특수"]],
  temperature: [["any", "무관"], ["cold", "한랭"], ["cool", "서늘"], ["temperate", "온대"], ["hot", "고온"]],
  humidity: [["any", "무관"], ["dry", "건조"], ["normal", "보통"], ["humid", "다습"], ["aquatic", "수중"]],
  weather: [["any", "무관"], ["clear", "맑음"], ["rain", "비"], ["thunder", "뇌우"], ["snow", "눈"], ["fog", "안개"]],
  time: [["any", "무관"], ["day", "낮"], ["night", "밤"], ["twilight", "황혼"]]
};
const settlementFacilityCatalog = [
  { id: "player_house", label: "플레이어 집", note: "플레이어의 거점 주택", width: 16, depth: 16, height: 13, color: "#9a7248" },
  { id: "laboratory", label: "연구소", note: "스타팅 포켓몬 지급", width: 32, depth: 32, height: 14, color: "#4cc9f0" },
  { id: "fossil_laboratory", label: "화석연구소", note: "화석 포켓몬 복원", width: 32, depth: 32, height: 14, color: "#c9a66b" },
  { id: "daycare", label: "키우미집", note: "건물과 야외 목장", width: 32, depth: 32, height: 10, color: "#80b918" },
  { id: "tm_workshop", label: "기술머신 조합소", note: "기술머신 제작 시설", width: 32, depth: 16, height: 10, color: "#f48c06" },
  { id: "hotel", label: "호텔", note: "중대형 숙박 시설", width: 32, depth: 32, height: 20, color: "#e85d75" },
  { id: "casino", label: "카지노", note: "CasinoCraft 게임 시설 예정", width: 48, depth: 48, height: 20, color: "#d4a017" },
  { id: "battle_tower", label: "배틀타워", note: "전투 랜드마크", width: 48, depth: 48, height: 32, color: "#9d4edd" },
  { id: "radio_tower", label: "라디오 타워", note: "방송국과 송신탑", width: 48, depth: 48, height: 32, color: "#4361ee" },
  { id: "train_station", label: "기차역", note: "역사와 선로 예약부지", width: 48, depth: 64, height: 14, color: "#495057" },
  { id: "lighthouse", label: "등대", note: "해안과 항구의 항로 랜드마크", width: 32, depth: 32, height: 48, color: "#f1f3f5" },
  { id: "power_plant", label: "파워플랜트", note: "발전 설비와 사건 진행 시설", width: 48, depth: 48, height: 24, color: "#adb5bd" },
  { id: "mansion", label: "멘션", note: "대저택과 스토리 랜드마크", width: 48, depth: 48, height: 24, color: "#6f4e37" }
];
const legacyGymFacilityIds = new Set(["gym_site", "gym_lot"]);
const houseBaseCatalog = [
  { id: "one_story", label: "1층 주택", width: 16, depth: 16, height: 13 },
  { id: "two_story", label: "2층 주택", width: 16, depth: 16, height: 18 },
  { id: "five_story", label: "5층 고층주택", width: 16, depth: 16, height: 33 }
];
const houseRoofCatalog = [
  { id: "gable", label: "박공지붕" },
  { id: "gambrel", label: "갬브럴지붕" },
  { id: "shed", label: "외쪽 경사지붕" },
  { id: "flat", label: "평지붕" }
];
const houseRoofColorCatalog = [
  { id: "red", label: "빨강", color: "#9d3030" },
  { id: "orange", label: "주황", color: "#c96b32" },
  { id: "yellow", label: "노랑", color: "#c6a632" },
  { id: "green", label: "초록", color: "#567d46" },
  { id: "blue", label: "파랑", color: "#39778a" },
  { id: "purple", label: "보라", color: "#75517f" },
  { id: "brown", label: "갈색", color: "#654b36" },
  { id: "gray", label: "회색", color: "#656b70" },
  { id: "black", label: "검정", color: "#292d32" },
  { id: "white", label: "하양", color: "#e4e1d9" }
];
const defaultHousePalette = {
  bases: houseBaseCatalog.map((item) => item.id),
  roofs: houseRoofCatalog.map((item) => item.id),
  roof_colors: ["red", "blue", "green", "brown"]
};
const civicFacilityCatalog = {
  pokemon_center: { id: "pokemon_center", label: "포켓몬센터", width: 32, depth: 32, height: 16, color: "#e63946", structure: "bca:default/one_off/pokecenter" },
  pokemart: { id: "pokemart", label: "포켓몬상점", width: 32, depth: 16, height: 12, color: "#3a86ff", structure: "bca:default/one_off/structure_pokemart" },
  department_store: { id: "department_store", label: "백화점", width: 48, depth: 48, height: 24, color: "#ff006e", structure: "bca:default/centers/center_department_store" }
};
const natureDefinitions = [
  ["hardy", "노력", null, null], ["lonely", "외로움", "attack", "defense"],
  ["brave", "용감", "attack", "speed"], ["adamant", "고집", "attack", "special_attack"],
  ["naughty", "개구쟁이", "attack", "special_defense"], ["bold", "대담", "defense", "attack"],
  ["docile", "온순", null, null], ["relaxed", "무사태평", "defense", "speed"],
  ["impish", "장난꾸러기", "defense", "special_attack"], ["lax", "촐랑", "defense", "special_defense"],
  ["timid", "겁쟁이", "speed", "attack"], ["hasty", "성급", "speed", "defense"],
  ["serious", "성실", null, null], ["jolly", "명랑", "speed", "special_attack"],
  ["naive", "천진난만", "speed", "special_defense"], ["modest", "조심", "special_attack", "attack"],
  ["mild", "의젓", "special_attack", "defense"], ["quiet", "냉정", "special_attack", "speed"],
  ["bashful", "수줍음", null, null], ["rash", "덜렁", "special_attack", "special_defense"],
  ["calm", "차분", "special_defense", "attack"], ["gentle", "얌전", "special_defense", "defense"],
  ["sassy", "건방", "special_defense", "speed"], ["careful", "신중", "special_defense", "special_attack"],
  ["quirky", "변덕", null, null]
].map(([id, name, increased, decreased]) => ({ id, name, increased, decreased }));
const statLabels = { hp: "체력", attack: "공격", defense: "방어", special_attack: "특수공격", special_defense: "특수방어", speed: "스피드" };
const pokemonStatKeys = ["hp", "attack", "defense", "special_attack", "special_defense", "speed"];
const natureStats = ["", "hp", "attack", "defense", "special_attack", "special_defense", "speed"];
const neutralNatureByStat = { attack: "hardy", defense: "docile", special_attack: "bashful", special_defense: "quirky", speed: "serious" };
const pokemonTypeNames = { Normal:"노말", Fire:"불꽃", Water:"물", Electric:"전기", Grass:"풀", Ice:"얼음", Fighting:"격투", Poison:"독", Ground:"땅", Flying:"비행", Psychic:"에스퍼", Bug:"벌레", Rock:"바위", Ghost:"고스트", Dragon:"드래곤", Dark:"악", Steel:"강철", Fairy:"페어리" };
const zCrystalByType = {
  Normal: "normalium_z", Fire: "firium_z", Water: "waterium_z", Electric: "electrium_z",
  Grass: "grassium_z", Ice: "icium_z", Fighting: "fightinium_z", Poison: "poisonium_z",
  Ground: "groundium_z", Flying: "flyinium_z", Psychic: "psychium_z", Bug: "buginium_z",
  Rock: "rockium_z", Ghost: "ghostium_z", Dragon: "dragonium_z", Dark: "darkinium_z",
  Steel: "steelium_z", Fairy: "fairium_z"
};
const signatureZCrystals = [
  { item: "aloraichium_z", species: ["raichu"], forms: ["alola"], moves: ["thunderbolt"] },
  { item: "decidium_z", species: ["decidueye"], moves: ["spiritshackle"] },
  { item: "eevium_z", species: ["eevee"], moves: ["lastresort"] },
  { item: "incinium_z", species: ["incineroar"], moves: ["darkestlariat"] },
  { item: "kommonium_z", species: ["kommoo"], moves: ["clangingscales"] },
  { item: "lunalium_z", species: ["lunala"], moves: ["moongeistbeam"] },
  { item: "lycanium_z", species: ["lycanroc"], moves: ["stoneedge"] },
  { item: "marshadium_z", species: ["marshadow"], moves: ["spectralthief"] },
  { item: "mewnium_z", species: ["mew"], moves: ["psychic"] },
  { item: "mimikium_z", species: ["mimikyu"], moves: ["playrough"] },
  { item: "pikanium_z", species: ["pikachu"], moves: ["volttackle"] },
  { item: "pikashunium_z", species: ["pikachu"], moves: ["thunderbolt"] },
  { item: "primarium_z", species: ["primarina"], moves: ["sparklingaria"] },
  { item: "snorlium_z", species: ["snorlax"], moves: ["gigaimpact"] },
  { item: "solganium_z", species: ["solgaleo"], moves: ["sunsteelstrike"] },
  { item: "tapunium_z", speciesPrefix: "tapu", moves: ["naturesmadness"] },
  { item: "ultranecrozium_z", species: ["necrozma"], moves: ["photongeyser"] }
];

const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => [...document.querySelectorAll(selector)];

async function request(url, options = {}) {
  let response;
  try {
    response = await fetch(url, {
      ...options,
      headers: { "Content-Type": "application/json", ...(options.headers || {}) }
    });
  } catch (error) {
    return { ok: false, status: 0, data: { error: `서버 요청에 실패했습니다: ${error.message}` } };
  }
  let data;
  try { data = await response.json(); }
  catch { data = { error: `응답을 읽을 수 없습니다. (${response.status})` }; }
  return { ok: response.ok, status: response.status, data };
}

function toast(message) {
  const element = $("#toast");
  element.textContent = message;
  element.classList.add("show");
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => element.classList.remove("show"), 2600);
}

function showProjectLoading(detail = "프로젝트 데이터 확인 중…") {
  const overlay = $("#project-loading-overlay");
  clearTimeout(showProjectLoading.hideTimer);
  showProjectLoading.startedAt = performance.now();
  overlay.classList.remove("is-hidden");
  overlay.setAttribute("aria-busy", "true");
  $("#project-loading-detail").textContent = detail;
}

function updateProjectLoading(detail) {
  $("#project-loading-detail").textContent = detail;
}

function hideProjectLoading() {
  const overlay = $("#project-loading-overlay");
  const finish = () => {
    overlay.setAttribute("aria-busy", "false");
    overlay.classList.add("is-hidden");
  };
  const elapsed = performance.now() - (showProjectLoading.startedAt || 0);
  clearTimeout(showProjectLoading.hideTimer);
  if (elapsed >= 450) finish();
  else showProjectLoading.hideTimer = setTimeout(finish, 450 - elapsed);
}

function showIssues(target, payload) {
  const element = $(target);
  const issues = payload?.issues || [];
  if (!issues.length) {
    element.className = "issues empty";
    element.textContent = payload?.valid === false ? "검증에 실패했습니다." : "오류가 없습니다.";
    return;
  }
  element.className = "issues";
  element.innerHTML = issues.map((issue) => {
    const fieldLabel = issueFieldLabel(issue.path);
    return `
    <div class="issue ${issue.level === "warning" ? "warning" : ""}">
      <span class="issue-level">${issue.level === "warning" ? "경고" : "오류"}</span>
      <span><b>${fieldLabel ? `${escapeHtml(fieldLabel)} — ` : ""}${escapeHtml(issue.message)}</b><br><span class="issue-path">설정 위치: ${escapeHtml(issue.path || "$")}</span></span>
    </div>`;
  }).join("");
}

function issueFieldLabel(path) {
  const labels = {
    "$.content_profile.pokemon.biome_set": "바이옴 세트",
    "$.content_profile.pokemon.spawn_profile": "포켓몬 스폰 프로필",
    "$.content_profile.trainers.population_profile": "트레이너 인구 프로필"
  };
  return labels[path] || "";
}

function escapeHtml(value) {
  return String(value).replace(/[&<>'"]/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;"
  })[character]);
}

function musicTrack(trackId) {
  return (state.musicCatalog.tracks || []).find((track) => track.id === trackId) || null;
}

function musicTrackLabel(trackId) {
  const track = musicTrack(trackId);
  const review = track && (state.musicCatalog.review_candidates || []).some(
    (candidate) => candidate.source_file === track.source_file
  );
  return track ? `${review ? "[검토 필요] " : ""}${track.usage} · ${track.id}` : trackId || "미지정";
}

function musicOptions(selected = "", inheritContext = "") {
  const inherited = inheritContext ? state.musicCatalog.defaults?.[inheritContext] : "";
  const first = inheritContext
    ? `<option value="">상속 · ${escapeHtml(musicTrackLabel(inherited))}</option>`
    : "";
  return first + (state.musicCatalog.tracks || []).map((track) =>
    `<option value="${escapeHtml(track.id)}" ${track.id === selected ? "selected" : ""}>${escapeHtml(musicTrackLabel(track.id))}</option>`
  ).join("");
}

function musicOverrideAt(q, r) {
  return (state.worldLayout?.music_overrides || []).find((entry) => entry.q === q && entry.r === r) || null;
}

function setMusicOverride(q, r, trackId) {
  state.worldLayout.music_overrides ||= [];
  state.worldLayout.music_overrides = state.worldLayout.music_overrides.filter((entry) => entry.q !== q || entry.r !== r);
  if (trackId) state.worldLayout.music_overrides.push({ q, r, music_track: trackId });
}

function renderMusicSettings() {
  const form = $("#music-defaults-form");
  if (!form) return;
  for (const context of ["tile", "road", "settlement", "building", "pokemon_center", "pokemart", "battle", "gym"]) {
    form.elements[context].innerHTML = musicOptions(state.musicCatalog.defaults?.[context] || "");
    form.elements[context].value = state.musicCatalog.defaults?.[context] || "";
  }
  const tracks = state.musicCatalog.tracks || [];
  $("#music-track-count").textContent = `${tracks.length}곡`;
  const library = state.musicCatalog.local_library || {};
  $("#music-library-path").textContent = library.directory
    ? `${library.directory} · OGG ${library.registered_ogg || tracks.length}곡${library.added ? ` · 이번에 ${library.added}곡 자동 추가` : ""}`
    : "로컬 music 폴더의 OGG 파일을 자동으로 등록합니다. 리소스팩에는 실제 배정된 곡만 포함됩니다.";
  $("#music-track-list").innerHTML = tracks.map((track) => {
    const review = (state.musicCatalog.review_candidates || []).find((candidate) => candidate.source_file === track.source_file);
    return `<article class="definition-card"><header><div><strong>${escapeHtml(track.usage)}</strong><code>${escapeHtml(track.id)}</code></div>${review ? '<span class="count-pill">검토 필요</span>' : ""}</header><div class="definition-fields"><label><span>로컬 파일</span><input readonly value="${escapeHtml(track.source_file)}"></label><label><span>사운드 이벤트</span><input readonly value="${escapeHtml(state.musicCatalog.namespace)}:${escapeHtml(track.sound_event)}"></label><label><span>분류</span><input readonly value="${escapeHtml(track.category)}"></label>${review ? `<label class="wide"><span>검토 사유</span><input readonly value="${escapeHtml(review.reason)}"></label>` : ""}</div></article>`;
  }).join("");
  showIssues("#music-issues", { valid: true, issues: [] });
}

async function refreshMusicLibrary() {
  const result = await request("/api/music-catalog");
  if (!result.ok) {
    toast(result.data.error || "로컬 음원 폴더를 읽지 못했습니다.");
    return;
  }
  state.musicCatalog = result.data;
  renderMusicSettings();
  toast(result.data.local_library?.added
    ? `새 OGG ${result.data.local_library.added}곡을 자동 등록했습니다.`
    : "로컬 음원 목록이 최신 상태입니다.");
}

async function saveMusicSettings() {
  const form = $("#music-defaults-form");
  state.musicCatalog.defaults = Object.fromEntries(
    ["tile", "road", "settlement", "building", "pokemon_center", "pokemart", "battle", "gym"].map((context) => [context, form.elements[context].value])
  );
  const result = await request("/api/music-catalog", { method: "PUT", body: JSON.stringify(state.musicCatalog) });
  showIssues("#music-issues", result.data);
  toast(result.ok ? "상황별 기본 음악을 저장했습니다." : "음악 기본값을 확인해 주세요.");
  if (result.ok) renderMusicSettings();
}

function switchPage(section) {
  const navigationSection = ["gyms", "trainer-card"].includes(section) ? "league" : section;
  $$(".nav-item").forEach((button) => button.classList.toggle("is-active", button.dataset.section === navigationSection));
  $$(".page").forEach((page) => page.classList.toggle("is-active", page.id === section));
  const titles = { dashboard: "프로젝트 현황", trainers: "트레이너풀", battles: "배틀 프리셋", league: "리그 운영 · 구성원", "trainer-card": "리그 운영 · 자동 카드", worlds: "세대별 월드맵", caves: "동굴 관리", forests: "숲 관리", settlements: "마을 프리셋", gyms: "리그 운영 · 체육관 시설", "space-connections": "공간 연결 관계", structures: "NBT 건물 설정", biomes: "바이옴 관리", definitions: "아이템 · 진행 변수", economy: "상점 · 드롭 · NPC 제작", music: "음악 배정 · 기본값", builds: "빌드 및 검사" };
  $("#page-title").textContent = titles[section];
  if (section === "worlds") requestAnimationFrame(resizeWorldMapWorkspace);
  if (section === "structures") requestAnimationFrame(renderBuildingModel);
  loadSectionData(section).catch((error) => toast(error.message));
}

async function loadDashboard() {
  const result = await request("/api/dashboard");
  if (!result.ok) throw new Error(result.data.error || "대시보드를 불러오지 못했습니다.");
  const data = result.data;
  $("#trainer-count").textContent = data.trainers;
  $("#settlement-count").textContent = data.settlements;
  $("#validation-count").textContent = data.validation.errors;
  $("#validation-caption").textContent = `오류 ${data.validation.errors} · 경고 ${data.validation.warnings}`;
  $("#health-score").textContent = data.validation.valid ? "OK" : "CHECK";
  showIssues("#dashboard-issues", data.validation);
  state.buildCommands = data.build_commands;
  state.exportLanguages = data.export_languages || [{ id: "ko_kr", name: "한국어" }, { id: "en_us", name: "English (US)" }];
  renderBuildCommands();
}

function renderActiveProject() {
  const project = state.project;
  $("#active-project-name").textContent = project?.name || "프로젝트 없음";
  $("#active-project-path").textContent = project?.path || "";
  $("#open-project").title = project?.path || "프로젝트 불러오기";
}

async function loadActiveProject() {
  updateProjectLoading("프로젝트 정보 확인 중…");
  const result = await request("/api/project");
  if (!result.ok) throw new Error(result.data.error || "현재 프로젝트를 불러오지 못했습니다.");
  state.project = result.data.project;
  renderActiveProject();
  updateProjectLoading(`${state.project.name} 데이터 목록 확인 중…`);
}

function openProjectDialog() {
  const form = $("#project-form");
  form.elements.path.value = state.project?.path || "";
  $("#project-issues").className = "issues empty";
  $("#project-issues").textContent = "";
  $("#project-dialog").showModal();
  form.elements.path.select();
}

async function loadProject(event) {
  event.preventDefault();
  const path = event.currentTarget.elements.path.value.trim();
  showProjectLoading("프로젝트 폴더 확인 중…");
  const result = await request("/api/project", {
    method: "PUT",
    body: JSON.stringify({ path })
  });
  if (!result.ok) {
    hideProjectLoading();
    $("#project-issues").className = "issues";
    $("#project-issues").textContent = result.data.error || "프로젝트를 불러오지 못했습니다.";
    return;
  }
  state.project = result.data.project;
  renderActiveProject();
  updateProjectLoading(`${state.project.name} 프로젝트를 여는 중…`);
  toast(`${state.project.name} 프로젝트를 불러왔습니다.`);
  window.setTimeout(() => window.location.reload(), 250);
}

async function pickProjectFolder() {
  const form = $("#project-form");
  const button = $("#pick-project-folder");
  button.disabled = true;
  try {
    const result = await request("/api/project/pick", {
      method: "POST",
      body: JSON.stringify({ path: form.elements.path.value.trim() })
    });
    if (!result.ok) throw new Error(result.data.error || "폴더 선택창을 열지 못했습니다.");
    if (!result.data.cancelled) form.elements.path.value = result.data.path;
  } catch (error) {
    $("#project-issues").className = "issues";
    $("#project-issues").textContent = error.message;
  } finally {
    button.disabled = false;
  }
}

async function loadLists() {
  const [trainers, battles, settlements, caves, forests, worldLayouts, worldLayout, worldPokemonMap, league, gyms, badges, music] = await Promise.all([
    request("/api/trainers"), request("/api/battles"), request("/api/settlements"), request("/api/caves"), request("/api/forests"),
    request("/api/world-layouts"), request(`/api/world-layout?generation=${state.selectedGeneration}`),
    request(`/api/world-pokemon-map?generation=${state.selectedGeneration}`), request("/api/league-progression"), request("/api/gyms"), request("/api/badges"), request("/api/music-catalog")
  ]);
  const failures = [];
  const applyDocumentList = (category, result, label) => {
    const singular = documentSingular(category);
    if (result.ok) {
      state[category] = result.data.items || [];
      renderList(category);
      return;
    }
    state[category] = [];
    const message = result.data.error || `${label} 목록을 불러오지 못했습니다.`;
    failures.push(`${label}: ${message}`);
    $(`#${singular}-list-count`).textContent = "오류";
    $(`#${singular}-list`).innerHTML = `<div class="issues">${escapeHtml(message)}</div>`;
  };
  applyDocumentList("trainers", trainers, "NPC");
  applyDocumentList("battles", battles, "배틀 프리셋");
  applyDocumentList("settlements", settlements, "마을 프리셋");
  applyDocumentList("caves", caves, "동굴");
  applyDocumentList("forests", forests, "숲");
  if (league.ok) state.leagueProgression = league.data;
  else {
    const message = league.data.error || "리그 설정을 불러오지 못했습니다.";
    failures.push(`리그: ${message}`);
    $("#league-entry-count").textContent = "오류";
    $("#league-entry-list").innerHTML = `<div class="issues">${escapeHtml(message)}</div>`;
  }
  if (gyms.ok) state.gymCatalog = gyms.data;
  else {
    const message = gyms.data.error || "체육관 목록을 불러오지 못했습니다.";
    failures.push(`체육관: ${message}`);
    $("#gym-list-count").textContent = "오류";
    $("#gym-list").innerHTML = `<div class="issues">${escapeHtml(message)}</div>`;
  }
  if (badges.ok) state.badgeCatalog = badges.data;
  else failures.push(`배지: ${badges.data.error || "배지 카탈로그를 불러오지 못했습니다."}`);
  if (music.ok) state.musicCatalog = music.data;
  else {
    const message = music.data.error || "음악 카탈로그를 불러오지 못했습니다.";
    failures.push(`음악: ${message}`);
    $("#music-issues").className = "issues";
    $("#music-issues").textContent = message;
  }
  state.worldGenerations = worldLayouts.ok ? worldLayouts.data.generations || [1] : [1];
  state.worldLayout = worldLayout.ok ? worldLayout.data : null;
  if (worldLayout.ok && syncWorldSettlementPresets()) state.worldDirty = true;
  if (worldPokemonMap.ok) state.worldPokemonMap = worldPokemonMap.data;
  if (!worldLayouts.ok) failures.push(`월드 목록: ${worldLayouts.data.error || "불러오기 실패"}`);
  if (!worldLayout.ok) failures.push(`월드맵: ${worldLayout.data.error || "불러오기 실패"}`);
  if (!worldPokemonMap.ok) failures.push(`월드 포켓몬 지도: ${worldPokemonMap.data.error || "불러오기 실패"}`);
  if (league.ok) renderLeagueList();
  if (gyms.ok) renderGymList();
  if (badges.ok) renderTrainerCardManager();
  if (music.ok) renderMusicSettings();
  renderWorldLayout();
  if (failures.length) throw new Error(failures.join("\n"));
}

async function loadTrainerData(force = false) {
  if (lazyDataLoaded.trainers && !force) return;
  if (lazyDataPromises.trainers) return lazyDataPromises.trainers;
  lazyDataPromises.trainers = (async () => {
    const [trainerClasses, trainerRoster, trainerReferences, editorCatalog] = await Promise.all([
      request("/api/trainer-classes"), request("/api/trainer-roster"), request("/api/trainer-reference-entries"), request("/api/editor-catalog")
    ]);
    if (!editorCatalog.ok) throw new Error(editorCatalog.data.error || "전투 데이터 카탈로그를 불러오지 못했습니다.");
    state.trainerClasses = trainerClasses.data.classes || [];
    state.trainerRoster = trainerRoster.ok ? trainerRoster.data : { organizations: [], league_characters: [] };
    state.trainerReferences = trainerReferences.ok ? trainerReferences.data : { sources: [], entries: [] };
    state.editorCatalog = editorCatalog.data;
    lazyDataLoaded.trainers = true;
    if (state.trainer) renderTrainer();
    if (state.battlePreset) renderBattlePreset();
  })();
  try { await lazyDataPromises.trainers; }
  finally { lazyDataPromises.trainers = null; }
}

async function loadBiomeData(force = false) {
  if (lazyDataLoaded.biomes && !force) return;
  if (lazyDataPromises.biomes) return lazyDataPromises.biomes;
  lazyDataPromises.biomes = (async () => {
    const [biomeCatalog, pokemonHabitats] = await Promise.all([
      request("/api/biome-catalog"), request("/api/pokemon-habitats")
    ]);
    if (!biomeCatalog.ok || !pokemonHabitats.ok) {
      const message = biomeCatalog.status === 404 || pokemonHabitats.status === 404
        ? "바이옴 API가 없는 이전 서버가 실행 중입니다. build.bat web을 다시 시작해 주세요."
        : (biomeCatalog.data.error || pokemonHabitats.data.error || "바이옴 데이터를 불러오지 못했습니다.");
      $("#biome-issues").className = "issues";
      $("#biome-issues").textContent = message;
      throw new Error(message);
    }
    state.biomeCatalog = biomeCatalog.data;
    state.pokemonHabitats = pokemonHabitats.data.pokemon || [];
    lazyDataLoaded.biomes = true;
    renderBiomeManager();
  })();
  try { await lazyDataPromises.biomes; }
  finally { lazyDataPromises.biomes = null; }
}

async function loadStructureData(force = false) {
  if (lazyDataLoaded.structures && !force) return;
  if (lazyDataPromises.structures) return lazyDataPromises.structures;
  if ($("#nbt-structure-count")) $("#nbt-structure-count").textContent = "불러오는 중";
  if ($("#nbt-structure-list")) $("#nbt-structure-list").innerHTML = '<div class="issues empty">NBT 목록을 필요한 시점에 불러오고 있습니다.</div>';
  lazyDataPromises.structures = (async () => {
    const suffix = force ? "?refresh=1" : "";
    const [result, buildingSettings] = await Promise.all([
      request(`/api/structure-sizes${suffix}`), request(`/api/building-settings${suffix}`)
    ]);
    if (!result.ok) throw new Error(result.data.error || "NBT 구조물 목록을 불러오지 못했습니다.");
    state.structureSizes = result.data.structures || {};
    if (buildingSettings.ok) for (const [id, metadata] of Object.entries(buildingSettings.data.structures || {})) {
      state.structureSizes[id] = { ...(state.structureSizes[id] || {}), ...metadata };
    }
    lazyDataLoaded.structures = true;
    renderStructureBrowser();
    if (state.settlement) renderVillageGenerationTest();
  })();
  try { await lazyDataPromises.structures; }
  catch (error) {
    if ($("#nbt-structure-count")) $("#nbt-structure-count").textContent = "오류";
    if ($("#nbt-structure-list")) $("#nbt-structure-list").innerHTML = `<div class="issues">${escapeHtml(error.message)}</div>`;
    throw error;
  } finally { lazyDataPromises.structures = null; }
}

async function loadGymStructureData(force = false) {
  await loadStructureData(force);
  const result = await request("/api/gym-interior-modules");
  if (!result.ok) throw new Error(result.data.error || "체육관 내부 모듈 정보를 불러오지 못했습니다.");
  for (const module of result.data.modules || []) {
    if (!module?.structure) continue;
    state.structureSizes[module.structure] = {
      ...(state.structureSizes[module.structure] || {}),
      ...module
    };
  }
}

async function loadBuildingSettingsData(force = false) {
  if (lazyDataLoaded.buildingSettings && !force) return;
  if (lazyDataPromises.buildingSettings) return lazyDataPromises.buildingSettings;
  lazyDataPromises.buildingSettings = (async () => {
    const result = await request(`/api/building-settings${force ? "?refresh=1" : ""}`);
    if (!result.ok) throw new Error(result.data.error || "건물 설정을 불러오지 못했습니다.");
    state.buildingSettings.structures = result.data.structures || {};
    state.buildingSettings.npcs = result.data.npcs || [];
    state.buildingSettings.dirty = false;
    const generatedAt = Number(result.data.cache?.generated_at || 0);
    const cacheLabel = generatedAt
      ? ` · 목록 ${new Date(generatedAt * 1000).toLocaleTimeString()}` : "";
    $("#building-settings-path").textContent = `${result.data.path || "content/catalogs/building-settings.json"}${cacheLabel}`;
    lazyDataLoaded.buildingSettings = true;
    renderBuildingList();
    const entries = buildingEntries();
    if (!state.buildingSettings.selected && entries.length) await loadBuildingModel(entries[0][0]);
    else if (state.buildingSettings.selected) renderBuildingEditor();
  })();
  try { await lazyDataPromises.buildingSettings; }
  catch (error) {
    $("#building-count").textContent = "오류";
    $("#building-list").innerHTML = `<div class="issues">${escapeHtml(error.message)}</div>`;
    throw error;
  } finally { lazyDataPromises.buildingSettings = null; }
}

function loadSectionData(section, force = false) {
  if (section === "music") {
    renderMusicSettings();
    return Promise.resolve();
  }
  if (section === "trainer-card") { renderTrainerCardManager(); return Promise.resolve(); }
  if (section === "trainers" || section === "battles" || section === "league") return Promise.all([loadTrainerData(force), loadGameDefinitions(force)]).then(() => { if (section === "league") renderLeagueEditor(); });
  if (section === "biomes") return loadBiomeData(force);
  if (section === "caves" || section === "forests") return loadBiomeData(force);
  if (section === "worlds") return loadStructureData(force).then(() => { renderWorldObjectNbtOptions(); renderMapToolOptions(); });
  if (section === "structures") return loadBuildingSettingsData(force);
  if (section === "gyms") return loadGymStructureData(force).then(renderGymEditor);
  if (section === "settlements") return Promise.all([loadBiomeData(force), loadStructureData(force), loadEconomy(force)]);
  if (section === "definitions") return loadGameDefinitions(force);
  if (section === "economy") return loadEconomy(force);
  if (section === "builds") return loadStructureBuilder();
  return Promise.resolve();
}

function settlementSummary(settlementId) {
  return state.settlements.find((item) => item.id === settlementId);
}
function caveSummary(caveId) { return state.caves.find((item) => item.id === caveId); }
function forestSummary(forestId) { return state.forests.find((item) => item.id === forestId); }
function caveEntranceAt(q, r) { return (state.worldLayout?.cave_entrances || []).find((entry) => entry.anchor?.q === q && entry.anchor?.r === r); }
function forestEntranceAt(q, r) { return (state.worldLayout?.forest_entrances || []).find((entry) => entry.anchor?.q === q && entry.anchor?.r === r); }
function selectedEntrance() {
  if (!state.selectedEntrance) return null;
  const list = state.selectedEntrance.kind === "cave" ? state.worldLayout?.cave_entrances : state.worldLayout?.forest_entrances;
  const entrance = (list || []).find((entry) => entry.id === state.selectedEntrance.id);
  return entrance ? { kind: state.selectedEntrance.kind, entrance } : null;
}
function normalizeTownCellCount(value) { const count = Number(value); return count === 3 || count === 5 || count === 7 || count === 19 ? count : 1; }
function settlementPresetRadius(settlementId) { return normalizeTownCellCount(settlementSummary(settlementId)?.town_radius_cells); }
function normalizeTownFootprintShape(value) { return ["triangle_up", "triangle_down", "line_q", "line_r", "line_s", "five_up", "five_down", "custom"].includes(value) ? value : "line_q"; }
function settlementPresetFootprintShape(settlementId) { return normalizeTownFootprintShape(settlementSummary(settlementId)?.town_footprint_shape); }
function normalizedAxialCells(value) { const seen = new Set(); return (Array.isArray(value) ? value : []).filter((cell) => Number.isInteger(cell?.q) && Number.isInteger(cell?.r)).map((cell) => ({ q: cell.q, r: cell.r })).filter((cell) => { const key = `${cell.q},${cell.r}`; if (seen.has(key)) return false; seen.add(key); return true; }); }
function settlementPresetFootprintCells(settlementId) { return normalizedAxialCells(settlementSummary(settlementId)?.town_footprint_cells); }
function settlementPresetRoadExits(settlementId) { return normalizedAxialCells(settlementSummary(settlementId)?.town_road_exits); }
function worldSettlementCellCount(node) { return settlementSummary(node?.settlement) ? settlementPresetRadius(node.settlement) : normalizeTownCellCount(node?.town_radius_cells); }
function worldSettlementFootprintShape(node) { return settlementSummary(node?.settlement) ? settlementPresetFootprintShape(node.settlement) : normalizeTownFootprintShape(node?.town_footprint_shape); }
function worldSettlementFootprintCells(node) { return settlementSummary(node?.settlement) ? settlementPresetFootprintCells(node.settlement) : normalizedAxialCells(node?.town_footprint_cells); }

function syncWorldSettlementPresets() {
  let changed = false;
  for (const node of state.worldLayout?.settlements || []) {
    if (!settlementSummary(node.settlement)) continue;
    const next = {
      town_radius_cells: settlementPresetRadius(node.settlement),
      town_footprint_shape: settlementPresetFootprintShape(node.settlement),
      town_footprint_cells: settlementPresetFootprintCells(node.settlement),
      town_road_exits: settlementPresetRoadExits(node.settlement)
    };
    if (node.town_radius_cells !== next.town_radius_cells
      || node.town_footprint_shape !== next.town_footprint_shape
      || JSON.stringify(normalizedAxialCells(node.town_footprint_cells)) !== JSON.stringify(next.town_footprint_cells)
      || JSON.stringify(normalizedAxialCells(node.town_road_exits)) !== JSON.stringify(next.town_road_exits)) changed = true;
    Object.assign(node, next);
  }
  return changed;
}

function worldSettlementOptions(selected) {
  const token = `generation_${state.selectedGeneration}/`;
  const candidates = state.settlements.filter((item) => item.path?.replaceAll("\\", "/").includes(token));
  return '<option value="">마을 선택</option>' + candidates.map((item) => `<option value="${escapeHtml(item.id)}" ${item.id === selected ? "selected" : ""}>${escapeHtml(item.name || item.id)}</option>`).join("");
}
function easyNpcPresetResource(npcId) {
  const slug = String(npcId || "").split("/").at(-1);
  return slug ? `easy_npc:preset/encounter/${slug}.npc.snbt` : "";
}
function worldGatekeeperOptions(selected = "") {
  const options = state.trainers.map((trainer) => {
    const resource = easyNpcPresetResource(trainer.id);
    return `<option value="${escapeHtml(resource)}" ${resource === selected ? "selected" : ""}>${escapeHtml(trainer.name || trainer.id)} · ${escapeHtml(trainer.id)}</option>`;
  });
  if (selected && !options.some((option) => option.includes(`value="${escapeHtml(selected)}"`))) options.unshift(`<option value="${escapeHtml(selected)}" selected>${escapeHtml(selected)} · 목록에 없음</option>`);
  return `<option value="">NPC 없음</option>${options.join("")}`;
}

function renderWorldLayout() {
  const layout = state.worldLayout;
  if (!layout) {
    renderGenerationTabs();
    $("#world-hex-map").innerHTML = "";
    $("#world-map-title").textContent = "월드맵이 없습니다";
    $("#world-layout-issues").className = "issues empty";
    $("#world-layout-issues").textContent = "＋ 세대 추가 버튼으로 새 월드맵을 만들 수 있습니다.";
    $("#save-world-layout").disabled = true;
    $("#delete-world-layout").disabled = true;
    return;
  }
  $("#save-world-layout").disabled = false;
  $("#delete-world-layout").disabled = false;
  layout.tiles ||= [];
  layout.settlements ||= [];
  layout.connections ||= [];
  layout.objects ||= [];
  layout.cave_entrances ||= [];
  layout.forest_entrances ||= [];
  for (const entrance of [...layout.cave_entrances, ...layout.forest_entrances]) {
    if (typeof entrance.pokemon_center_enabled !== "boolean") entrance.pokemon_center_enabled = Boolean(entrance.pokemon_center);
    delete entrance.pokemon_center;
  }
  layout.environment_overrides ||= [];
  layout.level_overrides ||= [];
  layout.music_overrides ||= [];
  layout.empty_terrain ||= { default_type: "high_forest", tiles: [] };
  layout.empty_terrain.default_type ||= "high_forest";
  layout.empty_terrain.tiles ||= [];
  const occupiedExtent = [...layout.tiles, ...layout.empty_terrain.tiles, ...layout.level_overrides, ...layout.settlements.map((node) => node.anchor || { q: 0, r: 0 }), ...layout.objects.map((node) => node.anchor || { q: 0, r: 0 }), ...layout.cave_entrances.map((node) => node.anchor || { q: 0, r: 0 }), ...layout.forest_entrances.map((node) => node.anchor || { q: 0, r: 0 })].reduce((largest, cell) => Math.max(largest, Math.abs(cell.q || 0), Math.abs(cell.r || 0), Math.abs((cell.q || 0) + (cell.r || 0))), 0);
  state.mapRadius = Math.max(Number(layout.grid?.map_radius_cells || state.mapRadius), Math.min(14, occupiedExtent + 1));
  renderGenerationTabs();
  renderMapToolOptions();
  $("#world-map-title").textContent = `${state.selectedGeneration}세대 월드`;
  $("#tile-radius-blocks").value = layout.grid?.tile_radius_blocks || 64;
  $("#level-overlay-toggle").checked = state.levelOverlayVisible;
  if (!state.mapViewInitialized) fitMapToContent();
  renderHexMap();
  renderTileInspector();
  updateWorldSaveState();
  showIssues("#world-layout-issues", { valid: true, issues: [] });
  requestAnimationFrame(resizeWorldMapWorkspace);
}

function resizeWorldMapWorkspace() {
  const workspace = $(".world-map-workspace");
  if (!workspace || !workspace.offsetParent) return;
  if (window.matchMedia("(max-width: 980px)").matches) { workspace.style.removeProperty("height"); return; }
  const issuesHeight = $("#world-layout-issues")?.offsetHeight || 0;
  const available = window.innerHeight - workspace.getBoundingClientRect().top - issuesHeight - 48;
  workspace.style.height = `${Math.max(320, Math.floor(available))}px`;
}

function renderGenerationTabs() {
  $("#generation-tabs").innerHTML = state.worldGenerations.map((generation) => `<button type="button" role="tab" aria-selected="${generation === state.selectedGeneration}" class="${generation === state.selectedGeneration ? "is-active" : ""}" data-generation="${generation}"><b>${generation}</b>세대</button>`).join("");
  $$("[data-generation]").forEach((button) => button.addEventListener("click", () => loadWorldGeneration(Number(button.dataset.generation))));
}

async function loadWorldGeneration(generation) {
  if (generation === state.selectedGeneration) return;
  if (state.worldDirty && !confirm("저장하지 않은 지도 변경을 버리고 다른 세대로 이동할까요?")) return;
  const [result, pokemonMap] = await Promise.all([
    request(`/api/world-layout?generation=${generation}`),
    request(`/api/world-pokemon-map?generation=${generation}`)
  ]);
  if (!result.ok) { toast(result.data.error || "세대 지도를 불러오지 못했습니다."); return; }
  state.selectedGeneration = generation;
  state.worldLayout = result.data;
  if (pokemonMap.ok) state.worldPokemonMap = pokemonMap.data;
  state.selectedHex = null;
  state.selectedEntrance = null;
  state.selectedRouteId = null;
  state.worldDirty = false;
  state.mapViewInitialized = false;
  renderWorldLayout();
}

function hexKey(q, r) { return `${q},${r}`; }
function mapHexSize() { return 24; }
function hexPoint(q, r) { const size = mapHexSize(); return { x: 490 + Math.sqrt(3) * size * (q + r / 2), y: 330 + size * 1.5 * r }; }
function entranceMapPoint(entrance, anchor = entrance.anchor) {
  const center = hexPoint(anchor.q, anchor.r); const distance = mapHexSize() - 7;
  const direction = ({ north: { x: 0, y: -1 }, east: { x: 1, y: 0 }, south: { x: 0, y: 1 }, west: { x: -1, y: 0 } })[entrance.facing] || { x: 1, y: 0 };
  return { x: center.x + direction.x * distance, y: center.y + direction.y * distance };
}
function hexPolygon(x, y, radius = mapHexSize() - 2) { return Array.from({ length: 6 }, (_, i) => { const angle = Math.PI / 180 * (60 * i - 30); return `${x + radius * Math.cos(angle)},${y + radius * Math.sin(angle)}`; }).join(" "); }
function pixelToHex(x, y) { const size = mapHexSize(); const r = (y - 330) / (size * 1.5); return roundHex((x - 490) / (Math.sqrt(3) * size) - r / 2, r); }
function mapViewBox() { const width = 980 / state.mapZoom; const height = 660 / state.mapZoom; return { x: state.mapCenter.x - width / 2, y: state.mapCenter.y - height / 2, width, height }; }
function mapPointFromPointer(event) {
  const svg = $("#world-hex-map"); const point = svg.createSVGPoint(); point.x = event.clientX; point.y = event.clientY;
  return point.matrixTransform(svg.getScreenCTM().inverse());
}
function zoomWorldMap(nextZoom, event = null) {
  const zoom = Math.max(.65, Math.min(1.6, nextZoom));
  if (zoom === state.mapZoom) return;
  if (event) {
    const currentView = mapViewBox();
    const anchor = mapPointFromPointer(event);
    const ratioX = (anchor.x - currentView.x) / currentView.width;
    const ratioY = (anchor.y - currentView.y) / currentView.height;
    state.mapZoom = zoom;
    const nextView = mapViewBox();
    state.mapCenter = {
      x: anchor.x + (.5 - ratioX) * nextView.width,
      y: anchor.y + (.5 - ratioY) * nextView.height,
    };
  } else state.mapZoom = zoom;
  renderHexMap();
}
function handleWorldMapWheel(event) {
  event.preventDefault();
  zoomWorldMap(state.mapZoom * (event.deltaY < 0 ? 1.12 : .89), event);
}
function visibleHexCells() {
  const view = mapViewBox(); const padding = mapHexSize() * 3;
  const corners = [[view.x - padding, view.y - padding], [view.x + view.width + padding, view.y - padding], [view.x - padding, view.y + view.height + padding], [view.x + view.width + padding, view.y + view.height + padding]].map(([x, y]) => pixelToHex(x, y));
  const minQ = Math.min(...corners.map((cell) => cell.q)) - 2; const maxQ = Math.max(...corners.map((cell) => cell.q)) + 2;
  const minR = Math.min(...corners.map((cell) => cell.r)) - 2; const maxR = Math.max(...corners.map((cell) => cell.r)) + 2;
  const cells = [];
  for (let q = minQ; q <= maxQ; q++) for (let r = minR; r <= maxR; r++) {
    const point = hexPoint(q, r);
    if (point.x >= view.x - padding && point.x <= view.x + view.width + padding && point.y >= view.y - padding && point.y <= view.y + view.height + padding) cells.push({ q, r });
  }
  return cells;
}
function fitMapToContent() {
  const cells = [...(state.worldLayout?.tiles || []), ...(state.worldLayout?.empty_terrain?.tiles || []), ...(state.worldLayout?.settlements || []).map((node) => node.anchor), ...(state.worldLayout?.objects || []).map((node) => node.anchor), ...(state.worldLayout?.cave_entrances || []).map((node) => node.anchor), ...(state.worldLayout?.forest_entrances || []).map((node) => node.anchor), ...(state.worldLayout?.connections || []).flatMap((connection) => connectionPath(connection))].filter(Boolean);
  if (!cells.length) { state.mapCenter = { x: 490, y: 330 }; state.mapZoom = 1; state.mapViewInitialized = true; return; }
  const points = cells.map((cell) => hexPoint(cell.q, cell.r)); const padding = 130;
  const minX = Math.min(...points.map((point) => point.x)); const maxX = Math.max(...points.map((point) => point.x));
  const minY = Math.min(...points.map((point) => point.y)); const maxY = Math.max(...points.map((point) => point.y));
  state.mapCenter = { x: (minX + maxX) / 2, y: (minY + maxY) / 2 };
  state.mapZoom = Math.max(.65, Math.min(1.6, Math.min(980 / Math.max(1, maxX - minX + padding), 660 / Math.max(1, maxY - minY + padding))));
  state.mapViewInitialized = true;
}
function biomeTone(biome = "") {
  if (/beach/.test(biome)) return "beach";
  if (/ocean|river/.test(biome)) return "water";
  if (/snow|ice|frozen|peak/.test(biome)) return "snow";
  if (/forest|jungle|grove/.test(biome)) return "forest";
  if (/desert|badlands|savanna/.test(biome)) return "arid";
  if (/swamp|marsh/.test(biome)) return "wetland";
  if (/hill|mountain|stone|windswept/.test(biome)) return "mountain";
  return "plains";
}
function tileAt(q, r) { return state.worldLayout?.tiles?.find((tile) => tile.q === q && tile.r === r); }
function emptyTerrainAt(q, r) { return state.worldLayout?.empty_terrain?.tiles?.find((tile) => tile.q === q && tile.r === r)?.type || state.worldLayout?.empty_terrain?.default_type || "high_forest"; }
function emptyTerrainTone(type) { return ({ high_forest: "forest", dense_forest: "dense-forest", ocean: "water", deep_ocean: "water", desert: "arid", stone_mountain: "mountain", red_rock_mountain: "arid", snow_mountain: "snow" })[type] || "forest"; }
function emptyTerrainLabel(type) { return ({ high_forest: "숲", dense_forest: "우거진 숲", ocean: "바다", deep_ocean: "심해", desert: "사막", stone_mountain: "돌산", red_rock_mountain: "적갈색 돌산", snow_mountain: "눈산" })[type] || type; }
function emptyTerrainSymbol(type) { return ({ high_forest: "♣", dense_forest: "♠", ocean: "≈", deep_ocean: "≋", desert: "·", stone_mountain: "▲", red_rock_mountain: "◆", snow_mountain: "△" })[type] || "×"; }
function settlementAt(q, r) { return state.worldLayout?.settlements?.find((node) => node.anchor?.q === q && node.anchor?.r === r); }
function objectAt(q, r) { return state.worldLayout?.objects?.find((node) => node.anchor?.q === q && node.anchor?.r === r); }
function environmentOverrideAt(q, r) { return state.worldLayout?.environment_overrides?.find((entry) => entry.q === q && entry.r === r); }
function levelOverrideAt(q, r) { return state.worldLayout?.level_overrides?.find((entry) => entry.q === q && entry.r === r); }
function townFootprintCells(center, cellCount, shape = "line_q", customCells = []) {
  const count = normalizeTownCellCount(cellCount);
  if (shape === "custom") return normalizedAxialCells(customCells).map((cell) => ({ q: center.q + cell.q, r: center.r + cell.r }));
  if (count === 1) return [{ ...center }];
  if (count === 3) {
    const shapes = {
      triangle_up: [[0, 0], [0, -1], [1, -1]],
      triangle_down: [[0, 0], [0, 1], [-1, 1]],
      line_q: [[-1, 0], [0, 0], [1, 0]],
      line_r: [[0, -1], [0, 0], [0, 1]],
      line_s: [[-1, 1], [0, 0], [1, -1]]
    };
    const offsets = shapes[normalizeTownFootprintShape(shape)] || shapes.line_q;
    return offsets.map(([q, r]) => ({ q: center.q + q, r: center.r + r }));
  }
  if (count === 5) {
    const offsets = normalizeTownFootprintShape(shape) === "five_down"
      ? [[-1, 0], [0, 0], [1, 0], [-1, 1], [0, 1]]
      : [[-1, 0], [0, 0], [1, 0], [0, -1], [1, -1]];
    return offsets.map(([q, r]) => ({ q: center.q + q, r: center.r + r }));
  }
  return hexArea(center, count === 19 ? 2 : 1);
}
function settlementFootprintAt(q, r) { return state.worldLayout?.settlements?.find((node) => node.anchor && townFootprintCells(node.anchor, worldSettlementCellCount(node), worldSettlementFootprintShape(node), worldSettlementFootprintCells(node)).some((cell) => cell.q === q && cell.r === r)); }
function hexDistance(from, to) { return (Math.abs(from.q - to.q) + Math.abs(from.r - to.r) + Math.abs((-from.q - from.r) - (-to.q - to.r))) / 2; }
function roundHex(q, r) {
  let roundedQ = Math.round(q); let roundedR = Math.round(r); let roundedS = Math.round(-q - r);
  const qDiff = Math.abs(roundedQ - q); const rDiff = Math.abs(roundedR - r); const sDiff = Math.abs(roundedS + q + r);
  if (qDiff > rDiff && qDiff > sDiff) roundedQ = -roundedR - roundedS;
  else if (rDiff > sDiff) roundedR = -roundedQ - roundedS;
  return { q: roundedQ, r: roundedR };
}
function straightHexPath(from, to) {
  const distance = hexDistance(from, to);
  if (!distance) return [{ q: from.q, r: from.r }];
  return Array.from({ length: distance + 1 }, (_, index) => {
    const ratio = index / distance;
    return roundHex(from.q + (to.q - from.q) * ratio, from.r + (to.r - from.r) * ratio);
  });
}
function routeCellsFromAnchors(anchors = []) {
  if (anchors.length < 2) return anchors.map(({ q, r }) => ({ q, r }));
  const cells = [];
  for (let index = 1; index < anchors.length; index++) {
    const segment = straightHexPath(anchors[index - 1], anchors[index]);
    if (index > 1) segment.shift();
    cells.push(...segment);
  }
  return cells;
}
function connectionAnchors(connection) {
  if (connection?.anchors?.length) return connection.anchors;
  const cells = connectionPath(connection);
  return cells.length > 1 ? [cells[0], cells.at(-1)] : cells;
}
function routeEndpointAnchor(endpointId) {
  if (!endpointId) return null;
  return state.worldLayout?.settlements?.find((node) => node.settlement === endpointId)?.anchor
    || state.worldLayout?.cave_entrances?.find((node) => node.id === endpointId)?.anchor
    || state.worldLayout?.forest_entrances?.find((node) => node.id === endpointId)?.anchor
    || null;
}
function routeEndpointEntrance(endpointId) {
  if (!endpointId) return null;
  return (state.worldLayout?.cave_entrances || []).find((node) => node.id === endpointId)
    || (state.worldLayout?.forest_entrances || []).find((node) => node.id === endpointId)
    || null;
}
function routeCellMapPoint(connection, cell, index, cells) {
  const endpointId = index === 0 ? connection.from : index === cells.length - 1 ? connection.to : null;
  const entrance = routeEndpointEntrance(endpointId);
  return entrance ? entranceMapPoint(entrance) : hexPoint(cell.q, cell.r);
}
function syncRouteEndpointAnchors(connection) {
  const anchors = connectionAnchors(connection).map((anchor) => ({ q: anchor.q, r: anchor.r }));
  const from = routeEndpointAnchor(connection?.from);
  const to = routeEndpointAnchor(connection?.to);
  let changed = false;
  if (!anchors.length) {
    if (!from || !to) return false;
    anchors.push({ ...from }, { ...to });
    changed = true;
  }
  if (from && (anchors[0].q !== from.q || anchors[0].r !== from.r)) {
    anchors[0] = { ...from };
    changed = true;
  }
  if (to) {
    if (anchors.length === 1 && from) {
      anchors.push({ ...to });
      changed = true;
    } else if (anchors.at(-1).q !== to.q || anchors.at(-1).r !== to.r) {
      anchors[anchors.length - 1] = { ...to };
      changed = true;
    }
  }
  if (!changed) return false;
  connection.anchors = anchors;
  connection.cells = routeCellsFromAnchors(anchors);
  connection.pathfinding = "explicit";
  return true;
}
function syncRoutesForEndpoint(endpointId) {
  return (state.worldLayout?.connections || []).reduce((changed, connection) => {
    if (connection.from !== endpointId && connection.to !== endpointId) return changed;
    return syncRouteEndpointAnchors(connection) || changed;
  }, false);
}
function connectionPath(connection) {
  const cells = connection.cells || [];
  if (cells.length) return cells;
  const from = routeEndpointAnchor(connection.from);
  const to = routeEndpointAnchor(connection.to);
  if (!from || !to) return [];
  return straightHexPath(from, to);
}
function syncConnectionPaths() {
  for (const connection of state.worldLayout?.connections || []) {
    connection.pathfinding = "explicit";
    connection.cells ||= connectionPath(connection);
  }
}
function routesAt(q, r) { return (state.worldLayout?.connections || []).filter((connection) => connectionPath(connection).some((cell) => cell.q === q && cell.r === r)); }
function primaryRouteAt(q, r) {
  return routesAt(q, r).find((route) => route.surface_style !== "water") || null;
}

function baseBiomeAt(q, r) {
  const tile = tileAt(q, r);
  if (tile) return tile.biome;
  const townArea = settlementFootprintAt(q, r);
  return townArea ? (townArea.town_biome || "minecraft:plains") : null;
}

const brushMapTools = new Set(["biome", "terrain", "climate", "level", "eraser"]);
function activeBrushRadius() {
  const inputId = {
    biome: "biome-brush-radius",
    terrain: "empty-terrain-brush-radius",
    climate: "climate-brush-radius",
    level: "level-brush-radius",
    eraser: "eraser-radius"
  }[state.activeMapTool];
  return inputId ? Math.max(0, Number($(`#${inputId}`)?.value || 0)) : null;
}
function renderBrushPreview() {
  const radius = activeBrushRadius();
  if (!state.brushPreview || radius === null || state.spacePanActive) return "";
  const centerKey = hexKey(state.brushPreview.q, state.brushPreview.r);
  const cells = hexArea(state.brushPreview, radius).map((cell) => {
    const { x, y } = hexPoint(cell.q, cell.r);
    const center = hexKey(cell.q, cell.r) === centerKey ? " is-center" : "";
    return `<polygon class="brush-preview-cell${center}" points="${hexPolygon(x, y, mapHexSize() - 3)}"></polygon>`;
  }).join("");
  return `<g class="brush-preview tool-${state.activeMapTool}" aria-hidden="true">${cells}</g>`;
}
function levelOverlayColor(level) {
  const ratio = Math.max(0, Math.min(1, (level - 1) / 99));
  return `hsl(${Math.round(112 - ratio * 112)} 72% 48%)`;
}
function renderLevelOverlay(cells) {
  if (!state.levelOverlayVisible && state.activeMapTool !== "level") return "";
  const visible = new Set(cells.map((cell) => hexKey(cell.q, cell.r)));
  return `<g class="level-overlay" aria-label="평균 레벨 오버레이">${(state.worldLayout.level_overrides || []).filter((entry) => visible.has(hexKey(entry.q, entry.r))).map((entry) => {
    const { x, y } = hexPoint(entry.q, entry.r); const color = levelOverlayColor(entry.average_level);
    return `<g class="level-overlay-entry"><polygon style="--level-color:${color}" points="${hexPolygon(x, y, mapHexSize() - 3)}"></polygon><text x="${x}" y="${y + 4}">${entry.average_level}</text></g>`;
  }).join("")}</g>`;
}

function renderWorldDragPreview() {
  if (state.draggedSettlement?.moved) {
    const drag = state.draggedSettlement; const node = state.worldLayout.settlements.find((entry) => entry.settlement === drag.id);
    if (!node || !drag.target) return "";
    const cells = townFootprintCells(drag.target, worldSettlementCellCount(node), worldSettlementFootprintShape(node), worldSettlementFootprintCells(node));
    const footprint = cells.map((cell) => { const point = hexPoint(cell.q, cell.r); return `<polygon points="${hexPolygon(point.x, point.y, mapHexSize() - 4)}"></polygon>`; }).join("");
    const point = hexPoint(drag.target.q, drag.target.r); const name = settlementSummary(node.settlement)?.name || node.settlement.split("/").pop();
    return `<g class="world-drag-preview settlement-preview${drag.valid ? " is-valid" : " is-invalid"}"><g class="drag-preview-footprint">${footprint}</g><g class="drag-preview-marker" transform="translate(${point.x} ${point.y})"><circle r="18"></circle><path d="M-7 5V-4L0-10L7-4V5H2V0H-2V5Z"></path><text y="31">${escapeHtml(name)}</text><text class="drag-preview-status" y="48">${drag.valid ? "여기에 놓기" : "배치 불가"}</text></g></g>`;
  }
  if (state.entranceDrag?.moved) {
    const drag = state.entranceDrag; if (!drag.target) return "";
    const list = drag.kind === "cave" ? state.worldLayout.cave_entrances : state.worldLayout.forest_entrances;
    const entrance = (list || []).find((entry) => entry.id === drag.id); if (!entrance) return "";
    const point = entranceMapPoint(entrance, drag.target); const forest = drag.kind === "forest";
    const icon = forest ? `<path d="M-6 5V-3L0-8L6-3V5H3V0H-3V5Z"></path><path class="forest-crown" d="M-9-2L-6-9L-3-2M3-2L6-9L9-2"></path>` : `<path d="M-6 5Q-5-6 0-7Q5-6 6 5ZM-2 5V1Q0-2 2 1V5Z"></path>`;
    return `<g class="world-drag-preview entrance-preview${drag.valid ? " is-valid" : " is-invalid"}" transform="translate(${point.x} ${point.y})"><circle r="12"></circle>${icon}<text class="drag-preview-status" y="28">${drag.valid ? "여기에 놓기" : "배치 불가"}</text></g>`;
  }
  if (state.routeAnchorDrag?.moved && state.routeAnchorDrag.previewAnchors) {
    const drag = state.routeAnchorDrag; const points = drag.previewAnchors.map((anchor) => { const point = hexPoint(anchor.q, anchor.r); return `${point.x},${point.y}`; }).join(" ");
    const anchor = drag.previewAnchors[drag.index]; const point = anchor ? hexPoint(anchor.q, anchor.r) : null;
    return `<g class="world-drag-preview route-preview is-valid"><polyline points="${points}"></polyline>${point ? `<g class="drag-preview-marker" transform="translate(${point.x} ${point.y})"><circle r="10"></circle><text class="drag-preview-status" y="25">여기에 놓기</text></g>` : ""}</g>`;
  }
  return "";
}

function renderHexMap() {
  const svg = $("#world-hex-map");
  svg.classList.toggle("has-selected-route", Boolean(state.selectedRouteId));
  const view = mapViewBox(); const cells = visibleHexCells();
  svg.setAttribute("viewBox", `${view.x} ${view.y} ${view.width} ${view.height}`);
  const tiles = cells.map(({ q, r }) => {
    const { x, y } = hexPoint(q, r); const tile = tileAt(q, r); const town = settlementAt(q, r); const townArea = settlementFootprintAt(q, r);
    const route = townArea ? null : primaryRouteAt(q, r);
    const selected = state.selectedHex?.q === q && state.selectedHex?.r === r; const environment = environmentOverrideAt(q, r); const leveling = levelOverrideAt(q, r);
    const emptyType = emptyTerrainAt(q, r);
    const tone = townArea ? biomeTone(townArea.town_biome || "minecraft:plains") : tile ? biomeTone(tile.biome) : emptyTerrainTone(emptyType);
    const baseLabel = town ? settlementSummary(town.settlement)?.name || "마을" : townArea ? `${settlementSummary(townArea.settlement)?.name || "마을"} 사용 범위` : tile ? tile.biome : emptyTerrainLabel(emptyType);
    const climateLabel = environment ? `, 기후 ${environment.temperature || "기본"}/${environment.humidity || "기본"}/${environment.weather || "기본"}` : "";
    const levelLabel = leveling ? `, 평균 레벨 ${leveling.average_level}` : "";
    const label = (route ? `${baseLabel}, 길 ${route.id}` : baseLabel) + climateLabel + levelLabel;
    const isEmpty = !townArea && !tile; const polygon = hexPolygon(x, y);
    return `<g class="hex-cell ${selected ? "is-selected" : ""} ${route ? "is-route-terrain" : ""} ${environment ? "has-climate-override" : ""} ${isEmpty ? `is-empty-terrain empty-type-${emptyType}` : ""} tone-${tone}" data-hex-q="${q}" data-hex-r="${r}" tabindex="0" role="button" aria-label="Q ${q}, R ${r}, ${escapeHtml(label)}"><polygon points="${polygon}"></polygon>${isEmpty ? `<path class="empty-terrain-hatch" d="M${polygon}Z"></path><text class="empty-terrain-symbol" x="${x}" y="${y + 3}">${emptyTerrainSymbol(emptyType)}</text>` : ""}${tile && !townArea ? `<circle class="biome-pin" cx="${x}" cy="${y}" r="3"></circle>` : ""}${environment ? `<path class="climate-pin" d="M${x - 7} ${y - 12}h14v5h-14z"></path>` : ""}</g>`;
  }).join("");
  const levelOverlay = renderLevelOverlay(cells);
  const townAreas = cells.map(({ q, r }) => {
    const owner = settlementFootprintAt(q, r); if (!owner) return "";
    const { x, y } = hexPoint(q, r); const name = settlementSummary(owner.settlement)?.name || owner.settlement;
    return `<g class="hex-town-area${owner.anchor.q === q && owner.anchor.r === r ? " is-anchor" : ""}"><polygon points="${hexPolygon(x, y, mapHexSize() - 4)}"></polygon><title>${escapeHtml(name)} · 마을 크기 ${worldSettlementCellCount(owner)}칸</title></g>`;
  }).join("");
  const routes = (state.worldLayout.connections || []).filter((connection) => connection.surface_style !== "water").map((connection) => {
    const cells = connectionPath(connection);
    const points = cells.map((cell, index) => { const point = routeCellMapPoint(connection, cell, index, cells); return `${point.x},${point.y}`; }).join(" ");
    if (!points) return "";
    const routeClass = connection.surface_style === "water" ? "water" : connection.access_requirement?.endsWith("/rock_climb") ? "climb" : "road";
    const selected = state.selectedRouteId === connection.id;
    return `<g class="hex-route-group${selected ? " is-selected" : ""}" data-select-route="${escapeHtml(connection.id)}" tabindex="0" role="button" aria-label="${escapeHtml(routeDisplayName(connection))} 길 선택"><polyline class="hex-route ${routeClass}" points="${points}"><title>${escapeHtml(routeDisplayName(connection))}</title></polyline><polyline class="hex-route-hit" points="${points}"></polyline></g>`;
  }).join("");
  const draftRoute = state.routeDraft?.cells?.length ? (() => {
    const points = state.routeDraft.cells.map((cell) => { const point = hexPoint(cell.q, cell.r); return `${point.x},${point.y}`; }).join(" ");
    return `<polyline class="hex-route draft" points="${points}"></polyline>`;
  })() : "";
  const routeAnchors = (() => {
    const draft = state.routeDraft;
    const selectedRoute = !draft && state.activeMapTool === "select" ? state.worldLayout.connections.find((entry) => entry.id === state.selectedRouteId) : null;
    const anchors = draft?.anchors || (selectedRoute ? connectionAnchors(selectedRoute) : []);
    const routeId = draft ? "__draft__" : selectedRoute?.id;
    if (!routeId) return "";
    const renderedAnchors = anchors.map((anchor, index) => {
      const endpoint = index === 0 || index === anchors.length - 1;
      const endpointId = index === 0 ? (draft?.from || selectedRoute?.from) : index === anchors.length - 1 ? (draft?.to || selectedRoute?.to) : null;
      const entrance = routeEndpointEntrance(endpointId);
      const { x, y } = entrance ? entranceMapPoint(entrance) : hexPoint(anchor.q, anchor.r);
      const locked = Boolean((draft && index === 0 && draft.from) || (!draft && ((index === 0 && selectedRoute?.from) || (index === anchors.length - 1 && selectedRoute?.to))));
      return `<g class="route-anchor${endpoint ? " endpoint" : ""}${locked ? " is-locked" : ""}" data-route-anchor-route="${escapeHtml(routeId)}" data-route-anchor-index="${index}" transform="translate(${x} ${y})" role="button" aria-label="길 앵커 ${index + 1}${locked ? " 연결 위치 연동" : " 이동"}"><circle r="8"></circle><circle r="3"></circle><text y="-12">${index + 1}</text></g>`;
    }).join("");
    if (draft || !selectedRoute || !anchors.length) return renderedAnchors;
    const actionAnchor = anchors[Math.floor((anchors.length - 1) / 2)]; const actionPoint = hexPoint(actionAnchor.q, actionAnchor.r);
    return `${renderedAnchors}<g class="route-anchor-actions" transform="translate(${actionPoint.x} ${actionPoint.y - 29})" data-delete-route-inline="${escapeHtml(selectedRoute.id)}" tabindex="0" role="button" aria-label="${escapeHtml(selectedRoute.id)} 길 삭제"><rect x="-25" y="-10" width="50" height="20" rx="10"></rect><text y="4">× 삭제</text></g>`;
  })();
  const towns = (state.worldLayout.settlements || []).map((node) => {
    const { x, y } = hexPoint(node.anchor.q, node.anchor.r); const name = settlementSummary(node.settlement)?.name || node.settlement.split("/").pop();
    const selected = !state.selectedEntrance && state.selectedHex?.q === node.anchor.q && state.selectedHex?.r === node.anchor.r;
    return `<g class="hex-settlement${selected ? " is-selected" : ""}${state.routeDraft?.from === node.settlement ? " is-route-origin" : ""}${state.draggedSettlement?.id === node.settlement ? " is-drag-source" : ""}" data-drag-settlement="${escapeHtml(node.settlement)}" tabindex="0" transform="translate(${x} ${y})" role="button" aria-label="${escapeHtml(name)} 선택 및 이동"><circle r="18"></circle><path d="M-7 5V-4L0-10L7-4V5H2V0H-2V5Z"></path><text y="31">${escapeHtml(name)}</text></g>`;
  }).join("");
  const objects = (state.worldLayout.objects || []).map((node) => {
    const { x, y } = hexPoint(node.anchor.q, node.anchor.r);
    if (node.type === "villain_base") {
      return `<g class="hex-custom-object villain-base-object" data-select-object="${escapeHtml(node.id)}" tabindex="0" role="button" aria-label="${escapeHtml(node.id)} 오브젝트 선택" transform="translate(${x} ${y})"><path d="M-12 8V-8H12V8ZM-7-8V-14H7V-8ZM-6 8V1H0V8ZM3-3H8V2H3Z"></path><text y="25">${escapeHtml(node.id)}</text></g>`;
    }
    if (node.type === "legendary_site") {
      return `<g class="hex-custom-object legendary-site-object" data-select-object="${escapeHtml(node.id)}" tabindex="0" role="button" aria-label="${escapeHtml(node.id)} 오브젝트 선택" transform="translate(${x} ${y})"><path d="M0-15L4-5L14-4L6 3L9 13L0 7L-9 13L-6 3L-14-4L-4-5Z"></path><circle r="4"></circle><text y="27">${escapeHtml(node.id)}</text></g>`;
    }
    const gateMode = node.properties?.gate_mode || "classic";
    if (gateMode === "system_only") {
      return `<g class="hex-custom-object gate-object system-only" data-select-object="${escapeHtml(node.id)}" tabindex="0" role="button" aria-label="${escapeHtml(node.id)} 오브젝트 선택" transform="translate(${x} ${y})"><circle r="16"></circle><path d="M0-11L9-7V0Q9 8 0 13Q-9 8-9 0V-7Z"></path><text y="28">${escapeHtml(node.id)}</text></g>`;
    }
    if (gateMode === "npc_only") {
      return `<g class="hex-custom-object gate-object npc-only" data-select-object="${escapeHtml(node.id)}" tabindex="0" role="button" aria-label="${escapeHtml(node.id)} 오브젝트 선택" transform="translate(${x} ${y})"><circle cy="-6" r="5"></circle><path d="M-8 10Q-7 0 0 0Q7 0 8 10Z"></path><text y="27">${escapeHtml(node.id)}</text></g>`;
    }
    const horizontal = ["north", "south"].includes(node.properties?.facing || "north");
    const wall = horizontal ? `<rect x="-${mapHexSize() - 7}" y="-4" width="${(mapHexSize() - 7) * 2}" height="8" rx="2"></rect>` : `<rect x="-4" y="-${mapHexSize() - 7}" width="8" height="${(mapHexSize() - 7) * 2}" rx="2"></rect>`;
    return `<g class="hex-custom-object gate-object" data-select-object="${escapeHtml(node.id)}" tabindex="0" role="button" aria-label="${escapeHtml(node.id)} 오브젝트 선택" transform="translate(${x} ${y})">${wall}<path d="M-7 7V-5L0-11L7-5V7H3V0H-3V7Z"></path><text y="25">${escapeHtml(node.id)}</text></g>`;
  }).join("");
  const entranceUnderlays = [...(state.worldLayout.cave_entrances || []), ...(state.worldLayout.forest_entrances || [])]
    .filter((node, index, entries) => entries.findIndex((entry) => entry.anchor.q === node.anchor.q && entry.anchor.r === node.anchor.r) === index)
    .map((node) => { const center = hexPoint(node.anchor.q, node.anchor.r); const marker = entranceMapPoint(node); const outer = hexPolygon(center.x, center.y, mapHexSize() - 3).split(" ").join("L"); return `<path class="entrance-underlay-hit" data-entrance-underlay-q="${node.anchor.q}" data-entrance-underlay-r="${node.anchor.r}" tabindex="0" role="button" aria-label="Q ${node.anchor.q}, R ${node.anchor.r} 입구 아래 바이옴 선택" fill-rule="evenodd" d="M${outer}ZM${marker.x - 12},${marker.y}a12,12 0 1,0 24,0a12,12 0 1,0 -24,0Z"></path>`; }).join("");
  const caveEntrances = (state.worldLayout.cave_entrances || []).map((node) => {
    const { x, y } = entranceMapPoint(node);
    const caveName = caveSummary(node.cave)?.name || node.cave.split("/").pop();
    const selected = state.selectedEntrance?.kind === "cave" && state.selectedEntrance.id === node.id;
    const centerBadge = node.pokemon_center_enabled ? `<text class="center-badge" y="-14" aria-label="포켓몬센터">P</text>` : "";
    return `<g class="hex-cave-entrance${selected ? " is-selected" : ""}${state.entranceDrag?.kind === "cave" && state.entranceDrag.id === node.id ? " is-drag-source" : ""}" data-route-cave-entrance="${escapeHtml(node.id)}" data-drag-entrance-kind="cave" data-drag-entrance-id="${escapeHtml(node.id)}" tabindex="0" transform="translate(${x} ${y})" role="button" aria-label="${escapeHtml(caveName)} ${escapeHtml(node.entrance)} 입구${node.pokemon_center_enabled ? " 포켓몬센터" : ""} 선택 및 이동"><circle class="entrance-marker-hit" r="18"></circle><circle r="10"></circle><path d="M-6 5Q-5-6 0-7Q5-6 6 5ZM-2 5V1Q0-2 2 1V5Z"></path>${centerBadge}<text y="24">${escapeHtml(caveName)} · ${escapeHtml(node.entrance)}</text></g>`;
  }).join("");
  const forestEntrances = (state.worldLayout.forest_entrances || []).map((node) => {
    const { x, y } = entranceMapPoint(node); const forestName = forestSummary(node.forest)?.name || node.forest.split("/").pop();
    const selected = state.selectedEntrance?.kind === "forest" && state.selectedEntrance.id === node.id;
    const centerBadge = node.pokemon_center_enabled ? `<text class="center-badge" y="-14" aria-label="포켓몬센터">P</text>` : "";
    return `<g class="hex-forest-entrance${selected ? " is-selected" : ""}${state.entranceDrag?.kind === "forest" && state.entranceDrag.id === node.id ? " is-drag-source" : ""}" data-route-forest-entrance="${escapeHtml(node.id)}" data-drag-entrance-kind="forest" data-drag-entrance-id="${escapeHtml(node.id)}" tabindex="0" transform="translate(${x} ${y})" role="button" aria-label="${escapeHtml(forestName)} ${escapeHtml(node.entrance)} 숲 입구${node.pokemon_center_enabled ? " 포켓몬센터" : ""} 선택 및 이동"><circle class="entrance-marker-hit" r="18"></circle><circle r="10"></circle><path d="M-6 5V-3L0-8L6-3V5H3V0H-3V5Z"></path><path class="forest-crown" d="M-9-2L-6-9L-3-2M3-2L6-9L9-2"></path>${centerBadge}<text y="24">${escapeHtml(forestName)} · 숲 입구</text></g>`;
  }).join("");
  const brushPreview = renderBrushPreview();
  const dragPreview = renderWorldDragPreview();
  svg.innerHTML = `<defs><pattern id="empty-terrain-red-hatch" width="8" height="8" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><line x1="0" y1="0" x2="0" y2="8" stroke="#d52828" stroke-width="2.2" opacity=".78"></line></pattern></defs><g class="hex-map-layer">${tiles}${levelOverlay}${townAreas}${routes}${draftRoute}${towns}${objects}${entranceUnderlays}${caveEntrances}${forestEntrances}${routeAnchors}${brushPreview}${dragPreview}</g>`;
  const routeCellCount = new Set((state.worldLayout.connections || []).flatMap((connection) => connectionPath(connection).map((cell) => `${cell.q},${cell.r}`))).size;
  const forestEntranceCount = (state.worldLayout.forest_entrances || []).length;
  $("#map-tile-count").textContent = `${cells.length}개 표시 · 바이옴 ${(state.worldLayout.tiles || []).length}개 · 길 ${routeCellCount}칸 · 기후 ${(state.worldLayout.environment_overrides || []).length}칸 · 레벨 ${(state.worldLayout.level_overrides || []).length}칸 · 마을 ${(state.worldLayout.settlements || []).length}곳 · 동굴 입구 ${(state.worldLayout.cave_entrances || []).length}곳 · 숲 입구 ${forestEntranceCount}곳`;
  $("#map-zoom").textContent = `${Math.round(state.mapZoom * 100)}%`;
  $$("[data-hex-q]").forEach((cell) => {
    const select = () => { if (!state.suppressMapClick) handleHexSelection(Number(cell.dataset.hexQ), Number(cell.dataset.hexR)); };
    cell.addEventListener("click", select);
    cell.addEventListener("keydown", (event) => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); select(); } });
  });
  $$("[data-drag-settlement]").forEach((marker) => marker.addEventListener("pointerdown", (event) => beginSettlementDrag(event, marker.dataset.dragSettlement)));
  $$("[data-drag-settlement]").forEach((marker) => marker.addEventListener("click", (event) => {
    if (!["select", "route"].includes(state.activeMapTool) || state.suppressMapClick) return;
    event.preventDefault(); event.stopPropagation();
    const node = state.worldLayout.settlements.find((entry) => entry.settlement === marker.dataset.dragSettlement);
    if (!node?.anchor) return;
    if (state.activeMapTool === "route") handleRoutePoint(node.anchor.q, node.anchor.r, node.settlement);
    else selectHex(node.anchor.q, node.anchor.r);
  }));
  $$("[data-drag-settlement]").forEach((marker) => marker.addEventListener("keydown", (event) => {
    if ((event.key !== "Enter" && event.key !== " ") || state.activeMapTool !== "select") return;
    const node = state.worldLayout.settlements.find((entry) => entry.settlement === marker.dataset.dragSettlement);
    if (node?.anchor) { event.preventDefault(); event.stopPropagation(); selectHex(node.anchor.q, node.anchor.r); }
  }));
  $$("[data-select-object]").forEach((marker) => {
    const select = (event) => {
      if (state.activeMapTool !== "select" || state.suppressMapClick) return;
      const node = (state.worldLayout.objects || []).find((entry) => entry.id === marker.dataset.selectObject);
      if (!node?.anchor) return;
      event.preventDefault(); event.stopPropagation(); selectHex(node.anchor.q, node.anchor.r);
    };
    marker.addEventListener("click", select);
    marker.addEventListener("keydown", (event) => { if (event.key === "Enter" || event.key === " ") select(event); });
  });
  $$("[data-route-cave-entrance]").forEach((marker) => marker.addEventListener("click", (event) => {
    const entrance = (state.worldLayout.cave_entrances || []).find((entry) => entry.id === marker.dataset.routeCaveEntrance);
    if (!entrance) return;
    event.preventDefault(); event.stopPropagation();
    if (state.activeMapTool === "route") handleRoutePoint(entrance.anchor.q, entrance.anchor.r, entrance.id);
    else if (state.activeMapTool === "select" && !state.suppressMapClick) selectWorldEntrance("cave", entrance.id);
  }));
  $$('[data-route-forest-entrance]').forEach((marker) => marker.addEventListener("click", (event) => {
    const entrance = (state.worldLayout.forest_entrances || []).find((entry) => entry.id === marker.dataset.routeForestEntrance);
    if (!entrance) return;
    event.preventDefault(); event.stopPropagation();
    if (state.activeMapTool === "route") handleRoutePoint(entrance.anchor.q, entrance.anchor.r, entrance.id);
    else if (state.activeMapTool === "select" && !state.suppressMapClick) selectWorldEntrance("forest", entrance.id);
  }));
  $$('[data-drag-entrance-id]').forEach((marker) => marker.addEventListener("pointerdown", (event) => beginEntranceDrag(event, marker.dataset.dragEntranceKind, marker.dataset.dragEntranceId)));
  $$('[data-drag-entrance-id]').forEach((marker) => marker.addEventListener("keydown", (event) => {
    if ((event.key !== "Enter" && event.key !== " ") || state.activeMapTool !== "select") return;
    event.preventDefault(); event.stopPropagation(); selectWorldEntrance(marker.dataset.dragEntranceKind, marker.dataset.dragEntranceId);
  }));
  $$('[data-entrance-underlay-q]').forEach((underlay) => {
    const selectUnderlay = (event) => { if (state.activeMapTool !== "select") return; event.preventDefault(); event.stopPropagation(); selectHex(Number(underlay.dataset.entranceUnderlayQ), Number(underlay.dataset.entranceUnderlayR)); };
    underlay.addEventListener("click", selectUnderlay);
    underlay.addEventListener("keydown", (event) => { if (event.key === "Enter" || event.key === " ") selectUnderlay(event); });
  });
  $$("[data-select-route]").forEach((route) => {
    const select = (event) => {
      if (!new Set(["select", "route"]).has(state.activeMapTool)) return;
      event.preventDefault(); event.stopPropagation();
      if (state.activeMapTool === "route") {
        const cell = nearestHexFromPointer(event); handleRoutePoint(cell.q, cell.r); return;
      }
      if (state.activeMapTool === "select" && event.type === "click" && state.selectedRouteId === route.dataset.selectRoute) {
        const cell = nearestHexFromPointer(event); selectHex(cell.q, cell.r); return;
      }
      focusRoute(route.dataset.selectRoute, false);
    };
    route.addEventListener("click", select);
    route.addEventListener("keydown", (event) => { if ((event.key === "Enter" || event.key === " ") && state.activeMapTool === "select") select(event); });
  });
  $$("[data-route-anchor-index]").forEach((anchor) => anchor.addEventListener("pointerdown", (event) => beginRouteAnchorDrag(event, anchor.dataset.routeAnchorRoute, Number(anchor.dataset.routeAnchorIndex), anchor.classList.contains("is-locked"))));
  $$("[data-delete-route-inline]").forEach((button) => {
    button.addEventListener("pointerdown", (event) => { event.preventDefault(); event.stopPropagation(); });
    button.addEventListener("click", (event) => { event.preventDefault(); event.stopPropagation(); removeRouteConnection(button.dataset.deleteRouteInline); });
    button.addEventListener("keydown", (event) => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); removeRouteConnection(button.dataset.deleteRouteInline); } });
  });
}

async function saveWorldLayout() {
  if (!state.worldLayout) return;
  for (const node of state.worldLayout.settlements || []) {
    node.town_radius_cells = settlementPresetRadius(node.settlement);
    node.town_footprint_shape = settlementPresetFootprintShape(node.settlement);
    node.town_footprint_cells = settlementPresetFootprintCells(node.settlement);
    node.town_road_exits = settlementPresetRoadExits(node.settlement);
  }
  syncConnectionPaths();
  state.worldLayout.schema_version = 2;
  state.worldLayout.grid.tile_radius_blocks = Number($("#tile-radius-blocks").value || 64);
  const result = await request(`/api/world-layout?generation=${state.selectedGeneration}`, { method: "PUT", body: JSON.stringify(state.worldLayout) });
  showIssues("#world-layout-issues", result.data);
  if (!result.ok) { toast(result.data.error || "월드 검증 오류로 저장하지 않았습니다."); return; }
  toast(`${state.selectedGeneration}세대 육각 월드를 저장했습니다.`);
  state.worldDirty = false;
  const reloaded = await request(`/api/world-layout?generation=${state.selectedGeneration}`);
  if (reloaded.ok) state.worldLayout = reloaded.data;
  const pokemonMap = await request(`/api/world-pokemon-map?generation=${state.selectedGeneration}`);
  if (pokemonMap.ok) state.worldPokemonMap = pokemonMap.data;
  renderWorldLayout();
}

function selectHex(q, r) { state.selectedRouteId = null; state.selectedEntrance = null; state.selectedHex = { q, r }; renderHexMap(); renderTileInspector(); }
function selectWorldEntrance(kind, id) {
  const list = kind === "cave" ? state.worldLayout.cave_entrances : state.worldLayout.forest_entrances;
  const entrance = (list || []).find((entry) => entry.id === id); if (!entrance) return;
  state.selectedRouteId = null; state.selectedHex = { ...entrance.anchor }; state.selectedEntrance = { kind, id };
  renderHexMap(); renderTileInspector();
}
function handleHexSelection(q, r) {
  const tool = state.activeMapTool;
  if (tool === "biome") paintBiomeArea(q, r);
  else if (tool === "terrain") paintEmptyTerrainArea(q, r);
  else if (tool === "climate") paintClimateArea(q, r);
  else if (tool === "level") paintLevelArea(q, r);
  else if (tool === "route") handleRoutePoint(q, r);
  else if (tool === "settlement") placeSettlementWithTool(q, r);
  else if (tool === "entrance") {
    if ($("#world-entrance-kind").value === "forest") placeForestEntranceWithTool(q, r);
    else placeCaveEntranceWithTool(q, r);
  }
  else if (tool === "object") placeObjectWithTool(q, r);
  else if (tool === "eraser") eraseMapArea(q, r);
  else selectHex(q, r);
}
function hexArea(center, radius) {
  const cells = [];
  for (let dq = -radius; dq <= radius; dq++) {
    for (let dr = Math.max(-radius, -dq - radius); dr <= Math.min(radius, -dq + radius); dr++) cells.push({ q: center.q + dq, r: center.r + dr });
  }
  return cells;
}
function setEmptyTerrainTile(q, r, type) {
  const config = state.worldLayout.empty_terrain ||= { default_type: "high_forest", tiles: [] };
  config.tiles = (config.tiles || []).filter((tile) => tile.q !== q || tile.r !== r);
  if (type !== config.default_type) config.tiles.push({ q, r, type });
}
function paintBiomeArea(q, r) {
  const biome = $("#biome-brush-type").value;
  const radius = Number($("#biome-brush-radius").value || 0);
  for (const cell of hexArea({ q, r }, radius)) {
    if (settlementFootprintAt(cell.q, cell.r)) continue;
    state.worldLayout.empty_terrain.tiles = state.worldLayout.empty_terrain.tiles.filter((entry) => entry.q !== cell.q || entry.r !== cell.r);
    const current = tileAt(cell.q, cell.r);
    if (current) current.biome = biome;
    else state.worldLayout.tiles.push(defaultWorldTile(cell.q, cell.r, biome));
  }
  state.selectedHex = { q, r }; markWorldDirty(); renderWorldLayout();
}
function paintEmptyTerrainArea(q, r) {
  const type = $("#empty-terrain-brush-type").value;
  const radius = Math.max(0, Math.min(5, Number($("#empty-terrain-brush-radius").value || 0)));
  for (const cell of hexArea({ q, r }, radius)) {
    if (settlementFootprintAt(cell.q, cell.r) || routesAt(cell.q, cell.r).length) continue;
    state.worldLayout.tiles = state.worldLayout.tiles.filter((tile) => tile.q !== cell.q || tile.r !== cell.r);
    setEmptyTerrainTile(cell.q, cell.r, type);
  }
  state.selectedHex = { q, r }; markWorldDirty(); renderWorldLayout();
}
function paintClimateArea(q, r) {
  const values = {
    temperature: $("#climate-temperature").value,
    humidity: $("#climate-humidity").value,
    weather: $("#climate-weather").value
  };
  const radius = Number($("#climate-brush-radius").value || 0);
  for (const cell of hexArea({ q, r }, radius)) {
    state.worldLayout.environment_overrides = state.worldLayout.environment_overrides.filter((entry) => entry.q !== cell.q || entry.r !== cell.r);
    const override = { q: cell.q, r: cell.r };
    for (const [key, value] of Object.entries(values)) if (value !== "any") override[key] = value;
    if (Object.keys(override).length > 2) state.worldLayout.environment_overrides.push(override);
  }
  state.selectedHex = { q, r }; markWorldDirty(); renderWorldLayout();
}
function paintLevelArea(q, r) {
  const averageLevel = Math.max(1, Math.min(100, Math.round(Number($("#level-brush-average").value || 1))));
  const radius = Number($("#level-brush-radius").value || 0);
  for (const cell of hexArea({ q, r }, radius)) {
    state.worldLayout.level_overrides = state.worldLayout.level_overrides.filter((entry) => entry.q !== cell.q || entry.r !== cell.r);
    state.worldLayout.level_overrides.push({ q: cell.q, r: cell.r, average_level: averageLevel });
  }
  state.selectedHex = { q, r }; markWorldDirty(); renderWorldLayout();
}
function eraseMapArea(q, r) {
  const target = $("#eraser-target").value; const radius = Number($("#eraser-radius").value || 0);
  const keys = new Set(hexArea({ q, r }, radius).map((cell) => hexKey(cell.q, cell.r)));
  if (target === "route" || target === "all") state.worldLayout.connections = state.worldLayout.connections.filter((route) => !connectionPath(route).some((cell) => keys.has(hexKey(cell.q, cell.r))));
  if (target === "biome" || target === "all") state.worldLayout.tiles = state.worldLayout.tiles.filter((tile) => !keys.has(hexKey(tile.q, tile.r)));
  if (target === "climate" || target === "all") state.worldLayout.environment_overrides = state.worldLayout.environment_overrides.filter((entry) => !keys.has(hexKey(entry.q, entry.r)));
  if (target === "level" || target === "all") state.worldLayout.level_overrides = state.worldLayout.level_overrides.filter((entry) => !keys.has(hexKey(entry.q, entry.r)));
  if (target === "terrain" || target === "all") state.worldLayout.empty_terrain.tiles = state.worldLayout.empty_terrain.tiles.filter((tile) => !keys.has(hexKey(tile.q, tile.r)));
  if (target === "object" || target === "all") state.worldLayout.objects = state.worldLayout.objects.filter((object) => !keys.has(hexKey(object.anchor.q, object.anchor.r)));
  if (target === "all") state.worldLayout.cave_entrances = state.worldLayout.cave_entrances.filter((entrance) => !keys.has(hexKey(entrance.anchor.q, entrance.anchor.r)));
  if (target === "all") state.worldLayout.forest_entrances = state.worldLayout.forest_entrances.filter((entrance) => !keys.has(hexKey(entrance.anchor.q, entrance.anchor.r)));
  state.selectedHex = { q, r }; markWorldDirty(); renderWorldLayout();
}
function placeSettlementWithTool(q, r) {
  const id = $("#settlement-tool-preset").value;
  if (!id) { toast("왼쪽 도구 옵션에서 배치할 마을을 선택해 주세요."); return; }
  let node = state.worldLayout.settlements.find((entry) => entry.settlement === id);
  const occupied = settlementAt(q, r); const radius = settlementPresetRadius(id);
  if (occupied && occupied !== node) { toast("이미 다른 마을이 배치된 타일입니다."); return; }
  const conflict = settlementRangeConflict(node, q, r, radius);
  if (conflict) { toast(`${settlementSummary(conflict.settlement)?.name || "다른 마을"}의 사용 범위와 겹칩니다.`); return; }
  if (!node) { node = defaultWorldSettlement(id, q, r, $("#settlement-tool-biome").value); state.worldLayout.settlements.push(node); }
  node.anchor = { q, r }; node.town_radius_cells = radius; node.town_footprint_shape = settlementPresetFootprintShape(id); node.town_footprint_cells = settlementPresetFootprintCells(id); node.town_road_exits = settlementPresetRoadExits(id);
  state.selectedHex = { q, r }; markWorldDirty(); renderWorldLayout();
}
function caveToolEntranceOptions() {
  const caveId = $("#cave-tool-cave").value;
  const cave = caveSummary(caveId);
  const placed = new Set((state.worldLayout.cave_entrances || []).filter((item) => item.cave === caveId).map((item) => item.entrance));
  return (cave?.entrances || []).map((entry) => `<option value="${escapeHtml(entry.id)}" ${placed.has(entry.id) ? "disabled" : ""}>${escapeHtml(entry.display_name || entry.id)}${placed.has(entry.id) ? " · 배치됨" : ""}</option>`).join("");
}
function refreshCaveToolEntrances() { $("#cave-tool-entrance").innerHTML = caveToolEntranceOptions(); }
function placeCaveEntranceWithTool(q, r) {
  const caveId = $("#cave-tool-cave").value; const entranceId = $("#cave-tool-entrance").value;
  if (!caveId || !entranceId) { toast("배치할 동굴과 미배치 내부 입구를 선택해 주세요."); return; }
  if (caveEntranceAt(q, r) || settlementAt(q, r)) { toast("이미 마을 또는 동굴 입구가 배치된 타일입니다."); return; }
  if ((state.worldLayout.cave_entrances || []).some((entry) => entry.cave === caveId && entry.entrance === entranceId)) { toast("같은 내부 입구가 이미 배치되어 있습니다."); return; }
  const slug = caveId.split("/").pop();
  state.worldLayout.cave_entrances.push({
    id: `cobbleventure:cave_entrance/${slug}_${entranceId}`, cave: caveId, entrance: entranceId,
    anchor: { q, r }, facing: $("#cave-tool-facing").value,
    structure: defaultWorldEntranceStructures.cave,
    structure_variants: { ...defaultWorldEntranceStructures.caveVariants },
    pokemon_center_enabled: $("#world-entrance-pokemon-center").value === "true"
  });
  state.selectedHex = { q, r }; state.selectedEntrance = { kind: "cave", id: `cobbleventure:cave_entrance/${slug}_${entranceId}` }; markWorldDirty(); refreshCaveToolEntrances(); renderWorldLayout(); toast("동굴 출입구를 배치했습니다. 길 도구로 출입구까지 연결해 주세요.");
}
function forestToolEntranceOptions() {
  const forestId = $("#forest-tool-forest").value;
  const forest = forestSummary(forestId);
  const placed = new Set((state.worldLayout?.forest_entrances || []).filter((item) => item.forest === forestId).map((item) => item.entrance));
  return (forest?.entrances || []).map((entry) => `<option value="${escapeHtml(entry.id)}" ${placed.has(entry.id) ? "disabled" : ""}>${escapeHtml(entry.display_name || entry.id)}${placed.has(entry.id) ? " · 배치됨" : ""}</option>`).join("");
}
function refreshForestToolEntrances() { $("#forest-tool-entrance").innerHTML = forestToolEntranceOptions(); }
const forestFacingOffsets = { north: { q: 0, r: -1 }, east: { q: 1, r: 0 }, south: { q: 0, r: 1 }, west: { q: -1, r: 0 } };
function forestGateRotation(direction) { return ({ north: 0, east: 1, south: 2, west: 3 })[direction] ?? 0; }
function placeForestEntranceWithTool(q, r) {
  const forestId = $("#forest-tool-forest").value; const entranceId = $("#forest-tool-entrance").value;
  const direction = $("#forest-tool-facing").value;
  if (!forestId || !entranceId) { toast("배치할 숲과 미배치 내부 입구를 선택해 주세요."); return; }
  if (settlementAt(q, r) || caveEntranceAt(q, r) || forestEntranceAt(q, r) || (state.worldLayout.objects || []).some((node) => node.anchor?.q === q && node.anchor?.r === r)) { toast("이미 마을, 입구 또는 오브젝트가 배치된 타일입니다."); return; }
  if ((state.worldLayout.forest_entrances || []).some((entry) => entry.forest === forestId && entry.entrance === entranceId)) { toast("같은 숲 내부 입구가 이미 배치되어 있습니다."); return; }
  const slug = `${forestId.split("/").pop()}_${entranceId}`.replace(/[^a-z0-9_.-]+/g, "_");
  const entrance = { id: `forest_entrance_${slug}`, forest: forestId, entrance: entranceId, anchor: { q, r }, facing: direction,
    pokemon_center_enabled: $("#world-entrance-pokemon-center").value === "true",
    structure: defaultWorldEntranceStructures.forest, rotation: forestGateRotation(direction), tree_log: "minecraft:spruce_log", tree_leaves: "minecraft:spruce_leaves",
    wall_thickness: 7, wall_height: 14, opening_width: 7, barrier_height: 32 };
  state.worldLayout.forest_entrances.push(entrance);
  const offset = forestFacingOffsets[direction] || forestFacingOffsets.east; const dense = { q: q + offset.q, r: r + offset.r };
  if (!settlementFootprintAt(dense.q, dense.r) && !routesAt(dense.q, dense.r).length) {
    state.worldLayout.tiles = state.worldLayout.tiles.filter((tile) => tile.q !== dense.q || tile.r !== dense.r);
    setEmptyTerrainTile(dense.q, dense.r, "dense_forest");
  }
  state.selectedHex = { q, r }; state.selectedEntrance = { kind: "forest", id: entrance.id }; markWorldDirty(); refreshForestToolEntrances(); renderWorldLayout(); toast("독립 숲 입구와 입구 뒤 우거진 숲을 배치했습니다. 길 도구로 입구까지 연결해 주세요.");
}
function parseGateConditions(source) {
  return String(source || "").split(/\r?\n/).map((line) => line.trim()).filter(Boolean).map((line, index) => {
    const parts = line.split("|").map((part) => part.trim()); const type = parts[0];
    if (type === "variable" && parts.length === 5 && ["scoreboard", "persistent_data"].includes(parts[1]) && ["==", "!=", ">", ">=", "<", "<="].includes(parts[3]) && Number.isFinite(Number(parts[4]))) return { type, source: parts[1], key: parts[2], operator: parts[3], value: Number(parts[4]) };
    if (type === "item" && parts.length === 3 && /^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(parts[1]) && Number(parts[2]) >= 1) return { type, item: parts[1], count: Math.floor(Number(parts[2])) };
    if (type === "pokemon" && parts.length === 2 && /^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(parts[1])) return { type, species: parts[1] };
    throw new Error(`${index + 1}번째 관문 조건 형식이 올바르지 않습니다: ${line}`);
  });
}
function formatGateConditions(conditions) {
  return (conditions || []).map((condition) => condition.type === "variable" ? `variable|${condition.source}|${condition.key}|${condition.operator}|${condition.value}` : condition.type === "item" ? `item|${condition.item}|${condition.count || 1}` : `pokemon|${condition.species}`).join("\n");
}
function normalizedOdd(value, minimum, maximum) {
  let number = Math.max(minimum, Math.min(maximum, Math.round(Number(value) || minimum)));
  if (number % 2 === 0) number = Math.min(maximum, number + 1);
  return number;
}
function gateProperties(values) {
  const gateMode = values.gateMode || "classic";
  if (gateMode === "npc_only" && !values.npc.trim()) throw new Error("NPC 전용 관문에는 NPC 프리셋을 지정해 주세요.");
  const conditions = gateMode === "npc_only" ? [] : parseGateConditions(values.conditions);
  if (gateMode === "system_only" && !conditions.length) throw new Error("시스템 차단 관문에는 통과 조건을 하나 이상 지정해 주세요.");
  const wallHeight = Math.max(3, Math.min(32, Math.round(Number(values.wallHeight) || 7)));
  const properties = {
    facing: values.facing, gate_mode: gateMode, building_enabled: gateMode === "classic",
    surrounding_type: gateMode === "classic" ? values.surroundingType : "none", wall_block: values.wallBlock.trim(),
    tree_log: values.treeLog.trim() || "minecraft:oak_log",
    tree_leaves: values.treeLeaves.trim() || "minecraft:oak_leaves",
    wall_thickness: normalizedOdd(values.wallThickness, 1, 15),
    wall_height: wallHeight, opening_width: normalizedOdd(values.openingWidth, 3, 31),
    barrier_height: Math.max(wallHeight + 1, Math.min(128, Math.round(Number(values.barrierHeight) || 24))),
    condition_mode: values.conditionMode, conditions,
    deny_message: values.denyMessage.trim() || (gateMode === "system_only" ? "조건을 달성하지 않아 이 지역에 들어갈 수 없습니다." : "아직 이 관문을 통과할 수 없습니다.")
  };
  if (gateMode !== "system_only" && values.npc.trim()) properties.npc = values.npc.trim();
  return properties;
}
function placeObjectWithTool(q, r) {
  const id = $("#object-tool-id").value.trim(); const type = $("#object-tool-type").value.trim(); const resource = $("#object-tool-resource").value.trim();
  if (!/^[a-z0-9_.-]+$/.test(id) || !/^[a-z0-9_.-]+$/.test(type)) { toast("오브젝트 ID와 타입을 영문 소문자 형식으로 입력해 주세요."); return; }
  if (reservedWorldObjectTypes.has(type)) {
    if (!/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(resource)) { toast(`${reservedWorldObjectTypes.get(type)} NBT 리소스를 선택해 주세요.`); return; }
    if (state.worldLayout.objects.some((entry) => entry.id === id && (entry.anchor.q !== q || entry.anchor.r !== r))) { toast("이미 사용 중인 오브젝트 ID입니다."); return; }
    state.worldLayout.objects = state.worldLayout.objects.filter((entry) => entry.anchor.q !== q || entry.anchor.r !== r);
    state.worldLayout.objects.push({ id, type, anchor: { q, r }, resource, rotation: Number($("#object-tool-rotation").value) });
    state.selectedHex = { q, r }; markWorldDirty(); renderWorldLayout(); return;
  }
  const gateMode = $("#object-tool-gate-mode").value;
  const buildingEnabled = gateMode === "classic";
  if (buildingEnabled && !/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(resource)) { toast("관문 건물 NBT 리소스 ID를 입력해 주세요."); return; }
  if (state.worldLayout.objects.some((entry) => entry.id === id && (entry.anchor.q !== q || entry.anchor.r !== r))) { toast("이미 사용 중인 오브젝트 ID입니다."); return; }
  state.worldLayout.objects = state.worldLayout.objects.filter((entry) => entry.anchor.q !== q || entry.anchor.r !== r);
  let properties; try { properties = gateProperties({ facing: $("#object-tool-facing").value, gateMode, surroundingType: $("#object-tool-surrounding-type").value, wallBlock: $("#object-tool-wall-block").value, treeLog: $("#object-tool-tree-log").value, treeLeaves: $("#object-tool-tree-leaves").value, wallThickness: $("#object-tool-wall-thickness").value, wallHeight: $("#object-tool-wall-height").value, openingWidth: $("#object-tool-opening-width").value, barrierHeight: $("#object-tool-barrier-height").value, conditionMode: $("#object-tool-condition-mode").value, conditions: $("#object-tool-conditions").value, denyMessage: $("#object-tool-deny-message").value, npc: $("#object-tool-npc").value }); } catch (error) { toast(error.message); return; }
  const object = { id, type, anchor: { q, r }, rotation: Number($("#object-tool-rotation").value), properties };
  if (buildingEnabled) object.resource = resource;
  state.worldLayout.objects.push(object); state.selectedHex = { q, r }; markWorldDirty(); renderWorldLayout();
}
function markWorldDirty() { state.worldDirty = true; updateWorldSaveState(); }
function updateWorldSaveState() { $("#world-save-state").textContent = state.worldDirty ? "저장하지 않은 변경" : "저장된 상태"; $("#world-save-state").classList.toggle("is-dirty", state.worldDirty); }

function worldBiomeOptions(selected = "") {
  const common = ["minecraft:plains", "minecraft:forest", "minecraft:flower_forest", "minecraft:river", "minecraft:beach", "minecraft:ocean", "minecraft:deep_ocean", "minecraft:warm_ocean", "minecraft:desert", "minecraft:savanna", "minecraft:badlands", "minecraft:windswept_hills", "minecraft:stony_peaks", "minecraft:snowy_plains"];
  const current = [...(state.worldLayout?.tiles || []).map((tile) => tile.biome), ...(state.worldLayout?.settlements || []).flatMap((node) => [node.town_biome, ...(node.surroundings || []).map((region) => region.biome)])];
  return [...new Set([...common, ...current, selected].filter(Boolean))].map((biome) => `<option value="${escapeHtml(biome)}" ${biome === selected ? "selected" : ""}>${escapeHtml(biome.replace("minecraft:", ""))}</option>`).join("");
}

function renderWorldObjectNbtOptions() {
  $("#world-object-nbt-options").innerHTML = Object.keys(state.structureSizes || {}).sort()
    .map((resource) => `<option value="${escapeHtml(resource)}"></option>`).join("");
}

function ensureWorldObjectTypeOptions() {
  [$("#object-tool-type"), $("#tile-inspector-form").elements.objectType].forEach((select) => {
    for (const [value, label] of reservedWorldObjectTypes) {
      if (!select.querySelector(`option[value="${value}"]`)) select.insertAdjacentHTML("beforeend", `<option value="${value}">${label}</option>`);
    }
  });
}

function updateGateOptionVisibility() {
  const toolPanel = $('[data-tool-options="object"]');
  const toolIsGate = $("#object-tool-type").value === "gate";
  const toolMode = $("#object-tool-gate-mode").value;
  const toolBuilding = toolMode === "classic";
  const toolSurrounding = $("#object-tool-surrounding-type").value;
  toolPanel.querySelector("[data-gate-mode]").hidden = !toolIsGate;
  $("#object-tool-building-enabled").closest("label").hidden = true;
  ["object-tool-surrounding-type", "object-tool-wall-block", "object-tool-tree-log", "object-tool-tree-leaves", "object-tool-wall-thickness", "object-tool-opening-width", "object-tool-wall-height", "object-tool-barrier-height"].forEach((id) => { $(`#${id}`).closest("label").hidden = !toolIsGate || toolMode !== "classic"; });
  ["object-tool-facing", "object-tool-rotation"].forEach((id) => { $(`#${id}`).closest("label").hidden = !toolIsGate || toolMode === "system_only"; });
  $("#object-tool-npc").closest("label").hidden = !toolIsGate || toolMode === "system_only";
  ["object-tool-condition-mode", "object-tool-conditions", "object-tool-deny-message"].forEach((id) => { $(`#${id}`).closest("label").hidden = !toolIsGate || toolMode === "npc_only"; });
  $("#edit-object-npc").hidden = !toolIsGate || toolMode === "system_only";
  toolPanel.querySelector("[data-object-resource]").hidden = toolIsGate && !toolBuilding;
  toolPanel.querySelectorAll("[data-gate-wall]").forEach((field) => { field.hidden = !toolIsGate || toolSurrounding !== "wall"; });
  toolPanel.querySelectorAll("[data-gate-trees]").forEach((field) => { field.hidden = !toolIsGate || toolSurrounding !== "trees"; });
  toolPanel.querySelectorAll("[data-gate-surrounding]").forEach((field) => { field.hidden = !toolIsGate || toolSurrounding === "none"; });

  const form = $("#tile-inspector-form");
  const objectFields = form.querySelector('section[data-tile-field="object"]');
  const inspectorIsGate = form.elements.objectType.value === "gate";
  const inspectorMode = form.elements.objectGateMode.value;
  const inspectorBuilding = inspectorMode === "classic";
  const inspectorSurrounding = form.elements.objectSurroundingType.value;
  form.querySelector("[data-gate-mode]").hidden = !inspectorIsGate;
  form.elements.objectBuildingEnabled.closest("label").hidden = true;
  ["objectSurroundingType", "objectWallBlock", "objectTreeLog", "objectTreeLeaves", "objectWallThickness", "objectOpeningWidth", "objectWallHeight", "objectBarrierHeight"].forEach((name) => { form.elements[name].closest("label").hidden = !inspectorIsGate || inspectorMode !== "classic"; });
  ["objectFacing", "objectRotation"].forEach((name) => { form.elements[name].closest("label").hidden = !inspectorIsGate || inspectorMode === "system_only"; });
  form.elements.objectNpc.closest("label").hidden = !inspectorIsGate || inspectorMode === "system_only";
  ["objectConditionMode", "objectConditions", "objectDenyMessage"].forEach((name) => { form.elements[name].closest("label").hidden = !inspectorIsGate || inspectorMode === "npc_only"; });
  objectFields.querySelector("[data-object-resource]").hidden = inspectorIsGate && !inspectorBuilding;
  objectFields.querySelectorAll("[data-gate-wall]").forEach((field) => { field.hidden = !inspectorIsGate || inspectorSurrounding !== "wall"; });
  objectFields.querySelectorAll("[data-gate-trees]").forEach((field) => { field.hidden = !inspectorIsGate || inspectorSurrounding !== "trees"; });
  objectFields.querySelectorAll("[data-gate-surrounding]").forEach((field) => { field.hidden = !inspectorIsGate || inspectorSurrounding === "none"; });
}

function selectedRoute() {
  return (state.worldLayout?.connections || []).find((entry) => entry.id === state.selectedRouteId) || null;
}
function routeDisplayName(route) { return route?.display_name || route?.id || "길"; }
function ensureRoutePokemonSettings(route) {
  route.pokemon_spawns ||= { inherit_biome: true, excluded_species: [], additions: [] };
  route.pokemon_spawns.inherit_biome = route.pokemon_spawns.inherit_biome !== false;
  route.pokemon_spawns.excluded_species ||= [];
  route.pokemon_spawns.additions ||= [];
  route.pokemon_spawns.level_overrides ||= [];
  return route.pokemon_spawns;
}
function worldPokemonCatalog() {
  const entries = [...(state.worldPokemonMap?.available_pokemon || []), ...(state.worldPokemonMap?.unavailable_pokemon || [])];
  return [...new Map(entries.map((entry) => [entry.id, entry])).values()].sort((a, b) => Number(a.dex_number || 99999) - Number(b.dex_number || 99999));
}
function worldPokemonById() { return new Map(worldPokemonCatalog().map((entry) => [entry.id, entry])); }
function ensureEncounterSettings(document, defaultBiome = "minecraft:plains", defaultMinLevel = 1, defaultMaxLevel = 10) {
  document.random_encounters ||= {};
  const settings = document.random_encounters;
  settings.enabled = settings.enabled !== false;
  settings.minimum_distance = Math.max(1, Number(settings.minimum_distance || 72));
  settings.maximum_distance = Math.max(settings.minimum_distance, Number(settings.maximum_distance || 128));
  settings.minimum_level = Math.max(1, Number(settings.minimum_level || defaultMinLevel));
  settings.maximum_level = Math.max(settings.minimum_level, Number(settings.maximum_level || defaultMaxLevel));
  settings.pokemon_biome ||= defaultBiome;
  settings.inherit_biome = settings.inherit_biome !== false;
  settings.excluded_species ||= [];
  settings.additions ||= [];
  settings.level_overrides ||= [];
  delete settings.spawn_profile; delete settings.density_multiplier;
  return settings;
}
function encounterDocument(target = state.encounterPokemonTarget || $("#encounter-pokemon-dialog")?.dataset.target) { return target === "cave" ? state.cave : target === "forest" ? state.forest : null; }
function encounterSettings(target = state.encounterPokemonTarget || $("#encounter-pokemon-dialog")?.dataset.target) {
  const document = encounterDocument(target); if (!document) return null;
  return ensureEncounterSettings(document, target === "cave" ? "minecraft:dripstone_caves" : "minecraft:old_growth_spruce_taiga", target === "cave" ? 5 : 3, target === "cave" ? 10 : 7);
}
function encounterBiomeOptions(select, selected) {
  const entries = [];
  for (const profile of state.biomeCatalog.profiles || []) for (const biome of profile.minecraft_biomes || []) entries.push([biome, profile.display_name?.ko_kr || profile.id]);
  const unique = [...new Map(entries.map(([id, label]) => [id, label])).entries()].sort((a, b) => a[1].localeCompare(b[1], "ko"));
  if (selected && !unique.some(([id]) => id === selected)) unique.unshift([selected, selected]);
  select.innerHTML = unique.map(([id, label]) => `<option value="${escapeHtml(id)}">${escapeHtml(label)} · ${escapeHtml(id)}</option>`).join("");
  select.value = selected;
}
function encounterBasePokemonIds(settings) {
  if (!settings) return [];
  const profiles = (state.biomeCatalog.profiles || []).filter((profile) => (profile.minecraft_biomes || []).includes(settings.pokemon_biome));
  const habitats = new Set(profiles.map((profile) => profile.habitat).filter(Boolean));
  return worldPokemonCatalog().filter((entry) => habitats.has(entry.habitats?.primary) || (profiles.some((profile) => profile.settings?.include_secondary !== false) && habitats.has(entry.habitats?.secondary))).map((entry) => entry.id);
}
function renderEncounterSummary(target) {
  const settings = encounterSettings(target); if (!settings) return;
  const count = (settings.inherit_biome ? encounterBasePokemonIds(settings).filter((id) => !settings.excluded_species.includes(id)).length : 0) + settings.additions.filter((addition) => !encounterBasePokemonIds(settings).includes(addition.species) || settings.excluded_species.includes(addition.species)).length;
  $(`[data-encounter-pokemon-count="${target}"]`).textContent = `${count}종`;
  $(`[data-encounter-pokemon-description="${target}"]`).textContent = `${settings.pokemon_biome} 기본 ${settings.inherit_biome ? "사용" : "미사용"} · 직접 추가 ${settings.additions.length}종 · 제외 ${settings.excluded_species.length}종`;
}
function renderEncounterPokemonPicker() {
  const catalog = worldPokemonCatalog(), picker = state.encounterPokemonPicker;
  const pickerList = $("#encounter-pokemon-picker-list"), pickerScrollTop = pickerList.scrollTop;
  const unique = (values) => [...new Set(values.filter(Boolean).map(String))].sort((left, right) => left.localeCompare(right, "ko", { numeric: true }));
  routePokemonFilterOptions("#encounter-pokemon-picker-generation", "모든 세대", unique(catalog.map((entry) => entry.generation)), (value) => `${value}세대`);
  routePokemonFilterOptions("#encounter-pokemon-picker-type", "모든 타입", unique(catalog.flatMap((entry) => entry.types || [])), (value) => pokemonTypeLabels[value] || value);
  routePokemonFilterOptions("#encounter-pokemon-picker-habitat", "모든 서식지", unique(catalog.map((entry) => entry.habitats?.primary)), (value) => pokemonHabitatLabels[value] || value);
  routePokemonFilterOptions("#encounter-pokemon-picker-rarity", "모든 희귀도", unique(catalog.map((entry) => entry.preferences?.rarity)), (value) => pokemonRarityLabels[value] || value);
  $("#encounter-pokemon-picker-generation").value = picker.generation;
  $("#encounter-pokemon-picker-type").value = picker.type;
  $("#encounter-pokemon-picker-habitat").value = picker.habitat;
  $("#encounter-pokemon-picker-rarity").value = picker.rarity;
  $("#encounter-pokemon-picker-special").value = picker.special;
  $("#encounter-pokemon-picker-availability").value = picker.availability;
  $("#encounter-pokemon-picker-search").value = picker.query;
  const settings = encounterSettings(), added = new Set((settings?.additions || []).map((entry) => entry.species));
  const matches = filteredEncounterPokemonPickerEntries(), visible = matches.slice(0, 180);
  $("#encounter-pokemon-picker-result").textContent = `${matches.length.toLocaleString()}종${matches.length > visible.length ? ` · 앞 ${visible.length}종 표시` : ""}`;
  pickerList.innerHTML = visible.length ? visible.map((entry) => {
    const isAdded = added.has(entry.id), selected = picker.selected.has(entry.id);
    return `<button type="button" class="route-pokemon-picker-card${selected ? " is-selected" : ""}${isAdded ? " is-added" : ""}" data-encounter-picker-species="${escapeHtml(entry.id)}" aria-pressed="${selected}" ${isAdded ? "disabled" : ""}><img loading="lazy" src="https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/${entry.dex_number}.png" alt="">${routePokemonCardCopy(entry)}<em>${isAdded ? "추가됨" : selected ? "선택됨" : "선택"}</em></button>`;
  }).join("") : '<p class="pokemon-map-empty">조건에 맞는 포켓몬이 없습니다.<br>필터를 줄이거나 초기화해 보세요.</p>';
  pickerList.scrollTop = pickerScrollTop;
  const selectedCount = picker.selected.size, addButton = $("#encounter-pokemon-picker-add");
  addButton.textContent = `선택한 ${selectedCount}종 추가`; addButton.disabled = selectedCount === 0;
}
function pokemonLevelOverride(settings, species) { return (settings.level_overrides || []).find((entry) => entry.species === species) || null; }
function pokemonLevelButtonMarkup(settings, species, target) {
  const override = pokemonLevelOverride(settings, species);
  return `<button type="button" class="route-pokemon-level-button${override ? " has-override" : ""}" data-${target}-pokemon-level="${escapeHtml(species)}" aria-label="개별 레벨 설정">${override ? `Lv.${override.min_level}–${override.max_level}` : "Lv"}</button>`;
}
function directPokemonCardMarkup(addition, index, attribute, settings, target) {
  const entry = worldPokemonById().get(addition.species), dex = entry?.dex_number;
  const copy = entry ? routePokemonCardCopy(entry) : `<div class="route-pokemon-card-copy"><b class="route-pokemon-name">${escapeHtml(addition.species)}</b></div>`;
  return `<article class="route-biome-pokemon-card is-direct-added">${dex ? `<img loading="lazy" src="https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/${dex}.png" alt="">` : "<span></span>"}${copy}<button type="button" class="route-pokemon-card-state" ${attribute}="${index}" aria-label="${escapeHtml(entry ? pokemonMapEntryName(entry) : addition.species)} 직접 추가에서 제거">제거 ×</button>${pokemonLevelButtonMarkup(settings, addition.species, target)}</article>`;
}
function renderPokemonLevelEditor(target, settings, defaultRange) {
  const species = state[`${target}PokemonLevelSpecies`], editor = $(`#${target}-pokemon-level-editor`);
  editor.hidden = !species;
  if (!species) return;
  const entry = worldPokemonById().get(species), override = pokemonLevelOverride(settings, species);
  $(`#${target}-pokemon-level-name`).textContent = `${entry ? pokemonMapEntryName(entry) : species} 개별 레벨`;
  $(`#${target}-pokemon-level-min`).value = override?.min_level ?? defaultRange.min;
  $(`#${target}-pokemon-level-max`).value = override?.max_level ?? defaultRange.max;
  $(`#${target}-pokemon-level-reset`).disabled = !override;
}
function routeDefaultPokemonLevelRange(route) {
  const levels = connectionPath(route).map((cell) => levelOverrideAt(cell.q, cell.r)?.average_level).filter(Number.isFinite);
  const average = levels.length ? Math.round(levels.reduce((sum, level) => sum + level, 0) / levels.length) : 5;
  return { min: Math.max(1, average - 2), max: Math.min(100, average + 2) };
}
function renderEncounterPokemonDialog() {
  const target = state.encounterPokemonTarget, document = encounterDocument(target), settings = encounterSettings(target); if (!document || !settings) return;
  const baseList = $("#encounter-biome-pokemon-list"), baseScrollTop = baseList.scrollTop;
  const directList = $("#encounter-direct-pokemon-list"), directScrollTop = directList.scrollTop;
  const byId = worldPokemonById(), query = state.encounterPokemonQuery.toLowerCase(), excluded = new Set(settings.excluded_species);
  $("#encounter-pokemon-dialog-title").textContent = `${document.display_name?.ko_kr || document.id} 서식 포켓몬 편집`;
  $("#encounter-pokemon-dialog-subtitle").textContent = `${settings.pokemon_biome} · 외형 설정과 독립`;
  $("#encounter-inherit-biome").checked = settings.inherit_biome;
  const baseEntries = encounterBasePokemonIds(settings).map((id) => byId.get(id)).filter(Boolean).filter((entry) => !query || pokemonSearchText(entry).includes(query));
  const directEntries = settings.additions.map((addition, index) => ({ addition, index, entry: byId.get(addition.species) })).filter(({ addition, entry }) => !query || pokemonSearchText(entry || addition).includes(query));
  baseList.innerHTML = baseEntries.length ? baseEntries.map((entry) => { const enabled = settings.inherit_biome && !excluded.has(entry.id); return `<article class="route-biome-pokemon-card${enabled ? " is-enabled" : ""}"><img loading="lazy" src="https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/${entry.dex_number}.png" alt="">${routePokemonCardCopy(entry)}<button type="button" class="route-pokemon-card-state" data-encounter-biome-species="${escapeHtml(entry.id)}" aria-pressed="${enabled}" ${settings.inherit_biome ? "" : "disabled"}>${enabled ? "포함됨" : "제외됨"}</button>${pokemonLevelButtonMarkup(settings, entry.id, "encounter")}</article>`; }).join("") : '<p class="pokemon-map-empty">해당하는 바이옴 포켓몬이 없습니다.</p>';
  directList.innerHTML = directEntries.length ? directEntries.map(({ addition, index }) => directPokemonCardMarkup(addition, index, "data-remove-encounter-pokemon", settings, "encounter")).join("") : '<p class="pokemon-map-empty">직접 추가한 포켓몬이 없습니다.</p>';
  $("#encounter-biome-pokemon-count").textContent = `${baseEntries.length}종`; $("#encounter-direct-pokemon-count").textContent = `${directEntries.length}종`;
  $("#encounter-custom-pokemon-count").textContent = `${settings.additions.length}종 추가됨`;
  renderEncounterPokemonPicker();
  renderPokemonLevelEditor("encounter", settings, { min: settings.minimum_level, max: settings.maximum_level });
  baseList.scrollTop = baseScrollTop; directList.scrollTop = directScrollTop;
  renderEncounterSummary(target);
}
function openEncounterPokemonDialog(target) { state.encounterPokemonTarget = target; state.encounterPokemonLevelSpecies = null; $("#encounter-pokemon-dialog").dataset.target = target; state.encounterPokemonQuery = ""; state.encounterPokemonPicker = { query: "", generation: "all", type: "all", habitat: "all", rarity: "all", special: "all", availability: "all", selected: new Set() }; $("#encounter-biome-pokemon-search").value = ""; renderEncounterPokemonDialog(); $("#encounter-pokemon-dialog").showModal(); }
function addSelectedEncounterPokemon() {
  const settings = encounterSettings(); if (!settings) return;
  const existing = new Set(settings.additions.map((addition) => addition.species));
  const selected = worldPokemonCatalog().filter((entry) => state.encounterPokemonPicker.selected.has(entry.id) && !existing.has(entry.id));
  if (!selected.length) return;
  settings.additions.push(...selected.map((entry) => ({ species: entry.id, min_level: settings.minimum_level, max_level: settings.maximum_level })));
  state.encounterPokemonPicker.selected.clear(); renderEncounterPokemonDialog(); toast(`${selected.length}종을 서식 포켓몬에 추가했습니다.`);
}
function routeBasePokemonIds(route) {
  const ids = new Set(); const locations = state.worldPokemonMap?.locations || [];
  for (const cell of connectionPath(route)) {
    const location = locations.find((entry) => entry.q === cell.q && entry.r === cell.r);
    for (const species of location?.base_pokemon_ids || location?.pokemon_ids || []) ids.add(species);
  }
  return [...ids];
}
function routeEffectivePokemonIds(route) {
  const settings = ensureRoutePokemonSettings(route); const excluded = new Set(settings.excluded_species);
  const ids = settings.inherit_biome ? routeBasePokemonIds(route).filter((id) => !excluded.has(id)) : [];
  for (const addition of settings.additions) if (addition?.species && !ids.includes(addition.species)) ids.push(addition.species);
  return ids;
}
function renderRouteInspector(route) {
  const form = $("#route-inspector-form"); const cells = connectionPath(route); const settings = ensureRoutePokemonSettings(route);
  $("#selection-inspector-kind").textContent = "SELECTED ROUTE";
  $("#selected-tile-title").textContent = routeDisplayName(route);
  $("#selected-tile-coord").textContent = `${cells.length}칸`;
  $("#route-inspector-id").textContent = route.id;
  form.elements.displayName.value = route.display_name || "";
  form.elements.surfaceStyle.value = route.surface_style || "road";
  form.elements.corridorWidth.value = Number(route.corridor_width_blocks || 12);
  form.elements.musicTrack.innerHTML = musicOptions(route.music_track || "", "road");
  form.elements.musicTrack.value = route.music_track || "";
  $("#route-music-resolution").textContent = route.music_track ? `직접 지정 · ${musicTrackLabel(route.music_track)}` : `상속 · ${musicTrackLabel(state.musicCatalog.defaults?.road)}`;
  const effectiveCount = routeEffectivePokemonIds(route).length;
  $("#route-pokemon-count").textContent = `${effectiveCount}종`;
  $("#route-pokemon-description").textContent = settings.inherit_biome
    ? `기존 바이옴 사용 · ${settings.excluded_species.length}종 제외 · ${settings.additions.length}종 직접 추가`
    : `기존 바이옴 미사용 · ${settings.additions.length}종만 직접 출현`;
  const fromName = route.from ? settlementSummary(route.from)?.name || route.from : "직접 시작";
  const toName = route.to ? settlementSummary(route.to)?.name || route.to : "직접 종료";
  $("#route-summary").innerHTML = `<b>${escapeHtml(routeDisplayName(route))}</b><span>${escapeHtml(fromName)} → ${escapeHtml(toName)}</span><small>${escapeHtml(route.surface_style || "road")} · ${cells.length}칸 · 폭 ${Number(route.corridor_width_blocks || 12)}블록</small>`;
}

function renderEntranceInspector(selection) {
  const { kind, entrance } = selection; const form = $("#entrance-inspector-form"); const tile = tileAt(entrance.anchor.q, entrance.anchor.r);
  const underlying = tile ? tile.biome : emptyTerrainLabel(emptyTerrainAt(entrance.anchor.q, entrance.anchor.r));
  $("#selection-inspector-kind").textContent = kind === "cave" ? "CAVE ENTRANCE" : "FOREST ENTRANCE";
  $("#selected-tile-title").textContent = kind === "cave" ? `${caveSummary(entrance.cave)?.name || entrance.cave} 입구` : `${forestSummary(entrance.forest)?.name || entrance.forest} 입구`;
  $("#selected-tile-coord").textContent = `Q ${entrance.anchor.q} · R ${entrance.anchor.r}`;
  $("#entrance-inspector-label").textContent = kind === "cave" ? "동굴 입구 속성" : "숲 입구 속성";
  $("#entrance-inspector-id").textContent = entrance.id; $("#entrance-target-label").textContent = kind === "cave" ? "연결 동굴" : "연결 숲";
  form.elements.id.value = entrance.id; form.elements.target.value = kind === "cave" ? entrance.cave : entrance.forest; form.elements.internalEntrance.value = entrance.entrance;
  form.elements.q.value = entrance.anchor.q; form.elements.r.value = entrance.anchor.r; form.elements.facing.value = entrance.facing;
  form.elements.rotation.value = entrance.rotation || 0; form.elements.pokemonCenterEnabled.value = String(Boolean(entrance.pokemon_center_enabled));
  form.elements.treeLog.value = entrance.tree_log || "minecraft:spruce_log"; form.elements.treeLeaves.value = entrance.tree_leaves || "minecraft:spruce_leaves";
  form.elements.wallThickness.value = entrance.wall_thickness || 7; form.elements.wallHeight.value = entrance.wall_height || 14; form.elements.openingWidth.value = entrance.opening_width || 7; form.elements.barrierHeight.value = entrance.barrier_height || 32;
  $$('[data-cave-entrance-field]').forEach((field) => field.hidden = kind !== "cave"); $$('[data-forest-entrance-field]').forEach((field) => field.hidden = kind !== "forest");
  $("#entrance-underlay-summary").innerHTML = `<b>입구 아래 바이옴</b><span>${escapeHtml(underlying)}</span><small>마커 바깥의 육각 타일을 누르면 바이옴 속성을 선택합니다.</small>`;
}

function renderTileInspector() {
  ensureWorldObjectTypeOptions();
  const selected = state.selectedHex; const route = selectedRoute(); const entrance = selectedEntrance(); const form = $("#tile-inspector-form"); const routeForm = $("#route-inspector-form"); const entranceForm = $("#entrance-inspector-form");
  $("#tile-inspector-empty").hidden = Boolean(selected || route || entrance); form.hidden = !selected || Boolean(entrance); routeForm.hidden = !route; entranceForm.hidden = !entrance;
  if (route) { renderRouteInspector(route); renderWorldPokemonPanel(); return; }
  if (entrance) { renderEntranceInspector(entrance); renderWorldPokemonPanel(); return; }
  $("#selection-inspector-kind").textContent = "SELECTED TILE";
  if (!selected) { $("#selected-tile-title").textContent = "타일을 선택하세요"; $("#selected-tile-coord").textContent = "Q — · R —"; renderWorldPokemonPanel(); return; }
  const tile = tileAt(selected.q, selected.r); const town = settlementAt(selected.q, selected.r); const customObject = objectAt(selected.q, selected.r); const townArea = settlementFootprintAt(selected.q, selected.r); const environment = environmentOverrideAt(selected.q, selected.r); const leveling = levelOverrideAt(selected.q, selected.r);
  const routes = routesAt(selected.q, selected.r);
  const musicOverride = musicOverrideAt(selected.q, selected.r);
  const musicContext = town || townArea ? "settlement" : routes.length ? "road" : "tile";
  const kind = customObject ? "object" : town ? "settlement" : tile ? "biome" : "empty";
  $("#selected-tile-title").textContent = customObject ? customObject.id : town ? (settlementSummary(town.settlement)?.name || "마을 타일") : tile ? tile.biome.replace("minecraft:", "") : emptyTerrainLabel(emptyTerrainAt(selected.q, selected.r));
  $("#selected-tile-coord").textContent = `Q ${selected.q} · R ${selected.r}`;
  form.elements.kind.value = kind;
  form.elements.biome.innerHTML = worldBiomeOptions(tile?.biome || "minecraft:plains");
  form.elements.whirlpoolBoundary.value = String(tile?.access_requirement === "cobbleventure:field_move/whirlpool");
  form.elements.emptyTerrainType.value = emptyTerrainAt(selected.q, selected.r);
  form.elements.settlement.innerHTML = worldSettlementOptions(town?.settlement || "");
  form.elements.townBiome.innerHTML = worldBiomeOptions(town?.town_biome || "minecraft:plains");
  form.elements.connectionHeight.value = Number((town || tile)?.terrain_profile?.connection_height || 0);
  form.querySelector("[data-terrain-connection-field]").hidden = kind !== "biome" && kind !== "settlement";
  form.elements.musicTrack.innerHTML = musicOptions(musicOverride?.music_track || "", musicContext);
  form.elements.musicTrack.value = musicOverride?.music_track || "";
  $("#tile-music-resolution").textContent = musicOverride
    ? `직접 지정 · ${musicTrackLabel(musicOverride.music_track)}`
    : `상속 · ${musicTrackLabel(state.musicCatalog.defaults?.[musicContext])}`;
  form.elements.objectId.value = customObject?.id || "";
  form.elements.objectType.value = customObject?.type || "gate";
  form.elements.objectResource.value = customObject?.resource || "";
  form.elements.objectGateMode.value = customObject?.properties?.gate_mode || "classic";
  form.elements.objectBuildingEnabled.value = String(customObject?.properties?.building_enabled ?? true);
  form.elements.objectFacing.value = customObject?.properties?.facing || "north";
  form.elements.objectRotation.value = customObject?.rotation || 0;
  form.elements.objectSurroundingType.value = customObject?.properties?.surrounding_type || "wall";
  form.elements.objectWallBlock.value = customObject?.properties?.wall_block || "minecraft:stone_bricks";
  form.elements.objectTreeLog.value = customObject?.properties?.tree_log || "minecraft:oak_log";
  form.elements.objectTreeLeaves.value = customObject?.properties?.tree_leaves || "minecraft:oak_leaves";
  form.elements.objectWallThickness.value = customObject?.properties?.wall_thickness || 5;
  form.elements.objectWallHeight.value = customObject?.properties?.wall_height || 7;
  form.elements.objectOpeningWidth.value = customObject?.properties?.opening_width || 7;
  form.elements.objectBarrierHeight.value = customObject?.properties?.barrier_height || 24;
  form.elements.objectNpc.value = customObject?.properties?.npc || "";
  form.elements.objectConditionMode.value = customObject?.properties?.condition_mode || "all";
  form.elements.objectConditions.value = formatGateConditions(customObject?.properties?.conditions);
  form.elements.objectDenyMessage.value = customObject?.properties?.deny_message || "아직 이 관문을 통과할 수 없습니다.";
  updateGateOptionVisibility();
  $$('[data-tile-field]').forEach((field) => field.hidden = field.dataset.tileField !== kind);
  const routePanel = $("#route-overlay-panel");
  routePanel.hidden = !routes.length;
  $("#route-overlay-list").innerHTML = routes.map((route) => `<div class="route-overlay-item"><span>${escapeHtml(route.id)} · ${escapeHtml(route.surface_style)}</span><button type="button" data-remove-route="${escapeHtml(route.id)}">연결 삭제</button></div>`).join("");
  $$('[data-remove-route]').forEach((button) => button.addEventListener("click", () => removeRouteConnection(button.dataset.removeRoute)));
  const routeNote = routes.length ? `<small>길 오버레이: ${routes.map((route) => escapeHtml(route.id)).join(", ")} · 기본 바이옴은 별도로 유지됩니다.</small>` : "";
  const climateNote = environment ? `<small class="climate-override-note">기후 덮어쓰기 · 온도 ${escapeHtml(environment.temperature || "기본")} · 습도 ${escapeHtml(environment.humidity || "기본")} · 날씨 ${escapeHtml(environment.weather || "기본")}</small>` : "";
  const levelNote = leveling ? `<small class="level-override-note">레벨링 오버레이 · 지역 평균 Lv.${leveling.average_level} · 야생 스폰은 평균 ±2 적용</small>` : "";
  const townAreaNote = townArea && !town ? `<small class="town-area-warning">실제 생성: ${escapeHtml(settlementSummary(townArea.settlement)?.name || townArea.settlement)} 사용 범위 · 이 타일의 바이옴 배치는 무시됩니다.</small>` : "";
  const objectSummary = reservedWorldObjectTypes.has(customObject?.type)
    ? `<b>${escapeHtml(customObject.id)}</b><span>${escapeHtml(reservedWorldObjectTypes.get(customObject.type))} · NBT 배치 예약</span><small>${escapeHtml(customObject.resource || "NBT 미지정")} · 전용 동작은 추후 설계</small>`
    : customObject ? `<b>${escapeHtml(customObject.id)}</b><span>조건부 관문 · ${{ classic: "실제 관문", npc_only: "NPC 전용", system_only: "시스템 차단" }[customObject.properties?.gate_mode || "classic"]}</span><small>${customObject.properties?.gate_mode === "system_only" ? "타일 진입 감지 · 조건과 차단 문구 사용" : customObject.properties?.gate_mode === "npc_only" ? "NPC proximity 이벤트로 제지" : `${customObject.properties?.surrounding_type || "wall"} 차단물 · 중앙 NBT 건물`}</small>` : "";
  $("#tile-summary").innerHTML = (kind === "object" ? objectSummary : kind === "settlement" ? `<b>마을 중심 타일</b><span>${escapeHtml(town.settlement)}</span><small>마을 크기 ${worldSettlementCellCount(town)}칸 · 마커를 드래그해 이동</small>` : kind === "biome" ? `<b>${escapeHtml(tile.biome)}</b><span>직접 배치된 기본 바이옴</span><small>길 유무와 관계없이 월드 지형에 적용됩니다.</small>` : `<b>${escapeHtml(emptyTerrainLabel(emptyTerrainAt(selected.q, selected.r)))}</b><span>접근 불가 배경 지형</span><small>바이옴과 길은 각각 별도로 배치할 수 있습니다.</small>`) + townAreaNote + routeNote + climateNote + levelNote;
  renderWorldPokemonPanel();
}

function pokemonSearchText(entry) {
  return [entry?.dex_number, entry?.id, entry?.slug, entry?.display_name?.ko_kr, entry?.display_name?.en_us].filter(Boolean).join(" ").toLowerCase();
}

const pokemonTypeLabels = {
  normal: "노말", fire: "불꽃", water: "물", electric: "전기", grass: "풀", ice: "얼음",
  fighting: "격투", poison: "독", ground: "땅", flying: "비행", psychic: "에스퍼", bug: "벌레",
  rock: "바위", ghost: "고스트", dragon: "드래곤", dark: "악", steel: "강철", fairy: "페어리",
};
const pokemonHabitatLabels = {
  forest: "숲", plains: "평원", mountain: "산악", wetland: "습지", freshwater: "민물",
  ocean: "바다", cave: "동굴", desert: "사막", arid: "건조지", urban: "도시", volcanic: "화산",
  cold: "한랭지", snow: "설원", coast: "해안", sky: "공중", special: "특수 지역", unknown: "미분류",
};
const pokemonRarityLabels = { common: "흔함", medium: "보통", uncommon: "드묾", rare: "희귀", ultra_rare: "매우 희귀", legendary: "전설", mythical: "환상" };

function routePokemonFilterOptions(selector, allLabel, values, labeler = (value) => value) {
  const select = $(selector); const current = select.value || "all";
  select.innerHTML = `<option value="all">${escapeHtml(allLabel)}</option>${values.map((value) => `<option value="${escapeHtml(value)}">${escapeHtml(labeler(value))}</option>`).join("")}`;
  select.value = values.map(String).includes(String(current)) ? current : "all";
}

function pokemonPickerMatches(entry, picker) {
  if (picker.query && !pokemonSearchText(entry).includes(picker.query.toLowerCase())) return false;
  if (picker.generation !== "all" && String(entry.generation) !== picker.generation) return false;
  if (picker.type !== "all" && !(entry.types || []).includes(picker.type)) return false;
  if (picker.habitat !== "all" && entry.habitats?.primary !== picker.habitat) return false;
  if (picker.rarity !== "all" && entry.preferences?.rarity !== picker.rarity) return false;
  if (picker.special === "legendary" && !entry.is_legendary) return false;
  if (picker.special === "mythical" && !entry.is_mythical) return false;
  if (picker.special === "regular" && (entry.is_legendary || entry.is_mythical)) return false;
  const available = new Set((state.worldPokemonMap?.available_pokemon || []).map((pokemon) => pokemon.id));
  if (picker.availability === "available" && !available.has(entry.id)) return false;
  if (picker.availability === "unavailable" && available.has(entry.id)) return false;
  return true;
}
function routePokemonPickerMatches(entry) { return pokemonPickerMatches(entry, state.routePokemonPicker); }
function encounterPokemonPickerMatches(entry) { return pokemonPickerMatches(entry, state.encounterPokemonPicker); }

function filteredRoutePokemonPickerEntries() { return worldPokemonCatalog().filter(routePokemonPickerMatches); }
function filteredEncounterPokemonPickerEntries() { return worldPokemonCatalog().filter(encounterPokemonPickerMatches); }

function routePokemonCardFacts(entry) {
  const types = entry?.types || [];
  const habitat = pokemonHabitatLabels[entry?.habitats?.primary] || entry?.habitats?.primary || "서식지 미분류";
  const rarity = pokemonRarityLabels[entry?.preferences?.rarity] || entry?.preferences?.rarity || "희귀도 미분류";
  return { types, habitat, rarity };
}

function routePokemonCardCopy(entry) {
  const facts = routePokemonCardFacts(entry);
  const typeBadges = facts.types.length
    ? facts.types.map((type) => `<b class="move-type-badge type-${escapeHtml(toId(type))}">${escapeHtml(pokemonTypeLabels[type] || pokemonTypeNames[type] || type)}</b>`).join("")
    : '<b class="move-type-badge type-unknown">미분류</b>';
  return `<div class="route-pokemon-card-copy"><b class="route-pokemon-name">${escapeHtml(pokemonMapEntryName(entry))}</b><small>No.${String(entry.dex_number).padStart(4, "0")}</small><span class="route-pokemon-type-badges">${typeBadges}</span></div>`;
}

function renderRoutePokemonPicker() {
  const catalog = worldPokemonCatalog(); const picker = state.routePokemonPicker;
  const pickerList = $("#route-pokemon-picker-list"); const pickerScrollTop = pickerList.scrollTop;
  const unique = (values) => [...new Set(values.filter(Boolean).map(String))].sort((left, right) => left.localeCompare(right, "ko", { numeric: true }));
  routePokemonFilterOptions("#route-pokemon-picker-generation", "모든 세대", unique(catalog.map((entry) => entry.generation)), (value) => `${value}세대`);
  routePokemonFilterOptions("#route-pokemon-picker-type", "모든 타입", unique(catalog.flatMap((entry) => entry.types || [])), (value) => pokemonTypeLabels[value] || value);
  routePokemonFilterOptions("#route-pokemon-picker-habitat", "모든 서식지", unique(catalog.map((entry) => entry.habitats?.primary)), (value) => pokemonHabitatLabels[value] || value);
  routePokemonFilterOptions("#route-pokemon-picker-rarity", "모든 희귀도", unique(catalog.map((entry) => entry.preferences?.rarity)), (value) => pokemonRarityLabels[value] || value);
  $("#route-pokemon-picker-generation").value = picker.generation;
  $("#route-pokemon-picker-type").value = picker.type;
  $("#route-pokemon-picker-habitat").value = picker.habitat;
  $("#route-pokemon-picker-rarity").value = picker.rarity;
  $("#route-pokemon-picker-special").value = picker.special;
  $("#route-pokemon-picker-availability").value = picker.availability;
  $("#route-pokemon-picker-search").value = picker.query;
  const route = selectedRoute(); const added = new Set(ensureRoutePokemonSettings(route).additions.map((entry) => entry.species));
  const matches = filteredRoutePokemonPickerEntries(); const visible = matches.slice(0, 180);
  $("#route-pokemon-picker-result").textContent = `${matches.length.toLocaleString()}종${matches.length > visible.length ? ` · 앞 ${visible.length}종 표시` : ""}`;
  pickerList.innerHTML = visible.length ? visible.map((entry) => {
    const isAdded = added.has(entry.id); const selected = picker.selected.has(entry.id);
    return `<button type="button" class="route-pokemon-picker-card${selected ? " is-selected" : ""}${isAdded ? " is-added" : ""}" data-route-picker-species="${escapeHtml(entry.id)}" aria-pressed="${selected}" ${isAdded ? "disabled" : ""}><img loading="lazy" src="https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/${entry.dex_number}.png" alt="">${routePokemonCardCopy(entry)}<em>${isAdded ? "추가됨" : selected ? "선택됨" : "선택"}</em></button>`;
  }).join("") : '<p class="pokemon-map-empty">조건에 맞는 포켓몬이 없습니다.<br>필터를 줄이거나 초기화해 보세요.</p>';
  pickerList.scrollTop = pickerScrollTop;
  const selectedCount = picker.selected.size; const addButton = $("#route-pokemon-picker-add");
  addButton.textContent = `선택한 ${selectedCount}종 추가`; addButton.disabled = selectedCount === 0;
}
function renderRoutePokemonDialog() {
  const route = selectedRoute(); if (!route) return;
  const baseList = $("#route-biome-pokemon-list"); const baseScrollTop = baseList.scrollTop;
  const directList = $("#route-direct-pokemon-list"); const directScrollTop = directList.scrollTop;
  const settings = ensureRoutePokemonSettings(route); const byId = worldPokemonById(); const query = state.routePokemonQuery.toLowerCase();
  $("#route-pokemon-dialog-title").textContent = `${routeDisplayName(route)} 포켓몬 편집`;
  $("#route-pokemon-dialog-subtitle").textContent = `${route.id} · ${connectionPath(route).length}칸`;
  $("#route-inherit-biome").checked = settings.inherit_biome;
  const excluded = new Set(settings.excluded_species);
  const baseEntries = routeBasePokemonIds(route).map((id) => byId.get(id)).filter(Boolean).filter((entry) => !query || pokemonSearchText(entry).includes(query));
  baseList.innerHTML = baseEntries.length ? baseEntries.map((entry) => {
    const enabled = settings.inherit_biome && !excluded.has(entry.id);
    return `<article class="route-biome-pokemon-card${enabled ? " is-enabled" : ""}"><img loading="lazy" src="https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/${entry.dex_number}.png" alt="">${routePokemonCardCopy(entry)}<button type="button" class="route-pokemon-card-state" data-route-biome-species="${escapeHtml(entry.id)}" aria-pressed="${enabled}" ${settings.inherit_biome ? "" : "disabled"}>${enabled ? "포함됨" : "제외됨"}</button>${pokemonLevelButtonMarkup(settings, entry.id, "route")}</article>`;
  }).join("") : `<p class="pokemon-map-empty">${query ? "검색 결과가 없습니다." : "이 길 아래에 포켓몬 바이옴 정보가 없습니다."}</p>`;
  const directEntries = settings.additions.map((addition, index) => ({ addition, index, entry: byId.get(addition.species) })).filter(({ addition, entry }) => !query || pokemonSearchText(entry || addition).includes(query));
  directList.innerHTML = directEntries.length ? directEntries.map(({ addition, index }) => directPokemonCardMarkup(addition, index, "data-remove-route-pokemon", settings, "route")).join("") : '<p class="pokemon-map-empty">직접 추가한 포켓몬이 없습니다.</p>';
  $("#route-biome-pokemon-count").textContent = `${baseEntries.length}종`; $("#route-direct-pokemon-count").textContent = `${directEntries.length}종`;
  $("#route-custom-pokemon-count").textContent = `${settings.additions.length}종 추가됨`;
  renderRoutePokemonPicker();
  renderPokemonLevelEditor("route", settings, routeDefaultPokemonLevelRange(route));
  baseList.scrollTop = baseScrollTop;
  directList.scrollTop = directScrollTop;
}
function openRoutePokemonDialog() {
  if (!selectedRoute()) return;
  state.routePokemonLevelSpecies = null;
  state.routePokemonQuery = "";
  state.routePokemonPicker = { query: "", generation: "all", type: "all", habitat: "all", rarity: "all", special: "all", availability: "all", selected: new Set() };
  $("#route-biome-pokemon-search").value = "";
  renderRoutePokemonDialog(); $("#route-pokemon-dialog").showModal();
}
function addSelectedRoutePokemon() {
  const route = selectedRoute(); if (!route) return;
  const settings = ensureRoutePokemonSettings(route); const existing = new Set(settings.additions.map((addition) => addition.species));
  const selected = worldPokemonCatalog().filter((entry) => state.routePokemonPicker.selected.has(entry.id) && !existing.has(entry.id));
  if (!selected.length) return;
  settings.additions.push(...selected.map((entry) => ({ species: entry.id, min_level: 1, max_level: 100 })));
  state.routePokemonPicker.selected.clear(); markWorldDirty(); renderRouteInspector(route); renderRoutePokemonDialog(); renderWorldPokemonPanel();
  toast(`${selected.length}종을 길 포켓몬에 추가했습니다.`);
}
function toggleRouteBiomePokemon(species) {
  const route = selectedRoute(); if (!route) return; const settings = ensureRoutePokemonSettings(route);
  const excluded = new Set(settings.excluded_species);
  if (excluded.has(species)) excluded.delete(species); else excluded.add(species);
  settings.excluded_species = [...excluded].sort(); markWorldDirty(); renderRouteInspector(route); renderRoutePokemonDialog(); renderWorldPokemonPanel();
}
function removeRoutePokemon(index) {
  const route = selectedRoute(); if (!route) return; const settings = ensureRoutePokemonSettings(route), [removed] = settings.additions.splice(index, 1);
  if (removed) settings.level_overrides = settings.level_overrides.filter((entry) => entry.species !== removed.species);
  markWorldDirty(); renderRouteInspector(route); renderRoutePokemonDialog(); renderWorldPokemonPanel();
}

function applyPokemonLevelOverride(target) {
  const settings = target === "route" ? ensureRoutePokemonSettings(selectedRoute()) : encounterSettings();
  const species = state[`${target}PokemonLevelSpecies`]; if (!settings || !species) return;
  const minimum = Math.round(Number($(`#${target}-pokemon-level-min`).value));
  const maximum = Math.round(Number($(`#${target}-pokemon-level-max`).value));
  if (!Number.isInteger(minimum) || !Number.isInteger(maximum) || minimum < 1 || maximum > 100 || minimum > maximum) { toast("레벨은 1~100 범위에서 최소가 최대보다 작거나 같아야 합니다."); return; }
  settings.level_overrides = settings.level_overrides.filter((entry) => entry.species !== species);
  settings.level_overrides.push({ species, min_level: minimum, max_level: maximum });
  settings.level_overrides.sort((left, right) => left.species.localeCompare(right.species));
  if (target === "route") { markWorldDirty(); renderRouteInspector(selectedRoute()); renderRoutePokemonDialog(); renderWorldPokemonPanel(); } else renderEncounterPokemonDialog();
}
function resetPokemonLevelOverride(target) {
  const settings = target === "route" ? ensureRoutePokemonSettings(selectedRoute()) : encounterSettings();
  const species = state[`${target}PokemonLevelSpecies`]; if (!settings || !species) return;
  settings.level_overrides = settings.level_overrides.filter((entry) => entry.species !== species);
  state[`${target}PokemonLevelSpecies`] = null;
  if (target === "route") { markWorldDirty(); renderRouteInspector(selectedRoute()); renderRoutePokemonDialog(); renderWorldPokemonPanel(); } else renderEncounterPokemonDialog();
}

function pokemonMapEntryName(entry) { return entry?.display_name?.ko_kr || entry?.display_name?.en_us || entry?.slug || entry?.id || "알 수 없음"; }
function pokemonMapMatches(entry, query) {
  if (!query) return true;
  const text = [entry.dex_number, entry.id, entry.slug, entry.display_name?.ko_kr, entry.display_name?.en_us].filter(Boolean).join(" ").toLowerCase();
  return text.includes(query.toLowerCase());
}
function pokemonMapCard(entry, unavailable = false) {
  const reason = entry.unavailable_reason === "other_generation" ? `${entry.generation}세대 포켓몬` : "현재 월드 조건과 불일치";
  return `<article class="pokemon-map-card${unavailable ? " is-unavailable" : " is-available"}"><span class="pokemon-availability-badge">${unavailable ? "미출현" : "출현"}</span><img loading="lazy" src="https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/${entry.dex_number}.png" alt=""><div><b>${escapeHtml(pokemonMapEntryName(entry))}</b><span>No.${String(entry.dex_number).padStart(4, "0")} · ${escapeHtml((entry.types || []).join(" / "))}</span><code>${escapeHtml(entry.id)}</code><small>${unavailable ? escapeHtml(reason) : `${escapeHtml(entry.habitats?.primary || "unknown")} · ${escapeHtml(entry.preferences?.rarity || "unknown")}`}</small></div></article>`;
}
function renderWorldPokemonPanel() {
  const data = state.worldPokemonMap || {}; const summary = data.summary || {};
  if (!new Set(["available", "unavailable"]).has(state.pokemonMapTab)) state.pokemonMapTab = "available";
  const availableCount = Number(summary.available || data.available_pokemon?.length || 0);
  const unavailableCount = Number(summary.unavailable || data.unavailable_pokemon?.length || 0);
  const totalCount = availableCount + unavailableCount;
  const coverage = totalCount ? Math.round(availableCount / totalCount * 100) : 0;
  $("#pokemon-map-generation").textContent = `${state.selectedGeneration}세대`;
  $("#pokemon-map-summary").textContent = `총 ${totalCount.toLocaleString()}종`;
  $("#available-pokemon-count").textContent = availableCount.toLocaleString();
  $("#unavailable-pokemon-count").textContent = unavailableCount.toLocaleString();
  $("#pokemon-map-coverage").textContent = `${coverage}%`;
  $("#available-pokemon-tab-count").textContent = availableCount.toLocaleString();
  $("#unavailable-pokemon-tab-count").textContent = unavailableCount.toLocaleString();
  $$('[data-pokemon-map-tab]').forEach((button) => { const active = button.dataset.pokemonMapTab === state.pokemonMapTab; button.classList.toggle("is-active", active); button.setAttribute("aria-selected", String(active)); });
  let entries;
  if (state.pokemonMapTab === "unavailable") {
    entries = data.unavailable_pokemon || [];
    $("#pokemon-map-location").innerHTML = `<b>${state.selectedGeneration}세대 월드 미출현 포켓몬</b><span>현재 세대의 모든 출현 지역과 길의 직접 추가 목록 어디에도 포함되지 않은 포켓몬입니다.</span>`;
  } else {
    entries = data.available_pokemon || [];
    $("#pokemon-map-location").innerHTML = `<b>${state.selectedGeneration}세대 월드 출현 포켓몬</b><span>현재 세대의 마을·길·바이옴 중 한 곳 이상에서 만날 수 있는 포켓몬의 합집합입니다.</span>${state.worldDirty ? "<small>저장하지 않은 월드맵 변경은 아직 집계에 반영되지 않았습니다.</small>" : ""}`;
  }
  const filtered = entries.filter((entry) => pokemonMapMatches(entry, state.pokemonMapQuery));
  $("#pokemon-map-list").innerHTML = filtered.length
    ? filtered.map((entry) => pokemonMapCard(entry, state.pokemonMapTab === "unavailable")).join("")
    : `<p class="pokemon-map-empty">${entries.length ? "검색 결과가 없습니다." : "표시할 포켓몬이 없습니다."}</p>`;
}

const mapToolCopy = {
  select: ["선택 도구", "기존 타일·마을·길·입구·오브젝트를 선택하거나 이동합니다."],
  biome: ["바이옴 브러시", "기본 바이옴 레이어를 클릭하거나 드래그해 칠합니다."],
  terrain: ["빈 지형 브러시", "접근 불가 배경 지형을 칠합니다."],
  climate: ["기후 오버라이드", "온도·습도·날씨를 좌표별로 덮어씁니다."],
  level: ["평균 레벨 브러시", "야생 포켓몬 지역의 목표 평균 레벨을 칠합니다."],
  route: ["길 만들기", "마을 자동 연결 또는 타일 경유 경로를 만듭니다."],
  settlement: ["마을 배치", "빈 타일에 프리셋 마을을 새로 배치합니다."],
  entrance: ["출입구 배치", "동굴·숲 출입구 종류와 포켓몬센터 여부를 정해 배치합니다."],
  object: ["오브젝트 배치", "확장 가능한 커스텀 오브젝트를 배치합니다."],
  eraser: ["지우개", "선택한 월드 레이어를 제거합니다."]
};
function renderMapToolOptions() {
  ensureWorldObjectTypeOptions();
  const tool = state.activeMapTool; const [name, description] = mapToolCopy[tool];
  $("#active-tool-name").textContent = name; $("#active-tool-description").textContent = description;
  $$('[data-map-tool]').forEach((button) => { button.classList.toggle("is-active", button.dataset.mapTool === tool); button.setAttribute("aria-pressed", String(button.dataset.mapTool === tool)); });
  $$('[data-tool-options]').forEach((panel) => panel.hidden = panel.dataset.toolOptions !== tool);
  $("#biome-brush-type").innerHTML = worldBiomeOptions($("#biome-brush-type").value || "minecraft:plains");
  $("#settlement-tool-biome").innerHTML = worldBiomeOptions($("#settlement-tool-biome").value || "minecraft:plains");
  $("#settlement-tool-preset").innerHTML = worldSettlementOptions($("#settlement-tool-preset").value);
  const selectedGatekeeper = $("#object-tool-npc").value;
  $("#object-tool-npc").innerHTML = worldGatekeeperOptions(selectedGatekeeper);
  renderWorldObjectNbtOptions();
  updateGateOptionVisibility();
  const currentCave = $("#cave-tool-cave").value;
  $("#cave-tool-cave").innerHTML = state.caves.filter((item) => Number(item.generation || 1) === state.selectedGeneration).map((item) => `<option value="${escapeHtml(item.id)}">${escapeHtml(item.name || item.id)}</option>`).join("");
  if (currentCave && state.caves.some((item) => item.id === currentCave)) $("#cave-tool-cave").value = currentCave;
  refreshCaveToolEntrances();
  const currentForest = $("#forest-tool-forest").value;
  $("#forest-tool-forest").innerHTML = state.forests.filter((item) => Number(item.generation || 1) === state.selectedGeneration).map((item) => `<option value="${escapeHtml(item.id)}">${escapeHtml(item.name || item.id)}</option>`).join("");
  if (currentForest && state.forests.some((item) => item.id === currentForest)) $("#forest-tool-forest").value = currentForest;
  refreshForestToolEntrances();
  const help = { select: "선택 도구 · 기존 타일·마을·길·출입구·오브젝트 선택 및 이동", biome: "바이옴 브러시 · 누른 채 드래그하여 칠하기", terrain: "빈 지형 브러시 · 마을과 길은 유지", climate: "기후 오버라이드 · 원본 바이옴 설정은 유지", level: "평균 레벨 브러시 · 레벨링 오버레이에 표시", route: "길 도구 · 새 길 생성 (마을·출입구·센터 지정 출입구를 끝점으로 사용 가능)", settlement: "마을 도구 · 새 마을 배치", entrance: "출입구 도구 · 동굴·숲 출입구 배치", object: "오브젝트 도구 · 새 오브젝트 배치", eraser: "지우개 · 선택 레이어 제거" };
  $("#map-interaction-help").textContent = help[tool];
  $("#world-hex-map").dataset.activeTool = tool;
  renderRouteCreator();
}
function setActiveMapTool(tool) {
  if (!mapToolCopy[tool] || tool === state.activeMapTool) return;
  if (state.routeDraft && tool !== "route") state.routeDraft = null;
  if (!new Set(["select", "route"]).has(tool)) state.selectedRouteId = null;
  state.activeMapTool = tool; state.paintStroke = null; state.brushPreview = null; state.draggedSettlement = null; state.entranceDrag = null; state.routeAnchorDrag = null;
  renderMapToolOptions(); renderHexMap();
}

function applyStrokeTool(q, r) {
  if (state.activeMapTool === "biome") paintBiomeArea(q, r);
  else if (state.activeMapTool === "terrain") paintEmptyTerrainArea(q, r);
  else if (state.activeMapTool === "climate") paintClimateArea(q, r);
  else if (state.activeMapTool === "level") paintLevelArea(q, r);
  else if (state.activeMapTool === "eraser") eraseMapArea(q, r);
}
function beginToolStroke(event) {
  if (!new Set(["biome", "terrain", "climate", "level", "eraser"]).has(state.activeMapTool) || event.button !== 0 || state.spacePanActive) return false;
  event.preventDefault(); const cell = nearestHexFromPointer(event);
  state.paintStroke = { pointerId: event.pointerId, lastKey: "" }; $("#world-hex-map").setPointerCapture?.(event.pointerId);
  continueToolStroke(event, cell); return true;
}
function continueToolStroke(event, knownCell = null) {
  if (!state.paintStroke || state.paintStroke.pointerId !== event.pointerId) return;
  const cell = knownCell || nearestHexFromPointer(event); const key = hexKey(cell.q, cell.r);
  if (key === state.paintStroke.lastKey) return;
  state.paintStroke.lastKey = key; applyStrokeTool(cell.q, cell.r);
}
function finishToolStroke(event) {
  if (!state.paintStroke || state.paintStroke.pointerId !== event.pointerId) return false;
  state.paintStroke = null; state.suppressMapClick = true; setTimeout(() => { state.suppressMapClick = false; }, 0); return true;
}

function renderRouteCreator() {
  const active = Boolean(state.routeDraft);
  $("#finish-route").hidden = !active;
  $("#cancel-route").hidden = !active;
  $("#undo-route-anchor").hidden = !active || state.routeDraft.anchors.length < 2;
  $("#route-surface").disabled = active;
  $("#route-width").disabled = active;
  const origin = state.routeDraft?.from ? settlementSummary(state.routeDraft.from)?.name || state.routeDraft.from : null;
  $("#route-tool-status").textContent = active ? (origin ? `${origin}에서 연결 중 · 앵커 ${state.routeDraft.anchors.length}개` : `직접 그리는 중 · 앵커 ${state.routeDraft.anchors.length}개`) : "첫 앵커를 배치하세요";
  $("#route-editor-help").textContent = active ? `${state.routeDraft.cells.length}칸 경로 · ${origin ? "지형을 누르면 앵커 추가, 다른 마을을 누르면 현재 앵커를 거쳐 완료" : "지형을 누를 때마다 앵커 추가"}` : "마을이나 지형을 누를 때마다 앵커가 생깁니다. 앵커 사이의 길은 자동으로 이어집니다.";
  const routes = state.worldLayout?.connections || [];
  $("#route-list-count").textContent = `${routes.length}개`;
  $("#route-manager-list").innerHTML = routes.length ? routes.map((route) => {
    const fromName = route.from ? settlementSummary(route.from)?.name || route.from.split("/").pop() : "직접";
    const toName = route.to ? settlementSummary(route.to)?.name || route.to.split("/").pop() : "그리기";
    return `<article class="route-manager-item"><button type="button" class="route-focus" data-focus-route="${escapeHtml(route.id)}"><b>${escapeHtml(routeDisplayName(route))}</b><span>${escapeHtml(fromName)} → ${escapeHtml(toName)}</span><small>${escapeHtml(route.id)} · ${escapeHtml(route.surface_style)} · ${connectionPath(route).length}칸</small></button><label><span>도로 BGM</span><select data-route-music="${escapeHtml(route.id)}">${musicOptions(route.music_track || "", "road")}</select></label><button type="button" class="route-delete" data-delete-route="${escapeHtml(route.id)}" aria-label="${escapeHtml(routeDisplayName(route))} 삭제">×</button></article>`;
  }).join("") : '<p class="route-list-empty">아직 배치된 길이 없습니다.</p>';
  $$('[data-delete-route]').forEach((button) => button.addEventListener("click", () => removeRouteConnection(button.dataset.deleteRoute)));
  $$('[data-focus-route]').forEach((button) => button.addEventListener("click", () => focusRoute(button.dataset.focusRoute)));
}

function nextRouteId() {
  const used = new Set((state.worldLayout?.connections || []).map((connection) => connection.id));
  let index = 1;
  while (used.has(`route_custom_${String(index).padStart(2, "0")}`)) index++;
  return `route_custom_${String(index).padStart(2, "0")}`;
}

function routeDraftDefaults() {
  return { id: nextRouteId(), surface_style: $("#route-surface").value, corridor_width_blocks: Number($("#route-width").value || 12), edge_noise: 0, anchors: [], cells: [] };
}
function handleRoutePoint(q, r, settlementId = settlementAt(q, r)?.settlement) {
  if (!state.routeDraft) {
    state.selectedRouteId = null;
    state.routeDraft = { ...routeDraftDefaults(), ...(settlementId ? { from: settlementId } : {}), anchors: [{ q, r }], cells: [{ q, r }] };
    state.selectedHex = { q, r }; renderWorldLayout(); return;
  }
  const draft = state.routeDraft;
  if (draft.from && settlementId && settlementId !== draft.from) {
    if (state.worldLayout.connections.some((route) => (route.from === draft.from && route.to === settlementId) || (route.from === settlementId && route.to === draft.from))) { toast("두 마을 사이에 이미 연결 길이 있습니다."); return; }
    const destination = state.worldLayout.settlements.find((node) => node.settlement === settlementId)?.anchor;
    const anchors = [...draft.anchors, { ...(destination || { q, r }) }]; const cells = routeCellsFromAnchors(anchors);
    state.worldLayout.connections.push({ id: draft.id, from: draft.from, to: settlementId, anchors, cells, corridor_width_blocks: draft.corridor_width_blocks, edge_noise: draft.edge_noise, surface_style: draft.surface_style, pathfinding: "explicit" });
    state.routeDraft = null; state.selectedHex = { q, r }; markWorldDirty(); renderWorldLayout(); toast("두 마을을 자동 경로로 연결했습니다."); return;
  }
  if (draft.from && settlementId === draft.from && draft.anchors.length === 1) { toast("연결할 다른 마을이나 지형 앵커를 선택해 주세요."); return; }
  appendRouteDraft(q, r);
}
function appendRouteDraft(q, r) {
  const draft = state.routeDraft; if (!draft) return;
  const last = draft.anchors.at(-1); if (last?.q === q && last?.r === r) return;
  draft.anchors.push({ q, r }); draft.cells = routeCellsFromAnchors(draft.anchors);
  state.selectedHex = { q, r }; renderWorldLayout();
}
function undoRouteAnchor() {
  const draft = state.routeDraft; if (!draft || draft.anchors.length < 2) return;
  draft.anchors.pop(); draft.cells = routeCellsFromAnchors(draft.anchors); state.selectedHex = { ...draft.anchors.at(-1) }; renderWorldLayout();
}
function finishRouteConnection() {
  const draft = state.routeDraft;
  if (!draft || draft.anchors.length < 2) { toast("앵커를 두 곳 이상 배치해 주세요."); return; }
  state.worldLayout.connections.push({ id: draft.id, anchors: draft.anchors, cells: draft.cells, corridor_width_blocks: draft.corridor_width_blocks, edge_noise: draft.edge_noise, surface_style: draft.surface_style, pathfinding: "explicit", ...(draft.from ? { from: draft.from } : {}) });
  state.routeDraft = null; markWorldDirty(); renderWorldLayout(); toast("바이옴과 독립된 길을 추가했습니다.");
}
function cancelRouteConnection() {
  state.routeDraft = null; renderWorldLayout();
}

function removeRouteConnection(routeId) {
  state.worldLayout.connections = (state.worldLayout.connections || []).filter((connection) => connection.id !== routeId);
  if (state.selectedRouteId === routeId) state.selectedRouteId = null;
  markWorldDirty(); renderWorldLayout(); toast(`${routeId} 길을 삭제했습니다.`);
}
function focusRoute(routeId, center = true) {
  const route = (state.worldLayout.connections || []).find((entry) => entry.id === routeId);
  const endpointSynced = route ? syncRouteEndpointAnchors(route) : false;
  const cells = route ? connectionPath(route) : [];
  if (!cells.length) return;
  if (endpointSynced) {
    markWorldDirty();
    toast("이동한 연결 위치에 맞춰 길 끝점을 자동 보정했습니다.");
  }
  state.selectedRouteId = routeId;
  state.selectedHex = null;
  state.selectedEntrance = null;
  if (center) {
    const points = cells.map((cell) => hexPoint(cell.q, cell.r));
    state.mapCenter = { x: points.reduce((sum, point) => sum + point.x, 0) / points.length, y: points.reduce((sum, point) => sum + point.y, 0) / points.length };
  }
  renderHexMap(); renderTileInspector();
}

function defaultWorldTile(q, r, biome) { return { q, r, biome, boundary_profile: "cobbleventure:boundary/earthwork", terrain_profile: { base_height_offset: 0, height_variation: 3, noise_scale_blocks: 96, connection_height: 0 } }; }
function defaultWorldSettlement(id, q, r, biome = "minecraft:plains") { return { settlement: id, anchor: { q, r }, town_radius_cells: settlementPresetRadius(id), town_footprint_shape: settlementPresetFootprintShape(id), town_footprint_cells: settlementPresetFootprintCells(id), town_road_exits: settlementPresetRoadExits(id), town_biome: biome, surroundings: [], boundary_profile: "cobbleventure:boundary/stone_wall", terrain_profile: { base_height_offset: 0, height_variation: 3, noise_scale_blocks: 96, connection_height: 0 } }; }
function settlementRangeConflict(node, q, r, cellCount) {
  const candidateCells = townFootprintCells({ q, r }, cellCount, node ? worldSettlementFootprintShape(node) : "line_q", node ? worldSettlementFootprintCells(node) : []);
  return state.worldLayout.settlements.find((entry) => entry !== node && entry.anchor && candidateCells.some((candidate) => townFootprintCells(entry.anchor, worldSettlementCellCount(entry), worldSettlementFootprintShape(entry), worldSettlementFootprintCells(entry)).some((occupied) => hexDistance(candidate, occupied) < 2)));
}

function applyTilePlacement() {
  const { q, r } = state.selectedHex || {}; if (q === undefined) return;
  const form = $("#tile-inspector-form");
  const kind = form.elements.kind.value;
  if (kind === "object") {
    const id = form.elements.objectId.value.trim(); const type = form.elements.objectType.value.trim();
    if (!/^[a-z0-9_.-]+$/.test(id) || !/^[a-z0-9_.-]+$/.test(type)) { toast("오브젝트 ID와 타입은 영문 소문자, 숫자, ., _, -만 사용할 수 있습니다."); return; }
    const duplicate = state.worldLayout.objects.find((entry) => entry.id === id && (entry.anchor.q !== q || entry.anchor.r !== r));
    if (duplicate) { toast("이미 사용 중인 오브젝트 ID입니다."); return; }
    state.worldLayout.objects = state.worldLayout.objects.filter((entry) => entry.anchor.q !== q || entry.anchor.r !== r);
    const resource = form.elements.objectResource.value.trim();
    if (reservedWorldObjectTypes.has(type)) {
      if (!/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(resource)) { toast(`${reservedWorldObjectTypes.get(type)} NBT 리소스를 선택해 주세요.`); return; }
      state.worldLayout.objects.push({ id, type, anchor: { q, r }, resource, rotation: Number(form.elements.objectRotation.value) });
      markWorldDirty(); renderWorldLayout(); return;
    }
    const gateMode = form.elements.objectGateMode.value;
    const buildingEnabled = gateMode === "classic";
    if (buildingEnabled && !/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(resource)) { toast("관문 건물 NBT 리소스 ID를 입력해 주세요."); return; }
    let properties; try { properties = gateProperties({ facing: form.elements.objectFacing.value, gateMode, surroundingType: form.elements.objectSurroundingType.value, wallBlock: form.elements.objectWallBlock.value, treeLog: form.elements.objectTreeLog.value, treeLeaves: form.elements.objectTreeLeaves.value, wallThickness: form.elements.objectWallThickness.value, wallHeight: form.elements.objectWallHeight.value, openingWidth: form.elements.objectOpeningWidth.value, barrierHeight: form.elements.objectBarrierHeight.value, conditionMode: form.elements.objectConditionMode.value, conditions: form.elements.objectConditions.value, denyMessage: form.elements.objectDenyMessage.value, npc: form.elements.objectNpc.value }); } catch (error) { toast(error.message); return; }
    const object = { id, type, anchor: { q, r }, rotation: Number(form.elements.objectRotation.value), properties };
    if (buildingEnabled) object.resource = resource;
    state.worldLayout.objects.push(object); markWorldDirty(); renderWorldLayout(); return;
  }
  const previousTile = tileAt(q, r);
  const connectionHeight = Math.max(-8, Math.min(8, Math.round(Number(form.elements.connectionHeight.value) || 0)));
  const townIndex = state.worldLayout.settlements.findIndex((node) => node.anchor?.q === q && node.anchor?.r === r);
  if (townIndex >= 0 && kind !== "settlement") {
    state.worldLayout.settlements.splice(townIndex, 1);
  }
  state.worldLayout.tiles = state.worldLayout.tiles.filter((tile) => tile.q !== q || tile.r !== r);
  if (kind === "empty") setEmptyTerrainTile(q, r, form.elements.emptyTerrainType.value);
  else state.worldLayout.empty_terrain.tiles = state.worldLayout.empty_terrain.tiles.filter((tile) => tile.q !== q || tile.r !== r);
  if (kind === "biome") {
    const tile = previousTile ? structuredClone(previousTile) : defaultWorldTile(q, r, form.elements.biome.value);
    tile.q = q; tile.r = r; tile.biome = form.elements.biome.value;
    if (form.elements.whirlpoolBoundary.value === "true" && !tile.biome.includes("ocean")) {
      toast("바다회오리 경계는 해양 바이옴 타일에만 설정할 수 있습니다.");
      return;
    }
    if (form.elements.whirlpoolBoundary.value === "true") tile.access_requirement = "cobbleventure:field_move/whirlpool";
    else if (tile.access_requirement === "cobbleventure:field_move/whirlpool") delete tile.access_requirement;
    tile.terrain_profile ||= defaultWorldTile(q, r, tile.biome).terrain_profile;
    tile.terrain_profile.connection_height = connectionHeight;
    state.worldLayout.tiles.push(tile);
  }
  if (kind === "settlement") {
    const id = form.elements.settlement.value;
    if (!id) { toast("배치할 마을을 선택해 주세요."); return; }
    let node = state.worldLayout.settlements.find((entry) => entry.settlement === id);
    const townRadius = settlementPresetRadius(id);
    const occupied = settlementAt(q, r);
    if (occupied && occupied.settlement !== id) { toast("이미 다른 마을이 배치된 타일입니다."); return; }
    const conflict = settlementRangeConflict(node, q, r, townRadius);
    if (conflict) { toast(`${settlementSummary(conflict.settlement)?.name || "다른 마을"}의 사용 범위와 겹칩니다.`); renderTileInspector(); return; }
    if (!node) { node = defaultWorldSettlement(id, q, r, form.elements.townBiome.value); state.worldLayout.settlements.push(node); }
    else node.anchor = { q, r };
    node.town_radius_cells = townRadius;
    node.town_footprint_shape = settlementPresetFootprintShape(id);
    node.town_footprint_cells = settlementPresetFootprintCells(id);
    node.town_road_exits = settlementPresetRoadExits(id);
    node.town_biome = form.elements.townBiome.value;
    node.terrain_profile ||= defaultWorldSettlement(id, q, r, node.town_biome).terrain_profile;
    node.terrain_profile.connection_height = connectionHeight;
  }
  markWorldDirty(); renderWorldLayout();
}

function clearSelectedTile() {
  const { q, r } = state.selectedHex || {}; if (q === undefined) return;
  if (objectAt(q, r)) {
    state.worldLayout.objects = state.worldLayout.objects.filter((entry) => entry.anchor.q !== q || entry.anchor.r !== r);
    markWorldDirty(); renderWorldLayout(); return;
  }
  const form = $("#tile-inspector-form"); form.elements.kind.value = "empty";
  form.elements.emptyTerrainType.value = state.worldLayout.empty_terrain?.default_type || "high_forest";
  applyTilePlacement();
}

function handleTileInspectorChange(event) {
  if (event.target.name === "musicTrack") {
    const { q, r } = state.selectedHex;
    setMusicOverride(q, r, event.target.value);
    markWorldDirty(); renderTileInspector(); return;
  }
  if (event.target.name === "kind") {
    $$('[data-tile-field]').forEach((field) => field.hidden = field.dataset.tileField !== event.target.value);
    if (event.target.value === "settlement" || event.target.value === "object") return;
  }
  applyTilePlacement();
}

function handleEntranceInspectorChange(event) {
  const selection = selectedEntrance(); if (!selection) return; const { kind, entrance } = selection; const form = event.currentTarget;
  if (event.target.name === "q" || event.target.name === "r") {
    const target = { q: Math.round(Number(form.elements.q.value) || 0), r: Math.round(Number(form.elements.r.value) || 0) };
    const occupied = caveEntranceAt(target.q, target.r) || forestEntranceAt(target.q, target.r);
    if ((occupied && occupied !== entrance) || settlementAt(target.q, target.r) || objectAt(target.q, target.r)) { toast("해당 타일에는 다른 배치가 있습니다."); renderTileInspector(); return; }
    entrance.anchor = target; state.selectedHex = { ...target }; syncRoutesForEndpoint(entrance.id);
  } else if (event.target.name === "facing") {
    entrance.facing = event.target.value;
    if (kind === "forest") entrance.rotation = forestGateRotation(entrance.facing);
  }
  else if (event.target.name === "pokemonCenterEnabled") entrance.pokemon_center_enabled = event.target.value === "true";
  else if (kind === "forest" && event.target.name === "rotation") entrance.rotation = Number(event.target.value);
  else if (kind === "forest" && event.target.name === "treeLog") entrance.tree_log = event.target.value.trim();
  else if (kind === "forest" && event.target.name === "treeLeaves") entrance.tree_leaves = event.target.value.trim();
  else if (kind === "forest" && ["wallThickness", "wallHeight", "openingWidth", "barrierHeight"].includes(event.target.name)) {
    entrance[({ wallThickness: "wall_thickness", wallHeight: "wall_height", openingWidth: "opening_width", barrierHeight: "barrier_height" })[event.target.name]] = Math.round(Number(event.target.value) || 1);
  } else return;
  markWorldDirty(); renderWorldLayout();
}

function deleteSelectedEntrance() {
  const selection = selectedEntrance(); if (!selection) return; const { kind, entrance } = selection;
  if (kind === "cave") state.worldLayout.cave_entrances = state.worldLayout.cave_entrances.filter((entry) => entry !== entrance);
  else state.worldLayout.forest_entrances = state.worldLayout.forest_entrances.filter((entry) => entry !== entrance);
  state.worldLayout.connections = (state.worldLayout.connections || []).filter((route) => route.from !== entrance.id && route.to !== entrance.id);
  state.selectedEntrance = null; markWorldDirty(); renderWorldLayout();
}

function handleRouteInspectorInput(event) {
  const route = selectedRoute(); if (!route) return;
  if (event.target.name === "displayName") {
    const value = event.target.value.trim();
    if (value) route.display_name = value; else delete route.display_name;
    $("#selected-tile-title").textContent = routeDisplayName(route);
  } else if (event.target.name === "surfaceStyle") route.surface_style = event.target.value;
  else if (event.target.name === "corridorWidth") route.corridor_width_blocks = Math.max(12, Math.min(256, Number(event.target.value) || 12));
  else if (event.target.name === "musicTrack") {
    if (event.target.value) route.music_track = event.target.value; else delete route.music_track;
  } else return;
  markWorldDirty();
  if (event.type === "change" || event.target.name !== "displayName") { renderHexMap(); renderTileInspector(); renderRouteCreator(); }
}

function beginRouteAnchorDrag(event, routeId, index, locked) {
  event.preventDefault(); event.stopPropagation();
  if (locked) {
    const route = state.worldLayout.connections.find((entry) => entry.id === routeId);
    if (route && syncRouteEndpointAnchors(route)) {
      markWorldDirty(); renderWorldLayout(); toast("이동한 연결 위치에 맞춰 길 끝점을 자동 보정했습니다.");
    } else toast("이 끝점은 연결된 마을이나 동굴 입구의 위치를 자동으로 따라갑니다.");
    return;
  }
  const owner = routeId === "__draft__" ? state.routeDraft : state.worldLayout.connections.find((entry) => entry.id === routeId);
  const anchors = (owner?.anchors?.length ? owner.anchors : connectionAnchors(owner || {})).map((anchor) => ({ ...anchor }));
  state.routeAnchorDrag = { pointerId: event.pointerId, routeId, index, moved: false, previewAnchors: anchors };
  $("#world-hex-map").setPointerCapture?.(event.pointerId);
}
function moveRouteAnchorDrag(event) {
  const drag = state.routeAnchorDrag; if (!drag || drag.pointerId !== event.pointerId) return false;
  const target = nearestHexFromPointer(event);
  const anchor = drag.previewAnchors?.[drag.index]; if (!anchor || (anchor.q === target.q && anchor.r === target.r)) return true;
  drag.previewAnchors[drag.index] = target; drag.moved = true; renderHexMap(); return true;
}
function finishRouteAnchorDrag(event) {
  const drag = state.routeAnchorDrag; if (!drag || drag.pointerId !== event.pointerId) return false;
  state.routeAnchorDrag = null;
  const owner = drag.routeId === "__draft__" ? state.routeDraft : state.worldLayout.connections.find((route) => route.id === drag.routeId);
  if (drag.moved && owner) {
    owner.anchors = drag.previewAnchors.map((anchor) => ({ ...anchor })); owner.cells = routeCellsFromAnchors(owner.anchors);
    if (drag.routeId !== "__draft__") markWorldDirty();
  }
  renderWorldLayout(); return true;
}

function beginSettlementDrag(event, settlementId) {
  event.preventDefault(); event.stopPropagation();
  if (state.activeMapTool === "route") return;
  if (state.activeMapTool !== "select") return;
  const node = state.worldLayout.settlements.find((entry) => entry.settlement === settlementId); if (!node) return;
  state.draggedSettlement = { pointerId: event.pointerId, id: settlementId, startX: event.clientX, startY: event.clientY, target: { ...node.anchor }, moved: false, valid: true };
  $("#world-hex-map").setPointerCapture?.(event.pointerId);
  $("#world-hex-map").classList.add("is-dragging");
}
function beginEntranceDrag(event, kind, id) {
  if (state.activeMapTool !== "select") return;
  event.preventDefault(); event.stopPropagation();
  const list = kind === "cave" ? state.worldLayout.cave_entrances : state.worldLayout.forest_entrances;
  const entrance = (list || []).find((entry) => entry.id === id); if (!entrance) return;
  state.entranceDrag = { pointerId: event.pointerId, kind, id, startX: event.clientX, startY: event.clientY, target: { ...entrance.anchor }, moved: false, valid: true };
  $("#world-hex-map").setPointerCapture?.(event.pointerId); $("#world-hex-map").classList.add("is-dragging");
}
function moveWorldPlacementDrag(event) {
  const settlementDrag = state.draggedSettlement;
  if (settlementDrag?.pointerId === event.pointerId) {
    const node = state.worldLayout.settlements.find((entry) => entry.settlement === settlementDrag.id); const target = nearestHexFromPointer(event);
    if (!node) return true;
    const occupied = settlementAt(target.q, target.r); const conflict = settlementRangeConflict(node, target.q, target.r, settlementPresetRadius(node.settlement));
    settlementDrag.target = target; settlementDrag.moved ||= Math.hypot(event.clientX - settlementDrag.startX, event.clientY - settlementDrag.startY) >= 4;
    settlementDrag.valid = !conflict && (!occupied || occupied === node) && !caveEntranceAt(target.q, target.r) && !forestEntranceAt(target.q, target.r) && !objectAt(target.q, target.r);
    renderHexMap(); return true;
  }
  const entranceDrag = state.entranceDrag;
  if (entranceDrag?.pointerId === event.pointerId) {
    const list = entranceDrag.kind === "cave" ? state.worldLayout.cave_entrances : state.worldLayout.forest_entrances;
    const entrance = (list || []).find((entry) => entry.id === entranceDrag.id); const target = nearestHexFromPointer(event);
    const occupied = caveEntranceAt(target.q, target.r) || forestEntranceAt(target.q, target.r);
    entranceDrag.target = target; entranceDrag.moved ||= Math.hypot(event.clientX - entranceDrag.startX, event.clientY - entranceDrag.startY) >= 4;
    entranceDrag.valid = Boolean(entrance) && (!occupied || occupied === entrance) && !settlementAt(target.q, target.r) && !objectAt(target.q, target.r);
    renderHexMap(); return true;
  }
  return false;
}
function finishEntranceDrag(event) {
  const drag = state.entranceDrag; if (!drag || drag.pointerId !== event.pointerId) return;
  state.entranceDrag = null; $("#world-hex-map").classList.remove("is-dragging");
  if (!drag.moved) { selectWorldEntrance(drag.kind, drag.id); return; }
  const list = drag.kind === "cave" ? state.worldLayout.cave_entrances : state.worldLayout.forest_entrances;
  const entrance = (list || []).find((entry) => entry.id === drag.id); const target = drag.target;
  if (!entrance || !target || !drag.valid) {
    toast("이미 마을, 오브젝트 또는 다른 입구가 배치된 타일입니다."); renderHexMap(); return;
  }
  entrance.anchor = target; syncRoutesForEndpoint(entrance.id); state.selectedHex = { ...target }; state.selectedEntrance = { kind: drag.kind, id: entrance.id };
  state.suppressMapClick = true; setTimeout(() => { state.suppressMapClick = false; }, 0);
  markWorldDirty(); renderWorldLayout();
}
function nearestHexFromPointer(event) {
  const local = mapPointFromPointer(event);
  return pixelToHex(local.x, local.y);
}
function updateBrushPreview(event) {
  if (!brushMapTools.has(state.activeMapTool) || state.spacePanActive) {
    if (state.brushPreview) { state.brushPreview = null; renderHexMap(); }
    return;
  }
  const cell = nearestHexFromPointer(event);
  if (state.brushPreview?.q === cell.q && state.brushPreview?.r === cell.r) return;
  state.brushPreview = cell;
  renderHexMap();
}
function clearBrushPreview() {
  if (!state.brushPreview) return;
  state.brushPreview = null;
  renderHexMap();
}
function beginMapPan(event) {
  updateBrushPreview(event);
  if (beginToolStroke(event)) return;
  if (event.button !== 0 || event.target.closest?.("[data-drag-settlement], [data-drag-entrance-id], [data-select-route], [data-route-anchor-index], [data-delete-route-inline]") || (state.activeMapTool !== "select" && !state.spacePanActive)) return;
  const svg = $("#world-hex-map");
  state.mapPan = { pointerId: event.pointerId, startX: event.clientX, startY: event.clientY, centerX: state.mapCenter.x, centerY: state.mapCenter.y, lastRenderX: state.mapCenter.x, lastRenderY: state.mapCenter.y, moved: false };
}
function moveMapPan(event) {
  updateBrushPreview(event);
  if (moveRouteAnchorDrag(event)) return;
  if (moveWorldPlacementDrag(event)) return;
  if (state.paintStroke) { continueToolStroke(event); return; }
  const pan = state.mapPan; if (!pan || pan.pointerId !== event.pointerId) return;
  const svg = $("#world-hex-map"); const rect = svg.getBoundingClientRect(); const view = mapViewBox();
  const dx = event.clientX - pan.startX; const dy = event.clientY - pan.startY;
  if (Math.hypot(dx, dy) > 4 && !pan.moved) {
    pan.moved = true; svg.setPointerCapture?.(event.pointerId); svg.classList.add("is-panning");
  }
  if (!pan.moved) return;
  state.mapCenter = { x: pan.centerX - dx * view.width / rect.width, y: pan.centerY - dy * view.height / rect.height };
  const next = mapViewBox(); svg.setAttribute("viewBox", `${next.x} ${next.y} ${next.width} ${next.height}`);
  if (Math.hypot(state.mapCenter.x - pan.lastRenderX, state.mapCenter.y - pan.lastRenderY) >= mapHexSize() * 2) {
    pan.lastRenderX = state.mapCenter.x; pan.lastRenderY = state.mapCenter.y;
    renderHexMap();
  }
}
function finishMapPan(event) {
  if (finishRouteAnchorDrag(event)) return;
  if (finishToolStroke(event)) return;
  const pan = state.mapPan; if (!pan || pan.pointerId !== event.pointerId) return;
  state.mapPan = null; $("#world-hex-map").classList.remove("is-panning");
  if (pan.moved) {
    state.suppressMapClick = true;
    renderHexMap();
    setTimeout(() => { state.suppressMapClick = false; }, 0);
  }
}
function finishSettlementDrag(event) {
  const drag = state.draggedSettlement; if (!drag || drag.pointerId !== event.pointerId) return;
  const target = drag.target; const node = state.worldLayout.settlements.find((entry) => entry.settlement === drag.id);
  state.draggedSettlement = null; $("#world-hex-map").classList.remove("is-dragging");
  if (!drag.moved) { if (node?.anchor) selectHex(node.anchor.q, node.anchor.r); else renderHexMap(); return; }
  const conflict = node && target ? settlementRangeConflict(node, target.q, target.r, settlementPresetRadius(node.settlement)) : null;
  if (conflict) toast(`${settlementSummary(conflict.settlement)?.name || "다른 마을"}의 사용 범위와 겹칩니다.`);
  else if (node && target && drag.valid) {
    node.anchor = target;
    syncRoutesForEndpoint(node.settlement);
    state.selectedHex = { ...target };
    state.suppressMapClick = true; setTimeout(() => { state.suppressMapClick = false; }, 0);
    markWorldDirty();
  }
  else toast("이미 다른 요소가 배치되었거나 마을 사용 범위가 겹치는 타일입니다.");
  renderWorldLayout();
}

async function addGeneration() {
  const generation = Array.from({ length: 9 }, (_, index) => index + 1).find((value) => !state.worldGenerations.includes(value));
  if (!generation) { toast("9세대까지 모두 추가되어 있습니다."); return; }
  const payload = { "$schema": "../schemas/hex-world.schema.json", schema_version: 2, id: `cobbleventure:world/generation_${generation}`, dimension: `cobbleventure:generation_${generation}`, seed_salt: 1700 + generation, grid: { orientation: "pointy_top", tile_radius_blocks: 64, map_radius_cells: 6, origin: { x: 0, y: 69, z: 0 } }, empty_terrain: { default_type: "high_forest", tiles: [] }, tiles: [], environment_overrides: [], level_overrides: [], music_overrides: [], settlements: [], cave_entrances: [], forest_entrances: [], connections: [], objects: [] };
  const result = await request(`/api/world-layout?generation=${generation}`, { method: "PUT", body: JSON.stringify(payload) });
  if (!result.ok) { toast(result.data.error || "세대를 추가하지 못했습니다."); return; }
  state.worldGenerations.push(generation); state.worldGenerations.sort((a, b) => a - b); state.selectedGeneration = generation; state.worldLayout = payload; state.selectedHex = null; state.worldDirty = false; state.mapViewInitialized = false; renderWorldLayout(); toast(`${generation}세대 월드를 추가했습니다.`);
}

function documentSingular(category) {
  return category === "trainers" ? "trainer" : category === "battles" ? "battle" : category === "caves" ? "cave" : category === "forests" ? "forest" : "settlement";
}

function renderList(category) {
  const items = state[category];
  const singular = documentSingular(category);
  $(`#${singular}-list-count`).textContent = items.length;
  const list = $(`#${singular}-list`);
  if (!items.length) { list.innerHTML = '<div class="issues empty">등록된 문서가 없습니다.</div>'; return; }
  if (category === "settlements") {
    const orderSaving = state.settlementOrderSaving;
    list.innerHTML = items.map((item, index) => `
      <article class="document-button settlement-order-row ${state.settlementPath === item.path ? "is-active" : ""}">
        <button type="button" class="settlement-order-select" data-path="${escapeHtml(item.path)}">
          <span class="settlement-order-index">${String(index + 1).padStart(2, "0")}</span>
          <span><strong>${escapeHtml(item.name || item.id || "이름 없음")}</strong><small>${escapeHtml(item.id || item.path)}</small></span>
        </button>
        <span class="settlement-order-actions">
          <button type="button" data-settlement-move="-1" data-settlement-path="${escapeHtml(item.path)}" aria-label="위로 이동" title="인게임 로드 순서를 앞으로 이동"${orderSaving || index === 0 ? " disabled" : ""}>↑</button>
          <button type="button" data-settlement-move="1" data-settlement-path="${escapeHtml(item.path)}" aria-label="아래로 이동" title="인게임 로드 순서를 뒤로 이동"${orderSaving || index === items.length - 1 ? " disabled" : ""}>↓</button>
        </span>
      </article>`).join("");
    $$("#settlement-list [data-path]").forEach((button) => button.addEventListener("click", () => loadDocument(category, button.dataset.path)));
    $$("#settlement-list [data-settlement-move]").forEach((button) => button.addEventListener("click", () => moveSettlementPreset(button.dataset.settlementPath, Number(button.dataset.settlementMove))));
    return;
  }
  list.innerHTML = items.map((item) => `
    <button class="document-button ${state[`${singular}Path`] === item.path ? "is-active" : ""}" data-path="${escapeHtml(item.path)}">
      <strong>${escapeHtml(item.name || item.id || "이름 없음")}</strong><small>${escapeHtml(item.id || item.path)}</small>
    </button>`).join("");
  $$( `#${singular}-list .document-button`).forEach((button) => button.addEventListener("click", () => loadDocument(category, button.dataset.path)));
}

async function moveSettlementPreset(path, delta) {
  if (state.settlementOrderSaving) return;
  const sourceIndex = state.settlements.findIndex((item) => item.path === path);
  const targetIndex = sourceIndex + delta;
  if (sourceIndex < 0 || targetIndex < 0 || targetIndex >= state.settlements.length) return;
  const previous = state.settlements.map((item) => ({ ...item }));
  [state.settlements[sourceIndex], state.settlements[targetIndex]] = [state.settlements[targetIndex], state.settlements[sourceIndex]];
  state.settlements.forEach((item, index) => { item.load_order = index + 1; });
  state.settlementOrderSaving = true;
  renderList("settlements");
  const result = await request("/api/settlements/order", {
    method: "POST",
    body: JSON.stringify({ ids: state.settlements.map((item) => item.id) })
  });
  state.settlementOrderSaving = false;
  if (!result.ok) {
    state.settlements = previous;
    renderList("settlements");
    toast(result.data.error || "마을 순서를 저장하지 못했습니다.");
    return;
  }
  if (state.settlement) state.settlement.load_order = state.settlements.find((item) => item.id === state.settlement.id)?.load_order || state.settlement.load_order;
  renderList("settlements");
  toast("마을 편집 순서와 인게임 로드 순서를 저장했습니다.");
}

async function loadDocument(category, path) {
  const singular = documentSingular(category);
  const result = await request(`/api/${category}?path=${encodeURIComponent(path)}`);
  if (!result.ok) { toast(result.data.error || "문서를 불러오지 못했습니다."); return; }
  if (category === "battles") {
    state.battlePreset = result.data.document;
    state.battlePath = result.data.path;
  } else {
    state[singular] = result.data.document;
    state[`${singular}Path`] = result.data.path;
  }
  if (category === "forests") { state.forestPreview.selectedPath = null; state.forestPreview.selectedAnchor = null; state.forestPreview.selectedEntranceIndex = null; state.forestPreview.zoom = 1; state.forestPreview.panX = 0; state.forestPreview.panY = 0; state.forestPreview.brushHover = null; }
  if (category === "trainers") {
    state.battlePreset = null;
    state.battlePath = "";
    if (result.data.document.schema_version === 3) {
      const battleRef = result.data.document.interaction?.nodes
        ?.flatMap((node) => [...(node.actions || []), ...(node.choices || []).flatMap((choice) => choice.actions || [])])
        .find((action) => action.type === "start_battle")?.battle;
      const summary = state.battles.find((entry) => entry.id === battleRef);
      if (summary) {
        const battleResult = await request(`/api/battles?path=${encodeURIComponent(summary.path)}`);
        if (battleResult.ok) {
          state.battlePreset = battleResult.data.document;
          state.battlePath = battleResult.data.path;
        }
      }
    }
  }
  renderList(category);
  if (category === "trainers") renderTrainer();
  else if (category === "battles") renderBattlePreset();
  else if (category === "caves") renderCave();
  else if (category === "forests") renderForest();
  else renderSettlement();
}

function trainerBattle() {
  return state.battlePreset?.battle || state.trainer?.battle || null;
}

function renderBattlePreset() {
  const document = state.battlePreset;
  const form = $("#battle-form");
  if (!document?.battle) return;
  const battle = document.battle;
  const ai = normalizeTrainerAi(battle);
  battle.ai = ai;
  $("#battle-editor-title").textContent = document.name?.ko_kr || document.id;
  $("#battle-path").textContent = state.battlePath;
  setFormValue(form, "id", document.id);
  setFormValue(form, "nameKo", document.name?.ko_kr || "");
  setFormValue(form, "trainerId", battle.trainer_id || "");
  form.elements.musicTrack.innerHTML = musicOptions(battle.music_track || "", "battle");
  setFormValue(form, "musicTrack", battle.music_track || "");
  setFormValue(form, "format", battle.format || "GEN_9_SINGLES");
  setFormValue(form, "battleType", battle.battle_type || "singles");
  setFormValue(form, "difficulty", ai.difficulty);
  setFormValue(form, "strategy", ai.strategy);
  setFormValue(form, "levelMode", battle.level_mode || "fixed");
  setFormValue(form, "megaEvolution", Boolean(battle.mechanics?.mega_evolution));
  setFormValue(form, "zMove", Boolean(battle.mechanics?.z_move));
  setFormValue(form, "dynamax", Boolean(battle.mechanics?.dynamax));
  setFormValue(form, "terastallization", Boolean(battle.mechanics?.terastallization));
  $("#max-item-uses").value = Number.isInteger(battle.rules?.max_item_uses) ? battle.rules.max_item_uses : "";
  $("#battle-json").value = JSON.stringify(document, null, 2);
  [...form.elements].forEach((element) => { element.disabled = false; });
  form.elements.id.disabled = true;
  ["#battle-json", "#apply-battle-json", "#delete-battle", "#validate-battle", "#save-battle", "#max-item-uses", "#add-bag-item", "#load-trainer-reference", "#copy-team-json", "#paste-team-json", "#add-pokemon"].forEach((selector) => { $(selector).disabled = false; });
  renderBag();
  renderTeam();
  showIssues("#battle-issues", { valid: true, issues: [] });
}

function updateBattlePresetFromForm() {
  if (!state.battlePreset?.battle) return false;
  const form = $("#battle-form");
  const battle = state.battlePreset.battle;
  state.battlePreset.name = { ...(state.battlePreset.name || {}), ko_kr: form.elements.nameKo.value.trim() };
  battle.trainer_id = form.elements.trainerId.value.trim();
  if (form.elements.musicTrack.value) battle.music_track = form.elements.musicTrack.value;
  else delete battle.music_track;
  battle.format = form.elements.format.value;
  battle.battle_type = form.elements.battleType.value;
  battle.level_mode = form.elements.levelMode.value;
  battle.ai = {
    controller: "cobbleventure",
    difficulty: form.elements.difficulty.value,
    strategy: form.elements.strategy.value,
    options: form.elements.difficulty.value === "cheater"
      ? { cheat_probability: battle.ai?.options?.cheat_probability ?? 0.5 }
      : {},
  };
  battle.mechanics = {
    mega_evolution: form.elements.megaEvolution.checked,
    z_move: form.elements.zMove.checked,
    dynamax: form.elements.dynamax.checked,
    terastallization: form.elements.terastallization.checked,
  };
  battle.rules ||= {};
  const maxItemUses = $("#max-item-uses").value.trim();
  if (maxItemUses === "") delete battle.rules.max_item_uses;
  else battle.rules.max_item_uses = Math.max(0, Number.parseInt(maxItemUses, 10) || 0);
  $("#battle-json").value = JSON.stringify(state.battlePreset, null, 2);
  return true;
}

function generationFromDocumentPath(path) {
  const match = String(path || "").match(/(?:^|\/)generation_(\d+)(?:\/|$)/);
  return match ? Number(match[1]) : state.selectedGeneration || 1;
}

function dimensionSizeLabel(bounds) {
  const width = Math.max(0, Number(bounds?.max_x ?? 0) - Number(bounds?.min_x ?? 0));
  const depth = Math.max(0, Number(bounds?.max_z ?? 0) - Number(bounds?.min_z ?? 0));
  return `${width.toLocaleString()} × ${depth.toLocaleString()} 블록`;
}

function derivedAlignedBounds(extents, padding, minimumSize, alignment = 16) {
  if (!extents.length) extents.push({ minX: 0, minZ: 0, maxX: 0, maxZ: 0 });
  let minX = Math.floor((Math.min(...extents.map((item) => item.minX)) - padding) / alignment) * alignment;
  let minZ = Math.floor((Math.min(...extents.map((item) => item.minZ)) - padding) / alignment) * alignment;
  let maxX = Math.ceil((Math.max(...extents.map((item) => item.maxX)) + padding) / alignment) * alignment;
  let maxZ = Math.ceil((Math.max(...extents.map((item) => item.maxZ)) + padding) / alignment) * alignment;
  const expand = (minimum, maximum) => { const missing = Math.max(0, minimumSize - (maximum - minimum)), before = Math.ceil((missing / 2) / alignment) * alignment, after = Math.ceil((missing - before) / alignment) * alignment; return [minimum - before, maximum + after]; };
  [minX, maxX] = expand(minX, maxX); [minZ, maxZ] = expand(minZ, maxZ);
  return { min_x: minX, min_z: minZ, max_x: maxX, max_z: maxZ };
}

function leastCommonMultiple(left, right) {
  const gcd = (a, b) => b ? gcd(b, a % b) : Math.abs(a);
  return Math.abs(left * right) / gcd(left, right);
}

function caveBuildBounds(document = state.cave) {
  const origin = document?.dimension?.origin || {}, extents = [{ minX: Number(origin.x || 0), minZ: Number(origin.z || 0), maxX: Number(origin.x || 0), maxZ: Number(origin.z || 0) }];
  for (const entrance of document?.entrances || []) for (const field of ["destination_anchor", "fallback_anchor"]) { const point = entrance[field]; if (!point) continue; const x = Number(point.x || 0), z = Number(point.z || 0); extents.push({ minX: x - 4, minZ: z - 4, maxX: x + 4, maxZ: z + 4 }); }
  const generator = document?.generator || {}, manual = generator.manual_layout || {};
  for (const anchor of manual.anchors || []) { const point = anchor.position || {}, x = Number(point.x || 0), z = Number(point.z || 0), radiusX = Math.max(1, Number(anchor.radius_x || 12)), radiusZ = Math.max(1, Number(anchor.radius_z || 12)); extents.push({ minX: x - radiusX, minZ: z - radiusZ, maxX: x + radiusX, maxZ: z + radiusZ }); }
  const padding = Math.max(16, Math.ceil(Number(generator.tunnel_radius?.max || 7) * 2)), minimumSize = manual.enabled ? 64 : Math.max(128, Number(generator.main_rooms || 7) * 24);
  return derivedAlignedBounds(extents, padding, minimumSize);
}

function forestBuildBounds(document = state.forest) {
  const origin = document?.dimension?.origin || {}, extents = [{ minX: Number(origin.x || 0), minZ: Number(origin.z || 0), maxX: Number(origin.x || 0), maxZ: Number(origin.z || 0) }];
  for (const route of document?.paths || []) { const radius = Math.max(1, Number(route.width || 5) / 2); for (const point of route.points || []) { const x = Number(point.x || 0), z = Number(point.z || 0); extents.push({ minX: x - radius, minZ: z - radius, maxX: x + radius, maxZ: z + radius }); } }
  for (const entrance of document?.entrances || []) { const x = Number(entrance.position?.x || 0), z = Number(entrance.position?.z || 0); extents.push({ minX: x - 8, minZ: z - 8, maxX: x + 8, maxZ: z + 8 }); }
  const cell = Math.max(4, Math.min(64, Math.round(Number(document?.generator?.cell_size) || 16)));
  for (const tile of document?.terrain_tiles || []) { const x = Number(tile.x || 0), z = Number(tile.z || 0); extents.push({ minX: x - cell / 2, minZ: z - cell / 2, maxX: x + cell / 2, maxZ: z + cell / 2 }); }
  const padding = Math.max(16, cell * 2, Number(document?.tree_barrier?.max_height || 16));
  return derivedAlignedBounds(extents, padding, Math.max(64, cell * 4), leastCommonMultiple(16, cell));
}

function syncCaveBuildBounds() { if (state.cave?.dimension) state.cave.dimension.bounds = caveBuildBounds(state.cave); }
function syncForestBuildBounds() { if (state.forest?.dimension) state.forest.dimension.bounds = forestBuildBounds(state.forest); }

function renderCaveDimensionSummary() {
  if (!state.cave) return;
  const bounds = state.cave.dimension?.bounds || {};
  $("#cave-dimension-size").textContent = dimensionSizeLabel(bounds);
}

function renderCave() {
  const document = state.cave; const form = $("#cave-form");
  if (!document) return;
  delete document.generation;
  const generation = generationFromDocumentPath(state.cavePath);
  const generator = { layout: "natural_network", seed_salt: 0, main_rooms: 7, branch_count: 4, loop_chance: .35, vertical_range: 28, room_radius: { min: 10, max: 28 }, tunnel_radius: { min: 4, max: 7 }, surface_roughness: .18, water_level: 38, grand_room_scale: 1.65, elevated_crossing: false, bridge_clearance: 13, ...(document.generator || {}) };
  delete generator.lake_radius; delete generator.lake_depth;
  delete generator.room_types; delete document.internal_biomes;
  generator.room_radius = { min: 10, max: 28, ...(document.generator?.room_radius || {}) }; generator.tunnel_radius = { min: 4, max: 7, ...(document.generator?.tunnel_radius || {}) };
  generator.manual_layout = { enabled: false, anchors: [], connections: [], ...(document.generator?.manual_layout || {}) };
  document.generator = generator;
  const dimension = document.dimension || {}; const origin = dimension.origin || {}; const bounds = dimension.bounds || {};
  document.dimension = { id: "cobbleventure:dungeons", region_id: `generation_${generation}/${document.id?.split("/").pop() || "cave"}`, origin: { x: Number(origin.x ?? 0), y: Number(origin.y ?? 48), z: Number(origin.z ?? 0) }, bounds: { min_x: Number(bounds.min_x ?? -256), min_z: Number(bounds.min_z ?? -256), max_x: Number(bounds.max_x ?? 256), max_z: Number(bounds.max_z ?? 256) } };
  syncCaveBuildBounds();
  $("#selected-cave-editor").hidden = false; $("#cave-editor-title").textContent = document.display_name?.ko_kr || document.id; $("#cave-path").textContent = state.cavePath;
  setFormValue(form, "id", document.id); setFormValue(form, "nameKo", document.display_name?.ko_kr);
  setFormValue(form, "nameEn", document.display_name?.en_us || ""); setFormValue(form, "enabled", document.enabled !== false);
  setFormValue(form, "caveType", document.cave_type);
  setFormValue(form, "caveStyle", document.style || "rock");
  const encounters = ensureEncounterSettings(document, "minecraft:dripstone_caves", 5, 10);
  setFormValue(form, "requiresFlash", Boolean(document.requires_flash)); setFormValue(form, "randomEncounters", encounters.enabled);
  setFormValue(form, "encounterMinDistance", encounters.minimum_distance); setFormValue(form, "encounterMaxDistance", encounters.maximum_distance);
  setFormValue(form, "encounterMinLevel", encounters.minimum_level); setFormValue(form, "encounterMaxLevel", encounters.maximum_level);
  encounterBiomeOptions(form.elements.encounterPokemonBiome, encounters.pokemon_biome);
  setFormValue(form, "trainersEnabled", Boolean(document.trainer_settings?.enabled)); setFormValue(form, "maxActiveTrainers", document.trainer_settings?.max_active ?? 0);
  setFormValue(form, "trainerClassPool", (document.trainer_settings?.class_pool || []).join(", "));
  renderCaveDimensionSummary();
  renderEncounterSummary("cave");
  renderCaveArrayEditors();
  renderCaveManualLayoutEditors();
  renderCaveLayoutPreview();
  ["#delete-cave", "#validate-cave", "#save-cave"].forEach((selector) => $(selector).disabled = false);
  showIssues("#cave-issues", { valid: true, issues: [] });
}
function updateCaveFromForm() {
  if (!state.cave) return false; const form = $("#cave-form");
  state.cave.id = form.elements.id.value.trim(); state.cave.display_name = { ...(state.cave.display_name || {}), ko_kr: form.elements.nameKo.value.trim() };
  const nameEn = form.elements.nameEn.value.trim(); if (nameEn) state.cave.display_name.en_us = nameEn; else delete state.cave.display_name.en_us;
  state.cave.enabled = form.elements.enabled.checked;
  state.cave.cave_type = form.elements.caveType.value;
  state.cave.style = form.elements.caveStyle.value;
  state.cave.requires_flash = form.elements.requiresFlash.checked;
  const encounters = encounterSettings("cave");
  Object.assign(encounters, { enabled: form.elements.randomEncounters.checked, minimum_distance: Math.round(Number(form.elements.encounterMinDistance.value || 72)), maximum_distance: Math.round(Number(form.elements.encounterMaxDistance.value || 128)), minimum_level: Math.round(Number(form.elements.encounterMinLevel.value || 5)), maximum_level: Math.round(Number(form.elements.encounterMaxLevel.value || 10)), pokemon_biome: form.elements.encounterPokemonBiome.value });
  state.cave.trainer_settings ||= { placements: [] }; state.cave.trainer_settings.enabled = form.elements.trainersEnabled.checked; state.cave.trainer_settings.max_active = Number(form.elements.maxActiveTrainers.value || 0); state.cave.trainer_settings.class_pool = form.elements.trainerClassPool.value.split(",").map((value) => value.trim()).filter(Boolean); state.cave.trainer_settings.placements ||= [];
  const generation = generationFromDocumentPath(state.cavePath); state.cave.dimension.id = "cobbleventure:dungeons"; state.cave.dimension.region_id = `generation_${generation}/${state.cave.id.split("/").pop() || "cave"}`;
  state.cave.trainer_settings.placements = $$("#cave-trainer-list [data-cave-trainer-row]").map((row) => { const entry = { id: row.querySelector('[data-field="id"]').value.trim(), trainer_id: row.querySelector('[data-field="trainer_id"]').value.trim(), position: { x: Number(row.querySelector('[data-field="x"]').value), y: Number(row.querySelector('[data-field="y"]').value), z: Number(row.querySelector('[data-field="z"]').value) } }; const progress = row.querySelector('[data-field="required_progress"]').value.trim(); if (progress) entry.required_progress = progress; return entry; });
  syncCaveBuildBounds(); renderCaveDimensionSummary();
  renderEncounterSummary("cave");
  return true;
}

function renderCaveGeneratorDialogForm() {
  if (!state.cave) return;
  const form = $("#cave-generator-dialog-form");
  const generator = state.cave.generator || {};
  setFormValue(form, "generatorLayout", generator.layout || "natural_network");
  setFormValue(form, "seedSalt", generator.seed_salt ?? 0);
  setFormValue(form, "mainRooms", generator.main_rooms ?? 7);
  setFormValue(form, "branchCount", generator.branch_count ?? 4);
  setFormValue(form, "loopChance", generator.loop_chance ?? .35);
  setFormValue(form, "verticalRange", generator.vertical_range ?? 28);
  setFormValue(form, "roomRadiusMin", generator.room_radius?.min ?? 10);
  setFormValue(form, "roomRadiusMax", generator.room_radius?.max ?? 28);
  setFormValue(form, "tunnelRadiusMin", generator.tunnel_radius?.min ?? 4);
  setFormValue(form, "tunnelRadiusMax", generator.tunnel_radius?.max ?? 7);
  setFormValue(form, "surfaceRoughness", generator.surface_roughness ?? .18);
  setFormValue(form, "waterLevel", generator.water_level ?? 38);
  setFormValue(form, "grandRoomScale", generator.grand_room_scale ?? 1.65);
  setFormValue(form, "elevatedCrossing", Boolean(generator.elevated_crossing));
  setFormValue(form, "bridgeClearance", generator.bridge_clearance ?? 13);
}

function openCaveGeneratorDialog() {
  if (!state.cave) { toast("먼저 동굴을 선택해 주세요."); return; }
  updateCaveFromForm();
  renderCaveGeneratorDialogForm();
  $("#cave-generator-dialog").showModal();
}

function applyCaveGeneratorDialog() {
  if (!state.cave) return;
  const form = $("#cave-generator-dialog-form");
  if (!form.reportValidity()) return;
  const current = state.cave.generator || {};
  const manual = current.manual_layout || { anchors: [], connections: [] };
  const roomMinimum = Math.max(2, Math.min(64, forestNumber(form, "roomRadiusMin", 10)));
  const roomMaximum = Math.max(roomMinimum, Math.min(96, forestNumber(form, "roomRadiusMax", 28)));
  const tunnelMinimum = Math.max(2, Math.min(32, forestNumber(form, "tunnelRadiusMin", 4)));
  const tunnelMaximum = Math.max(tunnelMinimum, Math.min(48, forestNumber(form, "tunnelRadiusMax", 7)));
  state.cave.generator = {
    ...current,
    layout: form.elements.generatorLayout.value,
    seed_salt: Math.trunc(forestNumber(form, "seedSalt", 0)),
    main_rooms: Math.max(3, Math.min(32, Math.round(forestNumber(form, "mainRooms", 7)))),
    branch_count: Math.max(0, Math.min(16, Math.round(forestNumber(form, "branchCount", 4)))),
    loop_chance: Math.max(0, Math.min(1, forestNumber(form, "loopChance", .35))),
    vertical_range: Math.max(8, Math.min(96, Math.round(forestNumber(form, "verticalRange", 28)))),
    room_radius: { min: roomMinimum, max: roomMaximum },
    tunnel_radius: { min: tunnelMinimum, max: tunnelMaximum },
    surface_roughness: Math.max(0, Math.min(1, forestNumber(form, "surfaceRoughness", .18))),
    water_level: Math.max(1, Math.min(250, Math.round(forestNumber(form, "waterLevel", 38)))),
    grand_room_scale: Math.max(1, Math.min(3, forestNumber(form, "grandRoomScale", 1.65))),
    elevated_crossing: form.elements.elevatedCrossing.checked,
    bridge_clearance: Math.max(7, Math.min(32, Math.round(forestNumber(form, "bridgeClearance", 13)))),
    manual_layout: { ...manual, enabled: false, anchors: manual.anchors || [], connections: manual.connections || [] }
  };
  state.cavePreview.selected = null;
  setCavePreviewTool("select");
  $("#cave-generator-dialog").close();
  renderCaveLayoutPreview();
  toast("자동 동굴 배치를 생성했습니다. 동굴 저장 시 파일에 반영됩니다.");
}

function renderCaveArrayEditors() {
  const trainers = state.cave?.trainer_settings?.placements || [];
  $("#cave-trainer-list").innerHTML = trainers.length ? trainers.map((entry, index) => `<article class="cave-entry-card" data-cave-trainer-row data-index="${index}"><header><strong>트레이너 ${index + 1}</strong><button type="button" data-remove-cave-trainer="${index}">삭제</button></header><div class="cave-entry-fields"><label><span>배치 ID</span><input data-field="id" value="${escapeHtml(entry.id || "")}" required></label><label class="span-2"><span>트레이너 ID</span><input data-field="trainer_id" value="${escapeHtml(entry.trainer_id || "")}" required></label><label><span>필요 진행도</span><input data-field="required_progress" value="${escapeHtml(entry.required_progress || "")}" placeholder="선택 사항"></label><label><span>X</span><input data-field="x" type="number" value="${Number(entry.position?.x ?? 0)}"></label><label><span>Y</span><input data-field="y" type="number" value="${Number(entry.position?.y ?? 48)}"></label><label><span>Z</span><input data-field="z" type="number" value="${Number(entry.position?.z ?? 0)}"></label></div></article>`).join("") : '<div class="cave-entry-empty">고정 트레이너 배치가 없습니다.</div>';
}

function renderCaveManualLayoutEditors() {
  // 공동과 통로는 3D 지도 및 오른쪽 속성 패널에서 직접 편집합니다.
}

function handleCaveEditorClick(event) {
  if (!state.cave) return;
  const selectEntrance = event.target.closest("[data-select-cave-entrance]");
  if (selectEntrance) {
    state.cavePreview.selected = { source: "entrance", id: selectEntrance.dataset.selectCaveEntrance };
    setCavePreviewTool("select"); renderCaveLayoutPreview();
    return;
  }
  const addTrainer = event.target.closest("[data-add-cave-trainer]");
  const removeTrainer = event.target.closest("[data-remove-cave-trainer]");
  if (!(addTrainer || removeTrainer)) return;
  updateCaveFromForm();
  state.cave.entrances ||= [];
  if (addTrainer) { state.cave.trainer_settings ||= { enabled: true, max_active: 1, class_pool: [], placements: [] }; state.cave.trainer_settings.placements.push({ id: `trainer_${state.cave.trainer_settings.placements.length + 1}`, trainer_id: "cobbleventure:trainer/", position: { x: 0, y: 49, z: 0 } }); }
  if (removeTrainer) state.cave.trainer_settings.placements.splice(Number(removeTrainer.dataset.removeCaveTrainer), 1);
  renderCaveArrayEditors();
  renderCaveManualLayoutEditors();
  renderCaveLayoutPreview();
}

function forestNumber(form, name, fallback = 0) {
  const value = Number(form.elements[name].value);
  return Number.isFinite(value) ? value : fallback;
}

function renderForestDimensionSummary() {
  if (!state.forest) return;
  const dimension = state.forest.dimension || {}; const origin = dimension.origin || {}; const bounds = dimension.bounds || {};
  $("#forest-dimension-size").textContent = dimensionSizeLabel(bounds);
  $("#forest-origin-y").textContent = `Y ${Number(origin.y ?? 69).toLocaleString()}`;
}

const forestEnvironmentPresets = {
  sparse: { label: "성긴 숲", minHeight: 6, maxHeight: 11, trunks: ["minecraft:oak_log"], foliage: ["minecraft:oak_leaves"], density: .35, clearance: 4, undergrowth: ["minecraft:short_grass", "minecraft:fern"] },
  balanced: { label: "보통 숲", minHeight: 8, maxHeight: 16, trunks: ["minecraft:oak_log"], foliage: ["minecraft:oak_leaves"], density: .65, clearance: 2, undergrowth: ["minecraft:short_grass", "minecraft:fern", "minecraft:tall_grass"] },
  dense: { label: "우거진 숲", minHeight: 12, maxHeight: 22, trunks: ["minecraft:spruce_log"], foliage: ["minecraft:spruce_leaves"], density: .82, clearance: 2, undergrowth: ["minecraft:short_grass", "minecraft:fern", "minecraft:tall_grass"] },
};

function forestEnvironmentPresetKey() {
  if (!state.forest) return null;
  const trees = state.forest.tree_barrier || {}, undergrowth = state.forest.undergrowth || {};
  const sameList = (left, right) => JSON.stringify(left || []) === JSON.stringify(right || []);
  return Object.entries(forestEnvironmentPresets).find(([, preset]) => Number(trees.min_height) === preset.minHeight && Number(trees.max_height) === preset.maxHeight && sameList(trees.trunk_blocks, preset.trunks) && sameList(trees.foliage_blocks, preset.foliage) && Number(undergrowth.density) === preset.density && Number(undergrowth.path_clearance) === preset.clearance && sameList(undergrowth.blocks, preset.undergrowth))?.[0] || null;
}

function renderForestEnvironmentPreset() {
  const selected = forestEnvironmentPresetKey();
  $$('[data-forest-environment-preset]').forEach((button) => { const active = button.dataset.forestEnvironmentPreset === selected; button.classList.toggle("is-active", active); button.setAttribute("aria-pressed", String(active)); });
  const status = $("#forest-environment-preset-status");
  if (status) status.textContent = selected ? `${forestEnvironmentPresets[selected].label} 설정을 사용 중입니다.` : "직접 조절한 세부 설정을 사용 중입니다.";
}

function applyForestEnvironmentPreset(key) {
  const preset = forestEnvironmentPresets[key], form = $("#forest-form");
  if (!preset || !state.forest) return;
  setFormValue(form, "treeMinHeight", preset.minHeight); setFormValue(form, "treeMaxHeight", preset.maxHeight);
  setFormValue(form, "trunkBlocks", preset.trunks.join(", ")); setFormValue(form, "foliageBlocks", preset.foliage.join(", ")); setFormValue(form, "barrierBlock", "minecraft:barrier");
  setFormValue(form, "undergrowthDensity", preset.density); setFormValue(form, "pathClearance", preset.clearance); setFormValue(form, "undergrowthBlocks", preset.undergrowth.join(", "));
  updateForestFromForm(); renderForestEnvironmentPreset(); renderForestPreview(); toast(`${preset.label} 설정을 적용했습니다.`);
}

function renderForest() {
  const document = state.forest; const form = $("#forest-form");
  if (!document) return;
  delete document.generation;
  const generation = generationFromDocumentPath(state.forestPath);
  const dimension = document.dimension || {}; const origin = dimension.origin || {}; const bounds = dimension.bounds || {};
  document.dimension = { id: "cobbleventure:forests", region_id: `generation_${generation}/${document.id?.split("/").pop() || "forest"}`, origin: { x: Number(origin.x ?? 0), y: Number(origin.y ?? 69), z: Number(origin.z ?? 0) }, bounds: { min_x: Number(bounds.min_x ?? -256), min_z: Number(bounds.min_z ?? -256), max_x: Number(bounds.max_x ?? 256), max_z: Number(bounds.max_z ?? 256) } };
  const environment = { fixed_time: 6000, weather: "clear", ...(document.environment || {}) };
  const encounters = ensureEncounterSettings(document, "minecraft:old_growth_spruce_taiga", 3, 7);
  const trees = { min_height: 8, max_height: 16, trunk_blocks: ["minecraft:oak_log"], foliage_blocks: ["minecraft:oak_leaves"], barrier_block: "minecraft:barrier", ...(document.tree_barrier || {}) };
  const undergrowth = { density: .72, blocks: ["minecraft:short_grass", "minecraft:fern"], path_clearance: 2, ...(document.undergrowth || {}) };
  const generator = { layout: "hybrid", seed_salt: 0, cell_size: 16, maze_complexity: .65, loop_chance: .18, spline_enabled: true, spline_tension: .45, ...(document.generator || {}) };
  document.paths = (document.paths || []).map((path) => ({ ...path, kind: path.kind || (path.id === "main" ? "main" : path.id?.startsWith("shortcut_") ? "shortcut" : "manual"), points: path.points || [], spline: { enabled: path.spline?.enabled !== false, tension: generator.spline_tension } }));
  document.tree_barrier = { ...trees };
  document.undergrowth = { ...undergrowth };
  document.terrain_tiles ||= [];
  delete document.one_way_walls;
  document.entrances ||= [];
  $("#forest-editor-title").textContent = document.display_name?.ko_kr || document.id; $("#forest-path").textContent = state.forestPath;
  setFormValue(form, "id", document.id); setFormValue(form, "nameKo", document.display_name?.ko_kr || ""); setFormValue(form, "nameEn", document.display_name?.en_us || ""); setFormValue(form, "enabled", document.enabled !== false);
  setFormValue(form, "fixedTime", environment.fixed_time); setFormValue(form, "weather", environment.weather);
  setFormValue(form, "randomEncounters", encounters.enabled); setFormValue(form, "encounterMinDistance", encounters.minimum_distance); setFormValue(form, "encounterMaxDistance", encounters.maximum_distance);
  setFormValue(form, "encounterMinLevel", encounters.minimum_level); setFormValue(form, "encounterMaxLevel", encounters.maximum_level); encounterBiomeOptions(form.elements.encounterPokemonBiome, encounters.pokemon_biome);
  setFormValue(form, "treeMinHeight", trees.min_height); setFormValue(form, "treeMaxHeight", trees.max_height); setFormValue(form, "trunkBlocks", trees.trunk_blocks.join(", ")); setFormValue(form, "foliageBlocks", trees.foliage_blocks.join(", ")); setFormValue(form, "barrierBlock", trees.barrier_block);
  setFormValue(form, "undergrowthDensity", undergrowth.density); setFormValue(form, "undergrowthBlocks", undergrowth.blocks.join(", ")); setFormValue(form, "pathClearance", undergrowth.path_clearance);
  renderForestEnvironmentPreset();
  renderEncounterSummary("forest");
  generator.cell_size = Math.max(Math.max(4, Math.min(64, Math.round(Number(generator.cell_size) || 16))), forestMinimumMazeCellSize(document)); document.generator = { ...(document.generator || {}), ...generator };
  syncForestBuildBounds(); renderForestDimensionSummary();
  renderForestMazeDialogForm();
  [...form.elements].forEach((element) => { element.disabled = false; }); form.elements.id.disabled = true;
  ["#delete-forest", "#validate-forest", "#save-forest"].forEach((selector) => { $(selector).disabled = false; });
  $("#forest-height-brush-radius").value = String(state.forestPreview.heightBrushRadius); $("#forest-height-brush-size").textContent = `${state.forestPreview.heightBrushRadius * 2 + 1}×${state.forestPreview.heightBrushRadius * 2 + 1}`;
  $("#forest-height-brush-target").value = String(state.forestPreview.heightBrushTarget); renderForestHeightTarget();
  if (state.forestPreview.selectedPath != null && !document.paths[state.forestPreview.selectedPath]) state.forestPreview.selectedPath = null;
  if (state.forestPreview.selectedAnchor && !document.paths[state.forestPreview.selectedAnchor.pathIndex]?.points?.[state.forestPreview.selectedAnchor.pointIndex]) state.forestPreview.selectedAnchor = null;
  if (state.forestPreview.selectedEntranceIndex != null && !document.entrances[state.forestPreview.selectedEntranceIndex]) state.forestPreview.selectedEntranceIndex = null;
  renderForestEditors(); renderForestPreview(); showIssues("#forest-issues", { valid: true, issues: [] });
}

function updateForestFromForm() {
  if (!state.forest) return false; const form = $("#forest-form"); const document = state.forest;
  const csv = (name) => form.elements[name].value.split(",").map((value) => value.trim()).filter(Boolean);
  document.display_name = { ko_kr: form.elements.nameKo.value.trim(), ...(form.elements.nameEn.value.trim() ? { en_us: form.elements.nameEn.value.trim() } : {}) };
  document.enabled = form.elements.enabled.checked;
  document.environment = { fixed_time: forestNumber(form, "fixedTime", 6000), weather: form.elements.weather.value };
  const encounters = encounterSettings("forest");
  Object.assign(encounters, { enabled: form.elements.randomEncounters.checked, minimum_distance: Math.round(forestNumber(form, "encounterMinDistance", 72)), maximum_distance: Math.round(forestNumber(form, "encounterMaxDistance", 128)), minimum_level: Math.round(forestNumber(form, "encounterMinLevel", 3)), maximum_level: Math.round(forestNumber(form, "encounterMaxLevel", 7)), pokemon_biome: form.elements.encounterPokemonBiome.value });
  document.tree_barrier = { min_height: forestNumber(form, "treeMinHeight", 8), max_height: forestNumber(form, "treeMaxHeight", 16), trunk_blocks: csv("trunkBlocks"), foliage_blocks: csv("foliageBlocks"), barrier_block: form.elements.barrierBlock.value.trim() };
  document.undergrowth = { density: forestNumber(form, "undergrowthDensity", .72), blocks: csv("undergrowthBlocks"), path_clearance: forestNumber(form, "pathClearance", 2) };
  const generation = generationFromDocumentPath(state.forestPath); document.dimension.id = "cobbleventure:forests"; document.dimension.region_id = `generation_${generation}/${document.id.split("/").pop() || "forest"}`;
  document.generator ||= { layout: "hybrid", seed_salt: 0, cell_size: 16, maze_complexity: .65, loop_chance: .18, spline_enabled: true, spline_tension: .45 };
  const minimumCellSize = forestMinimumMazeCellSize(document); const cellSize = Math.max(Math.round(Number(document.generator.cell_size) || 16), minimumCellSize);
  document.generator.cell_size = cellSize;
  document.paths.forEach((route) => { route.spline = { enabled: route.spline?.enabled !== false, tension: document.generator.spline_tension }; });
  document.paths.forEach((route) => { route.points = route.points.map(clampForestPoint); });
  document.entrances.forEach((entrance) => { entrance.position = clampForestPoint(entrance.position); });
  const terrainByPosition = new Map(); document.terrain_tiles.forEach((tile) => { const point = snapForestTilePoint(tile); terrainByPosition.set(`${point.x},${point.z}`, { ...point, height_offset: tile.height_offset, ...(tile.transition ? { transition: { ...tile.transition } } : {}) }); }); document.terrain_tiles = [...terrainByPosition.values()];
  syncForestBuildBounds(); renderForestDimensionSummary();
  renderEncounterSummary("forest");
  return true;
}

function renderForestEditors() {
  if (!state.forest) return;
  $(".forest-layout-editor")?.setAttribute("data-active-tool", state.forestPreview.tool || "select");
  const selectedPathIndex = state.forestPreview.selectedEntranceIndex == null ? state.forestPreview.selectedPath : null;
  const selectedPath = selectedPathIndex == null ? null : state.forest.paths[selectedPathIndex];
  const selectedEntranceIndex = state.forestPreview.selectedEntranceIndex;
  const selectedEntrance = selectedEntranceIndex == null ? null : state.forest.entrances[selectedEntranceIndex];
  $("#forest-selection-empty").hidden = Boolean(selectedPath || selectedEntrance);
  $("#forest-path-properties").hidden = !selectedPath;
  $("#forest-entrance-properties").hidden = !selectedEntrance;
  if (selectedPath) { const first = selectedPath.points[0], last = selectedPath.points.at(-1), endpoints = first && last ? `${first.x},${first.z} → ${last.x},${last.z}` : "지도에서 시작점 지정"; $("#forest-path-list").innerHTML = `<article class="forest-item-card is-active" data-forest-path-card="${selectedPathIndex}"><header><strong>${escapeHtml(selectedPath.id)}</strong><button type="button" data-remove-forest-path="${selectedPathIndex}">삭제</button></header><div class="forest-item-fields"><label><span>길 역할</span><select data-forest-path-field="kind"><option value="main" ${selectedPath.kind === "main" ? "selected" : ""}>주 경로</option><option value="shortcut" ${selectedPath.kind === "shortcut" ? "selected" : ""}>지름길</option><option value="manual" ${selectedPath.kind === "manual" ? "selected" : ""}>수동 길</option></select></label><label><span>너비</span><input type="number" min="2" max="32" data-forest-path-field="width" value="${Number(selectedPath.width || 5)}"></label><label><span>곡선</span><select data-forest-path-field="spline"><option value="true" ${selectedPath.spline?.enabled !== false ? "selected" : ""}>스플라인</option><option value="false" ${selectedPath.spline?.enabled === false ? "selected" : ""}>직선</option></select></label><label class="wide"><span>바닥 블록</span><input data-forest-path-field="surface" value="${escapeHtml(selectedPath.surface || "minecraft:dirt_path")}"></label><label class="wide"><span>시작 → 끝</span><input readonly value="${endpoints}"></label><label class="wide"><span>타일 앵커</span><input readonly value="${selectedPath.points.length}개 · 끝점에서 계속 설치 가능"></label></div></article>`; } else $("#forest-path-list").innerHTML = "";
  if (selectedEntrance) $("#forest-entrance-list").innerHTML = `<article class="forest-item-card is-active" data-forest-entrance-card="${selectedEntranceIndex}"><header><strong>${escapeHtml(selectedEntrance.display_name || selectedEntrance.id)}</strong><small>자유 배치</small></header><div class="forest-item-fields"><label><span>X</span><input type="number" step="1" data-forest-entrance-field="x" value="${selectedEntrance.position.x}"></label><label><span>Z</span><input type="number" step="1" data-forest-entrance-field="z" value="${selectedEntrance.position.z}"></label><label class="wide"><span>조작</span><input readonly value="선택 도구에서 노란 점을 자유롭게 드래그"></label></div></article>`; else $("#forest-entrance-list").innerHTML = "";
  $("#forest-height-list").innerHTML = "";
  $$('[data-forest-tool]').forEach((button) => {
    const active = button.dataset.forestTool === state.forestPreview.tool;
    button.classList.toggle("is-active", active); button.setAttribute("aria-pressed", String(active));
  });
}

function forestMinimumMazeCellSize(document = state.forest) {
  const maximumPathWidth = Math.max(2, ...(document?.paths || []).map((route) => Number(route.width) || 5));
  const clearance = Math.max(0, Number(document?.undergrowth?.path_clearance) || 0);
  return Math.max(4, Math.min(64, Math.ceil(maximumPathWidth + clearance * 2)));
}
function forestCellSize() { return Math.max(forestMinimumMazeCellSize(), Math.min(64, Math.round(Number(state.forest?.generator?.cell_size) || 16))); }

function renderForestMazeDialogForm() {
  if (!state.forest) return;
  const form = $("#forest-maze-dialog-form");
  const generator = { layout: "hybrid", seed_salt: 0, cell_size: 16, maze_complexity: .65, loop_chance: .18, spline_enabled: true, spline_tension: .45, ...(state.forest.generator || {}) };
  const minimumCellSize = forestMinimumMazeCellSize(state.forest);
  setFormValue(form, "generatorLayout", generator.layout);
  setFormValue(form, "seedSalt", generator.seed_salt);
  setFormValue(form, "cellSize", Math.max(minimumCellSize, Number(generator.cell_size) || 16));
  setFormValue(form, "mazeComplexity", generator.maze_complexity);
  setFormValue(form, "loopChance", generator.loop_chance);
  setFormValue(form, "splineEnabled", generator.spline_enabled !== false);
  setFormValue(form, "splineTension", generator.spline_tension);
  form.elements.cellSize.min = String(minimumCellSize);
  $("#forest-cell-size-hint").textContent = `자동 경로·높이 타일 최소 ${minimumCellSize}블록 · 수동 길과 입출구는 자유 배치`;
}

function openForestMazeDialog() {
  if (!state.forest) { toast("먼저 숲을 선택해 주세요."); return; }
  renderForestMazeDialogForm();
  $("#forest-maze-dialog").showModal();
}

function applyForestMazeDialog() {
  if (!state.forest) return;
  updateForestFromForm();
  const form = $("#forest-maze-dialog-form"); const minimumCellSize = forestMinimumMazeCellSize(state.forest);
  state.forest.generator = {
    layout: form.elements.generatorLayout.value,
    seed_salt: forestNumber(form, "seedSalt", 0),
    cell_size: Math.max(minimumCellSize, Math.min(64, Math.round(forestNumber(form, "cellSize", 16)))),
    maze_complexity: Math.max(0, Math.min(1, forestNumber(form, "mazeComplexity", .65))),
    loop_chance: Math.max(0, Math.min(1, forestNumber(form, "loopChance", .18))),
    spline_enabled: form.elements.splineEnabled.checked,
    spline_tension: Math.max(0, Math.min(1, forestNumber(form, "splineTension", .45)))
  };
  $("#forest-maze-dialog").close();
  if (state.forest.generator.layout === "manual") {
    state.forest.paths.forEach((route) => { route.spline = { enabled: route.spline?.enabled !== false, tension: state.forest.generator.spline_tension }; });
    renderForestEditors(); renderForestPreview(); toast("수동 길 모드 설정을 적용했습니다. 기존 길은 유지됩니다."); return;
  }
  generateForestMazePaths();
}

function clampForestPoint(point) {
  const bounds = state.forest.dimension.bounds;
  return { x: Math.max(bounds.min_x, Math.min(bounds.max_x, Math.round(Number(point.x) || 0))), z: Math.max(bounds.min_z, Math.min(bounds.max_z, Math.round(Number(point.z) || 0))) };
}

function forestTileGrid() {
  const bounds = state.forest.dimension.bounds, cell = forestCellSize();
  const columns = Math.max(1, Math.floor((bounds.max_x - bounds.min_x) / cell)), rows = Math.max(1, Math.floor((bounds.max_z - bounds.min_z) / cell));
  return { cell, minCenterX: Math.round(bounds.min_x + cell / 2), maxCenterX: Math.round(bounds.min_x + (columns - .5) * cell), minCenterZ: Math.round(bounds.min_z + cell / 2), maxCenterZ: Math.round(bounds.min_z + (rows - .5) * cell) };
}

function snapForestTilePoint(point) {
  const grid = forestTileGrid();
  const snapAxis = (value, minimum, maximum) => Math.max(minimum, Math.min(maximum, minimum + Math.floor((Number(value) - minimum + grid.cell / 2) / grid.cell) * grid.cell));
  return { x: snapAxis(point.x, grid.minCenterX, grid.maxCenterX), z: snapAxis(point.z, grid.minCenterZ, grid.maxCenterZ) };
}

function forestCanvasTransform() {
  const canvas = $("#forest-layout-canvas"); const bounds = state.forest.dimension.bounds; const pad = 30;
  const width = Math.max(1, bounds.max_x - bounds.min_x); const depth = Math.max(1, bounds.max_z - bounds.min_z);
  const baseScale = Math.min((canvas.width - pad * 2) / width, (canvas.height - pad * 2) / depth); const scale = baseScale * state.forestPreview.zoom; const mapWidth = width * scale, mapDepth = depth * scale;
  const offsetX = (canvas.width - mapWidth) / 2 + state.forestPreview.panX, offsetY = (canvas.height - mapDepth) / 2 + state.forestPreview.panY;
  return { canvas, scale, offsetX, offsetY, x: (value) => offsetX + (value - bounds.min_x) * scale, y: (value) => offsetY + (value - bounds.min_z) * scale, world: (clientX, clientY) => { const rect = canvas.getBoundingClientRect(); const canvasX = (clientX - rect.left) * canvas.width / rect.width, canvasY = (clientY - rect.top) * canvas.height / rect.height; return clampForestPoint({ x: bounds.min_x + (canvasX - offsetX) / scale, z: bounds.min_z + (canvasY - offsetY) / scale }); } };
}

function zoomForestPreview(nextZoom, clientX, clientY) {
  if (!state.forest) return;
  const previous = forestCanvasTransform(), canvas = previous.canvas, rect = canvas.getBoundingClientRect();
  const canvasX = (clientX - rect.left) * canvas.width / rect.width, canvasY = (clientY - rect.top) * canvas.height / rect.height;
  const bounds = state.forest.dimension.bounds, anchor = { x: bounds.min_x + (canvasX - previous.offsetX) / previous.scale, z: bounds.min_z + (canvasY - previous.offsetY) / previous.scale };
  const zoom = Math.max(.55, Math.min(4, nextZoom)); if (Math.abs(zoom - state.forestPreview.zoom) < .001) return;
  state.forestPreview.zoom = zoom;
  const next = forestCanvasTransform(); state.forestPreview.panX += canvasX - next.x(anchor.x); state.forestPreview.panY += canvasY - next.y(anchor.z);
  renderForestPreview();
}

function drawForestPath(context, route, transform, selected, pathIndex) {
  const points = route.points || []; if (points.length < 2) return;
  context.save(); context.strokeStyle = selected ? "#fff2a5" : route.kind === "shortcut" ? "#efab55" : "#a8c85c"; context.lineWidth = Math.max(3, Number(route.width || 5) * transform.scale); context.lineCap = "round"; context.lineJoin = "round"; context.beginPath(); context.moveTo(transform.x(points[0].x), transform.y(points[0].z));
  if (state.forest.generator?.spline_enabled !== false && route.spline?.enabled !== false && points.length > 2) {
    const tension = Math.max(0, Math.min(1, Number(route.spline?.tension ?? state.forest.generator?.spline_tension ?? .45))); const smoothing = .08 + tension * .22;
    for (let i = 0; i < points.length - 1; i++) {
      const previous = points[Math.max(0, i - 1)], current = points[i], next = points[i + 1], following = points[Math.min(points.length - 1, i + 2)];
      const control1 = { x: current.x + (next.x - previous.x) * smoothing, z: current.z + (next.z - previous.z) * smoothing };
      const control2 = { x: next.x - (following.x - current.x) * smoothing, z: next.z - (following.z - current.z) * smoothing };
      context.bezierCurveTo(transform.x(control1.x), transform.y(control1.z), transform.x(control2.x), transform.y(control2.z), transform.x(next.x), transform.y(next.z));
    }
  } else points.slice(1).forEach((point) => context.lineTo(transform.x(point.x), transform.y(point.z)));
  context.stroke(); points.forEach((point, pointIndex) => { const anchorSelected = state.forestPreview.selectedAnchor?.pathIndex === pathIndex && state.forestPreview.selectedAnchor?.pointIndex === pointIndex; context.fillStyle = anchorSelected ? "#fff" : "#fff7a0"; context.strokeStyle = anchorSelected ? "#d84a4a" : "transparent"; context.lineWidth = 3; context.beginPath(); context.arc(transform.x(point.x), transform.y(point.z), anchorSelected ? 7 : selected ? 4 : 2.5, 0, Math.PI * 2); context.fill(); if (anchorSelected) context.stroke(); }); context.restore();
}

function removeForestPathAnchor(pathIndex, pointIndex) {
  const route = state.forest?.paths?.[pathIndex];
  if (!route?.points?.[pointIndex]) return false;
  if (route.points.length <= 2) { toast("길에는 최소 2개의 앵커가 필요합니다."); return false; }
  route.points.splice(pointIndex, 1); state.forestPreview.selectedPath = pathIndex; state.forestPreview.selectedAnchor = null; state.forestPreview.selectedEntranceIndex = null; renderForestEditors(); renderForestPreview(); toast("선택한 길 앵커를 삭제했습니다."); return true;
}

function renderForestPreview() {
  if (!state.forest) return; syncForestBuildBounds(); renderForestDimensionSummary(); const transform = forestCanvasTransform(); const context = transform.canvas.getContext("2d"); const generator = state.forest.generator; context.clearRect(0, 0, transform.canvas.width, transform.canvas.height);
  context.fillStyle = "#315c37"; context.fillRect(0, 0, transform.canvas.width, transform.canvas.height);
  const bounds = state.forest.dimension.bounds, worldWidth = bounds.max_x - bounds.min_x, worldDepth = bounds.max_z - bounds.min_z;
  let random = ((Number(generator.seed_salt) || 0) + state.forestPreview.seedOffset + 1) >>> 0; const rand = () => ((random = (random * 1664525 + 1013904223) >>> 0) / 4294967296);
  context.fillStyle = "rgba(150,196,91,.38)"; const grassCount = Math.round(250 * (state.forest.undergrowth?.density ?? .7)); for (let i = 0; i < grassCount; i++) { const x = transform.x(bounds.min_x + rand() * worldWidth), y = transform.y(bounds.min_z + rand() * worldDepth); context.fillRect(x, y, 2, 5); }
  context.fillStyle = "rgba(20,64,27,.75)"; for (let i = 0; i < 180; i++) { const x = transform.x(bounds.min_x + rand() * worldWidth), y = transform.y(bounds.min_z + rand() * worldDepth); context.beginPath(); context.arc(x, y, 3 + rand() * 5, 0, Math.PI * 2); context.fill(); }
  const cell = forestCellSize(); const cellWidth = Math.abs(transform.x(cell) - transform.x(0)), cellHeight = Math.abs(transform.y(cell) - transform.y(0));
  for (const tile of state.forest.terrain_tiles) { const centerX = transform.x(tile.x), centerY = transform.y(tile.z), x = centerX - cellWidth / 2, y = centerY - cellHeight / 2; context.fillStyle = tile.height_offset > 0 ? `rgba(137,88,45,${Math.min(.8, .25 + tile.height_offset * .04)})` : `rgba(67,139,191,${Math.min(.8, .25 + Math.abs(tile.height_offset) * .04)})`; context.fillRect(x, y, cellWidth, cellHeight); context.fillStyle = "#fff"; context.font = "bold 10px sans-serif"; context.textAlign = "center"; context.fillText(`${tile.height_offset > 0 ? "+" : ""}${tile.height_offset}`, centerX, centerY + 3); if (tile.transition) { const angle = { east: 0, south: Math.PI / 2, west: Math.PI, north: -Math.PI / 2 }[tile.transition.direction] || 0, length = Math.max(9, Math.min(cellWidth, cellHeight) * .34); context.save(); context.translate(centerX, centerY); context.rotate(angle); context.strokeStyle = "#fff7d0"; context.fillStyle = "#fff7d0"; context.lineWidth = 2; for (let step = -1; step <= 1; step++) { context.beginPath(); context.moveTo(-length * .45 + step * length * .22, -length * .35); context.lineTo(-length * .45 + step * length * .22, length * .35); context.stroke(); } context.beginPath(); context.moveTo(length * .55, 0); context.lineTo(length * .2, -length * .25); context.lineTo(length * .2, length * .25); context.closePath(); context.fill(); context.restore(); } }
  if (state.forestPreview.tool.startsWith("height-") || state.forestPreview.tool === "stairs") { context.save(); context.strokeStyle = "rgba(230,245,220,.16)"; context.lineWidth = 1; const bounds = state.forest.dimension.bounds; for (let x = bounds.min_x; x <= bounds.max_x; x += cell) { context.beginPath(); context.moveTo(transform.x(x), transform.y(bounds.min_z)); context.lineTo(transform.x(x), transform.y(bounds.max_z)); context.stroke(); } for (let z = bounds.min_z; z <= bounds.max_z; z += cell) { context.beginPath(); context.moveTo(transform.x(bounds.min_x), transform.y(z)); context.lineTo(transform.x(bounds.max_x), transform.y(z)); context.stroke(); } context.restore(); }
  const brushHover = state.forestPreview.brushHover;
  if (brushHover && state.forestPreview.tool.startsWith("height-")) { const colors = state.forestPreview.tool === "height-up" ? ["rgba(246,181,78,.28)", "#ffd68a"] : state.forestPreview.tool === "height-down" ? ["rgba(74,166,226,.3)", "#a7ddff"] : ["rgba(255,255,255,.2)", "#ffffff"], targetHeight = Math.max(1, Math.min(16, Math.round(Number(state.forestPreview.heightBrushTarget) || 1))), appliedHeight = state.forestPreview.tool === "height-down" ? -targetHeight : state.forestPreview.tool === "height-reset" ? 0 : targetHeight, radius = state.forestPreview.heightBrushRadius, grid = forestTileGrid(); context.save(); context.fillStyle = colors[0]; context.strokeStyle = colors[1]; context.lineWidth = 2; context.setLineDash([5, 3]); for (let dz = -radius; dz <= radius; dz++) for (let dx = -radius; dx <= radius; dx++) { const x = brushHover.x + dx * cell, z = brushHover.z + dz * cell; if (x < grid.minCenterX || x > grid.maxCenterX || z < grid.minCenterZ || z > grid.maxCenterZ) continue; const left = transform.x(x) - cellWidth / 2 + 1, top = transform.y(z) - cellHeight / 2 + 1; context.fillRect(left, top, Math.max(1, cellWidth - 2), Math.max(1, cellHeight - 2)); context.strokeRect(left, top, Math.max(1, cellWidth - 2), Math.max(1, cellHeight - 2)); } context.setLineDash([]); context.fillStyle = "rgba(18,28,22,.82)"; context.fillRect(transform.x(brushHover.x) - 15, transform.y(brushHover.z) - 9, 30, 18); context.fillStyle = "#fff"; context.font = "bold 10px sans-serif"; context.textAlign = "center"; context.textBaseline = "middle"; context.fillText(appliedHeight > 0 ? `+${appliedHeight}` : String(appliedHeight), transform.x(brushHover.x), transform.y(brushHover.z)); context.restore(); }
  state.forestPreview.hitTargets = [];
  state.forest.paths.forEach((route, index) => drawForestPath(context, route, transform, state.forestPreview.selectedEntranceIndex == null && index === state.forestPreview.selectedPath, index));
  state.forest.paths.forEach((route, pathIndex) => route.points.forEach((point, pointIndex) => state.forestPreview.hitTargets.push({ kind: "path", pathIndex, pointIndex, x: transform.x(point.x), y: transform.y(point.z) })));
  const selectedAnchor = state.forestPreview.selectedAnchor; const selectedAnchorPoint = selectedAnchor && state.forest.paths[selectedAnchor.pathIndex]?.points?.[selectedAnchor.pointIndex];
  const anchorDeleteButton = $("#forest-anchor-delete");
  if (selectedAnchorPoint) { const anchorX = transform.x(selectedAnchorPoint.x), anchorY = transform.y(selectedAnchorPoint.z), displayX = anchorX * transform.canvas.clientWidth / transform.canvas.width, displayY = anchorY * transform.canvas.clientHeight / transform.canvas.height; anchorDeleteButton.hidden = false; anchorDeleteButton.dataset.pathIndex = String(selectedAnchor.pathIndex); anchorDeleteButton.dataset.pointIndex = String(selectedAnchor.pointIndex); anchorDeleteButton.style.transform = `translate(${displayX - 12}px, ${Math.max(2, displayY - 39)}px)`; } else { anchorDeleteButton.hidden = true; delete anchorDeleteButton.dataset.pathIndex; delete anchorDeleteButton.dataset.pointIndex; }
  state.forest.entrances.forEach((entrance, entranceIndex) => { const x = transform.x(entrance.position.x), y = transform.y(entrance.position.z), selected = entranceIndex === state.forestPreview.selectedEntranceIndex; if (selected) { context.fillStyle = "rgba(255,209,102,.22)"; context.strokeStyle = "#fff0a8"; context.lineWidth = 3; context.beginPath(); context.arc(x, y, 14, 0, Math.PI * 2); context.fill(); context.stroke(); } context.fillStyle = selected ? "#ffe38a" : "#ffd166"; context.strokeStyle = selected ? "#fff7cb" : "#473400"; context.lineWidth = selected ? 3 : 2; context.beginPath(); context.arc(x, y, selected ? 9 : 8, 0, Math.PI * 2); context.fill(); context.stroke(); context.fillStyle = "#fff5c7"; context.font = `bold ${selected ? 11 : 10}px sans-serif`; context.textAlign = "left"; context.fillText(entrance.display_name || entrance.id, x + 12, y - 9); state.forestPreview.hitTargets.push({ kind: "entrance", entranceIndex, x, y }); });
  const brushSize = state.forestPreview.heightBrushRadius * 2 + 1, targetHeight = Math.max(1, Math.min(16, Math.round(Number(state.forestPreview.heightBrushTarget) || 1))), appliedHeight = state.forestPreview.tool === "height-down" ? -targetHeight : state.forestPreview.tool === "height-reset" ? 0 : targetHeight, shortcutCount = state.forest.paths.filter((route) => route.kind === "shortcut").length, transitionCount = state.forest.terrain_tiles.filter((tile) => tile.transition).length; $("#forest-preview-summary").textContent = `화면 ${Math.round(state.forestPreview.zoom * 100)}% · 길·입출구 자유 배치 · 높이 타일 ${cell}블록 · 주/수동 길 ${state.forest.paths.length - shortcutCount}개 · 지름길 ${shortcutCount}개 · 앵커 ${state.forest.paths.reduce((sum, route) => sum + route.points.length, 0)}개 · 높이 타일 ${state.forest.terrain_tiles.length}개 · 계단/경사 ${transitionCount}개 · 높이 브러시 ${brushSize}×${brushSize} → ${appliedHeight > 0 ? `+${appliedHeight}` : appliedHeight}`;
}

function handleForestEditorClick(event) {
  const environmentPreset = event.target.closest("[data-forest-environment-preset]"); if (environmentPreset) { applyForestEnvironmentPreset(environmentPreset.dataset.forestEnvironmentPreset); return; }
  const removeAnchor = event.target.closest("#forest-anchor-delete"); if (removeAnchor) { removeForestPathAnchor(Number(removeAnchor.dataset.pathIndex), Number(removeAnchor.dataset.pointIndex)); return; }
  const tool = event.target.closest("[data-forest-tool]"); if (tool) { state.forestPreview.tool = tool.dataset.forestTool; state.forestPreview.brushHover = null; if (!['select', 'path'].includes(state.forestPreview.tool)) state.forestPreview.selectedAnchor = null; renderForestHeightTarget(); renderForestEditors(); renderForestPreview(); return; }
  const addPath = event.target.closest("[data-add-forest-path]"); if (addPath) { const previous = state.forest.paths[state.forestPreview.selectedPath]; const endpoint = previous?.points?.at(-1); const index = state.forest.paths.length; state.forest.paths.push({ id: `path_${index + 1}`, kind: "manual", width: 5, surface: "minecraft:dirt_path", points: endpoint ? [{ ...endpoint }] : [], spline: { enabled: true, tension: state.forest.generator.spline_tension ?? .45 } }); state.forestPreview.selectedPath = index; state.forestPreview.selectedAnchor = null; state.forestPreview.tool = "path"; renderForestEditors(); renderForestPreview(); return; }
  const removePath = event.target.closest("[data-remove-forest-path]"); if (removePath) { state.forest.paths.splice(Number(removePath.dataset.removeForestPath), 1); state.forestPreview.selectedPath = null; state.forestPreview.selectedAnchor = null; renderForestEditors(); renderForestPreview(); return; }
  const removeHeight = event.target.closest("[data-remove-forest-height]"); if (removeHeight) { state.forest.terrain_tiles.splice(Number(removeHeight.dataset.removeForestHeight), 1); renderForestEditors(); renderForestPreview(); return; }
  const card = event.target.closest("[data-forest-path-card]"); if (card && !event.target.matches("input,select,button")) { state.forestPreview.selectedPath = Number(card.dataset.forestPathCard); state.forestPreview.selectedAnchor = null; state.forestPreview.selectedEntranceIndex = null; renderForestEditors(); renderForestPreview(); }
  const entranceCard = event.target.closest("[data-forest-entrance-card]"); if (entranceCard && !event.target.matches("input,select,button")) { state.forestPreview.selectedPath = null; state.forestPreview.selectedAnchor = null; state.forestPreview.selectedEntranceIndex = Number(entranceCard.dataset.forestEntranceCard); renderForestEditors(); renderForestPreview(); }
}

function handleForestListInput(event) {
  const entranceCard = event.target.closest("[data-forest-entrance-card]");
  if (entranceCard) { const index = Number(entranceCard.dataset.forestEntranceCard), entrance = state.forest.entrances[index], field = event.target.dataset.forestEntranceField; if (!entrance || !["x", "z"].includes(field)) return; entrance.position = clampForestPoint({ ...entrance.position, [field]: Number(event.target.value) }); state.forestPreview.selectedPath = null; state.forestPreview.selectedAnchor = null; state.forestPreview.selectedEntranceIndex = index; if (event.type === "change") renderForestEditors(); renderForestPreview(); return; }
  const pathCard = event.target.closest("[data-forest-path-card]"); if (pathCard) { const route = state.forest.paths[Number(pathCard.dataset.forestPathCard)]; const field = event.target.dataset.forestPathField; if (field === "kind") route.kind = event.target.value; if (field === "width") route.width = Number(event.target.value); if (field === "surface") route.surface = event.target.value.trim(); if (field === "spline") route.spline = { enabled: event.target.value === "true", tension: route.spline?.tension ?? .45 }; renderForestPreview(); return; }
  const heightCard = event.target.closest("[data-forest-height-card]"); if (!heightCard) return; const tile = state.forest.terrain_tiles[Number(heightCard.dataset.forestHeightCard)]; tile.height_offset = Math.max(-16, Math.min(16, Math.round(Number(event.target.value) || 0))); if (!tile.height_offset) state.forest.terrain_tiles.splice(Number(heightCard.dataset.forestHeightCard), 1); renderForestEditors(); renderForestPreview();
}

function renderForestHeightTarget() {
  const targetHeight = Math.max(1, Math.min(16, Math.round(Number(state.forestPreview.heightBrushTarget) || 1)));
  state.forestPreview.heightBrushTarget = targetHeight;
  const appliedHeight = state.forestPreview.tool === "height-down" ? -targetHeight : state.forestPreview.tool === "height-reset" ? 0 : targetHeight;
  $("#forest-height-target-value").textContent = appliedHeight > 0 ? `+${appliedHeight}` : String(appliedHeight);
}

function adjustForestTileHeight(point, tool = state.forestPreview.tool, painted = null) {
  const grid = forestTileGrid(), cell = grid.cell, radius = state.forestPreview.heightBrushRadius, targetHeight = Math.max(1, Math.min(16, Math.round(Number(state.forestPreview.heightBrushTarget) || 1)));
  for (let dz = -radius; dz <= radius; dz++) for (let dx = -radius; dx <= radius; dx++) { const x = point.x + dx * cell, z = point.z + dz * cell, key = `${x},${z}`; if (x < grid.minCenterX || x > grid.maxCenterX || z < grid.minCenterZ || z > grid.maxCenterZ || painted?.has(key)) continue; painted?.add(key); const index = state.forest.terrain_tiles.findIndex((tile) => tile.x === x && tile.z === z); const next = tool === "height-up" ? targetHeight : tool === "height-down" ? -targetHeight : 0; if (!next && index >= 0) state.forest.terrain_tiles.splice(index, 1); else if (index >= 0) state.forest.terrain_tiles[index].height_offset = next; else if (next) state.forest.terrain_tiles.push({ x, z, height_offset: next }); }
}

function handleForestStairSetting(event) {
  const field = event.target.dataset.forestStairField; if (!field) return false;
  state.forestPreview.stairPlacement[field] = event.target.value; return true;
}

function placeForestHeightTransition(point) {
  const cell = forestCellSize(), directions = [
    { direction: "north", dx: 0, dz: -cell }, { direction: "south", dx: 0, dz: cell },
    { direction: "east", dx: cell, dz: 0 }, { direction: "west", dx: -cell, dz: 0 }
  ];
  const tileAt = (x, z) => state.forest.terrain_tiles.find((tile) => tile.x === x && tile.z === z);
  const selected = tileAt(point.x, point.z), selectedHeight = selected?.height_offset || 0, preferred = state.forestPreview.stairPlacement.direction;
  const opposite = { north: "south", south: "north", east: "west", west: "east" };
  const candidates = directions.map((entry) => { const neighbor = tileAt(point.x + entry.dx, point.z + entry.dz), neighborHeight = neighbor?.height_offset || 0, selectedIsHigh = selectedHeight > neighborHeight; return { ...entry, neighbor, neighborHeight, selectedIsHigh, risingDirection: selectedIsHigh ? opposite[entry.direction] : entry.direction, difference: Math.abs(selectedHeight - neighborHeight) }; }).filter((entry) => entry.difference >= 2 && (preferred === "auto" || entry.risingDirection === preferred));
  const candidate = candidates.sort((left, right) => right.difference - left.difference)[0];
  if (!candidate) { toast("선택한 타일 주변에 2칸 이상의 높이 차가 없습니다."); return false; }
  const highTile = candidate.selectedIsHigh ? selected : candidate.neighbor;
  if (!highTile) return false;
  const transition = { kind: state.forestPreview.stairPlacement.kind, direction: candidate.risingDirection, block: state.forestPreview.stairPlacement.block.trim() || "minecraft:oak_stairs" };
  if (highTile.transition && highTile.transition.kind === transition.kind && highTile.transition.direction === transition.direction && highTile.transition.block === transition.block) { delete highTile.transition; toast("높이 전환을 제거했습니다."); }
  else { highTile.transition = transition; toast(`${candidate.difference}칸 높이 차에 ${transition.kind === "stairs" ? "계단" : "경사로"}를 배치했습니다.`); }
  renderForestEditors(); renderForestPreview(); return true;
}

function generateForestMazePaths() {
  if (!state.forest) return; state.forestPreview.seedOffset += 1;
  const cell = forestCellSize(), bounds = state.forest.dimension.bounds; let random = ((Number(state.forest.generator.seed_salt) || 0) + state.forestPreview.seedOffset) >>> 0; const rand = () => ((random = (Math.imul(random, 1664525) + 1013904223) >>> 0) / 4294967296);
  const complexity = Math.max(0, Math.min(1, Number(state.forest.generator.maze_complexity) || 0));
  const entrancePoints = state.forest.entrances.map((entrance) => clampForestPoint(entrance.position)); const startPoint = entrancePoints[0] || clampForestPoint({ x: bounds.min_x, z: 0 }), exitPoint = entrancePoints.at(-1) || clampForestPoint({ x: bounds.max_x, z: 0 });
  const dx = exitPoint.x - startPoint.x, dz = exitPoint.z - startPoint.z, distance = Math.max(cell, Math.hypot(dx, dz)), perpendicular = { x: -dz / distance, z: dx / distance };
  const mainAnchorCount = 6 + Math.round(complexity * 5), waveCount = 1 + Math.round(complexity * 2), amplitude = cell * (1.5 + complexity * 3.5), phase = rand() * Math.PI * 2;
  const mainPoints = []; const pushUnique = (points, point) => { const bounded = clampForestPoint(point), previous = points.at(-1); if (!previous || previous.x !== bounded.x || previous.z !== bounded.z) points.push(bounded); };
  for (let index = 0; index < mainAnchorCount; index++) { const progress = index / (mainAnchorCount - 1), envelope = Math.sin(progress * Math.PI), wave = Math.sin(progress * Math.PI * waveCount + phase), jitter = (rand() - .5) * amplitude * .5, offset = envelope * (wave * amplitude + jitter); pushUnique(mainPoints, { x: startPoint.x + dx * progress + perpendicular.x * offset, z: startPoint.z + dz * progress + perpendicular.z * offset }); }
  mainPoints[0] = { ...startPoint }; mainPoints[mainPoints.length - 1] = { ...exitPoint };
  const loopChance = Math.max(0, Math.min(1, Number(state.forest.generator.loop_chance) || 0)), shortcutCount = Math.min(4, Math.max(1, 1 + Math.round(loopChance * 3))), shortcutPaths = [];
  for (let index = 0; index < shortcutCount && mainPoints.length >= 5; index++) { const available = mainPoints.length - 3, fromIndex = Math.min(mainPoints.length - 4, 1 + Math.floor((index / Math.max(1, shortcutCount)) * available)), remaining = mainPoints.length - fromIndex - 1, toIndex = Math.min(mainPoints.length - 1, fromIndex + Math.max(2, Math.round(remaining * (.45 + rand() * .25)))); if (toIndex - fromIndex < 2) continue; const from = mainPoints[fromIndex], to = mainPoints[toIndex], shortcutDx = to.x - from.x, shortcutDz = to.z - from.z, shortcutDistance = Math.max(cell, Math.hypot(shortcutDx, shortcutDz)), bendSign = rand() < .5 ? -1 : 1, midpoint = clampForestPoint({ x: (from.x + to.x) / 2 + (-shortcutDz / shortcutDistance) * cell * bendSign, z: (from.z + to.z) / 2 + (shortcutDx / shortcutDistance) * cell * bendSign }); const points = [{ ...from }]; pushUnique(points, midpoint); pushUnique(points, to); if (points.length >= 2) shortcutPaths.push(points); }
  const previousMain = state.forest.paths.find((route) => route.id === "main") || state.forest.paths[0], width = previousMain?.width || 5, surface = previousMain?.surface || "minecraft:dirt_path", tension = Number(state.forest.generator.spline_tension ?? .45), splineEnabled = state.forest.generator.spline_enabled !== false;
  const mazePaths = [{ id: previousMain?.id || "main", kind: "main", width, surface, points: mainPoints, spline: { enabled: splineEnabled, tension } }, ...shortcutPaths.map((points, index) => ({ id: `shortcut_${index + 1}`, kind: "shortcut", width: Math.max(2, width - 1), surface, points, spline: { enabled: splineEnabled, tension } }))];
  state.forest.paths = mazePaths; state.forestPreview.selectedPath = 0; state.forestPreview.selectedAnchor = null; state.forestPreview.tool = "path"; renderForestEditors(); renderForestPreview(); toast(`곡선 주 경로와 지름길 ${mazePaths.length - 1}개를 생성했습니다. 자동 경로 간격 ${cell}블록`);
}

function cavePreviewRandom(seed) {
  const multiplier = 0x5DEECE66Dn; const addend = 0xBn; const mask = (1n << 48n) - 1n;
  let value = (BigInt.asIntN(64, BigInt(seed)) ^ multiplier) & mask;
  const next = (bits) => { value = (value * multiplier + addend) & mask; return Number(value >> BigInt(48 - bits)); };
  const nextIntBound = (bound) => {
    if (bound <= 0) throw new Error("동굴 미리보기 난수 범위가 올바르지 않습니다.");
    if ((bound & -bound) === bound) return Number((BigInt(bound) * BigInt(next(31))) >> 31n);
    let bits; let result;
    do { bits = next(31); result = bits % bound; } while (((bits - result + (bound - 1)) | 0) < 0);
    return result;
  };
  return {
    nextDouble: () => (next(26) * 134217728 + next(27)) / 9007199254740992,
    nextInt: (origin, bound) => origin + nextIntBound(bound - origin)
  };
}

function cavePreviewHash(value) {
  let hash = 0;
  for (const character of String(value || "cave")) hash = (Math.imul(hash, 31) + character.charCodeAt(0)) | 0;
  return hash;
}

function buildCavePreviewLayout() {
  if (!state.cave) return null;
  const generator = state.cave.generator || {};
  const entrances = (state.cave.entrances || []).map((entry, index) => ({
    x: Number(entry.destination_anchor?.x ?? 0), y: Number(entry.destination_anchor?.y ?? 48), z: Number(entry.destination_anchor?.z ?? 0),
    id: entry.id, source: "entrance", sourceIndex: index, kind: "entrance", label: entry.display_name || entry.id || "입출구"
  })).sort((a, b) => a.x - b.x);
  const bounds = state.cave.dimension?.bounds || {};
  const origin = state.cave.dimension?.origin || { x: 0, y: 48, z: 0 };
  const fallbackStart = { x: Number(bounds.min_x ?? -256) + 48, y: Number(origin.y ?? 48), z: Number(origin.z ?? 0), kind: "entrance", label: "입구" };
  const fallbackEnd = { x: Number(bounds.max_x ?? 256) - 48, y: Number(origin.y ?? 48), z: Number(origin.z ?? 0), kind: "entrance", label: "출구" };
  if (!entrances.length) entrances.push(fallbackStart, fallbackEnd);
  else if (entrances.length === 1) entrances.push(entrances[0].x < Number(origin.x ?? 0) ? fallbackEnd : fallbackStart);
  entrances.sort((a, b) => a.x - b.x);
  const start = entrances[0]; const end = entrances.at(-1);
  const manual = generator.manual_layout || {};
  if (manual.enabled) {
    const nodes = new Map();
    for (const entry of state.cave.entrances || []) nodes.set(entry.id, { id: entry.id, x: Number(entry.destination_anchor?.x ?? 0), y: Number(entry.destination_anchor?.y ?? 48), z: Number(entry.destination_anchor?.z ?? 0) });
    const rooms = (manual.anchors || []).map((anchor, index) => {
      const room = { id: anchor.id, source: "anchor", sourceIndex: index, x: Number(anchor.position?.x ?? 0), y: Number(anchor.position?.y ?? 48), z: Number(anchor.position?.z ?? 0), radiusX: Number(anchor.radius_x ?? 12), radiusZ: Number(anchor.radius_z ?? 12), height: Number(anchor.height ?? 12), kind: anchor.kind === "landmark" ? "moon" : anchor.kind === "room" || anchor.kind === "lake" ? "wild" : anchor.kind };
      nodes.set(anchor.id, room); return room;
    });
    const paths = (manual.connections || []).map((connection) => ({ id: connection.id, source: "connection", kind: connection.kind, points: [nodes.get(connection.from), nodes.get(connection.to)].filter(Boolean), width: Number(connection.width || 5) })).filter((path) => path.points.length === 2);
    return { rooms, paths, entrances, waterLevel: Number(generator.water_level ?? 38), manual: true };
  }
  const roomCount = Math.max(3, Math.round(Number(generator.main_rooms ?? 7)));
  const branchCount = Math.max(0, Math.round(Number(generator.branch_count ?? 4)));
  const verticalRange = Math.max(8, Number(generator.vertical_range ?? 28));
  const roomMin = Number(generator.room_radius?.min ?? 10);
  const roomMax = Math.max(roomMin, Number(generator.room_radius?.max ?? 28));
  const grandScale = Math.max(1, Number(generator.grand_room_scale ?? 1.65));
  const caveGeneration = generationFromDocumentPath(state.cavePath);
  const worldSeed = BigInt(state.worldLayout?.seed_salt ?? (caveGeneration === 1 ? 19960227 : 1700 + caveGeneration));
  const caveSeed = BigInt.asIntN(64, worldSeed ^ BigInt.asIntN(64, BigInt(cavePreviewHash(state.cave.id)) * 341873128712n) ^ BigInt(Number(generator.seed_salt) || 0));
  const random = cavePreviewRandom(caveSeed);
  const between = (minimum, maximum) => minimum + random.nextDouble() * Math.max(0, maximum - minimum);
  const rooms = []; const mainRooms = [];
  for (let index = 0; index < roomCount; index++) {
    const progress = index / (roomCount - 1);
    const edge = index === 0 || index === roomCount - 1;
    let x = start.x + (end.x - start.x) * progress;
    let z = start.z + (end.z - start.z) * progress;
    let y = Math.round(start.y + (end.y - start.y) * progress);
    if (!edge) {
      z += Math.sin(index * 1.37) * 27 + random.nextInt(-12, 13);
      const verticalAmplitude = Math.max(4, Math.floor(verticalRange / 2));
      y += Math.round(Math.sin(index * 1.71) * verticalAmplitude * .72)
        + random.nextInt(-Math.max(2, Math.floor(verticalAmplitude / 4)), Math.max(3, Math.floor(verticalAmplitude / 4) + 1));
      if (index === Math.max(2, Math.floor(roomCount * 2 / 3))) y += Math.max(5, Math.floor(verticalAmplitude / 2));
    }
    y = Math.max(34, Math.min(72, y));
    let radiusX = edge ? Math.max(9, roomMin) : between(roomMin, roomMax);
    let radiusZ = edge ? Math.max(9, roomMin - 1) : between(roomMin, roomMax);
    let height = edge ? 10 : between(Math.max(9, roomMin * .72), Math.max(11, roomMax * .72));
    const kind = index === Math.floor(roomCount / 2) ? "grand" : "main";
    if (kind === "grand") { radiusX = Math.max(radiusX, roomMax * grandScale); radiusZ = Math.max(radiusZ, roomMax * grandScale * .82); height = Math.max(height, roomMax * grandScale * .78); }
    const room = { id: `main_${index + 1}`, x, y, z, radiusX, radiusZ, height, kind };
    rooms.push(room); mainRooms.push(room);
  }
  const point = (room) => ({ x: room.x, y: room.y, z: room.z });
  const paths = [{ kind: "main", points: mainRooms.map(point) }];
  for (let index = 0; index < branchCount; index++) {
    const rootIndex = Math.max(1, Math.min(roomCount - 2, 1 + Math.floor((index + 1) * (roomCount - 2) / (branchCount + 1))));
    const root = mainRooms[rootIndex]; const direction = index % 2 === 0 ? -1 : 1;
    const kind = index === 0 ? "moon" : "wild";
    const x = root.x + random.nextInt(-24, 25); const z = root.z + direction * (58 + random.nextInt(0, 29));
    let y = Math.max(30, Math.min(76, root.y + random.nextInt(-Math.floor(verticalRange / 2), Math.floor(verticalRange / 2) + 1)));
    const radiusX = between(roomMin, roomMax);
    const radiusZ = between(roomMin, roomMax);
    const room = { id: `${kind}_${index + 1}`, x, y, z, radiusX, radiusZ, height: between(Math.max(9, roomMin * .7), Math.max(11, roomMax * .72)), kind };
    rooms.push(room);
    paths.push({ kind: "branch", points: [point(root), { x: (root.x + x) / 2 + random.nextInt(-10, 11), y: Math.trunc((root.y + y) / 2), z: (root.z + z) / 2 }, point(room)] });
  }
  if (roomCount >= 5 && random.nextDouble() <= Number(generator.loop_chance ?? .35)) {
    const from = mainRooms[Math.max(1, Math.floor(roomCount / 3))]; const to = mainRooms[Math.min(roomCount - 2, Math.floor(roomCount * 2 / 3))];
    paths.push({ kind: "loop", points: [point(from), { x: (from.x + to.x) / 2, y: (from.y + to.y) / 2 + 7, z: Math.min(from.z, to.z) - 58 }, point(to)] });
  }
  if (generator.elevated_crossing !== false && roomCount >= 5) {
    const grandIndex = Math.floor(roomCount / 2); const grand = mainRooms[grandIndex];
    const from = mainRooms[Math.min(roomCount - 2, grandIndex + 1)]; const to = mainRooms[Math.min(roomCount - 1, grandIndex + 2)];
    const bridgeY = grand.y + Math.max(10, Number(generator.bridge_clearance ?? 13)); const span = Math.max(16, grand.radiusZ * .72);
    paths.push({ kind: "bridge", points: [point(from), { x: grand.x + grand.radiusX * .62, y: bridgeY, z: grand.z - span }, { x: grand.x, y: bridgeY, z: grand.z }, { x: grand.x - grand.radiusX * .62, y: bridgeY, z: grand.z + span }, point(to)] });
  }
  return { rooms, paths, entrances, waterLevel: Number(generator.water_level ?? 38), manual: false };
}

function selectedCavePreviewNode() {
  const selected = state.cavePreview.selected;
  if (!selected || !state.cave) return null;
  if (selected.source === "anchor") {
    const node = state.cave.generator?.manual_layout?.anchors?.find((entry) => entry.id === selected.id);
    return node ? { selected, node, label: node.id, position: node.position, anchor: true } : null;
  }
  if (selected.source === "connection") {
    const node = state.cave.generator?.manual_layout?.connections?.find((entry) => entry.id === selected.id);
    return node ? { selected, node, label: node.id, path: true, anchor: false } : null;
  }
  const node = state.cave.entrances?.find((entry) => entry.id === selected.id);
  return node ? { selected, node, label: node.display_name || node.id, position: node.destination_anchor, entrance: true, anchor: false } : null;
}

function mutateSelectedCaveNode(values) {
  const selected = selectedCavePreviewNode();
  if (!selected) return false;
  if (selected.path) {
    for (const [field, rawValue] of Object.entries(values)) {
      if (field === "pathKind" && ["tunnel", "stairs", "bridge"].includes(rawValue)) selected.node.kind = rawValue;
      else if (field === "width") selected.node.width = Math.max(3, Math.min(15, Math.round(Number(rawValue))));
    }
  } else if (selected.anchor) {
    for (const [field, rawValue] of Object.entries(values)) {
      const value = Number(rawValue);
      if (["x", "y", "z"].includes(field)) selected.node.position[field] = Math.round(value);
      else if (field === "radiusX") selected.node.radius_x = Math.max(3, Math.min(96, value));
      else if (field === "radiusZ") selected.node.radius_z = Math.max(3, Math.min(96, value));
      else if (field === "height") selected.node.height = Math.max(5, Math.min(96, value));
      else if (field === "kind" && ["room", "grand", "junction", "landmark"].includes(rawValue)) selected.node.kind = rawValue;
    }
  } else {
    for (const [field, rawValue] of Object.entries(values)) {
      if (["x", "y", "z"].includes(field)) {
        const value = Math.round(Number(rawValue));
        const delta = value - selected.node.destination_anchor[field];
        selected.node.destination_anchor[field] = value;
        selected.node.fallback_anchor[field] += delta;
      } else if (["fallbackX", "fallbackY", "fallbackZ"].includes(field)) {
        selected.node.fallback_anchor[field.at(-1).toLowerCase()] = Math.round(Number(rawValue));
      } else if (field === "displayName") selected.node.display_name = String(rawValue);
      else if (field === "requiredProgress") {
        const progress = String(rawValue).trim();
        if (progress) selected.node.required_progress = progress; else delete selected.node.required_progress;
      } else if (field === "entranceId") {
        const nextId = String(rawValue).trim();
        const conflict = [...(state.cave.entrances || []), ...(state.cave.generator?.manual_layout?.anchors || [])].some((entry) => entry !== selected.node && entry.id === nextId);
        if (!/^[a-z0-9_.-]+$/.test(nextId) || conflict) continue;
        const previousId = selected.node.id;
        selected.node.id = nextId; selected.selected.id = nextId;
        for (const connection of state.cave.generator?.manual_layout?.connections || []) {
          if (connection.from === previousId) connection.from = nextId;
          if (connection.to === previousId) connection.to = nextId;
        }
        if (state.cavePreview.pathDraft?.id === previousId) state.cavePreview.pathDraft.id = nextId;
      }
    }
  }
  return true;
}

function renderCaveNodeInspector() {
  const inspector = $("#cave-node-inspector"); const selected = selectedCavePreviewNode();
  if (!inspector) return;
  if (!selected) { inspector.hidden = true; state.cavePreview.selected = null; return; }
  inspector.hidden = false;
  $("#cave-node-inspector-title").textContent = `${selected.path ? "통로" : selected.anchor ? "앵커" : "입출구"} · ${selected.label}`;
  inspector.querySelectorAll('[data-cave-size-field], [data-cave-anchor-field]').forEach((element) => { element.hidden = !selected.anchor; });
  inspector.querySelectorAll('[data-cave-entrance-field]').forEach((element) => { element.hidden = !selected.entrance; });
  inspector.querySelectorAll('[data-cave-position-field]').forEach((element) => { element.hidden = selected.path; });
  inspector.querySelectorAll('[data-cave-path-field]').forEach((element) => { element.hidden = !selected.path; });
  inspector.querySelector('[data-delete-selected-cave-anchor]').hidden = !selected.anchor;
  inspector.querySelector('[data-delete-selected-cave-entrance]').hidden = !selected.entrance;
  inspector.querySelector('[data-delete-selected-cave-path]').hidden = !selected.path;
  if (selected.path) {
    inspector.querySelector('[data-cave-selected-field="pathId"]').value = selected.node.id;
    inspector.querySelector('[data-cave-selected-field="from"]').value = selected.node.from;
    inspector.querySelector('[data-cave-selected-field="to"]').value = selected.node.to;
    inspector.querySelector('[data-cave-selected-field="width"]').value = selected.node.width;
    inspector.querySelector('[data-cave-selected-field="pathKind"]').value = selected.node.kind;
    $("#cave-node-inspector-help").textContent = "통로 선을 직접 선택했습니다. 종류와 너비를 변경하거나 삭제할 수 있습니다.";
  } else {
    for (const field of ["x", "y", "z"]) inspector.querySelector(`[data-cave-selected-field="${field}"]`).value = selected.position[field];
    $("#cave-node-inspector-help").textContent = selected.entrance
      ? "입구 위치를 드래그하면 안전 위치도 함께 이동합니다. 안전 위치는 개별 조정할 수 있습니다."
      : "평면 보기에서는 화면에 보이는 두 축으로 이동합니다. 3D에서는 XZ 평면으로 이동합니다.";
  }
  if (selected.anchor) {
    inspector.querySelector('[data-cave-selected-field="id"]').value = selected.node.id;
    inspector.querySelector('[data-cave-selected-field="kind"]').value = selected.node.kind;
    inspector.querySelector('[data-cave-selected-field="radiusX"]').value = selected.node.radius_x;
    inspector.querySelector('[data-cave-selected-field="radiusZ"]').value = selected.node.radius_z;
    inspector.querySelector('[data-cave-selected-field="height"]').value = selected.node.height;
  } else if (selected.entrance) {
    inspector.querySelector('[data-cave-selected-field="entranceId"]').value = selected.node.id;
    inspector.querySelector('[data-cave-selected-field="displayName"]').value = selected.node.display_name || "";
    inspector.querySelector('[data-cave-selected-field="requiredProgress"]').value = selected.node.required_progress || "";
    for (const field of ["x", "y", "z"]) inspector.querySelector(`[data-cave-selected-field="fallback${field.toUpperCase()}"]`).value = selected.node.fallback_anchor[field];
  }
  $(".cave-layout-preview")?.setAttribute("data-editing-node", "true");
}

function cavePreviewProjection(layout, canvas) {
  const allPoints = [...layout.rooms, ...layout.entrances];
  const center = { x: allPoints.reduce((sum, item) => sum + item.x, 0) / allPoints.length, y: allPoints.reduce((sum, item) => sum + item.y, 0) / allPoints.length, z: allPoints.reduce((sum, item) => sum + item.z, 0) / allPoints.length };
  const extents = {
    minX: Math.min(...allPoints.map((item) => item.x - Number(item.radiusX || 0))), maxX: Math.max(...allPoints.map((item) => item.x + Number(item.radiusX || 0))),
    minY: Math.min(...allPoints.map((item) => item.y)), maxY: Math.max(...allPoints.map((item) => item.y + Number(item.height || 0))),
    minZ: Math.min(...allPoints.map((item) => item.z - Number(item.radiusZ || 0))), maxZ: Math.max(...allPoints.map((item) => item.z + Number(item.radiusZ || 0)))
  };
  const spanX = Math.max(100, extents.maxX - extents.minX); const spanY = Math.max(48, extents.maxY - extents.minY); const spanZ = Math.max(100, extents.maxZ - extents.minZ);
  const view = state.cavePreview.view || "perspective";
  const baseScale = view === "xy" ? Math.min(canvas.width / (spanX * 1.18), canvas.height / (spanY * 1.45))
    : view === "xz" ? Math.min(canvas.width / (spanX * 1.18), canvas.height / (spanZ * 1.18))
      : view === "zy" ? Math.min(canvas.width / (spanZ * 1.18), canvas.height / (spanY * 1.45))
        : Math.min(canvas.width / (spanX * 1.18), canvas.height / (spanZ * .85 + spanY * .9 + 100));
  const scale = baseScale * state.cavePreview.zoom;
  const cosYaw = Math.cos(state.cavePreview.yaw); const sinYaw = Math.sin(state.cavePreview.yaw); const cosPitch = Math.cos(state.cavePreview.pitch); const sinPitch = Math.sin(state.cavePreview.pitch);
  const project = (item) => {
    const x = item.x - center.x; const y = item.y - center.y; const z = item.z - center.z;
    if (view === "xy") return { x: canvas.width / 2 + x * scale, y: canvas.height / 2 - y * scale, depth: z };
    if (view === "xz") return { x: canvas.width / 2 + x * scale, y: canvas.height / 2 + z * scale, depth: y };
    if (view === "zy") return { x: canvas.width / 2 + z * scale, y: canvas.height / 2 - y * scale, depth: x };
    const rotatedX = x * cosYaw - z * sinYaw; const rotatedZ = x * sinYaw + z * cosYaw;
    return { x: canvas.width / 2 + rotatedX * scale, y: canvas.height * .52 - (y * cosPitch - rotatedZ * sinPitch) * scale, depth: y * sinPitch + rotatedZ * cosPitch };
  };
  return { center, extents, scale, view, project, cosYaw, sinYaw, cosPitch, sinPitch, canvasWidth: canvas.width, canvasHeight: canvas.height };
}

function cavePreviewWorldPosition(pointer) {
  const projection = state.cavePreview.projection;
  if (!projection) return null;
  const { center, scale, view } = projection;
  const defaultY = Number(state.cave.dimension?.origin?.y ?? 48);
  if (view === "xy") return { x: center.x + (pointer.x - projection.canvasWidth / 2) / scale, y: center.y - (pointer.y - projection.canvasHeight / 2) / scale, z: center.z };
  if (view === "xz") return { x: center.x + (pointer.x - projection.canvasWidth / 2) / scale, y: defaultY, z: center.z + (pointer.y - projection.canvasHeight / 2) / scale };
  if (view === "zy") return { x: center.x, y: center.y - (pointer.y - projection.canvasHeight / 2) / scale, z: center.z + (pointer.x - projection.canvasWidth / 2) / scale };
  const rotatedX = (pointer.x - projection.canvasWidth / 2) / scale;
  const deltaY = defaultY - center.y;
  const screenY = (pointer.y - projection.canvasHeight * .52) / scale;
  const rotatedZ = (screenY + deltaY * projection.cosPitch) / projection.sinPitch;
  return {
    x: center.x + projection.cosYaw * rotatedX + projection.sinYaw * rotatedZ,
    y: defaultY,
    z: center.z - projection.sinYaw * rotatedX + projection.cosYaw * rotatedZ
  };
}

function addCaveAnchorAt(pointer) {
  const position = cavePreviewWorldPosition(pointer); if (!position || !state.cave) return;
  const manual = state.cave.generator.manual_layout;
  const settings = state.cavePreview.placement.anchor;
  const prefix = String(settings.idPrefix || "anchor").trim().replace(/[^a-z0-9_.-]+/g, "_") || "anchor";
  let index = manual.anchors.length + 1;
  const used = new Set([...(state.cave.entrances || []).map((entry) => entry.id), ...manual.anchors.map((entry) => entry.id)]);
  while (used.has(`${prefix}_${index}`)) index++;
  const anchor = { id: `${prefix}_${index}`, kind: settings.kind, position: { x: Math.round(position.x), y: Math.round(position.y), z: Math.round(position.z) }, radius_x: Math.max(3, Math.min(96, Number(settings.radiusX) || 12)), radius_z: Math.max(3, Math.min(96, Number(settings.radiusZ) || 12)), height: Math.max(5, Math.min(96, Number(settings.height) || 12)) };
  manual.anchors.push(anchor); manual.enabled = true;
  state.cavePreview.selected = { source: "anchor", id: anchor.id };
  renderCaveManualLayoutEditors(); renderCaveLayoutPreview();
  toast(`공동 ${anchor.id}을(를) 추가했습니다. 계속 배치할 수 있습니다.`);
}

function addCaveEntranceAt(pointer) {
  const position = cavePreviewWorldPosition(pointer); if (!position || !state.cave) return;
  state.cave.entrances ||= [];
  const settings = state.cavePreview.placement.entrance;
  const prefix = String(settings.idPrefix || "entrance").trim().replace(/[^a-z0-9_.-]+/g, "_") || "entrance";
  let index = state.cave.entrances.length + 1;
  const used = new Set([...(state.cave.entrances || []).map((entry) => entry.id), ...(state.cave.generator?.manual_layout?.anchors || []).map((entry) => entry.id)]);
  while (used.has(`${prefix}_${index}`)) index++;
  const destination = { x: Math.round(position.x), y: Math.round(position.y), z: Math.round(position.z) };
  const entrance = { id: `${prefix}_${index}`, display_name: `${String(settings.displayName || "입출구").trim()} ${index}`.trim(), destination_anchor: destination, fallback_anchor: { x: destination.x + Number(settings.fallbackX), y: destination.y + Number(settings.fallbackY), z: destination.z + Number(settings.fallbackZ) } };
  if (String(settings.requiredProgress || "").trim()) entrance.required_progress = String(settings.requiredProgress).trim();
  state.cave.entrances.push(entrance);
  state.cavePreview.selected = { source: "entrance", id: entrance.id };
  renderCaveLayoutPreview();
  toast(`${entrance.display_name}을(를) 추가했습니다. 계속 배치할 수 있습니다.`);
}

function deleteSelectedCaveAnchor() {
  const selected = selectedCavePreviewNode(); if (!selected?.anchor) return;
  const manual = state.cave.generator.manual_layout;
  manual.anchors = manual.anchors.filter((anchor) => anchor !== selected.node);
  manual.connections = manual.connections.filter((connection) => connection.from !== selected.node.id && connection.to !== selected.node.id);
  state.cavePreview.selected = null;
  renderCaveManualLayoutEditors(); renderCaveLayoutPreview();
}

function deleteSelectedCaveEntrance() {
  const selected = selectedCavePreviewNode(); if (!selected?.entrance) return;
  state.cave.entrances = state.cave.entrances.filter((entrance) => entrance !== selected.node);
  const manual = state.cave.generator.manual_layout;
  manual.connections = manual.connections.filter((connection) => connection.from !== selected.node.id && connection.to !== selected.node.id);
  state.cavePreview.selected = null;
  renderCaveLayoutPreview();
}

function deleteSelectedCavePath() {
  const selected = selectedCavePreviewNode(); if (!selected?.path) return;
  const manual = state.cave.generator.manual_layout;
  manual.connections = manual.connections.filter((connection) => connection !== selected.node);
  state.cavePreview.selected = null;
  renderCaveLayoutPreview();
}

function chooseCavePathEndpoint(target) {
  if (!target || !["anchor", "entrance"].includes(target.source)) {
    toast("공동 또는 입출구를 선택해 주세요.");
    return;
  }
  if (!state.cavePreview.pathDraft) {
    state.cavePreview.pathDraft = { source: target.source, id: target.id };
    setCavePreviewTool("connect");
    renderCaveLayoutPreview();
    return;
  }
  if (state.cavePreview.pathDraft.id === target.id) {
    toast("서로 다른 두 앵커를 선택해 주세요.");
    return;
  }
  const manual = state.cave.generator.manual_layout;
  const existing = manual.connections.find((connection) => (connection.from === state.cavePreview.pathDraft.id && connection.to === target.id) || (connection.from === target.id && connection.to === state.cavePreview.pathDraft.id));
  if (existing) {
    state.cavePreview.selected = { source: "connection", id: existing.id };
    state.cavePreview.pathDraft = { source: target.source, id: target.id }; setCavePreviewTool("connect"); renderCaveLayoutPreview();
    toast("이미 연결된 통로입니다. 선택한 끝점부터 계속 연결할 수 있습니다.");
    return;
  }
  const settings = state.cavePreview.placement.path;
  const prefix = String(settings.idPrefix || "connection").trim().replace(/[^a-z0-9_.-]+/g, "_") || "connection";
  let index = manual.connections.length + 1;
  const used = new Set(manual.connections.map((entry) => entry.id));
  while (used.has(`${prefix}_${index}`)) index++;
  const connection = { id: `${prefix}_${index}`, from: state.cavePreview.pathDraft.id, to: target.id, kind: settings.kind, width: Math.max(3, Math.min(15, Number(settings.width) || 5)) };
  manual.connections.push(connection); manual.enabled = true;
  state.cavePreview.selected = { source: "connection", id: connection.id };
  state.cavePreview.pathDraft = { source: target.source, id: target.id };
  setCavePreviewTool("connect"); renderCaveLayoutPreview();
  toast(`${connection.from} → ${connection.to} 통로를 추가했습니다. 도착점부터 계속 연결할 수 있습니다.`);
}

function handleCavePlacementInput(event) {
  const field = event.target.dataset.cavePlacementField; const group = event.target.dataset.cavePlacementGroup;
  if (!field || !group || !state.cavePreview.placement[group]) return false;
  state.cavePreview.placement[group][field] = event.target.type === "number" ? Number(event.target.value) : event.target.value;
  return true;
}

function setCavePreviewTool(tool) {
  state.cavePreview.tool = ["add-anchor", "add-entrance", "connect"].includes(tool) ? tool : "select";
  if (state.cavePreview.tool !== "connect") state.cavePreview.pathDraft = null;
  const preview = $(".cave-layout-preview");
  preview.setAttribute("data-active-tool", state.cavePreview.tool);
  if (state.cavePreview.tool === "select") preview.removeAttribute("data-tool"); else preview.setAttribute("data-tool", state.cavePreview.tool);
  $$('[data-cave-preview-tool]').forEach((button) => {
    const active = button.dataset.cavePreviewTool === state.cavePreview.tool;
    button.classList.toggle("is-active", active); button.setAttribute("aria-pressed", String(active));
  });
  const hint = $("#cave-add-anchor-hint"); hint.hidden = state.cavePreview.tool === "select";
  hint.textContent = state.cavePreview.tool === "add-anchor"
    ? "지도에서 새 공동의 중심을 클릭하세요 · Esc 취소"
    : state.cavePreview.tool === "add-entrance"
      ? "지도에서 새 입구의 위치를 클릭하세요 · Esc 취소"
      : state.cavePreview.pathDraft
      ? `${state.cavePreview.pathDraft.id} 선택됨 · 연결할 두 번째 앵커를 선택하세요`
      : "길의 시작 앵커 또는 입출구를 선택하세요 · Esc 취소";
}

function renderCaveLayoutPreview() {
  const canvas = $("#cave-layout-canvas"); const summary = $("#cave-preview-summary");
  if (!canvas || !summary) return;
  syncCaveBuildBounds(); renderCaveDimensionSummary();
  const layout = buildCavePreviewLayout(); const context = canvas.getContext("2d");
  context.clearRect(0, 0, canvas.width, canvas.height);
  state.cavePreview.hitTargets = [];
  if (!layout) { summary.textContent = "동굴을 선택하면 배치를 계산합니다."; return; }
  const entranceQuickList = $("#cave-entrance-quick-list");
  if (entranceQuickList) entranceQuickList.innerHTML = (state.cave.entrances || []).map((entrance) => `<button type="button" data-select-cave-entrance="${escapeHtml(entrance.id)}" class="${state.cavePreview.selected?.source === "entrance" && state.cavePreview.selected.id === entrance.id ? "is-active" : ""}">${escapeHtml(entrance.display_name || entrance.id)}</button>`).join("") || "<small>등록된 입구가 없습니다.</small>";
  const projection = cavePreviewProjection(layout, canvas); const { project, scale, center, view } = projection;
  state.cavePreview.projection = projection;
  $$("[data-cave-view]").forEach((button) => button.classList.toggle("is-active", button.dataset.caveView === view));
  context.lineCap = "round"; context.lineJoin = "round";
  context.strokeStyle = "rgba(126, 157, 167, .13)"; context.lineWidth = 1;
  const gridY = Math.min(...[...layout.rooms, ...layout.entrances].map((item) => item.y)) - 8;
  for (let offset = -320; offset <= 320; offset += 64) {
    for (const axis of ["x", "z"]) {
      const a = project(axis === "x" ? { x: center.x + offset, y: gridY, z: center.z - 280 } : { x: center.x - 320, y: gridY, z: center.z + offset });
      const b = project(axis === "x" ? { x: center.x + offset, y: gridY, z: center.z + 280 } : { x: center.x + 320, y: gridY, z: center.z + offset });
      context.beginPath(); context.moveTo(a.x, a.y); context.lineTo(b.x, b.y); context.stroke();
    }
  }

  const bounds = state.cave.dimension?.bounds || {}; const waterY = layout.waterLevel;
  const waterCorners = [
    { x: Number(bounds.min_x ?? -256), y: waterY, z: Number(bounds.min_z ?? -256) }, { x: Number(bounds.max_x ?? 256), y: waterY, z: Number(bounds.min_z ?? -256) },
    { x: Number(bounds.max_x ?? 256), y: waterY, z: Number(bounds.max_z ?? 256) }, { x: Number(bounds.min_x ?? -256), y: waterY, z: Number(bounds.max_z ?? 256) }
  ].map(project);
  if (view === "xy" || view === "zy") {
    const left = view === "xy" ? project({ x: Number(bounds.min_x ?? -256), y: waterY, z: center.z }) : project({ x: center.x, y: waterY, z: Number(bounds.min_z ?? -256) });
    const right = view === "xy" ? project({ x: Number(bounds.max_x ?? 256), y: waterY, z: center.z }) : project({ x: center.x, y: waterY, z: Number(bounds.max_z ?? 256) });
    context.strokeStyle = "rgba(67, 184, 232, .72)"; context.lineWidth = 2; context.setLineDash([8, 6]); context.beginPath(); context.moveTo(left.x, left.y); context.lineTo(right.x, right.y); context.stroke(); context.setLineDash([]);
    context.fillStyle = "#75d4f5"; context.font = "700 10px sans-serif"; context.textAlign = "left"; context.fillText(`수면 Y ${waterY}`, Math.max(8, left.x + 8), left.y - 7);
  } else {
    context.fillStyle = view === "xz" ? "rgba(38, 146, 190, .08)" : "rgba(38, 146, 190, .13)"; context.strokeStyle = "rgba(67, 184, 232, .35)"; context.lineWidth = 1;
    context.beginPath(); waterCorners.forEach((point, index) => index ? context.lineTo(point.x, point.y) : context.moveTo(point.x, point.y)); context.closePath(); context.fill(); context.stroke();
  }

  const pathSegments = [];
  for (const path of layout.paths) for (let index = 0; index < path.points.length - 1; index++) {
    const from = path.points[index]; const to = path.points[index + 1];
    pathSegments.push({ id: path.id, source: path.source, kind: path.kind, from, to, a: project(from), b: project(to) });
  }
  pathSegments.sort((a, b) => ((a.a.depth + a.b.depth) - (b.a.depth + b.b.depth)));
  for (const segment of pathSegments) {
    const isSelectedPath = state.cavePreview.selected?.source === "connection" && state.cavePreview.selected.id === segment.id;
    context.strokeStyle = isSelectedPath ? "#ffffff" : "rgba(5, 9, 11, .82)"; context.lineWidth = segment.kind === "bridge" ? 12 : isSelectedPath ? 10 : 8; context.beginPath(); context.moveTo(segment.a.x, segment.a.y); context.lineTo(segment.b.x, segment.b.y); context.stroke();
    context.strokeStyle = segment.kind === "bridge" ? "#f19b62" : segment.kind === "stairs" || segment.kind === "loop" ? "#9ebc83" : segment.kind === "branch" ? "#718990" : "#a5b6ba";
    context.lineWidth = segment.kind === "bridge" ? 6 : segment.kind === "main" ? 4 : 3; context.beginPath(); context.moveTo(segment.a.x, segment.a.y); context.lineTo(segment.b.x, segment.b.y); context.stroke();
    if (segment.kind !== "bridge" && (segment.from.y < waterY || segment.to.y < waterY)) {
      let wetFrom = segment.from; let wetTo = segment.to;
      if ((segment.from.y < waterY) !== (segment.to.y < waterY)) {
        const factor = (waterY - segment.from.y) / (segment.to.y - segment.from.y);
        const crossing = { x: segment.from.x + (segment.to.x - segment.from.x) * factor, y: waterY, z: segment.from.z + (segment.to.z - segment.from.z) * factor };
        if (segment.from.y < waterY) wetTo = crossing; else wetFrom = crossing;
      }
      const wetA = project(wetFrom); const wetB = project(wetTo);
      context.strokeStyle = "rgba(67, 184, 232, .9)"; context.lineWidth = segment.kind === "main" ? 6 : 5; context.beginPath(); context.moveTo(wetA.x, wetA.y); context.lineTo(wetB.x, wetB.y); context.stroke();
    }
    if (segment.source === "connection") state.cavePreview.hitTargets.push({ mode: "select-path", source: "connection", id: segment.id, x1: segment.a.x, y1: segment.a.y, x2: segment.b.x, y2: segment.b.y, radius: 9 });
  }

  const selected = state.cavePreview.selected;
  const rooms = layout.rooms.map((room) => ({ room, projected: project(room) })).sort((a, b) => a.projected.depth - b.projected.depth);
  for (const { room, projected } of rooms) {
    const submerged = room.y < waterY; const color = submerged ? [67, 184, 232] : room.kind === "grand" ? [196, 139, 255] : room.kind === "moon" ? [208, 188, 244] : room.kind === "wild" ? [117, 174, 118] : [132, 153, 160];
    const radiusX = Math.max(7, (view === "zy" ? room.radiusZ : room.radiusX) * scale);
    const radiusY = Math.max(5, (view === "xz" ? room.radiusZ : room.height * .72) * scale);
    const isSelected = selected?.source === "anchor" && selected.id === room.id; const isDraft = state.cavePreview.pathDraft?.id === room.id;
    context.fillStyle = `rgba(${color.join(",")}, ${submerged ? .32 : .17})`; context.strokeStyle = isDraft ? "#b8e86b" : isSelected ? "#ffffff" : `rgba(${color.join(",")}, .9)`; context.lineWidth = isDraft || isSelected ? 3 : room.kind === "grand" ? 2.5 : 1.5;
    context.beginPath(); context.ellipse(projected.x, projected.y, radiusX, radiusY, 0, 0, Math.PI * 2); context.fill(); context.stroke();
    context.beginPath(); context.ellipse(projected.x, projected.y, radiusX * .66, radiusY, 0, 0, Math.PI * 2); context.strokeStyle = `rgba(${color.join(",")}, .28)`; context.stroke();
    if (room.kind === "grand" || submerged || isSelected) { context.fillStyle = "#e6f1f1"; context.font = "700 11px sans-serif"; context.textAlign = "center"; context.fillText(submerged ? `${room.id} · 수심 ${waterY - room.y}` : room.kind === "grand" ? "대공동" : room.id, projected.x, projected.y - radiusY - 8); }
    if (room.source === "anchor") state.cavePreview.hitTargets.push({ mode: "move", source: "anchor", id: room.id, x: projected.x, y: projected.y, radius: Math.max(10, Math.min(26, radiusX * .35)) });
  }
  for (const entrance of layout.entrances) {
    const p = project(entrance); const submerged = entrance.y < waterY; const isSelected = selected?.source === "entrance" && selected.id === entrance.id; const isDraft = state.cavePreview.pathDraft?.id === entrance.id;
    context.fillStyle = submerged ? "#43b8e8" : "#ffce67"; context.strokeStyle = isDraft ? "#b8e86b" : isSelected ? "#ffffff" : "#211b0d"; context.lineWidth = isDraft || isSelected ? 3 : 2; context.beginPath(); context.arc(p.x, p.y, isSelected || isDraft ? 10 : 8, 0, Math.PI * 2); context.fill(); context.stroke();
    if (entrance.id) state.cavePreview.hitTargets.push({ mode: "move", source: "entrance", id: entrance.id, x: p.x, y: p.y, radius: 22 });
  }

  const selectedRoom = selected?.source === "anchor" ? layout.rooms.find((room) => room.id === selected.id) : null;
  if (selectedRoom) {
    const handleSpecs = [
      { field: "radiusX", label: "X", point: { ...selectedRoom, x: selectedRoom.x + selectedRoom.radiusX } },
      { field: "radiusZ", label: "Z", point: { ...selectedRoom, z: selectedRoom.z + selectedRoom.radiusZ } },
      { field: "height", label: "H", point: { ...selectedRoom, y: selectedRoom.y + selectedRoom.height } }
    ];
    const centerPoint = project(selectedRoom);
    for (const handle of handleSpecs) {
      const point = project(handle.point); const dx = point.x - centerPoint.x; const dy = point.y - centerPoint.y; const pixels = Math.hypot(dx, dy);
      if (pixels < 5) continue;
      context.strokeStyle = "rgba(255,255,255,.52)"; context.lineWidth = 1; context.beginPath(); context.moveTo(centerPoint.x, centerPoint.y); context.lineTo(point.x, point.y); context.stroke();
      context.fillStyle = handle.field === "height" ? "#c48bff" : "#ffce67"; context.fillRect(point.x - 6, point.y - 6, 12, 12); context.fillStyle = "#132027"; context.font = "800 8px sans-serif"; context.textAlign = "center"; context.fillText(handle.label, point.x, point.y + 3);
      const value = handle.field === "radiusX" ? selectedRoom.radiusX : handle.field === "radiusZ" ? selectedRoom.radiusZ : selectedRoom.height;
      state.cavePreview.hitTargets.push({ mode: "resize", source: "anchor", id: selectedRoom.id, field: handle.field, value, x: point.x, y: point.y, radius: 10, unitX: dx / pixels, unitY: dy / pixels, pixelsPerUnit: pixels / value });
    }
  }
  renderCaveNodeInspector();
  if (!selectedCavePreviewNode()) $(".cave-layout-preview")?.removeAttribute("data-editing-node");
  const submergedRooms = layout.rooms.filter((room) => room.y < waterY).length;
  summary.textContent = `${layout.manual ? "수동 배치" : "자동 배치"} · 입구 ${state.cave.entrances?.length || 0}개 · 공동 ${layout.rooms.length}개 · 연결 ${layout.paths.length}개 · 수면 Y ${waterY} · 침수 공동 ${submergedRooms}개 · 돌다리 ${layout.paths.filter((path) => path.kind === "bridge").length}개`;
}

function cavePreviewPointer(event) {
  const canvas = event.currentTarget; const bounds = canvas.getBoundingClientRect();
  return { x: (event.clientX - bounds.left) * canvas.width / bounds.width, y: (event.clientY - bounds.top) * canvas.height / bounds.height };
}

function cavePreviewHitDistance(target, pointer) {
  if (target.mode !== "select-path") return Math.hypot(pointer.x - target.x, pointer.y - target.y);
  const dx = target.x2 - target.x1; const dy = target.y2 - target.y1;
  const lengthSquared = dx * dx + dy * dy;
  const progress = lengthSquared === 0 ? 0 : Math.max(0, Math.min(1, ((pointer.x - target.x1) * dx + (pointer.y - target.y1) * dy) / lengthSquared));
  return Math.hypot(pointer.x - (target.x1 + dx * progress), pointer.y - (target.y1 + dy * progress));
}

function cavePreviewTargetPriority(target) {
  if (target.mode === "resize") return 0;
  if (target.source === "entrance") return 1;
  if (target.mode === "move") return 2;
  return 3;
}

function beginCavePreviewDrag(event) {
  const pointer = cavePreviewPointer(event);
  const target = [...state.cavePreview.hitTargets]
    .map((candidate) => ({ candidate, distance: cavePreviewHitDistance(candidate, pointer) }))
    .filter(({ candidate, distance }) => distance <= candidate.radius)
    .sort((left, right) => cavePreviewTargetPriority(left.candidate) - cavePreviewTargetPriority(right.candidate) || left.distance - right.distance)[0]?.candidate;
  if (state.cavePreview.tool === "add-anchor") {
    addCaveAnchorAt(pointer);
    return;
  }
  if (state.cavePreview.tool === "add-entrance") {
    addCaveEntranceAt(pointer);
    return;
  }
  if (state.cavePreview.tool === "connect") {
    chooseCavePathEndpoint(target);
    return;
  }
  if (target) {
    state.cavePreview.selected = { source: target.source, id: target.id };
    renderCaveLayoutPreview();
    if (target.mode === "select-path") return;
    const node = selectedCavePreviewNode();
    state.cavePreview.drag = target.mode === "resize"
      ? { ...target, pointer }
      : { mode: "move", pointer, start: { ...node.position } };
  } else if ((state.cavePreview.view || "perspective") === "perspective") {
    state.cavePreview.drag = { mode: "rotate", pointer, yaw: state.cavePreview.yaw, pitch: state.cavePreview.pitch };
  } else {
    state.cavePreview.selected = null; renderCaveLayoutPreview(); return;
  }
  event.currentTarget.setPointerCapture(event.pointerId); event.currentTarget.classList.add("is-dragging");
}

function moveCavePreviewDrag(event) {
  const drag = state.cavePreview.drag; if (!drag) return;
  const pointer = cavePreviewPointer(event); const deltaX = pointer.x - drag.pointer.x; const deltaY = pointer.y - drag.pointer.y;
  if (drag.mode === "rotate") {
    state.cavePreview.yaw = drag.yaw + deltaX * .009;
    state.cavePreview.pitch = Math.max(-1.25, Math.min(-.18, drag.pitch + deltaY * .006));
  } else if (drag.mode === "resize") {
    const projectedDelta = deltaX * drag.unitX + deltaY * drag.unitY;
    mutateSelectedCaveNode({ [drag.field]: Math.round((drag.value + projectedDelta / drag.pixelsPerUnit) * 2) / 2 });
  } else {
    const projection = state.cavePreview.projection; const values = {};
    if (projection.view === "xy") { values.x = drag.start.x + deltaX / projection.scale; values.y = drag.start.y - deltaY / projection.scale; }
    else if (projection.view === "xz") { values.x = drag.start.x + deltaX / projection.scale; values.z = drag.start.z + deltaY / projection.scale; }
    else if (projection.view === "zy") { values.z = drag.start.z + deltaX / projection.scale; values.y = drag.start.y - deltaY / projection.scale; }
    else {
      const screenX = deltaX / projection.scale; const rotatedZ = deltaY / (projection.scale * projection.sinPitch);
      values.x = drag.start.x + projection.cosYaw * screenX + projection.sinYaw * rotatedZ;
      values.z = drag.start.z - projection.sinYaw * screenX + projection.cosYaw * rotatedZ;
    }
    mutateSelectedCaveNode(values);
  }
  renderCaveLayoutPreview();
}

function endCavePreviewDrag(event) {
  const edited = state.cavePreview.drag?.mode === "move" || state.cavePreview.drag?.mode === "resize";
  state.cavePreview.drag = null; event.currentTarget.classList.remove("is-dragging");
  if (edited) { renderCaveArrayEditors(); renderCaveManualLayoutEditors(); renderCaveLayoutPreview(); }
}

function handleCavePreviewInspectorInput(event) {
  const field = event.target.dataset.caveSelectedField;
  if (!field) return false;
  if (["id", "pathId", "from", "to"].includes(field)) return true;
  const value = ["kind", "pathKind", "entranceId", "displayName", "requiredProgress"].includes(field) ? event.target.value : Number(event.target.value);
  if (mutateSelectedCaveNode({ [field]: value })) renderCaveLayoutPreview();
  return true;
}

function doubleBattleDefaults(npcId) {
  const slug = String(npcId || "double_npc").split("/").at(-1).replace(/[^a-z0-9_./-]/g, "_");
  return {
    group_id: `cobbleventure:double_battle/${slug}`,
    shared_clear_key: `cobbleventure:clear/double_battle/${slug}`,
  };
}

function renderDoubleBattleSettings(form = $("#trainer-form")) {
  const enabled = form.elements.doubleBattleEnabled.checked;
  $$('[data-double-battle-field]').forEach((element) => { element.hidden = !enabled; });
  const partnerId = form.elements.doubleBattlePartner.value;
  const partnerSlug = partnerId.split("/").at(-1);
  form.elements.doubleBattleSpawnCommand.value = partnerId
    ? `/easy_npc preset import_new data easy_npc:preset/encounter/${partnerSlug}.npc.snbt ~ ~ ~`
    : "파트너 NPC를 선택하세요.";
  $("#copy-double-spawn-command").disabled = !enabled || !partnerId;
}

function ensureDoubleBattleClearCommand(clearKey) {
  if (!clearKey) return;
  for (const event of state.trainer?.events || []) {
    const commands = event.commands || [];
    if (commands.some((command) => command.type === "mark_clear" && command.key === clearKey)) continue;
    const winLabels = new Set(commands
      .filter((command) => command.type === "start_battle")
      .map((command) => command.results?.player_win)
      .filter(Boolean));
    const labelIndex = commands.findIndex((command) => command.type === "label" && winLabels.has(command.name));
    if (labelIndex >= 0) commands.splice(labelIndex + 1, 0, { type: "mark_clear", key: clearKey });
  }
}

function renderTrainer() {
  const document = state.trainer;
  const form = $("#trainer-form");
  const attachedBattle = trainerBattle();
  const battle = attachedBattle || {
    format: "GEN_9_SINGLES", battle_type: "singles", level_mode: "fixed",
    ai: { controller: "cobbleventure", difficulty: "standard", strategy: "balanced", options: {} },
    rules: {}, bag: [], mechanics: { mega_evolution: false, z_move: false, dynamax: false, terastallization: false }, team: [],
  };
  const ai = normalizeTrainerAi(battle);
  battle.ai = ai;
  delete battle.difficulty;
  $("#trainer-editor-title").textContent = document.name?.ko_kr || document.id;
  $("#trainer-path").textContent = state.trainerPath;
  setFormValue(form, "id", document.id);
  setFormValue(form, "nameKo", document.name?.ko_kr);
  setFormValue(form, "nameEn", document.name?.en_us);
  setFormValue(form, "tags", (document.tags || []).join(", "));
  const categoryNames = {
    children: "어린이·학생", outdoor: "야외·탐험", specialist: "타입 특화",
    occupation: "직업·취미", social: "성인·상류층", advanced: "상급·특수",
    boss: "보스·네임드", custom: "사용자 정의"
  };
  form.elements.trainerClass.innerHTML = Object.entries(categoryNames).map(([category, label]) => {
    const options = state.trainerClasses.filter((entry) => entry.category === category).map((trainerClass) =>
      `<option value="${escapeHtml(trainerClass.id)}">${escapeHtml(trainerClass.display_name?.ko_kr || trainerClass.id)}</option>`
    ).join("");
    return options ? `<optgroup label="${label}">${options}</optgroup>` : "";
  }).join("");
  setFormValue(form, "trainerClass", document.npc?.trainer_class);
  form.elements.rosterCharacter.innerHTML = rosterCharacterOptions(document.npc?.trainer_class);
  setFormValue(form, "rosterCharacter", document.npc?.character || "");
  setFormValue(form, "appearanceSource", document.npc?.appearance?.source);
  setFormValue(form, "appearanceResource", document.npc?.appearance?.resource);
  setFormValue(form, "npcRole", document.npc?.role || "default");
  setFormValue(form, "movement", document.npc?.behavior?.movement);
  setFormValue(form, "interactionRange", document.npc?.behavior?.interaction_range);
  const encounter = document.npc?.behavior?.encounter || { mode: "interaction", trigger_range: document.npc?.behavior?.interaction_range || 4, warning_range: { min: 4, max: 6 } };
  setFormValue(form, "encounterMode", encounter.mode);
  setFormValue(form, "encounterTriggerRange", encounter.trigger_range ?? document.npc?.behavior?.interaction_range ?? 4);
  setFormValue(form, "warningRangeMin", encounter.warning_range?.min ?? 4);
  setFormValue(form, "warningRangeMax", encounter.warning_range?.max ?? 6);
  setFormValue(form, "lookAtPlayer", document.npc?.behavior?.look_at_player);
  setFormValue(form, "invulnerable", document.npc?.behavior?.invulnerable);
  const doubleBattle = document.npc?.double_battle;
  form.elements.doubleBattlePartner.innerHTML = [
    '<option value="">파트너 NPC 선택</option>',
    ...state.trainers
      .filter((trainer) => trainer.id !== document.id)
      .map((trainer) => `<option value="${escapeHtml(trainer.id)}">${escapeHtml(trainer.name || trainer.id)} · ${escapeHtml(trainer.id)}</option>`),
  ].join("");
  setFormValue(form, "doubleBattleEnabled", Boolean(doubleBattle));
  setFormValue(form, "doubleBattlePartner", doubleBattle?.partner || "");
  const doubleDefaults = doubleBattleDefaults(document.id);
  setFormValue(form, "doubleBattleGroupId", doubleBattle?.group_id || doubleDefaults.group_id);
  setFormValue(form, "doubleBattleClearKey", doubleBattle?.shared_clear_key || doubleDefaults.shared_clear_key);
  renderDoubleBattleSettings(form);
  setFormValue(form, "battleFormat", battle.format);
  setFormValue(form, "battleType", battle.battle_type);
  setFormValue(form, "battleDifficulty", ai.difficulty);
  setFormValue(form, "battleAi", ai.strategy);
  setFormValue(form, "cheatProbability", Math.round((ai.options?.cheat_probability ?? 0.5) * 100));
  renderCheatProbability(form);
  setFormValue(form, "levelMode", battle.level_mode);
  setFormValue(form, "megaEvolution", battle.mechanics?.mega_evolution);
  setFormValue(form, "zMove", battle.mechanics?.z_move);
  setFormValue(form, "dynamax", battle.mechanics?.dynamax);
  setFormValue(form, "terastallization", battle.mechanics?.terastallization);
  const defaultEntry = document.interaction?.entry_routes?.at(-1)?.entry || document.dialogue?.entry;
  const interactionNodes = document.interaction?.nodes || document.dialogue?.nodes || [];
  const greeting = interactionNodes.find((node) => node.id === defaultEntry) || interactionNodes.find((node) => node.type === "dialogue");
  setFormValue(form, "greetingText", greeting?.text?.ko_kr || "");
  const rewardNode = interactionNodes.find((node) => node.type === "actions" && node.actions?.some((action) => action.type === "give_money" || action.type === "grant_loot" || action.type === "give_item"));
  const money = document.npc?.battle_rewards?.money || rewardNode?.actions?.find((action) => action.type === "give_money") || document.rewards?.money || { enabled: false, mode: "fixed", amount: 0, held_item_bonus: true, conditions: [] };
  setFormValue(form, "moneyEnabled", money.enabled ?? true);
  setFormValue(form, "moneyMode", money.mode);
  setFormValue(form, "moneyAmount", money.amount ?? 0);
  setFormValue(form, "moneyMultiplier", money.per_level ?? money.multiplier ?? 20);
  setFormValue(form, "moneyOffset", money.offset ?? 0);
  setFormValue(form, "moneyFallbackLevel", money.fallback_region_level ?? 5);
  setFormValue(form, "moneyConditionFlag", money.conditions?.find((condition) => condition.type === "flag_equals")?.key || "");
  setFormValue(form, "moneyHeldItemBonus", money.held_item_bonus ?? true);
  setFormValue(form, "moneyHeldItem", money.held_item || "cobblemon:amulet_coin");
  setFormValue(form, "moneyHeldMultiplier", money.held_item_multiplier ?? 2);
  const lootAction = rewardNode?.actions?.find((action) => action.type === "grant_loot");
  const itemAction = rewardNode?.actions?.find((action) => action.type === "give_item");
  const itemReward = lootAction ? { mode: "loot_table", loot_table: lootAction.loot_table } : itemAction ? { mode: "fixed", entries: [{ item: itemAction.item, count: itemAction.count }] } : document.rewards?.items || { mode: "fixed", entries: [] };
  const fixedReward = itemReward.entries?.[0] || { item: "cobblemon:poke_ball", count: 1 };
  setFormValue(form, "itemRewardMode", itemReward.mode);
  setFormValue(form, "rewardItem", fixedReward.item);
  setFormValue(form, "rewardItemCount", fixedReward.count);
  setFormValue(form, "rewardLootTable", itemReward.loot_table || "cobbleventure:trainer/rewards");
  setFormValue(form, "spawnCommand", `/easy_npc preset import_new data easy_npc:preset/encounter/${document.id.split("/").at(-1)}.npc.snbt ~ ~ ~`);
  renderTrainerRewardFields(form);
  $("#max-item-uses").value = Number.isInteger(battle.rules?.max_item_uses)
    ? battle.rules.max_item_uses
    : "";
  [...form.elements].forEach((element) => element.disabled = false);
  renderDoubleBattleSettings(form);
  $("#max-item-uses").disabled = false;
  const battleFieldNames = ["battleFormat", "battleType", "battleDifficulty", "battleAi", "cheatProbability", "levelMode", "megaEvolution", "zMove", "dynamax", "terastallization"];
  battleFieldNames.forEach((name) => { form.elements[name].disabled = !attachedBattle; });
  ["#max-item-uses", "#add-bag-item", "#load-trainer-reference", "#copy-team-json", "#paste-team-json", "#add-pokemon"].forEach((selector) => { $(selector).disabled = !attachedBattle; });
  renderTrainerPreview();
  if (document.schema_version === 4) renderEventScript();
  else renderEntryRoutes();
  $("#trainer-json").value = JSON.stringify(document, null, 2);
  ["#trainer-json", "#apply-trainer-json", "#add-event-command", "#event-command-type", "#event-trigger-type", "#event-trigger-range", "#event-warning-offset", "#copy-spawn-command", "#delete-trainer", "#validate-trainer", "#save-trainer"].forEach((selector) => { if ($(selector)) $(selector).disabled = false; });
  showIssues("#trainer-issues", { valid: true, issues: [] });
}

function updateTrainerFromForm() {
  if (!state.trainer) return;
  const form = $("#trainer-form");
  const name = { ...(state.trainer.name || {}), ko_kr: form.elements.nameKo.value };
  if (form.elements.nameEn.value.trim()) name.en_us = form.elements.nameEn.value;
  else delete name.en_us;
  state.trainer.name = name;
  state.trainer.npc.trainer_class = form.elements.trainerClass.value;
  if (form.elements.rosterCharacter.value) state.trainer.npc.character = form.elements.rosterCharacter.value;
  else delete state.trainer.npc.character;
  state.trainer.npc.display_name = trainerDisplayName(name, state.trainer.npc.trainer_class);
  state.trainer.npc.appearance.source = form.elements.appearanceSource.value;
  state.trainer.npc.appearance.resource = form.elements.appearanceResource.value;
  state.trainer.tags = form.elements.tags.value.split(",").map((tag) => tag.trim()).filter(Boolean);
  if (state.trainer.schema_version === 4) {
    Object.assign(state.trainer.npc.behavior, {
      movement: form.elements.movement.value,
      look_at_player: form.elements.lookAtPlayer.checked,
      invulnerable: form.elements.invulnerable.checked,
    });
    state.trainer.npc.role = form.elements.npcRole.value;
    const conditionFlag = form.elements.moneyConditionFlag.value.trim();
    const moneyMode = form.elements.moneyMode.value;
    state.trainer.npc.battle_rewards = { money: {
      enabled: form.elements.moneyEnabled.checked,
      mode: moneyMode,
      ...(moneyMode === "fixed"
        ? { amount: Math.max(0, Number.parseInt(form.elements.moneyAmount.value, 10) || 0) }
        : {
          fallback_region_level: Math.max(1, Math.min(100, Number.parseInt(form.elements.moneyFallbackLevel.value, 10) || 5)),
          per_level: Math.max(0, Number.parseInt(form.elements.moneyMultiplier.value, 10) || 0),
          offset: Number.parseInt(form.elements.moneyOffset.value, 10) || 0,
        }),
      held_item_bonus: form.elements.moneyHeldItemBonus.checked,
      held_item: form.elements.moneyHeldItem.value.trim() || "cobblemon:amulet_coin",
      held_item_multiplier: Math.max(1, Number.parseInt(form.elements.moneyHeldMultiplier.value, 10) || 2),
      conditions: conditionFlag ? [{ type: "flag_equals", key: conditionFlag, value: true }] : [],
    } };
    delete state.trainer.npc.behavior.interaction_range;
    delete state.trainer.npc.behavior.encounter;
    if (form.elements.doubleBattleEnabled.checked) {
      const defaults = doubleBattleDefaults(state.trainer.id);
      const sharedClearKey = form.elements.doubleBattleClearKey.value.trim() || defaults.shared_clear_key;
      state.trainer.npc.double_battle = {
        partner: form.elements.doubleBattlePartner.value,
        group_id: form.elements.doubleBattleGroupId.value.trim() || defaults.group_id,
        shared_clear_key: sharedClearKey,
      };
      ensureDoubleBattleClearCommand(sharedClearKey);
    } else {
      delete state.trainer.npc.double_battle;
    }
    renderDoubleBattleSettings(form);
    renderTrainerRewardFields(form);
    renderTrainerPreview();
    syncTrainerJson();
    return;
  }
  Object.assign(state.trainer.npc.behavior, {
    movement: form.elements.movement.value,
    interaction_range: Number(form.elements.interactionRange.value),
    look_at_player: form.elements.lookAtPlayer.checked,
    invulnerable: form.elements.invulnerable.checked
  });
  state.trainer.npc.role = form.elements.npcRole.value;
  state.trainer.npc.behavior.encounter = {
    mode: form.elements.encounterMode.value,
    trigger_range: Number(form.elements.encounterTriggerRange.value),
    warning_range: {
      min: Number(form.elements.warningRangeMin.value),
      max: Number(form.elements.warningRangeMax.value),
      indicator: "trainer_nearby"
    }
  };
  const difficulty = form.elements.battleDifficulty.value;
  const aiOptions = difficulty === "cheater"
    ? { cheat_probability: Math.max(0, Math.min(1, Number(form.elements.cheatProbability.value) / 100)) }
    : {};
  const battle = trainerBattle();
  if (battle) Object.assign(battle, {
      format: form.elements.battleFormat.value,
      battle_type: form.elements.battleType.value,
      ai: {
        controller: "cobbleventure",
        difficulty,
        strategy: form.elements.battleAi.value,
        options: aiOptions,
      },
      level_mode: form.elements.levelMode.value
    });
  renderCheatProbability(form);
  if (battle) {
    battle.rules ||= {};
    const maxItemUses = $("#max-item-uses").value.trim();
    if (maxItemUses === "") delete battle.rules.max_item_uses;
    else battle.rules.max_item_uses = Math.max(0, Number.parseInt(maxItemUses, 10) || 0);
    battle.mechanics ||= {};
    Object.assign(battle.mechanics, {
      mega_evolution: form.elements.megaEvolution.checked,
      z_move: form.elements.zMove.checked,
      dynamax: form.elements.dynamax.checked,
      terastallization: form.elements.terastallization.checked
    });
  }
  const interactionNodes = state.trainer.interaction?.nodes || state.trainer.dialogue?.nodes || [];
  const defaultEntry = state.trainer.interaction?.entry_routes?.at(-1)?.entry || state.trainer.dialogue?.entry;
  const greeting = interactionNodes.find((node) => node.id === defaultEntry) || interactionNodes.find((node) => node.type === "dialogue");
  if (greeting) greeting.text = { ...(greeting.text || {}), ko_kr: form.elements.greetingText.value };
  const moneyMode = form.elements.moneyMode.value;
  const currencyObjective = "cobbleventure_money";
  const money = moneyMode === "level_cap_multiplier"
    ? { mode: moneyMode, multiplier: Math.max(1, Number(form.elements.moneyMultiplier.value) || 1), currency_objective: currencyObjective, level_cap_objective: "cobbleventure_level_cap" }
    : { mode: moneyMode, amount: Math.max(0, Number.parseInt(form.elements.moneyAmount.value, 10) || 0), currency_objective: currencyObjective };
  const itemRewardMode = form.elements.itemRewardMode.value;
  const items = itemRewardMode === "loot_table"
    ? { mode: itemRewardMode, loot_table: form.elements.rewardLootTable.value.trim() }
    : { mode: itemRewardMode, entries: [{ item: form.elements.rewardItem.value.trim(), count: Math.max(1, Number.parseInt(form.elements.rewardItemCount.value, 10) || 1) }] };
  const rewardNode = interactionNodes.find((node) => node.type === "actions" && node.actions?.some((action) => ["give_money", "grant_loot", "give_item"].includes(action.type)));
  if (rewardNode) {
    rewardNode.actions = rewardNode.actions.filter((action) => !["give_money", "grant_loot", "give_item"].includes(action.type));
    rewardNode.actions.push({ type: "give_money", ...money });
    if (items.mode === "loot_table") rewardNode.actions.push({ type: "grant_loot", loot_table: items.loot_table });
    else rewardNode.actions.push(...items.entries.map((entry) => ({ type: "give_item", ...entry })));
  } else {
    state.trainer.rewards = { money, items };
  }
  renderTrainerRewardFields(form);
  for (const pokemon of battle?.team || []) {
    if (!pokemon.gimmick?.type) continue;
    battle.mechanics[pokemon.gimmick.type] = true;
    form.elements[pokemon.gimmick.type === "mega_evolution" ? "megaEvolution" : "zMove"].checked = true;
  }
  renderTrainerPreview();
  syncTrainerJson();
}

function normalizeTrainerAi(battle) {
  const raw = battle?.ai;
  if (raw && typeof raw === "object" && !Array.isArray(raw)) {
    return {
      controller: raw.controller || "cobbleventure",
      difficulty: raw.difficulty || "standard",
      strategy: raw.strategy || "balanced",
      options: raw.options && typeof raw.options === "object" ? { ...raw.options } : {},
    };
  }
  return {
    controller: "cobbleventure",
    difficulty: battle?.difficulty || "standard",
    strategy: String(raw || "cobbleventure:ai/balanced").replace(/^cobbleventure:ai\//, ""),
    options: {},
  };
}

function renderCheatProbability(form = $("#trainer-form")) {
  const isCheater = form.elements.battleDifficulty.value === "cheater";
  $("#ai-cheat-probability").hidden = !isCheater;
  const percentage = Math.max(0, Math.min(100, Number(form.elements.cheatProbability.value) || 0));
  $("#cheat-probability-output").textContent = `${percentage}%`;
}

function renderTrainerRewardFields(form = $("#trainer-form")) {
  $$('[data-money-field]').forEach((element) => { element.hidden = element.dataset.moneyField !== form.elements.moneyMode.value; });
  $$('[data-item-reward-field]').forEach((element) => { element.hidden = element.dataset.itemRewardField !== form.elements.itemRewardMode.value; });
}

const eventCommandLabels = {
  branch: "조건 분기", label: "라벨", dialogue: "대화 표시", choices: "선택지 표시",
  goto: "라벨로 이동", start_battle: "배틀 시작", set_flag: "플래그 변경",
  give_money: "돈 지급", take_money: "돈 차감", give_item: "아이템 지급", grant_loot: "루트 테이블 지급", grant_badge: "배지 기록", grant_field_move: "비전머신 획득", mark_clear: "클리어 처리", teleport_to_gate: "관문으로 이동", end: "이벤트 종료",
};
const fieldMoveChoices = [
  ["surf", "파도타기"], ["fly", "공중날기"], ["flash", "플래쉬"], ["defog", "안개제거"],
  ["rock_climb", "락클레임"], ["whirlpool", "바다회오리"], ["strength", "괴력"], ["rock_smash", "바위깨기"],
];
const expandedEventCommands = new Set();

function selectedNpcEvent() {
  return state.trainer?.schema_version === 4 ? state.trainer.events?.[0] : null;
}

function eventCommandSummary(command) {
  const short = (value, limit = 92) => {
    const text = String(value || "").replace(/\s+/g, " ").trim();
    return text.length > limit ? `${text.slice(0, limit)}…` : text;
  };
  if (command.type === "branch") {
    const condition = command.conditions?.[0] || { type: "always" };
    const detail = condition.type === "flag_equals" ? `${condition.key} = ${String(condition.value)}` : condition.type === "has_item" ? `${condition.item} × ${condition.count || 1} 보유` : "항상";
    return `${detail} → ${command.target || "라벨 없음"}`;
  }
  if (command.type === "label") return command.name || "이름 없는 라벨";
  if (command.type === "dialogue") return `“${short(command.text?.ko_kr || "대사 없음")}”`;
  if (command.type === "choices") return (command.options || []).map((option) => `${short(option.text?.ko_kr, 24)} → ${option.target}`).join("  ·  ") || "선택지 없음";
  if (command.type === "goto") return `→ ${command.target || "라벨 없음"}`;
  if (command.type === "start_battle") return `${command.battle || "배틀 없음"} · 승리→${command.results?.player_win || "-"} · 패배→${command.results?.player_loss || "-"}`;
  if (command.type === "set_flag") return `${command.key || "플래그 없음"} = ${String(command.value)}`;
  if (command.type === "mark_clear") return command.key || "클리어 ID 없음";
  if (command.type === "give_money") return command.mode === "level_cap_multiplier" ? `레벨캡 × ${command.multiplier || 1}` : `${Number(command.amount || 0).toLocaleString()} 지급`;
  if (command.type === "take_money") return command.mode === "level_cap_multiplier" ? `레벨캡 × ${command.multiplier || 1} 차감` : `${Number(command.amount || 0).toLocaleString()} 차감`;
  if (command.type === "give_item") return `${command.item || "아이템 없음"} × ${command.count || 1}`;
  if (command.type === "grant_loot") return command.loot_table || "루트 테이블 없음";
  if (command.type === "grant_badge") return badgeById(command.badge)?.display_name?.ko_kr || command.badge || "배지 없음";
  if (command.type === "grant_field_move") return `${fieldMoveChoices.find(([id]) => id === command.move)?.[1] || command.move || "비전머신 없음"} 획득`;
  if (command.type === "teleport_to_gate") return `${command.subject === "npc" ? "NPC" : "플레이어"} → ${command.gate || "관문 없음"} · ${command.side || "front"}`;
  if (command.type === "end") return "이벤트 실행 종료";
  return command.type;
}

function defaultNpcDialogueFlag() {
  const rawId = String(state.trainer?.id || "cobbleventure:npc/new_npc");
  const [namespace = "cobbleventure", path = "npc/new_npc"] = rawId.split(":");
  const slug = path.replace(/^npc\//, "").replace(/[^a-z0-9_/.-]+/gi, "_");
  return `${namespace}:flag/npc/${slug}/talked`;
}

function defaultProgressionClearKey(type) {
  const rawId = String(state.trainer?.id || "cobbleventure:npc/new_npc");
  const [namespace = "cobbleventure", path = "npc/new_npc"] = rawId.split(":");
  const slug = path.replace(/^npc\//, "").replace(/[^a-z0-9_/.-]+/gi, "_");
  const group = { gym: "gym", elite: "elite", champion: "champion" }[type] || "battle";
  return `${namespace}:clear/${group}/${slug}`;
}

function clearKeyChoices() {
  const choices = [
    [defaultProgressionClearKey("battle"), "현재 NPC · 일반 배틀"],
    [defaultProgressionClearKey("gym"), "현재 NPC · 체육관"],
    [defaultProgressionClearKey("elite"), "현재 NPC · 사천왕"],
    [defaultProgressionClearKey("champion"), "현재 NPC · 챔피언"],
  ];
  const sharedClearKey = state.trainer?.npc?.double_battle?.shared_clear_key;
  if (sharedClearKey) choices.push([sharedClearKey, "현재 NPC · 더블배틀 공유"]);
  for (const event of state.trainer?.events || []) {
    for (const command of event.commands || []) {
      if (command.type === "mark_clear" && command.key) choices.push([command.key, "현재 이벤트에서 사용 중"]);
    }
  }
  return choices.filter(([key], index, entries) => key && entries.findIndex(([candidate]) => candidate === key) === index);
}

function clearKeyOptions(value) {
  const choices = clearKeyChoices();
  const selected = choices.some(([key]) => key === value) ? value : "__custom__";
  return `${choices.map(([key, label]) => `<option value="${escapeHtml(key)}" ${selected === key ? "selected" : ""}>${escapeHtml(label)} · ${escapeHtml(key)}</option>`).join("")}<option value="__custom__" ${selected === "__custom__" ? "selected" : ""}>직접 입력…</option>`;
}

function presetClearKeyValue() {
  const select = $("#event-preset-clear-key");
  return select.value === "__custom__" ? $("#event-preset-clear-key-custom").value.trim() : select.value;
}

function updatePresetClearKeyMode(focusCustom = false) {
  const select = $("#event-preset-clear-key");
  const custom = $("#event-preset-clear-key-custom");
  const isCustom = select.value === "__custom__";
  if (isCustom && !custom.value) custom.value = select.dataset.currentValue || "";
  custom.hidden = !isCustom;
  custom.disabled = select.disabled || !isCustom;
  if (focusCustom && isCustom) requestAnimationFrame(() => custom.focus());
}

function renderEventPresetFields() {
  const type = $("#event-preset-type").value;
  const battleTypes = ["battle", "gym", "elite", "champion"];
  const progressionTypes = ["gym", "elite", "champion"];
  $$('[data-event-preset-option="repeat"]').forEach((element) => { element.hidden = !["repeat", "item"].includes(type); });
  $$('[data-event-preset-option="item"]').forEach((element) => { element.hidden = type !== "item"; });
  $$('[data-event-preset-option="battle"]').forEach((element) => { element.hidden = !battleTypes.includes(type); });
  $$('[data-event-preset-option="gym"]').forEach((element) => { element.hidden = type !== "gym"; });
  $$('[data-event-preset-option="progression"]').forEach((element) => { element.hidden = !progressionTypes.includes(type); });
  const battleSelect = $("#event-preset-battle");
  const selectedBattle = battleSelect.value;
  battleSelect.innerHTML = state.battles.map((battle) => `<option value="${escapeHtml(battle.id)}">${escapeHtml(battle.name?.ko_kr || battle.id)}</option>`).join("") || '<option value="cobbleventure:battle/example">등록된 배틀 프리셋 없음</option>';
  if ([...battleSelect.options].some((option) => option.value === selectedBattle)) battleSelect.value = selectedBattle;
  const badgeSelect = $("#event-preset-badge");
  const selectedBadge = badgeSelect.value;
  const badges = state.badgeCatalog.badges || [];
  badgeSelect.innerHTML = badges.map((badge) => `<option value="${escapeHtml(badge.id)}">${badge.generation}세대 ${badge.order}번째 · ${escapeHtml(badge.display_name?.ko_kr || badge.id)}</option>`).join("") || '<option value="">배지 카탈로그 없음</option>';
  if ([...badgeSelect.options].some((option) => option.value === selectedBadge)) badgeSelect.value = selectedBadge;
  const clearKey = $("#event-preset-clear-key");
  const customClearKey = $("#event-preset-clear-key-custom");
  let selectedClearKey = presetClearKeyValue();
  if (progressionTypes.includes(type) && clearKey.dataset.presetType !== type) {
    clearKey.dataset.presetType = type;
    selectedClearKey = defaultProgressionClearKey(type);
  }
  clearKey.innerHTML = clearKeyOptions(selectedClearKey);
  clearKey.dataset.currentValue = selectedClearKey;
  customClearKey.value = clearKey.value === "__custom__" ? selectedClearKey : "";
  updatePresetClearKeyMode();
}

function dialogueLines(value) {
  return String(value || "").split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
}

function dialogueCommands(idPrefix, text, speaker = "npc") {
  const lines = dialogueLines(text);
  return (lines.length ? lines : ["대사를 입력하세요."]).map((line, index) => ({
    type: "dialogue",
    id: lines.length > 1 ? `${idPrefix}_${index + 1}` : idPrefix,
    speaker,
    text: { ko_kr: line },
  }));
}

function applyEventScriptPreset() {
  const event = selectedNpcEvent();
  if (!event) return;
  const type = $("#event-preset-type").value;
  const firstText = $("#event-preset-first-text").value.trim() || "안녕하세요!";
  const repeatText = $("#event-preset-repeat-text").value.trim() || "다시 만났네요.";
  if (type === "simple") {
    event.commands = [
      ...dialogueCommands("greeting", firstText),
      { type: "end" },
    ];
  } else if (["battle", "gym", "elite", "champion"].includes(type)) {
    const battle = $("#event-preset-battle").value;
    const currency = $("#event-preset-currency").value.trim() || "cobbleventure_money";
    const winMoney = Math.max(0, Number($("#event-preset-win-money").value) || 0);
    const lossMoney = Math.max(0, Number($("#event-preset-loss-money").value) || 0);
    const winItem = $("#event-preset-win-item").value.trim();
    const previousMoney = state.trainer.npc?.battle_rewards?.money || {};
    state.trainer.npc.battle_rewards = { money: {
      enabled: winMoney > 0,
      mode: "fixed",
      amount: winMoney,
      held_item_bonus: previousMoney.held_item_bonus ?? true,
      held_item: previousMoney.held_item || "cobblemon:amulet_coin",
      held_item_multiplier: previousMoney.held_item_multiplier || 2,
      conditions: previousMoney.conditions || [],
    } };
    event.commands = [
      ...dialogueCommands("battle_greeting", firstText),
      { type: "choices", options: [
        { id: "battle", text: { ko_kr: "승부한다" }, target: "battle_start" },
        { id: "cancel", text: { ko_kr: "다음에" }, target: "end" },
      ] },
      { type: "label", name: "battle_start" },
      { type: "start_battle", battle, results: { player_win: "battle_win", player_loss: "battle_loss", cancelled: "end" } },
      { type: "label", name: "battle_win" },
    ];
    const clearKey = presetClearKeyValue();
    if (["gym", "elite", "champion"].includes(type) && clearKey) event.commands.push({ type: "mark_clear", key: clearKey });
    if (type === "gym") event.commands.push({ type: "grant_badge", badge: $("#event-preset-badge").value });
    if (winItem) event.commands.push({ type: "give_item", item: winItem, count: Math.max(1, Number($("#event-preset-win-item-count").value) || 1) });
    event.commands.push(
      ...dialogueCommands("battle_win", $("#event-preset-win-text").value),
      { type: "goto", target: "end" },
      { type: "label", name: "battle_loss" },
    );
    if (lossMoney > 0) event.commands.push({ type: "take_money", mode: "fixed", amount: lossMoney, currency_objective: currency });
    event.commands.push(
      ...dialogueCommands("battle_loss", $("#event-preset-loss-text").value),
      { type: "label", name: "end" },
      { type: "end" },
    );
  } else {
    const flag = $("#event-preset-flag").value.trim() || defaultNpcDialogueFlag();
    event.commands = [
      { type: "branch", conditions: [{ type: "flag_equals", key: flag, value: true }], target: "repeat_greeting" },
      ...dialogueCommands("first_greeting", firstText),
    ];
    if (type === "item") event.commands.push({ type: "give_item", item: $("#event-preset-item").value.trim() || "cobblemon:poke_ball", count: Math.max(1, Number($("#event-preset-item-count").value) || 1) });
    event.commands.push(
      { type: "set_flag", key: flag, value: true },
      { type: "goto", target: "end" },
      { type: "label", name: "repeat_greeting" },
      ...dialogueCommands("repeat_greeting", repeatText),
      { type: "label", name: "end" },
      { type: "end" },
    );
  }
  expandedEventCommands.clear();
  renderEventScript();
  syncTrainerJson();
  toast("선택한 대화 프리셋으로 이벤트 명령을 교체했습니다. 저장 전까지 원본 파일은 변경되지 않습니다.");
}

function eventField(label, field, value, options = {}) {
  const type = options.type || "text";
  const attributes = [
    `data-command-field="${escapeHtml(field)}"`,
    options.valueType ? `data-value-type="${escapeHtml(options.valueType)}"` : "",
    options.rerender ? 'data-command-rerender="true"' : "",
    options.min !== undefined ? `min="${escapeHtml(options.min)}"` : "",
    options.step !== undefined ? `step="${escapeHtml(options.step)}"` : "",
    options.placeholder ? `placeholder="${escapeHtml(options.placeholder)}"` : "",
  ].filter(Boolean).join(" ");
  if (options.choices) {
    const choices = options.choices.map(([choiceValue, choiceLabel]) => `<option value="${escapeHtml(choiceValue)}" ${String(value) === String(choiceValue) ? "selected" : ""}>${escapeHtml(choiceLabel)}</option>`).join("");
    return `<label class="${options.wide ? "wide" : ""}"><span>${escapeHtml(label)}</span><select ${attributes}>${choices}</select>${options.help ? `<small>${escapeHtml(options.help)}</small>` : ""}</label>`;
  }
  const control = type === "textarea"
    ? `<textarea ${attributes} rows="2">${escapeHtml(value ?? "")}</textarea>`
    : `<input ${attributes} type="${escapeHtml(type)}" value="${escapeHtml(value ?? "")}">`;
  return `<label class="${options.wide ? "wide" : ""}"><span>${escapeHtml(label)}</span>${control}${options.help ? `<small>${escapeHtml(options.help)}</small>` : ""}</label>`;
}

function eventNestedValue(command, path) {
  return path.split(".").reduce((value, key) => value?.[key], command);
}

function setEventNestedValue(command, path, value) {
  const keys = path.split(".");
  const last = keys.pop();
  const target = keys.reduce((node, key) => (node[key] ||= {}), command);
  target[last] = value;
}

function eventValueType(value) {
  if (typeof value === "boolean") return "boolean";
  if (typeof value === "number") return "number";
  return "string";
}

function renderEventValueEditor(value, field = "value", prefix = "command") {
  const type = eventValueType(value);
  const dataField = prefix === "condition" ? "data-condition-field" : "data-command-field";
  const typeField = prefix === "condition" ? "data-condition-value-type" : "data-command-value-type";
  const valueControl = type === "boolean"
    ? `<select ${dataField}="${field}" data-value-type="boolean"><option value="true" ${value === true ? "selected" : ""}>참</option><option value="false" ${value === false ? "selected" : ""}>거짓</option></select>`
    : `<input ${dataField}="${field}" data-value-type="${type}" type="${type === "number" ? "number" : "text"}" value="${escapeHtml(value ?? "")}">`;
  return `<label><span>값 형식</span><select ${typeField}="${field}"><option value="boolean" ${type === "boolean" ? "selected" : ""}>참/거짓</option><option value="string" ${type === "string" ? "selected" : ""}>문자</option><option value="number" ${type === "number" ? "selected" : ""}>숫자</option></select></label><label><span>비교 값</span>${valueControl}</label>`;
}

function renderConditionEditor(condition, conditionIndex) {
  const type = condition.type || "always";
  let details = "";
  if (type === "flag_equals") {
    details = `<label class="wide"><span>변수 ID</span><input list="declared-variable-ids" data-condition-field="key" value="${escapeHtml(condition.key || "")}" placeholder="cobbleventure:flag/example"><small>게임 데이터에서 선언한 진행 변수를 선택하거나 직접 입력할 수 있습니다.</small></label>${renderEventValueEditor(condition.value ?? true, "value", "condition")}`;
  } else if (type === "has_item") {
    details = `<label class="wide"><span>아이템 ID</span><input list="declared-item-ids" data-condition-field="item" value="${escapeHtml(condition.item || "")}" placeholder="cobblemon:potion"><small>선언한 퀘스트 아이템 또는 실제 게임 아이템 ID를 사용할 수 있습니다.</small></label><label><span>필요 수량</span><input data-condition-field="count" data-value-type="number" type="number" min="1" step="1" value="${escapeHtml(condition.count ?? 1)}"></label>`;
  } else {
    details = '<p class="command-help wide">별도의 검사 없이 이 분기로 이동합니다.</p>';
  }
  return `<div class="event-subrow" data-condition-index="${conditionIndex}"><label><span>조건 종류</span><select data-condition-field="type" data-condition-rerender="true"><option value="flag_equals" ${type === "flag_equals" ? "selected" : ""}>플래그 비교</option><option value="has_item" ${type === "has_item" ? "selected" : ""}>아이템 보유</option><option value="always" ${type === "always" ? "selected" : ""}>항상</option></select></label>${details}<button type="button" class="remove-bag-item" data-condition-remove="${conditionIndex}">조건 삭제</button></div>`;
}

function renderChoiceEditor(option, optionIndex) {
  return `<div class="event-subrow choice-subrow" data-option-index="${optionIndex}"><label><span>선택지 ID</span><input data-option-field="id" value="${escapeHtml(option.id || "")}"></label><label class="wide"><span>화면에 표시할 문구</span><input data-option-field="text.ko_kr" value="${escapeHtml(option.text?.ko_kr || "")}"></label><label><span>이동할 라벨</span><input data-option-field="target" value="${escapeHtml(option.target || "")}"></label><button type="button" class="remove-bag-item" data-option-remove="${optionIndex}">선택지 삭제</button></div>`;
}

function clearKeyEventField(value) {
  return `<label class="wide"><span>클리어 처리 ID</span><div class="hybrid-select-field"><select data-clear-key-select>${clearKeyOptions(value)}</select><input data-command-field="key" value="${escapeHtml(value || "")}" placeholder="cobbleventure:clear/..." hidden disabled></div><small>기존 클리어 키를 선택하거나 ‘직접 입력’을 선택해 새 ID를 입력할 수 있습니다.</small></label>`;
}

function renderEventCommandEditor(command) {
  if (command.type === "branch") {
    const conditions = command.conditions?.length ? command.conditions : [{ type: "always" }];
    return `<div class="event-command-fields"><div class="event-subsection-heading"><strong>조건</strong><button type="button" class="button secondary compact-button" data-condition-add>조건 추가</button></div>${conditions.map(renderConditionEditor).join("")}${eventField("조건이 맞으면 이동할 라벨", "target", command.target || "", { wide: true })}</div>`;
  }
  if (command.type === "label") return `<div class="event-command-fields">${eventField("라벨 이름", "name", command.name || "", { wide: true, help: "분기, 선택지, 배틀 결과가 이동할 위치입니다." })}</div>`;
  if (command.type === "dialogue") return `<div class="event-command-fields">${eventField("대화 ID", "id", command.id || "")}${eventField("화자", "speaker", command.speaker || "npc", { choices: [["npc", "NPC"], ["player", "플레이어"], ["system", "시스템"]] })}${eventField("대화 내용", "text.ko_kr", command.text?.ko_kr || "", { type: "textarea", wide: true, help: "Enter로 줄을 나눈 뒤 입력창을 벗어나면 줄마다 별도의 대화 명령으로 분리됩니다." })}</div>`;
  if (command.type === "choices") {
    return `<div class="event-command-fields"><div class="event-subsection-heading"><strong>선택지</strong><button type="button" class="button secondary compact-button" data-option-add>선택지 추가</button></div>${(command.options || []).map(renderChoiceEditor).join("")}</div>`;
  }
  if (command.type === "goto") return `<div class="event-command-fields">${eventField("이동할 라벨", "target", command.target || "", { wide: true })}</div>`;
  if (command.type === "start_battle") {
    const battles = [...state.battles.map((battle) => [battle.id, battle.name?.ko_kr || battle.id])];
    if (command.battle && !battles.some(([id]) => id === command.battle)) battles.unshift([command.battle, command.battle]);
    return `<div class="event-command-fields">${eventField("배틀 프리셋", "battle", command.battle || "", { choices: battles, wide: true })}${eventField("플레이어 승리 시 라벨", "results.player_win", command.results?.player_win || "")}${eventField("플레이어 패배 시 라벨", "results.player_loss", command.results?.player_loss || "")}${eventField("취소 시 라벨", "results.cancelled", command.results?.cancelled || "", { help: "취소를 사용하지 않으면 비워둘 수 있습니다." })}</div>`;
  }
  if (command.type === "set_flag") return `<div class="event-command-fields">${eventField("플래그 ID", "key", command.key || "", { wide: true })}${renderEventValueEditor(command.value ?? true)}</div>`;
  if (command.type === "mark_clear") return `<div class="event-command-fields">${clearKeyEventField(command.key || "")}</div>`;
  if (["give_money", "take_money"].includes(command.type)) {
    const mode = command.mode || "fixed";
    const action = command.type === "take_money" ? "차감" : "지급";
    return `<div class="event-command-fields">${eventField(`${action} 방식`, "mode", mode, { choices: [["fixed", "고정 금액"], ["level_cap_multiplier", "현재 레벨캡 × 배율"]], rerender: true })}${mode === "fixed" ? eventField(`${action} 금액`, "amount", command.amount ?? 0, { type: "number", valueType: "number", min: 0, step: 1 }) : eventField("레벨캡 배율", "multiplier", command.multiplier ?? 1, { type: "number", valueType: "number", min: .01, step: .01 })}${eventField("화폐 점수판", "currency_objective", command.currency_objective || "cobbleventure_money")}${mode === "level_cap_multiplier" ? eventField("레벨캡 점수판", "level_cap_objective", command.level_cap_objective || "cobbleventure_level_cap") : ""}</div>`;
  }
  if (command.type === "give_item") return `<div class="event-command-fields">${eventField("아이템 ID", "item", command.item || "", { wide: true })}${eventField("수량", "count", command.count ?? 1, { type: "number", valueType: "number", min: 1, step: 1 })}</div>`;
  if (command.type === "grant_loot") return `<div class="event-command-fields">${eventField("루트 테이블 ID", "loot_table", command.loot_table || "", { wide: true, help: "확률과 아이템 구성은 별도의 루트 테이블에서 관리합니다." })}</div>`;
  if (command.type === "grant_badge") return `<div class="event-command-fields">${eventField("기록할 배지", "badge", command.badge || "", { choices: (state.badgeCatalog.badges || []).map((badge) => [badge.id, `${badge.generation}세대 ${badge.order}번째 · ${badge.display_name?.ko_kr || badge.id}`]), wide: true, help: "아이템을 지급하지 않고 트레이너 카드 진행도에 기록합니다." })}</div>`;
  if (command.type === "grant_field_move") return `<div class="event-command-fields">${eventField("획득할 비전머신", "move", command.move || "surf", { choices: fieldMoveChoices, wide: true, help: "대화 중인 플레이어의 비전머신 플래그를 영구 해금합니다." })}</div>`;
  if (command.type === "teleport_to_gate") {
    const gates = (state.worldLayout?.objects || []).filter((object) => object.type === "gate").map((gate) => [gate.id, gate.id]);
    if (command.gate && !gates.some(([id]) => id === command.gate)) gates.unshift([command.gate, `${command.gate} · 현재 월드에 없음`]);
    return `<div class="event-command-fields">${eventField("대상 관문", "gate", command.gate || "", { choices: gates.length ? gates : [["gate_01", "등록된 관문 없음"]], wide: true, help: "관문의 위치·방향은 월드맵 오브젝트에서 관리합니다." })}${eventField("이동 대상", "subject", command.subject || "player", { choices: [["player", "대화 중인 플레이어"], ["npc", "이 NPC"]] })}${eventField("도착 위치", "side", command.side || "front", { choices: [["front", "관문 정면"], ["back", "관문 뒤편"], ["center", "관문 중앙"]] })}</div>`;
  }
  if (command.type === "end") return '<div class="event-command-fields"><p class="command-help wide">여기에서 이벤트 실행을 종료합니다. 추가 설정은 필요하지 않습니다.</p></div>';
  return '<div class="event-command-fields"><p class="command-help wide">지원하지 않는 명령입니다.</p></div>';
}

function renderEventScript() {
  const event = selectedNpcEvent();
  const list = $("#event-command-list");
  if (!event) {
    list.innerHTML = '<div class="issues empty">이벤트 스크립트가 없습니다.</div>';
    return;
  }
  if (!$("#event-command-type").querySelector('option[value="teleport_to_gate"]')) {
    $("#event-command-type").insertAdjacentHTML("beforeend", '<option value="teleport_to_gate">관문으로 이동</option>');
  }
  if (list.dataset.npcId !== state.trainer.id) {
    expandedEventCommands.clear();
    list.dataset.npcId = state.trainer.id;
  }
  const trigger = event.trigger || { type: "interact", range: 4 };
  $("#event-trigger-type").value = trigger.type;
  $("#event-trigger-range").value = trigger.range ?? 4;
  $("#event-warning-offset").value = trigger.warning_offset ?? 2;
  $("#event-range-label").textContent = trigger.type === "proximity" ? "자동 발동 거리" : "대화 가능 거리";
  $("#event-warning-offset-field").hidden = trigger.type !== "proximity";
  ["#event-trigger-type", "#event-trigger-range", "#event-warning-offset", "#event-command-type", "#add-event-command", "#event-preset-type", "#event-preset-first-text", "#event-preset-repeat-text", "#event-preset-item", "#event-preset-item-count", "#event-preset-flag", "#event-preset-battle", "#event-preset-win-money", "#event-preset-loss-money", "#event-preset-currency", "#event-preset-badge", "#event-preset-win-item", "#event-preset-win-item-count", "#event-preset-win-text", "#event-preset-loss-text", "#event-preset-clear-key", "#apply-event-preset"].forEach((selector) => { $(selector).disabled = false; });
  $$('[data-item-picker]').forEach((button) => { button.disabled = false; });
  const presetFlag = $("#event-preset-flag");
  if (presetFlag.dataset.npcId !== state.trainer.id) {
    presetFlag.dataset.npcId = state.trainer.id;
    presetFlag.value = defaultNpcDialogueFlag();
  }
  renderEventPresetFields();
  list.innerHTML = event.commands.map((command, index) => {
    const expanded = expandedEventCommands.has(index);
    const editor = renderEventCommandEditor(command).replace('class="event-command-fields"', `class="event-command-fields"${expanded ? "" : " hidden"}`);
    return `<article class="event-command-row" data-event-command="${index}">
      <span class="command-index">${String(index + 1).padStart(2, "0")}</span>
      <strong class="command-type">${escapeHtml(eventCommandLabels[command.type] || command.type)}</strong>
      <span class="command-summary">${escapeHtml(eventCommandSummary(command))}</span>
      ${editor}
      <div class="team-heading-actions"><button type="button" class="button secondary" data-command-up="${index}" ${index === 0 ? "disabled" : ""}>↑</button><button type="button" class="button secondary" data-command-down="${index}" ${index === event.commands.length - 1 ? "disabled" : ""}>↓</button></div>
      <button type="button" class="button secondary command-toggle" data-command-toggle="${index}">${expanded ? "접기" : "편집"}</button>
      <button type="button" class="remove-bag-item" data-command-remove="${index}">삭제</button>
    </article>`;
  }).join("");
}

function defaultEventCommand(type) {
  const defaults = {
    branch: { type, conditions: [{ type: "flag_equals", key: "cobbleventure:flag/example", value: true }], target: "target_label" },
    label: { type, name: "new_label" },
    dialogue: { type, id: "dialogue", speaker: "npc", text: { ko_kr: "대사를 입력하세요." } },
    choices: { type, options: [{ id: "continue", text: { ko_kr: "계속" }, target: "next" }] },
    goto: { type, target: "target_label" },
    start_battle: { type, battle: state.battles[0]?.id || "cobbleventure:battle/example", results: { player_win: "win", player_loss: "loss" } },
    set_flag: { type, key: "cobbleventure:flag/example", value: true },
    mark_clear: { type, key: defaultProgressionClearKey("battle") },
    give_money: { type, mode: "fixed", amount: 500, currency_objective: "cobbleventure_money" },
    take_money: { type, mode: "fixed", amount: 500, currency_objective: "cobbleventure_money" },
    give_item: { type, item: "cobblemon:poke_ball", count: 1 },
    grant_loot: { type, loot_table: "cobbleventure:rewards/example" },
    grant_badge: { type, badge: state.badgeCatalog.badges?.[0]?.id || "cobbleventure:badge/kanto/boulder" },
    grant_field_move: { type, move: "surf" },
    teleport_to_gate: { type, gate: (state.worldLayout?.objects || []).find((object) => object.type === "gate")?.id || "gate_01", subject: "player", side: "front" },
    end: { type },
  };
  return defaults[type];
}

function addEventCommand() {
  const event = selectedNpcEvent(); if (!event) return;
  event.commands.push(defaultEventCommand($("#event-command-type").value));
  expandedEventCommands.clear();
  expandedEventCommands.add(event.commands.length - 1);
  renderEventScript(); syncTrainerJson();
}

function updateEventTrigger() {
  const event = selectedNpcEvent(); if (!event) return;
  const type = $("#event-trigger-type").value;
  event.trigger = { type, range: Math.max(.5, Number($("#event-trigger-range").value) || 4) };
  if (type === "proximity") {
    event.trigger.warning_offset = Math.max(0, Number($("#event-warning-offset").value) || 0);
    event.trigger.indicator = "trainer_nearby";
  }
  renderEventScript(); syncTrainerJson();
}

function handleEventCommandInput(event) {
  const script = selectedNpcEvent();
  const row = event.target.closest("[data-event-command]");
  if (!row || !script) return;
  const command = script.commands[Number(row.dataset.eventCommand)];
  const parseValue = (element) => element.dataset.valueType === "number" ? Number(element.value) : element.dataset.valueType === "boolean" ? element.value === "true" : element.value;
  if (event.target.dataset.clearKeySelect !== undefined) {
    const customInput = event.target.parentElement.querySelector('[data-command-field="key"]');
    const isCustom = event.target.value === "__custom__";
    customInput.hidden = !isCustom;
    customInput.disabled = !isCustom;
    if (isCustom) requestAnimationFrame(() => customInput.focus());
    else {
      command.key = event.target.value;
      const summary = row.querySelector(".command-summary");
      if (summary) summary.textContent = eventCommandSummary(command);
      syncTrainerJson();
    }
    return;
  }
  if (event.target.dataset.commandValueType) {
    const field = event.target.dataset.commandValueType;
    setEventNestedValue(command, field, event.target.value === "boolean" ? true : event.target.value === "number" ? 0 : "");
    renderEventScript(); syncTrainerJson(); return;
  }
  if (event.target.dataset.conditionValueType) {
    const condition = command.conditions[Number(event.target.closest("[data-condition-index]").dataset.conditionIndex)];
    condition[event.target.dataset.conditionValueType] = event.target.value === "boolean" ? true : event.target.value === "number" ? 0 : "";
    renderEventScript(); syncTrainerJson(); return;
  }
  if (event.target.dataset.commandField) setEventNestedValue(command, event.target.dataset.commandField, parseValue(event.target));
  else if (event.target.dataset.conditionField) {
    const condition = command.conditions[Number(event.target.closest("[data-condition-index]").dataset.conditionIndex)];
    condition[event.target.dataset.conditionField] = parseValue(event.target);
    if (event.target.dataset.conditionRerender) {
      Object.keys(condition).forEach((key) => { if (key !== "type") delete condition[key]; });
      if (event.target.value === "flag_equals") Object.assign(condition, { type: "flag_equals", key: "cobbleventure:flag/example", value: true });
      else if (event.target.value === "has_item") Object.assign(condition, { type: "has_item", item: "cobblemon:potion", count: 1 });
    }
  } else if (event.target.dataset.optionField) {
    const option = command.options[Number(event.target.closest("[data-option-index]").dataset.optionIndex)];
    setEventNestedValue(option, event.target.dataset.optionField, parseValue(event.target));
  } else return;
  if (event.target.dataset.commandRerender && event.target.dataset.commandField === "mode") {
    if (event.target.value === "fixed") { delete command.multiplier; delete command.level_cap_objective; command.amount ??= 0; }
    else { delete command.amount; command.multiplier ??= 1; command.level_cap_objective ??= "cobbleventure_level_cap"; }
  }
  const summary = row.querySelector(".command-summary");
  if (summary) summary.textContent = eventCommandSummary(command);
  if (event.target.dataset.commandRerender || event.target.dataset.conditionRerender) renderEventScript();
  syncTrainerJson();
}

function splitDialogueCommandLines(event) {
  const textarea = event.target.closest('[data-command-field="text.ko_kr"]');
  const script = selectedNpcEvent();
  const row = textarea?.closest("[data-event-command]");
  if (!textarea || !script || !row) return;
  const lines = dialogueLines(textarea.value);
  if (lines.length < 2) return;
  const index = Number(row.dataset.eventCommand);
  const command = script.commands[index];
  if (command?.type !== "dialogue") return;
  const idPrefix = String(command.id || "dialogue").replace(/_\d+$/, "");
  const commands = lines.map((line, lineIndex) => ({
    ...command,
    id: `${idPrefix}_${lineIndex + 1}`,
    text: { ...(command.text || {}), ko_kr: line },
  }));
  script.commands.splice(index, 1, ...commands);
  expandedEventCommands.clear();
  renderEventScript();
  syncTrainerJson();
  toast(`${lines.length}줄의 대사를 ${lines.length}개의 대화 명령으로 나눴습니다.`);
}

function handleEventCommandClick(event) {
  const script = selectedNpcEvent(); if (!script) return;
  const remove = event.target.closest("[data-command-remove]");
  const up = event.target.closest("[data-command-up]");
  const down = event.target.closest("[data-command-down]");
  const addCondition = event.target.closest("[data-condition-add]");
  const removeCondition = event.target.closest("[data-condition-remove]");
  const addOption = event.target.closest("[data-option-add]");
  const removeOption = event.target.closest("[data-option-remove]");
  const toggle = event.target.closest("[data-command-toggle]");
  const row = event.target.closest("[data-event-command]");
  if (toggle) {
    const index = Number(toggle.dataset.commandToggle);
    if (expandedEventCommands.has(index)) expandedEventCommands.delete(index); else expandedEventCommands.add(index);
    renderEventScript();
    return;
  }
  if (remove) { script.commands.splice(Number(remove.dataset.commandRemove), 1); expandedEventCommands.clear(); }
  else if (addCondition && row) script.commands[Number(row.dataset.eventCommand)].conditions.push({ type: "flag_equals", key: "cobbleventure:flag/example", value: true });
  else if (removeCondition && row) {
    const conditions = script.commands[Number(row.dataset.eventCommand)].conditions;
    conditions.splice(Number(removeCondition.dataset.conditionRemove), 1);
    if (!conditions.length) conditions.push({ type: "always" });
  } else if (addOption && row) script.commands[Number(row.dataset.eventCommand)].options.push({ id: "option", text: { ko_kr: "새 선택지" }, target: "next" });
  else if (removeOption && row) script.commands[Number(row.dataset.eventCommand)].options.splice(Number(removeOption.dataset.optionRemove), 1);
  else if (up) {
    const index = Number(up.dataset.commandUp); [script.commands[index - 1], script.commands[index]] = [script.commands[index], script.commands[index - 1]];
    expandedEventCommands.clear();
  } else if (down) {
    const index = Number(down.dataset.commandDown); [script.commands[index + 1], script.commands[index]] = [script.commands[index], script.commands[index + 1]];
    expandedEventCommands.clear();
  } else return;
  renderEventScript(); syncTrainerJson();
}

function bagItemCatalogEntry(itemId) {
  return (state.editorCatalog?.bagItems || []).find((entry) => entry.id === itemId) || null;
}

function renderEntryRoutes() {
  const list = $("#entry-route-list");
  const interaction = state.trainer?.interaction;
  if (!interaction) {
    list.innerHTML = '<div class="issues empty">구형 번들은 전체 JSON의 dialogue_routes에서 시작 조건을 관리합니다.</div>';
    $("#add-entry-route").disabled = true;
    return;
  }
  const dialogueNodes = interaction.nodes.filter((node) => node.type === "dialogue");
  const options = dialogueNodes.map((node) => `<option value="${escapeHtml(node.id)}">${escapeHtml(node.text?.ko_kr || node.id)}</option>`).join("");
  list.innerHTML = interaction.entry_routes.map((route, index) => {
    const condition = route.conditions?.[0] || { type: "always" };
    const isFallback = !route.conditions?.length;
    const detailFields = condition.type === "flag_equals"
      ? `<label><span>플래그 ID</span><input data-route-field="key" value="${escapeHtml(condition.key || "")}"></label><label><span>값</span><select data-route-field="value"><option value="true">true</option><option value="false">false</option></select></label>`
      : condition.type === "has_item"
        ? `<label><span>아이템 ID</span><input data-route-field="item" value="${escapeHtml(condition.item || "")}"></label><label><span>수량</span><input type="number" min="1" data-route-field="count" value="${Number(condition.count || 1)}"></label>`
        : '<span class="route-fallback-note">위 조건이 모두 실패하면 이 대화로 시작합니다.</span>';
    return `<article class="bag-item-row entry-route-row" data-entry-route="${index}">
      <span class="bag-item-index">${String(index + 1).padStart(2, "0")}</span>
      <label><span>시작 조건</span><select data-route-field="type"><option value="flag_equals">플래그 비교</option><option value="has_item">아이템 보유</option><option value="always">기본 경로</option></select></label>
      ${detailFields}
      <label><span>시작 대화</span><select data-route-field="entry">${options}</select></label>
      <button type="button" class="remove-bag-item" data-remove-entry-route="${index}" ${isFallback ? "disabled" : ""}>삭제</button>
    </article>`;
  }).join("");
  $$("[data-entry-route]").forEach((row) => {
    const route = interaction.entry_routes[Number(row.dataset.entryRoute)];
    const condition = route.conditions?.[0] || { type: "always" };
    row.querySelector('[data-route-field="type"]').value = condition.type;
    row.querySelector('[data-route-field="entry"]').value = route.entry;
    if (condition.type === "flag_equals") row.querySelector('[data-route-field="value"]').value = String(condition.value ?? true);
  });
}

function updateEntryRoute(event) {
  const row = event.target.closest("[data-entry-route]");
  if (!row || !state.trainer?.interaction) return;
  const index = Number(row.dataset.entryRoute);
  const route = state.trainer.interaction.entry_routes[index];
  const field = event.target.dataset.routeField;
  if (field === "entry") route.entry = event.target.value;
  else if (field === "type") {
    const type = event.target.value;
    if (type === "always") {
      route.conditions = [];
      state.trainer.interaction.entry_routes.splice(index, 1);
      state.trainer.interaction.entry_routes.push(route);
    } else if (type === "flag_equals") route.conditions = [{ type, key: "cobbleventure:flag/example", value: true }];
    else route.conditions = [{ type, item: "cobblemon:potion", count: 1 }];
    renderEntryRoutes();
  } else {
    const condition = route.conditions[0];
    if (field === "count") condition.count = Math.max(1, Number.parseInt(event.target.value, 10) || 1);
    else if (field === "value") condition.value = event.target.value === "true";
    else condition[field] = event.target.value.trim();
  }
  syncTrainerJson();
}

function addEntryRoute() {
  const routes = state.trainer?.interaction?.entry_routes;
  if (!routes) return;
  const fallbackIndex = routes.findIndex((route) => !route.conditions?.length);
  const fallback = routes[fallbackIndex];
  routes.splice(fallbackIndex < 0 ? routes.length : fallbackIndex, 0, {
    conditions: [{ type: "flag_equals", key: "cobbleventure:flag/example", value: true }],
    entry: fallback?.entry || state.trainer.interaction.nodes.find((node) => node.type === "dialogue")?.id
  });
  renderEntryRoutes();
  syncTrainerJson();
}

function renderBag() {
  const list = $("#bag-list");
  if (!trainerBattle()) {
    list.innerHTML = '<div class="issues empty">연결된 배틀 프리셋이 없습니다.</div>';
    return;
  }
  trainerBattle().bag ||= [];
  const bag = trainerBattle().bag;
  if (!bag.length) {
    list.innerHTML = '<div class="issues empty">등록된 전투 아이템이 없습니다.</div>';
    return;
  }
  list.innerHTML = bag.map((entry, index) => {
    const catalogEntry = bagItemCatalogEntry(entry.item);
    const label = catalogEntry?.name || entry.item;
    const category = catalogEntry?.description || "사용할 아이템";
    return `<article class="bag-item-row">
      <span class="bag-item-index">${String(index + 1).padStart(2, "0")}</span>
      <div class="bag-item-description"><strong>${escapeHtml(label)}</strong><small>${escapeHtml(category)} · ${escapeHtml(entry.item)}</small></div>
      <button type="button" class="button secondary" data-select-bag-item="${index}">아이템 선택</button>
      <label><span>수량</span><input type="number" min="1" step="1" value="${Math.max(1, Number(entry.quantity) || 1)}" data-bag-quantity="${index}"></label>
      <button type="button" class="remove-bag-item" data-remove-bag-item="${index}">삭제</button>
    </article>`;
  }).join("");
  $$('[data-select-bag-item]').forEach((button) => button.addEventListener("click", () => openChoiceDialog("bag_item", null, Number(button.dataset.selectBagItem))));
  $$('[data-bag-quantity]').forEach((input) => input.addEventListener("input", () => {
    const entry = trainerBattle().bag[Number(input.dataset.bagQuantity)];
    if (!entry) return;
    entry.quantity = Math.max(1, Number.parseInt(input.value, 10) || 1);
    syncTrainerJson();
  }));
  $$('[data-remove-bag-item]').forEach((button) => button.addEventListener("click", () => {
    trainerBattle().bag.splice(Number(button.dataset.removeBagItem), 1);
    renderBag();
    syncTrainerJson();
  }));
}

function addBagItem() {
  if (!trainerBattle()) return;
  trainerBattle().bag ||= [];
  const fallback = state.editorCatalog?.bagItems?.find((entry) => entry.shortId === "potion")
    || state.editorCatalog?.bagItems?.[0];
  trainerBattle().bag.push({ item: fallback?.id || "cobblemon:potion", quantity: 1 });
  renderBag();
  syncTrainerJson();
  openChoiceDialog("bag_item", null, trainerBattle().bag.length - 1);
}

function applyTrainerClass() {
  const form = $("#trainer-form");
  const trainerClass = state.trainerClasses.find((entry) => entry.id === form.elements.trainerClass.value);
  if (trainerClass?.default_appearance) {
    const { source, type, resource, portrait } = trainerClass.default_appearance;
    form.elements.appearanceSource.value = source;
    form.elements.appearanceResource.value = resource;
    state.trainer.npc.appearance = {
      ...state.trainer.npc.appearance,
      source, type, resource,
      ...(portrait ? { portrait } : {})
    };
  }
  form.elements.rosterCharacter.innerHTML = rosterCharacterOptions(trainerClass?.id);
  form.elements.rosterCharacter.value = "";
  delete state.trainer.npc.character;
  updateTrainerFromForm();
}

function trainerClassAppearanceForSource(trainerClass, source) {
  return [trainerClass?.default_appearance, ...(trainerClass?.appearance_options || [])]
    .find((appearance) => appearance?.source === source);
}

function applyAppearanceSource() {
  const form = $("#trainer-form");
  const trainerClass = state.trainerClasses.find((entry) => entry.id === form.elements.trainerClass.value);
  const appearance = trainerClassAppearanceForSource(trainerClass, form.elements.appearanceSource.value);
  if (appearance) {
    const { source, type, resource, portrait } = appearance;
    form.elements.appearanceResource.value = resource;
    state.trainer.npc.appearance = {
      ...state.trainer.npc.appearance,
      source, type, resource,
      ...(portrait ? { portrait } : {})
    };
  }
  updateTrainerFromForm();
}

function rosterCharacters() {
  const organizations = (state.trainerRoster.organizations || []).flatMap((organization) => [
    ...(organization.grunt_variants || []), ...(organization.named_characters || [])
  ]);
  return [...organizations, ...(state.trainerRoster.league_characters || [])];
}

function currentRosterReferenceEntryId() {
  const characterId = state.trainer?.npc?.character;
  return (state.trainerRoster.battle_reference_defaults || [])
    .find((entry) => entry.character === characterId)?.entry || "";
}

const rosterRolesByClass = {
  gym_leader: ["gym_leader"], elite_four: ["elite_four"], champion: ["champion"],
  villain_grunt: ["grunt"], villain_admin: ["admin", "named_agent"], villain_boss: ["boss"]
};

function effectiveCharacterAppearance(character) {
  return character?.appearance || {};
}

function rosterCharactersForClass(classId) {
  const slug = trainerClassSlug(classId);
  if (slug === "custom") return rosterCharacters();
  const roles = rosterRolesByClass[slug];
  return roles ? rosterCharacters().filter((character) => roles.includes(character.role)) : [];
}

function characterVisualMatchStatus(character) {
  const appearance = effectiveCharacterAppearance(character);
  if (appearance.visual_match_status) return appearance.visual_match_status;
  if (appearance.implementation_status === "placeholder") return "unverified";
  if (appearance.source?.startsWith("rct_") && character?.role !== "grunt") return "generic";
  return "matched";
}

function characterAppearanceLabel(character) {
  const status = characterVisualMatchStatus(character);
  if (status === "matched") return "전용 스킨";
  if (status === "generic") {
    return effectiveCharacterAppearance(character).source === "custom" ? "1차 스킨" : "RCT 후보만 있음";
  }
  return character?.appearance?.asset_status === "definition_only" ? "정의만 확인" : "전용 스킨 준비 중";
}

function rosterCharacterOptions(classId) {
  const allowedIds = new Set(rosterCharactersForClass(classId).map((character) => character.id));
  const organizationGroups = (state.trainerRoster.organizations || []).map((organization) => {
    const characters = [...(organization.grunt_variants || []), ...(organization.named_characters || [])]
      .filter((character) => allowedIds.has(character.id));
    return characters.length ? `<optgroup label="${escapeHtml(organization.display_name?.ko_kr || organization.id)}">${characters.map((character) =>
      `<option value="${escapeHtml(character.id)}">${escapeHtml(character.display_name?.ko_kr || character.id)} · ${characterAppearanceLabel(character)}</option>`
    ).join("")}</optgroup>` : "";
  }).join("");
  const regionNames = { kanto: "관동 리그", johto: "성도 리그", hoenn: "호연 리그", sinnoh: "신오 리그" };
  const leagueGroups = Object.entries(regionNames).map(([region, label]) => {
    const characters = (state.trainerRoster.league_characters || [])
      .filter((entry) => entry.region === region && allowedIds.has(entry.id));
    return characters.length ? `<optgroup label="${label}">${characters.map((character) =>
      `<option value="${escapeHtml(character.id)}">${escapeHtml(character.display_name?.ko_kr || character.id)} · ${characterAppearanceLabel(character)}</option>`
    ).join("")}</optgroup>` : "";
  }).join("");
  return '<option value="">직접 설정</option>' + organizationGroups + leagueGroups;
}

function applyRosterCharacter() {
  const form = $("#trainer-form");
  const character = rosterCharacters().find((entry) => entry.id === form.elements.rosterCharacter.value);
  if (!character) {
    delete state.trainer.npc.character;
    updateTrainerFromForm();
    return;
  }
  const classByRole = {
    grunt: "villain_grunt", admin: "villain_admin", named_agent: "villain_admin", boss: "villain_boss",
    gym_leader: "gym_leader", elite_four: "elite_four", champion: "champion"
  };
  form.elements.trainerClass.value = `cobbleventure:trainer_class/${classByRole[character.role] || "custom"}`;
  form.elements.nameKo.value = character.display_name?.ko_kr || character.id;
  form.elements.nameEn.value = character.display_name?.en_us || "";
  const appearance = effectiveCharacterAppearance(character);
  form.elements.appearanceSource.value = appearance.source;
  form.elements.appearanceResource.value = appearance.resource;
  form.elements.rosterCharacter.innerHTML = rosterCharacterOptions(form.elements.trainerClass.value);
  form.elements.rosterCharacter.value = character.id;
  state.trainer.npc.character = character.id;
  updateTrainerFromForm();
}

function rctSkinUrl(resource) {
  if (!resource?.startsWith("rctmod:trainers/")) return "";
  return `/api/trainer-skin?resource=${encodeURIComponent(resource)}`;
}

const trainerReferenceSprites = {
  youngster: ["youngster-gen4", "4세대"], lass: ["lass-gen4", "4세대"], bug_catcher: ["bugcatcher-gen4dp", "4세대 DP"],
  school_kid: ["schoolkid-gen4", "4세대"], preschooler: ["preschooler-gen6", "6세대"], twins: ["twins-gen4", "4세대"],
  camper: ["camper-gen6", "6세대"], picnicker: ["picnicker-gen6", "6세대"], hiker: ["hiker-gen4", "4세대"],
  fisherman: ["fisherman-gen4", "4세대"], sailor: ["sailor-gen6", "6세대"], swimmer_male: ["swimmer-gen4", "4세대"],
  swimmer_female: ["swimmerf-gen4", "4세대"], bird_keeper: ["birdkeeper-gen4dp", "4세대 DP"],
  pokemon_ranger_male: ["pokemonranger-gen4", "4세대"], pokemon_ranger_female: ["pokemonrangerf-gen4", "4세대"],
  backpacker: ["backpacker-gen6", "6세대"], skier: ["skierf-gen4dp", "4세대 DP"], boarder: ["boarder-gen2", "2세대"],
  black_belt: ["blackbelt-gen4", "4세대"], battle_girl: ["battlegirl-gen4", "4세대"], psychic: ["psychic-gen4", "4세대"],
  dragon_tamer: ["dragontamer-gen3", "3세대"], hex_maniac: ["hexmaniac-gen6", "6세대"], aroma_lady: ["aromalady", "본가 계열"],
  bug_maniac: ["bugmaniac-gen3", "3세대"], kindler: ["kindler-gen3", "3세대"], tamer: ["tamer-gen3", "3세대"],
  scientist: ["scientist-gen4", "4세대"], super_nerd: ["supernerd-gen3", "3세대"], pokemon_breeder: ["pokemonbreeder-gen4", "4세대"],
  pokefan: ["pokefan-gen4", "4세대"], poke_maniac: ["pokemaniac-gen6", "6세대"], collector: ["collector-gen3", "3세대"],
  police_officer: ["policeman-gen4", "4세대"], worker: ["worker-gen4", "4세대"], office_worker: ["officeworker-gen9", "9세대"],
  cook: ["cook-gen7", "7세대"], waiter: ["waiter-gen4", "4세대"], waitress: ["waitress-gen9", "9세대"],
  musician: ["musician-gen8", "8세대"], guitarist: ["guitarist-gen4", "4세대"], artist: ["artist-gen4", "4세대"],
  biker: ["biker-gen4", "4세대"], beauty: ["beauty-gen4dp", "4세대 DP"], gentleman: ["gentleman-gen4", "4세대"],
  rich_boy: ["richboy-gen4", "4세대"], lady: ["lady-gen4", "4세대"], madame: ["madame-gen6", "6세대"],
  maid: ["maid-gen4", "4세대"], old_couple_male: ["oldcouple-gen3", "3세대"], old_couple_female: ["oldcouple-gen3", "3세대"],
  young_couple_male: ["youngcouple-gen4dp", "4세대 DP"], young_couple_female: ["youngcouple-gen4dp", "4세대 DP"],
  ace_trainer_male: ["acetrainer-gen4", "4세대"], ace_trainer_female: ["acetrainerf-gen4", "4세대"],
  ace_trainer_gen6_male: ["acetrainer-gen6", "6세대"], ace_trainer_gen6_female: ["acetrainerf-gen6", "6세대"],
  veteran_male: ["veteran-gen4", "4세대"], veteran_female: ["veteranf-gen6", "6세대"],
  interviewers_male: ["interviewers-gen3", "3세대"], interviewers_female: ["interviewers-gen3", "3세대"],
  expert: ["expert-gen3", "3세대"], gym_leader: ["brock-gen3", "3세대 대표"],
  elite_four: ["bruno-gen3", "3세대 대표"], champion: ["blue-gen3champion", "3세대 대표"], rival: ["red-gen3", "3세대 대표"],
  villain_grunt: ["teamrocketgruntm-gen3", "3세대 대표"]
};

const trainerCharacterReferenceSprites = {
  brock: "brock-gen3", misty: "misty-gen3", lt_surge: "ltsurge-gen3", erika: "erika-gen3",
  koga: "koga-gen3", sabrina: "sabrina-gen3", blaine: "blaine-gen3", giovanni_gym: "giovanni-gen3",
  lorelei: "lorelei-gen3", bruno_kanto: "bruno-gen3", agatha: "agatha-gen3", lance_kanto: "lance-gen3",
  blue_champion: "blue-gen3champion", falkner: "falkner-gen2", bugsy: "bugsy-gen2", whitney: "whitney-gen2",
  morty: "morty-gen2", chuck: "chuck-gen2", jasmine: "jasmine-gen2", pryce: "pryce-gen2", clair: "clair-gen2",
  will: "will-gen2", koga_johto: "koga-gen2", bruno_johto: "bruno-gen2", karen: "karen-gen2",
  lance_champion: "lance-gen2", roxanne: "roxanne-gen3", brawly: "brawly-gen3", wattson: "wattson-gen3",
  flannery: "flannery-gen3", norman: "norman-gen3", winona: "winona-gen3", tate: "tateandliza-gen3",
  liza: "tateandliza-gen3", wallace: "wallace-gen3", sidney: "sidney-gen3", phoebe: "phoebe-gen3",
  glacia: "glacia-gen3", drake: "drake-gen3", steven: "steven-gen3", roark: "roark", gardenia: "gardenia",
  maylene: "maylene", crasher_wake: "crasherwake", fantina: "fantina", byron: "byron", candice: "candice",
  volkner: "volkner", aaron: "aaron", bertha: "bertha", flint: "flint", lucian: "lucian", cynthia: "cynthia-gen4",
  giovanni: "giovanni-gen3", archer: "archer", ariana: "ariana", proton: "proton", petrel: "petrel",
  archie: "archie-gen3", maxie: "maxie-gen3", cyrus: "cyrus", mars: "mars", jupiter: "jupiter",
  saturn: "saturn", charon: "charon",
  ghetsis: "ghetsis", n: "n", colress: "colress", lysandre: "lysandre", guzma: "guzma",
  plumeria: "plumeria", lusamine: "lusamine", piers: "piers", marnie: "marnie", rose: "rose",
  oleana: "oleana", penny: "penny"
};

function trainerClassSlug(classId) {
  return String(classId || "").split("/").pop();
}

function trainerReferenceUrl(sprite) {
  if (sprite?.startsWith("local:")) {
    return `/api/trainer-skin?resource=${encodeURIComponent(`trainer-reference:${sprite.slice("local:".length)}`)}`;
  }
  return `/api/trainer-reference?sprite=${encodeURIComponent(sprite)}`;
}

function trainerReferenceHtml(trainerClass, rosterCharacter = null) {
  const [classSprite, classGeneration] = trainerReferenceSprites[trainerClassSlug(trainerClass?.id)] || [];
  const characterSlug = String(rosterCharacter?.id || "").split("/").pop();
  const appearance = effectiveCharacterAppearance(rosterCharacter);
  const mappedCharacterSprite = trainerCharacterReferenceSprites[characterSlug];
  const characterSprites = appearance.source === "custom"
    ? [`local:${characterSlug}`, mappedCharacterSprite]
    : [mappedCharacterSprite];
  const candidates = [...new Set([...characterSprites, classSprite].filter(Boolean))];
  const sprite = candidates.shift();
  const generation = rosterCharacter ? `${rosterCharacter.generation || "?"}세대 인물` : classGeneration;
  if (!sprite) return `<div class="trainer-reference-empty"><b>?</b><span>참조 이미지 준비 중</span></div>`;
  return `<img class="trainer-reference-image" src="${trainerReferenceUrl(sprite)}" data-fallback-sprites="${escapeHtml(JSON.stringify(candidates))}" alt="${escapeHtml(rosterCharacter?.display_name?.ko_kr || trainerClass?.display_name?.ko_kr || "트레이너")} 본가 전투 스프라이트"><div class="trainer-reference-empty" hidden><b>?</b><span>참조 이미지 준비 중</span></div><small>${generation} 전투 스프라이트</small>`;
}

function trainerSkinUrl(appearance) {
  if (appearance.source?.startsWith("rct_")) return rctSkinUrl(appearance.resource);
  if (appearance.type === "skin" && appearance.resource) {
    return `/api/trainer-skin?resource=${encodeURIComponent(appearance.resource)}`;
  }
  return "";
}

const minecraftParts = [
  { name: "head", x: 20, y: 0, w: 40, h: 40, d: 40, uv: { top: [8,0], bottom: [16,0], right: [0,8], front: [8,8], left: [16,8], back: [24,8] }, overlayUv: { top: [40,0], bottom: [48,0], right: [32,8], front: [40,8], left: [48,8], back: [56,8] } },
  { name: "body", x: 20, y: 40, w: 40, h: 60, d: 20, uv: { top: [20,16], bottom: [28,16], right: [16,20], front: [20,20], left: [28,20], back: [32,20] }, overlayUv: { top: [20,32], bottom: [28,32], right: [16,36], front: [20,36], left: [28,36], back: [32,36] } },
  { name: "arm right-arm", x: 0, y: 40, w: 20, h: 60, d: 20, uv: { top: [44,16], bottom: [48,16], right: [40,20], front: [44,20], left: [48,20], back: [52,20] }, overlayUv: { top: [44,32], bottom: [48,32], right: [40,36], front: [44,36], left: [48,36], back: [52,36] } },
  { name: "arm left-arm", x: 60, y: 40, w: 20, h: 60, d: 20, uv: { top: [36,48], bottom: [40,48], right: [32,52], front: [36,52], left: [40,52], back: [44,52] }, overlayUv: { top: [52,48], bottom: [56,48], right: [48,52], front: [52,52], left: [56,52], back: [60,52] } },
  { name: "leg right-leg", x: 20, y: 100, w: 20, h: 60, d: 20, uv: { top: [4,16], bottom: [8,16], right: [0,20], front: [4,20], left: [8,20], back: [12,20] }, overlayUv: { top: [4,32], bottom: [8,32], right: [0,36], front: [4,36], left: [8,36], back: [12,36] } },
  { name: "leg left-leg", x: 40, y: 100, w: 20, h: 60, d: 20, uv: { top: [20,48], bottom: [24,48], right: [16,52], front: [20,52], left: [24,52], back: [28,52] }, overlayUv: { top: [4,48], bottom: [8,48], right: [0,52], front: [4,52], left: [8,52], back: [12,52] } }
];

function minecraftFace(part, face, overlay = false) {
  const [u, v] = (overlay ? part.overlayUv : part.uv)[face];
  return `<i class="mc-face ${face}${overlay ? " overlay" : ""}" style="--uv-x:${u * -5}px;--uv-y:${v * -5}px"></i>`;
}

function minecraftModelHtml(skinUrl, body = {}) {
  const scale = Math.max(0.5, Math.min(1.25, Number(body.height_scale) || 1));
  const slim = body.arm_model === "slim";
  const placeholderUrl = "/api/trainer-skin?resource=cobbleventure%3Atrainer_skin%2Funimplemented";
  const skinLayers = skinUrl.startsWith("http")
    ? `url('${skinUrl}'),url('${placeholderUrl}')`
    : `url('${skinUrl}')`;
  const parts = minecraftParts.map((source) => {
    const part = slim && source.name.startsWith("arm")
      ? { ...source, x: source.name.includes("right-arm") ? 5 : 60, w: 15, d: 20 }
      : source;
    const faceNames = ["front", "back", "right", "left", "top", "bottom"];
    const faces = faceNames.map((face) => minecraftFace(part, face)).join("")
      + faceNames.map((face) => minecraftFace(part, face, true)).join("");
    return `<div class="mc-part mc-${part.name.replace(" ", " mc-")}" style="--x:${part.x}px;--y:${part.y}px;--w:${part.w}px;--h:${part.h}px;--d:${part.d}px">${faces}</div>`;
  }).join("");
  return `<div class="minecraft-stage" style="--skin-image:${skinLayers};--height-scale:${scale}"><div class="minecraft-model">${parts}</div><span class="minecraft-ground"></span></div>`;
}

function renderTrainerPreview() {
  if (!state.trainer) return;
  const trainerClass = state.trainerClasses.find((entry) => entry.id === state.trainer.npc?.trainer_class);
  const rosterCharacter = rosterCharacters().find((entry) => entry.id === state.trainer.npc?.character);
  const appearance = rosterCharacter
    ? effectiveCharacterAppearance(rosterCharacter)
    : (state.trainer.npc?.appearance || {});
  const className = trainerClass?.display_name?.ko_kr || "사용자 정의";
  const fullTitle = trainerDisplayName(state.trainer.name || {}, state.trainer.npc?.trainer_class).ko_kr || className;
  const visualMatch = rosterCharacter ? characterVisualMatchStatus(rosterCharacter) : "matched";
  const skinUrl = trainerSkinUrl(appearance);
  const body = {
    ...(rosterCharacter?.body || trainerClass?.body || {}),
    ...(rosterCharacter?.gender === "male" ? { arm_model: "classic" } : {}),
    ...(rosterCharacter?.gender === "female" ? { arm_model: "slim" } : {}),
  };
  const appearanceState = visualMatch === "generic"
    ? { className: "placeholder", label: "1차 스킨 검토 필요" }
    : (appearance.implementation_status || trainerClass?.default_appearance?.implementation_status) === "placeholder"
      ? { className: "placeholder", label: "전용 스킨 준비 중" }
      : { className: "ready", label: "전용 스킨 준비됨" };
  const preview = $("#trainer-preview");
  preview.innerHTML = skinUrl ? `
    <div class="trainer-appearance-comparison">
      <section class="trainer-reference-card"><span>본가 디자인 기준</span>${trainerReferenceHtml(trainerClass, rosterCharacter)}</section>
      <section class="trainer-minecraft-card"><span>현재 Minecraft 외형</span>${minecraftModelHtml(skinUrl, body)}</section>
    </div>
    <strong>${escapeHtml(fullTitle)}</strong>
    <span class="appearance-status ${appearanceState.className}">${appearanceState.label}</span>`
    : `<div class="trainer-preview-fallback">${escapeHtml(className.slice(0, 2))}</div><strong>${escapeHtml(fullTitle)}</strong>`;
  const referenceImage = preview.querySelector(".trainer-reference-image");
  referenceImage?.addEventListener("error", () => {
    const fallbackSprites = JSON.parse(referenceImage.dataset.fallbackSprites || "[]");
    const nextSprite = fallbackSprites.shift();
    if (nextSprite) {
      referenceImage.dataset.fallbackSprites = JSON.stringify(fallbackSprites);
      referenceImage.src = trainerReferenceUrl(nextSprite);
      return;
    }
    referenceImage.hidden = true;
    const fallback = preview.querySelector(".trainer-reference-empty");
    if (fallback) fallback.hidden = false;
  });
  const ageNames = { child: "어린이", teen: "청소년", adult: "성인" };
  $("#trainer-appearance-note").textContent = visualMatch === "generic"
    ? `${rosterCharacter?.display_name?.ko_kr || "선택한 인물"}의 검토용 1차 slim 스킨입니다. 원작과 다른 부분은 수동 리터치 또는 개별 재생성으로 교체할 수 있습니다.`
    : skinUrl
    ? `Minecraft 64×64 스킨의 실제 앞·뒤·옆면을 입체로 표시합니다. 체형: ${ageNames[body.age_group] || "성인"}, 키 ${Math.round((body.height_scale || 1) * 100)}%, 팔 ${body.arm_model === "slim" ? "슬림" : "기본"}.`
    : "스킨 또는 모델 리소스를 연결하면 Minecraft 외형을 표시합니다.";
}

function trainerDisplayName(name, classId) {
  const trainerClass = state.trainerClasses.find((entry) => entry.id === classId);
  const result = {};
  for (const language of new Set(["ko_kr", "en_us", ...Object.keys(name || {})])) {
    const personalName = name?.[language] || name?.ko_kr || "";
    const pattern = trainerClass?.title_pattern?.[language] || trainerClass?.title_pattern?.ko_kr || "{name}";
    if (personalName) result[language] = pattern.replaceAll("{name}", personalName);
  }
  return result;
}

function toId(value) {
  return String(value || "").toLowerCase().replace(/[^a-z0-9]+/g, "");
}

function currentPokemon() {
  return trainerBattle()?.team?.[state.selectedPokemonIndex] || null;
}

function natureById(value) {
  return natureDefinitions.find((nature) => nature.id === String(value || "").toLowerCase()) || null;
}

function natureLabel(value) {
  const nature = natureById(value);
  return nature ? `${nature.name} (${nature.id})` : "자동 결정";
}

function natureEffectLabel(value) {
  const nature = natureById(value);
  if (!nature) return "상승·하락 능력치로 성격을 결정합니다.";
  if (!nature.increased || !nature.decreased) return "능력치 보정 없음";
  return `${statLabels[nature.increased]} ↑ · ${statLabels[nature.decreased]} ↓`;
}

function catalogSpeciesForPokemon(pokemon) {
  const catalog = state.editorCatalog?.species || [];
  const formId = toId(pokemon?.form);
  if (formId) {
    const form = catalog.find((entry) => entry.id === formId);
    if (form) return form;
  }
  const speciesId = toId(String(pokemon?.species || "").replace(/^.*:/, ""));
  return catalog.find((entry) => entry.id === speciesId || (entry.number && toId(entry.baseSpecies) === speciesId && !entry.forme)) || null;
}

function speciesResourceId(entry) {
  const base = (state.editorCatalog?.species || []).find(
    (candidate) => candidate.number === entry.number && !candidate.forme,
  );
  return `cobblemon:${base?.id || toId(entry.baseSpecies || entry.englishName || entry.id)}`;
}

function formOptionsForPokemon(pokemon) {
  const selected = catalogSpeciesForPokemon(pokemon);
  if (!selected) return [];
  return (state.editorCatalog?.species || []).filter((entry) => entry.number === selected.number);
}

const pokemonFormNames = {
  alola: "알로라", galar: "가라르", hisui: "히스이", paldea: "팔데아",
  mega: "메가진화", megax: "메가진화 X", megay: "메가진화 Y", gmax: "거다이맥스",
  primal: "원시회귀", origin: "오리진폼", therian: "영물폼", incarnate: "화신폼",
  sky: "스카이폼", land: "랜드폼", attack: "어택폼", defense: "디펜스폼",
  speed: "스피드폼", school: "군집의 모습", solo: "단독의 모습", zen: "달마모드",
  crowned: "왕의 모습", hero: "역전의 용사", complete: "퍼펙트폼", totem: "주인 포켓몬",
};

function pokemonFormLabel(forme) {
  const form = String(forme || "");
  const id = toId(form);
  if (pokemonFormNames[id]) return pokemonFormNames[id];
  const translated = form.split("-").map((part) => pokemonFormNames[toId(part)] || part).join(" ");
  return translated || "기본";
}

function pokemonCatalogDisplayName(entry) {
  if (!entry) return "포켓몬";
  if (entry.name && entry.name !== entry.englishName) return entry.name;
  if (entry.forme) {
    const base = (state.editorCatalog?.species || []).find(
      (candidate) => candidate.number === entry.number && !candidate.forme,
    );
    if (base?.name) return `${base.name} (${pokemonFormLabel(entry.forme)})`;
  }
  return entry.name || entry.englishName || entry.id;
}

function pokemonCatalogDescription(entry) {
  if (!entry) return "설명 없음";
  const description = String(entry.description || "");
  if (!entry.forme || /[가-힣]/.test(description)) return description || entry.englishName || "설명 없음";
  const base = (state.editorCatalog?.species || []).find(
    (candidate) => candidate.number === entry.number && !candidate.forme,
  );
  return base?.description || description || entry.englishName || "설명 없음";
}

function catalogItemByShortId(shortId) {
  return (state.editorCatalog?.items || []).find((item) => item.shortId === shortId) || null;
}

function moveCatalogId(value) {
  return toId(String(value || "").replace(/^.*:/, ""));
}

function moveCatalogEntry(value) {
  const id = moveCatalogId(value);
  return (state.editorCatalog?.moves || []).find((entry) => moveCatalogId(entry.id) === id) || null;
}

function focusedMoveCard(move, index) {
  const entry = moveCatalogEntry(move);
  const typeId = entry ? toId(entry.type) : "";
  const name = entry?.name || move || "";
  const categoryNames = { Physical: "물리", Special: "특수", Status: "변화" };
  const details = entry
    ? `<span class="focused-move-meta"><b class="move-type-badge type-${escapeHtml(typeId)}">${escapeHtml(pokemonTypeNames[entry.type] || entry.type)}</b><span>${escapeHtml(categoryNames[entry.category] || entry.category)}</span><span>위력 ${entry.power || "—"}</span><span>명중 ${entry.accuracy === true ? "필중" : (entry.accuracy || "—")}</span><span>PP ${entry.pp ?? "—"}</span></span><span class="focused-move-description">${escapeHtml(entry.description || entry.englishName || "설명 없음")}</span>`
    : `<span class="focused-move-empty-hint">기술을 선택하면 타입과 전투 정보가 표시됩니다.</span>`;
  return `<div class="focused-move-field ${move ? `type-${escapeHtml(typeId)}` : "empty"}"><span>${String(index + 1).padStart(2, "0")}</span><label><small>MOVE ${index + 1}</small><input data-move="${index}" data-value="${escapeHtml(move)}" value="${escapeHtml(name)}" readonly placeholder="기술 선택">${details}</label><div class="move-field-actions"><button type="button" data-open-move="${index}">선택</button><button type="button" data-clear-move="${index}">지우기</button></div></div>`;
}

function commonPrefixLength(left, right) {
  let length = 0;
  while (length < left.length && length < right.length && left[length] === right[length]) length += 1;
  return length;
}

function megaItemCandidates(pokemon) {
  const selected = catalogSpeciesForPokemon(pokemon);
  if (!selected) return [];
  const baseId = toId(selected.baseSpecies || selected.englishName || pokemon.species?.replace(/^.*:/, ""));
  const hasMegaForm = (state.editorCatalog?.species || []).some(
    (entry) => entry.number === selected.number && /mega/i.test(entry.forme || ""),
  );
  if (!hasMegaForm) return [];
  return (state.editorCatalog?.items || [])
    .filter((item) => item.category === "mega")
    .map((item) => {
      const stem = toId(item.shortId).replace(/ite[xy]?$/, "");
      const prefix = commonPrefixLength(baseId, stem);
      return { item, prefix, score: prefix * 10 - Math.abs(baseId.length - stem.length) };
    })
    .filter(({ item, prefix }) => prefix >= Math.max(4, Math.ceil(Math.min(baseId.length, toId(item.shortId).length) * 0.6)))
    .sort((left, right) => right.score - left.score || left.item.shortId.localeCompare(right.item.shortId))
    .map(({ item }) => item);
}

function zItemCandidates(pokemon) {
  const selected = catalogSpeciesForPokemon(pokemon);
  if (!selected) return [];
  const baseId = toId(selected.baseSpecies || selected.englishName || pokemon.species?.replace(/^.*:/, ""));
  const formId = toId(`${selected.forme || ""} ${pokemon.form || ""}`);
  const moveIds = new Set((pokemon.moves || []).map(moveCatalogId));
  const candidates = [];
  for (const rule of signatureZCrystals) {
    const speciesMatches = rule.speciesPrefix ? baseId.startsWith(rule.speciesPrefix) : rule.species.includes(baseId);
    const formMatches = !rule.forms || rule.forms.some((form) => formId.includes(form));
    const moveMatches = rule.moves.some((move) => moveIds.has(move));
    if (speciesMatches && formMatches && moveMatches) candidates.push(catalogItemByShortId(rule.item));
  }
  for (const moveId of pokemon.moves || []) {
    const move = (state.editorCatalog?.moves || []).find((entry) => entry.id === moveCatalogId(moveId));
    const crystal = move ? catalogItemByShortId(zCrystalByType[move.type]) : null;
    if (crystal) candidates.push(crystal);
  }
  return candidates.filter((item, index, items) => item && items.findIndex((candidate) => candidate?.id === item.id) === index);
}

function gimmickCandidates(pokemon, type) {
  return type === "mega_evolution" ? megaItemCandidates(pokemon) : type === "z_move" ? zItemCandidates(pokemon) : [];
}

function normalizePokemonGimmick(pokemon) {
  if (!pokemon.gimmick) return;
  const candidates = gimmickCandidates(pokemon, pokemon.gimmick.type);
  if (!candidates.length) {
    pokemon.gimmick = null;
    return;
  }
  if (!candidates.some((item) => item.id === pokemon.gimmick.item)) pokemon.gimmick.item = candidates[0].id;
  pokemon.held_item = null;
}

function itemLabel(itemId) {
  const item = (state.editorCatalog?.items || []).find((entry) => entry.id === itemId);
  return item ? `${item.name} (${item.shortId})` : itemId;
}

function pokemonTemplate() {
  return {
    species: "cobblemon:rattata", level: 5, form: null, aspects: [], gender: "random",
    nature: null, ability: null, held_item: null, moves: ["tackle"], ivs: {}, evs: {},
    tera_type: "auto", shiny: false, gigantamax_factor: false, gimmick: null
  };
}

function renderTeam() {
  const team = trainerBattle()?.team || [];
  const list = $("#team-list");
  if (!team.length) {
    list.innerHTML = '<div class="focused-entry-editor"><button class="empty-team-prompt" type="button">＋ 첫 포켓몬 추가</button></div>';
    $(".empty-team-prompt").addEventListener("click", addPokemon);
    return;
  }
  state.selectedPokemonIndex = Math.min(state.selectedPokemonIndex, team.length - 1);
  const pokemon = team[state.selectedPokemonIndex];
  normalizePokemonStats(pokemon);
  const stats = [
    ["hp", "HP"], ["attack", "공격"], ["defense", "방어"],
    ["special_attack", "특수공격"], ["special_defense", "특수방어"], ["speed", "스피드"]
  ];
  const moves = Array.from({ length: 4 }, (_, index) => pokemon.moves?.[index] || "");
  const formOptions = formOptionsForPokemon(pokemon);
  const baseFormOption = formOptions.find((entry) => !entry.forme);
  normalizePokemonGimmick(pokemon);
  const megaCandidates = megaItemCandidates(pokemon);
  const zCandidates = zItemCandidates(pokemon);
  const gimmickCandidatesForPokemon = gimmickCandidates(pokemon, pokemon.gimmick?.type);
  list.innerHTML = `
    <div class="focused-entry-editor">
      <nav class="focused-party-tabs" aria-label="편집할 포켓몬 선택">
        ${Array.from({ length: 6 }, (_, index) => {
          const member = team[index];
          if (!member) return `<button type="button" class="empty" data-add-slot="${index}"><b>＋</b><strong>빈 슬롯</strong><small>SLOT ${String(index + 1).padStart(2, "0")}</small></button>`;
          return `<button type="button" class="${index === state.selectedPokemonIndex ? "active" : ""}" data-pokemon-index="${index}"><span>${String(index + 1).padStart(2, "0")}</span><img data-party-art="${index}" alt="" hidden><b data-party-fallback="${index}">●</b><strong>${escapeHtml(pokemonDisplayName(member))}</strong><small>Lv.${escapeHtml(member.level)}</small></button>`;
        }).join("")}
      </nav>
      <div class="party-order-toolbar" aria-label="선택한 포켓몬 순서 이동">
        <span><b>PARTY ORDER</b> 선택한 포켓몬을 이동합니다.</span>
        <button type="button" id="move-pokemon-left" ${state.selectedPokemonIndex === 0 ? "disabled" : ""}>← 왼쪽</button>
        <button type="button" id="move-pokemon-right" ${state.selectedPokemonIndex === team.length - 1 ? "disabled" : ""}>오른쪽 →</button>
      </div>
      <article class="focused-pokemon-editor">
        <aside class="focused-pokemon-preview">
          <span class="slot-number">PARTY SLOT ${String(state.selectedPokemonIndex + 1).padStart(2, "0")}</span>
          <div><img class="focused-pokemon-sprite" id="focused-pokemon-art" alt="${escapeHtml(pokemonDisplayName(pokemon))}" hidden><button class="empty-pokemon-prompt" id="pokemon-art-fallback" type="button"><b>?</b><span>이미지 불러오는 중</span></button></div>
          <h3 id="focused-species-name">${escapeHtml(pokemonDisplayName(pokemon))}</h3>
          <p>Lv.${escapeHtml(pokemon.level)} · PokeAPI official-artwork</p>
          <div class="focused-preview-actions"><button type="button" id="duplicate-pokemon">복제</button><button type="button" class="danger" id="remove-focused-pokemon">팀에서 제거</button></div>
        </aside>
        <section class="focused-profile-panel">
          <header><span>PROFILE</span><strong>기본 설정</strong><small>필수 정보</small></header>
          <div class="focused-profile-fields">
            <label class="wide"><span>포켓몬</span><span class="editor-picker-input"><input name="species" value="${escapeHtml(pokemon.species || "")}" readonly><button type="button" data-open-choice="pokemon">선택</button></span></label>
            <label class="wide"><span>폼·특수 형태</span><select name="form"><option value="">${escapeHtml(baseFormOption ? `${pokemonCatalogDisplayName(baseFormOption)} (기본)` : "기본 형태")}</option>${formOptions.filter((entry) => entry.forme).map((entry) => `<option value="${escapeHtml(entry.id)}" ${String(pokemon.form || "") === entry.id ? "selected" : ""}>${escapeHtml(pokemonCatalogDisplayName(entry))}</option>`).join("")}</select></label>
            <label><span>레벨</span><input type="number" min="1" max="100" name="level" value="${escapeHtml(pokemon.level ?? 5)}"></label>
            <label><span>성별</span><select name="gender"><option value="random" ${pokemon.gender === "random" ? "selected" : ""}>무작위</option><option value="male" ${pokemon.gender === "male" ? "selected" : ""}>수컷</option><option value="female" ${pokemon.gender === "female" ? "selected" : ""}>암컷</option><option value="genderless" ${pokemon.gender === "genderless" ? "selected" : ""}>무성</option></select></label>
            <label class="wide"><span>성격</span><span class="editor-picker-input"><input name="nature" value="${escapeHtml(natureLabel(pokemon.nature))}" data-value="${escapeHtml(pokemon.nature || "")}" readonly><button type="button" data-open-choice="nature">선택</button></span><small class="nature-effect-summary">${escapeHtml(natureEffectLabel(pokemon.nature))}</small></label>
            <label class="wide"><span>특성</span><span class="editor-picker-input"><input name="ability" value="${escapeHtml(pokemon.ability || "")}" readonly placeholder="비우면 자동"><button type="button" data-open-choice="ability">선택</button></span></label>
            <label class="wide"><span>일반 소지품</span><span class="editor-picker-input"><input name="heldItem" value="${escapeHtml(pokemon.held_item || "")}" readonly placeholder="${pokemon.gimmick ? "기믹 아이템 사용 중" : "비우면 없음"}"><button type="button" data-open-choice="item">선택</button></span></label>
            <label class="wide"><span>테라 타입</span><select name="teraType"><option value="auto" ${!pokemon.tera_type || pokemon.tera_type === "auto" ? "selected" : ""}>자동 (주속성 중 하나)</option>${Object.entries(pokemonTypeNames).map(([type, label]) => `<option value="${type.toLowerCase()}" ${pokemon.tera_type === type.toLowerCase() ? "selected" : ""}>${label}</option>`).join("")}</select><small class="tera-type-summary">자동은 RCT 출력 시 포켓몬의 원래 타입 중 하나로 확정됩니다.</small></label>
          </div>
          <div class="focused-gimmick-row">
            <label><input type="checkbox" name="shiny" ${pokemon.shiny ? "checked" : ""}><span>이로치</span></label>
            <label><input type="checkbox" name="gigantamax" ${pokemon.gigantamax_factor ? "checked" : ""}><span>거다이맥스</span></label>
            <label title="${megaCandidates.length ? "체크하면 메가스톤을 자동으로 지정합니다." : "현재 포켓몬에 대응하는 메가스톤이 없습니다."}"><input type="checkbox" name="pokemonMegaEvolution" ${pokemon.gimmick?.type === "mega_evolution" ? "checked" : ""} ${megaCandidates.length ? "" : "disabled"}><span>메가진화<small>${megaCandidates.length ? `${megaCandidates.length}개 호환` : "사용 불가"}</small></span></label>
            <label title="${zCandidates.length ? "현재 기술에 맞는 Z크리스탈을 자동으로 지정합니다." : "현재 기술과 포켓몬에 맞는 Z크리스탈이 없습니다."}"><input type="checkbox" name="pokemonZMove" ${pokemon.gimmick?.type === "z_move" ? "checked" : ""} ${zCandidates.length ? "" : "disabled"}><span>Z기술<small>${zCandidates.length ? `${zCandidates.length}개 호환` : "사용 불가"}</small></span></label>
          </div>
          ${pokemon.gimmick ? `<label class="focused-gimmick-item"><span>RCT 출력용 기믹 소지품</span><select name="gimmickItem">${gimmickCandidatesForPokemon.map((item) => `<option value="${escapeHtml(item.id)}" ${item.id === pokemon.gimmick.item ? "selected" : ""}>${escapeHtml(itemLabel(item.id))}</option>`).join("")}</select><small>정규화 JSON에는 기믹으로 보관하고, RCT 출력 시 실제 소지품으로 변환합니다.</small></label>` : ""}
        </section>
        <section class="focused-stat-panel">
          <header><span>STATS</span><strong>개체값·노력치</strong><small id="ev-total" title="IV는 능력치별 31, EV는 능력치별 252·전체 510으로 자동 제한됩니다.">EV ${evTotal(pokemon)}/510</small></header>
          <div class="focused-stat-table"><div class="focused-stat-heading"><span>능력치</span><span>IV</span><span>EV</span></div>${stats.map(([key, label]) => `<label><strong>${label}</strong><input type="number" min="0" max="31" data-iv="${key}" value="${escapeHtml(pokemon.ivs?.[key] ?? 0)}"><input type="number" min="0" max="252" data-ev="${key}" value="${escapeHtml(pokemon.evs?.[key] ?? 0)}"></label>`).join("")}</div>
        </section>
        <section class="focused-moves-panel">
          <header><span>MOVESET</span><strong>기술 구성</strong><small>최대 4개</small></header>
          <div class="focused-moves-list">${moves.map(focusedMoveCard).join("")}</div>
        </section>
      </article>
    </div>`;
  $$('[data-pokemon-index]').forEach((button) => button.addEventListener("click", () => selectPokemon(Number(button.dataset.pokemonIndex))));
  $$('[data-add-slot]').forEach((button) => button.addEventListener("click", addPokemon));
  $$(".focused-pokemon-editor input, .focused-pokemon-editor select").forEach((input) => input.addEventListener("input", updateFocusedPokemon));
  list.querySelector('[name="form"]')?.addEventListener("change", () => { updateFocusedPokemon(); renderTeam(); });
  list.querySelector('[name="shiny"]')?.addEventListener("change", () => { updateFocusedPokemon(); renderTeam(); });
  list.querySelector('[name="pokemonMegaEvolution"]')?.addEventListener("change", (event) => setPokemonGimmick("mega_evolution", event.target.checked));
  list.querySelector('[name="pokemonZMove"]')?.addEventListener("change", (event) => setPokemonGimmick("z_move", event.target.checked));
  list.querySelector('[name="gimmickItem"]')?.addEventListener("change", (event) => setPokemonGimmickItem(event.target.value));
  $$('[data-open-choice]').forEach((button) => button.addEventListener("click", () => openChoiceDialog(button.dataset.openChoice)));
  $$('[data-open-move]').forEach((button) => button.addEventListener("click", () => openChoiceDialog("move", Number(button.dataset.openMove))));
  $$('[data-clear-move]').forEach((button) => button.addEventListener("click", () => clearMove(Number(button.dataset.clearMove))));
  $("#remove-focused-pokemon").addEventListener("click", () => removePokemon(state.selectedPokemonIndex));
  $("#duplicate-pokemon").addEventListener("click", duplicatePokemon);
  $("#move-pokemon-left").addEventListener("click", () => moveSelectedPokemon(-1));
  $("#move-pokemon-right").addEventListener("click", () => moveSelectedPokemon(1));
  hydrateFocusedPokemonArt();
  hydratePartyArt();
}

function setPokemonGimmick(type, enabled) {
  const pokemon = currentPokemon();
  if (!pokemon) return;
  updateFocusedPokemon();
  if (!enabled) {
    if (pokemon.gimmick?.type === type) pokemon.gimmick = null;
  } else {
    const candidates = gimmickCandidates(pokemon, type);
    if (!candidates.length) {
      toast("현재 포켓몬 설정에 맞는 기믹 아이템이 없습니다.");
      renderTeam();
      return;
    }
    pokemon.gimmick = { type, item: candidates[0].id };
    pokemon.held_item = null;
    const mechanicInput = $("#battle-form").elements[type === "mega_evolution" ? "megaEvolution" : "zMove"];
    mechanicInput.checked = true;
    trainerBattle().mechanics[type] = true;
  }
  renderTeam();
  syncTrainerJson();
}

function setPokemonGimmickItem(itemId) {
  const pokemon = currentPokemon();
  if (!pokemon?.gimmick) return;
  const candidates = gimmickCandidates(pokemon, pokemon.gimmick.type);
  if (candidates.some((item) => item.id === itemId)) pokemon.gimmick.item = itemId;
  pokemon.held_item = null;
  syncTrainerJson();
}

function addPokemon() {
  if (!trainerBattle()) return;
  if (trainerBattle().team.length >= 6) { toast("팀은 최대 6마리까지 구성할 수 있습니다."); return; }
  trainerBattle().team.push(pokemonTemplate());
  state.selectedPokemonIndex = trainerBattle().team.length - 1;
  renderTeam();
  syncTrainerJson();
}

function removePokemon(index) {
  if (trainerBattle().team.length <= 1) { toast("팀에는 포켓몬이 한 마리 이상 필요합니다."); return; }
  trainerBattle().team.splice(index, 1);
  state.selectedPokemonIndex = Math.min(index, trainerBattle().team.length - 1);
  renderTeam();
  syncTrainerJson();
}

function selectPokemon(index) {
  updateFocusedPokemon();
  state.selectedPokemonIndex = index;
  renderTeam();
}

function moveSelectedPokemon(offset) {
  const team = trainerBattle()?.team;
  if (!team) return;
  updateFocusedPokemon();
  const targetIndex = state.selectedPokemonIndex + offset;
  if (targetIndex < 0 || targetIndex >= team.length) return;
  [team[state.selectedPokemonIndex], team[targetIndex]] = [team[targetIndex], team[state.selectedPokemonIndex]];
  state.selectedPokemonIndex = targetIndex;
  renderTeam();
  syncTrainerJson();
}

function duplicatePokemon() {
  const team = trainerBattle().team;
  if (team.length >= 6) { toast("팀은 최대 6마리까지 구성할 수 있습니다."); return; }
  team.splice(state.selectedPokemonIndex + 1, 0, structuredClone(team[state.selectedPokemonIndex]));
  state.selectedPokemonIndex += 1;
  renderTeam();
  syncTrainerJson();
}

function clipboardCatalogOptions() {
  return {
    species: state.editorCatalog?.species || [],
    items: state.editorCatalog?.items || [],
  };
}

async function copyTeamJson() {
  if (!trainerBattle()?.team?.length) return;
  updateFocusedPokemon();
  try {
    const entry = createPartyClipboardEntry(
      trainerBattle().team,
      clipboardCatalogOptions(),
    );
    await writeClipboardText(JSON.stringify(entry, null, 2));
    toast(`포켓몬 ${entry.pokemon.length}마리의 엔트리 JSON을 복사했습니다.`);
  } catch (error) {
    toast(error.message);
  }
}

async function pasteTeamJson() {
  if (!trainerBattle()) return;
  try {
    const entry = parsePartyClipboardText(
      await readClipboardText(),
      clipboardCatalogOptions(),
    );
    const team = toContentManagerParty(entry, clipboardCatalogOptions());
    trainerBattle().team = team;
    trainerBattle().mechanics ||= {};
    for (const pokemon of team) {
      if (pokemon.gimmick?.type) trainerBattle().mechanics[pokemon.gimmick.type] = true;
      if (pokemon.gigantamax_factor) trainerBattle().mechanics.dynamax = true;
    }
    state.selectedPokemonIndex = 0;
    renderBattlePreset();
    toast(`클립보드에서 포켓몬 ${team.length}마리를 붙여넣었습니다.`);
  } catch (error) {
    toast(error.message);
  }
}

function updateFocusedPokemon(event = null) {
  const editor = $(".focused-pokemon-editor");
  if (!editor || !trainerBattle()?.team?.[state.selectedPokemonIndex]) return;
  const pokemon = trainerBattle().team[state.selectedPokemonIndex];
  const value = (name) => editor.querySelector(`[name="${name}"]`).value.trim();
  const ivInputs = [...editor.querySelectorAll('[data-iv]')];
  const evInputs = [...editor.querySelectorAll('[data-ev]')];
  const ivs = Object.fromEntries(ivInputs.map((input) => {
    const amount = clampInteger(input.value, 0, 31);
    input.value = amount;
    return [input.dataset.iv, amount];
  }));
  const evs = Object.fromEntries(evInputs.map((input) => [input.dataset.ev, clampInteger(input.value, 0, 252)]));
  const activeEvKey = event?.target?.dataset?.ev || null;
  const total = Object.values(evs).reduce((sum, amount) => sum + amount, 0);
  if (total > 510 && activeEvKey && Object.hasOwn(evs, activeEvKey)) {
    const otherTotal = Object.entries(evs).reduce((sum, [key, amount]) => key === activeEvKey ? sum : sum + amount, 0);
    evs[activeEvKey] = Math.max(0, 510 - otherTotal);
  } else if (total > 510) {
    let remaining = 510;
    for (const key of pokemonStatKeys) {
      evs[key] = Math.min(evs[key] || 0, remaining);
      remaining -= evs[key];
    }
  }
  evInputs.forEach((input) => { input.value = evs[input.dataset.ev] ?? 0; });
  Object.assign(pokemon, {
    species: value("species"), level: Number(value("level")), gender: value("gender"),
    form: value("form") || null,
    aspects: Array.isArray(pokemon.aspects) ? [...pokemon.aspects] : [],
    nature: editor.querySelector('[name="nature"]').dataset.value || null,
    ability: value("ability") || null,
    held_item: value("heldItem") || null, tera_type: value("teraType") || "auto",
    shiny: editor.querySelector('[name="shiny"]').checked,
    gigantamax_factor: editor.querySelector('[name="gigantamax"]').checked,
    ivs, evs,
    moves: $$('[data-move]').map((input) => String(input.dataset.value ?? input.value).trim()).filter(Boolean)
  });
  normalizePokemonGimmick(pokemon);
  $("#ev-total").textContent = `EV ${evTotal(pokemon)}/510`;
  $("#focused-species-name").textContent = pokemonDisplayName(pokemon);
  syncTrainerJson();
}

function clearMove(index) {
  const input = $(`[data-move="${index}"]`);
  input.dataset.value = "";
  input.value = "";
  updateFocusedPokemon();
  input.focus();
}

function natureSelection(value) {
  const nature = natureById(value);
  if (!nature) return ["", ""];
  if (nature.increased && nature.decreased) return [nature.increased, nature.decreased];
  const neutralStat = Object.entries(neutralNatureByStat).find(([, id]) => id === nature.id)?.[0] || "";
  return [neutralStat, neutralStat];
}

function natureForStats(increased, decreased) {
  if (!increased && !decreased) return natureById("hardy");
  if (!increased || !decreased) return null;
  if (increased === decreased) return natureById(neutralNatureByStat[increased]);
  return natureDefinitions.find((nature) => nature.increased === increased && nature.decreased === decreased) || null;
}

function openChoiceDialog(kind, moveIndex = null, bagIndex = null) {
  if (kind === "trainer_reference" && !state.battlePreset) {
    toast("먼저 적용할 배틀 프리셋을 선택하세요.");
    return;
  }
  const isReferenceChoice = ["trainer_reference", "trainer_reference_create"].includes(kind);
  if (!state.editorCatalog || (!["bag_item", "trainer_reference", "trainer_reference_create"].includes(kind) && !currentPokemon())) {
    toast("전투 데이터 카탈로그를 아직 불러오지 못했습니다.");
    return;
  }
  if (!["bag_item", "trainer_reference", "trainer_reference_create"].includes(kind)) updateFocusedPokemon();
  const [natureUp, natureDown] = natureSelection(currentPokemon()?.nature);
  const initialScope = kind === "pokemon" ? "all" : kind === "item" ? "battle" : isReferenceChoice ? "all" : "recommended";
  state.choice = { kind, moveIndex, bagIndex, query: "", type: "", category: "", scope: kind === "bag_item" ? "all" : initialScope, generation: "", natureUp, natureDown };
  const titles = {
    pokemon: ["포켓몬 선택", "기본 모습, 지역 폼과 특수 형태를 함께 검색합니다."],
    nature: ["성격 선택", "올릴 능력치와 내릴 능력치를 고르면 실제 성격으로 자동 연결됩니다."],
    ability: ["특성 선택", "현재 포켓몬이 사용할 수 있는 특성을 우선 표시합니다."],
    item: ["지닌 도구 선택", "배틀에서 사용할 수 있는 도구를 종류와 출처별로 찾습니다."],
    bag_item: ["가방 아이템 선택", "트레이너가 전투 중 사용할 회복·상태회복·능력치 아이템을 찾습니다."],
    trainer_reference: ["참고 트레이너 엔트리", "출처·분류·이름·포켓몬을 검색한 뒤 현재 트레이너의 전투 데이터로 적용합니다."],
    trainer_reference_create: ["배틀 프리셋 시작 엔트리", "출처·분류·이름·포켓몬을 검색하고 새 배틀 프리셋의 시작 데이터로 선택합니다."],
    move: ["기술 선택", "현재 포켓몬이 배울 수 있는 기술을 우선 표시합니다."]
  };
  $("#choice-eyebrow").textContent = "BATTLE DATA PICKER";
  [$("#choice-title").textContent, $("#choice-subtitle").textContent] = titles[kind];
  $("#choice-dialog").showModal();
  renderChoiceDialog();
}

function openItemChoice(targetId, options = {}) {
  if (!state.editorCatalog?.rewardItems) {
    toast("아이템 카탈로그를 아직 불러오지 못했습니다.");
    return;
  }
  state.choice = {
    kind: "reward_item",
    targetId,
    allowEmpty: options.allowEmpty === true,
    query: "",
    scope: "",
  };
  $("#choice-eyebrow").textContent = "ITEM PICKER";
  $("#choice-title").textContent = options.title || "아이템 선택";
  $("#choice-subtitle").textContent = options.subtitle || "이름이나 ID로 검색한 뒤 지급할 아이템을 선택합니다.";
  $("#choice-dialog").showModal();
  renderChoiceDialog();
  requestAnimationFrame(() => $("#choice-search")?.focus());
}

function choiceSearchInput() {
  return '<input id="choice-search" placeholder="이름·ID·설명 검색" value="">';
}

function natureStatButtons(tone, selected) {
  return natureStats.map((stat) => {
    const disabled = stat === "hp";
    const label = stat ? (disabled ? "체력 (불가)" : statLabels[stat]) : "보정 없음";
    return `<button type="button" class="${selected === stat ? "active" : ""}" data-nature-${tone}="${stat}" ${disabled ? "disabled title=\"포켓몬의 성격은 체력을 보정하지 않습니다.\"" : ""}>${label}</button>`;
  }).join("");
}

function renderChoiceDialog() {
  const choice = state.choice;
  if (!choice) return;
  const pokemon = currentPokemon();
  const selectedSpecies = catalogSpeciesForPokemon(pokemon);
  const filters = $("#choice-filters");
  if (choice.kind === "nature") {
    filters.className = "choice-dialog-filters nature-choice-filters";
    filters.innerHTML = `<fieldset class="nature-stat-selector up"><legend>올릴 능력치 (+10%)</legend><div>${natureStatButtons("up", choice.natureUp)}</div></fieldset><fieldset class="nature-stat-selector down"><legend>내릴 능력치 (-10%)</legend><div>${natureStatButtons("down", choice.natureDown)}</div></fieldset>`;
  } else if (choice.kind === "pokemon") {
    filters.className = "choice-dialog-filters";
    filters.innerHTML = `${choiceSearchInput()}<select id="choice-type"><option value="">모든 타입</option>${Object.entries(pokemonTypeNames).map(([type, label]) => `<option value="${type}">${label}</option>`).join("")}</select><select id="choice-generation"><option value="">모든 세대</option>${Array.from({length: 9}, (_, index) => `<option value="${index + 1}">${index + 1}세대</option>`).join("")}</select><select id="choice-scope"><option value="all">모든 모습</option><option value="base">기본 모습</option><option value="forms">폼·지역 모습</option><option value="special">특수·전투 형태</option></select>`;
  } else if (choice.kind === "move") {
    filters.className = "choice-dialog-filters";
    filters.innerHTML = `${choiceSearchInput()}<select id="choice-scope"><option value="recommended">현재 포켓몬의 기술</option><option value="all">전체 기술</option></select><select id="choice-type"><option value="">모든 타입</option>${Object.entries(pokemonTypeNames).map(([type, label]) => `<option value="${type}">${label}</option>`).join("")}</select><select id="choice-category"><option value="">모든 분류</option><option value="Physical">물리</option><option value="Special">특수</option><option value="Status">변화</option></select>`;
  } else if (choice.kind === "economy_item") {
    const categoryIndex = economySaleCategoryIndex();
    const categories = [...new Set([...categoryIndex.values()].flatMap((values) => [...values]))].sort((left, right) => left.localeCompare(right, "ko"));
    const namespaces = [...new Set((state.economy.editor_catalog?.items || []).map((item) => item.id.split(":", 1)[0]))].sort();
    filters.className = "choice-dialog-filters economy-item-choice-filters";
    filters.innerHTML = `${choiceSearchInput()}<select id="choice-category"><option value="">전체 아이템 · 판매 분류 제한 없음</option>${categories.map((category) => `<option value="${escapeHtml(category)}">${escapeHtml(category)}</option>`).join("")}</select><select id="choice-scope"><option value="">모든 아이템 출처</option>${namespaces.map((namespace) => `<option value="${escapeHtml(namespace)}">${escapeHtml(namespace)}</option>`).join("")}</select>`;
  } else if (choice.kind === "reward_item") {
    const namespaces = [...new Set((state.editorCatalog.rewardItems || []).map((item) => item.namespace))].sort();
    filters.className = "choice-dialog-filters reward-item-choice-filters";
    filters.innerHTML = `${choiceSearchInput()}<select id="choice-scope"><option value="">모든 아이템 출처</option>${namespaces.map((namespace) => `<option value="${escapeHtml(namespace)}">${escapeHtml(namespace)}</option>`).join("")}</select>`;
  } else if (choice.kind === "item") {
    const ordinaryItems = (state.editorCatalog.items || []).filter((item) => !["mega", "z"].includes(item.category));
    const namespaces = [...new Set(ordinaryItems.map((item) => item.namespace))].sort();
    filters.className = "choice-dialog-filters";
    filters.innerHTML = `${choiceSearchInput()}<select id="choice-scope"><option value="battle">배틀 사용 가능 전체</option><option value="held">일반 지닌 도구</option><option value="berry">나무열매</option><option value="gem">타입 주얼</option><option value="all">전체 일반 아이템</option></select><select id="choice-category"><option value="">모든 출처 모드</option>${namespaces.map((namespace) => `<option value="${escapeHtml(namespace)}">${escapeHtml(namespace)}</option>`).join("")}</select>`;
  } else if (choice.kind === "bag_item") {
    filters.className = "choice-dialog-filters";
    filters.innerHTML = `${choiceSearchInput()}<select id="choice-scope"><option value="all">모든 가방 아이템</option><option value="potion">HP 회복</option><option value="status">상태 회복</option><option value="revive">기절 회복</option><option value="battle">능력치 강화</option></select>`;
  } else if (["trainer_reference", "trainer_reference_create"].includes(choice.kind)) {
    const sources = state.trainerReferences.sources || [];
    const categories = [...new Set((state.trainerReferences.entries || []).map((entry) => entry.category))].sort();
    filters.className = "choice-dialog-filters";
    filters.innerHTML = `${choiceSearchInput()}<select id="choice-scope"><option value="all">모든 출처</option>${sources.map((source) => `<option value="${escapeHtml(source.id)}">${escapeHtml(source.display_name)}</option>`).join("")}</select><select id="choice-category"><option value="">모든 분류</option>${categories.map((category) => `<option value="${escapeHtml(category)}">${escapeHtml(category)}</option>`).join("")}</select>`;
  } else {
    filters.className = "choice-dialog-filters";
    filters.innerHTML = `${choiceSearchInput()}<select id="choice-scope"><option value="recommended">현재 포켓몬의 특성</option><option value="all">모든 특성</option></select>`;
  }
  bindChoiceFilters();
  renderChoiceResults(selectedSpecies);
}

function bindChoiceFilters() {
  const choice = state.choice;
  if (!choice) return;
  $("#choice-search")?.addEventListener("input", (event) => { choice.query = event.target.value; renderChoiceResults(catalogSpeciesForPokemon(currentPokemon())); });
  for (const [selector, key] of [["#choice-type", "type"], ["#choice-category", "category"], ["#choice-scope", "scope"], ["#choice-generation", "generation"]]) {
    const element = $(selector);
    if (!element) continue;
    element.value = choice[key] || element.value;
    element.addEventListener("change", (event) => { choice[key] = event.target.value; renderChoiceResults(catalogSpeciesForPokemon(currentPokemon())); });
  }
  $$('[data-nature-up]').forEach((button) => button.addEventListener("click", () => { choice.natureUp = button.dataset.natureUp; renderChoiceDialog(); }));
  $$('[data-nature-down]').forEach((button) => button.addEventListener("click", () => { choice.natureDown = button.dataset.natureDown; renderChoiceDialog(); }));
}

function specialForm(entry) {
  return /mega|primal|gmax|eternamax|ultra|crowned|origin|therian|school|complete/i.test(`${entry.id} ${entry.forme}`);
}

function renderChoiceResults(selectedSpecies) {
  const choice = state.choice;
  if (!choice) return;
  const query = choice.query.trim().toLowerCase();
  const matches = (...values) => !query || values.join(" ").toLowerCase().includes(query);
  let rows = [];
  if (choice.kind === "nature") {
    const nature = natureForStats(choice.natureUp, choice.natureDown);
    $("#choice-count").textContent = nature ? "현재 선택된 성격" : "상승·하락 능력치를 모두 선택해 주세요.";
    $("#choice-results").className = "choice-results nature-choice-result";
    $("#choice-results").innerHTML = nature ? `${optionalChoiceCard("nature")}<button type="button" class="nature-result-card" data-choice-value="${nature.id}"><span class="nature-result-heading"><span><small>SELECTED NATURE</small><strong>${nature.name}</strong></span><b>${nature.id}</b></span><span class="nature-result-effects">${nature.increased ? `<b class="up">${statLabels[nature.increased]} 10% 상승</b><b class="down">${statLabels[nature.decreased]} 10% 하락</b>` : '<b class="neutral">능력치 보정 없음</b>'}</span><span class="nature-result-action">이 성격을 적용하려면 클릭하세요</span></button>` : '<div class="choice-empty">실제 성격으로 연결하려면 두 능력치를 모두 선택하세요.</div>';
    bindChoiceResultButtons();
    return;
  }
  if (choice.kind === "pokemon") {
    rows = (state.editorCatalog.species || []).filter((entry) => matches(entry.id, entry.name, entry.englishName, entry.forme, pokemonCatalogDisplayName(entry), pokemonFormLabel(entry.forme), entry.number) && (!choice.type || entry.types.includes(choice.type)) && (!choice.generation || entry.generation === Number(choice.generation)) && (choice.scope === "all" || (choice.scope === "base" && !entry.forme) || (choice.scope === "forms" && entry.forme && !specialForm(entry)) || (choice.scope === "special" && specialForm(entry))));
  } else if (choice.kind === "move") {
    const learnset = state.editorCatalog.learnsets?.[selectedSpecies?.id] || {};
    rows = (state.editorCatalog.moves || []).filter((entry) => matches(entry.id, entry.name, entry.englishName, entry.description) && (!choice.type || entry.type === choice.type) && (!choice.category || entry.category === choice.category) && (choice.scope === "all" || !selectedSpecies || entry.id in learnset));
  } else if (choice.kind === "ability") {
    const allowed = new Set(selectedSpecies?.abilities || []);
    rows = (state.editorCatalog.abilities || []).filter((entry) => matches(entry.id, entry.name, entry.englishName, entry.description) && (choice.scope === "all" || !selectedSpecies || allowed.has(entry.id)));
  } else if (choice.kind === "economy_item") {
    const categoryIndex = economySaleCategoryIndex();
    rows = (state.economy.editor_catalog?.items || []).filter((entry) => {
      const categories = [...(categoryIndex.get(entry.id) || [])];
      const namespace = entry.id.split(":", 1)[0];
      return matches(entry.id, entry.ko_kr, entry.en_us, categories.join(" "))
        && (!choice.category || categories.includes(choice.category))
        && (!choice.scope || namespace === choice.scope);
    }).map((entry) => ({ ...entry, namespace: entry.id.split(":", 1)[0], saleCategories: [...(categoryIndex.get(entry.id) || [])] }));
  } else if (choice.kind === "reward_item") {
    rows = (state.editorCatalog.rewardItems || []).filter((entry) => matches(entry.id, entry.shortId, entry.name, entry.englishName, entry.tags?.join(" ")) && (!choice.scope || entry.namespace === choice.scope));
  } else if (choice.kind === "bag_item") {
    rows = (state.editorCatalog.bagItems || []).filter((entry) => matches(entry.id, entry.shortId, entry.name, entry.englishName, entry.description) && (choice.scope === "all" || entry.category === choice.scope));
  } else if (["trainer_reference", "trainer_reference_create"].includes(choice.kind)) {
    const recommendedId = choice.kind === "trainer_reference" ? currentRosterReferenceEntryId() : "";
    rows = (state.trainerReferences.entries || []).filter((entry) => {
      const pokemon = (entry.battle?.team || []).map((member) => member.species).join(" ");
      return matches(entry.id, entry.source_label, entry.category, entry.name, entry.entry_number, entry.trainer_type, entry.primary_type, pokemon)
        && (choice.scope === "all" || entry.source === choice.scope)
        && (!choice.category || entry.category === choice.category);
    }).sort((left, right) => Number(right.id === recommendedId) - Number(left.id === recommendedId));
  } else {
    rows = (state.editorCatalog.items || []).filter((entry) => !["mega", "z"].includes(entry.category) && matches(entry.id, entry.shortId, entry.name, entry.englishName, entry.description) && (choice.scope === "all" || (choice.scope === "battle" && entry.battleUsable) || entry.category === choice.scope) && (!choice.category || entry.namespace === choice.category));
  }
  $("#choice-count").textContent = `검색 결과 ${rows.length}개 · 전체 표시`;
  $("#choice-results").className = "choice-results";
  const optionalCard = choice.kind === "trainer_reference_create" ? '<button type="button" class="choice-card optional-choice-card" data-choice-value=""><span class="choice-card-title"><strong>빈 프리셋으로 시작</strong><small>EMPTY PRESET</small></span><p>기존 엔트리를 복사하지 않고 빈 배틀 프리셋을 만듭니다.</p></button>' : choice.kind === "reward_item" && choice.allowEmpty ? '<button type="button" class="choice-card optional-choice-card" data-choice-value=""><span class="choice-card-title"><strong>아이템 지급 안 함</strong><small>OPTIONAL</small></span><p>승리 보상에서 아이템 지급을 제외합니다.</p></button>' : optionalChoiceCard(choice.kind);
  const resultCards = rows.map((entry) => choiceCard(choice.kind === "trainer_reference_create" ? "trainer_reference" : choice.kind, entry, selectedSpecies)).join("");
  $("#choice-results").innerHTML = optionalCard + (resultCards || '<div class="choice-empty">조건에 맞는 항목이 없습니다.</div>');
  bindChoiceResultButtons();
  if (choice.kind === "pokemon") hydrateChoicePokemonArt(rows);
  else if (["trainer_reference", "trainer_reference_create"].includes(choice.kind)) hydrateTrainerReferencePartyArt();
}

function optionalChoiceCard(kind) {
  const labels = { nature: ["자동 결정", "성격을 지정하지 않습니다."], ability: ["자동 특성", "종과 폼의 기본 규칙에 맡깁니다."], item: ["지닌 도구 없음", "이 포켓몬의 지닌 도구를 비웁니다."] };
  const option = labels[kind];
  return option ? `<button type="button" class="choice-card optional-choice-card" data-choice-value=""><span class="choice-card-title"><strong>${option[0]}</strong><small>OPTIONAL</small></span><p>${option[1]}</p></button>` : "";
}

function choiceCard(kind, entry, selectedSpecies) {
  if (kind === "trainer_reference") {
    const number = entry.entry_number ? `#${entry.entry_number}` : "기본";
    const party = entry.battle?.team || [];
    const partyPreview = party.length ? `<span class="trainer-reference-party">${party.map((member) => {
      const label = speciesLabel(member.species);
      return `<span class="trainer-reference-party-member" title="${escapeHtml(label)} · Lv.${escapeHtml(member.level ?? "-")}"><span class="trainer-reference-party-art"><img loading="lazy" decoding="async" data-reference-party-art data-species="${escapeHtml(member.species || "")}" data-form="${escapeHtml(member.form || "")}" data-shiny="${member.shiny ? "true" : "false"}" alt="${escapeHtml(label)}" hidden><b data-reference-party-fallback>${escapeHtml(label.slice(0, 1).toUpperCase() || "?")}</b></span><small>${escapeHtml(label)}</small><em>Lv.${escapeHtml(member.level ?? "-")}</em></span>`;
    }).join("")}</span>` : '<p class="trainer-reference-party-empty">포켓몬 팀 없음</p>';
    const recommended = entry.id === currentRosterReferenceEntryId();
    return `<button type="button" class="choice-card trainer-entry-choice-card${recommended ? " recommended" : ""}" data-choice-value="${escapeHtml(entry.id)}"><span class="choice-card-title"><strong>${escapeHtml(entry.name)} <span class="entry-number">${escapeHtml(number)}</span></strong><small>${recommended ? "명단 기본 · " : ""}${escapeHtml(entry.source_label)}</small></span><span class="choice-tags"><b>${escapeHtml(entry.category)}</b>${entry.primary_type ? `<b>${escapeHtml(entry.primary_type)}</b>` : ""}<b>${entry.team_size}마리</b><b>Lv.${entry.min_level}-${entry.max_level}</b></span>${partyPreview}</button>`;
  }
  if (kind === "pokemon") return `<button type="button" class="choice-card pokemon-choice-card" data-choice-value="${escapeHtml(entry.id)}"><span class="choice-art"><img loading="lazy" decoding="async" data-choice-art="${escapeHtml(entry.id)}" alt="" hidden><b data-choice-art-fallback="${escapeHtml(entry.id)}">●</b></span><span><span class="choice-card-title"><strong>${escapeHtml(pokemonCatalogDisplayName(entry))}</strong><small>#${entry.number}</small></span><span class="choice-tags">${entry.types.map((type) => `<b class="move-type-badge type-${escapeHtml(toId(type))}">${escapeHtml(pokemonTypeNames[type] || type)}</b>`).join("")}${entry.forme ? `<b class="form">${escapeHtml(pokemonFormLabel(entry.forme))}</b>` : ""}${specialForm(entry) ? '<b class="special">특수 형태</b>' : ""}</span><small>HP ${entry.baseStats.hp} · 공 ${entry.baseStats.atk} · 방 ${entry.baseStats.def} · 특공 ${entry.baseStats.spa} · 특방 ${entry.baseStats.spd} · 스피드 ${entry.baseStats.spe}</small><p>${escapeHtml(pokemonCatalogDescription(entry))}</p></span></button>`;
  if (kind === "economy_item") return `<button type="button" class="choice-card economy-item-choice-card" data-choice-value="${escapeHtml(entry.id)}"><span class="choice-card-title"><strong>${escapeHtml(entry.ko_kr || entry.en_us || entry.id)}</strong><small>${economyStandardPrice(entry.id) ? `표준 ${escapeHtml(economyStandardPrice(entry.id))}원` : escapeHtml(entry.namespace)}</small></span><p>${escapeHtml(entry.en_us || entry.id)}</p><span class="choice-tags">${(entry.saleCategories || []).map((category) => `<b>${escapeHtml(category)}</b>`).join("") || '<b>미분류</b>'}</span><code>${escapeHtml(entry.id)}</code></button>`;
  if (kind === "reward_item") return `<button type="button" class="choice-card reward-item-choice-card" data-choice-value="${escapeHtml(entry.id)}"><span class="choice-card-title"><strong>${escapeHtml(entry.name || entry.id)}</strong><small>${escapeHtml(entry.namespace)}</small></span>${entry.englishName && entry.englishName !== entry.name ? `<p>${escapeHtml(entry.englishName)}</p>` : ""}<code>${escapeHtml(entry.id)}</code></button>`;
  if (kind === "move") return `<button type="button" class="choice-card" data-choice-value="${escapeHtml(entry.id)}"><span class="choice-card-title"><strong>${escapeHtml(entry.name)}</strong><b class="move-type-badge type-${escapeHtml(toId(entry.type))}">${escapeHtml(pokemonTypeNames[entry.type] || entry.type)}</b></span><small>${escapeHtml(entry.category)} · 위력 ${entry.power || "—"} · 명중 ${entry.accuracy === true ? "필중" : entry.accuracy} · PP ${entry.pp}</small><p>${escapeHtml(entry.description || entry.englishName)}</p></button>`;
  const allowed = kind === "ability" && selectedSpecies?.abilities?.includes(entry.id);
  return `<button type="button" class="choice-card" data-choice-value="${escapeHtml(entry.id)}"><span class="choice-card-title"><strong>${escapeHtml(entry.name)}</strong><small>${escapeHtml(entry.namespace || entry.id)}</small></span><p>${escapeHtml(entry.description || entry.englishName || "설명 없음")}</p><span class="choice-tags">${allowed ? '<b>사용 가능</b>' : ""}${entry.category ? `<b>${escapeHtml(entry.category)}</b>` : ""}</span></button>`;
}

function bindChoiceResultButtons() {
  $$('[data-choice-value]').forEach((button) => button.addEventListener("click", () => chooseDialogValue(button.dataset.choiceValue)));
}

function hydrateChoicePokemonArt(entries) {
  for (const entry of entries) {
    const pokemon = { species: speciesResourceId(entry), form: entry.forme ? entry.id : null };
    const image = document.querySelector(`[data-choice-art="${CSS.escape(entry.id)}"]`);
    const fallback = document.querySelector(`[data-choice-art-fallback="${CSS.escape(entry.id)}"]`);
    applyPokemonArtwork(image, fallback, pokemon);
  }
}

let trainerReferencePartyArtObserver = null;

function hydrateTrainerReferencePartyArt() {
  trainerReferencePartyArtObserver?.disconnect();
  const loadArtwork = (image) => {
    const fallback = image.parentElement.querySelector('[data-reference-party-fallback]');
    applyPokemonArtwork(image, fallback, {
      species: image.dataset.species,
      form: image.dataset.form || null,
      shiny: image.dataset.shiny === "true",
    });
  };
  if (!("IntersectionObserver" in window)) {
    $$('[data-reference-party-art]').forEach(loadArtwork);
    return;
  }
  trainerReferencePartyArtObserver = new IntersectionObserver((entries, observer) => {
    entries.filter((entry) => entry.isIntersecting).forEach((entry) => {
      observer.unobserve(entry.target);
      loadArtwork(entry.target);
    });
  }, { root: $("#choice-results"), rootMargin: "180px 0px" });
  $$('[data-reference-party-art]').forEach((image) => trainerReferencePartyArtObserver.observe(image));
}

function chooseDialogValue(value) {
  const choice = state.choice;
  if (choice?.kind === "reward_item") {
    const input = document.getElementById(choice.targetId);
    if (!input || (!value && !choice.allowEmpty)) return;
    input.value = value;
    input.dispatchEvent(new Event("change", { bubbles: true }));
    closeChoiceDialog();
    return;
  }
  if (choice?.kind === "economy_item") {
    const editable = editableEconomyEntry("shop", choice.vendorId);
    const sourceCategory = editable?.entry.categories?.[choice.categoryIndex];
    const offer = sourceCategory?.offers?.[choice.offerIndex];
    if (!editable || !sourceCategory || !offer || !value) return;
    offer.item = value;
    const standardPrice = economyStandardPrice(value);
    if (standardPrice) offer.price = standardPrice;
    if (choice.category && choice.category !== economyText(sourceCategory.name)) {
      sourceCategory.offers.splice(choice.offerIndex, 1);
      let destination = editable.entry.categories.find((category) => economyText(category.name) === choice.category);
      if (!destination) { destination = { name: economyLocalized(choice.category, choice.category), offers: [] }; editable.entry.categories.push(destination); }
      destination.offers.push(offer);
      editable.entry.categories = editable.entry.categories.filter((category) => (category.offers || []).length);
    }
    syncResolvedEconomyEntry("shop", editable.entry);
    closeChoiceDialog();
    renderEconomy();
    return;
  }
  if (choice?.kind === "trainer_reference_create") {
    const reference = (state.trainerReferences.entries || []).find((entry) => entry.id === value);
    const form = $("#create-form");
    form.elements.referenceId.value = reference?.id || "";
    $("#create-reference-name").value = reference ? `${reference.name}${reference.entry_number ? ` #${reference.entry_number}` : ""}` : "빈 프리셋으로 시작";
    $("#create-reference-summary").textContent = reference ? `${reference.source_label} · ${reference.category} · ${reference.team_size}마리 · Lv.${reference.min_level}-${reference.max_level}` : "엔트리를 선택하면 팀·AI·규칙·가방을 새 프리셋에 복사합니다.";
    closeChoiceDialog();
    return;
  }
  if (choice?.kind === "trainer_reference") {
    const reference = (state.trainerReferences.entries || []).find((entry) => entry.id === value);
    if (!reference || !state.battlePreset) return;
    const trainerId = trainerBattle()?.trainer_id || "cobbleventure:trainer/imported";
    const importedBattle = structuredClone(reference.battle);
    importedBattle.trainer_id = trainerId;
    state.battlePreset.battle = importedBattle;
    state.selectedPokemonIndex = 0;
    closeChoiceDialog();
    renderBattlePreset();
    toast(`${reference.name}${reference.entry_number ? ` #${reference.entry_number}` : ""} 엔트리를 적용했습니다. 저장 전까지 원본 파일은 변경되지 않습니다.`);
    return;
  }
  if (choice?.kind === "bag_item") {
    const entry = trainerBattle()?.bag?.[choice.bagIndex];
    if (!entry || !value) return;
    entry.item = value;
    closeChoiceDialog();
    renderBag();
    syncTrainerJson();
    return;
  }
  const pokemon = currentPokemon();
  if (!choice || !pokemon) return;
  if (choice.kind === "pokemon") {
    const entry = state.editorCatalog.species.find((candidate) => candidate.id === value);
    if (!entry) return;
    pokemon.species = speciesResourceId(entry);
    pokemon.form = entry.forme ? entry.id : null;
    pokemon.aspects = pokemon.aspects || [];
    if (!pokemon.ability) pokemon.ability = entry.abilities?.[0] || null;
    normalizePokemonGimmick(pokemon);
  } else if (choice.kind === "nature") pokemon.nature = value;
  else if (choice.kind === "ability") pokemon.ability = value;
  else if (choice.kind === "item") {
    pokemon.held_item = value;
    pokemon.gimmick = null;
  }
  else if (choice.kind === "move") {
    const moves = Array.from({ length: 4 }, (_, index) => pokemon.moves?.[index] || "");
    moves[choice.moveIndex] = value;
    pokemon.moves = moves.filter(Boolean);
    normalizePokemonGimmick(pokemon);
  }
  closeChoiceDialog();
  renderTeam();
  syncTrainerJson();
}

function closeChoiceDialog() {
  state.choice = null;
  $("#choice-dialog").close();
}

function evTotal(pokemon) {
  return Object.values(pokemon.evs || {}).reduce((sum, value) => sum + (Number(value) || 0), 0);
}

function clampInteger(value, minimum, maximum) {
  const number = Number(value);
  if (!Number.isFinite(number)) return minimum;
  return Math.min(maximum, Math.max(minimum, Math.trunc(number)));
}

function normalizePokemonStats(pokemon) {
  pokemon.ivs ||= {};
  pokemon.evs ||= {};
  let remainingEvs = 510;
  for (const key of pokemonStatKeys) {
    pokemon.ivs[key] = clampInteger(pokemon.ivs[key], 0, 31);
    pokemon.evs[key] = Math.min(clampInteger(pokemon.evs[key], 0, 252), remainingEvs);
    remainingEvs -= pokemon.evs[key];
  }
}

function speciesLabel(species) {
  return String(species || "포켓몬").replace(/^.*:/, "").replaceAll("_", " ");
}

function pokemonDisplayName(pokemon) {
  const entry = catalogSpeciesForPokemon(pokemon);
  return entry ? pokemonCatalogDisplayName(entry) : speciesLabel(pokemon?.species);
}

function pokemonPokedexNumber(pokemon) {
  const catalogEntry = catalogSpeciesForPokemon(pokemon);
  const number = Number(catalogEntry?.number);
  return Number.isInteger(number) && number > 0 ? number : null;
}

const pokemonArtworkCache = new Map();

function basePokemonArtworkUrls(pokemon) {
  const number = pokemonPokedexNumber(pokemon);
  if (!number) return [];
  const root = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon";
  const shiny = pokemon?.shiny ? "shiny/" : "";
  return [
    `${root}/other/official-artwork/${shiny}${number}.png`,
    `${root}/${shiny}${number}.png`,
  ];
}

function pokeApiSpeciesSlug(entry) {
  return String(entry?.englishName || entry?.id || "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

async function pokemonArtworkUrls(pokemon) {
  const entry = catalogSpeciesForPokemon(pokemon);
  if (!entry?.forme) return basePokemonArtworkUrls(pokemon);
  const slug = pokeApiSpeciesSlug(entry);
  const cacheKey = `${slug}:${pokemon?.shiny ? "shiny" : "normal"}`;
  if (pokemonArtworkCache.has(cacheKey)) return pokemonArtworkCache.get(cacheKey);
  const request = fetch(`https://pokeapi.co/api/v2/pokemon/${encodeURIComponent(slug)}`)
    .then((response) => {
      if (!response.ok) throw new Error(`PokeAPI ${response.status}`);
      return response.json();
    })
    .then((data) => {
      const official = data.sprites?.other?.["official-artwork"];
      return pokemon?.shiny
        ? [official?.front_shiny, data.sprites?.front_shiny].filter(Boolean)
        : [official?.front_default, data.sprites?.front_default].filter(Boolean);
    })
    .catch(() => []);
  pokemonArtworkCache.set(cacheKey, request);
  return request;
}

async function applyPokemonArtwork(image, fallback, pokemon) {
  if (!image) return;
  const requestId = `${pokemon?.species || ""}:${pokemon?.form || ""}:${pokemon?.shiny ? "shiny" : "normal"}`;
  image.dataset.artRequest = requestId;
  image.hidden = true;
  image.removeAttribute("src");
  if (fallback) {
    fallback.hidden = false;
    const message = fallback.querySelector?.("span");
    if (message) message.textContent = "이미지 불러오는 중";
  }
  const urls = await pokemonArtworkUrls(pokemon);
  if (image.dataset.artRequest !== requestId) return;
  let stage = 0;
  const showFallback = () => {
    image.hidden = true;
    image.removeAttribute("src");
    if (fallback) {
      fallback.hidden = false;
      const message = fallback.querySelector?.("span");
      if (message) message.textContent = "이미지 없음";
    }
  };
  const loadNext = () => {
    if (stage >= urls.length) {
      showFallback();
      return;
    }
    image.src = urls[stage++];
  };
  if (!urls.length) {
    showFallback();
    return;
  }
  image.hidden = false;
  if (fallback) fallback.hidden = true;
  image.onload = () => {
    image.hidden = false;
    if (fallback) fallback.hidden = true;
  };
  image.onerror = loadNext;
  loadNext();
}

function hydrateFocusedPokemonArt() {
  const pokemon = trainerBattle()?.team?.[state.selectedPokemonIndex];
  if (!pokemon) return;
  const image = $("#focused-pokemon-art");
  const fallback = $("#pokemon-art-fallback");
  applyPokemonArtwork(image, fallback, pokemon);
}

function hydratePartyArt() {
  (trainerBattle()?.team || []).forEach((pokemon, index) => {
    const image = document.querySelector(`[data-party-art="${index}"]`);
    const fallback = document.querySelector(`[data-party-fallback="${index}"]`);
    applyPokemonArtwork(image, fallback, pokemon);
  });
}

function syncTrainerJson() {
  if (state.trainer && $("#trainer-json")) $("#trainer-json").value = JSON.stringify(state.trainer, null, 2);
  if (state.battlePreset && $("#battle-json")) $("#battle-json").value = JSON.stringify(state.battlePreset, null, 2);
}

function setFormValue(form, name, value) {
  const input = form.elements[name];
  if (input.type === "checkbox") input.checked = Boolean(value);
  else input.value = value ?? "";
}

function csvValues(value) {
  return String(value || "").split(",").map((entry) => entry.trim()).filter(Boolean);
}

function choiceOptions(values, selected = "", includeEmpty = false) {
  const options = includeEmpty ? '<option value="">선택 안 함</option>' : "";
  const knownSelected = values.some(([id]) => id === selected);
  const preservedSelected = selected && !knownSelected
    ? `<option value="${escapeHtml(selected)}" selected>현재 값 · ${escapeHtml(selected)}</option>`
    : "";
  return options + preservedSelected + values.map(([id, label]) => `<option value="${escapeHtml(id)}" ${id === selected ? "selected" : ""}>${escapeHtml(label)}</option>`).join("");
}

function profileOptions(selected = "", includeEmpty = true) {
  const values = state.biomeCatalog.profiles.map((profile) => [profile.id, profile.display_name?.ko_kr || profile.id]);
  if (!values.length) return '<option value="">프로필 데이터 없음 — 서버를 다시 시작하세요</option>';
  return choiceOptions(values, selected, includeEmpty);
}

function pokemonResultHtml(payload) {
  const entries = payload?.pokemon || [];
  if (!entries.length) return '<div class="issues empty">조건에 맞는 포켓몬이 없습니다.</div>';
  return `<div class="result-caption">${entries.length.toLocaleString()}마리</div><div class="pokemon-result-grid">${entries.slice(0, 300).map((entry) => `<article class="habitat-pokemon-card"><b>#${String(entry.dex_number).padStart(4, "0")} ${escapeHtml(entry.display_name?.ko_kr || entry.slug)}</b><small>${escapeHtml(entry.id)} · ${entry.generation}세대</small><span>${escapeHtml(entry.habitats?.primary || "-")} · ${escapeHtml(entry.preferences?.rarity || "-")}${entry.match_reason === "unconditional" ? " · 조건 무시" : ""}</span></article>`).join("")}</div>${entries.length > 300 ? '<div class="result-caption">성능을 위해 앞의 300마리만 표시합니다.</div>' : ""}`;
}

function renderBiomeManager() {
  const profiles = state.biomeCatalog.profiles || [];
  if (!profiles.length) {
    $("#biome-profile-list").innerHTML = '<div class="issues">서식지 프로필을 불러오지 못했습니다. 콘텐츠 관리자 서버를 다시 시작한 뒤 새로고침하세요.</div>';
    $("#biome-profile-form").elements.profileId.innerHTML = '<option value="">프로필 데이터 없음</option>';
    $("#biome-set-select").innerHTML = '<option value="">바이옴 세트 데이터 없음</option>';
    return;
  }
  state.selectedBiomeProfile ||= profiles[0].id;
  const form = $("#biome-profile-form");
  form.elements.profileId.innerHTML = profileOptions(state.selectedBiomeProfile, false);
  form.elements.habitat.innerHTML = choiceOptions(biomeChoices.habitat);
  for (const key of ["temperature", "humidity", "weather", "time"]) form.elements[key].innerHTML = choiceOptions(biomeChoices[key]);
  $("#biome-profile-list").innerHTML = profiles.map((profile) => `<button class="document-button ${profile.id === state.selectedBiomeProfile ? "is-active" : ""}" data-profile-id="${escapeHtml(profile.id)}"><strong>${escapeHtml(profile.display_name?.ko_kr || profile.id)}</strong><small>${escapeHtml(profile.habitat)} · ${escapeHtml(profile.id)}</small></button>`).join("");
  renderSelectedBiomeProfile();
  const sets = state.biomeCatalog.sets || [];
  $("#biome-set-select").innerHTML = sets.map((entry) => `<option value="${escapeHtml(entry.id)}">${escapeHtml(entry.display_name?.ko_kr || entry.id)}</option>`).join("");
  renderBiomeSet();
  $("#habitat-generation-filter").innerHTML = '<option value="0">전체</option>' + Array.from({ length: 9 }, (_, index) => `<option value="${index + 1}">${index + 1}세대</option>`).join("");
  $("#habitat-filter").innerHTML = choiceOptions(biomeChoices.habitat, "", true);
  renderHabitatPokemon();
  $("#biome-issues").className = "issues empty";
  $("#biome-issues").textContent = `${profiles.length}개 프로필 · ${sets.length}개 세트 · ${state.pokemonHabitats.length.toLocaleString()}마리`;
  $$('[data-profile-id]').forEach((button) => button.addEventListener("click", () => { state.selectedBiomeProfile = button.dataset.profileId; renderBiomeManager(); }));
}

function renderSelectedBiomeProfile() {
  const profile = state.biomeCatalog.profiles.find((entry) => entry.id === state.selectedBiomeProfile);
  if (!profile) return;
  const form = $("#biome-profile-form");
  if (!form.elements.defaultWeather) {
    const label = document.createElement("label");
    label.innerHTML = '<span>지역 기본 날씨</span><select name="defaultWeather"><option value="inherit">월드 날씨 상속</option><option value="clear">맑음</option><option value="rain">비</option><option value="thunder">뇌우</option><option value="snow">눈</option><option value="fog">안개</option></select>';
    form.elements.habitat.closest("label").after(label);
    form.elements.weather.closest("label").querySelector("span").textContent = "포켓몬 출현 날씨 조건";
  }
  setFormValue(form, "profileId", profile.id); setFormValue(form, "nameKo", profile.display_name?.ko_kr);
  setFormValue(form, "habitat", profile.habitat); setFormValue(form, "defaultWeather", profile.weather || "inherit"); setFormValue(form, "generation", profile.settings?.generation ?? 0);
  setFormValue(form, "habitatVariant", profile.settings?.habitat_variant ?? 0);
  setFormValue(form, "series", profile.settings?.series || "");
  for (const key of ["temperature", "humidity", "weather", "time"]) setFormValue(form, key, profile.settings?.[key] || "any");
  setFormValue(form, "rarities", (profile.settings?.rarities || []).join(", "));
  setFormValue(form, "forced", (profile.forced_includes || []).join(", ")); setFormValue(form, "excluded", (profile.excluded_pokemon || []).join(", "));
  setFormValue(form, "secondary", profile.settings?.include_secondary ?? true);
}

function updateBiomeProfileFromForm() {
  const form = $("#biome-profile-form");
  const profile = state.biomeCatalog.profiles.find((entry) => entry.id === form.elements.profileId.value);
  if (!profile) return;
  profile.display_name = { ...(profile.display_name || {}), ko_kr: form.elements.nameKo.value.trim() };
  profile.habitat = form.elements.habitat.value;
  profile.weather = form.elements.defaultWeather.value;
  profile.settings = { generation: Number(form.elements.generation.value), habitat_variant: Number(form.elements.habitatVariant.value), temperature: form.elements.temperature.value, humidity: form.elements.humidity.value, weather: form.elements.weather.value, time: form.elements.time.value, rarities: csvValues(form.elements.rarities.value), include_secondary: form.elements.secondary.checked };
  if (form.elements.series.value) profile.settings.series = form.elements.series.value;
  profile.forced_includes = csvValues(form.elements.forced.value); profile.excluded_pokemon = csvValues(form.elements.excluded.value);
}

function addBiomeProfile() {
  const slug = prompt("새 바이옴의 파일 ID를 입력하세요.\n예: crystal_cave");
  if (slug === null) return;
  const normalized = slug.trim().toLowerCase();
  if (!/^[a-z0-9][a-z0-9_]*$/.test(normalized)) { toast("소문자, 숫자와 밑줄로 된 ID를 입력해 주세요."); return; }
  const id = `cobbleventure:biome_profile/${normalized}`;
  if (state.biomeCatalog.profiles.some((profile) => profile.id === id)) { toast("같은 ID의 바이옴이 이미 있습니다."); return; }
  state.biomeCatalog.profiles.push({
    id,
    display_name: { ko_kr: normalized },
    habitat: "plains",
    weather: "inherit",
    minecraft_biomes: ["minecraft:plains"],
    settings: { generation: 0, habitat_variant: 0, temperature: "any", humidity: "any", weather: "any", time: "any", rarities: ["common", "medium", "uncommon", "rare"], include_secondary: true },
    forced_includes: [],
    excluded_pokemon: []
  });
  state.selectedBiomeProfile = id;
  renderBiomeManager();
  toast("새 바이옴을 추가했습니다. 설정을 확인한 뒤 바이옴 저장을 눌러 주세요.");
}

function deleteBiomeProfile() {
  updateBiomeProfileFromForm();
  const profile = state.biomeCatalog.profiles.find((entry) => entry.id === state.selectedBiomeProfile);
  if (!profile) return;
  const referencingSets = (state.biomeCatalog.sets || []).filter((biomeSet) => biomeSet.profiles?.some((entry) => entry.profile === profile.id));
  if (referencingSets.length) {
    toast(`바이옴 세트에서 사용 중이라 삭제할 수 없습니다: ${referencingSets.map((entry) => entry.display_name?.ko_kr || entry.id).join(", ")}`);
    return;
  }
  const name = profile.display_name?.ko_kr || profile.id;
  if (!confirm(`'${name}' 바이옴을 삭제할까요?\n바이옴 저장 전까지 파일에는 반영되지 않습니다.`)) return;
  const index = state.biomeCatalog.profiles.indexOf(profile);
  state.biomeCatalog.profiles.splice(index, 1);
  state.selectedBiomeProfile = state.biomeCatalog.profiles[Math.min(index, state.biomeCatalog.profiles.length - 1)]?.id || null;
  renderBiomeManager();
  toast("바이옴을 목록에서 삭제했습니다. 바이옴 저장을 눌러 확정해 주세요.");
}

function renderBiomeSet() {
  const biomeSet = state.biomeCatalog.sets.find((entry) => entry.id === $("#biome-set-select").value) || state.biomeCatalog.sets[0];
  if (!biomeSet) return;
  $("#biome-set-select").value = biomeSet.id;
  $("#biome-set-unconditional").value = (biomeSet.unconditional_spawns || []).join(", ");
  $("#biome-set-profiles").innerHTML = biomeSet.profiles.map((item, index) => { const profile = state.biomeCatalog.profiles.find((entry) => entry.id === item.profile); return `<label><span>${escapeHtml(profile?.display_name?.ko_kr || item.profile)}</span><input type="number" min="1" max="100" value="${Number(item.weight || 1)}" data-set-weight="${index}"></label>`; }).join("");
}

function updateBiomeSet() {
  const biomeSet = state.biomeCatalog.sets.find((entry) => entry.id === $("#biome-set-select").value);
  if (!biomeSet) return;
  biomeSet.unconditional_spawns = csvValues($("#biome-set-unconditional").value);
  $$('[data-set-weight]').forEach((input) => { biomeSet.profiles[Number(input.dataset.setWeight)].weight = Number(input.value); });
}

async function runBiomePreview(payload, target) {
  const result = await request("/api/biome-preview", { method: "POST", body: JSON.stringify(payload) });
  $(target).innerHTML = result.ok ? pokemonResultHtml(result.data) : `<div class="issues">${escapeHtml(result.data.error || "미리보기에 실패했습니다.")}</div>`;
}

async function previewBiomeProfile() {
  updateBiomeProfileFromForm();
  const profile = state.biomeCatalog.profiles.find((entry) => entry.id === state.selectedBiomeProfile);
  await runBiomePreview({ profile_id: profile.id, profile, settings: profile.settings }, "#biome-profile-preview");
}

async function testBiomeSet() {
  updateBiomeSet();
  await runBiomePreview({ set_id: $("#biome-set-select").value, unconditional_spawns: csvValues($("#biome-set-unconditional").value) }, "#biome-set-preview");
}

async function saveBiomeCatalog() {
  updateBiomeProfileFromForm(); updateBiomeSet();
  const result = await request("/api/biome-catalog", { method: "PUT", body: JSON.stringify(state.biomeCatalog) });
  showIssues("#biome-issues", result.data);
  toast(result.ok ? "바이옴 프로필과 세트를 저장했습니다." : "바이옴 설정을 확인해 주세요.");
}

function renderHabitatPokemon() {
  const query = $("#habitat-pokemon-search").value.trim().toLowerCase();
  const generation = Number($("#habitat-generation-filter").value || 0);
  const habitat = $("#habitat-filter").value;
  const entries = state.pokemonHabitats.filter((entry) => (!generation || entry.generation === generation) && (!habitat || entry.habitats?.primary === habitat || entry.habitats?.secondary === habitat) && (!query || `${entry.id} ${entry.slug} ${entry.display_name?.ko_kr} ${entry.display_name?.en_us}`.toLowerCase().includes(query)));
  $("#habitat-pokemon-count").textContent = `${entries.length.toLocaleString()} / ${state.pokemonHabitats.length.toLocaleString()}마리`;
  $("#habitat-pokemon-list").innerHTML = pokemonResultHtml({ pokemon: entries });
}

function leagueRoleLabel(role) {
  return ({ gym_leader: "체육관 관장", elite_four: "사천왕", champion: "챔피언" })[role] || role;
}

function badgeById(id) {
  return (state.badgeCatalog.badges || []).find((badge) => badge.id === id) || null;
}

function trainerById(id) {
  return (state.trainers || []).find((trainer) => trainer.id === id) || null;
}

function gymForLeagueEntry(entryId) {
  return (state.gymCatalog.gyms || []).find((gym) => gym.staff?.leader?.league_entry_id === entryId) || null;
}

function leagueEntryForGym(gym) {
  const entryId = gym?.staff?.leader?.league_entry_id;
  return (state.leagueProgression.entries || []).find((entry) => entry.id === entryId) || null;
}

function badgeSprite(badge, scale = 2) {
  if (!badge) return '<span class="badge-sprite is-empty"></span>';
  const icon = badge.icon || {};
  const atlas = state.badgeCatalog.atlas || { width: 256, height: 288, tile_size: 32 };
  const size = Number(icon.size || atlas.tile_size || 32) * scale;
  return `<span class="badge-sprite" style="width:${size}px;height:${size}px;background-size:${atlas.width * scale}px ${atlas.height * scale}px;background-position:-${Number(icon.u || 0) * scale}px -${Number(icon.v || 0) * scale}px" title="${escapeHtml(badge.tooltip?.ko_kr || badge.display_name?.ko_kr || badge.id)}"></span>`;
}

function badgeOptions(selected = "") {
  const badges = state.badgeCatalog.badges || [];
  return '<option value="">배지 선택</option>' + badges.map((badge) => `<option value="${escapeHtml(badge.id)}"${badge.id === selected ? " selected" : ""}>${badge.generation}세대 ${badge.order}번째 · ${escapeHtml(badge.display_name?.ko_kr || badge.id)} · ${escapeHtml(badge.leader_name?.ko_kr || "")}</option>`).join("");
}

function orderedTrainerCardEntries(visibleOnly = false) {
  return (state.leagueProgression.entries || []).filter((entry) => entry.role === "gym_leader" && gymForLeagueEntry(entry.id));
}

function setTrainerCardEntryVisible(entryId, visible) {
  const entry = (state.leagueProgression.entries || []).find((candidate) => candidate.id === entryId && candidate.role === "gym_leader");
  if (!entry) return;
  entry.trainer_card_visible = visible;
  renderTrainerCardManager();
}

function renderTrainerCardManager() {
  const filter = Number($("#badge-generation-filter")?.value || 0);
  const leaders = orderedTrainerCardEntries().filter((entry) => !filter || entry.generation === filter);
  if ($("#badge-library-count")) $("#badge-library-count").textContent = `${leaders.length}명`;
  if ($("#badge-library-grid")) {
    $("#badge-library-grid").innerHTML = leaders.length ? leaders.map((entry) => {
      const gym = gymForLeagueEntry(entry.id); const badge = badgeById(entry.encounter?.rewards?.badge_id); const visible = true;
      return `<article class="badge-library-card" title="${escapeHtml(badge?.tooltip?.ko_kr || "배지가 지정되지 않은 관장입니다.")}">${badgeSprite(badge)}<div><strong>${escapeHtml(entry.display_name?.ko_kr || entry.id)}</strong><span>${entry.generation}세대 · ${escapeHtml(gym?.display_name?.ko_kr || gym?.id || "체육관")}</span><small>${escapeHtml(badge?.display_name?.ko_kr || "배지 미지정")} · NPC 외형 자동 사용</small><button type="button" class="badge-library-action${visible ? " is-remove" : ""}" data-card-${visible ? "remove" : "add"}="${escapeHtml(entry.id)}">${visible ? "카드에서 제거" : "카드에 추가"}</button></div></article>`;
    }).join("") : '<div class="definition-empty">해당 세대에 등록된 체육관 관장이 없습니다.</div>';
  }
  if ($("#trainer-card-order-list")) {
    const entries = orderedTrainerCardEntries(true);
    if ($("#trainer-card-order-count")) $("#trainer-card-order-count").textContent = `${entries.length}명 · 8명마다 다음 카드 장으로 나뉩니다.`;
    $("#trainer-card-order-list").innerHTML = entries.length ? entries.map((entry, index) => {
      const gym = gymForLeagueEntry(entry.id); const badge = badgeById(entry.encounter?.rewards?.badge_id);
      return `<article class="trainer-card-order-row" data-card-entry="${escapeHtml(entry.id)}">${badgeSprite(badge, 1.25)}<span class="trainer-card-rank">${index + 1}</span><div><strong>${escapeHtml(entry.display_name?.ko_kr || entry.id)}</strong><small>${escapeHtml(badge?.display_name?.ko_kr || "배지 미지정")} · ${entry.generation}세대</small></div><div class="card-order-actions"><button type="button" data-card-move="-1" aria-label="위로">↑</button><button type="button" data-card-move="1" aria-label="아래로">↓</button><button type="button" class="card-remove-button" data-card-remove="${escapeHtml(entry.id)}">제거</button></div></article>`;
    }).join("") : '<div class="definition-empty">카드에 배치된 관장이 없습니다. 왼쪽 관장 목록에서 추가하세요.</div>';
  }
  showIssues("#trainer-card-issues", { valid: true, issues: [] });
}

function moveTrainerCardEntry(entryId, delta) {
  const entry = (state.leagueProgression.entries || []).find((candidate) => candidate.id === entryId);
  if (!entry) return;
  const group = orderedTrainerCardEntries(true).filter((candidate) => candidate.generation === entry.generation && candidate.region === entry.region);
  const index = group.indexOf(entry); const target = group[index + delta];
  if (!target) return;
  const entries = state.leagueProgression.entries || [];
  const sourceIndex = entries.indexOf(entry); const targetIndex = entries.indexOf(target);
  [entries[sourceIndex], entries[targetIndex]] = [entries[targetIndex], entries[sourceIndex]];
  delete entry.trainer_card_order;
  delete target.trainer_card_order;
  renderTrainerCardManager();
}

function selectedLeagueEntry() {
  return (state.leagueProgression.entries || []).find((entry) => entry.id === state.selectedLeagueId) || null;
}

const leagueTypeColors = { normal:"#929da3",fire:"#ff9d55",water:"#5090d6",electric:"#f4d23c",grass:"#63bc5a",ice:"#73cec0",fighting:"#ce416b",poison:"#aa6bc8",ground:"#d97845",flying:"#8fa9de",psychic:"#fa7179",bug:"#91c12f",rock:"#c5b78c",ghost:"#5269ad",dragon:"#0b6dc3",dark:"#5a5465",steel:"#5a8ea2",fairy:"#ec8fe6" };
const leagueTypeLabels = { normal:"노말",fire:"불꽃",water:"물",electric:"전기",grass:"풀",ice:"얼음",fighting:"격투",poison:"독",ground:"땅",flying:"비행",psychic:"에스퍼",bug:"벌레",rock:"바위",ghost:"고스트",dragon:"드래곤",dark:"악",steel:"강철",fairy:"페어리" };

function leagueEntryBadgeId(entry) {
  return entry?.role === "gym_leader" ? entry.encounter?.rewards?.badge_id : entry?.badge_id;
}

function dialogueLinesValue(value) {
  return (Array.isArray(value) ? value : String(value || "").split(/\r?\n/)).filter(Boolean).join("\n");
}

function dialogueLinesFromInput(value) {
  return String(value || "").split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
}

function renderLeagueAppearancePreview() {
  const entry = selectedLeagueEntry();
  const preview = $("#league-appearance-preview");
  if (!entry || entry.role !== "gym_leader" || !preview) return;
  const encounter = entry.encounter || {};
  const character = rosterCharacters().find((item) => item.id === encounter.character);
  const appearance = character ? effectiveCharacterAppearance(character) : (encounter.appearance || {});
  const trainerClass = state.trainerClasses.find((item) => item.id === "cobbleventure:trainer_class/gym_leader");
  const skinUrl = trainerSkinUrl(appearance);
  const body = { ...(character?.body || trainerClass?.body || {}), ...(appearance.arm_model ? { arm_model: appearance.arm_model } : {}) };
  preview.innerHTML = skinUrl ? `<div class="trainer-appearance-comparison"><section class="trainer-reference-card"><span>본가 디자인 기준</span>${trainerReferenceHtml(trainerClass, character)}</section><section class="trainer-minecraft-card"><span>현재 Minecraft 외형</span>${minecraftModelHtml(skinUrl, body)}</section></div><strong>${escapeHtml(entry.display_name?.ko_kr || entry.id)}</strong>` : `<div class="trainer-preview-fallback">관장</div><strong>${escapeHtml(entry.display_name?.ko_kr || entry.id)}</strong>`;
}

function renderLeagueBadgePreviews() {
  const entry = selectedLeagueEntry();
  const victoryBadge = badgeById(entry?.encounter?.rewards?.badge_id);
  const displayBadge = badgeById(leagueEntryBadgeId(entry));
  const badgePreview = (badge, empty) => `${badgeSprite(badge, 1.8)}<span><strong>${escapeHtml(badge?.display_name?.ko_kr || empty)}</strong><small>${escapeHtml(badge?.tooltip?.ko_kr || "선택한 배지가 목록과 카드에 표시됩니다.")}</small></span>`;
  if ($("#league-badge-preview")) $("#league-badge-preview").innerHTML = badgePreview(victoryBadge, "승리 배지를 선택하세요");
  if ($("#league-display-badge-preview")) $("#league-display-badge-preview").innerHTML = badgePreview(displayBadge, "표시 배지를 선택하세요");
}

function renderLeagueList() {
  const entries = state.leagueProgression.entries || [];
  $("#league-entry-count").textContent = entries.length;
  const groups = [["gym_leader", "체육관 관장"], ["elite_four", "사천왕"], ["champion", "챔피언"]];
  $("#league-entry-list").innerHTML = entries.length ? groups.map(([role, label]) => {
    const members = entries.filter((entry) => entry.role === role);
    if (!members.length) return "";
    return `<div class="league-role-heading"><strong>${label}</strong><span>${members.length}</span></div>${members.map((entry, index) => { const type = entry.primary_type || "normal"; const badge = badgeById(leagueEntryBadgeId(entry)); return `<article class="document-button league-member-row ${entry.id === state.selectedLeagueId ? "is-active" : ""}" style="--league-type:${leagueTypeColors[type] || leagueTypeColors.normal}"><button type="button" class="league-member-select" data-league-entry="${escapeHtml(entry.id)}"><span class="league-member-badge">${badgeSprite(badge, 1.05)}</span><span><strong>${escapeHtml(entry.display_name?.ko_kr || entry.id)}</strong><small><b>${escapeHtml(leagueTypeLabels[type] || type)}</b> · ${entry.generation}세대 · ${escapeHtml(entry.region.split("/").at(-1))} · ${entry.order}번째 · Lv.${entry.level_cap}</small></span></button><span class="league-order-actions"><button type="button" data-league-move="-1" data-league-id="${escapeHtml(entry.id)}" aria-label="위로 이동" title="위로 이동"${index === 0 ? " disabled" : ""}>↑</button><button type="button" data-league-move="1" data-league-id="${escapeHtml(entry.id)}" aria-label="아래로 이동" title="아래로 이동"${index === members.length - 1 ? " disabled" : ""}>↓</button></span></article>`; }).join("")}`;
  }).join("") : '<div class="issues empty">등록된 리그 구성원이 없습니다.</div>';
  $$('[data-league-entry]').forEach((button) => button.addEventListener("click", () => { state.selectedLeagueId = button.dataset.leagueEntry; renderLeagueList(); renderLeagueEditor(); }));
  $$('[data-league-move]').forEach((button) => button.addEventListener("click", () => moveLeagueEntry(button.dataset.leagueId, Number(button.dataset.leagueMove))));
}

async function moveLeagueEntry(entryId, delta) {
  const entries = state.leagueProgression.entries || [];
  const entry = entries.find((candidate) => candidate.id === entryId);
  if (!entry) return;
  const members = entries.filter((candidate) => candidate.role === entry.role);
  const memberIndex = members.indexOf(entry); const target = members[memberIndex + delta];
  if (!target) return;
  const sourceIndex = entries.indexOf(entry); const targetIndex = entries.indexOf(target);
  [entries[sourceIndex], entries[targetIndex]] = [entries[targetIndex], entries[sourceIndex]];
  delete entry.trainer_card_order;
  delete target.trainer_card_order;
  renderLeagueList();
  const result = await request("/api/league-progression", { method: "PUT", body: JSON.stringify(state.leagueProgression) });
  showIssues("#league-issues", result.data);
  if (result.ok) {
    toast(`${entry.display_name?.ko_kr || entry.id} 표시 순서를 저장했습니다.`);
    renderTrainerCardManager();
    return;
  }
  [entries[sourceIndex], entries[targetIndex]] = [entries[targetIndex], entries[sourceIndex]];
  renderLeagueList();
  toast("표시 순서를 저장하지 못했습니다. 리그 설정을 확인해 주세요.");
}

function trainerPoolOptions(selected = "") {
  return '<option value="">트레이너풀에서 선택</option>' + state.trainers.map((trainer) => `<option value="${escapeHtml(trainer.id)}"${trainer.id === selected ? " selected" : ""}>${escapeHtml(trainer.name || trainer.id)} · ${escapeHtml(trainer.id)}</option>`).join("");
}

function battlePresetOptions(selected = "") {
  return '<option value="">배틀 프리셋 선택</option>' + state.battles.map((battle) => `<option value="${escapeHtml(battle.id)}"${battle.id === selected ? " selected" : ""}>${escapeHtml(battle.name || battle.id)} · ${escapeHtml(battle.id)}</option>`).join("");
}

function renderLeagueEditor() {
  renderLeagueList();
  const entry = selectedLeagueEntry();
  const form = $("#league-form");
  if (!entry) {
    form.reset();
    [...form.elements].forEach((element) => { element.disabled = true; });
    $("#delete-league-entry").disabled = true; $("#save-league").disabled = true;
    $("#league-editor-title").textContent = "리그 항목을 선택하세요";
    $("#league-card-derived").hidden = true;
    $("#league-encounter-fields").hidden = true;
    $("#league-trainer-link").hidden = true;
    $("#league-display-badge-fields").hidden = true;
    return;
  }
  $("#league-editor-title").textContent = entry.display_name?.ko_kr || entry.id;
  setFormValue(form, "id", entry.id); setFormValue(form, "role", entry.role); setFormValue(form, "levelCap", entry.level_cap);
  setFormValue(form, "primaryType", entry.primary_type || "normal");
  setFormValue(form, "nameKo", entry.display_name?.ko_kr || ""); setFormValue(form, "nameEn", entry.display_name?.en_us || "");
  setFormValue(form, "generation", entry.generation); setFormValue(form, "order", entry.order); setFormValue(form, "region", entry.region);
  form.elements.trainerId.innerHTML = trainerPoolOptions(entry.trainer_id); setFormValue(form, "trainerId", entry.trainer_id);
  const isGymLeader = entry.role === "gym_leader";
  const encounter = entry.encounter || {};
  const rewards = encounter.rewards || {};
  form.elements.battleId.innerHTML = battlePresetOptions(encounter.battle_id || "");
  setFormValue(form, "battleId", encounter.battle_id || "");
  setFormValue(form, "appearanceResource", encounter.appearance?.resource || "");
  setFormValue(form, "appearanceSource", encounter.appearance?.source || "rct_single");
  form.elements.rosterCharacter.innerHTML = rosterCharacterOptions("cobbleventure:trainer_class/gym_leader");
  setFormValue(form, "rosterCharacter", encounter.character || "");
  setFormValue(form, "challengeDialogue", dialogueLinesValue(encounter.dialogue?.challenge));
  setFormValue(form, "victoryDialogue", dialogueLinesValue(encounter.dialogue?.victory));
  setFormValue(form, "defeatDialogue", dialogueLinesValue(encounter.dialogue?.defeat));
  setFormValue(form, "clearedDialogue", dialogueLinesValue(encounter.dialogue?.cleared));
  setFormValue(form, "rewardMoney", rewards.money ?? 0);
  setFormValue(form, "rewardItem", rewards.item || "");
  setFormValue(form, "rewardItemCount", rewards.item_count || 1);
  form.elements.badgeId.innerHTML = badgeOptions(rewards.badge_id || "");
  setFormValue(form, "badgeId", rewards.badge_id || "");
  form.elements.displayBadgeId.innerHTML = badgeOptions(leagueEntryBadgeId(entry) || "");
  setFormValue(form, "displayBadgeId", leagueEntryBadgeId(entry) || "");
  [...form.elements].forEach((element) => { element.disabled = false; });
  $$("#league-form .league-badge-fields input, #league-form .league-badge-fields select").forEach((element) => { element.disabled = true; });
  $("#delete-league-entry").disabled = false; $("#save-league").disabled = false; $("#edit-league-trainer").disabled = !entry.trainer_id;
  $("#league-trainer-link").hidden = isGymLeader;
  $("#league-encounter-fields").hidden = !isGymLeader;
  $("#league-display-badge-fields").hidden = isGymLeader;
  $("#choose-league-reward-item").disabled = !isGymLeader;
  $("#league-card-derived").hidden = !isGymLeader;
  if (isGymLeader) {
    const pageEntries = orderedTrainerCardEntries().filter((candidate) => candidate.generation === entry.generation && candidate.region === entry.region);
    const position = pageEntries.findIndex((candidate) => candidate.id === entry.id);
    $("#league-card-position").textContent = position >= 0 ? `목록 ${position + 1}번째` : "체육관 연결 후 목록에 배치";
  }
  renderLeagueAppearancePreview();
  renderLeagueBadgePreviews();
  showIssues("#league-issues", { valid: true, issues: [] });
}

function updateLeagueEntryFromForm() {
  const entry = selectedLeagueEntry(); if (!entry) return;
  const form = $("#league-form"); const previousId = entry.id;
  entry.id = form.elements.id.value.trim(); entry.role = form.elements.role.value; entry.primary_type = form.elements.primaryType.value;
  entry.display_name = { ko_kr: form.elements.nameKo.value.trim() };
  if (form.elements.nameEn.value.trim()) entry.display_name.en_us = form.elements.nameEn.value.trim();
  entry.generation = Number(form.elements.generation.value); entry.region = form.elements.region.value.trim();
  entry.order = Number(form.elements.order.value); entry.level_cap = Number(form.elements.levelCap.value); entry.trainer_id = form.elements.trainerId.value;
  if (entry.role === "gym_leader") {
    delete entry.trainer_id;
    entry.encounter = {
      battle_id: form.elements.battleId.value,
      appearance: {
        source: form.elements.appearanceSource.value, type: "skin", resource: form.elements.appearanceResource.value.trim(),
      },
      dialogue: {
        challenge: dialogueLinesFromInput(form.elements.challengeDialogue.value),
        victory: dialogueLinesFromInput(form.elements.victoryDialogue.value),
        defeat: dialogueLinesFromInput(form.elements.defeatDialogue.value),
        cleared: dialogueLinesFromInput(form.elements.clearedDialogue.value),
      },
      rewards: {
        money: Number(form.elements.rewardMoney.value || 0),
        badge_id: form.elements.badgeId.value,
      },
    };
    if (form.elements.rosterCharacter.value) entry.encounter.character = form.elements.rosterCharacter.value;
    if (form.elements.rewardItem.value) {
      entry.encounter.rewards.item = form.elements.rewardItem.value;
      entry.encounter.rewards.item_count = Number(form.elements.rewardItemCount.value || 1);
    }
  } else {
    delete entry.encounter;
    if (form.elements.displayBadgeId.value) entry.badge_id = form.elements.displayBadgeId.value;
    else delete entry.badge_id;
  }
  state.selectedLeagueId = entry.id || previousId;
}

function addLeagueEntry() {
  const form = $("#league-member-form");
  form.reset();
  form.elements.generation.value = state.selectedGeneration;
  form.elements.region.value = `cobbleventure:region/generation_${state.selectedGeneration}`;
  form.elements.badgeId.innerHTML = badgeOptions("");
  form.elements.displayBadgeId.innerHTML = badgeOptions("");
  form.elements.rosterCharacter.innerHTML = rosterCharacterOptions("cobbleventure:trainer_class/gym_leader");
  updateLeagueMemberDialog();
  showIssues("#league-member-issues", { valid: true, issues: [] });
  $("#league-member-dialog").showModal();
}

function updateLeagueMemberDialog() {
  const form = $("#league-member-form");
  const role = form.elements.role.value;
  const gymLeader = role === "gym_leader";
  $(".league-member-gym-fields").hidden = !gymLeader;
  $(".league-member-display-badge-fields").hidden = gymLeader;
  form.elements.badgeId.required = gymLeader;
  form.elements.appearanceResource.required = gymLeader;
  if (gymLeader) form.elements.primaryType.value = form.elements.primaryType.value || "normal";
  const roleLabel = leagueRoleLabel(role);
  const outputs = gymLeader
    ? "리그 약식 이벤트 + 배틀 프리셋 + 체육관 연결 · NPC 데이터는 빌드 시 자동 생성"
    : "NPC + 배틀 프리셋 + 표준 승부 이벤트 + 리그 항목";
  $("#league-member-summary").innerHTML = `<strong>${escapeHtml(roleLabel)}</strong><br>${outputs}`;
}

async function createLeagueMember(event) {
  event.preventDefault();
  const form = event.currentTarget;
  if (!form.reportValidity()) return;
  const payload = {
    role: form.elements.role.value,
    slug: form.elements.slug.value.trim(),
    name: form.elements.name.value.trim(),
    name_en: form.elements.nameEn.value.trim(),
    generation: Number(form.elements.generation.value),
    region: form.elements.region.value.trim(),
    order: Number(form.elements.order.value),
    level_cap: Number(form.elements.levelCap.value),
    primary_type: form.elements.primaryType.value,
    theme: form.elements.primaryType.value,
    badge_id: form.elements.role.value === "gym_leader" ? form.elements.badgeId.value : "",
    display_badge_id: form.elements.role.value === "gym_leader" ? "" : form.elements.displayBadgeId.value,
    character: form.elements.role.value === "gym_leader" ? form.elements.rosterCharacter.value : "",
    appearance_resource: form.elements.role.value === "gym_leader" ? form.elements.appearanceResource.value.trim() : "",
    reward_money: form.elements.role.value === "gym_leader" ? Number(form.elements.rewardMoney.value || 0) : 0,
    reward_item: form.elements.role.value === "gym_leader" ? form.elements.rewardItem.value.trim() : "",
    reward_item_count: 1
  };
  const submit = form.querySelector('[type="submit"]');
  submit.disabled = true;
  try {
    const result = await request("/api/league-members/create", { method: "POST", body: JSON.stringify(payload) });
    showIssues("#league-member-issues", result.data);
    if (!result.ok) {
      toast(result.data.error || "리그 구성원 생성 정보를 확인해 주세요.");
      return;
    }
    $("#league-member-dialog").close();
    await loadLists();
    state.selectedLeagueId = result.data.member.league_entry.id;
    renderLeagueEditor();
    toast(`${leagueRoleLabel(payload.role)} ${payload.name} 구성을 한 번에 생성했습니다.`);
  } finally {
    submit.disabled = false;
  }
}

async function saveLeagueProgression() {
  updateLeagueEntryFromForm();
  if (!$("#league-form").reportValidity() && selectedLeagueEntry()) return;
  const result = await request("/api/league-progression", { method: "PUT", body: JSON.stringify(state.leagueProgression) });
  showIssues("#league-issues", result.data);
  toast(result.ok ? "리그 설정과 트레이너카드 구성을 저장했습니다." : "리그 설정을 확인해 주세요.");
  if (result.ok) { await loadLists(); renderLeagueEditor(); }
}

function facilityTrainerOptions(selected = "") {
  return '<option value="">나중에 지정</option>' + state.trainers.map((trainer) =>
    `<option value="${escapeHtml(trainer.id)}"${trainer.id === selected ? " selected" : ""}>${escapeHtml(trainer.name || trainer.id)} · ${escapeHtml(trainer.id)}</option>`
  ).join("");
}

function gymLeagueOptions(selected = "") {
  const leaders = (state.leagueProgression.entries || []).filter((entry) => entry.role === "gym_leader");
  return '<option value="">나중에 지정</option>' + leaders.map((entry) => `<option value="${escapeHtml(entry.id)}"${entry.id === selected ? " selected" : ""}>${escapeHtml(entry.display_name?.ko_kr || entry.id)} · Lv.${entry.level_cap}</option>`).join("");
}

function settlementFacilityRequirements() {
  const requirements = (Array.isArray(state.settlement?.structure_profile?.facility_requirements)
    ? state.settlement.structure_profile.facility_requirements
    : []).filter((item) => !legacyGymFacilityIds.has(item?.id));
  if (!isStarterSettlement() || requirements.some((item) => item.id === "laboratory")) return requirements;
  const laboratory = settlementFacilityCatalog.find((item) => item.id === "laboratory");
  return laboratory ? [...requirements, {
    id: laboratory.id, label: laboratory.label, count: 1, required: true,
    footprint: { width: laboratory.width, depth: laboratory.depth, height: laboratory.height }
  }] : requirements;
}

function isStarterSettlement(document = state.settlement) {
  return Boolean(document?.id?.endsWith("/starter_town"));
}

function renderFacilityOptions() {
  const selected = new Map(settlementFacilityRequirements().map((item) => [item.id, item]));
  $("#facility-option-list").innerHTML = settlementFacilityCatalog.map((facility) => {
    const requirement = selected.get(facility.id);
    const checked = Boolean(requirement?.required);
    const count = Math.max(1, Math.min(8, Number(requirement?.count || 1)));
    return `<article class="facility-option${checked ? " is-selected" : ""}" data-facility-id="${facility.id}">
      <label class="facility-option-toggle"><input class="facility-enabled" type="checkbox"${checked ? " checked" : ""}><span class="facility-swatch" style="--facility-color:${facility.color}"></span><span><strong>${escapeHtml(facility.label)}</strong><small>${escapeHtml(facility.note)}</small></span></label>
      <div class="facility-option-size"><span>${facility.width}×${facility.depth}</span><small>표준 부지</small></div>
      <label class="facility-count"><span>수량</span><input type="number" min="1" max="8" step="1" value="${count}"${checked ? "" : " disabled"}></label>
    </article>`;
  }).join("");
}

function selectedFacilityRequirements() {
  return $$("#facility-option-list [data-facility-id]").flatMap((row) => {
    if (!row.querySelector(".facility-enabled")?.checked) return [];
    const facility = settlementFacilityCatalog.find((item) => item.id === row.dataset.facilityId);
    if (!facility) return [];
    const count = Math.max(1, Math.min(8, Number(row.querySelector(".facility-count input")?.value || 1)));
    const structure = `cobbleventure:placeholder/${facility.id}`;
    const footprint = structureFootprint(structure, facility);
    return [{
      id: facility.id, label: facility.label, count, required: true,
      structure, footprint
    }];
  });
}

function structureFootprint(structure, fallback = {}) {
  const nbt = structure ? state.structureSizes[structure] : null;
  return {
    width: Number(nbt?.width || fallback.width || fallback.footprint?.width || 16),
    depth: Number(nbt?.depth || fallback.depth || fallback.footprint?.depth || 16),
    height: Number(nbt?.height || fallback.height || fallback.footprint?.height || 1),
    occupied: nbt?.occupied || null,
    topView: nbt?.top_view || null,
    nbtResolved: Boolean(nbt),
    source: nbt?.source || ""
  };
}

function minecraftTopBlockColor(blockName) {
  const name = String(blockName || "").split(":").at(-1);
  const colors = [
    [/water|bubble_column/, "#3f76e4"], [/lava/, "#ff6b16"],
    [/grass_block|moss/, "#78a84f"], [/leaves|vine/, "#56893f"],
    [/sandstone|sand/, "#d8c47b"], [/snow|ice/, "#dceff2"],
    [/deepslate|blackstone/, "#45434a"], [/cobblestone|stone|andesite|tuff/, "#818486"],
    [/brick|terracotta/, "#a75d4d"], [/quartz|calcite/, "#e7e1d4"],
    [/copper/, "#b76e4f"], [/iron|anvil|cauldron/, "#9ca2a3"],
    [/gold|yellow_/, "#e6c447"], [/diamond|cyan_/, "#53b9bc"],
    [/red_|nether_wart/, "#ad3d3d"], [/blue_|lapis/, "#4869ae"],
    [/purple_|amethyst/, "#9565b8"], [/pink_|magenta_/, "#cf79a3"],
    [/orange_/, "#d8873e"], [/lime_/, "#82ad48"],
    [/black_|coal/, "#343538"], [/gray_/, "#6f7376"], [/white_/, "#deddd7"],
    [/glass/, "#9fc7cc"], [/wool|concrete/, "#b8ad94"],
    [/planks|log|wood|stem|hyphae|barrel|chest|bookshelf|crafting_table/, "#9a7248"],
    [/dirt|mud|farmland|path/, "#806044"], [/gravel/, "#8e8984"],
    [/flower|tulip|orchid|dandelion|poppy/, "#d97983"],
    [/torch|lantern|glowstone|shroomlight|sea_lantern/, "#e7c66a"]
  ];
  return colors.find(([pattern]) => pattern.test(name))?.[1] || "#9b927f";
}

function shadeMinecraftTopColor(hex, factor) {
  const value = Number.parseInt(hex.slice(1), 16);
  const channel = (shift) => Math.max(0, Math.min(255, Math.round(((value >> shift) & 255) * factor)));
  return `rgb(${channel(16)}, ${channel(8)}, ${channel(0)})`;
}

function structureViewerEntries() {
  const query = state.structureViewer.query.trim().toLowerCase();
  return Object.entries(state.structureViewer.catalog || {})
    .filter(([id, metadata]) => !query || `${id} ${metadata.source || ""}`.toLowerCase().includes(query))
    .sort(([left], [right]) => left.localeCompare(right));
}

function renderStructureBrowser() {
  const list = $("#nbt-structure-list");
  if (!list) return;
  const entries = structureViewerEntries();
  $("#nbt-structure-count").textContent = `${entries.length.toLocaleString()}개`;
  list.innerHTML = entries.length ? entries.map(([id, metadata]) => `
    <button type="button" class="nbt-structure-item${id === state.structureViewer.selected ? " is-active" : ""}" data-nbt-structure="${escapeHtml(id)}">
      <span><strong>${escapeHtml(id.split(":").at(-1))}</strong><small>${escapeHtml(id)}</small></span>
      <span class="nbt-structure-size">${metadata.width}×${metadata.height}×${metadata.depth}</span>
    </button>`).join("") : '<div class="issues empty">검색 조건에 맞는 NBT가 없습니다.</div>';
}

async function loadStructureModel(structureId) {
  state.structureViewer.selected = structureId;
  state.structureViewer.model = null;
  const requestId = ++state.structureViewer.requestId;
  renderStructureBrowser();
  $("#nbt-model-title").textContent = structureId;
  $("#nbt-model-meta").textContent = "실제 NBT 블록을 읽는 중입니다…";
  $("#nbt-model-source").textContent = "불러오는 중";
  $("#nbt-model-empty").hidden = false;
  $("#nbt-model-empty").textContent = "3D 모델을 만드는 중입니다…";
  renderStructureModel();
  const result = await request(`/api/structure-model?structure=${encodeURIComponent(structureId)}`);
  if (requestId !== state.structureViewer.requestId) return;
  if (!result.ok) {
    $("#nbt-model-meta").textContent = result.data.error || "NBT 모델을 불러오지 못했습니다.";
    $("#nbt-model-source").textContent = "불러오기 실패";
    $("#nbt-model-empty").textContent = result.data.error || "NBT 모델을 불러오지 못했습니다.";
    return;
  }
  state.structureViewer.model = result.data;
  resetStructureView();
  const model = result.data;
  $("#nbt-model-title").textContent = structureId;
  $("#nbt-model-meta").textContent = `${model.width} × ${model.height} × ${model.depth} 블록 · 전체 ${Number(model.total_blocks).toLocaleString()}블록`;
  $("#nbt-model-source").textContent = model.source || "NBT";
  $("#nbt-model-blocks").textContent = `표면 블록 ${Number(model.surface_blocks).toLocaleString()}개`;
  $("#nbt-model-empty").hidden = Boolean(model.blocks?.length);
  if (!model.blocks?.length) $("#nbt-model-empty").textContent = "표시할 블록이 없는 구조물입니다.";
  renderStructureModel();
}

function resetStructureView() {
  Object.assign(state.structureViewer, { yaw: -.75, pitch: structureViewPitch.default, zoom: 1, drag: null });
  renderStructureModel();
}

function projectStructurePoint(point, model, canvas, scale = 1) {
  const view = state.structureViewer;
  const dx = point[0] - model.width / 2;
  const dy = point[1] - model.height / 2;
  const dz = point[2] - model.depth / 2;
  const cosine = Math.cos(view.yaw), sine = Math.sin(view.yaw);
  const rotatedX = dx * cosine - dz * sine;
  const rotatedZ = dx * sine + dz * cosine;
  const pitchCosine = Math.cos(view.pitch), pitchSine = Math.sin(view.pitch);
  return {
    x: canvas.width / 2 + rotatedX * scale,
    y: canvas.height / 2 + (rotatedZ * pitchSine - dy * pitchCosine) * scale,
    depth: rotatedZ * pitchCosine + dy * pitchSine
  };
}

function structureViewScale(model, canvas) {
  const corners = [];
  for (const x of [0, model.width]) for (const y of [0, model.height]) for (const z of [0, model.depth]) {
    corners.push(projectStructurePoint([x, y, z], model, canvas));
  }
  const spanX = Math.max(...corners.map((point) => point.x)) - Math.min(...corners.map((point) => point.x));
  const spanY = Math.max(...corners.map((point) => point.y)) - Math.min(...corners.map((point) => point.y));
  return Math.max(1.5, Math.min((canvas.width - 100) / Math.max(1, spanX), (canvas.height - 80) / Math.max(1, spanY))) * state.structureViewer.zoom;
}

function renderStructureModel() {
  const canvas = $("#nbt-model-canvas");
  if (!canvas) return;
  const model = state.structureViewer?.model;
  const context = canvas.getContext("2d");
  context.clearRect(0, 0, canvas.width, canvas.height);
  if (!model?.blocks?.length) return;
  const scale = structureViewScale(model, canvas);
  const faceDefinitions = [
    [[0,0,0],[0,0,1],[0,1,1],[0,1,0], .72],
    [[1,0,1],[1,0,0],[1,1,0],[1,1,1], .86],
    [[0,0,1],[0,0,0],[1,0,0],[1,0,1], .58],
    [[0,1,0],[0,1,1],[1,1,1],[1,1,0], 1.08],
    [[1,0,0],[0,0,0],[0,1,0],[1,1,0], .76],
    [[0,0,1],[1,0,1],[1,1,1],[0,1,1], .92]
  ];
  const faces = [];
  for (const [x, y, z, paletteIndex, faceMask] of model.blocks) {
    for (let index = 0; index < faceDefinitions.length; index += 1) {
      if (!(faceMask & (1 << index))) continue;
      const definition = faceDefinitions[index];
      const points = definition.slice(0, 4).map(([dx, dy, dz]) => projectStructurePoint([x + dx, y + dy, z + dz], model, canvas, scale));
      faces.push({ points, depth: points.reduce((sum, point) => sum + point.depth, 0) / 4, color: shadeMinecraftTopColor(minecraftTopBlockColor(model.palette[paletteIndex]), definition[4]), blockName: model.palette[paletteIndex] });
    }
  }
  faces.sort((left, right) => right.depth - left.depth);
  context.lineJoin = "round";
  for (const face of faces) {
    context.beginPath();
    context.moveTo(face.points[0].x, face.points[0].y);
    for (const point of face.points.slice(1)) context.lineTo(point.x, point.y);
    context.closePath();
    context.globalAlpha = /glass|leaves|water/.test(face.blockName) ? .78 : 1;
    context.fillStyle = face.color;
    context.fill();
    if (scale >= 3) {
      context.strokeStyle = "rgba(18, 25, 23, .28)";
      context.lineWidth = Math.min(1.2, scale * .09);
      context.stroke();
    }
  }
  context.globalAlpha = 1;
}

function beginStructureDrag(event) {
  state.structureViewer.drag = { x: event.clientX, y: event.clientY, yaw: state.structureViewer.yaw, pitch: state.structureViewer.pitch };
  event.currentTarget.setPointerCapture(event.pointerId);
  event.currentTarget.classList.add("is-dragging");
}

function moveStructureDrag(event) {
  const drag = state.structureViewer.drag;
  if (!drag) return;
  state.structureViewer.yaw = drag.yaw + (event.clientX - drag.x) * .012;
  state.structureViewer.pitch = Math.max(
    structureViewPitch.minimum,
    Math.min(structureViewPitch.maximum, drag.pitch - (event.clientY - drag.y) * .008)
  );
  renderStructureModel();
}

function endStructureDrag(event) {
  state.structureViewer.drag = null;
  event.currentTarget.classList.remove("is-dragging");
}

function selectedGym() {
  return (state.gymCatalog.gyms || []).find((gym) => gym.id === state.selectedGymId) || null;
}

const standardGymExterior = "cobbleventure:gyms/base_gym";
const gymThemeColors = {
  normal: ["white", "light_gray", "white"], fire: ["red", "orange", "red"],
  water: ["blue", "light_blue", "blue"], electric: ["yellow", "orange", "yellow"],
  grass: ["green", "lime", "green"], ice: ["light_blue", "white", "light_blue"],
  fighting: ["red", "brown", "red"], poison: ["purple", "magenta", "purple"],
  ground: ["brown", "orange", "brown"], flying: ["light_blue", "white", "light_blue"],
  psychic: ["pink", "magenta", "pink"], bug: ["green", "lime", "green"],
  rock: ["light_gray", "gray", "light_gray"], ghost: ["purple", "blue", "purple"],
  dragon: ["purple", "blue", "purple"], dark: ["black", "gray", "black"],
  steel: ["gray", "light_gray", "gray"], fairy: ["pink", "magenta", "pink"]
};

function gymThemeBlockName(blockName, theme) {
  const colors = gymThemeColors[theme] || gymThemeColors.rock;
  if (blockName === "minecraft:light_gray_concrete") return `minecraft:${colors[0]}_concrete`;
  if (blockName === "minecraft:yellow_concrete") return `minecraft:${colors[1]}_concrete`;
  if (blockName === "minecraft:orange_stained_glass_pane") return `minecraft:${colors[2]}_stained_glass_pane`;
  return blockName;
}

function gymOptions(selected = "") {
  return '<option value="">체육관을 선택하세요</option>' + (state.gymCatalog.gyms || [])
    .filter((gym) => gym.enabled || gym.id === selected)
    .map((gym) => `<option value="${escapeHtml(gym.id)}"${gym.id === selected ? " selected" : ""}>${escapeHtml(gym.display_name?.ko_kr || gym.id)} · ${escapeHtml(gym.theme)}</option>`)
    .join("");
}

function renderGymList() {
  const gyms = state.gymCatalog.gyms || [];
  const list = $("#gym-list");
  if (!list) return;
  $("#gym-list-count").textContent = gyms.length;
  list.innerHTML = gyms.length ? gyms.map((gym) => {
    const leaderEntry = leagueEntryForGym(gym);
    const type = leaderEntry?.primary_type || gym.theme || "normal";
    const badge = badgeById(leagueEntryBadgeId(leaderEntry));
    return `<button class="document-button gym-catalog-row${gym.id === state.selectedGymId ? " is-active" : ""}" style="--gym-type:${leagueTypeColors[type] || leagueTypeColors.normal}" data-gym-id="${escapeHtml(gym.id)}"><span class="gym-catalog-badge">${badgeSprite(badge, 1.05)}</span><span class="gym-catalog-copy"><strong>${escapeHtml(gym.display_name?.ko_kr || gym.id)}</strong><small><b>${escapeHtml(leagueTypeLabels[type] || type)}</b> · ${escapeHtml(leaderEntry?.display_name?.ko_kr || "관장 미연결")}</small></span></button>`;
  }).join("") : '<div class="issues empty">등록된 체육관이 없습니다.</div>';
}

function gymRotatedPoint(x, z, rotation) {
  if (rotation === "clockwise_90") return [-z, x];
  if (rotation === "clockwise_180") return [-x, -z];
  if (rotation === "counterclockwise_90") return [z, -x];
  return [x, z];
}

function gymModuleLayoutInfo(module, index) {
  const metadata = state.structureSizes[module.structure] || {};
  const width = Math.max(1, Number(metadata.width) || 16);
  const depth = Math.max(1, Number(metadata.depth) || 16);
  const originX = Number(module.position?.[0]) || 0;
  const originZ = Number(module.position?.[2]) || 0;
  const rotation = module.rotation || "none";
  const corners = [[0, 0], [width, 0], [width, depth], [0, depth]].map(([x, z]) => {
    const [rotatedX, rotatedZ] = gymRotatedPoint(x, z, rotation);
    return [originX + rotatedX, originZ + rotatedZ];
  });
  const xs = corners.map(([x]) => x), zs = corners.map(([, z]) => z);
  return {
    module, index, metadata, width, depth, originX, originZ, rotation, corners,
    minX: Math.min(...xs), maxX: Math.max(...xs), minZ: Math.min(...zs), maxZ: Math.max(...zs)
  };
}

function gymLayoutPoint(event, canvas) {
  const bounds = canvas.getBoundingClientRect();
  return {
    x: (event.clientX - bounds.left) * canvas.width / Math.max(1, bounds.width),
    y: (event.clientY - bounds.top) * canvas.height / Math.max(1, bounds.height)
  };
}

function gymLayoutProjection(infos, canvas) {
  if (!infos.length) return { minX: -4, minZ: -4, scale: 10, margin: 52 };
  const minX = Math.min(...infos.map((info) => info.minX)) - 4;
  const maxX = Math.max(...infos.map((info) => info.maxX)) + 4;
  const minZ = Math.min(...infos.map((info) => info.minZ)) - 4;
  const maxZ = Math.max(...infos.map((info) => info.maxZ)) + 4;
  const margin = 52;
  const scale = Math.max(1, Math.min(14,
    (canvas.width - margin * 2) / Math.max(1, maxX - minX),
    (canvas.height - margin * 2) / Math.max(1, maxZ - minZ)
  ));
  return { minX, minZ, scale, margin };
}

function renderGymLayout() {
  const canvas = $("#gym-layout-canvas");
  if (!canvas) return;
  const context = canvas.getContext("2d");
  const gym = selectedGym();
  const modules = gym?.interior?.modules || [];
  const infos = modules.map(gymModuleLayoutInfo);
  const empty = $("#gym-layout-empty");
  context.clearRect(0, 0, canvas.width, canvas.height);
  context.fillStyle = "#10181d";
  context.fillRect(0, 0, canvas.width, canvas.height);
  empty.hidden = Boolean(gym && modules.length);
  empty.textContent = gym ? "내부 모듈을 추가하면 공간 배치가 표시됩니다." : "체육관을 선택하세요.";
  $("#fit-gym-layout").disabled = !infos.length;
  if (!infos.length) {
    state.gymLayout.hitTargets = [];
    $("#gym-layout-summary").textContent = "배치 정보 없음";
    return;
  }

  if (state.gymLayout.selected !== null && !infos[state.gymLayout.selected]) state.gymLayout.selected = null;
  const projection = state.gymLayout.drag?.projection || gymLayoutProjection(infos, canvas);
  const project = ([x, z]) => ({
    x: projection.margin + (x - projection.minX) * projection.scale,
    y: projection.margin + (z - projection.minZ) * projection.scale
  });
  const visibleWidth = (canvas.width - projection.margin * 2) / projection.scale;
  const visibleDepth = (canvas.height - projection.margin * 2) / projection.scale;
  const gridStep = projection.scale >= 8 ? 1 : projection.scale >= 3 ? 4 : projection.scale >= 1.5 ? 8 : 16;
  context.lineWidth = 1;
  context.strokeStyle = "rgba(137, 164, 176, .12)";
  context.beginPath();
  const gridMinX = Math.floor(projection.minX / gridStep) * gridStep;
  const gridMinZ = Math.floor(projection.minZ / gridStep) * gridStep;
  for (let x = gridMinX; x <= projection.minX + visibleWidth; x += gridStep) {
    const point = project([x, projection.minZ]); context.moveTo(point.x, projection.margin); context.lineTo(point.x, canvas.height - projection.margin);
  }
  for (let z = gridMinZ; z <= projection.minZ + visibleDepth; z += gridStep) {
    const point = project([projection.minX, z]); context.moveTo(projection.margin, point.y); context.lineTo(canvas.width - projection.margin, point.y);
  }
  context.stroke();

  const infoById = new Map(infos.map((info) => [info.module.id, info]));
  const anchorPoint = (endpoint) => {
    const [moduleId, label] = String(endpoint || "").split(":", 2);
    const info = infoById.get(moduleId); if (!info) return null;
    const anchors = [...(info.metadata.door_anchors || []), ...(info.metadata.arrival_anchors || []), ...(info.metadata.npc_labels || [])];
    const anchor = anchors.find((candidate) => candidate.label === label);
    if (!anchor) return project([(info.minX + info.maxX) / 2, (info.minZ + info.maxZ) / 2]);
    const [localX, , localZ] = anchor.position;
    const [rotatedX, rotatedZ] = gymRotatedPoint(localX + .5, localZ + .5, info.rotation);
    return project([info.originX + rotatedX, info.originZ + rotatedZ]);
  };
  context.strokeStyle = "rgba(101, 210, 226, .78)";
  context.lineWidth = Math.max(2, projection.scale * .18);
  context.setLineDash([8, 7]);
  for (const connection of gym.interior?.connections || []) {
    const from = anchorPoint(connection.from), to = anchorPoint(connection.to);
    if (!from || !to) continue;
    context.beginPath(); context.moveTo(from.x, from.y); context.lineTo(to.x, to.y); context.stroke();
  }
  context.setLineDash([]);

  const assigned = new Map();
  if (gym.staff?.leader?.anchor) assigned.set(gym.staff.leader.anchor, { kind: "leader", label: "관장" });
  for (const trainer of gym.staff?.trainers || []) if (trainer.anchor) assigned.set(trainer.anchor, { kind: "trainer", label: trainer.id || "트레이너" });
  const hitTargets = [];
  for (const info of infos) {
    const projectedCorners = info.corners.map(project);
    context.beginPath(); context.moveTo(projectedCorners[0].x, projectedCorners[0].y);
    for (const point of projectedCorners.slice(1)) context.lineTo(point.x, point.y);
    context.closePath();
    context.fillStyle = info.index === state.gymLayout.selected ? "rgba(53, 168, 208, .22)" : "rgba(232, 240, 238, .10)";
    context.fill();

    const topView = info.metadata.top_view;
    if (topView?.blocks?.length) {
      for (const [x, z, , paletteIndex] of topView.blocks) {
        const cellCorners = [[x, z], [x + 1, z], [x + 1, z + 1], [x, z + 1]].map(([localX, localZ]) => {
          const [rotatedX, rotatedZ] = gymRotatedPoint(localX, localZ, info.rotation);
          return project([info.originX + rotatedX, info.originZ + rotatedZ]);
        });
        context.beginPath(); context.moveTo(cellCorners[0].x, cellCorners[0].y);
        for (const point of cellCorners.slice(1)) context.lineTo(point.x, point.y);
        context.closePath();
        context.globalAlpha = .76;
        context.fillStyle = minecraftTopBlockColor(topView.palette?.[paletteIndex] || "minecraft:stone");
        context.fill();
      }
      context.globalAlpha = 1;
    }

    context.beginPath(); context.moveTo(projectedCorners[0].x, projectedCorners[0].y);
    for (const point of projectedCorners.slice(1)) context.lineTo(point.x, point.y);
    context.closePath();
    context.strokeStyle = info.index === state.gymLayout.selected ? "#69d5ee" : "rgba(221, 238, 239, .82)";
    context.lineWidth = info.index === state.gymLayout.selected ? 4 : 2;
    context.stroke();

    const center = project([(info.minX + info.maxX) / 2, (info.minZ + info.maxZ) / 2]);
    context.textAlign = "center"; context.textBaseline = "middle";
    context.font = "800 18px Segoe UI, sans-serif";
    context.lineWidth = 5; context.strokeStyle = "rgba(8, 15, 18, .9)";
    context.strokeText(info.module.id || `room_${info.index + 1}`, center.x, center.y);
    context.fillStyle = "#f2f8f7"; context.fillText(info.module.id || `room_${info.index + 1}`, center.x, center.y);

    for (const entrance of info.metadata.arrival_anchors || []) {
      const [localX, , localZ] = entrance.position;
      const [rotatedX, rotatedZ] = gymRotatedPoint(localX + .5, localZ + .5, info.rotation);
      const point = project([info.originX + rotatedX, info.originZ + rotatedZ]);
      context.fillStyle = "#65d2e2"; context.beginPath();
      context.moveTo(point.x, point.y - 9); context.lineTo(point.x + 9, point.y + 8); context.lineTo(point.x - 9, point.y + 8); context.closePath(); context.fill();
    }
    for (const marker of info.metadata.npc_labels || []) {
      const [localX, , localZ] = marker.position;
      const [rotatedX, rotatedZ] = gymRotatedPoint(localX + .5, localZ + .5, info.rotation);
      const point = project([info.originX + rotatedX, info.originZ + rotatedZ]);
      const staff = assigned.get(marker.label);
      context.fillStyle = staff?.kind === "leader" ? "#f07b70" : "#ffd166";
      context.strokeStyle = "#10181d"; context.lineWidth = 3;
      context.beginPath(); context.arc(point.x, point.y, staff?.kind === "leader" ? 9 : 7, 0, Math.PI * 2); context.fill(); context.stroke();
      context.font = "700 12px Segoe UI, sans-serif"; context.textBaseline = "bottom";
      context.lineWidth = 4; context.strokeStyle = "rgba(8, 15, 18, .92)"; context.strokeText(staff?.label || marker.label, point.x, point.y - 10);
      context.fillStyle = "#ffffff"; context.fillText(staff?.label || marker.label, point.x, point.y - 10);
    }
    const xs = projectedCorners.map((point) => point.x), ys = projectedCorners.map((point) => point.y);
    hitTargets.push({ index: info.index, minX: Math.min(...xs), maxX: Math.max(...xs), minY: Math.min(...ys), maxY: Math.max(...ys) });
  }
  state.gymLayout.hitTargets = hitTargets;
  const selected = state.gymLayout.selected === null ? null : infos[state.gymLayout.selected];
  $("#gym-layout-summary").textContent = selected
    ? `${selected.module.id} · ${selected.width}×${selected.depth} · X ${selected.originX}, Y ${Number(selected.module.position?.[1]) || 0}, Z ${selected.originZ}`
    : `${infos.length}개 방 · 방을 선택하거나 드래그해 배치`;
}

function renderGymModules() {
  const gym = selectedGym();
  const modules = gym?.interior?.modules || [];
  $("#gym-module-list").innerHTML = gym ? (modules.length ? modules.map((module, index) => `<article class="cave-entry-card${index === state.gymLayout.selected ? " is-layout-selected" : ""}" data-gym-module="${index}"><div class="form-grid compact-grid"><label><span>모듈 ID</span><input data-gym-module-field="id" value="${escapeHtml(module.id || "")}"></label><label class="wide"><span>방 NBT</span><select data-gym-module-field="structure">${Object.keys(state.structureSizes).filter((id) => id.startsWith("cobbleventure:interiors/")).map((id) => `<option value="${escapeHtml(id)}"${id === module.structure ? " selected" : ""}>${escapeHtml(id)}</option>`).join("")}</select></label><label><span>X</span><input type="number" data-gym-module-axis="0" value="${Number(module.position?.[0] || 0)}"></label><label><span>층 높이 Y</span><input type="number" data-gym-module-axis="1" value="${Number(module.position?.[1] || 0)}"></label><label><span>Z</span><input type="number" data-gym-module-axis="2" value="${Number(module.position?.[2] || 0)}"></label><label><span>회전</span><select data-gym-module-field="rotation"><option value="none">회전 없음</option><option value="clockwise_90">90°</option><option value="clockwise_180">180°</option><option value="counterclockwise_90">-90°</option></select></label></div><button type="button" class="button danger compact-button" data-remove-gym-module="${index}">모듈 삭제</button></article>`).join("") : '<div class="issues empty">내부 모듈이 없습니다. 외관 출입문은 별도 내부 공간으로 순간이동하며, 필요한 방을 계속 추가할 수 있습니다.</div>') : '<div class="issues empty">체육관을 선택하세요.</div>';
  modules.forEach((module, index) => { const select = $(`[data-gym-module="${index}"] [data-gym-module-field="rotation"]`); if (select) select.value = module.rotation || "none"; });
}

function renderGymStaff() {
  const gym = selectedGym();
  const editor = $("#gym-staff-editor");
  if (!gym) {
    editor.innerHTML = '<div class="issues empty">체육관을 선택하세요.</div>';
    return;
  }
  gym.staff ||= { leader: { league_entry_id: "", anchor: "leader" }, trainers: [] };
  gym.staff.leader ||= { league_entry_id: "", anchor: "leader" };
  gym.staff.trainers ||= [];
  const leader = gym.staff.leader;
  const leaderEntry = (state.leagueProgression.entries || []).find((entry) => entry.id === leader.league_entry_id);
  const leaderAppearance = leaderEntry?.encounter?.appearance;
  const leaderName = leaderEntry?.display_name?.ko_kr;
  const selectedBadge = badgeById(leaderEntry?.encounter?.rewards?.badge_id);
  const anchorOptions = (selected = "") => {
    const labels = [...new Set((gym.interior?.modules || []).flatMap((module) =>
      (state.structureSizes[module.structure]?.npc_labels || []).map((marker) => marker.label)
    ).filter(Boolean))].sort();
    if (selected && !labels.includes(selected)) labels.unshift(selected);
    return '<option value="">NPC 라벨 선택</option>' + labels.map((label) => `<option value="${escapeHtml(label)}"${label === selected ? " selected" : ""}>${escapeHtml(label)}</option>`).join("");
  };
  const rows = gym.staff.trainers.map((trainer, index) => `
    <article class="cave-entry-card" data-gym-trainer="${index}">
      <div class="form-grid compact-grid">
        <label><span>배치 ID</span><input data-gym-trainer-field="id" value="${escapeHtml(trainer.id || "")}"></label>
        <label class="wide"><span>트레이너</span><select data-gym-trainer-field="trainer_id">${trainerPoolOptions(trainer.trainer_id)}</select></label>
        <label><span>내부 NPC 라벨</span><select data-gym-trainer-field="anchor">${anchorOptions(trainer.anchor)}</select></label>
      </div>
      <button type="button" class="button danger compact-button" data-remove-gym-trainer="${index}">트레이너 삭제</button>
    </article>`).join("");
  editor.innerHTML = `
    <article class="cave-entry-card">
      <div class="form-grid compact-grid">
        <label class="wide"><span>체육관 관장</span><select id="gym-leader-entry">${gymLeagueOptions(leader.league_entry_id)}</select></label>
        <div class="wide gym-badge-picker"><span>승리 배지</span><output class="gym-badge-preview">${badgeSprite(selectedBadge, 2)}<span><strong>${escapeHtml(selectedBadge?.display_name?.ko_kr || "리그 운영에서 배지를 지정하세요")}</strong><small>배지는 리그 관장 약식 이벤트에서 관리합니다.</small></span></output></div>
        <div class="wide gym-npc-appearance"><span>빌드 시 생성할 관장 그래픽</span><strong>${escapeHtml(leaderName || "관장을 선택하세요")}</strong><small>${leaderAppearance?.resource ? `리그 원본 외형 ${escapeHtml(leaderAppearance.resource)}을 빌드 산출물에 사용합니다.` : "리그 운영에서 관장 외형을 지정합니다."}</small></div>
        <label><span>관장 NPC 라벨</span><select id="gym-leader-anchor">${anchorOptions(leader.anchor || "leader")}</select></label>
        <small class="wide">NPC ID는 빌드 시 배틀 프리셋 ID를 기준으로 자동 생성됩니다.</small>
      </div>
    </article>
    ${rows || '<div class="issues empty">기타 트레이너가 없습니다. 내부 NBT에 NPC 라벨을 만든 뒤 필요한 만큼 추가하세요.</div>'}`;
}

function renderGymEditor() {
  renderGymList();
  const gym = selectedGym();
  const form = $("#gym-form");
  if (!gym) { form.reset(); [...form.elements].forEach((element) => { element.disabled = true; }); $("#gym-editor-title").textContent = "체육관을 선택하세요"; $("#gym-editor-badge").hidden = true; $("#gym-editor-type").hidden = true; $("#delete-gym").disabled = true; $("#preview-gym-exterior").disabled = true; $("#add-gym-module").disabled = true; $("#add-gym-trainer").disabled = true; $("#gym-json").disabled = true; $("#apply-gym-json").disabled = true; state.gymLayout.selected = null; renderGymStaff(); renderGymModules(); renderGymLayout(); return; }
  const leaderEntry = leagueEntryForGym(gym);
  const type = leaderEntry?.primary_type || gym.theme || "normal";
  const badge = badgeById(leagueEntryBadgeId(leaderEntry));
  $("#gym-editor-title").textContent = gym.display_name?.ko_kr || gym.id;
  $("#gym-editor-badge").innerHTML = badgeSprite(badge, 1.35);
  $("#gym-editor-badge").style.setProperty("--gym-type", leagueTypeColors[type] || leagueTypeColors.normal);
  $("#gym-editor-badge").hidden = false;
  $("#gym-editor-type").textContent = `${leagueTypeLabels[type] || type} 타입 · ${leaderEntry?.display_name?.ko_kr || "관장 미연결"}`;
  $("#gym-editor-type").style.setProperty("--gym-type", leagueTypeColors[type] || leagueTypeColors.normal);
  $("#gym-editor-type").hidden = false;
  setFormValue(form, "id", gym.id); setFormValue(form, "nameKo", gym.display_name?.ko_kr || ""); setFormValue(form, "nameEn", gym.display_name?.en_us || ""); setFormValue(form, "theme", gym.theme || "normal"); setFormValue(form, "enabled", gym.enabled !== false);
  form.elements.musicTrack.innerHTML = musicOptions(gym.music_track || "", "gym");
  setFormValue(form, "musicTrack", gym.music_track || "");
  form.elements.exteriorStructure.innerHTML = `<option value="${standardGymExterior}">${standardGymExterior}</option>`;
  setFormValue(form, "exteriorStructure", standardGymExterior);
  [...form.elements].forEach((element) => { element.disabled = false; }); form.elements.id.disabled = true;
  for (const selector of ["#delete-gym", "#preview-gym-exterior", "#add-gym-module", "#add-gym-trainer", "#gym-json", "#apply-gym-json"]) $(selector).disabled = false;
  $("#gym-json").value = JSON.stringify(gym, null, 2); renderGymStaff(); renderGymModules(); renderGymLayout();
}

function updateGymFromForm() {
  const gym = selectedGym(); if (!gym) return;
  const form = $("#gym-form");
  gym.display_name = { ko_kr: form.elements.nameKo.value.trim(), en_us: form.elements.nameEn.value.trim() };
  gym.theme = form.elements.theme.value; gym.enabled = form.elements.enabled.checked;
  if (form.elements.musicTrack.value) gym.music_track = form.elements.musicTrack.value;
  else delete gym.music_track;
  gym.exterior = { structure: standardGymExterior };
  $("#gym-json").value = JSON.stringify(gym, null, 2); renderGymList();
}

async function saveGyms() {
  updateGymFromForm();
  const result = await request("/api/gyms", { method: "PUT", body: JSON.stringify(state.gymCatalog) });
  showIssues("#gym-issues", result.data); toast(result.ok ? "체육관 외관과 내부 구성을 저장했습니다." : "체육관 설정을 확인해 주세요.");
  if (result.ok) { const refreshed = await request("/api/gyms"); if (refreshed.ok) state.gymCatalog = refreshed.data; renderGymEditor(); }
}

async function addGym() {
  const slug = prompt("새 체육관 ID (소문자/숫자/밑줄)", "new_gym"); if (!slug) return;
  const name = prompt("새 체육관의 한국어 이름", "새 체육관"); if (!name) return;
  const result = await request("/api/gyms/create", { method: "POST", body: JSON.stringify({ slug: slug.trim(), name: name.trim(), source_structure: standardGymExterior }) });
  if (!result.ok) { showIssues("#gym-issues", result.data); toast("체육관을 만들지 못했습니다."); return; }
  const refreshed = await request("/api/gyms"); if (refreshed.ok) state.gymCatalog = refreshed.data;
  state.selectedGymId = result.data.gym.id; renderGymEditor(); toast("재사용 외관 템플릿과 내부 모듈로 새 체육관을 구성했습니다.");
}

function deleteGym() {
  const gym = selectedGym(); if (!gym || !confirm(`'${gym.display_name?.ko_kr || gym.id}' 체육관 설정을 삭제할까요?\n공용 외관 템플릿과 내부 모듈은 삭제되지 않습니다.`)) return;
  if (state.settlements.some((settlement) => settlement.gym_id === gym.id)) { toast("마을에서 사용하는 체육관은 삭제할 수 없습니다."); return; }
  state.gymCatalog.gyms = state.gymCatalog.gyms.filter((candidate) => candidate !== gym); state.selectedGymId = ""; renderGymEditor();
}

function buildingEntries() {
  const query = state.buildingSettings.query.trim().toLowerCase();
  const category = state.buildingSettings.category;
  return Object.entries(state.buildingSettings.structures || {})
    .filter(([, metadata]) => category === "all" || metadata.category === category)
    .filter(([id, metadata]) => !query || `${id} ${metadata.source || ""}`.toLowerCase().includes(query))
    .sort(([left], [right]) => left.localeCompare(right));
}

function renderBuildingList() {
  const entries = buildingEntries();
  $("#building-count").textContent = `${entries.length.toLocaleString()}개`;
  const itemMarkup = ([id, metadata]) => {
    const labels = metadata.npc_labels || [];
    const assigned = Object.keys(metadata.settings?.fixed_npcs || {}).length;
    const badge = metadata.settings?.citizen_placement_allowed
      ? "시민 수용" : labels.length ? `${assigned}/${labels.length} 배정` : "라벨 없음";
    return `<button type="button" class="nbt-structure-item${id === state.buildingSettings.selected ? " is-active" : ""}" data-building-id="${escapeHtml(id)}">
      <span><strong>${escapeHtml(id.split(":").at(-1))}</strong><small>${escapeHtml(id)}</small></span>
      <span class="nbt-structure-size">${escapeHtml(badge)}</span>
    </button>`;
  };
  if (!entries.length) {
    $("#building-list").innerHTML = '<div class="issues empty">검색 조건에 맞는 구조물이 없습니다.</div>';
    return;
  }
  const groups = new Map();
  for (const entry of entries) {
    const category = entry[1].category || "building";
    if (!groups.has(category)) groups.set(category, []);
    groups.get(category).push(entry);
  }
  const preferredOrder = ["building", "residential", "decoration", "natural_feature", "gym_exterior", "gym_interior", "interior", "league", "placeholder"];
  const order = [
    ...preferredOrder.filter((category) => groups.has(category)),
    ...[...groups.keys()].filter((category) => !preferredOrder.includes(category)).sort(),
  ];
  $("#building-list").innerHTML = order.map((category) => {
    const group = groups.get(category);
    const label = group[0][1].category_label || category;
    return `<section class="nbt-structure-group"><header><strong>${escapeHtml(label)}</strong><span>${group.length}</span></header>${group.map(itemMarkup).join("")}</section>`;
  }).join("");
}

async function loadBuildingModel(structureId) {
  const view = state.buildingSettings;
  view.selected = structureId;
  view.model = null;
  const requestId = ++view.requestId;
  renderBuildingList();
  renderBuildingEditor();
  $("#building-model-title").textContent = structureId;
  $("#building-model-meta").textContent = "NBT 블록과 NPC 위치를 읽는 중입니다…";
  $("#building-model-empty").hidden = false;
  $("#building-model-empty").textContent = "3D 미리보기를 만드는 중입니다…";
  renderBuildingModel();
  const result = await request(`/api/structure-model?structure=${encodeURIComponent(structureId)}`);
  if (requestId !== view.requestId) return;
  if (!result.ok) {
    $("#building-model-meta").textContent = result.data.error || "NBT 모델을 불러오지 못했습니다.";
    $("#building-model-empty").textContent = result.data.error || "NBT 모델을 불러오지 못했습니다.";
    return;
  }
  view.model = result.data;
  Object.assign(view, { yaw: -.75, pitch: structureViewPitch.default, zoom: 1, drag: null });
  const metadata = view.structures[structureId] || {};
  const labels = metadata.npc_labels || [];
  $("#building-model-title").textContent = structureId;
  $("#building-model-meta").textContent = `${result.data.width} × ${result.data.height} × ${result.data.depth} 블록 · NPC 위치 ${labels.length}개`;
  $("#building-marker-count").textContent = `NPC 위치 ${labels.length}개`;
  $("#building-model-empty").hidden = Boolean(result.data.blocks?.length);
  renderBuildingModel();
}

function projectBuildingPoint(point, model, canvas, scale = 1) {
  const view = state.buildingSettings;
  const dx = point[0] - model.width / 2, dy = point[1] - model.height / 2, dz = point[2] - model.depth / 2;
  const cosine = Math.cos(view.yaw), sine = Math.sin(view.yaw);
  const rotatedX = dx * cosine - dz * sine, rotatedZ = dx * sine + dz * cosine;
  const pitchCosine = Math.cos(view.pitch), pitchSine = Math.sin(view.pitch);
  return { x: canvas.width / 2 + rotatedX * scale, y: canvas.height / 2 + (rotatedZ * pitchSine - dy * pitchCosine) * scale, depth: rotatedZ * pitchCosine + dy * pitchSine };
}

function buildingViewScale(model, canvas) {
  const corners = [];
  for (const x of [0, model.width]) for (const y of [0, model.height]) for (const z of [0, model.depth]) corners.push(projectBuildingPoint([x, y, z], model, canvas));
  const spanX = Math.max(...corners.map((point) => point.x)) - Math.min(...corners.map((point) => point.x));
  const spanY = Math.max(...corners.map((point) => point.y)) - Math.min(...corners.map((point) => point.y));
  return Math.max(1.5, Math.min((canvas.width - 100) / Math.max(1, spanX), (canvas.height - 80) / Math.max(1, spanY))) * state.buildingSettings.zoom;
}

function renderBuildingModel() {
  const canvas = $("#building-model-canvas"), model = state.buildingSettings.model;
  if (!canvas) return;
  const context = canvas.getContext("2d");
  context.clearRect(0, 0, canvas.width, canvas.height);
  if (!model?.blocks?.length) return;
  const scale = buildingViewScale(model, canvas);
  const definitions = [
    [[0,0,0],[0,0,1],[0,1,1],[0,1,0],.72], [[1,0,1],[1,0,0],[1,1,0],[1,1,1],.86],
    [[0,0,1],[0,0,0],[1,0,0],[1,0,1],.58], [[0,1,0],[0,1,1],[1,1,1],[1,1,0],1.08],
    [[1,0,0],[0,0,0],[0,1,0],[1,1,0],.76], [[0,0,1],[1,0,1],[1,1,1],[0,1,1],.92]
  ];
  const faces = [];
  for (const [x, y, z, paletteIndex, mask] of model.blocks) for (let index = 0; index < definitions.length; index += 1) {
    if (!(mask & (1 << index))) continue;
    const definition = definitions[index];
    const points = definition.slice(0, 4).map(([dx, dy, dz]) => projectBuildingPoint([x + dx, y + dy, z + dz], model, canvas, scale));
    const blockName = gymThemeBlockName(model.palette[paletteIndex], state.buildingSettings.gymThemePreview);
    faces.push({ points, depth: points.reduce((sum, point) => sum + point.depth, 0) / 4, color: shadeMinecraftTopColor(minecraftTopBlockColor(blockName), definition[4]), blockName });
  }
  faces.sort((left, right) => right.depth - left.depth);
  context.lineJoin = "round";
  for (const face of faces) {
    context.beginPath(); context.moveTo(face.points[0].x, face.points[0].y);
    for (const point of face.points.slice(1)) context.lineTo(point.x, point.y);
    context.closePath(); context.globalAlpha = /glass|leaves|water/.test(face.blockName) ? .78 : 1;
    context.fillStyle = face.color; context.fill();
    if (scale >= 3) { context.strokeStyle = "rgba(18,25,23,.28)"; context.lineWidth = Math.min(1.2, scale * .09); context.stroke(); }
  }
  context.globalAlpha = 1;
  const metadata = state.buildingSettings.structures[state.buildingSettings.selected] || {};
  const fixed = metadata.settings?.fixed_npcs || {};
  for (const marker of metadata.npc_labels || []) {
    const [x, y, z] = marker.position;
    const base = projectBuildingPoint([x + .5, y, z + .5], model, canvas, scale);
    const top = projectBuildingPoint([x + .5, y + Math.min(2, Math.max(1.2, model.height / 5)), z + .5], model, canvas, scale);
    const assigned = Boolean(fixed[marker.label]);
    context.strokeStyle = assigned ? "#70e1a1" : "#ffca5c"; context.lineWidth = 4;
    context.beginPath(); context.moveTo(base.x, base.y); context.lineTo(top.x, top.y); context.stroke();
    context.fillStyle = assigned ? "#70e1a1" : "#ffca5c"; context.beginPath(); context.arc(top.x, top.y, 8, 0, Math.PI * 2); context.fill();
    context.font = "bold 20px system-ui"; context.textAlign = "center"; context.textBaseline = "bottom";
    context.lineWidth = 5; context.strokeStyle = "rgba(8,14,12,.88)"; context.strokeText(marker.label, top.x, top.y - 12);
    context.fillStyle = "#ffffff"; context.fillText(marker.label, top.x, top.y - 12);
  }
}

function renderBuildingEditor() {
  const view = state.buildingSettings, metadata = view.structures[view.selected];
  $("#building-editor-empty").hidden = Boolean(metadata);
  $("#building-editor").hidden = !metadata;
  if (!metadata) return;
  metadata.settings ||= {};
  metadata.settings.interiors ||= [];
  metadata.settings.door_routes ||= {};
  const spaces = buildingConfiguredSpaces(metadata);
  const labels = spaces.flatMap(({ key, metadata: space }) =>
    (space?.npc_labels || []).map((marker) => ({ ...marker, scopedLabel: `${key}:${marker.label}` }))
  );
  const fixed = metadata.settings?.fixed_npcs || {};
  const banner = $("#building-type-banner");
  const citizenRow = $("#building-citizen-placement-row");
  const noInteriorSpace = Boolean(metadata.settings?.no_interior_space);
  const citizenPlacementAllowed = Boolean(metadata.settings?.citizen_placement_allowed);
  const league = metadata.category === "league";
  const interior = ["interior", "gym_interior"].includes(metadata.category);
  $("#building-music-track").innerHTML = musicOptions(metadata.settings?.music_track || "", "building");
  $("#building-music-track").value = metadata.settings?.music_track || "";
  $("#building-placement-y-offset").value = Number(metadata.settings?.placement_y_offset || 0);
  $("#building-size-width").value = metadata.width;
  $("#building-size-height").value = metadata.height;
  $("#building-size-depth").value = metadata.depth;
  $("#building-size-width").max = league ? "512" : "64";
  $("#building-size-depth").max = league ? "512" : "64";
  $("#building-size-width").min = interior ? "5" : "1";
  $("#building-size-depth").min = interior ? "5" : "1";
  $("#building-size-height").max = league ? "512" : interior ? "80" : "240";
  $("#building-no-interior").checked = noInteriorSpace;
  citizenRow.hidden = noInteriorSpace;
  $("#building-citizen-placement").checked = !noInteriorSpace && citizenPlacementAllowed;
  $("#building-editor").querySelectorAll(".legacy-space-editor").forEach((section) => {
    section.hidden = noInteriorSpace;
  });
  renderBuildingInteriorAssignments(metadata);
  renderBuildingDoorRoutes(metadata, spaces);
  if (noInteriorSpace) {
    banner.className = "building-type-banner fixed";
    banner.innerHTML = "<strong>내부 공간 없는 NBT</strong><span>자연물·장식·동굴용 구조물입니다. 내부 차원 생성과 문 이동 설정에서 완전히 제외됩니다.</span>";
  } else if (citizenPlacementAllowed) {
    banner.className = "building-type-banner residential";
    banner.innerHTML = "<strong>시민 수용 건물</strong><span>마을이 정한 전체 시민 수를 이 건물과 다른 수용 건물의 빈 NPC 위치에 분산합니다. 건물별 확률이나 인원은 설정하지 않습니다.</span>";
    $("#building-npc-assignments").innerHTML = '<div class="issues empty">이 NBT의 npc_position 라벨은 마을 시민을 분산 배치할 수 있는 자리로 사용됩니다.</div>';
  } else {
    banner.className = "building-type-banner fixed";
    banner.innerHTML = `<strong>고정 NPC 건물</strong><span>NBT에서 발견한 ${labels.length}개 라벨에 NPC 콘텐츠를 연결합니다.</span>`;
    $("#building-npc-assignments").innerHTML = labels.length ? labels.map((marker) => `
      <label class="building-npc-row"><span><strong>${escapeHtml(marker.scopedLabel)}</strong><small>상대 위치 ${marker.position.join(", ")}</small></span><select data-building-npc-label="${escapeHtml(marker.scopedLabel)}"><option value="">배정하지 않음</option>${buildingNpcOptions(fixed[marker.scopedLabel] || (marker.scopedLabel.startsWith("exterior:") ? fixed[marker.label] || "" : ""))}</select></label>
    `).join("") : '<div class="issues empty">이 NBT에는 npc_position 라벨이 없습니다. 건축 월드에서 먼저 위치를 지정하세요.</div>';
  }
  $("#building-settings-issues").className = "issues empty";
  $("#building-settings-issues").textContent = view.dirty ? "저장되지 않은 변경 사항이 있습니다." : "저장된 설정입니다.";
}

function buildingConfiguredSpaces(metadata) {
  const spaces = [{ key: "exterior", structure: state.buildingSettings.selected, metadata }];
  for (const entry of metadata.settings?.interiors || []) {
    const interior = state.buildingSettings.structures[entry.structure];
    if (interior) spaces.push({ key: entry.key, structure: entry.structure, metadata: interior });
  }
  return spaces;
}

function buildingInteriorOptions(selected = "") {
  return Object.entries(state.buildingSettings.structures)
    .filter(([, metadata]) => ["interior", "gym_interior"].includes(metadata.category))
    .map(([id]) => `<option value="${escapeHtml(id)}"${id === selected ? " selected" : ""}>${escapeHtml(id)}</option>`)
    .join("");
}

function renderBuildingInteriorAssignments(metadata) {
  const target = $("#building-interior-assignments");
  const entries = metadata.settings?.interiors || [];
  $("#add-building-interior").disabled = ["interior", "gym_interior", "league"].includes(metadata.category);
  target.innerHTML = entries.length ? entries.map((entry, index) => `
    <article class="building-interior-row" data-building-interior="${index}">
      <label><span>공간 키</span><input data-building-interior-field="key" value="${escapeHtml(entry.key || "")}" pattern="[a-z0-9][a-z0-9_]*"></label>
      <label><span>내부 NBT</span><select data-building-interior-field="structure">${buildingInteriorOptions(entry.structure)}</select></label>
      <button type="button" class="button danger" data-remove-building-interior="${index}">연결 해제</button>
    </article>`).join("") : '<div class="issues empty">연결된 내부공간이 없습니다.</div>';
}

function renderBuildingDoorRoutes(metadata, spaces = buildingConfiguredSpaces(metadata)) {
  const target = $("#building-door-routes");
  const arrivals = spaces.flatMap(({ key, metadata: space }) =>
    (space?.arrival_anchors || []).map((anchor) => ({ value: `${key}:${anchor.label}`, label: `${key}:${anchor.label}` }))
  );
  const doors = spaces.flatMap(({ key, metadata: space }) =>
    (space?.door_anchors || []).map((anchor) => ({ key: `${key}:${anchor.label}`, position: anchor.position }))
  );
  target.innerHTML = doors.length ? doors.map((door) => {
    const route = metadata.settings?.door_routes?.[door.key];
    const selected = route ? `${route.space}:${route.arrival}` : "";
    return `<label class="building-door-route"><span><strong>${escapeHtml(door.key)}</strong><small>문 위치 ${door.position.join(", ")}</small></span><select data-building-door-route="${escapeHtml(door.key)}"><option value="">목적지 미지정</option>${arrivals.map((arrival) => `<option value="${escapeHtml(arrival.value)}"${arrival.value === selected ? " selected" : ""}>${escapeHtml(arrival.label)}</option>`).join("")}</select></label>`;
  }).join("") : '<div class="issues empty">이 건물과 연결된 내부 NBT에 이름 있는 문이 없습니다.</div>';
}

function buildingNpcOptions(selected) {
  return state.buildingSettings.npcs.map((npc) => `<option value="${escapeHtml(npc.id)}" ${npc.id === selected ? "selected" : ""}>${escapeHtml(npc.npc_name || npc.name || npc.id)} · ${escapeHtml(npc.id)}</option>`).join("");
}

function markBuildingSettingsDirty() {
  state.buildingSettings.dirty = true;
  renderBuildingList(); renderBuildingEditor(); renderBuildingModel();
}

async function saveBuildingSettings() {
  const buildings = {};
  for (const [id, metadata] of Object.entries(state.buildingSettings.structures)) {
    buildings[id] = {
      placement_y_offset: Number(metadata.settings?.placement_y_offset || 0),
      ...(metadata.settings?.music_track ? { music_track: metadata.settings.music_track } : {}),
      no_interior_space: Boolean(metadata.settings?.no_interior_space),
      fixed_npcs: metadata.settings?.citizen_placement_allowed
        ? {} : { ...(metadata.settings?.fixed_npcs || {}) },
      citizen_placement_allowed: !metadata.settings?.no_interior_space && Boolean(metadata.settings?.citizen_placement_allowed),
      interiors: metadata.settings?.no_interior_space ? [] : (metadata.settings?.interiors || []).map((entry) => ({ key: entry.key, structure: entry.structure })),
      door_routes: metadata.settings?.no_interior_space ? {} : { ...(metadata.settings?.door_routes || {}) }
    };
  }
  const result = await request("/api/building-settings", { method: "PUT", body: JSON.stringify({ schema_version: 1, buildings }) });
  showIssues("#building-settings-issues", result.data);
  if (!result.ok) return toast(result.data.error || "건물 설정을 저장하지 못했습니다.");
  state.buildingSettings.dirty = false;
  renderBuildingEditor();
  toast("건물 설정을 저장했습니다.");
}

async function resizeSelectedBuilding() {
  const view = state.buildingSettings;
  const id = view.selected, metadata = view.structures[id];
  if (!id || !metadata) return;
  if (view.dirty) return toast("먼저 NBT 건물 설정 변경 사항을 저장하세요.");
  const width = Number($("#building-size-width").value);
  const height = Number($("#building-size-height").value);
  const depth = Number($("#building-size-depth").value);
  if (![width, height, depth].every(Number.isInteger)) return toast("NBT 크기는 정수로 입력하세요.");
  const shrinking = width < metadata.width || height < metadata.height || depth < metadata.depth;
  const button = $("#resize-building-nbt");
  const payload = { structure: id, width, height, depth };
  button.disabled = true; button.textContent = "변경 내용 확인 중…";
  try {
    const preview = await request("/api/structure-size", {
      method: "PUT", body: JSON.stringify({ ...payload, preview: true })
    });
    if (!preview.ok) return toast(preview.data.error || "NBT 크기 변경 내용을 확인하지 못했습니다.");
    const anchorConflicts = preview.data.structure?.anchor_conflicts || [];
    const anchorWarning = anchorConflicts.length
      ? `\n\n다음 앵커 ${anchorConflicts.length}개가 지워집니다:\n${anchorConflicts.map((anchor) => `- ${anchor.label}`).join("\n")}\n\n그래도 하시겠습니까?`
      : "\n\n계속하시겠습니까?";
    const message = (shrinking
      ? `${id}를 ${width}×${height}×${depth}으로 축소합니다. 범위 밖 블록은 NBT에서 제거되며 되돌릴 수 없습니다.`
      : `${id}의 작업 영역을 ${width}×${height}×${depth}으로 변경합니다.`) + anchorWarning;
    if (!confirm(message)) return;
    button.textContent = "크기 변경 중…";
    const result = await request("/api/structure-size", {
      method: "PUT", body: JSON.stringify({
        ...payload, remove_out_of_bounds_anchors: anchorConflicts.length > 0
      })
    });
    if (!result.ok) return toast(result.data.error || "NBT 크기를 변경하지 못했습니다.");
    const resized = result.data.structure || {};
    Object.assign(metadata, resized);
    if (state.structureSizes[id]) Object.assign(state.structureSizes[id], resized);
    renderBuildingList();
    renderBuildingEditor();
    void loadBuildingModel(id);
    const removedCount = resized.removed_anchors?.length || 0;
    toast(`${removedCount ? `범위 밖 앵커 ${removedCount}개를 제거하고 ` : ""}NBT 크기를 변경했습니다. 3D 미리보기는 백그라운드에서 갱신합니다.`);
  } finally {
    button.disabled = false; button.textContent = "NBT 크기 적용";
  }
}

async function createInteriorSpace(event) {
  event.preventDefault();
  const form = event.currentTarget;
  if (!form.reportValidity()) return;
  const body = {
    id: form.elements.id.value.trim(), width: Number(form.elements.width.value),
    depth: Number(form.elements.depth.value), floor_height: Number(form.elements.floorHeight.value),
    floors: Number(form.elements.floors.value)
  };
  const result = await request("/api/interior-spaces", { method: "POST", body: JSON.stringify(body) });
  if (!result.ok) return toast(result.data.error || "빈 내부공간 NBT를 만들지 못했습니다.");
  lazyDataLoaded.buildingSettings = false;
  await loadBuildingSettingsData(true);
  await loadBuildingModel(result.data.space.structure);
  form.elements.id.value = "";
  toast("공용 내부공간 NBT를 만들었습니다. 건축 팩을 다시 빌드하면 에딧 월드에서 편집할 수 있습니다.");
}

function rotateMinecraftTopBlock(x, z, width, depth, rotation) {
  if (rotation === "clockwise_90") return { x: depth - 1 - z, z: x };
  if (rotation === "clockwise_180") return { x: width - 1 - x, z: depth - 1 - z };
  if (rotation === "counterclockwise_90") return { x: z, z: width - 1 - x };
  return { x, z };
}

function drawMinecraftStructureTopView(context, plot, project, scale) {
  const palette = plot.topView?.palette;
  const blocks = plot.topView?.blocks;
  if (!Array.isArray(palette) || !Array.isArray(blocks) || !blocks.length) return false;
  const heights = blocks.map((block) => Number(block[2]));
  const minHeight = Math.min(...heights);
  const heightRange = Math.max(1, Math.max(...heights) - minHeight);
  for (const block of blocks) {
    const [localX, localZ, y, paletteIndex] = block.map(Number);
    const blockName = palette[paletteIndex] || "minecraft:unknown";
    const rotated = rotateMinecraftTopBlock(localX, localZ, plot.width, plot.depth, plot.rotation);
    const start = project(plot.x + rotated.x, plot.z + rotated.z);
    const end = project(plot.x + rotated.x + 1, plot.z + rotated.z + 1);
    const heightShade = .82 + ((y - minHeight) / heightRange) * .22;
    const variation = ((localX * 17 + localZ * 31 + paletteIndex * 7) % 5 - 2) * .018;
    context.globalAlpha = /glass|leaves|water/.test(blockName) ? .82 : 1;
    context.fillStyle = shadeMinecraftTopColor(minecraftTopBlockColor(blockName), heightShade + variation);
    context.fillRect(start.x, start.y, Math.max(.7, end.x - start.x + .15), Math.max(.7, end.y - start.y + .15));
    if (scale >= 5) {
      context.strokeStyle = "rgba(24,31,28,.2)";
      context.lineWidth = Math.min(1, scale * .08);
      context.strokeRect(start.x, start.y, end.x - start.x, end.y - start.y);
    }
  }
  context.globalAlpha = 1;
  return true;
}

function normalizedHousePalette(profile = state.settlement?.structure_profile?.generation_profile?.house_palette) {
  const allowed = (values, catalog, fallback) => {
    const valid = new Set(catalog.map((item) => item.id));
    const migrated = Array.isArray(values)
      ? values.map((value) => value === "compact" || value === "wide" ? "one_story" : value)
      : [];
    const selected = [...new Set(migrated.filter((value) => valid.has(value)))];
    return selected.length ? selected : [...fallback];
  };
  return {
    bases: allowed(profile?.bases, houseBaseCatalog, defaultHousePalette.bases),
    roofs: allowed(profile?.roofs, houseRoofCatalog, defaultHousePalette.roofs),
    roof_colors: allowed(profile?.roof_colors, houseRoofColorCatalog, defaultHousePalette.roof_colors)
  };
}

function renderHousePaletteOptions(profile) {
  const palette = normalizedHousePalette(profile);
  const render = (target, name, catalog, selected, color = false) => {
    const enabled = new Set(selected);
    $(target).innerHTML = catalog.map((item) => `<label class="house-option"><input type="checkbox" name="${name}" value="${item.id}"${enabled.has(item.id) ? " checked" : ""} disabled>${color ? `<span class="house-color-swatch" style="--house-color:${item.color}"></span>` : ""}<span>${escapeHtml(item.label)}</span></label>`).join("");
  };
  render("#house-base-options", "houseBase", houseBaseCatalog, palette.bases);
  render("#house-roof-options", "houseRoof", houseRoofCatalog, palette.roofs);
  render("#house-color-options", "houseRoofColor", houseRoofColorCatalog, palette.roof_colors, true);
}

function selectedHousePalette() {
  const selected = (name, fallback) => {
    const values = [...$("#settlement-form").querySelectorAll(`input[name="${name}"]:checked`)].map((input) => input.value);
    return values.length ? values : [...fallback];
  };
  return {
    bases: selected("houseBase", defaultHousePalette.bases),
    roofs: selected("houseRoof", defaultHousePalette.roofs),
    roof_colors: selected("houseRoofColor", defaultHousePalette.roof_colors)
  };
}

function keepHousePaletteGroupSelected(event) {
  const name = event.target?.name;
  if (!new Set(["houseBase", "houseRoof", "houseRoofColor"]).has(name) || event.target.checked) return;
  if ($(`#settlement-form input[name="${name}"]:checked`)) return;
  event.target.checked = true;
  toast("주택 구성의 각 그룹에서 하나 이상 선택해야 합니다.");
}

function selectedCivicFacilities() {
  const form = $("#settlement-form");
  const selected = [];
  if (form.elements.pokemonCenterEnabled.checked) selected.push(civicFacilityCatalog.pokemon_center);
  const commercial = civicFacilityCatalog[form.elements.commercialFacility.value];
  if (commercial) selected.push(commercial);
  return selected.map((facility) => ({
    id: facility.id, label: facility.label, count: 1, required: true,
    structure: facility.structure, color: facility.color,
    footprint: structureFootprint(facility.structure, facility)
  }));
}

function selectedGymFacility() {
  const form = $("#settlement-form");
  if (!form?.elements.gymEnabled?.checked) return [];
  const gym = (state.gymCatalog.gyms || []).find((item) => item.id === form.elements.gymId.value);
  if (!gym) return [];
  const structure = gym.exterior.structure;
  const footprint = structureFootprint(structure, { width: 25, depth: 26, height: 13 });
  if (!footprint.topView?.blocks?.length) {
    footprint.topView = gymFallbackTopView(gym.theme, footprint.width, footprint.depth);
  }
  return [{
    id: "gym_building", label: "체육관", count: 1, required: true,
    structure, color: "#ef233c",
    footprint
  }];
}

function facilityCanonicalEntranceFacing(id) {
  if (["department_store", "facility_department_store"].includes(id)) return "north";
  if (["pokemon_center", "facility_pokemon_center"].includes(id)) return "west";
  if (["pokemart", "facility_pokemart"].includes(id)) return "east";
  if (String(id).includes("gym")) return "west";
  return "north";
}

function facilityEntrancePoint(id, occupiedPlot, facing = facilityCanonicalEntranceFacing(id)) {
  const blockX = Math.round(occupiedPlot.x);
  const blockZ = Math.round(occupiedPlot.z);
  if (["pokemon_center", "facility_pokemon_center"].includes(id)) {
    return { x: blockX - 1, z: blockZ + Math.min(10, occupiedPlot.depth - 1) };
  }
  if (["pokemart", "facility_pokemart"].includes(id)) {
    return { x: blockX + occupiedPlot.width, z: blockZ + Math.min(15, occupiedPlot.depth - 1) };
  }
  return ({
    north: { x: blockX + occupiedPlot.width / 2, z: blockZ - 1 },
    east: { x: blockX + occupiedPlot.width, z: blockZ + occupiedPlot.depth / 2 },
    south: { x: blockX + occupiedPlot.width / 2, z: blockZ + occupiedPlot.depth },
    west: { x: blockX - 1, z: blockZ + (String(id).includes("gym") ? Math.min(10, occupiedPlot.depth - 1) : occupiedPlot.depth / 2) }
  })[facing];
}

function gymFallbackTopView(theme, width = 25, depth = 26) {
  const themeBlock = ({
    fire: "minecraft:red_concrete", water: "minecraft:blue_concrete",
    electric: "minecraft:yellow_concrete", grass: "minecraft:green_concrete",
    ice: "minecraft:light_blue_concrete", fighting: "minecraft:orange_concrete",
    poison: "minecraft:purple_concrete", ground: "minecraft:brown_concrete",
    flying: "minecraft:white_concrete", psychic: "minecraft:magenta_concrete",
    bug: "minecraft:lime_concrete", rock: "minecraft:gray_concrete",
    ghost: "minecraft:purple_concrete", dragon: "minecraft:blue_concrete",
    dark: "minecraft:black_concrete", steel: "minecraft:light_gray_concrete",
    fairy: "minecraft:pink_concrete"
  })[theme] || "minecraft:red_concrete";
  const palette = ["minecraft:stone_bricks", "minecraft:white_concrete", themeBlock];
  const blocks = [];
  const minX = 2, maxX = Math.max(2, width - 3), minZ = 3, maxZ = Math.max(3, depth - 3);
  for (let z = minZ; z <= maxZ; z += 1) for (let x = minX; x <= maxX; x += 1) {
    const border = x === minX || x === maxX || z === minZ || z === maxZ;
    const emblem = Math.abs(x - Math.floor(width / 2)) <= 1 || Math.abs(z - Math.floor(depth / 2)) <= 1;
    blocks.push([x, z, border ? 8 : 10, border ? 0 : emblem ? 1 : 2]);
  }
  for (let x = 0; x < minX; x += 1) for (let z = 9; z <= 11; z += 1) blocks.push([x, z, 1, 0]);
  return { palette, blocks, schematic: true };
}

function facilityLayoutOffsets(shape, count) {
  if (!count) return [];
  if (shape === "linear") {
    return Array.from({ length: count }, (_, index) => ({
      x: Math.round((index - (count - 1) / 2) * 96), z: index % 2 ? 42 : -42, y: 0
    }));
  }
  if (shape === "radial") {
    const radius = Math.max(96, count * 16);
    return Array.from({ length: count }, (_, index) => {
      const angle = -Math.PI / 2 + (Math.PI * 2 * index / count);
      return { x: Math.round(Math.cos(angle) * radius), z: Math.round(Math.sin(angle) * radius), y: 0 };
    });
  }
  if (shape === "loop") {
    const perimeter = [
      [-88, -70], [-30, -82], [32, -82], [90, -68], [104, -8], [92, 60],
      [32, 82], [-32, 82], [-94, 60], [-105, -8]
    ];
    return Array.from({ length: count }, (_, index) => {
      const ring = Math.floor(index / perimeter.length);
      const [x, z] = perimeter[index % perimeter.length];
      const scale = 1 + ring * .65;
      return { x: Math.round(x * scale), z: Math.round(z * scale), y: 0 };
    });
  }
  if (shape === "terraced") {
    const columns = Math.min(4, Math.max(2, Math.ceil(Math.sqrt(count))));
    return Array.from({ length: count }, (_, index) => {
      const row = Math.floor(index / columns);
      const column = index % columns;
      return { x: Math.round((column - (columns - 1) / 2) * 88), z: 58 + row * 76, y: row * 5 };
    });
  }
  const branches = [
    [0, 66], [76, -42], [-78, -24], [68, 48], [-58, 72], [118, 86],
    [-116, 116], [24, 142], [145, -88], [-148, -94], [168, 28], [-172, 42]
  ];
  return Array.from({ length: count }, (_, index) => {
    if (index < branches.length) return { x: branches[index][0], z: branches[index][1], y: 0 };
    const ringIndex = index - branches.length;
    const angle = -Math.PI / 2 + ringIndex * Math.PI / 4;
    const radius = 200 + Math.floor(ringIndex / 8) * 70;
    return { x: Math.round(Math.cos(angle) * radius), z: Math.round(Math.sin(angle) * radius), y: 0 };
  });
}

function facilityTemplatePlacements(requirements, shape, center) {
  const instances = requirements.flatMap((requirement) =>
    Array.from({ length: requirement.count }, (_, index) => ({ requirement, instance: index + 1 }))
  );
  const offsets = facilityLayoutOffsets(shape, instances.length);
  return instances.map(({ requirement, instance }, index) => {
    const offset = offsets[index];
    const anchor = `facility_${requirement.id}_${instance}`;
    return {
      anchor,
      position: {
        x: Math.round(Number(center.x) + offset.x - requirement.footprint.width / 2),
        y: Math.round(Number(center.y) + offset.y),
        z: Math.round(Number(center.z) + offset.z - requirement.footprint.depth / 2)
      },
      placement: {
        id: anchor,
        facility_type: requirement.id,
        mode: "direct_template",
        structure: requirement.structure || `cobbleventure:placeholder/${requirement.id}`,
        anchor,
        label: requirement.count > 1 ? `${requirement.label} ${instance}` : requirement.label,
        footprint: { ...requirement.footprint },
        clearance: 2
      },
      requirement,
      offset
    };
  });
}

function villagePreviewRandom(seed) {
  let value = Number(seed) >>> 0;
  return () => {
    value += 0x6d2b79f5;
    let result = value;
    result = Math.imul(result ^ (result >>> 15), result | 1);
    result ^= result + Math.imul(result ^ (result >>> 7), result | 61);
    return ((result ^ (result >>> 14)) >>> 0) / 4294967296;
  };
}

function villagePreviewRectIntersects(a, b, margin = 0) {
  return a.x - margin < b.x + b.width && a.x + a.width + margin > b.x
    && a.z - margin < b.z + b.depth && a.z + a.depth + margin > b.z;
}

function villagePreviewRoadRect(segment, width) {
  return {
    x: Math.min(segment.x1, segment.x2) - width / 2,
    z: Math.min(segment.z1, segment.z2) - width / 2,
    width: Math.abs(segment.x2 - segment.x1) + width,
    depth: Math.abs(segment.z2 - segment.z1) + width
  };
}

const villagePreviewTileRadius = 64;
const villageDensityProfiles = {
  sparse: { label: "여유로움", plotGap: 8, houseMultiplier: .7, slotRatios: [.22, .5, .78] },
  normal: { label: "보통", plotGap: 4, houseMultiplier: 1, slotRatios: [.15, .32, .5, .68, .85] },
  dense: { label: "밀집", plotGap: 1, houseMultiplier: 1.4, slotRatios: [.08, .22, .36, .5, .64, .78, .92] },
  packed: { label: "빽빽함", plotGap: 0, houseMultiplier: 1.8, slotRatios: [.06, .17, .28, .39, .5, .61, .72, .83, .94] }
};

function normalizeVillageDensity(value) {
  return villageDensityProfiles[value] ? value : "packed";
}

function ensureVillageDensityControl(form) {
  if (form.elements.townBuildingDensity) return;
  const controls = form.querySelector(".village-generation-controls");
  if (!controls) return;
  const label = document.createElement("label");
  label.innerHTML = `<span>건물 밀집도</span><select name="townBuildingDensity">${Object.entries(villageDensityProfiles).map(([value, profile]) => `<option value="${value}">${profile.label}</option>`).join("")}</select>`;
  controls.append(label);
  label.querySelector("select").addEventListener("change", renderVillageGenerationTest);
}

function villageLayoutCells(cellCount, shape = "line_q", customCells = []) {
  const count = normalizeTownCellCount(cellCount);
  if (shape === "custom") return normalizedAxialCells(customCells);
  if (count === 1) return [{ q: 0, r: 0 }];
  if (count === 3 || count === 5) return townFootprintCells({ q: 0, r: 0 }, count, shape);
  return hexArea({ q: 0, r: 0 }, count === 19 ? 2 : 1);
}

function villageLayoutCellCenter(cell) {
  return {
    x: villagePreviewTileRadius * Math.sqrt(3) * (cell.q + cell.r / 2),
    z: villagePreviewTileRadius * 1.5 * cell.r
  };
}
function villageLayoutCentroid(layoutCells) {
  const centers = layoutCells.map(villageLayoutCellCenter);
  if (!centers.length) return { x: 0, z: 0 };
  return {
    x: centers.reduce((sum, center) => sum + center.x, 0) / centers.length,
    z: centers.reduce((sum, center) => sum + center.z, 0) / centers.length
  };
}
function villageLayoutCenteredCellCenter(cell, layoutCells) {
  const center = villageLayoutCellCenter(cell);
  const centroid = villageLayoutCentroid(layoutCells);
  return { x: center.x - centroid.x, z: center.z - centroid.z };
}
function villageLayoutExitPoint(cell, cellCount, shape, customCells = []) {
  const layoutCells = villageLayoutCells(cellCount, shape, customCells);
  const center = villageLayoutCenteredCellCenter(cell, layoutCells);
  const rawCenter = villageLayoutCellCenter(cell); const keys = new Set(normalizedAxialCells(customCells).map(customTownCellKey));
  const available = customTownDirections.filter(([dq, dr]) => !keys.has(`${cell.q + dq},${cell.r + dr}`));
  const radial = Math.hypot(center.x, center.z) < .001 ? { x: 0, z: 1 } : center;
  const [dq, dr] = (available.length ? available : customTownDirections).reduce((best, offset) => {
    const delta = villageLayoutCellCenter({ q: cell.q + offset[0], r: cell.r + offset[1] });
    const bestDelta = villageLayoutCellCenter({ q: cell.q + best[0], r: cell.r + best[1] });
    return (delta.x - rawCenter.x) * radial.x + (delta.z - rawCenter.z) * radial.z > (bestDelta.x - rawCenter.x) * radial.x + (bestDelta.z - rawCenter.z) * radial.z ? offset : best;
  });
  const neighbor = villageLayoutCellCenter({ q: cell.q + dq, r: cell.r + dr }); const dx = neighbor.x - rawCenter.x; const dz = neighbor.z - rawCenter.z; const length = Math.hypot(dx, dz);
  const direction = { x: dx / length, z: dz / length };
  return { x: Math.round((center.x + direction.x * 48) / 16) * 16, z: Math.round((center.z + direction.z * 48) / 16) * 16 };
}

const villageCenterPatterns = [
  { id: "tee_east", directions: [0, 1, 2] },
  { id: "tee_west", directions: [0, 2, 3] },
  { id: "tee_north", directions: [0, 1, 3] },
  { id: "tee_south", directions: [1, 2, 3] }
];

function villageLayoutHub() {
  return { x: 0, z: 0 };
}

function villageCenterPattern(shape, seed) {
  if (shape === "linear") return { id: "linear", directions: [1, 3] };
  if (shape === "terraced") return { id: "terraced", directions: [1, 3, 2] };
  return villageCenterPatterns[(Math.max(1, Number(seed)) - 1) % villageCenterPatterns.length];
}

function villageLayoutHexContains(x, z, center, margin = 0) {
  const usable = Math.max(16, villagePreviewTileRadius - margin);
  const localX = Math.abs(x - center.x);
  const localZ = Math.abs(z - center.z);
  return localZ <= usable
    && localX <= usable * Math.sqrt(3) / 2
    && localZ + localX / Math.sqrt(3) <= usable;
}

function villageLayoutContains(x, z, cellCount, shape, margin = 0, customCells = []) {
  const layoutCells = villageLayoutCells(cellCount, shape, customCells);
  return layoutCells.some((cell) => villageLayoutHexContains(x, z, villageLayoutCenteredCellCenter(cell, layoutCells), margin));
}

function villageRoadCenterInsideLayout(x, z, cellCount, shape, margin = 0, customCells = []) {
  if (margin <= 0) return villageLayoutContains(x, z, cellCount, shape, 0, customCells);
  // Margin belongs only on the outside of the complete town footprint. Shrinking
  // every hex separately creates a false empty strip along shared cell edges and
  // cuts a continuous road into one segment per hex.
  const diagonal = margin / Math.sqrt(2);
  return [[0, 0], [margin, 0], [-margin, 0], [0, margin], [0, -margin],
    [diagonal, diagonal], [diagonal, -diagonal], [-diagonal, diagonal], [-diagonal, -diagonal]]
    .every(([offsetX, offsetZ]) => villageLayoutContains(
      x + offsetX, z + offsetZ, cellCount, shape, 0, customCells
    ));
}

function villagePlotInsideLayout(plot, cellCount, shape, customCells = []) {
  const step = 4;
  const samples = [];
  for (let x = plot.x; x <= plot.x + plot.width; x += step) {
    for (let z = plot.z; z <= plot.z + plot.depth; z += step) samples.push([x, z]);
  }
  samples.push([plot.x + plot.width, plot.z + plot.depth], [plot.x + plot.width / 2, plot.z + plot.depth / 2]);
  return samples.every(([x, z]) => villageLayoutContains(x, z, cellCount, shape, 4, customCells));
}

function simulateJigsawVillage(seed, depth, shape, roadWidth, requirements, cellCount = 1, housePalette = defaultHousePalette, footprintShape = "line_q", customCells = [], roadExits = [], density = "normal", roadTemplate = "cross") {
  const random = villagePreviewRandom(seed);
  const normalizedCellCount = normalizeTownCellCount(cellCount);
  const normalizedFootprintShape = normalizeTownFootprintShape(footprintShape);
  const layoutCells = villageLayoutCells(normalizedCellCount, normalizedFootprintShape, customCells);
  const directions = [{ x: 0, z: -1 }, { x: 1, z: 0 }, { x: 0, z: 1 }, { x: -1, z: 0 }];
  const centerPattern = villageCenterPattern(shape, seed);
  const hub = villageLayoutHub();
  const queue = roadTemplate === "cross"
    ? centerPattern.directions.map((direction) => ({ x: hub.x, z: hub.z, direction, depth: 0 }))
    : [];
  const occupiedRoad = new Set([`${Math.round(hub.x / 16)},${Math.round(hub.z / 16)}`]);
  const roads = [];
  const centers = layoutCells.map((cell) => villageLayoutCenteredCellCenter(cell, layoutCells));
  const scanMinX = Math.floor((Math.min(...centers.map((center) => center.x)) - villagePreviewTileRadius) / 16) * 16;
  const scanMaxX = Math.ceil((Math.max(...centers.map((center) => center.x)) + villagePreviewTileRadius) / 16) * 16;
  const scanMinZ = Math.floor((Math.min(...centers.map((center) => center.z)) - villagePreviewTileRadius) / 16) * 16;
  const scanMaxZ = Math.ceil((Math.max(...centers.map((center) => center.z)) + villagePreviewTileRadius) / 16) * 16;
  const appendClippedTemplateLine = (axis, fixed) => {
    const start = axis === "x" ? scanMinX : scanMinZ;
    const end = axis === "x" ? scanMaxX : scanMaxZ;
    let run = [];
    const flush = () => {
      if (run.length >= 2) {
        roads.push({ x1: run[0][0], z1: run[0][1], x2: run.at(-1)[0], z2: run.at(-1)[1], depth: 0 });
      }
      run = [];
    };
    for (let coordinate = start; coordinate <= end; coordinate += 16) {
      const x = axis === "x" ? coordinate : fixed;
      const z = axis === "x" ? fixed : coordinate;
      if (villageRoadCenterInsideLayout(x, z, normalizedCellCount, normalizedFootprintShape, 8, customCells)) run.push([x, z]);
      else flush();
    }
    flush();
  };
  if (roadTemplate === "grid") {
    for (const offset of [-32, 32]) {
      appendClippedTemplateLine("z", hub.x + offset);
      appendClippedTemplateLine("x", hub.z + offset);
    }
  } else if (roadTemplate === "spine") {
    if (scanMaxX - scanMinX >= scanMaxZ - scanMinZ) {
      appendClippedTemplateLine("x", hub.z);
      for (const offset of [-32, 0, 32]) appendClippedTemplateLine("z", hub.x + offset);
    } else {
      appendClippedTemplateLine("z", hub.x);
      for (const offset of [-32, 0, 32]) appendClippedTemplateLine("x", hub.z + offset);
    }
  } else if (roadTemplate === "ring") {
    const ringX = Math.max(32, Math.floor((scanMaxX - scanMinX) * .25 / 16) * 16);
    const ringZ = Math.max(32, Math.floor((scanMaxZ - scanMinZ) * .25 / 16) * 16);
    for (const offset of [-ringZ, ringZ]) appendClippedTemplateLine("x", hub.z + offset);
    for (const offset of [-ringX, ringX]) appendClippedTemplateLine("z", hub.x + offset);
  }
  const maximumRoads = normalizedCellCount === 19 ? Math.min(36, 6 + depth * 5) : Math.min(20, 3 + depth * 3);
  let rejectedRoads = 0;

  while (queue.length && roads.length < maximumRoads) {
    const connector = queue.shift();
    const vector = directions[connector.direction];
    const cells = connector.depth === 0 ? 2 : 2 + Math.floor(random() * 3);
    const points = [];
    let blocked = false;
    for (let step = 1; step <= cells; step += 1) {
      const cellX = Math.round(connector.x / 16) + vector.x * step;
      const cellZ = Math.round(connector.z / 16) + vector.z * step;
      const key = `${cellX},${cellZ}`;
      if (!villageRoadCenterInsideLayout(cellX * 16, cellZ * 16, normalizedCellCount, normalizedFootprintShape, 8, customCells)) break;
      if (occupiedRoad.has(key) && step > 1) { blocked = true; break; }
      points.push({ key, x: cellX * 16, z: cellZ * 16 });
    }
    if (blocked || points.length < 2) { rejectedRoads += 1; continue; }
    for (const point of points) occupiedRoad.add(point.key);
    const end = points[points.length - 1];
    roads.push({ x1: connector.x, z1: connector.z, x2: end.x, z2: end.z, depth: connector.depth });
    if (connector.depth + 1 >= depth) continue;

    const nextDirections = [connector.direction];
    const branchChance = shape === "linear" ? .12 : shape === "radial" ? .2 : shape === "loop" ? .34 : .55;
    const branchRoll = random();
    if ((shape === "branching" && connector.depth === 0) || branchRoll < branchChance) {
      nextDirections.push((connector.direction + (random() < .5 ? 1 : 3)) % 4);
    }
    for (const direction of [...new Set(nextDirections)]) {
      queue.push({ x: end.x, z: end.z, direction, depth: connector.depth + 1 });
    }
  }

  if (!roads.length) roads.push({ x1: hub.x, z1: hub.z - 32, x2: hub.x, z2: hub.z + 32, depth: 0 });

  const roadKeys = new Set(roads.flatMap((road) => [
    `${road.x1},${road.z1},${road.x2},${road.z2}`,
    `${road.x2},${road.z2},${road.x1},${road.z1}`
  ]));
  const addCoverageRoad = (x1, z1, x2, z2) => {
    if (x1 === x2 && z1 === z2) {
      const roadIndex = roads.findIndex((road) => {
        const insideX = Math.min(road.x1, road.x2) <= x1 && x1 <= Math.max(road.x1, road.x2);
        const insideZ = Math.min(road.z1, road.z2) <= z1 && z1 <= Math.max(road.z1, road.z2);
        const endpoint = (road.x1 === x1 && road.z1 === z1) || (road.x2 === x1 && road.z2 === z1);
        return insideX && insideZ && !endpoint;
      });
      if (roadIndex >= 0) {
        const road = roads[roadIndex];
        roadKeys.delete(`${road.x1},${road.z1},${road.x2},${road.z2}`);
        roadKeys.delete(`${road.x2},${road.z2},${road.x1},${road.z1}`);
        const first = { x1: road.x1, z1: road.z1, x2: x1, z2: z1, depth: road.depth || 0 };
        const second = { x1, z1, x2: road.x2, z2: road.z2, depth: road.depth || 0 };
        roads.splice(roadIndex, 1, first, second);
        for (const item of [first, second]) {
          roadKeys.add(`${item.x1},${item.z1},${item.x2},${item.z2}`);
          roadKeys.add(`${item.x2},${item.z2},${item.x1},${item.z1}`);
        }
      }
      return;
    }
    const key = `${x1},${z1},${x2},${z2}`;
    if (roadKeys.has(key)) return;
    roads.push({ x1, z1, x2, z2, depth: 0 });
    roadKeys.add(key);
    roadKeys.add(`${x2},${z2},${x1},${z1}`);
  };
  const addCellBranchRoad = (targetX, targetZ, sourceX, sourceZ, preferredAxis = null) => {
    // Coverage roads only used to bring the network to an outer cell center.
    // Continue sideways from that center as well, otherwise outer cells become
    // a single dead-end street even when most of the cell is still empty.
    const horizontal = sourceZ !== targetZ;
    const axis = preferredAxis || (horizontal ? "x" : "z");
    const available = (direction) => {
      let distance = 0;
      for (let step = 1; step <= 3; step += 1) {
        const x = axis === "x" ? targetX + direction * step * 16 : targetX;
        const z = axis === "z" ? targetZ + direction * step * 16 : targetZ;
        if (!villageRoadCenterInsideLayout(x, z, normalizedCellCount, normalizedFootprintShape, 8, customCells)) break;
        distance = step * 16;
      }
      return distance;
    };
    const negative = available(-1);
    const positive = available(1);
    if (negative + positive < 32) return;
    if (axis === "x") addCoverageRoad(targetX - negative, targetZ, targetX + positive, targetZ);
    else addCoverageRoad(targetX, targetZ - negative, targetX, targetZ + positive);
  };
  const coverageSources = roads.flatMap((road) => [[road.x1, road.z1], [road.x2, road.z2]])
    .filter(([x, z]) => x !== hub.x || z !== hub.z);
  for (const cell of layoutCells) {
    const center = villageLayoutCenteredCellCenter(cell, layoutCells);
    const targetX = Math.round(center.x / 16) * 16;
    const targetZ = Math.round(center.z / 16) * 16;
    if (targetX === hub.x && targetZ === hub.z) continue;
    if (roadTemplate !== "cross") {
      const nearestTemplate = roads.map((road) => {
        const nearestX = Math.min(Math.max(targetX, Math.min(road.x1, road.x2)), Math.max(road.x1, road.x2));
        const nearestZ = Math.min(Math.max(targetZ, Math.min(road.z1, road.z2)), Math.max(road.z1, road.z2));
        return {
          x: nearestX, z: nearestZ,
          distance: (targetX - nearestX) ** 2 + (targetZ - nearestZ) ** 2,
          branchAxis: road.z1 === road.z2 ? "z" : "x"
        };
      }).sort((left, right) => left.distance - right.distance)[0];
      if (nearestTemplate.distance <= 40 ** 2) {
        addCoverageRoad(nearestTemplate.x, nearestTemplate.z, targetX, targetZ);
        addCellBranchRoad(targetX, targetZ, nearestTemplate.x, nearestTemplate.z, nearestTemplate.branchAxis);
        coverageSources.push([targetX, targetZ]);
        continue;
      }
    }
    const candidates = coverageSources.length ? coverageSources : [[hub.x, hub.z]];
    const [sourceX, sourceZ] = candidates.reduce((best, point) =>
      Math.abs(point[0] - targetX) + Math.abs(point[1] - targetZ) < Math.abs(best[0] - targetX) + Math.abs(best[1] - targetZ) ? point : best
    );
    addCoverageRoad(sourceX, sourceZ, targetX, sourceZ);
    addCoverageRoad(targetX, sourceZ, targetX, targetZ);
    addCellBranchRoad(targetX, targetZ, sourceX, sourceZ);
    coverageSources.push([targetX, targetZ]);
  }
  for (const exit of normalizedAxialCells(roadExits)) {
    if (!layoutCells.some((cell) => cell.q === exit.q && cell.r === exit.r)) continue;
    const target = villageLayoutExitPoint(exit, normalizedCellCount, normalizedFootprintShape, customCells);
    const targetX = target.x; const targetZ = target.z;
    const [sourceX, sourceZ] = coverageSources.reduce((best, point) =>
      Math.abs(point[0] - targetX) + Math.abs(point[1] - targetZ) < Math.abs(best[0] - targetX) + Math.abs(best[1] - targetZ) ? point : best,
      [hub.x, hub.z]
    );
    addCoverageRoad(sourceX, sourceZ, targetX, sourceZ);
    addCoverageRoad(targetX, sourceZ, targetX, targetZ);
    coverageSources.push([targetX, targetZ]);
  }

  const densityProfile = villageDensityProfiles[normalizeVillageDensity(density)];
  const plots = [];
  const missing = [];
  const slots = roads.flatMap((road, roadIndex) => densityProfile.slotRatios.flatMap((ratio) => [-1, 1].map((side) => ({ road, roadIndex, ratio, side }))));
  const roadRects = roads.map((road) => villagePreviewRoadRect(road, roadWidth + 3));
  const blockedRoadIndices = new Set();

  function tryPlacePlot(definition, kind, label, attempts = slots.length, balanceCells = false) {
    const width = Number(definition.footprint?.width || definition.width || 16);
    const depthSize = Number(definition.footprint?.depth || definition.depth || 16);
    const height = Number(definition.footprint?.height || definition.height || 1);
    const occupied = definition.footprint?.occupied || {
      min_x: 0, min_z: 0, max_x: width - 1, max_z: depthSize - 1,
      width, depth: depthSize
    };
    const startSlot = Math.floor(random() * Math.max(1, slots.length));
    const validCandidates = [];
    for (let attempt = 0; attempt < Math.min(attempts, slots.length); attempt += 1) {
      const slot = slots[(startSlot + attempt) % slots.length];
      if (!slot) break;
      if (blockedRoadIndices.has(slot.roadIndex)) continue;
      const road = slot.road;
      const horizontal = road.z1 === road.z2;
      const alongX = road.x1 + (road.x2 - road.x1) * slot.ratio;
      const alongZ = road.z1 + (road.z2 - road.z1) * slot.ratio;
      const roadFacing = horizontal ? (slot.side < 0 ? "south" : "north") : (slot.side < 0 ? "east" : "west");
      const fixedFacilityFacing = kind === "facility" ? facilityCanonicalEntranceFacing(definition.id) : null;
      if (fixedFacilityFacing && roadFacing !== fixedFacilityFacing) continue;
      const occupiedCenterX = (Number(occupied.min_x) + Number(occupied.max_x) + 1) / 2;
      const occupiedCenterZ = (Number(occupied.min_z) + Number(occupied.max_z) + 1) / 2;
      const originX = horizontal
        ? alongX - occupiedCenterX
        : slot.side > 0
          ? alongX + roadWidth / 2 - Number(occupied.min_x)
          : alongX - roadWidth / 2 - Number(occupied.max_x) - 1;
      const originZ = horizontal
        ? slot.side > 0
          ? alongZ + roadWidth / 2 - Number(occupied.min_z)
          : alongZ - roadWidth / 2 - Number(occupied.max_z) - 1
        : alongZ - occupiedCenterZ;
      const plot = {
        x: originX, z: originZ,
        id: definition.id, width, depth: depthSize, height, kind, label,
        structure: definition.structure || "",
        topView: definition.footprint?.topView || null,
        nbtResolved: Boolean(definition.footprint?.nbtResolved),
        nbtSource: definition.footprint?.source || ""
      };
      plot.occupied = {
        x: originX + Number(occupied.min_x), z: originZ + Number(occupied.min_z),
        width: Number(occupied.width), depth: Number(occupied.depth)
      };
      if (kind === "house" || kind === "facility") {
        const facing = kind === "house" ? roadFacing : fixedFacilityFacing;
        plot.entrance_facing = facing;
        plot.rotation = kind === "house" ? ({ north: "none", east: "clockwise_90", south: "clockwise_180", west: "counterclockwise_90" })[facing] : "none";
        plot.road_connection = { x: Math.round(alongX), z: Math.round(alongZ) };
        plot.entrance = facilityEntrancePoint(definition.id, plot.occupied, facing);
      }
      if (!villagePlotInsideLayout(plot.occupied, normalizedCellCount, normalizedFootprintShape, customCells)) continue;
      if (plots.some((other) => villagePreviewRectIntersects(plot.occupied, other.occupied || other, densityProfile.plotGap))) continue;
      if (roadRects.some((roadRect, index) => index !== slot.roadIndex && !blockedRoadIndices.has(index) && villagePreviewRectIntersects(plot.occupied, roadRect, 1))) continue;
      if (!balanceCells) {
        plots.push(plot);
        return true;
      }
      validCandidates.push({ plot, attempt });
    }
    if (validCandidates.length) {
      const centers = layoutCells.map((cell) => villageLayoutCenteredCellCenter(cell, layoutCells));
      const cellIndex = (plot) => {
        const occupied = plot.occupied || plot;
        const centerX = occupied.x + occupied.width / 2;
        const centerZ = occupied.z + occupied.depth / 2;
        return centers.reduce((best, center, index) => {
          const distance = (centerX - center.x) ** 2 + (centerZ - center.z) ** 2;
          return distance < best.distance ? { index, distance } : best;
        }, { index: 0, distance: Number.POSITIVE_INFINITY }).index;
      };
      const occupancy = Array(centers.length).fill(0);
      plots.forEach((plot) => { occupancy[cellIndex(plot)] += 1; });
      validCandidates.sort((left, right) => {
        const occupancyDifference = occupancy[cellIndex(left.plot)] - occupancy[cellIndex(right.plot)];
        return occupancyDifference || left.attempt - right.attempt;
      });
      plots.push(validCandidates[0].plot);
      return true;
    }
    return false;
  }

  function tryPlaceGridFacility(definition, label) {
    const width = Number(definition.footprint?.width || definition.width || 16);
    const depthSize = Number(definition.footprint?.depth || definition.depth || 16);
    const height = Number(definition.footprint?.height || definition.height || 1);
    const occupied = definition.footprint?.occupied || {
      min_x: 0, min_z: 0, max_x: width - 1, max_z: depthSize - 1,
      width, depth: depthSize
    };
    const centers = layoutCells.map((cell) => villageLayoutCenteredCellCenter(cell, layoutCells));
    const bounds = {
      minX: Math.floor(Math.min(...centers.map((center) => center.x)) - villagePreviewTileRadius),
      maxX: Math.ceil(Math.max(...centers.map((center) => center.x)) + villagePreviewTileRadius),
      minZ: Math.floor(Math.min(...centers.map((center) => center.z)) - villagePreviewTileRadius),
      maxZ: Math.ceil(Math.max(...centers.map((center) => center.z)) + villagePreviewTileRadius)
    };
    const candidates = [];
    for (let occupiedX = bounds.minX; occupiedX <= bounds.maxX - Number(occupied.width); occupiedX += 8) {
      for (let occupiedZ = bounds.minZ; occupiedZ <= bounds.maxZ - Number(occupied.depth); occupiedZ += 8) {
        const occupiedPlot = { x: occupiedX, z: occupiedZ, width: Number(occupied.width), depth: Number(occupied.depth) };
        if (!villagePlotInsideLayout(occupiedPlot, normalizedCellCount, normalizedFootprintShape, customCells)) continue;
        if (plots.some((other) => villagePreviewRectIntersects(occupiedPlot, other.occupied || other, 4))) continue;
        const centerX = occupiedX + occupiedPlot.width / 2;
        const centerZ = occupiedZ + occupiedPlot.depth / 2;
        const usableRoads = roads.map((road, roadIndex) => ({ road, roadIndex })).filter(({ roadIndex }) => !blockedRoadIndices.has(roadIndex));
        const intersectingRoads = usableRoads.filter(({ road }) => villagePreviewRectIntersects(occupiedPlot, villagePreviewRoadRect(road, roadWidth), .5)).length;
        const roadDistances = usableRoads.map(({ road }) => {
          const nearestX = Math.min(Math.max(centerX, Math.min(road.x1, road.x2)), Math.max(road.x1, road.x2));
          const nearestZ = Math.min(Math.max(centerZ, Math.min(road.z1, road.z2)), Math.max(road.z1, road.z2));
          return (centerX - nearestX) ** 2 + (centerZ - nearestZ) ** 2;
        });
        const roadDistance = roadDistances.length ? Math.min(...roadDistances) : 0;
        const centerDistance = (centerX - hub.x) ** 2 + (centerZ - hub.z) ** 2;
        const departmentStore = ["department_store", "facility_department_store"].includes(definition.id);
        const plazaZ = occupiedZ + Math.min(19, Number(occupied.depth) - 1);
        const rearZ = occupiedZ + Number(occupied.depth) - 1 - Math.min(19, Number(occupied.depth) - 1);
        const plazaDistance = (centerX - hub.x) ** 2 + (plazaZ - hub.z) ** 2;
        const rearDistance = (centerX - hub.x) ** 2 + (rearZ - hub.z) ** 2;
        const score = departmentStore
          ? [intersectingRoads, plazaDistance > rearDistance ? 1 : 0, plazaDistance, roadDistance]
          : [intersectingRoads, roadDistance, centerDistance];
        // Removing an entire intersecting road segment can disconnect every
        // outer-cell road behind this lot. A grid facility must therefore use
        // a genuinely road-free lot; callers can still try a roadside slot or
        // reroll the layout when no such lot exists.
        if (intersectingRoads > 0) continue;
        candidates.push({ occupiedPlot, score });
      }
    }
    if (!candidates.length) return false;
    candidates.sort((left, right) => {
      for (let index = 0; index < left.score.length; index += 1) {
        if (left.score[index] !== right.score[index]) return left.score[index] - right.score[index];
      }
      return 0;
    });
    const occupiedPlot = candidates[0].occupiedPlot;
    const plot = {
      x: occupiedPlot.x - Number(occupied.min_x),
      z: occupiedPlot.z - Number(occupied.min_z),
      id: definition.id, width, depth: depthSize, height, kind: "facility", label,
      structure: definition.structure || "",
      topView: definition.footprint?.topView || null,
      nbtResolved: Boolean(definition.footprint?.nbtResolved),
      nbtSource: definition.footprint?.source || "",
      occupied: occupiedPlot
    };
    const facing = facilityCanonicalEntranceFacing(definition.id);
    const entrance = facilityEntrancePoint(definition.id, occupiedPlot, facing);
    const roadPoints = roads.flatMap((road) => {
      const x = Math.min(Math.max(entrance.x, Math.min(road.x1, road.x2)), Math.max(road.x1, road.x2));
      const z = Math.min(Math.max(entrance.z, Math.min(road.z1, road.z2)), Math.max(road.z1, road.z2));
      return [{ x, z, distance: (x - entrance.x) ** 2 + (z - entrance.z) ** 2 }];
    });
    const roadConnection = roadPoints.length ? roadPoints.reduce((best, point) => point.distance < best.distance ? point : best) : hub;
    Object.assign(plot, { entrance_facing: facing, rotation: "none", entrance, road_connection: { x: Math.round(roadConnection.x), z: Math.round(roadConnection.z) } });
    roads.forEach((road, roadIndex) => {
      if (villagePreviewRectIntersects(occupiedPlot, villagePreviewRoadRect(road, roadWidth), .5)) blockedRoadIndices.add(roadIndex);
    });
    plots.push(plot);
    return true;
  }

  const facilityInstances = requirements.flatMap((requirement) =>
    Array.from({ length: Math.max(1, Number(requirement.count || 1)) }, (_, index) => ({
      ...requirement,
      instanceLabel: Number(requirement.count || 1) > 1 ? `${requirement.label} ${index + 1}` : requirement.label
    }))
  ).sort((left, right) => {
    const leftArea = Number(left.footprint?.occupied?.width || left.footprint?.width || left.width || 16)
      * Number(left.footprint?.occupied?.depth || left.footprint?.depth || left.depth || 16);
    const rightArea = Number(right.footprint?.occupied?.width || right.footprint?.width || right.width || 16)
      * Number(right.footprint?.occupied?.depth || right.footprint?.depth || right.depth || 16);
    return rightArea - leftArea;
  });
  for (const facility of facilityInstances) {
    const departmentStore = ["department_store", "facility_department_store"].includes(facility.id);
    const placed = departmentStore
      ? tryPlaceGridFacility(facility, facility.instanceLabel)
        || tryPlacePlot(facility, "facility", facility.instanceLabel, slots.length * 4)
      : tryPlacePlot(facility, "facility", facility.instanceLabel, slots.length * 4)
        || tryPlaceGridFacility(facility, facility.instanceLabel);
    if (!placed) missing.push(facility.instanceLabel);
  }

  const baseHouseTarget = normalizedCellCount === 19 ? Math.min(36, Math.max(12, 6 + depth * 5)) : Math.min(18, Math.max(4, 3 + depth * 3));
  const houseTarget = Math.max(2, Math.round(baseHouseTarget * densityProfile.houseMultiplier));
  const palette = normalizedHousePalette(housePalette);
  for (let index = 0; index < houseTarget; index += 1) {
    const base = houseBaseCatalog.find((item) => item.id === palette.bases[Math.floor(random() * palette.bases.length)]) || houseBaseCatalog[0];
    const roof = palette.roofs[Math.floor(random() * palette.roofs.length)];
    const roofColor = palette.roof_colors[Math.floor(random() * palette.roof_colors.length)];
    const structure = `cobbleventure:houses/${base.id}_${roof}_${roofColor}`;
    const houseDefinition = {
      ...base, structure,
      footprint: structureFootprint(structure, base)
    };
    const before = plots.length;
    tryPlacePlot(
      houseDefinition, "house",
      `${base.label} · ${houseRoofCatalog.find((item) => item.id === roof)?.label || roof}`,
      slots.length * 2, true
    );
    if (plots.length > before) Object.assign(plots.at(-1), { base: base.id, roof, roof_color: roofColor });
  }
  const accessRoads = [];
  for (const plot of plots.filter((candidate) => candidate.entrance && candidate.road_connection)) {
    const entrances = plot.id === "department_store" ? [
      { facing: "north", x: plot.occupied.x + plot.occupied.width / 2, z: plot.occupied.z - 1 },
      { facing: "west", x: plot.occupied.x - 1, z: plot.occupied.z + Math.min(19, plot.occupied.depth - 1) },
      { facing: "east", x: plot.occupied.x + plot.occupied.width, z: plot.occupied.z + Math.min(19, plot.occupied.depth - 1) }
    ] : [{ facing: plot.entrance_facing, ...plot.entrance }];
    plot.entrance = entrances[0];
    plot.plazaEntrances = entrances;
    for (let entranceIndex = 0; entranceIndex < entrances.length; entranceIndex += 1) {
      const entrance = entrances[entranceIndex];
      let connection = entranceIndex === 0 ? plot.road_connection : null;
      if (!connection) {
        const candidates = roads.map((road) => {
          const x = Math.min(Math.max(entrance.x, Math.min(road.x1, road.x2)), Math.max(road.x1, road.x2));
          const z = Math.min(Math.max(entrance.z, Math.min(road.z1, road.z2)), Math.max(road.z1, road.z2));
          const sameSide = (entrance.facing === "north" && z <= plot.occupied.z)
            || (entrance.facing === "south" && z >= plot.occupied.z + plot.occupied.depth)
            || (entrance.facing === "west" && x <= plot.occupied.x)
            || (entrance.facing === "east" && x >= plot.occupied.x + plot.occupied.width);
          return { x, z, sameSide, distance: (x - entrance.x) ** 2 + (z - entrance.z) ** 2 };
        });
        const sameSide = candidates.filter((candidate) => candidate.sameSide);
        connection = (sameSide.length ? sameSide : candidates).sort((left, right) => left.distance - right.distance)[0];
      }
      if (!connection) continue;
      let roadX = connection.x, roadZ = connection.z;
      if (roadX !== entrance.x && roadZ !== entrance.z) {
        const corner = ["east", "west"].includes(entrance.facing) ? { x: entrance.x, z: roadZ } : { x: roadX, z: entrance.z };
        accessRoads.push({ building: plot.label, x1: roadX, z1: roadZ, x2: corner.x, z2: corner.z });
        roadX = corner.x; roadZ = corner.z;
      }
      accessRoads.push({ building: plot.label, x1: roadX, z1: roadZ, x2: entrance.x, z2: entrance.z });
    }
  }
  const visibleRoads = roads.filter((_, index) => !blockedRoadIndices.has(index));
  const decorations = [];
  const tryAddDecoration = (type, x, z, clearance) => {
    const footprint = { x: x - clearance, z: z - clearance, width: clearance * 2 + 1, depth: clearance * 2 + 1 };
    if (!villagePlotInsideLayout(footprint, normalizedCellCount, normalizedFootprintShape, customCells)) return;
    if (plots.some((plot) => villagePreviewRectIntersects(footprint, plot.occupied || plot, 1))) return;
    if (accessRoads.some((road) => villagePreviewRectIntersects(footprint, villagePreviewRoadRect(road, 3), .75))) return;
    const minimumSpacing = type === "street_tree" ? 5 : 4;
    if (decorations.some((item) => (x - item.x) ** 2 + (z - item.z) ** 2 < minimumSpacing ** 2)) return;
    decorations.push({ type, x, z });
  };
  const roadEdge = Math.ceil(roadWidth / 2);
  visibleRoads.forEach((road, roadIndex) => {
    const deltaX = road.x2 - road.x1;
    const deltaZ = road.z2 - road.z1;
    const length = Math.abs(deltaX) + Math.abs(deltaZ);
    if (length < 20) return;
    const directionX = deltaX === 0 ? 0 : Math.sign(deltaX);
    const directionZ = deltaZ === 0 ? 0 : Math.sign(deltaZ);
    const perpendicularX = -directionZ;
    const perpendicularZ = directionX;
    let markerIndex = 0;
    for (let distance = 10; distance < length - 9; distance += 24, markerIndex += 1) {
      const side = (roadIndex + markerIndex) % 2 === 0 ? 1 : -1;
      const centerX = road.x1 + directionX * distance;
      const centerZ = road.z1 + directionZ * distance;
      tryAddDecoration(
        "street_lamp",
        centerX + perpendicularX * side * (roadEdge + 2),
        centerZ + perpendicularZ * side * (roadEdge + 2),
        1
      );
      const treeDistance = distance + 12;
      if (treeDistance <= length - 10) {
        tryAddDecoration(
          "street_tree",
          road.x1 + directionX * treeDistance - perpendicularX * side * (roadEdge + 4),
          road.z1 + directionZ * treeDistance - perpendicularZ * side * (roadEdge + 4),
          3
        );
      }
    }
  });
  return { roads: visibleRoads, accessRoads, decorations, plots, missing, rejectedRoads, openConnectors: queue.length, layoutCells, hub, centerPattern: centerPattern.id, roadLayoutTemplate: roadTemplate };
}

const villageLayoutRerollLimit = 8;
const villageLayoutRerollStep = 104729;

function villageLayoutRerollSeed(seed, attempt) {
  return 1 + ((Math.max(1, Number(seed)) - 1 + attempt * villageLayoutRerollStep) % 999999999);
}

function simulateVillageWithRerolls(seed, depth, shape, roadWidth, requirements, cellCount = 1, housePalette = defaultHousePalette, footprintShape = "line_q", customCells = [], roadExits = [], density = "normal", roadTemplate = "cross") {
  let result = null;
  for (let attempt = 0; attempt < villageLayoutRerollLimit; attempt += 1) {
    const resolvedSeed = villageLayoutRerollSeed(seed, attempt);
    result = simulateJigsawVillage(resolvedSeed, depth, shape, roadWidth, requirements, cellCount, housePalette, footprintShape, customCells, roadExits, density, roadTemplate);
    Object.assign(result, { requestedSeed: seed, resolvedSeed, rerollCount: attempt, rerollLimit: villageLayoutRerollLimit });
    if (!result.missing.length) return result;
  }
  return result;
}

function ensureVillageViewControls(canvas) {
  if (canvas.dataset.viewControlsReady === "true") return;
  canvas.dataset.viewControlsReady = "true";
  const controls = document.createElement("div");
  controls.className = "village-view-controls";
  controls.innerHTML = `<button type="button" data-village-zoom="out" title="축소">−</button><button type="button" data-village-zoom="reset" title="화면 맞춤">100%</button><button type="button" data-village-zoom="in" title="확대">＋</button><span data-village-position>휠 확대 · 드래그 이동 · 1칸=1블록</span>`;
  canvas.insertAdjacentElement("afterend", controls);
  const changeZoom = (nextZoom, anchorX = canvas.width / 2, anchorY = canvas.height / 2) => {
    const view = state.villageView;
    const oldZoom = view.zoom;
    const zoom = Math.max(.35, Math.min(8, nextZoom));
    const ratio = zoom / oldZoom;
    view.panX = anchorX - canvas.width / 2 - (anchorX - canvas.width / 2 - view.panX) * ratio;
    view.panY = anchorY - canvas.height / 2 - (anchorY - canvas.height / 2 - view.panY) * ratio;
    view.zoom = zoom;
    renderVillageGenerationTest();
  };
  controls.addEventListener("click", (event) => {
    const action = event.target.closest("[data-village-zoom]")?.dataset.villageZoom;
    if (!action) return;
    if (action === "reset") {
      Object.assign(state.villageView, { zoom: 1, panX: 0, panY: 0, drag: null });
      renderVillageGenerationTest();
    } else {
      changeZoom(state.villageView.zoom * (action === "in" ? 1.25 : .8));
    }
  });
  canvas.addEventListener("wheel", (event) => {
    event.preventDefault();
    const bounds = canvas.getBoundingClientRect();
    const x = (event.clientX - bounds.left) * canvas.width / bounds.width;
    const y = (event.clientY - bounds.top) * canvas.height / bounds.height;
    changeZoom(state.villageView.zoom * (event.deltaY < 0 ? 1.14 : .877), x, y);
  }, { passive: false });
  canvas.addEventListener("pointerdown", (event) => {
    const view = state.villageView;
    view.drag = { x: event.clientX, y: event.clientY, panX: view.panX, panY: view.panY };
    canvas.setPointerCapture(event.pointerId);
    canvas.classList.add("is-panning");
  });
  canvas.addEventListener("pointermove", (event) => {
    const view = state.villageView;
    const bounds = canvas.getBoundingClientRect();
    const screenX = (event.clientX - bounds.left) * canvas.width / bounds.width;
    const screenY = (event.clientY - bounds.top) * canvas.height / bounds.height;
    if (view.drag) {
      view.panX = view.drag.panX + (event.clientX - view.drag.x) * canvas.width / bounds.width;
      view.panY = view.drag.panY + (event.clientY - view.drag.y) * canvas.height / bounds.height;
      renderVillageGenerationTest();
      return;
    }
    const fitScale = Number(canvas.dataset.fitScale || 1);
    const worldX = Number(canvas.dataset.worldCenterX || 0) + (screenX - canvas.width / 2 - view.panX) / (fitScale * view.zoom);
    const worldZ = Number(canvas.dataset.worldCenterZ || 0) + (screenY - canvas.height / 2 - view.panY) / (fitScale * view.zoom);
    const position = controls.querySelector("[data-village-position]");
    position.textContent = `X ${Math.round(worldX)} · Z ${Math.round(worldZ)} · ${Math.round(view.zoom * 100)}% · 1칸=1블록`;
  });
  const stopPan = () => {
    state.villageView.drag = null;
    canvas.classList.remove("is-panning");
  };
  canvas.addEventListener("pointerup", stopPan);
  canvas.addEventListener("pointercancel", stopPan);
  canvas.addEventListener("pointerleave", () => {
    if (!state.villageView.drag) controls.querySelector("[data-village-position]").textContent = `휠 확대 · 드래그 이동 · ${Math.round(state.villageView.zoom * 100)}% · 1칸=1블록`;
  });
}

function renderVillageGenerationTest() {
  const canvas = $("#village-generation-canvas");
  const summary = $("#village-generation-summary");
  if (!canvas || !summary || !state.settlement) return;
  ensureVillageViewControls(canvas);
  const form = $("#settlement-form");
  ensureVillageDensityControl(form);
  const seed = Math.max(1, Number(form.elements.villagePreviewSeed.value || 1));
  const depth = Math.max(1, Math.min(7, Number(form.elements.villagePreviewDepth.value || 4)));
  const shape = form.elements.townLayoutShape.value || "branching";
  const roadTemplate = form.elements.townRoadLayoutTemplate?.value || "cross";
  const roadWidth = Number(form.elements.townRoadWidth.value || 7);
  const requirements = [...selectedCivicFacilities(), ...selectedFacilityRequirements(), ...selectedGymFacility()];
  const radiusCells = normalizeTownCellCount(form.elements.townRadiusCells.value);
  const footprintShape = normalizeTownFootprintShape(form.elements.townFootprintShape.value);
  const density = normalizeVillageDensity(form.elements.townBuildingDensity?.value);
  const result = simulateVillageWithRerolls(seed, depth, shape, roadWidth, requirements, radiusCells, selectedHousePalette(), footprintShape, customTownCells(), customTownExits(), density, roadTemplate);
  const viewport = ({
    1: { width: 560, height: 320 },
    3: { width: 760, height: 430 },
    5: { width: 820, height: 480 },
    7: { width: 900, height: 520 },
    19: { width: 1180, height: 720 }
  })[radiusCells];
  canvas.width = viewport.width;
  canvas.height = viewport.height;
  canvas.style.maxWidth = `${viewport.width}px`;
  canvas.dataset.townCellCount = String(radiusCells);
  const context = canvas.getContext("2d");
  const previewPadding = 28;
  const centers = result.layoutCells.map((cell) => villageLayoutCenteredCellCenter(cell, result.layoutCells));
  const hexHalfWidth = villagePreviewTileRadius * Math.sqrt(3) / 2;
  const minX = Math.min(...centers.map((center) => center.x - hexHalfWidth)) - previewPadding;
  const maxX = Math.max(...centers.map((center) => center.x + hexHalfWidth)) + previewPadding;
  const minZ = Math.min(...centers.map((center) => center.z - villagePreviewTileRadius)) - previewPadding;
  const maxZ = Math.max(...centers.map((center) => center.z + villagePreviewTileRadius)) + previewPadding;
  const fitScale = Math.min(canvas.width / Math.max(1, maxX - minX), canvas.height / Math.max(1, maxZ - minZ));
  const worldCenterX = (minX + maxX) / 2;
  const worldCenterZ = (minZ + maxZ) / 2;
  const view = state.villageView;
  const scale = fitScale * view.zoom;
  const project = (x, z) => ({
    x: canvas.width / 2 + view.panX + (x - worldCenterX) * scale,
    y: canvas.height / 2 + view.panY + (z - worldCenterZ) * scale
  });
  canvas.dataset.fitScale = String(fitScale);
  canvas.dataset.worldCenterX = String(worldCenterX);
  canvas.dataset.worldCenterZ = String(worldCenterZ);
  context.clearRect(0, 0, canvas.width, canvas.height);
  context.fillStyle = "#c9d7b3";
  context.fillRect(0, 0, canvas.width, canvas.height);
  context.fillStyle = "rgba(255,255,255,.2)";
  context.strokeStyle = "rgba(58,91,53,.75)";
  context.lineWidth = 2;
  for (const cell of result.layoutCells) {
    const center = villageLayoutCenteredCellCenter(cell, result.layoutCells);
    const hexCorners = Array.from({ length: 6 }, (_, index) => {
      const angle = -Math.PI / 2 + Math.PI / 3 * index;
      return project(center.x + Math.cos(angle) * villagePreviewTileRadius, center.z + Math.sin(angle) * villagePreviewTileRadius);
    });
    context.beginPath();
    hexCorners.forEach((point, index) => index ? context.lineTo(point.x, point.y) : context.moveTo(point.x, point.y));
    context.closePath(); context.fill(); context.stroke();
  }
  const drawBlockGrid = (spacing, color, lineWidth) => {
    context.strokeStyle = color; context.lineWidth = lineWidth;
    for (let x = Math.ceil(minX / spacing) * spacing; x <= maxX; x += spacing) {
      const start = project(x, minZ); const end = project(x, maxZ);
      context.beginPath(); context.moveTo(start.x, start.y); context.lineTo(end.x, end.y); context.stroke();
    }
    for (let z = Math.ceil(minZ / spacing) * spacing; z <= maxZ; z += spacing) {
      const start = project(minX, z); const end = project(maxX, z);
      context.beginPath(); context.moveTo(start.x, start.y); context.lineTo(end.x, end.y); context.stroke();
    }
  };
  if (scale >= 4) drawBlockGrid(1, "rgba(65,88,60,.1)", 1);
  else if (scale >= 1.5) drawBlockGrid(4, "rgba(65,88,60,.12)", 1);
  drawBlockGrid(16, "rgba(54,76,49,.3)", 1);
  if (shape === "terraced") {
    context.strokeStyle = "rgba(92,85,63,.3)";
    for (let z = Math.ceil(minZ / 64) * 64; z <= maxZ; z += 64) {
      const start = project(minX, z); const end = project(maxX, z);
      context.beginPath(); context.moveTo(start.x, start.y); context.lineTo(end.x, end.y); context.stroke();
    }
  }
  const roadColors = { cobblestone: "#8d9292", stone_bricks: "#727b80", bricks: "#a05245", grass_path: "#8a704f", gravel: "#aaa397", packed_mud: "#846b55", sandstone: "#d4ba7d", snow: "#d9e9ed" };
  context.lineCap = "square";
  for (const road of result.roads) {
    const start = project(road.x1, road.z1); const end = project(road.x2, road.z2);
    context.strokeStyle = "rgba(49,58,54,.45)"; context.lineWidth = Math.max(4, (roadWidth + 3) * scale);
    context.beginPath(); context.moveTo(start.x, start.y); context.lineTo(end.x, end.y); context.stroke();
    context.strokeStyle = roadColors[form.elements.townRoadMaterial.value] || roadColors.cobblestone;
    context.lineWidth = Math.max(2, roadWidth * scale);
    context.beginPath(); context.moveTo(start.x, start.y); context.lineTo(end.x, end.y); context.stroke();
  }
  context.strokeStyle = roadColors[form.elements.townRoadMaterial.value] || roadColors.cobblestone;
  context.lineWidth = Math.max(2, 3 * scale);
  for (const road of result.accessRoads) {
    const start = project(road.x1, road.z1); const end = project(road.x2, road.z2);
    context.beginPath(); context.moveTo(start.x, start.y); context.lineTo(end.x, end.y); context.stroke();
  }
  for (const decoration of result.decorations || []) {
    const position = project(decoration.x, decoration.z);
    if (decoration.type === "street_tree") {
      context.fillStyle = "#5a3f28";
      context.fillRect(position.x - Math.max(1, scale * .45), position.y - Math.max(1, scale * .45), Math.max(2, scale * .9), Math.max(2, scale * .9));
      context.fillStyle = "#4f8b45";
      context.strokeStyle = "#2f6134";
      context.lineWidth = 1;
      context.beginPath(); context.arc(position.x, position.y, Math.max(3, scale * 2.5), 0, Math.PI * 2); context.fill(); context.stroke();
    } else {
      context.fillStyle = "#ffd85d";
      context.strokeStyle = "#5d4a24";
      context.lineWidth = Math.max(1, scale * .3);
      context.beginPath(); context.arc(position.x, position.y, Math.max(2.5, scale * 1.1), 0, Math.PI * 2); context.fill(); context.stroke();
    }
  }
  const labels = [];
  for (const plot of result.plots) {
    const templatePosition = project(plot.x, plot.z);
    const occupiedPlot = plot.occupied || plot;
    const position = project(occupiedPlot.x, occupiedPlot.z);
    const roofColor = houseRoofColorCatalog.find((item) => item.id === plot.roof_color)?.color;
    if (plot.nbtResolved && (occupiedPlot.width !== plot.width || occupiedPlot.depth !== plot.depth)) {
      context.strokeStyle = "rgba(35,54,65,.35)";
      context.lineWidth = 1;
      context.setLineDash([4, 3]);
      context.strokeRect(templatePosition.x, templatePosition.y, plot.width * scale, plot.depth * scale);
      context.setLineDash([]);
    }
    const renderedNbtTopView = drawMinecraftStructureTopView(context, plot, project, scale);
    context.fillStyle = plot.kind === "facility" ? "#55c9bd" : roofColor || "#e4d5b5";
    context.strokeStyle = plot.kind === "facility" ? "#166b70" : "#796a50";
    context.lineWidth = plot.kind === "facility" ? 3 : 1.5;
    if (!renderedNbtTopView) context.fillRect(position.x, position.y, occupiedPlot.width * scale, occupiedPlot.depth * scale);
    context.strokeRect(position.x, position.y, occupiedPlot.width * scale, occupiedPlot.depth * scale);
    if (plot.entrance) {
      const entrance = project(plot.entrance.x, plot.entrance.z);
      context.fillStyle = plot.kind === "facility" ? "#ffcf4a" : "#fff4b8";
      context.strokeStyle = plot.kind === "facility" ? "#7b4b00" : "#5f4b26";
      context.lineWidth = 1;
      context.beginPath(); context.arc(entrance.x, entrance.y, Math.max(2.5, 2 * scale), 0, Math.PI * 2); context.fill(); context.stroke();
    }
    labels.push({
      text: renderedNbtTopView
        ? (occupiedPlot.width === plot.width && occupiedPlot.depth === plot.depth
            ? `${plot.label} · ${plot.width}×${plot.depth}`
            : `${plot.label} · ${occupiedPlot.width}×${occupiedPlot.depth} 점유 · ${plot.width}×${plot.depth} NBT`)
        : `${plot.label} · 대체 외곽 · ${occupiedPlot.width}×${occupiedPlot.depth} 외벽 · ${plot.width}×${plot.depth} NBT`,
      x: position.x + occupiedPlot.width * scale / 2,
      y: renderedNbtTopView && plot.kind !== "facility" ? position.y - 9 : position.y + occupiedPlot.depth * scale / 2,
      compact: plot.kind === "house",
      nbtTopView: renderedNbtTopView,
      facility: plot.kind === "facility"
    });
  }
  context.textAlign = "center"; context.textBaseline = "middle";
  for (const label of labels) {
    if (label.nbtTopView && !label.facility && scale < 4) continue;
    if (label.compact && scale < 1.7) continue;
    context.font = label.compact ? "700 9px sans-serif" : "700 11px sans-serif";
    const width = context.measureText(label.text).width + 8;
    context.fillStyle = "rgba(255,255,255,.82)"; context.fillRect(label.x - width / 2, label.y - 8, width, 16);
    context.fillStyle = "#16354a"; context.fillText(label.text, label.x, label.y);
  }
  const facilityCount = result.plots.filter((plot) => plot.kind === "facility").length;
  const houseCount = result.plots.filter((plot) => plot.kind === "house").length;
  const resolvedNbtCount = result.plots.filter((plot) => plot.nbtResolved).length;
  const renderedTopViewCount = result.plots.filter((plot) => plot.topView?.blocks?.length).length;
  const streetLampCount = (result.decorations || []).filter((item) => item.type === "street_lamp").length;
  const streetTreeCount = (result.decorations || []).filter((item) => item.type === "street_tree").length;
  const rerollText = result.missing.length
    ? ` · 자동 리롤 ${result.rerollLimit}회 모두 실패`
    : result.rerollCount > 0
      ? ` · 자동 리롤 ${result.rerollCount}회 후 성공 · 적용 시드 ${result.resolvedSeed}`
      : " · 첫 시도 성공";
  const missingText = result.missing.length ? ` · 누락 시설: ${result.missing.join(", ")}` : " · 필수 시설 전부 배치";
  const centerPatternLabel = ({ tee_east: "ㅏ형", tee_west: "ㅓ형", tee_north: "ㅗ형", tee_south: "ㅜ형", linear: "일자형", terraced: "계단형" })[result.centerPattern] || result.centerPattern;
  summary.textContent = `1칸 = 1블록 · ${villageDensityProfiles[density].label} · 확대 ${Math.round(view.zoom * 100)}% · NBT 탑뷰 ${renderedTopViewCount}/${result.plots.length} · 크기 실측 ${resolvedNbtCount}/${result.plots.length} · 마을 크기 ${radiusCells}칸 · 중앙 ${centerPatternLabel} · 허브 X ${result.hub.x} · Z ${result.hub.z} · 요청 시드 ${seed}${rerollText} · 주도로 ${result.roads.length} · 출입구 진입로 ${result.accessRoads.length} · 가로등 ${streetLampCount} · 가로수 ${streetTreeCount} · 시설 ${facilityCount} · 기본 건물 ${houseCount} · 막힌 연결 ${result.rejectedRoads}${missingText}`;
  summary.classList.toggle("has-error", result.missing.length > 0);
  summary.classList.toggle("has-warning", !result.missing.length && result.rerollCount > 0);
  canvas.setAttribute("aria-label", `${radiusCells}개 육각 타일에 도로 조각 ${result.roads.length}개, 시설 ${facilityCount}개, 기본 건물 ${houseCount}개가 생성된 마을 테스트`);
}

const customTownDirections = [[1, 0], [0, 1], [-1, 1], [-1, 0], [0, -1], [1, -1]];
function customTownCells() { return normalizedAxialCells(state.settlement?.town_footprint_cells); }
function customTownExits() { return normalizedAxialCells(state.settlement?.town_road_exits); }
function customTownCellKey(cell) { return `${cell.q},${cell.r}`; }
function customTownConnected(cells) {
  if (!cells.length) return false;
  const keys = new Set(cells.map(customTownCellKey));
  const visited = new Set([customTownCellKey(cells[0])]);
  const queue = [cells[0]];
  while (queue.length) {
    const cell = queue.shift();
    for (const [dq, dr] of customTownDirections) {
      const next = { q: cell.q + dq, r: cell.r + dr }; const key = customTownCellKey(next);
      if (keys.has(key) && !visited.has(key)) { visited.add(key); queue.push(next); }
    }
  }
  return visited.size === cells.length;
}
function customTownBoundaryCell(cell, cells) {
  const keys = new Set(cells.map(customTownCellKey));
  return customTownDirections.some(([dq, dr]) => !keys.has(`${cell.q + dq},${cell.r + dr}`));
}
function ensureCustomTownLayout() {
  if (!state.settlement) return;
  const required = normalizeTownCellCount($("#settlement-form").elements.townRadiusCells.value);
  let cells = customTownCells();
  if (!cells.length) cells = villageLayoutCells(required, required === 5 ? "five_up" : "line_q");
  state.settlement.town_footprint_cells = cells.slice(0, required);
  state.settlement.town_road_exits = customTownExits().filter((exit) => cells.some((cell) => customTownCellKey(cell) === customTownCellKey(exit)));
}
function renderCustomTownLayout() {
  const panel = $("#custom-town-layout"); const map = $("#custom-town-layout-map"); const summary = $("#custom-town-layout-summary");
  if (!panel || !state.settlement) return;
  const custom = $("#settlement-form").elements.townFootprintShape.value === "custom";
  panel.hidden = !custom;
  if (!custom) return;
  ensureCustomTownLayout();
  const cells = customTownCells(); const exits = customTownExits(); const required = normalizeTownCellCount($("#settlement-form").elements.townRadiusCells.value);
  const cellKeys = new Set(cells.map(customTownCellKey)); const exitKeys = new Set(exits.map(customTownCellKey));
  const editorRadius = required === 19 ? 3 : 2; const size = required === 19 ? 39 : 50; const centerX = 310; const centerY = 210;
  const candidates = hexArea({ q: 0, r: 0 }, editorRadius);
  map.innerHTML = candidates.map((cell) => {
    const x = centerX + Math.sqrt(3) * size * (cell.q + cell.r / 2); const y = centerY + 1.5 * size * cell.r;
    const points = Array.from({ length: 6 }, (_, index) => { const angle = -Math.PI / 2 + index * Math.PI / 3; return `${x + Math.cos(angle) * size},${y + Math.sin(angle) * size}`; }).join(" ");
    const key = customTownCellKey(cell); const classes = ["custom-town-hex", cellKeys.has(key) ? "is-cell" : "", exitKeys.has(key) ? "is-exit" : ""].filter(Boolean).join(" ");
    return `<g data-custom-town-cell="${cell.q},${cell.r}"><polygon class="${classes}" points="${points}"><title>Q ${cell.q} · R ${cell.r}</title></polygon><text class="custom-town-coordinate" x="${x}" y="${y + 4}">${cell.q},${cell.r}</text>${exitKeys.has(key) ? `<text class="custom-town-exit-mark" x="${x}" y="${y - 17}">↗</text>` : ""}</g>`;
  }).join("");
  const errors = [];
  if (cells.length !== required) errors.push(`타일 ${required}개가 필요합니다(현재 ${cells.length}개).`);
  if (!cells.some((cell) => cell.q === 0 && cell.r === 0)) errors.push("중심 타일 0,0이 필요합니다.");
  if (!customTownConnected(cells)) errors.push("모든 마을 타일이 서로 이어져야 합니다.");
  if (!exits.length) errors.push("외부 도로 출구를 1개 이상 지정해야 합니다.");
  if (exits.some((exit) => !customTownBoundaryCell(exit, cells))) errors.push("출구는 마을 외곽 타일에만 둘 수 있습니다.");
  summary.textContent = errors.length ? errors.join(" ") : `타일 ${cells.length}개 · 외부 출구 ${exits.length}개 · 내부 도로와 건물은 자동 생성됩니다.`;
  summary.classList.toggle("has-error", errors.length > 0);
}
function editCustomTownCell(q, r) {
  ensureCustomTownLayout();
  const cell = { q, r }; const key = customTownCellKey(cell); let cells = customTownCells(); let exits = customTownExits();
  if (state.customTownTool === "exit") {
    if (!cells.some((entry) => customTownCellKey(entry) === key)) { toast("먼저 마을 타일을 배치하세요."); return; }
    if (!customTownBoundaryCell(cell, cells)) { toast("외부 출구는 마을 외곽 타일에만 지정할 수 있습니다."); return; }
    exits = exits.some((entry) => customTownCellKey(entry) === key) ? exits.filter((entry) => customTownCellKey(entry) !== key) : [...exits, cell];
  } else {
    const required = normalizeTownCellCount($("#settlement-form").elements.townRadiusCells.value);
    if (key === "0,0" && cells.some((entry) => customTownCellKey(entry) === key)) { toast("중심 타일은 제거할 수 없습니다."); return; }
    if (cells.some((entry) => customTownCellKey(entry) === key)) { cells = cells.filter((entry) => customTownCellKey(entry) !== key); exits = exits.filter((entry) => customTownCellKey(entry) !== key); }
    else if (cells.length >= required) { toast(`마을 크기는 ${required}칸입니다. 기존 타일을 먼저 제거하세요.`); return; }
    else cells.push(cell);
  }
  state.settlement.town_footprint_cells = cells; state.settlement.town_road_exits = exits;
  renderCustomTownLayout(); renderVillageGenerationTest();
  $("#settlement-json").value = JSON.stringify(state.settlement, null, 2);
}

function updateFacilityFormState() {
  const form = $("#settlement-form");
  const townCellCount = normalizeTownCellCount(form.elements.townRadiusCells.value);
  const shapeSelect = form.elements.townFootprintShape;
  const previousShape = normalizeTownFootprintShape(shapeSelect.value);
  const shapeOptions = townCellCount === 3
    ? [["triangle_up", "윗삼각형"], ["triangle_down", "아랫삼각형"], ["line_q", "가로 일자"], ["line_r", "우하향 대각선"], ["line_s", "우상향 대각선"]]
    : townCellCount === 5
      ? [["five_up", "위 확장"], ["five_down", "아래 확장"]]
      // 1·7·19칸은 실제 점유 범위가 크기로 고정되므로 형태 값은 화면에
      // 영향을 주지 않는다. 그래도 기존 값을 보존해야 마을을 열거나
      // 시설 옵션을 바꾸는 것만으로 저장 데이터가 바뀌지 않는다.
      : [[previousShape === "custom" ? "line_q" : previousShape, "고정 형태"]];
  shapeOptions.push(["custom", "커스텀 · 직접 타일 편집"]);
  shapeSelect.innerHTML = shapeOptions.map(([value, label]) => `<option value="${value}">${label}</option>`).join("");
  shapeSelect.value = shapeOptions.some(([value]) => value === previousShape) ? previousShape : shapeOptions[0][0];
  shapeSelect.disabled = false;
  renderCustomTownLayout();
  const starterLayout = isStarterSettlement();
  if (starterLayout) {
    form.elements.pokemonCenterEnabled.checked = false;
    form.elements.commercialFacility.value = "none";
  }
  form.elements.pokemonCenterEnabled.disabled = starterLayout;
  form.elements.commercialFacility.disabled = starterLayout;
  const buildingEnabled = form.elements.specialBuildingEnabled.checked;
  const manualPlacement = form.elements.specialDistrictPlacementMode.value === "manual";
  for (const field of form.querySelectorAll('[data-special-manual]')) field.hidden = !manualPlacement;
  for (const name of ["specialBuildingPreset", "specialBuildingStructure", "specialDistrictPlacementMode", "specialDistrictWidth", "specialDistrictDepth", "specialDistrictClearance"]) {
    form.elements[name].disabled = !buildingEnabled;
  }
  for (const name of ["specialDistrictX", "specialDistrictY", "specialDistrictZ"]) {
    form.elements[name].disabled = !buildingEnabled || !manualPlacement;
  }
  const gymEnabled = form.elements.gymEnabled.checked;
  for (const name of ["gymId"]) {
    form.elements[name].disabled = !gymEnabled;
  }
  $$("#facility-option-list [data-facility-id]").forEach((row) => {
    const enabled = row.querySelector(".facility-enabled").checked;
    row.classList.toggle("is-selected", enabled);
    row.querySelector(".facility-count input").disabled = !enabled;
  });
}

function applySpecialBuildingPreset(event) {
  if (event.target?.name !== "specialBuildingPreset" || !event.target.value) return;
  const form = $("#settlement-form");
  form.elements.specialBuildingStructure.value = event.target.value;
  if (event.target.value === "cobbleventure:placeholder/player_house") {
    const metadata = state.structureSizes[event.target.value];
    form.elements.specialDistrictWidth.value = Number(metadata?.width || 16);
    form.elements.specialDistrictDepth.value = Number(metadata?.depth || 16);
  }
}

function renderSettlement() {
  const document = state.settlement;
  const form = $("#settlement-form");
  ensureVillageDensityControl(form);
  $("#selected-settlement-editor").hidden = false;
  $("#settlement-editor-title").textContent = document.display_name?.ko_kr || document.id;
  $("#settlement-path").textContent = state.settlementPath;
  setFormValue(form, "id", document.id); setFormValue(form, "enabled", document.enabled);
  setFormValue(form, "nameKo", document.display_name?.ko_kr); setFormValue(form, "nameEn", document.display_name?.en_us);
  setFormValue(form, "region", document.region); setFormValue(form, "dimension", document.dimension);
  const settlementBiome = document.biome_layout?.zones?.[0]?.biome || "minecraft:plains";
  form.elements.settlementBiome.innerHTML = worldBiomeOptions(settlementBiome);
  setFormValue(form, "settlementBiome", settlementBiome);
  form.elements.musicTrack.innerHTML = musicOptions(document.music_track || "", "settlement");
  setFormValue(form, "musicTrack", document.music_track || "");
  setFormValue(form, "townRadiusCells", normalizeTownCellCount(document.town_radius_cells));
  setFormValue(form, "townFootprintShape", normalizeTownFootprintShape(document.town_footprint_shape));
  setFormValue(form, "townLayoutShape", document.structure_profile?.layout_shape || "branching");
  setFormValue(form, "townRoadLayoutTemplate", document.structure_profile?.road_layout_template || "cross");
  setFormValue(form, "townRoadWidth", document.structure_profile?.road_profile?.width ?? 7);
  for (const [value, label] of [["bricks", "벽돌"], ["grass_path", "잔디 길"]]) {
    if (![...form.elements.townRoadMaterial.options].some((option) => option.value === value)) {
      form.elements.townRoadMaterial.add(new Option(label, value));
    }
  }
  setFormValue(form, "townRoadMaterial", document.structure_profile?.road_profile?.material || "cobblestone");
  const previewSeedHash = [...document.id].reduce((value, character) => ((value * 31) + character.charCodeAt(0)) >>> 0, 1);
  const generationProfile = document.structure_profile?.generation_profile || {};
  const previewSeed = generationProfile.seed || 1 + (previewSeedHash % 999999998);
  setFormValue(form, "villagePreviewSeed", previewSeed);
  setFormValue(form, "villagePreviewDepth", generationProfile.depth || 4);
  setFormValue(form, "townBuildingDensity", normalizeVillageDensity(generationProfile.building_density));
  renderHousePaletteOptions(generationProfile.house_palette);
  const starterPreset = isStarterSettlement(document);
  setFormValue(form, "pokemonCenterEnabled", document.structure_profile?.pokemon_center_enabled ?? !starterPreset);
  const savedCommercial = document.structure_profile?.commercial_center || (starterPreset ? "none" : "pokemart");
  setFormValue(form, "commercialFacility", savedCommercial === "preset" ? "pokemart" : savedCommercial);
  renderFacilityOptions();
  renderSettlementVendorUnits();
  const specialDistrict = document.structure_profile?.special_district || {};
  const specialBuilding = specialDistrict.building || {};
  const specialAnchor = document.anchors?.[specialDistrict.anchor || "special_district"] || document.center || { x: 0, y: 64, z: 0 };
  const settlementCenter = document.center || { x: 0, y: 64, z: 0 };
  setFormValue(form, "specialDistrictPlacementMode", specialDistrict.placement_mode || "manual");
  setFormValue(form, "specialDistrictX", Number(specialAnchor.x) - Number(settlementCenter.x));
  setFormValue(form, "specialDistrictY", Number(specialAnchor.y) - Number(settlementCenter.y));
  setFormValue(form, "specialDistrictZ", Number(specialAnchor.z) - Number(settlementCenter.z));
  setFormValue(form, "specialDistrictWidth", specialDistrict.footprint?.width ?? 48);
  setFormValue(form, "specialDistrictDepth", specialDistrict.footprint?.depth ?? 48);
  setFormValue(form, "specialDistrictClearance", specialDistrict.clearance ?? 6);
  setFormValue(form, "specialBuildingEnabled", specialBuilding.enabled ?? false);
  setFormValue(form, "specialBuildingPreset", specialBuilding.structure === "cobbleventure:placeholder/player_house" ? specialBuilding.structure : "");
  setFormValue(form, "specialBuildingStructure", specialBuilding.structure || "");
  const legacyGym = document.structure_profile?.facility_placements?.find((item) => item.id === "gym_building");
  const gym = document.structure_profile?.gym || {
    enabled: Boolean(legacyGym), structure: legacyGym?.structure || "",
    theme: document.structure_profile?.gym_theme || "normal", anchor: legacyGym?.anchor || "gym_building"
  };
  setFormValue(form, "gymEnabled", gym.enabled ?? false);
  const selectedGymId = gym.gym_id || (state.gymCatalog.gyms || []).find((item) => item.exterior?.structure === gym.structure)?.id || "";
  form.elements.gymId.innerHTML = gymOptions(selectedGymId);
  setFormValue(form, "gymId", selectedGymId);
  setFormValue(form, "gymAnchor", gym.anchor || "gym_building");
  setFormValue(form, "pokemonSpawnProfile", document.content_profile?.pokemon?.spawn_profile);
  setFormValue(form, "pokemonDensity", document.content_profile?.pokemon?.density_multiplier ?? 1);
  form.elements.pokemonBiomeSet.innerHTML = choiceOptions((state.biomeCatalog.sets || []).map((entry) => [entry.id, entry.display_name?.ko_kr || entry.id]), document.content_profile?.pokemon?.biome_set, true);
  setFormValue(form, "pokemonBiomeSet", document.content_profile?.pokemon?.biome_set);
  setFormValue(form, "unconditionalSpawns", (document.content_profile?.pokemon?.unconditional_spawns || []).join(", "));
  setFormValue(form, "trainerPopulationProfile", document.content_profile?.trainers?.population_profile);
  setFormValue(form, "trainerMaxActive", document.content_profile?.trainers?.max_active ?? 0);
  setFormValue(form, "trainerClassPool", (document.content_profile?.trainers?.class_pool || []).join(", "));
  const scaling = document.content_profile?.level_scaling || {};
  setFormValue(form, "scaleMode", scaling.mode || "badge_and_region");
  setFormValue(form, "scaleBase", scaling.base_level ?? 5); setFormValue(form, "scaleMin", scaling.min_level ?? 3);
  setFormValue(form, "scaleMax", scaling.max_level ?? 18); setFormValue(form, "scalePerBadge", scaling.per_badge ?? 2);
  setFormValue(form, "scalePerRegion", scaling.per_region ?? 3); setFormValue(form, "pokemonLevelOffset", scaling.pokemon_offset ?? 0);
  setFormValue(form, "trainerLevelOffset", scaling.trainer_offset ?? 1);
  setFormValue(form, "maxAmbient", document.npc_placement?.max_ambient_npcs);
  setFormValue(form, "wanderRadius", document.npc_placement?.default_wander_radius);
  [...form.elements].forEach((element) => element.disabled = false);
  updateFacilityFormState();
  renderVillageGenerationTest();
  renderTrainerSlots();
  $("#settlement-json").value = JSON.stringify(document, null, 2);
  ["#settlement-json", "#apply-settlement-json", "#add-trainer-slot", "#delete-settlement", "#validate-settlement", "#save-settlement"].forEach((selector) => $(selector).disabled = false);
  showIssues("#settlement-issues", { valid: true, issues: [] });
}

function trainerSlotId(trainerId, slots) {
  const base = (trainerId.split("/").pop() || "trainer").replace(/[^a-z0-9_.-]/g, "_");
  let candidate = base;
  let suffix = 2;
  const used = new Set(slots.map((slot) => slot.id));
  while (used.has(candidate)) candidate = `${base}_${suffix++}`;
  return candidate;
}

function settlementTrainer(trainerId) {
  return state.trainers.find((trainer) => trainer.id === trainerId);
}

function trainerSlotMember(id, npcProfile, position, rotation = 0) {
  return { id, npc_profile: npcProfile, position: { ...position }, rotation };
}

function syncTrainerSlotMembers(slot) {
  const battleType = settlementTrainer(slot.trainer_id)?.battle_type || slot.battle_type || "singles";
  const center = state.settlement?.center || { x: 0, y: 64, z: 0 };
  slot.battle_type = battleType;
  slot.members ||= [];
  if (!slot.members.length) {
    slot.members.push(trainerSlotMember("primary", slot.trainer_id, center));
  }
  if (battleType === "doubles" && slot.members.length < 2) {
    const partner = state.trainers.find((trainer) => trainer.id !== slot.members[0].npc_profile) || state.trainers[0];
    slot.members.push(trainerSlotMember(
      "partner",
      partner?.id || slot.trainer_id,
      { ...slot.members[0].position, x: Number(slot.members[0].position.x) + 2 },
      slot.members[0].rotation
    ));
  }
  slot.members = slot.members.slice(0, battleType === "doubles" ? 2 : 1);
}

function renderTrainerSlots() {
  const list = $("#trainer-slot-list");
  const slots = state.settlement?.npc_placement?.trainer_slots || [];
  if (!slots.length) {
    list.innerHTML = '<div class="issues empty">아직 이 마을에 배치된 트레이너가 없습니다.</div>';
    return;
  }
  const trainerOptions = state.trainers.map((trainer) =>
    `<option value="${escapeHtml(trainer.id)}">${escapeHtml(trainer.name || trainer.id)} · ${escapeHtml(trainer.id)}</option>`
  ).join("");
  slots.forEach(syncTrainerSlotMembers);
  list.innerHTML = slots.map((slot, index) => `
    <article class="trainer-slot-row" data-slot-index="${index}">
      <div class="trainer-slot-heading"><strong>배치 ${String(index + 1).padStart(2, "0")} · ${slot.battle_type === "doubles" ? "듀얼배틀 / EasyNPC 2명" : "싱글배틀 / EasyNPC 1명"}</strong><button type="button" class="remove-trainer-slot" data-remove-trainer-slot="${index}">삭제</button></div>
      <div class="trainer-slot-fields">
        <label class="trainer-choice"><span>전투 트레이너</span><select data-slot-field="trainer_id">${trainerOptions}</select></label>
        <label><span>슬롯 ID</span><input data-slot-field="id" value="${escapeHtml(slot.id || "")}"></label>
        <label><span>생성 정책</span><select data-slot-field="spawn_policy"><option value="persistent">항상 유지</option><option value="on_region_load">지역 로딩 시</option><option value="manual">수동 생성</option></select></label>
        <label class="slot-tags"><span>태그 — 쉼표로 구분</span><input data-slot-field="tags" value="${escapeHtml((slot.tags || []).join(", "))}"></label>
      </div>
      <div class="trainer-member-list">
        ${slot.members.map((member, memberIndex) => `
          <section class="trainer-member" data-member-index="${memberIndex}">
            <strong>EasyNPC ${memberIndex + 1}${memberIndex === 0 ? " · 대표" : " · 파트너"}</strong>
            <div class="trainer-member-fields">
              <label class="trainer-choice"><span>NPC 프로필</span><select data-member-field="npc_profile">${trainerOptions}</select></label>
              <label><span>멤버 ID</span><input data-member-field="id" value="${escapeHtml(member.id || "")}"></label>
              <label><span>X</span><input type="number" data-member-field="x" value="${Number(member.position?.x ?? 0)}"></label>
              <label><span>Y</span><input type="number" data-member-field="y" value="${Number(member.position?.y ?? 64)}"></label>
              <label><span>Z</span><input type="number" data-member-field="z" value="${Number(member.position?.z ?? 0)}"></label>
              <label><span>회전</span><input type="number" min="-360" max="360" step="1" data-member-field="rotation" value="${Number(member.rotation ?? 0)}"></label>
            </div>
          </section>`).join("")}
      </div>
    </article>`).join("");
  $$(".trainer-slot-row").forEach((row) => {
    const slot = slots[Number(row.dataset.slotIndex)];
    row.querySelector('[data-slot-field="trainer_id"]').value = slot.trainer_id || "";
    row.querySelector('[data-slot-field="spawn_policy"]').value = slot.spawn_policy || "persistent";
    row.querySelectorAll("[data-member-index]").forEach((memberRow) => {
      const member = slot.members[Number(memberRow.dataset.memberIndex)];
      memberRow.querySelector('[data-member-field="npc_profile"]').value = member.npc_profile || "";
    });
  });
}

function addTrainerSlot() {
  if (!state.settlement) return;
  if (!state.trainers.length) { toast("먼저 트레이너를 하나 이상 만들어 주세요."); return; }
  state.settlement.npc_placement ||= { max_ambient_npcs: 0, default_wander_radius: 5, trainer_slots: [], zones: [] };
  state.settlement.npc_placement.trainer_slots ||= [];
  const slots = state.settlement.npc_placement.trainer_slots;
  const assigned = new Set(slots.map((slot) => slot.trainer_id));
  const trainer = state.trainers.find((entry) => !assigned.has(entry.id)) || state.trainers[0];
  slots.push({
    id: trainerSlotId(trainer.id, slots),
    trainer_id: trainer.id,
    battle_type: trainer.battle_type || "singles",
    members: [trainerSlotMember("primary", trainer.id, state.settlement.center || { x: 0, y: 64, z: 0 })],
    spawn_policy: "persistent",
    tags: ["trainer"]
  });
  syncTrainerSlotMembers(slots.at(-1));
  renderTrainerSlots();
  updateSettlementFromForm();
}

function updateTrainerSlot(event) {
  const row = event.target.closest("[data-slot-index]");
  if (!row || !state.settlement) return;
  const slot = state.settlement.npc_placement.trainer_slots[Number(row.dataset.slotIndex)];
  const memberRow = event.target.closest("[data-member-index]");
  const memberField = event.target.dataset.memberField;
  if (memberRow && memberField) {
    const member = slot.members[Number(memberRow.dataset.memberIndex)];
    if (["x", "y", "z"].includes(memberField)) member.position[memberField] = Number(event.target.value);
    else if (memberField === "rotation") member.rotation = Number(event.target.value);
    else member[memberField] = event.target.value;
  } else {
    const field = event.target.dataset.slotField;
    if (!field) return;
    if (field === "tags") slot.tags = event.target.value.split(",").map((tag) => tag.trim()).filter(Boolean);
    else slot[field] = event.target.value;
    if (field === "trainer_id") {
      slot.members[0].npc_profile = slot.trainer_id;
      syncTrainerSlotMembers(slot);
      renderTrainerSlots();
    }
  }
  updateSettlementFromForm();
}

function removeTrainerSlot(index) {
  state.settlement?.npc_placement?.trainer_slots?.splice(index, 1);
  renderTrainerSlots();
  updateSettlementFromForm();
}

function updateSettlementFromForm() {
  if (!state.settlement) return;
  const form = $("#settlement-form");
  const number = (name) => Number(form.elements[name].value);
  const displayName = { ...(state.settlement.display_name || {}), ko_kr: form.elements.nameKo.value };
  if (form.elements.nameEn.value.trim()) displayName.en_us = form.elements.nameEn.value;
  else delete displayName.en_us;
  Object.assign(state.settlement, {
    id: form.elements.id.value, enabled: form.elements.enabled.checked,
    display_name: displayName,
    region: form.elements.region.value, dimension: form.elements.dimension.value,
    town_radius_cells: normalizeTownCellCount(number("townRadiusCells")),
    town_footprint_shape: normalizeTownFootprintShape(form.elements.townFootprintShape.value)
  });
  if (form.elements.musicTrack.value) state.settlement.music_track = form.elements.musicTrack.value;
  else delete state.settlement.music_track;
  if (state.settlement.town_footprint_shape === "custom") ensureCustomTownLayout();
  else { delete state.settlement.town_footprint_cells; delete state.settlement.town_road_exits; }
  delete state.settlement.biome;
  state.settlement.schema_version = 3;
  state.settlement.biome_layout ||= {};
  state.settlement.biome_layout.zones ||= [];
  state.settlement.biome_layout.zones[0] ||= {
    id: "town",
    size_blocks: 192,
    placement: "center",
    weight: 1
  };
  state.settlement.biome_layout.zones[0].biome = form.elements.settlementBiome.value;
  state.settlement.structure_profile ||= {};
  const starterPreset = isStarterSettlement();
  state.settlement.structure_profile.pokemon_center_enabled = starterPreset
    ? false : form.elements.pokemonCenterEnabled.checked;
  state.settlement.structure_profile.commercial_center = starterPreset
    ? "none" : form.elements.commercialFacility.value;
  const selectedShopCatalog = starterPreset ? null : selectedSettlementShopCatalog();
  state.settlement.structure_profile.shop_configuration = {
    catalog_id: selectedShopCatalog?.id || "cobbleventure:shop_catalog/none",
    vendor_units: (selectedShopCatalog?.assignments || []).map((assignment) => assignment.vendor_unit),
    assignments: (selectedShopCatalog?.assignments || []).map(({ slot_id, vendor_unit }) => ({ slot_id, vendor_unit }))
  };
  state.settlement.structure_profile.civic_facilities_explicit = true;
  delete state.settlement.structure_profile.village_preset;
  delete state.settlement.structure_profile.starter_layout;
  delete state.settlement.structure_profile.house_style;
  const layoutShape = form.elements.townLayoutShape.value || "branching";
  const roadLayoutTemplate = form.elements.townRoadLayoutTemplate?.value || "cross";
  const facilityRequirements = selectedFacilityRequirements();
  state.settlement.structure_profile.layout_shape = layoutShape;
  state.settlement.structure_profile.road_layout_template = roadLayoutTemplate;
  state.settlement.structure_profile.road_profile = {
    width: Number(form.elements.townRoadWidth.value),
    material: form.elements.townRoadMaterial.value
  };
  state.settlement.structure_profile.generation_profile = {
    seed: Math.max(1, Math.min(999999999, number("villagePreviewSeed"))),
    depth: Math.max(1, Math.min(7, number("villagePreviewDepth"))),
    basic_buildings: [
      "cobbleventure:placeholder/basic_building_1",
      "cobbleventure:placeholder/basic_building_2",
      "cobbleventure:placeholder/basic_building_3"
    ],
    house_palette: selectedHousePalette(),
    building_density: normalizeVillageDensity(form.elements.townBuildingDensity.value)
  };
  state.settlement.structure_profile.facility_requirements = facilityRequirements;
  state.settlement.anchors ||= {};
  for (const anchor of Object.keys(state.settlement.anchors)) {
    if (anchor.startsWith("preview_") || anchor.startsWith("facility_")) delete state.settlement.anchors[anchor];
  }
  // Pokemon Center and commercial facilities are represented by their
  // dedicated switches above. Persist only additional requirements here;
  // writing civic facilities again as numbered direct placements makes the
  // data builder and runtime place them twice.
  const configuredFacilities = facilityTemplatePlacements(
    facilityRequirements,
    layoutShape, state.settlement.center || { x: 0, y: 64, z: 0 }
  );
  const generatedLayout = simulateVillageWithRerolls(
    state.settlement.structure_profile.generation_profile.seed,
    state.settlement.structure_profile.generation_profile.depth,
    layoutShape,
    state.settlement.structure_profile.road_profile.width,
    [...selectedCivicFacilities(), ...facilityRequirements, ...selectedGymFacility()],
    Number(form.elements.townRadiusCells.value || 1),
    state.settlement.structure_profile.generation_profile.house_palette,
    state.settlement.town_footprint_shape,
    customTownCells(), customTownExits(),
    state.settlement.structure_profile.generation_profile.building_density,
    roadLayoutTemplate
  );
  const generatedFacilityPlots = generatedLayout.plots.filter((plot) => plot.kind === "facility");
  configuredFacilities.forEach((facility) => {
    const plot = generatedFacilityPlots.find((candidate) =>
      candidate.id === facility.requirement.id
    );
    if (!plot) return;
    facility.position = {
      x: Math.round(Number(state.settlement.center?.x || 0) + plot.x),
      y: Number(state.settlement.center?.y || 64),
      z: Math.round(Number(state.settlement.center?.z || 0) + plot.z)
    };
  });
  for (const facility of configuredFacilities) {
    state.settlement.anchors[facility.anchor] = facility.position;
  }
  const specialAnchorId = "special_district";
  const previousDistrict = state.settlement.structure_profile.special_district || {};
  const placementMode = form.elements.specialDistrictPlacementMode.value;
  const center = state.settlement.center || { x: 0, y: 64, z: 0 };
  const defaultAnchor = state.settlement.anchors[previousDistrict.anchor || specialAnchorId]
    || { x: Number(center.x) - 48, y: Number(center.y), z: Number(center.z) };
  state.settlement.anchors[specialAnchorId] = placementMode === "manual"
    ? {
      x: Number(center.x) + number("specialDistrictX"),
      y: Number(center.y) + number("specialDistrictY"),
      z: Number(center.z) + number("specialDistrictZ")
    }
    : { ...defaultAnchor };
  const specialBuildingEnabled = form.elements.specialBuildingEnabled.checked;
  state.settlement.structure_profile.special_district = {
    enabled: specialBuildingEnabled,
    anchor: specialAnchorId,
    placement_mode: placementMode,
    footprint: { width: number("specialDistrictWidth"), depth: number("specialDistrictDepth") },
    clearance: number("specialDistrictClearance"),
    building: {
      enabled: specialBuildingEnabled,
      structure: form.elements.specialBuildingStructure.value.trim()
    }
  };
  const gymEnabled = form.elements.gymEnabled.checked;
  const gymDefinition = (state.gymCatalog.gyms || []).find((gym) => gym.id === form.elements.gymId.value);
  const gymAnchor = form.elements.gymAnchor.value.trim() || "gym_building";
  if (!state.settlement.anchors[gymAnchor]) {
    state.settlement.anchors[gymAnchor] = { ...(state.settlement.center || { x: 0, y: 64, z: 0 }) };
  }
  state.settlement.structure_profile.gym = {
    enabled: gymEnabled,
    gym_id: gymDefinition?.id || "",
    structure: gymDefinition?.exterior?.structure || "",
    theme: gymDefinition?.theme || "normal",
    anchor: gymAnchor
  };
  // Keep legacy fields synchronized while older data packs are still accepted.
  state.settlement.structure_profile.gym_theme = gymDefinition?.theme || "normal";
  delete state.settlement.structure_profile.gym_entrance_offset;
  const otherFacilities = (state.settlement.structure_profile.facility_placements || []).filter((item) => !["gym_building", "special_district_building"].includes(item.id) && item.mode !== "placeholder" && !item.id.startsWith("facility_"));
  if (gymEnabled && gymDefinition) otherFacilities.push({ id: "gym_building", mode: "direct_template", structure: gymDefinition.exterior.structure, anchor: gymAnchor });
  if (specialBuildingEnabled) otherFacilities.push({ id: "special_district_building", mode: "direct_template", structure: form.elements.specialBuildingStructure.value.trim(), anchor: specialAnchorId });
  otherFacilities.push(...configuredFacilities.map((item) => item.placement));
  state.settlement.structure_profile.facility_placements = otherFacilities;
  const pokemonContentProfile = {
    spawn_profile: form.elements.pokemonSpawnProfile.value.trim(),
    density_multiplier: number("pokemonDensity"),
    unconditional_spawns: csvValues(form.elements.unconditionalSpawns.value)
  };
  const biomeSet = form.elements.pokemonBiomeSet.value.trim();
  if (biomeSet) pokemonContentProfile.biome_set = biomeSet;
  state.settlement.content_profile = {
    pokemon: pokemonContentProfile,
    trainers: {
      population_profile: form.elements.trainerPopulationProfile.value.trim(),
      max_active: number("trainerMaxActive"),
      class_pool: form.elements.trainerClassPool.value.split(",").map((value) => value.trim()).filter(Boolean)
    },
    level_scaling: {
      mode: form.elements.scaleMode.value,
      base_level: number("scaleBase"), min_level: number("scaleMin"), max_level: number("scaleMax"),
      per_badge: number("scalePerBadge"), per_region: number("scalePerRegion"),
      pokemon_offset: number("pokemonLevelOffset"), trainer_offset: number("trainerLevelOffset")
    }
  };
  state.settlement.connections = [];
  state.settlement.npc_placement = state.settlement.npc_placement || { trainer_slots: [], zones: [] };
  state.settlement.npc_placement.max_ambient_npcs = number("maxAmbient");
  state.settlement.npc_placement.default_wander_radius = number("wanderRadius");
  state.settlement.npc_placement.trainer_slots ||= [];
  state.settlement.npc_placement.trainer_slots = state.settlement.npc_placement.trainer_slots.filter((slot) => slot.id !== "gym_leader");
  updateFacilityFormState();
  renderVillageGenerationTest();
  $("#settlement-json").value = JSON.stringify(state.settlement, null, 2);
}

async function previewSettlementZone(index) {
  if (!state.settlement) return;
  if (!state.biomeCatalog.profiles.length) {
    toast("서식지 프로필을 불러오지 못했습니다. build.bat web을 다시 시작해 주세요.");
    return;
  }
  updateSettlementFromForm();
  const zone = state.settlement.biome_layout.zones[index - 1];
  if (!zone?.habitat_profile) { toast("먼저 서식지 프로필을 선택하세요."); return; }
  await runBiomePreview({ profile_id: zone.habitat_profile, settings: zone.spawn_settings, unconditional_spawns: state.settlement.content_profile.pokemon.unconditional_spawns }, `#biome${index}Preview`);
}

function parseEditor(selector) {
  try { return JSON.parse($(selector).value); }
  catch (error) { toast(`JSON 문법 오류: ${error.message}`); return null; }
}

async function validateDocument(category) {
  const singular = documentSingular(category);
  if (category === "settlements") {
    if (!$("#settlement-form").reportValidity()) return false;
    updateSettlementFromForm();
  }
  if (category === "caves" && (!$("#cave-form").reportValidity() || !updateCaveFromForm())) return false;
  if (category === "forests" && (!$("#forest-form").reportValidity() || !updateForestFromForm())) return false;
  if (category === "battles" && !updateBattlePresetFromForm()) return false;
  const document = category === "caves" ? state.cave : category === "forests" ? state.forest : parseEditor(`#${singular}-json`);
  if (!document) return false;
  const result = await request(`/api/document-validation?category=${category}`, { method: "POST", body: JSON.stringify(document) });
  if (result.ok && category === "trainers" && state.battlePreset) {
    const battleResult = await request("/api/document-validation?category=battles", { method: "POST", body: JSON.stringify(state.battlePreset) });
    if (!battleResult.ok) {
      showIssues(`#${singular}-issues`, battleResult.data);
      toast("연결된 배틀 프리셋에 수정이 필요한 항목이 있습니다.");
      return false;
    }
  }
  showIssues(`#${singular}-issues`, result.data);
  toast(result.ok ? "문서 검증을 통과했습니다." : "수정이 필요한 항목이 있습니다.");
  return result.ok;
}

async function saveDocument(category) {
  const singular = documentSingular(category);
  if (category === "settlements") {
    if (!$("#settlement-form").reportValidity()) { toast("입력값을 확인해 주세요."); return; }
    updateSettlementFromForm();
  }
  if (category === "caves") {
    if (!$("#cave-form").reportValidity() || !updateCaveFromForm()) { toast("입력값을 확인해 주세요."); return; }
  }
  if (category === "forests") {
    if (!$("#forest-form").reportValidity() || !updateForestFromForm()) { toast("입력값을 확인해 주세요."); return; }
  }
  if (category === "battles" && !updateBattlePresetFromForm()) return;
  const document = category === "caves" ? state.cave : category === "forests" ? state.forest : parseEditor(`#${singular}-json`);
  if (!document) return;
  const saveButton = $(`#save-${singular}`);
  const originalLabel = saveButton.textContent;
  saveButton.disabled = true;
  saveButton.textContent = "저장 중…";
  if (category === "trainers" && state.battlePreset) {
    const battleValidation = await request("/api/document-validation?category=battles", { method: "POST", body: JSON.stringify(state.battlePreset) });
    if (!battleValidation.ok) {
      saveButton.disabled = false;
      saveButton.textContent = originalLabel;
      showIssues(`#${singular}-issues`, battleValidation.data);
      toast("배틀 프리셋 검증 오류로 저장하지 않았습니다.");
      return;
    }
    const battleSave = await request(`/api/battles?path=${encodeURIComponent(state.battlePath)}`, { method: "PUT", body: JSON.stringify(state.battlePreset) });
    if (!battleSave.ok) {
      saveButton.disabled = false;
      saveButton.textContent = originalLabel;
      showIssues(`#${singular}-issues`, battleSave.data);
      toast("배틀 프리셋을 저장하지 못했습니다.");
      return;
    }
  }
  const result = await request(`/api/${category}?path=${encodeURIComponent(state[`${singular}Path`])}`, { method: "PUT", body: JSON.stringify(document) });
  saveButton.textContent = originalLabel;
  showIssues(`#${singular}-issues`, result.data);
  if (!result.ok) { saveButton.disabled = false; toast("검증 오류로 저장하지 않았습니다."); return; }
  if (category === "battles") state.battlePreset = document;
  else state[singular] = document;
  toast("검증 후 안전하게 저장했습니다.");
  await Promise.all([loadDashboard(), loadLists()]);
  if (category === "settlements") renderSettlement();
  else if (category === "caves") renderCave();
  else if (category === "forests") renderForest();
  else if (category === "battles") renderBattlePreset();
  else renderTrainer();
}

function openCreateDialog(category) {
  const form = $("#create-form");
  form.reset();
  form.elements.category.value = category;
  form.elements.generation.value = ["trainers", "battles"].includes(category) ? "generation_1" : `generation_${state.selectedGeneration}`;
  $("#create-title").textContent = category === "trainers" ? "새 NPC" : category === "battles" ? "새 배틀 프리셋" : category === "caves" ? "새 동굴" : category === "forests" ? "새 숲" : "새 마을";
  $("#generation-field").hidden = ["trainers", "battles", "caves", "forests"].includes(category);
  $("#battle-reference-field").hidden = category !== "battles";
  if (category === "battles") {
    form.elements.referenceId.value = "";
    $("#create-reference-name").value = "빈 프리셋으로 시작";
    $("#create-reference-summary").textContent = "엔트리를 선택하면 팀·AI·규칙·가방을 새 프리셋에 복사합니다.";
  }
  $("#create-issues").className = "issues empty";
  $("#create-issues").textContent = "";
  $("#create-dialog").showModal();
  form.elements.slug.focus();
}

async function createDocument(event) {
  event.preventDefault();
  const form = event.currentTarget;
  if (!form.reportValidity()) return;
  const payload = {
    category: form.elements.category.value,
    slug: form.elements.slug.value,
    name: form.elements.name.value,
    generation: form.elements.generation.value,
    reference_id: form.elements.referenceId.value
  };
  $("#create-submit").disabled = true;
  try {
    const result = await request("/api/documents", { method: "POST", body: JSON.stringify(payload) });
    if (!result.ok) { showIssues("#create-issues", result.data); return; }
    $("#create-dialog").close();
    await Promise.all([loadDashboard(), loadLists()]);
    switchPage(payload.category);
    await loadDocument(payload.category, result.data.path);
    toast(payload.category === "trainers" ? "새 NPC를 만들었습니다." : payload.category === "battles" ? "새 배틀 프리셋을 만들었습니다." : payload.category === "caves" ? "새 동굴을 만들었습니다." : payload.category === "forests" ? "새 숲을 만들었습니다." : "새 마을을 만들었습니다.");
  } finally {
    $("#create-submit").disabled = false;
  }
}

async function deleteManagedDocument(category) {
  const singular = documentSingular(category);
  const document = category === "battles" ? state.battlePreset : state[singular];
  const path = category === "battles" ? state.battlePath : state[`${singular}Path`];
  if (!document || !path) return;
  const labels = { trainers: "NPC", battles: "배틀 프리셋", caves: "동굴", forests: "숲" };
  const label = labels[category];
  const name = document.name?.ko_kr || document.display_name?.ko_kr || document.id;
  if (!confirm(`'${name}' ${label} 파일을 삭제할까요?\n이 작업은 되돌릴 수 없습니다.`)) return;
  const button = $(`#delete-${singular}`);
  const originalLabel = button.textContent;
  const deletedIndex = Math.max(0, state[category].findIndex((item) => item.path === path));
  button.disabled = true;
  button.textContent = "삭제 중…";
  try {
    const result = await request(`/api/${category}?path=${encodeURIComponent(path)}`, { method: "DELETE" });
    if (!result.ok) {
      const references = result.data.references?.length ? `\n참조: ${result.data.references.join(", ")}` : "";
      toast(`${result.data.error || `${label}을 삭제하지 못했습니다.`}${references}`);
      return;
    }
    if (category === "battles") { state.battlePreset = null; state.battlePath = ""; }
    else { state[singular] = null; state[`${singular}Path`] = ""; }
    await Promise.all([loadDashboard(), loadLists()]);
    const next = state[category][Math.min(deletedIndex, state[category].length - 1)];
    if (next) await loadDocument(category, next.path);
    else {
      const editor = $(`#${category} .editor`);
      editor?.querySelector("form")?.reset();
      editor?.querySelectorAll("input, select, textarea, button").forEach((element) => { element.disabled = true; });
      $(`#${singular}-editor-title`).textContent = `${label}을 선택하세요`;
      $(`#${singular}-path`).textContent = "—";
      renderList(category);
    }
    toast(`'${name}' ${label}을 삭제했습니다.`);
  } finally {
    button.textContent = originalLabel;
    if ((category === "battles" && state.battlePreset) || (category !== "battles" && state[singular])) button.disabled = false;
  }
}

async function deleteWorldLayout() {
  if (!state.worldLayout) return;
  const generation = state.selectedGeneration;
  if (!confirm(`${generation}세대 월드맵 파일을 삭제할까요?\n이 작업은 되돌릴 수 없습니다.`)) return;
  const result = await request(`/api/world-layout?generation=${generation}`, { method: "DELETE" });
  if (!result.ok) { toast(result.data.error || "월드맵을 삭제하지 못했습니다."); return; }
  state.worldGenerations = state.worldGenerations.filter((value) => value !== generation);
  const nextGeneration = state.worldGenerations.find((value) => value > generation) || state.worldGenerations.at(-1);
  state.worldLayout = null;
  state.worldDirty = false;
  state.selectedHex = null;
  if (nextGeneration) {
    const [world, pokemonMap] = await Promise.all([request(`/api/world-layout?generation=${nextGeneration}`), request(`/api/world-pokemon-map?generation=${nextGeneration}`)]);
    state.selectedGeneration = nextGeneration;
    if (world.ok) state.worldLayout = world.data;
    if (pokemonMap.ok) state.worldPokemonMap = pokemonMap.data;
  }
  renderWorldLayout();
  toast(`${generation}세대 월드맵을 삭제했습니다.`);
}

async function deleteSettlement() {
  if (!state.settlement || !state.settlementPath) return;
  const name = state.settlement.display_name?.ko_kr || state.settlement.id;
  if (!confirm(`'${name}' 마을 프리셋 파일을 삭제할까요?\n이 작업은 되돌릴 수 없습니다.`)) return;
  const button = $("#delete-settlement");
  const originalLabel = button.textContent;
  const deletedIndex = Math.max(0, state.settlements.findIndex((item) => item.path === state.settlementPath));
  button.disabled = true;
  button.textContent = "삭제 중…";
  try {
    const result = await request(`/api/settlements?path=${encodeURIComponent(state.settlementPath)}`, { method: "DELETE" });
    if (!result.ok) {
      const references = result.data.references?.length ? `\n참조: ${result.data.references.join(", ")}` : "";
      toast(`${result.data.error || "마을 프리셋을 삭제하지 못했습니다."}${references}`);
      return;
    }
    state.settlement = null;
    state.settlementPath = "";
    await Promise.all([loadDashboard(), loadLists()]);
    const next = state.settlements[Math.min(deletedIndex, state.settlements.length - 1)];
    if (next) await loadDocument("settlements", next.path);
    else {
      $("#settlement-editor-title").textContent = "마을 프리셋을 선택하세요";
      $("#settlement-path").textContent = "—";
      $("#settlement-form").reset();
      $$("#selected-settlement-editor input, #selected-settlement-editor select, #selected-settlement-editor textarea, #selected-settlement-editor button").forEach((element) => { element.disabled = true; });
      renderList("settlements");
    }
    toast(`'${name}' 마을 프리셋을 삭제했습니다.`);
  } finally {
    button.textContent = originalLabel;
    if (state.settlement) button.disabled = false;
  }
}

async function loadGameDefinitions(force = false) {
  if (lazyDataLoaded.definitions && !force) return;
  if (lazyDataPromises.definitions) return lazyDataPromises.definitions;
  lazyDataPromises.definitions = (async () => {
    const result = await request("/api/game-definitions");
    if (!result.ok) throw new Error(result.data.error || "게임 데이터 선언을 불러오지 못했습니다.");
    state.gameDefinitions = result.data;
    state.gameDefinitions.items ||= [];
    state.gameDefinitions.variables ||= [];
    lazyDataLoaded.definitions = true;
    renderGameDefinitions();
  })();
  try { await lazyDataPromises.definitions; }
  finally { lazyDataPromises.definitions = null; }
}

function definitionValueInput(entry) {
  if (entry.type === "boolean") return `<select data-definition-field="default"><option value="true" ${entry.default === true ? "selected" : ""}>true</option><option value="false" ${entry.default === false ? "selected" : ""}>false</option></select>`;
  if (entry.type === "integer") return `<input type="number" step="1" data-definition-field="default" value="${Number.isInteger(entry.default) ? entry.default : 0}">`;
  return `<input data-definition-field="default" value="${escapeHtml(typeof entry.default === "string" ? entry.default : "")}">`;
}

function renderGameDefinitions() {
  const definitions = state.gameDefinitions;
  const items = definitions.items || [];
  const variables = definitions.variables || [];
  $("#game-item-list").innerHTML = items.length ? items.map((entry, index) => `<article class="definition-card" data-definition-kind="item" data-definition-index="${index}"><header><div><strong>${escapeHtml(entry.display_name?.ko_kr || entry.id || `아이템 ${index + 1}`)}</strong><code>${escapeHtml(entry.id || "ID 미지정")}</code></div><button type="button" data-remove-definition="item" data-index="${index}">삭제</button></header><div class="definition-fields"><label class="span-2"><span>선언 ID</span><input data-definition-field="id" value="${escapeHtml(entry.id || "")}" placeholder="cobbleventure:item/quest_letter"></label><label class="span-2"><span>기반 아이템</span><input data-definition-field="base_item" value="${escapeHtml(entry.base_item || "")}" placeholder="minecraft:paper"></label><label><span>한국어 이름</span><input data-definition-field="display_name.ko_kr" value="${escapeHtml(entry.display_name?.ko_kr || "")}"></label><label><span>영문 이름</span><input data-definition-field="display_name.en_us" value="${escapeHtml(entry.display_name?.en_us || "")}"></label><label class="span-4"><span>용도와 설명</span><textarea rows="2" data-definition-field="description.ko_kr">${escapeHtml(entry.description?.ko_kr || "")}</textarea></label></div><footer><span>NPC 조건 키</span><code>has_item · ${escapeHtml(entry.id || "—")}</code></footer></article>`).join("") : '<div class="definition-empty">선언된 추가 아이템이 없습니다. ‘＋ 아이템’을 눌러 시작하세요.</div>';
  $("#game-variable-list").innerHTML = variables.length ? variables.map((entry, index) => `<article class="definition-card" data-definition-kind="variable" data-definition-index="${index}"><header><div><strong>${escapeHtml(entry.display_name?.ko_kr || entry.id || `변수 ${index + 1}`)}</strong><code>${escapeHtml(entry.id || "ID 미지정")}</code></div><button type="button" data-remove-definition="variable" data-index="${index}">삭제</button></header><div class="definition-fields"><label class="span-2"><span>변수 ID</span><input data-definition-field="id" value="${escapeHtml(entry.id || "")}" placeholder="cobbleventure:flag/story/met_professor"></label><label><span>저장 범위</span><select data-definition-field="scope"><option value="global" ${entry.scope === "global" ? "selected" : ""}>월드 전체</option><option value="player" ${entry.scope === "player" ? "selected" : ""}>플레이어별</option></select></label><label><span>자료형</span><select data-definition-field="type" data-definition-rerender><option value="boolean" ${entry.type === "boolean" ? "selected" : ""}>boolean</option><option value="integer" ${entry.type === "integer" ? "selected" : ""}>integer</option><option value="string" ${entry.type === "string" ? "selected" : ""}>string</option></select></label><label><span>한국어 이름</span><input data-definition-field="display_name.ko_kr" value="${escapeHtml(entry.display_name?.ko_kr || "")}"></label><label><span>기본값</span>${definitionValueInput(entry)}</label><label class="span-4"><span>용도와 설명</span><textarea rows="2" data-definition-field="description.ko_kr">${escapeHtml(entry.description?.ko_kr || "")}</textarea></label></div><footer><span>NPC 조건 키</span><code>flag_equals · ${escapeHtml(entry.id || "—")}</code></footer></article>`).join("") : '<div class="definition-empty">선언된 진행 변수가 없습니다. 전역 또는 플레이어별 변수를 추가하세요.</div>';
  const globalCount = variables.filter((entry) => entry.scope === "global").length;
  const playerCount = variables.filter((entry) => entry.scope === "player").length;
  $("#definition-summary").innerHTML = `<article><span>아이템</span><strong>${items.length}</strong></article><article><span>전역 변수</span><strong>${globalCount}</strong></article><article><span>플레이어 변수</span><strong>${playerCount}</strong></article>`;
  $("#declared-item-ids").innerHTML = items.map((entry) => `<option value="${escapeHtml(entry.id || "")}">${escapeHtml(entry.display_name?.ko_kr || "")}</option>`).join("");
  $("#declared-variable-ids").innerHTML = variables.map((entry) => `<option value="${escapeHtml(entry.id || "")}">${escapeHtml(entry.display_name?.ko_kr || "")}</option>`).join("");
}

function nextDefinitionId(kind) {
  const entries = kind === "item" ? state.gameDefinitions.items : state.gameDefinitions.variables;
  const prefix = kind === "item" ? "cobbleventure:item/new_item" : "cobbleventure:flag/new_variable";
  let id = prefix; let suffix = 2;
  while (entries.some((entry) => entry.id === id)) id = `${prefix}_${suffix++}`;
  return id;
}

function addGameDefinition(kind) {
  if (kind === "item") state.gameDefinitions.items.push({ id: nextDefinitionId(kind), base_item: "minecraft:paper", display_name: { ko_kr: "새 아이템", en_us: "New Item" }, description: { ko_kr: "" } });
  else state.gameDefinitions.variables.push({ id: nextDefinitionId(kind), scope: "player", type: "boolean", default: false, display_name: { ko_kr: "새 진행 변수" }, description: { ko_kr: "" } });
  renderGameDefinitions();
  const list = kind === "item" ? $("#game-item-list") : $("#game-variable-list");
  list.lastElementChild?.scrollIntoView({ behavior: "smooth", block: "center" });
}

function setNestedDefinitionField(entry, path, value) {
  const keys = path.split("."); let target = entry;
  for (const key of keys.slice(0, -1)) target = target[key] ||= {};
  target[keys.at(-1)] = value;
}

function handleDefinitionInput(event) {
  const row = event.target.closest("[data-definition-kind]");
  const field = event.target.dataset.definitionField;
  if (!row || !field) return;
  const entries = row.dataset.definitionKind === "item" ? state.gameDefinitions.items : state.gameDefinitions.variables;
  const entry = entries[Number(row.dataset.definitionIndex)];
  let value = event.target.value;
  if (field === "default" && entry.type === "boolean") value = value === "true";
  if (field === "default" && entry.type === "integer") value = Number(value);
  if (field === "type") {
    entry.type = value;
    entry.default = value === "boolean" ? false : value === "integer" ? 0 : "";
  } else setNestedDefinitionField(entry, field, value);
  if (event.target.dataset.definitionRerender !== undefined || field === "scope") renderGameDefinitions();
}

function handleDefinitionClick(event) {
  const add = event.target.closest("[data-add-definition]");
  if (add) { addGameDefinition(add.dataset.addDefinition); return; }
  const remove = event.target.closest("[data-remove-definition]");
  if (!remove) return;
  const entries = remove.dataset.removeDefinition === "item" ? state.gameDefinitions.items : state.gameDefinitions.variables;
  entries.splice(Number(remove.dataset.index), 1); renderGameDefinitions();
}

async function saveGameDefinitions() {
  const result = await request("/api/game-definitions", { method: "PUT", body: JSON.stringify(state.gameDefinitions) });
  showIssues("#definition-issues", result.data);
  if (!result.ok) { toast(result.data.error || "선언 내용을 확인해 주세요."); return; }
  toast("게임 데이터 선언을 저장했습니다.");
}

async function loadEconomy(force = false) {
  if (lazyDataLoaded.economy && !force) return;
  if (lazyDataPromises.economy) return lazyDataPromises.economy;
  lazyDataPromises.economy = (async () => {
    const result = await request("/api/economy");
    if (!result.ok) throw new Error(result.data.error || "경제 카탈로그를 불러오지 못했습니다.");
    state.economy = result.data;
    state.economy.vendor_units ||= [];
    state.economy.shop_catalogs ||= [];
    state.economy.standard_prices ||= [];
    state.economy.resolved_standard_prices ||= [];
    state.economy.pokemon_drop_rules ||= [];
    state.economy.resolved_shop_catalogs ||= [];
    state.economy.pokemon_drop_overrides ||= [];
    state.economy.resolved_vendor_units ||= [];
    state.economy.resolved_pokemon_drops ||= [];
    state.economy.npc_recipes ||= [];
    state.economy.vanilla_crafting_disabled = true;
    lazyDataLoaded.economy = true;
    renderEconomy();
  })();
  try { await lazyDataPromises.economy; }
  finally { lazyDataPromises.economy = null; }
}

function economyFacilityLabel(value) {
  return { pokemart: "프렌들리숍", department_store: "백화점", specialty: "전문 상점" }[value] || value;
}

function economyText(value, locale = "ko_kr") {
  if (typeof value === "string") return value;
  return value?.[locale] || value?.ko_kr || value?.en_us || "";
}

function economyLocalized(koKr, enUs) {
  return { ko_kr: String(koKr || "").trim(), en_us: String(enUs || koKr || "").trim() };
}

function economyStandardPrice(itemId) {
  return (state.economy.resolved_standard_prices || []).find((entry) => entry.item === itemId)?.price || "";
}

function setEconomyStandardPrice(itemId, price) {
  if (!itemId || !String(price).trim()) return;
  state.economy.standard_prices ||= [];
  const entry = state.economy.standard_prices.find((candidate) => candidate.item === itemId);
  if (entry) entry.price = String(price).trim();
  else state.economy.standard_prices.push({ item: itemId, price: String(price).trim() });
  state.economy.resolved_standard_prices = [...(state.economy.resolved_standard_prices || []).filter((candidate) => candidate.item !== itemId), { item: itemId, price: String(price).trim() }];
}

const ECONOMY_PRODUCT_GROUPS = [
  { id: "all", ko: "전체 상품", en: "All Items" },
  { id: "sold", ko: "판매 중", en: "Enabled" },
  { id: "balls", ko: "몬스터볼", en: "Poké Balls" },
  { id: "medicine", ko: "회복·상태", en: "Medicine" },
  { id: "battle", ko: "배틀 도구", en: "Battle Items" },
  { id: "gems", ko: "타입 Gem", en: "Type Gems" },
  { id: "machines", ko: "기술머신", en: "Technical Machines" },
  { id: "evolution", ko: "진화 아이템", en: "Evolution Items" },
  { id: "held", ko: "지닌물건", en: "Held Items" },
  { id: "berries", ko: "나무열매", en: "Berries" },
  { id: "food", ko: "식품·음료", en: "Food & Drinks" },
  { id: "materials", ko: "재료·규토리", en: "Materials" },
  { id: "other", ko: "기타", en: "Other" },
];

function economyProductGroup(itemOrId) {
  if (itemOrId && typeof itemOrId === "object") return itemOrId.product_group || "other";
  return (state.economy.editor_catalog?.items || []).find((item) => item.id === itemOrId)?.product_group || "other";
}

function economyProductLibrary(vendor, itemName) {
  const selected = new Map(economyVendorOfferRows(vendor).map((row) => [row.offer.item, row]));
  const query = String(state.economyView.vendorProductSearch || "").trim().toLocaleLowerCase("ko");
  const groupId = state.economyView.vendorProductGroup || "balls";
  const items = (state.economy.editor_catalog?.items || []).filter((item) => {
    const group = economyProductGroup(item);
    const matchesGroup = groupId === "all" || (groupId === "sold" ? selected.has(item.id) : group === groupId);
    return matchesGroup;
  });
  const matchesProductQuery = (item) => !query || `${item.id} ${item.ko_kr} ${item.en_us}`.toLocaleLowerCase("ko").includes(query);
  const groupButtons = ECONOMY_PRODUCT_GROUPS.map((group) => {
    const count = group.id === "sold" ? selected.size : (state.economy.editor_catalog?.items || []).filter((item) => group.id === "all" || economyProductGroup(item) === group.id).length;
    return `<button type="button" class="${group.id === groupId ? "is-active" : ""}" data-product-group="${group.id}"><span>${escapeHtml(group.ko)}</span><small>${escapeHtml(group.en)} · ${count}</small></button>`;
  }).join("");
  const cards = items.map((item) => {
    const active = selected.get(item.id);
    const standard = economyStandardPrice(item.id);
    return `<article class="economy-product-toggle ${active ? "is-active" : ""}" data-product-item="${escapeHtml(item.id)}" ${matchesProductQuery(item) ? "" : "hidden"}><button type="button" data-toggle-vendor-product="${escapeHtml(item.id)}" aria-pressed="${active ? "true" : "false"}"><i></i><span><strong>${escapeHtml(item.ko_kr || itemName(item.id))}</strong><small>${escapeHtml(item.en_us || item.id)}</small><code>${escapeHtml(item.id)}</code></span><b>${active ? "판매 중" : "판매 안 함"}</b></button><footer><span>표준가 <strong>${escapeHtml(standard || "미정")}</strong></span>${active ? `<label>수량 <input data-toggle-product-count data-item="${escapeHtml(item.id)}" type="number" min="1" value="${Number(active.offer.count || 1)}"></label><label>판매가 <input data-toggle-product-price data-item="${escapeHtml(item.id)}" type="number" min="0" value="${Number(active.offer.price || 0)}"></label><button type="button" data-toggle-set-standard="${escapeHtml(item.id)}">표준 지정</button>` : ""}</footer></article>`;
  }).join("");
  const visibleCount = items.filter(matchesProductQuery).length;
  const emptyMessage = groupId === "machines" && !items.length
    ? "Cobblemon 1.7.3 기본 아이템에는 기술머신이 없습니다. 기술머신을 제공하는 모드를 추가하면 이 탭에 표시됩니다."
    : "조건에 맞는 상품이 없습니다.";
  return `<div class="economy-product-browser"><div class="economy-product-controls"><label><span>상품 검색</span><input id="economy-product-search" value="${escapeHtml(state.economyView.vendorProductSearch || "")}" placeholder="한글명, 영문명 또는 아이템 ID"></label><div><strong>상품 종류</strong><small>종류를 선택한 뒤 상품을 켜거나 끄세요.</small></div></div><nav class="economy-product-groups">${groupButtons}</nav><div class="economy-product-result-head"><span data-product-visible-count>${visibleCount}개 상품</span><b>${selected.size}개 판매 중</b></div><div class="economy-product-toggle-grid">${cards || `<div class="economy-empty">${emptyMessage}</div>`}</div></div>`;
}

function economyCollection(kind) {
  if (kind === "catalog") return state.economy.shop_catalogs;
  if (kind === "rule") return state.economy.pokemon_drop_rules;
  if (kind === "shop") return state.economy.vendor_units;
  if (kind === "drop") return state.economy.pokemon_drop_overrides;
  return state.economy.npc_recipes;
}

function renderEconomyLegacy() {
  const shops = state.economy.shops || [];
  const drops = state.economy.pokemon_drops || [];
  const recipes = state.economy.npc_recipes || [];
  const townFilter = $("#economy-town-filter");
  const selectedTown = townFilter.value;
  const towns = [...new Set(shops.map((shop) => shop.town).filter(Boolean))].sort();
  townFilter.innerHTML = '<option value="">전체 마을</option>' + towns.map((town) => `<option value="${escapeHtml(town)}" ${town === selectedTown ? "selected" : ""}>${escapeHtml(town)}</option>`).join("");
  const visibleShops = selectedTown ? shops.filter((shop) => shop.town === selectedTown) : shops;
  $("#economy-shop-list").innerHTML = visibleShops.length ? visibleShops.map((shop) => {
    const index = shops.indexOf(shop);
    const items = shop.items || [];
    return `<article class="economy-card"><header><div><span class="economy-card-kicker">${escapeHtml(shop.town || "마을 미지정")} · ${escapeHtml(economyFacilityLabel(shop.facility))}${shop.floor ? ` · ${escapeHtml(shop.floor)}` : ""}</span><h4>${escapeHtml(shop.display_name || shop.npc || "판매 NPC")}<small>${items.length}개 품목</small></h4></div><div class="economy-card-actions"><button data-economy-edit="shop" data-index="${index}">편집</button><button data-economy-remove="shop" data-index="${index}">삭제</button></div></header><div class="economy-card-body">${items.length ? items.map((item) => `<div class="economy-stock-row"><code>${escapeHtml(item.item || "")}</code><b>${Number(item.price || 0).toLocaleString()} · ${escapeHtml(item.currency || "")}</b><span>${item.stock_limit == null ? "무제한" : `${item.stock_limit}개`}</span></div>`).join("") : '<div class="economy-empty">판매 아이템이 없습니다.</div>'}</div><footer><span>NPC 리소스</span><b>${escapeHtml(shop.npc || "—")}</b></footer></article>`;
  }).join("") : '<div class="economy-empty">등록된 판매 NPC가 없습니다. 마을과 NPC별 판매 목록을 추가하세요.</div>';

  $("#economy-drop-list").innerHTML = drops.length ? '<div class="economy-table-head"><span>포켓몬</span><span>드롭 아이템</span><span>확률</span><span>수량</span><span>관리</span></div>' + drops.map((drop, index) => `<div class="economy-drop-row"><span><strong>${escapeHtml(drop.pokemon || "포켓몬 미지정")}</strong><small>${escapeHtml(drop.id || "")}</small></span><span><strong>${escapeHtml(drop.item || "아이템 미지정")}</strong></span><span class="economy-chance"><b>${Math.round(Number(drop.chance || 0) * 100)}%</b><span><i style="width:${Math.max(0, Math.min(100, Number(drop.chance || 0) * 100))}%"></i></span></span><span>${drop.min_count || 1}–${drop.max_count || 1}개</span><span class="economy-row-actions"><button data-economy-edit="drop" data-index="${index}">편집</button><button data-economy-remove="drop" data-index="${index}">삭제</button></span></div>`).join("") : '<div class="economy-empty">등록된 포켓몬 드롭 규칙이 없습니다.</div>';

  $("#economy-recipe-list").innerHTML = recipes.length ? recipes.map((recipe, index) => `<article class="economy-card"><header><div><span class="economy-card-kicker">${escapeHtml(recipe.town || "마을 미지정")}</span><h4>${escapeHtml(recipe.display_name || "제작 의뢰")}<small>${escapeHtml(recipe.npc || "")}</small></h4></div><div class="economy-card-actions"><button data-economy-edit="recipe" data-index="${index}">편집</button><button data-economy-remove="recipe" data-index="${index}">삭제</button></div></header><div class="economy-recipe-flow"><div class="economy-ingredients">${(recipe.ingredients || []).map((item) => `<div class="economy-ingredient"><code>${escapeHtml(item.item || "")}</code><b>×${item.count || 1}</b></div>`).join("") || '<div class="economy-empty">재료 없음</div>'}</div><div class="economy-recipe-arrow">→</div><div class="economy-output"><strong>결과물</strong><code>${escapeHtml(recipe.output?.item || "")}</code><b>×${recipe.output?.count || 1}</b></div></div><footer><span>해금 조건</span><b>${escapeHtml(recipe.unlock_note || "조건 없음")}</b></footer></article>`).join("") : '<div class="economy-empty">등록된 NPC 제작법이 없습니다.</div>';

  const productCount = shops.reduce((sum, shop) => sum + (shop.items || []).length, 0);
  $("#economy-summary").innerHTML = `<article><span>판매 NPC</span><strong>${shops.length}</strong></article><article><span>판매 품목</span><strong>${productCount}</strong></article><article><span>드롭 규칙</span><strong>${drops.length}</strong></article><article><span>NPC 제작법</span><strong>${recipes.length}</strong></article>`;
  $("#economy-drop-item-ids").innerHTML = [...new Set(drops.map((drop) => drop.item).filter(Boolean))].map((item) => `<option value="${escapeHtml(item)}"></option>`).join("");
}

function economyEditorFieldsLegacy(kind, entry = {}) {
  if (kind === "shop") {
    const itemLines = (entry.items || []).map((item) => `${item.item} | ${item.price} | ${item.currency} | ${item.stock_limit ?? ""}`).join("\n");
    return `<label class="wide"><span>상점 ID</span><input name="id" value="${escapeHtml(entry.id || "cobbleventure:shop/new_shop")}" required></label><label><span>마을 ID</span><input name="town" value="${escapeHtml(entry.town || "cobbleventure:town/")}" required></label><label><span>시설</span><select name="facility"><option value="pokemart" ${entry.facility === "pokemart" ? "selected" : ""}>프렌들리숍</option><option value="department_store" ${entry.facility === "department_store" ? "selected" : ""}>백화점</option><option value="specialty" ${entry.facility === "specialty" ? "selected" : ""}>전문 상점</option></select></label><label><span>층 (백화점 필수)</span><input name="floor" value="${escapeHtml(entry.floor || "")}" placeholder="2F"></label><label><span>NPC 리소스 ID</span><input name="npc" value="${escapeHtml(entry.npc || "cobbleventure:npc/")}" required></label><label class="wide"><span>판매 NPC 표시 이름</span><input name="display_name" value="${escapeHtml(entry.display_name || "새 판매 NPC")}" required></label><label class="wide"><span>판매 목록</span><textarea name="items" rows="7" placeholder="cobblemon:poke_ball | 200 | cobbleventure:currency/pokedollar |&#10;cobblemon:great_ball | 600 | cobbleventure:currency/pokedollar | 10">${escapeHtml(itemLines)}</textarea><small>한 줄에 아이템 ID | 가격 | 화폐 ID | 재고 제한. 재고를 비우면 무제한입니다.</small></label>`;
  }
  if (kind === "drop") return `<label class="wide"><span>드롭 규칙 ID</span><input name="id" value="${escapeHtml(entry.id || "cobbleventure:drop/new_drop")}" required></label><label><span>포켓몬 ID</span><input name="pokemon" value="${escapeHtml(entry.pokemon || "cobblemon:")}" required></label><label><span>드롭 아이템 ID</span><input name="item" value="${escapeHtml(entry.item || "cobbleventure:")}" required></label><label><span>드롭 확률 (%)</span><input type="number" name="chance" min="0.01" max="100" step="0.01" value="${Number(entry.chance ?? .25) * 100}" required></label><label><span>최소 수량</span><input type="number" name="min_count" min="1" step="1" value="${entry.min_count || 1}" required></label><label><span>최대 수량</span><input type="number" name="max_count" min="1" step="1" value="${entry.max_count || 1}" required></label>`;
  const ingredientLines = (entry.ingredients || []).map((item) => `${item.item} | ${item.count}`).join("\n");
  return `<label class="wide"><span>제작법 ID</span><input name="id" value="${escapeHtml(entry.id || "cobbleventure:recipe/new_recipe")}" required></label><label><span>마을 ID</span><input name="town" value="${escapeHtml(entry.town || "cobbleventure:town/")}" required></label><label><span>담당 NPC ID</span><input name="npc" value="${escapeHtml(entry.npc || "cobbleventure:npc/")}" required></label><label class="wide"><span>제작 의뢰 이름</span><input name="display_name" value="${escapeHtml(entry.display_name || "새 제작 의뢰")}" required></label><label><span>결과 아이템 ID</span><input name="output_item" value="${escapeHtml(entry.output?.item || "cobblemon:")}" required></label><label><span>결과 수량</span><input type="number" name="output_count" min="1" step="1" value="${entry.output?.count || 1}" required></label><label class="wide"><span>포켓몬 드롭 재료</span><textarea name="ingredients" rows="6" placeholder="cobbleventure:hard_stone_shard | 2">${escapeHtml(ingredientLines)}</textarea><small>한 줄에 아이템 ID | 수량. 포켓몬 드롭 탭에 등록된 아이템만 저장할 수 있습니다.</small></label><label class="wide"><span>해금 조건</span><input name="unlock_note" value="${escapeHtml(entry.unlock_note || "")}" placeholder="회색체육관 클리어"></label>`;
}

function openEconomyEditor(kind, index = -1) {
  const entry = index >= 0 ? economyCollection(kind)[index] : {};
  const labels = { catalog: "백화점 카탈로그", shop: "상인 카탈로그", rule: "대량 루트 규칙", drop: "포켓몬 개별 드롭", recipe: "NPC 제작법" };
  $("#economy-dialog-title").textContent = `${labels[kind]} ${index >= 0 ? "편집" : "추가"}`;
  $("#economy-form").elements.kind.value = kind;
  $("#economy-form").elements.index.value = String(index);
  $("#economy-dialog-fields").innerHTML = economyEditorFields(kind, entry);
  $("#economy-dialog").showModal();
}

function parseEconomyLines(value, fields) {
  return value.split("\n").map((line) => line.trim()).filter(Boolean).map((line) => {
    const values = line.split("|").map((part) => part.trim());
    return Object.fromEntries(fields.map((field, index) => [field, values[index] ?? ""]));
  });
}

function submitEconomyEditorLegacy(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const data = new FormData(form);
  const kind = String(data.get("kind"));
  const index = Number(data.get("index"));
  let entry;
  if (kind === "shop") entry = { id: String(data.get("id")).trim(), town: String(data.get("town")).trim(), facility: String(data.get("facility")), floor: String(data.get("floor")).trim(), npc: String(data.get("npc")).trim(), display_name: String(data.get("display_name")).trim(), items: parseEconomyLines(String(data.get("items")), ["item", "price", "currency", "stock_limit"]).map((item) => ({ item: item.item, price: Number(item.price), currency: item.currency, stock_limit: item.stock_limit ? Number(item.stock_limit) : null })) };
  else if (kind === "drop") entry = { id: String(data.get("id")).trim(), pokemon: String(data.get("pokemon")).trim(), item: String(data.get("item")).trim(), chance: Number(data.get("chance")) / 100, min_count: Number(data.get("min_count")), max_count: Number(data.get("max_count")) };
  else entry = { id: String(data.get("id")).trim(), town: String(data.get("town")).trim(), npc: String(data.get("npc")).trim(), display_name: String(data.get("display_name")).trim(), output: { item: String(data.get("output_item")).trim(), count: Number(data.get("output_count")) }, ingredients: parseEconomyLines(String(data.get("ingredients")), ["item", "count"]).map((item) => ({ item: item.item, count: Number(item.count) })), unlock_note: String(data.get("unlock_note")).trim() };
  const collection = economyCollection(kind);
  if (index >= 0) collection[index] = entry; else collection.push(entry);
  $("#economy-dialog").close();
  renderEconomy();
}

function handleEconomyClickLegacy(event) {
  const tab = event.target.closest("[data-economy-tab]");
  if (tab) {
    $$('[data-economy-tab]').forEach((button) => button.classList.toggle("is-active", button === tab));
    $$('[data-economy-panel]').forEach((panel) => panel.classList.toggle("is-active", panel.dataset.economyPanel === tab.dataset.economyTab));
    return;
  }
  const add = event.target.closest("[data-economy-add]");
  if (add) { openEconomyEditor(add.dataset.economyAdd); return; }
  const edit = event.target.closest("[data-economy-edit]");
  if (edit) { openEconomyEditor(edit.dataset.economyEdit, Number(edit.dataset.index)); return; }
  const remove = event.target.closest("[data-economy-remove]");
  if (!remove) return;
  if (!confirm("이 경제 항목을 삭제할까요? 저장 전에는 새로고침으로 되돌릴 수 있습니다.")) return;
  economyCollection(remove.dataset.economyRemove).splice(Number(remove.dataset.index), 1);
  renderEconomy();
}

async function saveEconomy() {
  state.economy.vanilla_crafting_disabled = true;
  const result = await request("/api/economy", { method: "PUT", body: JSON.stringify(state.economy) });
  showIssues("#economy-issues", result.data);
  if (!result.ok) { toast(result.data.error || "상점·드롭·제작 설정을 확인해 주세요."); return; }
  await loadEconomy(true);
  toast("경제 카탈로그를 저장했습니다.");
}

// Economy schema v2: catalog units are independent from towns. Towns only keep
// references to complete vendor units (NPC + role + grouped offers).
function renderSettlementVendorUnits() {
  const form = $("#settlement-form");
  if (!form) return;
  let panel = $("#settlement-shop-vendors");
  if (!panel) {
    panel = document.createElement("section");
    panel.id = "settlement-shop-vendors";
    panel.className = "shop-vendor-config wide";
    panel.innerHTML = '<header><strong>상점 카탈로그 선택</strong><small>카탈로그에 묶인 판매원·NPC·판매 품목 전체가 인게임에 배치됩니다.</small></header><div id="settlement-vendor-unit-list"></div>';
    const gym = $("#gym-config-fields");
    gym?.parentElement?.insertBefore(panel, gym);
  }
  const facility = form.elements.commercialFacility?.value || "none";
  const configuredCatalog = state.settlement?.structure_profile?.shop_configuration?.catalog_id || "";
  const catalogs = (state.economy.resolved_shop_catalogs || []).filter((catalog) => catalog.facility_scope === facility);
  const vendorsById = new Map((state.economy.resolved_vendor_units || []).map((vendor) => [vendor.id, vendor]));
  panel.hidden = facility === "none";
  const list = $("#settlement-vendor-unit-list");
  if (!list) return;
  list.innerHTML = catalogs.length ? catalogs.map((catalog, index) => {
    const assignments = catalog.assignments || [];
    const vendorNames = assignments.map((assignment) => `${economyText(assignment.display_name)}: ${economyText(vendorsById.get(assignment.vendor_unit)?.role) || assignment.vendor_unit}`);
    const checked = configuredCatalog ? configuredCatalog === catalog.id : index === 0;
    return `<label class="vendor-unit-choice"><input type="radio" name="settlementShopCatalog" value="${escapeHtml(catalog.id)}" ${checked ? "checked" : ""}><span><strong>${escapeHtml(economyText(catalog.display_name))}</strong><small>${vendorNames.length}개 코너 · ${escapeHtml(vendorNames.join(", "))}</small><small>${escapeHtml(catalog.id)} · ${catalog.origin === "custom" ? "사용자 정의" : "기본 제공"}</small></span></label>`;
  }).join("") : '<div class="economy-empty">이 시설에 사용할 상점 카탈로그가 없습니다. 경제 · 제작에서 추가하세요.</div>';
}

function selectedSettlementShopCatalog() {
  const selectedId = $('input[name="settlementShopCatalog"]:checked')?.value;
  return (state.economy.resolved_shop_catalogs || []).find((catalog) => catalog.id === selectedId) || null;
}

function editableEconomyEntry(kind, id) {
  const collection = kind === "shop" ? state.economy.vendor_units : state.economy.shop_catalogs;
  const resolved = kind === "shop" ? state.economy.resolved_vendor_units : state.economy.resolved_shop_catalogs;
  let index = collection.findIndex((entry) => entry.id === id);
  if (index >= 0) return { entry: collection[index], index };
  const source = resolved.find((entry) => entry.id === id);
  if (!source) return null;
  const entry = structuredClone(source);
  delete entry.origin;
  collection.push(entry);
  index = collection.length - 1;
  return { entry, index };
}

function syncResolvedEconomyEntry(kind, entry) {
  const key = kind === "shop" ? "resolved_vendor_units" : "resolved_shop_catalogs";
  state.economy[key] = [...(state.economy[key] || []).filter((current) => current.id !== entry.id), { ...structuredClone(entry), origin: "custom" }];
}

function economyVendorOfferRows(vendor) {
  return (vendor.categories || []).flatMap((category, categoryIndex) => (category.offers || []).map((offer, offerIndex) => ({ category, categoryIndex, offer, offerIndex })));
}

function economySaleCategoryIndex() {
  const byItem = new Map();
  for (const vendor of state.economy.resolved_vendor_units || []) {
    for (const category of vendor.categories || []) {
      for (const offer of category.offers || []) {
        if (!byItem.has(offer.item)) byItem.set(offer.item, new Set());
        byItem.get(offer.item).add(economyText(category.name) || "일반");
      }
    }
  }
  return byItem;
}

function openEconomyItemChoice(vendorId, categoryIndex, offerIndex) {
  const vendor = (state.economy.resolved_vendor_units || []).find((entry) => entry.id === vendorId);
  const category = vendor?.categories?.[categoryIndex];
  if (!vendor || !category?.offers?.[offerIndex]) return;
  state.choice = { kind: "economy_item", vendorId, categoryIndex, offerIndex, query: "", type: "", category: "", scope: "" };
  $("#choice-eyebrow").textContent = "ECONOMY ITEM PICKER";
  $("#choice-title").textContent = "판매 상품 선택";
  $("#choice-subtitle").textContent = "시설과 상인에 관계없이 전체 아이템을 검색합니다. 판매 분류는 선택 필터입니다.";
  $("#choice-dialog").showModal();
  renderChoiceDialog();
}

function renderEconomy() {
  const itemById = new Map((state.economy.editor_catalog?.items || []).map((item) => [item.id, item]));
  const itemName = (id) => itemById.get(id)?.ko_kr || itemById.get(id)?.en_us || id;
  const allVendors = state.economy.resolved_vendor_units || [];
  const allCatalogs = state.economy.resolved_shop_catalogs || [];
  const drops = state.economy.resolved_pokemon_drops || [];
  const recipes = state.economy.npc_recipes || [];
  const normalize = (value) => String(value || "").toLocaleLowerCase("ko");
  const catalogQuery = normalize(state.economyView.catalogSearch);
  const departmentCatalogs = allCatalogs.filter((catalog) => catalog.facility_scope === "department_store");
  const catalogs = departmentCatalogs.filter((catalog) => !catalogQuery || normalize(`${economyText(catalog.display_name)} ${economyText(catalog.display_name, "en_us")} ${catalog.id}`).includes(catalogQuery));
  const vendorQuery = normalize(state.economyView.vendorSearch);
  const vendors = allVendors.filter((vendor) => !vendorQuery || normalize(`${economyText(vendor.role)} ${economyText(vendor.role, "en_us")} ${economyText(vendor.display_name)} ${economyText(vendor.display_name, "en_us")} ${vendor.id} ${(vendor.categories || []).flatMap((category) => (category.offers || []).map((offer) => `${offer.item} ${itemName(offer.item)}`)).join(" ")}`).includes(vendorQuery));
  const customVendorIds = new Map((state.economy.vendor_units || []).map((vendor, index) => [vendor.id, index]));
  const customCatalogIds = new Map((state.economy.shop_catalogs || []).map((catalog, index) => [catalog.id, index]));
  if (!allVendors.some((vendor) => vendor.id === state.economyView.selectedVendorId)) state.economyView.selectedVendorId = allVendors[0]?.id || "";
  if (!departmentCatalogs.some((catalog) => catalog.id === state.economyView.selectedCatalogId)) state.economyView.selectedCatalogId = departmentCatalogs[0]?.id || "";
  const selectedVendor = allVendors.find((vendor) => vendor.id === state.economyView.selectedVendorId);
  const selectedCatalog = departmentCatalogs.find((catalog) => catalog.id === state.economyView.selectedCatalogId);
  $("#economy-vendor-count").textContent = String(allVendors.length);
  $("#economy-catalog-count").textContent = String(departmentCatalogs.length);
  $("#economy-shop-list").innerHTML = vendors.length ? vendors.map((vendor) => {
    const offerCount = economyVendorOfferRows(vendor).length;
    return `<button class="economy-master-item ${vendor.id === state.economyView.selectedVendorId ? "is-active" : ""}" data-select-vendor="${escapeHtml(vendor.id)}" data-facility="${escapeHtml(vendor.facility_scope)}"><i class="economy-master-item-marker"></i><span><strong>${escapeHtml(economyText(vendor.role) || "이름 없는 상인")}</strong><small>${escapeHtml(economyFacilityLabel(vendor.facility_scope))} · ${escapeHtml(economyText(vendor.display_name) || vendor.npc_template || "")}</small></span><b>${offerCount}</b></button>`;
  }).join("") : '<div class="economy-empty">검색 조건에 맞는 상인이 없습니다.</div>';
  $("#economy-catalog-list").innerHTML = catalogs.length ? catalogs.map((catalog) => {
    const assignments = catalog.assignments || [];
    return `<button class="economy-master-item ${catalog.id === state.economyView.selectedCatalogId ? "is-active" : ""}" data-select-catalog="${escapeHtml(catalog.id)}" data-facility="department_store"><i class="economy-master-item-marker"></i><span><strong>${escapeHtml(economyText(catalog.display_name) || "이름 없는 백화점")}</strong><small>${escapeHtml(catalog.id)}</small></span><b>${assignments.length}</b></button>`;
  }).join("") : '<div class="economy-empty">검색 조건에 맞는 백화점이 없습니다.</div>';

  if (selectedVendor) {
    const rows = economyVendorOfferRows(selectedVendor);
    const customIndex = customVendorIds.get(selectedVendor.id);
    $("#economy-vendor-detail").innerHTML = `<header class="economy-detail-head"><div><p class="eyebrow">${escapeHtml(economyFacilityLabel(selectedVendor.facility_scope))}</p><h3>${escapeHtml(economyText(selectedVendor.role) || "판매원")}</h3><small>${escapeHtml(economyText(selectedVendor.display_name) || selectedVendor.npc_template || "")} · ${escapeHtml(economyText(selectedVendor.display_name, "en_us"))}</small><code>${escapeHtml(selectedVendor.id)}</code></div><div class="economy-detail-actions"><button data-economy-edit="shop" data-index="${customIndex ?? -1}" data-entry-id="${escapeHtml(selectedVendor.id)}">상인 정보 편집</button>${customIndex == null ? "" : `<button data-economy-remove="shop" data-index="${customIndex}">상인 삭제</button>`}</div></header><div class="economy-section-title"><div><h4>판매 상품 설정</h4><small>${rows.length}개 상품 판매 중 · 상품 카드를 눌러 즉시 추가하거나 제외합니다.</small></div></div>${economyProductLibrary(selectedVendor, itemName)}`;
  } else $("#economy-vendor-detail").innerHTML = '<div class="economy-detail-empty"><div><strong>상인을 선택하세요</strong><span>왼쪽 목록에서 상인을 누르면 판매 상품이 여기에 표시됩니다.</span></div></div>';

  if (selectedCatalog) {
    const assignments = selectedCatalog.assignments || [];
    const customIndex = customCatalogIds.get(selectedCatalog.id);
    $("#economy-catalog-detail").innerHTML = `<header class="economy-detail-head"><div><p class="eyebrow">CORNER ASSIGNMENT</p><h3>${escapeHtml(economyText(selectedCatalog.display_name) || "백화점")}</h3><small>${escapeHtml(economyText(selectedCatalog.display_name, "en_us"))}</small><code>${escapeHtml(selectedCatalog.id)}</code></div><div class="economy-detail-actions">${customIndex == null ? "" : `<button data-economy-remove="catalog" data-index="${customIndex}">백화점 삭제</button>`}</div></header><div class="economy-store-meta"><label><span>백화점 이름 · 한국어</span><input data-catalog-field="display_name_ko" data-catalog-id="${escapeHtml(selectedCatalog.id)}" value="${escapeHtml(economyText(selectedCatalog.display_name))}"></label><label><span>백화점 이름 · English</span><input data-catalog-field="display_name_en" data-catalog-id="${escapeHtml(selectedCatalog.id)}" value="${escapeHtml(economyText(selectedCatalog.display_name, "en_us"))}"></label></div><div class="economy-section-title"><div><h4>코너별 상인 배치</h4><small>각 코너의 한국어·영어 이름과 배치 상인을 설정합니다.</small></div><button class="economy-inline-add" data-add-catalog-assignment="${escapeHtml(selectedCatalog.id)}">＋ 코너 추가</button></div><div class="economy-corner-table"><div class="economy-corner-head"><span>위치 ID</span><span>코너 이름 (한/영)</span><span>배치할 상인</span><span></span></div>${assignments.map((assignment, index) => `<div class="economy-corner-row" data-catalog-id="${escapeHtml(selectedCatalog.id)}" data-assignment-index="${index}"><input data-assignment-field="slot_id" value="${escapeHtml(assignment.slot_id || "")}" aria-label="위치 ID"><span class="economy-localized-cell"><input data-assignment-field="display_name_ko" value="${escapeHtml(economyText(assignment.display_name))}" aria-label="코너 이름 한국어"><input data-assignment-field="display_name_en" value="${escapeHtml(economyText(assignment.display_name, "en_us"))}" aria-label="코너 이름 영어"></span><select data-assignment-field="vendor_unit" aria-label="배치할 상인">${allVendors.map((vendor) => `<option value="${escapeHtml(vendor.id)}" ${vendor.id === assignment.vendor_unit ? "selected" : ""}>${escapeHtml(economyText(vendor.role))} · ${escapeHtml(economyFacilityLabel(vendor.facility_scope))}</option>`).join("")}</select><button data-remove-catalog-assignment aria-label="코너 삭제">−</button></div>`).join("") || '<div class="economy-empty">배치된 코너가 없습니다. 오른쪽 위의 ＋ 코너 추가를 누르세요.</div>'}</div><p class="economy-detail-note">코너 수만큼 상인이 배치됩니다. 한국어와 영어 이름은 함께 저장됩니다.</p>`;
  } else $("#economy-catalog-detail").innerHTML = '<div class="economy-detail-empty"><div><strong>백화점을 선택하세요</strong><span>왼쪽 목록에서 백화점을 누르면 코너 배치가 여기에 표시됩니다.</span></div></div>';

  const rules = state.economy.pokemon_drop_rules || [];
  $("#economy-drop-rule-list").innerHTML = rules.length ? rules.map((rule, index) => {
    const matched = drops.filter((species) => (species.applied_rules || []).includes(rule.id)).length;
    const conditions = [
      ...(rule.match?.types || []).map((value) => `${value} 타입`), ...(rule.match?.generations || []).map((value) => `${value}세대`),
      ...(rule.match?.labels || []), ...(rule.match?.egg_groups || []).map((value) => `${value} 알그룹`),
      ...(rule.match?.forms || []).map((value) => `${value} 폼`), ...(rule.match?.species || []).map((value) => drops.find((entry) => entry.species === value)?.ko_kr || value),
      ...(rule.match?.size && rule.match.size !== "any" ? [`크기 ${rule.match.size}`] : [])
    ];
    return `<article class="economy-card economy-rule-card ${rule.enabled ? "" : "is-disabled"}"><header><div><span class="economy-card-kicker">${rule.mode === "replace" ? "기존 드롭 교체" : "기존 드롭에 추가"} · 우선순위 ${rule.priority}</span><h4>${escapeHtml(rule.display_name)}<small>${matched}종 적용</small></h4></div><div class="economy-card-actions"><button data-economy-edit="rule" data-index="${index}">편집</button><button data-economy-remove="rule" data-index="${index}">삭제</button></div></header><div class="economy-condition-chips">${conditions.map((value) => `<span>${escapeHtml(value)}</span>`).join("") || '<span>모든 포켓몬</span>'}</div><div class="economy-card-body">${(rule.entries || []).map((entry) => `<div class="economy-stock-row"><span><strong>${escapeHtml(itemName(entry.item))}</strong><code>${escapeHtml(entry.item)}</code></span><b>${entry.percentage ?? 100}%</b><span>${escapeHtml(entry.quantityRange || "1개")}</span></div>`).join("")}</div></article>`;
  }).join("") : '<div class="economy-empty">대량 루트 규칙이 없습니다. 타입이나 세대로 묶어 규칙을 추가하세요.</div>';

  const pokemonQuery = normalize(state.economyView.pokemonSearch);
  const visibleDrops = drops.filter((drop) => (!pokemonQuery || normalize(`${drop.ko_kr} ${drop.en_us} ${drop.species}`).includes(pokemonQuery)) && (!state.economyView.pokemonType || (drop.types || []).includes(state.economyView.pokemonType)) && (!state.economyView.pokemonGeneration || String(drop.generation) === String(state.economyView.pokemonGeneration))).slice(0, state.economyView.pokemonLimit);
  $("#economy-drop-list").innerHTML = visibleDrops.length ? '<div class="economy-table-head"><span>포켓몬</span><span>적용 드롭</span><span>선택량</span><span>적용 방식</span><span>관리</span></div>' + visibleDrops.map((drop) => {
    const overrideIndex = (state.economy.pokemon_drop_overrides || []).findIndex((entry) => entry.species === drop.species);
    const items = (drop.entries || []).map((entry) => `${itemName(entry.item)} ${entry.percentage ?? 100}%${entry.quantityRange ? ` (${entry.quantityRange})` : ""}`).join(", ");
    return `<div class="economy-drop-row"><span><strong>No.${drop.national_dex || "—"} ${escapeHtml(drop.ko_kr || drop.display_name || drop.species)}</strong><small>${escapeHtml(drop.en_us || "")} · ${(drop.types || []).join("/")} · ${drop.generation || "?"}세대</small></span><span><small>${escapeHtml(items || "드롭 없음")}</small></span><span>${escapeHtml(String(drop.amount ?? 0))}</span><span>${drop.origin === "override" ? "개별 재정의" : drop.origin === "rule" ? `${(drop.applied_rules || []).length}개 규칙` : "원본"}</span><span class="economy-row-actions"><button data-economy-edit-resolved-drop="${escapeHtml(drop.species)}">${overrideIndex >= 0 ? "편집" : "개별 설정"}</button>${overrideIndex >= 0 ? `<button data-economy-remove="drop" data-index="${overrideIndex}">원본 복원</button>` : ""}</span></div>`;
  }).join("") : '<div class="economy-empty">Cobblemon 종족 드롭 원본을 찾지 못했습니다.</div>';

  $("#economy-recipe-list").innerHTML = recipes.length ? recipes.map((recipe, index) => `<article class="economy-card"><header><div><span class="economy-card-kicker">특수 NPC 제작 단위</span><h4>${escapeHtml(recipe.display_name || "제작 의뢰")}<small>${escapeHtml(recipe.npc || "")}</small></h4></div><div class="economy-card-actions"><button data-economy-edit="recipe" data-index="${index}">편집</button><button data-economy-remove="recipe" data-index="${index}">삭제</button></div></header><div class="economy-recipe-flow"><div class="economy-ingredients">${(recipe.ingredients || []).map((item) => `<div class="economy-ingredient"><span><strong>${escapeHtml(itemName(item.item))}</strong><code>${escapeHtml(item.item || "")}</code></span><b>×${item.count || 1}</b></div>`).join("")}</div><div class="economy-recipe-arrow">→</div><div class="economy-output"><strong>${escapeHtml(itemName(recipe.output?.item))}</strong><code>${escapeHtml(recipe.output?.item || "")}</code><b>×${recipe.output?.count || 1}</b></div></div></article>`).join("") : '<div class="economy-empty">등록된 NPC 제작법이 없습니다.</div>';
  const offerCount = vendors.reduce((sum, vendor) => sum + (vendor.categories || []).reduce((inner, category) => inner + (category.offers || []).length, 0), 0);
  $("#economy-summary").innerHTML = `<article><span>상인 카탈로그</span><strong>${vendors.length}</strong></article><article><span>실제 판매 품목</span><strong>${offerCount}</strong></article><article><span>Cobblemon 드롭표</span><strong>${drops.length}</strong></article><article><span>NPC 제작법</span><strong>${recipes.length}</strong></article>`;
  const itemOptions = (state.economy.editor_catalog?.items || []).map((item) => `<option value="${escapeHtml(item.id)}">${escapeHtml(item.ko_kr)} · ${escapeHtml(item.en_us)}</option>`).join("");
  $("#economy-drop-item-ids").innerHTML = itemOptions; $("#economy-all-item-ids").innerHTML = itemOptions;
  $("#economy-species-ids").innerHTML = (state.economy.editor_catalog?.species || []).map((entry) => `<option value="${escapeHtml(entry.species)}">${escapeHtml(entry.ko_kr)} · ${escapeHtml(entry.en_us)}</option>`).join("");
  const filters = state.economy.editor_catalog?.filters || {};
  const typeSelect = $("#economy-pokemon-type"); if (typeSelect.options.length <= 1) typeSelect.innerHTML = '<option value="">전체 타입</option>' + (filters.types || []).map((value) => `<option value="${value}">${value}</option>`).join("");
  const genSelect = $("#economy-pokemon-generation"); if (genSelect.options.length <= 1) genSelect.innerHTML = '<option value="">전체 세대</option>' + (filters.generations || []).map((value) => `<option value="${value}">${value}세대</option>`).join("");
  if (state.settlement) renderSettlementVendorUnits();
}

function economyEditorFields(kind, entry = {}) {
  if (kind === "catalog") {
    const vendors = (state.economy.resolved_vendor_units || []).filter((vendor) => vendor.facility_scope === "department_store");
    const assignments = entry.assignments || [];
    const rows = (assignments.length ? assignments : [{ slot_id: "1f_left_a", display_name: economyLocalized("1층 왼쪽 A", "1F Left A"), vendor_unit: vendors[0]?.id || "" }]).map((assignment) => economyAssignmentRow(assignment, vendors)).join("");
    return `<label><span>백화점 이름 · 한국어</span><input name="display_name_ko" value="${escapeHtml(economyText(entry.display_name) || "새 백화점")}" required></label><label><span>백화점 이름 · English</span><input name="display_name_en" value="${escapeHtml(economyText(entry.display_name, "en_us") || "New Department Store")}" required></label><label class="wide"><span>카탈로그 ID</span><input name="id" value="${escapeHtml(entry.id || "cobbleventure:shop_catalog/new_department_store")}" required></label><input type="hidden" name="facility_scope" value="department_store"><fieldset class="wide economy-offer-editor"><legend>층·코너별 상인 배정</legend><p>각 위치에 상인 카탈로그를 배정하세요. 같은 상인을 여러 코너에 배정할 수도 있습니다.</p><div class="economy-assignment-head"><span>위치 ID</span><span>화면 이름</span><span>배정할 상인</span><span></span></div><div id="economy-assignment-rows">${rows}</div><button type="button" class="button secondary" data-add-assignment>＋ 코너 추가</button></fieldset>`;
  }
  if (kind === "shop") {
    const isNew = !entry.id;
    const templates = state.economy.resolved_vendor_units || [];
    const templateField = isNew ? `<label class="wide economy-template-copy"><span>기존 상인 템플릿 복사 (선택)</span><select name="vendor_template"><option value="">빈 상인으로 시작</option>${templates.map((vendor) => `<option value="${escapeHtml(vendor.id)}">${escapeHtml(economyText(vendor.role))} · ${escapeHtml(economyFacilityLabel(vendor.facility_scope))} · ${economyVendorOfferRows(vendor).length}개 상품</option>`).join("")}</select><small>선택한 상인의 기본 정보와 상품 목록을 복사합니다. 새 상인 ID는 복사되지 않습니다.</small></label>` : "";
    return `${templateField}<label><span>상인 역할 · 한국어</span><input name="role_ko" value="${escapeHtml(economyText(entry.role) || "새 판매원")}" required></label><label><span>상인 역할 · English</span><input name="role_en" value="${escapeHtml(economyText(entry.role, "en_us") || "New Merchant")}" required></label><label><span>NPC 표시 이름 · 한국어</span><input name="display_name_ko" value="${escapeHtml(economyText(entry.display_name) || "새 상인")}" required></label><label><span>NPC 표시 이름 · English</span><input name="display_name_en" value="${escapeHtml(economyText(entry.display_name, "en_us") || "New Merchant")}" required></label><label><span>사용 시설</span><select name="facility_scope"><option value="pokemart" ${entry.facility_scope === "pokemart" ? "selected" : ""}>프렌들리숍</option><option value="department_store" ${entry.facility_scope !== "pokemart" && entry.facility_scope !== "specialty" ? "selected" : ""}>백화점</option><option value="specialty" ${entry.facility_scope === "specialty" ? "selected" : ""}>전문 상점</option></select></label><label><span>상인 ID</span><input name="id" value="${escapeHtml(entry.id || "cobbleventure:vendor/new_merchant")}" required></label><label class="wide"><span>NBT/NPC 템플릿 ID</span><input name="npc_template" value="${escapeHtml(entry.npc_template || "cobbleventure:vendor/new_merchant")}" required></label><div class="wide economy-readonly-note">판매 상품은 상인을 만든 뒤 상품 종류별 토글에서 켜거나 끕니다.</div>`;
  }
  if (kind === "rule") {
    const filters = state.economy.editor_catalog?.filters || {}; const match = entry.match || {};
    const checks = (name, values, selected = []) => `<div class="economy-filter-checks">${values.map((value) => `<label><input type="checkbox" name="match_${name}" value="${escapeHtml(String(value))}" ${selected.includes(value) ? "checked" : ""}><span>${escapeHtml(String(value))}</span></label>`).join("")}</div>`;
    const entryRows = entry.entries || [];
    return `<label class="wide"><span>규칙 이름</span><input name="display_name" value="${escapeHtml(entry.display_name || "불꽃 타입 공통 드롭")}" required></label><label><span>규칙 ID</span><input name="id" value="${escapeHtml(entry.id || "cobbleventure:drop_rule/new_rule")}" required></label><label><span>활성화</span><select name="enabled"><option value="true" ${entry.enabled !== false ? "selected" : ""}>사용</option><option value="false" ${entry.enabled === false ? "selected" : ""}>중지</option></select></label><label><span>적용 방식</span><select name="mode"><option value="append" ${entry.mode !== "replace" ? "selected" : ""}>기존 드롭에 추가</option><option value="replace" ${entry.mode === "replace" ? "selected" : ""}>기존 드롭 교체</option></select></label><label><span>우선순위</span><input type="number" name="priority" min="-1000" max="1000" value="${entry.priority || 0}"></label><label><span>총 선택량 amount</span><input name="amount" value="${escapeHtml(String(entry.amount ?? 1))}"></label><fieldset class="wide economy-rule-filters"><legend>대상 포켓몬 조건</legend><label class="wide"><span>개별 포켓몬(선택)</span><input name="match_species" list="economy-species-ids" value="${escapeHtml((match.species || []).join(", "))}" placeholder="한국어 이름으로 검색 후 ID 선택, 여러 개는 쉼표"></label><strong>타입</strong>${checks("types", filters.types || [], match.types)}<strong>세대</strong>${checks("generations", filters.generations || [], match.generations)}<strong>분류·지역폼</strong>${checks("labels", filters.labels || [], match.labels)}<strong>폼 이름</strong>${checks("forms", filters.forms || [], match.forms)}<strong>알그룹</strong>${checks("egg_groups", filters.egg_groups || [], match.egg_groups)}<label><span>크기</span><select name="match_size"><option value="any">모든 크기</option>${["tiny","small","medium","large","giant"].map((value) => `<option value="${value}" ${match.size === value ? "selected" : ""}>${value}</option>`).join("")}</select></label></fieldset><fieldset class="wide economy-offer-editor"><legend>지급할 드롭 아이템</legend><div class="economy-offer-head"><span>메모</span><span>아이템 검색</span><span>확률 %</span><span>수량 범위</span><span></span></div><div id="economy-offer-rows">${entryRows.map((offer) => economyOfferRow({ category: "드롭", count: offer.percentage, price: offer.quantityRange || "", item: offer.item }, true)).join("")}</div><button type="button" class="button secondary" data-add-offer data-drop-row="true">＋ 드롭 아이템 추가</button></fieldset>`;
  }
  if (kind === "drop") {
    const lines = (entry.entries || []).map((item) => `${item.item} | ${item.percentage ?? 100} | ${item.quantityRange || ""}`).join("\n");
    return `<label class="wide"><span>포켓몬 종족 ID</span><input name="species" value="${escapeHtml(entry.species || "cobblemon:")}" required></label><label><span>Cobblemon amount</span><input name="amount" value="${escapeHtml(String(entry.amount ?? 1))}" required><small>한 번 처치 시 선택할 총 드롭 수량(예: 3 또는 1-3)</small></label><label class="wide"><span>Cobblemon 드롭 entries</span><textarea name="entries" rows="9" placeholder="cobblemon:thunder_stone | 5 | 0-1">${escapeHtml(lines)}</textarea><small>아이템 ID | percentage(0~100) | quantityRange(선택). Cobblemon 원본 필드명을 그대로 사용합니다.</small></label>`;
  }
  return `<label class="wide"><span>제작 의뢰 이름</span><input name="display_name" value="${escapeHtml(entry.display_name || "새 제작 의뢰")}" required></label><label><span>제작법 ID</span><input name="id" value="${escapeHtml(entry.id || "cobbleventure:recipe/new_recipe")}" required></label><label><span>담당 NPC 단위 ID</span><input name="npc" value="${escapeHtml(entry.npc || "cobbleventure:npc/")}" required></label><label><span>결과 아이템 검색</span><input name="output_item" list="economy-all-item-ids" value="${escapeHtml(entry.output?.item || "")}" required></label><label><span>결과 수량</span><input type="number" name="output_count" min="1" value="${entry.output?.count || 1}" required></label><label class="wide"><span>해금 조건</span><input name="unlock_note" value="${escapeHtml(entry.unlock_note || "")}"></label><fieldset class="wide economy-offer-editor"><legend>포켓몬 드롭 재료</legend><div class="economy-offer-head"><span>구분</span><span>아이템 검색</span><span>수량</span><span>메모</span><span></span></div><div id="economy-offer-rows">${(entry.ingredients || []).map((item) => economyOfferRow({ category: "재료", item: item.item, count: item.count, price: "" })).join("")}</div><button type="button" class="button secondary" data-add-offer>＋ 재료 추가</button></fieldset>`;
}

function economyOfferRow(offer = {}, drop = false) {
  return `<div class="economy-offer-row" data-drop-row="${drop}"><input name="offer_category" value="${escapeHtml(offer.category || (drop ? "드롭" : "일반"))}" ${drop ? "readonly" : ""}><input name="offer_item" list="economy-all-item-ids" value="${escapeHtml(offer.item || "")}" placeholder="한국어 아이템 이름 또는 ID" required><input name="offer_count" type="${drop ? "number" : "number"}" min="${drop ? ".01" : "1"}" step="${drop ? ".01" : "1"}" value="${offer.count ?? 1}" required><input name="offer_price" value="${escapeHtml(String(offer.price ?? (drop ? "" : "0")))}" placeholder="${drop ? "1-3 (선택)" : "가격"}"><button type="button" data-remove-offer aria-label="삭제">×</button></div>`;
}

function economyAssignmentRow(assignment = {}, vendors = state.economy.resolved_vendor_units || []) {
  return `<div class="economy-assignment-row"><input name="assignment_slot" value="${escapeHtml(assignment.slot_id || "new_corner")}" required><span class="economy-localized-cell"><input name="assignment_name_ko" value="${escapeHtml(economyText(assignment.display_name) || "새 코너")}" required><input name="assignment_name_en" value="${escapeHtml(economyText(assignment.display_name, "en_us") || "New Corner")}" required></span><select name="assignment_vendor">${vendors.map((vendor) => `<option value="${escapeHtml(vendor.id)}" ${vendor.id === assignment.vendor_unit ? "selected" : ""}>${escapeHtml(economyText(vendor.role))} · ${escapeHtml(economyText(vendor.display_name))}</option>`).join("")}</select><button type="button" data-remove-assignment>×</button></div>`;
}

function submitEconomyEditor(event) {
  event.preventDefault();
  const data = new FormData(event.currentTarget); const kind = String(data.get("kind")); const index = Number(data.get("index")); let entry;
  if (kind === "catalog") {
    const assignments = [...event.currentTarget.querySelectorAll(".economy-assignment-row")].map((row) => ({ slot_id: row.querySelector('[name="assignment_slot"]').value.trim(), display_name: economyLocalized(row.querySelector('[name="assignment_name_ko"]').value, row.querySelector('[name="assignment_name_en"]').value), vendor_unit: row.querySelector('[name="assignment_vendor"]').value }));
    entry = { id: String(data.get("id")).trim(), display_name: economyLocalized(data.get("display_name_ko"), data.get("display_name_en")), facility_scope: String(data.get("facility_scope")), assignments, vendor_units: assignments.map((assignment) => assignment.vendor_unit) };
  } else if (kind === "shop") {
    const existingCategories = index >= 0 ? economyCollection("shop")[index]?.categories || [] : [];
    const template = index < 0 ? (state.economy.resolved_vendor_units || []).find((vendor) => vendor.id === String(data.get("vendor_template"))) : null;
    entry = { id: String(data.get("id")).trim(), facility_scope: String(data.get("facility_scope")), role: economyLocalized(data.get("role_ko"), data.get("role_en")), display_name: economyLocalized(data.get("display_name_ko"), data.get("display_name_en")), npc_template: String(data.get("npc_template")).trim(), categories: structuredClone(template?.categories || existingCategories) };
  } else if (kind === "rule") {
    const rawAmount = String(data.get("amount")).trim();
    const values = (name, numeric = false) => data.getAll(name).map((value) => numeric ? Number(value) : String(value));
    const entries = [...event.currentTarget.querySelectorAll(".economy-offer-row")].map((row) => ({ item: row.querySelector('[name="offer_item"]').value.trim(), percentage: Number(row.querySelector('[name="offer_count"]').value), ...(row.querySelector('[name="offer_price"]').value.trim() ? { quantityRange: row.querySelector('[name="offer_price"]').value.trim() } : {}) }));
    entry = { id: String(data.get("id")).trim(), display_name: String(data.get("display_name")).trim(), enabled: data.get("enabled") === "true", priority: Number(data.get("priority")), mode: String(data.get("mode")), amount: /^\d+$/.test(rawAmount) ? Number(rawAmount) : rawAmount, match: { species: String(data.get("match_species") || "").split(",").map((value) => value.trim()).filter(Boolean), types: values("match_types"), generations: values("match_generations", true), labels: values("match_labels"), egg_groups: values("match_egg_groups"), forms: values("match_forms"), size: String(data.get("match_size") || "any") }, entries };
  } else if (kind === "drop") {
    const rawAmount = String(data.get("amount")).trim();
    entry = { species: String(data.get("species")).trim(), amount: /^\d+$/.test(rawAmount) ? Number(rawAmount) : rawAmount, entries: parseEconomyLines(String(data.get("entries")), ["item", "percentage", "quantityRange"]).map((item) => ({ item: item.item, percentage: Number(item.percentage), ...(item.quantityRange ? { quantityRange: item.quantityRange } : {}) })) };
  } else entry = { id: String(data.get("id")).trim(), npc: String(data.get("npc")).trim(), display_name: String(data.get("display_name")).trim(), output: { item: String(data.get("output_item")).trim(), count: Number(data.get("output_count")) }, ingredients: [...event.currentTarget.querySelectorAll(".economy-offer-row")].map((row) => ({ item: row.querySelector('[name="offer_item"]').value.trim(), count: Number(row.querySelector('[name="offer_count"]').value) })), unlock_note: String(data.get("unlock_note")).trim() };
  const collection = economyCollection(kind); if (index >= 0) collection[index] = entry; else collection.push(entry);
  if (kind === "catalog") state.economy.resolved_shop_catalogs = [...(state.economy.resolved_shop_catalogs || []).filter((catalog) => catalog.id !== entry.id), { ...entry, origin: "custom" }];
  if (kind === "shop") state.economy.resolved_vendor_units = [...(state.economy.resolved_vendor_units || []).filter((vendor) => vendor.id !== entry.id), { ...entry, origin: "custom" }];
  if (kind === "drop") state.economy.resolved_pokemon_drops = [...(state.economy.resolved_pokemon_drops || []).filter((drop) => drop.species !== entry.species), { ...entry, display_name: entry.species.split(":").pop(), origin: "override" }].sort((a, b) => a.species.localeCompare(b.species));
  if (kind === "rule") state.economy.resolved_pokemon_drops = state.economy.resolved_pokemon_drops || [];
  if (kind === "shop") state.economyView.selectedVendorId = entry.id;
  if (kind === "catalog") state.economyView.selectedCatalogId = entry.id;
  $("#economy-dialog").close(); renderEconomy();
}

function handleEconomyClick(event) {
  const tab = event.target.closest("[data-economy-tab]");
  if (tab) { $$('[data-economy-tab]').forEach((button) => button.classList.toggle("is-active", button === tab)); $$('[data-economy-panel]').forEach((panel) => panel.classList.toggle("is-active", panel.dataset.economyPanel === tab.dataset.economyTab)); return; }
  const vendorChoice = event.target.closest("[data-select-vendor]");
  if (vendorChoice) { state.economyView.selectedVendorId = vendorChoice.dataset.selectVendor; renderEconomy(); return; }
  const catalogChoice = event.target.closest("[data-select-catalog]");
  if (catalogChoice) { state.economyView.selectedCatalogId = catalogChoice.dataset.selectCatalog; renderEconomy(); return; }
  const productGroup = event.target.closest("[data-product-group]");
  if (productGroup) { state.economyView.vendorProductGroup = productGroup.dataset.productGroup; renderEconomy(); return; }
  const productToggle = event.target.closest("[data-toggle-vendor-product]");
  if (productToggle) {
    event.preventDefault();
    const pageScroll = { x: window.scrollX, y: window.scrollY };
    const productScroll = $(".economy-product-toggle-grid")?.scrollTop || 0;
    const vendorId = state.economyView.selectedVendorId;
    const itemId = productToggle.dataset.toggleVendorProduct;
    const editable = editableEconomyEntry("shop", vendorId); if (!editable) return;
    const existing = economyVendorOfferRows(editable.entry).find((row) => row.offer.item === itemId);
    if (existing) {
      existing.category.offers.splice(existing.offerIndex, 1);
      editable.entry.categories = (editable.entry.categories || []).filter((category) => (category.offers || []).length);
    } else {
      const groupId = economyProductGroup(itemId);
      const group = ECONOMY_PRODUCT_GROUPS.find((entry) => entry.id === groupId) || ECONOMY_PRODUCT_GROUPS.at(-1);
      let category = (editable.entry.categories || []).find((entry) => economyText(entry.name) === group.ko);
      if (!category) { category = { name: economyLocalized(group.ko, group.en), offers: [] }; editable.entry.categories ||= []; editable.entry.categories.push(category); }
      category.offers.push({ item: itemId, count: 1, price: economyStandardPrice(itemId) || "0" });
    }
    syncResolvedEconomyEntry("shop", editable.entry); renderEconomy();
    window.scrollTo(pageScroll.x, pageScroll.y);
    if ($(".economy-product-toggle-grid")) $(".economy-product-toggle-grid").scrollTop = productScroll;
    requestAnimationFrame(() => {
      window.scrollTo(pageScroll.x, pageScroll.y);
      if ($(".economy-product-toggle-grid")) $(".economy-product-toggle-grid").scrollTop = productScroll;
    });
    return;
  }
  const setToggleStandard = event.target.closest("[data-toggle-set-standard]");
  if (setToggleStandard) {
    const itemId = setToggleStandard.dataset.toggleSetStandard;
    const vendor = (state.economy.resolved_vendor_units || []).find((entry) => entry.id === state.economyView.selectedVendorId);
    const offer = economyVendorOfferRows(vendor || {}).find((row) => row.offer.item === itemId)?.offer;
    if (offer) { setEconomyStandardPrice(itemId, offer.price); renderEconomy(); toast("현재 판매가를 표준 가격으로 지정했습니다."); }
    return;
  }
  const itemPicker = event.target.closest("[data-pick-vendor-item]");
  if (itemPicker) {
    const row = itemPicker.closest(".economy-product-row");
    if (row) openEconomyItemChoice(row.dataset.vendorId, Number(row.dataset.categoryIndex), Number(row.dataset.offerIndex));
    return;
  }
  const addOffer = event.target.closest("[data-add-vendor-offer]");
  if (addOffer) {
    const editable = editableEconomyEntry("shop", addOffer.dataset.addVendorOffer); if (!editable) return;
    editable.entry.categories ||= [];
    let category = editable.entry.categories.find((entry) => economyText(entry.name) === "일반");
    if (!category) { category = { name: economyLocalized("일반", "General"), offers: [] }; editable.entry.categories.push(category); }
    category.offers ||= []; category.offers.push({ item: "", count: 1, price: "0" });
    syncResolvedEconomyEntry("shop", editable.entry); renderEconomy();
    const lastRow = $("#economy-vendor-detail .economy-product-row:last-child");
    if (lastRow) openEconomyItemChoice(lastRow.dataset.vendorId, Number(lastRow.dataset.categoryIndex), Number(lastRow.dataset.offerIndex));
    return;
  }
  const removeOffer = event.target.closest("[data-remove-vendor-offer]");
  if (removeOffer) {
    const row = removeOffer.closest(".economy-product-row"); const editable = editableEconomyEntry("shop", row?.dataset.vendorId); if (!row || !editable) return;
    const category = editable.entry.categories?.[Number(row.dataset.categoryIndex)];
    category?.offers?.splice(Number(row.dataset.offerIndex), 1);
    editable.entry.categories = (editable.entry.categories || []).filter((entry) => (entry.offers || []).length);
    syncResolvedEconomyEntry("shop", editable.entry); renderEconomy(); return;
  }
  const standardPrice = event.target.closest("[data-set-standard-price]");
  if (standardPrice) {
    const row = standardPrice.closest(".economy-product-row");
    const vendor = (state.economy.resolved_vendor_units || []).find((entry) => entry.id === row?.dataset.vendorId);
    const offer = vendor?.categories?.[Number(row?.dataset.categoryIndex)]?.offers?.[Number(row?.dataset.offerIndex)];
    if (offer?.item) { setEconomyStandardPrice(offer.item, offer.price); renderEconomy(); toast("현재 판매가를 표준 가격으로 지정했습니다."); }
    return;
  }
  const addAssignment = event.target.closest("[data-add-catalog-assignment]");
  if (addAssignment) {
    const editable = editableEconomyEntry("catalog", addAssignment.dataset.addCatalogAssignment); if (!editable) return;
    editable.entry.assignments ||= [];
    const number = editable.entry.assignments.length + 1;
    editable.entry.assignments.push({ slot_id: `corner_${number}`, display_name: economyLocalized(`${number}번 코너`, `Corner ${number}`), vendor_unit: (state.economy.resolved_vendor_units || [])[0]?.id || "" });
    editable.entry.vendor_units = editable.entry.assignments.map((assignment) => assignment.vendor_unit);
    syncResolvedEconomyEntry("catalog", editable.entry); renderEconomy();
    $("#economy-catalog-detail .economy-corner-row:last-child [data-assignment-field='display_name']")?.select(); return;
  }
  const removeAssignment = event.target.closest("[data-remove-catalog-assignment]");
  if (removeAssignment) {
    const row = removeAssignment.closest(".economy-corner-row"); const editable = editableEconomyEntry("catalog", row?.dataset.catalogId); if (!row || !editable) return;
    editable.entry.assignments?.splice(Number(row.dataset.assignmentIndex), 1);
    editable.entry.vendor_units = (editable.entry.assignments || []).map((assignment) => assignment.vendor_unit);
    syncResolvedEconomyEntry("catalog", editable.entry); renderEconomy(); return;
  }
  const resolvedDrop = event.target.closest("[data-economy-edit-resolved-drop]");
  if (resolvedDrop) { const species = resolvedDrop.dataset.economyEditResolvedDrop; const entry = (state.economy.resolved_pokemon_drops || []).find((drop) => drop.species === species); const index = (state.economy.pokemon_drop_overrides || []).findIndex((drop) => drop.species === species); if (index < 0) state.economy.pokemon_drop_overrides.push({ species, amount: entry.amount, entries: structuredClone(entry.entries || []) }); openEconomyEditor("drop", index < 0 ? state.economy.pokemon_drop_overrides.length - 1 : index); return; }
  const add = event.target.closest("[data-economy-add]"); if (add) { openEconomyEditor(add.dataset.economyAdd); return; }
  const edit = event.target.closest("[data-economy-edit]");
  if (edit) {
    let index = Number(edit.dataset.index);
    if (index < 0 && edit.dataset.entryId) index = editableEconomyEntry(edit.dataset.economyEdit, edit.dataset.entryId)?.index ?? -1;
    openEconomyEditor(edit.dataset.economyEdit, index); return;
  }
  const remove = event.target.closest("[data-economy-remove]"); if (!remove || !confirm("이 사용자 정의 항목을 삭제할까요?")) return;
  const collection = economyCollection(remove.dataset.economyRemove);
  const [removed] = collection.splice(Number(remove.dataset.index), 1);
  if (remove.dataset.economyRemove === "shop") state.economy.resolved_vendor_units = (state.economy.resolved_vendor_units || []).filter((vendor) => vendor.id !== removed?.id);
  if (remove.dataset.economyRemove === "catalog") state.economy.resolved_shop_catalogs = (state.economy.resolved_shop_catalogs || []).filter((catalog) => catalog.id !== removed?.id);
  if (remove.dataset.economyRemove === "drop") state.economy.resolved_pokemon_drops = (state.economy.resolved_pokemon_drops || []).filter((drop) => drop.species !== removed?.species);
  renderEconomy();
}

function handleEconomyInlineChange(event) {
  const toggleCount = event.target.closest("[data-toggle-product-count]");
  if (toggleCount) {
    const editable = editableEconomyEntry("shop", state.economyView.selectedVendorId); if (!editable) return;
    const offer = economyVendorOfferRows(editable.entry).find((row) => row.offer.item === toggleCount.dataset.item)?.offer;
    if (offer) { offer.count = Math.max(1, Number(toggleCount.value || 1)); syncResolvedEconomyEntry("shop", editable.entry); }
    return;
  }
  const togglePrice = event.target.closest("[data-toggle-product-price]");
  if (togglePrice) {
    const editable = editableEconomyEntry("shop", state.economyView.selectedVendorId); if (!editable) return;
    const offer = economyVendorOfferRows(editable.entry).find((row) => row.offer.item === togglePrice.dataset.item)?.offer;
    if (offer) { offer.price = String(togglePrice.value || "0"); syncResolvedEconomyEntry("shop", editable.entry); }
    return;
  }
  const offerField = event.target.closest("[data-offer-field]");
  if (offerField) {
    const row = offerField.closest(".economy-product-row"); const editable = editableEconomyEntry("shop", row?.dataset.vendorId); if (!row || !editable) return;
    const categoryIndex = Number(row.dataset.categoryIndex); const offerIndex = Number(row.dataset.offerIndex);
    const category = editable.entry.categories?.[categoryIndex]; const offer = category?.offers?.[offerIndex]; if (!offer) return;
    const field = offerField.dataset.offerField;
    if (field === "category_ko" || field === "category_en") {
      const rowInputs = row.querySelectorAll('[data-offer-field^="category_"]');
      category.name = economyLocalized(rowInputs[0].value || "일반", rowInputs[1].value || rowInputs[0].value || "General");
    } else if (field === "price") offer.price = String(offerField.value || "0");
    else offer[field] = field === "item" ? offerField.value.trim() : Number(offerField.value || 0);
    syncResolvedEconomyEntry("shop", editable.entry);
    return;
  }
  const assignmentField = event.target.closest("[data-assignment-field]");
  if (assignmentField) {
    const row = assignmentField.closest(".economy-corner-row"); const editable = editableEconomyEntry("catalog", row?.dataset.catalogId); if (!row || !editable) return;
    const assignment = editable.entry.assignments?.[Number(row.dataset.assignmentIndex)]; if (!assignment) return;
    const field = assignmentField.dataset.assignmentField;
    if (field === "display_name_ko" || field === "display_name_en") {
      const inputs = row.querySelectorAll('[data-assignment-field^="display_name_"]');
      assignment.display_name = economyLocalized(inputs[0].value, inputs[1].value);
    } else assignment[field] = assignmentField.value.trim();
    editable.entry.vendor_units = editable.entry.assignments.map((entry) => entry.vendor_unit);
    syncResolvedEconomyEntry("catalog", editable.entry); return;
  }
  const catalogField = event.target.closest("[data-catalog-field]");
  if (catalogField) {
    const editable = editableEconomyEntry("catalog", catalogField.dataset.catalogId); if (!editable) return;
    const ko = $(`[data-catalog-id="${CSS.escape(catalogField.dataset.catalogId)}"][data-catalog-field="display_name_ko"]`)?.value;
    const en = $(`[data-catalog-id="${CSS.escape(catalogField.dataset.catalogId)}"][data-catalog-field="display_name_en"]`)?.value;
    editable.entry.display_name = economyLocalized(ko, en);
    syncResolvedEconomyEntry("catalog", editable.entry); renderEconomy();
  }
}

function handleEconomyInput(event) {
  if (event.target.id !== "economy-product-search") return;
  const query = event.target.value.trim().toLocaleLowerCase("ko");
  state.economyView.vendorProductSearch = event.target.value;
  let visible = 0;
  $$("#economy-vendor-detail .economy-product-toggle").forEach((card) => {
    card.hidden = Boolean(query) && !card.textContent.toLocaleLowerCase("ko").includes(query);
    if (!card.hidden) visible += 1;
  });
  $("#economy-vendor-detail [data-product-visible-count]").textContent = `${visible}개 상품`;
}

function handleEconomyDialogClick(event) {
  const add = event.target.closest("[data-add-offer]");
  if (add) { $("#economy-offer-rows").insertAdjacentHTML("beforeend", economyOfferRow({}, add.dataset.dropRow === "true")); return; }
  const remove = event.target.closest("[data-remove-offer]");
  if (remove) { remove.closest(".economy-offer-row")?.remove(); return; }
  const addAssignment = event.target.closest("[data-add-assignment]");
  if (addAssignment) { $("#economy-assignment-rows").insertAdjacentHTML("beforeend", economyAssignmentRow()); return; }
  const removeAssignment = event.target.closest("[data-remove-assignment]");
  if (removeAssignment) removeAssignment.closest(".economy-assignment-row")?.remove();
}

function handleEconomyDialogChange(event) {
  const templateSelect = event.target.closest('[name="vendor_template"]');
  if (!templateSelect || !templateSelect.value) return;
  const template = (state.economy.resolved_vendor_units || []).find((vendor) => vendor.id === templateSelect.value);
  const form = $("#economy-form");
  if (!template || !form) return;
  form.elements.role_ko.value = economyText(template.role) || "새 판매원";
  form.elements.role_en.value = economyText(template.role, "en_us") || "New Merchant";
  form.elements.display_name_ko.value = economyText(template.display_name) || "새 상인";
  form.elements.display_name_en.value = economyText(template.display_name, "en_us") || "New Merchant";
  form.elements.facility_scope.value = template.facility_scope || "department_store";
  form.elements.npc_template.value = template.npc_template || "cobbleventure:vendor/new_merchant";
}

function updateEconomyView(field, value) {
  state.economyView[field] = value;
  renderEconomy();
}

function renderBuildCommands() {
  const descriptions = {
    validate: "모든 콘텐츠와 의존성 Lock을 빠르게 검사합니다.", test: "콘텐츠 관리와 패키징 회귀 테스트를 실행합니다.",
    "pack-smoke": "모드 없이 임포트 구조만 확인하는 ZIP을 만듭니다.", pack: "현재 설정으로 개발용 임포트 ZIP을 만듭니다.",
    "validate-pack": "실제 모드 파일과 버전이 모두 확정됐는지 검사합니다."
  };
  const languageSelect = $("#build-export-language");
  const selectedLanguage = languageSelect?.value || "ko_kr";
  if (languageSelect) {
    languageSelect.innerHTML = state.exportLanguages.map((language) =>
      `<option value="${escapeHtml(language.id)}">${escapeHtml(language.name)} · ${escapeHtml(language.id)}</option>`
    ).join("");
    languageSelect.value = state.exportLanguages.some((language) => language.id === selectedLanguage)
      ? selectedLanguage : "ko_kr";
  }
  $("#build-command-list").innerHTML = state.buildCommands.filter((command) => command.id !== "builder-world").map((command) => `
    <article class="build-command"><div><strong>${escapeHtml(command.id)}</strong><small>${escapeHtml(descriptions[command.id] || command.description)}</small></div><button class="button ${command.id.startsWith("pack") ? "primary" : "secondary"}" data-command="${escapeHtml(command.id)}">실행</button></article>`).join("");
  $$("[data-command]").forEach((button) => button.addEventListener("click", () => runBuild(button.dataset.command)));
}

function renderStructureBuilder() {
  const data = state.structureBuilder;
  if (!data) return;
  const instanceInput = $("#structure-builder-instance");
  if (document.activeElement !== instanceInput) instanceInput.value = data.instance_path || "";
  $("#structure-builder-candidates").innerHTML = (data.candidates || []).map((path) => `<option value="${escapeHtml(path)}"></option>`).join("");
  $("#structure-builder-package").textContent = data.package_exists ? "생성됨" : "없음";
  $("#structure-builder-world").textContent = data.world_exists ? "연결됨" : "찾지 못함";
  $("#structure-builder-exports").textContent = `${data.export_count || 0}개`;
  $("#structure-builder-sources").textContent = `${data.source_count || 0}개`;
  $("#structure-builder-world-path").textContent = data.world_path || "인스턴스 경로를 저장해 주세요.";
  $("#structure-builder-status").textContent = !data.instance_path ? "경로 설정 필요" : data.world_exists ? "가져오기 준비" : "월드 실행 필요";
  $("#import-structure-builder").disabled = !data.world_exists || Number(data.export_count || 0) === 0;
  $("#sync-structure-builder").disabled = !data.instance_exists;
}

async function loadStructureBuilder() {
  const result = await request("/api/structure-builder");
  if (!result.ok) throw new Error(result.data.error || "건축 월드 설정을 불러오지 못했습니다.");
  state.structureBuilder = result.data;
  renderStructureBuilder();
}

async function saveStructureBuilderSettings() {
  const instancePath = $("#structure-builder-instance").value.trim();
  const result = await request("/api/structure-builder/settings", {
    method: "PUT", body: JSON.stringify({ instance_path: instancePath })
  });
  if (!result.ok) { toast(result.data.error || "인스턴스 경로를 저장하지 못했습니다."); return; }
  state.structureBuilder = result.data;
  renderStructureBuilder();
  toast("CurseForge 건축 인스턴스 경로를 저장했습니다.");
}

async function importStructureBuilder() {
  const sourceCount = Number(state.structureBuilder?.source_count || 0);
  if (!confirm(`게임에서 내보낸 ${sourceCount}개 NBT를 검사한 뒤 content/structures의 변경 파일을 교체할까요?`)) return;
  const buttons = $$("#builds button");
  buttons.forEach((button) => button.disabled = true);
  $("#build-state").textContent = "NBT 가져오는 중";
  $("#build-output").textContent = "월드의 내보내기 NBT를 검사하고 있습니다…";
  $("#project-loading-title").textContent = "게임 NBT를 가져오는 중입니다";
  showProjectLoading("1/3 · 월드에서 내보낸 NBT를 검사하고 있습니다…");
  let completed = false;
  let failedMessage = "";
  try {
    const result = await request("/api/structure-builder/import", { method: "POST", body: "{}" });
    $("#build-output").textContent = result.data.output || result.data.error || "결과가 없습니다.";
    $("#build-state").textContent = result.ok ? "성공" : "실패";
    if (!result.ok) throw new Error(result.data.error || "NBT 가져오기에 실패했습니다. 하단 실행 결과를 확인하세요.");
    updateProjectLoading("2/3 · 저장소와 게임용 구조물 리소스를 갱신했습니다…");
    lazyDataLoaded.structures = false;
    lazyDataLoaded.buildingSettings = false;
    state.structureSizes = {};
    state.buildingSettings.structures = {};
    state.buildingSettings.model = null;
    state.buildingSettings.query = "";
    state.buildingSettings.category = "all";
    $("#building-search").value = "";
    $("#building-category").value = "all";
    updateProjectLoading("3/3 · NBT 목록과 3D 미리보기를 다시 불러오고 있습니다…");
    await loadBuildingSettingsData(true);
    switchPage("structures");
    const entries = buildingEntries();
    const preferred = entries.some(([id]) => id === state.buildingSettings.selected)
      ? state.buildingSettings.selected : entries[0]?.[0];
    if (preferred) await loadBuildingModel(preferred);
    completed = true;
  } catch (error) {
    $("#build-output").textContent = error.message;
    $("#build-state").textContent = "실패";
    failedMessage = error.message || "가져오기 결과를 확인해 주세요.";
  } finally {
    await loadStructureBuilder().catch((error) => toast(error.message));
    buttons.forEach((button) => button.disabled = false);
    renderStructureBuilder();
    $("#project-loading-title").textContent = completed
      ? "게임 NBT 가져오기가 완료되었습니다" : "게임 NBT 가져오기에 실패했습니다";
    updateProjectLoading(completed
      ? "저장소·게임 리소스·NBT 미리보기가 모두 최신 상태입니다."
      : failedMessage);
    await new Promise((resolve) => setTimeout(resolve, completed ? 700 : 1400));
    hideProjectLoading();
    setTimeout(() => {
      $("#project-loading-title").textContent = "프로젝트를 불러오는 중입니다";
      $("#project-loading-detail").textContent = "프로젝트 정보 확인 중…";
    }, 250);
    toast(completed ? "게임 NBT를 완전히 가져왔습니다." : "NBT 가져오기 결과를 확인해 주세요.");
  }
}

async function syncStructureBuilder() {
  if (!confirm("Minecraft 게임을 완전히 종료했나요? 기존 건축 월드는 백업한 뒤 새 월드로 교체됩니다.")) return;
  const buttons = $$("#builds button");
  buttons.forEach((button) => button.disabled = true);
  $("#build-state").textContent = "건축 월드 생성 중";
  $("#build-output").textContent = "최신 NBT로 건축 월드를 생성하고 인스턴스에 교체하고 있습니다…";
  try {
    const result = await request("/api/structure-builder/sync", {
      method: "POST", body: "{}"
    });
    $("#build-output").textContent = result.data.output || result.data.error || "결과가 없습니다.";
    $("#build-state").textContent = result.ok ? "성공" : "실패";
    if (!result.ok) throw new Error(result.data.error || "건축 월드 갱신에 실패했습니다.");
    toast("건축 월드를 교체했습니다. 이제 게임을 실행하면 됩니다.");
  } catch (error) {
    $("#build-state").textContent = "실패";
    toast(error.message || "건축 월드 갱신에 실패했습니다.");
  } finally {
    await loadStructureBuilder().catch((error) => toast(error.message));
    buttons.forEach((button) => button.disabled = false);
    renderStructureBuilder();
  }
}

async function runBuild(command) {
  const language = $("#build-export-language")?.value || "ko_kr";
  const buttons = $$("#builds button");
  buttons.forEach((button) => button.disabled = true);
  $("#build-state").textContent = `${command} 실행 중`;
  $("#build-output").textContent = "작업이 끝날 때까지 잠시 기다려 주세요…";
  try {
    const result = await request("/api/build", { method: "POST", body: JSON.stringify({ command, language }) });
    $("#build-output").textContent = result.data.output || result.data.error || "결과가 없습니다.";
    $("#build-state").textContent = result.ok ? "성공" : "실패";
    toast(result.ok ? `${command} 작업을 완료했습니다.` : `${command} 작업을 확인해 주세요.`);
    await loadDashboard();
  } catch (error) {
    $("#build-output").textContent = error.message;
    $("#build-state").textContent = "연결 실패";
    toast("빌드 서버 연결을 확인해 주세요.");
  } finally {
    buttons.forEach((button) => button.disabled = false);
    renderStructureBuilder();
  }
}

async function refreshAll() {
  showProjectLoading(state.project ? `${state.project.name} 기본 데이터 불러오는 중…` : "프로젝트 데이터 불러오는 중…");
  $("#server-dot").classList.remove("online");
  $("#server-label").textContent = "프로젝트 데이터 로드 중";
  const dashboardPromise = loadDashboard();
  const listsPromise = loadLists();
  const nbtPromise = Promise.all([loadStructureData(), loadBuildingSettingsData()]);
  listsPromise.then(() => {
    $("#server-dot").classList.add("online");
    $("#server-label").textContent = "기본 데이터 로드됨 · NBT 준비 중";
    updateProjectLoading("기본 목록 준비 완료 · NBT 불러오는 중…");
  }, () => {
    $("#server-dot").classList.add("online");
    $("#server-label").textContent = "일부 데이터 로드 실패";
  });
  nbtPromise.then(() => {
    $("#server-label").textContent = "프로젝트 데이터 로드됨 · 검증 중";
    updateProjectLoading("NBT 준비 완료 · 화면 여는 중…");
  }, () => {
    $("#server-label").textContent = "일부 데이터 로드 실패";
  });
  Promise.allSettled([listsPromise, nbtPromise]).then(() => hideProjectLoading());
  const [dashboardResult, listsResult, nbtResult] = await Promise.allSettled([
    dashboardPromise, listsPromise, nbtPromise
  ]);
  const errors = [dashboardResult, listsResult, nbtResult]
    .filter((result) => result.status === "rejected")
    .map((result) => result.reason?.message || "알 수 없는 로드 오류");
  if (dashboardResult.status === "rejected") {
    $("#dashboard-issues").className = "issues";
    $("#dashboard-issues").textContent = dashboardResult.reason?.message || "검증 결과를 불러오지 못했습니다.";
  }
  if (listsResult.status === "fulfilled") {
    const activeSection = $(".nav-item.is-active")?.dataset.section || "dashboard";
    try { await loadSectionData(activeSection, true); }
    catch (error) { errors.push(error.message); }
  }
  $("#server-dot").classList.add("online");
  $("#server-label").textContent = errors.length ? "일부 데이터 로드 실패" : "서버 연결됨";
  if (errors.length) toast(errors[0].split("\n")[0]);
}

$$(".nav-item").forEach((button) => button.addEventListener("click", () => switchPage(button.dataset.section)));
$("#gym-list").addEventListener("click", (event) => { const button = event.target.closest("[data-gym-id]"); if (button) { state.selectedGymId = button.dataset.gymId; state.gymLayout.selected = null; renderGymEditor(); } });
$("#gym-form").addEventListener("input", updateGymFromForm);
$("#gym-staff-editor").addEventListener("input", (event) => {
  const gym = selectedGym(); if (!gym) return;
  gym.staff ||= { leader: { league_entry_id: "", anchor: "leader" }, trainers: [] };
  if (event.target.id === "gym-leader-entry") {
    gym.staff.leader.league_entry_id = event.target.value;
    delete gym.staff.leader.trainer_id;
    delete gym.staff.leader.badge_id;
    renderGymStaff();
  } else if (event.target.id === "gym-leader-anchor") {
    gym.staff.leader.anchor = event.target.value.trim();
  } else {
    const row = event.target.closest("[data-gym-trainer]");
    const field = event.target.dataset.gymTrainerField;
    if (row && field) gym.staff.trainers[Number(row.dataset.gymTrainer)][field] = event.target.value.trim();
  }
  $("#gym-json").value = JSON.stringify(gym, null, 2);
});
$("#gym-staff-editor").addEventListener("click", (event) => {
  const button = event.target.closest("[data-remove-gym-trainer]");
  const gym = selectedGym(); if (!button || !gym) return;
  gym.staff.trainers.splice(Number(button.dataset.removeGymTrainer), 1);
  renderGymEditor();
});
$("#add-gym-trainer").addEventListener("click", () => {
  const gym = selectedGym(); if (!gym) return;
  if (!state.trainers.length) { toast("먼저 트레이너를 하나 이상 만들어 주세요."); return; }
  gym.staff ||= { leader: { league_entry_id: "", anchor: "leader" }, trainers: [] };
  gym.staff.trainers ||= [];
  const index = gym.staff.trainers.length + 1;
  const used = new Set([gym.staff.leader?.anchor, ...gym.staff.trainers.map((trainer) => trainer.anchor)]);
  const labels = (gym.interior?.modules || []).flatMap((module) =>
    (state.structureSizes[module.structure]?.npc_labels || []).map((marker) => marker.label)
  );
  const anchor = labels.find((label) => label && !used.has(label)) || `trainer_${index}`;
  gym.staff.trainers.push({ id: `trainer_${index}`, trainer_id: state.trainers[0].id, anchor });
  renderGymEditor();
});
$("#add-gym").addEventListener("click", addGym);
$("#delete-gym").addEventListener("click", deleteGym);
$("#save-gyms").addEventListener("click", saveGyms);
$("#preview-gym-exterior").addEventListener("click", async () => { const gym = selectedGym(); if (!gym) return; switchPage("structures"); await loadBuildingSettingsData(); state.buildingSettings.gymThemePreview = gym.theme; await loadBuildingModel(standardGymExterior); });
$("#add-gym-module").addEventListener("click", () => { const gym = selectedGym(); if (!gym) return; gym.interior ||= { modules: [], connections: [] }; gym.interior.modules ||= []; const structures = Object.keys(state.structureSizes).filter((id) => id.startsWith("cobbleventure:interiors/")); gym.interior.modules.push({ id: `room_${gym.interior.modules.length + 1}`, structure: structures[0] || "cobbleventure:interiors/new_room", position: [0, 0, gym.interior.modules.length * 32], rotation: "none" }); state.gymLayout.selected = gym.interior.modules.length - 1; renderGymEditor(); });
$("#interior-space-create-form").addEventListener("submit", createInteriorSpace);
$("#gym-module-list").addEventListener("input", (event) => { const card = event.target.closest("[data-gym-module]"); const gym = selectedGym(); if (!card || !gym) return; state.gymLayout.selected = Number(card.dataset.gymModule); const module = gym.interior.modules[Number(card.dataset.gymModule)]; if (event.target.dataset.gymModuleField) module[event.target.dataset.gymModuleField] = event.target.value; if (event.target.dataset.gymModuleAxis !== undefined) module.position[Number(event.target.dataset.gymModuleAxis)] = Number(event.target.value); $("#gym-json").value = JSON.stringify(gym, null, 2); renderGymLayout(); });
$("#gym-module-list").addEventListener("click", (event) => { const card = event.target.closest("[data-gym-module]"); const button = event.target.closest("[data-remove-gym-module]"); const gym = selectedGym(); if (!card || !gym) return; if (!button) { state.gymLayout.selected = Number(card.dataset.gymModule); renderGymModules(); renderGymLayout(); return; } const removedIndex = Number(button.dataset.removeGymModule); const removed = gym.interior.modules.splice(removedIndex, 1)[0]; gym.interior.connections = (gym.interior.connections || []).filter((connection) => !connection.from?.startsWith(`${removed.id}:`) && !connection.to?.startsWith(`${removed.id}:`)); state.gymLayout.selected = null; renderGymEditor(); });
$("#fit-gym-layout").addEventListener("click", () => { state.gymLayout.drag = null; renderGymLayout(); });
$("#gym-layout-canvas").addEventListener("pointerdown", (event) => {
  const canvas = event.currentTarget, point = gymLayoutPoint(event, canvas);
  const target = [...state.gymLayout.hitTargets].reverse().find((item) => point.x >= item.minX && point.x <= item.maxX && point.y >= item.minY && point.y <= item.maxY);
  if (!target) { state.gymLayout.selected = null; renderGymModules(); renderGymLayout(); return; }
  const gym = selectedGym(), module = gym?.interior?.modules?.[target.index]; if (!module) return;
  state.gymLayout.selected = target.index;
  const infos = gym.interior.modules.map(gymModuleLayoutInfo);
  state.gymLayout.drag = { index: target.index, startX: point.x, startY: point.y, originX: Number(module.position?.[0]) || 0, originZ: Number(module.position?.[2]) || 0, projection: gymLayoutProjection(infos, canvas) };
  canvas.setPointerCapture(event.pointerId); canvas.classList.add("is-dragging"); renderGymModules(); renderGymLayout();
});
$("#gym-layout-canvas").addEventListener("pointermove", (event) => {
  const drag = state.gymLayout.drag, gym = selectedGym(); if (!drag || !gym) return;
  const point = gymLayoutPoint(event, event.currentTarget), module = gym.interior.modules[drag.index];
  module.position[0] = drag.originX + Math.round((point.x - drag.startX) / drag.projection.scale);
  module.position[2] = drag.originZ + Math.round((point.y - drag.startY) / drag.projection.scale);
  $("#gym-json").value = JSON.stringify(gym, null, 2); renderGymLayout();
});
for (const eventName of ["pointerup", "pointercancel"]) $("#gym-layout-canvas").addEventListener(eventName, (event) => {
  if (!state.gymLayout.drag) return;
  state.gymLayout.drag = null; event.currentTarget.classList.remove("is-dragging");
  if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
  renderGymModules(); renderGymLayout();
});
$("#apply-gym-json").addEventListener("click", () => { const document = parseEditor("#gym-json"); if (!document) return; const index = state.gymCatalog.gyms.findIndex((gym) => gym.id === state.selectedGymId); if (index >= 0) { state.gymCatalog.gyms[index] = document; state.selectedGymId = document.id; renderGymEditor(); toast("JSON을 체육관 폼에 반영했습니다."); } });
$("#building-search").addEventListener("input", (event) => { state.buildingSettings.query = event.target.value; renderBuildingList(); });
$("#building-category").addEventListener("change", (event) => { state.buildingSettings.category = event.target.value; renderBuildingList(); });
$("#building-list").addEventListener("click", (event) => {
  const button = event.target.closest("[data-building-id]");
  if (button) { state.buildingSettings.gymThemePreview = null; loadBuildingModel(button.dataset.buildingId); }
});
$("#building-model-canvas").addEventListener("pointerdown", (event) => {
  const view = state.buildingSettings;
  view.drag = { x: event.clientX, y: event.clientY, yaw: view.yaw, pitch: view.pitch };
  event.currentTarget.setPointerCapture(event.pointerId); event.currentTarget.classList.add("is-dragging");
});
$("#building-model-canvas").addEventListener("pointermove", (event) => {
  const view = state.buildingSettings, drag = view.drag; if (!drag) return;
  view.yaw = drag.yaw + (event.clientX - drag.x) * .012;
  view.pitch = Math.max(structureViewPitch.minimum, Math.min(structureViewPitch.maximum, drag.pitch - (event.clientY - drag.y) * .008));
  renderBuildingModel();
});
for (const eventName of ["pointerup", "pointercancel"]) $("#building-model-canvas").addEventListener(eventName, (event) => { state.buildingSettings.drag = null; event.currentTarget.classList.remove("is-dragging"); });
$("#building-model-canvas").addEventListener("wheel", (event) => {
  event.preventDefault(); state.buildingSettings.zoom = Math.max(.45, Math.min(4, state.buildingSettings.zoom * (event.deltaY < 0 ? 1.12 : .89))); renderBuildingModel();
}, { passive: false });
$("#building-view-left").addEventListener("click", () => { state.buildingSettings.yaw -= Math.PI / 8; renderBuildingModel(); });
$("#building-view-right").addEventListener("click", () => { state.buildingSettings.yaw += Math.PI / 8; renderBuildingModel(); });
$("#building-view-reset").addEventListener("click", () => { Object.assign(state.buildingSettings, { yaw: -.75, pitch: structureViewPitch.default, zoom: 1, drag: null }); renderBuildingModel(); });
$("#building-npc-assignments").addEventListener("change", (event) => {
  const label = event.target.dataset.buildingNpcLabel; if (!label) return;
  const metadata = state.buildingSettings.structures[state.buildingSettings.selected];
  if (!metadata || metadata.settings?.citizen_placement_allowed) return;
  metadata.settings.fixed_npcs ||= {};
  if (event.target.value) metadata.settings.fixed_npcs[label] = event.target.value;
  else delete metadata.settings.fixed_npcs[label];
  markBuildingSettingsDirty();
});
$("#add-building-interior").addEventListener("click", () => {
  const metadata = state.buildingSettings.structures[state.buildingSettings.selected];
  if (!metadata) return;
  const candidates = Object.entries(state.buildingSettings.structures)
    .filter(([, entry]) => ["interior", "gym_interior"].includes(entry.category));
  if (!candidates.length) return toast("먼저 건축 월드에서 내부 NBT를 만들고 가져오세요.");
  metadata.settings ||= {};
  const entries = metadata.settings.interiors ||= [];
  let index = entries.length + 1, key = `room_${index}`;
  while (entries.some((entry) => entry.key === key)) key = `room_${++index}`;
  entries.push({ key, structure: candidates[0][0] });
  markBuildingSettingsDirty();
});
$("#building-interior-assignments").addEventListener("change", (event) => {
  const row = event.target.closest("[data-building-interior]");
  const field = event.target.dataset.buildingInteriorField;
  const metadata = state.buildingSettings.structures[state.buildingSettings.selected];
  if (!row || !field || !metadata) return;
  metadata.settings.interiors[Number(row.dataset.buildingInterior)][field] = event.target.value;
  markBuildingSettingsDirty();
});
$("#building-interior-assignments").addEventListener("click", (event) => {
  const button = event.target.closest("[data-remove-building-interior]");
  const metadata = state.buildingSettings.structures[state.buildingSettings.selected];
  if (!button || !metadata) return;
  const [removed] = metadata.settings.interiors.splice(Number(button.dataset.removeBuildingInterior), 1);
  for (const key of Object.keys(metadata.settings.door_routes || {})) {
    const route = metadata.settings.door_routes[key];
    if (key.startsWith(`${removed.key}:`) || route.space === removed.key) delete metadata.settings.door_routes[key];
  }
  markBuildingSettingsDirty();
});
$("#building-door-routes").addEventListener("change", (event) => {
  const source = event.target.dataset.buildingDoorRoute;
  const metadata = state.buildingSettings.structures[state.buildingSettings.selected];
  if (!source || !metadata) return;
  metadata.settings.door_routes ||= {};
  if (!event.target.value) delete metadata.settings.door_routes[source];
  else {
    const [space, arrival] = event.target.value.split(":", 2);
    metadata.settings.door_routes[source] = { space, arrival };
  }
  markBuildingSettingsDirty();
});
$("#building-citizen-placement").addEventListener("change", (event) => {
  const metadata = state.buildingSettings.structures[state.buildingSettings.selected];
  if (!metadata) return;
  metadata.settings.citizen_placement_allowed = event.target.checked;
  if (event.target.checked) metadata.settings.fixed_npcs = {};
  markBuildingSettingsDirty();
});
$("#building-no-interior").addEventListener("change", (event) => {
  const metadata = state.buildingSettings.structures[state.buildingSettings.selected];
  if (!metadata) return;
  metadata.settings.no_interior_space = event.target.checked;
  if (event.target.checked) {
    metadata.settings.citizen_placement_allowed = false;
    metadata.settings.interiors = [];
    metadata.settings.door_routes = {};
  }
  markBuildingSettingsDirty();
});
$("#building-placement-y-offset").addEventListener("change", (event) => {
  const metadata = state.buildingSettings.structures[state.buildingSettings.selected];
  if (!metadata) return;
  const value = Number(event.target.value);
  if (!Number.isInteger(value) || value < -64 || value > 64) {
    event.target.value = Number(metadata.settings?.placement_y_offset || 0);
    return toast("Y 배치 보정값은 -64~64 범위의 정수여야 합니다.");
  }
  metadata.settings.placement_y_offset = value;
  markBuildingSettingsDirty();
});
$("#building-music-track").addEventListener("change", (event) => {
  const metadata = state.buildingSettings.structures[state.buildingSettings.selected];
  if (!metadata) return;
  if (event.target.value) metadata.settings.music_track = event.target.value;
  else delete metadata.settings.music_track;
  markBuildingSettingsDirty();
});
$("#save-building-settings").addEventListener("click", saveBuildingSettings);
$("#resize-building-nbt").addEventListener("click", resizeSelectedBuilding);
$("#refresh-nbt-catalog").addEventListener("click", async (event) => {
  const button = event.currentTarget;
  button.disabled = true;
  button.textContent = "목록 갱신 중…";
  try {
    lazyDataLoaded.structures = false;
    lazyDataLoaded.buildingSettings = false;
    await loadBuildingSettingsData(true);
    toast("NBT 목록 캐시를 최신 상태로 갱신했습니다.");
  } catch (error) {
    toast(error.message || "NBT 목록 갱신에 실패했습니다.");
  } finally {
    button.disabled = false;
    button.textContent = "NBT 목록 갱신";
  }
});
$("#refresh-button").addEventListener("click", () => refreshAll());
$("#open-project").addEventListener("click", openProjectDialog);
$("#project-form").addEventListener("submit", loadProject);
$("#pick-project-folder").addEventListener("click", pickProjectFolder);
$("#project-close").addEventListener("click", () => $("#project-dialog").close());
$("#project-cancel").addEventListener("click", () => $("#project-dialog").close());
$("#save-structure-builder-settings").addEventListener("click", saveStructureBuilderSettings);
$("#refresh-structure-builder").addEventListener("click", () => loadStructureBuilder().catch((error) => toast(error.message)));
$("#build-structure-builder").addEventListener("click", async () => { await runBuild("builder-world"); await loadStructureBuilder().catch((error) => toast(error.message)); });
$("#sync-structure-builder").addEventListener("click", syncStructureBuilder);
$("#import-structure-builder").addEventListener("click", importStructureBuilder);
$("#add-game-item").addEventListener("click", () => addGameDefinition("item"));
$("#add-game-variable").addEventListener("click", () => addGameDefinition("variable"));
$("#save-game-definitions").addEventListener("click", saveGameDefinitions);
$("#save-music-settings").addEventListener("click", saveMusicSettings);
$("#refresh-music-library").addEventListener("click", refreshMusicLibrary);
$("#definitions").addEventListener("input", handleDefinitionInput);
$("#definitions").addEventListener("change", handleDefinitionInput);
$("#definitions").addEventListener("click", handleDefinitionClick);
$("#validate-repository").addEventListener("click", loadDashboard);
$("#validate-trainer").addEventListener("click", () => validateDocument("trainers"));
$("#save-trainer").addEventListener("click", () => saveDocument("trainers"));
$("#delete-trainer").addEventListener("click", () => deleteManagedDocument("trainers"));
$("#validate-battle").addEventListener("click", () => validateDocument("battles"));
$("#save-battle").addEventListener("click", () => saveDocument("battles"));
$("#delete-battle").addEventListener("click", () => deleteManagedDocument("battles"));
$("#add-league-entry").addEventListener("click", addLeagueEntry);
$("#save-league").addEventListener("click", saveLeagueProgression);
$("#badge-generation-filter").addEventListener("change", renderTrainerCardManager);
$("#badge-library-grid").addEventListener("click", (event) => {
  const add = event.target.closest("[data-card-add]");
  const remove = event.target.closest("[data-card-remove]");
  if (add) setTrainerCardEntryVisible(add.dataset.cardAdd, true);
  if (remove) setTrainerCardEntryVisible(remove.dataset.cardRemove, false);
});
$("#trainer-card-order-list").addEventListener("click", (event) => {
  const move = event.target.closest("[data-card-move]");
  const remove = event.target.closest("[data-card-remove]");
  if (move) moveTrainerCardEntry(move.closest("[data-card-entry]").dataset.cardEntry, Number(move.dataset.cardMove));
  if (remove) setTrainerCardEntryVisible(remove.dataset.cardRemove, false);
});
$("#save-trainer-card-order").addEventListener("click", saveLeagueProgression);
$("#delete-league-entry").addEventListener("click", () => {
  const entry = selectedLeagueEntry(); if (!entry || !confirm(`'${entry.display_name?.ko_kr || entry.id}' 리그 항목을 삭제할까요?`)) return;
  state.leagueProgression.entries = state.leagueProgression.entries.filter((candidate) => candidate !== entry);
  state.selectedLeagueId = ""; renderLeagueEditor();
});
$("#league-form").addEventListener("input", () => {
  updateLeagueEntryFromForm(); renderLeagueList();
  const entry = selectedLeagueEntry(); const isGym = entry?.role === "gym_leader";
  $$("#league-form .league-badge-fields input, #league-form .league-badge-fields select").forEach((element) => { element.disabled = !isGym; });
  $("#edit-league-trainer").disabled = !entry?.trainer_id;
  $("#league-editor-title").textContent = entry?.display_name?.ko_kr || entry?.id || "리그 항목을 선택하세요";
  $("#league-card-derived").hidden = !isGym;
  $("#league-trainer-link").hidden = isGym;
  $("#league-encounter-fields").hidden = !isGym;
  $("#league-display-badge-fields").hidden = isGym;
  renderLeagueAppearancePreview();
  renderLeagueBadgePreviews();
});
$("#league-form").addEventListener("change", (event) => {
  const form = event.currentTarget;
  if (event.target.name === "rosterCharacter") {
    const character = rosterCharacters().find((item) => item.id === form.elements.rosterCharacter.value);
    if (character) {
      const appearance = effectiveCharacterAppearance(character);
      form.elements.appearanceSource.value = appearance.source || "rct_single";
      form.elements.appearanceResource.value = appearance.resource || "";
      updateLeagueEntryFromForm();
      renderLeagueAppearancePreview();
    }
  }
});
$("#choose-league-reward-item").addEventListener("click", () => openItemChoice("league-reward-item", {
  allowEmpty: true,
  title: "관장 승리 아이템 선택",
  subtitle: "관장에게 승리한 뒤 지급할 아이템을 선택합니다."
}));
$$('[data-league-workspace]').forEach((tab) => tab.addEventListener("click", () => {
  const target = tab.dataset.leagueWorkspace;
  if (target === "league") {
    switchPage("league");
    renderLeagueEditor();
    return;
  }
  if ($("#league").classList.contains("is-active") && selectedLeagueEntry()) updateLeagueEntryFromForm();
  const linkedGym = gymForLeagueEntry(selectedLeagueEntry()?.id);
  if (linkedGym) state.selectedGymId = linkedGym.id;
  switchPage("gyms");
  renderGymEditor();
}));
$("#league-member-form").addEventListener("submit", createLeagueMember);
$("#league-member-form").addEventListener("change", (event) => {
  if (event.target.name === "role") updateLeagueMemberDialog();
  if (event.target.name === "rosterCharacter") {
    const form = event.currentTarget;
    const character = rosterCharacters().find((entry) => entry.id === form.elements.rosterCharacter.value);
    if (!character) return;
    const appearance = effectiveCharacterAppearance(character);
    form.elements.name.value = character.display_name?.ko_kr || form.elements.name.value;
    form.elements.nameEn.value = character.display_name?.en_us || form.elements.nameEn.value;
    form.elements.appearanceResource.value = appearance.resource || form.elements.appearanceResource.value;
  }
});
$("#league-member-close").addEventListener("click", () => $("#league-member-dialog").close());
$("#league-member-cancel").addEventListener("click", () => $("#league-member-dialog").close());
$("#edit-league-trainer").addEventListener("click", async () => {
  updateLeagueEntryFromForm(); const entry = selectedLeagueEntry(); const trainer = state.trainers.find((candidate) => candidate.id === entry?.trainer_id);
  if (!trainer) { toast("트레이너풀에서 NPC를 먼저 선택해 주세요."); return; }
  switchPage("trainers"); await loadDocument("trainers", trainer.path);
});
$("#edit-object-npc").addEventListener("click", async () => {
  const preset = $("#object-tool-npc").value;
  const slug = preset.match(/\/([^/]+)\.npc\.snbt$/)?.[1];
  const trainer = state.trainers.find((candidate) => candidate.id?.split("/").at(-1) === slug);
  if (!trainer) { toast("먼저 기존 NPC 프리셋을 선택해 주세요."); return; }
  switchPage("trainers"); await loadDocument("trainers", trainer.path);
});
$("#object-tool-building-enabled").addEventListener("change", updateGateOptionVisibility);
$("#object-tool-gate-mode").addEventListener("change", updateGateOptionVisibility);
$("#object-tool-surrounding-type").addEventListener("change", updateGateOptionVisibility);
$("#object-tool-type").addEventListener("change", updateGateOptionVisibility);
$("#battle-form").addEventListener("change", (event) => {
  const form = event.currentTarget;
  if (event.target.name === "battleType") form.elements.format.value = event.target.value === "doubles" ? "GEN_9_DOUBLES" : "GEN_9_SINGLES";
  if (event.target.name === "format") form.elements.battleType.value = event.target.value === "GEN_9_DOUBLES" ? "doubles" : "singles";
  updateBattlePresetFromForm();
});
$("#apply-battle-json").addEventListener("click", () => {
  const document = parseEditor("#battle-json");
  if (document) { state.battlePreset = document; renderBattlePreset(); toast("JSON을 배틀 프리셋 폼에 반영했습니다."); }
});
$("#copy-spawn-command").addEventListener("click", async () => {
  await navigator.clipboard.writeText($("#trainer-form").elements.spawnCommand.value);
  toast("EasyNPC 소환 명령어를 복사했습니다.");
});
$("#copy-double-spawn-command").addEventListener("click", async () => {
  await navigator.clipboard.writeText($("#trainer-form").elements.doubleBattleSpawnCommand.value);
  toast("파트너 EasyNPC 소환 명령어를 복사했습니다.");
});
$("#trainer-form").addEventListener("input", (event) => {
  if (event.target.name === "trainerClass") applyTrainerClass();
  else if (event.target.name === "rosterCharacter") applyRosterCharacter();
  else if (event.target.name === "appearanceSource") applyAppearanceSource();
  else {
    const form = event.currentTarget;
    if (event.target.name === "battleType") {
      form.elements.battleFormat.value = event.target.value === "doubles" ? "GEN_9_DOUBLES" : "GEN_9_SINGLES";
    } else if (event.target.name === "battleFormat") {
      form.elements.battleType.value = event.target.value === "GEN_9_DOUBLES" ? "doubles" : "singles";
    }
    updateTrainerFromForm();
  }
});
$("#add-pokemon").addEventListener("click", addPokemon);
$("#add-event-command").addEventListener("click", addEventCommand);
$("#event-preset-type").addEventListener("change", renderEventPresetFields);
$("#event-preset-clear-key").addEventListener("change", () => updatePresetClearKeyMode(true));
$("#apply-event-preset").addEventListener("click", applyEventScriptPreset);
$("#event-preset-builder").addEventListener("click", (event) => {
  const button = event.target.closest("[data-item-picker]");
  if (!button || button.disabled) return;
  openItemChoice(button.dataset.itemPicker, {
    title: button.dataset.itemPickerTitle,
    allowEmpty: button.dataset.itemPickerEmpty === "true",
  });
});
$("#event-trigger-type").addEventListener("change", updateEventTrigger);
$("#event-trigger-range").addEventListener("change", updateEventTrigger);
$("#event-warning-offset").addEventListener("change", updateEventTrigger);
$("#event-command-list").addEventListener("input", handleEventCommandInput);
$("#event-command-list").addEventListener("change", splitDialogueCommandLines);
$("#event-command-list").addEventListener("click", handleEventCommandClick);
$("#add-bag-item").addEventListener("click", addBagItem);
$("#max-item-uses").addEventListener("input", () => { updateBattlePresetFromForm(); syncTrainerJson(); });
$("#copy-team-json").addEventListener("click", copyTeamJson);
$("#paste-team-json").addEventListener("click", pasteTeamJson);
$("#load-trainer-reference").addEventListener("click", () => openChoiceDialog("trainer_reference"));
$("#choose-create-reference").addEventListener("click", () => openChoiceDialog("trainer_reference_create"));
$("#apply-trainer-json").addEventListener("click", () => { const document = parseEditor("#trainer-json"); if (document) { state.trainer = document; renderTrainer(); toast("JSON을 편집 폼에 반영했습니다."); } });
$("#validate-settlement").addEventListener("click", () => validateDocument("settlements"));
$("#save-settlement").addEventListener("click", () => saveDocument("settlements"));
$("#validate-cave").addEventListener("click", () => validateDocument("caves"));
$("#save-cave").addEventListener("click", () => saveDocument("caves"));
$("#delete-cave").addEventListener("click", () => deleteManagedDocument("caves"));
$("#cave-form").addEventListener("input", (event) => { if (handleCavePlacementInput(event) || handleCavePreviewInspectorInput(event)) return; updateCaveFromForm(); renderCaveLayoutPreview(); });
$("#cave-form").addEventListener("change", (event) => { if (handleCavePlacementInput(event) || handleCavePreviewInspectorInput(event)) return; updateCaveFromForm(); renderCaveLayoutPreview(); });
$("#cave-form").addEventListener("click", handleCaveEditorClick);
$("#cave-layout-canvas").addEventListener("pointerdown", beginCavePreviewDrag);
$("#cave-layout-canvas").addEventListener("pointermove", moveCavePreviewDrag);
$("#cave-layout-canvas").addEventListener("pointerup", endCavePreviewDrag);
$("#cave-layout-canvas").addEventListener("pointercancel", endCavePreviewDrag);
$("#cave-layout-canvas").addEventListener("wheel", (event) => { event.preventDefault(); state.cavePreview.zoom = Math.max(.55, Math.min(2.2, state.cavePreview.zoom * (event.deltaY > 0 ? .9 : 1.1))); renderCaveLayoutPreview(); }, { passive: false });
$("#reset-cave-preview-view").addEventListener("click", () => { state.cavePreview.yaw = -.72; state.cavePreview.pitch = -.52; state.cavePreview.zoom = 1; state.cavePreview.view = "perspective"; state.cavePreview.drag = null; renderCaveLayoutPreview(); });
$("#regenerate-cave-preview").addEventListener("click", () => {
  if (!state.cave) return;
  if (state.cave.generator?.manual_layout?.enabled) { openCaveGeneratorDialog(); return; }
  state.cave.generator.seed_salt = (Number(state.cave.generator.seed_salt) || 0) + 1;
  renderCaveLayoutPreview();
});
$("#open-cave-generator-dialog").addEventListener("click", openCaveGeneratorDialog);
$("#generate-cave-layout").addEventListener("click", applyCaveGeneratorDialog);
$$('[data-cave-view]').forEach((button) => button.addEventListener("click", () => { state.cavePreview.view = button.dataset.caveView; state.cavePreview.drag = null; renderCaveLayoutPreview(); }));
$("[data-clear-cave-selection]").addEventListener("click", () => { state.cavePreview.selected = null; renderCaveLayoutPreview(); });
function prepareUnifiedSpatialEditors() {
  const caveForm = $("#cave-form");
  const forestForm = $("#forest-form");
  const caveVisualEditor = $(".cave-layout-preview");
  const forestVisualEditor = $(".forest-layout-editor");
  if (caveForm && caveVisualEditor) {
    caveVisualEditor.classList.add("primary-spatial-editor");
  }
  if (forestForm && forestVisualEditor) {
    forestVisualEditor.classList.add("primary-spatial-editor");
  }
  const caveToolbar = $(".cave-layout-preview .spatial-editor-toolbar");
  if (caveToolbar && !caveToolbar.querySelector('[data-cave-preview-tool="select"]')) {
    caveToolbar.insertAdjacentHTML("afterbegin", `<button type="button" class="button secondary cave-map-tool-button" data-cave-preview-tool="select" aria-label="동굴 요소 선택">↖<small>선택</small></button><div class="spatial-tool-contexts"><section class="spatial-tool-context" data-tool-context="select"><span class="spatial-context-kicker">SELECT TOOL</span><strong>선택 도구</strong><p>공동·입구·통로를 선택하거나 드래그해 이동합니다.</p><div id="cave-entrance-quick-list" class="cave-entrance-quick-list"></div><div class="spatial-gesture-list"><b>클릭</b><span>요소 선택</span><b>드래그</b><span>선택 요소 이동</span></div></section><section class="spatial-tool-context" data-tool-context="add-anchor"><span class="spatial-context-kicker">PLACE TOOL</span><strong>공동 추가</strong><p>중앙 지도에서 새 공동의 중심을 지정합니다.</p><div class="spatial-gesture-list"><b>클릭</b><span>공동 배치</span><b>Esc</b><span>선택 도구 복귀</span></div></section><section class="spatial-tool-context" data-tool-context="add-entrance"><span class="spatial-context-kicker">PLACE TOOL</span><strong>입구 추가</strong><p>지도에 새 입출구를 배치합니다.</p><div class="spatial-gesture-list"><b>클릭</b><span>입구 배치</span><b>Esc</b><span>선택 도구 복귀</span></div></section><section class="spatial-tool-context" data-tool-context="connect"><span class="spatial-context-kicker">ROUTE TOOL</span><strong>길 만들기</strong><p>두 공동 또는 입출구를 차례로 선택합니다.</p><div class="spatial-gesture-list"><b>1차 클릭</b><span>시작점 선택</span><b>2차 클릭</b><span>통로 연결</span></div></section></div>`);
  }
  const forestToolbar = $(".forest-layout-editor .spatial-editor-toolbar");
  if (forestToolbar && !forestToolbar.querySelector('[data-forest-tool="select"]')) {
    forestToolbar.insertAdjacentHTML("afterbegin", `<button type="button" class="button secondary" data-forest-tool="select" aria-label="숲 요소 선택">↖<small>선택</small></button><div class="spatial-tool-contexts"><section class="spatial-tool-context" data-tool-context="select"><span class="spatial-context-kicker">SELECT TOOL</span><strong>선택 도구</strong><p>길 앵커와 입출구를 선택하고 드래그해 이동합니다.</p><div class="spatial-gesture-list"><b>클릭</b><span>길 속성 선택</span><b>드래그</b><span>앵커·입출구 이동</span></div></section><section class="spatial-tool-context" data-tool-context="path"><span class="spatial-context-kicker">ROUTE TOOL</span><strong>길 잇기</strong><p>선택한 길의 끝에서 새 앵커를 계속 추가합니다.</p><div class="spatial-gesture-list"><b>클릭</b><span>새 앵커 추가</span><b>드래그</b><span>기존 앵커 이동</span></div></section><section class="spatial-tool-context" data-tool-context="height"><span class="spatial-context-kicker">HEIGHT BRUSH</span><strong>높이 브러시</strong><p>격자 타일의 높이를 올리거나 낮추고 초기화합니다.</p><div class="spatial-gesture-list"><b>클릭</b><span>한 번 적용</span><b>드래그</b><span>연속 칠하기</span></div></section><section class="spatial-tool-context" data-tool-context="stairs"><span class="spatial-context-kicker">HEIGHT TRANSITION</span><strong>계단·경사 배치</strong><p>주변에 2칸 이상의 높이 차가 있는 타일을 클릭해 이동 경로를 만듭니다.</p><fieldset class="cave-placement-settings"><legend>설치 속성</legend><label><span>전환 종류</span><select data-forest-stair-field="kind"><option value="stairs">계단</option><option value="slope">경사로</option></select></label><label><span>오름 방향</span><select data-forest-stair-field="direction"><option value="auto">자동</option><option value="north">북쪽</option><option value="south">남쪽</option><option value="east">동쪽</option><option value="west">서쪽</option></select></label><label class="wide"><span>설치 블록</span><input data-forest-stair-field="block" value="minecraft:oak_stairs"></label></fieldset><div class="spatial-gesture-list"><b>클릭</b><span>계단 배치·같은 설정이면 제거</span></div></section></div>`);
  }
  const cavePlacementPanels = {
    "add-anchor": `<fieldset class="cave-placement-settings"><legend>설치 속성</legend><label class="wide"><span>ID 접두어</span><input data-cave-placement-group="anchor" data-cave-placement-field="idPrefix" value="anchor"></label><label class="wide"><span>공동 종류</span><select data-cave-placement-group="anchor" data-cave-placement-field="kind"><option value="room">일반 공동</option><option value="grand">대공동</option><option value="junction">통로 교차점</option><option value="landmark">랜드마크 공동</option></select></label><label><span>X 반경</span><input type="number" min="3" max="96" value="12" data-cave-placement-group="anchor" data-cave-placement-field="radiusX"></label><label><span>Z 반경</span><input type="number" min="3" max="96" value="12" data-cave-placement-group="anchor" data-cave-placement-field="radiusZ"></label><label class="wide"><span>공동 높이</span><input type="number" min="5" max="96" value="12" data-cave-placement-group="anchor" data-cave-placement-field="height"></label></fieldset>`,
    "add-entrance": `<fieldset class="cave-placement-settings"><legend>설치 속성</legend><label><span>ID 접두어</span><input data-cave-placement-group="entrance" data-cave-placement-field="idPrefix" value="entrance"></label><label><span>표시 이름</span><input data-cave-placement-group="entrance" data-cave-placement-field="displayName" value="입출구"></label><label class="wide"><span>필요 진행도</span><input data-cave-placement-group="entrance" data-cave-placement-field="requiredProgress" placeholder="선택 사항"></label><label><span>안전 X 간격</span><input type="number" value="4" data-cave-placement-group="entrance" data-cave-placement-field="fallbackX"></label><label><span>안전 Y 간격</span><input type="number" value="1" data-cave-placement-group="entrance" data-cave-placement-field="fallbackY"></label><label class="wide"><span>안전 Z 간격</span><input type="number" value="0" data-cave-placement-group="entrance" data-cave-placement-field="fallbackZ"></label></fieldset>`,
    connect: `<fieldset class="cave-placement-settings"><legend>설치 속성</legend><label class="wide"><span>ID 접두어</span><input data-cave-placement-group="path" data-cave-placement-field="idPrefix" value="connection"></label><label><span>통로 종류</span><select data-cave-placement-group="path" data-cave-placement-field="kind"><option value="tunnel">일반 통로</option><option value="stairs">계단 통로</option><option value="bridge">자연 돌다리</option></select></label><label><span>너비</span><input type="number" min="3" max="15" value="5" data-cave-placement-group="path" data-cave-placement-field="width"></label></fieldset>`
  };
  Object.entries(cavePlacementPanels).forEach(([tool, markup]) => {
    const context = caveToolbar?.querySelector(`[data-tool-context="${tool}"]`);
    if (context && !context.querySelector(".cave-placement-settings")) context.querySelector(".spatial-gesture-list")?.insertAdjacentHTML("beforebegin", markup);
  });
  const caveToolCopy = { select: ["↖", "선택"], "add-anchor": ["●", "공동"], "add-entrance": ["◆", "입구"], connect: ["⌁", "길"] };
  caveToolbar?.querySelectorAll("[data-cave-preview-tool]").forEach((button) => {
    const copy = caveToolCopy[button.dataset.cavePreviewTool]; if (copy) button.innerHTML = `<span>${copy[0]}</span><small>${copy[1]}</small>`;
  });
  const forestToolCopy = { select: ["↖", "선택"], path: ["⌁", "길"], "height-up": ["▲", "높이+"], "height-down": ["▼", "높이−"], "height-reset": ["○", "초기화"], stairs: ["▤", "계단"] };
  forestToolbar?.querySelectorAll("[data-forest-tool]").forEach((button) => {
    const copy = forestToolCopy[button.dataset.forestTool]; if (copy) button.innerHTML = `<span>${copy[0]}</span><small>${copy[1]}</small>`;
  });
  const caveEditor = $(".cave-layout-preview");
  const forestEditor = $(".forest-layout-editor");
  if (forestToolbar && !forestToolbar.querySelector(".spatial-toolbar-help")) forestToolbar.insertAdjacentHTML("beforeend", `<div class="spatial-toolbar-help"><b>FREE POSITION</b><span>길과 입출구는 자유 배치되며 높이 도구만 타일 격자를 사용합니다.</span></div>`);
  const caveInspectorTitle = caveEditor?.querySelector(".cave-node-inspector > header");
  if (caveInspectorTitle && !caveInspectorTitle.querySelector(".spatial-inspector-kicker")) caveInspectorTitle.insertAdjacentHTML("afterbegin", `<span class="spatial-inspector-kicker">SELECTED ELEMENT</span>`);
  const forestBody = forestEditor?.querySelector(".forest-layout-body");
  if (forestBody && !forestEditor.querySelector(".forest-preview-legend")) forestBody.insertAdjacentHTML("beforebegin", `<div class="forest-preview-legend spatial-editor-legend"><span data-kind="path">주 경로</span><span data-kind="shortcut">지름길</span><span data-kind="entrance">입출구</span><span data-kind="height-up">높은 타일</span><span data-kind="height-down">낮은 타일</span><span data-kind="stairs">계단·경사</span></div>`);
  const forestInspector = forestEditor?.querySelector(".spatial-editor-inspector");
  if (forestInspector && !forestInspector.querySelector(".spatial-inspector-heading")) forestInspector.insertAdjacentHTML("afterbegin", `<header class="spatial-inspector-heading"><span>ELEMENT PROPERTIES</span><strong>숲 배치 속성</strong><small>지도에서 선택한 길 또는 입출구 한 개의 속성을 표시합니다.</small></header>`);
  const forestStatusHint = forestEditor?.querySelector("footer small");
  if (forestStatusHint) forestStatusHint.textContent = "빈 공간 드래그: 화면 이동 · 휠: 확대·축소 · 요소 드래그: 위치 이동";
  const alignToolPanel = (toolbar, toolSelector) => {
    if (!toolbar || toolbar.querySelector(":scope > .spatial-tool-rail")) return;
    const rail = document.createElement("div"); rail.className = "spatial-tool-rail";
    const options = document.createElement("div"); options.className = "spatial-tool-options";
    [...toolbar.children].forEach((child) => (child.matches(toolSelector) ? rail : options).append(child));
    toolbar.classList.add("world-aligned-toolbar"); toolbar.append(rail, options);
  };
  alignToolPanel(caveToolbar, "[data-cave-preview-tool]");
  alignToolPanel(forestToolbar, "[data-forest-tool]");
  const forestPathContext = forestToolbar?.querySelector('[data-tool-context="path"]');
  const addForestPath = forestToolbar?.querySelector("[data-add-forest-path]");
  if (forestPathContext && addForestPath && addForestPath.parentElement !== forestPathContext) forestPathContext.append(addForestPath);
  caveEditor?.setAttribute("data-active-tool", state.cavePreview.tool || "select");
  forestEditor?.setAttribute("data-active-tool", state.forestPreview.tool || "select");
  caveToolbar?.querySelectorAll("[data-cave-preview-tool]").forEach((button) => {
    const active = button.dataset.cavePreviewTool === (state.cavePreview.tool || "select");
    button.classList.toggle("is-active", active); button.setAttribute("aria-pressed", String(active));
  });
  forestToolbar?.querySelectorAll("[data-forest-tool]").forEach((button) => {
    const active = button.dataset.forestTool === (state.forestPreview.tool || "select");
    button.classList.toggle("is-active", active); button.setAttribute("aria-pressed", String(active));
  });
}
prepareUnifiedSpatialEditors();
$$('[data-cave-preview-tool]').forEach((button) => button.addEventListener("click", () => setCavePreviewTool(state.cavePreview.tool === button.dataset.cavePreviewTool ? "select" : button.dataset.cavePreviewTool)));
$("[data-delete-selected-cave-anchor]").addEventListener("click", deleteSelectedCaveAnchor);
$("[data-delete-selected-cave-entrance]").addEventListener("click", deleteSelectedCaveEntrance);
$("[data-delete-selected-cave-path]").addEventListener("click", deleteSelectedCavePath);
window.addEventListener("keydown", (event) => { if (event.key === "Escape" && state.cavePreview.tool !== "select") setCavePreviewTool("select"); });
$("#validate-forest").addEventListener("click", () => validateDocument("forests"));
$("#save-forest").addEventListener("click", () => saveDocument("forests"));
$("#delete-forest").addEventListener("click", () => deleteManagedDocument("forests"));
$("#forest-form").addEventListener("input", (event) => { if (handleForestStairSetting(event) || event.target.closest(".forest-item-list")) return; updateForestFromForm(); renderForestEnvironmentPreset(); renderForestPreview(); });
$("#forest-form").addEventListener("change", (event) => { if (handleForestStairSetting(event) || event.target.closest(".forest-item-list")) return; updateForestFromForm(); renderForestEnvironmentPreset(); renderForestPreview(); });
$$("[data-edit-encounter-pokemon]").forEach((button) => button.addEventListener("click", () => openEncounterPokemonDialog(button.dataset.editEncounterPokemon)));
$("#encounter-inherit-biome").addEventListener("change", (event) => { const settings = encounterSettings(); if (!settings) return; settings.inherit_biome = event.target.checked; renderEncounterPokemonDialog(); });
$("#encounter-biome-pokemon-search").addEventListener("input", (event) => { state.encounterPokemonQuery = event.target.value; renderEncounterPokemonDialog(); });
$("#encounter-biome-pokemon-list").addEventListener("click", (event) => { const levelButton = event.target.closest("[data-encounter-pokemon-level]"); if (levelButton) { state.encounterPokemonLevelSpecies = levelButton.dataset.encounterPokemonLevel; renderEncounterPokemonDialog(); return; } const button = event.target.closest("[data-encounter-biome-species]"); const settings = encounterSettings(); if (!button || !settings) return; const excluded = new Set(settings.excluded_species); if (excluded.has(button.dataset.encounterBiomeSpecies)) excluded.delete(button.dataset.encounterBiomeSpecies); else excluded.add(button.dataset.encounterBiomeSpecies); settings.excluded_species = [...excluded].sort(); renderEncounterPokemonDialog(); });
$("#encounter-pokemon-picker-search").addEventListener("input", (event) => { state.encounterPokemonPicker.query = event.target.value.trim(); renderEncounterPokemonPicker(); });
for (const [selector, field] of [
  ["#encounter-pokemon-picker-generation", "generation"], ["#encounter-pokemon-picker-type", "type"],
  ["#encounter-pokemon-picker-habitat", "habitat"], ["#encounter-pokemon-picker-rarity", "rarity"],
  ["#encounter-pokemon-picker-special", "special"], ["#encounter-pokemon-picker-availability", "availability"],
]) $(selector).addEventListener("change", (event) => { state.encounterPokemonPicker[field] = event.target.value; renderEncounterPokemonPicker(); });
$("#encounter-pokemon-picker-list").addEventListener("click", (event) => {
  const card = event.target.closest("[data-encounter-picker-species]"); if (!card || card.disabled) return;
  const species = card.dataset.encounterPickerSpecies, selected = state.encounterPokemonPicker.selected;
  if (selected.has(species)) selected.delete(species); else selected.add(species);
  renderEncounterPokemonPicker();
});
$("#encounter-pokemon-picker-select-visible").addEventListener("click", () => {
  const settings = encounterSettings(); if (!settings) return;
  const added = new Set(settings.additions.map((entry) => entry.species));
  for (const entry of filteredEncounterPokemonPickerEntries().slice(0, 120)) if (!added.has(entry.id)) state.encounterPokemonPicker.selected.add(entry.id);
  renderEncounterPokemonPicker();
});
$("#encounter-pokemon-picker-clear").addEventListener("click", () => { state.encounterPokemonPicker.selected.clear(); renderEncounterPokemonPicker(); });
$("#encounter-pokemon-picker-reset").addEventListener("click", () => {
  Object.assign(state.encounterPokemonPicker, { query: "", generation: "all", type: "all", habitat: "all", rarity: "all", special: "all", availability: "all" });
  renderEncounterPokemonPicker();
});
$("#encounter-pokemon-picker-add").addEventListener("click", addSelectedEncounterPokemon);
$("#encounter-direct-pokemon-list").addEventListener("click", (event) => { const levelButton = event.target.closest("[data-encounter-pokemon-level]"); if (levelButton) { state.encounterPokemonLevelSpecies = levelButton.dataset.encounterPokemonLevel; renderEncounterPokemonDialog(); return; } const button = event.target.closest("[data-remove-encounter-pokemon]"), settings = encounterSettings(); if (!button || !settings) return; const [removed] = settings.additions.splice(Number(button.dataset.removeEncounterPokemon), 1); if (removed) settings.level_overrides = settings.level_overrides.filter((entry) => entry.species !== removed.species); renderEncounterPokemonDialog(); });
$("#encounter-pokemon-level-apply").addEventListener("click", () => applyPokemonLevelOverride("encounter"));
$("#encounter-pokemon-level-reset").addEventListener("click", () => resetPokemonLevelOverride("encounter"));
$("#encounter-pokemon-level-close").addEventListener("click", () => { state.encounterPokemonLevelSpecies = null; renderEncounterPokemonDialog(); });
$("#encounter-pokemon-dialog").addEventListener("close", () => { const target = state.encounterPokemonTarget; if (target) renderEncounterSummary(target); state.encounterPokemonTarget = null; });
$("#close-encounter-pokemon").addEventListener("click", () => $("#encounter-pokemon-dialog").close());
$("#forest-form").addEventListener("click", handleForestEditorClick);
$("#forest-path-list").addEventListener("input", handleForestListInput);
$("#forest-path-list").addEventListener("change", handleForestListInput);
$("#forest-entrance-list").addEventListener("input", handleForestListInput);
$("#forest-entrance-list").addEventListener("change", handleForestListInput);
$("#forest-height-list").addEventListener("input", handleForestListInput);
$("#forest-height-list").addEventListener("change", handleForestListInput);
$("#forest-layout-canvas").addEventListener("pointerdown", (event) => {
  if (!state.forest) return; const canvas = event.currentTarget, rect = canvas.getBoundingClientRect(), x = (event.clientX - rect.left) * canvas.width / rect.width, y = (event.clientY - rect.top) * canvas.height / rect.height;
  if (event.button === 1) { event.preventDefault(); state.forestPreview.drag = { target: { kind: "pan" }, lastClientX: event.clientX, lastClientY: event.clientY, suppressClick: true }; canvas.setPointerCapture(event.pointerId); canvas.classList.add("is-dragging"); return; }
  if (state.forestPreview.tool.startsWith("height-")) { const point = snapForestTilePoint(forestCanvasTransform().world(event.clientX, event.clientY)), painted = new Set(); state.forestPreview.brushHover = point; adjustForestTileHeight(point, state.forestPreview.tool, painted); state.forestPreview.drag = { target: { kind: "height" }, last: `${point.x},${point.z}`, painted, suppressClick: true }; canvas.setPointerCapture(event.pointerId); renderForestEditors(); renderForestPreview(); return; }
  if (state.forestPreview.tool === "stairs") { placeForestHeightTransition(snapForestTilePoint(forestCanvasTransform().world(event.clientX, event.clientY))); state.forestPreview.suppressClick = true; setTimeout(() => { state.forestPreview.suppressClick = false; }, 0); return; }
  const targets = state.forestPreview.hitTargets.map((item) => ({ ...item, distance: Math.hypot(item.x - x, item.y - y) }));
  const entranceTarget = targets.filter((item) => item.kind === "entrance").sort((a,b) => a.distance - b.distance)[0];
  const target = entranceTarget?.distance <= 18 ? entranceTarget : targets.sort((a,b) => a.distance - b.distance)[0];
  if (target?.distance <= 14) { state.forestPreview.drag = { target, suppressClick: true }; if (target.kind === "path") { state.forestPreview.selectedPath = target.pathIndex; state.forestPreview.selectedAnchor = { pathIndex: target.pathIndex, pointIndex: target.pointIndex }; state.forestPreview.selectedEntranceIndex = null; } else if (target.kind === "entrance") { state.forestPreview.selectedPath = null; state.forestPreview.selectedAnchor = null; state.forestPreview.selectedEntranceIndex = target.entranceIndex; } canvas.setPointerCapture(event.pointerId); canvas.classList.add("is-dragging"); renderForestEditors(); renderForestPreview(); return; }
  if (state.forestPreview.tool === "select") { state.forestPreview.selectedPath = null; state.forestPreview.selectedAnchor = null; state.forestPreview.selectedEntranceIndex = null; state.forestPreview.drag = { target: { kind: "pan" }, lastClientX: event.clientX, lastClientY: event.clientY, suppressClick: true }; canvas.setPointerCapture(event.pointerId); canvas.classList.add("is-dragging"); renderForestEditors(); renderForestPreview(); return; }
});
$("#forest-layout-canvas").addEventListener("pointermove", (event) => { if (!state.forest) return; const drag = state.forestPreview.drag; if (!drag) { if (state.forestPreview.tool.startsWith("height-")) { const point = snapForestTilePoint(forestCanvasTransform().world(event.clientX, event.clientY)), previous = state.forestPreview.brushHover; if (!previous || previous.x !== point.x || previous.z !== point.z) { state.forestPreview.brushHover = point; renderForestPreview(); } } return; } if (drag.target.kind === "pan") { const canvas = event.currentTarget, rect = canvas.getBoundingClientRect(); state.forestPreview.panX += (event.clientX - drag.lastClientX) * canvas.width / rect.width; state.forestPreview.panY += (event.clientY - drag.lastClientY) * canvas.height / rect.height; drag.lastClientX = event.clientX; drag.lastClientY = event.clientY; renderForestPreview(); return; } const point = forestCanvasTransform().world(event.clientX, event.clientY); if (drag.target.kind === "path") state.forest.paths[drag.target.pathIndex].points[drag.target.pointIndex] = point; else if (drag.target.kind === "entrance") state.forest.entrances[drag.target.entranceIndex].position = point; else if (drag.target.kind === "height") { const tilePoint = snapForestTilePoint(point); state.forestPreview.brushHover = tilePoint; if (drag.last !== `${tilePoint.x},${tilePoint.z}`) { adjustForestTileHeight(tilePoint, state.forestPreview.tool, drag.painted); drag.last = `${tilePoint.x},${tilePoint.z}`; } } renderForestPreview(); });
for (const eventName of ["pointerup", "pointercancel"]) $("#forest-layout-canvas").addEventListener(eventName, (event) => { if (!state.forestPreview.drag) return; state.forestPreview.suppressClick = Boolean(state.forestPreview.drag.suppressClick); state.forestPreview.drag = null; event.currentTarget.classList.remove("is-dragging"); renderForestEditors(); renderForestPreview(); setTimeout(() => { state.forestPreview.suppressClick = false; }, 0); });
$("#forest-layout-canvas").addEventListener("pointerleave", () => { if (!state.forestPreview.drag && state.forestPreview.brushHover) { state.forestPreview.brushHover = null; renderForestPreview(); } });
$("#forest-layout-canvas").addEventListener("click", (event) => { if (state.forestPreview.suppressClick || state.forestPreview.tool !== "path") return; const route = state.forest?.paths[state.forestPreview.selectedPath]; if (!route) { toast("먼저 새 길을 추가하거나 미로 길을 생성하세요."); return; } const point = forestCanvasTransform().world(event.clientX, event.clientY); const last = route.points.at(-1); if (!last || last.x !== point.x || last.z !== point.z) route.points.push(point); renderForestEditors(); renderForestPreview(); });
$("#forest-layout-canvas").addEventListener("wheel", (event) => { if (!state.forest) return; event.preventDefault(); zoomForestPreview(state.forestPreview.zoom * (event.deltaY < 0 ? 1.12 : .89), event.clientX, event.clientY); }, { passive: false });
$("#forest-height-brush-radius").addEventListener("input", (event) => { const radius = Math.max(0, Math.min(5, Math.round(Number(event.target.value) || 0))); state.forestPreview.heightBrushRadius = radius; $("#forest-height-brush-size").textContent = `${radius * 2 + 1}×${radius * 2 + 1}`; renderForestPreview(); });
$("#forest-height-brush-target").addEventListener("input", (event) => { const targetHeight = Math.max(1, Math.min(16, Math.round(Number(event.target.value) || 1))); state.forestPreview.heightBrushTarget = targetHeight; event.target.value = String(targetHeight); renderForestHeightTarget(); renderForestPreview(); });
$("#open-forest-maze-dialog").addEventListener("click", openForestMazeDialog);
$("#regenerate-forest-maze").addEventListener("click", applyForestMazeDialog);
$("#delete-settlement").addEventListener("click", deleteSettlement);
$("#save-world-layout").addEventListener("click", saveWorldLayout);
$("#delete-world-layout").addEventListener("click", deleteWorldLayout);
$("#add-generation").addEventListener("click", addGeneration);
$("#tile-inspector-form").addEventListener("change", (event) => {
  if (["objectType", "objectGateMode", "objectBuildingEnabled", "objectSurroundingType"].includes(event.target.name)) updateGateOptionVisibility();
  handleTileInspectorChange(event);
});
$("#entrance-inspector-form").addEventListener("change", handleEntranceInspectorChange);
$("#delete-selected-entrance").addEventListener("click", deleteSelectedEntrance);
$("#route-inspector-form").addEventListener("input", handleRouteInspectorInput);
$("#route-inspector-form").elements.displayName.addEventListener("change", handleRouteInspectorInput);
$("#edit-route-pokemon").addEventListener("click", openRoutePokemonDialog);
$("#delete-selected-route").addEventListener("click", () => {
  const route = selectedRoute();
  if (route) removeRouteConnection(route.id);
});
$("#route-inherit-biome").addEventListener("change", (event) => {
  const route = selectedRoute(); if (!route) return;
  ensureRoutePokemonSettings(route).inherit_biome = event.target.checked;
  markWorldDirty(); renderRouteInspector(route); renderRoutePokemonDialog(); renderWorldPokemonPanel();
});
$("#route-biome-pokemon-search").addEventListener("input", (event) => {
  state.routePokemonQuery = event.target.value.trim(); renderRoutePokemonDialog();
});
$("#route-biome-pokemon-list").addEventListener("click", (event) => {
  const levelButton = event.target.closest("[data-route-pokemon-level]");
  if (levelButton) { state.routePokemonLevelSpecies = levelButton.dataset.routePokemonLevel; renderRoutePokemonDialog(); return; }
  const button = event.target.closest("[data-route-biome-species]");
  if (button) toggleRouteBiomePokemon(button.dataset.routeBiomeSpecies);
});
$("#route-pokemon-picker-search").addEventListener("input", (event) => { state.routePokemonPicker.query = event.target.value.trim(); renderRoutePokemonPicker(); });
for (const [selector, field] of [
  ["#route-pokemon-picker-generation", "generation"], ["#route-pokemon-picker-type", "type"],
  ["#route-pokemon-picker-habitat", "habitat"], ["#route-pokemon-picker-rarity", "rarity"],
  ["#route-pokemon-picker-special", "special"], ["#route-pokemon-picker-availability", "availability"],
]) $(selector).addEventListener("change", (event) => { state.routePokemonPicker[field] = event.target.value; renderRoutePokemonPicker(); });
$("#route-pokemon-picker-list").addEventListener("click", (event) => {
  const card = event.target.closest("[data-route-picker-species]"); if (!card || card.disabled) return;
  const species = card.dataset.routePickerSpecies; const selected = state.routePokemonPicker.selected;
  if (selected.has(species)) selected.delete(species); else selected.add(species);
  renderRoutePokemonPicker();
});
$("#route-pokemon-picker-select-visible").addEventListener("click", () => {
  const route = selectedRoute(); if (!route) return;
  const added = new Set(ensureRoutePokemonSettings(route).additions.map((entry) => entry.species));
  for (const entry of filteredRoutePokemonPickerEntries().slice(0, 120)) if (!added.has(entry.id)) state.routePokemonPicker.selected.add(entry.id);
  renderRoutePokemonPicker();
});
$("#route-pokemon-picker-clear").addEventListener("click", () => { state.routePokemonPicker.selected.clear(); renderRoutePokemonPicker(); });
$("#route-pokemon-picker-reset").addEventListener("click", () => {
  Object.assign(state.routePokemonPicker, { query: "", generation: "all", type: "all", habitat: "all", rarity: "all", special: "all", availability: "all" });
  renderRoutePokemonPicker();
});
$("#route-pokemon-picker-add").addEventListener("click", addSelectedRoutePokemon);
$("#route-direct-pokemon-list").addEventListener("click", (event) => {
  const levelButton = event.target.closest("[data-route-pokemon-level]");
  if (levelButton) { state.routePokemonLevelSpecies = levelButton.dataset.routePokemonLevel; renderRoutePokemonDialog(); return; }
  const button = event.target.closest("[data-remove-route-pokemon]");
  if (button) removeRoutePokemon(Number(button.dataset.removeRoutePokemon));
});
$("#route-pokemon-level-apply").addEventListener("click", () => applyPokemonLevelOverride("route"));
$("#route-pokemon-level-reset").addEventListener("click", () => resetPokemonLevelOverride("route"));
$("#route-pokemon-level-close").addEventListener("click", () => { state.routePokemonLevelSpecies = null; renderRoutePokemonDialog(); });
$("#clear-tile").addEventListener("click", clearSelectedTile);
$$('[data-pokemon-map-tab]').forEach((button) => button.addEventListener("click", () => { state.pokemonMapTab = button.dataset.pokemonMapTab; renderWorldPokemonPanel(); }));
$("#pokemon-map-search").addEventListener("input", (event) => { state.pokemonMapQuery = event.target.value.trim(); renderWorldPokemonPanel(); });
$("#finish-route").addEventListener("click", finishRouteConnection);
$("#route-manager-list").addEventListener("change", (event) => {
  const routeId = event.target.dataset.routeMusic;
  if (!routeId) return;
  const route = state.worldLayout?.connections?.find((entry) => entry.id === routeId);
  if (!route) return;
  if (event.target.value) route.music_track = event.target.value;
  else delete route.music_track;
  markWorldDirty(); renderTileInspector();
});
$("#cancel-route").addEventListener("click", cancelRouteConnection);
$("#undo-route-anchor").addEventListener("click", undoRouteAnchor);
$$('[data-map-tool]').forEach((button) => button.addEventListener("click", () => setActiveMapTool(button.dataset.mapTool)));
$("#cave-tool-cave").addEventListener("change", refreshCaveToolEntrances);
$("#forest-tool-forest").addEventListener("change", refreshForestToolEntrances);
$("#world-entrance-kind").addEventListener("change", (event) => {
  $$('[data-world-entrance-panel]').forEach((panel) => panel.hidden = panel.dataset.worldEntrancePanel !== event.target.value);
});
for (const [inputId, outputId] of [["biome-brush-radius", "biome-brush-radius-value"], ["empty-terrain-brush-radius", "empty-terrain-brush-radius-value"], ["climate-brush-radius", "climate-brush-radius-value"], ["level-brush-radius", "level-brush-radius-value"], ["eraser-radius", "eraser-radius-value"]]) {
  $(`#${inputId}`).addEventListener("input", (event) => { $(`#${outputId}`).textContent = event.target.value; renderHexMap(); });
}
function setLevelBrushValue(value) {
  const level = Math.max(1, Math.min(100, Math.round(Number(value) || 1)));
  $("#level-brush-average").value = level; $("#level-brush-average-range").value = level; $("#level-brush-average-value").textContent = level;
}
$("#level-brush-average").addEventListener("input", (event) => setLevelBrushValue(event.target.value));
$("#level-brush-average-range").addEventListener("input", (event) => setLevelBrushValue(event.target.value));
$$('[data-level-quick]').forEach((button) => button.addEventListener("click", () => setLevelBrushValue(button.dataset.levelQuick)));
$("#level-overlay-toggle").addEventListener("change", (event) => { state.levelOverlayVisible = event.target.checked; renderHexMap(); });
$("#tile-radius-blocks").addEventListener("change", () => { state.worldLayout.grid.tile_radius_blocks = Number($("#tile-radius-blocks").value || 64); markWorldDirty(); });
$("#zoom-in").addEventListener("click", () => zoomWorldMap(state.mapZoom + .1));
$("#zoom-out").addEventListener("click", () => zoomWorldMap(state.mapZoom - .1));
$("#fit-map").addEventListener("click", () => { fitMapToContent(); renderHexMap(); });
$("#world-hex-map").addEventListener("wheel", handleWorldMapWheel, { passive: false });
$("#world-hex-map").addEventListener("pointerdown", beginMapPan);
$("#world-hex-map").addEventListener("pointermove", moveMapPan);
$("#world-hex-map").addEventListener("pointerleave", () => { if (!state.paintStroke) clearBrushPreview(); });
$("#world-hex-map").addEventListener("pointerup", finishSettlementDrag);
$("#world-hex-map").addEventListener("pointerup", finishEntranceDrag);
$("#world-hex-map").addEventListener("pointerup", finishMapPan);
$("#world-hex-map").addEventListener("pointercancel", (event) => { state.draggedSettlement = null; state.entranceDrag = null; state.mapPan = null; state.paintStroke = null; state.routeAnchorDrag = null; state.brushPreview = null; $("#world-hex-map").classList.remove("is-dragging", "is-panning"); finishMapPan(event); renderHexMap(); });
window.addEventListener("keydown", (event) => {
  if (event.code === "Space" && !/INPUT|SELECT|TEXTAREA/.test(event.target.tagName)) { state.spacePanActive = true; state.brushPreview = null; $("#world-hex-map").classList.add("is-space-panning"); renderHexMap(); event.preventDefault(); return; }
  if (/INPUT|SELECT|TEXTAREA/.test(event.target.tagName) || event.ctrlKey || event.metaKey || event.altKey) return;
  const tool = ({ v: "select", b: "biome", t: "terrain", c: "climate", l: "level", r: "route", s: "settlement", d: "entrance", f: "entrance", o: "object", e: "eraser" })[event.key.toLowerCase()];
  if (tool) { setActiveMapTool(tool); event.preventDefault(); }
});
window.addEventListener("keyup", (event) => { if (event.code === "Space") { state.spacePanActive = false; $("#world-hex-map").classList.remove("is-space-panning"); } });
window.addEventListener("resize", () => { resizeWorldMapWorkspace(); renderStructureModel(); renderCaveLayoutPreview(); renderForestPreview(); });
$("#settlement-form").addEventListener("input", (event) => { applySpecialBuildingPreset(event); keepHousePaletteGroupSelected(event); updateFacilityFormState(); updateSettlementFromForm(); });
$("#custom-town-layout").addEventListener("click", (event) => {
  const tool = event.target.closest("[data-custom-town-tool]")?.dataset.customTownTool;
  if (tool) { state.customTownTool = tool; $$("[data-custom-town-tool]").forEach((button) => button.classList.toggle("is-active", button.dataset.customTownTool === tool)); return; }
  if (event.target.closest("[data-custom-town-reset]")) {
    state.settlement.town_footprint_cells = [];
    state.settlement.town_road_exits = [];
    ensureCustomTownLayout(); renderCustomTownLayout(); renderVillageGenerationTest(); return;
  }
  const value = event.target.closest("[data-custom-town-cell]")?.dataset.customTownCell;
  if (value) { const [q, r] = value.split(",").map(Number); editCustomTownCell(q, r); }
});
$("#regenerate-village-preview").addEventListener("click", () => {
  const input = $("#settlement-form").elements.villagePreviewSeed;
  input.value = String(1 + Math.floor(Math.random() * 999999998));
  renderVillageGenerationTest();
});
$$('[data-preview-zone]').forEach((button) => button.addEventListener("click", () => previewSettlementZone(Number(button.dataset.previewZone))));
$("#add-trainer-slot").addEventListener("click", addTrainerSlot);
$("#trainer-slot-list").addEventListener("input", updateTrainerSlot);
$("#trainer-slot-list").addEventListener("click", (event) => {
  const button = event.target.closest("[data-remove-trainer-slot]");
  if (button) removeTrainerSlot(Number(button.dataset.removeTrainerSlot));
});
$("#apply-settlement-json").addEventListener("click", () => { const document = parseEditor("#settlement-json"); if (document) { state.settlement = document; renderSettlement(); toast("JSON을 기본 설정에 반영했습니다."); } });
$$('[data-create]').forEach((button) => button.addEventListener("click", () => openCreateDialog(button.dataset.create)));
$("#create-form").addEventListener("submit", createDocument);
$("#create-close").addEventListener("click", () => $("#create-dialog").close());
$("#create-cancel").addEventListener("click", () => $("#create-dialog").close());
$("#choice-close").addEventListener("click", closeChoiceDialog);
$("#choice-dialog").addEventListener("click", (event) => { if (event.target === event.currentTarget) closeChoiceDialog(); });
$$('[data-biome-tab]').forEach((button) => button.addEventListener("click", () => {
  $$('[data-biome-tab]').forEach((entry) => entry.classList.toggle("is-active", entry === button));
  $$('[data-biome-panel]').forEach((panel) => panel.classList.toggle("is-active", panel.dataset.biomePanel === button.dataset.biomeTab));
}));
$("#biome-profile-form").elements.profileId.addEventListener("change", (event) => { state.selectedBiomeProfile = event.target.value; renderBiomeManager(); });
$("#preview-biome-profile").addEventListener("click", previewBiomeProfile);
$("#save-biome-catalog").addEventListener("click", saveBiomeCatalog);
$("#add-biome-profile").addEventListener("click", addBiomeProfile);
$("#delete-biome-profile").addEventListener("click", deleteBiomeProfile);
$("#biome-set-select").addEventListener("change", renderBiomeSet);
$("#test-biome-set").addEventListener("click", testBiomeSet);
$("#habitat-pokemon-search").addEventListener("input", renderHabitatPokemon);
$("#habitat-generation-filter").addEventListener("change", renderHabitatPokemon);
$("#habitat-filter").addEventListener("change", renderHabitatPokemon);
$("#economy").addEventListener("click", handleEconomyClick);
$("#economy").addEventListener("change", handleEconomyInlineChange);
$("#economy").addEventListener("input", handleEconomyInput);
$("#save-economy").addEventListener("click", saveEconomy);
$("#economy-form").addEventListener("submit", submitEconomyEditor);
$("#economy-dialog").addEventListener("click", handleEconomyDialogClick);
$("#economy-dialog").addEventListener("change", handleEconomyDialogChange);
$("#economy-dialog-close").addEventListener("click", () => $("#economy-dialog").close());
$("#economy-dialog-cancel").addEventListener("click", () => $("#economy-dialog").close());
$("#settlement-form").elements.commercialFacility.addEventListener("change", renderSettlementVendorUnits);
$("#economy-catalog-search").addEventListener("input", (event) => updateEconomyView("catalogSearch", event.target.value));
$("#economy-vendor-search").addEventListener("input", (event) => updateEconomyView("vendorSearch", event.target.value));
$("#economy-pokemon-search").addEventListener("input", (event) => updateEconomyView("pokemonSearch", event.target.value));
$("#economy-pokemon-type").addEventListener("change", (event) => updateEconomyView("pokemonType", event.target.value));
$("#economy-pokemon-generation").addEventListener("change", (event) => updateEconomyView("pokemonGeneration", event.target.value));
$("#economy-pokemon-limit").addEventListener("change", (event) => updateEconomyView("pokemonLimit", Number(event.target.value)));

loadActiveProject().then(refreshAll).catch((error) => {
  hideProjectLoading();
  $("#server-dot").classList.remove("online");
  $("#server-label").textContent = "연결 실패";
  toast(error.message);
});
