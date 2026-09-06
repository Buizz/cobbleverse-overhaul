from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import sys
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any


PROFILE_ID = re.compile(r"^[a-z0-9][a-z0-9_-]*$")
FIXED_ZIP_TIME = (1980, 1, 1, 0, 0, 0)
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
SERVER_REQUIRED_FILES = {
    "README-SERVER.txt",
    "eula.txt",
    "server-manifest.json",
    "server.properties",
    "setup-server.ps1",
    "start-server.bat",
    "start-server.sh",
    "user_jvm_args.txt",
}
SERVER_EXCLUDED_PATHS = {
    "config/cobblemon-battle-extras-server.json",
    "config/cobblemon-battle-extras.json",
    "config/iris.properties",
    "config/neoforge-client.toml",
    "config/resourcepackoverrides.json",
    "options.txt",
}
SERVER_EXCLUDED_DIRECTORIES = {
    "resourcepacks",
    "shaderpacks",
    "screenshots",
    "saves",
    "config/paxi/resourcepacks",
}


class PackError(RuntimeError):
    pass


class DuplicateKeyError(ValueError):
    pass


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateKeyError(f"중복 JSON 키: {key}")
        result[key] = value
    return result


def load_json(path: Path) -> Any:
    try:
        with path.open("r", encoding="utf-8-sig") as source:
            return json.load(source, object_pairs_hook=_reject_duplicate_keys)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        raise PackError(f"JSON을 읽을 수 없습니다: {path}: {error}") from error


def _required_string(data: dict[str, Any], key: str, path: str) -> str:
    value = data.get(key)
    if not isinstance(value, str) or not value.strip():
        raise PackError(f"{path}.{key}는 비어 있지 않은 문자열이어야 합니다.")
    return value


def _inside(root: Path, candidate: Path, label: str) -> Path:
    resolved_root = root.resolve()
    resolved_candidate = candidate.resolve()
    try:
        resolved_candidate.relative_to(resolved_root)
    except ValueError as error:
        raise PackError(f"{label} 경로가 저장소 밖을 가리킵니다: {resolved_candidate}") from error
    return resolved_candidate


def _png_dimensions(data: bytes, label: str) -> tuple[int, int]:
    if len(data) < 24 or data[:8] != PNG_SIGNATURE or data[12:16] != b"IHDR":
        raise PackError(f"{label}은 올바른 PNG 파일이어야 합니다.")
    width = int.from_bytes(data[16:20], "big")
    height = int.from_bytes(data[20:24], "big")
    if width < 400 or height < 400 or width != height:
        raise PackError(f"{label}은 400x400 이상의 정사각형 PNG여야 합니다: {width}x{height}")
    return width, height


def _normalized_resource_pack(source: Path, pack_format: int) -> bytes:
    try:
        with zipfile.ZipFile(source) as archive:
            names = archive.namelist()
            if "pack.mcmeta" not in names or not any(
                name.startswith("assets/") for name in names
            ):
                raise PackError(
                    f"로컬 리소스팩 루트에 pack.mcmeta와 assets/가 필요합니다: {source}"
                )
            metadata = json.loads(archive.read("pack.mcmeta").decode("utf-8-sig"))
            if not isinstance(metadata, dict) or not isinstance(metadata.get("pack"), dict):
                raise PackError(f"로컬 리소스팩 pack.mcmeta 형식이 올바르지 않습니다: {source}")
            metadata["pack"]["pack_format"] = pack_format
            output = io.BytesIO()
            with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as normalized:
                for name in sorted(names):
                    if name == "pack.mcmeta":
                        normalized.writestr(
                            _zip_info(name), _json_bytes(metadata)
                        )
                    elif name.endswith("/"):
                        normalized.writestr(_zip_info(name, directory=True), b"")
                    else:
                        normalized.writestr(_zip_info(name), archive.read(name))
            return output.getvalue()
    except (OSError, zipfile.BadZipFile, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise PackError(f"로컬 리소스팩을 읽을 수 없습니다: {source}: {error}") from error


def load_profile(root: Path, profile_path: Path) -> dict[str, Any]:
    root = root.resolve()
    path = profile_path if profile_path.is_absolute() else root / profile_path
    path = _inside(root, path, "프로필")
    profile = load_json(path)
    if not isinstance(profile, dict):
        raise PackError("프로필 루트는 객체여야 합니다.")
    if profile.get("schema_version") != 1:
        raise PackError("지원하는 스모크 프로필 schema_version은 1입니다.")

    profile_id = _required_string(profile, "profile_id", "$")
    if not PROFILE_ID.fullmatch(profile_id):
        raise PackError("$.profile_id가 올바른 소문자 ID가 아닙니다.")
    for key in (
        "name",
        "version",
        "author",
        "purpose",
        "notice",
        "icon",
        "overrides_directory",
        "output",
    ):
        _required_string(profile, key, "$")
    if not isinstance(profile.get("production_ready"), bool):
        raise PackError("$.production_ready는 boolean이어야 합니다.")

    minecraft = profile.get("minecraft")
    if not isinstance(minecraft, dict):
        raise PackError("$.minecraft는 객체여야 합니다.")
    _required_string(minecraft, "version", "$.minecraft")
    loader = minecraft.get("mod_loader")
    if not isinstance(loader, dict):
        raise PackError("$.minecraft.mod_loader는 객체여야 합니다.")
    loader_id = _required_string(loader, "id", "$.minecraft.mod_loader")
    if not loader_id.startswith("neoforge-"):
        raise PackError("스모크 프로필의 로더 ID는 neoforge-로 시작해야 합니다.")
    if loader.get("primary") is not True:
        raise PackError("스모크 프로필의 NeoForge 로더는 primary여야 합니다.")

    files = profile.get("files")
    if not isinstance(files, list):
        raise PackError("$.files는 배열이어야 합니다.")
    for index, item in enumerate(files):
        if not isinstance(item, dict):
            raise PackError(f"$.files[{index}]는 객체여야 합니다.")
        project_id = item.get("projectID")
        file_id = item.get("fileID")
        if not isinstance(project_id, int) or project_id < 1:
            raise PackError(f"$.files[{index}].projectID는 양의 정수여야 합니다.")
        if not isinstance(file_id, int) or file_id < 1:
            raise PackError(f"$.files[{index}].fileID는 양의 정수여야 합니다.")
        if item.get("required") is not True:
            raise PackError(f"$.files[{index}].required는 true여야 합니다.")

    overrides = _inside(root, root / profile["overrides_directory"], "overrides")
    if not overrides.is_dir():
        raise PackError(f"overrides 디렉터리가 없습니다: {overrides}")
    output = _inside(root, root / profile["output"], "출력")
    expected_dist = (root / "dist").resolve()
    try:
        output.relative_to(expected_dist)
    except ValueError as error:
        raise PackError(f"출력은 dist 디렉터리 안에 있어야 합니다: {output}") from error
    if output.suffix.lower() != ".zip":
        raise PackError("출력 파일 확장자는 .zip이어야 합니다.")

    local_resourcepacks: list[tuple[Path, str, int]] = []
    configured_resourcepacks = profile.get("local_resourcepacks", [])
    if not isinstance(configured_resourcepacks, list):
        raise PackError("$.local_resourcepacks는 배열이어야 합니다.")
    seen_targets: set[str] = set()
    local_assets = (root / "local-assets").resolve()
    for index, configured in enumerate(configured_resourcepacks):
        item_path = f"$.local_resourcepacks[{index}]"
        if not isinstance(configured, dict):
            raise PackError(f"{item_path}는 객체여야 합니다.")
        source_value = _required_string(configured, "source", item_path)
        target = _required_string(configured, "target", item_path)
        if PurePosixPath(target).name != target or not target.lower().endswith(".zip"):
            raise PackError(f"{item_path}.target은 경로 없는 ZIP 파일명이어야 합니다.")
        target_key = target.casefold()
        if target_key in seen_targets:
            raise PackError(f"중복 로컬 리소스팩 대상 파일명: {target}")
        seen_targets.add(target_key)
        pack_format = configured.get("pack_format")
        if not isinstance(pack_format, int) or isinstance(pack_format, bool) or pack_format < 1:
            raise PackError(f"{item_path}.pack_format은 양의 정수여야 합니다.")
        source = _inside(root, root / source_value, "로컬 리소스팩")
        try:
            source.relative_to(local_assets)
        except ValueError as error:
            raise PackError(
                f"{item_path}.source는 local-assets 아래 파일이어야 합니다."
            ) from error
        if not source.is_file():
            raise PackError(f"로컬 리소스팩 파일이 없습니다: {source}")
        _normalized_resource_pack(source, pack_format)
        local_resourcepacks.append((source, target, pack_format))

    icon = _inside(root, root / profile["icon"], "아이콘")
    if not icon.is_file():
        raise PackError(f"아이콘 파일이 없습니다: {icon}")
    if icon.suffix.lower() != ".png":
        raise PackError("아이콘 파일 확장자는 .png여야 합니다.")
    _png_dimensions(icon.read_bytes(), "팩 아이콘")

    profile["_profile_path"] = path
    profile["_overrides_path"] = overrides
    profile["_output_path"] = output
    profile["_icon_path"] = icon
    profile["_local_resourcepacks"] = local_resourcepacks
    return profile


def manifest_for(profile: dict[str, Any]) -> dict[str, Any]:
    minecraft = profile["minecraft"]
    loader = minecraft["mod_loader"]
    return {
        "minecraft": {
            "version": minecraft["version"],
            "modLoaders": [
                {
                    "id": loader["id"],
                    "primary": True,
                }
            ],
        },
        "manifestType": "minecraftModpack",
        "manifestVersion": 1,
        "name": profile["name"],
        "version": profile["version"],
        "author": profile["author"],
        "image": "icon.png",
        "files": profile["files"],
        "overrides": "overrides",
    }


def _zip_info(name: str, directory: bool = False) -> zipfile.ZipInfo:
    normalized = name.rstrip("/") + "/" if directory else name
    info = zipfile.ZipInfo(normalized, FIXED_ZIP_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3
    info.external_attr = (0o755 if directory else 0o644) << 16
    if directory:
        info.external_attr |= 0x10
    return info


def _write_bytes(archive: zipfile.ZipFile, name: str, data: bytes) -> None:
    archive.writestr(_zip_info(name), data)


def _json_bytes(data: Any) -> bytes:
    return (json.dumps(data, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _archive_name(relative: Path) -> str:
    name = PurePosixPath("overrides", *relative.parts).as_posix()
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or "\\" in name:
        raise PackError(f"안전하지 않은 ZIP 경로입니다: {name}")
    return name


def _server_archive_name(relative: Path) -> str:
    name = PurePosixPath(*relative.parts).as_posix()
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or "\\" in name:
        raise PackError(f"안전하지 않은 서버 ZIP 경로입니다: {name}")
    return name


def _is_server_override(relative: Path) -> bool:
    name = PurePosixPath(*relative.parts).as_posix()
    folded = name.casefold()
    if folded in SERVER_EXCLUDED_PATHS:
        return False
    return not any(
        folded == directory or folded.startswith(f"{directory}/")
        for directory in SERVER_EXCLUDED_DIRECTORIES
    )


def _load_server_dependencies(root: Path, profile: dict[str, Any]) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    path = _inside(root, root / "pack" / "dependencies.lock.json", "의존성 Lock")
    lock = load_json(path)
    if not isinstance(lock, dict) or lock.get("schema_version") != 1:
        raise PackError("서버 팩에는 schema_version 1 의존성 Lock이 필요합니다.")
    minecraft = lock.get("minecraft")
    if not isinstance(minecraft, dict) or not isinstance(minecraft.get("loader"), dict):
        raise PackError("의존성 Lock의 Minecraft·NeoForge 설정이 올바르지 않습니다.")
    profile_minecraft = profile["minecraft"]
    loader_id = profile_minecraft["mod_loader"]["id"]
    expected_loader = f"neoforge-{minecraft['loader'].get('version')}"
    if minecraft.get("version") != profile_minecraft.get("version") or expected_loader != loader_id:
        raise PackError("의존성 Lock과 개발 팩의 Minecraft·NeoForge 버전이 다릅니다.")

    mods = lock.get("mods")
    if not isinstance(mods, list):
        raise PackError("의존성 Lock의 mods는 배열이어야 합니다.")
    selected: list[dict[str, Any]] = []
    seen_ids: set[str] = set()
    seen_files: set[tuple[int, int]] = set()
    for index, mod in enumerate(mods):
        if not isinstance(mod, dict):
            raise PackError(f"의존성 Lock mods[{index}]가 객체가 아닙니다.")
        if mod.get("enabled") is not True or mod.get("side") == "client":
            continue
        if mod.get("side") not in {"server", "both"}:
            raise PackError(f"서버 모드 {mod.get('id', index)}의 설치면이 올바르지 않습니다.")
        mod_id = _required_string(mod, "id", f"$.mods[{index}]")
        curseforge = mod.get("curseforge")
        project_id = curseforge.get("project_id") if isinstance(curseforge, dict) else None
        file_id = curseforge.get("file_id") if isinstance(curseforge, dict) else None
        if not isinstance(project_id, int) or not isinstance(file_id, int):
            raise PackError(f"서버 모드 {mod_id}의 CurseForge 파일이 확정되지 않았습니다.")
        file_key = (project_id, file_id)
        if mod_id in seen_ids or file_key in seen_files:
            raise PackError(f"서버 모드 의존성이 중복되었습니다: {mod_id}")
        seen_ids.add(mod_id)
        seen_files.add(file_key)
        selected.append({
            "id": mod_id,
            "display_name": _required_string(mod, "display_name", f"$.mods[{index}]"),
            "version": mod.get("version"),
            "side": mod["side"],
            "project_id": project_id,
            "file_id": file_id,
        })
    return lock, selected


def server_manifest_for(
    profile: dict[str, Any], external_mods: list[dict[str, Any]], vendored_mods: list[str]
) -> dict[str, Any]:
    loader_id = profile["minecraft"]["mod_loader"]["id"]
    return {
        "schema_version": 1,
        "name": f"{profile['name']} NeoForge Server",
        "version": profile["version"],
        "minecraft": {
            "version": profile["minecraft"]["version"],
            "loader": {"type": "neoforge", "version": loader_id.removeprefix("neoforge-")},
        },
        "external_mods": external_mods,
        "vendored_mods": vendored_mods,
    }


def _server_setup_script() -> bytes:
    script = r'''param(
    [string]$CurseForgeApiKey = $env:CURSEFORGE_API_KEY,
    [switch]$SkipNeoForge
)
$ErrorActionPreference = "Stop"
$ServerRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$Manifest = Get-Content -LiteralPath (Join-Path $ServerRoot "server-manifest.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$ModsDirectory = Join-Path $ServerRoot "mods"
New-Item -ItemType Directory -Force -Path $ModsDirectory | Out-Null

if (-not $SkipNeoForge -and -not (Test-Path -LiteralPath (Join-Path $ServerRoot "run.bat"))) {
    & java -version
    if ($LASTEXITCODE -ne 0) { throw "Java 21을 찾을 수 없습니다." }
    $NeoForgeVersion = $Manifest.minecraft.loader.version
    $InstallerName = "neoforge-$NeoForgeVersion-installer.jar"
    $Installer = Join-Path $ServerRoot $InstallerName
    $InstallerUrl = "https://maven.neoforged.net/releases/net/neoforged/neoforge/$NeoForgeVersion/$InstallerName"
    Write-Host "NeoForge $NeoForgeVersion 설치 파일을 받습니다."
    Invoke-WebRequest -UseBasicParsing -Uri $InstallerUrl -OutFile $Installer
    Push-Location $ServerRoot
    try { & java -jar $InstallerName --installServer }
    finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw "NeoForge 서버 설치에 실패했습니다." }
    Remove-Item -LiteralPath $Installer -Force
}

if ($Manifest.external_mods.Count -gt 0 -and [string]::IsNullOrWhiteSpace($CurseForgeApiKey)) {
    throw "CurseForge API 키가 필요합니다. CURSEFORGE_API_KEY 환경 변수나 -CurseForgeApiKey 인수로 전달해 주세요."
}
$Headers = @{ "x-api-key" = $CurseForgeApiKey }
foreach ($Mod in $Manifest.external_mods) {
    $Endpoint = "https://api.curseforge.com/v1/mods/$($Mod.project_id)/files/$($Mod.file_id)"
    Write-Host "[$($Mod.id)] 파일 정보를 확인합니다."
    $File = (Invoke-RestMethod -Uri $Endpoint -Headers $Headers).data
    if ([string]::IsNullOrWhiteSpace($File.downloadUrl)) {
        throw "$($Mod.display_name) 다운로드 URL을 받을 수 없습니다. CurseForge 파일 $($Mod.project_id):$($Mod.file_id)을 수동으로 mods 폴더에 넣어 주세요."
    }
    $FileName = [IO.Path]::GetFileName($File.fileName)
    if ([string]::IsNullOrWhiteSpace($FileName) -or -not $FileName.EndsWith(".jar")) {
        throw "$($Mod.display_name)의 안전한 JAR 파일명을 확인할 수 없습니다."
    }
    $Target = Join-Path $ModsDirectory $FileName
    if (Test-Path -LiteralPath $Target) {
        Write-Host "  이미 있음: $FileName"
        continue
    }
    $Temporary = "$Target.download"
    Invoke-WebRequest -UseBasicParsing -Uri $File.downloadUrl -OutFile $Temporary
    Move-Item -LiteralPath $Temporary -Destination $Target -Force
    Write-Host "  설치됨: $FileName"
}
Write-Host "서버 준비가 끝났습니다. eula.txt를 확인한 뒤 start-server.bat을 실행하세요."
'''
    return script.replace("\n", "\r\n").encode("utf-8-sig")


def _server_generated_files(profile: dict[str, Any]) -> dict[str, bytes]:
    loader = profile["minecraft"]["mod_loader"]["id"].removeprefix("neoforge-")
    readme = f"""Cobbleventure NeoForge 서버 준비 팩

Minecraft {profile['minecraft']['version']} / NeoForge {loader}

1. Java 21과 CurseForge API 키를 준비합니다.
2. PowerShell에서 다음 명령을 실행합니다.
   $env:CURSEFORGE_API_KEY = \"발급받은 API 키\"
   .\\setup-server.ps1
3. eula.txt의 내용을 읽고 동의할 경우 eula=false를 eula=true로 바꿉니다.
4. start-server.bat(Windows) 또는 start-server.sh(Linux)를 실행합니다.

setup-server.ps1은 NeoForge 서버 파일을 설치하고 server-manifest.json에 기록된
server/both 모드만 CurseForge에서 내려받습니다. 클라이언트 전용 모드, 셰이더,
리소스팩과 클라이언트 설정은 이 ZIP에 포함되지 않습니다.
"""
    start_bat = """@echo off
if not exist run.bat (
  echo [ERROR] setup-server.ps1을 먼저 실행해 주세요.
  exit /b 1
)
call run.bat nogui
"""
    start_sh = """#!/usr/bin/env sh
set -eu
if [ ! -f ./run.sh ]; then
  echo "[ERROR] setup-server.ps1을 먼저 실행해 주세요." >&2
  exit 1
fi
exec sh ./run.sh nogui
"""
    properties = """# Cobbleventure dedicated server defaults
motd=Cobbleventure NeoForge Server
online-mode=true
difficulty=normal
gamemode=survival
allow-flight=true
view-distance=10
simulation-distance=8
spawn-protection=0
"""
    return {
        "README-SERVER.txt": readme.replace("\n", "\r\n").encode("utf-8-sig"),
        "eula.txt": b"# Read https://aka.ms/MinecraftEULA before changing this value.\r\neula=false\r\n",
        "server.properties": properties.encode("ascii"),
        "setup-server.ps1": _server_setup_script(),
        "start-server.bat": start_bat.replace("\n", "\r\n").encode("utf-8-sig"),
        "start-server.sh": start_sh.encode("utf-8"),
        "user_jvm_args.txt": b"-Xms4G\r\n-Xmx8G\r\n",
    }


def build_pack(root: Path, profile_path: Path) -> Path:
    root = root.resolve()
    profile = load_profile(root, profile_path)
    output: Path = profile["_output_path"]
    overrides: Path = profile["_overrides_path"]
    icon: Path = profile["_icon_path"]
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.name + ".tmp")

    manifest = manifest_for(profile)
    pack_info = {
        "schema_version": 1,
        "profile_id": profile["profile_id"],
        "purpose": profile["purpose"],
        "production_ready": profile["production_ready"],
        "notice": profile["notice"],
        "icon": "icon.png",
        "minecraft": manifest["minecraft"],
    }

    if temporary.exists():
        temporary.unlink()
    try:
        with zipfile.ZipFile(temporary, "w", allowZip64=True) as archive:
            _write_bytes(archive, "manifest.json", _json_bytes(manifest))
            icon_data = icon.read_bytes()
            _write_bytes(archive, "icon.png", icon_data)
            archive.writestr(_zip_info("overrides", directory=True), b"")
            _write_bytes(archive, "overrides/icon.png", icon_data)
            _write_bytes(
                archive,
                "overrides/cobbleventure-pack-info.json",
                _json_bytes(pack_info),
            )
            for source, target, pack_format in profile["_local_resourcepacks"]:
                _write_bytes(
                    archive,
                    f"overrides/config/paxi/resourcepacks/{target}",
                    _normalized_resource_pack(source, pack_format),
                )
            for source in sorted(overrides.rglob("*")):
                if source.is_symlink():
                    raise PackError(f"overrides에 심볼릭 링크를 사용할 수 없습니다: {source}")
                if source.is_file():
                    relative = source.relative_to(overrides)
                    if relative.as_posix().casefold() == "icon.png":
                        raise PackError(
                            "overrides 최상위 icon.png는 프로필 icon과 충돌합니다."
                        )
                    _write_bytes(archive, _archive_name(relative), source.read_bytes())
        validate_pack(output=temporary, profile=profile)
        os.replace(temporary, output)
    except Exception:
        if temporary.exists():
            temporary.unlink()
        raise

    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    checksum_path = output.with_name(output.name + ".sha256")
    checksum_path.write_text(f"{digest}  {output.name}\n", encoding="ascii")
    return output


def server_output_path(profile: dict[str, Any]) -> Path:
    curseforge_output: Path = profile["_output_path"]
    stem = curseforge_output.stem
    if stem.endswith("-curseforge"):
        stem = stem.removesuffix("-curseforge")
    return curseforge_output.with_name(f"{stem}-neoforge-server.zip")


def build_server_pack(root: Path, profile_path: Path) -> Path:
    root = root.resolve()
    profile = load_profile(root, profile_path)
    _, external_mods = _load_server_dependencies(root, profile)
    overrides: Path = profile["_overrides_path"]
    vendored_mods = sorted(
        source.name
        for source in overrides.joinpath("mods").glob("*.jar")
        if source.is_file() and not source.is_symlink()
    )
    manifest = server_manifest_for(profile, external_mods, vendored_mods)
    generated = _server_generated_files(profile)
    generated["server-manifest.json"] = _json_bytes(manifest)
    output = server_output_path(profile)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.name + ".tmp")

    if temporary.exists():
        temporary.unlink()
    try:
        with zipfile.ZipFile(temporary, "w", allowZip64=True) as archive:
            for name, data in generated.items():
                _write_bytes(archive, name, data)
            for source in sorted(overrides.rglob("*")):
                if source.is_symlink():
                    raise PackError(f"overrides에 심볼릭 링크를 사용할 수 없습니다: {source}")
                if not source.is_file():
                    continue
                relative = source.relative_to(overrides)
                if not _is_server_override(relative):
                    continue
                name = _server_archive_name(relative)
                if name in generated:
                    raise PackError(f"서버 자동 생성 파일과 overrides가 충돌합니다: {name}")
                _write_bytes(archive, name, source.read_bytes())
        validate_server_pack(temporary, profile=profile)
        os.replace(temporary, output)
    except Exception:
        if temporary.exists():
            temporary.unlink()
        raise

    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_name(output.name + ".sha256").write_text(
        f"{digest}  {output.name}\n", encoding="ascii"
    )
    return output


def validate_server_pack(
    output: Path,
    profile: dict[str, Any] | None = None,
    root: Path | None = None,
    profile_path: Path | None = None,
) -> dict[str, Any]:
    if profile is None:
        if root is None or profile_path is None:
            raise PackError("서버 ZIP 검증에는 profile 또는 root와 profile_path가 필요합니다.")
        profile = load_profile(root, profile_path)
    profile_root = profile["_profile_path"].parents[2]
    _, external_mods = _load_server_dependencies(profile_root, profile)
    expected_vendored = sorted(
        source.name
        for source in profile["_overrides_path"].joinpath("mods").glob("*.jar")
        if source.is_file() and not source.is_symlink()
    )
    expected_manifest = server_manifest_for(profile, external_mods, expected_vendored)
    if not output.is_file():
        raise PackError(f"서버 ZIP 파일이 없습니다: {output}")
    try:
        with zipfile.ZipFile(output, "r") as archive:
            bad_entry = archive.testzip()
            if bad_entry is not None:
                raise PackError(f"서버 ZIP CRC 검증 실패: {bad_entry}")
            names = archive.namelist()
            if len(names) != len(set(names)):
                raise PackError("서버 ZIP에 중복 엔트리가 있습니다.")
            for name in names:
                path = PurePosixPath(name)
                if path.is_absolute() or ".." in path.parts or "\\" in name:
                    raise PackError(f"안전하지 않은 서버 ZIP 엔트리입니다: {name}")
            missing = SERVER_REQUIRED_FILES.difference(names)
            if missing:
                raise PackError(f"서버 ZIP 필수 파일이 없습니다: {', '.join(sorted(missing))}")
            forbidden = [name for name in names if not _is_server_override(Path(name)) and name not in SERVER_REQUIRED_FILES]
            if forbidden:
                raise PackError(f"클라이언트 전용 파일이 서버 ZIP에 포함됐습니다: {forbidden[0]}")
            manifest = json.loads(archive.read("server-manifest.json").decode("utf-8-sig"))
            vendored_names = sorted(
                PurePosixPath(name).name
                for name in names
                if name.startswith("mods/") and name.endswith(".jar")
            )
    except (OSError, zipfile.BadZipFile, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise PackError(f"서버 ZIP을 검증할 수 없습니다: {output}: {error}") from error
    if manifest != expected_manifest:
        raise PackError("server-manifest.json이 의존성 Lock과 일치하지 않습니다.")
    if vendored_names != expected_vendored:
        raise PackError("서버 ZIP의 직접 포함 JAR 목록이 개발 팩과 일치하지 않습니다.")
    if any(mod.get("side") == "client" for mod in manifest["external_mods"]):
        raise PackError("서버 manifest에 클라이언트 전용 모드가 포함됐습니다.")
    return manifest


def validate_pack(
    output: Path,
    profile: dict[str, Any] | None = None,
    root: Path | None = None,
    profile_path: Path | None = None,
) -> dict[str, Any]:
    if profile is None:
        if root is None or profile_path is None:
            raise PackError("검증에는 profile 또는 root와 profile_path가 필요합니다.")
        profile = load_profile(root, profile_path)
    if not output.is_file():
        raise PackError(f"ZIP 파일이 없습니다: {output}")

    try:
        with zipfile.ZipFile(output, "r") as archive:
            bad_entry = archive.testzip()
            if bad_entry is not None:
                raise PackError(f"ZIP CRC 검증 실패: {bad_entry}")
            names = archive.namelist()
            if len(names) != len(set(names)):
                raise PackError("ZIP에 중복 엔트리가 있습니다.")
            for name in names:
                path = PurePosixPath(name)
                if path.is_absolute() or ".." in path.parts or "\\" in name:
                    raise PackError(f"안전하지 않은 ZIP 엔트리입니다: {name}")
            if "manifest.json" not in names:
                raise PackError("ZIP 최상위에 manifest.json이 없습니다.")
            if "icon.png" not in names:
                raise PackError("ZIP 최상위에 icon.png가 없습니다.")
            if "overrides/" not in names:
                raise PackError("ZIP 최상위에 overrides/ 디렉터리가 없습니다.")
            if "overrides/icon.png" not in names:
                raise PackError("overrides/에 icon.png가 없습니다.")
            if not any(name.startswith("overrides/") and name != "overrides/" for name in names):
                raise PackError("overrides/에 테스트 파일이 없습니다.")
            manifest = json.loads(archive.read("manifest.json").decode("utf-8"))
            root_icon = archive.read("icon.png")
            override_icon = archive.read("overrides/icon.png")
    except (OSError, zipfile.BadZipFile, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise PackError(f"ZIP을 검증할 수 없습니다: {output}: {error}") from error

    expected = manifest_for(profile)
    if manifest != expected:
        raise PackError("ZIP manifest.json이 선택한 프로필과 일치하지 않습니다.")
    if manifest.get("manifestType") != "minecraftModpack":
        raise PackError("manifestType은 minecraftModpack이어야 합니다.")
    if manifest.get("manifestVersion") != 1:
        raise PackError("manifestVersion은 1이어야 합니다.")
    if manifest.get("overrides") != "overrides":
        raise PackError("manifest overrides는 overrides여야 합니다.")
    expected_icon = profile["_icon_path"].read_bytes()
    _png_dimensions(root_icon, "ZIP 팩 아이콘")
    if root_icon != expected_icon or override_icon != expected_icon:
        raise PackError("ZIP 아이콘이 선택한 프로필의 아이콘과 일치하지 않습니다.")
    return manifest


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Cobbleventure CurseForge·NeoForge 서버 팩 빌더")
    subcommands = parser.add_subparsers(dest="command", required=True)
    for command in ("build", "validate", "build-server", "validate-server"):
        child = subcommands.add_parser(command)
        child.add_argument("--root", type=Path, default=Path.cwd())
        child.add_argument("--profile", type=Path, required=True)
    return parser


def main() -> int:
    arguments = _parser().parse_args()
    try:
        root = arguments.root.resolve()
        profile = load_profile(root, arguments.profile)
        if arguments.command == "build":
            output = build_pack(root, arguments.profile)
            manifest = validate_pack(output, profile=profile)
            digest = hashlib.sha256(output.read_bytes()).hexdigest()
            with zipfile.ZipFile(output, "r") as archive:
                vendored_mod_count = sum(
                    name.startswith("overrides/mods/") and name.endswith(".jar")
                    for name in archive.namelist()
                )
            print(f"CurseForge ZIP 생성 완료: {output}")
            print(
                "프로필: "
                f"Minecraft {manifest['minecraft']['version']}, "
                f"{manifest['minecraft']['modLoaders'][0]['id']}, "
                f"CurseForge 외부 모드 {len(manifest['files'])}개, "
                f"직접 포함 JAR {vendored_mod_count}개"
            )
            print(f"SHA-256: {digest}")
        elif arguments.command == "build-server":
            output = build_server_pack(root, arguments.profile)
            manifest = validate_server_pack(output, profile=profile)
            digest = hashlib.sha256(output.read_bytes()).hexdigest()
            print(f"NeoForge 서버 준비 ZIP 생성 완료: {output}")
            print(
                "서버 구성: "
                f"Minecraft {manifest['minecraft']['version']}, "
                f"NeoForge {manifest['minecraft']['loader']['version']}, "
                f"서버 외부 모드 {len(manifest['external_mods'])}개, "
                f"직접 포함 JAR {len(manifest['vendored_mods'])}개"
            )
            print(f"SHA-256: {digest}")
        elif arguments.command == "validate-server":
            output = server_output_path(profile)
            validate_server_pack(output, profile=profile)
            print(f"NeoForge 서버 준비 ZIP 검증 성공: {output}")
        else:
            output: Path = profile["_output_path"]
            validate_pack(output, profile=profile)
            print(f"CurseForge ZIP 검증 성공: {output}")
        return 0
    except PackError as error:
        print(f"[오류] {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
