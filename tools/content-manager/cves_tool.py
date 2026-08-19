"""Command-line entry point for checking, formatting, and exporting CVES files."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Sequence, TextIO

from cves import (
    CvesCompilationError,
    CvesSyntaxError,
    compile_program,
    encode_program,
    format_program,
    load_project_catalog,
    parse,
    validate,
)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="CobbleVenture Event Script 도구")
    commands = parser.add_subparsers(dest="command", required=True)

    check = commands.add_parser("check", help="문법·타입·프로젝트 리소스 검증")
    check.add_argument("paths", nargs="+", type=Path, help=".cves 파일 또는 디렉터리")
    check.add_argument("--project-root", type=Path, help="교차 검증할 콘텐츠 프로젝트")
    check.add_argument("--item-catalog", type=Path, help="외부 Minecraft item catalog JSON")
    check.add_argument("--quiet", action="store_true", help="성공 파일을 출력하지 않음")

    formatter = commands.add_parser("format", help="결정적인 canonical CVES 포맷 적용")
    formatter.add_argument("paths", nargs="+", type=Path, help=".cves 파일 또는 디렉터리")
    mode = formatter.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="변경 없이 포맷 일치 여부만 검사")
    mode.add_argument("--write", action="store_true", help="canonical 결과를 파일에 기록")

    ast_command = commands.add_parser("ast", help="공통 AST JSON wire 출력")
    ast_command.add_argument("path", type=Path, help="하나의 .cves 파일")
    ast_command.add_argument("--no-spans", action="store_true", help="source span을 출력하지 않음")

    compiler = commands.add_parser("compile", help="주소 기반 runtime IR JSON 생성")
    compiler.add_argument("path", type=Path, help="하나의 .cves 파일")
    compiler.add_argument("--script-id", required=True, help="namespace:event_script/path")
    compiler.add_argument("--project-root", type=Path, help="교차 검증할 콘텐츠 프로젝트")
    compiler.add_argument("--item-catalog", type=Path, help="외부 Minecraft item catalog JSON")
    compiler.add_argument("--output", type=Path, help="IR JSON 저장 경로, 생략하면 stdout")
    return parser


def main(
    argv: Sequence[str] | None = None,
    *,
    stdout: TextIO | None = None,
    stderr: TextIO | None = None,
) -> int:
    output = stdout or sys.stdout
    errors = stderr or sys.stderr
    arguments = _parser().parse_args(argv)
    if arguments.command == "compile":
        return _compile(arguments, output, errors)
    if arguments.command == "ast":
        try:
            program = _read_program(arguments.path)
        except (OSError, CvesSyntaxError) as error:
            print(_error_text(error), file=errors)
            return 1
        print(json.dumps(
            encode_program(program, include_spans=not arguments.no_spans),
            ensure_ascii=False,
            indent=2,
        ), file=output)
        return 0

    try:
        paths = _resolve_paths(arguments.paths)
    except ValueError as error:
        print(f"[ERROR] {error}", file=errors)
        return 1
    if arguments.command == "check":
        return _check(paths, arguments, output, errors)
    return _format(paths, arguments, output, errors)


def _compile(arguments: argparse.Namespace, output: TextIO, errors: TextIO) -> int:
    if arguments.item_catalog is not None and arguments.project_root is None:
        print("[ERROR] --item-catalog은 --project-root와 함께 사용해야 합니다.", file=errors)
        return 1
    try:
        program = _read_program(arguments.path.resolve())
        catalog = (
            load_project_catalog(
                arguments.project_root.resolve(),
                item_catalog=arguments.item_catalog.resolve() if arguments.item_catalog else None,
            )
            if arguments.project_root is not None else None
        )
        ir = compile_program(program, arguments.script_id, catalog)
    except (OSError, CvesSyntaxError) as error:
        print(_error_text(error), file=errors)
        return 1
    except (ValueError, CvesCompilationError) as error:
        print(str(error), file=errors)
        return 1
    encoded = (json.dumps(ir, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if arguments.output is None:
        output.write(encoded.decode("utf-8"))
        return 0
    target = arguments.output.resolve()
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(encoded)
    except OSError as error:
        print(f"[ERROR] IR을 기록할 수 없습니다: {target}: {error}", file=errors)
        return 1
    print(f"[WRITE] {target}", file=output)
    return 0


def _check(paths: tuple[Path, ...], arguments: argparse.Namespace, output: TextIO, errors: TextIO) -> int:
    catalog = None
    if arguments.project_root is not None:
        try:
            catalog = load_project_catalog(
                arguments.project_root.resolve(),
                item_catalog=arguments.item_catalog.resolve() if arguments.item_catalog else None,
            )
        except ValueError as error:
            print(f"[ERROR] {error}", file=errors)
            return 1
    elif arguments.item_catalog is not None:
        print("[ERROR] --item-catalog은 --project-root와 함께 사용해야 합니다.", file=errors)
        return 1

    failed = False
    for path in paths:
        try:
            program = _read_program(path)
        except (OSError, CvesSyntaxError) as error:
            print(_error_text(error), file=errors)
            failed = True
            continue
        diagnostics = validate(program, catalog)
        if diagnostics:
            failed = True
            for diagnostic in diagnostics:
                print(diagnostic.render(), file=errors)
        elif not arguments.quiet:
            print(f"[OK] {path}", file=output)
    return 1 if failed else 0


def _format(paths: tuple[Path, ...], arguments: argparse.Namespace, output: TextIO, errors: TextIO) -> int:
    if not arguments.check and not arguments.write and len(paths) != 1:
        print("[ERROR] stdout 출력은 하나의 CVES 파일만 지정할 수 있습니다.", file=errors)
        return 1
    failed = False
    for path in paths:
        try:
            original = path.read_text(encoding="utf-8")
            formatted = format_program(parse(original, str(path)))
        except (OSError, CvesSyntaxError) as error:
            print(_error_text(error), file=errors)
            failed = True
            continue
        if arguments.check:
            if original != formatted:
                print(f"[FORMAT] canonical 포맷과 다릅니다: {path}", file=errors)
                failed = True
            else:
                print(f"[OK] {path}", file=output)
        elif arguments.write:
            if original != formatted:
                try:
                    path.write_bytes(formatted.encode("utf-8"))
                except OSError as error:
                    print(f"[ERROR] 파일을 기록할 수 없습니다: {path}: {error}", file=errors)
                    failed = True
                    continue
                print(f"[WRITE] {path}", file=output)
            else:
                print(f"[OK] {path}", file=output)
        else:
            output.write(formatted)
    return 1 if failed else 0


def _resolve_paths(values: Sequence[Path]) -> tuple[Path, ...]:
    resolved: set[Path] = set()
    for value in values:
        path = value.resolve()
        if path.is_file():
            if path.suffix != ".cves":
                raise ValueError(f".cves 파일이 아닙니다: {path}")
            resolved.add(path)
        elif path.is_dir():
            resolved.update(candidate.resolve() for candidate in path.rglob("*.cves") if candidate.is_file())
        else:
            raise ValueError(f"경로를 찾을 수 없습니다: {path}")
    if not resolved:
        raise ValueError("검사할 .cves 파일이 없습니다.")
    return tuple(sorted(resolved, key=lambda path: path.as_posix()))


def _read_program(path: Path):
    return parse(path.read_text(encoding="utf-8"), str(path))


def _error_text(error: OSError | CvesSyntaxError) -> str:
    if isinstance(error, CvesSyntaxError):
        return error.diagnostic.render()
    return f"[ERROR] 파일을 읽을 수 없습니다: {error}"


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    sys.exit(main())
