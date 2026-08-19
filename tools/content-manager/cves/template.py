"""Parser for the deliberately small CVES dialogue-template language."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class TemplateFilter:
    name: str
    argument: str | None = None


@dataclass(frozen=True, slots=True)
class TemplateReference:
    path: tuple[str, ...]
    filters: tuple[TemplateFilter, ...]
    start: int
    end: int
    source: str


@dataclass(frozen=True, slots=True)
class TemplateParseError(Exception):
    message: str
    offset: int
    token: str

    def __str__(self) -> str:
        return self.message


def parse_template(value: str) -> tuple[TemplateReference, ...]:
    """Parse references without evaluating expressions or accepting nested code."""
    references: list[TemplateReference] = []
    index = 0
    while index < len(value):
        if value[index] == "$" and index + 1 < len(value) and value[index + 1] == "{":
            start = index
            index += 2
            body_start = index
            while index < len(value) and value[index] != "}":
                if value[index] in "${":
                    raise TemplateParseError("템플릿 안에는 중첩된 표현식을 사용할 수 없습니다.", index, value[index])
                index += 1
            if index >= len(value):
                raise TemplateParseError("템플릿 참조를 닫는 '}'가 필요합니다.", start, value[start:])
            body = value[body_start:index]
            source = value[start:index + 1]
            references.append(_parse_reference(body, body_start, start, index + 1, source))
            index += 1
            continue
        index += 1
    return tuple(references)


def _parse_reference(
    body: str, body_offset: int, start: int, end: int, source: str
) -> TemplateReference:
    pieces = body.split("|")
    path_text = pieces[0]
    if not path_text:
        raise TemplateParseError("템플릿 변수 경로가 필요합니다.", body_offset, source)
    path = tuple(path_text.split("."))
    cursor = body_offset
    for part in path:
        if not _is_identifier(part):
            raise TemplateParseError(f"올바르지 않은 템플릿 경로 {path_text!r}입니다.", cursor, path_text)
        cursor += len(part) + 1

    filters: list[TemplateFilter] = []
    filter_offset = body_offset + len(path_text) + 1
    for piece in pieces[1:]:
        if not piece:
            raise TemplateParseError("빈 템플릿 필터는 사용할 수 없습니다.", filter_offset, "|")
        name, separator, argument = piece.partition(":")
        if not _is_identifier(name):
            raise TemplateParseError(f"올바르지 않은 템플릿 필터 {name!r}입니다.", filter_offset, piece)
        if separator and not argument:
            raise TemplateParseError(f"필터 {name!r}의 인자가 비어 있습니다.", filter_offset, piece)
        filters.append(TemplateFilter(name, argument if separator else None))
        filter_offset += len(piece) + 1
    return TemplateReference(path, tuple(filters), start, end, source)


def _is_identifier(value: str) -> bool:
    return bool(value) and (value[0].isalpha() or value[0] == "_") and all(
        char.isalnum() or char == "_" for char in value[1:]
    )
