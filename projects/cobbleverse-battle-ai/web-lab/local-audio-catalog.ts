import { execFile, spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { readFile, readdir } from "node:fs/promises";
import type { ServerResponse } from "node:http";
import { basename, join, relative } from "node:path";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const AUDIO_ENTRY_PATTERN =
  /^assets\/([^/]+)\/sounds\/(.+)\.(ogg|mp3|wav)$/i;
const SOUNDS_JSON_PATTERN = /^assets\/([^/]+)\/sounds\.json$/i;
const archiveListScript = String.raw`
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($env:COBBLEVERSE_AUDIO_ARCHIVE)
try {
  [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
  [Console]::Write(($archive.Entries.FullName -join [Environment]::NewLine))
} finally {
  $archive.Dispose()
}
`;
const archiveEntryScript = String.raw`
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($env:COBBLEVERSE_AUDIO_ARCHIVE)
try {
  $entry = $archive.GetEntry($env:COBBLEVERSE_AUDIO_ENTRY)
  if ($null -eq $entry) {
    [Console]::Error.Write("Archive entry not found.")
    exit 2
  }
  $inputStream = $entry.Open()
  try {
    $outputStream = [Console]::OpenStandardOutput()
    $inputStream.CopyTo($outputStream)
    $outputStream.Flush()
  } finally {
    $inputStream.Dispose()
  }
} finally {
  $archive.Dispose()
}
`;

type AudioSource =
  | { kind: "file"; path: string; extension: string }
  | { kind: "archive"; archivePath: string; entryPath: string; extension: string };

type SoundReference = {
  name: string;
  volume: number;
};

type SoundDefinition = {
  event: string;
  references: SoundReference[];
};

export type LocalBattleAudioTrack = {
  id: string;
  name: string;
  volume: number;
  pack: string;
};

export type LocalBattleAudioEvent = {
  id: string;
  tracks: LocalBattleAudioTrack[];
};

export type LocalBattleAudioCatalog = {
  configured: boolean;
  events: LocalBattleAudioEvent[];
  trackCount: number;
};

export type LocalBattleAudioCatalogResult = {
  catalog: LocalBattleAudioCatalog;
  sources: Map<string, AudioSource>;
};

function normalizedEntryPath(value: string) {
  return value.replaceAll("\\", "/").replace(/^\.?\//, "");
}

function contentType(extension: string) {
  if (extension === ".mp3") return "audio/mpeg";
  if (extension === ".wav") return "audio/wav";
  return "audio/ogg";
}

function trackId(packPath: string, entryPath: string) {
  return createHash("sha256")
    .update(`${packPath}\0${entryPath}`)
    .digest("hex")
    .slice(0, 24);
}

export function parseSoundDefinitions(
  contents: string,
  defaultNamespace: string,
) {
  const parsed = JSON.parse(contents) as Record<
    string,
    { sounds?: Array<string | { name?: string; volume?: number }> }
  >;
  return Object.entries(parsed).map(([event, definition]) => ({
    event: event.includes(":") ? event : `${defaultNamespace}:${event}`,
    references: (definition?.sounds ?? [])
      .map((sound) => {
        const name = typeof sound === "string" ? sound : sound?.name;
        if (!name) return null;
        return {
          name: name.includes(":") ? name : `${defaultNamespace}:${name}`,
          volume:
            typeof sound === "object" && Number.isFinite(sound.volume)
              ? Number(sound.volume)
              : 1,
        };
      })
      .filter((sound): sound is SoundReference => Boolean(sound)),
  }));
}

async function directoryFiles(root: string, directory = root): Promise<string[]> {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = await Promise.all(
    entries.map(async (entry) => {
      const path = join(directory, entry.name);
      return entry.isDirectory() ? directoryFiles(root, path) : [path];
    }),
  );
  return files.flat();
}

async function scanDirectoryPack(packPath: string) {
  const files = await directoryFiles(packPath);
  const definitions: SoundDefinition[] = [];
  const resources = new Map<
    string,
    { source: AudioSource; pack: string; entryPath: string }
  >();

  for (const path of files) {
    const entryPath = normalizedEntryPath(relative(packPath, path));
    const audioMatch = entryPath.match(AUDIO_ENTRY_PATTERN);
    if (audioMatch) {
      resources.set(`${audioMatch[1]}:${audioMatch[2]}`, {
        source: {
          kind: "file",
          path,
          extension: `.${audioMatch[3].toLowerCase()}`,
        },
        pack: basename(packPath),
        entryPath,
      });
      continue;
    }
    const soundsMatch = entryPath.match(SOUNDS_JSON_PATTERN);
    if (soundsMatch) {
      definitions.push(
        ...parseSoundDefinitions(await readFile(path, "utf8"), soundsMatch[1]),
      );
    }
  }
  return { definitions, resources };
}

async function archiveEntries(archivePath: string) {
  const { stdout } = await execFileAsync(
    "powershell.exe",
    [
      "-NoLogo",
      "-NoProfile",
      "-ExecutionPolicy",
      "Bypass",
      "-Command",
      archiveListScript,
    ],
    {
      windowsHide: true,
      encoding: "utf8",
      maxBuffer: 64 * 1024 * 1024,
      env: {
        ...process.env,
        COBBLEVERSE_AUDIO_ARCHIVE: archivePath,
      },
    },
  );
  return stdout
    .split(/\r?\n/)
    .map(normalizedEntryPath)
    .filter(Boolean);
}

async function archiveText(archivePath: string, entryPath: string) {
  const { stdout } = await execFileAsync(
    "powershell.exe",
    [
      "-NoLogo",
      "-NoProfile",
      "-ExecutionPolicy",
      "Bypass",
      "-Command",
      archiveEntryScript,
    ],
    {
      windowsHide: true,
      maxBuffer: 8 * 1024 * 1024,
      encoding: "buffer",
      env: {
        ...process.env,
        COBBLEVERSE_AUDIO_ARCHIVE: archivePath,
        COBBLEVERSE_AUDIO_ENTRY: entryPath,
      },
    },
  );
  return stdout.toString("utf8");
}

async function scanArchivePack(packPath: string) {
  const entries = await archiveEntries(packPath);
  const definitions: SoundDefinition[] = [];
  const resources = new Map<
    string,
    { source: AudioSource; pack: string; entryPath: string }
  >();

  for (const entryPath of entries) {
    const audioMatch = entryPath.match(AUDIO_ENTRY_PATTERN);
    if (audioMatch) {
      resources.set(`${audioMatch[1]}:${audioMatch[2]}`, {
        source: {
          kind: "archive",
          archivePath: packPath,
          entryPath,
          extension: `.${audioMatch[3].toLowerCase()}`,
        },
        pack: basename(packPath),
        entryPath,
      });
      continue;
    }
    const soundsMatch = entryPath.match(SOUNDS_JSON_PATTERN);
    if (soundsMatch) {
      definitions.push(
        ...parseSoundDefinitions(
          await archiveText(packPath, entryPath),
          soundsMatch[1],
        ),
      );
    }
  }
  return { definitions, resources };
}

export async function createLocalBattleAudioCatalog(
  resourcePacksPath: string,
  baseArchives: string[] = [],
): Promise<LocalBattleAudioCatalogResult> {
  const entries = await readdir(resourcePacksPath, { withFileTypes: true });
  const packs = entries.filter(
    (entry) =>
      entry.isDirectory() ||
      (entry.isFile() && /\.(?:zip|jar)$/i.test(entry.name)),
  );
  const scanned = await Promise.all([
    ...baseArchives.map((archivePath) => scanArchivePack(archivePath)),
    ...packs.map((entry) => {
      const packPath = join(resourcePacksPath, entry.name);
      return entry.isDirectory()
        ? scanDirectoryPack(packPath)
        : scanArchivePack(packPath);
    }),
  ]);
  const resources = new Map<
    string,
    { source: AudioSource; pack: string; entryPath: string }
  >();
  const definitions = new Map<string, SoundDefinition>();

  for (const pack of scanned) {
    for (const definition of pack.definitions) {
      definitions.set(definition.event, definition);
    }
    for (const [name, resource] of pack.resources) {
      resources.set(name, resource);
    }
  }

  const sources = new Map<string, AudioSource>();
  const events = Array.from(definitions.values())
    .filter((definition) =>
      /(?:^|:)(?:battle\.|move\.|impact\.|poke_ball\.(?:send_out|recall|hit))/i.test(
        definition.event,
      ),
    )
    .map((definition) => ({
      id: definition.event.replace(/^[^:]+:/, ""),
      tracks: definition.references
        .map((reference) => {
          const resource = resources.get(reference.name);
          if (!resource) return null;
          const id = trackId(
            resource.source.kind === "file"
              ? resource.source.path
              : resource.source.archivePath,
            resource.entryPath,
          );
          sources.set(id, resource.source);
          return {
            id,
            name: reference.name,
            volume: reference.volume,
            pack: resource.pack,
          };
        })
        .filter((track): track is LocalBattleAudioTrack => Boolean(track)),
    }))
    .filter((event) => event.tracks.length > 0);

  return {
    catalog: {
      configured: true,
      events,
      trackCount: sources.size,
    },
    sources,
  };
}

export function streamLocalBattleAudio(
  response: ServerResponse,
  source: AudioSource,
) {
  response.statusCode = 200;
  response.setHeader("content-type", contentType(source.extension));
  response.setHeader("cache-control", "private, max-age=3600");
  response.setHeader("accept-ranges", "none");

  if (source.kind === "file") {
    const stream = createReadStream(source.path);
    stream.on("error", () => {
      if (!response.headersSent) response.statusCode = 404;
      response.end();
    });
    stream.pipe(response);
    return;
  }

  const child = spawn(
    "powershell.exe",
    [
      "-NoLogo",
      "-NoProfile",
      "-ExecutionPolicy",
      "Bypass",
      "-Command",
      archiveEntryScript,
    ],
    {
      windowsHide: true,
      stdio: ["ignore", "pipe", "ignore"],
      env: {
        ...process.env,
        COBBLEVERSE_AUDIO_ARCHIVE: source.archivePath,
        COBBLEVERSE_AUDIO_ENTRY: source.entryPath,
      },
    },
  );
  child.stdout.pipe(response);
  child.on("error", () => {
    if (!response.headersSent) response.statusCode = 500;
    response.end();
  });
  response.on("close", () => {
    if (!child.killed) child.kill();
  });
}
