import assert from "node:assert/strict";
import test from "node:test";

async function requestWorker(path = "/", init = {}) {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);

  return worker.fetch(
    new Request(`http://localhost${path}`, {
      ...init,
      headers: { host: "localhost", ...init.headers },
    }),
    {
      ASSETS: {
        fetch: async () => new Response("Not found", { status: 404 }),
      },
    },
    {
      waitUntil() {},
      passThroughOnException() {},
    },
  );
}

test("server-renders the Cobbleverse battle lab shell", async () => {
  const response = await requestWorker("/", {
    headers: { accept: "text/html" },
  });
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  assert.match(html, /<html lang="ko">/i);
  assert.match(html, /<title>Cobbleverse Battle Lab(?: · Cobbleverse)?<\/title>/i);
  assert.match(html, /PvE 테스트/);
  assert.match(html, /EvE 테스트/);
  assert.match(html, /검증하고 배틀 시작/);
  assert.match(html, /저장된 전투 이어하기/);
  assert.match(html, /TEST SEED/);
  assert.doesNotMatch(html, /react-loading-skeleton|Your site is taking shape/);
});

test("server-renders the separate EVE report route", async () => {
  const response = await requestWorker("/eve-report", {
    headers: { accept: "text/html" },
  });
  assert.equal(response.status, 200);
  const html = await response.text();
  assert.match(html, /EVE ANALYSIS REPORT/);
  assert.match(html, /아직 자동대전 리포트가 없습니다/);
});

test("serves the scenario creation API from the production worker", async () => {
  const response = await requestWorker("/api/scenarios", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      mode: "eve",
      seed: 77,
      aiProfiles: [
        { difficulty: "expert", strategy: "aggressive" },
        { difficulty: "standard", strategy: "defensive" },
      ],
      sides: [
        { source: "preset", trainerId: "kanto_brock" },
        { source: "preset", trainerId: "kanto_misty" },
      ],
    }),
  });

  const body = await response.json();
  assert.equal(response.status, 201, JSON.stringify(body));
  assert.equal(body.ok, true);
  assert.equal(body.scenario.mode, "eve");
  assert.equal(body.scenario.seed, 77);
  assert.equal(body.scenario.sides[0].name, "Brock");
  assert.equal(body.scenario.sides[1].name, "Misty");
});

test("runs a complete preset battle through the production worker", async () => {
  const response = await requestWorker("/api/battles", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      mode: "eve",
      seed: 77,
      aiProfiles: [
        { difficulty: "expert", strategy: "aggressive" },
        { difficulty: "standard", strategy: "defensive" },
      ],
      sides: [
        { source: "preset", trainerId: "kanto_brock" },
        { source: "preset", trainerId: "kanto_misty" },
      ],
    }),
  });

  assert.equal(response.status, 201);
  const body = await response.json();
  assert.equal(body.ok, true);
  assert.equal(body.battle.status, "completed");
  assert.ok(["Brock", "Misty"].includes(body.battle.winner));
  assert.ok(body.battle.turns > 0);
  assert.ok(body.battle.events.some((event) => event.type === "move"));
  assert.match(
    body.battle.engine.controller,
    /expert-aggressive-vs-standard-defensive/,
  );
  assert.deepEqual(body.scenario.aiProfiles, [
    { difficulty: "expert", strategy: "aggressive" },
    { difficulty: "standard", strategy: "defensive" },
  ]);
  assert.ok(body.battle.aiTrace.length > 0);
});

test("returns exact native turn HP snapshots through the battle API", async () => {
  const response = await requestWorker("/api/battles", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      mode: "eve",
      seed: 78,
      battleEngine: "cobbleverse",
      aiProfiles: [
        { difficulty: "expert", strategy: "balanced" },
        { difficulty: "expert", strategy: "balanced" },
      ],
      sides: [
        { source: "preset", trainerId: "dbingsu-server-party" },
        { source: "preset", trainerId: "hoenn_league_drake" },
      ],
    }),
  });

  const body = await response.json();
  assert.equal(response.status, 201, JSON.stringify(body));
  assert.equal(body.ok, true);
  assert.equal(body.battle.engine.id, "cobbleverse-simple");
  assert.equal(body.battle.turnSnapshots.length, body.battle.turns + 1);
  assert.equal(body.battle.turnSnapshots[0].turn, 0);
  assert.ok(
    body.battle.turnSnapshots[0].sides.every((side) =>
      side.team.every((pokemon) => pokemon.hp === pokemon.maxHp),
    ),
  );
});
