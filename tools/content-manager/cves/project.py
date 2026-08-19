"""Deterministic project-level CVES discovery and datapack artifact generation."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path

from .catalog import load_project_catalog
from .compiler import compile_program
from .formatter import format_program
from .migration import (
    compare_battle_event_migration,
    compare_gym_leader_migration,
    compare_item_reward_migration,
    compare_simple_dialogue_migration,
    compare_starter_event_migration,
)
from .parser import parse
from .presets import preset_program


NAMESPACE = re.compile(r"^[a-z0-9_.-]+$")
RESOURCE_PATH = re.compile(r"^[a-z0-9_./-]+$")
BINDING_FIELDS = frozenset({"schema_version", "script_id"})


class CvesProjectError(ValueError):
    """Reports an invalid CVES project layout or binding document."""


@dataclass(frozen=True, slots=True)
class ProjectArtifact:
    relative_path: Path
    document: dict


@dataclass(frozen=True, slots=True)
class ProjectBuild:
    scripts: tuple[ProjectArtifact, ...]
    bindings: tuple[ProjectArtifact, ...]

    @property
    def artifacts(self) -> tuple[ProjectArtifact, ...]:
        return self.scripts + self.bindings


def compile_project(
    project_root: Path, *, item_catalog: Path | None = None
) -> ProjectBuild:
    """Compile all project CVES sources and validate representation bindings."""
    project_root = project_root.resolve()
    content_root = project_root / "content"
    event_root = content_root / "events"
    binding_root = content_root / "event-bindings"
    catalog = load_project_catalog(project_root, item_catalog=item_catalog)

    scripts: list[ProjectArtifact] = []
    known_scripts: set[str] = set()
    programs_by_id = {}
    battle_documents = _documents_by_id(content_root / "battles")
    league_entries = _league_entries(content_root / "catalogs" / "league-progression.json")
    gym_entries = {
        entry["encounter"]["battle_id"].rsplit("/", 1)[-1]: entry
        for entry in league_entries
        if entry.get("role") == "gym_leader"
        and isinstance(entry.get("encounter"), dict)
        and isinstance(entry["encounter"].get("battle_id"), str)
    }
    post_victory_caps = _post_victory_level_caps(league_entries)
    for source in sorted(event_root.rglob("*.cves")) if event_root.is_dir() else []:
        namespace, resource_path = _resource_parts(source, event_root, ".cves")
        script_id = f"{namespace}:event_script/{resource_path}"
        if script_id in known_scripts:
            raise CvesProjectError(f"중복 CVES script ID입니다: {script_id}")
        try:
            source_name = source.relative_to(project_root).as_posix()
            program = parse(source.read_text(encoding="utf-8"), source_name)
            document = compile_program(program, script_id, catalog)
        except OSError as error:
            raise CvesProjectError(f"CVES 원본을 읽을 수 없습니다: {source}: {error}") from error
        known_scripts.add(script_id)
        programs_by_id[script_id] = program
        scripts.append(ProjectArtifact(
            Path(namespace) / "event_script" / f"{resource_path}.json",
            document,
        ))

    bindings: list[ProjectArtifact] = []
    for source in sorted(binding_root.rglob("*.json")) if binding_root.is_dir() else []:
        namespace, resource_path = _resource_parts(source, binding_root, ".json")
        document = _binding_document(source)
        if document["script_id"] not in known_scripts:
            raise CvesProjectError(
                f"{source}: 프로젝트에 없는 CVES script_id입니다: {document['script_id']}"
            )
        legacy_source = content_root / "source" / f"{resource_path}.json"
        if legacy_source.is_file():
            try:
                legacy_document = json.loads(legacy_source.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError) as error:
                raise CvesProjectError(
                    f"{legacy_source}: V4 비교 원본을 읽을 수 없습니다: {error}"
                ) from error
            preset = legacy_document.get("event_design", {}).get("preset", {})
            runtime = legacy_document.get("event_runtime", {})
            if runtime.get("engine") == "cves_v5" and runtime.get("authoring") == "preset":
                generated = format_program(preset_program(legacy_document))
                current = format_program(programs_by_id[document["script_id"]])
                if generated != current:
                    raise CvesProjectError(
                        f"{source}: 행동 프리셋에서 생성한 CVES와 저장된 이벤트가 다릅니다. "
                        "NPC 설정에서 다시 저장하거나 사용자 정의 이벤트로 전환해 주세요."
                    )
            if legacy_document.get("schema_version") == 4 and preset.get("type") == "item":
                try:
                    differences = compare_item_reward_migration(
                        legacy_document, programs_by_id[document["script_id"]]
                    )
                except ValueError as error:
                    raise CvesProjectError(
                        f"{source}: V4 item preset 비교에 실패했습니다: {error}"
                    ) from error
                if differences:
                    raise CvesProjectError(
                        f"{source}: V4/V5 item reward 의미가 다릅니다: {differences[0]}"
                    )
            if legacy_document.get("schema_version") == 4 and preset.get("type") == "simple":
                try:
                    differences = compare_simple_dialogue_migration(
                        legacy_document, programs_by_id[document["script_id"]]
                    )
                except ValueError as error:
                    raise CvesProjectError(
                        f"{source}: V4 simple preset 비교에 실패했습니다: {error}"
                    ) from error
                if differences:
                    raise CvesProjectError(
                        f"{source}: V4/V5 simple dialogue 의미가 다릅니다: {differences[0]}"
                    )
            if _has_legacy_starter_roulette(legacy_document):
                try:
                    differences = compare_starter_event_migration(
                        legacy_document, programs_by_id[document["script_id"]]
                    )
                except ValueError as error:
                    raise CvesProjectError(
                        f"{source}: V4 starter event 비교에 실패했습니다: {error}"
                    ) from error
                if differences:
                    raise CvesProjectError(
                        f"{source}: V4/V5 starter event 의미가 다릅니다: {differences[0]}"
                    )
            battle_id = _legacy_battle_id(legacy_document)
            if battle_id is not None:
                battle_document = battle_documents.get(battle_id)
                if battle_document is None:
                    raise CvesProjectError(
                        f"{source}: V4가 참조하는 battle preset이 없습니다: {battle_id}"
                    )
                try:
                    differences = compare_battle_event_migration(
                        legacy_document,
                        programs_by_id[document["script_id"]],
                        battle_document,
                    )
                except ValueError as error:
                    raise CvesProjectError(
                        f"{source}: V4 battle event 비교에 실패했습니다: {error}"
                    ) from error
                if differences:
                    raise CvesProjectError(
                        f"{source}: V4/V5 battle event 의미가 다릅니다: {differences[0]}"
                    )
        gym_slug = Path(resource_path).name if resource_path.startswith("gym_leaders/") else None
        if gym_slug in gym_entries:
            entry = gym_entries[gym_slug]
            try:
                differences = compare_gym_leader_migration(
                    entry,
                    programs_by_id[document["script_id"]],
                    post_victory_caps[entry["id"]],
                )
            except (KeyError, ValueError) as error:
                raise CvesProjectError(
                    f"{source}: 리그 관장 V4/V5 비교에 실패했습니다: {error}"
                ) from error
            if differences:
                raise CvesProjectError(
                    f"{source}: 리그 관장 V4/V5 의미가 다릅니다: {differences[0]}"
                )
        bindings.append(ProjectArtifact(
            Path(namespace) / "npc_event_binding" / f"{resource_path}.json",
            document,
        ))
    return ProjectBuild(tuple(scripts), tuple(bindings))


def _documents_by_id(directory: Path) -> dict[str, dict]:
    result: dict[str, dict] = {}
    for path in sorted(directory.rglob("*.json")) if directory.is_dir() else []:
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise CvesProjectError(f"{path}: JSON 원본을 읽을 수 없습니다: {error}") from error
        if isinstance(value, dict) and isinstance(value.get("id"), str):
            result[value["id"]] = value
    return result


def _league_entries(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise CvesProjectError(f"리그 진행 원본을 읽을 수 없습니다: {path}: {error}") from error
    entries = value.get("entries") if isinstance(value, dict) else None
    if not isinstance(entries, list) or any(not isinstance(entry, dict) for entry in entries):
        raise CvesProjectError(f"리그 진행 entries 배열이 필요합니다: {path}")
    return entries


def _post_victory_level_caps(entries: list[dict]) -> dict[str, int]:
    groups: dict[tuple[int, str], list[dict]] = {}
    for entry in entries:
        if entry.get("role") != "gym_leader":
            continue
        key = (int(entry.get("generation", 1)), str(entry.get("region", "")))
        groups.setdefault(key, []).append(entry)
    result: dict[str, int] = {}
    for group in groups.values():
        ordered = sorted(group, key=lambda value: (int(value["order"]), str(value["id"])))
        for index, entry in enumerate(ordered):
            result[entry["id"]] = (
                int(ordered[index + 1]["level_cap"])
                if index + 1 < len(ordered) else 100
            )
    return result


def _legacy_battle_id(document: dict) -> str | None:
    for event in document.get("events", []):
        for command in event.get("commands", []):
            if command.get("type") == "start_battle" and isinstance(command.get("battle"), str):
                return command["battle"]
    return None


def _has_legacy_starter_roulette(document: dict) -> bool:
    return any(
        command.get("type") == "start_starter_roulette"
        for event in document.get("events", [])
        for command in event.get("commands", [])
    )


def write_project(build: ProjectBuild, data_root: Path) -> tuple[Path, ...]:
    """Write a build under a datapack data directory using canonical JSON."""
    written: list[Path] = []
    for artifact in build.artifacts:
        target = data_root / artifact.relative_path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(
            json.dumps(artifact.document, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
            newline="\n",
        )
        written.append(target)
    return tuple(written)


def _resource_parts(source: Path, root: Path, suffix: str) -> tuple[str, str]:
    relative = source.relative_to(root)
    if len(relative.parts) < 2:
        raise CvesProjectError(
            f"{source}: <namespace>/<path>{suffix} 구조여야 합니다."
        )
    namespace = relative.parts[0]
    resource_path = Path(*relative.parts[1:]).as_posix()
    if not resource_path.endswith(suffix):
        raise CvesProjectError(f"예상하지 못한 파일 확장자입니다: {source}")
    resource_path = resource_path[: -len(suffix)]
    if not NAMESPACE.fullmatch(namespace) or not RESOURCE_PATH.fullmatch(resource_path):
        raise CvesProjectError(f"올바르지 않은 CVES 리소스 경로입니다: {relative.as_posix()}")
    return namespace, resource_path


def _binding_document(source: Path) -> dict:
    try:
        value = json.loads(source.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise CvesProjectError(f"NPC 이벤트 바인딩을 읽을 수 없습니다: {source}: {error}") from error
    if not isinstance(value, dict):
        raise CvesProjectError(f"{source}: NPC 이벤트 바인딩은 object여야 합니다.")
    unknown = sorted(set(value) - BINDING_FIELDS)
    if unknown:
        raise CvesProjectError(f"{source}: 알 수 없는 바인딩 필드입니다: {unknown[0]}")
    if value.get("schema_version") != 1 or isinstance(value.get("schema_version"), bool):
        raise CvesProjectError(f"{source}: schema_version은 정수 1이어야 합니다.")
    script_id = value.get("script_id")
    if not isinstance(script_id, str) or not re.fullmatch(
        r"[a-z0-9_.-]+:event_script/[a-z0-9_./-]+", script_id
    ):
        raise CvesProjectError(
            f"{source}: script_id는 namespace:event_script/path 형식이어야 합니다."
        )
    return {"schema_version": 1, "script_id": script_id}
