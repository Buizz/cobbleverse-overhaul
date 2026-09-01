const $ = (selector) => document.querySelector(selector);
const form = $("#quest-form");
const state = { items: [], path: "", document: null };

const defaultObjectives = [{
  id: "objective_1",
  text: { ko_kr: "목표를 입력하세요." },
  conditions: { condition_mode: "all", conditions: [] }
}];

function parseJson(name) {
  const value = form.elements[name].value.trim();
  const parsed = JSON.parse(value || "[]");
  if (!Array.isArray(parsed)) throw new Error(`${name} 값은 JSON 배열이어야 합니다.`);
  return parsed;
}

function pathFor(id) {
  const [namespace, resource = ""] = id.split(":", 2);
  if (!namespace || !resource) throw new Error("퀘스트 ID는 namespace:path 형식이어야 합니다.");
  return `content/quests/${namespace}/${resource.replace(/^quest\//, "")}.json`;
}

function documentFromForm() {
  const id = form.elements.id.value.trim();
  const name = { ko_kr: form.elements.nameKo.value.trim() };
  const summary = { ko_kr: form.elements.summaryKo.value.trim() };
  if (form.elements.nameEn.value.trim()) name.en_us = form.elements.nameEn.value.trim();
  if (form.elements.summaryEn.value.trim()) summary.en_us = form.elements.summaryEn.value.trim();
  const requiredTools = parseJson("requiredTools");
  return {
    $schema: "../../../schemas/quest.schema.json",
    schema_version: 1,
    id,
    enabled: form.elements.enabled.checked,
    category: form.elements.category.value,
    display_name: name,
    summary,
    accept_conditions: {
      condition_mode: form.elements.acceptMode.value,
      conditions: parseJson("acceptConditions")
    },
    ...(state.document?.global_activation ? { global_activation: state.document.global_activation } : {}),
    objectives: parseJson("objectives"),
    completion: { mode: form.elements.completionMode.value },
    ...(requiredTools.length ? { guidance: { required_tools: requiredTools } } : {}),
    next_quests: form.elements.nextQuests.value.split(/\r?\n/).map(value => value.trim()).filter(Boolean)
  };
}

function fill(document = null) {
  const value = document || {
    id: "cobbleventure:quest/main/new_quest", enabled: true, category: "main",
    display_name: { ko_kr: "새 퀘스트" }, summary: { ko_kr: "퀘스트 설명" },
    accept_conditions: { condition_mode: "all", conditions: [] }, objectives: defaultObjectives,
    completion: { mode: "npc_turn_in" }, next_quests: []
  };
  form.elements.id.value = value.id || "";
  form.elements.category.value = value.category || "side";
  form.elements.nameKo.value = value.display_name?.ko_kr || "";
  form.elements.nameEn.value = value.display_name?.en_us || "";
  form.elements.summaryKo.value = value.summary?.ko_kr || "";
  form.elements.summaryEn.value = value.summary?.en_us || "";
  form.elements.enabled.checked = value.enabled !== false;
  form.elements.acceptMode.value = value.accept_conditions?.condition_mode || "all";
  form.elements.acceptConditions.value = JSON.stringify(value.accept_conditions?.conditions || [], null, 2);
  form.elements.objectives.value = JSON.stringify(value.objectives || defaultObjectives, null, 2);
  form.elements.completionMode.value = value.completion?.mode || "npc_turn_in";
  form.elements.requiredTools.value = JSON.stringify(value.guidance?.required_tools || [], null, 2);
  form.elements.nextQuests.value = (value.next_quests || []).join("\n");
  $("#editor-title").textContent = value.display_name?.ko_kr || "퀘스트 편집";
  $("#quest-path").textContent = state.path || "새 문서";
}

async function loadList() {
  const response = await fetch("/api/quests");
  const payload = await response.json();
  state.items = payload.items || [];
  renderList();
}

function renderList() {
  const query = $("#quest-search").value.trim().toLowerCase();
  $("#quest-list").innerHTML = state.items.filter(item =>
    `${item.id} ${item.name}`.toLowerCase().includes(query)
  ).map(item => `<button class="quest-card ${item.path === state.path ? "is-active" : ""}" data-path="${item.path}"><strong>${item.name || item.id}</strong><small>${item.category || "side"} · ${item.id}</small></button>`).join("") || "<small>저장된 퀘스트가 없습니다.</small>";
}

async function openQuest(path) {
  const response = await fetch(`/api/quests?path=${encodeURIComponent(path)}`);
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.error || "퀘스트를 불러오지 못했습니다.");
  state.path = payload.path;
  state.document = payload.document;
  fill(payload.document); renderList();
}

function showIssues(payload) {
  const box = $("#issues");
  const issues = payload.issues || [];
  box.hidden = !issues.length;
  box.textContent = issues.map(issue => `${issue.path}: ${issue.message}`).join("\n");
}

async function saveQuest() {
  try {
    const document = documentFromForm();
    const path = state.path || pathFor(document.id);
    const response = await fetch(`/api/quests?path=${encodeURIComponent(path)}`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(document)
    });
    const payload = await response.json();
    showIssues(payload);
    if (!response.ok || !payload.saved) return;
    state.path = payload.path; state.document = document;
    await loadList(); fill(document);
  } catch (error) { showIssues({ issues: [{ path: "$", message: error.message }] }); }
}

$("#quest-list").addEventListener("click", event => {
  const button = event.target.closest("[data-path]"); if (button) openQuest(button.dataset.path).catch(error => showIssues({issues:[{path:"$",message:error.message}]}));
});
$("#quest-search").addEventListener("input", renderList);
$("#new-quest").addEventListener("click", () => { state.path = ""; state.document = null; fill(); renderList(); });
$("#save-quest").addEventListener("click", saveQuest);
fill(); loadList().catch(error => showIssues({issues:[{path:"$",message:error.message}]}));
