const state = {
  trainers: [], settlements: [], trainer: null, settlement: null,
  trainerPath: "", settlementPath: "", buildCommands: [], trainerClasses: [],
  selectedPokemonIndex: 0
};
const pokemonArtworkCache = new Map();

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
  const [trainers, settlements, trainerClasses] = await Promise.all([
    request("/api/trainers"), request("/api/settlements"), request("/api/trainer-classes")
  ]);
  state.trainers = trainers.data.items || [];
  state.settlements = settlements.data.items || [];
  state.trainerClasses = trainerClasses.data.classes || [];
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
  setFormValue(form, "region", document.placement?.region);
  setFormValue(form, "settlement", document.placement?.settlement);
  setFormValue(form, "anchor", document.placement?.anchor);
  setFormValue(form, "rotation", document.placement?.rotation);
  setFormValue(form, "spawnPolicy", document.placement?.spawn_policy);
  setFormValue(form, "battleFormat", document.battle?.format);
  setFormValue(form, "battleType", document.battle?.battle_type);
  setFormValue(form, "battleAi", document.battle?.ai);
  setFormValue(form, "levelMode", document.battle?.level_mode);
  setFormValue(form, "megaEvolution", document.battle?.mechanics?.mega_evolution);
  setFormValue(form, "zMove", document.battle?.mechanics?.z_move);
  setFormValue(form, "dynamax", document.battle?.mechanics?.dynamax);
  setFormValue(form, "terastallization", document.battle?.mechanics?.terastallization);
  [...form.elements].forEach((element) => element.disabled = false);
  renderTrainerPreview();
  renderTeam();
  $("#trainer-json").value = JSON.stringify(document, null, 2);
  ["#trainer-json", "#apply-trainer-json", "#add-pokemon", "#validate-trainer", "#save-trainer"].forEach((selector) => $(selector).disabled = false);
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
  Object.assign(state.trainer.placement, {
    region: form.elements.region.value,
    settlement: form.elements.settlement.value,
    anchor: form.elements.anchor.value,
    rotation: Number(form.elements.rotation.value),
    spawn_policy: form.elements.spawnPolicy.value
  });
  Object.assign(state.trainer.battle, {
    format: form.elements.battleFormat.value,
    battle_type: form.elements.battleType.value,
    ai: form.elements.battleAi.value,
    level_mode: form.elements.levelMode.value
  });
  Object.assign(state.trainer.battle.mechanics, {
    mega_evolution: form.elements.megaEvolution.checked,
    z_move: form.elements.zMove.checked,
    dynamax: form.elements.dynamax.checked,
    terastallization: form.elements.terastallization.checked
  });
  renderTrainerPreview();
  syncTrainerJson();
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

function pokemonTemplate() {
  return {
    species: "cobblemon:rattata", level: 5, form: null, gender: "random",
    nature: null, ability: null, held_item: null, moves: ["tackle"], ivs: {}, evs: {},
    tera_type: null, shiny: false, gigantamax_factor: false
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
  const stats = [
    ["hp", "HP"], ["attack", "공격"], ["defense", "방어"],
    ["special_attack", "특수공격"], ["special_defense", "특수방어"], ["speed", "스피드"]
  ];
  const moves = Array.from({ length: 4 }, (_, index) => pokemon.moves?.[index] || "");
  list.innerHTML = `
    <div class="focused-entry-editor">
      <nav class="focused-party-tabs" aria-label="편집할 포켓몬 선택">
        ${Array.from({ length: 6 }, (_, index) => {
          const member = team[index];
          if (!member) return `<button type="button" class="empty" data-add-slot="${index}"><b>＋</b><strong>빈 슬롯</strong><small>SLOT ${String(index + 1).padStart(2, "0")}</small></button>`;
          return `<button type="button" class="${index === state.selectedPokemonIndex ? "active" : ""}" data-pokemon-index="${index}"><span>${String(index + 1).padStart(2, "0")}</span><img data-party-art="${index}" alt="" hidden><b data-party-fallback="${index}">●</b><strong>${escapeHtml(speciesLabel(member.species))}</strong><small>Lv.${escapeHtml(member.level)}</small></button>`;
        }).join("")}
      </nav>
      <article class="focused-pokemon-editor">
        <aside class="focused-pokemon-preview">
          <span class="slot-number">PARTY SLOT ${String(state.selectedPokemonIndex + 1).padStart(2, "0")}</span>
          <div><img class="focused-pokemon-sprite" id="focused-pokemon-art" alt="${escapeHtml(speciesLabel(pokemon.species))}" hidden><button class="empty-pokemon-prompt" id="pokemon-art-fallback" type="button"><b>?</b><span>이미지 불러오는 중</span></button></div>
          <h3 id="focused-species-name">${escapeHtml(speciesLabel(pokemon.species))}</h3>
          <p>Lv.${escapeHtml(pokemon.level)} · PokéAPI HOME PNG</p>
          <div class="focused-preview-actions"><button type="button" id="duplicate-pokemon">복제</button><button type="button" class="danger" id="remove-focused-pokemon">팀에서 제거</button></div>
        </aside>
        <section class="focused-profile-panel">
          <header><span>PROFILE</span><strong>기본 설정</strong><small>필수 정보</small></header>
          <div class="focused-profile-fields">
            <label class="wide"><span>종 ID</span><input name="species" value="${escapeHtml(pokemon.species || "")}"></label>
            <label><span>레벨</span><input type="number" min="1" max="100" name="level" value="${escapeHtml(pokemon.level ?? 5)}"></label>
            <label><span>성별</span><select name="gender"><option value="random" ${pokemon.gender === "random" ? "selected" : ""}>무작위</option><option value="male" ${pokemon.gender === "male" ? "selected" : ""}>수컷</option><option value="female" ${pokemon.gender === "female" ? "selected" : ""}>암컷</option><option value="genderless" ${pokemon.gender === "genderless" ? "selected" : ""}>무성</option></select></label>
            <label class="wide"><span>성격</span><input name="nature" value="${escapeHtml(pokemon.nature || "")}" placeholder="비우면 자동"></label>
            <label class="wide"><span>특성</span><input name="ability" value="${escapeHtml(pokemon.ability || "")}" placeholder="비우면 자동"></label>
            <label class="wide"><span>소지품 ID</span><input name="heldItem" value="${escapeHtml(pokemon.held_item || "")}" placeholder="비우면 없음"></label>
            <label class="wide"><span>테라 타입</span><input name="teraType" value="${escapeHtml(pokemon.tera_type || "")}" placeholder="비우면 없음"></label>
          </div>
          <div class="focused-gimmick-row"><label><input type="checkbox" name="shiny" ${pokemon.shiny ? "checked" : ""}>이로치</label><label><input type="checkbox" name="gigantamax" ${pokemon.gigantamax_factor ? "checked" : ""}>거다이맥스</label></div>
        </section>
        <section class="focused-stat-panel">
          <header><span>STATS</span><strong>개체값·노력치</strong><small id="ev-total">EV ${evTotal(pokemon)}/510</small></header>
          <div class="focused-stat-table"><div class="focused-stat-heading"><span>능력치</span><span>IV</span><span>EV</span></div>${stats.map(([key, label]) => `<label><strong>${label}</strong><input type="number" min="0" max="31" data-iv="${key}" value="${escapeHtml(pokemon.ivs?.[key] ?? 0)}"><input type="number" min="0" max="252" data-ev="${key}" value="${escapeHtml(pokemon.evs?.[key] ?? 0)}"></label>`).join("")}</div>
        </section>
        <section class="focused-moves-panel">
          <header><span>MOVESET</span><strong>기술 구성</strong><small>최대 4개</small></header>
          <div class="focused-moves-list">${moves.map((move, index) => `<div class="focused-move-field ${move ? "" : "empty"}"><span>${String(index + 1).padStart(2, "0")}</span><label><small>MOVE ${index + 1}</small><input data-move="${index}" value="${escapeHtml(move)}" placeholder="기술 ID"></label><button type="button" data-clear-move="${index}">지우기</button></div>`).join("")}</div>
        </section>
      </article>
    </div>`;
  $$('[data-pokemon-index]').forEach((button) => button.addEventListener("click", () => selectPokemon(Number(button.dataset.pokemonIndex))));
  $$('[data-add-slot]').forEach((button) => button.addEventListener("click", addPokemon));
  $$(".focused-pokemon-editor input, .focused-pokemon-editor select").forEach((input) => input.addEventListener("input", updateFocusedPokemon));
  list.querySelector('[name="species"]')?.addEventListener("change", hydrateFocusedPokemonArt);
  $$('[data-clear-move]').forEach((button) => button.addEventListener("click", () => clearMove(Number(button.dataset.clearMove))));
  $("#remove-focused-pokemon").addEventListener("click", () => removePokemon(state.selectedPokemonIndex));
  $("#duplicate-pokemon").addEventListener("click", duplicatePokemon);
  hydrateFocusedPokemonArt();
  hydratePartyArt();
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

function duplicatePokemon() {
  const team = state.trainer.battle.team;
  if (team.length >= 6) { toast("팀은 최대 6마리까지 구성할 수 있습니다."); return; }
  team.splice(state.selectedPokemonIndex + 1, 0, structuredClone(team[state.selectedPokemonIndex]));
  state.selectedPokemonIndex += 1;
  renderTeam();
  syncTrainerJson();
}

function updateFocusedPokemon() {
  const editor = $(".focused-pokemon-editor");
  if (!editor || !state.trainer?.battle?.team?.[state.selectedPokemonIndex]) return;
  const pokemon = state.trainer.battle.team[state.selectedPokemonIndex];
  const value = (name) => editor.querySelector(`[name="${name}"]`).value.trim();
  const ivs = {}; const evs = {};
  $$('[data-iv]').forEach((input) => ivs[input.dataset.iv] = Number(input.value));
  $$('[data-ev]').forEach((input) => evs[input.dataset.ev] = Number(input.value));
  Object.assign(pokemon, {
    species: value("species"), level: Number(value("level")), gender: value("gender"),
    nature: value("nature") || null, ability: value("ability") || null,
    held_item: value("heldItem") || null, tera_type: value("teraType") || null,
    shiny: editor.querySelector('[name="shiny"]').checked,
    gigantamax_factor: editor.querySelector('[name="gigantamax"]').checked,
    ivs, evs,
    moves: $$('[data-move]').map((input) => input.value.trim()).filter(Boolean)
  });
  $("#ev-total").textContent = `EV ${evTotal(pokemon)}/510`;
  $("#focused-species-name").textContent = speciesLabel(pokemon.species);
  syncTrainerJson();
}

function clearMove(index) {
  const input = $(`[data-move="${index}"]`);
  input.value = "";
  updateFocusedPokemon();
  input.focus();
}

function evTotal(pokemon) {
  return Object.values(pokemon.evs || {}).reduce((sum, value) => sum + (Number(value) || 0), 0);
}

function speciesLabel(species) {
  return String(species || "포켓몬").replace(/^.*:/, "").replaceAll("_", " ");
}

function pokeApiSlug(species) {
  return String(species || "").replace(/^.*:/, "").replaceAll("_", "-").toLowerCase();
}

async function pokemonArtwork(species) {
  const slug = pokeApiSlug(species);
  if (!slug) return "";
  if (pokemonArtworkCache.has(slug)) return pokemonArtworkCache.get(slug);
  try {
    const response = await fetch(`https://pokeapi.co/api/v2/pokemon/${encodeURIComponent(slug)}`);
    if (!response.ok) throw new Error("not found");
    const data = await response.json();
    const url = data.sprites?.other?.home?.front_default || data.sprites?.other?.["official-artwork"]?.front_default || "";
    pokemonArtworkCache.set(slug, url);
    return url;
  } catch {
    pokemonArtworkCache.set(slug, "");
    return "";
  }
}

async function hydrateFocusedPokemonArt() {
  const pokemon = state.trainer?.battle?.team?.[state.selectedPokemonIndex];
  if (!pokemon) return;
  const image = $("#focused-pokemon-art");
  const fallback = $("#pokemon-art-fallback");
  const url = await pokemonArtwork(pokemon.species);
  if (!image || !fallback) return;
  if (url) { image.src = url; image.hidden = false; fallback.hidden = true; }
  else { image.hidden = true; fallback.hidden = false; fallback.querySelector("span").textContent = "이미지 없음"; }
}

async function hydratePartyArt() {
  await Promise.all((state.trainer?.battle?.team || []).map(async (pokemon, index) => {
    const image = document.querySelector(`[data-party-art="${index}"]`);
    const fallback = document.querySelector(`[data-party-fallback="${index}"]`);
    const url = await pokemonArtwork(pokemon.species);
    if (image && url) { image.src = url; image.hidden = false; if (fallback) fallback.hidden = true; }
  }));
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
  $("#settlement-json").value = JSON.stringify(document, null, 2);
  ["#settlement-json", "#apply-settlement-json", "#validate-settlement", "#save-settlement"].forEach((selector) => $(selector).disabled = false);
  showIssues("#settlement-issues", { valid: true, issues: [] });
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
  else updateTrainerFromForm();
});
$("#add-pokemon").addEventListener("click", addPokemon);
$("#apply-trainer-json").addEventListener("click", () => { const document = parseEditor("#trainer-json"); if (document) { state.trainer = document; renderTrainer(); toast("JSON을 편집 폼에 반영했습니다."); } });
$("#validate-settlement").addEventListener("click", () => validateDocument("settlements"));
$("#save-settlement").addEventListener("click", () => saveDocument("settlements"));
$("#settlement-form").addEventListener("input", updateSettlementFromForm);
$("#apply-settlement-json").addEventListener("click", () => { const document = parseEditor("#settlement-json"); if (document) { state.settlement = document; renderSettlement(); toast("JSON을 기본 설정에 반영했습니다."); } });
$$('[data-create]').forEach((button) => button.addEventListener("click", () => openCreateDialog(button.dataset.create)));
$("#create-form").addEventListener("submit", createDocument);
$("#create-close").addEventListener("click", () => $("#create-dialog").close());
$("#create-cancel").addEventListener("click", () => $("#create-dialog").close());

refreshAll();
