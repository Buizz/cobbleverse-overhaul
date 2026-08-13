const $ = (selector, root = document) => root.querySelector(selector);

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
  pan: null,
  query: "",
  filters: { kind: "all", route: "all" },
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

function buildingCardTitle(choice) {
  if (choice.kind === "gym" || choice.label !== choice.owner) return choice.label;
  return choice.owner.split("/").pop()?.replaceAll("_", " ") || choice.owner;
}

function buildingChoices() {
  const choices = flow.graphs.map((graph) => ({
    id: graph.id, owner: graph.owner, kind: graph.kind,
    label: graph.display_name || graph.owner, graph,
  }));
  const owners = new Set(choices.map((choice) => choice.owner));
  for (const [id, metadata] of Object.entries(flow.structures)) {
    if (owners.has(id) || ["interior", "gym_interior", "league", "gym_exterior"].includes(metadata.category)) continue;
    choices.push({ id: `building:${id}`, owner: id, kind: "building", label: id, graph: null });
  }
  return choices.sort((left, right) => `${left.kind}:${left.label}`.localeCompare(`${right.kind}:${right.label}`, "ko"));
}

function renderLibrary() {
  const query = flow.query.trim().toLowerCase();
  const allChoices = buildingChoices();
  const choices = allChoices.filter((choice) => {
    const matchesQuery = !query || `${choice.label} ${choice.owner} ${choice.kind}`.toLowerCase().includes(query);
    const matchesKind = flow.filters.kind === "all" || choice.kind === flow.filters.kind;
    const hasConnections = Boolean(choice.graph?.connections?.length);
    const matchesRoute = flow.filters.route === "all"
      || (flow.filters.route === "connected" ? hasConnections : !hasConnections);
    return matchesQuery && matchesKind && matchesRoute;
  });
  $("#space-building-result-count").textContent = `${choices.length}/${allChoices.length}`;
  $("#space-building-cards").innerHTML = choices.length ? choices.map((choice) => {
    const graph = choice.graph;
    const count = graph?.nodes?.length ? graph.nodes.length - 1 : 0;
    const active = choice.id === flow.selectedGraphId;
    return `<button class="space-library-card building${active ? " is-active" : ""}" type="button" data-space-owner="${escapeHtml(choice.owner)}" data-space-kind="${choice.kind}"><i>${choice.kind === "gym" ? "GYM" : "NBT"}</i><span><strong title="${escapeHtml(choice.owner)}">${escapeHtml(buildingCardTitle(choice))}</strong><small title="${escapeHtml(choice.owner)}">${escapeHtml(choice.owner)}</small></span><b>${count}실</b></button>`;
  }).join("") : '<div class="issues empty">조건에 맞는 건물이 없습니다.</div>';

  const interiors = Object.entries(flow.structures).filter(([id, metadata]) =>
    ["interior", "gym_interior"].includes(metadata.category)
    && (!query || `${id} ${metadata.category_label || ""}`.toLowerCase().includes(query))
  );
  $("#space-interior-cards").innerHTML = interiors.length ? interiors.map(([id, metadata]) =>
    `<button class="space-library-card interior" type="button" draggable="true" data-interior-structure="${escapeHtml(id)}"><i>＋</i><span><strong>${escapeHtml(id.split("/").pop())}</strong><small>${escapeHtml(metadata.category_label || id)} · ${metadata.width || "?"}×${metadata.depth || "?"}</small></span><b>끌기</b></button>`
  ).join("") : '<div class="issues empty">사용 가능한 내부 공간이 없습니다.</div>';
}

function nodeAnchorEntries(node, type) {
  const metadata = flow.structures[node.structure] || {};
  let entries = type === "output" ? metadata.door_anchors || [] : metadata.arrival_anchors || [];
  const graph = selectedGraph();
  if (!entries.length && graph?.kind === "gym") {
    const width = Math.max(1, Number(metadata.width) || 16);
    const depth = Math.max(1, Number(metadata.depth) || 16);
    if (node.kind === "exterior") entries = [{ label: type === "output" ? "entrance" : "outside", position: [Math.floor(width / 2), 1, depth - 2] }];
    else entries = [{ label: type === "output" ? "exit" : "interior_spawn", position: [Math.floor(width / 2), 1, 2] }];
  }
  return entries;
}

function nodePorts(node, type) {
  const metadata = flow.structures[node.structure] || {};
  const entries = nodeAnchorEntries(node, type);
  const title = type === "output" ? "문" : "도착";
  if (!entries.length) return "";
  const width = Math.max(1, Number(metadata.width) || 16);
  const depth = Math.max(1, Number(metadata.depth) || 16);
  const cutoff = Number(metadata.cutaway_view?.cutoff_y || Math.ceil((Number(metadata.height) || 1) / 2));
  return entries.map((anchor) => {
    const active = type === "output" && flow.connectionDraft?.node === node.id && flow.connectionDraft?.anchor === anchor.label;
    const compatible = type === "input" && flow.connectionDraft;
    const position = Array.isArray(anchor.position) ? anchor.position : [width / 2, 1, depth / 2];
    const left = Math.max(3, Math.min(97, (Number(position[0]) + .5) / width * 100));
    const top = Math.max(3, Math.min(97, (Number(position[2]) + .5) / depth * 100));
    const aboveCut = Number(position[1]) >= cutoff;
    return `<button class="space-node-port space-map-pin ${type}${active ? " is-active" : ""}${compatible ? " is-compatible" : ""}${aboveCut ? " is-above-cut" : ""}" style="left:${left}%;top:${top}%" type="button" data-port-type="${type}" data-node-id="${escapeHtml(node.id)}" data-anchor="${escapeHtml(anchor.label)}" title="${escapeHtml(anchor.label)} · X ${position[0]} / Y ${position[1]} / Z ${position[2]}${aboveCut ? " · 절단면 위 앵커" : ""}"><i></i><span>${escapeHtml(anchor.label)}</span></button>`;
  }).join("");
}

function minecraftMapColor(blockName) {
  const name = String(blockName || "").split(":").at(-1);
  const colors = [
    [/water|bubble_column/, "#3f76e4"], [/lava/, "#ff6b16"], [/grass_block|moss/, "#78a84f"],
    [/leaves|vine/, "#56893f"], [/sandstone|sand/, "#d8c47b"], [/snow|ice/, "#dceff2"],
    [/deepslate|blackstone/, "#45434a"], [/cobblestone|stone|andesite|tuff/, "#818486"],
    [/brick|terracotta/, "#a75d4d"], [/quartz|calcite/, "#e7e1d4"], [/copper/, "#b76e4f"],
    [/iron|anvil|cauldron/, "#9ca2a3"], [/gold|yellow_/, "#e6c447"], [/diamond|cyan_/, "#53b9bc"],
    [/red_|nether_wart/, "#ad3d3d"], [/blue_|lapis/, "#4869ae"], [/purple_|amethyst/, "#9565b8"],
    [/pink_|magenta_/, "#cf79a3"], [/orange_/, "#d8873e"], [/lime_/, "#82ad48"],
    [/black_|coal/, "#343538"], [/gray_/, "#6f7376"], [/white_/, "#deddd7"], [/glass/, "#9fc7cc"],
    [/wool|concrete/, "#b8ad94"], [/planks|log|wood|stem|hyphae|barrel|chest|bookshelf|crafting_table/, "#9a7248"],
    [/dirt|mud|farmland|path/, "#806044"], [/gravel/, "#8e8984"], [/torch|lantern|glowstone|shroomlight|sea_lantern/, "#e7c66a"],
  ];
  return colors.find(([pattern]) => pattern.test(name))?.[1] || "#9b927f";
}

function shadeMapColor(hex, factor) {
  const value = Number.parseInt(hex.slice(1), 16);
  const channel = (shift) => Math.max(0, Math.min(255, Math.round(((value >> shift) & 255) * factor)));
  return `rgb(${channel(16)},${channel(8)},${channel(0)})`;
}

function drawNodeCutaways() {
  for (const canvas of document.querySelectorAll(".space-node-map-canvas")) {
    const graph = selectedGraph();
    const node = graph?.nodes?.find((candidate) => candidate.id === canvas.dataset.nodeId);
    const metadata = node ? flow.structures[node.structure] || {} : {};
    const view = metadata.cutaway_view || {};
    const width = Math.max(1, Number(metadata.width) || 16);
    const depth = Math.max(1, Number(metadata.depth) || 16);
    const context = canvas.getContext("2d");
    context.fillStyle = "#111a1e";
    context.fillRect(0, 0, canvas.width, canvas.height);
    const cellWidth = canvas.width / width, cellHeight = canvas.height / depth;
    context.strokeStyle = "rgba(190,216,210,.08)";
    context.lineWidth = 1;
    for (let x = 0; x <= width; x++) { context.beginPath(); context.moveTo(x * cellWidth, 0); context.lineTo(x * cellWidth, canvas.height); context.stroke(); }
    for (let z = 0; z <= depth; z++) { context.beginPath(); context.moveTo(0, z * cellHeight); context.lineTo(canvas.width, z * cellHeight); context.stroke(); }
    const cutoff = Math.max(1, Number(view.cutoff_y) || 1);
    for (const [x, z, y, paletteIndex] of view.blocks || []) {
      const base = minecraftMapColor(view.palette?.[paletteIndex]);
      context.fillStyle = shadeMapColor(base, .72 + Math.min(.28, Number(y) / cutoff * .28));
      context.fillRect(x * cellWidth, z * cellHeight, Math.ceil(cellWidth + .35), Math.ceil(cellHeight + .35));
    }
  }
}

function renderNodes() {
  const graph = selectedGraph();
  const layer = $("#space-flow-nodes");
  $("#space-flow-empty").hidden = Boolean(graph?.nodes?.length);
  layer.innerHTML = graph?.nodes?.map((node) => {
    const metadata = flow.structures[node.structure] || {};
    const selected = node.id === flow.selectedNodeId;
    const width = Math.max(1, Number(metadata.width) || 16);
    const depth = Math.max(1, Number(metadata.depth) || 16);
    const mapHeight = Math.max(145, Math.min(230, Math.round(270 * depth / width)));
    const cutoff = Number(metadata.cutaway_view?.cutoff_y || Math.ceil((Number(metadata.height) || 1) / 2));
    const doorPins = nodePorts(node, "output"), arrivalPins = nodePorts(node, "input");
    return `<article class="space-node${selected ? " is-selected" : ""}" data-space-node="${escapeHtml(node.id)}" style="transform:translate(${Number(node.position?.[0] || 0)}px,${Number(node.position?.[1] || 0)}px)">
      <header class="space-node-header"><span>${node.kind === "exterior" ? "오버월드" : "내부"}</span><strong>${escapeHtml(node.id)}</strong><i aria-hidden="true">⠿</i></header>
      <div class="space-node-map" style="height:${mapHeight}px">
        <canvas class="space-node-map-canvas" data-node-id="${escapeHtml(node.id)}" width="540" height="${mapHeight * 2}" aria-label="${escapeHtml(node.structure)} 높이 절반 반단면"></canvas>
        ${arrivalPins}${doorPins}
        ${!arrivalPins && !doorPins ? '<span class="space-node-no-pins">문·도착 앵커 없음</span>' : ""}
      </div>
      <div class="space-node-resource"><code>${escapeHtml(node.structure)}</code><small>반단면 Y 0–${Math.max(0, cutoff - 1)} · ${width}×${depth} · <b class="pin-key door"></b> 문 <b class="pin-key arrival"></b> 도착</small></div>
    </article>`;
  }).join("") || "";
  drawNodeCutaways();
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

function canvasPoint(event) {
  const rect = $("#space-flow-canvas").getBoundingClientRect();
  return { x: event.clientX - rect.left, y: event.clientY - rect.top };
}

function renderEdges() {
  const graph = selectedGraph();
  const svg = $("#space-flow-edges");
  svg.innerHTML = '<defs><marker id="space-flow-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z"></path></marker></defs>';
  for (const edge of graph?.connections || []) {
    const start = portCenter(edge.from?.node, edge.from?.anchor, "output");
    const end = portCenter(edge.to?.node, edge.to?.anchor, "input");
    if (!start || !end) continue;
    const bend = Math.max(80, Math.abs(end.x - start.x) * .45);
    const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
    path.setAttribute("d", `M ${start.x} ${start.y} C ${start.x + bend} ${start.y}, ${end.x - bend} ${end.y}, ${end.x} ${end.y}`);
    path.setAttribute("class", `space-flow-edge${edge.id === flow.selectedEdgeId ? " is-selected" : ""}`);
    path.setAttribute("marker-end", "url(#space-flow-arrow)");
    path.dataset.edgeId = edge.id;
    svg.append(path);
  }
  if (flow.connectionDraft?.pointer) {
    const start = portCenter(flow.connectionDraft.node, flow.connectionDraft.anchor, "output");
    const end = flow.connectionDraft.pointer;
    if (start && end) {
      const bend = Math.max(70, Math.abs(end.x - start.x) * .45);
      const preview = document.createElementNS("http://www.w3.org/2000/svg", "path");
      preview.setAttribute("d", `M ${start.x} ${start.y} C ${start.x + bend} ${start.y}, ${end.x - bend} ${end.y}, ${end.x} ${end.y}`);
      preview.setAttribute("class", "space-flow-edge is-preview");
      svg.append(preview);
    }
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
      ? `<header><p class="eyebrow">FLOW GUIDE</p><h3>${escapeHtml(graph.display_name || graph.owner)}</h3></header><div class="space-flow-help"><p>왼쪽 내부 공간 카드를 캔버스로 끌어 놓으세요.</p><p><b>나가는 문</b> 포트에서 <b>도착 지점</b> 포트까지 선을 직접 끌어 연결합니다.</p><p>빈 바닥 드래그는 화면 이동, 공간 머리글 드래그는 카드 이동입니다.</p><p>연결선을 누르면 잠금 조건과 대사를 설정할 수 있습니다.</p></div>`
      : '<div class="issues empty">왼쪽에서 오버월드 건물을 선택하세요.</div>';
  }
}

function renderAll() {
  renderLibrary();
  renderNodes();
  renderInspector();
  const graph = selectedGraph();
  setStatus(graph ? `${graph.nodes.length}개 공간 · ${(graph.connections || []).length}개 연결${flow.dirty ? " · 저장 필요" : ""}` : "연결도가 없습니다.");
}

function markDirty(updateLibrary = true) {
  flow.dirty = true;
  if (updateLibrary) renderLibrary();
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

function selectBuilding(owner, kind) {
  let graph = flow.graphs.find((candidate) => candidate.owner === owner && candidate.kind === kind);
  let created = false;
  if (!graph) {
    graph = {
      id: `building:${owner}`, kind: "building", owner, display_name: owner,
      nodes: [{ id: "exterior", kind: "exterior", structure: owner, position: [90, 170] }], connections: [],
    };
    flow.graphs.push(graph);
    created = true;
  }
  flow.selectedGraphId = graph.id;
  flow.selectedNodeId = "";
  flow.selectedEdgeId = "";
  flow.connectionDraft = null;
  if (created) flow.dirty = true;
  renderAll();
  requestAnimationFrame(fitGraph);
}

function addNode(structure, position = null) {
  const graph = selectedGraph();
  if (!graph || !structure) return;
  const id = uniqueNodeId(graph);
  const count = graph.nodes.length - 1;
  const node = { id, kind: "interior", structure, position: position || [470 + (count % 3) * 340, 90 + Math.floor(count / 3) * 260] };
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
  const source = { node: flow.connectionDraft.node, anchor: flow.connectionDraft.anchor };
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

function autoLayout() {
  const graph = selectedGraph();
  if (!graph) return;
  const exterior = graph.nodes.find((node) => node.id === "exterior");
  if (exterior) exterior.position = [100, 180];
  graph.nodes.filter((node) => node.id !== "exterior").forEach((node, index) => {
    node.position = [500 + (index % 2) * 360, 80 + Math.floor(index / 2) * 260];
  });
  markDirty();
  renderAll();
  requestAnimationFrame(fitGraph);
}

$("#save-space-flow").addEventListener("click", saveFlow);
$("#fit-space-flow").addEventListener("click", fitGraph);
$("#auto-layout-space-flow").addEventListener("click", autoLayout);
$("#space-library-search").addEventListener("input", (event) => { flow.query = event.target.value; renderLibrary(); });
$("#space-library-kind-filter").addEventListener("change", (event) => { flow.filters.kind = event.target.value; renderLibrary(); });
$("#space-library-route-filter").addEventListener("change", (event) => { flow.filters.route = event.target.value; renderLibrary(); });
$("#reset-space-library-filters").addEventListener("click", () => {
  flow.query = "";
  flow.filters = { kind: "all", route: "all" };
  $("#space-library-search").value = "";
  $("#space-library-kind-filter").value = "all";
  $("#space-library-route-filter").value = "all";
  renderLibrary();
});

$("#space-building-cards").addEventListener("click", (event) => {
  const card = event.target.closest("[data-space-owner]");
  if (card) selectBuilding(card.dataset.spaceOwner, card.dataset.spaceKind);
});

$("#space-interior-cards").addEventListener("click", (event) => {
  const card = event.target.closest("[data-interior-structure]");
  if (card && selectedGraph()) addNode(card.dataset.interiorStructure);
});
$("#space-interior-cards").addEventListener("dragstart", (event) => {
  const card = event.target.closest("[data-interior-structure]");
  if (!card || !selectedGraph()) { event.preventDefault(); return; }
  event.dataTransfer.effectAllowed = "copy";
  event.dataTransfer.setData("text/x-cobbleventure-interior", card.dataset.interiorStructure);
});

$("#space-flow-viewport").addEventListener("dragover", (event) => {
  if (!event.dataTransfer.types.includes("text/x-cobbleventure-interior")) return;
  event.preventDefault();
  event.dataTransfer.dropEffect = "copy";
  event.currentTarget.classList.add("is-drop-target");
});
$("#space-flow-viewport").addEventListener("dragleave", (event) => {
  if (!event.currentTarget.contains(event.relatedTarget)) event.currentTarget.classList.remove("is-drop-target");
});
$("#space-flow-viewport").addEventListener("drop", (event) => {
  event.preventDefault();
  event.currentTarget.classList.remove("is-drop-target");
  const structure = event.dataTransfer.getData("text/x-cobbleventure-interior");
  if (!structure || !selectedGraph()) return;
  const canvasRect = $("#space-flow-canvas").getBoundingClientRect();
  addNode(structure, [Math.max(20, Math.round(event.clientX - canvasRect.left - 140)), Math.max(20, Math.round(event.clientY - canvasRect.top - 30))]);
});

$("#space-flow-viewport").addEventListener("pointerdown", (event) => {
  if (event.target.closest(".space-node, .space-flow-controls, .space-flow-edge")) return;
  const viewport = event.currentTarget;
  flow.pan = { pointerId: event.pointerId, x: event.clientX, y: event.clientY, left: viewport.scrollLeft, top: viewport.scrollTop };
  viewport.setPointerCapture(event.pointerId);
  viewport.classList.add("is-panning");
});
$("#space-flow-viewport").addEventListener("pointermove", (event) => {
  if (!flow.pan || flow.pan.pointerId !== event.pointerId) return;
  event.currentTarget.scrollLeft = flow.pan.left - (event.clientX - flow.pan.x);
  event.currentTarget.scrollTop = flow.pan.top - (event.clientY - flow.pan.y);
});
for (const eventName of ["pointerup", "pointercancel"]) $("#space-flow-viewport").addEventListener(eventName, (event) => {
  if (!flow.pan || flow.pan.pointerId !== event.pointerId) return;
  flow.pan = null;
  event.currentTarget.classList.remove("is-panning");
});

$("#space-flow-nodes").addEventListener("click", (event) => {
  const port = event.target.closest("[data-port-type]");
  if (port) {
    event.stopPropagation();
    if (port.dataset.portType === "input" && flow.connectionDraft) {
      connectTo(port.dataset.nodeId, port.dataset.anchor);
    }
    return;
  }
  const node = event.target.closest("[data-space-node]");
  if (!node) return;
  flow.selectedNodeId = node.dataset.spaceNode;
  flow.selectedEdgeId = "";
  renderAll();
});

$("#space-flow-nodes").addEventListener("pointerdown", (event) => {
  const port = event.target.closest('.space-node-port.output[data-port-type="output"]');
  if (port) {
    event.preventDefault();
    event.stopPropagation();
    flow.connectionDraft = {
      node: port.dataset.nodeId, anchor: port.dataset.anchor,
      pointer: canvasPoint(event), pointerId: event.pointerId,
    };
    flow.selectedNodeId = "";
    flow.selectedEdgeId = "";
    port.setPointerCapture(event.pointerId);
    $("#space-flow-viewport").classList.add("is-connecting");
    port.classList.add("is-active");
    document.querySelectorAll(".space-node-port.input").forEach((target) => target.classList.add("is-compatible"));
    renderEdges();
    setStatus("강조된 도착 포트까지 선을 끌어 놓으세요.");
    return;
  }
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
  if (flow.connectionDraft?.pointerId === event.pointerId) {
    flow.connectionDraft.pointer = canvasPoint(event);
    const viewport = $("#space-flow-viewport");
    const rect = viewport.getBoundingClientRect();
    if (event.clientX > rect.right - 44) viewport.scrollLeft += 32;
    else if (event.clientX < rect.left + 44) viewport.scrollLeft = Math.max(0, viewport.scrollLeft - 32);
    if (event.clientY > rect.bottom - 44) viewport.scrollTop += 24;
    else if (event.clientY < rect.top + 44) viewport.scrollTop = Math.max(0, viewport.scrollTop - 24);
    renderEdges();
    return;
  }
  if (!flow.drag || flow.drag.pointerId !== event.pointerId) return;
  const { node, x, y, origin } = flow.drag;
  node.position = [Math.max(20, Math.round(origin[0] + event.clientX - x)), Math.max(20, Math.round(origin[1] + event.clientY - y))];
  const element = $(`[data-space-node="${CSS.escape(node.id)}"]`, $("#space-flow-nodes"));
  if (element) element.style.transform = `translate(${node.position[0]}px,${node.position[1]}px)`;
  renderEdges();
  markDirty(false);
});

for (const eventName of ["pointerup", "pointercancel"]) $("#space-flow-nodes").addEventListener(eventName, (event) => {
  if (!flow.drag || flow.drag.pointerId !== event.pointerId) return;
  event.target.closest("[data-space-node]")?.classList.remove("is-dragging");
  flow.drag = null;
  renderLibrary();
});

function finishConnection(event, cancelled = false) {
  if (!flow.connectionDraft || flow.connectionDraft.pointerId !== event.pointerId) return;
  const destination = document.elementFromPoint(event.clientX, event.clientY)?.closest('.space-node-port.input[data-port-type="input"]');
  $("#space-flow-viewport").classList.remove("is-connecting");
  if (!cancelled && destination) connectTo(destination.dataset.nodeId, destination.dataset.anchor);
  else if (cancelled) {
    flow.connectionDraft = null;
    renderAll();
  } else {
    flow.connectionDraft.pointer = null;
    flow.connectionDraft.pointerId = null;
    renderNodes();
    setStatus("연결할 도착 포트가 강조되어 있습니다. 화면을 옮긴 뒤 포트를 클릭해도 됩니다.");
  }
}
document.addEventListener("pointerup", (event) => finishConnection(event), true);
document.addEventListener("pointercancel", (event) => finishConnection(event, true), true);

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
