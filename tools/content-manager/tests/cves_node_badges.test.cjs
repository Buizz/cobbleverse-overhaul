const assert = require('node:assert/strict');
const {test} = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../web/cves-editor.js'), 'utf8');
function element(tag, className, text) {
  return {tag, className, text, dataset:{}, attributes:{}, children:[],
    setAttribute(key,value) {this.attributes[key]=value;}, append(...children) {this.children.push(...children);}};
}
const context = vm.createContext({element,document:{createElementNS:(namespace,tag)=>element(tag)}});
vm.runInContext(source.slice(source.indexOf('const LABELS'),source.indexOf('const state')),context);
vm.runInContext(source.slice(source.indexOf('function createNodeBadge'),source.indexOf('function renderTree')),context);

test('every AST node kind has a distinct vector icon and a readable Korean label', () => {
  const labels = {event:'이벤트',page:'페이지',say:'대사',narrate:'설명',let:'변수',if:'조건',choice:'선택지',choice_option:'항목',repeat:'반복',command:'명령'};
  const paths = new Set();
  for (const [kind,label] of Object.entries(labels)) {
    const badge = context.createNodeBadge({node:kind});
    assert.equal(badge.dataset.kind,kind);
    assert.equal(badge.children[1].text,label);
    const svg=badge.children[0];
    assert.equal(svg.attributes['aria-hidden'],'true');
    assert.equal(svg.attributes.focusable,'false');
    assert.equal(svg.attributes.viewBox,'0 0 16 16');
    paths.add(svg.children[0].attributes.d);
  }
  assert.equal(paths.size,Object.keys(labels).length);
});

test('unknown nodes retain their label and receive a safe fallback icon', () => {
  const badge=context.createNodeBadge({node:'future_node'});
  assert.equal(badge.children[1].text,'future_node');
  assert.ok(badge.children[0].children[0].attributes.d);
  assert.match(source,/row\.append\(toggle, createNodeBadge\(node\)\)/);
});
