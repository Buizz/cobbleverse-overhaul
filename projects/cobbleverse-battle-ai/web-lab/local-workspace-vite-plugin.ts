import { execFile } from "node:child_process";
import { access, readFile, readdir, stat, writeFile } from "node:fs/promises";
import type { IncomingMessage, ServerResponse } from "node:http";
import { basename, dirname, join, resolve } from "node:path";
import { promisify } from "node:util";
import type { Plugin } from "vite";
import {
  createLocalBattleAudioCatalog,
  streamLocalBattleAudio,
  type LocalBattleAudioCatalogResult,
} from "./local-audio-catalog";

const execFileAsync = promisify(execFile);

type SavedWorkspace = {
  modWorkspacePath?: string;
  resourceWorkspacePath?: string;
  resourcePacksPath?: string;
  resourcePackCount?: number;
  // Legacy key retained for reading existing local settings.
  workspacePath: string;
  modsPath: string;
  cobblemonJar: string;
  savedAt: string;
};

const folderPickerScript = String.raw`
Add-Type -AssemblyName System.Windows.Forms
$dialog = New-Object System.Windows.Forms.FolderBrowserDialog
$dialog.Description = "Cobbleverse Battle Lab 폴더 선택"
$dialog.ShowNewFolderButton = $true
$owner = New-Object System.Windows.Forms.Form
$owner.TopMost = $true
$owner.ShowInTaskbar = $false
$owner.StartPosition = [System.Windows.Forms.FormStartPosition]::CenterScreen
$owner.Size = New-Object System.Drawing.Size(1, 1)
$owner.Opacity = 0
if ($env:COBBLEVERSE_INITIAL_FOLDER -and (Test-Path -LiteralPath $env:COBBLEVERSE_INITIAL_FOLDER -PathType Container)) {
  $dialog.SelectedPath = (Resolve-Path -LiteralPath $env:COBBLEVERSE_INITIAL_FOLDER).Path
}
$owner.Show()
$result = $dialog.ShowDialog($owner)
if ($result -eq [System.Windows.Forms.DialogResult]::OK) {
  [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
  [Console]::Write($dialog.SelectedPath)
}
$owner.Close()
$owner.Dispose()
$dialog.Dispose()
`;

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
    modWorkspacePath?: unknown;
    resourceWorkspacePath?: unknown;
    workspacePath?: unknown;
  };
}

async function inspectModWorkspace(inputPath: string) {
  const selectedPath = resolve(inputPath.trim());
  const selectedStat = await stat(selectedPath);
  if (!selectedStat.isDirectory()) {
    throw new Error("선택한 경로가 폴더가 아닙니다.");
  }

  const modsPath =
    basename(selectedPath).toLowerCase() === "mods"
      ? selectedPath
      : join(selectedPath, "mods");
  const modsStat = await stat(modsPath);
  if (!modsStat.isDirectory()) {
    throw new Error("선택한 경로에서 mods 폴더를 찾지 못했습니다.");
  }

  const entries = await readdir(modsPath, { withFileTypes: true });
  const jars = entries
    .filter((entry) => entry.isFile() && /\.jar(?:\.disabled)?$/i.test(entry.name))
    .map((entry) => entry.name);
  const cobblemonJars = jars
    .filter((name) => /^cobblemon-(?:neoforge|fabric)-.+\.jar$/i.test(name))
    .sort((left, right) => right.localeCompare(left, undefined, { numeric: true }));

  if (cobblemonJars.length === 0) {
    throw new Error("mods 폴더에서 Cobblemon 본체 JAR을 찾지 못했습니다.");
  }

  return {
    workspacePath:
      basename(selectedPath).toLowerCase() === "mods"
        ? dirname(selectedPath)
        : selectedPath,
    modsPath,
    cobblemonJar: join(modsPath, cobblemonJars[0]),
    modCount: jars.length,
  };
}

async function inspectResourceWorkspace(inputPath: string) {
  const selectedPath = resolve(inputPath.trim());
  const selectedStat = await stat(selectedPath);
  if (!selectedStat.isDirectory()) {
    throw new Error("선택한 리소스 경로가 폴더가 아닙니다.");
  }

  const resourcePacksPath =
    basename(selectedPath).toLowerCase() === "resourcepacks"
      ? selectedPath
      : join(selectedPath, "resourcepacks");
  const resourcePacksStat = await stat(resourcePacksPath);
  if (!resourcePacksStat.isDirectory()) {
    throw new Error(
      "선택한 경로에서 resourcepacks 폴더를 찾지 못했습니다.",
    );
  }

  const entries = await readdir(resourcePacksPath, { withFileTypes: true });
  const resourcePackCount = entries.filter(
    (entry) =>
      entry.isDirectory() ||
      (entry.isFile() && /\.(?:zip|jar)$/i.test(entry.name)),
  ).length;

  return {
    resourceWorkspacePath:
      basename(selectedPath).toLowerCase() === "resourcepacks"
        ? dirname(selectedPath)
        : selectedPath,
    resourcePacksPath,
    resourcePackCount,
  };
}

export function localWorkspace(): Plugin {
  const appDirectory = process.cwd();
  const configPath = join(appDirectory, ".local-workspace.json");
  const syncScript = join(
    appDirectory,
    "scripts",
    "sync-cobblemon-localization.mjs",
  );
  let audioCatalogPath = "";
  let audioCatalogPromise: Promise<LocalBattleAudioCatalogResult> | null = null;

  const loadSavedWorkspace = async () =>
    JSON.parse(await readFile(configPath, "utf8")) as SavedWorkspace;
  const loadAudioCatalog = async () => {
    const saved = await loadSavedWorkspace();
    const resourcePacksPath = String(saved.resourcePacksPath ?? "").trim();
    if (!resourcePacksPath) {
      return {
        catalog: { configured: false, events: [], trackCount: 0 },
        sources: new Map(),
      } satisfies LocalBattleAudioCatalogResult;
    }
    if (audioCatalogPath !== resourcePacksPath || !audioCatalogPromise) {
      audioCatalogPath = resourcePacksPath;
      audioCatalogPromise = createLocalBattleAudioCatalog(resourcePacksPath);
    }
    return audioCatalogPromise;
  };

  return {
    name: "cobbleverse-local-workspace",
    enforce: "pre",
    configureServer(server) {
      server.middlewares.use(
        "/api/local-audio/catalog",
        async (request, response) => {
          if (request.method !== "GET") {
            sendJson(response, 405, {
              ok: false,
              message: "지원하지 않는 요청입니다.",
            });
            return;
          }
          try {
            const result = await loadAudioCatalog();
            sendJson(response, 200, { ok: true, ...result.catalog });
          } catch (error) {
            audioCatalogPromise = null;
            sendJson(response, 500, {
              ok: false,
              configured: true,
              events: [],
              trackCount: 0,
              message:
                error instanceof Error
                  ? error.message
                  : "리소스팩 사운드를 읽지 못했습니다.",
            });
          }
        },
      );

      server.middlewares.use(
        "/api/local-audio/file",
        async (request, response) => {
          if (request.method !== "GET") {
            sendJson(response, 405, {
              ok: false,
              message: "지원하지 않는 요청입니다.",
            });
            return;
          }
          try {
            const requestUrl = new URL(
              request.url ?? "/",
              "http://localhost",
            );
            const id = requestUrl.searchParams.get("id") ?? "";
            const result = await loadAudioCatalog();
            const source = result.sources.get(id);
            if (!source) {
              sendJson(response, 404, {
                ok: false,
                message: "요청한 배틀 사운드를 찾지 못했습니다.",
              });
              return;
            }
            streamLocalBattleAudio(response, source);
          } catch (error) {
            sendJson(response, 500, {
              ok: false,
              message:
                error instanceof Error
                  ? error.message
                  : "배틀 사운드를 재생하지 못했습니다.",
            });
          }
        },
      );

      server.middlewares.use(
        "/api/local-folder-picker",
        async (request, response) => {
          if (request.method !== "POST") {
            sendJson(response, 405, {
              ok: false,
              message: "지원하지 않는 요청입니다.",
            });
            return;
          }

          if (process.platform !== "win32") {
            sendJson(response, 501, {
              ok: false,
              message: "폴더 찾아보기는 현재 Windows에서만 지원합니다.",
            });
            return;
          }

          try {
            const payload = await requestBody(request);
            const initialPath = String(payload.workspacePath ?? "").trim();
            const { stdout } = await execFileAsync(
              "powershell.exe",
              [
                "-NoLogo",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-STA",
                "-Command",
                folderPickerScript,
              ],
              {
                cwd: appDirectory,
                env: {
                  ...process.env,
                  COBBLEVERSE_INITIAL_FOLDER: initialPath,
                },
                windowsHide: true,
                maxBuffer: 1024 * 1024,
              },
            );
            const selectedPath = stdout.trim();
            sendJson(response, 200, {
              ok: true,
              cancelled: !selectedPath,
              selectedPath,
            });
          } catch (error) {
            sendJson(response, 500, {
              ok: false,
              message:
                error instanceof Error
                  ? error.message
                  : "폴더 선택 창을 열지 못했습니다.",
            });
          }
        },
      );

      server.middlewares.use(
        "/api/local-workspace",
        async (request, response) => {
          if (request.method === "GET") {
            try {
              const saved = JSON.parse(
                await readFile(configPath, "utf8"),
              ) as SavedWorkspace;
              await access(saved.cobblemonJar);
              const modWorkspacePath =
                saved.modWorkspacePath || saved.workspacePath;
              const inspected = await inspectModWorkspace(modWorkspacePath);
              const resourceWorkspace = saved.resourceWorkspacePath
                ? await inspectResourceWorkspace(saved.resourceWorkspacePath)
                : null;
              sendJson(response, 200, {
                ok: true,
                configured: true,
                settings: {
                  ...saved,
                  modWorkspacePath: inspected.workspacePath,
                  workspacePath: inspected.workspacePath,
                  resourceWorkspacePath:
                    resourceWorkspace?.resourceWorkspacePath ?? "",
                  resourcePacksPath:
                    resourceWorkspace?.resourcePacksPath ?? "",
                  resourcePackCount:
                    resourceWorkspace?.resourcePackCount ?? 0,
                  modCount: inspected.modCount,
                  cobblemonVersion: basename(inspected.cobblemonJar),
                },
              });
            } catch {
              sendJson(response, 200, { ok: true, configured: false });
            }
            return;
          }

          if (request.method !== "POST") {
            sendJson(response, 405, {
              ok: false,
              message: "지원하지 않는 요청입니다.",
            });
            return;
          }

          try {
            const payload = await requestBody(request);
            const modWorkspacePath = String(
              payload.modWorkspacePath ?? payload.workspacePath ?? "",
            ).trim();
            const resourceWorkspacePath = String(
              payload.resourceWorkspacePath ?? "",
            ).trim();
            if (!modWorkspacePath) {
              sendJson(response, 422, {
                ok: false,
                message:
                  "MOD 작업공간 또는 mods 폴더를 입력해 주세요.",
              });
              return;
            }

            const inspected = await inspectModWorkspace(modWorkspacePath);
            const resourceWorkspace = resourceWorkspacePath
              ? await inspectResourceWorkspace(resourceWorkspacePath)
              : null;
            await execFileAsync(
              process.execPath,
              [syncScript, inspected.cobblemonJar],
              {
                cwd: appDirectory,
                windowsHide: true,
                maxBuffer: 16 * 1024 * 1024,
              },
            );
            const saved: SavedWorkspace = {
              modWorkspacePath: inspected.workspacePath,
              resourceWorkspacePath:
                resourceWorkspace?.resourceWorkspacePath ?? "",
              resourcePacksPath: resourceWorkspace?.resourcePacksPath ?? "",
              resourcePackCount: resourceWorkspace?.resourcePackCount ?? 0,
              workspacePath: inspected.workspacePath,
              modsPath: inspected.modsPath,
              cobblemonJar: inspected.cobblemonJar,
              savedAt: new Date().toISOString(),
            };
            await writeFile(
              configPath,
              `${JSON.stringify(saved, null, 2)}\n`,
              "utf8",
            );
            audioCatalogPath = "";
            audioCatalogPromise = null;
            sendJson(response, 200, {
              ok: true,
              configured: true,
              settings: {
                ...saved,
                modCount: inspected.modCount,
                cobblemonVersion: basename(inspected.cobblemonJar),
              },
            });
          } catch (error) {
            sendJson(response, 422, {
              ok: false,
              message:
                error instanceof Error
                  ? error.message
                  : "로컬 작업공간을 저장하지 못했습니다.",
            });
          }
        },
      );
    },
  };
}
