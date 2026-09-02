"""Git-backed authoring metadata and derived NPC usage; never execution data."""
from __future__ import annotations

import hashlib
import json
import os
import tempfile
from pathlib import Path

from .editor import CvesEditorConflict, list_scripts, resolve_script_path

CATEGORIES = ("npc", "quest", "region", "common", "system")


def _digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _metadata_path(root: Path, path: str) -> Path:
    source = resolve_script_path(root, path)
    relative = source.relative_to((root / "content/events").resolve()).with_suffix(".json")
    directory = (root / "content/event-metadata").resolve()
    target = (directory / relative).resolve()
    if directory not in target.parents:
        raise ValueError("이벤트 메타데이터 디렉터리 밖에는 저장할 수 없습니다.")
    return target


def validate_metadata(value: object) -> dict:
    if not isinstance(value, dict) or set(value) - {"schema_version", "display_name", "description", "category", "tags"}:
        raise ValueError("이벤트 메타데이터 필드가 올바르지 않습니다.")
    if type(value.get("schema_version")) is not int or value["schema_version"] != 1:
        raise ValueError("이벤트 메타데이터 schema_version은 1이어야 합니다.")
    result = {"schema_version": 1}
    for key, limit in (("display_name", 120), ("description", 2000)):
        text = value.get(key, "")
        if not isinstance(text, str) or len(text) > limit:
            raise ValueError(f"{key}는 {limit}자 이하 문자열이어야 합니다.")
        result[key] = text.strip()
    category = value.get("category", "common")
    if category not in CATEGORIES:
        raise ValueError("이벤트 분류가 올바르지 않습니다.")
    result["category"] = category
    tags = value.get("tags", [])
    if not isinstance(tags, list) or len(tags) > 30 or any(
        not isinstance(tag, str) or not tag.strip() or len(tag) > 40 for tag in tags
    ):
        raise ValueError("태그는 40자 이하의 빈 값이 아닌 문자열, 최대 30개여야 합니다.")
    result["tags"] = sorted(set(tag.strip() for tag in tags))
    return result


def usage_index(root: Path) -> dict[str, list[dict]]:
    """Scan saved documents, including binding-only consumers. No inferred quest hooks."""
    result: dict[str, list[dict]] = {}
    covered: set[tuple[str, str]] = set()
    source_root = root / "content/source"
    for file in sorted(source_root.rglob("*.json")):
        data = json.loads(file.read_text(encoding="utf-8-sig"))
        if not isinstance(data, dict):
            continue
        runtime = data.get("event_runtime", {})
        if not isinstance(runtime, dict) or runtime.get("engine") != "cves_v5":
            continue
        script_id = runtime.get("script_id")
        if not isinstance(script_id, str):
            continue
        name = data.get("name", data.get("id", file.stem))
        if isinstance(name, dict):
            name = name.get("ko_kr") or name.get("en_us") or file.stem
        usage = {"kind": "npc", "path": file.relative_to(root).as_posix(),
                 "id": data.get("id", ""), "name": str(name),
                 "managed": runtime.get("authoring") == "preset"}
        result.setdefault(script_id, []).append(usage)
        namespace = str(data.get("id", "")).partition(":")[0]
        covered.add((f"{namespace}/{file.relative_to(source_root).as_posix()}", script_id))
    binding_root = root / "content/event-bindings"
    for file in sorted(binding_root.rglob("*.json")):
        data = json.loads(file.read_text(encoding="utf-8-sig"))
        script_id = data.get("script_id") if isinstance(data, dict) else None
        if not isinstance(script_id, str) or (file.relative_to(binding_root).as_posix(), script_id) in covered:
            continue
        result.setdefault(script_id, []).append({"kind": "binding", "path": file.relative_to(root).as_posix(),
                                                "id": "", "name": file.stem, "managed": False})
    return result


def script_details(root: Path, path: str, *, usages: dict | None = None) -> dict:
    source = resolve_script_path(root, path)
    relative = source.relative_to((root / "content/events").resolve()).as_posix()
    namespace, resource = relative.split("/", 1)
    script_id = f"{namespace}:event_script/{resource[:-5]}"
    usage = (usage_index(root) if usages is None else usages).get(script_id, [])
    metadata_path = _metadata_path(root, relative)
    raw = metadata_path.read_bytes() if metadata_path.exists() else None
    metadata = validate_metadata(json.loads(raw)) if raw is not None else {
        "schema_version": 1, "display_name": "", "description": "",
        "category": "npc" if usage else "common", "tags": [],
    }
    return {"path": relative, "script_id": script_id, "name": metadata["display_name"] or source.stem,
            "metadata": metadata, "metadata_digest": _digest(raw) if raw is not None else None,
            "usages": usage, "managed": any(item["managed"] for item in usage),
            "usage_digest": _digest(json.dumps(usage, sort_keys=True, ensure_ascii=False).encode("utf-8"))}


def list_library(root: Path) -> list[dict]:
    usages = usage_index(root)
    return [script_details(root, item["path"], usages=usages) for item in list_scripts(root)]


def check_source_write(root: Path, path: str, usage_digest: str | None) -> None:
    details = script_details(root, path)
    if details["managed"]:
        raise ValueError("행동 프리셋 관리 이벤트입니다. NPC에서 사용자 정의로 전환해 저장하거나 복사본을 만드세요.")
    if len(details["usages"]) > 1 and usage_digest != details["usage_digest"]:
        raise CvesEditorConflict("공유 이벤트의 사용처를 다시 확인한 뒤 전체 적용을 승인해 주세요.")


def save_metadata(root: Path, path: str, metadata: object, expected_digest: str | None) -> dict:
    if not resolve_script_path(root, path).is_file():
        raise ValueError("먼저 CVES 원본을 저장해 주세요.")
    value = validate_metadata(metadata)
    target = _metadata_path(root, path)
    actual = _digest(target.read_bytes()) if target.exists() else None
    if expected_digest != actual:
        raise CvesEditorConflict("이벤트 분류 정보가 변경되었습니다. 다시 불러와 주세요.")
    target.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary = tempfile.mkstemp(prefix=".metadata-", suffix=".tmp", dir=target.parent)
    try:
        with os.fdopen(handle, "w", encoding="utf-8", newline="\n") as output:
            output.write(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
        os.replace(temporary, target)
    finally:
        Path(temporary).unlink(missing_ok=True)
    return script_details(root, path)
