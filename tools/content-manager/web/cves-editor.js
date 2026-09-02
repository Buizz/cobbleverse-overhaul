const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => [...document.querySelectorAll(selector)];

const LABELS = {
  event: "이벤트", page: "페이지", say: "대사", narrate: "설명", let: "변수",
  if: "조건", choice: "선택지", choice_option: "항목", repeat: "반복", command: "명령",
};

// Local vector icons stay crisp at any GUI scale and do not depend on emoji fonts.
const NODE_ICONS = {
  event: "M9 2 4 9h5l-2 5 6-8H8z",
  page: "M4 2h5l3 3v9H4z M9 2v4h3 M6 9h4 M6 11h3",
  say: "M2 3h12v8H7l-4 3v-3H2z M5 6h6 M5 8h4",
  narrate: "M3 2h10v12H3z M5 5h6 M5 8h6 M5 11h4",
  let: "M5 2H3v12h2 M11 2h2v12h-2 M6 6l4 4 M10 6l-4 4",
  if: "M8 2 12 6 8 10 4 6z M4 6H2v8 M12 6h2v8",
  choice: "M2 3h2v2H2z M7 4h7 M2 7h2v2H2z M7 8h7 M2 11h2v2H2z M7 12h7",
  choice_option: "M2 8h11 M9 4l4 4-4 4",
  repeat: "M3 5a5 5 0 0 1 9-1l1 2 M13 2v4H9 M13 11a5 5 0 0 1-9 1l-1-2 M3 14v-4h4",
  command: "M3 4l4 4-4 4 M9 12h4",
};

const state = {
  items: [], path: null, scriptId: null, digest: null, ast: null, source: "",
  selected: null, dirty: false, diagnostics: [], contract: null,
  collapsed: new WeakSet(), dragged: null, showAdvancedCommands: false,
  gameDefinitions: { items: [], variables: [] }, variableTarget: null,
  library: null, metadataDirty: false, sourceDirty: false, copyAst: null, loading: false,
};

// View-only lookup tables; navigation never stores DOM state in the AST.
let renderedRows = new WeakMap();
let renderedBlocks = new WeakMap();

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
  });
  let data;
  try { data = await response.json(); }
  catch { data = { error: `서버 응답을 읽을 수 없습니다. (${response.status})` }; }
  return { ok: response.ok, status: response.status, data };
}

function toast(message) {
  const target = $("#toast");
  target.textContent = message;
  target.classList.add("show");
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => target.classList.remove("show"), 2600);
}

function markDirty() {
  state.dirty = true;
  updateState("저장되지 않은 변경", "dirty");
  $("#save-script").disabled = Boolean(state.library?.managed);
}

function hasUnsavedChanges() { return state.dirty || state.metadataDirty || state.sourceDirty; }

function setDocumentBusy(busy) {
  state.loading = busy;
  for (const selector of ["main", ".lower-grid", "#library-details"]) $(selector).inert = busy;
}

function renderLibraryDetails() {
  const info = state.library;
  $("#library-details").hidden = !info;
  $("#tree-protection-note").hidden = !info || !info.managed && info.usages.length <= 1;
  if (!info) return;
  const metadata = info.metadata;
  $("#metadata-name").value = metadata.display_name;
  $("#metadata-description").value = metadata.description;
  $("#metadata-category").value = metadata.category;
  $("#metadata-tags").value = metadata.tags.join(", ");
  state.metadataDirty = false;
  $("#save-metadata").disabled = true;
  $("#library-protection").textContent = info.managed
    ? "행동 프리셋 관리 · 트리는 읽기 전용입니다. NPC에서 사용자 정의로 전환해 저장하거나 복사본을 만드세요."
    : info.usages.length > 1 ? `공유 이벤트 · 저장하면 ${info.usages.length}개 사용처에 적용됩니다. 개별 변경은 복사본을 연결하세요.` : "사용자 정의 이벤트 · NPC 프리셋 저장으로 덮어쓰지 않습니다.";
  $("#tree-protection-note").textContent = $("#library-protection").textContent;
  $("#library-usages").replaceChildren(...(info.usages.length ? info.usages.map((usage) => element("li", "", `${usage.name} · ${usage.path}`)) : [element("li", "", "저장된 NPC·바인딩·퀘스트 사용처 없음")]));
}

async function saveLibraryMetadata() {
  if (state.loading) return;
  if (!state.library) throw new Error("먼저 CVES 원본을 저장해 주세요.");
  setDocumentBusy(true);
  try {
  const path = state.path;
  const metadata = { schema_version: 1, display_name: $("#metadata-name").value,
    description: $("#metadata-description").value, category: $("#metadata-category").value,
    tags: $("#metadata-tags").value.split(",").map((tag) => tag.trim()).filter(Boolean) };
  const result = await request("/api/cves/metadata", { method: "PUT", body: JSON.stringify({ path, metadata, expected_digest: state.library.metadata_digest }) });
  if (!result.ok) throw new Error(result.data.error || "분류 정보 저장 실패");
  if (state.path !== path) return;
  state.library = result.data; renderLibraryDetails();
  state.items = state.items.map((item) => item.path === path ? result.data : item); renderScriptList();
  toast("분류 정보를 저장했습니다. CVES 실행 원본은 변경하지 않았습니다.");
  } finally { setDocumentBusy(false); }
}

function updateState(label, className = "") {
  const target = $("#document-state");
  target.textContent = label;
  target.className = `state-pill ${className}`.trim();
}

function literal(value, valueType) { return { node: "literal", value, value_type: valueType }; }
function nameExpression(name) { return { node: "name", name }; }
function argument(value, name = null) { return { node: "argument", value, name }; }
function callExpression(name, args = []) { return { node: "call", callee: nameExpression(name), arguments: args }; }
function text(value = "새 대사") { return { node: "text", value }; }
function block(statements = []) { return { node: "block", statements }; }

function newStatement(kind) {
  if (kind === "say") return { node: "say", speaker: "npc", text: text(), stable_id: null };
  if (kind === "narrate") return { node: "narrate", text: text("새 설명"), stable_id: null };
  if (kind === "let") return { node: "let", name: "value", value: literal(0, "int"), stable_id: null };
  if (kind === "if") return { node: "if", condition: literal(true, "bool"), then_block: block(), else_block: block(), stable_id: null };
  if (kind === "choice") return {
    node: "choice", prompt: text("선택하세요."), result: null, stable_id: null,
    options: [
      { node: "choice_option", text: text("예"), block: block() },
      { node: "choice_option", text: text("아니오"), block: block() },
    ],
  };
  if (kind === "repeat") return { node: "repeat", count: literal(1, "int"), block: block(), stable_id: null };
  return { node: "command", kind: "stop", arguments: [], properties: [], awaited: false, result: null, stable_id: null };
}

async function loadContract() {
  const result = await request("/api/cves/editor-contract");
  if (!result.ok) throw new Error(result.data.error || "CVES 편집 계약을 불러오지 못했습니다.");
  state.contract = result.data;
  const triggerSelect = $("#new-script-form select[name=trigger]");
  triggerSelect.replaceChildren();
  state.contract.triggers.forEach((trigger) => { const option = element("option", "", trigger.id); option.value = trigger.id; option.selected = trigger.id === "interact"; triggerSelect.append(option); });
}

async function loadGameDefinitions() {
  const result = await request("/api/game-definitions");
  if (!result.ok) throw new Error(result.data.error || "게임 데이터 변수를 불러오지 못했습니다.");
  state.gameDefinitions = result.data;
  state.gameDefinitions.items ||= [];
  state.gameDefinitions.variables ||= [];
}

function commandContract(kind) {
  return state.contract?.commands?.find((value) => value.id === kind);
}

function triggerContract(kind) {
  return state.contract?.triggers?.find((value) => value.id === kind);
}

function resetTrigger(trigger, kind) {
  const contract = triggerContract(kind); trigger.name = kind; trigger.arguments = [];
  for (const parameter of contract?.arguments || []) {
    if (!parameter.optional) trigger.arguments.push(argument(defaultExpression(parameter), parameter.name));
  }
}

function defaultExpression(parameter) {
  if (parameter.allowed_names?.length) return nameExpression(parameter.allowed_names[0]);
  if (parameter.resource_kind) return literal(state.contract?.resources?.[parameter.resource_kind]?.[0] || "", "string");
  if (parameter.types?.includes("bool")) return literal(false, "bool");
  if (parameter.types?.includes("int")) return literal(0, "int");
  if (parameter.types?.includes("decimal")) return literal("0.0", "decimal");
  if (parameter.types?.includes("location_ref") || parameter.types?.includes("position")) {
    return callExpression("relative", [
      argument(literal(0, "int"), "x"), argument(literal(0, "int"), "y"), argument(literal(0, "int"), "z"),
    ]);
  }
  return literal("", "string");
}

function resetCommand(node, kind) {
  const contract = commandContract(kind);
  node.kind = kind;
  node.awaited = Boolean(contract?.awaited);
  node.arguments = (contract?.positional || []).map((parameter) => argument(defaultExpression(parameter)));
  node.properties = [];
  node.result = null;
}

async function loadScripts(preferredPath = state.path, { reload = false } = {}) {
  const navigationVersion = state.navigationVersion = (state.navigationVersion || 0) + 1;
  const result = await request("/api/cves/scripts");
  if (state.navigationVersion !== navigationVersion) return;
  if (!result.ok) throw new Error(result.data.error || "CVES 목록을 불러오지 못했습니다.");
  state.items = result.data.items || [];
  renderScriptList();
  const target = preferredPath ? state.items.find((item) => item.path === preferredPath) : state.items.find((item) => !item.managed);
  if (target?.managed) { toast("행동 프리셋의 자동 생성 스크립트입니다. NPC 설정에서 행동 프리셋을 편집하세요."); return; }
  if (preferredPath && !target) toast("연결된 이벤트가 아직 없습니다. NPC 저장 여부와 이벤트 경로를 확인하세요.");
  if (target && (target.path !== state.path || reload)) await loadScript(target);
}

async function loadScript(item) {
  if (item.managed) { toast("행동 프리셋은 NPC 설정에서 편집하세요."); return; }
  if (state.loading) return;
  if (hasUnsavedChanges() && !confirm("저장하지 않은 트리·분류 변경을 버리고 다른 CVES 원본을 열까요?")) return;
  setDocumentBusy(true);
  try {
  updateState("불러오는 중");
  const result = await request(`/api/cves/script?path=${encodeURIComponent(item.path)}`);
  if (!result.ok) {
    renderDiagnostics(result.data.diagnostics || []);
    updateState("불러오기 실패", "invalid");
    throw new Error(result.data.error || "CVES 원본을 불러오지 못했습니다.");
  }
  applyDocument(result.data, item.script_id, false);
  } finally { setDocumentBusy(false); }
}

function applyDocument(document, scriptId = state.scriptId, dirty = state.dirty) {
  const changedPath = document.path && document.path !== state.path;
  if (document.library || changedPath) {
    state.library = document.library || null;
    state.metadataDirty = false;
    renderLibraryDetails();
  }
  state.path = document.path || state.path;
  state.scriptId = scriptId;
  // A new/duplicated document intentionally clears the old file's optimistic lock.
  if (Object.prototype.hasOwnProperty.call(document, "digest")) state.digest = document.digest;
  else if (changedPath) state.digest = null;
  state.ast = document.ast;
  state.collapsed = new WeakSet();
  state.dragged = null;
  state.source = document.canonical ?? document.source ?? "";
  state.selected = state.ast?.root?.events?.[0] || null;
  state.dirty = dirty;
  state.sourceDirty = false;
  state.diagnostics = document.diagnostics || [];
  $("#source-editor").value = state.source;
  $("#script-path").textContent = state.path || "이벤트 트리";
  $("#script-id").textContent = state.scriptId || "UNBOUND SCRIPT";
  $("#source-editor").disabled = !state.ast;
  $("#apply-source").disabled = !state.ast;
  $("#validate-ast").disabled = !state.ast;
  $("#save-script").disabled = !dirty;
  $("#duplicate-script").disabled = !state.ast;
  const managed = Boolean(state.library?.managed);
  $("#add-event").disabled = !state.ast || managed;
  $("#save-script").disabled = !dirty || managed;
  $("#source-editor").readOnly = managed;
  $("#apply-source").disabled = !state.ast || managed;
  $(".tree-actions").inert = managed;
  $(".node-actions").inert = managed;
  $("#inspector").inert = managed;
  $$('[data-add]').forEach((button) => { button.disabled = !state.ast; });
  renderScriptList();
  renderTree();
  renderInspector();
  renderDiagnostics(state.diagnostics);
  updateState(document.valid === false ? "진단 필요" : dirty ? "저장되지 않은 변경" : "원본과 동기화", document.valid === false ? "invalid" : dirty ? "dirty" : "valid");
}

function renderScriptList() {
  const list = $("#script-list");
  list.replaceChildren();
  if (!state.items.some(item => !item.managed)) {
    list.append(element("p", "panel-help", "사용자 정의 이벤트가 없습니다. ＋ 버튼으로 새 원본을 만드세요. 행동 프리셋은 NPC 설정에서 관리합니다."));
    return;
  }
  for (const item of state.items) {
    if (item.managed) continue;
    const query = $("#library-search").value.trim().toLocaleLowerCase();
    const category = $("#library-category").value;
    const searchable = [item.name, item.script_id, item.metadata?.description, ...(item.metadata?.tags || []), ...(item.usages || []).map((usage) => `${usage.name} ${usage.path}`)].join(" ").toLocaleLowerCase();
    if (query && !searchable.includes(query) || category && item.metadata?.category !== category) continue;
    const button = element("button", `script-button${item.path === state.path ? " active" : ""}`);
    button.type = "button";
    button.setAttribute("role", "option");
    button.setAttribute("aria-selected", String(item.path === state.path));
    button.append(element("strong", "", item.name), element("small", "", item.path));
    button.append(element("small", "", `사용처 ${item.usages?.length || 0} · ${(item.metadata?.tags || []).join(" · ")}`));
    button.addEventListener("click", () => loadScript(item).catch((error) => toast(error.message)));
    list.append(button);
  }
  if (!list.childElementCount) list.append(element("p", "panel-help", "검색 결과가 없습니다."));
}

function textSummary(value) {
  if (!value) return "";
  if (value.node === "text") return value.value;
  if (value.node === "localized_text") return value.entries.map((entry) => `${entry.language}: ${entry.value}`).join(" / ");
  return "";
}

function expressionSummary(value) {
  if (!value) return "default";
  if (value.node === "literal") return value.value_type === "string" ? JSON.stringify(value.value) : String(value.value);
  if (value.node === "name") return value.name;
  if (value.node === "member") return `${expressionSummary(value.target)}.${value.member}`;
  if (value.node === "call") return `${expressionSummary(value.callee)}(${(value.arguments || []).map((arg) => `${arg.name ? `${arg.name}: ` : ""}${arg.value ? expressionSummary(arg.value) : ""}`).join(", ")})`;
  if (value.node === "unary") return `${value.operator}${expressionSummary(value.operand)}`;
  if (value.node === "binary") return `${expressionSummary(value.left)} ${value.operator} ${expressionSummary(value.right)}`;
  return value.node || "expression";
}

function nodeLabel(node) {
  if (node.node === "event") return `${node.trigger.name} · 페이지 ${node.pages.length}`;
  if (node.node === "page") return node.condition ? `when ${expressionSummary(node.condition)}` : "default";
  if (node.node === "say") return `${node.speaker}: ${textSummary(node.text)}`;
  if (node.node === "narrate") return textSummary(node.text);
  if (node.node === "let") return `${node.name} = ${expressionSummary(node.value)}`;
  if (node.node === "if") return expressionSummary(node.condition);
  if (node.node === "choice") return textSummary(node.prompt);
  if (node.node === "choice_option") return textSummary(node.text);
  if (node.node === "repeat") return expressionSummary(node.count);
  if (node.node === "command") return `${node.awaited ? "await " : ""}${node.kind}${node.result ? ` → ${node.result}` : ""}`;
  return node.node;
}

function flowBadges(node) {
  const badges = [];
  const info = findNode(node);
  if (node.node === "page") badges.push({ text: node.condition ? `우선 ${info.index + 1}` : "FALLBACK", kind: node.condition ? "priority" : "fallback" });
  if (node.node === "choice") badges.push({ text: `${node.options?.length || 0}개 분기`, kind: "branch" });
  if (node.node === "choice_option") badges.push({ text: `선택 ${info.index + 1}`, kind: "branch" });
  if (node.node === "repeat") badges.push({ text: "반복", kind: "branch" });
  if (node.node === "command") {
    const contract = commandContract(node.kind);
    if (contract?.waits_for_completion) badges.push({ text: node.awaited ? "AWAIT" : "WAIT", kind: "await" });
    if (node.result && contract?.result_type) badges.push({ text: contract.result_type, kind: "result" });
    if (contract?.advanced) badges.push({ text: "고급", kind: "advanced" });
  }
  return badges;
}

function createNodeBadge(node) {
  const badge = element("span", "badge");
  badge.dataset.kind = node.node;
  const icon = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  icon.setAttribute("viewBox", "0 0 16 16");
  icon.setAttribute("aria-hidden", "true");
  icon.setAttribute("focusable", "false");
  const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
  path.setAttribute("d", NODE_ICONS[node.node] || NODE_ICONS.command);
  icon.append(path);
  badge.append(icon, element("span", "", LABELS[node.node] || node.node));
  return badge;
}

function navigateBranch(node, branch) {
  if (node.node !== "if" || !["then", "else"].includes(branch) || !findNode(node)) return;
  const block = branch === "then" ? node.then_block : node.else_block;
  if (!block) return;
  // Include ancestors so the same navigation remains safe from any editor entry point.
  for (let ancestor = node; ancestor; ancestor = findNode(ancestor)?.parent) state.collapsed.delete(ancestor);
  const first = block.statements[0];
  state.selected = first || node;
  renderTree(); renderInspector();
  const target = first ? renderedRows.get(first) : renderedBlocks.get(block);
  focusTreeDestination(target);
}

function navigateCondition(node) {
  if (node?.node !== "if" || !findNode(node)) return;
  for (let ancestor = node; ancestor; ancestor = findNode(ancestor)?.parent) state.collapsed.delete(ancestor);
  state.selected = node;
  renderTree(); renderInspector();
  focusTreeDestination(renderedRows.get(node));
}

function focusTreeDestination(target) {
  if (!target) return;
  target.classList.add("branch-destination");
  requestAnimationFrame(() => {
    if (!target.isConnected) return;
    target.focus({ preventScroll: true });
    target.scrollIntoView({ block: "center", inline: "nearest", behavior: "auto" });
  });
}

function createBranchNavigation(node) {
  const controls = element("span", "branch-jumps");
  for (const [branch, label] of [["then", "참으로 가기"], ["else", "거짓으로 가기"]]) {
    const block = branch === "then" ? node.then_block : node.else_block;
    const button = element("button", `branch-jump ${branch}`, branch === "then" ? "T" : "F");
    button.type = "button";
    button.setAttribute("aria-label", label);
    button.disabled = !block;
    button.title = !block ? "거짓 경로가 없습니다." : block.statements.length ? `${label} · 첫 항목 선택` : `${label} · 빈 분기 위치`;
    button.addEventListener("click", (event) => { event.stopPropagation(); navigateBranch(node, branch); });
    button.addEventListener("dragstart", (event) => { event.preventDefault(); event.stopPropagation(); });
    controls.append(button);
  }
  return controls;
}

function renderTree() {
  const tree = $("#event-tree");
  renderedRows = new WeakMap(); renderedBlocks = new WeakMap();
  tree.replaceChildren();
  tree.classList.toggle("empty", !state.ast);
  if (!state.ast) { tree.append(element("p", "", "왼쪽에서 CVES 원본을 선택하세요.")); return; }
  const events = state.ast.root.events || [];
  if (!events.length) tree.append(element("p", "panel-help", "이벤트가 없습니다. 고급 텍스트 모드에서 event를 추가하세요."));
  events.forEach((event) => tree.append(renderNode(event)));
}

function renderNode(node) {
  const wrapper = element("div", "tree-node");
  const row = element("div", `node-row${node === state.selected ? " selected" : ""}`);
  row.tabIndex = -1;
  renderedRows.set(node, row);
  const canExpand = ["event", "page", "if", "choice", "choice_option", "repeat"].includes(node.node);
  const toggle = element("button", `node-toggle${canExpand ? "" : " spacer"}`, state.collapsed.has(node) ? "▸" : "▾");
  toggle.type = "button"; toggle.disabled = !canExpand; toggle.setAttribute("aria-label", state.collapsed.has(node) ? "노드 펼치기" : "노드 접기");
  toggle.addEventListener("click", (event) => {
    event.stopPropagation();
    if (state.collapsed.has(node)) state.collapsed.delete(node); else state.collapsed.add(node);
    renderTree();
  });
  row.append(toggle, createNodeBadge(node));
  if (node.node === "if") row.append(createBranchNavigation(node));
  row.append(element("span", "label", nodeLabel(node)));
  flowBadges(node).forEach((value) => row.append(element("span", `flow-badge ${value.kind}`, value.text)));
  row.addEventListener("click", (event) => {
    event.stopPropagation(); state.selected = node; renderTree(); renderInspector();
  });
  const location = findNode(node);
  if (location?.array && !state.library?.managed) {
    row.draggable = true;
    row.addEventListener("dragstart", (event) => {
      state.dragged = node; row.classList.add("dragging"); event.dataTransfer.effectAllowed = "move";
      event.dataTransfer.setData("text/plain", node.node);
    });
    row.addEventListener("dragend", () => { state.dragged = null; row.classList.remove("dragging"); $$(".drag-over").forEach((value) => value.classList.remove("drag-over")); });
    row.addEventListener("dragover", (event) => {
      const source = findNode(state.dragged); const target = findNode(node);
      if (source?.array && source.array === target?.array && state.dragged !== node) {
        event.preventDefault(); event.dataTransfer.dropEffect = "move"; row.classList.add("drag-over");
      }
    });
    row.addEventListener("dragleave", () => row.classList.remove("drag-over"));
    row.addEventListener("drop", (event) => {
      event.preventDefault(); row.classList.remove("drag-over");
      const source = findNode(state.dragged); const target = findNode(node);
      if (!source?.array || source.array !== target?.array || state.dragged === node) return;
      const [moved] = source.array.splice(source.index, 1);
      const targetIndex = source.array.indexOf(node);
      source.array.splice(targetIndex, 0, moved); state.selected = moved; state.dragged = null;
      markDirty(); renderTree(); renderInspector();
    });
  }
  wrapper.append(row);
  if (state.collapsed.has(node)) return wrapper;
  const children = element("div", "node-children");
  if (node.node === "event") node.pages.forEach((child) => children.append(renderNode(child)));
  else if (node.node === "page") renderBlock(children, node.block, "commands");
  else if (node.node === "if") {
    renderBlock(children, node.then_block, "then", node);
    if (node.else_block) renderBlock(children, node.else_block, "else", node);
  } else if (node.node === "choice") node.options.forEach((child) => children.append(renderNode(child)));
  else if (node.node === "choice_option" || node.node === "repeat") renderBlock(children, node.block, "commands");
  if (children.childNodes.length) wrapper.append(children);
  return wrapper;
}

function renderBlock(target, value, caption, condition = null) {
  const labels = { commands: "실행 순서", then: "참 경로", else: "거짓 경로" };
  const heading = element("div", `block-caption ${caption}`, labels[caption] || caption);
  heading.tabIndex = -1;
  if (condition && (caption === "then" || caption === "else")) {
    const back = element("button", "branch-jump condition-return", "↶");
    back.type = "button";
    back.setAttribute("aria-label", "조건 노드로 가기");
    back.title = "조건 노드로 가기 · 이 경로를 소유한 조건으로 이동";
    back.addEventListener("click", (event) => { event.stopPropagation(); navigateCondition(condition); });
    heading.append(back);
  }
  renderedBlocks.set(value, heading);
  target.append(heading);
  if (!value.statements.length) target.append(element("div", "panel-help", "빈 블록"));
  value.statements.forEach((statement) => target.append(renderNode(statement)));
}

function field(form, label, value, onInput, options = {}) {
  const wrapper = element("div", "field");
  wrapper.append(element("label", "", label));
  const input = options.multiline ? element("textarea") : element("input");
  input.value = value ?? "";
  if (options.placeholder) input.placeholder = options.placeholder;
  input.addEventListener("input", () => onInput(input.value));
  wrapper.append(input); form.append(wrapper); return input;
}

function localVariableField(form, label, value, onInput) {
  const input = field(form, label, value, onInput, { placeholder: "예: battle_result" });
  input.pattern = "[A-Za-z_][A-Za-z0-9_]*";
  input.parentElement.append(element(
    "small", "resource-hint",
    "현재 이벤트 실행 중에만 존재하는 임시 변수입니다. 저장되는 진행 변수와는 별개입니다.",
  ));
  return input;
}

function readonlyField(form, label, value) {
  const wrapper = element("div", "field");
  wrapper.append(element("label", "", label), element("div", "readonly-value", value));
  form.append(wrapper);
}

function selectField(form, label, value, values, onChange) {
  const wrapper = element("div", "field"); wrapper.append(element("label", "", label));
  const select = element("select");
  values.forEach((item) => { const option = element("option", "", item); option.value = item; option.selected = item === value; select.append(option); });
  select.addEventListener("change", () => onChange(select.value)); wrapper.append(select); form.append(wrapper);
}

function checkboxField(form, label, checked, onChange) {
  const wrapper = element("label", "checkbox-field"); const input = element("input"); input.type = "checkbox"; input.checked = checked;
  input.addEventListener("change", () => onChange(input.checked)); wrapper.append(input, document.createTextNode(label)); form.append(wrapper);
}

function resourceDatalist(kind) {
  const id = `resource-${kind || "all"}`;
  let list = document.getElementById(id);
  if (list) return id;
  list = element("datalist"); list.id = id;
  const values = kind
    ? state.contract?.resources?.[kind] || []
    : Object.values(state.contract?.resources || {}).flat();
  [...new Set(values)].sort().forEach((value) => { const option = element("option"); option.value = value; list.append(option); });
  document.body.append(list); return id;
}

function resourceField(form, label, value, kind, onInput) {
  if (["flag", "variable"].includes(kind)) {
    return variableResourceField(form, label, value, kind, onInput);
  }
  const input = field(form, label, value, onInput, { placeholder: "namespace:path" });
  input.setAttribute("list", resourceDatalist(kind));
  const hint = element("small", "resource-hint", `${kind || "resource"} 카탈로그 · ${(state.contract?.resources?.[kind] || []).length}개`);
  input.parentElement.append(hint);
  return input;
}

function declaredVariableEntries(kind) {
  const definitions = (state.gameDefinitions.variables || []).filter((entry) => (
    kind === "flag" ? entry.type === "boolean" : entry.type !== "boolean"
  ));
  const byId = new Map(definitions.map((entry) => [entry.id, entry]));
  for (const id of state.contract?.resources?.[kind] || []) {
    if (!byId.has(id)) byId.set(id, { id, scope: "generated", type: kind === "flag" ? "boolean" : "unknown" });
  }
  return [...byId.values()].sort((left, right) => left.id.localeCompare(right.id));
}

function variableResourceField(form, label, value, kind, onInput) {
  const wrapper = element("div", "field variable-resource-field");
  wrapper.append(element("label", "", label));
  const row = element("div", "inline-fields");
  const select = element("select");
  const prompt = element("option", "", "진행 변수를 선택하세요"); prompt.value = ""; select.append(prompt);
  for (const entry of declaredVariableEntries(kind)) {
    const displayName = entry.display_name?.ko_kr;
    const meta = entry.scope === "generated" ? "자동 선언" : `${entry.scope === "player" ? "플레이어" : "월드"} · ${entry.type}`;
    const option = element("option", "", `${displayName ? `${displayName} · ` : ""}${entry.id} (${meta})`);
    option.value = entry.id; option.selected = entry.id === value; select.append(option);
  }
  if (value && ![...select.options].some((option) => option.value === value)) {
    const unknown = element("option", "", `${value} (미등록)`); unknown.value = value; unknown.selected = true; select.append(unknown);
  }
  select.addEventListener("change", () => onInput(select.value));
  const add = element("button", "mini-button", "+ 새 변수"); add.type = "button";
  add.addEventListener("click", () => openVariableDialog(kind, onInput));
  row.append(select, add); wrapper.append(row);
  wrapper.append(element(
    "small", "resource-hint",
    kind === "flag" ? "게임 데이터에 선언된 boolean 진행 변수" : "게임 데이터에 선언된 integer/string 진행 변수",
  ));
  form.append(wrapper); return select;
}

function nextVariableId(kind) {
  const prefix = kind === "flag" ? "cobbleventure:flag/new_variable" : "cobbleventure:variable/new_variable";
  const ids = new Set((state.gameDefinitions.variables || []).map((entry) => entry.id));
  let id = prefix; let suffix = 2;
  while (ids.has(id)) id = `${prefix}_${suffix++}`;
  return id;
}

function updateVariableDefaultInput() {
  const form = $("#new-variable-form");
  const type = form.elements.type.value;
  const input = form.elements.default;
  if (type === "boolean") { input.type = "text"; input.removeAttribute("step"); input.value = "false"; input.placeholder = "true 또는 false"; }
  else if (type === "integer") { input.type = "number"; input.step = "1"; input.value = "0"; input.placeholder = "0"; }
  else { input.type = "text"; input.removeAttribute("step"); input.value = ""; input.placeholder = "기본 문자열"; }
}

function openVariableDialog(kind, onCreated) {
  const form = $("#new-variable-form"); form.reset();
  state.variableTarget = { kind, onCreated };
  form.elements.id.value = nextVariableId(kind);
  form.elements.type.value = kind === "flag" ? "boolean" : "integer";
  form.elements.type.disabled = kind === "flag";
  [...form.elements.type.options].forEach((option) => { option.disabled = kind === "variable" && option.value === "boolean"; });
  $("#new-variable-kind-help").textContent = kind === "flag"
    ? "이 위치는 참/거짓 진행 플래그를 사용하므로 boolean 변수로 추가합니다."
    : "이 위치는 값 저장 변수를 사용하므로 integer 또는 string 변수로 추가합니다.";
  updateVariableDefaultInput();
  $("#new-variable-dialog").showModal();
  form.elements.nameKo.focus();
}

function variableDefaultValue(type, raw) {
  if (type === "boolean") {
    if (!['true', 'false'].includes(String(raw).toLowerCase())) throw new Error("boolean 기본값은 true 또는 false여야 합니다.");
    return String(raw).toLowerCase() === "true";
  }
  if (type === "integer") {
    const value = Number(raw); if (!Number.isInteger(value)) throw new Error("integer 기본값은 정수여야 합니다."); return value;
  }
  return String(raw);
}

async function createGameVariable(form) {
  const target = state.variableTarget;
  if (!target) return;
  const data = new FormData(form);
  const id = String(data.get("id") || "").trim();
  const nameKo = String(data.get("nameKo") || "").trim();
  const scope = String(data.get("scope") || "player");
  const type = target.kind === "flag" ? "boolean" : String(form.elements.type.value);
  if (!/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(id) || id.split("/").includes("..")) {
    throw new Error("변수 ID는 namespace:path 형식이어야 합니다.");
  }
  if (!nameKo) throw new Error("변수의 한국어 이름이 필요합니다.");
  if (target.kind === "variable" && !["integer", "string"].includes(type)) throw new Error("값 변수는 integer 또는 string이어야 합니다.");
  const latest = await request("/api/game-definitions");
  if (!latest.ok) throw new Error(latest.data.error || "최신 게임 데이터 변수를 불러오지 못했습니다.");
  const definitions = latest.data; definitions.items ||= []; definitions.variables ||= [];
  if (definitions.variables.some((entry) => entry.id === id)) throw new Error("이미 선언된 변수 ID입니다.");
  definitions.variables.push({
    id, scope, type, default: variableDefaultValue(type, form.elements.default.value),
    display_name: { ko_kr: nameKo },
    description: { ko_kr: String(data.get("description") || "").trim() },
  });
  const saved = await request("/api/game-definitions", { method: "PUT", body: JSON.stringify(definitions) });
  if (!saved.ok) {
    const message = saved.data.issues?.map((issue) => `${issue.path}: ${issue.message}`).join("\n");
    throw new Error(message || saved.data.error || "진행 변수를 저장하지 못했습니다.");
  }
  state.gameDefinitions = definitions;
  await loadContract();
  target.onCreated(id);
  state.variableTarget = null;
  $("#new-variable-dialog").close();
  renderInspector();
  toast(`진행 변수 ${id}를 추가하고 현재 필드에 선택했습니다.`);
}

function callName(expression) {
  return expression?.node === "call" && expression.callee?.node === "name" ? expression.callee.name : null;
}

function coordinateArgument(expression, name, fallback = 0) {
  let current = expression.arguments.find((value) => value.name === name);
  if (!current) { current = argument(literal(fallback, "int"), name); expression.arguments.push(current); }
  return current;
}

function locationExpression(kind) {
  if (kind === "relative") return callExpression("relative", [argument(literal(0, "int"), "x"), argument(literal(0, "int"), "y"), argument(literal(0, "int"), "z")]);
  if (kind === "position") return callExpression("position", [
    argument(literal(state.contract?.resources?.dimension?.[0] || "cobbleventure:overworld", "string"), "dimension"),
    argument(literal(0, "int"), "x"), argument(literal(64, "int"), "y"), argument(literal(0, "int"), "z"),
  ]);
  const resourceKind = { anchor: "event_anchor", settlement: "settlement", route: "route", dimension: "dimension", space: "space" }[kind];
  const value = resourceKind ? state.contract?.resources?.[resourceKind]?.[0] || "" : "";
  return callExpression(kind, [argument(literal(value, "string"))]);
}

function editLocationParameter(form, parameter, expression, onChange) {
  const kinds = ["relative", "position", "anchor", "settlement", "route", "dimension", "space"];
  let current = kinds.includes(callName(expression)) ? expression : locationExpression("relative");
  onChange(current);
  selectField(form, "목적지 종류", callName(current), kinds, (kind) => { onChange(locationExpression(kind)); markDirty(); renderTree(); renderInspector(); });
  const kind = callName(current);
  if (["relative", "position"].includes(kind)) {
    if (kind === "position") {
      const dimension = coordinateArgument(current, "dimension", "cobbleventure:overworld");
      resourceField(form, "차원", dimension.value?.value || "", "dimension", (value) => { dimension.value = literal(value, "string"); onChange(current); markDirty(); renderTree(); });
    }
    for (const coordinate of ["x", "y", "z"]) {
      const target = coordinateArgument(current, coordinate, coordinate === "y" && kind === "position" ? 64 : 0);
      const input = field(form, coordinate.toUpperCase(), target.value?.value ?? 0, (value) => { target.value = literal(Number.parseInt(value || "0", 10), "int"); onChange(current); markDirty(); renderTree(); });
      input.type = "number"; input.step = "1";
    }
    return;
  }
  const resourceKind = { settlement: "settlement", route: "route", dimension: "dimension", space: "space", anchor: "event_anchor" }[kind];
  const target = current.arguments[0] || argument(literal("", "string"));
  if (!current.arguments.length) current.arguments.push(target);
  resourceField(form, "목적지 ID", target.value?.value || "", resourceKind, (value) => { target.value = literal(value, "string"); onChange(current); markDirty(); renderTree(); });
}

function editAnchorProperty(form, node, property) {
  const destination = node.arguments.filter((value) => value.name === null)[1]?.value;
  const kind = callName(destination);
  const resourceKind = { settlement: "settlement", route: "route", dimension: "dimension", space: "space" }[kind];
  const resourceId = destination?.arguments?.[0]?.value?.value;
  const values = resourceKind && resourceId ? state.contract?.anchors?.[`${resourceKind}:${resourceId}`] || [] : [];
  const wrapper = element("div", "parameter-group"); wrapper.append(element("strong", "", "anchor"));
  const input = field(wrapper, "도착 앵커", property.value?.value || "", (value) => { property.value = literal(value, "string"); markDirty(); renderTree(); });
  if (values.length) {
    const id = `anchor-options-${Math.random().toString(36).slice(2)}`; const list = element("datalist"); list.id = id;
    values.forEach((value) => { const option = element("option"); option.value = value; list.append(option); }); document.body.append(list); input.setAttribute("list", id);
  }
  wrapper.append(element("small", "resource-hint", values.length ? `${resourceId} · 앵커 ${values.length}개` : "목적지를 먼저 선택하면 등록 앵커를 제안합니다.")); form.append(wrapper);
}

function editExpression(form, owner, key, label, onApplied = null) {
  const group = element("div", "expression-group");
  group.append(element("strong", "", label));
  const source = element("textarea", "expression-source"); source.value = expressionSummary(owner[key]); source.spellcheck = false;
  const apply = element("button", "mini-button", "식 적용"); apply.type = "button";
  const status = element("p", "expression-status", "CVES expression parser를 사용합니다.");
  apply.addEventListener("click", async () => {
    apply.disabled = true; status.className = "expression-status"; status.textContent = "해석 중…";
    const result = await request("/api/cves/expression", {
      method: "POST", body: JSON.stringify({ path: state.path || "<new-script>", source: source.value }),
    });
    apply.disabled = false;
    if (!result.ok || !result.data.expression) {
      const diagnostic = result.data.diagnostics?.[0]; status.className = "expression-status error";
      status.textContent = diagnostic ? `${diagnostic.line}:${diagnostic.column} ${diagnostic.message}` : result.data.error || "식을 해석하지 못했습니다.";
      return;
    }
    owner[key] = result.data.expression.root; source.value = result.data.canonical;
    if (onApplied) onApplied(owner[key]);
    status.textContent = "AST에 적용됨"; markDirty(); renderTree();
  });
  group.append(source, apply, status); form.append(group);
}

function conditionPreset(expression) {
  if (expression?.node === "literal" && expression.value_type === "bool") return expression.value ? "always" : "never";
  if (expression?.node === "call" && expression.callee?.node === "name" && expression.callee.name === "flag") return "flag";
  if (expression?.node === "unary" && expression.operator === "!") return "not";
  if (expression?.node === "binary" && expression.operator === "&&") return "all";
  if (expression?.node === "binary" && expression.operator === "||") return "any";
  if (expression?.node === "binary" && expression.left?.node === "call" && expression.left.callee?.name === "money") return "money";
  return "advanced";
}

function editCondition(form, owner, key, label) {
  const group = element("div", "condition-builder");
  group.append(element("strong", "condition-title", label));
  editConditionNode(group, owner, key, 0);
  form.append(group);
}

function editConditionNode(form, owner, key, depth) {
  const presets = ["always", "never", "flag", "money", "not", "all", "any", "advanced"];
  const level = element("div", `condition-level depth-${Math.min(depth, 3)}`); form.append(level);
  selectField(level, depth ? "하위 조건" : "조건 구성", conditionPreset(owner[key]), presets, (preset) => {
    const firstFlag = state.contract?.resources?.flag?.[0] || "cobbleventure:flag/example";
    if (preset === "always" || preset === "never") owner[key] = literal(preset === "always", "bool");
    else if (preset === "flag") owner[key] = callExpression("flag", [argument(literal(firstFlag, "string"))]);
    else if (preset === "money") owner[key] = { node: "binary", left: callExpression("money"), operator: ">=", right: literal(0, "int") };
    else if (preset === "not") owner[key] = { node: "unary", operator: "!", operand: literal(true, "bool") };
    else if (preset === "all" || preset === "any") owner[key] = { node: "binary", left: literal(true, "bool"), operator: preset === "all" ? "&&" : "||", right: literal(true, "bool") };
    markDirty(); renderTree(); renderInspector();
  });
  const preset = conditionPreset(owner[key]);
  if (preset === "flag") {
    const expression = owner[key];
    resourceField(level, "진행 플래그", expression.arguments?.[0]?.value?.value || "", "flag", (value) => {
      expression.arguments = [argument(literal(value, "string"))]; markDirty(); renderTree();
    });
  } else if (preset === "money") {
    const expression = owner[key];
    selectField(level, "비교", expression.operator, [">=", ">", "==", "!=", "<", "<="], (value) => { expression.operator = value; markDirty(); renderTree(); });
    field(level, "금액", expression.right?.value ?? 0, (value) => { expression.right = literal(Number.parseInt(value || "0", 10), "int"); markDirty(); renderTree(); });
  } else if (preset === "not" && depth < 4) {
    editConditionNode(level, owner[key], "operand", depth + 1);
  } else if ((preset === "all" || preset === "any") && depth < 4) {
    const columns = element("div", "condition-columns"); level.append(columns);
    editConditionNode(columns, owner[key], "left", depth + 1);
    editConditionNode(columns, owner[key], "right", depth + 1);
  } else if (preset === "advanced" || depth >= 4) {
    editExpression(level, owner, key, "CVES 고급 식");
  }
}

function editParameter(form, parameter, expression, onChange) {
  const group = element("div", "parameter-group"); group.append(element("strong", "", parameter.name));
  const meta = [parameter.types?.join(" | "), parameter.resource_kind, parameter.optional ? "optional" : "required"].filter(Boolean).join(" · ");
  group.append(element("small", "parameter-meta", meta));
  if (parameter.allowed_names?.length) {
    selectField(group, parameter.name, expression?.name || parameter.allowed_names[0], parameter.allowed_names, (value) => { onChange(nameExpression(value)); markDirty(); renderTree(); });
  } else if (parameter.resource_kind) {
    resourceField(group, parameter.name, expression?.value || "", parameter.resource_kind, (value) => { onChange(literal(value, "string")); markDirty(); renderTree(); });
  } else if (parameter.types?.includes("location_ref") || parameter.types?.includes("position")) {
    editLocationParameter(group, parameter, expression, onChange);
  } else if (parameter.types?.includes("bool")) {
    selectField(group, parameter.name, String(expression?.value ?? false), ["true", "false"], (value) => { onChange(literal(value === "true", "bool")); markDirty(); renderTree(); });
  } else if (parameter.types?.includes("int") || parameter.types?.includes("decimal")) {
    const input = field(group, parameter.name, expression?.value ?? 0, (value) => {
      const decimal = parameter.types.includes("decimal") && String(value).includes(".");
      onChange(literal(decimal ? String(value) : Number.parseInt(value || "0", 10), decimal ? "decimal" : "int")); markDirty(); renderTree();
    }); input.type = "number"; input.step = parameter.types.includes("decimal") ? "any" : "1";
  } else if (parameter.types?.includes("string")) {
    field(group, parameter.name, expression?.value ?? expressionSummary(expression), (value) => { onChange(literal(value, "string")); markDirty(); renderTree(); });
  } else {
    const holder = { value: expression || defaultExpression(parameter) };
    editExpression(group, holder, "value", parameter.name, onChange);
  }
  form.append(group);
}

function renderCommandFields(form, node) {
  const contract = commandContract(node.kind); if (!contract) return;
  const positional = node.arguments.filter((value) => value.name === null);
  contract.positional.forEach((parameter, index) => {
    if (!positional[index]) { positional[index] = argument(defaultExpression(parameter)); node.arguments.push(positional[index]); }
    editParameter(form, parameter, positional[index].value, (value) => {
      positional[index].value = value;
      if (index === 1 && callName(value) === "anchor") {
        node.properties = node.properties.filter((property) => property.name !== "anchor");
      }
    });
  });
  contract.named.forEach((parameter) => {
    let current = node.arguments.find((value) => value.name === parameter.name);
    checkboxField(form, `${parameter.name} 사용`, Boolean(current), (checked) => {
      if (checked && !current) node.arguments.push(argument(defaultExpression(parameter), parameter.name));
      if (!checked) node.arguments = node.arguments.filter((value) => value.name !== parameter.name);
      markDirty(); renderInspector(); renderTree();
    });
    current = node.arguments.find((value) => value.name === parameter.name);
    if (current) editParameter(form, parameter, current.value, (value) => { current.value = value; });
  });
  contract.flags.forEach((flagName) => checkboxField(form, flagName, node.arguments.some((value) => value.name === flagName), (checked) => {
    node.arguments = node.arguments.filter((value) => value.name !== flagName);
    if (checked) node.arguments.push(argument(null, flagName)); markDirty(); renderTree();
  }));
  contract.properties.forEach((parameter) => {
    const destinationKind = callName(positional[1]?.value);
    let current = node.properties.find((value) => value.name === parameter.name);
    if (parameter.name === "anchor" && destinationKind === "anchor" && !current) return;
    checkboxField(form, `${parameter.name} 속성`, Boolean(current), (checked) => {
      if (checked && !current) node.properties.push({ node: "property", name: parameter.name, value: defaultExpression(parameter) });
      if (!checked) node.properties = node.properties.filter((value) => value.name !== parameter.name);
      markDirty(); renderInspector(); renderTree();
    });
    current = node.properties.find((value) => value.name === parameter.name);
    if (current && parameter.name === "anchor") editAnchorProperty(form, node, current);
    else if (current) editParameter(form, parameter, current.value, (value) => { current.value = value; });
  });
  if (contract.result_type) localVariableField(form, `결과 임시 변수 · ${contract.result_type}`, node.result || "", (value) => { node.result = value || null; markDirty(); renderTree(); });
}

function renderTriggerFields(form, trigger) {
  const contract = triggerContract(trigger.name); if (!contract) return;
  contract.arguments.forEach((parameter) => {
    let current = trigger.arguments.find((value) => value.name === parameter.name);
    if (parameter.optional) {
      checkboxField(form, `${parameter.name} 사용`, Boolean(current), (checked) => {
        if (checked && !current) trigger.arguments.push(argument(defaultExpression(parameter), parameter.name));
        if (!checked) trigger.arguments = trigger.arguments.filter((value) => value.name !== parameter.name);
        markDirty(); renderInspector(); renderTree();
      });
      current = trigger.arguments.find((value) => value.name === parameter.name);
    } else if (!current) {
      current = argument(defaultExpression(parameter), parameter.name); trigger.arguments.push(current);
    }
    if (current) editParameter(form, parameter, current.value, (value) => { current.value = value; });
  });
}

function expressionType(expression) {
  if (expression?.node === "literal") return expression.value_type;
  return "inferred";
}

function variablesBefore(target) {
  let result = null;
  const initial = new Map([["player", "player"]]);
  const scanBlock = (value, incoming) => {
    const scope = new Map(incoming);
    for (const statement of value?.statements || []) {
      if (statement === target) { result = scope; return true; }
      if (statement.node === "if") {
        if (scanBlock(statement.then_block, scope) || scanBlock(statement.else_block, scope)) return true;
      } else if (statement.node === "choice") {
        for (const option of statement.options || []) {
          if (option === target) { result = new Map(scope); return true; }
          if (scanBlock(option.block, scope)) return true;
        }
      } else if (statement.node === "repeat" && scanBlock(statement.block, scope)) return true;
      if (statement.node === "command" && statement.result) scope.set(statement.result, commandContract(statement.kind)?.result_type || "inferred");
      else if (statement.node === "choice" && statement.result) scope.set(statement.result, "int");
      else if (statement.node === "let" && statement.name) scope.set(statement.name, expressionType(statement.value));
    }
    return false;
  };
  for (const event of state.ast?.root?.events || []) {
    if (event === target) return initial;
    for (const page of event.pages || []) {
      if (page === target) return initial;
      if (scanBlock(page.block, initial)) return result;
    }
  }
  return result || initial;
}

function templateVariables(target) {
  const suggestions = [{ path: "player.name", type: "string" }];
  for (const [name, type] of variablesBefore(target)) {
    if (name === "player") continue;
    const fields = state.contract?.result_fields?.[type] || [];
    if (fields.length) fields.forEach((field) => suggestions.push({ path: `${name}.${field.name}`, type: field.type }));
    else suggestions.push({ path: name, type });
  }
  return suggestions;
}

function hasFinalConsonant(value) {
  const character = [...String(value).trim()].at(-1) || "";
  const code = character.charCodeAt(0);
  return code >= 0xac00 && code <= 0xd7a3 ? (code - 0xac00) % 28 !== 0 : /[136780]$/.test(character);
}

function templateSample(path, type) {
  if (path === "player.name") return "레드";
  if (type === "localized_name") return "피카츄";
  if (type === "int" || type === "decimal") return "500";
  if (type === "bool") return "true";
  if (type === "resource_id") return "cobbleventure:example";
  if (type === "movement_result") return "도착";
  return path.split(".").at(-1);
}

function previewTemplate(source, suggestions) {
  const types = new Map(suggestions.map((value) => [value.path, value.type]));
  return String(source).replace(/\$\{([A-Za-z_][A-Za-z0-9_.]*)(?:\|josa:([^}]+))?\}/g, (match, path, pair) => {
    const sample = templateSample(path, types.get(path) || "string");
    if (!pair) return sample;
    const [withFinal, withoutFinal = withFinal] = pair.split("/");
    return `${sample}${hasFinalConsonant(sample) ? withFinal : withoutFinal}`;
  });
}

function templateInsertion(path, filter = null) {
  return `\${${path}${filter ? `|${filter}` : ""}}`;
}

function appendTemplate(owner, key, path, filter = null) {
  const value = owner[key];
  if (value.node === "text") value.value += templateInsertion(path, filter);
  else for (const entry of value.entries || []) {
    const localizedFilter = entry.language === "ko_kr" ? filter : null;
    entry.value += templateInsertion(path, localizedFilter);
  }
}

function renderTemplateTools(form, owner, key, inputs) {
  const suggestions = templateVariables(owner);
  const group = element("div", "template-tools");
  group.append(element("strong", "", "대사 변수"), element("small", "parameter-meta", "앞선 명령 결과를 타입에 맞는 경로로 삽입합니다."));
  const buttons = element("div", "template-buttons");
  const insert = (path, filter = null) => {
    appendTemplate(owner, key, path, filter);
    markDirty(); renderTree(); renderInspector();
  };
  for (const suggestion of suggestions) {
    const button = element("button", "template-chip", suggestion.path); button.type = "button";
    button.addEventListener("click", () => insert(suggestion.path)); buttons.append(button);
    const filters = state.contract?.template_filters?.[suggestion.type] || [];
    if (filters.includes("josa:을/를")) {
      const josa = element("button", "template-chip josa", `${suggestion.path} + 을/를`); josa.type = "button";
      josa.addEventListener("click", () => insert(suggestion.path, "josa:을/를")); buttons.append(josa);
    }
  }
  const preview = element("div", "template-preview");
  const update = () => {
    const source = owner[key].node === "text" ? owner[key].value : owner[key].entries?.[0]?.value || "";
    preview.textContent = previewTemplate(source, suggestions) || "미리 볼 대사가 없습니다.";
  };
  inputs.forEach((input) => input.addEventListener("input", update)); update();
  group.append(buttons, element("small", "preview-label", "한국어 조사 미리보기"), preview); form.append(group);
}

function editText(form, owner, key, label) {
  const value = owner[key];
  selectField(form, `${label} 형식`, value.node === "localized_text" ? "다국어" : "단일 문자열", ["단일 문자열", "다국어"], (mode) => {
    owner[key] = mode === "다국어"
      ? { node: "localized_text", entries: [{ node: "localized_entry", language: "ko_kr", value: textSummary(value) }] }
      : text(value.node === "localized_text" ? value.entries?.[0]?.value || "" : textSummary(value));
    markDirty(); renderTree(); renderInspector();
  });
  const inputs = [];
  if (value.node === "text") {
    inputs.push(field(form, label, value.value, (next) => { value.value = next; markDirty(); renderTree(); }, { multiline: true }));
  } else {
    value.entries.forEach((entry, index) => {
      const entryBox = element("div", "locale-entry");
      const header = element("div", "locale-header");
      const language = element("input"); language.value = entry.language;
      language.setAttribute("aria-label", `${label} 언어 ${index + 1}`);
      const remove = element("button", "locale-remove", "삭제"); remove.type = "button";
      remove.disabled = value.entries.length <= 1;
      remove.addEventListener("click", () => {
        value.entries.splice(index, 1); markDirty(); renderTree(); renderInspector();
      });
      const content = element("textarea"); content.value = entry.value;
      content.setAttribute("aria-label", `${entry.language} ${label}`);
      language.addEventListener("input", () => { entry.language = language.value; markDirty(); renderTree(); });
      content.addEventListener("input", () => { entry.value = content.value; markDirty(); renderTree(); });
      header.append(language, remove); entryBox.append(header, content); form.append(entryBox); inputs.push(content);
    });
    const addLocale = element("button", "mini-button locale-add", "언어 추가"); addLocale.type = "button";
    addLocale.addEventListener("click", () => {
      const used = new Set(value.entries.map((entry) => entry.language));
      const language = ["ko_kr", "en_us", "ja_jp", "zh_cn"].find((candidate) => !used.has(candidate)) || `locale_${value.entries.length + 1}`;
      const seed = value.entries[0]?.value || "";
      const localizedSeed = language === "ko_kr"
        ? seed
        : seed.replace(/\$\{([A-Za-z_][A-Za-z0-9_.]*)\|josa:[^}]+\}/g, "\${$1}");
      value.entries.push({ node: "localized_entry", language, value: localizedSeed });
      markDirty(); renderTree(); renderInspector();
    });
    form.append(addLocale);
  }
  renderTemplateTools(form, owner, key, inputs);
}

function executionPath(target) {
  let found = null;
  const scanBlock = (value, prefix) => {
    for (let index = 0; index < (value?.statements || []).length; index += 1) {
      const statement = value.statements[index];
      const current = [...prefix, `${index + 1}. ${LABELS[statement.node] || statement.node}`];
      if (statement === target) { found = current; return true; }
      if (statement.node === "if") {
        if (scanBlock(statement.then_block, [...current, "참"]) || scanBlock(statement.else_block, [...current, "거짓"])) return true;
      } else if (statement.node === "choice") {
        for (let optionIndex = 0; optionIndex < (statement.options || []).length; optionIndex += 1) {
          const option = statement.options[optionIndex];
          const optionPath = [...current, `선택 ${optionIndex + 1}`];
          if (option === target) { found = optionPath; return true; }
          if (scanBlock(option.block, optionPath)) return true;
        }
      } else if (statement.node === "repeat" && scanBlock(statement.block, [...current, "반복 본문"])) return true;
    }
    return false;
  };
  for (let eventIndex = 0; eventIndex < (state.ast?.root?.events || []).length; eventIndex += 1) {
    const event = state.ast.root.events[eventIndex];
    const eventPath = [`이벤트 ${eventIndex + 1} · ${event.trigger.name}`];
    if (event === target) return eventPath;
    for (let pageIndex = 0; pageIndex < (event.pages || []).length; pageIndex += 1) {
      const page = event.pages[pageIndex];
      const pagePath = [...eventPath, page.condition ? `페이지 ${pageIndex + 1} · 조건 일치` : `페이지 ${pageIndex + 1} · fallback`];
      if (page === target) return pagePath;
      if (scanBlock(page.block, pagePath)) return found;
    }
  }
  return found || [];
}

function renderFlowInspector(form, node) {
  const group = element("div", "flow-inspector");
  group.append(element("strong", "", "실행 경로"));
  const path = element("div", "flow-path");
  executionPath(node).forEach((segment, index) => {
    if (index) path.append(element("span", "flow-arrow", "›"));
    path.append(element("span", "flow-segment", segment));
  });
  group.append(path);
  if (node.node === "page") group.append(element("p", "flow-help", node.condition ? "위에서부터 조건을 검사해 처음 일치한 페이지만 실행합니다." : "앞선 조건부 페이지가 모두 불일치할 때 실행합니다."));
  else if (node.node === "if") group.append(element("p", "flow-help", "조건 결과에 따라 참 또는 거짓 자식 블록으로 진입한 뒤 다음 형제로 합류합니다."));
  else if (node.node === "choice") group.append(element("p", "flow-help", "선택 결과를 변수에 저장하고 해당 항목 블록을 실행한 뒤 다음 형제로 합류합니다."));
  else if (node.node === "command" && commandContract(node.kind)?.waits_for_completion) {
    const result = commandContract(node.kind)?.result_type ? " 결과 변수를 지정했다면 값을 저장하고" : "";
    group.append(element("p", "flow-help await", `명령 완료까지 세션을 중단합니다.${result} 다음 형제에서 재개합니다.`));
  }
  form.append(group);
}

function renderInspector() {
  const host = $("#inspector"); host.replaceChildren();
  const node = state.selected; $("#node-kind").textContent = node ? node.node : "—";
  const info = node ? findNode(node) : null;
  $("#move-up").disabled = !info?.array || info.index <= 0;
  $("#move-down").disabled = !info?.array || info.index >= info.array.length - 1;
  $("#delete-node").disabled = !info?.array;
  if (!node) { host.className = "inspector-empty"; host.append(element("p", "", "트리 노드를 선택하면 편집 가능한 속성이 표시됩니다.")); return; }
  host.className = ""; const form = element("div", "inspector-form"); host.append(form); renderFlowInspector(form, node);
  if (node.node === "event") {
    selectField(form, "트리거", node.trigger.name, state.contract?.triggers?.map((value) => value.id) || [], (value) => { resetTrigger(node.trigger, value); markDirty(); renderTree(); renderInspector(); });
    renderTriggerFields(form, node.trigger);
    const addPage = element("button", "button secondary", "조건부 페이지 추가"); addPage.type = "button";
    addPage.addEventListener("click", () => {
      const page = { node: "page", condition: literal(true, "bool"), block: block() };
      const fallback = node.pages.findIndex((value) => value.condition === null);
      node.pages.splice(fallback < 0 ? node.pages.length : fallback, 0, page);
      state.selected = page; markDirty(); renderTree(); renderInspector();
    }); form.append(addPage);
  } else if (node.node === "page") {
    selectField(form, "페이지 종류", node.condition ? "조건부" : "default", ["조건부", "default"], (value) => {
      const event = findNode(node)?.parent;
      if (value === "default" && event?.pages.some((page) => page !== node && page.condition === null)) {
        toast("default 페이지는 이벤트마다 하나만 둘 수 있습니다."); renderInspector(); return;
      }
      node.condition = value === "default" ? null : literal(true, "bool");
      if (value === "default" && event) { event.pages.splice(event.pages.indexOf(node), 1); event.pages.push(node); }
      markDirty(); renderTree(); renderInspector();
    });
    if (node.condition) editCondition(form, node, "condition", "실행 조건");
  }
  else if (node.node === "say") { selectField(form, "화자", node.speaker, state.contract?.speakers || ["npc", "player", "system"], (value) => { node.speaker = value; markDirty(); renderTree(); }); editText(form, node, "text", "대사"); editStableId(form, node); }
  else if (node.node === "narrate") { editText(form, node, "text", "설명"); editStableId(form, node); }
  else if (node.node === "if") { editCondition(form, node, "condition", "조건식"); editStableId(form, node); }
  else if (node.node === "choice") { editText(form, node, "prompt", "질문"); localVariableField(form, "선택 결과 임시 변수", node.result || "", (value) => { node.result = value || null; markDirty(); }); editStableId(form, node); const addOption = element("button", "button secondary", "선택 항목 추가"); addOption.type = "button"; addOption.addEventListener("click", () => { const option = { node: "choice_option", text: text("새 선택"), block: block() }; node.options.push(option); state.selected = option; markDirty(); renderTree(); renderInspector(); }); form.append(addOption); }
  else if (node.node === "choice_option") editText(form, node, "text", "선택 문구");
  else if (node.node === "command") {
    checkboxField(form, "고급 흐름 명령 표시", state.showAdvancedCommands, (checked) => { state.showAdvancedCommands = checked; renderInspector(); });
    const commandIds = state.contract?.commands?.filter((value) => !value.advanced || state.showAdvancedCommands || value.id === node.kind).map((value) => value.id) || [];
    selectField(form, "명령", node.kind, commandIds, (value) => { resetCommand(node, value); markDirty(); renderTree(); renderInspector(); });
    const boundary = commandContract(node.kind)?.waits_for_completion
      ? node.awaited ? "명시적 await · 완료까지 중단 후 재개" : "암시적 완료 대기 · 중단 후 재개"
      : "동기 명령";
    readonlyField(form, "실행 경계", boundary);
    renderCommandFields(form, node); editStableId(form, node);
  }
  else if (node.node === "let") { localVariableField(form, "임시 변수명", node.name, (value) => { node.name = value; markDirty(); renderTree(); }); editExpression(form, node, "value", "값"); editStableId(form, node); }
  else if (node.node === "repeat") { editExpression(form, node, "count", "반복 횟수"); editStableId(form, node); }
}

function editStableId(form, node) {
  field(form, "안정 작업 ID", node.stable_id || "", (value) => { node.stable_id = value || null; markDirty(); }, { placeholder: "예: reward/give_item" });
}

function findNode(target) {
  const root = state.ast?.root; if (!root) return null;
  const scanArray = (array, parent) => {
    for (let index = 0; index < array.length; index += 1) {
      const node = array[index]; if (node === target) return { node, array, index, parent };
      const found = scanNode(node); if (found) return found;
    } return null;
  };
  const scanBlock = (value, parent) => scanArray(value?.statements || [], parent);
  const scanNode = (node) => {
    if (node.node === "event") return scanArray(node.pages, node);
    if (node.node === "page") return scanBlock(node.block, node);
    if (node.node === "if") return scanBlock(node.then_block, node) || scanBlock(node.else_block, node);
    if (node.node === "choice") return scanArray(node.options, node);
    if (node.node === "choice_option" || node.node === "repeat") return scanBlock(node.block, node);
    return null;
  };
  if (root === target) return { node: root, array: null, index: -1, parent: null };
  return scanArray(root.events, root);
}

function insertionTarget() {
  const node = state.selected; if (!node) return null;
  if (node.node === "event") return { array: node.pages[0]?.block.statements || null, index: -1 };
  if (node.node === "page") return { array: node.block.statements, index: node.block.statements.length - 1 };
  if (node.node === "if") return { array: node.then_block.statements, index: node.then_block.statements.length - 1 };
  if (node.node === "choice") return node.options[0] ? { array: node.options[0].block.statements, index: node.options[0].block.statements.length - 1 } : null;
  if (node.node === "choice_option" || node.node === "repeat") return { array: node.block.statements, index: node.block.statements.length - 1 };
  const info = findNode(node); return info?.array ? { array: info.array, index: info.index } : null;
}

function addStatement(kind) {
  const target = insertionTarget();
  if (!target?.array) { toast("문장을 넣을 페이지나 블록을 먼저 선택하세요."); return; }
  const statement = newStatement(kind); target.array.splice(target.index + 1, 0, statement); state.selected = statement;
  markDirty(); renderTree(); renderInspector();
}

function moveSelected(offset) {
  const info = findNode(state.selected); if (!info?.array) return;
  const next = info.index + offset; if (next < 0 || next >= info.array.length) return;
  [info.array[info.index], info.array[next]] = [info.array[next], info.array[info.index]];
  markDirty(); renderTree(); renderInspector();
}

function deleteSelected() {
  const info = findNode(state.selected); if (!info?.array) return;
  if (!confirm(`${LABELS[state.selected.node] || state.selected.node} 노드와 모든 자식 블록을 삭제할까요?`)) return;
  info.array.splice(info.index, 1); state.selected = info.parent || null; markDirty(); renderTree(); renderInspector();
}

function renderDiagnostics(diagnostics) {
  state.diagnostics = diagnostics;
  $("#diagnostic-count").textContent = String(diagnostics.length);
  const list = $("#diagnostic-list"); list.replaceChildren();
  if (!diagnostics.length) { list.append(element("p", "success", "문법 및 타입 검사를 통과했습니다.")); return; }
  diagnostics.forEach((item) => {
    const row = element("p", "diagnostic-item");
    const location = element("code", "", `${item.source}:${item.line}:${item.column}`);
    row.append(location, document.createTextNode(` · ${item.message}${item.token ? ` · 토큰 ${JSON.stringify(item.token)}` : ""}`)); list.append(row);
  });
}

async function validateTree() {
  if (state.sourceDirty) throw new Error("변경한 텍스트를 먼저 AST에 적용해 주세요.");
  const validatedAst = state.ast;
  const validatedPath = state.path;
  updateState("트리 검증 중");
  const result = await request("/api/cves/validate", { method: "POST", body: JSON.stringify({ path: validatedPath, ast: validatedAst }) });
  // A script switch or text replacement can finish before this validation.
  // Never display the previous document's AST or diagnostics under the new path.
  if (state.ast !== validatedAst || state.path !== validatedPath || state.sourceDirty) return result.data;
  if (!result.data.ast) { renderDiagnostics(result.data.diagnostics || []); updateState("검증 실패", "invalid"); throw new Error(result.data.error || "AST를 검증하지 못했습니다."); }
  const selected = state.selected;
  state.ast = result.data.ast; state.source = result.data.canonical; state.selected = state.ast.root.events[0] || null;
  $("#source-editor").value = state.source; renderTree(); renderInspector(); renderDiagnostics(result.data.diagnostics || []);
  updateState(result.data.valid ? (state.dirty ? "검증 완료 · 저장 필요" : "검증 완료") : "진단 필요", result.data.valid ? (state.dirty ? "dirty" : "valid") : "invalid");
  return result.data;
}

async function applySource() {
  if (state.loading) return;
  setDocumentBusy(true);
  try {
  updateState("텍스트 해석 중");
  const result = await request("/api/cves/validate", { method: "POST", body: JSON.stringify({ path: state.path, source: $("#source-editor").value }) });
  renderDiagnostics(result.data.diagnostics || []);
  if (!result.data.ast) { updateState("문법 오류", "invalid"); throw new Error(result.data.error || result.data.diagnostics?.[0]?.rendered || "CVES 문법 오류가 있습니다."); }
  applyDocument({ ...result.data, digest: state.digest }, state.scriptId, true);
  toast(result.data.valid ? "CVES 텍스트를 공통 AST에 적용했습니다." : "AST는 적용했지만 의미 진단을 확인해야 합니다.");
  } finally { setDocumentBusy(false); }
}

async function saveScript() {
  if (state.loading) return;
  if (state.sourceDirty) throw new Error("변경한 CVES 텍스트를 먼저 AST에 적용한 뒤 저장해 주세요.");
  if (state.library?.managed) throw new Error("행동 프리셋 관리 이벤트는 복사하거나 NPC에서 사용자 정의로 전환해 주세요.");
  if (state.library?.usages.length > 1 && !confirm(`이 이벤트의 ${state.library.usages.length}개 사용처에 모두 적용할까요?\n${state.library.usages.map((usage) => usage.name).join(", ")}\n개별 수정은 취소 후 복사본을 만드세요.`)) return;
  setDocumentBusy(true);
  try {
  updateState("결정적 포맷으로 저장 중");
  const result = await request("/api/cves/script", { method: "PUT", body: JSON.stringify({ path: state.path, ast: state.ast, expected_digest: state.digest, usage_digest: state.library?.usage_digest }) });
  renderDiagnostics(result.data.diagnostics || []);
  if (!result.ok) { updateState(result.status === 409 ? "외부 변경 충돌" : "저장 실패", "invalid"); throw new Error(result.data.error || "CVES를 저장하지 못했습니다."); }
  applyDocument(result.data, state.scriptId, false);
  await loadScripts(state.path);
  if (!state.library) { state.library = state.items.find((item) => item.path === state.path) || null; renderLibraryDetails(); }
  toast("CVES 권위 원본을 결정적으로 저장했습니다.");
  } finally { setDocumentBusy(false); }
}

const TRIGGER_LABELS = {
  interact: "말 걸기", proximity_enter: "주변 범위 진입", proximity_exit: "주변 범위 이탈",
  region_enter: "지역 진입", region_exit: "지역 이탈", anchor_step: "앵커 밟기",
  building_enter: "건물 진입", building_exit: "건물 이탈", dimension_enter: "차원 진입",
  dimension_exit: "차원 이탈", flag_changed: "플래그 변경", item_used: "아이템 사용",
  battle_finished: "배틀 종료", quest: "퀘스트 훅",
};
let eventDialogAst = null;

function requireEventEditable() {
  if (!state.ast || state.loading) throw new Error("편집할 스크립트를 먼저 불러오세요.");
  if (state.library?.managed) throw new Error("프리셋 관리 원본은 복사하거나 사용자 정의로 전환한 뒤 편집하세요.");
  if (state.sourceDirty) throw new Error("변경한 CVES 텍스트를 먼저 AST에 적용하세요.");
}

function uniqueTriggerExists(kind) {
  return ["interact", "quest"].includes(kind) && state.ast.root.events.some(event => event.trigger.name === kind);
}

function updateAddEventFields() {
  const form = $("#add-event-form");
  const contract = triggerContract(form.elements.trigger.value);
  const hasRange = Boolean(contract?.arguments.some(parameter => parameter.name === "range"));
  $("#add-event-range").hidden = !hasRange;
  form.elements.range.disabled = !hasRange; form.elements.range.required = hasRange;
  const target = contract?.arguments.find(parameter => parameter.name === "target");
  $("#add-event-target").hidden = !target;
  form.elements.target.disabled = !target; form.elements.target.required = Boolean(target);
  form.elements.target.replaceChildren(new Option("대상 선택", ""));
  for (const id of state.contract.resources[target?.resource_kind] || []) form.elements.target.add(new Option(id, id));
  $("#add-event-error").hidden = true;
}

function openAddEvent() {
  requireEventEditable();
  const form = $("#add-event-form"); form.reset();
  form.elements.trigger.replaceChildren();
  for (const contract of state.contract.triggers) {
    const exists = uniqueTriggerExists(contract.id);
    const option = new Option(`${TRIGGER_LABELS[contract.id] || contract.id} · ${contract.id}${exists ? " (이미 있음)" : ""}`, contract.id);
    option.disabled = exists; form.elements.trigger.add(option);
  }
  form.elements.trigger.value = uniqueTriggerExists("interact") ? "proximity_enter" : "interact";
  eventDialogAst = state.ast;
  updateAddEventFields(); $("#add-event-dialog").showModal();
}

function appendEvent(kind, values = {}) {
  requireEventEditable();
  const contract = triggerContract(kind);
  if (!contract) throw new Error("지원하지 않는 발동 조건입니다.");
  if (uniqueTriggerExists(kind)) throw new Error("말 걸기·퀘스트 이벤트는 하나만 추가할 수 있습니다. 기존 이벤트의 페이지를 편집하세요.");
  const args = [];
  if (contract.arguments.some(parameter => parameter.name === "range")) {
    const raw = String(values.range ?? "").trim();
    const range = Number(raw);
    if (!/^(?:\d+(?:\.\d*)?|\.\d+)$/.test(raw) || !Number.isFinite(range) || range <= 0) throw new Error("감지 거리는 0보다 큰 숫자여야 합니다. 지수 표기 대신 일반 소수를 입력하세요.");
    const integer = Number.isSafeInteger(range);
    let decimal = raw.startsWith(".") ? `0${raw}` : raw;
    if (!decimal.includes(".")) decimal += ".0";
    else if (decimal.endsWith(".")) decimal += "0";
    args.push(argument(literal(integer ? range : decimal, integer ? "int" : "decimal"), "range"));
  }
  const target = contract.arguments.find(parameter => parameter.name === "target");
  if (target) {
    if (!(state.contract.resources[target.resource_kind] || []).includes(values.target)) throw new Error("발동 대상 리소스를 선택하세요.");
    args.push(argument(literal(values.target, "string"), "target"));
  }
  const event = { node: "event", trigger: {node:"trigger", name:kind, arguments:args}, pages:[{node:"page",condition:null,block:block()}] };
  state.ast.root.events.push(event); state.selected = event;
  markDirty(); renderTree(); renderInspector();
  return event;
}

function scriptIdFromPath(path) {
  const parts = path.split("/");
  return parts.length >= 2 && path.endsWith(".cves")
    ? `${parts[0]}:event_script/${parts.slice(1).join("/").slice(0, -5)}` : null;
}

async function createNewScript(form) {
  const path = String(new FormData(form).get("path") || "").trim();
  const trigger = String(new FormData(form).get("trigger") || "interact");
  const scriptId = scriptIdFromPath(path);
  if (!scriptId || path.includes("..") || path.includes("\\")) throw new Error("경로는 <namespace>/<path>.cves 형식이어야 합니다.");
  if (state.items.some((item) => item.path === path)) throw new Error("이미 존재하는 CVES 원본 경로입니다.");
  if (state.loading) return;
  if (hasUnsavedChanges() && !confirm("현재 저장하지 않은 변경을 버리고 새 CVES 트리를 만들까요?")) return;
  setDocumentBusy(true);
  try {
  const triggerNode = { node: "trigger", name: trigger, arguments: [] };
  resetTrigger(triggerNode, trigger);
  const ast = state.copyAst || {
    wire_version: 1,
    root: {
      node: "program",
      events: [{
        node: "event", trigger: triggerNode,
        pages: [{ node: "page", condition: null, block: block() }],
      }],
    },
  };
  const result = await request("/api/cves/validate", { method: "POST", body: JSON.stringify({ path, ast }) });
  if (!result.data.ast) throw new Error(result.data.error || "새 CVES 트리를 만들지 못했습니다.");
  state.items.push({ path, script_id: scriptId, name: path.split("/").at(-1).slice(0, -5) });
  state.items.sort((left, right) => left.path.localeCompare(right.path));
  applyDocument({ ...result.data, path, digest: null }, scriptId, true);
  $("#new-script-dialog").close(); form.reset(); toast("새 트리를 만들었습니다. 저장 전까지 파일은 생성되지 않습니다.");
  } finally { setDocumentBusy(false); }
}

$("#refresh-scripts").addEventListener("click", () => loadScripts(state.path, { reload: true }).catch((error) => toast(error.message)));
$("#add-event").addEventListener("click", () => { try { openAddEvent(); } catch (error) { toast(error.message); } });
$("#add-event-form select[name=trigger]").addEventListener("change", updateAddEventFields);
for (const id of ["close-add-event", "cancel-add-event"]) $("#" + id).addEventListener("click", () => $("#add-event-dialog").close());
$("#add-event-dialog").addEventListener("close", () => { eventDialogAst = null; });
$("#add-event-form").addEventListener("submit", event => {
  event.preventDefault();
  try {
    if (eventDialogAst !== state.ast) throw new Error("편집 문서가 바뀌었습니다. 추가창을 다시 열어 주세요.");
    const values = Object.fromEntries(new FormData(event.currentTarget));
    const added = appendEvent(values.trigger, values);
    $("#add-event-dialog").close(); focusTreeDestination(renderedRows.get(added));
    toast("현재 스크립트에 이벤트를 추가했습니다. CVES 저장 후 적용됩니다.");
  } catch (error) { $("#add-event-error").textContent = error.message; $("#add-event-error").hidden = false; }
});
$("#new-script").addEventListener("click", () => { if (state.loading) return; state.copyAst = null; $("#new-script-dialog").showModal(); });
$("#duplicate-script").addEventListener("click", () => {
  if (state.loading) return;
  if (state.sourceDirty) { toast("텍스트 변경을 먼저 AST에 적용한 뒤 복사하세요."); return; }
  state.copyAst = structuredClone(state.ast);
  $("#new-script-form input[name=path]").value = state.path.replace(/\.cves$/, "_copy.cves");
  $("#new-script-dialog").showModal();
  toast("현재 트리를 새 경로에 복사합니다. 저장 후 NPC에서 복사본을 선택하세요.");
});
for (const id of ["library-search", "library-category"]) $("#" + id).addEventListener("input", renderScriptList);
for (const id of ["metadata-name", "metadata-description", "metadata-category", "metadata-tags"]) $("#" + id).addEventListener("input", () => { state.metadataDirty = true; $("#save-metadata").disabled = false; });
$("#save-metadata").addEventListener("click", () => saveLibraryMetadata().catch((error) => toast(error.message)));
$("#return-to-npc").addEventListener("click", () => {
  window.parent.postMessage({ type: "cves:return-to-npc" }, window.location.origin);
});
window.addEventListener("message", (event) => {
  if (event.origin !== window.location.origin || event.source !== window.parent || event.data?.type !== "cves:open") return;
  $("#return-to-npc").hidden = !event.data.fromNpc;
  $("#return-to-npc").textContent = event.data.returnLabel || "← NPC로 돌아가기";
  if (typeof event.data.path === "string") loadScripts(event.data.path, { reload: event.data.path === state.path && !hasUnsavedChanges() }).catch((error) => toast(error.message));
});
$("#source-editor").addEventListener("input", () => { state.sourceDirty = true; updateState("텍스트 변경 · AST 적용 필요", "dirty"); });
$("#close-new-script").addEventListener("click", () => $("#new-script-dialog").close());
$("#cancel-new-script").addEventListener("click", () => $("#new-script-dialog").close());
$("#new-script-form").addEventListener("submit", (event) => { event.preventDefault(); createNewScript(event.currentTarget).catch((error) => toast(error.message)); });
$("#close-new-variable").addEventListener("click", () => { state.variableTarget = null; $("#new-variable-dialog").close(); });
$("#cancel-new-variable").addEventListener("click", () => { state.variableTarget = null; $("#new-variable-dialog").close(); });
$("#new-variable-dialog").addEventListener("close", () => { state.variableTarget = null; });
$("#new-variable-form select[name=type]").addEventListener("change", updateVariableDefaultInput);
$("#new-variable-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const submit = event.currentTarget.querySelector('button[type="submit"]'); submit.disabled = true;
  createGameVariable(event.currentTarget).catch((error) => toast(error.message)).finally(() => { submit.disabled = false; });
});
$("#validate-ast").addEventListener("click", () => validateTree().catch((error) => toast(error.message)));
$("#apply-source").addEventListener("click", () => applySource().catch((error) => toast(error.message)));
$("#save-script").addEventListener("click", () => saveScript().catch((error) => toast(error.message)));
$$('[data-add]').forEach((button) => button.addEventListener("click", () => addStatement(button.dataset.add)));
$("#move-up").addEventListener("click", () => moveSelected(-1));
$("#move-down").addEventListener("click", () => moveSelected(1));
$("#delete-node").addEventListener("click", deleteSelected);
window.addEventListener("beforeunload", (event) => { if (hasUnsavedChanges()) { event.preventDefault(); event.returnValue = ""; } });

async function initialize() {
  await Promise.all([loadContract(), loadGameDefinitions()]);
  const preferredPath = new URLSearchParams(window.location.search).get("path") || state.path;
  await loadScripts(preferredPath);
  if (window.parent !== window) window.parent.postMessage({ type: "cves:ready" }, window.location.origin);
}

initialize().catch((error) => { updateState("연결 실패", "invalid"); toast(error.message); });
