// Uses the actual Cobblemon engine extracted by the development run, not vanilla Showdown.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const root = path.resolve(__dirname, '../../../../..');
const engine = path.resolve(process.env.COBBLEVENTURE_SHOWDOWN_PATH ||
  path.join(root, 'projects/cobbleventure-world-bootstrap/run/showdown'));
const rules = JSON.parse(fs.readFileSync(path.join(__dirname, '../../main/resources/showdown-empty-slots.json'), 'utf8'));
const originalLoader = Module._extensions['.js'];
Module._extensions['.js'] = (module, filename) => {
  const replacements = rules[path.relative(engine, filename).replaceAll('\\', '/')];
  if (!replacements) return originalLoader(module, filename);
  let source = fs.readFileSync(filename, 'utf8');
  for (const {from, to} of replacements) {
    if (source.includes(to)) continue;
    assert.ok(source.includes(from), `Engine patch target missing: ${from}`);
    source = source.replaceAll(from, to);
  }
  module._compile(source, filename);
};
const {Battle} = require(path.join(engine, 'sim'));
let serial = 0;
const pokemon = (level = 50) => ({name:'Eevee',species:'Eevee',uuid:String(++serial),
  level,currentHealth:100,moves:['tackle'],movesInfo:[{pp:35,maxPp:35}],ability:'runaway'});
for (const {count, level} of [{count:1,level:5}, {count:1,level:50}, {count:1,level:100}, {count:2,level:50}]) {
  const requests = [];
  const battle = new Battle({formatid:'gen9doublescustomgame', seed:[1,2,3,4],
    send(type, data) {
      if (type === 'sideupdate' && data.startsWith('p1\n|request|')) {
        requests.push(JSON.parse(data.slice('p1\n|request|'.length)));
      }
    }});
  battle.setPlayer('p1',{name:'Player',team:Array.from({length:count},()=>pokemon(level))});
  battle.setPlayer('p2',{name:'Trainers',team:[pokemon(),pokemon()]});
  battle.makeChoices(count === 1 ? 'team 1' : 'team 12', 'team 12');
  assert.equal(battle.p1.pokemon.length, count);
  assert.equal(battle.p1.active.filter(Boolean).length, count);
  assert.equal(battle.p2.active.filter(Boolean).length, 2);
  if (count === 1) {
    assert.equal(battle.p1.active[1], null);
    // Preserve the engine's null slot, but never send a null moveset to Cobblemon.
    assert.equal(JSON.parse(JSON.stringify(battle.p1.activeRequest)).active[1], null);
    assert.deepEqual(requests.at(-1).active[1], {moves:[]});
  }
  assert.ok(!battle.log.some(line => line.includes('switch failed')), 'Do not switch an absent Pokémon in');
  for (let turn = 0; turn < 100 && !battle.ended; turn++) {
    // Mirror the client: every empty slot must retain its index and submit a pass.
    const request = requests.at(-1);
    if (request.active) {
      for (const moveset of request.active) {
        assert.ok(moveset && Array.isArray(moveset.moves), 'Cobblemon requires non-null movesets');
      }
      if (count === 1) {
        const target = battle.p2.active.findIndex(pokemon => pokemon && !pokemon.fainted) + 1;
        assert.ok(battle.p1.choose(`move 1 ${target}, pass`), battle.p1.choice.error);
        battle.p2.autoChoose();
        battle.commitDecisions();
      } else {
        battle.makeChoices();
      }
    } else {
      battle.makeChoices();
    }
    assert.ok(!battle.p1.choice.error && !battle.p2.choice.error);
  }
  assert.ok(battle.ended, 'Battle must finish without hanging on an empty slot');
  console.log(`${count} vs 2 (level ${level}) completed: winner=${battle.winner}, turn=${battle.turn}`);
  // Wire normalization never compacts slots or changes wait/switch/ordinary requests.
  for (const update of [
    {wait:true}, {forceSwitch:[true,false]},
    {active:[{moves:[],canMegaEvo:true}]},
    {active:[null,{moves:[],canMegaEvo:true}]},
  ]) {
    const original = JSON.stringify(update);
    battle.p1.emitRequest(update);
    const expected = JSON.parse(original);
    if (expected.active) expected.active = expected.active.map(slot => slot ?? {moves:[]});
    assert.deepEqual(requests.at(-1), expected);
    assert.equal(JSON.stringify(update), original, 'Wire adaptation must not mutate engine state');
  }
}
