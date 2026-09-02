/* Shared selection editor extracted from the existing gate/Gym condition UI. */
(() => {
"use strict";
let getBadges = () => [];
const escapeHtml = value => String(value).replace(/[&<>"']/g, character => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[character]));
const gateResourceIdPattern = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/;
function defaultPlayerCondition(type = "item") {
  if (type === "always") return { type };
  if (type === "badge") return { type, badge: getBadges()?.[0]?.id || "cobbleventure:badge/kanto/boulder", negate: false };
  if (type === "flag") return { type, key: "cobbleventure:flag/example", value: true };
  if (type === "variable") return { type, source: "scoreboard", key: "quest.progress", operator: ">=", value: 1 };
  if (type === "pokemon") return { type, species: "cobblemon:pikachu", negate: false };
  if (type === "party_count") return { type, operator: ">=", value: 1 };
  return { type: "item", item: "minecraft:diamond", count: 1, negate: false };
}
function playerConditionTypeOptions(selected, includeAlways = false) {
  const entries = [["flag", "진행 플래그"], ["item", "아이템 소지"], ["badge", "배지 클리어"], ["variable", "숫자 변수 비교"], ["pokemon", "특정 파티 포켓몬"], ["party_count", "파티 포켓몬 수"]];
  if (includeAlways || selected === "always") entries.push(["always", "항상"]);
  if (selected === "flag_equals") entries.push(["flag_equals", "진행 플래그 (기존 형식)"]);
  if (selected === "has_item") entries.push(["has_item", "아이템 소지 (기존 형식)"]);
  return entries
    .map(([value, label]) => `<option value="${value}" ${value === selected ? "selected" : ""}>${label}</option>`).join("");
}
function playerConditionBadgeOptions(selected) {
  const entries = getBadges().map((badge) => [badge.id, `${badge.generation}세대 ${badge.order}번째 · ${badge.display_name?.ko_kr || badge.id}`]);
  if (selected && !entries.some(([id]) => id === selected)) entries.unshift([selected, selected]);
  return entries.map(([id, label]) => `<option value="${escapeHtml(id)}" ${id === selected ? "selected" : ""}>${escapeHtml(label)}</option>`).join("");
}
function playerConditionDetails(condition) {
  const selected = (value, expected) => value === expected ? "selected" : "";
  if (condition.type === "always") return "<small>항상 만족하는 조건입니다.</small>";
  if (condition.type === "badge") return `<label class="gate-condition-main"><span>배지</span><select data-gate-condition-field="badge">${playerConditionBadgeOptions(condition.badge)}</select></label><label><span>판정</span><select data-gate-condition-field="negate"><option value="false" ${selected(Boolean(condition.negate), false)}>클리어함</option><option value="true" ${selected(Boolean(condition.negate), true)}>클리어하지 않음</option></select></label>`;
  if (["flag", "flag_equals"].includes(condition.type)) {
    const valueType = typeof condition.value;
    const valueInput = valueType === "boolean"
      ? `<select data-gate-condition-field="value"><option value="true" ${selected(condition.value, true)}>완료</option><option value="false" ${selected(condition.value, false)}>미완료</option></select>`
      : `<input data-gate-condition-field="value" type="${valueType === "number" ? "number" : "text"}" value="${escapeHtml(condition.value ?? "")}">`;
    return `<label class="gate-condition-main"><span>진행 플래그 ID</span><input data-gate-condition-field="key" list="declared-variable-ids" value="${escapeHtml(condition.key || "")}" placeholder="cobbleventure:flag/story/example"></label><label><span>값 종류</span><select data-gate-condition-value-type><option value="boolean" ${selected(valueType, "boolean")}>완료 여부</option><option value="number" ${selected(valueType, "number")}>숫자</option>${condition.type === "flag_equals" ? `<option value="string" ${selected(valueType, "string")}>문자열 (기존 형식)</option>` : ""}</select></label><label><span>기준값</span>${valueInput}</label>`;
  }
  if (condition.type === "variable") return `<label><span>저장 위치</span><select data-gate-condition-field="source"><option value="scoreboard" ${selected(condition.source, "scoreboard")}>스코어보드</option><option value="persistent_data" ${selected(condition.source, "persistent_data")}>플레이어 데이터</option></select></label><label class="gate-condition-main"><span>변수 키</span><input data-gate-condition-field="key" value="${escapeHtml(condition.key || "")}" placeholder="quest.chapter"></label><label><span>비교</span><select data-gate-condition-field="operator">${["==", "!=", ">", ">=", "<", "<="].map((operator) => `<option ${selected(condition.operator, operator)}>${operator}</option>`).join("")}</select></label><label><span>기준값</span><input data-gate-condition-field="value" type="number" value="${Number(condition.value) || 0}"></label>`;
  if (condition.type === "pokemon") return `<label class="gate-condition-main"><span>포켓몬 종 ID</span><input data-gate-condition-field="species" value="${escapeHtml(condition.species || "")}" placeholder="cobblemon:pikachu"></label><label><span>판정</span><select data-gate-condition-field="negate"><option value="false" ${selected(Boolean(condition.negate), false)}>파티에 있음</option><option value="true" ${selected(Boolean(condition.negate), true)}>파티에 없음</option></select></label>`;
  if (condition.type === "party_count") return `<label><span>비교</span><select data-gate-condition-field="operator">${["==", "!=", ">", ">=", "<", "<="].map((operator) => `<option ${selected(condition.operator, operator)}>${operator}</option>`).join("")}</select></label><label class="gate-condition-main"><span>포켓몬 수</span><input data-gate-condition-field="value" type="number" min="0" max="6" step="1" value="${Math.max(0, Math.min(6, Math.floor(Number(condition.value) || 0)))}"></label>`;
  return `<label class="gate-condition-main"><span>아이템 ID</span><input data-gate-condition-field="item" list="declared-item-ids" value="${escapeHtml(condition.item || "")}" placeholder="minecraft:diamond"></label><label><span>수량</span><input data-gate-condition-field="count" type="number" min="1" value="${Math.max(1, Number(condition.count) || 1)}"></label><label><span>판정</span><select data-gate-condition-field="negate"><option value="false" ${selected(Boolean(condition.negate), false)}>소지함</option><option value="true" ${selected(Boolean(condition.negate), true)}>소지하지 않음</option></select></label>`;
}
function renderGateConditionEditor(editor, conditions = null) {
  if (!editor) return;
  if (conditions !== null) editor.gateConditions = structuredClone(conditions || []);
  editor.gateConditions ||= [];
  const list = editor.querySelector("[data-gate-condition-list]");
  list.innerHTML = editor.gateConditions.length
    ? editor.gateConditions.map((condition, index) => `<article class="gate-condition-row" data-gate-condition-index="${index}"><div class="gate-condition-row-head"><label><span>조건 종류</span><select data-gate-condition-type>${playerConditionTypeOptions(condition.type, editor.conditionOptions?.includeAlways)}</select></label><button type="button" data-gate-condition-remove aria-label="조건 삭제">삭제</button></div><div class="gate-condition-fields">${playerConditionDetails(condition)}</div></article>`).join("")
    : `<p class="gate-condition-empty">아직 조건이 없습니다. 조건 추가를 눌러 규칙을 만드세요.</p>`;
}
function validatePlayerConditions(conditions) {
  return (conditions || []).map((condition, index) => {
    const position = `${index + 1}번째 조건`;
    if (condition.type === "always") return { type: "always" };
    if (["item", "has_item"].includes(condition.type)) {
      if (!gateResourceIdPattern.test(condition.item || "")) throw new Error(`${position}의 아이템 ID가 올바르지 않습니다.`);
      return { ...condition, type: condition.type, item: condition.item, count: Math.max(1, Math.floor(Number(condition.count) || 1)), ...(condition.negate ? { negate: true } : {}) };
    }
    if (condition.type === "badge") {
      if (!gateResourceIdPattern.test(condition.badge || "")) throw new Error(`${position}의 배지를 선택해 주세요.`);
      return { type: "badge", badge: condition.badge, ...(condition.negate ? { negate: true } : {}) };
    }
    if (["flag", "flag_equals"].includes(condition.type)) {
      if (!gateResourceIdPattern.test(condition.key || "")) throw new Error(`${position}의 진행 플래그 ID가 올바르지 않습니다.`);
      return { ...condition };
    }
    if (condition.type === "pokemon") {
      if (!gateResourceIdPattern.test(condition.species || "")) throw new Error(`${position}의 포켓몬 종 ID가 올바르지 않습니다.`);
      return { type: "pokemon", species: condition.species, ...(condition.negate ? { negate: true } : {}) };
    }
    if (condition.type === "variable") {
      if (!["scoreboard", "persistent_data"].includes(condition.source) || !String(condition.key || "").trim() || !["==", "!=", ">", ">=", "<", "<="].includes(condition.operator) || !Number.isFinite(Number(condition.value))) throw new Error(`${position}의 변수 비교 설정이 올바르지 않습니다.`);
      return { type: "variable", source: condition.source, key: condition.key.trim(), operator: condition.operator, value: Number(condition.value) };
    }
    if (condition.type === "party_count") {
      const value = Number(condition.value);
      if (!["==", "!=", ">", ">=", "<", "<="].includes(condition.operator) || !Number.isInteger(value) || value < 0 || value > 6) throw new Error(`${position}의 파티 포켓몬 수 설정이 올바르지 않습니다.`);
      return { type: "party_count", operator: condition.operator, value };
    }
    throw new Error(`${position}의 종류를 선택해 주세요.`);
  });
}
function gateConditionsFromEditor(selector) {
  return validatePlayerConditions((typeof selector === "string" ? document.querySelector(selector) : selector)?.gateConditions || []);
}
function initializeGateConditionEditor(editor, options = {}) {
  if (!editor || editor.dataset.initialized) return;
  editor.dataset.initialized = "true";
  editor.conditionOptions = options;
  renderGateConditionEditor(editor, []);
  editor.addEventListener("click", (event) => {
    if (event.target.closest("[data-gate-condition-add]")) editor.gateConditions.push(defaultPlayerCondition());
    else {
      const remove = event.target.closest("[data-gate-condition-remove]");
      if (!remove) return;
      editor.gateConditions.splice(Number(remove.closest("[data-gate-condition-index]").dataset.gateConditionIndex), 1);
    }
    renderGateConditionEditor(editor);
    options.onChange?.();
  });
  const update = (event) => {
    const row = event.target.closest("[data-gate-condition-index]");
    if (!row) return;
    const index = Number(row.dataset.gateConditionIndex);
    if (event.target.matches("[data-gate-condition-type]")) {
      if (event.type !== "change") return;
      editor.gateConditions[index] = defaultPlayerCondition(event.target.value);
      renderGateConditionEditor(editor);
      options.onChange?.();
      return;
    }
    if (event.target.matches("[data-gate-condition-value-type]")) {
      if (event.type !== "change") return;
      editor.gateConditions[index].value = event.target.value === "number" ? 0 : event.target.value === "string" ? "" : true;
      renderGateConditionEditor(editor);
      options.onChange?.();
      return;
    }
    const field = event.target.dataset.gateConditionField;
    if (!field) return;
    let value = event.target.value;
    if (field === "value" && ["flag", "flag_equals"].includes(editor.gateConditions[index].type)) value = typeof editor.gateConditions[index].value === "boolean" ? value === "true" : typeof editor.gateConditions[index].value === "number" ? Number(value) : value;
    else if (["count", "value"].includes(field)) value = Number(value);
    if (field === "negate") value = value === "true";
    editor.gateConditions[index][field] = value;
    options.onChange?.();
  };
  editor.addEventListener("input", update);
  editor.addEventListener("change", update);
}
globalThis.PlayerConditionEditor = {
  async loadCatalogs() {
    const request = async path => {
      const response = await fetch(path);
      if (!response.ok) throw new Error("조건 선택 목록을 불러오지 못했습니다: " + path);
      return response.json();
    };
    const [catalog, definitions] = await Promise.all([request("/api/badges"), request("/api/game-definitions")]);
    getBadges = () => catalog.badges || [];
    for (const [id, entries] of [["declared-variable-ids", definitions.variables || []], ["declared-item-ids", definitions.items || []]]) {
      const list = document.getElementById(id);
      if (list) list.innerHTML = entries.map(entry => `<option value="${escapeHtml(entry.id)}">${escapeHtml(entry.display_name?.ko_kr || entry.id)}</option>`).join("");
    }
    document.querySelectorAll(".gate-condition-builder").forEach(editor => {
      if (editor.dataset.initialized) renderGateConditionEditor(editor);
    });
  },
  configure(options) { if (options.getBadges) getBadges = options.getBadges; },
  initialize: initializeGateConditionEditor,
  render: renderGateConditionEditor,
  read: gateConditionsFromEditor,
  validate: validatePlayerConditions,
  defaultCondition: defaultPlayerCondition,
  typeOptions: playerConditionTypeOptions,
  badgeOptions: playerConditionBadgeOptions,
  escapeHtml
};
})();
