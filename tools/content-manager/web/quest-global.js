const $ = selector => document.querySelector(selector);
const form = $("#activation-form");
const state = { items: [], path: "", document: null };

function showIssues(issues = []) {
  const box = $("#issues");
  box.hidden = issues.length === 0;
  box.textContent = issues.map(issue => `${issue.path || "$"}: ${issue.message}`).join("\n");
}

function renderList() {
  const query = $("#quest-search").value.trim().toLowerCase();
  const matches = state.items.filter(item =>
    item.category === "main" && `${item.id} ${item.name}`.toLowerCase().includes(query)
  );
  $("#quest-list").innerHTML = matches.map(item => `
    <button class="quest-card ${item.path === state.path ? "is-active" : ""}" data-path="${item.path}">
      <strong>${item.name || item.id}</strong>
      <small>${item.global_activation_enabled ? "전역 발동 사용 중" : "NPC/V5 부여만 사용"} · ${item.id}</small>
    </button>`).join("") || "<small>등록된 메인 퀘스트가 없습니다.</small>";
}

async function loadList() {
  const response = await fetch("/api/quests");
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.error || "퀘스트 목록을 불러오지 못했습니다.");
  state.items = payload.items || [];
  renderList();
}

function setEditorEnabled(enabled) {
  form.elements.enabled.disabled = !enabled;
  form.elements.conditionMode.disabled = !enabled;
  form.elements.conditions.disabled = !enabled;
  $("#save-activation").disabled = !enabled;
}

function fill(document, path) {
  state.document = document;
  state.path = path;
  const activation = document.global_activation || {};
  form.elements.enabled.checked = activation.enabled === true;
  form.elements.conditionMode.value = activation.conditions?.condition_mode || "all";
  form.elements.conditions.value = JSON.stringify(activation.conditions?.conditions || [], null, 2);
  $("#quest-path").textContent = path;
  $("#editor-title").textContent = document.display_name?.ko_kr || document.id;
  setEditorEnabled(true);
  showIssues();
  renderList();
}

async function openQuest(path) {
  const response = await fetch(`/api/quests?path=${encodeURIComponent(path)}`);
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.error || "퀘스트를 불러오지 못했습니다.");
  if (payload.document?.category !== "main") throw new Error("메인 퀘스트만 전역 발동할 수 있습니다.");
  fill(payload.document, payload.path);
}

async function saveActivation() {
  try {
    if (!state.document || !state.path) return;
    const document = structuredClone(state.document);
    if (form.elements.enabled.checked) {
      const conditions = JSON.parse(form.elements.conditions.value.trim() || "[]");
      if (!Array.isArray(conditions) || conditions.length === 0) {
        throw new Error("전역 발동 조건을 하나 이상 추가해야 합니다.");
      }
      document.global_activation = {
        enabled: true,
        conditions: { condition_mode: form.elements.conditionMode.value, conditions }
      };
    } else {
      delete document.global_activation;
    }
    const response = await fetch(`/api/quests?path=${encodeURIComponent(state.path)}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(document)
    });
    const payload = await response.json();
    showIssues(payload.issues || []);
    if (!response.ok || !payload.saved) return;
    state.document = document;
    await loadList();
    fill(document, payload.path);
  } catch (error) {
    showIssues([{ path: "$", message: error.message }]);
  }
}

$("#quest-list").addEventListener("click", event => {
  const button = event.target.closest("[data-path]");
  if (button) openQuest(button.dataset.path).catch(error => showIssues([{ path: "$", message: error.message }]));
});
$("#quest-search").addEventListener("input", renderList);
$("#save-activation").addEventListener("click", saveActivation);
loadList().catch(error => showIssues([{ path: "$", message: error.message }]));
