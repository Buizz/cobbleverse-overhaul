const $ = selector => document.querySelector(selector);
const state = { document: null, quests: [], npcs: [] };

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, character => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;", "'": "&#39;"
  })[character]);
}

function showIssues(issues = []) {
  const box = $("#issues");
  box.hidden = issues.length === 0;
  box.textContent = issues.map(issue => `${issue.path || "$"}: ${issue.message}`).join("\n");
}

function options(items, selected, fallbackLabel) {
  const values = items.map(item => `<option value="${escapeHtml(item.id)}" ${item.id === selected ? "selected" : ""}>${escapeHtml(item.name || item.id)} · ${escapeHtml(item.id)}</option>`);
  if (selected && !items.some(item => item.id === selected)) {
    values.unshift(`<option value="${escapeHtml(selected)}" selected>${escapeHtml(selected)} (${fallbackLabel})</option>`);
  }
  return values.join("");
}

function render() {
  const steps = state.document?.steps || [];
  $("#flow-enabled").checked = state.document?.enabled !== false;
  $("#flow-steps").innerHTML = steps.map((step, index) => `
    <article class="flow-step" data-index="${index}">
      <div class="flow-order">${index + 1}</div>
      <label><span>단계 ID</span><input data-field="id" value="${escapeHtml(step.id)}"></label>
      <label><span>메인 퀘스트</span><select data-field="quest">${options(state.quests, step.quest, "없는 퀘스트")}</select></label>
      <label><span>진행 NPC</span><select data-field="npc">${options(state.npcs, step.npc, "없는 NPC")}</select></label>
      <div class="flow-actions">
        <button data-action="up" title="위로" ${index === 0 ? "disabled" : ""}>↑</button>
        <button data-action="down" title="아래로" ${index === steps.length - 1 ? "disabled" : ""}>↓</button>
        <button data-action="remove" title="삭제">삭제</button>
      </div>
    </article>`).join("") || '<div class="flow-empty">작성한 NPC 단계가 없습니다. 다음 체육관이 기본 메인 퀘스트가 됩니다.</div>';
}

async function load() {
  const response = await fetch("/api/main-quest-progression");
  const payload = await response.json();
  if (!response.ok) throw new Error(payload.error || "진행 문서를 불러오지 못했습니다.");
  state.document = payload.document;
  state.quests = payload.quests || [];
  state.npcs = payload.npcs || [];
  render();
}

function updateStep(target) {
  const row = target.closest("[data-index]");
  if (!row || !target.dataset.field) return;
  state.document.steps[Number(row.dataset.index)][target.dataset.field] = target.value.trim();
}

function moveStep(index, offset) {
  const target = index + offset;
  if (target < 0 || target >= state.document.steps.length) return;
  [state.document.steps[index], state.document.steps[target]] = [state.document.steps[target], state.document.steps[index]];
  render();
}

async function save() {
  state.document.enabled = $("#flow-enabled").checked;
  const response = await fetch("/api/main-quest-progression", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(state.document)
  });
  const payload = await response.json();
  showIssues(payload.issues || (payload.error ? [{ path: "$", message: payload.error }] : []));
  if (response.ok && payload.saved) await load();
}

$("#flow-steps").addEventListener("input", event => updateStep(event.target));
$("#flow-steps").addEventListener("change", event => updateStep(event.target));
$("#flow-steps").addEventListener("click", event => {
  const button = event.target.closest("[data-action]");
  if (!button) return;
  const index = Number(button.closest("[data-index]").dataset.index);
  if (button.dataset.action === "up") moveStep(index, -1);
  if (button.dataset.action === "down") moveStep(index, 1);
  if (button.dataset.action === "remove") { state.document.steps.splice(index, 1); render(); }
});
$("#add-step").addEventListener("click", () => {
  const used = new Set(state.document.steps.map(step => step.quest));
  const quest = state.quests.find(item => !used.has(item.id)) || state.quests[0];
  const npc = state.npcs[0];
  state.document.steps.push({ id: `step_${state.document.steps.length + 1}`, quest: quest?.id || "", npc: npc?.id || "" });
  render();
});
$("#save-flow").addEventListener("click", () => save().catch(error => showIssues([{ path: "$", message: error.message }])));
load().catch(error => showIssues([{ path: "$", message: error.message }]));
