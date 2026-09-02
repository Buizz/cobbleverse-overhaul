"""Quest hook contract shared by authoring validation and library usage discovery."""
from __future__ import annotations
import json
import re
from pathlib import Path
from .editor import resolve_script_path
from .parser import parse
from .diagnostics import CvesSyntaxError


def iter_hooks(document: dict):
    hooks = document.get("event_hooks", {})
    if not isinstance(hooks, dict) or set(hooks) - {"on_accept", "on_complete"}:
        raise ValueError("$.event_hooks: on_accept/on_complete만 지정할 수 있습니다.")
    for name, hook in hooks.items():
        yield f"$.event_hooks.{name}", hook
    for index, objective in enumerate(document.get("objectives", [])):
        if isinstance(objective, dict) and "on_complete" in objective:
            yield f"$.objectives[{index}].on_complete", objective["on_complete"]


def validate_hook(hook: object) -> None:
    if not isinstance(hook, dict) or set(hook) != {"script_id", "npc_id"}:
        raise ValueError("이벤트 훅에는 script_id와 npc_id가 필요합니다.")
    for key, kind in (("script_id", "event_script"), ("npc_id", "npc")):
        value = hook[key]
        if not isinstance(value, str) or not re.fullmatch(r"[a-z0-9_.-]+:" + kind + r"/[a-z0-9_./-]+", value):
            raise ValueError(f"{key} 리소스 ID가 올바르지 않습니다.")
        if any(part in ("", ".", "..") for part in value.split("/")):
            raise ValueError(f"{key} 경로가 올바르지 않습니다.")


def validate_references(root: Path, document: dict) -> None:
    hooks = list(iter_hooks(document))
    if not hooks:
        return
    from .library import script_details
    npc_ids = set()
    for path in (root / "content/source").rglob("*.json"):
        npc = json.loads(path.read_text(encoding="utf-8-sig"))
        if isinstance(npc, dict) and isinstance(npc.get("id"), str):
            npc_ids.add(npc.get("id"))
    for location, hook in hooks:
        try:
            validate_hook(hook)
            namespace, resource = hook["script_id"].split(":event_script/", 1)
            path = f"{namespace}/{resource}.cves"
            source = resolve_script_path(root, path)
            if not source.is_file():
                raise ValueError("연결할 CVES 원본이 없습니다.")
            if hook["npc_id"] not in npc_ids:
                raise ValueError("실행 기준 NPC가 없습니다.")
            if script_details(root, path)["managed"]:
                raise ValueError("프리셋 관리 원본 대신 사용자 정의 이벤트를 연결하세요.")
            program = parse(source.read_text(encoding="utf-8"), path)
            events = [event for event in program.events if event.trigger.name == "quest"]
            if len(events) != 1 or events[0].trigger.arguments:
                raise ValueError("인수 없는 event quest 진입점이 정확히 하나 필요합니다.")
        except (ValueError, OSError, CvesSyntaxError) as error:
            raise ValueError(f"{location}: {error}") from error
