from __future__ import annotations

import argparse
import hashlib
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
    parser = argparse.ArgumentParser(description="Cobbleventure CurseForge 팩 빌더")
    subcommands = parser.add_subparsers(dest="command", required=True)
    for command in ("build", "validate"):
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
