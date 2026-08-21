#!/usr/bin/env python3
"""Build a Paxi datapack from the editable Cobblemon spawn workbook."""

from __future__ import annotations

import argparse
import json
import re
import zipfile
from collections import Counter, defaultdict
from pathlib import Path, PurePosixPath
from typing import Iterable
from xml.etree import ElementTree


WORKBOOK = Path("코블몬_바이옴_스폰_정리.xlsx")
SHEET_NAME = "스폰_편집"
OUTPUT = Path(
    "pack/overrides/development-placeholder/config/paxi/datapacks/"
    "zzz-cobbleventure-spawns.zip"
)
REPORT = Path("outputs/cobbleventure-custom-spawns-report.json")
SPAWN_RESOURCE = re.compile(
    r"^data/cobblemon/spawn_pool_world/(?P<name>[a-z0-9_.-]+\.json)$"
)
XML_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
REQUIRED_COLUMNS = {
    "관리키",
    "적용여부_편집",
    "허용세대월드_편집",
    "지역배정_편집",
    "바이옴_편집",
    "희귀도",
    "원본가중치",
    "가중치배율_편집",
    "원본레벨",
    "레벨_편집",
    "위치유형",
    "원본파일",
    "스폰ID",
    "포켓몬선택자",
    "스폰타입",
    "프리셋",
    "조건JSON",
    "제외조건JSON",
    "가중치보정JSON",
    "기타조건",
}


class SpawnBuildError(RuntimeError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="엑셀의 바이옴·세대 설정으로 Cobblemon 스폰 데이터팩을 생성합니다."
    )
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--workbook", type=Path, default=WORKBOOK)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--report", type=Path, default=REPORT)
    return parser.parse_args()


def _inside(root: Path, path: Path, label: str) -> Path:
    candidate = path if path.is_absolute() else root / path
    resolved = candidate.resolve()
    try:
        resolved.relative_to(root)
    except ValueError as error:
        raise SpawnBuildError(f"{label} 경로가 저장소 밖을 가리킵니다: {resolved}") from error
    return resolved


def _column_index(reference: str) -> int:
    letters = "".join(character for character in reference if character.isalpha())
    result = 0
    for character in letters.upper():
        result = result * 26 + ord(character) - ord("A") + 1
    return result - 1


def _shared_strings(archive: zipfile.ZipFile) -> list[str]:
    try:
        root = ElementTree.fromstring(archive.read("xl/sharedStrings.xml"))
    except KeyError:
        return []
    return [
        "".join(node.text or "" for node in item.iter(f"{{{XML_NS}}}t"))
        for item in root.findall(f"{{{XML_NS}}}si")
    ]


def _sheet_path(archive: zipfile.ZipFile, sheet_name: str) -> str:
    workbook = ElementTree.fromstring(archive.read("xl/workbook.xml"))
    relationship_id = None
    sheets = workbook.find(f"{{{XML_NS}}}sheets")
    for sheet in sheets if sheets is not None else []:
        if sheet.get("name") == sheet_name:
            relationship_id = sheet.get(f"{{{REL_NS}}}id")
            break
    if relationship_id is None:
        raise SpawnBuildError(f"워크북 시트를 찾지 못했습니다: {sheet_name}")

    relationships = ElementTree.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
    for relationship in relationships.findall(f"{{{PKG_REL_NS}}}Relationship"):
        if relationship.get("Id") == relationship_id:
            target = relationship.get("Target", "")
            normalized = target.lstrip("/")
            if normalized.startswith("xl/"):
                return normalized
            return str(PurePosixPath("xl") / normalized)
    raise SpawnBuildError(f"워크북 시트 관계를 찾지 못했습니다: {sheet_name}")


def _cell_value(cell: ElementTree.Element, shared: list[str]) -> object:
    cell_type = cell.get("t")
    if cell_type == "inlineStr":
        return "".join(node.text or "" for node in cell.iter(f"{{{XML_NS}}}t"))
    value_node = cell.find(f"{{{XML_NS}}}v")
    if value_node is None or value_node.text is None:
        return None
    value = value_node.text
    if cell_type == "s":
        return shared[int(value)]
    if cell_type in {"str", "e"}:
        return value
    if cell_type == "b":
        return value == "1"
    try:
        number = float(value)
        return int(number) if number.is_integer() else number
    except ValueError:
        return value


def read_sheet_rows(path: Path, sheet_name: str = SHEET_NAME) -> list[dict[str, object]]:
    try:
        with zipfile.ZipFile(path) as archive:
            shared = _shared_strings(archive)
            sheet = ElementTree.fromstring(archive.read(_sheet_path(archive, sheet_name)))
    except (OSError, zipfile.BadZipFile, KeyError, ElementTree.ParseError) as error:
        raise SpawnBuildError(f"엑셀 파일을 읽을 수 없습니다: {path}") from error

    rows: list[list[object]] = []
    sheet_data = sheet.find(f"{{{XML_NS}}}sheetData")
    if sheet_data is None:
        raise SpawnBuildError(f"시트에 데이터가 없습니다: {sheet_name}")
    for row in sheet_data.findall(f"{{{XML_NS}}}row"):
        values: list[object] = []
        for cell in row.findall(f"{{{XML_NS}}}c"):
            index = _column_index(cell.get("r", "A1"))
            if len(values) <= index:
                values.extend([None] * (index + 1 - len(values)))
            values[index] = _cell_value(cell, shared)
        rows.append(values)

    header_index = next(
        (index for index, row in enumerate(rows) if row and row[0] == "관리키"),
        None,
    )
    if header_index is None:
        raise SpawnBuildError("스폰 편집표의 헤더 행을 찾지 못했습니다.")
    headers = [str(value) if value is not None else "" for value in rows[header_index]]
    missing = sorted(REQUIRED_COLUMNS - set(headers))
    if missing:
        raise SpawnBuildError(f"스폰 편집표에 필수 열이 없습니다: {', '.join(missing)}")

    result: list[dict[str, object]] = []
    for values in rows[header_index + 1 :]:
        if not values or not values[0]:
            continue
        padded = values + [None] * (len(headers) - len(values))
        result.append(dict(zip(headers, padded, strict=False)))
    return result


def _split(value: object) -> list[str]:
    if value is None:
        return []
    return [part.strip() for part in str(value).split(";") if part.strip()]


def _json_object(value: object, label: str, key: str) -> dict:
    if value is None or value == "":
        return {}
    try:
        parsed = json.loads(str(value))
    except json.JSONDecodeError as error:
        raise SpawnBuildError(f"{key}의 {label} 값이 올바른 JSON이 아닙니다.") from error
    if not isinstance(parsed, dict):
        raise SpawnBuildError(f"{key}의 {label} 값은 JSON 객체여야 합니다.")
    return parsed


def _scaled_weight(original: object, scale: object, key: str) -> int | float:
    try:
        weight = float(original)
        multiplier = 1.0 if scale in {None, ""} else float(scale)
    except (TypeError, ValueError) as error:
        raise SpawnBuildError(f"{key}의 가중치 또는 배율이 숫자가 아닙니다.") from error
    if weight < 0 or multiplier <= 0:
        raise SpawnBuildError(f"{key}의 가중치는 0 이상, 배율은 0보다 커야 합니다.")
    if multiplier == 1.0 and isinstance(original, (int, float)):
        return original
    result = round(weight * multiplier, 6)
    return int(result) if result.is_integer() else result


def _source_filename(row: dict[str, object]) -> str:
    key = str(row["관리키"])
    source_resource = str(row["원본파일"])
    match = SPAWN_RESOURCE.fullmatch(source_resource)
    if not match:
        raise SpawnBuildError(f"{key}의 원본파일 경로가 올바르지 않습니다: {source_resource}")
    return match.group("name")


def _spawn_from_row(row: dict[str, object]) -> tuple[str, dict]:
    key = str(row["관리키"])
    filename = _source_filename(row)

    regions = _split(row["지역배정_편집"])
    if regions:
        raise SpawnBuildError(
            f"{key}에 지역배정이 있지만 Cobblemon 조건으로 변환할 바이옴 편집값이 없습니다. "
            "지역별 바이옴을 확정한 뒤 바이옴_편집을 사용하세요."
        )

    condition = _json_object(row["조건JSON"], "조건JSON", key)
    dimensions = _split(row["허용세대월드_편집"])
    if not dimensions:
        raise SpawnBuildError(f"{key}에 허용세대월드_편집 값이 없습니다.")
    condition["dimensions"] = dimensions
    edited_biomes = _split(row["바이옴_편집"])
    if edited_biomes:
        condition["biomes"] = edited_biomes

    spawn_type = str(row["스폰타입"])
    level = row["레벨_편집"] or row["원본레벨"]
    spawn: dict[str, object] = {
        "id": str(row["스폰ID"]),
        "presets": _split(row["프리셋"]),
        "type": spawn_type,
        "bucket": str(row["희귀도"]),
        "weight": _scaled_weight(row["원본가중치"], row["가중치배율_편집"], key),
        "condition": condition,
    }
    if spawn_type == "pokemon-herd":
        spawn["levelRange"] = str(level)
    else:
        selector = str(row["포켓몬선택자"] or "").strip()
        if not selector:
            raise SpawnBuildError(f"{key}에 포켓몬선택자가 없습니다.")
        spawn["pokemon"] = selector
        spawn["level"] = str(level)
    if row["위치유형"] not in {None, ""}:
        spawn["spawnablePositionType"] = str(row["위치유형"])

    anticondition = _json_object(row["제외조건JSON"], "제외조건JSON", key)
    if anticondition:
        spawn["anticondition"] = anticondition
    spawn.update(_json_object(row["가중치보정JSON"], "가중치보정JSON", key))
    extras = _json_object(row["기타조건"], "기타조건", key)
    spawn_extras = extras.get("spawn", {})
    if not isinstance(spawn_extras, dict):
        raise SpawnBuildError(f"{key}의 기타조건.spawn은 JSON 객체여야 합니다.")
    spawn.update(spawn_extras)
    return filename, spawn


def build_documents(rows: Iterable[dict[str, object]]) -> tuple[dict[str, dict], dict]:
    grouped: dict[str, list[dict]] = defaultdict(list)
    source_files: set[str] = set()
    excluded = 0
    match_reasons = Counter()
    dimensions = Counter()
    biome_overrides = 0
    weight_overrides = 0
    level_overrides = 0

    for row in rows:
        source_files.add(_source_filename(row))
        status = row.get("적용여부_편집")
        if status == "제외":
            excluded += 1
            continue
        if status != "사용":
            raise SpawnBuildError(f"{row.get('관리키')}의 적용여부가 사용/제외가 아닙니다: {status}")
        filename, spawn = _spawn_from_row(row)
        grouped[filename].append(spawn)
        dimensions.update(spawn["condition"]["dimensions"])
        if _split(row.get("바이옴_편집")):
            biome_overrides += 1
        if float(row.get("가중치배율_편집") or 1) != 1.0:
            weight_overrides += 1
        if row.get("레벨_편집") not in {None, ""}:
            level_overrides += 1
        match_reasons[str(row.get("원본상태") or "미분류")] += 1

    documents: dict[str, dict] = {}
    renamed_duplicate_ids = 0
    for filename in sorted(source_files):
        spawns = grouped.get(filename, [])
        seen_ids: set[str] = set()
        duplicate_counts: Counter[str] = Counter()
        for spawn in spawns:
            original_id = str(spawn["id"])
            duplicate_counts[original_id] += 1
            candidate = original_id
            if candidate in seen_ids:
                suffix = duplicate_counts[original_id]
                candidate = f"{original_id}-cobbleventure-{suffix}"
                while candidate in seen_ids:
                    suffix += 1
                    candidate = f"{original_id}-cobbleventure-{suffix}"
                spawn["id"] = candidate
                renamed_duplicate_ids += 1
            seen_ids.add(candidate)
        documents[filename] = {
            "enabled": bool(spawns),
            "neededInstalledMods": [],
            "neededUninstalledMods": [],
            "spawns": spawns,
        }

    report = {
        "schema_version": 1,
        "summary": {
            "resource_files": len(documents),
            "active_spawns": sum(len(document["spawns"]) for document in documents.values()),
            "excluded_spawns": excluded,
            "biome_overrides": biome_overrides,
            "weight_overrides": weight_overrides,
            "level_overrides": level_overrides,
            "renamed_duplicate_ids": renamed_duplicate_ids,
        },
        "dimensions": dict(sorted(dimensions.items())),
        "source_status": dict(sorted(match_reasons.items())),
    }
    return documents, report


def _zip_info(name: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    return info


def write_datapack(path: Path, documents: dict[str, dict], report: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as archive:
        pack_meta = {
            "pack": {
                "pack_format": 48,
                "supported_formats": {"min_inclusive": 48, "max_inclusive": 48},
                "description": "Cobbleventure biome and generation spawn overrides",
            }
        }
        archive.writestr(
            _zip_info("pack.mcmeta"),
            json.dumps(pack_meta, ensure_ascii=False, indent=2).encode("utf-8") + b"\n",
        )
        archive.writestr(
            _zip_info("cobbleventure-spawn-report.json"),
            json.dumps(report, ensure_ascii=False, indent=2).encode("utf-8") + b"\n",
        )
        for filename, document in documents.items():
            archive.writestr(
                _zip_info(f"data/cobblemon/spawn_pool_world/{filename}"),
                json.dumps(document, ensure_ascii=False, indent=2).encode("utf-8") + b"\n",
            )


def write_report(path: Path, report: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    args = parse_args()
    root = args.root.resolve()
    workbook = _inside(root, args.workbook, "워크북")
    output = _inside(root, args.output, "데이터팩 출력")
    report_path = _inside(root, args.report, "검증 보고서 출력")
    rows = read_sheet_rows(workbook)
    documents, report = build_documents(rows)
    report["source_workbook"] = workbook.relative_to(root).as_posix()
    report["output"] = output.relative_to(root).as_posix()
    report["development_notice"] = (
        "내부 개발용 생성물입니다. 원본 데이터의 배포 조건을 확인한 뒤 공개 배포하세요."
    )
    write_datapack(output, documents, report)
    write_report(report_path, report)
    print(json.dumps(report["summary"], ensure_ascii=False))
    print(f"Paxi 스폰 데이터팩 생성 완료: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
