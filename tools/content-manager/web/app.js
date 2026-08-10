import {
  createPartyClipboardEntry,
  parsePartyClipboardText,
  readClipboardText,
  toContentManagerParty,
  writeClipboardText,
} from "/pokemon-entry-clipboard.mjs";

const state = {
  trainers: [], settlements: [], trainer: null, settlement: null,
  trainerPath: "", settlementPath: "", buildCommands: [], trainerClasses: [], trainerRoster: { organizations: [], league_characters: [] },
  selectedPokemonIndex: 0, editorCatalog: null, choice: null,
  biomeCatalog: { profiles: [], sets: [] }, pokemonHabitats: [], selectedBiomeProfile: null,
  worldLayout: null, worldGenerations: [1], selectedGeneration: 1,
  selectedHex: null, mapRadius: 6, mapZoom: 1, mapCenter: { x: 490, y: 330 }, mapViewInitialized: false,
  mapPan: null, suppressMapClick: false, draggedSettlement: null, routeDraft: null, worldDirty: false,
  emptyTerrainBrushActive: false
};
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
  { id: "battle_tower", label: "배틀타워", note: "전투 랜드마크", width: 48, depth: 48, height: 32, color: "#9d4edd" },
  { id: "radio_tower", label: "라디오 타워", note: "방송국과 송신탑", width: 48, depth: 48, height: 32, color: "#4361ee" },
  { id: "train_station", label: "기차역", note: "역사와 선로 예약부지", width: 48, depth: 64, height: 14, color: "#495057" },
  { id: "gym_site", label: "체육관 부지", note: "향후 체육관 예약", width: 64, depth: 64, height: 12, color: "#ef233c" }
];
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
  const titles = { dashboard: "프로젝트 현황", trainers: "트레이너 데이터", worlds: "세대별 월드맵", settlements: "마을 프리셋", biomes: "바이옴 관리", builds: "빌드 및 검사" };
  $("#page-title").textContent = titles[section];
  if (section === "worlds") requestAnimationFrame(resizeWorldMapWorkspace);
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
  const [trainers, settlements, trainerClasses, trainerRoster, editorCatalog, biomeCatalog, pokemonHabitats, worldLayouts, worldLayout] = await Promise.all([
    request("/api/trainers"), request("/api/settlements"), request("/api/trainer-classes"),
    request("/api/trainer-roster"),
    request("/api/editor-catalog"), request("/api/biome-catalog"), request("/api/pokemon-habitats"),
    request("/api/world-layouts"), request(`/api/world-layout?generation=${state.selectedGeneration}`)
  ]);
  state.trainers = trainers.data.items || [];
  state.settlements = settlements.data.items || [];
  state.trainerClasses = trainerClasses.data.classes || [];
  state.trainerRoster = trainerRoster.ok ? trainerRoster.data : { organizations: [], league_characters: [] };
  state.editorCatalog = editorCatalog.ok ? editorCatalog.data : null;
  state.biomeCatalog = biomeCatalog.ok ? biomeCatalog.data : { profiles: [], sets: [] };
  state.pokemonHabitats = pokemonHabitats.ok ? pokemonHabitats.data.pokemon || [] : [];
  state.worldGenerations = worldLayouts.ok ? worldLayouts.data.generations || [1] : [1];
  state.worldLayout = worldLayout.ok ? worldLayout.data : null;
  if (!editorCatalog.ok) toast(editorCatalog.data.error || "전투 데이터 카탈로그를 불러오지 못했습니다.");
  if (!biomeCatalog.ok || !pokemonHabitats.ok) {
    const message = biomeCatalog.status === 404 || pokemonHabitats.status === 404
      ? "바이옴 API가 없는 이전 서버가 실행 중입니다. build.bat web을 다시 시작해 주세요."
      : (biomeCatalog.data.error || pokemonHabitats.data.error || "바이옴 데이터를 불러오지 못했습니다.");
    toast(message);
    $("#biome-issues").className = "issues";
    $("#biome-issues").textContent = message;
  }
  renderList("trainers");
  renderList("settlements");
  renderWorldLayout();
  renderBiomeManager();
}

function settlementSummary(settlementId) {
  return state.settlements.find((item) => item.id === settlementId);
}
function settlementPresetBiome(settlementId) { return settlementSummary(settlementId)?.biome || "minecraft:plains"; }
function settlementPresetRadius(settlementId) { return settlementSummary(settlementId)?.town_radius_cells ?? 1; }

function worldSettlementOptions(selected) {
  const token = `generation_${state.selectedGeneration}/`;
  const candidates = state.settlements.filter((item) => item.path?.replaceAll("\\", "/").includes(token));
  return '<option value="">마을 선택</option>' + candidates.map((item) => `<option value="${escapeHtml(item.id)}" ${item.id === selected ? "selected" : ""}>${escapeHtml(item.name || item.id)}</option>`).join("");
}

function renderWorldLayout() {
  const layout = state.worldLayout;
  if (!layout) {
    $("#world-hex-map").innerHTML = "";
    return;
  }
  layout.tiles ||= [];
  layout.settlements ||= [];
  layout.connections ||= [];
  layout.objects ||= [];
  layout.empty_terrain ||= { default_type: "high_forest", tiles: [] };
  layout.empty_terrain.default_type ||= "high_forest";
  layout.empty_terrain.tiles ||= [];
  const occupiedExtent = [...layout.tiles, ...layout.empty_terrain.tiles, ...layout.settlements.map((node) => node.anchor || { q: 0, r: 0 }), ...layout.objects.map((node) => node.anchor || { q: 0, r: 0 })].reduce((largest, cell) => Math.max(largest, Math.abs(cell.q || 0), Math.abs(cell.r || 0), Math.abs((cell.q || 0) + (cell.r || 0))), 0);
  state.mapRadius = Math.max(Number(layout.grid?.map_radius_cells || state.mapRadius), Math.min(14, occupiedExtent + 1));
  renderGenerationTabs();
  renderRouteCreator();
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
  const result = await request(`/api/world-layout?generation=${generation}`);
  if (!result.ok) { toast(result.data.error || "세대 지도를 불러오지 못했습니다."); return; }
  state.selectedGeneration = generation;
  state.worldLayout = result.data;
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
function settlementAt(q, r) { return state.worldLayout?.settlements?.find((node) => node.anchor?.q === q && node.anchor?.r === r); }
function objectAt(q, r) { return state.worldLayout?.objects?.find((node) => node.anchor?.q === q && node.anchor?.r === r); }
function settlementFootprintAt(q, r) { return state.worldLayout?.settlements?.find((node) => node.anchor && hexDistance(node.anchor, { q, r }) <= settlementPresetRadius(node.settlement)); }
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
  return townArea ? settlementPresetBiome(townArea.settlement) : null;
}

function renderHexMap() {
  const svg = $("#world-hex-map");
  const view = mapViewBox(); const cells = visibleHexCells();
  svg.setAttribute("viewBox", `${view.x} ${view.y} ${view.width} ${view.height}`);
  const tiles = cells.map(({ q, r }) => {
    const { x, y } = hexPoint(q, r); const tile = tileAt(q, r); const town = settlementAt(q, r); const townArea = settlementFootprintAt(q, r);
    const route = townArea ? null : primaryRouteAt(q, r);
    const selected = state.selectedHex?.q === q && state.selectedHex?.r === r;
    const emptyType = emptyTerrainAt(q, r);
    const tone = townArea ? biomeTone(settlementPresetBiome(townArea.settlement)) : tile ? biomeTone(tile.biome) : emptyTerrainTone(emptyType);
    const baseLabel = town ? settlementSummary(town.settlement)?.name || "마을" : townArea ? `${settlementSummary(townArea.settlement)?.name || "마을"} 사용 범위` : tile ? tile.biome : emptyTerrainLabel(emptyType);
    const label = route ? `${baseLabel}, 길 ${route.id}` : baseLabel;
    return `<g class="hex-cell ${selected ? "is-selected" : ""} ${route ? "is-route-terrain" : ""} ${!townArea && !tile ? "is-empty-terrain" : ""} tone-${tone}" data-hex-q="${q}" data-hex-r="${r}" tabindex="0" role="button" aria-label="Q ${q}, R ${r}, ${escapeHtml(label)}"><polygon points="${hexPolygon(x, y)}"></polygon>${tile && !townArea ? `<circle class="biome-pin" cx="${x}" cy="${y}" r="3"></circle>` : ""}</g>`;
  }).join("");
  const townAreas = cells.map(({ q, r }) => {
    const owner = settlementFootprintAt(q, r); if (!owner) return "";
    const { x, y } = hexPoint(q, r); const name = settlementSummary(owner.settlement)?.name || owner.settlement;
    return `<g class="hex-town-area${owner.anchor.q === q && owner.anchor.r === r ? " is-anchor" : ""}"><polygon points="${hexPolygon(x, y, mapHexSize() - 4)}"></polygon><title>${escapeHtml(name)} · 프리셋 사용 범위 ${settlementPresetRadius(owner.settlement)}</title></g>`;
  }).join("");
  const routes = (state.worldLayout.connections || []).map((connection) => {
    const points = connectionPath(connection).map((cell) => { const point = hexPoint(cell.q, cell.r); return `${point.x},${point.y}`; }).join(" ");
    if (!points) return "";
    const routeClass = connection.surface_style === "water" ? "water" : connection.access_requirement?.endsWith("/rock_climb") ? "climb" : "road";
    return `<polyline class="hex-route ${routeClass}" points="${points}"><title>${escapeHtml(connection.id)}</title></polyline>`;
  }).join("");
  const draftRoute = state.routeDraft?.cells?.length ? (() => {
    const points = state.routeDraft.cells.map((cell) => { const point = hexPoint(cell.q, cell.r); return `${point.x},${point.y}`; }).join(" ");
    return `<polyline class="hex-route draft" points="${points}"></polyline>`;
  })() : "";
  const towns = (state.worldLayout.settlements || []).map((node) => {
    const { x, y } = hexPoint(node.anchor.q, node.anchor.r); const name = settlementSummary(node.settlement)?.name || node.settlement.split("/").pop();
    return `<g class="hex-settlement" data-drag-settlement="${escapeHtml(node.settlement)}" transform="translate(${x} ${y})" role="button" aria-label="${escapeHtml(name)} 이동"><circle r="18"></circle><path d="M-7 5V-4L0-10L7-4V5H2V0H-2V5Z"></path><text y="31">${escapeHtml(name)}</text></g>`;
  }).join("");
  const objects = (state.worldLayout.objects || []).map((node) => {
    const { x, y } = hexPoint(node.anchor.q, node.anchor.r);
    return `<g class="hex-custom-object" transform="translate(${x} ${y})"><rect x="-9" y="-9" width="18" height="18" rx="3"></rect><text y="25">${escapeHtml(node.id)}</text></g>`;
  }).join("");
  svg.innerHTML = `<g class="hex-map-layer">${tiles}${townAreas}${routes}${draftRoute}${towns}${objects}</g>`;
  const routeCellCount = new Set((state.worldLayout.connections || []).flatMap((connection) => connectionPath(connection).map((cell) => `${cell.q},${cell.r}`))).size;
  $("#map-tile-count").textContent = `${cells.length}개 표시 · 바이옴 ${(state.worldLayout.tiles || []).length}개 · 길 오버레이 ${routeCellCount}칸 · 빈 지형 지정 ${(state.worldLayout.empty_terrain?.tiles || []).length}개 · 마을 ${(state.worldLayout.settlements || []).length}곳 · 오브젝트 ${(state.worldLayout.objects || []).length}개`;
  $("#map-zoom").textContent = `${Math.round(state.mapZoom * 100)}%`;
  $$("[data-hex-q]").forEach((cell) => {
    const select = () => { if (!state.suppressMapClick) handleHexSelection(Number(cell.dataset.hexQ), Number(cell.dataset.hexR)); };
    cell.addEventListener("click", select);
    cell.addEventListener("keydown", (event) => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); select(); } });
  });
  $$("[data-drag-settlement]").forEach((marker) => marker.addEventListener("pointerdown", (event) => beginSettlementDrag(event, marker.dataset.dragSettlement)));
}

async function saveWorldLayout() {
  if (!state.worldLayout) return;
  for (const node of state.worldLayout.settlements || []) {
    node.town_biome = settlementPresetBiome(node.settlement);
    node.town_radius_cells = settlementPresetRadius(node.settlement);
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
  renderWorldLayout();
}

function selectHex(q, r) { state.selectedHex = { q, r }; renderHexMap(); renderTileInspector(); }
function handleHexSelection(q, r) {
  if (state.emptyTerrainBrushActive) paintEmptyTerrainArea(q, r);
  else if (state.routeDraft) appendRouteDraft(q, r);
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
  if (!selected) { $("#selected-tile-title").textContent = "타일을 선택하세요"; $("#selected-tile-coord").textContent = "Q — · R —"; return; }
  const tile = tileAt(selected.q, selected.r); const town = settlementAt(selected.q, selected.r); const customObject = objectAt(selected.q, selected.r); const townArea = settlementFootprintAt(selected.q, selected.r);
  const routes = routesAt(selected.q, selected.r);
  const kind = customObject ? "object" : town ? "settlement" : tile ? "biome" : "empty";
  $("#selected-tile-title").textContent = customObject ? customObject.id : town ? (settlementSummary(town.settlement)?.name || "마을 타일") : tile ? tile.biome.replace("minecraft:", "") : emptyTerrainLabel(emptyTerrainAt(selected.q, selected.r));
  $("#selected-tile-coord").textContent = `Q ${selected.q} · R ${selected.r}`;
  form.elements.kind.value = kind;
  form.elements.biome.innerHTML = worldBiomeOptions(tile?.biome || "minecraft:plains");
  form.elements.emptyTerrainType.value = emptyTerrainAt(selected.q, selected.r);
  form.elements.settlement.innerHTML = worldSettlementOptions(town?.settlement || "");
  form.elements.objectId.value = customObject?.id || "";
  form.elements.objectType.value = customObject?.type || "landmark";
  form.elements.objectResource.value = customObject?.resource || "";
  $$('[data-tile-field]').forEach((field) => field.hidden = field.dataset.tileField !== kind);
  const routePanel = $("#route-overlay-panel");
  routePanel.hidden = !routes.length;
  $("#route-overlay-list").innerHTML = routes.map((route) => `<div class="route-overlay-item"><span>${escapeHtml(route.id)} · ${escapeHtml(route.surface_style)}</span><button type="button" data-remove-route="${escapeHtml(route.id)}">연결 삭제</button></div>`).join("");
  $$('[data-remove-route]').forEach((button) => button.addEventListener("click", () => removeRouteConnection(button.dataset.removeRoute)));
  const routeNote = routes.length ? `<small>길 오버레이: ${routes.map((route) => escapeHtml(route.id)).join(", ")} · 기본 바이옴은 별도로 유지됩니다.</small>` : "";
  const townAreaNote = townArea && !town ? `<small class="town-area-warning">실제 생성: ${escapeHtml(settlementSummary(townArea.settlement)?.name || townArea.settlement)} 사용 범위 · 이 타일의 바이옴 배치는 무시됩니다.</small>` : "";
  $("#tile-summary").innerHTML = (kind === "object" ? `<b>${escapeHtml(customObject.id)}</b><span>${escapeHtml(customObject.type)} 오브젝트</span><small>바이옴과 길 위에 독립적으로 배치되는 확장용 메타데이터입니다.</small>` : kind === "settlement" ? `<b>마을 중심 타일</b><span>${escapeHtml(town.settlement)}</span><small>프리셋 사용 반경 ${settlementPresetRadius(town.settlement)} · 마커를 드래그해 이동</small>` : kind === "biome" ? `<b>${escapeHtml(tile.biome)}</b><span>직접 배치된 기본 바이옴</span><small>길 유무와 관계없이 월드 지형에 적용됩니다.</small>` : `<b>${escapeHtml(emptyTerrainLabel(emptyTerrainAt(selected.q, selected.r)))}</b><span>접근 불가 배경 지형</span><small>바이옴과 길은 각각 별도로 배치할 수 있습니다.</small>`) + townAreaNote + routeNote;
}

function renderRouteCreator() {
  const active = Boolean(state.routeDraft);
  $("#create-route").hidden = active;
  $("#finish-route").hidden = !active;
  $("#cancel-route").hidden = !active;
  $("#route-surface").disabled = active;
  $("#route-editor-help").textContent = active ? `${state.routeDraft.cells.length}칸 지정됨 · 다음 셀을 누르거나 길 완료를 선택하세요.` : "길은 바이옴·마을과 별개입니다. 새 길 시작 후 지도 셀을 순서대로 누르세요.";
}

function nextRouteId() {
  const used = new Set((state.worldLayout?.connections || []).map((connection) => connection.id));
  let index = 1;
  while (used.has(`route_custom_${String(index).padStart(2, "0")}`)) index++;
  return `route_custom_${String(index).padStart(2, "0")}`;
}

function createRouteConnection() {
  state.routeDraft = { id: nextRouteId(), surface_style: $("#route-surface").value, cells: [] };
  state.emptyTerrainBrushActive = false;
  $("#toggle-empty-terrain-brush").setAttribute("aria-pressed", "false");
  renderWorldLayout();
}
function appendRouteDraft(q, r) {
  const draft = state.routeDraft; if (!draft) return;
  const last = draft.cells.at(-1);
  const segment = last ? straightHexPath(last, { q, r }).slice(1) : [{ q, r }];
  for (const cell of segment) {
    if (!draft.cells.some((entry) => entry.q === cell.q && entry.r === cell.r)) draft.cells.push(cell);
  }
  state.selectedHex = { q, r }; renderWorldLayout();
}
function finishRouteConnection() {
  const draft = state.routeDraft;
  if (!draft?.cells.length) { toast("지도에서 길 셀을 하나 이상 선택해 주세요."); return; }
  state.worldLayout.connections.push({ id: draft.id, cells: draft.cells, corridor_width_blocks: 12, surface_style: draft.surface_style });
  state.routeDraft = null; markWorldDirty(); renderWorldLayout(); toast("바이옴과 독립된 길을 추가했습니다.");
}
function cancelRouteConnection() {
  state.routeDraft = null; renderWorldLayout();
}

function removeRouteConnection(routeId) {
  state.worldLayout.connections = (state.worldLayout.connections || []).filter((connection) => connection.id !== routeId);
  markWorldDirty(); renderWorldLayout(); toast(`${routeId} 연결을 삭제했습니다.`);
}

function defaultWorldTile(q, r, biome) { return { q, r, biome, boundary_profile: "cobbleventure:boundary/earthwork", terrain_profile: { base_height_offset: 0, height_variation: 3, noise_scale_blocks: 96 } }; }
function defaultWorldSettlement(id, q, r) { return { settlement: id, anchor: { q, r }, town_radius_cells: 1, town_biome: "minecraft:plains", surroundings: [], boundary_profile: "cobbleventure:boundary/stone_wall", terrain_profile: { base_height_offset: 0, height_variation: 3, noise_scale_blocks: 96 } }; }
function settlementRangeConflict(node, q, r, radius) {
  return state.worldLayout.settlements.find((entry) => entry !== node && entry.anchor && hexDistance(entry.anchor, { q, r }) <= radius + settlementPresetRadius(entry.settlement));
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
    if (!node) { node = defaultWorldSettlement(id, q, r); state.worldLayout.settlements.push(node); }
    else node.anchor = { q, r };
    node.town_radius_cells = townRadius;
    node.town_biome = settlementPresetBiome(id);
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

function beginSettlementDrag(event, settlementId) {
  event.preventDefault(); event.stopPropagation();
  state.draggedSettlement = settlementId;
  $("#world-hex-map").setPointerCapture?.(event.pointerId);
  $("#world-hex-map").classList.add("is-dragging");
}
function nearestHexFromPointer(event) {
  const svg = $("#world-hex-map"); const point = svg.createSVGPoint(); point.x = event.clientX; point.y = event.clientY;
  const local = point.matrixTransform(svg.getScreenCTM().inverse());
  return pixelToHex(local.x, local.y);
}
function beginMapPan(event) {
  if (event.button !== 0 || event.target.closest?.("[data-drag-settlement]")) return;
  const svg = $("#world-hex-map");
  state.mapPan = { pointerId: event.pointerId, startX: event.clientX, startY: event.clientY, centerX: state.mapCenter.x, centerY: state.mapCenter.y, lastRenderX: state.mapCenter.x, lastRenderY: state.mapCenter.y, moved: false };
}
function moveMapPan(event) {
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
  const payload = { "$schema": "../schemas/hex-world.schema.json", schema_version: 2, id: `cobbleventure:world/generation_${generation}`, dimension: `cobbleventure:generation_${generation}`, seed_salt: 1700 + generation, grid: { orientation: "pointy_top", tile_radius_blocks: 64, map_radius_cells: 6, origin: { x: 0, y: 69, z: 0 } }, empty_terrain: { default_type: "high_forest", tiles: [] }, tiles: [], settlements: [], connections: [], objects: [] };
  const result = await request(`/api/world-layout?generation=${generation}`, { method: "PUT", body: JSON.stringify(payload) });
  if (!result.ok) { toast(result.data.error || "세대를 추가하지 못했습니다."); return; }
  state.worldGenerations.push(generation); state.worldGenerations.sort((a, b) => a - b); state.selectedGeneration = generation; state.worldLayout = payload; state.selectedHex = null; state.worldDirty = false; state.mapViewInitialized = false; renderWorldLayout(); toast(`${generation}세대 월드를 추가했습니다.`);
}

function renderList(category) {
  const items = state[category];
  const singular = category === "trainers" ? "trainer" : "settlement";
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
  const singular = category === "trainers" ? "trainer" : "settlement";
  const result = await request(`/api/${category}?path=${encodeURIComponent(path)}`);
  if (!result.ok) { toast(result.data.error || "문서를 불러오지 못했습니다."); return; }
  state[singular] = result.data.document;
  state[`${singular}Path`] = result.data.path;
  renderList(category);
  if (category === "trainers") renderTrainer(); else renderSettlement();
}

function renderTrainer() {
  const document = state.trainer;
  const form = $("#trainer-form");
  const ai = normalizeTrainerAi(document.battle);
  document.schema_version = 2;
  document.battle.ai = ai;
  delete document.battle.difficulty;
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
  setFormValue(form, "lookAtPlayer", document.npc?.behavior?.look_at_player);
  setFormValue(form, "invulnerable", document.npc?.behavior?.invulnerable);
  setFormValue(form, "battleFormat", document.battle?.format);
  setFormValue(form, "battleType", document.battle?.battle_type);
  setFormValue(form, "battleDifficulty", ai.difficulty);
  setFormValue(form, "battleAi", ai.strategy);
  setFormValue(form, "cheatProbability", Math.round((ai.options?.cheat_probability ?? 0.5) * 100));
  renderCheatProbability(form);
  setFormValue(form, "levelMode", document.battle?.level_mode);
  setFormValue(form, "megaEvolution", document.battle?.mechanics?.mega_evolution);
  setFormValue(form, "zMove", document.battle?.mechanics?.z_move);
  setFormValue(form, "dynamax", document.battle?.mechanics?.dynamax);
  setFormValue(form, "terastallization", document.battle?.mechanics?.terastallization);
  $("#max-item-uses").value = Number.isInteger(document.battle?.rules?.max_item_uses)
    ? document.battle.rules.max_item_uses
    : "";
  [...form.elements].forEach((element) => element.disabled = false);
  $("#max-item-uses").disabled = false;
  renderTrainerPreview();
  renderBag();
  renderTeam();
  $("#trainer-json").value = JSON.stringify(document, null, 2);
  ["#trainer-json", "#apply-trainer-json", "#add-bag-item", "#add-pokemon", "#copy-team-json", "#paste-team-json", "#validate-trainer", "#save-trainer"].forEach((selector) => $(selector).disabled = false);
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
  Object.assign(state.trainer.npc.behavior, {
    movement: form.elements.movement.value,
    interaction_range: Number(form.elements.interactionRange.value),
    look_at_player: form.elements.lookAtPlayer.checked,
    invulnerable: form.elements.invulnerable.checked
  });
  const difficulty = form.elements.battleDifficulty.value;
  const aiOptions = difficulty === "cheater"
    ? { cheat_probability: Math.max(0, Math.min(1, Number(form.elements.cheatProbability.value) / 100)) }
    : {};
  Object.assign(state.trainer.battle, {
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
  state.trainer.battle.rules ||= {};
  const maxItemUses = $("#max-item-uses").value.trim();
  if (maxItemUses === "") delete state.trainer.battle.rules.max_item_uses;
  else state.trainer.battle.rules.max_item_uses = Math.max(0, Number.parseInt(maxItemUses, 10) || 0);
  Object.assign(state.trainer.battle.mechanics, {
    mega_evolution: form.elements.megaEvolution.checked,
    z_move: form.elements.zMove.checked,
    dynamax: form.elements.dynamax.checked,
    terastallization: form.elements.terastallization.checked
  });
  for (const pokemon of state.trainer.battle.team || []) {
    if (!pokemon.gimmick?.type) continue;
    state.trainer.battle.mechanics[pokemon.gimmick.type] = true;
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

function bagItemCatalogEntry(itemId) {
  return (state.editorCatalog?.bagItems || []).find((entry) => entry.id === itemId) || null;
}

function renderBag() {
  const list = $("#bag-list");
  if (!state.trainer?.battle) {
    list.innerHTML = '<div class="issues empty">트레이너를 선택하면 가방이 표시됩니다.</div>';
    return;
  }
  state.trainer.battle.bag ||= [];
  const bag = state.trainer.battle.bag;
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
    const entry = state.trainer.battle.bag[Number(input.dataset.bagQuantity)];
    if (!entry) return;
    entry.quantity = Math.max(1, Number.parseInt(input.value, 10) || 1);
    syncTrainerJson();
  }));
  $$('[data-remove-bag-item]').forEach((button) => button.addEventListener("click", () => {
    state.trainer.battle.bag.splice(Number(button.dataset.removeBagItem), 1);
    renderBag();
    syncTrainerJson();
  }));
}

function addBagItem() {
  if (!state.trainer?.battle) return;
  state.trainer.battle.bag ||= [];
  const fallback = state.editorCatalog?.bagItems?.find((entry) => entry.shortId === "potion")
    || state.editorCatalog?.bagItems?.[0];
  state.trainer.battle.bag.push({ item: fallback?.id || "cobblemon:potion", quantity: 1 });
  renderBag();
  syncTrainerJson();
  openChoiceDialog("bag_item", null, state.trainer.battle.bag.length - 1);
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
  return state.trainer?.battle?.team?.[state.selectedPokemonIndex] || null;
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
  const team = state.trainer?.battle?.team || [];
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
    const mechanicInput = $("#trainer-form").elements[type === "mega_evolution" ? "megaEvolution" : "zMove"];
    mechanicInput.checked = true;
    state.trainer.battle.mechanics[type] = true;
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
  if (!state.trainer) return;
  if (state.trainer.battle.team.length >= 6) { toast("팀은 최대 6마리까지 구성할 수 있습니다."); return; }
  state.trainer.battle.team.push(pokemonTemplate());
  state.selectedPokemonIndex = state.trainer.battle.team.length - 1;
  renderTeam();
  syncTrainerJson();
}

function removePokemon(index) {
  if (state.trainer.battle.team.length <= 1) { toast("팀에는 포켓몬이 한 마리 이상 필요합니다."); return; }
  state.trainer.battle.team.splice(index, 1);
  state.selectedPokemonIndex = Math.min(index, state.trainer.battle.team.length - 1);
  renderTeam();
  syncTrainerJson();
}

function selectPokemon(index) {
  updateFocusedPokemon();
  state.selectedPokemonIndex = index;
  renderTeam();
}

function moveSelectedPokemon(offset) {
  const team = state.trainer?.battle?.team;
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
  const team = state.trainer.battle.team;
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
  if (!state.trainer?.battle?.team?.length) return;
  updateFocusedPokemon();
  try {
    const entry = createPartyClipboardEntry(
      state.trainer.battle.team,
      clipboardCatalogOptions(),
    );
    await writeClipboardText(JSON.stringify(entry, null, 2));
    toast(`포켓몬 ${entry.pokemon.length}마리의 엔트리 JSON을 복사했습니다.`);
  } catch (error) {
    toast(error.message);
  }
}

async function pasteTeamJson() {
  if (!state.trainer?.battle) return;
  try {
    const entry = parsePartyClipboardText(
      await readClipboardText(),
      clipboardCatalogOptions(),
    );
    const team = toContentManagerParty(entry, clipboardCatalogOptions());
    state.trainer.battle.team = team;
    state.trainer.battle.mechanics ||= {};
    for (const pokemon of team) {
      if (pokemon.gimmick?.type) state.trainer.battle.mechanics[pokemon.gimmick.type] = true;
      if (pokemon.gigantamax_factor) state.trainer.battle.mechanics.dynamax = true;
    }
    state.selectedPokemonIndex = 0;
    renderTrainer();
    toast(`클립보드에서 포켓몬 ${team.length}마리를 붙여넣었습니다.`);
  } catch (error) {
    toast(error.message);
  }
}

function updateFocusedPokemon(event = null) {
  const editor = $(".focused-pokemon-editor");
  if (!editor || !state.trainer?.battle?.team?.[state.selectedPokemonIndex]) return;
  const pokemon = state.trainer.battle.team[state.selectedPokemonIndex];
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
  if (!state.editorCatalog || (kind !== "bag_item" && !currentPokemon())) {
    toast("전투 데이터 카탈로그를 아직 불러오지 못했습니다.");
    return;
  }
  if (kind !== "bag_item") updateFocusedPokemon();
  const [natureUp, natureDown] = natureSelection(currentPokemon()?.nature);
  const initialScope = kind === "pokemon" ? "all" : kind === "item" ? "battle" : "recommended";
  state.choice = { kind, moveIndex, bagIndex, query: "", type: "", category: "", scope: kind === "bag_item" ? "all" : initialScope, generation: "", natureUp, natureDown };
  const titles = {
    pokemon: ["포켓몬 선택", "기본 모습, 지역 폼과 특수 형태를 함께 검색합니다."],
    nature: ["성격 선택", "올릴 능력치와 내릴 능력치를 고르면 실제 성격으로 자동 연결됩니다."],
    ability: ["특성 선택", "현재 포켓몬이 사용할 수 있는 특성을 우선 표시합니다."],
    item: ["지닌 도구 선택", "배틀에서 사용할 수 있는 도구를 종류와 출처별로 찾습니다."],
    bag_item: ["가방 아이템 선택", "트레이너가 전투 중 사용할 회복·상태회복·능력치 아이템을 찾습니다."],
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
  } else {
    rows = (state.editorCatalog.items || []).filter((entry) => !["mega", "z"].includes(entry.category) && matches(entry.id, entry.shortId, entry.name, entry.englishName, entry.description) && (choice.scope === "all" || (choice.scope === "battle" && entry.battleUsable) || entry.category === choice.scope) && (!choice.category || entry.namespace === choice.category));
  }
  $("#choice-count").textContent = `검색 결과 ${rows.length}개 · 전체 표시`;
  $("#choice-results").className = "choice-results";
  const optionalCard = optionalChoiceCard(choice.kind);
  const resultCards = rows.map((entry) => choiceCard(choice.kind, entry, selectedSpecies)).join("");
  $("#choice-results").innerHTML = optionalCard + (resultCards || '<div class="choice-empty">조건에 맞는 항목이 없습니다.</div>');
  bindChoiceResultButtons();
  if (choice.kind === "pokemon") hydrateChoicePokemonArt(rows);
}

function optionalChoiceCard(kind) {
  const labels = { nature: ["자동 결정", "성격을 지정하지 않습니다."], ability: ["자동 특성", "종과 폼의 기본 규칙에 맡깁니다."], item: ["지닌 도구 없음", "이 포켓몬의 지닌 도구를 비웁니다."] };
  const option = labels[kind];
  return option ? `<button type="button" class="choice-card optional-choice-card" data-choice-value=""><span class="choice-card-title"><strong>${option[0]}</strong><small>OPTIONAL</small></span><p>${option[1]}</p></button>` : "";
}

function choiceCard(kind, entry, selectedSpecies) {
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

function chooseDialogValue(value) {
  const choice = state.choice;
  if (choice?.kind === "bag_item") {
    const entry = state.trainer?.battle?.bag?.[choice.bagIndex];
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
  const pokemon = state.trainer?.battle?.team?.[state.selectedPokemonIndex];
  if (!pokemon) return;
  const image = $("#focused-pokemon-art");
  const fallback = $("#pokemon-art-fallback");
  applyPokemonArtwork(image, fallback, pokemon);
}

function hydratePartyArt() {
  (state.trainer?.battle?.team || []).forEach((pokemon, index) => {
    const image = document.querySelector(`[data-party-art="${index}"]`);
    const fallback = document.querySelector(`[data-party-fallback="${index}"]`);
    applyPokemonArtwork(image, fallback, pokemon);
  });
}

function syncTrainerJson() {
  $("#trainer-json").value = JSON.stringify(state.trainer, null, 2);
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
  const requirements = Array.isArray(state.settlement?.structure_profile?.facility_requirements)
    ? state.settlement.structure_profile.facility_requirements
    : [];
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
    return [{
      id: facility.id, label: facility.label, count, required: true,
      footprint: { width: facility.width, depth: facility.depth, height: facility.height }
    }];
  });
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
    footprint: { width: facility.width, depth: facility.depth, height: facility.height }
  }));
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

function simulateJigsawVillage(seed, depth, shape, roadWidth, requirements) {
  const random = villagePreviewRandom(seed);
  const directions = [{ x: 0, z: -1 }, { x: 1, z: 0 }, { x: 0, z: 1 }, { x: -1, z: 0 }];
  const initialDirections = {
    linear: [1, 3], radial: [0, 1, 2, 3], loop: [0, 1, 2, 3], terraced: [1, 3, 2], branching: [0, 2]
  }[shape] || [0, 2];
  const queue = initialDirections.map((direction) => ({ x: 0, z: 0, direction, depth: 0 }));
  const occupiedRoad = new Set(["0,0"]);
  const roads = [];
  const maximumRoads = Math.min(56, 5 + depth * 8);
  let rejectedRoads = 0;

  while (queue.length && roads.length < maximumRoads) {
    const connector = queue.shift();
    const vector = directions[connector.direction];
    const cells = 3 + Math.floor(random() * 4);
    const points = [];
    let blocked = false;
    for (let step = 1; step <= cells; step += 1) {
      const cellX = Math.round(connector.x / 16) + vector.x * step;
      const cellZ = Math.round(connector.z / 16) + vector.z * step;
      const key = `${cellX},${cellZ}`;
      if (occupiedRoad.has(key) && step > 1) { blocked = true; break; }
      points.push({ key, x: cellX * 16, z: cellZ * 16 });
    }
    if (blocked || points.length < 2) { rejectedRoads += 1; continue; }
    for (const point of points) occupiedRoad.add(point.key);
    const end = points[points.length - 1];
    roads.push({ x1: connector.x, z1: connector.z, x2: end.x, z2: end.z, depth: connector.depth });
    if (connector.depth + 1 >= depth) continue;

    const nextDirections = [connector.direction];
    const branchChance = shape === "linear" ? .12 : shape === "radial" ? .2 : shape === "loop" ? .34 : .46;
    if (random() < branchChance) nextDirections.push((connector.direction + (random() < .5 ? 1 : 3)) % 4);
    if (shape === "branching" && random() < .22) nextDirections.push((connector.direction + (random() < .5 ? 1 : 3)) % 4);
    if (shape === "terraced" && connector.depth % 2 === 1 && random() < .6) nextDirections.push((connector.direction + 1) % 4);
    for (const direction of [...new Set(nextDirections)]) {
      queue.push({ x: end.x, z: end.z, direction, depth: connector.depth + 1 });
    }
  }

  const plots = [];
  const missing = [];
  const slots = roads.flatMap((road, roadIndex) => [0.3, 0.58, 0.82].flatMap((ratio) => [-1, 1].map((side) => ({ road, roadIndex, ratio, side }))));
  const shuffledSlots = [...slots].sort(() => random() - .5);
  const roadRects = roads.map((road) => villagePreviewRoadRect(road, roadWidth + 3));

  function tryPlacePlot(definition, kind, label, attempts = shuffledSlots.length) {
    const width = Number(definition.footprint?.width || definition.width || 16);
    const depthSize = Number(definition.footprint?.depth || definition.depth || 16);
    for (let attempt = 0; attempt < attempts; attempt += 1) {
      const slot = shuffledSlots[(attempt + Math.floor(random() * Math.max(1, shuffledSlots.length))) % shuffledSlots.length];
      if (!slot) break;
      const road = slot.road;
      const horizontal = road.z1 === road.z2;
      const alongX = road.x1 + (road.x2 - road.x1) * slot.ratio;
      const alongZ = road.z1 + (road.z2 - road.z1) * slot.ratio;
      const perpendicularDistance = roadWidth / 2 + (horizontal ? depthSize : width) / 2 + 7;
      const centerX = alongX + (horizontal ? 0 : slot.side * perpendicularDistance);
      const centerZ = alongZ + (horizontal ? slot.side * perpendicularDistance : 0);
      const plot = { x: centerX - width / 2, z: centerZ - depthSize / 2, width, depth: depthSize, kind, label };
      if (plots.some((other) => villagePreviewRectIntersects(plot, other, 5))) continue;
      if (roadRects.some((roadRect, index) => index !== slot.roadIndex && villagePreviewRectIntersects(plot, roadRect, 2))) continue;
      plots.push(plot);
      return true;
    }
    return false;
  }

  const facilityInstances = requirements.flatMap((requirement) =>
    Array.from({ length: Math.max(1, Number(requirement.count || 1)) }, (_, index) => ({
      ...requirement,
      instanceLabel: Number(requirement.count || 1) > 1 ? `${requirement.label} ${index + 1}` : requirement.label
    }))
  );
  for (const facility of facilityInstances) {
    if (!tryPlacePlot(facility, "facility", facility.instanceLabel)) missing.push(facility.instanceLabel);
  }

  const houseTarget = Math.min(30, Math.max(4, roads.length + Math.floor(depth * 1.5)));
  for (let index = 0; index < houseTarget; index += 1) {
    const wide = random() < .34;
    tryPlacePlot({ width: wide ? 32 : 16, depth: 16 }, "house", `기본 건물 ${index + 1}`, 18);
  }
  return { roads, plots, missing, rejectedRoads, openConnectors: queue.length };
}

function renderVillageGenerationTest() {
  const canvas = $("#village-generation-canvas");
  const summary = $("#village-generation-summary");
  if (!canvas || !summary || !state.settlement) return;
  const form = $("#settlement-form");
  const seed = Math.max(1, Number(form.elements.villagePreviewSeed.value || 1));
  const depth = Math.max(1, Math.min(7, Number(form.elements.villagePreviewDepth.value || 4)));
  const shape = form.elements.townLayoutShape.value || "branching";
  const roadWidth = Number(form.elements.townRoadWidth.value || 7);
  const requirements = [...selectedCivicFacilities(), ...selectedFacilityRequirements()];
  const result = simulateJigsawVillage(seed, depth, shape, roadWidth, requirements);
  const context = canvas.getContext("2d");
  const allX = [0, ...result.roads.flatMap((road) => [road.x1, road.x2]), ...result.plots.flatMap((plot) => [plot.x, plot.x + plot.width])];
  const allZ = [0, ...result.roads.flatMap((road) => [road.z1, road.z2]), ...result.plots.flatMap((plot) => [plot.z, plot.z + plot.depth])];
  const minX = Math.min(...allX) - 48; const maxX = Math.max(...allX) + 48;
  const minZ = Math.min(...allZ) - 48; const maxZ = Math.max(...allZ) + 48;
  const scale = Math.min(canvas.width / Math.max(1, maxX - minX), canvas.height / Math.max(1, maxZ - minZ));
  const offsetX = (canvas.width - (maxX - minX) * scale) / 2 - minX * scale;
  const offsetZ = (canvas.height - (maxZ - minZ) * scale) / 2 - minZ * scale;
  const project = (x, z) => ({ x: offsetX + x * scale, y: offsetZ + z * scale });
  context.clearRect(0, 0, canvas.width, canvas.height);
  context.fillStyle = "#c9d7b3";
  context.fillRect(0, 0, canvas.width, canvas.height);
  context.strokeStyle = "rgba(87,108,68,.18)";
  context.lineWidth = 1;
  for (let x = Math.ceil(minX / 16) * 16; x <= maxX; x += 16) {
    const start = project(x, minZ); const end = project(x, maxZ);
    context.beginPath(); context.moveTo(start.x, start.y); context.lineTo(end.x, end.y); context.stroke();
  }
  for (let z = Math.ceil(minZ / 16) * 16; z <= maxZ; z += 16) {
    const start = project(minX, z); const end = project(maxX, z);
    context.beginPath(); context.moveTo(start.x, start.y); context.lineTo(end.x, end.y); context.stroke();
  }
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
  const hub = project(0, 0);
  context.fillStyle = "#5b6770";
  context.fillRect(hub.x - 14 * scale, hub.y - 14 * scale, 28 * scale, 28 * scale);
  const labels = [];
  for (const plot of result.plots) {
    const position = project(plot.x, plot.z);
    context.fillStyle = plot.kind === "facility" ? "#55c9bd" : "#e4d5b5";
    context.strokeStyle = plot.kind === "facility" ? "#166b70" : "#796a50";
    context.lineWidth = plot.kind === "facility" ? 3 : 1.5;
    context.fillRect(position.x, position.y, plot.width * scale, plot.depth * scale);
    context.strokeRect(position.x, position.y, plot.width * scale, plot.depth * scale);
    if (plot.kind === "facility") labels.push({ text: plot.label, x: position.x + plot.width * scale / 2, y: position.y + plot.depth * scale / 2 });
  }
  context.textAlign = "center"; context.textBaseline = "middle"; context.font = "700 11px sans-serif";
  for (const label of labels) {
    const width = context.measureText(label.text).width + 8;
    context.fillStyle = "rgba(255,255,255,.82)"; context.fillRect(label.x - width / 2, label.y - 8, width, 16);
    context.fillStyle = "#16354a"; context.fillText(label.text, label.x, label.y);
  }
  const facilityCount = result.plots.filter((plot) => plot.kind === "facility").length;
  const houseCount = result.plots.filter((plot) => plot.kind === "house").length;
  const missingText = result.missing.length ? ` · 배치 실패: ${result.missing.join(", ")}` : " · 필수 시설 전부 배치";
  summary.textContent = `시드 ${seed} · 도로 조각 ${result.roads.length} · 시설 ${facilityCount} · 기본 건물 ${houseCount} · 막힌 연결 ${result.rejectedRoads}${missingText}`;
  summary.classList.toggle("has-error", result.missing.length > 0);
  canvas.setAttribute("aria-label", `도로 조각 ${result.roads.length}개, 시설 ${facilityCount}개, 기본 건물 ${houseCount}개가 생성된 마을 테스트`);
}

function updateFacilityFormState() {
  const form = $("#settlement-form");
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
  $("#selected-settlement-editor").hidden = false;
  $("#settlement-editor-title").textContent = document.display_name?.ko_kr || document.id;
  $("#settlement-path").textContent = state.settlementPath;
  setFormValue(form, "id", document.id); setFormValue(form, "enabled", document.enabled);
  setFormValue(form, "nameKo", document.display_name?.ko_kr); setFormValue(form, "nameEn", document.display_name?.en_us);
  setFormValue(form, "region", document.region); setFormValue(form, "dimension", document.dimension);
  const presetBiome = document.biome || document.biome_layout?.zones?.[0]?.biome || "minecraft:plains";
  form.elements.presetBiome.innerHTML = worldBiomeOptions(presetBiome);
  setFormValue(form, "presetBiome", presetBiome);
  setFormValue(form, "townRadiusCells", document.town_radius_cells ?? 1);
  setFormValue(form, "townLayoutShape", document.structure_profile?.layout_shape || "branching");
  setFormValue(form, "townRoadWidth", document.structure_profile?.road_profile?.width ?? 7);
  setFormValue(form, "townRoadMaterial", document.structure_profile?.road_profile?.material || "cobblestone");
  const previewSeedHash = [...document.id].reduce((value, character) => ((value * 31) + character.charCodeAt(0)) >>> 0, 1);
  const previewSeed = 1 + (previewSeedHash % 999999998);
  setFormValue(form, "villagePreviewSeed", previewSeed);
  setFormValue(form, "villagePreviewDepth", 4);
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
  ["#settlement-json", "#apply-settlement-json", "#add-trainer-slot", "#validate-settlement", "#save-settlement"].forEach((selector) => $(selector).disabled = false);
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
    biome: form.elements.presetBiome.value,
    town_radius_cells: Math.max(0, Math.min(8, number("townRadiusCells")))
  });
  const primaryZone = state.settlement.biome_layout?.zones?.[0];
  if (primaryZone) {
    primaryZone.biome = state.settlement.biome;
    primaryZone.placement = "center";
    state.settlement.biome_layout.zones = [primaryZone];
  }
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
  state.settlement.structure_profile.facility_requirements = facilityRequirements;
  state.settlement.anchors ||= {};
  for (const anchor of Object.keys(state.settlement.anchors)) {
    if (anchor.startsWith("preview_") || anchor.startsWith("facility_")) delete state.settlement.anchors[anchor];
  }
  const configuredFacilities = facilityTemplatePlacements(
    [...selectedCivicFacilities(), ...facilityRequirements],
    layoutShape, state.settlement.center || { x: 0, y: 64, z: 0 }
  );
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
  const singular = category === "trainers" ? "trainer" : "settlement";
  if (category === "settlements") {
    if (!$("#settlement-form").reportValidity()) return false;
    updateSettlementFromForm();
  }
  const document = parseEditor(`#${singular}-json`);
  if (!document) return false;
  const result = await request(`/api/document-validation?category=${category}`, { method: "POST", body: JSON.stringify(document) });
  showIssues(`#${singular}-issues`, result.data);
  toast(result.ok ? "문서 검증을 통과했습니다." : "수정이 필요한 항목이 있습니다.");
  return result.ok;
}

async function saveDocument(category) {
  const singular = category === "trainers" ? "trainer" : "settlement";
  if (category === "settlements") {
    if (!$("#settlement-form").reportValidity()) { toast("입력값을 확인해 주세요."); return; }
    updateSettlementFromForm();
  }
  const document = parseEditor(`#${singular}-json`);
  if (!document) return;
  const saveButton = $(`#save-${singular}`);
  const originalLabel = saveButton.textContent;
  saveButton.disabled = true;
  saveButton.textContent = "저장 중…";
  const result = await request(`/api/${category}?path=${encodeURIComponent(state[`${singular}Path`])}`, { method: "PUT", body: JSON.stringify(document) });
  saveButton.textContent = originalLabel;
  showIssues(`#${singular}-issues`, result.data);
  if (!result.ok) { saveButton.disabled = false; toast("검증 오류로 저장하지 않았습니다."); return; }
  state[singular] = document;
  toast("검증 후 안전하게 저장했습니다.");
  await Promise.all([loadDashboard(), loadLists()]);
  if (category === "settlements") renderSettlement(); else renderTrainer();
}

function openCreateDialog(category) {
  const form = $("#create-form");
  form.reset();
  form.elements.category.value = category;
  form.elements.generation.value = category === "settlements" ? `generation_${state.selectedGeneration}` : "generation_1";
  $("#create-title").textContent = category === "trainers" ? "새 트레이너" : "새 마을";
  $("#generation-field").hidden = category === "trainers";
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
    generation: form.elements.generation.value
  };
  $("#create-submit").disabled = true;
  try {
    const result = await request("/api/documents", { method: "POST", body: JSON.stringify(payload) });
    if (!result.ok) { showIssues("#create-issues", result.data); return; }
    $("#create-dialog").close();
    await Promise.all([loadDashboard(), loadLists()]);
    switchPage(payload.category);
    await loadDocument(payload.category, result.data.path);
    toast(payload.category === "trainers" ? "새 트레이너를 만들었습니다." : "새 마을을 만들었습니다.");
  } finally {
    $("#create-submit").disabled = false;
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
    $("#server-dot").classList.add("online"); $("#server-label").textContent = "서버 연결됨";
  } catch (error) {
    $("#server-dot").classList.remove("online"); $("#server-label").textContent = "연결 실패"; toast(error.message);
  }
}

$$(".nav-item").forEach((button) => button.addEventListener("click", () => switchPage(button.dataset.section)));
$("#refresh-button").addEventListener("click", refreshAll);
$("#validate-repository").addEventListener("click", loadDashboard);
$("#validate-trainer").addEventListener("click", () => validateDocument("trainers"));
$("#save-trainer").addEventListener("click", () => saveDocument("trainers"));
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
$("#add-bag-item").addEventListener("click", addBagItem);
$("#max-item-uses").addEventListener("input", updateTrainerFromForm);
$("#copy-team-json").addEventListener("click", copyTeamJson);
$("#paste-team-json").addEventListener("click", pasteTeamJson);
$("#apply-trainer-json").addEventListener("click", () => { const document = parseEditor("#trainer-json"); if (document) { state.trainer = document; renderTrainer(); toast("JSON을 편집 폼에 반영했습니다."); } });
$("#validate-settlement").addEventListener("click", () => validateDocument("settlements"));
$("#save-settlement").addEventListener("click", () => saveDocument("settlements"));
$("#save-world-layout").addEventListener("click", saveWorldLayout);
$("#add-generation").addEventListener("click", addGeneration);
$("#tile-inspector-form").addEventListener("change", handleTileInspectorChange);
$("#clear-tile").addEventListener("click", clearSelectedTile);
$("#create-route").addEventListener("click", createRouteConnection);
$("#finish-route").addEventListener("click", finishRouteConnection);
$("#cancel-route").addEventListener("click", cancelRouteConnection);
$("#toggle-empty-terrain-brush").addEventListener("click", (event) => {
  state.emptyTerrainBrushActive = !state.emptyTerrainBrushActive;
  event.currentTarget.setAttribute("aria-pressed", String(state.emptyTerrainBrushActive));
  event.currentTarget.textContent = state.emptyTerrainBrushActive ? "칠하기 종료" : "넓게 칠하기";
  $("#world-hex-map").classList.toggle("is-painting", state.emptyTerrainBrushActive);
});
$("#tile-radius-blocks").addEventListener("change", () => { state.worldLayout.grid.tile_radius_blocks = Number($("#tile-radius-blocks").value || 64); markWorldDirty(); });
$("#zoom-in").addEventListener("click", () => { state.mapZoom = Math.min(1.6, state.mapZoom + .1); renderHexMap(); });
$("#zoom-out").addEventListener("click", () => { state.mapZoom = Math.max(.65, state.mapZoom - .1); renderHexMap(); });
$("#fit-map").addEventListener("click", () => { fitMapToContent(); renderHexMap(); });
$("#world-hex-map").addEventListener("pointerdown", beginMapPan);
$("#world-hex-map").addEventListener("pointermove", moveMapPan);
$("#world-hex-map").addEventListener("pointerup", finishSettlementDrag);
$("#world-hex-map").addEventListener("pointerup", finishMapPan);
$("#world-hex-map").addEventListener("pointercancel", (event) => { state.draggedSettlement = null; state.mapPan = null; $("#world-hex-map").classList.remove("is-dragging", "is-panning"); finishMapPan(event); });
window.addEventListener("resize", resizeWorldMapWorkspace);
$("#settlement-form").addEventListener("input", () => { updateSettlementFromForm(); updateFacilityFormState(); });
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
$("#biome-set-select").addEventListener("change", renderBiomeSet);
$("#test-biome-set").addEventListener("click", testBiomeSet);
$("#habitat-pokemon-search").addEventListener("input", renderHabitatPokemon);
$("#habitat-generation-filter").addEventListener("change", renderHabitatPokemon);
$("#habitat-filter").addEventListener("change", renderHabitatPokemon);

refreshAll();
