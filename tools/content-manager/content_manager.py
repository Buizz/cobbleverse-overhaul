from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import asdict, dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse


RESOURCE_ID = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")
MOD_ID = re.compile(r"^[a-z][a-z0-9_-]*$")
CHOICE_ID = re.compile(r"^[a-z0-9_.-]+$")
VALID_LOCK_STATUSES = {"draft", "locked"}
VALID_SIDES = {"client", "server", "both"}
VALID_CLASSIFICATIONS = {
    "required",
    "required-candidate",
    "optional",
    "profile-optional",
    "development",
}


@dataclass(frozen=True)
class Issue:
    level: str
    file: str
    path: str
    message: str


@dataclass(frozen=True)
class ValidationResult:
    valid: bool
    errors: int
    warnings: int
    issues: list[Issue]

    def as_json(self) -> dict[str, Any]:
        return {
            "valid": self.valid,
            "errors": self.errors,
            "warnings": self.warnings,
            "issues": [asdict(issue) for issue in self.issues],
        }


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
    with path.open("r", encoding="utf-8-sig") as source:
        return json.load(source, object_pairs_hook=_reject_duplicate_keys)


def _issue(
    issues: list[Issue], level: str, path: Path, data_path: str, message: str
) -> None:
    issues.append(Issue(level, path.as_posix(), data_path, message))


def _require_object(
    value: Any,
    issues: list[Issue],
    file: Path,
    data_path: str,
) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        _issue(issues, "error", file, data_path, "객체여야 합니다.")
        return None
    return value


def _require_list(
    value: Any,
    issues: list[Issue],
    file: Path,
    data_path: str,
) -> list[Any] | None:
    if not isinstance(value, list):
        _issue(issues, "error", file, data_path, "배열이어야 합니다.")
        return None
    return value


def validate_dependency_lock(path: Path, strict_pack: bool) -> list[Issue]:
    issues: list[Issue] = []
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", path, "$", f"JSON을 읽을 수 없습니다: {error}")
        return issues

    root = _require_object(data, issues, path, "$")
    if root is None:
        return issues

    if root.get("schema_version") != 1:
        _issue(issues, "error", path, "$.schema_version", "지원 버전은 1입니다.")

    status = root.get("status")
    if status not in VALID_LOCK_STATUSES:
        _issue(issues, "error", path, "$.status", "draft 또는 locked여야 합니다.")

    profile = root.get("profile")
    if not isinstance(profile, str) or not profile.strip():
        _issue(issues, "error", path, "$.profile", "비어 있지 않은 문자열이어야 합니다.")

    minecraft = _require_object(root.get("minecraft"), issues, path, "$.minecraft")
    if minecraft is not None:
        loader = _require_object(
            minecraft.get("loader"), issues, path, "$.minecraft.loader"
        )
        if loader is not None and loader.get("type") != "neoforge":
            _issue(
                issues,
                "error",
                path,
                "$.minecraft.loader.type",
                "현재 기준 로더는 neoforge입니다.",
            )
        must_be_locked = strict_pack or status == "locked"
        if must_be_locked and not minecraft.get("version"):
            _issue(
                issues,
                "error",
                path,
                "$.minecraft.version",
                "패키징 전 Minecraft 버전을 고정해야 합니다.",
            )
        if must_be_locked and (loader is None or not loader.get("version")):
            _issue(
                issues,
                "error",
                path,
                "$.minecraft.loader.version",
                "패키징 전 NeoForge 버전을 고정해야 합니다.",
            )

    mods = _require_list(root.get("mods"), issues, path, "$.mods")
    seen_ids: set[str] = set()
    seen_cf_files: set[tuple[int, int]] = set()
    if mods is not None:
        for index, value in enumerate(mods):
            item_path = f"$.mods[{index}]"
            mod = _require_object(value, issues, path, item_path)
            if mod is None:
                continue
            mod_id = mod.get("id")
            if not isinstance(mod_id, str) or not MOD_ID.fullmatch(mod_id):
                _issue(issues, "error", path, f"{item_path}.id", "올바른 모드 ID가 아닙니다.")
            elif mod_id in seen_ids:
                _issue(issues, "error", path, f"{item_path}.id", f"중복 모드 ID: {mod_id}")
            else:
                seen_ids.add(mod_id)

            if mod.get("side") not in VALID_SIDES:
                _issue(issues, "error", path, f"{item_path}.side", "client, server, both 중 하나여야 합니다.")
            if mod.get("classification") not in VALID_CLASSIFICATIONS:
                _issue(issues, "error", path, f"{item_path}.classification", "지원하지 않는 분류입니다.")
            if not isinstance(mod.get("enabled"), bool):
                _issue(issues, "error", path, f"{item_path}.enabled", "boolean이어야 합니다.")
            if not isinstance(mod.get("display_name"), str) or not mod.get("display_name", "").strip():
                _issue(issues, "error", path, f"{item_path}.display_name", "이름이 필요합니다.")
            if not isinstance(mod.get("reason"), str) or not mod.get("reason", "").strip():
                _issue(issues, "error", path, f"{item_path}.reason", "선정 이유가 필요합니다.")

            curseforge = _require_object(
                mod.get("curseforge"), issues, path, f"{item_path}.curseforge"
            )
            project_id = curseforge.get("project_id") if curseforge else None
            file_id = curseforge.get("file_id") if curseforge else None
            if project_id is not None and (not isinstance(project_id, int) or project_id < 1):
                _issue(issues, "error", path, f"{item_path}.curseforge.project_id", "양의 정수 또는 null이어야 합니다.")
            if file_id is not None and (not isinstance(file_id, int) or file_id < 1):
                _issue(issues, "error", path, f"{item_path}.curseforge.file_id", "양의 정수 또는 null이어야 합니다.")
            if isinstance(project_id, int) and isinstance(file_id, int):
                pair = (project_id, file_id)
                if pair in seen_cf_files:
                    _issue(issues, "error", path, f"{item_path}.curseforge", "동일한 CurseForge 파일이 중복되었습니다.")
                seen_cf_files.add(pair)

            if (strict_pack or status == "locked") and mod.get("enabled"):
                if not mod.get("version"):
                    _issue(issues, "error", path, f"{item_path}.version", "활성 모드 버전을 고정해야 합니다.")
                if not isinstance(project_id, int) or not isinstance(file_id, int):
                    _issue(issues, "error", path, f"{item_path}.curseforge", "활성 외부 모드의 CurseForge ID를 고정해야 합니다.")

    if status == "draft" and not strict_pack:
        _issue(
            issues,
            "warning",
            path,
            "$.status",
            "의존성이 draft 상태입니다. 일반 콘텐츠 개발은 가능하지만 테스트팩 패키징은 차단됩니다.",
        )
    return issues


def validate_content_file(path: Path) -> tuple[str | None, list[Issue]]:
    issues: list[Issue] = []
    try:
        data = load_json(path)
    except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
        _issue(issues, "error", path, "$", f"JSON을 읽을 수 없습니다: {error}")
        return None, issues

    root = _require_object(data, issues, path, "$")
    if root is None:
        return None, issues
    if root.get("schema_version") != 1:
        _issue(issues, "error", path, "$.schema_version", "지원 버전은 1입니다.")

    content_id = root.get("id")
    if not isinstance(content_id, str) or not RESOURCE_ID.fullmatch(content_id):
        _issue(issues, "error", path, "$.id", "namespace:path 형식의 리소스 ID가 필요합니다.")
        content_id = None

    trainer = _require_object(root.get("trainer"), issues, path, "$.trainer")
    if trainer is not None:
        for key in ("name", "ai", "battle_format"):
            if not isinstance(trainer.get(key), str) or not trainer.get(key, "").strip():
                _issue(issues, "error", path, f"$.trainer.{key}", "비어 있지 않은 문자열이어야 합니다.")
        _require_list(trainer.get("team"), issues, path, "$.trainer.team")

    npc = _require_object(root.get("npc"), issues, path, "$.npc")
    initial_dialogue = None
    if npc is not None:
        initial_dialogue = npc.get("initial_dialogue")
        if not isinstance(initial_dialogue, str) or not RESOURCE_ID.fullmatch(initial_dialogue):
            _issue(issues, "error", path, "$.npc.initial_dialogue", "올바른 대화 리소스 ID가 필요합니다.")

    dialogues = _require_list(root.get("dialogues"), issues, path, "$.dialogues")
    dialogue_ids: set[str] = set()
    pending_targets: list[tuple[str, str]] = []
    if dialogues is not None:
        for dialogue_index, value in enumerate(dialogues):
            dialogue_path = f"$.dialogues[{dialogue_index}]"
            dialogue = _require_object(value, issues, path, dialogue_path)
            if dialogue is None:
                continue
            dialogue_id = dialogue.get("id")
            if not isinstance(dialogue_id, str) or not RESOURCE_ID.fullmatch(dialogue_id):
                _issue(issues, "error", path, f"{dialogue_path}.id", "올바른 대화 리소스 ID가 필요합니다.")
            elif dialogue_id in dialogue_ids:
                _issue(issues, "error", path, f"{dialogue_path}.id", f"중복 대화 ID: {dialogue_id}")
            else:
                dialogue_ids.add(dialogue_id)

            _require_list(dialogue.get("conditions"), issues, path, f"{dialogue_path}.conditions")
            choices = _require_list(dialogue.get("choices"), issues, path, f"{dialogue_path}.choices")
            seen_choices: set[str] = set()
            if choices is None:
                continue
            for choice_index, choice_value in enumerate(choices):
                choice_path = f"{dialogue_path}.choices[{choice_index}]"
                choice = _require_object(choice_value, issues, path, choice_path)
                if choice is None:
                    continue
                choice_id = choice.get("id")
                if not isinstance(choice_id, str) or not CHOICE_ID.fullmatch(choice_id):
                    _issue(issues, "error", path, f"{choice_path}.id", "올바른 선택지 ID가 아닙니다.")
                elif choice_id in seen_choices:
                    _issue(issues, "error", path, f"{choice_path}.id", f"현재 대화의 중복 선택지 ID: {choice_id}")
                else:
                    seen_choices.add(choice_id)
                _require_list(choice.get("conditions"), issues, path, f"{choice_path}.conditions")
                actions = _require_list(choice.get("actions"), issues, path, f"{choice_path}.actions")
                if actions is None or not actions:
                    _issue(issues, "error", path, f"{choice_path}.actions", "행동이 하나 이상 필요합니다.")
                    continue
                for action_index, action_value in enumerate(actions):
                    action_path = f"{choice_path}.actions[{action_index}]"
                    action = _require_object(action_value, issues, path, action_path)
                    if action is None:
                        continue
                    action_type = action.get("type")
                    if not isinstance(action_type, str) or not action_type:
                        _issue(issues, "error", path, f"{action_path}.type", "행동 타입이 필요합니다.")
                    elif action_type == "next_dialogue":
                        target = action.get("target")
                        if not isinstance(target, str) or not RESOURCE_ID.fullmatch(target):
                            _issue(issues, "error", path, f"{action_path}.target", "올바른 대상 대화 ID가 필요합니다.")
                        else:
                            pending_targets.append((action_path, target))
                    elif action_type == "start_rct_battle":
                        target = action.get("trainer")
                        if target != content_id:
                            _issue(issues, "error", path, f"{action_path}.trainer", "현재 콘텐츠의 트레이너 ID와 일치해야 합니다.")

    if initial_dialogue and initial_dialogue not in dialogue_ids:
        _issue(issues, "error", path, "$.npc.initial_dialogue", f"존재하지 않는 대화 ID: {initial_dialogue}")
    for action_path, target in pending_targets:
        if target not in dialogue_ids:
            _issue(issues, "error", path, f"{action_path}.target", f"존재하지 않는 대화 ID: {target}")

    _require_list(root.get("quests"), issues, path, "$.quests")
    return content_id, issues


def validate_repository(root: Path, strict_pack: bool = False) -> ValidationResult:
    root = root.resolve()
    issues = validate_dependency_lock(root / "pack" / "dependencies.lock.json", strict_pack)
    content_dir = root / "content" / "source"
    seen_content: dict[str, Path] = {}
    if not content_dir.exists():
        _issue(issues, "error", content_dir, "$", "콘텐츠 원본 디렉터리가 없습니다.")
    else:
        for path in sorted(content_dir.rglob("*.json")):
            content_id, file_issues = validate_content_file(path)
            issues.extend(file_issues)
            if content_id is None:
                continue
            if content_id in seen_content:
                _issue(
                    issues,
                    "error",
                    path,
                    "$.id",
                    f"다른 파일과 중복된 콘텐츠 ID: {content_id} ({seen_content[content_id].as_posix()})",
                )
            else:
                seen_content[content_id] = path

    errors = sum(issue.level == "error" for issue in issues)
    warnings = sum(issue.level == "warning" for issue in issues)
    return ValidationResult(errors == 0, errors, warnings, issues)


def _print_result(result: ValidationResult) -> None:
    for issue in result.issues:
        label = "오류" if issue.level == "error" else "경고"
        print(f"[{label}] {issue.file} {issue.path}: {issue.message}")
    if result.valid:
        print(f"검증 성공: 오류 0개, 경고 {result.warnings}개")
    else:
        print(f"검증 실패: 오류 {result.errors}개, 경고 {result.warnings}개")


def create_handler(root: Path) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        server_version = "CobbleventureContentManager/0.1"

        def _json(self, status: int, payload: Any) -> None:
            body = json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _route(self) -> None:
            request = urlparse(self.path)
            if request.path == "/health":
                self._json(200, {"status": "ok", "service": "cobbleventure-content-manager"})
                return
            if request.path == "/dependencies":
                try:
                    self._json(200, load_json(root / "pack" / "dependencies.lock.json"))
                except (OSError, json.JSONDecodeError, DuplicateKeyError) as error:
                    self._json(500, {"error": str(error)})
                return
            if request.path == "/validate":
                query = parse_qs(request.query)
                strict_pack = query.get("strict_pack", ["false"])[0].lower() in {"1", "true", "yes"}
                result = validate_repository(root, strict_pack)
                self._json(200 if result.valid else 422, result.as_json())
                return
            self._json(404, {"error": "not_found"})

        def do_GET(self) -> None:
            self._route()

        def do_POST(self) -> None:
            if urlparse(self.path).path == "/validate":
                self._route()
                return
            self._json(404, {"error": "not_found"})

        def log_message(self, format: str, *args: Any) -> None:
            print(f"[API] {self.address_string()} {format % args}")

    return Handler


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Cobbleventure 콘텐츠 관리 도구")
    subcommands = parser.add_subparsers(dest="command", required=True)

    validate = subcommands.add_parser("validate", help="콘텐츠와 의존성 Lock 검증")
    validate.add_argument("--root", type=Path, default=Path.cwd())
    validate.add_argument("--strict-pack", action="store_true")
    validate.add_argument("--json", action="store_true", dest="json_output")

    api = subcommands.add_parser("api", help="로컬 Web API 실행")
    api.add_argument("--root", type=Path, default=Path.cwd())
    api.add_argument("--host", default="127.0.0.1")
    api.add_argument("--port", type=int, default=8765)
    return parser


def main() -> int:
    arguments = _parser().parse_args()
    if arguments.command == "validate":
        result = validate_repository(arguments.root, arguments.strict_pack)
        if arguments.json_output:
            print(json.dumps(result.as_json(), ensure_ascii=False, indent=2))
        else:
            _print_result(result)
        return 0 if result.valid else 1

    root = arguments.root.resolve()
    server = ThreadingHTTPServer((arguments.host, arguments.port), create_handler(root))
    print(f"Cobbleventure Content Manager API: http://{arguments.host}:{arguments.port}")
    print(f"저장소: {root}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nAPI를 종료합니다.")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
