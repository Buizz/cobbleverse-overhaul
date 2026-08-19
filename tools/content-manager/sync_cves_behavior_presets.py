"""Explicitly enable and synchronize preset-authored CVES NPC events."""

from __future__ import annotations

import argparse
import json
import os
import tempfile
from pathlib import Path

import content_manager


def synchronize(
    project_root: Path,
    source_directory: Path,
    *,
    enable: bool,
    dry_run: bool = False,
    upgrade_managed: bool = False,
) -> list[dict]:
    root = project_root.resolve()
    directory = source_directory.resolve()
    source_root = (root / "content" / "source").resolve()
    if directory != source_root and source_root not in directory.parents:
        raise ValueError("동기화 디렉터리는 content/source 아래여야 합니다.")
    results: list[dict] = []
    for source in sorted(directory.rglob("*.json")):
        original_source = source.read_text(encoding="utf-8")
        document = json.loads(original_source)
        if document.get("schema_version") != 4 \
                or document.get("event_design", {}).get("mode") != "preset":
            continue
        relative = source.relative_to(source_root).with_suffix("")
        namespace = str(document.get("id", "cobbleventure:npc/unknown")).split(":", 1)[0]
        expected = f"{namespace}:event_script/{relative.as_posix()}"
        runtime = document.get("event_runtime")
        if enable and not isinstance(runtime, dict):
            document["event_runtime"] = {
                "engine": "cves_v5", "authoring": "preset", "script_id": expected,
            }
        if document.get("event_runtime", {}).get("engine") != "cves_v5":
            continue
        plan = content_manager._prepare_v5_preset_sync(
            root, source, document,
            allow_managed_upgrade=upgrade_managed,
        )
        if plan is None:
            continue
        generated_source = _json_source(document)
        preview = content_manager._preview_v5_preset_sync(root, plan)
        source_action = "unchanged" if original_source == generated_source else "update"
        artifacts = [{
            "kind": "source",
            "path": source.relative_to(root).as_posix(),
            "action": source_action,
        }, *[
            {key: artifact[key] for key in ("kind", "path", "action")}
            for artifact in preview["artifacts"]
        ]]
        results.append({
            "npc_id": document.get("id"),
            "script_id": document["event_runtime"]["script_id"],
            "changed": any(artifact["action"] != "unchanged" for artifact in artifacts),
            "artifacts": artifacts,
        })
        if not dry_run:
            if source_action != "unchanged":
                _write_json_atomic(source, document)
            if preview["changed"]:
                content_manager._write_v5_preset_sync(plan)
    return results


def _write_json_atomic(target: Path, document: dict) -> None:
    handle, temporary_name = tempfile.mkstemp(
        prefix=f".{target.stem}-", suffix=".json.tmp", dir=target.parent
    )
    try:
        with os.fdopen(handle, "w", encoding="utf-8", newline="\n") as output:
            json.dump(document, output, ensure_ascii=False, indent=2)
            output.write("\n")
        os.replace(temporary_name, target)
    finally:
        Path(temporary_name).unlink(missing_ok=True)


def _json_source(document: dict) -> str:
    return json.dumps(document, ensure_ascii=False, indent=2) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(
        description="NPC 행동 프리셋을 결정적인 V5 CVES와 바인딩으로 동기화합니다."
    )
    parser.add_argument("project_root", type=Path)
    parser.add_argument("source_directory", type=Path)
    parser.add_argument(
        "--enable", action="store_true",
        help="아직 실행 방식을 지정하지 않은 행동 프리셋 NPC를 CVES V5로 명시적으로 전환합니다.",
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="파일을 쓰지 않고 생성·변경·동일 산출물 목록만 출력합니다.",
    )
    parser.add_argument(
        "--upgrade-managed", action="store_true",
        help="preset 작성으로 표시된 관리 대상 CVES를 최신 생성기 계약으로 갱신합니다.",
    )
    parser.add_argument(
        "--json", action="store_true",
        help="검토하거나 자동화하기 쉬운 JSON 보고서를 출력합니다.",
    )
    arguments = parser.parse_args()
    results = synchronize(
        arguments.project_root, arguments.source_directory,
        enable=arguments.enable, dry_run=arguments.dry_run,
        upgrade_managed=arguments.upgrade_managed,
    )
    report = {
        "dry_run": arguments.dry_run,
        "npcs": len(results),
        "changed_npcs": sum(result["changed"] for result in results),
        "results": results,
    }
    if arguments.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        mode = "검토" if arguments.dry_run else "동기화"
        for result in results:
            print(f"[{result['npc_id']}] {result['script_id']}")
            for artifact in result["artifacts"]:
                print(f"  {artifact['action']:9} {artifact['path']}")
        print(
            f"CVES 행동 프리셋 {len(results)}개를 {mode}했습니다. "
            f"변경 대상: {report['changed_npcs']}개"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
