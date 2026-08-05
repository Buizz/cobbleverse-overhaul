import {
  createPartyClipboardEntry,
  parsePartyClipboardText,
  readClipboardText,
  toContentManagerParty,
  writeClipboardText,
} from "/pokemon-entry-clipboard.mjs";

const state = {
  trainers: [], settlements: [], trainer: null, settlement: null,
  trainerPath: "", settlementPath: "", buildCommands: [], trainerClasses: [],
  selectedPokemonIndex: 0, editorCatalog: null, choice: null
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
  const titles = { dashboard: "프로젝트 현황", trainers: "트레이너 데이터", settlements: "마을 기본 설정", builds: "빌드 및 검사" };
  $("#page-title").textContent = titles[section];
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
  const [trainers, settlements, trainerClasses, editorCatalog] = await Promise.all([
    request("/api/trainers"), request("/api/settlements"), request("/api/trainer-classes"),
    request("/api/editor-catalog")
  ]);
  state.trainers = trainers.data.items || [];
  state.settlements = settlements.data.items || [];
  state.trainerClasses = trainerClasses.data.classes || [];
  state.editorCatalog = editorCatalog.ok ? editorCatalog.data : null;
  if (!editorCatalog.ok) toast(editorCatalog.data.error || "전투 데이터 카탈로그를 불러오지 못했습니다.");
  renderList("trainers");
  renderList("settlements");
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
  $("#trainer-editor-title").textContent = document.name?.ko_kr || document.id;
  $("#trainer-path").textContent = state.trainerPath;
  setFormValue(form, "id", document.id);
  setFormValue(form, "nameKo", document.name?.ko_kr);
  setFormValue(form, "nameEn", document.name?.en_us);
  setFormValue(form, "tags", (document.tags || []).join(", "));
  form.elements.trainerClass.innerHTML = state.trainerClasses.map((trainerClass) => `<option value="${escapeHtml(trainerClass.id)}">${escapeHtml(trainerClass.display_name?.ko_kr || trainerClass.id)}</option>`).join("");
  setFormValue(form, "trainerClass", document.npc?.trainer_class);
  setFormValue(form, "appearanceSource", document.npc?.appearance?.source);
  setFormValue(form, "appearanceResource", document.npc?.appearance?.resource);
  setFormValue(form, "movement", document.npc?.behavior?.movement);
  setFormValue(form, "interactionRange", document.npc?.behavior?.interaction_range);
  setFormValue(form, "lookAtPlayer", document.npc?.behavior?.look_at_player);
  setFormValue(form, "invulnerable", document.npc?.behavior?.invulnerable);
  setFormValue(form, "battleFormat", document.battle?.format);
  setFormValue(form, "battleType", document.battle?.battle_type);
  setFormValue(form, "battleDifficulty", document.battle?.difficulty || "standard");
  setFormValue(form, "battleAi", document.battle?.ai);
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
  Object.assign(state.trainer.battle, {
    format: form.elements.battleFormat.value,
    battle_type: form.elements.battleType.value,
    difficulty: form.elements.battleDifficulty.value,
    ai: form.elements.battleAi.value,
    level_mode: form.elements.levelMode.value
  });
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
    form.elements.appearanceSource.value = trainerClass.default_appearance.source;
    form.elements.appearanceResource.value = trainerClass.default_appearance.resource;
    state.trainer.npc.appearance = {
      ...state.trainer.npc.appearance,
      ...trainerClass.default_appearance
    };
  }
  updateTrainerFromForm();
}

function rctSkinUrl(resource) {
  if (!resource?.startsWith("rctmod:trainers/")) return "";
  const path = resource.slice("rctmod:".length);
  return `https://gitlab.com/srcmc/rct/mod/-/raw/1.21.1/common/src/main/resources/assets/rctmod/textures/${path}.png`;
}

function renderTrainerPreview() {
  if (!state.trainer) return;
  const appearance = state.trainer.npc?.appearance || {};
  const trainerClass = state.trainerClasses.find((entry) => entry.id === state.trainer.npc?.trainer_class);
  const className = trainerClass?.display_name?.ko_kr || "사용자 정의";
  const fullTitle = trainerDisplayName(state.trainer.name || {}, state.trainer.npc?.trainer_class).ko_kr || className;
  const skinUrl = appearance.source?.startsWith("rct_") ? rctSkinUrl(appearance.resource) : "";
  const preview = $("#trainer-preview");
  preview.innerHTML = skinUrl ? `
    <div class="skin-doll" style="--skin-image: url('${skinUrl}')" aria-label="${escapeHtml(className)} RCT 스킨 미리보기">
      <i class="skin-head"></i><i class="skin-body"></i><i class="skin-arm left"></i><i class="skin-arm right"></i><i class="skin-leg left"></i><i class="skin-leg right"></i>
    </div><strong>${escapeHtml(fullTitle)}</strong>` : `<div class="trainer-preview-fallback">${escapeHtml(className.slice(0, 2))}</div><strong>${escapeHtml(fullTitle)}</strong>`;
  $("#trainer-appearance-note").textContent = skinUrl
    ? "RCT 1.21.1 리소스팩의 플레이어 스킨 구조를 미리 봅니다. 빌드에서는 선택한 리소스 ID를 사용합니다."
    : "직접 제작 외형은 리소스팩에 스킨 또는 모델을 추가한 뒤 리소스 ID로 연결합니다.";
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

function renderSettlement() {
  const document = state.settlement;
  const form = $("#settlement-form");
  $("#settlement-editor-title").textContent = document.display_name?.ko_kr || document.id;
  $("#settlement-path").textContent = state.settlementPath;
  setFormValue(form, "id", document.id); setFormValue(form, "enabled", document.enabled);
  setFormValue(form, "nameKo", document.display_name?.ko_kr); setFormValue(form, "nameEn", document.display_name?.en_us);
  setFormValue(form, "region", document.region); setFormValue(form, "dimension", document.dimension);
  setFormValue(form, "centerX", document.center?.x); setFormValue(form, "centerY", document.center?.y); setFormValue(form, "centerZ", document.center?.z);
  setFormValue(form, "minX", document.bounds?.min_x); setFormValue(form, "minZ", document.bounds?.min_z);
  setFormValue(form, "maxX", document.bounds?.max_x); setFormValue(form, "maxZ", document.bounds?.max_z);
  setFormValue(form, "maxAmbient", document.npc_placement?.max_ambient_npcs);
  setFormValue(form, "wanderRadius", document.npc_placement?.default_wander_radius);
  [...form.elements].forEach((element) => element.disabled = false);
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
  list.innerHTML = slots.map((slot, index) => `
    <article class="trainer-slot-row" data-slot-index="${index}">
      <div class="trainer-slot-heading"><strong>배치 ${String(index + 1).padStart(2, "0")}</strong><button type="button" class="remove-trainer-slot" data-remove-trainer-slot="${index}">삭제</button></div>
      <div class="trainer-slot-fields">
        <label class="trainer-choice"><span>트레이너</span><select data-slot-field="trainer_id">${trainerOptions}</select></label>
        <label><span>슬롯 ID</span><input data-slot-field="id" value="${escapeHtml(slot.id || "")}"></label>
        <label><span>X</span><input type="number" data-slot-field="x" value="${Number(slot.position?.x ?? 0)}"></label>
        <label><span>Y</span><input type="number" data-slot-field="y" value="${Number(slot.position?.y ?? 64)}"></label>
        <label><span>Z</span><input type="number" data-slot-field="z" value="${Number(slot.position?.z ?? 0)}"></label>
        <label><span>회전</span><input type="number" min="-360" max="360" step="1" data-slot-field="rotation" value="${Number(slot.rotation ?? 0)}"></label>
        <label><span>생성 정책</span><select data-slot-field="spawn_policy"><option value="persistent">항상 유지</option><option value="on_region_load">지역 로딩 시</option><option value="manual">수동 생성</option></select></label>
        <label class="slot-tags"><span>태그 — 쉼표로 구분</span><input data-slot-field="tags" value="${escapeHtml((slot.tags || []).join(", "))}"></label>
      </div>
    </article>`).join("");
  $$(".trainer-slot-row").forEach((row) => {
    const slot = slots[Number(row.dataset.slotIndex)];
    row.querySelector('[data-slot-field="trainer_id"]').value = slot.trainer_id || "";
    row.querySelector('[data-slot-field="spawn_policy"]').value = slot.spawn_policy || "persistent";
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
    position: { ...(state.settlement.center || { x: 0, y: 64, z: 0 }) },
    rotation: 0,
    spawn_policy: "persistent",
    tags: ["trainer"]
  });
  renderTrainerSlots();
  updateSettlementFromForm();
}

function updateTrainerSlot(event) {
  const row = event.target.closest("[data-slot-index]");
  const field = event.target.dataset.slotField;
  if (!row || !field || !state.settlement) return;
  const slot = state.settlement.npc_placement.trainer_slots[Number(row.dataset.slotIndex)];
  if (["x", "y", "z"].includes(field)) slot.position[field] = Number(event.target.value);
  else if (field === "rotation") slot.rotation = Number(event.target.value);
  else if (field === "tags") slot.tags = event.target.value.split(",").map((tag) => tag.trim()).filter(Boolean);
  else slot[field] = event.target.value;
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
    center: { x: number("centerX"), y: number("centerY"), z: number("centerZ") },
    bounds: { min_x: number("minX"), min_z: number("minZ"), max_x: number("maxX"), max_z: number("maxZ") }
  });
  state.settlement.npc_placement = state.settlement.npc_placement || { trainer_slots: [], zones: [] };
  state.settlement.npc_placement.max_ambient_npcs = number("maxAmbient");
  state.settlement.npc_placement.default_wander_radius = number("wanderRadius");
  $("#settlement-json").value = JSON.stringify(state.settlement, null, 2);
}

function parseEditor(selector) {
  try { return JSON.parse($(selector).value); }
  catch (error) { toast(`JSON 문법 오류: ${error.message}`); return null; }
}

async function validateDocument(category) {
  const singular = category === "trainers" ? "trainer" : "settlement";
  const document = parseEditor(`#${singular}-json`);
  if (!document) return false;
  const result = await request(`/api/document-validation?category=${category}`, { method: "POST", body: JSON.stringify(document) });
  showIssues(`#${singular}-issues`, result.data);
  toast(result.ok ? "문서 검증을 통과했습니다." : "수정이 필요한 항목이 있습니다.");
  return result.ok;
}

async function saveDocument(category) {
  const singular = category === "trainers" ? "trainer" : "settlement";
  const document = parseEditor(`#${singular}-json`);
  if (!document) return;
  const result = await request(`/api/${category}?path=${encodeURIComponent(state[`${singular}Path`])}`, { method: "PUT", body: JSON.stringify(document) });
  showIssues(`#${singular}-issues`, result.data);
  if (!result.ok) { toast("검증 오류로 저장하지 않았습니다."); return; }
  state[singular] = document;
  toast("검증 후 안전하게 저장했습니다.");
  await Promise.all([loadDashboard(), loadLists()]);
  if (category === "settlements") renderSettlement(); else renderTrainer();
}

function openCreateDialog(category) {
  const form = $("#create-form");
  form.reset();
  form.elements.category.value = category;
  form.elements.generation.value = "generation_1";
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
$("#settlement-form").addEventListener("input", updateSettlementFromForm);
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

refreshAll();
