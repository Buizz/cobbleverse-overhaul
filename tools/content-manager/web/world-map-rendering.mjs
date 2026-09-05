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

const TILE_FILLS = {
  plains: '#abc58f', forest: '#76a27e', 'dense-forest': '#274e32', beach: '#e2cb83',
  water: '#78add0', arid: '#d2a06d', snow: '#c6d8dc', mountain: '#aaa79f', wetland: '#91b1a2',
};
const hatchPatterns = new WeakMap();

function emptyTerrainHatch(canvas, context) {
  if (hatchPatterns.has(context)) return hatchPatterns.get(context);
  const document = canvas.ownerDocument;
  if (!document) return null;
  const hatch = document.createElement('canvas'); hatch.width = 8; hatch.height = 8;
  const hatchContext = hatch.getContext('2d');
  hatchContext.strokeStyle = 'rgba(213,40,40,.72)'; hatchContext.lineWidth = 1.4;
  hatchContext.beginPath(); hatchContext.moveTo(-2, 6); hatchContext.lineTo(2, 10);
  hatchContext.moveTo(0, 0); hatchContext.lineTo(8, 8);
  hatchContext.moveTo(6, -2); hatchContext.lineTo(10, 2); hatchContext.stroke();
  const pattern = context.createPattern(hatch, 'repeat');
  hatchPatterns.set(context, pattern); return pattern;
}

function traceHex(context, x, y, radius) {
  context.beginPath();
  for (let index = 0; index < 6; index += 1) {
    const angle = Math.PI / 180 * (60 * index - 30);
    const px = x + radius * Math.cos(angle), py = y + radius * Math.sin(angle);
    if (!index) context.moveTo(px, py); else context.lineTo(px, py);
  }
  context.closePath();
}

export function drawWorldMapTiles(canvas, view, viewport, tiles, options = {}) {
  const context = canvas.getContext('2d', { alpha: true });
  if (!context || !viewport.width || !viewport.height) return;
  const pixelRatio = Math.min(options.pixelRatio || globalThis.devicePixelRatio || 1, 2);
  const outputWidth = Math.max(1, Math.round(viewport.width * pixelRatio));
  const outputHeight = Math.max(1, Math.round(viewport.height * pixelRatio));
  if (canvas.width !== outputWidth || canvas.height !== outputHeight) {
    canvas.width = outputWidth; canvas.height = outputHeight;
  }
  const scale = viewport.width / view.width;
  context.setTransform(pixelRatio * scale, 0, 0, pixelRatio * scale, -view.x * pixelRatio * scale, -view.y * pixelRatio * scale);
  context.clearRect(view.x, view.y, view.width, view.height);
  context.lineJoin = 'round';
  for (const tile of tiles) {
    traceHex(context, tile.x, tile.y, tile.radius);
    // An empty-terrain override describes only cells without a biome/town tile.  The frame
    // payload also carries the underlying empty terrain for editor actions, so never let that
    // value replace the visible biome palette on populated cells.
    context.fillStyle = (tile.isEmpty ? tile.emptyFill : null) || TILE_FILLS[tile.tone] || '#f3f7f4';
    context.fill();
    context.strokeStyle = tile.selected ? '#ff9f22' : tile.environment ? '#7b5bb5' : tile.route ? '#b9852c' : tile.isEmpty ? '#b84242' : '#b7c7be';
    context.lineWidth = (tile.selected ? 3 : tile.route ? 1.8 : tile.isEmpty ? 1.45 : 1.35) / scale;
    context.setLineDash(tile.selected || tile.route ? [] : tile.environment ? [2 / scale, 2 / scale] : tile.isEmpty ? [3 / scale, 2 / scale] : []);
    context.stroke();
    if (tile.isEmpty) {
      const hatch = emptyTerrainHatch(canvas, context);
      if (hatch) { traceHex(context, tile.x, tile.y, tile.radius); context.fillStyle = hatch; context.fill(); }
      context.fillStyle = '#7b1111'; context.font = `900 ${10 / scale}px sans-serif`; context.textAlign = 'center'; context.textBaseline = 'middle';
      context.fillText(tile.symbol, tile.x, tile.y);
    } else if (tile.hasTile && !tile.townArea) {
      context.beginPath(); context.arc(tile.x, tile.y, 3 / scale, 0, Math.PI * 2); context.fillStyle = '#789168'; context.fill();
    }
    if (tile.environment) {
      context.fillStyle = '#744ca3'; context.fillRect(tile.x - 7 / scale, tile.y - 12 / scale, 14 / scale, 5 / scale);
    }
  }
  context.setLineDash([]);
  canvas.dataset.tileCount = String(tiles.length);
}
