const $ = (selector, root = document) => root.querySelector(selector);
document.documentElement.dataset.spaceFlowModule = "ready";

const flow = {
  loaded: false,
  loading: null,
  graphs: [],
  structures: {},
  selectedGraphId: "",
  selectedNodeId: "",
  selectedEdgeId: "",
  connectionDraft: null,
  drag: null,
  dirty: false,
};

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  })[character]);
}

function selectedGraph() {
  return flow.graphs.find((graph) => graph.id === flow.selectedGraphId) || null;
}

function setStatus(message, error = false) {
  const target = $("#space-flow-status");
  target.textContent = message;
  target.classList.toggle("has-error", error);
}

async function api(path, options = {}) {
  const response = await fetch(path, { headers: { "Content-Type": "application/json" }, ...options });
  const data = await response.json().catch(() => ({ error: `HTTP ${response.status}` }));
  return { ok: response.ok, data };
}

function structureLabel(id) {
  const metadata = flow.structures[id] || {};
  return metadata.category_label ? `${metadata.category_label} · ${id}` : id;
}

function renderSelectors() {
  const graphSelect = $("#space-graph-select");
  graphSelect.innerHTML = flow.graphs.length
    ? flow.graphs.map((graph) => `<option value="${escapeHtml(graph.id)}">${graph.kind === "gym" ? "체육관" : "건물"} · ${escapeHtml(graph.display_name || graph.owner)}</option>`).join("")
    : '<option value="">연결도가 없습니다</option>';
  graphSelect.value = flow.selectedGraphId;

  const configured = new Set(flow.graphs.filter((graph) => graph.kind === "building").map((graph) => graph.owner));
  const exteriorEntries = Object.entries(flow.structures).filter(([id, metadata]) =>
    !configured.has(id) && !["interior", "gym_interior", "league", "gym_exterior"].includes(metadata.category)
  );
  $("#space-new-exterior").innerHTML = exteriorEntries.length
    ? exteriorEntries.map(([id]) => `<option value="${escapeHtml(id)}">${escapeHtml(structureLabel(id))}</option>`).join("")
    : '<option value="">추가할 외부 공간 없음</option>';
  $("#add-space-graph").disabled = !exteriorEntries.length;

  const interiorEntries = Object.entries(flow.structures).filter(([, metadata]) =>
    ["interior", "gym_interior"].includes(metadata.category)
  );
  $("#space-new-interior").innerHTML = interiorEntries.length
    ? interiorEntries.map(([id]) => `<option value="${escapeHtml(id)}">${escapeHtml(structureLabel(id))}</option>`).join("")
    : '<option value="">내부 공간 NBT 없음</option>';
  $("#add-space-node").disabled = !selectedGraph() || !interiorEntries.length;
}

function nodePorts(node, type) {
  const metadata = flow.structures[node.structure] || {};
  let entries = type === "output" ? metadata.door_anchors || [] : metadata.arrival_anchors || [];
  const graph = selectedGraph();
  if (!entries.length && graph?.kind === "gym") {
    if (node.kind === "exterior") entries = [{ label: type === "output" ? "entrance" : "outside" }];
    else entries = [{ label: type === "output" ? "exit" : "interior_spawn" }];
  }
  const title = type === "output" ? "문" : "도착";
  if (!entries.length) return `<div class="space-port-empty">${title} 포트 없음</div>`;
  return entries.map((anchor) => {
    const active = type === "output" && flow.connectionDraft?.node === node.id && flow.connectionDraft?.anchor === anchor.label;
    return `<button class="space-node-port ${type}${active ? " is-active" : ""}" type="button" data-port-type="${type}" data-node-id="${escapeHtml(node.id)}" data-anchor="${escapeHtml(anchor.label)}"><i></i><span>${escapeHtml(anchor.label)}</span></button>`;
  }).join("");
}

function renderNodes() {
  const graph = selectedGraph();
  const layer = $("#space-flow-nodes");
  $("#space-flow-empty").hidden = Boolean(graph?.nodes?.length);
  layer.innerHTML = graph?.nodes?.map((node) => {
    const metadata = flow.structures[node.structure] || {};
    const selected = node.id === flow.selectedNodeId;
    return `<article class="space-node${selected ? " is-selected" : ""}" data-space-node="${escapeHtml(node.id)}" style="transform:translate(${Number(node.position?.[0] || 0)}px,${Number(node.position?.[1] || 0)}px)">
      <header class="space-node-header"><span>${node.kind === "exterior" ? "외부" : "내부"}</span><strong>${escapeHtml(node.id)}</strong><i aria-hidden="true">⠿</i></header>
      <div class="space-node-resource"><code>${escapeHtml(node.structure)}</code><small>${escapeHtml(metadata.category_label || "NBT 공간")}${metadata.width ? ` · ${metadata.width}×${metadata.depth}` : ""}</small></div>
      <div class="space-node-ports"><div class="space-node-port-column input"><b>도착 지점</b>${nodePorts(node, "input")}</div><div class="space-node-port-column output"><b>나가는 문</b>${nodePorts(node, "output")}</div></div>
    </article>`;
  }).join("") || "";
  requestAnimationFrame(renderEdges);
}

function portCenter(nodeId, anchor, type) {
  const selector = `.space-node-port[data-port-type="${type}"][data-node-id="${CSS.escape(nodeId)}"][data-anchor="${CSS.escape(anchor)}"]`;
  const port = $(selector, $("#space-flow-nodes"));
  const canvas = $("#space-flow-canvas");
  if (!port) return null;
  const portRect = port.getBoundingClientRect();
  const canvasRect = canvas.getBoundingClientRect();
  return { x: portRect.left + portRect.width / 2 - canvasRect.left, y: portRect.top + portRect.height / 2 - canvasRect.top };
}

function renderEdges() {
  const graph = selectedGraph();
  const svg = $("#space-flow-edges");
  svg.innerHTML = "";
  for (const edge of graph?.connections || []) {
    const start = portCenter(edge.from?.node, edge.from?.anchor, "output");
    const end = portCenter(edge.to?.node, edge.to?.anchor, "input");
    if (!start || !end) continue;
    const bend = Math.max(80, Math.abs(end.x - start.x) * .45);
    const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
    path.setAttribute("d", `M ${start.x} ${start.y} C ${start.x + bend} ${start.y}, ${end.x - bend} ${end.y}, ${end.x} ${end.y}`);
    path.setAttribute("class", `space-flow-edge${edge.id === flow.selectedEdgeId ? " is-selected" : ""}`);
    path.dataset.edgeId = edge.id;
    svg.append(path);
  }
}

function renderInspector() {
  const graph = selectedGraph();
  const inspector = $("#space-flow-inspector");
  const node = graph?.nodes?.find((item) => item.id === flow.selectedNodeId);
  const edge = graph?.connections?.find((item) => item.id === flow.selectedEdgeId);
  if (node) {
    const world = Array.isArray(node.world_position) ? node.world_position : [0, 0, 0];
    inspector.innerHTML = `<header><p class="eyebrow">SPACE NODE</p><h3>${escapeHtml(node.id)}</h3><small>공간 카드 설정</small></header>
      <div class="space-inspector-fields">
        <label><span>공간 키</span><input data-node-field="id" value="${escapeHtml(node.id)}" ${node.kind === "exterior" ? "disabled" : ""}></label>
        <label><span>NBT 공간</span><select data-node-field="structure">${Object.entries(flow.structures).filter(([, metadata]) => node.kind === "exterior" ? !["interior", "gym_interior"].includes(metadata.category) : ["interior", "gym_interior"].includes(metadata.category)).map(([id]) => `<option value="${escapeHtml(id)}"${id === node.structure ? " selected" : ""}>${escapeHtml(id)}</option>`).join("")}</select></label>
        ${graph.kind === "gym" && node.kind === "interior" ? `<div class="space-world-position"><label><span>월드 X</span><input type="number" data-world-axis="0" value="${Number(world[0] || 0)}"></label><label><span>Y</span><input type="number" data-world-axis="1" value="${Number(world[1] || 0)}"></label><label><span>Z</span><input type="number" data-world-axis="2" value="${Number(world[2] || 0)}"></label></div><label><span>회전</span><select data-node-field="rotation">${["none", "clockwise_90", "clockwise_180", "counterclockwise_90"].map((value) => `<option value="${value}"${value === node.rotation ? " selected" : ""}>${value}</option>`).join("")}</select></label>` : ""}
      </div>
      ${node.kind === "interior" ? '<button class="button danger space-delete" id="delete-space-node" type="button">이 공간 삭제</button>' : ""}`;
  } else if (edge) {
    inspector.innerHTML = `<header><p class="eyebrow">DOOR ROUTE</p><h3>${escapeHtml(edge.from.node)}:${escapeHtml(edge.from.anchor)}</h3><small>${escapeHtml(edge.to.node)}:${escapeHtml(edge.to.anchor)}로 이동</small></header>
      <div class="space-inspector-fields">
        <label><span>조건 조합</span><select data-edge-field="condition_mode"><option value="all"${edge.condition_mode !== "any" ? " selected" : ""}>모두 만족</option><option value="any"${edge.condition_mode === "any" ? " selected" : ""}>하나 이상 만족</option></select></label>
        <label><span>조건 JSON</span><textarea rows="7" data-edge-json="conditions" placeholder='[{"type":"variable",…}]'>${escapeHtml(JSON.stringify(edge.conditions || [], null, 2))}</textarea><small>문 잠금 조건을 배열로 입력합니다.</small></label>
        <label><span>잠겼을 때 대사</span><textarea rows="4" data-edge-lines="locked_dialogue" placeholder="문이 잠겨 있다.">${escapeHtml((edge.locked_dialogue || []).join("\n"))}</textarea></label>
        <label><span>입장할 때 대사</span><textarea rows="4" data-edge-lines="enter_dialogue">${escapeHtml((edge.enter_dialogue || []).join("\n"))}</textarea></label>
      </div><button class="button danger space-delete" id="delete-space-edge" type="button">연결선 삭제</button>`;
  } else {
    inspector.innerHTML = graph
      ? `<header><p class="eyebrow">FLOW GUIDE</p><h3>${escapeHtml(graph.display_name || graph.owner)}</h3></header><div class="space-flow-help"><p>공간 카드를 드래그해 보기 좋은 위치에 놓으세요.</p><p><b>나가는 문</b>의 포트를 누르고 목적 공간의 <b>도착 지점</b> 포트를 누르면 연결선이 만들어집니다.</p><p>연결선을 누르면 잠금 조건과 대사를 설정할 수 있습니다.</p></div>`
      : '<div class="issues empty">연결도를 선택하거나 외부 공간을 추가하세요.</div>';
  }
}

function renderAll() {
  renderSelectors();
  renderNodes();
  renderInspector();
  const graph = selectedGraph();
  setStatus(graph ? `${graph.nodes.length}개 공간 · ${(graph.connections || []).length}개 연결${flow.dirty ? " · 저장 필요" : ""}` : "연결도가 없습니다.");
}

function markDirty() {
  flow.dirty = true;
  renderSelectors();
  const graph = selectedGraph();
  setStatus(`${graph?.nodes?.length || 0}개 공간 · ${graph?.connections?.length || 0}개 연결 · 저장 필요`);
}

async function loadFlow(force = false) {
  if (flow.loaded && !force) return;
  if (flow.loading) return flow.loading;
  setStatus("공간 연결 데이터를 불러오는 중입니다.");
  flow.loading = (async () => {
    const result = await api("/api/space-connections");
    if (!result.ok) throw new Error(result.data.error || "공간 연결 데이터를 불러오지 못했습니다.");
    flow.graphs = result.data.graphs || [];
    flow.structures = result.data.structures || {};
    flow.selectedGraphId = flow.graphs.some((graph) => graph.id === flow.selectedGraphId) ? flow.selectedGraphId : flow.graphs[0]?.id || "";
    flow.selectedNodeId = "";
    flow.selectedEdgeId = "";
    flow.connectionDraft = null;
    flow.loaded = true;
    flow.dirty = false;
    $("#space-flow-path").textContent = result.data.path || "content/catalogs/space-connections.json";
    renderAll();
  })();
  try { await flow.loading; }
  catch (error) { setStatus(error.message, true); }
  finally { flow.loading = null; }
}

function uniqueNodeId(graph) {
  let index = graph.nodes.length, id = `room_${index}`;
  while (graph.nodes.some((node) => node.id === id)) id = `room_${++index}`;
  return id;
}

function addGraph() {
  const structure = $("#space-new-exterior").value;
  if (!structure) return;
  const id = `building:${structure}`;
  flow.graphs.push({
    id, kind: "building", owner: structure, display_name: structure,
    nodes: [{ id: "exterior", kind: "exterior", structure, position: [90, 170] }], connections: [],
  });
  flow.selectedGraphId = id;
  flow.selectedNodeId = "exterior";
  markDirty();
  renderAll();
}

function addNode() {
  const graph = selectedGraph();
  const structure = $("#space-new-interior").value;
  if (!graph || !structure) return;
  const id = uniqueNodeId(graph);
  const count = graph.nodes.length - 1;
  const node = { id, kind: "interior", structure, position: [470 + (count % 3) * 340, 90 + Math.floor(count / 3) * 260] };
  if (graph.kind === "gym") Object.assign(node, { world_position: [0, 0, count * 32], rotation: "none" });
  graph.nodes.push(node);
  flow.selectedNodeId = id;
  flow.selectedEdgeId = "";
  markDirty();
  renderAll();
}

function connectTo(nodeId, anchor) {
  const graph = selectedGraph();
  if (!graph || !flow.connectionDraft) return;
  const source = flow.connectionDraft;
  graph.connections ||= [];
  graph.connections = graph.connections.filter((edge) => !(edge.from.node === source.node && edge.from.anchor === source.anchor));
  let index = graph.connections.length + 1, id = `route_${index}`;
  while (graph.connections.some((edge) => edge.id === id)) id = `route_${++index}`;
  graph.connections.push({ id, from: source, to: { node: nodeId, anchor }, condition_mode: "all", conditions: [], locked_dialogue: [], enter_dialogue: [] });
  flow.connectionDraft = null;
  flow.selectedNodeId = "";
  flow.selectedEdgeId = id;
  markDirty();
  renderAll();
}

async function saveFlow() {
  const button = $("#save-space-flow");
  button.disabled = true;
  setStatus("공간 연결과 런타임 설정을 저장하는 중입니다.");
  const result = await api("/api/space-connections", {
    method: "PUT", body: JSON.stringify({ schema_version: 1, graphs: flow.graphs }),
  });
  button.disabled = false;
  if (!result.ok) {
    const issue = result.data.issues?.find((item) => item.level === "error");
    setStatus(issue ? `${issue.path}: ${issue.message}` : result.data.error || "저장하지 못했습니다.", true);
    return;
  }
  flow.loaded = false;
  await loadFlow(true);
  setStatus("공간 연결과 건물·체육관 런타임 설정을 저장했습니다.");
}

function fitGraph() {
  const graph = selectedGraph();
  const viewport = $("#space-flow-viewport");
  if (!graph?.nodes?.length) return;
  const xs = graph.nodes.map((node) => Number(node.position?.[0] || 0));
  const ys = graph.nodes.map((node) => Number(node.position?.[1] || 0));
  viewport.scrollTo({ left: Math.max(0, Math.min(...xs) - 70), top: Math.max(0, Math.min(...ys) - 70), behavior: "smooth" });
}

$("#space-graph-select").addEventListener("change", (event) => {
  flow.selectedGraphId = event.target.value;
  flow.selectedNodeId = "";
  flow.selectedEdgeId = "";
  flow.connectionDraft = null;
  renderAll();
});
$("#add-space-graph").addEventListener("click", addGraph);
$("#add-space-node").addEventListener("click", addNode);
$("#save-space-flow").addEventListener("click", saveFlow);
$("#fit-space-flow").addEventListener("click", fitGraph);

$("#space-flow-nodes").addEventListener("click", (event) => {
  const port = event.target.closest("[data-port-type]");
  if (port) {
    event.stopPropagation();
    if (port.dataset.portType === "output") {
      flow.connectionDraft = { node: port.dataset.nodeId, anchor: port.dataset.anchor };
      flow.selectedNodeId = "";
      flow.selectedEdgeId = "";
      renderAll();
    } else if (flow.connectionDraft) connectTo(port.dataset.nodeId, port.dataset.anchor);
    return;
  }
  const node = event.target.closest("[data-space-node]");
  if (!node) return;
  flow.selectedNodeId = node.dataset.spaceNode;
  flow.selectedEdgeId = "";
  renderAll();
});

$("#space-flow-nodes").addEventListener("pointerdown", (event) => {
  const header = event.target.closest(".space-node-header");
  const element = header?.closest("[data-space-node]");
  const graph = selectedGraph();
  const node = graph?.nodes?.find((item) => item.id === element?.dataset.spaceNode);
  if (!header || !node) return;
  event.preventDefault();
  flow.drag = { node, pointerId: event.pointerId, x: event.clientX, y: event.clientY, origin: [...node.position] };
  element.setPointerCapture(event.pointerId);
  element.classList.add("is-dragging");
});

$("#space-flow-nodes").addEventListener("pointermove", (event) => {
  if (!flow.drag || flow.drag.pointerId !== event.pointerId) return;
  const { node, x, y, origin } = flow.drag;
  node.position = [Math.max(20, Math.round(origin[0] + event.clientX - x)), Math.max(20, Math.round(origin[1] + event.clientY - y))];
  const element = $(`[data-space-node="${CSS.escape(node.id)}"]`, $("#space-flow-nodes"));
  if (element) element.style.transform = `translate(${node.position[0]}px,${node.position[1]}px)`;
  renderEdges();
  markDirty();
});

for (const eventName of ["pointerup", "pointercancel"]) $("#space-flow-nodes").addEventListener(eventName, (event) => {
  if (!flow.drag || flow.drag.pointerId !== event.pointerId) return;
  event.target.closest("[data-space-node]")?.classList.remove("is-dragging");
  flow.drag = null;
});

$("#space-flow-edges").addEventListener("click", (event) => {
  const path = event.target.closest("[data-edge-id]");
  if (!path) return;
  flow.selectedEdgeId = path.dataset.edgeId;
  flow.selectedNodeId = "";
  flow.connectionDraft = null;
  renderAll();
});

$("#space-flow-inspector").addEventListener("input", (event) => {
  const graph = selectedGraph();
  const node = graph?.nodes?.find((item) => item.id === flow.selectedNodeId);
  const edge = graph?.connections?.find((item) => item.id === flow.selectedEdgeId);
  if (node && event.target.dataset.nodeField) {
    const field = event.target.dataset.nodeField;
    if (field === "id") {
      const next = event.target.value.trim();
      if (!/^[a-z0-9][a-z0-9_]*$/.test(next) || graph.nodes.some((item) => item !== node && item.id === next)) return;
      for (const route of graph.connections || []) {
        if (route.from.node === node.id) route.from.node = next;
        if (route.to.node === node.id) route.to.node = next;
      }
      node.id = next;
      flow.selectedNodeId = next;
    } else node[field] = event.target.value;
    markDirty();
    renderNodes();
  } else if (node && event.target.dataset.worldAxis !== undefined) {
    node.world_position ||= [0, 0, 0];
    node.world_position[Number(event.target.dataset.worldAxis)] = Number(event.target.value);
    markDirty();
  } else if (edge && event.target.dataset.edgeField) {
    edge[event.target.dataset.edgeField] = event.target.value;
    markDirty();
  } else if (edge && event.target.dataset.edgeLines) {
    edge[event.target.dataset.edgeLines] = event.target.value.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
    markDirty();
  } else if (edge && event.target.dataset.edgeJson) {
    try {
      const value = JSON.parse(event.target.value);
      if (!Array.isArray(value)) throw new Error("array");
      edge[event.target.dataset.edgeJson] = value;
      event.target.classList.remove("is-invalid");
      markDirty();
    } catch (_) {
      event.target.classList.add("is-invalid");
      setStatus("조건 JSON 배열을 확인해 주세요.", true);
    }
  }
});

$("#space-flow-inspector").addEventListener("click", (event) => {
  const graph = selectedGraph();
  if (event.target.id === "delete-space-edge") {
    graph.connections = graph.connections.filter((edge) => edge.id !== flow.selectedEdgeId);
    flow.selectedEdgeId = "";
    markDirty();
    renderAll();
  } else if (event.target.id === "delete-space-node") {
    graph.nodes = graph.nodes.filter((node) => node.id !== flow.selectedNodeId);
    graph.connections = (graph.connections || []).filter((edge) => edge.from.node !== flow.selectedNodeId && edge.to.node !== flow.selectedNodeId);
    flow.selectedNodeId = "";
    markDirty();
    renderAll();
  }
});

document.addEventListener("click", (event) => {
  if (event.target.closest?.('[data-section="space-connections"]')) loadFlow();
}, true);
if (document.querySelector('[data-section="space-connections"]')?.classList.contains("is-active")) loadFlow();
window.addEventListener("resize", () => { if (selectedGraph()) renderEdges(); });
