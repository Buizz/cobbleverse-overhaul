const assert = require('node:assert/strict');
const {test} = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../web/cves-editor.js'), 'utf8');

test('condition navigation follows the badge and precedes the expression',()=>{
  const condition={node:'if'};
  const makeElement=(tag,className,text)=>({tag,className,text,children:[],append(...items){this.children.push(...items);},setAttribute(){},addEventListener(){}});
  const context=vm.createContext({state:{collapsed:new WeakSet([condition])},renderedRows:new WeakMap(),
    element:makeElement,createNodeBadge:()=>makeElement('span','badge'),nodeLabel:()=> 'a very long condition',
    createBranchNavigation:()=>makeElement('span','branch-jumps'),flowBadges:()=>[],findNode:()=>null});
  vm.runInContext(source.slice(source.indexOf('function renderNode('),source.indexOf('function renderBlock(')),context);
  const row=context.renderNode(condition).children[0];
  assert.deepEqual(Array.from(row.children,child=>child.className),['node-toggle','badge','branch-jumps','label']);
  const css=fs.readFileSync(path.join(__dirname,'../web/cves-editor.css'),'utf8');
  const jumps=css.split('.branch-jumps {')[1].split('}')[0];
  assert.doesNotMatch(jumps,/margin-left:\s*auto/);
  assert.match(jumps,/flex:\s*none/);
});

function harness({empty=false,missing=false,managed=false}={}) {
  const first={node:'say'}, other={node:'command'};
  const node={node:'if',then_block:{statements:empty?[]:[first]},else_block:missing?null:{statements:[other]}};
  const parent={node:'page'};
  const state={selected:node,dirty:false,library:{managed},collapsed:new WeakSet([node,parent])};
  const calls=[];
  const target={isConnected:true,classList:{add:x=>calls.push(x)},focus:x=>calls.push(['focus',x]),scrollIntoView:x=>calls.push(['scroll',x])};
  const context=vm.createContext({state,
    findNode:x=>x===node?{parent}:x===parent?{parent:null}:null,
    renderedRows:new WeakMap([[first,target],[other,target],[node,target]]),renderedBlocks:new WeakMap([[node.then_block,target]]),
    renderTree:()=>calls.push('tree'),renderInspector:()=>calls.push('inspector'),
    requestAnimationFrame:fn=>calls.push(fn),element:(tag,className,text)=>({tag,className,text,children:[],listeners:{},attributes:{},setAttribute(k,v){this.attributes[k]=v;},append(x){this.children.push(x);},addEventListener(k,fn){this.listeners[k]=fn;}})});
  vm.runInContext(source.slice(source.indexOf('function navigateBranch'),source.indexOf('function renderTree')),context);
  return {context,state,node,parent,first,other,calls,target};
}

for(const branch of ['then','else']) test(`${branch} selects its first statement, opens ancestors and focuses after rendering`,()=>{
  const h=harness();const before=JSON.stringify(h.node);
  h.context.navigateBranch(h.node,branch);
  assert.equal(h.state.selected,branch==='then'?h.first:h.other);
  assert.equal(h.state.collapsed.has(h.node),false);
  assert.equal(h.state.collapsed.has(h.parent),false);
  assert.deepEqual(h.calls.slice(0,3),['tree','inspector','branch-destination']);
  h.calls[3]();
  assert.equal(h.calls[4][0],'focus');assert.equal(h.calls[4][1].preventScroll,true);
  assert.equal(h.calls[5][0],'scroll');assert.equal(h.calls[5][1].block,'center');
  assert.equal(h.state.dirty,false);assert.equal(JSON.stringify(h.node),before);
});

test('compact T/F buttons retain descriptive accessible names and tooltips',()=>{
  const h=harness();const controls=h.context.createBranchNavigation(h.node);
  for(const [index,text,label] of [[0,'T','참으로 가기'],[1,'F','거짓으로 가기']]) {
    const button=controls.children[index];
    assert.equal(button.text,text);assert.equal(button.attributes['aria-label'],label);
    assert.ok(button.title.startsWith(label));
  }
  const css=fs.readFileSync(path.join(__dirname,'../web/cves-editor.css'),'utf8');
  const style=css.split('.branch-jump {')[1].split('}')[0];
  assert.match(style,/background: transparent/);assert.match(style,/border: 1px solid transparent/);
});

test('empty branch focuses its caption without inventing AST nodes',()=>{
  const h=harness({empty:true});h.context.navigateBranch(h.node,'then');
  assert.equal(h.state.selected,h.node);assert.equal(h.node.then_block.statements.length,0);
  h.calls[3]();assert.equal(h.calls[4][0],'focus');
});

test('missing else is disabled; clicking an available branch stops row selection bubbling',()=>{
  const h=harness({missing:true});const controls=h.context.createBranchNavigation(h.node);
  assert.equal(controls.children[1].disabled,true);
  h.context.navigateBranch(h.node,'else');assert.equal(h.calls.length,0);
  let stopped=false;controls.children[0].listeners.click({stopPropagation(){stopped=true;}});
  assert.equal(stopped,true);assert.equal(h.state.selected,h.first);
});

test('read-only preset trees allow navigation without edits',()=>{
  const h=harness({managed:true});h.context.navigateBranch(h.node,'then');
  assert.equal(h.state.selected,h.first);assert.equal(h.state.dirty,false);
});

test('obsolete destinations and removed nodes cannot steal focus',()=>{
  const h=harness();h.context.navigateBranch(h.node,'then');
  h.target.isConnected=false;h.calls[3]();assert.equal(h.calls.length,4);
  h.context.navigateBranch({...h.node},'then');assert.equal(h.calls.length,4);
});

test('return selects and focuses the owning condition without changing the script',()=>{
  const h=harness({managed:true}); const before=JSON.stringify(h.node);
  h.state.selected=h.first;
  h.context.navigateCondition(h.node);
  assert.equal(h.state.selected,h.node);
  assert.equal(h.state.collapsed.has(h.parent),false);
  assert.equal(h.state.collapsed.has(h.node),false);
  h.calls[3]();
  assert.equal(h.calls[4][0],'focus'); assert.equal(h.calls[5][0],'scroll');
  assert.equal(JSON.stringify(h.node),before); assert.equal(h.state.dirty,false);
});

test('both empty branch captions have return buttons; ordinary blocks do not',()=>{
  const h=harness({empty:true});
  vm.runInContext(source.slice(source.indexOf('function renderBlock('),source.indexOf('function field(')),h.context);
  for(const caption of ['then','else','commands']) {
    const headings=[];
    h.context.renderBlock({append:x=>headings.push(x)},{statements:[]},caption,caption==='commands'?null:h.node);
    const button=headings[0].children[0];
    if(caption==='commands') {assert.equal(button,undefined);continue;}
    assert.equal(button.text,'↶'); assert.equal(button.attributes['aria-label'],'조건 노드로 가기'); assert.equal(button.type,'button');
    let stopped=false;button.listeners.click({stopPropagation(){stopped=true;}});
    assert.equal(stopped,true);assert.equal(h.state.selected,h.node);
  }
});

test('nested branch returns to its own condition, not an outer condition',()=>{
  const h=harness();const inner={node:'if',then_block:{statements:[]},else_block:null};
  h.node.then_block.statements=[inner];
  h.parent.block={statements:[h.node]};
  h.state.ast={root:{events:[{node:'event',pages:[h.parent]}]}};
  h.context.renderedRows.set(inner,h.target);
  vm.runInContext(source.slice(source.indexOf('function findNode('),source.indexOf('function insertionTarget(')),h.context);
  h.context.navigateCondition(inner);
  assert.equal(h.state.selected,inner);assert.equal(h.state.dirty,false);
});

test('return ignores removed conditions and detached destinations',()=>{
  const h=harness();h.context.navigateCondition({...h.node});assert.equal(h.calls.length,0);
  h.context.navigateCondition(h.node);h.target.isConnected=false;h.calls[3]();
  assert.equal(h.calls.length,4);
});
