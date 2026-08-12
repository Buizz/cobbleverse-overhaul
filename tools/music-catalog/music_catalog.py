from __future__ import annotations

import argparse
import json
import shutil
import tempfile
import zipfile
from pathlib import Path
from typing import Any


DEFAULT_CATALOG = Path("content/catalogs/music-tracks.json")
DEFAULT_OUTPUT = Path(
    "pack/overrides/development-placeholder/config/paxi/resourcepacks/"
    "Cobbleventure-Music.zip"
)
RESOURCE_PACK_FORMAT = 34


class MusicCatalogError(RuntimeError):
    pass


def load_catalog(path: Path) -> dict[str, Any]:
    try:
        catalog = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise MusicCatalogError(f"음악 카탈로그를 읽을 수 없습니다: {path}") from error

    if catalog.get("schema_version") != 1:
        raise MusicCatalogError("음악 카탈로그는 schema_version 1이어야 합니다.")
    if catalog.get("datapack_required") is not False:
        raise MusicCatalogError("음악 카탈로그는 데이터팩을 필수로 요구할 수 없습니다.")
    source = catalog.get("source")
    if not isinstance(source, dict) or source.get("audio_tracked_by_git") is not False:
        raise MusicCatalogError("음원은 Git 추적 대상에서 제외해야 합니다.")
    local_directory = source.get("local_directory")
    if not isinstance(local_directory, str) or not local_directory.startswith(
        "local-assets/music/"
    ):
        raise MusicCatalogError("로컬 음원은 local-assets/music 아래에 있어야 합니다.")
    notification = catalog.get("music_notification")
    if not isinstance(notification, dict) or notification.get("enabled") is not True:
        raise MusicCatalogError("Music Notification 메타데이터가 필요합니다.")

    tracks = catalog.get("tracks")
    if not isinstance(tracks, list) or not tracks:
        raise MusicCatalogError("선택된 음악이 한 곡 이상 필요합니다.")

    seen: dict[str, set[str]] = {
        "id": set(),
        "sound_event": set(),
        "resource": set(),
        "source_file": set(),
    }
    for index, track in enumerate(tracks):
        if not isinstance(track, dict):
            raise MusicCatalogError(f"tracks[{index}]가 객체가 아닙니다.")
        for field in seen:
            value = track.get(field)
            if not isinstance(value, str) or not value:
                raise MusicCatalogError(f"tracks[{index}].{field}가 비어 있습니다.")
            if value in seen[field]:
                raise MusicCatalogError(f"중복된 {field}: {value}")
            seen[field].add(value)
        if not track["source_file"].lower().endswith(".ogg"):
            raise MusicCatalogError(
                f"선택된 음악은 OGG여야 합니다: {track['source_file']}"
            )
    return catalog


def build_sounds_manifest(catalog: dict[str, Any]) -> dict[str, Any]:
    namespace = catalog["namespace"]
    return {
        track["sound_event"]: {
            "sounds": [
                {
                    "name": f"{namespace}:{track['resource']}",
                    "stream": True,
                }
            ]
        }
        for track in catalog["tracks"]
    }


def build_music_notification_manifest(catalog: dict[str, Any]) -> dict[str, Any]:
    namespace = catalog["namespace"]
    metadata = catalog["music_notification"]
    return {
        f"{namespace}:{track['sound_event']}": {
            "album": metadata["album"],
            "author": metadata["author"],
            "title": track["usage"],
        }
        for track in catalog["tracks"]
    }


def check_external_audio(catalog: dict[str, Any], source_dir: Path) -> list[str]:
    return [
        track["source_file"]
        for track in catalog["tracks"]
        if not (source_dir / track["source_file"]).is_file()
    ]


def write_manifest(catalog: dict[str, Any], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(build_sounds_manifest(catalog), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def _write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def stage_resource_pack(
    catalog: dict[str, Any], source_dir: Path, staging_dir: Path
) -> None:
    missing = check_external_audio(catalog, source_dir)
    if missing:
        raise MusicCatalogError(
            "로컬 음원 폴더에 선택 파일이 없습니다: " + ", ".join(missing)
        )

    namespace = catalog["namespace"]
    _write_json(
        staging_dir / "pack.mcmeta",
        {
            "pack": {
                "pack_format": RESOURCE_PACK_FORMAT,
                "description": "Cobbleventure selected music for Minecraft 1.21.1",
            }
        },
    )
    _write_json(
        staging_dir / "assets" / namespace / "sounds.json",
        build_sounds_manifest(catalog),
    )
    _write_json(
        staging_dir / "assets" / "musicnotification" / "musics.json",
        build_music_notification_manifest(catalog),
    )

    sounds_root = staging_dir / "assets" / namespace / "sounds"
    for track in catalog["tracks"]:
        target = sounds_root / f"{track['resource']}.ogg"
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source_dir / track["source_file"], target)


def build_resource_pack(
    catalog: dict[str, Any], source_dir: Path, output: Path
) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="cobbleventure-music-") as directory:
        temporary_root = Path(directory)
        staging_dir = temporary_root / "resourcepack"
        stage_resource_pack(catalog, source_dir, staging_dir)
        temporary_zip = temporary_root / "Cobbleventure-Music.zip"
        with zipfile.ZipFile(
            temporary_zip, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6
        ) as archive:
            for path in sorted(staging_dir.rglob("*")):
                if path.is_file():
                    archive.write(path, path.relative_to(staging_dir).as_posix())
        shutil.copy2(temporary_zip, output)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="로컬 음원 중 선택 목록만 모아 리소스팩을 생성합니다."
    )
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument(
        "--check-source",
        type=Path,
        help="저장소 밖의 음원 폴더에 선택 파일이 있는지만 확인합니다.",
    )
    parser.add_argument(
        "--manifest-only",
        action="store_true",
        help="음원을 복사하지 않고 --output 위치에 sounds.json만 생성합니다.",
    )
    args = parser.parse_args()

    try:
        root = args.root.resolve()
        catalog_path = args.catalog if args.catalog.is_absolute() else root / args.catalog
        output = args.output if args.output.is_absolute() else root / args.output
        catalog = load_catalog(catalog_path)
        if args.check_source is not None:
            missing = check_external_audio(catalog, args.check_source)
            if missing:
                raise MusicCatalogError(
                    "외부 음원 폴더에 선택 파일이 없습니다: " + ", ".join(missing)
                )
        if args.manifest_only:
            write_manifest(catalog, output)
        elif args.check_source is None:
            source_dir = root / catalog["source"]["local_directory"]
            build_resource_pack(catalog, source_dir, output)
    except MusicCatalogError as error:
        parser.error(str(error))

    print(
        f"선택 음악 {len(catalog['tracks'])}곡 / "
        f"검토 대기 {len(catalog['review_candidates'])}곡 / "
        "Music Notification 메타데이터 포함 / 데이터팩 필수 아님"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
