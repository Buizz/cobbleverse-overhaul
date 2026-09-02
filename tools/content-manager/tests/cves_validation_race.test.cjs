const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../web/cves-editor.js'), 'utf8');
const validateSource = source.slice(source.indexOf('async function validateTree()'), source.indexOf('async function applySource()'));

function harness() {
  let finish;
  const rendered = [];
  const original = { root: { events: [] } };
  const context = {
    state: { path: 'first.cves', ast: original, source: 'first', dirty: false },
    request: () => new Promise(resolve => { finish = resolve; }),
    updateState: (...args) => rendered.push(args),
    $: () => ({}),
    renderTree: () => rendered.push('tree'),
    renderInspector: () => rendered.push('inspector'),
    renderDiagnostics: () => rendered.push('diagnostics'),
  };
  vm.createContext(context);
  vm.runInContext(validateSource, context);
  return { context, rendered, finish: result => finish(result) };
}

test('validation still updates the current document', async () => {
  const h = harness();
  const task = h.context.validateTree();
  const ast = { root: { events: [] } };
  h.finish({ data: { ast, canonical: 'formatted', valid: true } });
  await task;
  assert.equal(h.context.state.ast, ast);
  assert.equal(h.context.state.source, 'formatted');
  assert.ok(h.rendered.includes('tree'));
});

for (const replacement of ['different-script', 'same-path-new-ast']) {
  for (const success of [true, false]) {
    test(`ignore stale ${success ? 'success' : 'error'} after ${replacement}`, async () => {
      const h = harness();
      const task = h.context.validateTree();
      const newAst = { root: { events: [] } };
      h.context.state.ast = newAst;
      h.context.state.source = 'new document';
      if (replacement === 'different-script') h.context.state.path = 'second.cves';
      h.rendered.length = 0;
      h.finish({ data: success ? { ast: { root: { events: [] } }, canonical: 'old', valid: true } : { error: 'old error' } });
      await task;
      assert.equal(h.context.state.ast, newAst);
      assert.equal(h.context.state.source, 'new document');
      assert.deepEqual(h.rendered, []);
    });
  }
}
