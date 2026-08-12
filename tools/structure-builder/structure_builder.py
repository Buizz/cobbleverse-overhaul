from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
from pathlib import Path


PROJECT = Path("projects/cobbleventure-structure-builder")
GENERATED_RESOURCES = PROJECT / "src/generated/resources"
SOURCE_STRUCTURES = Path("content/structures")
CATALOG_RESOURCE = Path(
    "data/cobbleventure_builder/structure_builder/catalog.json"
)
CELL_SIZE = 80
COLUMNS = 8


class StructureBuilderError(RuntimeError):
    pass


def _validate_structure_metadata(document: object, path: Path) -> dict[str, object]:
    if not isinstance(document, dict) or document.get("schema_version") != 1:
        raise StructureBuilderError(
            f"지원하지 않는 구조물 메타데이터입니다: {path}"
        )
    anchors = document.get("anchors", [])
    if not isinstance(anchors, list):
        raise StructureBuilderError(f"anchors는 배열이어야 합니다: {path}")
    for index, anchor in enumerate(anchors):
        if not isinstance(anchor, dict):
            raise StructureBuilderError(f"올바르지 않은 앵커입니다: {path} #{index}")
        anchor_type = anchor.get("type")
        if anchor_type not in {
            "interior_entry", "interior_exit", "npc_position", "easy_npc_spawn",
            "interior_spawn", "exterior_spawn", "interaction_point", "patrol_point",
        }:
            raise StructureBuilderError(f"알 수 없는 출입구 앵커입니다: {path} #{index}")
        is_door = anchor_type in {"interior_entry", "interior_exit"}
        is_npc = anchor_type in {"npc_position", "easy_npc_spawn"}
        position_fields = ("position", "safe_spawn") if is_door else ("position",)
        for field in position_fields:
            value = anchor.get(field)
            if (not isinstance(value, list) or len(value) != 3
                    or any(not isinstance(component, int) for component in value)):
                raise StructureBuilderError(
                    f"{field}는 정수 좌표 3개여야 합니다: {path} #{index}"
                )
        direction_fields = ("door_facing", "safe_side") if is_door else (() if is_npc else ("facing",))
        for field in direction_fields:
            if anchor.get(field) not in {"north", "east", "south", "west"}:
                raise StructureBuilderError(
                    f"{field} 방향이 올바르지 않습니다: {path} #{index}"
                )
        if is_npc:
            label = anchor.get("label", anchor.get("id"))
            if not isinstance(label, str) or not re.fullmatch(r"[a-z0-9][a-z0-9_]*", label):
                raise StructureBuilderError(
                    f"NPC 위치 라벨이 올바르지 않습니다: {path} #{index}"
                )
    interior = document.get("interior")
    if interior is not None:
        if not isinstance(interior, dict):
            raise StructureBuilderError(f"interior는 객체여야 합니다: {path}")
        if not isinstance(interior.get("id"), str):
            raise StructureBuilderError(f"내부 공간 ID가 필요합니다: {path}")
        for field, minimum, maximum in (
            ("width", 5, 80), ("depth", 5, 80),
            ("floor_height", 3, 12), ("floors", 1, 8),
        ):
            value = interior.get(field)
            if not isinstance(value, int) or not minimum <= value <= maximum:
                raise StructureBuilderError(f"{field} 값이 올바르지 않습니다: {path}")
    return document


def _metadata_reader(root: Path):
    module_root = root / "tools/content-manager"
    sys.path.insert(0, str(module_root))
    try:
        from content_manager import read_minecraft_structure_metadata
    finally:
        sys.path.pop(0)
    return read_minecraft_structure_metadata


def catalog_entries(root: Path) -> list[dict[str, object]]:
    source_root = root / SOURCE_STRUCTURES
    if not source_root.is_dir():
        raise StructureBuilderError(f"구조물 원본 디렉터리가 없습니다: {source_root}")
    read_metadata = _metadata_reader(root)
    entries: list[dict[str, object]] = []
    for source in sorted(source_root.rglob("*.nbt")):
        relative = source.relative_to(source_root).with_suffix("")
        resource_path = relative.as_posix()
        metadata = read_metadata(source.read_bytes())
        size = [metadata["width"], metadata["height"], metadata["depth"]]
        sidecar = source.with_suffix(".structure.json")
        authored_metadata: dict[str, object] = {}
        if sidecar.is_file():
            authored_metadata = _validate_structure_metadata(
                json.loads(sidecar.read_text(encoding="utf-8")), sidecar
            )
        if size[0] > CELL_SIZE - 16 or size[2] > CELL_SIZE - 16:
            raise StructureBuilderError(
                f"80x80 부지에 8블록 여백을 확보할 수 없습니다: {source} ({size})"
            )
        entries.append({
            "source": source.relative_to(root).as_posix(),
            "structure": f"cobbleventure_builder:source/{resource_path}",
            "export": f"cobbleventure_builder:export/{resource_path}",
            "label": relative.name,
            "category": relative.parts[0],
            "size": size,
            "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            "anchors": authored_metadata.get("anchors", []),
            "interior": authored_metadata.get("interior"),
        })
    if not entries:
        raise StructureBuilderError("건축 월드에 넣을 NBT 구조물이 없습니다.")
    return entries


def generate(root: Path) -> Path:
    root = root.resolve()
    output = root / GENERATED_RESOURCES
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)

    entries = catalog_entries(root)
    for entry in entries:
        source = root / str(entry["source"])
        resource = str(entry["structure"]).split(":", 1)[1]
        target = output / "data/cobbleventure_builder/structure" / f"{resource}.nbt"
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)

    canonical = json.dumps(entries, ensure_ascii=False, separators=(",", ":"))
    catalog = {
        "schema_version": 1,
        "catalog_hash": hashlib.sha256(canonical.encode("utf-8")).hexdigest(),
        "columns": COLUMNS,
        "cell_size": CELL_SIZE,
        "entries": entries,
    }
    target = output / CATALOG_RESOURCE
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        json.dumps(catalog, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return target


def _export_root(world: Path) -> Path:
    candidates = (
        world / "generated/cobbleventure_builder/structures/export",
        world / "generated/cobbleventure_builder/structure/export",
        world,
    )
    for candidate in candidates:
        if candidate.is_dir() and any(candidate.rglob("*.nbt")):
            return candidate
    raise StructureBuilderError(
        "내보낸 NBT를 찾을 수 없습니다. 월드에서 "
        "/cobbleventure_builder save all을 먼저 실행하세요."
    )


def import_exports(root: Path, world: Path) -> int:
    root = root.resolve()
    export_root = _export_root(world.resolve())
    read_metadata = _metadata_reader(root)
    pending: list[tuple[Path, Path]] = []
    pending_metadata: list[tuple[Path | None, Path]] = []
    missing: list[str] = []
    for entry in catalog_entries(root):
        relative = Path(str(entry["source"])).relative_to(SOURCE_STRUCTURES)
        exported = export_root / relative
        if not exported.is_file():
            missing.append(relative.as_posix())
            continue
        metadata = read_metadata(exported.read_bytes())
        expected = entry["size"]
        actual = [metadata["width"], metadata["height"], metadata["depth"]]
        if actual != expected:
            raise StructureBuilderError(
                f"내보낸 구조물 크기가 원본 계약과 다릅니다: {relative} "
                f"(예상 {expected}, 실제 {actual})"
            )
        pending.append((exported, root / str(entry["source"])))
        relative_without_suffix = relative.with_suffix("")
        exported_metadata = (
            world.resolve()
            / "generated/cobbleventure_builder/structure_metadata/export"
            / Path(str(relative_without_suffix) + ".structure.json")
        )
        if exported_metadata.is_file():
            _validate_structure_metadata(
                json.loads(exported_metadata.read_text(encoding="utf-8")),
                exported_metadata,
            )
            pending_metadata.append((
                exported_metadata,
                (root / str(entry["source"])).with_suffix(".structure.json"),
            ))
    if missing:
        raise StructureBuilderError(
            "내보내기가 누락된 구조물: " + ", ".join(missing)
        )

    known_targets = {target.resolve() for _, target in pending}
    interior_exports = export_root / "interiors"
    if interior_exports.is_dir():
        for exported in sorted(interior_exports.rglob("*.nbt")):
            relative = exported.relative_to(export_root)
            target = root / SOURCE_STRUCTURES / relative
            if target.resolve() in known_targets:
                continue
            exported_metadata = (
                world.resolve()
                / "generated/cobbleventure_builder/structure_metadata/export"
                / relative.with_suffix(".structure.json")
            )
            if not exported_metadata.is_file():
                raise StructureBuilderError(
                    f"새 내부 NBT의 메타데이터가 없습니다: {relative.as_posix()}"
                )
            document = _validate_structure_metadata(
                json.loads(exported_metadata.read_text(encoding="utf-8")),
                exported_metadata,
            )
            workspace = document.get("interior")
            if not isinstance(workspace, dict):
                raise StructureBuilderError(
                    f"새 내부 NBT에 interior 계약이 없습니다: {relative.as_posix()}"
                )
            metadata = read_metadata(exported.read_bytes())
            expected = [
                workspace["width"],
                workspace["floor_height"] * workspace["floors"],
                workspace["depth"],
            ]
            actual = [metadata["width"], metadata["height"], metadata["depth"]]
            if actual != expected:
                raise StructureBuilderError(
                    f"내부 NBT 크기와 층 계약이 다릅니다: {relative} "
                    f"(예상 {expected}, 실제 {actual})"
                )
            pending.append((exported, target))
            pending_metadata.append((exported_metadata, target.with_suffix(".structure.json")))

    changed = 0
    for exported, target in pending:
        payload = exported.read_bytes()
        if target.is_file() and target.read_bytes() == payload:
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(target.name + ".builder-import.tmp")
        temporary.write_bytes(payload)
        os.replace(temporary, target)
        changed += 1
    for exported, target in pending_metadata:
        if exported is None:
            continue
        payload = exported.read_bytes()
        if target.is_file() and target.read_bytes() == payload:
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(target.name + ".builder-import.tmp")
        temporary.write_bytes(payload)
        os.replace(temporary, target)
        changed += 1
    return changed


def main() -> int:
    parser = argparse.ArgumentParser(description="Cobbleventure 독립 건축 월드 도구")
    parser.add_argument("--root", type=Path, default=Path.cwd())
    subcommands = parser.add_subparsers(dest="command", required=True)
    subcommands.add_parser("generate")
    import_parser = subcommands.add_parser("import")
    import_parser.add_argument("world", type=Path)
    arguments = parser.parse_args()
    try:
        if arguments.command == "generate":
            catalog = generate(arguments.root)
            print(f"건축 월드 구조물 카탈로그 생성 완료: {catalog}")
        else:
            changed = import_exports(arguments.root, arguments.world)
            print(f"건축 월드 NBT 가져오기 완료: 변경 {changed}개")
        return 0
    except (OSError, ValueError, StructureBuilderError) as error:
        print(f"[ERROR] {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
