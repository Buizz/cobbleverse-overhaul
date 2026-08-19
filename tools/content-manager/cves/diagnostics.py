"""Source locations and user-facing CVES diagnostics."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class SourcePosition:
    offset: int
    line: int
    column: int


@dataclass(frozen=True, slots=True)
class SourceSpan:
    source: str
    start: SourcePosition
    end: SourcePosition

    @classmethod
    def covering(cls, first: "SourceSpan", last: "SourceSpan") -> "SourceSpan":
        return cls(first.source, first.start, last.end)


@dataclass(frozen=True, slots=True)
class Diagnostic:
    message: str
    span: SourceSpan
    token: str | None = None

    def render(self) -> str:
        location = f"{self.span.source}:{self.span.start.line}:{self.span.start.column}"
        if self.token is None:
            return f"{location}: {self.message}"
        return f"{location}: {self.message} (문제 토큰: {self.token!r})"


class CvesSyntaxError(Exception):
    """Raised when lexing or parsing cannot produce a valid syntax tree."""

    def __init__(self, diagnostic: Diagnostic):
        self.diagnostic = diagnostic
        super().__init__(diagnostic.render())
