"""Position-aware lexer for CVES."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum, auto

from .diagnostics import CvesSyntaxError, Diagnostic, SourcePosition, SourceSpan


class TokenKind(Enum):
    IDENTIFIER = auto()
    INTEGER = auto()
    DECIMAL = auto()
    STRING = auto()
    NEWLINE = auto()
    LEFT_BRACE = auto()
    RIGHT_BRACE = auto()
    LEFT_PAREN = auto()
    RIGHT_PAREN = auto()
    COMMA = auto()
    COLON = auto()
    DOT = auto()
    ARROW = auto()
    OPERATOR = auto()
    EOF = auto()


@dataclass(frozen=True, slots=True)
class Token:
    kind: TokenKind
    lexeme: str
    value: object | None
    span: SourceSpan


class Lexer:
    def __init__(self, text: str, source: str = "<memory>") -> None:
        self.text = text
        self.source = source
        self.offset = 0
        self.line = 1
        self.column = 1

    def tokenize(self) -> tuple[Token, ...]:
        tokens: list[Token] = []
        while not self._at_end():
            char = self._peek()
            if char in " \t\r":
                self._advance()
                continue
            if char == "#":
                self._skip_comment()
                continue
            if char == "\n":
                start = self._position()
                self._advance()
                tokens.append(self._token(TokenKind.NEWLINE, start, "\n"))
                continue
            if char == '"':
                tokens.append(self._string())
                continue
            if char.isdigit():
                tokens.append(self._number())
                continue
            if char.isalpha() or char == "_":
                tokens.append(self._identifier())
                continue

            start = self._position()
            two = self.text[self.offset : self.offset + 2]
            if two == "->":
                self._advance(); self._advance()
                tokens.append(self._token(TokenKind.ARROW, start, two))
                continue
            if two in {"==", "!=", "<=", ">=", "&&", "||"}:
                self._advance(); self._advance()
                tokens.append(self._token(TokenKind.OPERATOR, start, two, two))
                continue
            punctuation = {
                "{": TokenKind.LEFT_BRACE, "}": TokenKind.RIGHT_BRACE,
                "(": TokenKind.LEFT_PAREN, ")": TokenKind.RIGHT_PAREN,
                ",": TokenKind.COMMA, ":": TokenKind.COLON, ".": TokenKind.DOT,
            }
            if char in punctuation:
                self._advance()
                tokens.append(self._token(punctuation[char], start, char))
                continue
            if char in "+-*/%<>=!":
                self._advance()
                tokens.append(self._token(TokenKind.OPERATOR, start, char, char))
                continue
            self._error(f"인식할 수 없는 문자 {char!r}입니다.", start, char)

        position = self._position()
        tokens.append(Token(TokenKind.EOF, "", None, SourceSpan(self.source, position, position)))
        return tuple(tokens)

    def _string(self) -> Token:
        start = self._position()
        self._advance()
        value: list[str] = []
        escapes = {'"': '"', "\\": "\\", "n": "\n", "r": "\r", "t": "\t"}
        while not self._at_end() and self._peek() != '"':
            if self._peek() == "\n":
                self._error("문자열을 닫는 큰따옴표가 필요합니다.", start, self.text[start.offset:self.offset])
            if self._peek() == "\\":
                self._advance()
                if self._at_end():
                    break
                escaped = self._advance()
                if escaped == "u":
                    digits = self.text[self.offset : self.offset + 4]
                    if len(digits) != 4 or any(c not in "0123456789abcdefABCDEF" for c in digits):
                        self._error("유니코드 이스케이프에는 16진수 네 자리가 필요합니다.", start, "\\u" + digits)
                    value.append(chr(int(digits, 16)))
                    for _ in range(4): self._advance()
                elif escaped in escapes:
                    value.append(escapes[escaped])
                else:
                    self._error(f"지원하지 않는 이스케이프 \\{escaped}입니다.", start, "\\" + escaped)
            else:
                value.append(self._advance())
        if self._at_end():
            self._error("문자열을 닫는 큰따옴표가 필요합니다.", start, self.text[start.offset:self.offset])
        self._advance()
        return self._token(TokenKind.STRING, start, self.text[start.offset:self.offset], "".join(value))

    def _number(self) -> Token:
        start = self._position()
        while self._peek().isdigit(): self._advance()
        kind = TokenKind.INTEGER
        if self._peek() == "." and self._peek(1).isdigit():
            kind = TokenKind.DECIMAL
            self._advance()
            while self._peek().isdigit(): self._advance()
        lexeme = self.text[start.offset:self.offset]
        value: object = int(lexeme) if kind is TokenKind.INTEGER else lexeme
        return self._token(kind, start, lexeme, value)

    def _identifier(self) -> Token:
        start = self._position()
        while self._peek().isalnum() or self._peek() == "_": self._advance()
        lexeme = self.text[start.offset:self.offset]
        return self._token(TokenKind.IDENTIFIER, start, lexeme, lexeme)

    def _skip_comment(self) -> None:
        while not self._at_end() and self._peek() != "\n": self._advance()

    def _peek(self, distance: int = 0) -> str:
        index = self.offset + distance
        return "\0" if index >= len(self.text) else self.text[index]

    def _advance(self) -> str:
        char = self.text[self.offset]
        self.offset += 1
        if char == "\n":
            self.line += 1; self.column = 1
        else:
            self.column += 1
        return char

    def _at_end(self) -> bool:
        return self.offset >= len(self.text)

    def _position(self) -> SourcePosition:
        return SourcePosition(self.offset, self.line, self.column)

    def _token(self, kind: TokenKind, start: SourcePosition, lexeme: str, value: object | None = None) -> Token:
        return Token(kind, lexeme, value, SourceSpan(self.source, start, self._position()))

    def _error(self, message: str, start: SourcePosition, token: str) -> None:
        raise CvesSyntaxError(Diagnostic(message, SourceSpan(self.source, start, self._position()), token))
