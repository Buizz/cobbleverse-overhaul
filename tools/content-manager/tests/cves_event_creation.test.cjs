const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../web/cves-editor.js'), 'utf8');

function harness() {
  const original = {node:'event',trigger:{name:'interact'},pages:[]};
  const state = {ast:{root:{events:[original]}}, path:'test/original.cves',digest:'original',library:{managed:false},contract:{
    triggers:[{id:'interact',arguments:[{name:'range'}]}, {id:'proximity_enter',arguments:[{name:'range'}]},
      {id:'quest',arguments:[]},{id:'flag_changed',arguments:[{name:'target',resource_kind:'flag'}]}],
    resources:{flag:['test:ready']},
  }};
  const context = vm.createContext({state, markDirty:()=>{state.dirty=true;},renderTree:()=>{},renderInspector:()=>{}});
  for (const name of ['literal','argument','block']) {
    vm.runInContext(source.split('\n').find(line=>line.startsWith(`function ${name}(`)), context);
  }
  vm.runInContext('function triggerContract(kind) { return state.contract.triggers.find(t=>t.id===kind); }', context);
  vm.runInContext(source.slice(source.indexOf('function requireEventEditable('),source.indexOf('function scriptIdFromPath(')),context);
  return {state,context,original};
}

test('append preserves existing events and document identity; creates an empty default page',()=>{
  const {state,context,original}=harness(); const ast=state.ast;
  const added=context.appendEvent('proximity_enter',{range:'6'});
  assert.equal(state.ast,ast); assert.equal(state.ast.root.events[0],original);
  assert.equal(state.ast.root.events.length,2); assert.equal(state.selected,added);
  assert.equal(state.path,'test/original.cves'); assert.equal(state.digest,'original'); assert.equal(state.dirty,true);
  assert.equal(added.pages[0].condition,null); assert.equal(added.pages[0].block.statements.length,0);
  assert.equal(added.trigger.arguments[0].value.value,6);
  context.appendEvent('proximity_enter',{range:'9'});
  assert.equal(state.ast.root.events.length,3);
});

test('interact and quest are unique; quest has no trigger arguments',()=>{
  const {state,context}=harness();
  assert.throws(()=>context.appendEvent('interact',{range:'4'}),/하나만/);
  const quest=context.appendEvent('quest'); assert.equal(quest.trigger.arguments.length,0);
  assert.throws(()=>context.appendEvent('quest'),/하나만/);
  assert.equal(state.ast.root.events.length,2);
});

test('positive fractional range is supported; invalid ranges do not mutate the tree',()=>{
  const {state,context}=harness();
  for(const range of ['',0,-1,'NaN','Infinity',undefined]) {
    assert.throws(()=>context.appendEvent('proximity_enter',{range}),/0보다/);
  }
  assert.equal(state.ast.root.events.length,1); assert.equal(state.dirty,undefined);
  const added=context.appendEvent('proximity_enter',{range:'0.25'});
  assert.equal(added.trigger.arguments[0].value.value_type,'decimal');
  assert.equal(added.trigger.arguments[0].value.value,'0.25');
});

test('targeted triggers require an explicitly selected catalog resource',()=>{
  const {state,context}=harness();
  for(const target of ['',undefined,'test:missing']) assert.throws(()=>context.appendEvent('flag_changed',{target}),/리소스/);
  assert.equal(state.ast.root.events.length,1);
  const added=context.appendEvent('flag_changed',{target:'test:ready'});
  assert.equal(added.trigger.arguments[0].name,'target');
  assert.equal(added.trigger.arguments[0].value.value,'test:ready');
});

for(const guard of ['loading','managed','sourceDirty','missingAst']) {
  test(`event addition respects ${guard}`,()=>{
    const {state,context}=harness(); const ast=state.ast;
    if(guard==='managed') state.library.managed=true;
    else if(guard==='missingAst') state.ast=null;
    else state[guard]=true;
    assert.throws(()=>context.appendEvent('proximity_enter',{range:4}));
    assert.equal(ast.root.events.length,1); assert.equal(state.dirty,undefined);
  });
}

test('unknown triggers fail without mutation',()=>{
  const {state,context}=harness(); assert.throws(()=>context.appendEvent('unknown'),/지원하지/);
  assert.equal(state.ast.root.events.length,1);
});

test('dialog checks stale document identity before adding; closing only clears the dialog snapshot',()=>{
  const submit=source.slice(source.indexOf('$("#add-event-form").addEventListener("submit"'),source.indexOf('$("#new-script")'));
  assert.ok(submit.indexOf('eventDialogAst !== state.ast') < submit.indexOf('appendEvent(values.trigger'));
  assert.match(source,/"close", \(\) => \{ eventDialogAst = null; \}/);
});
