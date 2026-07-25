import { resolve } from "node:path";
import { readNbt } from "../lib/nbt-reader.mjs";

const filePath = process.argv[2];
if (!filePath) {
  throw new Error(
    "Usage: node scripts/inspect-cobblemon-party.mjs <playerpartystore.dat>",
  );
}

const document = await readNbt(resolve(filePath));
process.stdout.write(`${JSON.stringify(document.value, null, 2)}\n`);
