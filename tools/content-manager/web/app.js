const state = {
  trainers: [], settlements: [], trainer: null, settlement: null,
  trainerPath: "", settlementPath: "", buildCommands: []
};

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
  const [trainers, settlements] = await Promise.all([request("/api/trainers"), request("/api/settlements")]);
  state.trainers = trainers.data.items || [];
  state.settlements = settlements.data.items || [];
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
  $("#trainer-editor-title").textContent = document.name?.ko_kr || document.id;
  $("#trainer-path").textContent = state.trainerPath;
  $("#trainer-json").value = JSON.stringify(document, null, 2);
  ["#trainer-json", "#validate-trainer", "#save-trainer"].forEach((selector) => $(selector).disabled = false);
  showIssues("#trainer-issues", { valid: true, issues: [] });
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
$("#validate-settlement").addEventListener("click", () => validateDocument("settlements"));
$("#save-settlement").addEventListener("click", () => saveDocument("settlements"));
$("#settlement-form").addEventListener("input", updateSettlementFromForm);
$("#apply-settlement-json").addEventListener("click", () => { const document = parseEditor("#settlement-json"); if (document) { state.settlement = document; renderSettlement(); toast("JSON을 기본 설정에 반영했습니다."); } });

refreshAll();
