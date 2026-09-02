const $ = (selector) => document.querySelector(selector);
const form = $("#quest-form");
const state = { items: [], path: "", document: null, events: [], npcs: [], dirty: false };
const conditionEditor = PlayerConditionEditor;
const escapeHtml = conditionEditor.escapeHtml;
const acceptEditor = $("#quest-accept-conditions");
conditionEditor.initialize(acceptEditor, { includeAlways: true });

const defaultObjectives = [{
  id: "objective_1",
  text: { ko_kr: "목표를 입력하세요." },
  conditions: { condition_mode: "all", conditions: [] }
}];

function hookValue(host) {
  const scriptId = host.querySelector('[data-hook-script]')?.value || "";
  return scriptId ? {script_id: scriptId, npc_id: host.querySelector('[data-hook-npc]').value} : null;
}

function renderHookPicker(host, hook, title) {
  host.classList.add('quest-hook-picker');
  host.innerHTML = `<strong>${escapeHtml(title)}</strong><label><span>이벤트 검색</span><input type="search" data-hook-search placeholder="이름, ID, 태그"></label><label><span>실행 이벤트</span><select data-hook-script></select></label><label><span>실행 기준 NPC</span><select data-hook-npc><option value="">NPC 선택</option></select></label><button type="button" data-hook-edit>이벤트 바로 편집</button>`;
  const script = host.querySelector('[data-hook-script]');
  const npc = host.querySelector('[data-hook-npc]');
  function options(query = '') {
    const selected = script.value || hook?.script_id || '';
    script.replaceChildren(new Option('연결 없음', ''));
    const events = state.events.filter(item => !item.managed && item.quest_compatible);
    for (const item of events) {
      if (item.script_id !== selected && ![item.name,item.script_id,...(item.metadata?.tags || [])].join(' ').toLowerCase().includes(query.toLowerCase())) continue;
      script.add(new Option(`${item.name} · ${item.script_id}`, item.script_id));
    }
    if (selected && ![...script.options].some(option => option.value === selected)) script.add(new Option(`현재 연결 (검증 필요): ${selected}`, selected));
    script.value = selected;
  }
  options();
  for (const item of state.npcs) npc.add(new Option(`${item.name || item.id} · ${item.id}`, item.id));
  if (hook?.npc_id && ![...npc.options].some(option => option.value === hook.npc_id)) npc.add(new Option(`현재 NPC (검증 필요): ${hook.npc_id}`, hook.npc_id));
  npc.value = hook?.npc_id || '';
  const update = () => { npc.disabled = !script.value; host.querySelector('[data-hook-edit]').disabled = !script.value; };
  update();
  script.addEventListener('change', () => { hook = null; update(); state.dirty = true; });
  host.querySelector('[data-hook-search]').addEventListener('input', event => options(event.target.value));
  host.querySelector('[data-hook-edit]').addEventListener('click', () => openHookEvent(script.value));
}

function openHookEvent(scriptId = '') {
  const item = state.events.find(item => item.script_id === scriptId);
  if (window.parent !== window) window.parent.postMessage({type:'quest:open-event',path:item?.path || null}, location.origin);
  else window.open(`/cves.html${item ? '?path=' + encodeURIComponent(item.path) : ''}`, '_blank', 'noopener');
}

async function loadHookCatalogs() {
  const [eventResponse,npcResponse] = await Promise.all([fetch('/api/cves/scripts'),fetch('/api/trainers')]);
  if (!eventResponse.ok || !npcResponse.ok) throw new Error('이벤트/NPC 목록을 불러오지 못했습니다.');
  state.events = (await eventResponse.json()).items || [];
  state.npcs = (await npcResponse.json()).items || [];
  document.querySelectorAll('.quest-hook-picker').forEach(host => renderHookPicker(host, hookValue(host), host.querySelector('strong').textContent));
}

function pathFor(id) {
  const [namespace, resource = ""] = id.split(":", 2);
  if (!namespace || !resource) throw new Error("퀘스트 ID는 namespace:path 형식이어야 합니다.");
  return `content/quests/${namespace}/${resource.replace(/^quest\//, "")}.json`;
}

function renderObjectives(objectives) {
  const container = $("#quest-objectives");
  container.replaceChildren();
  objectives.forEach((objective, index) => {
    const card = document.createElement("article");
    card.className = "quest-objective";
    card.objectiveDocument = structuredClone(objective);
    card.innerHTML = `
      <header class="objective-heading"><strong>목표 ${index + 1}</strong><div class="objective-actions">
        <button type="button" data-objective-action="up" ${index === 0 ? "disabled" : ""} aria-label="목표 위로">↑</button>
        <button type="button" data-objective-action="down" ${index === objectives.length - 1 ? "disabled" : ""} aria-label="목표 아래로">↓</button>
        <button type="button" data-objective-action="remove" ${objectives.length === 1 ? "disabled" : ""}>목표 삭제</button>
      </div></header>
      <div class="objective-fields">
        <label><span>목표 ID</span><input data-objective-field="id" value="${escapeHtml(objective.id || "")}" required></label>
        <label><span>달성 조건 방식</span><select data-objective-field="mode"><option value="all">모두 만족</option><option value="any">하나 이상 만족</option></select></label>
        <label><span>한국어 목표 안내</span><textarea data-objective-field="ko" rows="2">${escapeHtml(objective.text?.ko_kr || "")}</textarea></label>
        <label><span>영문 목표 안내</span><textarea data-objective-field="en" rows="2">${escapeHtml(objective.text?.en_us || "")}</textarea></label>
        <label><span>안내 마커</span><select data-objective-field="markerType"><option value="">없음</option><option value="npc">NPC</option><option value="settlement">마을</option><option value="route">길</option><option value="anchor">앵커</option></select></label>
        <label><span>마커 대상 ID</span><input data-objective-field="markerTarget" value="${escapeHtml(objective.marker?.target || "")}" placeholder="cobbleventure:npc/professor_oak"></label>
      </div>
      <section class="gate-condition-builder"><header><span>목표 달성 조건</span><button type="button" data-gate-condition-add>+ 조건 추가</button></header><div class="gate-condition-list" data-gate-condition-list></div></section>`;
    card.querySelector('[data-objective-field="mode"]').value = objective.conditions?.condition_mode || "all";
    card.querySelector('[data-objective-field="markerType"]').value = objective.marker?.type || "";
    const editor = card.querySelector(".gate-condition-builder");
    conditionEditor.initialize(editor, { includeAlways: true });
    conditionEditor.render(editor, objective.conditions?.conditions || []);
    container.append(card);
    const hookHost = document.createElement('div'); card.append(hookHost);
    renderHookPicker(hookHost, objective.on_complete, '목표 최초 달성 이벤트');
  });
}

function objectivesFromEditor(validate = true) {
  return [...document.querySelectorAll(".quest-objective")].map(card => {
    const value = key => card.querySelector(`[data-objective-field="${key}"]`).value.trim();
    const objective = structuredClone(card.objectiveDocument);
    objective.id = value("id");
    objective.text = { ...objective.text, ko_kr: value("ko") };
    if (value("en")) objective.text.en_us = value("en");
    else delete objective.text.en_us;
    const editor = card.querySelector(".gate-condition-builder");
    objective.conditions = {
      condition_mode: value("mode"),
      conditions: validate ? conditionEditor.read(editor) : structuredClone(editor.gateConditions)
    };
    if (value("markerType")) objective.marker = { ...objective.marker, type: value("markerType"), target: value("markerTarget") };
    else delete objective.marker;
    const hook = hookValue(card.querySelector('.quest-hook-picker'));
    if (hook) objective.on_complete = hook; else delete objective.on_complete;
    return objective;
  });
}

function documentFromForm() {
  const id = form.elements.id.value.trim();
  const name = { ...state.document?.display_name, ko_kr: form.elements.nameKo.value.trim() };
  const summary = { ...state.document?.summary, ko_kr: form.elements.summaryKo.value.trim() };
  if (form.elements.nameEn.value.trim()) name.en_us = form.elements.nameEn.value.trim();
  if (form.elements.summaryEn.value.trim()) summary.en_us = form.elements.summaryEn.value.trim();
  else delete summary.en_us;
  if (!form.elements.nameEn.value.trim()) delete name.en_us;
  const document = {
    // Preserve legacy guidance/next_quests data without exposing unused settings.
    ...state.document,
    $schema: "../../../schemas/quest.schema.json",
    schema_version: 1,
    id,
    enabled: form.elements.enabled.checked,
    category: form.elements.category.value,
    display_name: name,
    summary,
    accept_conditions: {
      condition_mode: form.elements.acceptMode.value,
      conditions: conditionEditor.read(acceptEditor)
    },
    ...(state.document?.global_activation ? { global_activation: state.document.global_activation } : {}),
    objectives: objectivesFromEditor(),
    completion: { mode: form.elements.completionMode.value }
  };
  const onAccept = hookValue($('#quest-accept-event'));
  const onComplete = hookValue($('#quest-complete-event'));
  if (onAccept || onComplete) document.event_hooks = {...(onAccept ? {on_accept:onAccept}:{}),...(onComplete ? {on_complete:onComplete}:{})};
  else delete document.event_hooks;
  return document;
}

function fill(document = null) {
  const value = document || {
    id: "cobbleventure:quest/main/new_quest", enabled: true, category: "main",
    display_name: { ko_kr: "새 퀘스트" }, summary: { ko_kr: "퀘스트 설명" },
    accept_conditions: { condition_mode: "all", conditions: [] }, objectives: defaultObjectives,
    completion: { mode: "npc_turn_in" }
  };
  form.elements.id.value = value.id || "";
  form.elements.category.value = value.category || "side";
  form.elements.nameKo.value = value.display_name?.ko_kr || "";
  form.elements.nameEn.value = value.display_name?.en_us || "";
  form.elements.summaryKo.value = value.summary?.ko_kr || "";
  form.elements.summaryEn.value = value.summary?.en_us || "";
  form.elements.enabled.checked = value.enabled !== false;
  form.elements.acceptMode.value = value.accept_conditions?.condition_mode || "all";
  conditionEditor.render(acceptEditor, value.accept_conditions?.conditions || []);
  renderObjectives(value.objectives || defaultObjectives);
  form.elements.completionMode.value = value.completion?.mode || "npc_turn_in";
  renderHookPicker($('#quest-accept-event'), value.event_hooks?.on_accept, '퀘스트 수락 이벤트');
  renderHookPicker($('#quest-complete-event'), value.event_hooks?.on_complete, '퀘스트 완료 이벤트');
  state.dirty = false;
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
  ).map(item => `<button class="quest-card ${item.path === state.path ? "is-active" : ""}" data-path="${escapeHtml(item.path)}"><strong>${escapeHtml(item.name || item.id)}</strong><small>${escapeHtml(item.category || "side")} · ${escapeHtml(item.id)}</small></button>`).join("") || "<small>저장된 퀘스트가 없습니다.</small>";
}

async function openQuest(path) {
  if (state.dirty && !confirm('저장하지 않은 퀘스트 변경을 버리고 다른 퀘스트를 열까요?')) return;
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
      method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(document)
    });
    const payload = await response.json();
    showIssues(payload);
    if (!response.ok || !payload.saved) {
      if (!payload.issues?.length) throw new Error(payload.error || "퀘스트를 저장하지 못했습니다.");
      return;
    }
    state.path = payload.path; state.document = document;
    await loadList(); fill(document);
  } catch (error) { showIssues({ issues: [{ path: "$", message: error.message }] }); }
}

$("#quest-list").addEventListener("click", event => {
  const button = event.target.closest("[data-path]"); if (button) openQuest(button.dataset.path).catch(error => showIssues({issues:[{path:"$",message:error.message}]}));
});
$("#quest-search").addEventListener("input", renderList);
$("#new-quest").addEventListener("click", () => { if (state.dirty && !confirm('저장하지 않은 변경을 버릴까요?')) return; state.path = ""; state.document = null; fill(); renderList(); });
$("#save-quest").addEventListener("click", saveQuest);
$("#add-objective").addEventListener("click", () => {
  const objectives = objectivesFromEditor(false);
  let index = objectives.length + 1;
  while (objectives.some(objective => objective.id === `objective_${index}`)) index++;
  objectives.push({ ...structuredClone(defaultObjectives[0]), id: `objective_${index}` });
  renderObjectives(objectives);
  state.dirty = true;
});
$("#quest-objectives").addEventListener("click", event => {
  const button = event.target.closest("[data-objective-action]");
  if (!button) return;
  const cards = [...document.querySelectorAll(".quest-objective")];
  const index = cards.indexOf(button.closest(".quest-objective"));
  const objectives = objectivesFromEditor(false);
  if (button.dataset.objectiveAction === "remove" && objectives.length > 1) objectives.splice(index, 1);
  else {
    const next = index + (button.dataset.objectiveAction === "up" ? -1 : 1);
    if (next >= 0 && next < objectives.length) [objectives[index], objectives[next]] = [objectives[next], objectives[index]];
  }
  renderObjectives(objectives);
  state.dirty = true;
});
form.addEventListener('input', event => { if (!event.target.matches('[data-hook-search]')) state.dirty = true; });
form.addEventListener('change', () => {state.dirty = true;});
form.addEventListener('click', event => {
  if (event.target.closest('[data-gate-condition-add], [data-gate-condition-remove]')) state.dirty = true;
});
window.addEventListener('beforeunload', event => {if (state.dirty) {event.preventDefault(); event.returnValue = '';}});
$('#refresh-hook-events').addEventListener('click', () => loadHookCatalogs().catch(error => showIssues({issues:[{path:'$.event_hooks',message:error.message}]})));
$('#open-hook-library').addEventListener('click', () => openHookEvent());
loadHookCatalogs().catch(error => showIssues({issues:[{path:'$.event_hooks',message:error.message}]}));
fill(); loadList().catch(error => showIssues({issues:[{path:"$",message:error.message}]}));
conditionEditor.loadCatalogs().catch(error => showIssues({issues:[{path:"$",message:error.message}]}));
