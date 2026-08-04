import { availableParallelism } from "node:os";
import { join } from "node:path";
import { Worker } from "node:worker_threads";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { Plugin } from "vite";

const difficulties = new Set([
  "novice",
  "standard",
  "advanced",
  "expert",
  "expert_winrate",
  "expert_search",
  "cheater",
]);
const strategies = new Set([
  "balanced",
  "aggressive",
  "defensive",
  "ace_check",
  "reckless_ace",
  "setup",
  "hazard",
  "tempo",
  "unpredictable",
]);

type SweepProfile = {
  difficulty: string;
  strategy: string;
};

type SweepJob = {
  seed: number;
  aiProfiles: SweepProfile[];
};

type IndexedSweepJob = SweepJob & {
  index: number;
};

type WorkerResult =
  | {
      ok: true;
      results: Array<{
        index: number;
        seed: number;
        status: string;
        winner: string | null;
        turns: number;
        durationMs: number;
      }>;
    }
  | {
      ok: false;
      issues: Array<{ path: string; code: string; message: string }>;
    };

function sendJson(
  response: ServerResponse,
  status: number,
  payload: unknown,
) {
  response.statusCode = status;
  response.setHeader("content-type", "application/json; charset=utf-8");
  response.setHeader("cache-control", "no-store");
  response.end(JSON.stringify(payload));
}

async function requestBody(request: IncomingMessage) {
  const chunks: Buffer[] = [];
  for await (const chunk of request) {
    chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8")) as {
    scenario?: unknown;
    jobs?: unknown;
    concurrency?: unknown;
  };
}

function validJob(value: unknown): value is SweepJob {
  if (!value || typeof value !== "object") return false;
  const job = value as Partial<SweepJob>;
  return (
    Number.isInteger(job.seed) &&
    Array.isArray(job.aiProfiles) &&
    job.aiProfiles.length === 2 &&
    job.aiProfiles.every(
      (profile) =>
        profile &&
        difficulties.has(profile.difficulty) &&
        strategies.has(profile.strategy),
    )
  );
}

function requestedParallelism(value: unknown, jobCount: number) {
  const automatic = Math.min(8, Math.max(1, availableParallelism() - 1));
  const requested = Number(value);
  const parallelism =
    Number.isInteger(requested) && requested > 0 ? requested : automatic;
  return Math.min(jobCount, Math.max(1, Math.min(8, parallelism)));
}

function splitJobs(jobs: SweepJob[], parallelism: number) {
  const chunks = Array.from(
    { length: parallelism },
    () => [] as IndexedSweepJob[],
  );
  jobs.forEach((job, index) => {
    chunks[index % parallelism].push({ ...job, index });
  });
  return chunks.filter((chunk) => chunk.length > 0);
}

function runWorker(
  workerPath: string,
  scenario: unknown,
  jobs: IndexedSweepJob[],
) {
  return new Promise<WorkerResult>((resolve, reject) => {
    const worker = new Worker(workerPath, { type: "module" });
    let settled = false;
    const finish = (result: WorkerResult) => {
      if (settled) return;
      settled = true;
      resolve(result);
      void worker.terminate();
    };
    worker.once("message", finish);
    worker.once("error", (error) => {
      if (settled) return;
      settled = true;
      reject(error);
    });
    worker.once("exit", (code) => {
      if (settled) return;
      settled = true;
      reject(
        new Error(
          `반복 전투 워커가 결과 없이 종료 코드 ${code}로 중단됐습니다.`,
        ),
      );
    });
    worker.postMessage({ scenario, jobs });
  });
}

export function localBattleSweep(): Plugin {
  const workerPath = join(
    process.cwd(),
    "scripts",
    "battle-sweep-worker.mjs",
  );

  return {
    name: "cobbleventure-local-battle-sweep",
    enforce: "pre",
    configureServer(server) {
      server.middlewares.use(
        "/api/battle-sweep",
        async (request, response) => {
          if (request.method !== "POST") {
            sendJson(response, 405, {
              ok: false,
              issues: [
                {
                  path: "$",
                  code: "method_not_allowed",
                  message: "POST 요청만 지원합니다.",
                },
              ],
            });
            return;
          }

          try {
            const payload = await requestBody(request);
            if (
              !Array.isArray(payload.jobs) ||
              payload.jobs.length < 1 ||
              payload.jobs.length > 1_600 ||
              !payload.jobs.every(validJob)
            ) {
              sendJson(response, 422, {
                ok: false,
                issues: [
                  {
                    path: "jobs",
                    code: "invalid_sweep_jobs",
                    message:
                      "반복 전투 작업은 1~1600개의 올바른 AI 설정이어야 합니다.",
                  },
                ],
              });
              return;
            }

            const parallelism = requestedParallelism(
              payload.concurrency,
              payload.jobs.length,
            );
            const workerResults = await Promise.all(
              splitJobs(payload.jobs, parallelism).map((jobs) =>
                runWorker(workerPath, payload.scenario, jobs),
              ),
            );
            const failure = workerResults.find((result) => !result.ok);
            if (failure && !failure.ok) {
              sendJson(response, 422, failure);
              return;
            }
            const results = workerResults
              .flatMap((result) => (result.ok ? result.results : []))
              .sort((left, right) => left.index - right.index)
              .map((result) => ({
                seed: result.seed,
                status: result.status,
                winner: result.winner,
                turns: result.turns,
                durationMs: result.durationMs,
              }));
            sendJson(response, 201, {
              ok: true,
              results,
              parallelism,
              executionMode: "worker_threads",
            });
          } catch (error) {
            sendJson(response, 422, {
              ok: false,
              issues: [
                {
                  path: "$",
                  code: "battle_sweep_failed",
                  message:
                    error instanceof Error
                      ? error.message
                      : "반복 전투를 완료하지 못했습니다.",
                },
              ],
            });
          }
        },
      );
    },
  };
}
