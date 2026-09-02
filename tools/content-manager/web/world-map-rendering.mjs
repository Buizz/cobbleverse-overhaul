// View geometry and per-frame indexes are independent of editor mutations.
export function worldMapViewBox(center, zoom, viewport) {
  const width = Math.max(1, viewport.width) / zoom;
  const height = Math.max(1, viewport.height) / zoom;
  return { x: center.x - width / 2, y: center.y - height / 2, width, height };
}

export function buildWorldRenderIndex(layout, footprint, connectionPath) {
  const key = cell => `${cell.q},${cell.r}`;
  const first = (map, cell, value) => { if (cell && !map.has(key(cell))) map.set(key(cell), value); };
  const byCell = entries => {
    const map = new Map();
    for (const entry of entries || []) first(map, entry, entry);
    return map;
  };
  const index = {
    tiles: byCell(layout.tiles), environment: byCell(layout.environment_overrides),
    levels: byCell(layout.level_overrides), empty: byCell(layout.empty_terrain?.tiles),
    towns: new Map(), footprints: new Map(), roads: new Map(), paths: new Map(), routeCells: new Set(),
  };
  for (const town of layout.settlements || []) {
    first(index.towns, town.anchor, town);
    if (town.anchor) for (const cell of footprint(town)) first(index.footprints, cell, town);
  }
  for (const route of layout.connections || []) {
    const cells = connectionPath(route);
    index.paths.set(route, cells);
    for (const cell of cells) {
      index.routeCells.add(key(cell));
      if (route.surface_style !== 'water') first(index.roads, cell, route);
    }
  }
  return index;
}

export function createFrameScheduler(draw, requestFrame = requestAnimationFrame) {
  let pending = false;
  return () => {
    if (pending) return;
    pending = true;
    requestFrame(() => { pending = false; draw(); });
  };
}

const tileLayers = new WeakMap();
export function reconcileHexTiles(layer, tiles) {
  let previous = tileLayers.get(layer);
  if (!previous) { previous = new Map(); tileLayers.set(layer, previous); }
  const visible = new Set(tiles.map(tile => tile.key));
  for (const [key, entry] of previous) {
    if (!visible.has(key)) { entry.node.remove(); previous.delete(key); }
  }
  const changed = tiles.filter(tile => previous.get(tile.key)?.markup !== tile.markup);
  if (!changed.length) return;
  const scratch = layer.ownerDocument.createElementNS('http://www.w3.org/2000/svg', 'svg');
  scratch.innerHTML = changed.map(tile => tile.markup).join('');
  const nodes = [...scratch.children];
  const additions = layer.ownerDocument.createDocumentFragment();
  changed.forEach((tile, i) => {
    const next = nodes[i], current = previous.get(tile.key)?.node;
    if (current) {
      // Preserve focused tile elements and only replace changed visual content.
      for (const attribute of [...current.attributes]) if (!next.hasAttribute(attribute.name)) current.removeAttribute(attribute.name);
      for (const attribute of next.attributes) if (current.getAttribute(attribute.name) !== attribute.value) current.setAttribute(attribute.name, attribute.value);
      if (current.innerHTML !== next.innerHTML) current.innerHTML = next.innerHTML;
    } else additions.append(next);
    previous.set(tile.key, { node: current || next, markup: tile.markup });
  });
  layer.append(additions);
}
