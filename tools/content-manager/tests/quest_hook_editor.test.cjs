const assert = require('node:assert/strict');
const {test} = require('node:test');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../web/quest-editor.js'), 'utf8');

function picker(hook = null) {
  const controls = Object.fromEntries(['script','npc','edit','search'].map(key => [key, {
    value:'', options:[], listeners:{}, disabled:false,
    replaceChildren(...values) {this.options=values;this.value=values[0]?.value || '';},
    add(value) {this.options.push(value);},
    addEventListener(type, callback) {this.listeners[type]=callback;}
  }]));
  const host = {classList:{add(){}},querySelector(selector) {return controls[selector.match(/data-hook-(\w+)/)[1]];}};
  const state = {dirty:false, npcs:[{id:'cv:npc/oak',name:'오박사'}], events:[
    {script_id:'cv:event_script/quest',name:'수락 인사',quest_compatible:true,metadata:{tags:['메인']}},
    {script_id:'cv:event_script/other',name:'완료',quest_compatible:true},
    {script_id:'cv:event_script/preset',name:'프리셋',quest_compatible:true,managed:true},
    {script_id:'cv:event_script/interact',name:'대화',quest_compatible:false}
  ]};
  const context = vm.createContext({state,escapeHtml:String, Option:function(text,value){this.text=text;this.value=value;},openHookEvent(){}});
  vm.runInContext(source.slice(source.indexOf('function hookValue'),source.indexOf('function openHookEvent')), context);
  context.renderHookPicker(host, hook, '수락');
  return {controls,host,state,context};
}

test('picker excludes managed/non-quest scripts and searches tags', () => {
  const {controls} = picker();
  assert.deepEqual(controls.script.options.map(x=>x.value), ['', 'cv:event_script/quest','cv:event_script/other']);
  controls.search.listeners.input({target:{value:'메인'}});
  assert.deepEqual(controls.script.options.map(x=>x.value), ['', 'cv:event_script/quest']);
  assert.equal(controls.npc.disabled,true);
});

test('selected stale references survive rendering and search until explicitly cleared', () => {
  const {controls,host,context,state} = picker({script_id:'cv:event_script/missing',npc_id:'cv:npc/missing'});
  controls.search.listeners.input({target:{value:'찾을 수 없음'}});
  assert.equal(context.hookValue(host).script_id,'cv:event_script/missing');
  assert.equal(context.hookValue(host).npc_id,'cv:npc/missing');
  controls.script.value=''; controls.script.listeners.change();
  controls.search.listeners.input({target:{value:''}});
  assert.equal(context.hookValue(host),null);
  assert.equal(state.dirty,true);
  assert.equal(controls.npc.disabled,true);
});

test('serializer saves and removes optional hooks without losing legacy data', () => {
  const fields = {id:'cv:quest/one',nameKo:'테스트',nameEn:'',summaryKo:'',summaryEn:'',category:'main',acceptMode:'all',completionMode:'automatic'};
  const hooks = {'#quest-accept-event':{script_id:'cv:event_script/start',npc_id:'cv:npc/oak'},'#quest-complete-event':{script_id:'cv:event_script/end',npc_id:'cv:npc/oak'}};
  const context=vm.createContext({state:{document:{next_quests:['legacy'],event_hooks:{}}},
    form:{elements:{...Object.fromEntries(Object.entries(fields).map(([k,v])=>[k,{value:v}])),enabled:{checked:true}}},
    conditionEditor:{read:()=>[]},acceptEditor:{},objectivesFromEditor:()=>[], $:x=>x,hookValue:x=>hooks[x]});
  vm.runInContext(source.slice(source.indexOf('function documentFromForm'), source.indexOf('function fill(')),context);
  const saved=context.documentFromForm();
  assert.equal(saved.event_hooks.on_accept.script_id,'cv:event_script/start');
  assert.equal(saved.event_hooks.on_complete.script_id,'cv:event_script/end');
  assert.deepEqual(saved.next_quests,['legacy']);
  hooks['#quest-accept-event']=null;hooks['#quest-complete-event']=null;
  assert.equal(Object.hasOwn(context.documentFromForm(),'event_hooks'),false);
});
