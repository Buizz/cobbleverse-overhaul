"""Canonical, deterministic CVES formatter."""

from __future__ import annotations

import json

from . import ast
from .parser import PRECEDENCE


INDENT = "  "


class Formatter:
    def format(self, program: ast.Program) -> str:
        chunks = [self._event(event) for event in program.events]
        return "\n\n".join(chunks) + "\n"

    def _event(self, event: ast.Event) -> str:
        trigger = event.trigger.name
        if event.trigger.arguments:
            trigger += "(" + ", ".join(self._argument(value, colon=True) for value in event.trigger.arguments) + ")"
        lines = [f"event {trigger} {{"]
        for index, page in enumerate(event.pages):
            if index: lines.append("")
            lines.extend(self._page(page, 1))
        lines.append("}")
        return "\n".join(lines)

    def _page(self, page: ast.Page, depth: int) -> list[str]:
        header = "page default" if page.condition is None else f"page when {self._expression(page.condition)}"
        return self._block(header, page.block, depth)

    def _block(self, header: str, block: ast.Block, depth: int) -> list[str]:
        prefix = INDENT * depth
        lines = [f"{prefix}{header} {{"]
        for statement in block.statements:
            lines.extend(self._statement(statement, depth + 1))
        lines.append(f"{prefix}}}")
        return lines

    def _statement(self, statement: ast.Statement, depth: int) -> list[str]:
        lines = self._statement_body(statement, depth)
        if statement.stable_id is not None:
            prefix = INDENT * depth
            lines[0] = f"{prefix}id {self._quote(statement.stable_id)} " + lines[0][len(prefix):]
        return lines

    def _statement_body(self, statement: ast.Statement, depth: int) -> list[str]:
        prefix = INDENT * depth
        if isinstance(statement, ast.SayStatement):
            return self._text_statement(f"say {statement.speaker}", statement.text, depth)
        if isinstance(statement, ast.NarrateStatement):
            return self._text_statement("narrate", statement.text, depth)
        if isinstance(statement, ast.LetStatement):
            return [f"{prefix}let {statement.name} = {self._expression(statement.value)}"]
        if isinstance(statement, ast.IfStatement):
            lines = self._block(f"if {self._expression(statement.condition)}", statement.then_block, depth)
            if statement.else_block is not None:
                lines[-1] += " else {"
                for child in statement.else_block.statements:
                    lines.extend(self._statement(child, depth + 1))
                lines.append(f"{prefix}}}")
            return lines
        if isinstance(statement, ast.ChoiceStatement):
            prompt = self._inline_text(statement.prompt)
            lines = [f"{prefix}choice {prompt} {{"]
            for option in statement.options:
                option_header = self._inline_text(option.text)
                lines.extend(self._block(option_header, option.block, depth + 1))
            suffix = ""
            if statement.result is not None: suffix = f" -> {statement.result}"
            lines.append(f"{prefix}}}{suffix}")
            return lines
        if isinstance(statement, ast.RepeatStatement):
            return self._block(f"repeat {self._expression(statement.count)}", statement.block, depth)
        if isinstance(statement, ast.CommandStatement):
            start = "await " if statement.awaited else ""
            start += statement.kind.value
            if statement.arguments:
                start += " " + " ".join(self._argument(value, colon=False) for value in statement.arguments)
            if statement.properties:
                lines = [f"{prefix}{start} {{"]
                for prop in statement.properties:
                    lines.append(f"{prefix}{INDENT}{prop.name}: {self._expression(prop.value)}")
                suffix = f" -> {statement.result}" if statement.result else ""
                lines.append(f"{prefix}}}{suffix}")
                return lines
            if statement.result is not None: start += f" -> {statement.result}"
            return [prefix + start]
        raise TypeError(f"지원하지 않는 AST 문장: {type(statement).__name__}")

    def _text_statement(self, header: str, text: ast.Text, depth: int) -> list[str]:
        prefix = INDENT * depth
        if isinstance(text, ast.TextLiteral):
            return [f"{prefix}{header} {self._quote(text.value)}"]
        lines = [f"{prefix}{header} {{"]
        for entry in text.entries:
            lines.append(f"{prefix}{INDENT}{entry.language}: {self._quote(entry.value)}")
        lines.append(f"{prefix}}}")
        return lines

    def _inline_text(self, text: ast.Text) -> str:
        if isinstance(text, ast.TextLiteral): return self._quote(text.value)
        # Choice prompts/options use one-line blocks to keep their following command block unambiguous.
        return "{ " + ", ".join(
            f"{entry.language}: {self._quote(entry.value)}" for entry in text.entries
        ) + " }"

    def _argument(self, argument: ast.Argument, colon: bool) -> str:
        if argument.name is None:
            if argument.value is None: raise ValueError("이름과 값이 모두 없는 인자는 포맷할 수 없습니다.")
            return self._expression(argument.value)
        if argument.value is None: return argument.name
        separator = ": " if colon else " "
        return f"{argument.name}{separator}{self._expression(argument.value)}"

    def _expression(self, expression: ast.Expression, parent_precedence: int = 0) -> str:
        if isinstance(expression, ast.LiteralExpression):
            if expression.value_type is ast.ValueType.STRING: return self._quote(str(expression.value))
            if expression.value_type is ast.ValueType.BOOL: return "true" if expression.value else "false"
            return str(expression.value)
        if isinstance(expression, ast.NameExpression): return expression.name
        if isinstance(expression, ast.MemberExpression): return f"{self._expression(expression.target, 8)}.{expression.member}"
        if isinstance(expression, ast.CallExpression):
            args = ", ".join(self._argument(value, colon=True) for value in expression.arguments)
            return f"{self._expression(expression.callee, 8)}({args})"
        if isinstance(expression, ast.UnaryExpression):
            value = f"{expression.operator}{self._expression(expression.operand, 7)}"
            return f"({value})" if parent_precedence > 7 else value
        if isinstance(expression, ast.BinaryExpression):
            precedence = PRECEDENCE[expression.operator]
            value = f"{self._expression(expression.left, precedence)} {expression.operator} {self._expression(expression.right, precedence + 1)}"
            return f"({value})" if precedence < parent_precedence else value
        raise TypeError(f"지원하지 않는 AST 식: {type(expression).__name__}")

    @staticmethod
    def _quote(value: str) -> str:
        return json.dumps(value, ensure_ascii=False)


def format_program(program: ast.Program) -> str:
    return Formatter().format(program)


def format_expression(expression: ast.Expression) -> str:
    return Formatter()._expression(expression)
