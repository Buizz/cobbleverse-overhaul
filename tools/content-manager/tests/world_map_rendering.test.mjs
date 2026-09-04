import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { worldMapViewBox, buildWorldRenderIndex, createFrameScheduler, drawWorldMapTiles } from '../web/world-map-rendering.mjs';

test('viewBox matches wide, narrow and resized viewports without letterboxing', () => {
  for (const viewport of [{width:1600,height:650},{width:350,height:900},{width:980,height:360}]) {
    for (const zoom of [.65,1,1.6]) {
      const view = worldMapViewBox({x:123,y:-456},zoom,viewport);
      assert.ok(Math.abs(view.width/view.height-viewport.width/viewport.height)<1e-10);
      assert.ok(Math.abs(view.x+view.width/2-123)<1e-10);
      assert.ok(Math.abs(view.y+view.height/2+456)<1e-10);
      assert.ok(Math.abs(view.width*zoom-viewport.width)<1e-10);
    }
  }
});

test('cell indexes preserve first-match semantics and exclude water from roads', () => {
  const town={anchor:{q:0,r:0}}, otherTown={anchor:{q:0,r:0}};
  const water={surface_style:'water',cells:[{q:1,r:1}]};
  const road={cells:[{q:1,r:1},{q:2,r:1}]};
  const duplicate={cells:[{q:1,r:1}]};
  const tile={q:1,r:1,biome:'forest'};
  const index=buildWorldRenderIndex({tiles:[tile,{...tile,biome:'ocean'}],settlements:[town,otherTown],connections:[water,road,duplicate]},t=>[t.anchor],r=>r.cells);
  assert.equal(index.tiles.get('1,1'),tile);
  assert.equal(index.towns.get('0,0'),town);
  assert.equal(index.footprints.get('0,0'),town);
  assert.equal(index.roads.get('1,1'),road);
  assert.equal(index.paths.get(water),water.cells);
  assert.equal(index.routeCells.size,2);
});

test('footprints and paths are calculated once per entity, not per visible tile', () => {
  let footprints=0,paths=0;
  const layout={settlements:Array.from({length:100},(_,q)=>({anchor:{q,r:0}})),connections:Array.from({length:100},(_,q)=>({cells:[{q,r:0}]}))};
  const index=buildWorldRenderIndex(layout,t=>{footprints++;return [t.anchor];},r=>{paths++;return r.cells;});
  for(let q=0;q<10000;q++) {index.footprints.get(`${q},0`);index.roads.get(`${q},0`);}
  assert.equal(footprints,100);assert.equal(paths,100);
});

test('new frame index reflects deletions and edited cell properties', () => {
  const layout={tiles:[{q:0,r:0}],environment_overrides:[{q:0,r:0}],level_overrides:[{q:0,r:0,average_level:20}],empty_terrain:{tiles:[{q:0,r:0,type:'water'}]}};
  const before=buildWorldRenderIndex(layout,()=>[],()=>[]);
  assert.equal(before.levels.get('0,0').average_level,20);
  assert.equal(before.empty.get('0,0').type,'water');
  layout.tiles=[];layout.environment_overrides=[];
  const after=buildWorldRenderIndex(layout,()=>[],()=>[]);
  assert.equal(after.tiles.size,0);assert.equal(after.environment.size,0);
});

test('render requests coalesce per animation frame and schedule again afterward', () => {
  const queue=[];let draws=0;
  const schedule=createFrameScheduler(()=>draws++,callback=>queue.push(callback));
  for(let n=0;n<100;n++)schedule();
  assert.equal(queue.length,1);assert.equal(draws,0);
  queue.shift()();assert.equal(draws,1);
  schedule();queue.shift()();assert.equal(draws,2);
});

test('canvas renderer applies one transform and draws all visible tiles', () => {
  const calls=[];
  const context=new Proxy({}, {get(target,key) {
    if (!(key in target)) target[key]=(...args)=>calls.push([key,...args]);
    return target[key];
  },set(target,key,value){target[key]=value;calls.push(['set',key,value]);return true;}});
  const hatchContext={beginPath(){},moveTo(){},lineTo(){},stroke(){}};
  const canvas={width:0,height:0,dataset:{},ownerDocument:{createElement:()=>({getContext:()=>hatchContext})},getContext:()=>context};
  drawWorldMapTiles(canvas,{x:0,y:0,width:400,height:200},{width:800,height:400},[
    {x:10,y:10,radius:8,tone:'forest',isEmpty:false,hasTile:true},
    {x:30,y:10,radius:8,tone:'water',isEmpty:true,symbol:'≈'},
  ],{pixelRatio:1});
  assert.deepEqual([canvas.width,canvas.height],[800,400]);
  assert.equal(canvas.dataset.tileCount,'2');
  assert.equal(calls.filter(([name])=>name==='setTransform').length,1);
  assert.equal(calls.filter(([name])=>name==='fill').length,4);
  assert.ok(calls.some(([name])=>name==='fillText'));
});

test('editor observes its viewport, uses a canvas tile layer and delegates activation', () => {
  const source=readFileSync(new URL('../web/app.js',import.meta.url),'utf8');
  const markup=readFileSync(new URL('../web/index.html',import.meta.url),'utf8');
  assert.ok(source.includes('worldMapResizeObserver.observe($("#world-hex-map"))'));
  assert.ok(source.includes('drawWorldMapTiles($("#world-hex-tiles"), view, viewport, tiles)'));
  assert.ok(source.includes('const overlayChanged = nextOverlayMarkup !== worldMapOverlayMarkup'));
  assert.ok(!source.includes('reconcileHexTiles(svg.querySelector'));
  assert.ok(markup.includes('<canvas id="world-hex-tiles" aria-hidden="true"></canvas>'));
  assert.ok(source.includes('addEventListener("keydown", handleWorldTileActivation)'));
  const hover=source.slice(source.indexOf('function updateBrushPreview('),source.indexOf('function beginMapPan('));
  assert.ok(hover.includes('renderWorldMapPreview()'));
  assert.ok(!hover.includes('renderHexMap()'));
  assert.ok(!source.includes('980 / state.mapZoom'));
});
