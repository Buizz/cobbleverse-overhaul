from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


DEFAULT_CATALOG = Path("content/catalogs/music-tracks.json")


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
    if not isinstance(source, dict) or source.get("audio_in_repository") is not False:
        raise MusicCatalogError("음원은 저장소 외부에서 관리하도록 설정해야 합니다.")

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


def main() -> int:
    parser = argparse.ArgumentParser(
        description="선택된 음악 목록만 사용해 리소스팩 sounds.json을 생성합니다."
    )
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--output", type=Path)
    parser.add_argument(
        "--check-source",
        type=Path,
        help="저장소 밖의 음원 폴더에 선택 파일이 있는지만 확인합니다.",
    )
    args = parser.parse_args()

    try:
        catalog = load_catalog(args.catalog)
        if args.check_source is not None:
            missing = check_external_audio(catalog, args.check_source)
            if missing:
                raise MusicCatalogError(
                    "외부 음원 폴더에 선택 파일이 없습니다: " + ", ".join(missing)
                )
        if args.output is not None:
            write_manifest(catalog, args.output)
    except MusicCatalogError as error:
        parser.error(str(error))

    print(
        f"선택 음악 {len(catalog['tracks'])}곡 / "
        f"검토 대기 {len(catalog['review_candidates'])}곡 / "
        "데이터팩 필수 아님"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
