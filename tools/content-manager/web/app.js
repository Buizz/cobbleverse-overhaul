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
  trainers: [], battles: [], settlements: [], caves: [], trainer: null, battlePreset: null, settlement: null, cave: null,
  trainerPath: "", battlePath: "", settlementPath: "", cavePath: "", buildCommands: [], trainerClasses: [], trainerRoster: { organizations: [], league_characters: [] },
  trainerReferences: { sources: [], entries: [] },
  selectedPokemonIndex: 0, editorCatalog: null, choice: null,
  biomeCatalog: { profiles: [], sets: [] }, pokemonHabitats: [], selectedBiomeProfile: null,
  worldLayout: null, worldGenerations: [1], selectedGeneration: 1,
  worldPokemonMap: { locations: [], available_pokemon: [], unavailable_pokemon: [], summary: {} },
  pokemonMapTab: "selected", pokemonMapQuery: "",
  selectedHex: null, mapRadius: 6, mapZoom: 1, mapCenter: { x: 490, y: 330 }, mapViewInitialized: false,
  mapPan: null, suppressMapClick: false, draggedSettlement: null, routeDraft: null, worldDirty: false,
  activeMapTool: "select", paintStroke: null, brushPreview: null, spacePanActive: false, selectedRouteId: null, routeAnchorDrag: null,
  structureSizes: {}, villageView: { zoom: 1, panX: 0, panY: 0, drag: null },
  structureViewer: { query: "", selected: "", model: null, yaw: -.75, pitch: structureViewPitch.default, zoom: 1, drag: null, requestId: 0 },
  customTownTool: "cell"
};
const lazyDataLoaded = { trainers: false, biomes: false, structures: false };
const lazyDataPromises = { trainers: null, biomes: null, structures: null };
const biomeChoices = {
  habitat: [["plains", "평원"], ["forest", "숲"], ["arid", "건조지"], ["mountain", "산악"], ["cave", "동굴"], ["wetland", "습지"], ["freshwater", "담수"], ["ocean", "해양"], ["snow", "설원"], ["volcanic", "화산"], ["urban", "도시"], ["special", "특수"]],
  temperature: [["any", "무관"], ["cold", "한랭"], ["cool", "서늘"], ["temperate", "온대"], ["hot", "고온"]],
  humidity: [["any", "무관"], ["dry", "건조"], ["normal", "보통"], ["humid", "다습"], ["aquatic", "수중"]],
  weather: [["any", "무관"], ["clear", "맑음"], ["rain", "비"], ["thunder", "뇌우"], ["snow", "눈"], ["fog", "안개"]],
  time: [["any", "무관"], ["day", "낮"], ["night", "밤"], ["twilight", "황혼"]]
};
const settlementFacilityCatalog = [
  { id: "laboratory", label: "연구소", note: "스타팅 포켓몬 지급", width: 32, depth: 32, height: 14, color: "#4cc9f0" },
  { id: "fossil_laboratory", label: "화석연구소", note: "화석 포켓몬 복원", width: 32, depth: 32, height: 14, color: "#c9a66b" },
  { id: "daycare", label: "키우미집", note: "건물과 야외 목장", width: 32, depth: 32, height: 10, color: "#80b918" },
  { id: "tm_workshop", label: "기술머신 조합소", note: "기술머신 제작 시설", width: 32, depth: 16, height: 10, color: "#f48c06" },
  { id: "hotel", label: "호텔", note: "중대형 숙박 시설", width: 32, depth: 32, height: 20, color: "#e85d75" },
  { id: "casino", label: "카지노", note: "CasinoCraft 게임 시설 예정", width: 48, depth: 48, height: 20, color: "#d4a017" },
  { id: "battle_tower", label: "배틀타워", note: "전투 랜드마크", width: 48, depth: 48, height: 32, color: "#9d4edd" },
  { id: "radio_tower", label: "라디오 타워", note: "방송국과 송신탑", width: 48, depth: 48, height: 32, color: "#4361ee" },
  { id: "train_station", label: "기차역", note: "역사와 선로 예약부지", width: 48, depth: 64, height: 14, color: "#495057" }
];
const legacyGymFacilityIds = new Set(["gym_site", "gym_lot"]);
const houseBaseCatalog = [
  { id: "one_story", label: "1층 주택", width: 16, depth: 16, height: 13 },
  { id: "two_story", label: "2층 주택", width: 16, depth: 16, height: 18 },
  { id: "five_story", label: "5층 고층주택", width: 16, depth: 16, height: 33 }
];
const houseRoofCatalog = [
  { id: "gable", label: "박공지붕" },
  { id: "hip", label: "모임지붕" },
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
  const response = await fetch(url, {
    ...options,
    headers: { "Content-Type": "application/json", ...(options.headers || {}) }
  });
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

function showIssues(target, payload) {
  const element = $(target);
  const issues = payload?.issues || [];
  if (!issues.length) {
    element.className = "issues empty";
    element.textContent = payload?.valid === false ? "검증에 실패했습니다." : "오류가 없습니다.";
    return;
  }
  element.className = "issues";
  element.innerHTML = issues.map((issue) => `
    <div class="issue ${issue.level === "warning" ? "warning" : ""}">
      <span class="issue-level">${issue.level === "warning" ? "경고" : "오류"}</span>
      <span><b>${escapeHtml(issue.message)}</b><br><span class="issue-path">${escapeHtml(issue.path || "$ ")}</span></span>
    </div>`).join("");
}

function escapeHtml(value) {
  return String(value).replace(/[&<>'"]/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;"
  })[character]);
}

function switchPage(section) {
  $$(".nav-item").forEach((button) => button.classList.toggle("is-active", button.dataset.section === section));
  $$(".page").forEach((page) => page.classList.toggle("is-active", page.id === section));
  const titles = { dashboard: "프로젝트 현황", trainers: "NPC 상호작용", battles: "배틀 프리셋", worlds: "세대별 월드맵", caves: "동굴 관리", settlements: "마을 프리셋", structures: "NBT 건물 3D", biomes: "바이옴 관리", builds: "빌드 및 검사" };
  $("#page-title").textContent = titles[section];
  if (section === "worlds") requestAnimationFrame(resizeWorldMapWorkspace);
  if (section === "structures") requestAnimationFrame(renderStructureModel);
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
  renderBuildCommands();
}

async function loadLists() {
  const [trainers, battles, settlements, caves, worldLayouts, worldLayout, worldPokemonMap] = await Promise.all([
    request("/api/trainers"), request("/api/battles"), request("/api/settlements"), request("/api/caves"),
    request("/api/world-layouts"), request(`/api/world-layout?generation=${state.selectedGeneration}`),
    request(`/api/world-pokemon-map?generation=${state.selectedGeneration}`)
  ]);
  state.trainers = trainers.data.items || [];
  state.battles = battles.data.items || [];
  state.settlements = settlements.data.items || [];
  state.caves = caves.data.items || [];
  state.worldGenerations = worldLayouts.ok ? worldLayouts.data.generations || [1] : [1];
  state.worldLayout = worldLayout.ok ? worldLayout.data : null;
  if (worldPokemonMap.ok) state.worldPokemonMap = worldPokemonMap.data;
  renderList("trainers");
  renderList("battles");
  renderList("settlements");
  renderList("caves");
  renderWorldLayout();
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
  $("#nbt-structure-count").textContent = "불러오는 중";
  $("#nbt-structure-list").innerHTML = '<div class="issues empty">NBT 목록을 필요한 시점에 불러오고 있습니다.</div>';
  lazyDataPromises.structures = (async () => {
    const result = await request("/api/structure-sizes");
    if (!result.ok) throw new Error(result.data.error || "NBT 구조물 목록을 불러오지 못했습니다.");
    state.structureSizes = result.data.structures || {};
    lazyDataLoaded.structures = true;
    renderStructureBrowser();
    if (state.settlement) renderVillageGenerationTest();
  })();
  try { await lazyDataPromises.structures; }
  catch (error) {
    $("#nbt-structure-count").textContent = "오류";
    $("#nbt-structure-list").innerHTML = `<div class="issues">${escapeHtml(error.message)}</div>`;
    throw error;
  } finally { lazyDataPromises.structures = null; }
}

function loadSectionData(section, force = false) {
  if (section === "trainers" || section === "battles") return loadTrainerData(force);
  if (section === "biomes") return loadBiomeData(force);
  if (section === "structures") return loadStructureData(force);
  if (section === "settlements") return Promise.all([loadBiomeData(force), loadStructureData(force)]);
  return Promise.resolve();
}

function settlementSummary(settlementId) {
  return state.settlements.find((item) => item.id === settlementId);
}
function caveSummary(caveId) { return state.caves.find((item) => item.id === caveId); }
function caveEntranceAt(q, r) { return (state.worldLayout?.cave_entrances || []).find((entry) => entry.anchor?.q === q && entry.anchor?.r === r); }
function normalizeTownCellCount(value) { const count = Number(value); return count === 3 || count === 5 || count === 7 || count === 19 ? count : 1; }
function settlementPresetRadius(settlementId) { return normalizeTownCellCount(settlementSummary(settlementId)?.town_radius_cells); }
function normalizeTownFootprintShape(value) { return ["triangle_up", "triangle_down", "line_q", "line_r", "line_s", "five_up", "five_down", "custom"].includes(value) ? value : "line_q"; }
function settlementPresetFootprintShape(settlementId) { return normalizeTownFootprintShape(settlementSummary(settlementId)?.town_footprint_shape); }
function normalizedAxialCells(value) { const seen = new Set(); return (Array.isArray(value) ? value : []).filter((cell) => Number.isInteger(cell?.q) && Number.isInteger(cell?.r)).map((cell) => ({ q: cell.q, r: cell.r })).filter((cell) => { const key = `${cell.q},${cell.r}`; if (seen.has(key)) return false; seen.add(key); return true; }); }
function settlementPresetFootprintCells(settlementId) { return normalizedAxialCells(settlementSummary(settlementId)?.town_footprint_cells); }
function settlementPresetRoadExits(settlementId) { return normalizedAxialCells(settlementSummary(settlementId)?.town_road_exits); }
function worldSettlementCellCount(node) { return normalizeTownCellCount(node?.town_radius_cells ?? settlementPresetRadius(node?.settlement)); }
function worldSettlementFootprintShape(node) { return normalizeTownFootprintShape(node?.town_footprint_shape ?? settlementPresetFootprintShape(node?.settlement)); }
function worldSettlementFootprintCells(node) { return normalizedAxialCells(node?.town_footprint_cells?.length ? node.town_footprint_cells : settlementPresetFootprintCells(node?.settlement)); }

function worldSettlementOptions(selected) {
  const token = `generation_${state.selectedGeneration}/`;
  const candidates = state.settlements.filter((item) => item.path?.replaceAll("\\", "/").includes(token));
  return '<option value="">마을 선택</option>' + candidates.map((item) => `<option value="${escapeHtml(item.id)}" ${item.id === selected ? "selected" : ""}>${escapeHtml(item.name || item.id)}</option>`).join("");
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
  layout.environment_overrides ||= [];
  layout.empty_terrain ||= { default_type: "high_forest", tiles: [] };
  layout.empty_terrain.default_type ||= "high_forest";
  layout.empty_terrain.tiles ||= [];
  const occupiedExtent = [...layout.tiles, ...layout.empty_terrain.tiles, ...layout.settlements.map((node) => node.anchor || { q: 0, r: 0 }), ...layout.objects.map((node) => node.anchor || { q: 0, r: 0 }), ...layout.cave_entrances.map((node) => node.anchor || { q: 0, r: 0 })].reduce((largest, cell) => Math.max(largest, Math.abs(cell.q || 0), Math.abs(cell.r || 0), Math.abs((cell.q || 0) + (cell.r || 0))), 0);
  state.mapRadius = Math.max(Number(layout.grid?.map_radius_cells || state.mapRadius), Math.min(14, occupiedExtent + 1));
  renderGenerationTabs();
  renderMapToolOptions();
  $("#world-map-title").textContent = `${state.selectedGeneration}세대 월드`;
  $("#tile-radius-blocks").value = layout.grid?.tile_radius_blocks || 64;
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
  state.worldDirty = false;
  state.mapViewInitialized = false;
  renderWorldLayout();
}

function hexKey(q, r) { return `${q},${r}`; }
function mapHexSize() { return 24; }
function hexPoint(q, r) { const size = mapHexSize(); return { x: 490 + Math.sqrt(3) * size * (q + r / 2), y: 330 + size * 1.5 * r }; }
function hexPolygon(x, y, radius = mapHexSize() - 2) { return Array.from({ length: 6 }, (_, i) => { const angle = Math.PI / 180 * (60 * i - 30); return `${x + radius * Math.cos(angle)},${y + radius * Math.sin(angle)}`; }).join(" "); }
function pixelToHex(x, y) { const size = mapHexSize(); const r = (y - 330) / (size * 1.5); return roundHex((x - 490) / (Math.sqrt(3) * size) - r / 2, r); }
function mapViewBox() { const width = 980 / state.mapZoom; const height = 660 / state.mapZoom; return { x: state.mapCenter.x - width / 2, y: state.mapCenter.y - height / 2, width, height }; }
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
  const cells = [...(state.worldLayout?.tiles || []), ...(state.worldLayout?.empty_terrain?.tiles || []), ...(state.worldLayout?.settlements || []).map((node) => node.anchor), ...(state.worldLayout?.objects || []).map((node) => node.anchor), ...(state.worldLayout?.connections || []).flatMap((connection) => connectionPath(connection))].filter(Boolean);
  if (!cells.length) { state.mapCenter = { x: 490, y: 330 }; state.mapZoom = 1; state.mapViewInitialized = true; return; }
  const points = cells.map((cell) => hexPoint(cell.q, cell.r)); const padding = 130;
  const minX = Math.min(...points.map((point) => point.x)); const maxX = Math.max(...points.map((point) => point.x));
  const minY = Math.min(...points.map((point) => point.y)); const maxY = Math.max(...points.map((point) => point.y));
  state.mapCenter = { x: (minX + maxX) / 2, y: (minY + maxY) / 2 };
  state.mapZoom = Math.max(.65, Math.min(1.6, Math.min(980 / Math.max(1, maxX - minX + padding), 660 / Math.max(1, maxY - minY + padding))));
  state.mapViewInitialized = true;
}
function biomeTone(biome = "") {
  if (/ocean|river|beach/.test(biome)) return "water";
  if (/snow|ice|frozen|peak/.test(biome)) return "snow";
  if (/forest|jungle|grove/.test(biome)) return "forest";
  if (/desert|badlands|savanna/.test(biome)) return "arid";
  if (/swamp|marsh/.test(biome)) return "wetland";
  if (/hill|mountain|stone|windswept/.test(biome)) return "mountain";
  return "plains";
}
function tileAt(q, r) { return state.worldLayout?.tiles?.find((tile) => tile.q === q && tile.r === r); }
function emptyTerrainAt(q, r) { return state.worldLayout?.empty_terrain?.tiles?.find((tile) => tile.q === q && tile.r === r)?.type || state.worldLayout?.empty_terrain?.default_type || "high_forest"; }
function emptyTerrainTone(type) { return ({ high_forest: "forest", ocean: "water", desert: "arid", stone_mountain: "mountain", snow_mountain: "snow" })[type] || "forest"; }
function emptyTerrainLabel(type) { return ({ high_forest: "높은 숲", ocean: "바다", desert: "사막", stone_mountain: "돌산", snow_mountain: "눈산" })[type] || type; }
function emptyTerrainSymbol(type) { return ({ high_forest: "♣", ocean: "≈", desert: "·", stone_mountain: "▲", snow_mountain: "△" })[type] || "×"; }
function settlementAt(q, r) { return state.worldLayout?.settlements?.find((node) => node.anchor?.q === q && node.anchor?.r === r); }
function objectAt(q, r) { return state.worldLayout?.objects?.find((node) => node.anchor?.q === q && node.anchor?.r === r); }
function environmentOverrideAt(q, r) { return state.worldLayout?.environment_overrides?.find((entry) => entry.q === q && entry.r === r); }
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
function connectionPath(connection) {
  const cells = connection.cells || [];
  if (cells.length) return cells;
  const from = state.worldLayout?.settlements?.find((node) => node.settlement === connection.from)?.anchor;
  const to = state.worldLayout?.settlements?.find((node) => node.settlement === connection.to)?.anchor;
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
  const routes = routesAt(q, r);
  return routes.find((route) => route.surface_style === "water") || routes[0] || null;
}

function baseBiomeAt(q, r) {
  const tile = tileAt(q, r);
  if (tile) return tile.biome;
  const townArea = settlementFootprintAt(q, r);
  return townArea ? (townArea.town_biome || "minecraft:plains") : null;
}

const brushMapTools = new Set(["biome", "terrain", "climate", "eraser"]);
function activeBrushRadius() {
  const inputId = {
    biome: "biome-brush-radius",
    terrain: "empty-terrain-brush-radius",
    climate: "climate-brush-radius",
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

function renderHexMap() {
  const svg = $("#world-hex-map");
  const view = mapViewBox(); const cells = visibleHexCells();
  svg.setAttribute("viewBox", `${view.x} ${view.y} ${view.width} ${view.height}`);
  const tiles = cells.map(({ q, r }) => {
    const { x, y } = hexPoint(q, r); const tile = tileAt(q, r); const town = settlementAt(q, r); const townArea = settlementFootprintAt(q, r);
    const route = townArea ? null : primaryRouteAt(q, r);
    const selected = state.selectedHex?.q === q && state.selectedHex?.r === r; const environment = environmentOverrideAt(q, r);
    const emptyType = emptyTerrainAt(q, r);
    const tone = townArea ? biomeTone(townArea.town_biome || "minecraft:plains") : tile ? biomeTone(tile.biome) : emptyTerrainTone(emptyType);
    const baseLabel = town ? settlementSummary(town.settlement)?.name || "마을" : townArea ? `${settlementSummary(townArea.settlement)?.name || "마을"} 사용 범위` : tile ? tile.biome : emptyTerrainLabel(emptyType);
    const climateLabel = environment ? `, 기후 ${environment.temperature || "기본"}/${environment.humidity || "기본"}/${environment.weather || "기본"}` : "";
    const label = (route ? `${baseLabel}, 길 ${route.id}` : baseLabel) + climateLabel;
    const isEmpty = !townArea && !tile; const polygon = hexPolygon(x, y);
    return `<g class="hex-cell ${selected ? "is-selected" : ""} ${route ? "is-route-terrain" : ""} ${environment ? "has-climate-override" : ""} ${isEmpty ? `is-empty-terrain empty-type-${emptyType}` : ""} tone-${tone}" data-hex-q="${q}" data-hex-r="${r}" tabindex="0" role="button" aria-label="Q ${q}, R ${r}, ${escapeHtml(label)}"><polygon points="${polygon}"></polygon>${isEmpty ? `<path class="empty-terrain-hatch" d="M${polygon}Z"></path><text class="empty-terrain-symbol" x="${x}" y="${y + 3}">${emptyTerrainSymbol(emptyType)}</text>` : ""}${tile && !townArea ? `<circle class="biome-pin" cx="${x}" cy="${y}" r="3"></circle>` : ""}${environment ? `<path class="climate-pin" d="M${x - 7} ${y - 12}h14v5h-14z"></path>` : ""}</g>`;
  }).join("");
  const townAreas = cells.map(({ q, r }) => {
    const owner = settlementFootprintAt(q, r); if (!owner) return "";
    const { x, y } = hexPoint(q, r); const name = settlementSummary(owner.settlement)?.name || owner.settlement;
    return `<g class="hex-town-area${owner.anchor.q === q && owner.anchor.r === r ? " is-anchor" : ""}"><polygon points="${hexPolygon(x, y, mapHexSize() - 4)}"></polygon><title>${escapeHtml(name)} · 마을 크기 ${worldSettlementCellCount(owner)}칸</title></g>`;
  }).join("");
  const routes = (state.worldLayout.connections || []).map((connection) => {
    const points = connectionPath(connection).map((cell) => { const point = hexPoint(cell.q, cell.r); return `${point.x},${point.y}`; }).join(" ");
    if (!points) return "";
    const routeClass = connection.surface_style === "water" ? "water" : connection.access_requirement?.endsWith("/rock_climb") ? "climb" : "road";
    const selected = state.selectedRouteId === connection.id;
    return `<g class="hex-route-group${selected ? " is-selected" : ""}" data-select-route="${escapeHtml(connection.id)}" tabindex="0" role="button" aria-label="${escapeHtml(connection.id)} 길 선택"><polyline class="hex-route ${routeClass}" points="${points}"><title>${escapeHtml(connection.id)}</title></polyline><polyline class="hex-route-hit" points="${points}"></polyline></g>`;
  }).join("");
  const draftRoute = state.routeDraft?.cells?.length ? (() => {
    const points = state.routeDraft.cells.map((cell) => { const point = hexPoint(cell.q, cell.r); return `${point.x},${point.y}`; }).join(" ");
    return `<polyline class="hex-route draft" points="${points}"></polyline>`;
  })() : "";
  const routeAnchors = (() => {
    const draft = state.routeDraft;
    const selectedRoute = !draft && new Set(["select", "route"]).has(state.activeMapTool) ? state.worldLayout.connections.find((entry) => entry.id === state.selectedRouteId) : null;
    const anchors = draft?.anchors || (selectedRoute ? connectionAnchors(selectedRoute) : []);
    const routeId = draft ? "__draft__" : selectedRoute?.id;
    if (!routeId) return "";
    const renderedAnchors = anchors.map((anchor, index) => {
      const { x, y } = hexPoint(anchor.q, anchor.r); const endpoint = index === 0 || index === anchors.length - 1;
      const locked = Boolean((draft && index === 0 && draft.from) || (!draft && endpoint && (selectedRoute?.from || selectedRoute?.to)));
      return `<g class="route-anchor${endpoint ? " endpoint" : ""}${locked ? " is-locked" : ""}" data-route-anchor-route="${escapeHtml(routeId)}" data-route-anchor-index="${index}" transform="translate(${x} ${y})" role="button" aria-label="길 앵커 ${index + 1}${locked ? " 고정" : " 이동"}"><circle r="8"></circle><circle r="3"></circle><text y="-12">${index + 1}</text></g>`;
    }).join("");
    if (draft || !selectedRoute || !anchors.length) return renderedAnchors;
    const actionAnchor = anchors[Math.floor((anchors.length - 1) / 2)]; const actionPoint = hexPoint(actionAnchor.q, actionAnchor.r);
    return `${renderedAnchors}<g class="route-anchor-actions" transform="translate(${actionPoint.x} ${actionPoint.y - 29})" data-delete-route-inline="${escapeHtml(selectedRoute.id)}" tabindex="0" role="button" aria-label="${escapeHtml(selectedRoute.id)} 길 삭제"><rect x="-25" y="-10" width="50" height="20" rx="10"></rect><text y="4">× 삭제</text></g>`;
  })();
  const towns = (state.worldLayout.settlements || []).map((node) => {
    const { x, y } = hexPoint(node.anchor.q, node.anchor.r); const name = settlementSummary(node.settlement)?.name || node.settlement.split("/").pop();
    return `<g class="hex-settlement${state.routeDraft?.from === node.settlement ? " is-route-origin" : ""}" data-drag-settlement="${escapeHtml(node.settlement)}" transform="translate(${x} ${y})" role="button" aria-label="${escapeHtml(name)} 이동"><circle r="18"></circle><path d="M-7 5V-4L0-10L7-4V5H2V0H-2V5Z"></path><text y="31">${escapeHtml(name)}</text></g>`;
  }).join("");
  const objects = (state.worldLayout.objects || []).map((node) => {
    const { x, y } = hexPoint(node.anchor.q, node.anchor.r);
    return `<g class="hex-custom-object" transform="translate(${x} ${y})"><rect x="-9" y="-9" width="18" height="18" rx="3"></rect><text y="25">${escapeHtml(node.id)}</text></g>`;
  }).join("");
  const caveEntrances = (state.worldLayout.cave_entrances || []).map((node) => {
    const { x, y } = hexPoint(node.anchor.q, node.anchor.r);
    const caveName = caveSummary(node.cave)?.name || node.cave.split("/").pop();
    return `<g class="hex-cave-entrance" data-route-cave-entrance="${escapeHtml(node.id)}" transform="translate(${x} ${y})" role="button" aria-label="${escapeHtml(caveName)} ${escapeHtml(node.entrance)} 입구"><circle r="16"></circle><path d="M-10 7Q-9-9 0-11Q9-9 10 7ZM-4 7V0Q0-5 4 0V7Z"></path><text y="30">${escapeHtml(caveName)} · ${escapeHtml(node.entrance)}</text><text class="center-badge" x="13" y="-12">＋</text></g>`;
  }).join("");
  const brushPreview = renderBrushPreview();
  svg.innerHTML = `<defs><pattern id="empty-terrain-red-hatch" width="8" height="8" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><line x1="0" y1="0" x2="0" y2="8" stroke="#d52828" stroke-width="2.2" opacity=".78"></line></pattern></defs><g class="hex-map-layer">${tiles}${townAreas}${routes}${draftRoute}${towns}${objects}${caveEntrances}${routeAnchors}${brushPreview}</g>`;
  const routeCellCount = new Set((state.worldLayout.connections || []).flatMap((connection) => connectionPath(connection).map((cell) => `${cell.q},${cell.r}`))).size;
  $("#map-tile-count").textContent = `${cells.length}개 표시 · 바이옴 ${(state.worldLayout.tiles || []).length}개 · 길 ${routeCellCount}칸 · 기후 오버라이드 ${(state.worldLayout.environment_overrides || []).length}칸 · 마을 ${(state.worldLayout.settlements || []).length}곳 · 동굴 입구 ${(state.worldLayout.cave_entrances || []).length}곳`;
  $("#map-zoom").textContent = `${Math.round(state.mapZoom * 100)}%`;
  $$("[data-hex-q]").forEach((cell) => {
    const select = () => { if (!state.suppressMapClick) handleHexSelection(Number(cell.dataset.hexQ), Number(cell.dataset.hexR)); };
    cell.addEventListener("click", select);
    cell.addEventListener("keydown", (event) => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); select(); } });
  });
  $$("[data-drag-settlement]").forEach((marker) => marker.addEventListener("pointerdown", (event) => beginSettlementDrag(event, marker.dataset.dragSettlement)));
  $$("[data-drag-settlement]").forEach((marker) => marker.addEventListener("click", (event) => {
    if (state.activeMapTool !== "route") return;
    event.preventDefault(); event.stopPropagation();
    const node = state.worldLayout.settlements.find((entry) => entry.settlement === marker.dataset.dragSettlement);
    if (node?.anchor) handleRoutePoint(node.anchor.q, node.anchor.r, node.settlement);
  }));
  $$("[data-route-cave-entrance]").forEach((marker) => marker.addEventListener("click", (event) => {
    const entrance = (state.worldLayout.cave_entrances || []).find((entry) => entry.id === marker.dataset.routeCaveEntrance);
    if (!entrance) return;
    event.preventDefault(); event.stopPropagation();
    if (state.activeMapTool === "route") handleRoutePoint(entrance.anchor.q, entrance.anchor.r, entrance.id);
    else selectHex(entrance.anchor.q, entrance.anchor.r);
  }));
  $$("[data-select-route]").forEach((route) => {
    const select = (event) => {
      if (!new Set(["select", "route"]).has(state.activeMapTool)) return;
      event.preventDefault(); event.stopPropagation();
      if (state.activeMapTool === "route" && state.routeDraft) {
        const cell = nearestHexFromPointer(event); handleRoutePoint(cell.q, cell.r); return;
      }
      focusRoute(route.dataset.selectRoute, false);
    };
    route.addEventListener("click", select);
    route.addEventListener("keydown", (event) => { if (event.key === "Enter" || event.key === " ") select(event); });
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

function selectHex(q, r) { state.selectedHex = { q, r }; renderHexMap(); renderTileInspector(); }
function handleHexSelection(q, r) {
  const tool = state.activeMapTool;
  if (tool === "biome") paintBiomeArea(q, r);
  else if (tool === "terrain") paintEmptyTerrainArea(q, r);
  else if (tool === "climate") paintClimateArea(q, r);
  else if (tool === "route") handleRoutePoint(q, r);
  else if (tool === "settlement") placeSettlementWithTool(q, r);
  else if (tool === "cave") placeCaveEntranceWithTool(q, r);
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
function eraseMapArea(q, r) {
  const target = $("#eraser-target").value; const radius = Number($("#eraser-radius").value || 0);
  const keys = new Set(hexArea({ q, r }, radius).map((cell) => hexKey(cell.q, cell.r)));
  if (target === "route" || target === "all") state.worldLayout.connections = state.worldLayout.connections.filter((route) => !connectionPath(route).some((cell) => keys.has(hexKey(cell.q, cell.r))));
  if (target === "biome" || target === "all") state.worldLayout.tiles = state.worldLayout.tiles.filter((tile) => !keys.has(hexKey(tile.q, tile.r)));
  if (target === "climate" || target === "all") state.worldLayout.environment_overrides = state.worldLayout.environment_overrides.filter((entry) => !keys.has(hexKey(entry.q, entry.r)));
  if (target === "terrain" || target === "all") state.worldLayout.empty_terrain.tiles = state.worldLayout.empty_terrain.tiles.filter((tile) => !keys.has(hexKey(tile.q, tile.r)));
  if (target === "object" || target === "all") state.worldLayout.objects = state.worldLayout.objects.filter((object) => !keys.has(hexKey(object.anchor.q, object.anchor.r)));
  if (target === "all") state.worldLayout.cave_entrances = state.worldLayout.cave_entrances.filter((entrance) => !keys.has(hexKey(entrance.anchor.q, entrance.anchor.r)));
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
    structure: $("#cave-tool-structure").value.trim(),
    pokemon_center: { structure: $("#cave-tool-center-structure").value.trim(), offset: { q: Number($("#cave-tool-center-q").value || 0), r: Number($("#cave-tool-center-r").value || 0) } }
  });
  state.selectedHex = { q, r }; markWorldDirty(); refreshCaveToolEntrances(); renderWorldLayout(); toast("동굴 입구와 필수 포켓몬센터를 배치했습니다. 길 도구로 입구까지 연결해 주세요.");
}
function placeObjectWithTool(q, r) {
  const id = $("#object-tool-id").value.trim(); const type = $("#object-tool-type").value.trim(); const resource = $("#object-tool-resource").value.trim();
  if (!/^[a-z0-9_.-]+$/.test(id) || !/^[a-z0-9_.-]+$/.test(type)) { toast("오브젝트 ID와 타입을 영문 소문자 형식으로 입력해 주세요."); return; }
  if (state.worldLayout.objects.some((entry) => entry.id === id && (entry.anchor.q !== q || entry.anchor.r !== r))) { toast("이미 사용 중인 오브젝트 ID입니다."); return; }
  state.worldLayout.objects = state.worldLayout.objects.filter((entry) => entry.anchor.q !== q || entry.anchor.r !== r);
  const object = { id, type, anchor: { q, r }, properties: {} }; if (resource) object.resource = resource;
  state.worldLayout.objects.push(object); state.selectedHex = { q, r }; markWorldDirty(); renderWorldLayout();
}
function markWorldDirty() { state.worldDirty = true; updateWorldSaveState(); }
function updateWorldSaveState() { $("#world-save-state").textContent = state.worldDirty ? "저장하지 않은 변경" : "저장된 상태"; $("#world-save-state").classList.toggle("is-dirty", state.worldDirty); }

function worldBiomeOptions(selected = "") {
  const common = ["minecraft:plains", "minecraft:forest", "minecraft:flower_forest", "minecraft:river", "minecraft:beach", "minecraft:warm_ocean", "minecraft:desert", "minecraft:savanna", "minecraft:badlands", "minecraft:windswept_hills", "minecraft:stony_peaks", "minecraft:snowy_plains"];
  const current = [...(state.worldLayout?.tiles || []).map((tile) => tile.biome), ...(state.worldLayout?.settlements || []).flatMap((node) => [node.town_biome, ...(node.surroundings || []).map((region) => region.biome)])];
  return [...new Set([...common, ...current, selected].filter(Boolean))].map((biome) => `<option value="${escapeHtml(biome)}" ${biome === selected ? "selected" : ""}>${escapeHtml(biome.replace("minecraft:", ""))}</option>`).join("");
}

function renderTileInspector() {
  const selected = state.selectedHex; const form = $("#tile-inspector-form");
  $("#tile-inspector-empty").hidden = Boolean(selected); form.hidden = !selected;
  if (!selected) { $("#selected-tile-title").textContent = "타일을 선택하세요"; $("#selected-tile-coord").textContent = "Q — · R —"; renderWorldPokemonPanel(); return; }
  const tile = tileAt(selected.q, selected.r); const town = settlementAt(selected.q, selected.r); const customObject = objectAt(selected.q, selected.r); const townArea = settlementFootprintAt(selected.q, selected.r); const environment = environmentOverrideAt(selected.q, selected.r);
  const routes = routesAt(selected.q, selected.r);
  const kind = customObject ? "object" : town ? "settlement" : tile ? "biome" : "empty";
  $("#selected-tile-title").textContent = customObject ? customObject.id : town ? (settlementSummary(town.settlement)?.name || "마을 타일") : tile ? tile.biome.replace("minecraft:", "") : emptyTerrainLabel(emptyTerrainAt(selected.q, selected.r));
  $("#selected-tile-coord").textContent = `Q ${selected.q} · R ${selected.r}`;
  form.elements.kind.value = kind;
  form.elements.biome.innerHTML = worldBiomeOptions(tile?.biome || "minecraft:plains");
  form.elements.emptyTerrainType.value = emptyTerrainAt(selected.q, selected.r);
  form.elements.settlement.innerHTML = worldSettlementOptions(town?.settlement || "");
  form.elements.townBiome.innerHTML = worldBiomeOptions(town?.town_biome || "minecraft:plains");
  form.elements.objectId.value = customObject?.id || "";
  form.elements.objectType.value = customObject?.type || "landmark";
  form.elements.objectResource.value = customObject?.resource || "";
  $$('[data-tile-field]').forEach((field) => field.hidden = field.dataset.tileField !== kind);
  const routePanel = $("#route-overlay-panel");
  routePanel.hidden = !routes.length;
  $("#route-overlay-list").innerHTML = routes.map((route) => `<div class="route-overlay-item"><span>${escapeHtml(route.id)} · ${escapeHtml(route.surface_style)}</span><button type="button" data-remove-route="${escapeHtml(route.id)}">연결 삭제</button></div>`).join("");
  $$('[data-remove-route]').forEach((button) => button.addEventListener("click", () => removeRouteConnection(button.dataset.removeRoute)));
  const routeNote = routes.length ? `<small>길 오버레이: ${routes.map((route) => escapeHtml(route.id)).join(", ")} · 기본 바이옴은 별도로 유지됩니다.</small>` : "";
  const climateNote = environment ? `<small class="climate-override-note">기후 덮어쓰기 · 온도 ${escapeHtml(environment.temperature || "기본")} · 습도 ${escapeHtml(environment.humidity || "기본")} · 날씨 ${escapeHtml(environment.weather || "기본")}</small>` : "";
  const townAreaNote = townArea && !town ? `<small class="town-area-warning">실제 생성: ${escapeHtml(settlementSummary(townArea.settlement)?.name || townArea.settlement)} 사용 범위 · 이 타일의 바이옴 배치는 무시됩니다.</small>` : "";
  $("#tile-summary").innerHTML = (kind === "object" ? `<b>${escapeHtml(customObject.id)}</b><span>${escapeHtml(customObject.type)} 오브젝트</span><small>바이옴과 길 위에 독립적으로 배치되는 확장용 메타데이터입니다.</small>` : kind === "settlement" ? `<b>마을 중심 타일</b><span>${escapeHtml(town.settlement)}</span><small>마을 크기 ${worldSettlementCellCount(town)}칸 · 마커를 드래그해 이동</small>` : kind === "biome" ? `<b>${escapeHtml(tile.biome)}</b><span>직접 배치된 기본 바이옴</span><small>길 유무와 관계없이 월드 지형에 적용됩니다.</small>` : `<b>${escapeHtml(emptyTerrainLabel(emptyTerrainAt(selected.q, selected.r)))}</b><span>접근 불가 배경 지형</span><small>바이옴과 길은 각각 별도로 배치할 수 있습니다.</small>`) + townAreaNote + routeNote + climateNote;
  renderWorldPokemonPanel();
}

function pokemonMapEntryName(entry) { return entry?.display_name?.ko_kr || entry?.display_name?.en_us || entry?.slug || entry?.id || "알 수 없음"; }
function pokemonMapMatches(entry, query) {
  if (!query) return true;
  const text = [entry.dex_number, entry.id, entry.slug, entry.display_name?.ko_kr, entry.display_name?.en_us].filter(Boolean).join(" ").toLowerCase();
  return text.includes(query.toLowerCase());
}
function pokemonMapCard(entry, unavailable = false) {
  const reason = entry.unavailable_reason === "other_generation" ? `${entry.generation}세대 포켓몬` : "현재 월드 조건과 불일치";
  return `<article class="pokemon-map-card"><img loading="lazy" src="https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/${entry.dex_number}.png" alt=""><div><b>${escapeHtml(pokemonMapEntryName(entry))}</b><span>No.${String(entry.dex_number).padStart(4, "0")} · ${escapeHtml((entry.types || []).join(" / "))}</span><small>${unavailable ? escapeHtml(reason) : `${escapeHtml(entry.habitats?.primary || "unknown")} · ${escapeHtml(entry.preferences?.rarity || "unknown")}`}</small></div></article>`;
}
function renderWorldPokemonPanel() {
  const data = state.worldPokemonMap || {}; const summary = data.summary || {};
  $("#pokemon-map-summary").textContent = `출현 ${summary.available || 0} · 미출현 ${summary.unavailable || 0}`;
  $("#unavailable-pokemon-count").textContent = summary.unavailable || 0;
  $$('[data-pokemon-map-tab]').forEach((button) => { const active = button.dataset.pokemonMapTab === state.pokemonMapTab; button.classList.toggle("is-active", active); button.setAttribute("aria-selected", String(active)); });
  const selected = state.selectedHex;
  const location = selected ? (data.locations || []).find((entry) => entry.q === selected.q && entry.r === selected.r) : null;
  let entries = [];
  if (state.pokemonMapTab === "unavailable") {
    entries = data.unavailable_pokemon || [];
    $("#pokemon-map-location").innerHTML = `<b>${state.selectedGeneration}세대 월드 미출현</b><span>저장된 모든 출현 가능 지역의 합집합에서 제외된 포켓몬입니다.</span>`;
  } else if (!selected) {
    $("#pokemon-map-location").innerHTML = `<b>지역을 선택하세요</b><span>지도 타일을 누르면 해당 위치의 포켓몬을 볼 수 있습니다.</span>`;
  } else if (!location) {
    $("#pokemon-map-location").innerHTML = `<b>출현 불가 지역</b><span>빈 지형 또는 포켓몬 출현 정보가 없는 타일입니다.</span>`;
  } else {
    const pokemonById = new Map([...(data.available_pokemon || []), ...(data.unavailable_pokemon || [])].map((entry) => [entry.id, entry]));
    entries = location.pokemon_ids.map((id) => pokemonById.get(id)).filter(Boolean);
    const label = location.settlement || location.biome;
    $("#pokemon-map-location").innerHTML = `<b>${escapeHtml(label)}</b><span>${location.count}종 · ${location.unmapped_biome ? "바이옴 프로필 매핑 필요" : location.profile_ids.map((id) => id.split("/").pop()).join(", ")}</span>${state.worldDirty ? "<small>저장하지 않은 지도 변경은 아직 반영되지 않았습니다.</small>" : ""}`;
  }
  const filtered = entries.filter((entry) => pokemonMapMatches(entry, state.pokemonMapQuery));
  $("#pokemon-map-list").innerHTML = filtered.length
    ? filtered.map((entry) => pokemonMapCard(entry, state.pokemonMapTab === "unavailable")).join("")
    : `<p class="pokemon-map-empty">${entries.length ? "검색 결과가 없습니다." : "표시할 포켓몬이 없습니다."}</p>`;
}

const mapToolCopy = {
  select: ["선택 도구", "타일과 배치물을 선택해 속성을 편집합니다."],
  biome: ["바이옴 브러시", "기본 바이옴 레이어를 클릭하거나 드래그해 칠합니다."],
  terrain: ["빈 지형 브러시", "접근 불가 배경 지형을 칠합니다."],
  climate: ["기후 오버라이드", "온도·습도·날씨를 좌표별로 덮어씁니다."],
  route: ["길 만들기", "마을 자동 연결 또는 타일 경유 경로를 만듭니다."],
  settlement: ["마을 배치", "프리셋 마을을 배치하거나 이동합니다."],
  cave: ["동굴 입구 배치", "동굴 내부 앵커와 연결되는 입구와 필수 포켓몬센터를 배치합니다."],
  object: ["오브젝트 배치", "확장 가능한 커스텀 오브젝트를 배치합니다."],
  eraser: ["지우개", "선택한 월드 레이어를 제거합니다."]
};
function renderMapToolOptions() {
  const tool = state.activeMapTool; const [name, description] = mapToolCopy[tool];
  $("#active-tool-name").textContent = name; $("#active-tool-description").textContent = description;
  $$('[data-map-tool]').forEach((button) => { button.classList.toggle("is-active", button.dataset.mapTool === tool); button.setAttribute("aria-pressed", String(button.dataset.mapTool === tool)); });
  $$('[data-tool-options]').forEach((panel) => panel.hidden = panel.dataset.toolOptions !== tool);
  $("#biome-brush-type").innerHTML = worldBiomeOptions($("#biome-brush-type").value || "minecraft:plains");
  $("#settlement-tool-biome").innerHTML = worldBiomeOptions($("#settlement-tool-biome").value || "minecraft:plains");
  $("#settlement-tool-preset").innerHTML = worldSettlementOptions($("#settlement-tool-preset").value);
  const currentCave = $("#cave-tool-cave").value;
  $("#cave-tool-cave").innerHTML = state.caves.filter((item) => Number(item.generation || 1) === state.selectedGeneration).map((item) => `<option value="${escapeHtml(item.id)}">${escapeHtml(item.name || item.id)}</option>`).join("");
  if (currentCave && state.caves.some((item) => item.id === currentCave)) $("#cave-tool-cave").value = currentCave;
  refreshCaveToolEntrances();
  const help = { select: "선택 도구 · 드래그: 지도/마을 이동", biome: "바이옴 브러시 · 누른 채 드래그하여 칠하기", terrain: "빈 지형 브러시 · 마을과 길은 유지", climate: "기후 오버라이드 · 원본 바이옴 설정은 유지", route: "길 도구 · 마을/동굴 입구까지 자동 연결", settlement: "마을 도구 · 타일을 눌러 배치 또는 이동", cave: "동굴 입구 도구 · 입구와 포켓몬센터를 함께 배치", object: "오브젝트 도구 · 독립 레이어에 배치", eraser: "지우개 · 선택 레이어 제거" };
  $("#map-interaction-help").textContent = help[tool];
  $("#world-hex-map").dataset.activeTool = tool;
  renderRouteCreator();
}
function setActiveMapTool(tool) {
  if (!mapToolCopy[tool] || tool === state.activeMapTool) return;
  if (state.routeDraft && tool !== "route") state.routeDraft = null;
  if (!new Set(["select", "route"]).has(tool)) state.selectedRouteId = null;
  state.activeMapTool = tool; state.paintStroke = null; state.brushPreview = null; state.draggedSettlement = null;
  renderMapToolOptions(); renderHexMap();
}

function applyStrokeTool(q, r) {
  if (state.activeMapTool === "biome") paintBiomeArea(q, r);
  else if (state.activeMapTool === "terrain") paintEmptyTerrainArea(q, r);
  else if (state.activeMapTool === "climate") paintClimateArea(q, r);
  else if (state.activeMapTool === "eraser") eraseMapArea(q, r);
}
function beginToolStroke(event) {
  if (!new Set(["biome", "terrain", "climate", "eraser"]).has(state.activeMapTool) || event.button !== 0 || state.spacePanActive) return false;
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
    return `<article class="route-manager-item"><button type="button" class="route-focus" data-focus-route="${escapeHtml(route.id)}"><b>${escapeHtml(route.id)}</b><span>${escapeHtml(fromName)} → ${escapeHtml(toName)}</span><small>${escapeHtml(route.surface_style)} · ${connectionPath(route).length}칸</small></button><button type="button" class="route-delete" data-delete-route="${escapeHtml(route.id)}" aria-label="${escapeHtml(route.id)} 삭제">×</button></article>`;
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
  const cells = route ? connectionPath(route) : [];
  if (!cells.length) return;
  state.selectedRouteId = routeId;
  state.selectedHex = { ...cells[0] };
  if (center) {
    const points = cells.map((cell) => hexPoint(cell.q, cell.r));
    state.mapCenter = { x: points.reduce((sum, point) => sum + point.x, 0) / points.length, y: points.reduce((sum, point) => sum + point.y, 0) / points.length };
  }
  renderHexMap(); renderTileInspector();
}

function defaultWorldTile(q, r, biome) { return { q, r, biome, boundary_profile: "cobbleventure:boundary/earthwork", terrain_profile: { base_height_offset: 0, height_variation: 3, noise_scale_blocks: 96 } }; }
function defaultWorldSettlement(id, q, r, biome = "minecraft:plains") { return { settlement: id, anchor: { q, r }, town_radius_cells: settlementPresetRadius(id), town_footprint_shape: settlementPresetFootprintShape(id), town_footprint_cells: settlementPresetFootprintCells(id), town_road_exits: settlementPresetRoadExits(id), town_biome: biome, surroundings: [], boundary_profile: "cobbleventure:boundary/stone_wall", terrain_profile: { base_height_offset: 0, height_variation: 3, noise_scale_blocks: 96 } }; }
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
    const object = { id, type, anchor: { q, r }, properties: {} };
    if (form.elements.objectResource.value.trim()) object.resource = form.elements.objectResource.value.trim();
    state.worldLayout.objects.push(object); markWorldDirty(); renderWorldLayout(); return;
  }
  const townIndex = state.worldLayout.settlements.findIndex((node) => node.anchor?.q === q && node.anchor?.r === r);
  if (townIndex >= 0 && kind !== "settlement") {
    state.worldLayout.settlements.splice(townIndex, 1);
  }
  state.worldLayout.tiles = state.worldLayout.tiles.filter((tile) => tile.q !== q || tile.r !== r);
  if (kind === "empty") setEmptyTerrainTile(q, r, form.elements.emptyTerrainType.value);
  else state.worldLayout.empty_terrain.tiles = state.worldLayout.empty_terrain.tiles.filter((tile) => tile.q !== q || tile.r !== r);
  if (kind === "biome") state.worldLayout.tiles.push(defaultWorldTile(q, r, form.elements.biome.value));
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
  if (event.target.name === "kind") {
    $$('[data-tile-field]').forEach((field) => field.hidden = field.dataset.tileField !== event.target.value);
    if (event.target.value === "settlement" || event.target.value === "object") return;
  }
  applyTilePlacement();
}

function beginRouteAnchorDrag(event, routeId, index, locked) {
  event.preventDefault(); event.stopPropagation();
  if (locked) { toast("마을에 연결된 시작·도착 앵커는 고정됩니다."); return; }
  state.routeAnchorDrag = { pointerId: event.pointerId, routeId, index, changed: false };
  $("#world-hex-map").setPointerCapture?.(event.pointerId);
}
function moveRouteAnchorDrag(event) {
  const drag = state.routeAnchorDrag; if (!drag || drag.pointerId !== event.pointerId) return false;
  const target = nearestHexFromPointer(event);
  const owner = drag.routeId === "__draft__" ? state.routeDraft : state.worldLayout.connections.find((route) => route.id === drag.routeId);
  if (!owner) return true;
  owner.anchors ||= connectionAnchors(owner).map((anchor) => ({ ...anchor }));
  const anchor = owner.anchors[drag.index]; if (!anchor || (anchor.q === target.q && anchor.r === target.r)) return true;
  owner.anchors[drag.index] = target; owner.cells = routeCellsFromAnchors(owner.anchors); drag.changed = true; state.selectedHex = target; renderHexMap(); return true;
}
function finishRouteAnchorDrag(event) {
  const drag = state.routeAnchorDrag; if (!drag || drag.pointerId !== event.pointerId) return false;
  state.routeAnchorDrag = null;
  if (drag.changed && drag.routeId !== "__draft__") markWorldDirty();
  renderWorldLayout(); return true;
}

function beginSettlementDrag(event, settlementId) {
  event.preventDefault(); event.stopPropagation();
  if (state.activeMapTool === "route") return;
  if (state.activeMapTool !== "select") return;
  state.draggedSettlement = settlementId;
  $("#world-hex-map").setPointerCapture?.(event.pointerId);
  $("#world-hex-map").classList.add("is-dragging");
}
function nearestHexFromPointer(event) {
  const svg = $("#world-hex-map"); const point = svg.createSVGPoint(); point.x = event.clientX; point.y = event.clientY;
  const local = point.matrixTransform(svg.getScreenCTM().inverse());
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
  if (event.button !== 0 || event.target.closest?.("[data-drag-settlement], [data-select-route], [data-route-anchor-index], [data-delete-route-inline]") || (state.activeMapTool !== "select" && !state.spacePanActive)) return;
  const svg = $("#world-hex-map");
  state.mapPan = { pointerId: event.pointerId, startX: event.clientX, startY: event.clientY, centerX: state.mapCenter.x, centerY: state.mapCenter.y, lastRenderX: state.mapCenter.x, lastRenderY: state.mapCenter.y, moved: false };
}
function moveMapPan(event) {
  updateBrushPreview(event);
  if (moveRouteAnchorDrag(event)) return;
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
  if (!state.draggedSettlement) return;
  const target = nearestHexFromPointer(event); const node = state.worldLayout.settlements.find((entry) => entry.settlement === state.draggedSettlement);
  const occupied = target && settlementAt(target.q, target.r);
  const conflict = node && target ? settlementRangeConflict(node, target.q, target.r, settlementPresetRadius(node.settlement)) : null;
  if (conflict) toast(`${settlementSummary(conflict.settlement)?.name || "다른 마을"}의 사용 범위와 겹칩니다.`);
  else if (node && target && (!occupied || occupied === node)) { node.anchor = target; state.selectedHex = target; markWorldDirty(); }
  else if (occupied && occupied !== node) toast("이미 다른 마을이 배치된 타일입니다.");
  state.draggedSettlement = null; $("#world-hex-map").classList.remove("is-dragging"); renderWorldLayout();
}

async function addGeneration() {
  const generation = Array.from({ length: 9 }, (_, index) => index + 1).find((value) => !state.worldGenerations.includes(value));
  if (!generation) { toast("9세대까지 모두 추가되어 있습니다."); return; }
  const payload = { "$schema": "../schemas/hex-world.schema.json", schema_version: 2, id: `cobbleventure:world/generation_${generation}`, dimension: `cobbleventure:generation_${generation}`, seed_salt: 1700 + generation, grid: { orientation: "pointy_top", tile_radius_blocks: 64, map_radius_cells: 6, origin: { x: 0, y: 69, z: 0 } }, empty_terrain: { default_type: "high_forest", tiles: [] }, tiles: [], environment_overrides: [], settlements: [], cave_entrances: [], connections: [], objects: [] };
  const result = await request(`/api/world-layout?generation=${generation}`, { method: "PUT", body: JSON.stringify(payload) });
  if (!result.ok) { toast(result.data.error || "세대를 추가하지 못했습니다."); return; }
  state.worldGenerations.push(generation); state.worldGenerations.sort((a, b) => a - b); state.selectedGeneration = generation; state.worldLayout = payload; state.selectedHex = null; state.worldDirty = false; state.mapViewInitialized = false; renderWorldLayout(); toast(`${generation}세대 월드를 추가했습니다.`);
}

function documentSingular(category) {
  return category === "trainers" ? "trainer" : category === "battles" ? "battle" : category === "caves" ? "cave" : "settlement";
}

function renderList(category) {
  const items = state[category];
  const singular = documentSingular(category);
  $(`#${singular}-list-count`).textContent = items.length;
  const list = $(`#${singular}-list`);
  if (!items.length) { list.innerHTML = '<div class="issues empty">등록된 문서가 없습니다.</div>'; return; }
  list.innerHTML = items.map((item) => `
    <button class="document-button ${state[`${singular}Path`] === item.path ? "is-active" : ""}" data-path="${escapeHtml(item.path)}">
      <strong>${escapeHtml(item.name || item.id || "이름 없음")}</strong><small>${escapeHtml(item.id || item.path)}</small>
    </button>`).join("");
  $$( `#${singular}-list .document-button`).forEach((button) => button.addEventListener("click", () => loadDocument(category, button.dataset.path)));
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

function renderCave() {
  const document = state.cave; const form = $("#cave-form");
  if (!document) return;
  $("#selected-cave-editor").hidden = false; $("#cave-editor-title").textContent = document.display_name?.ko_kr || document.id; $("#cave-path").textContent = state.cavePath;
  setFormValue(form, "id", document.id); setFormValue(form, "nameKo", document.display_name?.ko_kr); setFormValue(form, "generation", document.generation || 1);
  setFormValue(form, "caveType", document.cave_type); setFormValue(form, "dimensionId", document.dimension?.id || "cobbleventure:dungeons");
  setFormValue(form, "requiresFlash", Boolean(document.requires_flash)); setFormValue(form, "randomEncounters", Boolean(document.random_encounters?.enabled));
  setFormValue(form, "spawnProfile", document.random_encounters?.spawn_profile || ""); setFormValue(form, "densityMultiplier", document.random_encounters?.density_multiplier ?? 1);
  setFormValue(form, "trainersEnabled", Boolean(document.trainer_settings?.enabled)); setFormValue(form, "maxActiveTrainers", document.trainer_settings?.max_active ?? 0);
  setFormValue(form, "trainerClassPool", (document.trainer_settings?.class_pool || []).join(", "));
  setFormValue(form, "internalBiomes", JSON.stringify(document.internal_biomes || [], null, 2)); setFormValue(form, "entrances", JSON.stringify(document.entrances || [], null, 2));
  $("#cave-json").value = JSON.stringify(document, null, 2);
  ["#cave-json", "#apply-cave-json", "#delete-cave", "#validate-cave", "#save-cave"].forEach((selector) => $(selector).disabled = false);
  showIssues("#cave-issues", { valid: true, issues: [] });
}
function updateCaveFromForm() {
  if (!state.cave) return false; const form = $("#cave-form");
  let internalBiomes; let entrances;
  try { internalBiomes = JSON.parse(form.elements.internalBiomes.value); entrances = JSON.parse(form.elements.entrances.value); }
  catch (error) { toast(`동굴 목록 JSON 문법 오류: ${error.message}`); return false; }
  state.cave.id = form.elements.id.value.trim(); state.cave.display_name = { ...(state.cave.display_name || {}), ko_kr: form.elements.nameKo.value.trim() };
  state.cave.generation = Number(form.elements.generation.value); state.cave.cave_type = form.elements.caveType.value;
  state.cave.dimension ||= { region_id: `generation_${state.cave.generation}/${state.cave.id.split("/").pop()}`, origin: { x: 0, y: 48, z: 0 }, bounds: { min_x: -256, min_z: -256, max_x: 256, max_z: 256 } };
  state.cave.dimension.id = form.elements.dimensionId.value.trim(); state.cave.requires_flash = form.elements.requiresFlash.checked;
  state.cave.random_encounters = { enabled: form.elements.randomEncounters.checked, spawn_profile: form.elements.spawnProfile.value.trim(), density_multiplier: Number(form.elements.densityMultiplier.value || 0) };
  state.cave.trainer_settings ||= { placements: [] }; state.cave.trainer_settings.enabled = form.elements.trainersEnabled.checked; state.cave.trainer_settings.max_active = Number(form.elements.maxActiveTrainers.value || 0); state.cave.trainer_settings.class_pool = form.elements.trainerClassPool.value.split(",").map((value) => value.trim()).filter(Boolean); state.cave.trainer_settings.placements ||= [];
  state.cave.internal_biomes = internalBiomes; state.cave.entrances = entrances; $("#cave-json").value = JSON.stringify(state.cave, null, 2); return true;
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
  setFormValue(form, "movement", document.npc?.behavior?.movement);
  setFormValue(form, "interactionRange", document.npc?.behavior?.interaction_range);
  const encounter = document.npc?.behavior?.encounter || { mode: "interaction", trigger_range: document.npc?.behavior?.interaction_range || 4, warning_range: { min: 4, max: 6 } };
  setFormValue(form, "encounterMode", encounter.mode);
  setFormValue(form, "encounterTriggerRange", encounter.trigger_range ?? document.npc?.behavior?.interaction_range ?? 4);
  setFormValue(form, "warningRangeMin", encounter.warning_range?.min ?? 4);
  setFormValue(form, "warningRangeMax", encounter.warning_range?.max ?? 6);
  setFormValue(form, "lookAtPlayer", document.npc?.behavior?.look_at_player);
  setFormValue(form, "invulnerable", document.npc?.behavior?.invulnerable);
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
  const money = rewardNode?.actions?.find((action) => action.type === "give_money") || document.rewards?.money || { mode: "fixed", amount: 0, currency_objective: "cobbleventure_money" };
  setFormValue(form, "moneyMode", money.mode);
  setFormValue(form, "moneyAmount", money.amount ?? 0);
  setFormValue(form, "moneyMultiplier", money.multiplier ?? 1);
  setFormValue(form, "currencyObjective", money.currency_objective || "cobbleventure_money");
  const lootAction = rewardNode?.actions?.find((action) => action.type === "grant_loot");
  const itemAction = rewardNode?.actions?.find((action) => action.type === "give_item");
  const itemReward = lootAction ? { mode: "loot_table", loot_table: lootAction.loot_table } : itemAction ? { mode: "fixed", entries: [{ item: itemAction.item, count: itemAction.count }] } : document.rewards?.items || { mode: "fixed", entries: [] };
  const fixedReward = itemReward.entries?.[0] || { item: "cobblemon:poke_ball", count: 1 };
  setFormValue(form, "itemRewardMode", itemReward.mode);
  setFormValue(form, "rewardItem", fixedReward.item);
  setFormValue(form, "rewardItemCount", fixedReward.count);
  setFormValue(form, "rewardLootTable", itemReward.loot_table || "cobbleventure:trainer/rewards");
  setFormValue(form, "spawnCommand", `/easy_npc preset import_new data cobbleventure:encounter/${document.id.split("/").at(-1)} ~ ~ ~`);
  renderTrainerRewardFields(form);
  $("#max-item-uses").value = Number.isInteger(battle.rules?.max_item_uses)
    ? battle.rules.max_item_uses
    : "";
  [...form.elements].forEach((element) => element.disabled = false);
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
    delete state.trainer.npc.behavior.interaction_range;
    delete state.trainer.npc.behavior.encounter;
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
  const currencyObjective = form.elements.currencyObjective.value.trim() || "cobbleventure_money";
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
  give_money: "돈 지급", give_item: "아이템 지급", grant_loot: "루트 테이블 지급", end: "이벤트 종료",
};
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
  if (command.type === "give_money") return command.mode === "level_cap_multiplier" ? `레벨캡 × ${command.multiplier || 1}` : `${Number(command.amount || 0).toLocaleString()} 지급`;
  if (command.type === "give_item") return `${command.item || "아이템 없음"} × ${command.count || 1}`;
  if (command.type === "grant_loot") return command.loot_table || "루트 테이블 없음";
  if (command.type === "end") return "이벤트 실행 종료";
  return command.type;
}

function defaultNpcDialogueFlag() {
  const rawId = String(state.trainer?.id || "cobbleventure:npc/new_npc");
  const [namespace = "cobbleventure", path = "npc/new_npc"] = rawId.split(":");
  const slug = path.replace(/^npc\//, "").replace(/[^a-z0-9_/.-]+/gi, "_");
  return `${namespace}:flag/npc/${slug}/talked`;
}

function renderEventPresetFields() {
  const type = $("#event-preset-type").value;
  $$('[data-event-preset-option="repeat"]').forEach((element) => { element.hidden = type === "simple"; });
  $$('[data-event-preset-option="item"]').forEach((element) => { element.hidden = type !== "item"; });
}

function applyEventScriptPreset() {
  const event = selectedNpcEvent();
  if (!event) return;
  const type = $("#event-preset-type").value;
  const firstText = $("#event-preset-first-text").value.trim() || "안녕하세요!";
  const repeatText = $("#event-preset-repeat-text").value.trim() || "다시 만났네요.";
  if (type === "simple") {
    event.commands = [
      { type: "dialogue", id: "greeting", speaker: "npc", text: { ko_kr: firstText } },
      { type: "end" },
    ];
  } else {
    const flag = $("#event-preset-flag").value.trim() || defaultNpcDialogueFlag();
    event.commands = [
      { type: "branch", conditions: [{ type: "flag_equals", key: flag, value: true }], target: "repeat_greeting" },
      { type: "dialogue", id: "first_greeting", speaker: "npc", text: { ko_kr: firstText } },
    ];
    if (type === "item") event.commands.push({ type: "give_item", item: $("#event-preset-item").value.trim() || "cobblemon:poke_ball", count: Math.max(1, Number($("#event-preset-item-count").value) || 1) });
    event.commands.push(
      { type: "set_flag", key: flag, value: true },
      { type: "goto", target: "end" },
      { type: "label", name: "repeat_greeting" },
      { type: "dialogue", id: "repeat_greeting", speaker: "npc", text: { ko_kr: repeatText } },
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
    details = `<label class="wide"><span>플래그 ID</span><input data-condition-field="key" value="${escapeHtml(condition.key || "")}" placeholder="cobbleventure:flag/example"></label>${renderEventValueEditor(condition.value ?? true, "value", "condition")}`;
  } else if (type === "has_item") {
    details = `<label class="wide"><span>아이템 ID</span><input data-condition-field="item" value="${escapeHtml(condition.item || "")}" placeholder="cobblemon:potion"></label><label><span>필요 수량</span><input data-condition-field="count" data-value-type="number" type="number" min="1" step="1" value="${escapeHtml(condition.count ?? 1)}"></label>`;
  } else {
    details = '<p class="command-help wide">별도의 검사 없이 이 분기로 이동합니다.</p>';
  }
  return `<div class="event-subrow" data-condition-index="${conditionIndex}"><label><span>조건 종류</span><select data-condition-field="type" data-condition-rerender="true"><option value="flag_equals" ${type === "flag_equals" ? "selected" : ""}>플래그 비교</option><option value="has_item" ${type === "has_item" ? "selected" : ""}>아이템 보유</option><option value="always" ${type === "always" ? "selected" : ""}>항상</option></select></label>${details}<button type="button" class="remove-bag-item" data-condition-remove="${conditionIndex}">조건 삭제</button></div>`;
}

function renderChoiceEditor(option, optionIndex) {
  return `<div class="event-subrow choice-subrow" data-option-index="${optionIndex}"><label><span>선택지 ID</span><input data-option-field="id" value="${escapeHtml(option.id || "")}"></label><label class="wide"><span>화면에 표시할 문구</span><input data-option-field="text.ko_kr" value="${escapeHtml(option.text?.ko_kr || "")}"></label><label><span>이동할 라벨</span><input data-option-field="target" value="${escapeHtml(option.target || "")}"></label><button type="button" class="remove-bag-item" data-option-remove="${optionIndex}">선택지 삭제</button></div>`;
}

function renderEventCommandEditor(command) {
  if (command.type === "branch") {
    const conditions = command.conditions?.length ? command.conditions : [{ type: "always" }];
    return `<div class="event-command-fields"><div class="event-subsection-heading"><strong>조건</strong><button type="button" class="button secondary compact-button" data-condition-add>조건 추가</button></div>${conditions.map(renderConditionEditor).join("")}${eventField("조건이 맞으면 이동할 라벨", "target", command.target || "", { wide: true })}</div>`;
  }
  if (command.type === "label") return `<div class="event-command-fields">${eventField("라벨 이름", "name", command.name || "", { wide: true, help: "분기, 선택지, 배틀 결과가 이동할 위치입니다." })}</div>`;
  if (command.type === "dialogue") return `<div class="event-command-fields">${eventField("대화 ID", "id", command.id || "")}${eventField("화자", "speaker", command.speaker || "npc", { choices: [["npc", "NPC"], ["player", "플레이어"], ["system", "시스템"]] })}${eventField("대화 내용", "text.ko_kr", command.text?.ko_kr || "", { type: "textarea", wide: true })}</div>`;
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
  if (command.type === "give_money") {
    const mode = command.mode || "fixed";
    return `<div class="event-command-fields">${eventField("지급 방식", "mode", mode, { choices: [["fixed", "고정 금액"], ["level_cap_multiplier", "현재 레벨캡 × 배율"]], rerender: true })}${mode === "fixed" ? eventField("지급 금액", "amount", command.amount ?? 0, { type: "number", valueType: "number", min: 0, step: 1 }) : eventField("레벨캡 배율", "multiplier", command.multiplier ?? 1, { type: "number", valueType: "number", min: .01, step: .01 })}${eventField("화폐 점수판", "currency_objective", command.currency_objective || "cobbleventure_money")}${mode === "level_cap_multiplier" ? eventField("레벨캡 점수판", "level_cap_objective", command.level_cap_objective || "cobbleventure_level_cap") : ""}</div>`;
  }
  if (command.type === "give_item") return `<div class="event-command-fields">${eventField("아이템 ID", "item", command.item || "", { wide: true })}${eventField("수량", "count", command.count ?? 1, { type: "number", valueType: "number", min: 1, step: 1 })}</div>`;
  if (command.type === "grant_loot") return `<div class="event-command-fields">${eventField("루트 테이블 ID", "loot_table", command.loot_table || "", { wide: true, help: "확률과 아이템 구성은 별도의 루트 테이블에서 관리합니다." })}</div>`;
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
  const trigger = event.trigger || { type: "interact", range: 4 };
  $("#event-trigger-type").value = trigger.type;
  $("#event-trigger-range").value = trigger.range ?? 4;
  $("#event-warning-offset").value = trigger.warning_offset ?? 2;
  $("#event-range-label").textContent = trigger.type === "proximity" ? "자동 발동 거리" : "대화 가능 거리";
  $("#event-warning-offset-field").hidden = trigger.type !== "proximity";
  ["#event-trigger-type", "#event-trigger-range", "#event-warning-offset", "#event-command-type", "#add-event-command", "#event-preset-type", "#event-preset-first-text", "#event-preset-repeat-text", "#event-preset-item", "#event-preset-item-count", "#event-preset-flag", "#apply-event-preset"].forEach((selector) => { $(selector).disabled = false; });
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
    give_money: { type, mode: "fixed", amount: 500, currency_objective: "cobbleventure_money" },
    give_item: { type, item: "cobblemon:poke_ball", count: 1 },
    grant_loot: { type, loot_table: "cobbleventure:rewards/example" },
    end: { type },
  };
  return defaults[type];
}

function addEventCommand() {
  const event = selectedNpcEvent(); if (!event) return;
  event.commands.push(defaultEventCommand($("#event-command-type").value));
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
  if (event.target.dataset.commandRerender || event.target.dataset.conditionRerender) renderEventScript();
  syncTrainerJson();
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
  const row = event.target.closest("[data-event-command]");
  if (remove) script.commands.splice(Number(remove.dataset.commandRemove), 1);
  else if (addCondition && row) script.commands[Number(row.dataset.eventCommand)].conditions.push({ type: "flag_equals", key: "cobbleventure:flag/example", value: true });
  else if (removeCondition && row) {
    const conditions = script.commands[Number(row.dataset.eventCommand)].conditions;
    conditions.splice(Number(removeCondition.dataset.conditionRemove), 1);
    if (!conditions.length) conditions.push({ type: "always" });
  } else if (addOption && row) script.commands[Number(row.dataset.eventCommand)].options.push({ id: "option", text: { ko_kr: "새 선택지" }, target: "next" });
  else if (removeOption && row) script.commands[Number(row.dataset.eventCommand)].options.splice(Number(removeOption.dataset.optionRemove), 1);
  else if (up) {
    const index = Number(up.dataset.commandUp); [script.commands[index - 1], script.commands[index]] = [script.commands[index], script.commands[index - 1]];
  } else if (down) {
    const index = Number(down.dataset.commandDown); [script.commands[index + 1], script.commands[index]] = [script.commands[index], script.commands[index + 1]];
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
  [$("#choice-title").textContent, $("#choice-subtitle").textContent] = titles[kind];
  $("#choice-dialog").showModal();
  renderChoiceDialog();
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
  const optionalCard = choice.kind === "trainer_reference_create" ? '<button type="button" class="choice-card optional-choice-card" data-choice-value=""><span class="choice-card-title"><strong>빈 프리셋으로 시작</strong><small>EMPTY PRESET</small></span><p>기존 엔트리를 복사하지 않고 빈 배틀 프리셋을 만듭니다.</p></button>' : optionalChoiceCard(choice.kind);
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
  return options + values.map(([id, label]) => `<option value="${escapeHtml(id)}" ${id === selected ? "selected" : ""}>${escapeHtml(label)}</option>`).join("");
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
  setFormValue(form, "profileId", profile.id); setFormValue(form, "nameKo", profile.display_name?.ko_kr);
  setFormValue(form, "habitat", profile.habitat); setFormValue(form, "generation", profile.settings?.generation ?? 0);
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
  profile.settings = { generation: Number(form.elements.generation.value), temperature: form.elements.temperature.value, humidity: form.elements.humidity.value, weather: form.elements.weather.value, time: form.elements.time.value, rarities: csvValues(form.elements.rarities.value), include_secondary: form.elements.secondary.checked };
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
    minecraft_biomes: ["minecraft:plains"],
    settings: { generation: 0, temperature: "any", humidity: "any", weather: "any", time: "any", rarities: ["common", "medium", "uncommon", "rare", "legendary"], include_secondary: true },
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

function facilityTrainerOptions(selected = "") {
  return '<option value="">나중에 지정</option>' + state.trainers.map((trainer) =>
    `<option value="${escapeHtml(trainer.id)}"${trainer.id === selected ? " selected" : ""}>${escapeHtml(trainer.name || trainer.id)} · ${escapeHtml(trainer.id)}</option>`
  ).join("");
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
  return Object.entries(state.structureSizes)
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
  const model = state.structureViewer.model;
  if (!canvas) return;
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
  const structure = form.elements.gymStructure.value.trim();
  const footprint = structureFootprint(structure, { width: 25, depth: 26, height: 13 });
  if (!footprint.topView?.blocks?.length) {
    footprint.topView = gymFallbackTopView(form.elements.gymTheme.value, footprint.width, footprint.depth);
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
  return villageDensityProfiles[value] ? value : "normal";
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

function villagePlotInsideLayout(plot, cellCount, shape, customCells = []) {
  const step = 4;
  const samples = [];
  for (let x = plot.x; x <= plot.x + plot.width; x += step) {
    for (let z = plot.z; z <= plot.z + plot.depth; z += step) samples.push([x, z]);
  }
  samples.push([plot.x + plot.width, plot.z + plot.depth], [plot.x + plot.width / 2, plot.z + plot.depth / 2]);
  return samples.every(([x, z]) => villageLayoutContains(x, z, cellCount, shape, 4, customCells));
}

function simulateJigsawVillage(seed, depth, shape, roadWidth, requirements, cellCount = 1, housePalette = defaultHousePalette, footprintShape = "line_q", customCells = [], roadExits = [], density = "normal") {
  const random = villagePreviewRandom(seed);
  const normalizedCellCount = normalizeTownCellCount(cellCount);
  const normalizedFootprintShape = normalizeTownFootprintShape(footprintShape);
  const layoutCells = villageLayoutCells(normalizedCellCount, normalizedFootprintShape, customCells);
  const directions = [{ x: 0, z: -1 }, { x: 1, z: 0 }, { x: 0, z: 1 }, { x: -1, z: 0 }];
  const centerPattern = villageCenterPattern(shape, seed);
  const hub = villageLayoutHub();
  const queue = centerPattern.directions.map((direction) => ({ x: hub.x, z: hub.z, direction, depth: 0 }));
  const occupiedRoad = new Set([`${Math.round(hub.x / 16)},${Math.round(hub.z / 16)}`]);
  const roads = [];
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
      if (!villageLayoutContains(cellX * 16, cellZ * 16, normalizedCellCount, normalizedFootprintShape, 8, customCells)) break;
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

  const roadKeys = new Set(roads.flatMap((road) => [
    `${road.x1},${road.z1},${road.x2},${road.z2}`,
    `${road.x2},${road.z2},${road.x1},${road.z1}`
  ]));
  const addCoverageRoad = (x1, z1, x2, z2) => {
    if (x1 === x2 && z1 === z2) return;
    const key = `${x1},${z1},${x2},${z2}`;
    if (roadKeys.has(key)) return;
    roads.push({ x1, z1, x2, z2, depth: 0 });
    roadKeys.add(key);
    roadKeys.add(`${x2},${z2},${x1},${z1}`);
  };
  const coverageSources = roads.flatMap((road) => [[road.x1, road.z1], [road.x2, road.z2]])
    .filter(([x, z]) => x !== hub.x || z !== hub.z);
  for (const cell of layoutCells) {
    const center = villageLayoutCenteredCellCenter(cell, layoutCells);
    const targetX = Math.round(center.x / 16) * 16;
    const targetZ = Math.round(center.z / 16) * 16;
    if (targetX === hub.x && targetZ === hub.z) continue;
    const candidates = coverageSources.length ? coverageSources : [[hub.x, hub.z]];
    const [sourceX, sourceZ] = candidates.reduce((best, point) =>
      Math.abs(point[0] - targetX) + Math.abs(point[1] - targetZ) < Math.abs(best[0] - targetX) + Math.abs(best[1] - targetZ) ? point : best
    );
    addCoverageRoad(sourceX, sourceZ, targetX, sourceZ);
    addCoverageRoad(targetX, sourceZ, targetX, targetZ);
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

  function tryPlacePlot(definition, kind, label, attempts = slots.length) {
    const width = Number(definition.footprint?.width || definition.width || 16);
    const depthSize = Number(definition.footprint?.depth || definition.depth || 16);
    const height = Number(definition.footprint?.height || definition.height || 1);
    const occupied = definition.footprint?.occupied || {
      min_x: 0, min_z: 0, max_x: width - 1, max_z: depthSize - 1,
      width, depth: depthSize
    };
    const startSlot = Math.floor(random() * Math.max(1, slots.length));
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
      plots.push(plot);
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
    tryPlacePlot(houseDefinition, "house", `${base.label} · ${houseRoofCatalog.find((item) => item.id === roof)?.label || roof}`, slots.length * 2);
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
  return { roads: visibleRoads, accessRoads, decorations, plots, missing, rejectedRoads, openConnectors: queue.length, layoutCells, hub, centerPattern: centerPattern.id };
}

const villageLayoutRerollLimit = 8;
const villageLayoutRerollStep = 104729;

function villageLayoutRerollSeed(seed, attempt) {
  return 1 + ((Math.max(1, Number(seed)) - 1 + attempt * villageLayoutRerollStep) % 999999999);
}

function simulateVillageWithRerolls(seed, depth, shape, roadWidth, requirements, cellCount = 1, housePalette = defaultHousePalette, footprintShape = "line_q", customCells = [], roadExits = [], density = "normal") {
  let result = null;
  for (let attempt = 0; attempt < villageLayoutRerollLimit; attempt += 1) {
    const resolvedSeed = villageLayoutRerollSeed(seed, attempt);
    result = simulateJigsawVillage(resolvedSeed, depth, shape, roadWidth, requirements, cellCount, housePalette, footprintShape, customCells, roadExits, density);
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
  const roadWidth = Number(form.elements.townRoadWidth.value || 7);
  const requirements = [...selectedCivicFacilities(), ...selectedFacilityRequirements(), ...selectedGymFacility()];
  const radiusCells = normalizeTownCellCount(form.elements.townRadiusCells.value);
  const footprintShape = normalizeTownFootprintShape(form.elements.townFootprintShape.value);
  const density = normalizeVillageDensity(form.elements.townBuildingDensity?.value);
  const result = simulateVillageWithRerolls(seed, depth, shape, roadWidth, requirements, radiusCells, selectedHousePalette(), footprintShape, customTownCells(), customTownExits(), density);
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
  const roadColors = { cobblestone: "#8d9292", stone_bricks: "#727b80", gravel: "#aaa397", packed_mud: "#846b55", sandstone: "#d4ba7d", snow: "#d9e9ed" };
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
  const hub = project(0, 0);
  context.fillStyle = "#5b6770";
  context.fillRect(hub.x - 14 * scale, hub.y - 14 * scale, 28 * scale, 28 * scale);
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
      : [["line_q", "고정 형태"]];
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
  for (const name of ["specialBuildingStructure", "specialDistrictPlacementMode", "specialDistrictWidth", "specialDistrictDepth", "specialDistrictClearance"]) {
    form.elements[name].disabled = !buildingEnabled;
  }
  for (const name of ["specialDistrictX", "specialDistrictY", "specialDistrictZ"]) {
    form.elements[name].disabled = !buildingEnabled || !manualPlacement;
  }
  const gymEnabled = form.elements.gymEnabled.checked;
  for (const name of ["gymStructure", "gymTheme", "gymAnchor", "gymLeaderTrainer"]) {
    form.elements[name].disabled = !gymEnabled;
  }
  $$("#facility-option-list [data-facility-id]").forEach((row) => {
    const enabled = row.querySelector(".facility-enabled").checked;
    row.classList.toggle("is-selected", enabled);
    row.querySelector(".facility-count input").disabled = !enabled;
  });
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
  setFormValue(form, "townRadiusCells", normalizeTownCellCount(document.town_radius_cells));
  setFormValue(form, "townFootprintShape", normalizeTownFootprintShape(document.town_footprint_shape));
  setFormValue(form, "townLayoutShape", document.structure_profile?.layout_shape || "branching");
  setFormValue(form, "townRoadWidth", document.structure_profile?.road_profile?.width ?? 7);
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
  setFormValue(form, "specialBuildingStructure", specialBuilding.structure || "");
  const legacyGym = document.structure_profile?.facility_placements?.find((item) => item.id === "gym_building");
  const gym = document.structure_profile?.gym || {
    enabled: Boolean(legacyGym), structure: legacyGym?.structure || "",
    theme: document.structure_profile?.gym_theme || "normal", anchor: legacyGym?.anchor || "gym_building",
    leader_trainer_id: document.npc_placement?.trainer_slots?.find((slot) => slot.id === "gym_leader")?.trainer_id || ""
  };
  setFormValue(form, "gymEnabled", gym.enabled ?? false);
  setFormValue(form, "gymStructure", gym.structure || "");
  setFormValue(form, "gymTheme", gym.theme || "normal");
  setFormValue(form, "gymAnchor", gym.anchor || "gym_building");
  form.elements.gymLeaderTrainer.innerHTML = facilityTrainerOptions(gym.leader_trainer_id || "");
  setFormValue(form, "gymLeaderTrainer", gym.leader_trainer_id || "");
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
  if (state.settlement.town_footprint_shape === "custom") ensureCustomTownLayout();
  else { delete state.settlement.town_footprint_cells; delete state.settlement.town_road_exits; }
  delete state.settlement.biome;
  state.settlement.schema_version = 3;
  state.settlement.structure_profile ||= {};
  const starterPreset = isStarterSettlement();
  state.settlement.structure_profile.pokemon_center_enabled = starterPreset
    ? false : form.elements.pokemonCenterEnabled.checked;
  state.settlement.structure_profile.commercial_center = starterPreset
    ? "none" : form.elements.commercialFacility.value;
  state.settlement.structure_profile.civic_facilities_explicit = true;
  delete state.settlement.structure_profile.village_preset;
  delete state.settlement.structure_profile.starter_layout;
  delete state.settlement.structure_profile.house_style;
  const layoutShape = form.elements.townLayoutShape.value || "branching";
  const facilityRequirements = selectedFacilityRequirements();
  state.settlement.structure_profile.layout_shape = layoutShape;
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
  const configuredFacilities = facilityTemplatePlacements(
    [...selectedCivicFacilities(), ...facilityRequirements],
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
    state.settlement.structure_profile.generation_profile.building_density
  );
  const generatedFacilityPlots = generatedLayout.plots.filter((plot) => plot.kind === "facility");
  configuredFacilities.forEach((facility, index) => {
    const plot = generatedFacilityPlots[index];
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
  const gymAnchor = form.elements.gymAnchor.value.trim() || "gym_building";
  if (!state.settlement.anchors[gymAnchor]) {
    state.settlement.anchors[gymAnchor] = { ...(state.settlement.center || { x: 0, y: 64, z: 0 }) };
  }
  const gymLeader = form.elements.gymLeaderTrainer.value;
  state.settlement.structure_profile.gym = {
    enabled: gymEnabled,
    structure: form.elements.gymStructure.value.trim(),
    theme: form.elements.gymTheme.value,
    anchor: gymAnchor,
    leader_trainer_id: gymLeader
  };
  // Keep legacy fields synchronized while older data packs are still accepted.
  state.settlement.structure_profile.gym_theme = form.elements.gymTheme.value;
  delete state.settlement.structure_profile.gym_entrance_offset;
  const otherFacilities = (state.settlement.structure_profile.facility_placements || []).filter((item) => !["gym_building", "special_district_building"].includes(item.id) && item.mode !== "placeholder" && !item.id.startsWith("facility_"));
  if (gymEnabled) otherFacilities.push({ id: "gym_building", mode: "direct_template", structure: form.elements.gymStructure.value.trim(), anchor: gymAnchor });
  if (specialBuildingEnabled) otherFacilities.push({ id: "special_district_building", mode: "direct_template", structure: form.elements.specialBuildingStructure.value.trim(), anchor: specialAnchorId });
  otherFacilities.push(...configuredFacilities.map((item) => item.placement));
  state.settlement.structure_profile.facility_placements = otherFacilities;
  state.settlement.content_profile = {
    pokemon: {
      spawn_profile: form.elements.pokemonSpawnProfile.value.trim(),
      density_multiplier: number("pokemonDensity"),
      biome_set: form.elements.pokemonBiomeSet.value,
      unconditional_spawns: csvValues(form.elements.unconditionalSpawns.value)
    },
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
  if (gymEnabled && gymLeader) {
    const trainer = settlementTrainer(gymLeader);
    const origin = state.settlement.anchors[gymAnchor];
    const position = { x: origin.x + 2, y: origin.y + 3, z: origin.z + 10 };
    const leaderSlot = {
      id: "gym_leader", trainer_id: gymLeader, battle_type: trainer?.battle_type || "singles",
      members: [trainerSlotMember("primary", gymLeader, position)], spawn_policy: "persistent",
      tags: ["trainer", "gym_leader"]
    };
    syncTrainerSlotMembers(leaderSlot);
    state.settlement.npc_placement.trainer_slots.unshift(leaderSlot);
  }
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
  if (category === "battles" && !updateBattlePresetFromForm()) return false;
  const document = parseEditor(`#${singular}-json`);
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
  if (category === "battles" && !updateBattlePresetFromForm()) return;
  const document = parseEditor(`#${singular}-json`);
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
  else if (category === "battles") renderBattlePreset();
  else renderTrainer();
}

function openCreateDialog(category) {
  const form = $("#create-form");
  form.reset();
  form.elements.category.value = category;
  form.elements.generation.value = ["trainers", "battles"].includes(category) ? "generation_1" : `generation_${state.selectedGeneration}`;
  $("#create-title").textContent = category === "trainers" ? "새 NPC" : category === "battles" ? "새 배틀 프리셋" : category === "caves" ? "새 동굴" : "새 마을";
  $("#generation-field").hidden = ["trainers", "battles"].includes(category);
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
    toast(payload.category === "trainers" ? "새 NPC를 만들었습니다." : payload.category === "battles" ? "새 배틀 프리셋을 만들었습니다." : payload.category === "caves" ? "새 동굴을 만들었습니다." : "새 마을을 만들었습니다.");
  } finally {
    $("#create-submit").disabled = false;
  }
}

async function deleteManagedDocument(category) {
  const singular = documentSingular(category);
  const document = category === "battles" ? state.battlePreset : state[singular];
  const path = category === "battles" ? state.battlePath : state[`${singular}Path`];
  if (!document || !path) return;
  const labels = { trainers: "NPC", battles: "배틀 프리셋", caves: "동굴" };
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

function renderBuildCommands() {
  const descriptions = {
    validate: "모든 콘텐츠와 의존성 Lock을 빠르게 검사합니다.", test: "콘텐츠 관리와 패키징 회귀 테스트를 실행합니다.",
    "pack-smoke": "모드 없이 임포트 구조만 확인하는 ZIP을 만듭니다.", pack: "현재 설정으로 개발용 임포트 ZIP을 만듭니다.",
    "validate-pack": "실제 모드 파일과 버전이 모두 확정됐는지 검사합니다."
  };
  $("#build-command-list").innerHTML = state.buildCommands.map((command) => `
    <article class="build-command"><div><strong>${escapeHtml(command.id)}</strong><small>${escapeHtml(descriptions[command.id] || command.description)}</small></div><button class="button ${command.id.startsWith("pack") ? "primary" : "secondary"}" data-command="${escapeHtml(command.id)}">실행</button></article>`).join("");
  $$("[data-command]").forEach((button) => button.addEventListener("click", () => runBuild(button.dataset.command)));
}

async function runBuild(command) {
  const buttons = $$("[data-command]");
  buttons.forEach((button) => button.disabled = true);
  $("#build-state").textContent = `${command} 실행 중`;
  $("#build-output").textContent = "작업이 끝날 때까지 잠시 기다려 주세요…";
  try {
    const result = await request("/api/build", { method: "POST", body: JSON.stringify({ command }) });
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
  }
}

async function refreshAll() {
  try {
    await Promise.all([loadDashboard(), loadLists()]);
    const activeSection = $(".nav-item.is-active")?.dataset.section || "dashboard";
    await loadSectionData(activeSection, true);
    $("#server-dot").classList.add("online"); $("#server-label").textContent = "서버 연결됨";
  } catch (error) {
    $("#server-dot").classList.remove("online"); $("#server-label").textContent = "연결 실패"; toast(error.message);
  }
}

$$(".nav-item").forEach((button) => button.addEventListener("click", () => switchPage(button.dataset.section)));
$("#nbt-structure-search").addEventListener("input", (event) => { state.structureViewer.query = event.target.value; renderStructureBrowser(); });
$("#nbt-structure-list").addEventListener("click", (event) => {
  const button = event.target.closest("[data-nbt-structure]");
  if (button) loadStructureModel(button.dataset.nbtStructure);
});
$("#nbt-model-canvas").addEventListener("pointerdown", beginStructureDrag);
$("#nbt-model-canvas").addEventListener("pointermove", moveStructureDrag);
$("#nbt-model-canvas").addEventListener("pointerup", endStructureDrag);
$("#nbt-model-canvas").addEventListener("pointercancel", endStructureDrag);
$("#nbt-model-canvas").addEventListener("wheel", (event) => {
  event.preventDefault();
  state.structureViewer.zoom = Math.max(.45, Math.min(4, state.structureViewer.zoom * (event.deltaY < 0 ? 1.12 : .89)));
  renderStructureModel();
}, { passive: false });
$("#nbt-view-left").addEventListener("click", () => { state.structureViewer.yaw -= Math.PI / 8; renderStructureModel(); });
$("#nbt-view-right").addEventListener("click", () => { state.structureViewer.yaw += Math.PI / 8; renderStructureModel(); });
$("#nbt-view-reset").addEventListener("click", resetStructureView);
$("#refresh-button").addEventListener("click", refreshAll);
$("#validate-repository").addEventListener("click", loadDashboard);
$("#validate-trainer").addEventListener("click", () => validateDocument("trainers"));
$("#save-trainer").addEventListener("click", () => saveDocument("trainers"));
$("#delete-trainer").addEventListener("click", () => deleteManagedDocument("trainers"));
$("#validate-battle").addEventListener("click", () => validateDocument("battles"));
$("#save-battle").addEventListener("click", () => saveDocument("battles"));
$("#delete-battle").addEventListener("click", () => deleteManagedDocument("battles"));
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
$("#event-trigger-type").addEventListener("change", updateEventTrigger);
$("#event-trigger-range").addEventListener("change", updateEventTrigger);
$("#event-warning-offset").addEventListener("change", updateEventTrigger);
$("#event-command-list").addEventListener("input", handleEventCommandInput);
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
$("#cave-form").addEventListener("change", updateCaveFromForm);
$("#apply-cave-json").addEventListener("click", () => { const document = parseEditor("#cave-json"); if (document) { state.cave = document; renderCave(); toast("JSON을 동굴 폼에 반영했습니다."); } });
$("#delete-settlement").addEventListener("click", deleteSettlement);
$("#save-world-layout").addEventListener("click", saveWorldLayout);
$("#delete-world-layout").addEventListener("click", deleteWorldLayout);
$("#add-generation").addEventListener("click", addGeneration);
$("#tile-inspector-form").addEventListener("change", handleTileInspectorChange);
$("#clear-tile").addEventListener("click", clearSelectedTile);
$$('[data-pokemon-map-tab]').forEach((button) => button.addEventListener("click", () => { state.pokemonMapTab = button.dataset.pokemonMapTab; renderWorldPokemonPanel(); }));
$("#pokemon-map-search").addEventListener("input", (event) => { state.pokemonMapQuery = event.target.value.trim(); renderWorldPokemonPanel(); });
$("#finish-route").addEventListener("click", finishRouteConnection);
$("#cancel-route").addEventListener("click", cancelRouteConnection);
$("#undo-route-anchor").addEventListener("click", undoRouteAnchor);
$$('[data-map-tool]').forEach((button) => button.addEventListener("click", () => setActiveMapTool(button.dataset.mapTool)));
$("#cave-tool-cave").addEventListener("change", refreshCaveToolEntrances);
for (const [inputId, outputId] of [["biome-brush-radius", "biome-brush-radius-value"], ["empty-terrain-brush-radius", "empty-terrain-brush-radius-value"], ["climate-brush-radius", "climate-brush-radius-value"], ["eraser-radius", "eraser-radius-value"]]) {
  $(`#${inputId}`).addEventListener("input", (event) => { $(`#${outputId}`).textContent = event.target.value; renderHexMap(); });
}
$("#tile-radius-blocks").addEventListener("change", () => { state.worldLayout.grid.tile_radius_blocks = Number($("#tile-radius-blocks").value || 64); markWorldDirty(); });
$("#zoom-in").addEventListener("click", () => { state.mapZoom = Math.min(1.6, state.mapZoom + .1); renderHexMap(); });
$("#zoom-out").addEventListener("click", () => { state.mapZoom = Math.max(.65, state.mapZoom - .1); renderHexMap(); });
$("#fit-map").addEventListener("click", () => { fitMapToContent(); renderHexMap(); });
$("#world-hex-map").addEventListener("pointerdown", beginMapPan);
$("#world-hex-map").addEventListener("pointermove", moveMapPan);
$("#world-hex-map").addEventListener("pointerleave", () => { if (!state.paintStroke) clearBrushPreview(); });
$("#world-hex-map").addEventListener("pointerup", finishSettlementDrag);
$("#world-hex-map").addEventListener("pointerup", finishMapPan);
$("#world-hex-map").addEventListener("pointercancel", (event) => { state.draggedSettlement = null; state.mapPan = null; state.paintStroke = null; state.routeAnchorDrag = null; state.brushPreview = null; $("#world-hex-map").classList.remove("is-dragging", "is-panning"); finishMapPan(event); renderHexMap(); });
window.addEventListener("keydown", (event) => {
  if (event.code === "Space" && !/INPUT|SELECT|TEXTAREA/.test(event.target.tagName)) { state.spacePanActive = true; state.brushPreview = null; $("#world-hex-map").classList.add("is-space-panning"); renderHexMap(); event.preventDefault(); return; }
  if (/INPUT|SELECT|TEXTAREA/.test(event.target.tagName) || event.ctrlKey || event.metaKey || event.altKey) return;
  const tool = ({ v: "select", b: "biome", t: "terrain", c: "climate", r: "route", s: "settlement", d: "cave", o: "object", e: "eraser" })[event.key.toLowerCase()];
  if (tool) { setActiveMapTool(tool); event.preventDefault(); }
});
window.addEventListener("keyup", (event) => { if (event.code === "Space") { state.spacePanActive = false; $("#world-hex-map").classList.remove("is-space-panning"); } });
window.addEventListener("resize", () => { resizeWorldMapWorkspace(); renderStructureModel(); });
$("#settlement-form").addEventListener("input", (event) => { keepHousePaletteGroupSelected(event); updateFacilityFormState(); updateSettlementFromForm(); });
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

refreshAll();
