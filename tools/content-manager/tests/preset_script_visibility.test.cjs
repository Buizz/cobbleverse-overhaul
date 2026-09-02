const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const web = name => fs.readFileSync(path.join(__dirname, '../web', name), 'utf8');
const editor = web('cves-editor.js'), app = web('app.js');
const preset = {path:'test/generated.cves',script_id:'test:event_script/generated',name:'Generated',managed:true,usages:[]};
const custom = {path:'test/battle.cves',script_id:'test:event_script/battle',name:'Custom battle',managed:false,usages:[]};
function node() {return {value:'',children:[],append(...items){this.children.push(...items);},replaceChildren(){this.children=[];},
  setAttribute(){},removeAttribute(){},addEventListener(){},querySelector(){return {};},get childElementCount(){return this.children.length;}};}

test('library hides generated entries without dropping them from collision detection',()=>{
  const nodes = new Map(); const $ = key => {if(!nodes.has(key)) nodes.set(key,node());return nodes.get(key);};
  const state={items:[preset,custom]};
  const context=vm.createContext({state,$,element:(tag,cls,text)=>Object.assign(node(),{text}),loadScript(){},toast(){}});
  vm.runInContext(editor.slice(editor.indexOf('function renderScriptList('),editor.indexOf('function textSummary(')),context);
  context.renderScriptList();
  assert.equal($('#script-list').children.length,1);
  assert.equal($('#script-list').children[0].children[0].text,'Custom battle');
  assert.equal(state.items.length,2);
  state.items=[preset];context.renderScriptList();
  assert.match($('#script-list').children[0].text,/사용자 정의 이벤트가 없습니다/);
});

test('initial loading skips generated entries; direct generated links do not load',async()=>{
  const loaded=[],messages=[];
  const state={path:null};
  const context=vm.createContext({state,request:async()=>({ok:true,data:{items:[preset,custom]}}),renderScriptList(){},
    loadScript:async item=>loaded.push(item.path),toast:message=>messages.push(message)});
  vm.runInContext(editor.slice(editor.indexOf('async function loadScripts('),editor.indexOf('async function loadScript(')),context);
  await context.loadScripts();assert.deepEqual(loaded,[custom.path]);
  await context.loadScripts(preset.path);assert.equal(loaded.length,1);assert.match(messages[0],/행동 프리셋/);
});

for(const authoring of ['preset','custom']) test(`NPC ${authoring} mode only exposes appropriate controls without changing data`,()=>{
  const nodes=new Map(); const $=key=>{if(!nodes.has(key))nodes.set(key,node());return nodes.get(key);};
  const state={trainerPath:'content/source/npc.json',trainer:{event_runtime:{engine:'cves_v5',authoring,script_id:'test:event_script/npc'}}};
  const before=JSON.stringify(state);
  const context=vm.createContext({state,$,expectedNpcCves:()=>({scriptId:'test:event_script/npc'}),eventPathFromId:()=>true});
  vm.runInContext(app.slice(app.indexOf('function closeNpcEventPicker('),app.indexOf('function renderNpcEventPicker(')),context);
  vm.runInContext(app.slice(app.indexOf('function renderEventRuntime('),app.indexOf('function changeEventRuntimeEngine(')),context);
  context.renderEventRuntime();
  const isCustom=authoring==='custom';
  assert.equal($('#event-script-link').hidden,!isCustom);
  assert.equal($('#event-script-id').value,isCustom?'test:event_script/npc':'');
  assert.equal($('#event-script-id').disabled,!isCustom);
  assert.equal($('#open-cves-event').disabled,!isCustom);
  assert.equal($('#event-customize-action').hidden,isCustom);
  assert.equal(JSON.stringify(state),before);
  if(!isCustom)assert.equal($('#event-cves-preview').hidden,true);
});

test('NPC event picker hides even its own generated script',()=>{
  const nodes=new Map();const $=key=>{if(!nodes.has(key))nodes.set(key,node());return nodes.get(key);};
  const context=vm.createContext({$,npcEventItems:[preset,custom],npcEventPickerIndex:-1,document:{createElement:node}});
  vm.runInContext(app.slice(app.indexOf('function renderNpcEventPicker('),app.indexOf('async function openNpcEventPicker(')),context);
  context.renderNpcEventPicker();
  assert.equal($('#npc-event-options').children.length,1);
  assert.match($('#npc-event-options').children[0].textContent,/Custom battle/);
});

test('conversion action is outside hidden linkage and generated mode has no library filter',()=>{
  const html=web('index.html');
  const link=html.split('\n').find(line=>line.includes('id="event-script-link"'));
  assert.ok(!link.includes('id="customize-cves-event"'));
  assert.match(html,/id="event-customize-action"[^\n]+id="customize-cves-event"/);
  assert.ok(!web('cves.html').includes('library-management'));
  assert.match(web('styles.css'),/#event-script-link\[hidden\], #event-customize-action\[hidden\] \{ display:none; \}/);
});
