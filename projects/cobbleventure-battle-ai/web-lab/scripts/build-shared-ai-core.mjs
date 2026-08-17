import { cp, mkdir } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const battleAiRoot = resolve(scriptDirectory, "../..");
const wrapper = process.platform === "win32" ? "gradlew.bat" : "./gradlew";
const build = spawnSync(
  join(battleAiRoot, wrapper),
  ["-p", battleAiRoot, ":shared-ai-core:jsNodeProductionLibraryDistribution"],
  { cwd: battleAiRoot, stdio: "inherit", shell: process.platform === "win32" },
);
if (build.status !== 0) process.exit(build.status ?? 1);

const source = join(battleAiRoot, "shared-ai-core/build/dist/js/productionLibrary");
const destination = resolve(scriptDirectory, "../generated/shared-ai-core");
await mkdir(destination, { recursive: true });
await cp(source, destination, { recursive: true, force: true });
