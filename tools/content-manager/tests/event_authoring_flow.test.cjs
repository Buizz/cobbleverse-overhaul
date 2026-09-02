const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const app = fs.readFileSync(path.join(__dirname, '../web/app.js'), 'utf8');
const editor = fs.readFileSync(path.join(__dirname, '../web/cves-editor.js'), 'utf8');

test('NPC editor resolves the selected resource ID, not the NPC default path', () => {
  const context = vm.createContext({});
  vm.runInContext(app.slice(app.indexOf('function eventPathFromId('), app.indexOf('let npcEventItems')), context);
  assert.equal(context.eventPathFromId('test:event_script/shared/heal'), 'test/shared/heal.cves');
  for (const id of ['test:event_script/../outside', 'test:event_script/a//b', 'test:event_script/./b', 'bad', 'test:event_script/a\\b']) {
    assert.equal(context.eventPathFromId(id), null);
  }
  const opener = app.slice(app.indexOf('function openLinkedCvesEvent()'), app.indexOf('function eventPathFromId('));
  assert.match(opener, /event_runtime\?\.script_id/);
  assert.match(opener, /openEmbeddedTool/);
  assert.doesNotMatch(opener, /window\.location\s*=/);
});

function loaderHarness() {
  const events = [];
  const state = { path: 'test/first.cves', dirty: false, metadataDirty: false, loading: false };
  const context = vm.createContext({ state, confirm: () => false,
    request: async url => url === '/api/cves/scripts'
      ? {ok:true,data:{items:[{path:'test/first.cves'},{path:'test/second.cves'}]}}
      : {ok:true,data:{path:url.split('=')[1]}},
    renderScriptList: () => {}, toast: message => events.push(message), updateState: () => {},
    renderDiagnostics: () => {}, setDocumentBusy: value => {state.loading=value;},
    applyDocument: document => { events.push(document.path); state.path = document.path; },
  });
  vm.runInContext('function hasUnsavedChanges() {return state.dirty || state.metadataDirty || state.sourceDirty;}', context);
  vm.runInContext(editor.slice(editor.indexOf('async function loadScripts('), editor.indexOf('function applyDocument(')), context);
  return {context,state,events};
}

test('opening a missing linked event never silently selects the first event', async () => {
  const h = loaderHarness(); await h.context.loadScripts('test/missing.cves');
  assert.equal(h.state.path, 'test/first.cves');
  assert.equal(h.events.length, 1);
  assert.match(h.events[0], /아직 없습니다/);
});

for (const dirty of ['dirty','metadataDirty','sourceDirty']) {
  test(`cancel preserves the current document with ${dirty}`, async () => {
    const h = loaderHarness(); h.state[dirty] = true;
    await h.context.loadScripts('test/second.cves');
    assert.equal(h.state.path, 'test/first.cves'); assert.equal(h.events.length, 0);
  });
}

test('refresh reloads the same document to pick up saved ownership changes', async () => {
  const h = loaderHarness();
  await h.context.loadScripts('test/first.cves'); assert.equal(h.events.length, 0);
  await h.context.loadScripts('test/first.cves', {reload:true}); assert.equal(h.events.length, 1);
  assert.equal(h.state.loading, false);
});

test('late library response cannot replace a newer navigation request', async () => {
  const h = loaderHarness(); const pending = [];
  h.context.request = url => url === '/api/cves/scripts' ? new Promise(resolve => pending.push(resolve))
    : Promise.resolve({ok:true,data:{path:'test/second.cves'}});
  const first = h.context.loadScripts('test/first.cves');
  const second = h.context.loadScripts('test/second.cves');
  pending[1]({ok:true,data:{items:[{path:'test/second.cves'}]}}); await second;
  pending[0]({ok:true,data:{items:[{path:'test/first.cves'}]}}); await first;
  assert.equal(h.state.path, 'test/second.cves');
  assert.equal(h.state.items[0].path, 'test/second.cves');
});

test('embedded routing authenticates sender and does not recreate existing frames', () => {
  assert.match(app, /event\.source !== frame\.contentWindow/);
  assert.match(editor, /event\.source !== window\.parent/);
  assert.match(app, /if \(!frame\) \{/);
  assert.match(editor, /event\.data\.path === state\.path && !hasUnsavedChanges\(\)/);
});

test('copying a document clears the original source digest before the first save', () => {
  const state = {path:'test/original.cves', digest:'old-file-hash', library:{managed:true}};
  const context = vm.createContext({state, $:()=>({}), $$:()=>[], WeakSet,
    renderLibraryDetails:()=>{}, renderScriptList:()=>{}, renderTree:()=>{},
    renderInspector:()=>{}, renderDiagnostics:()=>{}, updateState:()=>{}});
  vm.runInContext(editor.slice(editor.indexOf('function applyDocument('), editor.indexOf('function renderScriptList(')), context);
  context.applyDocument({path:'test/copy.cves',digest:null,ast:{root:{events:[]}}},'test:event_script/copy',true);
  assert.equal(state.digest,null);
  assert.equal(state.library,null);
  assert.equal(state.dirty,true);
});
