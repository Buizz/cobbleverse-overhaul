const skinImages = new Map();
const previewStates = new WeakMap();

function loadSkin(url) {
  if (!skinImages.has(url)) {
    skinImages.set(url, new Promise((resolve, reject) => {
      const image = new Image();
      image.decoding = "async";
      image.onload = () => resolve(image);
      image.onerror = () => { skinImages.delete(url); reject(new Error("스킨 이미지를 불러오지 못했습니다.")); };
      image.src = url;
    }));
  }
  return skinImages.get(url);
}

function skinFace(x, y, width, height) {
  return { x, y, width, height };
}

function skinCuboid(top, right, front, left, back) {
  return { top, right, front, left, back };
}

function skinParts(slim, legacy) {
  const armWidth = slim ? 3 : 4;
  const rightArm = skinCuboid(
    skinFace(44, 16, armWidth, 4), skinFace(40, 20, 4, 12),
    skinFace(44, 20, armWidth, 12), skinFace(44 + armWidth, 20, 4, 12),
    skinFace(48 + armWidth, 20, armWidth, 12)
  );
  const rightArmLayer = skinCuboid(
    skinFace(44, 32, armWidth, 4), skinFace(40, 36, 4, 12),
    skinFace(44, 36, armWidth, 12), skinFace(44 + armWidth, 36, 4, 12),
    skinFace(48 + armWidth, 36, armWidth, 12)
  );
  const leftArm = legacy ? rightArm : skinCuboid(
    skinFace(36, 48, armWidth, 4), skinFace(32, 52, 4, 12),
    skinFace(36, 52, armWidth, 12), skinFace(36 + armWidth, 52, 4, 12),
    skinFace(40 + armWidth, 52, armWidth, 12)
  );
  const leftArmLayer = legacy ? null : skinCuboid(
    skinFace(52, 48, armWidth, 4), skinFace(48, 52, 4, 12),
    skinFace(52, 52, armWidth, 12), skinFace(52 + armWidth, 52, 4, 12),
    skinFace(56 + armWidth, 52, armWidth, 12)
  );
  const rightLeg = skinCuboid(
    skinFace(4, 16, 4, 4), skinFace(0, 20, 4, 12), skinFace(4, 20, 4, 12),
    skinFace(8, 20, 4, 12), skinFace(12, 20, 4, 12)
  );
  const leftLeg = legacy ? rightLeg : skinCuboid(
    skinFace(20, 48, 4, 4), skinFace(16, 52, 4, 12), skinFace(20, 52, 4, 12),
    skinFace(24, 52, 4, 12), skinFace(28, 52, 4, 12)
  );
  return [
    { box: [-4, 4, 0, 8, -4, 4], base: skinCuboid(skinFace(8, 0, 8, 8), skinFace(0, 8, 8, 8), skinFace(8, 8, 8, 8), skinFace(16, 8, 8, 8), skinFace(24, 8, 8, 8)), layer: skinCuboid(skinFace(40, 0, 8, 8), skinFace(32, 8, 8, 8), skinFace(40, 8, 8, 8), skinFace(48, 8, 8, 8), skinFace(56, 8, 8, 8)), layerSize: .5 },
    { box: [-4, 4, 8, 20, -2, 2], base: skinCuboid(null, skinFace(16, 20, 4, 12), skinFace(20, 20, 8, 12), skinFace(28, 20, 4, 12), skinFace(32, 20, 8, 12)), layer: legacy ? null : skinCuboid(null, skinFace(16, 36, 4, 12), skinFace(20, 36, 8, 12), skinFace(28, 36, 4, 12), skinFace(32, 36, 8, 12)), layerSize: .15 },
    { box: [-4 - armWidth, -4, 8, 20, -2, 2], base: rightArm, layer: legacy ? null : rightArmLayer, layerSize: .15 },
    { box: [4, 4 + armWidth, 8, 20, -2, 2], base: leftArm, layer: leftArmLayer, layerSize: .15 },
    { box: [-4, 0, 20, 32, -2, 2], base: rightLeg, layer: legacy ? null : skinCuboid(null, skinFace(0, 36, 4, 12), skinFace(4, 36, 4, 12), skinFace(8, 36, 4, 12), skinFace(12, 36, 4, 12)), layerSize: .15 },
    { box: [0, 4, 20, 32, -2, 2], base: leftLeg, layer: legacy ? null : skinCuboid(null, skinFace(0, 52, 4, 12), skinFace(4, 52, 4, 12), skinFace(8, 52, 4, 12), skinFace(12, 52, 4, 12)), layerSize: .15 }
  ];
}

function drawSkinFace(context, image, uv, points) {
  if (!uv) return;
  const sourceScaleX = image.naturalWidth / 64;
  const sourceScaleY = image.naturalHeight / (image.naturalHeight >= image.naturalWidth ? 64 : 32);
  const sourceX = uv.x * sourceScaleX, sourceY = uv.y * sourceScaleY;
  const sourceWidth = uv.width * sourceScaleX, sourceHeight = uv.height * sourceScaleY;
  const [topLeft, topRight,, bottomLeft] = points;
  context.save();
  context.beginPath();
  context.moveTo(points[0].x, points[0].y);
  for (let index = 1; index < points.length; index += 1) context.lineTo(points[index].x, points[index].y);
  context.closePath();
  context.clip();
  context.setTransform(
    (topRight.x - topLeft.x) / sourceWidth,
    (topRight.y - topLeft.y) / sourceWidth,
    (bottomLeft.x - topLeft.x) / sourceHeight,
    (bottomLeft.y - topLeft.y) / sourceHeight,
    topLeft.x - sourceX * (topRight.x - topLeft.x) / sourceWidth - sourceY * (bottomLeft.x - topLeft.x) / sourceHeight,
    topLeft.y - sourceX * (topRight.y - topLeft.y) / sourceWidth - sourceY * (bottomLeft.y - topLeft.y) / sourceHeight
  );
  context.imageSmoothingEnabled = false;
  context.drawImage(image, 0, 0);
  context.restore();
}

function renderSkinPreview(canvas) {
  const preview = previewStates.get(canvas);
  if (!preview?.image) return;
  const context = canvas.getContext("2d");
  const width = canvas.width, height = canvas.height;
  context.setTransform(1, 0, 0, 1, 0, 0);
  context.clearRect(0, 0, width, height);
  context.imageSmoothingEnabled = false;
  context.fillStyle = "rgba(30, 56, 47, .14)";
  context.beginPath();
  context.ellipse(width / 2, height * .91, width * .194, height * .041, 0, 0, Math.PI * 2);
  context.fill();

  const yaw = preview.yaw;
  const sinYaw = Math.sin(yaw), cosYaw = Math.cos(yaw);
  const heightScale = Math.max(.5, Math.min(1.25, Number(canvas.dataset.heightScale) || 1));
  const scale = Math.min(width, height) * .0225 / Math.max(1, heightScale);
  const top = height * .84 - 32 * scale * heightScale;
  const project = ([x, y, z]) => {
    const horizontal = x * cosYaw - z * sinYaw;
    const depth = x * sinYaw + z * cosYaw;
    return { x: width / 2 + horizontal * scale, y: top + y * scale * heightScale - depth * scale * .29, depth };
  };
  const faces = [];
  const addFaces = (box, texture, expansion, layer) => {
    if (!texture) return;
    let [x0, x1, y0, y1, z0, z1] = box;
    x0 -= expansion; x1 += expansion; y0 -= expansion; y1 += expansion; z0 -= expansion; z1 += expansion;
    const definitions = [
      ["front", [0, 0, 1], [[x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]]],
      ["back", [0, 0, -1], [[x1, y0, z0], [x0, y0, z0], [x0, y1, z0], [x1, y1, z0]]],
      ["right", [1, 0, 0], [[x1, y0, z1], [x1, y0, z0], [x1, y1, z0], [x1, y1, z1]]],
      ["left", [-1, 0, 0], [[x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0]]],
      ["top", [0, -1, 0], [[x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1]]]
    ];
    for (const [name, normal, vertices] of definitions) {
      if (!texture[name]) continue;
      if (name !== "top" && normal[0] * sinYaw + normal[2] * cosYaw <= .015) continue;
      const points = vertices.map(project);
      faces.push({ uv: texture[name], points, depth: points.reduce((sum, point) => sum + point.depth, 0) / 4 + layer * .001 });
    }
  };
  const legacy = preview.image.naturalHeight * 2 <= preview.image.naturalWidth;
  for (const part of skinParts(canvas.dataset.armModel === "slim", legacy)) {
    addFaces(part.box, part.base, 0, 0);
    addFaces(part.box, part.layer, part.layerSize, 1);
  }
  faces.sort((left, right) => left.depth - right.depth);
  for (const face of faces) drawSkinFace(context, preview.image, face.uv, face.points);
}


function escapeAttribute(value) {
  return String(value ?? "").replaceAll("&", "&amp;").replaceAll('"', "&quot;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}

export function skinPreviewHtml(url, body = {}, options = {}) {
  const size = options.size || 320;
  return `<canvas class="npc-skin-preview ${escapeAttribute(options.className || "")}" width="${size}" height="${size}" data-skin-url="${escapeAttribute(url)}" data-arm-model="${body.arm_model === "slim" ? "slim" : "classic"}" data-height-scale="${Math.max(.5, Math.min(1.25, Number(body.height_scale) || 1))}" aria-label="${escapeAttribute(options.label || "NPC 3D 스킨 미리보기")}" title="드래그하여 회전 · 두 번 클릭하여 초기화"></canvas>`;
}

export function updateSkinPreview(canvas, options = {}) {
  if (!canvas) return;
  if (options.url !== undefined) canvas.dataset.skinUrl = options.url;
  if (options.armModel !== undefined) canvas.dataset.armModel = options.armModel;
  if (options.heightScale !== undefined) canvas.dataset.heightScale = options.heightScale;
  let preview = previewStates.get(canvas);
  if (!preview) {
    preview = { image: null, yaw: .48, drag: null, url: null, revision: 0 };
    previewStates.set(canvas, preview);
    canvas.addEventListener("pointerdown", (event) => {
      if (event.button !== 0) return;
      preview.drag = { pointerId: event.pointerId, x: event.clientX, yaw: preview.yaw };
      canvas.setPointerCapture(event.pointerId);
      canvas.classList.add("is-dragging");
    });
    canvas.addEventListener("pointermove", (event) => {
      if (!preview.drag || preview.drag.pointerId !== event.pointerId) return;
      preview.yaw = preview.drag.yaw + (event.clientX - preview.drag.x) * .018;
      renderSkinPreview(canvas);
    });
    const stopDragging = (event) => {
      if (!preview.drag || preview.drag.pointerId !== event.pointerId) return;
      preview.drag = null;
      canvas.classList.remove("is-dragging");
      if (canvas.hasPointerCapture(event.pointerId)) canvas.releasePointerCapture(event.pointerId);
    };
    canvas.addEventListener("pointerup", stopDragging);
    canvas.addEventListener("pointercancel", stopDragging);
    canvas.addEventListener("lostpointercapture", stopDragging);
    canvas.addEventListener("dblclick", () => { preview.yaw = .48; renderSkinPreview(canvas); });
  }
  const url = canvas.dataset.skinUrl || "";
  if (preview.url === url) { renderSkinPreview(canvas); return; }
  preview.url = url;
  preview.image = null;
  const revision = ++preview.revision;
  const context = canvas.getContext("2d");
  const status = (text) => {
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.fillStyle = "#526a7a";
    context.font = `${Math.max(12, canvas.width / 24)}px sans-serif`;
    context.textAlign = "center";
    context.fillText(text, canvas.width / 2, canvas.height / 2);
  };
  canvas.dataset.previewState = url ? "loading" : "empty";
  status(url ? "스킨 불러오는 중…" : "스킨 없음");
  if (!url) return;
  return loadSkin(url).then((image) => {
    if (revision !== preview.revision) return;
    preview.image = image;
    canvas.dataset.previewState = "ready";
    renderSkinPreview(canvas);
  }).catch(() => {
    if (revision !== preview.revision) return;
    preview.url = null; // Allow retrying the same resource after a load failure.
    canvas.dataset.previewState = "error";
    status("3D 미리보기 없음");
  });
}

export function initializeSkinPreviews(root = document) {
  root.querySelectorAll(".npc-skin-preview").forEach((canvas) => updateSkinPreview(canvas));
}
