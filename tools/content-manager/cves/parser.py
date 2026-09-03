"""Recursive-descent CVES parser with Pratt expression parsing."""

from __future__ import annotations

from dataclasses import replace

from . import ast
from .diagnostics import CvesSyntaxError, Diagnostic, SourceSpan
from .lexer import Lexer, Token, TokenKind


COMMAND_MODIFIERS: dict[ast.CommandKind, tuple[frozenset[str], frozenset[str]]] = {
    ast.CommandKind.GIVE_ITEM: (frozenset({"count"}), frozenset({"notify"})),
    ast.CommandKind.GIVE_LOOT: (frozenset({"count"}), frozenset({"notify"})),
    ast.CommandKind.GIVE_MONEY: (frozenset(), frozenset({"notify"})),
    ast.CommandKind.TAKE_MONEY: (frozenset(), frozenset({"allow_debt"})),
    ast.CommandKind.HEAL_PARTY: (frozenset(), frozenset({"fallback"})),
    ast.CommandKind.MOVE: (frozenset(), frozenset()),
    ast.CommandKind.TELEPORT: (frozenset(), frozenset()),
    ast.CommandKind.ENTER_SPACE: (frozenset(), frozenset()),
}

AWAIT_COMMANDS = {
    ast.CommandKind.BATTLE, ast.CommandKind.STARTER_ROULETTE,
    ast.CommandKind.MAP_SELECTION, ast.CommandKind.MOVE,
    ast.CommandKind.TELEPORT, ast.CommandKind.ENTER_SPACE,
    ast.CommandKind.HEAL_PARTY, ast.CommandKind.NUMBER_INPUT,
}

PRECEDENCE = {"||": 1, "&&": 2, "==": 3, "!=": 3, "<": 4, "<=": 4, ">": 4, ">=": 4,
              "+": 5, "-": 5, "*": 6, "/": 6, "%": 6}


class Parser:
    def __init__(self, tokens: tuple[Token, ...]) -> None:
        self.tokens = tokens
        self.index = 0

    @classmethod
    def from_text(cls, text: str, source: str = "<memory>") -> "Parser":
        return cls(Lexer(text, source).tokenize())

    def parse(self) -> ast.Program:
        self._skip_newlines()
        start = self._current().span
        events: list[ast.Event] = []
        while not self._check(TokenKind.EOF):
            events.append(self._parse_event())
            self._skip_newlines()
        if not events:
            self._error(self._current(), "event 선언이 하나 이상 필요합니다.")
        return ast.Program(tuple(events), SourceSpan.covering(start, self._current().span))

    def parse_expression(self) -> ast.Expression:
        """Parse one standalone expression for GUI fields and tooling."""
        self._skip_newlines()
        expression = self._parse_expression()
        self._skip_newlines()
        if not self._check(TokenKind.EOF):
            self._error(self._current(), "식 뒤에 예상하지 못한 토큰이 있습니다.")
        return expression

    def _parse_event(self) -> ast.Event:
        start = self._expect_word("event")
        name = self._expect(TokenKind.IDENTIFIER, "event 뒤에 트리거 이름이 필요합니다.")
        arguments = self._parse_parenthesized_arguments() if self._check(TokenKind.LEFT_PAREN) else ()
        trigger = ast.Trigger(name.lexeme, arguments, SourceSpan.covering(name.span, (arguments[-1].span if arguments else name.span)))
        self._expect(TokenKind.LEFT_BRACE, "event 본문을 여는 '{'가 필요합니다.")
        self._skip_newlines()
        pages: list[ast.Page] = []
        saw_default = False
        while not self._check(TokenKind.RIGHT_BRACE):
            page = self._parse_page()
            if saw_default:
                self._error_token_span(page.span, "default page 뒤에는 다른 page를 둘 수 없습니다.", "page")
            saw_default = page.condition is None
            pages.append(page)
            self._skip_newlines()
        end = self._advance()
        if not pages:
            self._error(end, "event에는 page가 하나 이상 필요합니다.")
        return ast.Event(trigger, tuple(pages), SourceSpan.covering(start.span, end.span))

    def _parse_page(self) -> ast.Page:
        start = self._expect_word("page")
        if self._match_word("default"):
            condition = None
        else:
            self._expect_word("when")
            condition = self._parse_expression()
        block = self._parse_block()
        return ast.Page(condition, block, SourceSpan.covering(start.span, block.span))

    def _parse_block(self) -> ast.Block:
        start = self._expect(TokenKind.LEFT_BRACE, "블록을 여는 '{'가 필요합니다.")
        self._skip_newlines()
        statements: list[ast.Statement] = []
        while not self._check(TokenKind.RIGHT_BRACE):
            if self._check(TokenKind.EOF):
                self._error(self._current(), "블록을 닫는 '}'가 필요합니다.")
            statements.append(self._parse_statement())
            if not self._check(TokenKind.RIGHT_BRACE):
                self._expect(TokenKind.NEWLINE, "명령 뒤에는 줄바꿈 또는 '}'가 필요합니다.")
                self._skip_newlines()
        end = self._advance()
        return ast.Block(tuple(statements), SourceSpan.covering(start.span, end.span))

    def _parse_statement(self) -> ast.Statement:
        if self._check_word("id"):
            start = self._advance()
            stable_id = self._expect(TokenKind.STRING, "id 뒤에 안정 ID 문자열이 필요합니다.")
            if self._check(TokenKind.NEWLINE) or self._check(TokenKind.RIGHT_BRACE):
                self._error(self._current(), "안정 ID 뒤에 같은 줄의 명령이 필요합니다.")
            statement = self._parse_statement_body()
            return replace(
                statement,
                span=SourceSpan.covering(start.span, statement.span),
                stable_id=str(stable_id.value),
            )
        return self._parse_statement_body()

    def _parse_statement_body(self) -> ast.Statement:
        if self._check_word("say"):
            return self._parse_say()
        if self._check_word("narrate"):
            return self._parse_narrate()
        if self._check_word("let"):
            return self._parse_let()
        if self._check_word("if"):
            return self._parse_if()
        if self._check_word("choice"):
            return self._parse_choice()
        if self._check_word("repeat"):
            return self._parse_repeat()
        return self._parse_command()

    def _parse_say(self) -> ast.SayStatement:
        start = self._expect_word("say")
        speaker = self._expect(TokenKind.IDENTIFIER, "say 뒤에 화자가 필요합니다.")
        text = self._parse_text()
        return ast.SayStatement(speaker.lexeme, text, SourceSpan.covering(start.span, text.span))

    def _parse_narrate(self) -> ast.NarrateStatement:
        start = self._expect_word("narrate")
        text = self._parse_text()
        return ast.NarrateStatement(text, SourceSpan.covering(start.span, text.span))

    def _parse_text(self) -> ast.Text:
        if self._match(TokenKind.STRING):
            token = self._previous()
            return ast.TextLiteral(str(token.value), token.span)
        start = self._expect(TokenKind.LEFT_BRACE, "대사는 문자열 또는 언어별 텍스트 블록이어야 합니다.")
        self._skip_newlines()
        entries: list[ast.LocalizedTextEntry] = []
        languages: set[str] = set()
        while not self._check(TokenKind.RIGHT_BRACE):
            language = self._expect(TokenKind.IDENTIFIER, "언어 코드가 필요합니다.")
            self._expect(TokenKind.COLON, "언어 코드 뒤에 ':'가 필요합니다.")
            value = self._expect(TokenKind.STRING, "현지화 대사는 문자열이어야 합니다.")
            if language.lexeme in languages:
                self._error(language, f"중복 언어 코드 {language.lexeme!r}입니다.")
            languages.add(language.lexeme)
            entries.append(ast.LocalizedTextEntry(
                language.lexeme,
                str(value.value),
                SourceSpan.covering(language.span, value.span),
            ))
            if not self._check(TokenKind.RIGHT_BRACE):
                if not self._match(TokenKind.COMMA):
                    self._expect(TokenKind.NEWLINE, "현지화 대사 뒤에는 줄바꿈 또는 ','가 필요합니다.")
                self._skip_newlines()
        end = self._advance()
        if not entries:
            self._error(end, "현지화 대사 블록은 비어 있을 수 없습니다.")
        return ast.LocalizedText(tuple(entries), SourceSpan.covering(start.span, end.span))

    def _parse_let(self) -> ast.LetStatement:
        start = self._expect_word("let")
        name = self._expect(TokenKind.IDENTIFIER, "let 뒤에 변수 이름이 필요합니다.")
        self._expect_operator("=", "변수 이름 뒤에 '='가 필요합니다.")
        value = self._parse_expression()
        return ast.LetStatement(name.lexeme, value, SourceSpan.covering(start.span, value.span))

    def _parse_if(self) -> ast.IfStatement:
        start = self._expect_word("if")
        condition = self._parse_expression()
        then_block = self._parse_block()
        saved = self.index
        self._skip_newlines()
        else_block = self._parse_block() if self._match_word("else") else None
        if else_block is None:
            self.index = saved
        end_span = else_block.span if else_block else then_block.span
        return ast.IfStatement(condition, then_block, else_block, SourceSpan.covering(start.span, end_span))

    def _parse_choice(self) -> ast.ChoiceStatement:
        start = self._expect_word("choice")
        prompt = self._parse_text()
        self._expect(TokenKind.LEFT_BRACE, "choice 선택지 블록을 여는 '{'가 필요합니다.")
        self._skip_newlines()
        options: list[ast.ChoiceOption] = []
        while not self._check(TokenKind.RIGHT_BRACE):
            option_text = self._parse_text()
            block = self._parse_block()
            options.append(ast.ChoiceOption(option_text, block, SourceSpan.covering(option_text.span, block.span)))
            self._skip_newlines()
        end = self._advance()
        if not options:
            self._error(end, "choice에는 선택지가 하나 이상 필요합니다.")
        result = self._parse_result()
        return ast.ChoiceStatement(prompt, tuple(options), result, SourceSpan.covering(start.span, end.span))

    def _parse_repeat(self) -> ast.RepeatStatement:
        start = self._expect_word("repeat")
        count = self._parse_expression()
        block = self._parse_block()
        return ast.RepeatStatement(count, block, SourceSpan.covering(start.span, block.span))

    def _parse_command(self) -> ast.CommandStatement:
        start = self._current()
        awaited = self._match_word("await")
        name = self._expect(TokenKind.IDENTIFIER, "명령 이름이 필요합니다.")
        try:
            kind = ast.CommandKind(name.lexeme)
        except ValueError:
            self._error(name, f"지원하지 않는 명령 {name.lexeme!r}입니다.")
        if kind in AWAIT_COMMANDS and not awaited:
            self._error(name, f"비동기 명령 {name.lexeme!r} 앞에는 await가 필요합니다.")
        if awaited and kind not in AWAIT_COMMANDS:
            self._error(name, f"명령 {name.lexeme!r}에는 await를 사용할 수 없습니다.")
        named, flags = COMMAND_MODIFIERS.get(kind, (frozenset(), frozenset()))
        arguments: list[ast.Argument] = []
        while not self._at_statement_suffix():
            if self._check(TokenKind.IDENTIFIER) and self._current().lexeme in named | flags:
                modifier = self._advance()
                value = None if modifier.lexeme in flags else self._parse_expression()
                span = modifier.span if value is None else SourceSpan.covering(modifier.span, value.span)
                arguments.append(ast.Argument(value, modifier.lexeme, span))
            else:
                value = self._parse_expression()
                arguments.append(ast.Argument(value, None, value.span))
        properties = self._parse_properties() if self._check(TokenKind.LEFT_BRACE) else ()
        result = self._parse_result()
        end_span = self._previous().span if self.index else name.span
        return ast.CommandStatement(kind, tuple(arguments), properties, awaited, result, SourceSpan.covering(start.span, end_span))

    def _parse_properties(self) -> tuple[ast.Property, ...]:
        self._expect(TokenKind.LEFT_BRACE, "속성 블록을 여는 '{'가 필요합니다.")
        self._skip_newlines()
        properties: list[ast.Property] = []
        names: set[str] = set()
        while not self._check(TokenKind.RIGHT_BRACE):
            name = self._expect(TokenKind.IDENTIFIER, "속성 이름이 필요합니다.")
            self._expect(TokenKind.COLON, "속성 이름 뒤에 ':'가 필요합니다.")
            value = self._parse_expression()
            if name.lexeme in names:
                self._error(name, f"중복 속성 {name.lexeme!r}입니다.")
            names.add(name.lexeme)
            properties.append(ast.Property(name.lexeme, value, SourceSpan.covering(name.span, value.span)))
            if not self._check(TokenKind.RIGHT_BRACE):
                self._expect(TokenKind.NEWLINE, "속성 뒤에는 줄바꿈이 필요합니다.")
                self._skip_newlines()
        self._advance()
        return tuple(properties)

    def _parse_result(self) -> str | None:
        if not self._match(TokenKind.ARROW):
            return None
        return self._expect(TokenKind.IDENTIFIER, "'->' 뒤에 결과 변수 이름이 필요합니다.").lexeme

    def _parse_expression(self, minimum_precedence: int = 0) -> ast.Expression:
        left = self._parse_prefix()
        while self._check(TokenKind.OPERATOR):
            operator = self._current().lexeme
            precedence = PRECEDENCE.get(operator)
            if precedence is None or precedence < minimum_precedence:
                break
            self._advance()
            right = self._parse_expression(precedence + 1)
            left = ast.BinaryExpression(left, operator, right, SourceSpan.covering(left.span, right.span))
        return left

    def _parse_prefix(self) -> ast.Expression:
        if self._check(TokenKind.OPERATOR) and self._current().lexeme in {"!", "-"}:
            operator = self._advance()
            operand = self._parse_expression(7)
            return ast.UnaryExpression(operator.lexeme, operand, SourceSpan.covering(operator.span, operand.span))
        if self._match(TokenKind.INTEGER):
            token = self._previous(); expression: ast.Expression = ast.LiteralExpression(int(token.value), ast.ValueType.INT, token.span)
        elif self._match(TokenKind.DECIMAL):
            token = self._previous(); expression = ast.LiteralExpression(str(token.value), ast.ValueType.DECIMAL, token.span)
        elif self._match(TokenKind.STRING):
            token = self._previous(); expression = ast.LiteralExpression(str(token.value), ast.ValueType.STRING, token.span)
        elif self._match_word("true") or self._match_word("false"):
            token = self._previous(); expression = ast.LiteralExpression(token.lexeme == "true", ast.ValueType.BOOL, token.span)
        elif self._match(TokenKind.IDENTIFIER):
            token = self._previous(); expression = ast.NameExpression(token.lexeme, token.span)
        elif self._match(TokenKind.LEFT_PAREN):
            expression = self._parse_expression()
            self._expect(TokenKind.RIGHT_PAREN, "식을 닫는 ')'가 필요합니다.")
        else:
            self._error(self._current(), "식이 필요합니다.")
        while True:
            if self._match(TokenKind.DOT):
                member = self._expect(TokenKind.IDENTIFIER, "'.' 뒤에 필드 이름이 필요합니다.")
                expression = ast.MemberExpression(expression, member.lexeme, SourceSpan.covering(expression.span, member.span))
            elif self._check(TokenKind.LEFT_PAREN):
                arguments = self._parse_parenthesized_arguments()
                end = self._previous().span
                expression = ast.CallExpression(expression, arguments, SourceSpan.covering(expression.span, end))
            else:
                break
        return expression

    def _parse_parenthesized_arguments(self) -> tuple[ast.Argument, ...]:
        self._expect(TokenKind.LEFT_PAREN, "'('가 필요합니다.")
        arguments: list[ast.Argument] = []
        if not self._check(TokenKind.RIGHT_PAREN):
            while True:
                if self._check(TokenKind.IDENTIFIER) and self._peek_kind(1) is TokenKind.COLON:
                    name = self._advance(); self._advance()
                    value = self._parse_expression()
                    arguments.append(ast.Argument(value, name.lexeme, SourceSpan.covering(name.span, value.span)))
                else:
                    value = self._parse_expression()
                    arguments.append(ast.Argument(value, None, value.span))
                if not self._match(TokenKind.COMMA): break
        self._expect(TokenKind.RIGHT_PAREN, "인자 목록을 닫는 ')'가 필요합니다.")
        return tuple(arguments)

    def _at_statement_suffix(self) -> bool:
        return self._check(TokenKind.NEWLINE) or self._check(TokenKind.RIGHT_BRACE) or self._check(TokenKind.EOF) or self._check(TokenKind.ARROW) or self._check(TokenKind.LEFT_BRACE)

    def _skip_newlines(self) -> None:
        while self._match(TokenKind.NEWLINE): pass

    def _expect_word(self, word: str) -> Token:
        if not self._check_word(word): self._error(self._current(), f"{word!r}가 필요합니다.")
        return self._advance()

    def _expect_operator(self, operator: str, message: str) -> Token:
        if not self._check(TokenKind.OPERATOR) or self._current().lexeme != operator: self._error(self._current(), message)
        return self._advance()

    def _expect(self, kind: TokenKind, message: str) -> Token:
        if not self._check(kind): self._error(self._current(), message)
        return self._advance()

    def _match_word(self, word: str) -> bool:
        if self._check_word(word): self._advance(); return True
        return False

    def _check_word(self, word: str) -> bool:
        return self._check(TokenKind.IDENTIFIER) and self._current().lexeme == word

    def _match(self, kind: TokenKind) -> bool:
        if self._check(kind): self._advance(); return True
        return False

    def _check(self, kind: TokenKind) -> bool:
        return self._current().kind is kind

    def _peek_kind(self, distance: int) -> TokenKind:
        return self.tokens[min(self.index + distance, len(self.tokens) - 1)].kind

    def _advance(self) -> Token:
        token = self._current()
        if token.kind is not TokenKind.EOF: self.index += 1
        return token

    def _current(self) -> Token:
        return self.tokens[self.index]

    def _previous(self) -> Token:
        return self.tokens[max(0, self.index - 1)]

    def _error(self, token: Token, message: str) -> None:
        raise CvesSyntaxError(Diagnostic(message, token.span, token.lexeme or "<EOF>"))

    def _error_token_span(self, span: SourceSpan, message: str, token: str) -> None:
        raise CvesSyntaxError(Diagnostic(message, span, token))


def parse(text: str, source: str = "<memory>") -> ast.Program:
    return Parser.from_text(text, source).parse()


def parse_expression(text: str, source: str = "<expression>") -> ast.Expression:
    return Parser.from_text(text, source).parse_expression()
