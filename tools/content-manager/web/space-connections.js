const $ = (selector, root = document) => root.querySelector(selector);

const flow = {
  loaded: false,
  loading: null,
  graphs: [],
  structures: {},
  selectedGraphId: "",
  selectedNodeId: "",
  selectedEdgeId: "",
  selectedDoorAnchor: null,
  selectedDungeonEntrance: null,
  availableDungeonEntrances: [],
  dungeonEntranceAssignments: [],
  connectionDraft: null,
  drag: null,
  pan: null,
  queries: { building: "", interior: "" },
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

function setSaveStatus(state, message, detail = message) {
  const target = $("#space-flow-save-state");
  target.dataset.state = state;
  target.textContent = message;
  target.title = detail;
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

function dungeonAssignment(structure, anchor) {
  return flow.dungeonEntranceAssignments.find((item) =>
    item.structure === structure && item.anchor === anchor
  ) || null;
}

function dungeonEntranceOptions(current = "") {
  const assignedElsewhere = new Map(flow.dungeonEntranceAssignments
    .filter((item) => item.entrance_id !== current)
    .map((item) => [item.entrance_id, `${item.structure}#${item.anchor}`]));
  return `<option value="">일반 공간 연결 문</option>${flow.availableDungeonEntrances.map((entry) => {
    const owner = assignedElsewhere.get(entry.entrance_id);
    const label = `${entry.display_name} · ${entry.entrance_id}${owner ? ` · 사용 중: ${owner}` : ""}`;
    return `<option value="${escapeHtml(entry.entrance_id)}"${entry.entrance_id === current ? " selected" : ""}${owner ? " disabled" : ""}>${escapeHtml(label)}</option>`;
  }).join("")}`;
}

function buildingCardTitle(choice) {
  if (choice.kind === "gym" || choice.label !== choice.owner) return choice.label;
  return choice.owner.split("/").pop()?.replaceAll("_", " ") || choice.owner;
}

function supportsInteriorConnections(metadata = {}) {
  return !metadata.no_interior_space
    && !["interior", "gym_interior", "league", "decoration", "natural_feature"].includes(metadata.category);
}

function buildingChoices() {
  const choices = flow.graphs.filter((graph) => {
    if (graph.kind === "gym") return true;
    const metadata = flow.structures[graph.owner] || {};
    return supportsInteriorConnections(metadata) && metadata.category !== "gym_exterior";
  }).map((graph) => ({
    id: graph.id, owner: graph.owner, kind: graph.kind,
    label: graph.display_name || graph.owner, graph,
  }));
  const owners = new Set(choices.map((choice) => choice.owner));
  for (const [id, metadata] of Object.entries(flow.structures)) {
    if (owners.has(id) || !supportsInteriorConnections(metadata) || metadata.category === "gym_exterior") continue;
    choices.push({ id: `building:${id}`, owner: id, kind: "building", label: id, graph: null });
  }
  return choices.sort((left, right) => `${left.kind}:${left.label}`.localeCompare(`${right.kind}:${right.label}`, "ko"));
}

function renderLibrary() {
  const buildingQuery = flow.queries.building.trim().toLowerCase();
  const interiorQuery = flow.queries.interior.trim().toLowerCase();
  const allChoices = buildingChoices();
  const choices = allChoices.filter((choice) => {
    const matchesQuery = !buildingQuery || `${choice.label} ${choice.owner} ${choice.kind}`.toLowerCase().includes(buildingQuery);
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
    && (!interiorQuery || `${id} ${metadata.category_label || ""}`.toLowerCase().includes(interiorQuery))
  );
  const graph = selectedGraph();
  $("#space-interior-target").textContent = graph
    ? `배치 대상: ${graph.display_name || graph.owner}`
    : "외부 건물을 먼저 선택하세요.";
  $("#space-interior-cards").innerHTML = interiors.length ? interiors.map(([id, metadata]) =>
    `<button class="space-library-card interior" type="button" draggable="${graph ? "true" : "false"}" data-interior-structure="${escapeHtml(id)}"${graph ? "" : " disabled"}><i>＋</i><span><strong>${escapeHtml(id.split("/").pop())}</strong><small>${escapeHtml(metadata.category_label || id)} · ${metadata.width || "?"}×${metadata.depth || "?"}</small></span><b>${graph ? "끌기" : "대기"}</b></button>`
  ).join("") : '<div class="issues empty">사용 가능한 내부 공간이 없습니다.</div>';
}

function nodeAnchorEntries(node) {
  const metadata = flow.structures[node.structure] || {};
  return [
    ...(metadata.door_anchors || []).map((anchor) => ({ ...anchor, connectionType: "문" })),
    ...(selectedGraph()?.kind === "building" ? metadata.transition_anchors || [] : [])
      .map((anchor) => ({ ...anchor, connectionType: "접촉 영역" })),
  ];
}

function nodePorts(node) {
  const metadata = flow.structures[node.structure] || {};
  const entries = nodeAnchorEntries(node);
  if (!entries.length) return "";
  const width = Math.max(1, Number(metadata.width) || 16);
  const depth = Math.max(1, Number(metadata.depth) || 16);
  const cutoff = Number(metadata.cutaway_view?.cutoff_y || Math.ceil((Number(metadata.height) || 1) / 2));
  return entries.map((anchor) => {
    const active = (flow.connectionDraft?.node === node.id && flow.connectionDraft?.anchor === anchor.label)
      || (flow.selectedDoorAnchor?.node === node.id && flow.selectedDoorAnchor?.label === anchor.label);
    const compatible = flow.connectionDraft && !active;
    const position = Array.isArray(anchor.position) ? anchor.position : [width / 2, 1, depth / 2];
    const left = Math.max(3, Math.min(97, (Number(position[0]) + .5) / width * 100));
    const top = Math.max(3, Math.min(97, (Number(position[2]) + .5) / depth * 100));
    const aboveCut = Number(position[1]) >= cutoff;
    return `<button class="space-node-port space-map-pin door${active ? " is-active" : ""}${compatible ? " is-compatible" : ""}${aboveCut ? " is-above-cut" : ""}" style="left:${left}%;top:${top}%" type="button" data-port-type="door" data-node-id="${escapeHtml(node.id)}" data-anchor="${escapeHtml(anchor.label)}" title="${anchor.connectionType} · ${escapeHtml(anchor.label)} · X ${position[0]} / Y ${position[1]} / Z ${position[2]}${aboveCut ? " · 절단면 위 앵커" : ""}"><i></i><span>${escapeHtml(anchor.label)}</span></button>`;
  }).join("");
}

function nodeDungeonEntrancePins(node) {
  const metadata = flow.structures[node.structure] || {};
  const width = Math.max(1, Number(metadata.width) || 16);
  const depth = Math.max(1, Number(metadata.depth) || 16);
  const cutoff = Number(metadata.cutaway_view?.cutoff_y || Math.ceil((Number(metadata.height) || 1) / 2));
  return (metadata.dungeon_entrance_anchors || []).map((anchor) => {
    const position = Array.isArray(anchor.position) ? anchor.position : [width / 2, 1, depth / 2];
    const left = Math.max(3, Math.min(97, (Number(position[0]) + .5) / width * 100));
    const top = Math.max(3, Math.min(97, (Number(position[2]) + .5) / depth * 100));
    const aboveCut = Number(position[1]) >= cutoff;
    const active = flow.selectedDungeonEntrance?.node === node.id
      && flow.selectedDungeonEntrance?.label === anchor.label;
    return `<button class="space-node-port space-map-pin dungeon${active ? " is-active" : ""}${aboveCut ? " is-above-cut" : ""}" style="left:${left}%;top:${top}%" type="button" data-dungeon-entrance="${escapeHtml(anchor.label)}" data-node-id="${escapeHtml(node.id)}" title="던전 입구 · ${escapeHtml(anchor.entrance_id || anchor.label)} · X ${position[0]} / Y ${position[1]} / Z ${position[2]}"><i></i><span>${escapeHtml(anchor.label)}</span></button>`;
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
    const doorPins = nodePorts(node);
    const dungeonPins = nodeDungeonEntrancePins(node);
    const dungeonCount = (metadata.dungeon_entrance_anchors || []).length;
    return `<article class="space-node${selected ? " is-selected" : ""}" data-space-node="${escapeHtml(node.id)}" style="transform:translate(${Number(node.position?.[0] || 0)}px,${Number(node.position?.[1] || 0)}px)">
      <header class="space-node-header"><span>${node.kind === "exterior" ? "오버월드" : "내부"}</span><strong>${escapeHtml(node.id)}</strong><i aria-hidden="true">⠿</i></header>
      <div class="space-node-map" style="height:${mapHeight}px">
        <canvas class="space-node-map-canvas" data-node-id="${escapeHtml(node.id)}" width="540" height="${mapHeight * 2}" aria-label="${escapeHtml(node.structure)} 높이 절반 반단면"></canvas>
        ${doorPins}
        ${dungeonPins}
        ${!doorPins && !dungeonPins ? '<span class="space-node-no-pins">출입구 마커 없음</span>' : ""}
      </div>
      <div class="space-node-resource"><code>${escapeHtml(node.structure)}</code><small>반단면 Y 0–${Math.max(0, cutoff - 1)} · ${width}×${depth} · <b class="pin-key door"></b> 실제 문${dungeonCount ? ` · <b class="pin-key dungeon"></b> 던전 입구 ${dungeonCount}` : ""}</small></div>
    </article>`;
  }).join("") || "";
  drawNodeCutaways();
  requestAnimationFrame(renderEdges);
}

function portCenter(nodeId, anchor) {
  const selector = `.space-node-port[data-port-type="door"][data-node-id="${CSS.escape(nodeId)}"][data-anchor="${CSS.escape(anchor)}"]`;
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
    const start = portCenter(edge.from?.node, edge.from?.anchor);
    const end = portCenter(edge.to?.node, edge.to?.anchor);
    if (!start || !end) continue;
    const bend = Math.max(80, Math.abs(end.x - start.x) * .45);
    const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
    path.setAttribute("d", `M ${start.x} ${start.y} C ${start.x + bend} ${start.y}, ${end.x - bend} ${end.y}, ${end.x} ${end.y}`);
    path.setAttribute("class", `space-flow-edge${edge.id === flow.selectedEdgeId ? " is-selected" : ""}`);
    path.dataset.edgeId = edge.id;
    svg.append(path);
  }
  if (flow.connectionDraft?.pointer) {
    const start = portCenter(flow.connectionDraft.node, flow.connectionDraft.anchor);
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
  const doorAnchor = node && flow.selectedDoorAnchor?.node === node.id
    ? nodeAnchorEntries(node).find((anchor) => anchor.label === flow.selectedDoorAnchor.label)
    : null;
  const dungeonEntrance = node && flow.selectedDungeonEntrance?.node === node.id
    ? (flow.structures[node.structure]?.dungeon_entrance_anchors || [])
      .find((anchor) => anchor.label === flow.selectedDungeonEntrance.label)
    : null;
  if (dungeonEntrance) {
    const position = dungeonEntrance.position || [];
    const safeSpawn = dungeonEntrance.safe_spawn || [];
    inspector.innerHTML = `<header><p class="eyebrow">DUNGEON ENTRANCE</p><h3>${escapeHtml(dungeonEntrance.label)}</h3><small>${escapeHtml(node.structure)}</small></header>
      <div class="space-inspector-fields">
        <label><span>연결 방식</span><select data-dungeon-assignment data-structure="${escapeHtml(node.structure)}" data-anchor="${escapeHtml(dungeonEntrance.label)}">${dungeonEntranceOptions(dungeonEntrance.entrance_id || "")}</select></label>
        <label><span>마커 좌표</span><input value="X ${position[0] ?? "?"} / Y ${position[1] ?? "?"} / Z ${position[2] ?? "?"}" readonly></label>
        <label><span>안전 이동 좌표</span><input value="X ${safeSpawn[0] ?? "?"} / Y ${safeSpawn[1] ?? "?"} / Z ${safeSpawn[2] ?? "?"}" readonly></label>
        <label><span>방향</span><input value="${escapeHtml(dungeonEntrance.facing || "미지정")}" readonly></label>
        <p class="space-route-note">다른 던전을 선택하거나 ‘일반 공간 연결 문’으로 되돌릴 수 있습니다. 저장하면 구조물 메타데이터와 런타임 입구가 함께 갱신됩니다.</p>
      </div>`;
  } else if (doorAnchor) {
    const position = doorAnchor.position || [];
    const safeSpawn = doorAnchor.safe_spawn || [];
    inspector.innerHTML = `<header><p class="eyebrow">DOOR ANCHOR</p><h3>${escapeHtml(doorAnchor.label)}</h3><small>${escapeHtml(node.structure)}</small></header>
      <div class="space-inspector-fields">
        <label><span>연결 방식</span><select data-dungeon-assignment data-structure="${escapeHtml(node.structure)}" data-anchor="${escapeHtml(doorAnchor.label)}">${dungeonEntranceOptions("")}</select></label>
        <label><span>문 좌표</span><input value="X ${position[0] ?? "?"} / Y ${position[1] ?? "?"} / Z ${position[2] ?? "?"}" readonly></label>
        <label><span>안전 이동 좌표</span><input value="X ${safeSpawn[0] ?? "?"} / Y ${safeSpawn[1] ?? "?"} / Z ${safeSpawn[2] ?? "?"}" readonly></label>
        <label><span>문 방향</span><input value="${escapeHtml(doorAnchor.door_facing || doorAnchor.facing || "미지정")}" readonly></label>
        <p class="space-route-note">그대로 두면 다른 문으로 연결할 수 있습니다. 던전을 선택하면 이 문은 던전 안내창을 여는 전용 입구로 전환됩니다.</p>
      </div>`;
  } else if (node) {
    const world = Array.isArray(node.world_position) ? node.world_position : [0, 0, 0];
    inspector.innerHTML = `<header><p class="eyebrow">SPACE NODE</p><h3>${escapeHtml(node.id)}</h3><small>공간 카드 설정</small></header>
      <div class="space-inspector-fields">
        <label><span>공간 키</span><input data-node-field="id" value="${escapeHtml(node.id)}" ${node.kind === "exterior" ? "disabled" : ""}></label>
        <label><span>NBT 공간</span><select data-node-field="structure">${Object.entries(flow.structures).filter(([, metadata]) => node.kind === "exterior" ? supportsInteriorConnections(metadata) : ["interior", "gym_interior"].includes(metadata.category)).map(([id]) => `<option value="${escapeHtml(id)}"${id === node.structure ? " selected" : ""}>${escapeHtml(id)}</option>`).join("")}</select></label>
        ${graph.kind === "gym" && node.kind === "interior" ? `<div class="space-world-position"><label><span>월드 X</span><input type="number" data-world-axis="0" value="${Number(world[0] || 0)}"></label><label><span>Y</span><input type="number" data-world-axis="1" value="${Number(world[1] || 0)}"></label><label><span>Z</span><input type="number" data-world-axis="2" value="${Number(world[2] || 0)}"></label></div><label><span>회전</span><select data-node-field="rotation">${["none", "clockwise_90", "clockwise_180", "counterclockwise_90"].map((value) => `<option value="${value}"${value === node.rotation ? " selected" : ""}>${value}</option>`).join("")}</select></label>` : ""}
      </div>
      ${node.kind === "interior" ? '<button class="button danger space-delete" id="delete-space-node" type="button">이 공간 삭제</button>' : ""}`;
  } else if (edge) {
    inspector.innerHTML = `<header><p class="eyebrow">SPACE LINK</p><h3>${escapeHtml(edge.from.node)}:${escapeHtml(edge.from.anchor)}</h3><small>↔ ${escapeHtml(edge.to.node)}:${escapeHtml(edge.to.anchor)} · 양방향 출입구 연결</small></header>
      <div class="space-inspector-fields">
        <p class="space-route-note">두 문은 서로 오갈 수 있습니다. 아래 잠금 조건과 대사는 <b>${escapeHtml(edge.from.node)} → ${escapeHtml(edge.to.node)}</b> 입장 방향에만 적용되고, 반대편 퇴장은 항상 가능합니다.</p>
        <label><span>조건 조합</span><select data-edge-field="condition_mode"><option value="all"${edge.condition_mode !== "any" ? " selected" : ""}>모두 만족</option><option value="any"${edge.condition_mode === "any" ? " selected" : ""}>하나 이상 만족</option></select></label>
        <label><span>조건 JSON</span><textarea rows="7" data-edge-json="conditions" placeholder='[{"type":"variable",…}]'>${escapeHtml(JSON.stringify(edge.conditions || [], null, 2))}</textarea><small>문 잠금 조건을 배열로 입력합니다.</small></label>
        <label><span>잠겼을 때 대사</span><textarea rows="4" data-edge-lines="locked_dialogue" placeholder="문이 잠겨 있다.">${escapeHtml((edge.locked_dialogue || []).join("\n"))}</textarea></label>
        <label><span>입장할 때 대사</span><textarea rows="4" data-edge-lines="enter_dialogue">${escapeHtml((edge.enter_dialogue || []).join("\n"))}</textarea></label>
      </div><button class="button danger space-delete" id="delete-space-edge" type="button">연결선 삭제</button>`;
  } else {
    inspector.innerHTML = graph
      ? `<header><p class="eyebrow">FLOW GUIDE</p><h3>${escapeHtml(graph.display_name || graph.owner)}</h3></header><div class="space-flow-help"><p>아래 내부 공간 리소스를 캔버스로 끌어 놓으세요.</p><p>외부의 <b>출입구 핀</b>을 내부 핀까지 끌면 하나의 양방향 출입구가 됩니다.</p><p>문 핀을 짧게 누르면 일반 공간 문과 던전 입구 중에서 연결 방식을 정할 수 있습니다.</p><p>핀은 NBT 안의 실제 문과 배리어 접촉 영역을 함께 표시합니다.</p><p>연결선을 누르면 외부에서 입장할 때의 잠금 조건과 대사를 설정할 수 있습니다.</p></div>`
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
  setSaveStatus("dirty", "저장 필요");
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
    flow.availableDungeonEntrances = result.data.available_dungeon_entrances || [];
    flow.dungeonEntranceAssignments = result.data.dungeon_entrance_assignments || [];
    flow.selectedGraphId = flow.graphs.some((graph) => graph.id === flow.selectedGraphId) ? flow.selectedGraphId : "";
    flow.selectedNodeId = "";
    flow.selectedEdgeId = "";
    flow.selectedDoorAnchor = null;
    flow.selectedDungeonEntrance = null;
    flow.connectionDraft = null;
    flow.loaded = true;
    flow.dirty = false;
    const path = result.data.path || "content/catalogs/space-connections.json";
    $("#space-flow-path").textContent = path;
    $("#space-flow-path").title = path;
    setSaveStatus("saved", "저장된 상태");
    renderAll();
  })();
  try { await flow.loading; }
  catch (error) { setStatus(error.message, true); setSaveStatus("error", "불러오기 실패", error.message); }
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
  flow.selectedDoorAnchor = null;
  flow.selectedDungeonEntrance = null;
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
  flow.selectedDoorAnchor = null;
  flow.selectedDungeonEntrance = null;
  markDirty();
  renderAll();
}

function connectTo(nodeId, anchor) {
  const graph = selectedGraph();
  if (!graph || !flow.connectionDraft) return;
  let source = { node: flow.connectionDraft.node, anchor: flow.connectionDraft.anchor };
  let target = { node: nodeId, anchor };
  if (source.node === target.node && source.anchor === target.anchor) return;
  if (target.node === "exterior" && source.node !== "exterior") {
    [source, target] = [target, source];
  }
  graph.connections ||= [];
  graph.connections = graph.connections.filter((edge) => !(
    (edge.from.node === source.node && edge.from.anchor === source.anchor)
    || (edge.to.node === source.node && edge.to.anchor === source.anchor)
    || (edge.from.node === target.node && edge.from.anchor === target.anchor)
    || (edge.to.node === target.node && edge.to.anchor === target.anchor)
  ));
  let index = graph.connections.length + 1, id = `route_${index}`;
  while (graph.connections.some((edge) => edge.id === id)) id = `route_${++index}`;
  graph.connections.push({ id, from: source, to: target, condition_mode: "all", conditions: [], locked_dialogue: [], enter_dialogue: [] });
  flow.connectionDraft = null;
  flow.selectedNodeId = "";
  flow.selectedEdgeId = id;
  flow.selectedDoorAnchor = null;
  flow.selectedDungeonEntrance = null;
  markDirty();
  renderAll();
}

async function saveFlow() {
  const button = $("#save-space-flow");
  button.disabled = true;
  setSaveStatus("saving", "저장 중…");
  setStatus("공간 연결과 런타임 설정을 저장하는 중입니다.");
  let result;
  try {
    result = await api("/api/space-connections", {
      method: "PUT", body: JSON.stringify({
        schema_version: 1,
        graphs: flow.graphs,
        dungeon_entrance_assignments: flow.dungeonEntranceAssignments,
      }),
    });
  } catch (error) {
    const message = error.message || "저장 서버에 연결하지 못했습니다.";
    setStatus(message, true);
    setSaveStatus("error", "저장 실패", message);
    button.disabled = false;
    return;
  }
  button.disabled = false;
  if (!result.ok) {
    const issue = result.data.issues?.find((item) => item.level === "error");
    const message = issue ? `${issue.path}: ${issue.message}` : result.data.error || "저장하지 못했습니다.";
    setStatus(message, true);
    setSaveStatus("error", "저장 실패", message);
    return;
  }
  flow.loaded = false;
  await loadFlow(true);
  if (!flow.loaded) return;
  setStatus("공간 연결과 건물·체육관 런타임 설정을 저장했습니다.");
  setSaveStatus("success", "저장 완료");
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
$("#space-building-search").addEventListener("input", (event) => { flow.queries.building = event.target.value; renderLibrary(); });
$("#space-interior-search").addEventListener("input", (event) => { flow.queries.interior = event.target.value; renderLibrary(); });
$("#space-library-kind-filter").addEventListener("change", (event) => { flow.filters.kind = event.target.value; renderLibrary(); });
$("#space-library-route-filter").addEventListener("change", (event) => { flow.filters.route = event.target.value; renderLibrary(); });
$("#reset-space-library-filters").addEventListener("click", () => {
  flow.queries.building = "";
  flow.filters = { kind: "all", route: "all" };
  $("#space-building-search").value = "";
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
    if (port.dataset.portType === "door" && flow.connectionDraft) {
      connectTo(port.dataset.nodeId, port.dataset.anchor);
    } else if (port.dataset.portType === "door") {
      flow.selectedNodeId = port.dataset.nodeId;
      flow.selectedEdgeId = "";
      flow.selectedDoorAnchor = {
        node: port.dataset.nodeId,
        label: port.dataset.anchor,
      };
      flow.selectedDungeonEntrance = null;
      renderAll();
    }
    return;
  }
  const dungeonEntrance = event.target.closest("[data-dungeon-entrance]");
  if (dungeonEntrance) {
    event.stopPropagation();
    flow.selectedNodeId = dungeonEntrance.dataset.nodeId;
    flow.selectedEdgeId = "";
    flow.selectedDoorAnchor = null;
    flow.selectedDungeonEntrance = {
      node: dungeonEntrance.dataset.nodeId,
      label: dungeonEntrance.dataset.dungeonEntrance,
    };
    renderAll();
    return;
  }
  const node = event.target.closest("[data-space-node]");
  if (!node) return;
  flow.selectedNodeId = node.dataset.spaceNode;
  flow.selectedEdgeId = "";
  flow.selectedDoorAnchor = null;
  flow.selectedDungeonEntrance = null;
  renderAll();
});

$("#space-flow-nodes").addEventListener("pointerdown", (event) => {
  const port = event.target.closest('.space-node-port[data-port-type="door"]');
  if (port) {
    event.preventDefault();
    event.stopPropagation();
    flow.connectionDraft = {
      node: port.dataset.nodeId, anchor: port.dataset.anchor,
      pointer: canvasPoint(event), pointerId: event.pointerId,
    };
    flow.selectedNodeId = "";
    flow.selectedEdgeId = "";
    flow.selectedDoorAnchor = null;
    flow.selectedDungeonEntrance = null;
    port.setPointerCapture(event.pointerId);
    $("#space-flow-viewport").classList.add("is-connecting");
    port.classList.add("is-active");
    document.querySelectorAll(".space-node-port[data-port-type=door]").forEach((target) => {
      if (target !== port) target.classList.add("is-compatible");
    });
    renderEdges();
    setStatus("연결할 반대편 문까지 선을 끌어 놓으세요.");
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
  const destination = document.elementFromPoint(event.clientX, event.clientY)?.closest('.space-node-port[data-port-type="door"]');
  $("#space-flow-viewport").classList.remove("is-connecting");
  const sameDoor = destination
    && destination.dataset.nodeId === flow.connectionDraft.node
    && destination.dataset.anchor === flow.connectionDraft.anchor;
  if (!cancelled && sameDoor) {
    flow.selectedNodeId = flow.connectionDraft.node;
    flow.selectedEdgeId = "";
    flow.selectedDoorAnchor = {
      node: flow.connectionDraft.node,
      label: flow.connectionDraft.anchor,
    };
    flow.selectedDungeonEntrance = null;
    flow.connectionDraft = null;
  } else if (!cancelled && destination) connectTo(destination.dataset.nodeId, destination.dataset.anchor);
  else if (cancelled) {
    flow.connectionDraft = null;
    renderAll();
  } else {
    flow.connectionDraft.pointer = null;
    flow.connectionDraft.pointerId = null;
    renderNodes();
    setStatus("연결할 문이 강조되어 있습니다. 화면을 옮긴 뒤 반대편 문을 클릭해도 됩니다.");
  }
}
document.addEventListener("pointerup", (event) => finishConnection(event), true);
document.addEventListener("pointercancel", (event) => finishConnection(event, true), true);

$("#space-flow-edges").addEventListener("click", (event) => {
  const path = event.target.closest("[data-edge-id]");
  if (!path) return;
  flow.selectedEdgeId = path.dataset.edgeId;
  flow.selectedNodeId = "";
  flow.selectedDoorAnchor = null;
  flow.selectedDungeonEntrance = null;
  flow.connectionDraft = null;
  renderAll();
});

$("#space-flow-inspector").addEventListener("change", (event) => {
  const selector = event.target.closest("[data-dungeon-assignment]");
  if (!selector) return;
  const structure = selector.dataset.structure;
  const anchorLabel = selector.dataset.anchor;
  const entranceId = selector.value;
  const metadata = flow.structures[structure];
  if (!metadata) return;
  const existing = dungeonAssignment(structure, anchorLabel);
  flow.dungeonEntranceAssignments = flow.dungeonEntranceAssignments.filter((item) =>
    !(item.structure === structure && item.anchor === anchorLabel)
  );
  const doorIndex = (metadata.door_anchors || []).findIndex((anchor) => anchor.label === anchorLabel);
  const dungeonIndex = (metadata.dungeon_entrance_anchors || []).findIndex((anchor) => anchor.label === anchorLabel);
  if (entranceId) {
    if (flow.dungeonEntranceAssignments.some((item) => item.entrance_id === entranceId)) {
      selector.value = existing?.entrance_id || "";
      setStatus("이미 다른 문에 연결된 던전 입구입니다.", true);
      return;
    }
    let anchor = dungeonIndex >= 0
      ? metadata.dungeon_entrance_anchors[dungeonIndex]
      : doorIndex >= 0 ? metadata.door_anchors.splice(doorIndex, 1)[0] : null;
    if (!anchor) {
      selector.value = existing?.entrance_id || "";
      setStatus("선택한 문 앵커를 구조물에서 찾을 수 없습니다.", true);
      return;
    }
    flow.dungeonEntranceAssignments.push({ structure, anchor: anchorLabel, entrance_id: entranceId });
    anchor = { ...anchor, entrance_id: entranceId, facing: anchor.facing || anchor.door_facing || "north" };
    metadata.dungeon_entrance_anchors ||= [];
    if (dungeonIndex >= 0) metadata.dungeon_entrance_anchors[dungeonIndex] = anchor;
    else metadata.dungeon_entrance_anchors.push(anchor);
    for (const graph of flow.graphs) {
      const nodeIds = new Set((graph.nodes || [])
        .filter((node) => node.structure === structure)
        .map((node) => node.id));
      graph.connections = (graph.connections || []).filter((edge) => !(
        (nodeIds.has(edge.from.node) && edge.from.anchor === anchorLabel)
        || (nodeIds.has(edge.to.node) && edge.to.anchor === anchorLabel)
      ));
    }
    flow.selectedDoorAnchor = null;
    flow.selectedDungeonEntrance = { node: flow.selectedNodeId, label: anchorLabel };
  } else {
    const anchor = dungeonIndex >= 0
      ? metadata.dungeon_entrance_anchors.splice(dungeonIndex, 1)[0]
      : null;
    if (anchor) {
      delete anchor.entrance_id;
      metadata.door_anchors ||= [];
      metadata.door_anchors.push({
        ...anchor,
        door_facing: anchor.door_facing || anchor.facing || "north",
        safe_side: anchor.safe_side || "south",
      });
    }
    flow.selectedDungeonEntrance = null;
    flow.selectedDoorAnchor = { node: flow.selectedNodeId, label: anchorLabel };
  }
  markDirty();
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
window.addEventListener("building-settings-saved", () => {
  flow.loaded = false;
  if (document.querySelector('[data-section="space-connections"]')?.classList.contains("is-active")) loadFlow(true);
});
if (document.querySelector('[data-section="space-connections"]')?.classList.contains("is-active")) loadFlow();
window.addEventListener("resize", () => { if (selectedGraph()) renderEdges(); });
