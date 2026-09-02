import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import { skinPreviewHtml, updateSkinPreview } from '../web/npc-skin-preview.mjs';

const images = [];
globalThis.Image = class {
  constructor() { images.push(this); this.naturalWidth = 64; this.naturalHeight = 64; }
  set src(value) { this.url = value; }
};
function canvas(url, size = 320) {
  const calls = [], listeners = new Map(), classes = new Set();
  const context = new Proxy({}, { get: (_, name) => (...args) => calls.push([name, ...args]) });
  return {
    width: size, height: size, dataset: { skinUrl: url }, calls, listeners,
    getContext: () => context,
    addEventListener: (name, handler) => {
      assert.equal(listeners.has(name), false, 'listeners should only be installed once');
      listeners.set(name, handler);
    },
    classList: { add: name => classes.add(name), remove: name => classes.delete(name) },
    setPointerCapture() {}, hasPointerCapture: () => true, releasePointerCapture() {},
  };
}

test('shared markup escapes resources and preserves body options', () => {
  const html = skinPreviewHtml('skin?x="<test>', { arm_model: 'slim', height_scale: .7 });
  assert.match(html, /npc-skin-preview/);
  assert.match(html, /data-arm-model="slim"/);
  assert.match(html, /data-height-scale="0.7"/);
  assert.match(html, /&quot;&lt;test&gt;/);
});

test('both views share image requests; updating arm model reuses image and listeners', async () => {
  const a = canvas('shared'), b = canvas('shared');
  const count = images.length;
  const first = updateSkinPreview(a), second = updateSkinPreview(b);
  assert.equal(images.length, count + 1);
  images.at(-1).onload();
  await Promise.all([first, second]);
  assert.equal(a.dataset.previewState, 'ready');
  assert.equal(b.dataset.previewState, 'ready');
  const before = a.calls.length;
  updateSkinPreview(a, { armModel: 'slim', heightScale: .7 });
  assert.equal(images.length, count + 1);
  assert.ok(a.calls.length > before);
  a.listeners.get('pointerdown')({ button: 0, pointerId: 1, clientX: 0 });
  a.listeners.get('pointermove')({ pointerId: 1, clientX: 40 });
  a.listeners.get('pointerup')({ pointerId: 1 });
  a.listeners.get('dblclick')();
});

test('stale image requests cannot replace the latest skin', async () => {
  const target = canvas('old');
  const old = updateSkinPreview(target), oldImage = images.at(-1);
  const latest = updateSkinPreview(target, { url: 'new' }), newImage = images.at(-1);
  newImage.onload(); await latest;
  oldImage.onload(); await old;
  const draws = target.calls.filter(call => call[0] === 'drawImage');
  assert.ok(draws.length > 0);
  assert.ok(draws.every(call => call[1] === newImage));
});

test('failed skins can be retried; clearing a skin invalidates pending loads', async () => {
  const target = canvas('retry');
  const fail = updateSkinPreview(target);
  images.at(-1).onerror(); await fail;
  assert.equal(target.dataset.previewState, 'error');
  const retry = updateSkinPreview(target), retryImage = images.at(-1);
  updateSkinPreview(target, { url: '' });
  retryImage.onload(); await retry;
  assert.equal(target.dataset.previewState, 'empty');
  assert.equal(target.calls.some(call => call[0] === 'drawImage'), false);
});

test('160px thumbnails fit and legacy skins skip modern clothing layers', async () => {
  const small = canvas('legacy', 160);
  small.dataset.heightScale = '1.25';
  const promise = updateSkinPreview(small);
  images.at(-1).naturalHeight = 32;
  images.at(-1).onload(); await promise;
  const vertices = small.calls.filter(call => ['moveTo', 'lineTo'].includes(call[0]));
  assert.ok(vertices.length > 0);
  assert.ok(vertices.every(([, x, y]) => x >= 0 && x <= 160 && y >= 0 && y <= 160));
});

test('all NPC contexts use the shared module and old CSS model is removed', () => {
  const app = readFileSync(new URL('../web/app.js', import.meta.url), 'utf8');
  const css = readFileSync(new URL('../web/styles.css', import.meta.url), 'utf8');
  assert.match(app, /from "\/npc-skin-preview.mjs"/);
  assert.match(app, /initializeSkinPreviews\(\$\("#system-npc-grid"\)\)/);
  assert.match(app, /initializeSkinPreviews\(\$\("#system-npc-resource-grid"\)\)/);
  assert.equal((app.match(/initializeSkinPreviews\(preview\)/g) || []).length, 2);
  assert.doesNotMatch(app, /minecraftParts|minecraftFace|systemNpcSkinParts|previewReady/);
  assert.doesNotMatch(css, /\.mc-face|\.minecraft-model/);
});
